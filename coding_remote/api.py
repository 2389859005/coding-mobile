"""JSON only: no web pages, passwords, or arbitrary Desktop RPC forwarding."""
import hashlib
import json
import logging
import sqlite3
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from .native_content import codex_messages, fenced_messages
from .native_features import NativeFeatures
from .conversation import summarize, codex_response
from .deepseek import DeepSeek


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
        for part, offset in enumerate(range(0,len(text),3000)):
            chunks.append({**message, 'id':str(message.get('id') or index)+':'+str(part),
                           'role':message.get('role','activity'),'text':text[offset:offset+3000],
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
           'status':value.get('status','idle'), 'requests':requests, 'backend':'codex', 'updatedAt':value.get('updatedAt'),
           'model':value.get('model'), 'effort':value.get('effort'),
           'turnCount': len(value.get('turns', [])),
           'completedTurnId': str(next((t.get('id','') for t in reversed(value.get('turns',[])) if t.get('status') in ('completed','interrupted','failed')), '')),
           'turnId':str((value.get('turns') or [{}])[-1].get('id',''))}
    return {'session':row,'messages':messages,'connected':value.get('connected',False),
            'files':value.get('files',[]), 'error':value.get('connectionError') or value.get('latestError')}


class NativeServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, bridge, claude, pairing, directory):
        self.bridge, self.claude, self.pairing = bridge, claude, pairing
        self.deepseek = DeepSeek(directory)
        self.origins = set()
        self.hosts = set()
        self.write_lock = threading.Lock()
        self.db = sqlite3.connect(Path(directory)/'native-requests.sqlite',check_same_thread=False)
        self.db.execute('CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, fingerprint TEXT, result TEXT)')
        self.db.commit()
        self.features = NativeFeatures(self, directory)
        from .task_queue import TaskQueue
        self.queue = TaskQueue(self, directory)
        self.apps = None
        self.refresh_ingress = None
        super().__init__(('127.0.0.1',0), Handler)
        self.allow_origin(f'http://127.0.0.1:{self.server_port}')

    def allow_origin(self, origin):
        self.origins.add(origin)
        self.hosts.add(urlsplit(origin).netloc)

    def dispatch(self, backend, sid, action, body):
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
            if sid and not action:result['session']['contextUsage']=self.features.context_usage('claude',sid)
            return result
        if backend != 'codex':
            raise APIError(404,'页面不存在')
        if not sid:
            connected=False;connection_error=''
            try:
                self.bridge.ipc.connect()
                connected=bool(self.bridge.ipc.client_id and self.bridge.ipc.socket)
            except Exception as exc:connection_error=str(exc)[:200]
            rows = self.bridge.list(query='',limit=1000,offset=0,archived=False,working=False)
            archived_rows=self.bridge.list(query='',limit=1000,offset=0,archived=True,working=False)
            known={r['id'] for r in rows}
            rows=[{**r,'archived':False} for r in rows]+[{**r,'archived':True} for r in archived_rows if r['id'] not in known]
            active = {r['id'] for r in self.bridge.list(query='',limit=1000,offset=0,archived=False,working=True)}
            return {'connected':connected,'connectionError':connection_error,'sessions':[{'id':r['id'],'title':r.get('name') or r.get('title') or '未命名会话',
                                  'cwd':r.get('cwd',''),'archived':r['archived'],'status':'active' if r['id'] in active else 'idle','backend':'codex',
                                  'updatedAt':r.get('recency_at_ms') or r.get('updated_at_ms') or r.get('updated_at',0)} for r in rows if r.get('host','local')=='local']}
        if not action:
            value = self.bridge.view(sid,attach=True,background=True)
            if hasattr(self.bridge, 'store'):
                meta = self.bridge.store.get(sid)
                value = {**value, 'name': meta.get('name') or meta.get('title') or value.get('name'), 'updatedAt': meta.get('updated_at_ms') or meta.get('updated_at')}
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
        if action not in ('send','stop','respond','access','settings','goal','rename','archive','delete'):
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
            try:result={**self.apps.execute(app,action,body.get('confirmed') is True),'id':rid}
            except (ValueError,OSError) as exc:result={'status':'rejected','id':rid,'message':str(exc)}
            except Exception:logging.exception('Application operation outcome unknown')
            self.db.execute('UPDATE requests SET result=? WHERE id=?',(json.dumps(result),rid));self.db.commit()
            return result

    def server_close(self):
        self.queue.close()
        self.features.git_projects.close()
        super().server_close()
        self.db.close()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Never log pairing secrets or Authorization headers.

    def output(self, status, value):
        body=json.dumps(value,ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Cache-Control','no-store')
        self.send_header('Content-Length',str(len(body)))
        self.end_headers();self.wfile.write(body)

    def do_GET(self): self.handle_api(False)
    def do_POST(self): self.handle_api(True)

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
            body={}
            if write:
                self.connection.settimeout(10)
                length=self.headers.get('Content-Length','')
                if self.headers.get('Transfer-Encoding') or not length.isdigit() or not 0<int(length)<=5*1024*1024:
                    raise ValueError('请求长度无效')
                body=json.loads(self.rfile.read(int(length)))
                if not isinstance(body,dict):raise ValueError('请求格式无效')
            if path=='/api/pair' and write:
                result=self.server.pairing.pair(body.get('token'),body.get('device'),self.client_address[0],body.get('deviceInfo'),body.get('requestId'))
                logging.info('Device paired successfully (%s)',result['mode'])
                return self.output(200,result)
            header=self.headers.get('Authorization','')
            device=self.server.pairing.authenticate(header[7:] if header.startswith('Bearer ') else '')
            if not device:raise APIError(401,'连接已失效，请重新扫码')
            if path=='/api/device' and not write:
                return self.output(200,{'name':'coding远程','version':'2.0.11','device':device['name'],'computerId':self.server.pairing.computer_id,'mode':device.get('mode','permanent'),'claudeConnected':self.server.claude.desktop.connected})
            if path=='/api/apps' and not write:
                if self.server.apps is None:raise APIError(503,'应用控制尚未准备好')
                return self.output(200,self.server.apps.snapshot())
            app_parts=path.strip('/').split('/')
            if len(app_parts)==4 and app_parts[:2]==['api','apps'] and write:
                return self.output(200,self.server.application_action(app_parts[2],app_parts[3],body))
            if path=='/api/unpair' and write:
                self.server.pairing.revoke(device['id']);return self.output(200,{'ok':True})
            if path=='/api/native/status' and not write:
                return self.output(200,self.server.features.task_status())
            parts=path.strip('/').split('/')
            if len(parts) not in (3,4,5) or parts[:2]!=['api','native']:
                raise APIError(404,'接口不存在')
            backend=parts[2];sid=parts[3] if len(parts)>3 else None;action=parts[4] if len(parts)>4 else None
            if backend not in ('claude','codex','deepseek'):raise APIError(404,'页面不存在')
            query=parse_qs(urlsplit(self.path).query)
            features=self.server.features
            if sid and action=='git':return self.output(200,features.git(backend,sid,query,body if write else None))
            if sid and action=='queue':
                result=self.server.queue.request(backend,sid,body) if write else {'items':self.server.queue.listing(backend,sid)}
                return self.output(200,result)
            if sid=='projects' and not write and not action:return self.output(200,features.projects(backend))
            if sid=='create' and write and not action:return self.output(200,features.create(backend,body))
            if sid=='creation' and not write and action:return self.output(200,features.creation(backend,action))
            if sid and action=='upload' and write:return self.output(200,features.upload(backend,sid,body))
            if sid and not write and action in ('access','files','file','agents','catalog'):
                if action=='access':result=features.access(backend,sid)
                elif action=='files':result=features.files(backend,sid,query)
                elif action=='file':result=features.file(backend,sid,query)
                elif action=='catalog':result=features.catalog(backend,sid,query.get('section',['all'])[0])
                elif backend=='codex':result=features.agents(sid)
                else:result={'agents':[]}
                return self.output(200,result)
            if write != bool(action):raise APIError(405,'请求方法不支持')
            if backend in ('claude','deepseek') and action=='send':
                # Resolve staged image IDs only after authentication and session scoping.
                body={**body,'resolvedImages':features.images(backend,sid,body.get('images',[]))}
            result=self.server.dispatch(backend,sid,action,body)
            if sid and not action:
                query=parse_qs(urlsplit(self.path).query)
                result=message_window(result,query.get('before',[None])[0],query.get('limit',[60])[0])
                result['queue']=self.server.queue.listing(backend,sid)
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
