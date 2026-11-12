"""JSON only: no web pages, passwords, or arbitrary Desktop RPC forwarding."""
import hashlib
import gzip
import json
import logging
import os
import socket
import sqlite3
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from .native_content import codex_messages, fenced_messages, message_chunks
from .native_features import NativeFeatures
from .conversation import summarize, codex_response
from .deepseek import DeepSeek
from . import VERSION


def message_window(value, before=None, limit=60):
    """Bound both the wire payload and each Android text layout; retain all history."""
    value = summarize(value)
    limit = max(1,min(100,int(limit)))
    end = None if before is None else int(before)
    if end is not None and end < 0: raise ValueError('历史位置无效')
    chunks = []
    for index, message in enumerate(fenced_messages(value.get('messages',[]))):
        text = str(message.get('text') or '')
        # Split by code points, so UTF-16 surrogate pairs are never broken on Android.
        if len(text) <= 3000:
            chunks.append({**message, 'sourceId':str(message.get('id') or index), 'id':str(message.get('id') or index)+':0',
                           'role':message.get('role','activity'),'text':text,'continuation':False})
            continue
        for part, body in enumerate(message_chunks(message)):
            chunks.append({**message, 'sourceId':str(message.get('id') or index), 'id':str(message.get('id') or index)+':'+str(part),
                           'role':message.get('role','activity'),'text':body,
                           'continuation':part>0})
    total = len(chunks)
    end = total if end is None else min(end,total)
    start = max(0,end-limit)
    page = chunks[start:end]
    revision = hashlib.sha256(json.dumps(page,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    return {**value,'messages':page,'revision':revision,
            'history':{'before':start,'end':end,'total':total,'hasEarlier':start>0,'hasLater':end<total}}

from claude_bridge.errors import BridgeUnavailable
from claude_bridge.packaged import APIError


def codex_detail(value):
    messages = codex_messages(value)
    requests = []
    for p in value.get('requests', []):
        method = p.get('method', '')
        simple = method in ('item/commandExecution/requestApproval','item/fileChange/requestApproval','item/permissions/requestApproval')
        requests.append({'id':p.get('id'), 'tool':method.split('/')[-2] if '/' in method else method,
                         'method':method, 'input':p.get('params',{}), 'needsInput':not simple or not p.get('supported',True)})
    row = {'id':value['id'],'title':value.get('name') or value.get('title') or '未命名会话','cwd':value.get('cwd',''),
           'source':value.get('source','codex'),'sessionId':value['id'],'identity':'codex:'+value['id'],
           'status':value.get('status','idle'), 'requests':requests, 'backend':'codex', 'updatedAt':value.get('updatedAt'),
           'model':value.get('model'), 'effort':value.get('effort'),
           'turnCount': len(value.get('turns', [])),
           'completedTurnId': str(next((t.get('id','') for t in reversed(value.get('turns',[])) if t.get('status') in ('completed','interrupted','failed')), '')),
           'turnId':str((value.get('turns') or [{}])[-1].get('id',''))}
    return {'session':row,'messages':messages,'connected':value.get('connected',False),
            'files':value.get('files',[]), 'error':value.get('connectionError') or value.get('latestError')}


class NativeServer(ThreadingHTTPServer):
    daemon_threads = True
    # SO_REUSEADDR lets two Windows servers bind the same address successfully;
    # traffic can then continue reaching the older, unrelated listener.
    allow_reuse_address = os.name != 'nt'

    def server_bind(self):
        if os.name == 'nt':
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    @classmethod
    def bind_available(cls, bridge, claude, pairing, directory, port=0):
        try:return cls(bridge,claude,pairing,directory,bind_port=port)
        except OSError:
            if not port:raise
            server=cls(bridge,claude,pairing,directory,bind_port=0)
            logging.warning('Gateway port %s occupied; using exclusive loopback port %s',port,server.server_port)
            return server

    def __init__(self, bridge, claude, pairing, directory, bind_host="127.0.0.1", bind_port=0):
        self.bridge, self.claude, self.pairing = bridge, claude, pairing
        self.deepseek = DeepSeek(directory)
        self.origins = set()
        self.hosts = set()
        self.write_lock = threading.Lock()
        super().__init__((bind_host,bind_port), Handler)
        self.db = sqlite3.connect(Path(directory)/'native-requests.sqlite',check_same_thread=False)
        self.db.execute('CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, fingerprint TEXT, result TEXT)')
        self.db.commit()
        self.features = NativeFeatures(self, directory)
        from .live_hub import LiveHub
        self.live = LiveHub(self)
        from .task_queue import TaskQueue
        self.queue = TaskQueue(self, directory)
        from .workspace import Workspace
        self.workspace = Workspace(self, directory)
        from .document_preview import DocumentPreview
        self.preview = DocumentPreview(directory)
        self.apps = None
        from .power_plan import PowerPlan
        self.power_plan = PowerPlan(self)
        self.refresh_ingress = None
        self.gateway_active=True
        self.allow_origin(f'http://127.0.0.1:{self.server_port}')

    def allow_origin(self, origin):
        self.origins.add(origin)
        self.hosts.add(urlsplit(origin).netloc)

    def dispatch(self, backend, sid, action, body):
        try:
            return self._dispatch(backend, sid, action, body)
        finally:
            # The source can emit while the write is still in progress. Always
            # invalidate again after completion, including uncertain outcomes.
            if sid and action:
                self.features.forget_detail(backend, sid)

    def _dispatch(self, backend, sid, action, body):
        if sid and action:
            # A write invalidates the short detail cache, so the read that follows
            # it shows the new state instead of the previous poll's answer.
            self.features.forget_detail(backend, sid)
        if backend == 'deepseek':
            if not action:return self.deepseek.call('detail' if sid else 'list',sid)
            if action=='open':return self.deepseek.call(action,sid)
            if action not in ('send','stop','respond','access','settings','rename','archive'):raise APIError(404,'不支持的操作')
            rid=str(uuid.UUID(body.get('id','')))
            fingerprint=hashlib.sha256(json.dumps([backend,sid,action,body],sort_keys=True).encode()).hexdigest()
            with self.write_lock:
                old=self.db.execute('SELECT fingerprint,result FROM requests WHERE id=?',(rid,)).fetchone()
                if old:
                    if old[0]!=fingerprint:raise APIError(409,'请求 ID 已用于其他操作')
                    return json.loads(old[1])
                unknown={'status':'unknown','id':rid,'message':'结果待核对，不会自动重复执行'}
                self.db.execute('INSERT INTO requests VALUES (?,?,?)',(rid,fingerprint,json.dumps(unknown)));self.db.commit()
                try:result={**self.deepseek.call(action,sid,body),'id':rid}
                except ValueError as exc:result={'status':'rejected','id':rid,'message':str(exc)}
                except Exception:result=unknown
                self.db.execute('UPDATE requests SET result=? WHERE id=?',(json.dumps(result),rid));self.db.commit()
                return result
        if backend == 'claude':
            path = '/sessions'+('/'+sid if sid else '')+('/'+action if action else '')
            result=self.claude.run(self.claude.dispatch(path,{},'POST' if action else 'GET',body))
            if sid and not action and 'contextUsage' not in result['session']:result['session']['contextUsage']=self.features.context_usage('claude',sid)
            return result
        if backend != 'codex':
            raise APIError(404,'页面不存在')
        if not sid:
            connected=False;connection_error=''
            try:
                self.bridge.ipc.connect()
                connected=bool(self.bridge.ipc.client_id and self.bridge.ipc.socket)
            except Exception as exc:connection_error=str(exc)[:200]
            # Three passes for the session list, in increasing cost order: the archive
            # pass must run before the working scan is even possible (archived rows are
            # excluded from the unarchived listing), and the working scan reads every
            # thread's rollout tail, so it stays last and runs once.
            rows=self.bridge.list(query='',limit=1000,offset=0,archived=False,working=False)
            archived_rows=self.bridge.list(query='',limit=1000,offset=0,archived=True,working=False)
            known={r['id'] for r in rows}
            rows=[{**r,'archived':False} for r in rows]+[{**r,'archived':True} for r in archived_rows if r['id'] not in known]
            active={r['id'] for r in self.bridge.list(query='',limit=1000,offset=0,archived=False,working=True)}
            return {'connected':connected,'connectionError':connection_error,'sessions':[{'id':r['id'],'title':r.get('name') or r.get('title') or '未命名会话',
                                  'cwd':r.get('cwd',''),'archived':r['archived'],'pinned':bool(r.get('is_pinned')),'status':'active' if r['id'] in active else 'idle','backend':'codex','source':'chatgpt' if 'chatgpt' in (str(r.get('source',''))+' '+str(r.get('originator',''))).lower() else 'codex','sessionId':r['id'],'identity':'codex:'+r['id'],
                                  'updatedAt':r.get('recency_at_ms') or r.get('updated_at_ms') or r.get('updated_at',0)} for r in rows if r.get('host','local')=='local']}
        if not action:
            value = self.bridge.view(sid,attach=True,background=True)
            if hasattr(self.bridge, 'store'):
                meta = self.bridge.store.get(sid)
                value = {**value, 'name': meta.get('name') or meta.get('title') or value.get('name'), 'updatedAt': meta.get('updated_at_ms') or meta.get('updated_at'),'source':'chatgpt' if 'chatgpt' in (str(meta.get('source',''))+' '+str(meta.get('originator',''))).lower() else 'codex'}
            detail = codex_detail(value)
            if hasattr(self.bridge, 'session'):
                settings = self.features.codex_settings(sid)
                from .native_content import permission_selection
                for key in ('model', 'effort'):
                    if settings.get(key): detail['session'][key] = settings[key]
                detail['session']['permissionMode'] = permission_selection(settings)
            detail['session']['contextUsage'] = self.features.context_usage('codex',sid)
            return detail
        if action == 'open':
            return self.bridge.open_chat(sid)
        if action not in ('send','edit','stop','respond','access','settings','goal','rename','archive','delete'):
            raise APIError(404,'不支持的操作')
        rid = str(uuid.UUID(body.get('id','')))
        fingerprint = hashlib.sha256(json.dumps([sid,action,body],sort_keys=True).encode()).hexdigest()
        with self.write_lock:
            old=self.db.execute('SELECT fingerprint,result FROM requests WHERE id=?',(rid,)).fetchone()
            if old:
                if old[0]!=fingerprint: raise APIError(409,'同一提交标识不能用于不同内容')
                return json.loads(old[1])
            unknown={'status':'unknown','id':rid,'message':'结果不确定，请检查原会话；不会自动重发'}
            self.db.execute('INSERT INTO requests VALUES (?,?,?)',(rid,fingerprint,json.dumps(unknown)))
            self.db.commit()
            try:
                if action in ('rename','archive','delete'):
                    from bridge.actions import ThreadActions
                    self.bridge.store.get(sid)
                    if action!='rename' and body.get('confirmed') is not True:raise ValueError('请确认会话操作')
                    result=ThreadActions(self.bridge.store.home).apply(sid,action,body.get('title'))
                    outcome={'id':rid,'status':'accepted'}
                elif action=='edit':
                    from .message_edit import codex_edit
                    outcome=codex_edit(self,sid,body)
                elif action=='send':
                    images=self.features.images(backend,sid,body.get('images',[]))
                    mode=body.get('mode','auto')
                    if mode=='auto':
                        active=self.bridge.view(sid,attach=True,background=True).get('status') in ('active','running','inProgress')
                        mode='steer' if active else 'send'
                    result=self.bridge.send(sid,body.get('text',''),rid,mode,body.get('plugins',[]),images)
                    outcome={'id':rid,'status':result.get('status','accepted')}
                elif action=='access':
                    outcome={**self.features.access(backend,sid,body),'id':rid}
                elif action=='settings':
                    outcome={**self.features.settings(backend,sid,body),'id':rid}
                elif action=='goal':
                    outcome={**self.features.goal(sid,body),'id':rid}
                elif action=='stop':
                    self.bridge.interrupt(sid)
                    outcome={'id':rid,'status':'accepted'}
                else:
                    current=codex_detail(self.bridge.view(sid,attach=True,background=True))['session']
                    permission=next((p for p in current['requests'] if p['id']==body.get('requestId')),None)
                    if not permission:raise ValueError('提问或审批已过期，请刷新')
                    self.bridge.respond(sid,permission['id'],codex_response(permission,body))
                    outcome={'id':rid,'status':'accepted'}
            except (ValueError,KeyError) as exc:
                outcome={'id':rid,'status':'rejected','message':str(exc)}
            except Exception:
                logging.exception('Desktop operation outcome unknown')
                outcome=unknown
            self.db.execute('UPDATE requests SET result=? WHERE id=?',(json.dumps(outcome),rid));self.db.commit()
            return outcome

    def application_action(self, app, action, body):
        if self.apps is None:raise APIError(503,'应用控制尚未准备好')
        rid=str(uuid.UUID(body.get('id','')))
        fingerprint=hashlib.sha256(json.dumps(['apps',app,action,body],sort_keys=True).encode()).hexdigest()
        with self.write_lock:
            old=self.db.execute('SELECT fingerprint,result FROM requests WHERE id=?',(rid,)).fetchone()
            if old:
                if old[0]!=fingerprint:raise APIError(409,'请求 ID 已用于其他操作')
                return json.loads(old[1])
            result={'status':'unknown','id':rid,'message':'结果待核对，请刷新应用状态；不会自动重复执行'}
            self.db.execute('INSERT INTO requests VALUES (?,?,?)',(rid,fingerprint,json.dumps(result)));self.db.commit()
            try:
                if app=='assistant' and action in ('shutdown_when_idle','cancel_auto_shutdown'):
                    if body.get('confirmed') is not True:raise ValueError('请确认电源设置')
                    result={**self.power_plan.set_enabled(action=='shutdown_when_idle'),'id':rid}
                else:
                    if app=='assistant' and action in ('shutdown','cancel') and body.get('confirmed') is True:self.power_plan.set_enabled(False)
                    result={**self.apps.execute(app,action,body.get('confirmed') is True),'id':rid}
            except (ValueError,OSError) as exc:result={'status':'rejected','id':rid,'message':str(exc)}
            except Exception:logging.exception('Application operation outcome unknown')
            self.db.execute('UPDATE requests SET result=? WHERE id=?',(json.dumps(result),rid));self.db.commit()
            return result

    def server_close(self):
        # TCPServer calls this on bind failure, before any service/database exists.
        # Preserve the original OSError so the desktop can choose a free port.
        try:
            for name in ('power_plan','live','preview','workspace','queue'):
                resource=getattr(self,name,None)
                if resource is not None:resource.close()
            features=getattr(self,'features',None)
            if features is not None:features.close()
            db=getattr(self,'db',None)
            if db is not None:db.close()
        finally:super().server_close()


class Handler(BaseHTTPRequestHandler):
    # Keep-alive: the phone reuses one tunnel connection instead of paying a
    # fresh TCP/TLS handshake (and a possible stale-route failure) per poll.
    protocol_version='HTTP/1.1'
    # Also the idle keep-alive window; a stalled client cannot pin a thread forever.
    timeout=120

    def log_message(self, *args):
        pass  # Never log pairing secrets or Authorization headers.

    def output(self, status, value):
        body=json.dumps(value,ensure_ascii=False).encode()
        accepts=self.headers.get('Accept-Encoding','').lower()
        compressed=False
        if len(body)>=768 and 'gzip' in accepts:
            body=gzip.compress(body,compresslevel=6,mtime=0);compressed=True
        self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Cache-Control','no-store')
        if compressed:self.send_header('Content-Encoding','gzip')
        self.send_header('Content-Length',str(len(body)))
        if status>=400:
            # A rejected request may carry an unread body; it must not be parsed
            # as the next pipelined request on this connection.
            self.send_header('Connection','close')
            self.close_connection=True
        self.end_headers();self.wfile.write(body)

    def event(self, value=None, comment=None):
        """Write one SSE frame and flush it through the tunnel immediately."""
        if comment is not None:self.wfile.write((': '+str(comment)+'\n').encode())
        if value is not None:
            data=json.dumps(value,ensure_ascii=False,separators=(',',':')).splitlines() or ['']
            self.wfile.write(b'event: snapshot\n')
            for line in data:self.wfile.write(('data: '+line+'\n').encode())
        self.wfile.write(b'\n');self.wfile.flush()

    def event_stream(self, backend, sid, query):
        return self.topic_stream('session:'+backend+':'+sid,query)

    def list_event_stream(self, backend, query):
        return self.topic_stream('list:'+backend,query)

    def status_event_stream(self, query):
        return self.topic_stream('status',query)

    def topic_stream(self,topic,query):
        """Older clients receive the same event-driven snapshots without envelopes."""
        subscription=self.server.live.subscribe({topic})
        credential=self.headers.get('Authorization','')[7:]
        known=query.get('known',[''])[0]
        self.send_response(200)
        self.send_header('Content-Type','text/event-stream; charset=utf-8')
        self.send_header('Cache-Control','no-cache, no-store')
        self.send_header('X-Accel-Buffering','no')
        self.send_header('X-Computer-Id',self.server.pairing.computer_id)
        self.end_headers()
        try:
            while not subscription.closed and self.server.gateway_active:
                event=subscription.read(12)
                if not self.server.pairing.authenticate(credential):break
                if event:
                    value=event['data'];revision=event['revision']
                    token=value.get('token',revision)
                    if token!=known or topic.startswith('session:'):
                        self.event(dict(value,statusRevision=revision) if topic=='status' else value)
                        known=token
                    else:self.event(comment='connected')
                else:self.event(comment='keep-alive')
        except (BrokenPipeError,ConnectionResetError,TimeoutError,OSError):pass
        finally:
            subscription.close();self.close_connection=True


    def do_GET(self): self.handle_api(False)
    def do_POST(self): self.handle_api(True)

    def unified_events(self, query):
        topics=self.server.live.topics(filter(None,query.get('backends',['claude,codex,deepseek'])[0].split(',')),filter(None,query.get('sessions',[''])[0].split(',')))
        subscription=self.server.live.subscribe(topics)
        credential=self.headers.get('Authorization','')[7:]
        self.send_response(200)
        self.send_header('Content-Type','text/event-stream; charset=utf-8')
        self.send_header('Cache-Control','no-cache, no-store')
        self.send_header('X-Accel-Buffering','no')
        self.send_header('X-Computer-Id',self.server.pairing.computer_id)
        self.end_headers()
        try:
            self.event(comment='connected')
            while not subscription.closed and self.server.gateway_active:
                event=subscription.read(12)
                if not self.server.pairing.authenticate(credential):break
                if event:self.event(event)
                else:self.event(comment='keep-alive')
        except (BrokenPipeError,ConnectionResetError,TimeoutError,OSError):pass
        finally:
            subscription.close();self.close_connection=True

    def handle_api(self, write):
        try:
            # The private listener may become ready after the initial start call.
            # Refresh only from the assistant's authenticated local network node.
            if self.headers.get('Host') not in self.server.hosts and self.server.refresh_ingress:
                self.server.refresh_ingress()
            if len(self.headers.get_all('Host',[]))!=1 or self.headers.get('Host') not in self.server.hosts:
                raise APIError(403,'不允许的入口')
            if self.headers.get('Origin') and self.headers['Origin'] not in self.server.origins:
                raise APIError(403,'不允许的来源')
            path=urlsplit(self.path).path
            if not self.server.gateway_active:raise APIError(503,'电脑网关已暂停')
            body={}
            if write:
                length=self.headers.get('Content-Length','')
                if self.headers.get('Transfer-Encoding') or not length.isdigit() or not 0<int(length)<=5*1024*1024:
                    raise ValueError('请求长度无效')
                body=json.loads(self.rfile.read(int(length)))
                if not isinstance(body,dict):raise ValueError('请求格式无效')
            if path=='/api/gateway' and not write:
                return self.output(200,{'service':'coding-remote','protocol':'http','name':'coding远程','version':VERSION,'computerId':self.server.pairing.computer_id,'loginConfigured':self.server.pairing.login_settings()['configured']})
            if path=='/api/pair' and write:
                from urllib.parse import urlsplit as split_origin
                entrance=self.headers.get('Origin')
                if entrance and split_origin(entrance).netloc!=self.headers['Host']:raise APIError(403,'登录入口不匹配')
                if not entrance:
                    entrance=next((value for value in self.server.origins if split_origin(value).netloc==self.headers['Host']),None)
                result=self.server.pairing.pair(body.get('token'),body.get('device'),self.client_address[0],body.get('deviceInfo'),body.get('requestId'),origin=entrance)
                logging.info('Device paired successfully (%s)',result['mode'])
                return self.output(200,result)
            header=self.headers.get('Authorization','')
            device=self.server.pairing.authenticate(header[7:] if header.startswith('Bearer ') else '')
            if not device:raise APIError(401,'连接已失效，请重新扫码')
            if path=='/api/device' and not write:
                return self.output(200,{'name':'coding远程','hostname':socket.gethostname(),'platform':os.name=='nt' and 'Windows' or os.name,'version':VERSION,'device':device['name'],'computerId':self.server.pairing.computer_id,'mode':device.get('mode','permanent'),'claudeConnected':self.server.claude.desktop.connected})
            if path=='/api/apps' and not write:
                if self.server.apps is None:raise APIError(503,'应用控制尚未准备好')
                return self.output(200,{**self.server.apps.snapshot(),**self.server.power_plan.snapshot()})
            app_parts=path.strip('/').split('/')
            if len(app_parts)==4 and app_parts[:2]==['api','apps'] and write:
                return self.output(200,self.server.application_action(app_parts[2],app_parts[3],body))
            if path=='/api/unpair' and write:
                self.server.pairing.revoke(device['id']);return self.output(200,{'ok':True})
            if path=='/api/native/status' and not write:
                return self.output(200,self.server.features.task_status())
            if path=='/api/native/events' and not write:
                return self.unified_events(parse_qs(urlsplit(self.path).query))
            if path=='/api/workspace/notifications':
                return self.output(200,self.server.workspace.notifications(body if write else None))
            if path=='/api/workspace/environments' and not write:
                return self.output(200,self.server.workspace.environments())
            if path=='/api/workspace/state':
                key=parse_qs(urlsplit(self.path).query).get('key',[''])[0]
                return self.output(200,self.server.workspace.state(key,body if write else None))
            if path=='/api/native/status/events' and not write:
                return self.status_event_stream(parse_qs(urlsplit(self.path).query))
            parts=path.strip('/').split('/')
            if len(parts) not in (3,4,5) or parts[:2]!=['api','native']:
                raise APIError(404,'接口不存在')
            backend=parts[2];sid=parts[3] if len(parts)>3 else None;action=parts[4] if len(parts)>4 else None
            if backend not in ('claude','codex','deepseek'):raise APIError(404,'页面不存在')
            # Parsed once: the poll below re-read the same query string for the
            # history cursor, the `known` token and the queue listing.
            query=parse_qs(urlsplit(self.path).query)
            features=self.server.features
            if sid and action=='download' and not write:
                target=features.resolve_file(backend,sid,query)
                if not isinstance(target,Path):raise ValueError('此文件需要通过桌面适配器读取')
                original=target
                rendered=query.get('render',[''])[0]=='1' and target.suffix.lower() in ('.doc','.docx','.ppt','.pptx','.xls','.xlsx','.odt','.odp','.ods','.rtf')
                if rendered:target=self.server.preview.cached_path(original)
                stat=target.stat();revision='pdf:'+self.server.preview.revision(original) if rendered else hashlib.sha256(f'{target}:{stat.st_mtime_ns}:{stat.st_ctime_ns}:{stat.st_size}'.encode()).hexdigest()
                if self.headers.get('If-None-Match')==revision:
                    self.send_response(304);self.send_header('Content-Length','0');self.end_headers();return
                import mimetypes
                from urllib.parse import quote
                self.send_response(200)
                self.send_header('Content-Type',mimetypes.guess_type(target.name)[0] or 'application/octet-stream')
                self.send_header('Content-Length',str(stat.st_size));self.send_header('ETag',revision)
                self.send_header('X-Computer-Id',self.server.pairing.computer_id)
                self.send_header('X-File-Name',quote(original.stem+'.pdf' if rendered else original.name))
                if rendered:self.send_header('X-Rendered','1');self.send_header('X-Original-Name',quote(original.name))
                self.end_headers()
                with target.open('rb') as stream:
                    while True:
                        data=stream.read(128*1024)
                        if not data:break
                        self.wfile.write(data)
                return
            if sid and action=='terminal' and write:
                return self.output(200,self.server.workspace.terminal(backend,sid,body))
            if sid=='events' and not write and not action:
                return self.list_event_stream(backend,query)
            if sid and not write and action=='events':
                return self.event_stream(backend,sid,query)
            # These routes answer without going through dispatch(), so they have to
            # invalidate the session's cached reads themselves: otherwise a write
            # followed immediately by a poll could be answered from the pre-write
            # snapshot and look as if the upload or the enqueue never happened.
            if sid and action=='git':
                result=features.git(backend,sid,query,body if write else None)
                if write:features.forget_detail(backend,sid)
                return self.output(200,result)
            if sid and action=='queue':
                result=self.server.queue.request(backend,sid,body) if write else {'items':self.server.queue.listing(backend,sid)}
                if write:features.forget_detail(backend,sid)
                return self.output(200,result)
            if sid=='projects' and not write and not action:return self.output(200,features.projects(backend))
            if sid=='create' and write and not action:return self.output(200,features.create(backend,body))
            if sid=='creation' and not write and action:return self.output(200,features.creation(backend,action))
            if sid and action=='upload' and write:
                result=features.upload(backend,sid,body)
                features.forget_detail(backend,sid)
                return self.output(200,result)
            if sid and not write and action=='edit' and backend in ('claude','codex'):
                from .message_edit import draft
                return self.output(200,draft(self.server.dispatch(backend,sid,None,{}),query.get('messageId',[''])[0]))
            if sid and not write and action in ('access','files','file','agents','catalog'):
                if action=='access':result=features.access(backend,sid)
                elif action=='files':result=features.files(backend,sid,query)
                elif action=='file':result=features.file(backend,sid,query)
                elif action=='catalog':result=features.catalog(backend,sid,query.get('section',['all'])[0])
                elif backend=='codex':result=features.agents(sid)
                else:result={'agents':[]}
                return self.output(200,result)
            if write != bool(action):raise APIError(405,'请求方法不支持')
            if sid and not action and backend in ('claude','deepseek'):
                # Hold an unchanged idle poll briefly. A desktop update returns on
                # the next check instead of making the phone pay a fixed 1–3 second
                # polling delay, while the bounded wait still releases the worker
                # when a client disappears.
                known=query.get('known',[''])[0]
                wait_ms=min(15000,max(0,int(query.get('wait',['0'])[0] or 0)))
                deadline=time.monotonic()+wait_ms/1000
                answer=None
                while known and wait_ms>0 and time.monotonic()<deadline:
                    answer=self.server.features.unchanged_answer(backend,sid,known)
                    if answer is None:break
                    time.sleep(.35)
                if answer is None and known:answer=self.server.features.unchanged_answer(backend,sid,known)
                if answer is not None:return self.output(200,answer)
            if backend in ('claude','deepseek') and action=='send':
                # Resolve staged image IDs only after authentication and session scoping.
                body={**body,'resolvedImages':features.images(backend,sid,body.get('images',[]))}
            result=self.server.dispatch(backend,sid,action,body)
            if write and sid:features.forget_detail(backend,sid)
            if sid and not action:
                result=message_window(result,query.get('before',[None])[0],query.get('limit',[60])[0])
                result['queue']=self.server.queue.listing(backend,sid)
                if backend in ('claude','deepseek','codex'):result['token']=self.server.features.stream_token(backend,sid)
            return self.output(200,result)
        except APIError as exc:self.output(exc.status,{'error':str(exc)})
        except PermissionError as exc:
            logging.warning('Pairing rejected: %s',exc)
            self.output(401,{'error':str(exc)})
        except (ValueError,KeyError,TypeError) as exc:self.output(400,{'error':str(exc)})
        except BridgeUnavailable as exc:self.output(503,{'error':str(exc)})
        except (BrokenPipeError,ConnectionResetError):pass
        except Exception:
            logging.exception('Native API failed')
            self.output(503,{'error':'桌面连接不可用，请查看电脑日志'})
