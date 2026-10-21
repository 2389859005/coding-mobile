"""Scoped file, image, creation, permissions and task-status APIs for Android."""
import base64
import hashlib
import json
import mimetypes
import re
import threading
import time
import uuid
from pathlib import Path
from contextlib import closing

from claude_bridge.packaged import APIError
from .context_usage import normalize
from .native_content import permission_selection, permission_settings, referenced_paths

MAX_IMAGE = 3 * 1024 * 1024
MAX_FILE = 12 * 1024 * 1024


class NativeFeatures:
    # Transcript caches are cleared in batches: dropping a single oldest entry per
    # insert only pushed the eviction into the next read of the same transcript.
    CACHE_BATCH = 200
    REVISION_LIMIT = 1000
    REPLY_LIMIT = 2000

    def __init__(self, server, directory):
        self.server = server
        self.directory = Path(directory) / 'native-uploads'
        self.directory.mkdir(parents=True, exist_ok=True)
        self.status_lock = threading.Lock()
        self.status_cache = (0, {})
        self.context_cache = {}
        self.detail_cache = {}
        self.message_revisions = {}
        from .git_project import GitProjects
        self.git_projects = GitProjects(directory)

    def git(self, backend, sid, query, body=None):
        if backend=='codex':row=self.server.bridge.store.get(sid)
        elif backend=='claude':row=self.server.claude.index.get(sid) or self.server.claude.run(self.server.claude.session(sid))
        else:row=self.server.deepseek.call('detail',sid)['session']
        cwd=row.get('cwd')
        if not cwd:raise ValueError('当前会话没有本地项目目录')
        root=self.git_projects.root(cwd)
        if body is not None:return self.git_projects.mutate(root,body)
        if query.get('path'):return self.git_projects.diff(root,query['path'][0],query.get('staged',['false'])[0]=='true')
        return self.git_projects.snapshot(root)

    def context_usage(self, backend, sid):
        try:
            if backend == 'codex':
                live=self.server.bridge.session(sid, attach=False)
                with live.condition: value=(live.state or {}).get('latestTokenUsageInfo')
                if value is not None: return normalize(value)
                store=self.server.bridge.store
                path=store._rollout_path(store.get(sid))
                with path.open('rb') as stream:
                    stream.seek(0,2);stream.seek(max(0,stream.tell()-262144));lines=stream.read().split(b'\n')
                for line in reversed(lines):
                    try:
                        if b'"token_count"' not in line: continue
                        event=json.loads(line);payload=event.get('payload') or {}
                        if event.get('type')=='event_msg' and payload.get('type')=='token_count':return normalize(payload.get('info'))
                    except (ValueError,TypeError):pass
            elif backend == 'claude':
                cache=self.context_cache.get(sid)
                if cache and time.monotonic()-cache[0]<10:return cache[1]
                controller=self.server.claude;row=controller.index.get(sid)
                value=None
                if row and 'getContextUsageSummary' in controller.desktop.capabilities.get(row['backend'],[]):
                    value=controller.run(controller.desktop.call(row['backend'],'getContextUsageSummary',row['nativeId'],timeout=4))
                result=normalize(value,'claude');self.context_cache[sid]=(time.monotonic(),result)
                if len(self.context_cache)>100:self.context_cache.pop(next(iter(self.context_cache)))
                return result
        except Exception:pass
        return {'available':False}

    def detail(self, backend, sid):
        # Opening a conversation and the poll right after it, or the overlay's own
        # poll, must not rebuild the same transcript twice.
        key = (backend, sid)
        cached = self.detail_cache.get(key)
        now = time.monotonic()
        if cached and now-cached[0] < 2: return cached[1]
        value = self.server.dispatch(backend, sid, None, {})
        self.detail_cache[key] = (now, value)
        if len(self.detail_cache) > 40: self.detail_cache.pop(next(iter(self.detail_cache)))
        return value

    def catalog(self, backend, sid, section="all"):
        if backend == 'deepseek': return self.server.deepseek.call('catalog',sid,{'section':section})
        if backend == 'claude': return self.server.claude.run(self.server.claude.catalog(sid,section))
        catalog = self.server.bridge.catalog(sid, refresh=False)
        settings = self.codex_settings(sid)
        if section != 'models' and hasattr(self.server.bridge, 'plugin_inventory'):
            from .codex_plugins import grouped_catalog
            inventory = self.server.bridge.plugin_inventory(sid, refresh=True)
            catalog = {**catalog, 'skills': grouped_catalog(catalog, inventory),
                       'modes': [{'id': m['mode'], 'name': '计划模式' if m['mode']=='plan' else '普通模式'}
                                 for m in inventory.get('modes', []) if m.get('mode') in ('plan','default')]}
            try:
                from .codex_rpc import CodexRPC
                with CodexRPC(self.server.bridge.store.home) as rpc:
                    catalog['goal'] = rpc.request('thread/goal/get', {'threadId':sid}).get('goal')
                catalog['modes'].append({'id':'goal','name':'目标模式'})
            except ValueError as exc: catalog['goalError'] = str(exc)
        return {**catalog, 'currentModel': settings.get('model') or catalog.get('currentModel'),
                'currentEffort': settings.get('effort') or catalog.get('currentEffort'),
                'currentMode': (settings.get('collaborationMode') or {}).get('mode','default')}

    def codex_settings(self, sid):
        live = self.server.bridge.session(sid, attach=False)
        with live.condition:
            state = live.state or {}
            return {**{k:v for k,v in {'model':state.get('latestModel'), 'effort':state.get('latestReasoningEffort'),
                    'collaborationMode':state.get('latestCollaborationMode')}.items() if v is not None},
                    **(state.get('currentPermissions') or {}), **(state.get('latestThreadSettings') or {})}

    def settings(self, backend, sid, body):
        catalog = self.catalog(backend, sid, 'models')
        model = body.get('model') or catalog.get('currentModel')
        selected = next((m for m in catalog.get('models', []) if m['id'] == model), None)
        if not selected: raise ValueError('请选择桌面提供的模型')
        effort = body.get('effort') or (catalog.get('currentEffort') if model == catalog.get('currentModel') else None) or selected.get('defaultEffort')
        supported = [e.get('id', e.get('reasoningEffort')) if isinstance(e, dict) else e for e in selected.get('efforts', [])]
        if effort and effort not in supported: raise ValueError('该模型不支持所选思考强度')
        settings = {'model': model}
        if effort: settings['effort'] = effort
        if 'collaborationMode' in body:
            mode = body['collaborationMode']
            if mode not in ('default','plan'): raise ValueError('不支持的会话模式')
            settings['collaborationMode'] = {'mode':mode, 'settings':{'model':model, 'reasoning_effort':effort, 'developer_instructions':None}}
        target = self.server.bridge._target(sid)
        with target.action_lock:
            result = self.server.bridge._call(target, 'thread-follower-update-thread-settings', {'threadSettings': settings})
        if not isinstance(result, dict) or not (result.get('ok') or result.get('applied')):
            raise ValueError('桌面未接受模型设置')
        return {'status': 'accepted', **settings}

    def goal(self, sid, body):
        from .codex_rpc import CodexRPC
        self.server.bridge.store.get(sid)
        operation = body.get('operation','set')
        params = {'threadId':sid}
        if operation == 'set':
            objective = body.get('objective','').strip()
            if not objective or len(objective)>20000: raise ValueError('目标内容为 1–20000 字')
            params.update(objective=objective,status='active')
            if body.get('tokenBudget') is not None:
                budget=body['tokenBudget']
                if type(budget) is not int or budget<=0: raise ValueError('Token 预算必须为正整数')
                params['tokenBudget']=budget
        elif operation in ('pause','resume'): params['status']='paused' if operation=='pause' else 'active'
        elif operation != 'clear': raise ValueError('不支持的目标操作')
        target = self.server.bridge._target(sid)
        with target.action_lock:
            with CodexRPC(self.server.bridge.store.home) as rpc:
                previous=rpc.request('thread/goal/get',{'threadId':sid}).get('goal')
                if operation=='set' and previous and previous.get('status')!='complete':
                    raise ValueError('当前目标尚未完成，请先暂停或清除已有目标')
                if operation in ('pause','resume') and not previous: raise ValueError('当前会话没有目标')
                if operation in ('set','resume'):
                    # Goal continuations use the desktop owner's normal execution mode.
                    current=self.codex_settings(sid);model=current.get('model')
                    if not model: raise ValueError('请先选择模型')
                    result=self.server.bridge._call(target,'thread-follower-update-thread-settings',{'threadSettings':{'collaborationMode':{'mode':'default','settings':{'model':model,'reasoning_effort':current.get('effort'),'developer_instructions':None}}}})
                    if not isinstance(result,dict) or not(result.get('ok') or result.get('applied')):raise ValueError('桌面未接受目标模式设置')
                result=rpc.request('thread/goal/clear' if operation=='clear' else 'thread/goal/set',params)
        return {'status':'accepted',**result}

    def projects(self, backend):
        if backend == 'deepseek': return self.server.deepseek.call('projects')
        if backend == 'codex':
            return {'projects': [p for p in self.server.bridge.projects() if p.get('host', 'local') == 'local']}
        rows, errors = self.server.claude.run(self.server.claude.listing())
        projects = {}
        bridge = self.bridge_workspace()
        for row in rows:
            # Claude is grouped by the project it started from, not by the directory
            # the session currently works in; those differ after a worktree change.
            project = row.get('projectCwd') or row.get('cwd')
            if not project: continue
            key = row['backend']+'|'+project
            internal = bool(bridge) and self.same_path(project, bridge)
            projects[key] = {'key': key, 'name': Path(project).name+' · '+row['backend'].title()+('（助手内部目录）' if internal else ''),
                             'cwd': project, 'surface': row['backend'], 'hostLabel': '此电脑', 'internal': internal}
        # The bridge's own workspace is a real session, so it stays selectable, but
        # it must never be the project a new conversation defaults to.
        return {'projects': sorted(projects.values(), key=lambda p: p['internal']), 'errors': errors}

    def create(self, backend, body):
        rid = str(uuid.UUID(body.get('id', '')))
        projects = self.projects(backend)['projects']
        project = next((p for p in projects if p['key'] == body.get('projectKey')), None)
        requested = body.get('cwd')
        if project is None and isinstance(requested, str) and requested:
            # A stale key must not block creating in the directory the user picked.
            project = next((p for p in projects if p['cwd'] == requested), None)
        title = body.get('title', '').strip() or '新会话'
        if not project or len(title) > 120: raise ValueError('请选择项目，并使用不超过 120 字的名称')
        if backend == 'deepseek':
            fingerprint=hashlib.sha256(json.dumps(body,sort_keys=True).encode()).hexdigest()
            with self.server.write_lock:
                old=self.server.deepseek.receipt(rid)
                if old['status']!='missing':
                    if old.get('fingerprint')!=fingerprint:raise ValueError('同一创建标识不能用于不同内容')
                    return old
                unknown={'status':'unknown','fingerprint':fingerprint}
                self.server.deepseek.receipt(rid,unknown)
                try:result={**self.server.deepseek.call('create',body=body),'fingerprint':fingerprint}
                except Exception:return unknown
                self.server.deepseek.receipt(rid,result);return result
        if backend == 'codex':
            return self.server.bridge.start_creation(project['key'], title, rid)
        if body.get('mode') in ('code','work'):
            surface='cowork' if body['mode']=='work' else 'code'
            if 'start' not in self.server.claude.desktop.capabilities.get(surface,[]):raise ValueError('当前 Claude Desktop 未启用此模式')
            project={**project,'surface':surface,'key':surface+'|'+project['cwd']}
        return self.verified_creation(self.server.claude.run(self.server.claude.create_session(project, title, rid)), project['cwd'])

    def verified_creation(self, result, cwd):
        """Report whether the new session really opened in the chosen directory.

        Claude can silently fall back to another workspace (an untrusted folder,
        or a worktree), which looked to the phone like the directory "jumping".
        """
        if result.get('status') != 'ready' or not isinstance(result.get('result'), dict): return result
        try:
            row = self.server.claude.run(self.server.claude.session(result['result']['id'])) or {}
            actual = row.get('cwd') or ''
            project = row.get('projectCwd') or actual
        except Exception:return {**result, 'cwdVerified': None}
        if not actual:return {**result, 'cwdVerified': None}
        # A session may legitimately work in a worktree while belonging to the
        # project that was asked for, so either directory counts as a match.
        if self.same_path(actual, cwd) or self.same_path(project, cwd): return {**result, 'cwdVerified': True}
        return {**result, 'cwdVerified': False, 'actualCwd': actual}

    def creation(self, backend, rid):
        rid = str(uuid.UUID(rid))
        if backend == 'deepseek':return self.server.deepseek.receipt(rid)
        if backend == 'codex': return self.server.bridge.creation_status(rid, recover=True)
        return self.server.claude.run(self.server.claude.creation_status(rid))

    def access(self, backend, sid, body=None):
        if backend == 'deepseek':return self.server.deepseek.call('access',sid,body)
        if backend == 'claude':
            return self.server.claude.run(self.server.claude.access(sid, body))
        live = self.server.bridge.session(sid, attach=False)
        with live.condition:
            settings = {**(live.state.get('currentPermissions') or {}), **(live.state.get('latestThreadSettings') or {})}
            cwd = live.state.get('cwd') or self.server.bridge.store.get(sid).get('cwd', '')
        if body is None:
            return {'mode': permission_selection(settings), 'modes': ['read-only', 'workspace-write', 'full-access']}
        desired = permission_settings(body.get('mode'), cwd)
        target = self.server.bridge._target(sid)
        with target.action_lock:
            result = self.server.bridge._call(target, 'thread-follower-update-thread-settings', {'threadSettings': desired})
        if not isinstance(result, dict) or not (result.get('ok') or result.get('applied')):
            raise ValueError('桌面未接受权限变更，请在电脑检查当前限制')
        return {'status': 'accepted', 'mode': body['mode']}

    def upload(self, backend, sid, body):
        # Validate membership before storing an image; never accept an arbitrary disk path.
        self.detail(backend, sid)
        mime = body.get('mime', '')
        if mime not in ('image/png', 'image/jpeg', 'image/webp'): raise ValueError('请选择 PNG、JPEG 或 WebP 图片')
        encoded = body.get('data', '')
        if not isinstance(encoded, str) or len(encoded) > MAX_IMAGE * 4 // 3 + 4: raise ValueError('图片过大')
        try: data = base64.b64decode(encoded, validate=True)
        except (ValueError, TypeError): raise ValueError('图片数据无效')
        signatures = {'image/png': data.startswith(b'\x89PNG\r\n\x1a\n'), 'image/jpeg': data.startswith(b'\xff\xd8\xff'),
                      'image/webp': data.startswith(b'RIFF') and data[8:12] == b'WEBP'}
        if not data or len(data) > MAX_IMAGE or not signatures[mime]: raise ValueError('图片格式与内容不一致')
        uid = hashlib.sha256(data).hexdigest()
        folder = self.directory / backend / str(uuid.UUID(sid.removeprefix('session-'))); folder.mkdir(parents=True, exist_ok=True)
        # Atomic, content-addressed staging. Retrying an upload cannot duplicate a message.
        target = folder / (uid+'.json')
        if not target.exists():
            temp = folder / (uid+'.'+uuid.uuid4().hex+'.tmp')
            temp.write_text(json.dumps({'mime': mime, 'data': encoded}), encoding='utf-8'); temp.replace(target)
        return {'id': uid, 'name': str(body.get('name') or '图片')[:160], 'size': len(data)}

    def images(self, backend, sid, ids):
        if not isinstance(ids, list) or len(ids) > 4: raise ValueError('每条消息最多 4 张图片')
        images = []
        for uid in ids:
            if not isinstance(uid, str) or not re.fullmatch('[a-f0-9]{64}', uid): raise ValueError('图片标识无效')
            path = self.directory / backend / str(uuid.UUID(sid.removeprefix('session-'))) / (uid+'.json')
            if not path.is_file(): raise ValueError('图片已失效，请重新选择')
            value = json.loads(path.read_text('utf-8'))
            images.append({'url': 'data:'+value['mime']+';base64,'+value['data']})
        return images

    @staticmethod
    def same_path(left, right):
        try:return str(Path(left).resolve()).replace('\\','/').rstrip('/').lower() == str(Path(right).resolve()).replace('\\','/').rstrip('/').lower()
        except (OSError, ValueError):return False

    def bridge_workspace(self):
        """Directory the Claude file bridge uses for its own adapter session."""
        try:return str(self.server.claude.workspace)
        except AttributeError:return ''

    def session_row(self, backend, sid):
        """Session metadata only. Directory and file reads never pull a transcript."""
        if backend == 'codex':return self.server.bridge.store.get(sid) or {}
        if backend == 'claude':
            controller = self.server.claude
            index = getattr(controller, 'index', None)
            row = index.get(sid) if isinstance(index, dict) else None
            if row is None:
                controller.run(controller.listing())
                row = index.get(sid) if isinstance(index, dict) else None
            if row is None:row = controller.run(controller.session(sid))
            return row or {}
        return self.server.deepseek.call('detail', sid)['session']

    def progress_messages(self, task):
        """Recent transcript rows for one non-Codex session, read once per refresh."""
        backend, sid = task['backend'], task['id']
        if backend == 'claude':
            from claude_bridge import model
            controller = self.server.claude
            index = getattr(controller, 'index', None)
            row = index.get(sid) if isinstance(index, dict) else None
            if row is None:
                controller.run(controller.listing())
                row = index.get(sid) if isinstance(index, dict) else None
            if row is None:return []
            if row.get('requests'):return [{'role': 'assistant', 'text': '等待你的回答或确认'}]
            raw = controller.run(controller.desktop.call(row['backend'], 'mobileProgress', row['nativeId'], timeout=5))
            return model.transcript(raw)
        return self.server.deepseek.call('detail', sid).get('messages', [])

    def session_roots(self, row):
        """Directories to try for a session, in the order Desktop resolves them.

        A Claude session reports both its own working directory and the project it
        started from; they differ after a worktree or a fallback workspace, and the
        file APIs only answer relative to the session's own directory.
        """
        roots = []
        for value in (row.get('cwd'), row.get('projectCwd'), row.get('originCwd')):
            if not isinstance(value, str) or not value: continue
            try: path = Path(value).resolve()
            except (OSError, ValueError): continue
            if path not in roots: roots.append(path)
        return roots

    @staticmethod
    def inside(root, value):
        try:
            path = (root / value).resolve()
            return path == root or root in path.parents
        except (OSError, ValueError): return False

    def activity_token(self, backend, sid):
        """Cheap change detector for a session that is not running or waiting.

        Lets a poll answer "nothing changed" without rebuilding the transcript,
        which is what made entering a conversation wait for a refresh.
        """
        row = self.session_row(backend, sid)
        if not row: return ''
        if row.get('requests'): return ''
        status = str(row.get('status') or '')
        if status in ('active','running','working','inProgress'): return ''
        return status+':'+str(row.get('updatedAt'))

    def forget_detail(self, backend, sid):
        """Drop every short-lived read that a write to `sid` may have invalidated.

        Clearing only the detail entry left the 5s task status, the 10s context
        usage and the reply/message revisions serving pre-write values, so a poll
        arriving right after a send, an upload or a rename could answer with the
        state from before it. reply_revisions is created lazily, so it is looked up
        defensively rather than assumed to exist.
        """
        cache = getattr(self, 'detail_cache', None)
        if cache is not None: cache.pop((backend, sid), None)
        context = getattr(self, 'context_cache', None)
        if context is not None: context.pop(sid, None)
        lock = getattr(self, 'status_lock', None)
        if lock is not None:
            with lock: self.status_cache = (0, {})
        for name in ('message_revisions', 'reply_revisions'):
            table = getattr(self, name, None)
            if not isinstance(table, dict): continue
            for key in [k for k in table if k == sid or (isinstance(k, tuple) and sid in k)]:
                table.pop(key, None)

    def unchanged_answer(self, backend, sid, known):
        """The payload for a poll that changed nothing, or None for a full read."""
        if not known: return None
        token = self.activity_token(backend, sid)
        if not token or token != known: return None
        return {'unchanged':True, 'token':token, 'connected':True,
                'session':self.session_row(backend, sid),
                'queue':self.server.queue.listing(backend, sid)}

    def files(self, backend, sid, query):
        row = self.session_row(backend, sid)
        requested = query.get('path', [''])[0]
        usable = [value for value in self.session_roots(row) if value.is_dir()]
        # List the directory the session is actually in, and keep the project
        # directory as a fallback for sessions that moved elsewhere.
        root = next((base for base in usable if self.inside(base, requested)), None) or (usable[0] if usable else None)
        if root is None:
            if backend == 'claude': return self.server.claude.run(self.server.claude.files(sid, query))
            if row.get('files'):
                return {'path': '', 'parent': None, 'entries': [], 'more': False, 'artifacts': row['files']}
            raise ValueError('当前项目目录不可用')
        folder = self.contained(root, requested)
        if not folder.is_dir(): raise ValueError('请选择文件夹')
        entries = []
        for path in folder.iterdir():
            try:
                resolved = path.resolve()
                if not resolved.is_relative_to(root) or path.name in ('.git', '.coding-remote', 'node_modules'): continue
                stat = path.stat()
                entries.append({'name': path.name, 'path': path.relative_to(root).as_posix(), 'absolutePath':str(path),'directory': path.is_dir(),
                                'size': stat.st_size, 'modified': stat.st_mtime})
            except OSError: continue
        entries.sort(key=lambda e: (not e['directory'], -e['modified'], e['name'].lower()))
        offset = max(0, int(query.get('offset', [0])[0]))
        artifacts = [a for a in (row.get('files') or []) if isinstance(a, dict)]
        if query.get('references', [''])[0] == '1':
            # Opt-in only: scanning the transcript for referenced files costs a
            # full session detail, which is what made directory browsing slow.
            detail = self.detail(backend, sid)
            known = {a.get('path') for a in artifacts}
            for reference in referenced_paths(detail.get('messages', [])):
                try:
                    path = self.contained(root, reference)
                    relative = path.relative_to(root).as_posix()
                    if path.is_file() and relative not in known:
                        artifacts.append({'name': path.name, 'path': relative}); known.add(relative)
                except (ValueError, OSError): pass
        return {'root':str(root),'absolutePath':str(folder),'path': folder.relative_to(root).as_posix(), 'parent': folder.parent.relative_to(root).as_posix() if folder != root else None,
                'entries': entries[offset:offset+200], 'more': offset+200 < len(entries), 'offset': offset,
                'artifacts': artifacts}

    @staticmethod
    def contained(root, value):
        if not isinstance(value, str) or '\0' in value: raise ValueError('文件路径无效')
        path = (root / value).resolve()
        if not path.is_relative_to(root): raise ValueError('只能预览当前会话项目内的文件')
        return path

    @staticmethod
    def absolute_inside(roots, value):
        """Resolve an absolute path and require it to live under one of `roots`.

        used for transcript-referenced images, whose path is data rather than a
        user choice. Extended-length and UNC prefixes are rejected outright: they
        bypass the drive-letter assumptions `resolve()` makes on Windows.
        """
        if not isinstance(value, str) or '\0' in value: raise ValueError('文件路径无效')
        if value.startswith('\\\\'): raise ValueError('文件路径无效')
        try: path = Path(value).resolve()
        except (OSError, ValueError): raise ValueError('文件路径无效')
        for root in roots:
            try:
                resolved = Path(root).resolve()
                if path == resolved or path.is_relative_to(resolved):
                    if path.is_file(): return path
            except (OSError, ValueError): continue
        raise ValueError('只能预览当前会话项目内的文件')

    def file(self, backend, sid, query):
        artifact = query.get('artifact', [''])[0]
        requested = query.get('path', [''])[0]
        if artifact and backend == 'codex':
            if not re.fullmatch('[a-f0-9]{64}', artifact): raise ValueError('文件标识无效')
            resolved = self.server.bridge.artifact(sid, artifact)
            path = Path(resolved['path'] if isinstance(resolved, dict) else resolved)
        else:
            row = self.session_row(backend, sid)
            usable = [value for value in self.session_roots(row) if value.is_dir()]
            path = None
            # The common case is a file inside the session's own directory; trying
            # every known root first avoids rebuilding the transcript for it.
            for base in usable:
                try:
                    candidate = self.contained(base, requested)
                    if candidate.is_file(): path = candidate; break
                except (ValueError, OSError): continue
            if path is None:
                detail = self.detail(backend, sid)
                match = next((a for a in detail.get('files', []) if a.get('absolutePath', '').replace('\\', '/') == requested.replace('\\', '/')), None) if backend == 'codex' else None
                tool_images = referenced_paths([m for m in detail.get('messages', []) if m.get('kind') == 'image'])
                if backend == 'codex' and match is not None:
                    resolved = self.server.bridge.artifact(sid, match['id']); path = Path(resolved['path'] if isinstance(resolved, dict) else resolved)
                elif requested in tool_images and Path(requested).is_absolute() and Path(requested).suffix.lower() in ('.png','.jpg','.jpeg','.webp','.gif','.bmp'):
                    # Transcript references only say "this image belongs to the session";
                    # they do not authorise reading it. The path still has to resolve
                    # inside one of the session's own roots, otherwise a crafted
                    # transcript could pull any image off the disk.
                    if not usable: raise ValueError('当前项目目录不可用')
                    path = self.absolute_inside(usable, requested)
                elif not usable:
                    if backend == 'claude': return self.server.claude.run(self.server.claude.file(sid, query))
                    raise ValueError('当前项目目录不可用')
                else:
                    path = self.contained(usable[0], requested)
        if not path.is_file(): raise ValueError('文件不存在')
        if path.stat().st_size > MAX_FILE: raise ValueError('预览文件不能超过 12 MB，请在电脑打开')
        with path.open('rb') as stream: data = stream.read(MAX_FILE+1)
        if len(data) > MAX_FILE: raise ValueError('预览文件不能超过 12 MB，请在电脑打开')
        mime = mimetypes.guess_type(path.name)[0] or 'application/octet-stream'
        binary = mime.startswith('image/') or mime == 'application/pdf'
        # A second base64 copy doubles the payload of a large text preview, where
        # the phone only needs it to save or share the file.
        bulk = not binary and len(data) > 128*1024
        result = {'name': path.name, 'mime': mime, 'size': len(data)}
        if binary or not bulk or query.get('raw', [''])[0] == '1': result['data'] = base64.b64encode(data).decode()
        if not binary:
            try:
                content = data.decode('utf-8-sig')
                if '\0' not in content: result['text'] = content[:300000]; result['truncated'] = len(content) > 300000
            except UnicodeError: pass
        return result

    def agents(self, sid):
        bridge = self.server.bridge; agents = []
        # Agent children are intentionally excluded from the normal thread list.
        # Read only children of this exact parent; never turn them into main sessions.
        bridge.store.get(sid)  # Verify this is an accessible desktop parent thread.
        with closing(bridge.store._connect()) as conn:
            columns = {r[1] for r in conn.execute('PRAGMA table_info(threads)')}
            wanted = [c for c in ('id', 'title', 'agent_nickname', 'agent_role', 'source', 'rollout_path') if c in columns]
            if 'source' not in columns: return {'agents': []}
            for raw in conn.execute('SELECT '+','.join(wanted)+' FROM threads WHERE source LIKE ? LIMIT 100', ('%'+str(uuid.UUID(sid))+'%',)):
                row = dict(raw)
                try: source = json.loads(row['source']); spawn = source.get('subagent', {}).get('spawn', {})
                except (ValueError, AttributeError): continue
                if spawn.get('parent_thread_id') != sid: continue
                state = bridge.store.latest_task_event(row).get('type', '')
                agents.append({'id': row['id'], 'name': row.get('agent_nickname') or row.get('agent_role') or row.get('title') or '智能体',
                               'status': 'running' if state == 'task_started' else 'completed' if state == 'task_complete' else 'idle'})
        return {'agents': agents}

    @staticmethod
    def reply_only(text, limit=240):
        """Reduce a transcript entry to the prose the user actually wants to read.

        The floating bubble used to show whatever the last assistant entry was, which
        in practice meant shell commands, file paths and tool banners. The overlay is
        a glanceable summary, so it keeps the natural-language reply and drops the
        execution detail; Chinese is the marker for prose here because the operator
        writes and reads Chinese, while commands and paths are ASCII.
        """
        if not text:
            return ''
        kept = []
        fenced = False
        for raw in str(text).splitlines():
            line = raw.strip()
            if line.startswith('```') or line.startswith('~~~'):
                fenced = not fenced
                continue
            if fenced:
                continue
            if not line:
                if kept and kept[-1] != '':
                    kept.append('')
                continue
            # Banners, shell prompts, diff lines and bare paths are never the reply.
            if line.startswith(('[工具调用]', '[Tool', '工具调用', '调用工具', '$ ', '> ', 'diff ', '---', '+++')):
                continue
            if line.startswith(('+', '-')) and len(line) > 1 and line[1] in '+- ':
                continue
            if re.match(r'^(?:[A-Za-z]:[\\/]|/|\.\.?/|~/|\\\\)', line):
                continue
            # A line with no CJK is either code, a command or an identifier.
            if not re.search(r'[\u4e00-\u9fff]', line):
                continue
            kept.append(line)
        while kept and kept[-1] == '':
            kept.pop()
        while kept and kept[0] == '':
            kept.pop(0)
        joined = '\n'.join(kept).strip()
        return joined[-limit:] if joined else ''

    @staticmethod
    def progress_text(messages):
        for message in reversed(messages):
            if message.get('role') in ('assistant','reasoning','thought','analysis'):
                text=message.get('text','').strip()
                if not text or text.startswith('[工具调用]'):
                    continue
                # kind is set by the transcript reader and is the authoritative
                # signal; the prose filter below is the fallback for entries that
                # arrive without one.
                if message.get('kind') in ('reasoning','step','tool','activity'):
                    continue
                reply=NativeFeatures.reply_only(text)
                if reply:
                    return reply
                continue
            if message.get('role') == 'activity' and message.get('title'):
                title=NativeFeatures.reply_only(str(message['title']))
                if title:
                    return '正在执行：'+title[:200]
        return '正在思考…'

    def live_progress(self, task):
        backend,sid=task['backend'],task['id']
        if backend=='codex':
            store=self.server.bridge.store
            path=store._rollout_path(store.get(sid))
            with path.open('rb') as stream:
                stream.seek(0,2);size=stream.tell();stream.seek(max(0,size-262144));lines=stream.read().split(b'\n')
            for line in reversed(lines):
                try:
                    if b'"response_item"' not in line and b'"agent_message"' not in line:
                        # Only these two record shapes can carry the progress line;
                        # the rest of the tail is not worth parsing on every poll.
                        continue
                    item=json.loads(line);payload=item.get('payload',{})
                    if item.get('type')=='response_item' and payload.get('role')=='assistant':
                        text='\n'.join(x.get('text','') for x in payload.get('content',[]) if isinstance(x,dict)).strip()
                        reply=NativeFeatures.reply_only(text)
                        if reply:return reply
                        continue
                    if item.get('type')=='event_msg' and payload.get('type')=='agent_message':
                        reply=NativeFeatures.reply_only(str(payload.get('message','')))
                        if reply:return reply
                        continue
                except (ValueError,TypeError):pass
            return '正在思考…'
        if backend=='claude':
            from claude_bridge import model
            row=self.server.claude.index.get(sid)
            if row and row.get('requests'):return '等待你的回答或确认'
            if row:
                raw=self.server.claude.run(self.server.claude.desktop.call(row['backend'],'mobileProgress',row['nativeId'],timeout=8))
                return self.progress_text(model.transcript(raw))
            return '正在思考…'
        return self.progress_text(self.server.deepseek.call('detail',sid).get('messages',[]))

    def codex_message_revision(self, sid):
        store=self.server.bridge.store;path=store._rollout_path(store.get(sid));stat=path.stat()
        signature=(stat.st_size,stat.st_mtime_ns)
        old=self.message_revisions.get(sid)
        if old and old[0]==signature:return old[1]
        revision=old[1] if old else ''
        # The tail is read as a whole and only then split: splitlines() over the
        # byte tail built one temporary bytes object per line of the transcript.
        with path.open('rb') as stream:
            stream.seek(max(0,stat.st_size-262144));tail=stream.read().split(b'\n')
        for line in reversed(tail):
            try:
                if b'"agent_message"' not in line and b'"message"' not in line:
                    # The overlay's progress line needs the newest assistant text and
                    # nothing else. Every other record in a 256 KB tail still cost a
                    # full json.loads here, once per task and per status refresh.
                    continue
                value=json.loads(line);payload=value.get('payload') or {};kind=payload.get('type')
                message=value.get('type')=='response_item' and kind=='message' and payload.get('role')=='assistant' and payload.get('channel') not in ('analysis','commentary')
                message|=value.get('type')=='event_msg' and kind=='agent_message' and payload.get('phase') not in ('analysis','commentary')
                if message:
                    text=payload.get('message') or '\n'.join(x.get('text','') for x in payload.get('content',[]) if isinstance(x,dict))
                    if text:revision=hashlib.sha256(str(text).encode()).hexdigest();break
            except (ValueError,TypeError,AttributeError):continue
        self.message_revisions[sid]=(signature,revision)
        if len(self.message_revisions)>self.REVISION_LIMIT:
            for key in list(self.message_revisions)[:self.CACHE_BATCH]:self.message_revisions.pop(key,None)
        return revision

    def task_status(self):
        # The lock only guards the cache tuple: holding it across the bridge calls
        # below serialised every concurrent poll behind one slow backend read.
        with self.status_lock:
            cached = self.status_cache
        if time.monotonic()-cached[0] < 5: return cached[1]
        value = self._task_status_uncached()
        with self.status_lock:
            # A writer may have invalidated the cache while this read was running;
            # republishing then would resurrect data the write had just dropped.
            if self.status_cache[0] >= cached[0]: self.status_cache = (time.monotonic(), value)
            return self.status_cache[1]

    def _task_status_uncached(self):
        tasks = []; errors = []
        try:
            for row in self.server.bridge.task_statuses(include_recent=True).get('tasks', []):
                if row.get('host', 'local') != 'local': continue
                task = {**row, 'backend': 'codex'}
                requests = row.get('requests') or []
                if requests and not all(isinstance(r, dict) for r in requests):
                    try:
                        requests = self.server.bridge.view(row['id'], attach=False, background=True).get('requests', [])
                    except Exception:
                        requests = [{'id': rid, 'kind': 'attention'} for rid in requests]
                task['attention'] = self.attention(requests)
                try:task['messageRevision']=self.codex_message_revision(row['id'])
                except Exception:pass
                tasks.append(task)
        except Exception: errors.append('codex')
        try:
            rows, partial_errors = self.server.claude.run(self.server.claude.listing())
            if partial_errors: errors.append('claude')
            tasks += [{'id': r['id'], 'backend': 'claude', 'title': r['title'], 'status': r['status'],
                       'error': r.get('error', False), 'token': str(r.get('updatedAt', '')),
                       'attention': self.attention(r.get('requests', []))} for r in rows if not r['archived']]
        except Exception: errors.append('claude')
        try:
            rows=self.server.deepseek.call('list')['sessions']
            tasks += [{'id':r['id'],'backend':'deepseek','title':r['title'],'status':r['status'],'token':str(r.get('updatedAt','')),
                       'attention':self.attention(r.get('requests',[]))} for r in rows if not r.get('archived')]
        except Exception:errors.append('deepseek')
        if not hasattr(self,'reply_revisions'):self.reply_revisions={}
        # One transcript read per session: the overlay needs both a content
        # revision and a progress line, and asking twice doubles the load.
        running=('active','running','working','inProgress')
        budget=4;deadline=time.monotonic()+12
        for task in tasks:
            if task['backend']=='codex':continue
            key=(task['backend'],task['id']);cached=self.reply_revisions.get(key)
            signature=(task.get('token'),task.get('status'))
            if cached and cached[0]==signature and task.get('status') not in running:
                task['messageRevision']=cached[1]
                if len(cached)>2:task['progress']=cached[2]
                continue
            if budget<=0 or time.monotonic()>deadline:continue
            budget-=1
            try:
                messages=self.progress_messages(task)
                replies=[(m.get('id'),m.get('text')) for m in messages if m.get('role')=='assistant' and m.get('kind') not in ('reasoning','step','tool') and m.get('text')]
                revision=hashlib.sha256(json.dumps(replies[-1:],ensure_ascii=False).encode()).hexdigest() if replies else (cached[1] if cached else '')
                progress=self.progress_text(messages)
                task['messageRevision']=revision;task['progress']=progress
                self.reply_revisions[key]=(signature,revision,progress)
            except Exception:pass
        if len(self.reply_revisions)>self.REPLY_LIMIT:
            for key in list(self.reply_revisions)[:self.CACHE_BATCH*2]:self.reply_revisions.pop(key,None)
        for task in [t for t in tasks if t.get('status') in running][:8]:
            if task['backend']!='codex':continue
            try:task['progress']=self.live_progress(task)
            except Exception:task['progress']='正在思考…'
        for task in tasks:
            if task.get('status') in running and not task.get('progress'):task['progress']='正在思考…'
        value = {'tasks': tasks, 'unavailable': errors}; self.status_cache = (time.monotonic(), value); return value

    @staticmethod
    def attention(requests):
        """Expose pending actions, never infer a question from assistant prose."""
        result = []
        for request in requests:
            if not isinstance(request, dict): continue
            method = str(request.get('method', ''))
            source = request.get('input', request.get('params')) or {}
            if not isinstance(source, dict): source = {}
            questions = source.get('questions') or []
            approval = 'requestApproval' in method
            question = not approval and (bool(questions) or 'requestUserInput' in method or
                method == 'mcpServer/elicitation/request' or request.get('needsInput') is True)
            kind = request.get('kind') or ('question' if question else 'approval')
            label = '待回答' if kind == 'question' else '待审批' if kind == 'approval' else '待处理'
            preview = ''
            if question and questions and isinstance(questions[0], dict):
                preview = questions[0].get('question') or questions[0].get('header') or ''
            if not preview:
                preview = source.get('command') or source.get('reason') or source.get('justification') or request.get('tool') or label
            if isinstance(preview, list): preview = ' '.join(map(str, preview))
            rid = request.get('id')
            if rid is None:
                rid = hashlib.sha256(json.dumps(request, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
            result.append({'id': str(rid), 'kind': kind, 'label': label, 'preview': str(preview)[:240]})
        return result
