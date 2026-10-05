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
from .native_content import permission_selection, permission_settings, referenced_paths

MAX_IMAGE = 3 * 1024 * 1024
MAX_FILE = 12 * 1024 * 1024


class NativeFeatures:
    def __init__(self, server, directory):
        self.server = server
        self.directory = Path(directory) / 'native-uploads'
        self.directory.mkdir(parents=True, exist_ok=True)
        self.status_lock = threading.Lock()
        self.status_cache = (0, {})
        self.context_cache = {}
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
        from .context_usage import normalize
        try:
            if backend == 'codex':
                live=self.server.bridge.session(sid, attach=False)
                with live.condition: value=(live.state or {}).get('latestTokenUsageInfo')
                if value is not None: return normalize(value)
                store=self.server.bridge.store
                with store._rollout_path(store.get(sid)).open('rb') as stream:
                    stream.seek(0,2);stream.seek(max(0,stream.tell()-262144));lines=stream.read().splitlines()
                for line in reversed(lines):
                    try:
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
        return self.server.dispatch(backend, sid, None, {})

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
        for row in rows:
            if row['cwd']:
                key = row['backend']+'|'+row['cwd']
                projects[key] = {'key': key, 'name': Path(row['cwd']).name+' · '+row['backend'].title(), 'cwd': row['cwd'],
                                 'surface': row['backend'], 'hostLabel': '此电脑'}
        return {'projects': list(projects.values()), 'errors': errors}

    def create(self, backend, body):
        rid = str(uuid.UUID(body.get('id', '')))
        project = next((p for p in self.projects(backend)['projects'] if p['key'] == body.get('projectKey')), None)
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
        return self.server.claude.run(self.server.claude.create_session(project, title, rid))

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

    def files(self, backend, sid, query):
        if backend=='codex':detail={'session':self.server.bridge.store.get(sid)}
        elif backend=='claude':
            row=self.server.claude.index.get(sid) or self.server.claude.run(self.server.claude.session(sid))
            detail={'session':row}
        else:detail=self.detail(backend,sid)
        cwd = detail['session'].get('cwd')
        if not cwd or not Path(cwd).is_dir():
            if backend == 'claude': return self.server.claude.run(self.server.claude.files(sid, query))
            if detail.get('files'):
                return {'path': '', 'parent': None, 'entries': [], 'more': False, 'artifacts': detail['files']}
            raise ValueError('当前项目目录不可用')
        root = Path(cwd).resolve()
        requested = query.get('path', [''])[0]
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
        artifacts = list(detail.get('files') or [])
        known = {a.get('path') for a in artifacts if isinstance(a, dict)}
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

    def file(self, backend, sid, query):
        artifact = query.get('artifact', [''])[0]
        if artifact and backend == 'codex':
            if not re.fullmatch('[a-f0-9]{64}', artifact): raise ValueError('文件标识无效')
            resolved = self.server.bridge.artifact(sid, artifact)
            path = Path(resolved['path'] if isinstance(resolved, dict) else resolved)
        else:
            detail = self.detail(backend, sid); cwd = detail['session'].get('cwd')
            requested = query.get('path', [''])[0]
            match = next((a for a in detail.get('files', []) if a.get('absolutePath', '').replace('\\', '/') == requested.replace('\\', '/')), None)
            tool_images=referenced_paths([m for m in detail.get('messages',[]) if m.get('kind')=='image'])
            if backend=='codex' and match is not None:
                resolved=self.server.bridge.artifact(sid,match['id']);path=Path(resolved['path'] if isinstance(resolved,dict) else resolved)
            elif requested in tool_images and Path(requested).is_absolute() and Path(requested).suffix.lower() in ('.png','.jpg','.jpeg','.webp','.gif','.bmp'):
                path=Path(requested).resolve()
            else:
                if not cwd or not Path(cwd).is_dir():
                    if backend == 'claude': return self.server.claude.run(self.server.claude.file(sid, query))
                    raise ValueError('当前项目目录不可用')
                path=self.contained(Path(cwd).resolve(),requested)
        if not path.is_file(): raise ValueError('文件不存在')
        if path.stat().st_size > MAX_FILE: raise ValueError('预览文件不能超过 12 MB，请在电脑打开')
        with path.open('rb') as stream: data = stream.read(MAX_FILE+1)
        if len(data) > MAX_FILE: raise ValueError('预览文件不能超过 12 MB，请在电脑打开')
        mime = mimetypes.guess_type(path.name)[0] or 'application/octet-stream'
        result = {'name': path.name, 'mime': mime, 'size': len(data), 'data': base64.b64encode(data).decode()}
        if not mime.startswith('image/') and mime != 'application/pdf':
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
    def progress_text(messages):
        for message in reversed(messages):
            if message.get('role') in ('assistant','reasoning','thought','analysis'):
                text=message.get('text','').strip()
                if text and not text.startswith('[工具调用]'):
                    return text[-240:]
            if message.get('role') == 'activity' and message.get('title'):
                return '正在执行：'+str(message['title'])[:200]
        return '正在思考…'

    def live_progress(self, task):
        backend,sid=task['backend'],task['id']
        if backend=='codex':
            store=self.server.bridge.store
            path=store._rollout_path(store.get(sid))
            with path.open('rb') as stream:
                stream.seek(0,2);size=stream.tell();stream.seek(max(0,size-262144));lines=stream.read().splitlines()
            for line in reversed(lines):
                try:
                    item=json.loads(line);payload=item.get('payload',{})
                    if item.get('type')=='response_item' and payload.get('role')=='assistant':
                        text='\n'.join(x.get('text','') for x in payload.get('content',[]) if isinstance(x,dict)).strip()
                        if text:return text[-240:]
                    if item.get('type')=='event_msg' and payload.get('type')=='agent_message':
                        return str(payload.get('message','正在思考…'))[-240:]
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
        with path.open('rb') as stream:
            stream.seek(max(0,stat.st_size-262144));lines=stream.read().splitlines()
        for line in reversed(lines):
            try:
                value=json.loads(line);payload=value.get('payload') or {};kind=payload.get('type')
                message=value.get('type')=='response_item' and kind=='message' and payload.get('role')=='assistant' and payload.get('channel') not in ('analysis','commentary')
                message|=value.get('type')=='event_msg' and kind=='agent_message' and payload.get('phase') not in ('analysis','commentary')
                if message:
                    text=payload.get('message') or '\n'.join(x.get('text','') for x in payload.get('content',[]) if isinstance(x,dict))
                    if text:revision=hashlib.sha256(str(text).encode()).hexdigest();break
            except (ValueError,TypeError,AttributeError):continue
        self.message_revisions[sid]=(signature,revision)
        if len(self.message_revisions)>1000:self.message_revisions.pop(next(iter(self.message_revisions)))
        return revision

    def task_status(self):
        with self.status_lock:
            if time.monotonic()-self.status_cache[0] < 5: return self.status_cache[1]
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
            budget=8
            for task in tasks:
                if task['backend']=='codex':continue
                key=(task['backend'],task['id']);cached=self.reply_revisions.get(key)
                signature=(task.get('token'),task.get('status'))
                if cached and cached[0]==signature:task['messageRevision']=cached[1];continue
                if budget<=0:continue
                budget-=1
                try:
                    if task['backend']=='claude':
                        from claude_bridge import model
                        row=self.server.claude.index[task['id']]
                        raw=self.server.claude.run(self.server.claude.desktop.call(row['backend'],'mobileProgress',row['nativeId'],timeout=4))
                        messages=model.transcript(raw)
                    else:messages=self.server.deepseek.call('detail',task['id']).get('messages',[])
                    replies=[(m.get('id'),m.get('text')) for m in messages if m.get('role')=='assistant' and m.get('kind') not in ('reasoning','step','tool') and m.get('text')]
                    revision=hashlib.sha256(json.dumps(replies[-1:],ensure_ascii=False).encode()).hexdigest() if replies else (cached[1] if cached else '')
                    task['messageRevision']=revision;self.reply_revisions[key]=(signature,revision)
                except Exception:pass
            if len(self.reply_revisions)>2000:self.reply_revisions.clear()
            for task in [t for t in tasks if t.get('status') in ('active','running','working','inProgress')][:8]:
                try:task['progress']=self.live_progress(task)
                except Exception:task['progress']='正在思考…'
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
