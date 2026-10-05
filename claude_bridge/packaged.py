"""Claude session controller and durable requests for the native assistant."""
import asyncio
import concurrent.futures
import hashlib
import json
import secrets
import sqlite3
import threading
import time
import uuid
import base64
import mimetypes
from pathlib import Path

from . import model
from .errors import BridgeUnavailable
from .mailbox import FileDesktop

ROOT = Path(__file__).resolve().parent


class APIError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


class Controller:
    def __init__(self, data_dir, reconnect=False, transport="file"):
        self.directory = Path(data_dir).resolve() / 'claude'
        self.directory.mkdir(parents=True, exist_ok=True)
        config_file = self.directory / 'desktop.json'
        if config_file.exists():
            config = json.loads(config_file.read_text('utf-8'))
        else:
            config = {'token': secrets.token_urlsafe(32)}
            config_file.write_text(json.dumps(config), encoding='utf-8')
        if transport == 'cdp':
            from .cdp_desktop import CdpDesktop
            self.desktop = CdpDesktop(config['token'])
        else:
            self.desktop = FileDesktop(self.directory, config['token'])
        workspace = self.directory.parent.parent
        script_config = {'transport': transport, 'cwd': str(workspace), 'token': config['token'],
                         'generation': self.desktop.generation, 'reconnect': reconnect,
                         'request': (self.directory/'request.json').relative_to(workspace).as_posix(),
                         'response': (self.directory/'response.json').relative_to(workspace).as_posix()}
        self.workspace = workspace
        self.script = (ROOT/'desktop-file.js').read_text('utf-8').replace('__BRIDGE_CONFIG__', json.dumps(script_config))
        self.console_script = (ROOT/'desktop-console.js').read_text('utf-8').replace('__BRIDGE_CONFIG__', json.dumps(script_config))
        (self.directory/'connect-desktop.js').write_bytes(self.script.encode('utf-8'))
        self.index = {}
        self.catalog_cache = {}
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()
        self.run(self.initialize())

    async def initialize(self):
        self.lock = asyncio.Lock()
        self.db = sqlite3.connect(self.directory/'requests.sqlite')
        self.db.execute('CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, fingerprint TEXT, result TEXT)')
        self.db.commit()

    def run(self, coroutine):
        future = asyncio.run_coroutine_threadsafe(coroutine, self.loop)
        try:
            return future.result(timeout=120)
        except concurrent.futures.TimeoutError as exc:
            future.cancel()
            raise BridgeUnavailable('桌面响应超时，请检查原会话；不要重复提交') from exc

    async def listing(self):
        if not self.desktop.connected:
            raise BridgeUnavailable(getattr(self.desktop, 'message', '') or 'Claude 未连接，点击页面标题后选择启动')
        rows, errors = [], []
        for kind in ('code', 'cowork'):
            if 'getAll' not in self.desktop.capabilities.get(kind, []):
                continue
            try:
                rows.extend(model.session(kind, row) for row in model.rows(await self.desktop.call(kind, 'getAll')))
            except (BridgeUnavailable, ValueError) as exc:
                errors.append({'backend': kind, 'error': str(exc)})
        self.index = {row['id']: row for row in rows}
        return sorted(rows, key=lambda row: str(row['updatedAt']), reverse=True), errors

    async def session(self, sid):
        if sid not in self.index:
            await self.listing()
        row = self.index.get(sid)
        if not row:
            raise APIError(404, '找不到会话')
        raw = await self.desktop.call(row['backend'], 'getSession', row['nativeId'])
        if not isinstance(raw, dict) or raw.get('sessionId') != row['nativeId']:
            raise BridgeUnavailable('桌面返回的会话标识不一致，已取消操作')
        row = model.session(row['backend'], raw)
        self.index[sid] = row
        return row

    async def mutate(self, sid, action, body):
        if action not in ('send', 'stop', 'respond', 'access', 'settings', 'rename', 'archive', 'delete'):
            raise APIError(404, '不支持的操作')
        rid = str(uuid.UUID(body.get('id', '')))
        fingerprint = hashlib.sha256(json.dumps([sid, action, body], sort_keys=True).encode()).hexdigest()
        async with self.lock:
            old = self.db.execute('SELECT fingerprint,result FROM requests WHERE id=?', (rid,)).fetchone()
            if old:
                if old[0] != fingerprint:
                    raise APIError(409, '同一请求 ID 不能用于不同操作')
                return json.loads(old[1])
            row = await self.session(sid)
            kind, native = row['backend'], row['nativeId']
            operations = []
            if action == 'send':
                text = body.get('text')
                images=body.get('resolvedImages',[])
                if not isinstance(text, str) or (not text.strip() and not images) or len(text) > 100000:
                    raise ValueError('请输入 1–100000 字的消息')
                method, args = 'sendMessage', [native, text]
                plugins=body.get('plugins',[])
                if plugins:
                    catalog=await self.catalog(sid,'plugins')
                    choices={p['id']:p for p in catalog['skills']}
                    if not isinstance(plugins,list) or any(p not in choices for p in plugins):raise ValueError('所选插件已不可用，请刷新')
                    args[1]='请使用以下已安装技能：'+', '.join('/'+choices[p]['name'] for p in plugins)+'\n\n'+text
                if images:
                    native_images=[]
                    for image in images:
                        header,data=image['url'].split(',',1)
                        native_images.append({'base64':data,'mimeType':header[5:].split(';')[0]})
                    args.append(native_images)
                if kind=='code' and row['status']=='active':
                    while len(args)<5:args.append(None)
                    args.append('now' if body.get('mode')=='steer' else 'next')
            elif action in ('rename','archive','delete'):
                if action=='rename':
                    title=body.get('title','').strip()
                    if not title or len(title)>120:raise ValueError('标题为 1–120 字')
                    method,args='updateSession',[native,{'title':title}]
                else:
                    if body.get('confirmed') is not True:raise ValueError('请确认会话操作')
                    method,args=action,[native]
            elif action == 'stop':
                method, args = ('interrupt' if kind == 'code' else 'stop'), [native]
            elif action == 'access':
                mode=body.get('mode')
                if mode not in ('default','plan','acceptEdits','bypassPermissions'):raise ValueError('请选择有效权限')
                method,args='setPermissionMode',[native,mode]
            elif action == 'settings':
                catalog=await self.catalog(sid,'models')
                selected = next((m for m in catalog['models'] if m['id'] == (body.get('model') or row.get('model'))), None)
                if not selected: raise ValueError('请选择桌面提供的模型')
                if body.get('model'):
                    operations.append(('setModel', [native, body['model']]))
                if 'effort' in body:
                    effort=body.get('effort')
                    supported=[e.get('id',e.get('reasoningEffort')) if isinstance(e,dict) else e for e in selected.get('efforts',[])]
                    if effort not in supported:raise ValueError('该模型不支持所选思考强度')
                    operations.append(('setEffort', [native, effort]))
                if not operations: raise ValueError('请选择模型或思考强度')
                method,args=operations[0]
            else:
                permission = next((r for r in row['requests'] if r['id'] == body.get('requestId')), None)
                if not permission:
                    raise APIError(409, '确认请求已过期，请刷新')
                decision = {'accept': 'once', 'decline': 'deny'}.get(body.get('decision'))
                if not decision:
                    raise ValueError('仅支持本次允许或拒绝')
                method, args = 'respondToToolPermission', [permission['id'], decision]
                if permission['needsInput'] and decision=='once':
                    from coding_remote.conversation import validate_answers
                    answers=validate_answers(permission['input'].get('questions',[]),body.get('answers'))
                    args.append({**permission['input'],'answers':{k:', '.join(v['answers']) for k,v in answers.items()}})
            operations = operations or [(method, args)]
            if any(operation not in self.desktop.capabilities.get(kind, []) for operation, _ in operations):
                raise BridgeUnavailable('当前桌面版本不支持该操作')
            outcome = {'status': 'unknown', 'id': rid, 'message': '结果不确定，请检查原会话；相同请求不会再次执行'}
            self.db.execute('INSERT INTO requests VALUES (?,?,?)', (rid, fingerprint, json.dumps(outcome)))
            self.db.commit()
            try:
                rejected = False
                for operation, arguments in operations:
                    result = await self.desktop.call(kind, operation, *arguments, timeout=90)
                    rejected = result is False or (isinstance(result, dict) and
                        (result.get('delivered') is False or result.get('dispatched') is False or result.get('ok') is False or result.get('applied') is False))
                    if rejected: break
                outcome = {'status': 'rejected' if rejected else 'accepted', 'id': rid}
                if action == 'settings' and not rejected:
                    outcome.update({key: body[key] for key in ('model', 'effort') if key in body})
                if action == 'access' and not rejected: outcome['mode'] = body['mode']
                if action in ('settings', 'access') and not rejected:
                    observed = await self.session(sid)
                    expected = {key: body[key] for key in ('model', 'effort') if key in body} if action == 'settings' else {'permissionMode': body['mode']}
                    actual = {key: observed.get(key) for key in expected}
                    if actual != expected:
                        outcome.update(status='rejected', message='桌面保存的设置与所选值不同，请刷新选项后检查', actual=actual)
            except BridgeUnavailable:
                pass
            self.db.execute('UPDATE requests SET result=? WHERE id=?', (json.dumps(outcome), rid))
            self.db.commit()
            return outcome

    async def catalog(self,sid,section='all'):
        if section not in ('all','models','plugins'):raise ValueError('选项目录无效')
        row=self.index.get(sid) or await self.session(sid)
        kind=row['backend'];caps=self.desktop.capabilities.get(kind,[])
        cache=self.catalog_cache.get((sid,section))
        if cache and time.monotonic()-cache[0]<15:return cache[1]
        models=[];skills=[];notices=[]
        async def optional(method):
            argument={'sessionId':row['nativeId'],'cwd':row.get('cwd') or ''} if method=='getSupportedCommands' else row['nativeId']
            try:return await asyncio.wait_for(self.desktop.call(kind,method,argument,timeout=4),5)
            except (BridgeUnavailable,asyncio.TimeoutError):
                notices.append('部分电脑选项暂时不可用，可稍后刷新')
                return None
        if section!='plugins':
            if 'mobileCatalog' in caps:
                value=await optional('mobileCatalog')
                if isinstance(value,dict):models=[m for m in value.get('models',[]) if isinstance(m,dict) and m.get('id')]
            for known in [*self.index.values(),row]:
                model_id=known.get('model')
                if known.get('backend')==kind and model_id and model_id not in [m['id'] for m in models]:
                    models.append({'id':model_id,'name':model_id,'efforts':['low','medium','high','max'] if 'setEffort' in caps else []})
        if section!='models' and 'getSupportedCommands' in caps:
            commands=await optional('getSupportedCommands')
            if isinstance(commands,dict):commands=commands.get('commands',[])
            for c in commands if isinstance(commands,list) else []:
                if not isinstance(c,dict) or not c.get('name'):continue
                if c.get('type') not in ('prompt','skill','plugin') and not c.get('pluginName') and ':' not in c['name']:continue
                name=c['name'].lstrip('/')
                skills.append({'id':name,'name':name,'displayName':c.get('displayName') or name,'description':c.get('description','')})
        result={'models':models,'skills':skills,'currentModel':row.get('model'),'currentEffort':row.get('effort'),'canSetModel':'setModel' in caps,'canSetEffort':'setEffort' in caps,'notice':'；'.join(notices)}
        if not notices:self.catalog_cache[(sid,section)]=(time.monotonic(),result)
        return result

    async def create_session(self, project, title, rid):
        fingerprint=hashlib.sha256(json.dumps(['create',project['key'],title],sort_keys=True).encode()).hexdigest()
        async with self.lock:
            old=self.db.execute('SELECT fingerprint,result FROM requests WHERE id=?',(rid,)).fetchone()
            if old:
                if old[0]!=fingerprint:raise APIError(409,'不能用同一请求创建不同会话')
                return json.loads(old[1])
            kind=project['surface']
            if 'start' not in self.desktop.capabilities.get(kind,[]):raise BridgeUnavailable('请通过页面标题重新连接 Claude')
            result={'requestId':rid,'status':'unknown','message':'创建结果待核对，请勿重复创建'}
            self.db.execute('INSERT INTO requests VALUES (?,?,?)',(rid,fingerprint,json.dumps(result)));self.db.commit()
            try:
                value=await self.desktop.call(kind,'start',{'cwd':project['cwd'],'title':title,'message':'','useWorktree':False},timeout=90)
                if not isinstance(value,dict) or not value.get('sessionId'):raise BridgeUnavailable('创建返回格式不兼容')
                result={'requestId':rid,'status':'ready','result':{'id':model.gateway_id(kind,value['sessionId']),'title':title,'cwd':project['cwd']}}
                self.index[result['result']['id']]={**result['result'],'backend':kind,'nativeId':value['sessionId']}
            except BridgeUnavailable as exc:result['message']=str(exc)
            self.db.execute('UPDATE requests SET result=? WHERE id=?',(json.dumps(result),rid));self.db.commit();return result

    async def creation_status(self,rid):
        row=self.db.execute('SELECT result FROM requests WHERE id=?',(rid,)).fetchone()
        return json.loads(row[0]) if row else {'status':'missing','requestId':rid}

    async def access(self,sid,body=None):
        if body is not None:return await self.mutate(sid,'access',body)
        row=await self.session(sid);kind=row['backend']
        capabilities=self.desktop.capabilities.get(kind,[])
        if 'setPermissionMode' not in capabilities:raise BridgeUnavailable('请通过页面标题重新连接 Claude')
        value=await self.desktop.call(kind,'getPermissionMode',row['nativeId']) if 'getPermissionMode' in capabilities else row.get('permissionMode','desktop')
        mode=value.get('mode',value.get('permissionMode','default')) if isinstance(value,dict) else value
        return {'mode':mode,'modes':['default','plan','acceptEdits','bypassPermissions']}

    async def files(self,sid,query):
        row=await self.session(sid);path=query.get('path',[''])[0]
        value=await self.desktop.call(row['backend'],'listSessionDirectory',row['nativeId'],path or '.')
        entries=value if isinstance(value,list) else (value or {}).get('entries',[])
        return {'path':path,'parent':path.rsplit('/',1)[0] if path else None,'entries':[
            {'name':r.get('name','文件'),'path':r.get('path') or (path+'/'+r.get('name','')).lstrip('/'),
             'directory':r.get('isDirectory',r.get('directory',r.get('type')=='directory')),'size':r.get('size',0)} for r in entries if isinstance(r,dict)],'more':False}

    async def file(self,sid,query):
        row=await self.session(sid);path=query.get('path',[''])[0]
        if not path or '\0' in path or '..' in Path(path).parts:raise ValueError('文件路径无效')
        mime=mimetypes.guess_type(path)[0] or 'text/plain'
        if mime == 'application/pdf':
            value=await self.desktop.call(row['backend'],'readSessionPanelDocumentAsDataUrl',row['nativeId'],path)
            if not isinstance(value,str) or not value.startswith('data:application/pdf;base64,'):raise ValueError('当前桌面无法读取该 PDF，请在电脑打开')
            return {'name':Path(path).name,'mime':mime,'data':value.split(',',1)[1]}
        if mime.startswith('image/'):
            value=await self.desktop.call(row['backend'],'readSessionImageAsDataUrl',row['nativeId'],path)
            url=value if isinstance(value,str) else (value or {}).get('dataUrl','')
            if not url.startswith('data:image/') or ',' not in url:raise ValueError('图片暂时无法读取')
            return {'name':Path(path).name,'mime':url[5:].split(';')[0],'data':url.split(',',1)[1]}
        value=await self.desktop.call(row['backend'],'readSessionFile',row['nativeId'],path)
        content=value.get('contents') if isinstance(value,dict) else value
        if not isinstance(content,str):raise ValueError('当前文件暂不支持预览')
        return {'name':Path(path).name,'mime':mime,'text':content[:300000],
                'truncated':len(content)>300000,'data':base64.b64encode(content.encode()).decode()}

    async def dispatch(self, path, query, method, body):
        if method == 'GET' and path == '/diagnostics':
            return {'connected': self.desktop.connected, 'capabilities': self.desktop.capabilities,
                    'transport': 'file', 'integrated': True}
        if method == 'GET' and path == '/sessions':
            rows, errors = await self.listing()
            kind = query.get('backend', [''])[0]
            return {'connected':self.desktop.connected,'sessions': sorted([r for r in rows if not kind or r['backend'] == kind],key=lambda r:r['archived']), 'errors': errors}
        parts = path.strip('/').split('/')
        if len(parts) == 2 and parts[0] == 'sessions' and method == 'GET':
            row = await self.session(parts[1])
            raw = await self.desktop.call(row['backend'], 'getTranscript', row['nativeId'])
            messages = model.transcript(raw)
            return {'session': row, 'messages': messages, 'connected': self.desktop.connected,
                    'notice': model.transcript_notice(raw)}
        if len(parts) == 3 and parts[0] == 'sessions' and method == 'POST':
            if parts[2]=='open':
                row=await self.session(parts[1])
                if 'setThinkingSummariesWanted' in self.desktop.capabilities.get(row['backend'],[]):
                    await self.desktop.call(row['backend'],'setThinkingSummariesWanted',row['nativeId'],True)
                return {'ok':True}
            return await self.mutate(parts[1], parts[2], body)
        raise APIError(404, '接口不存在')

    def close(self):
        async def cleanup():
            self.desktop.close()
            self.db.close()
        try:
            self.run(cleanup())
        finally:
            self.loop.call_soon_threadsafe(self.loop.stop)
            self.thread.join(timeout=5)
