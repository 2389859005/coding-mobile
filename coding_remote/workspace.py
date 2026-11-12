"""Local workbench storage, terminal jobs, notifications and maintenance."""
import base64
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import uuid
import zipfile
from . import VERSION


class SecretStore:
    """Use the current Windows user's DPAPI; never export secrets to diagnostics."""
    class Blob(ctypes.Structure):
        _fields_ = [('size', ctypes.c_uint32), ('data', ctypes.POINTER(ctypes.c_ubyte))]

    def __init__(self, path):
        self.path = Path(path)

    @classmethod
    def crypt(cls, data, decrypt=False):
        if os.name != 'nt':
            raise RuntimeError('凭据加密需要 Windows 用户数据保护')
        buffer = ctypes.create_string_buffer(data)
        source = cls.Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
        target = cls.Blob()
        api = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
        if not api(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
            raise ctypes.WinError()
        try:
            return ctypes.string_at(target.data, target.size)
        finally:
            ctypes.windll.kernel32.LocalFree(target.data)

    def read(self):
        if not self.path.exists():
            return {}
        return json.loads(self.crypt(self.path.read_bytes(), True))

    def write(self, value):
        temporary = self.path.with_suffix('.tmp')
        temporary.write_bytes(self.crypt(json.dumps(value, ensure_ascii=False).encode()))
        temporary.replace(self.path)


class Workspace:
    def __init__(self, server, directory):
        self.server, self.directory = server, Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.directory/'workbench.sqlite', check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS state(key TEXT PRIMARY KEY,value TEXT,revision INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS notices(id TEXT PRIMARY KEY,backend TEXT,session TEXT,title TEXT,kind TEXT,
          state TEXT,created REAL,unread INTEGER,error TEXT,attempts INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY,fingerprint TEXT,result TEXT);
        ''')
        self.db.commit()
        self.secrets = SecretStore(self.directory/'workbench-secrets.dat')
        self.observed = {}
        self.transitions = {}
        self.processes = {}
        self.closed = False

    def state(self, key, body=None):
        if not isinstance(key, str) or not 0 < len(key) <= 500:
            raise ValueError('状态标识无效')
        with self.lock:
            old = self.db.execute('SELECT value,revision FROM state WHERE key=?', (key,)).fetchone()
            if body is None:
                return {'value': json.loads(old['value']) if old else {}, 'revision': old['revision'] if old else 0}
            revision = old['revision'] if old else 0
            if body.get('revision', revision) != revision:
                return {'status': 'conflict', **self.state(key)}
            value = body.get('value', {})
            text = json.dumps(value, ensure_ascii=False)
            if len(text) > 1024*1024:
                raise ValueError('状态内容过大')
            self.db.execute('INSERT OR REPLACE INTO state VALUES(?,?,?)', (key, text, revision+1))
            self.db.commit()
            if hasattr(self.server,'live'):
                if key.startswith('draft:'):self.server.live.invalidate(topic='session:'+key[6:])
                elif key=='pins':self.server.live.invalidate()
            return {'status': 'saved', 'value': value, 'revision': revision+1}

    def observe(self, snapshot):
        unavailable = set(snapshot.get('unavailable', []))
        for task in snapshot.get('tasks', []):
            backend = task.get('backend', '')
            if backend in unavailable:
                continue
            sid = str(task.get('id', task.get('sessionId', '')))
            if not sid:
                continue
            key = backend+':'+sid
            status = task.get('status', '')
            old = self.observed.get(key)
            if old and status != old[0]:
                self.transitions[key] = self.transitions.get(key, 0) + 1
            signature = hashlib.sha256(json.dumps(task.get('requests', task.get('attention', [])), sort_keys=True).encode()).hexdigest()
            waiting = bool(task.get('requests') or task.get('attention'))
            kind = 'attention' if waiting else 'failed' if old and old[0] not in ('failed','error') and status in ('failed', 'error') else 'completed' if old and old[0] in ('active','running','inProgress','working') and status in ('idle','completed','complete') else ''
            if old and kind and (kind != 'attention' or signature != old[1]):
                identity = hashlib.sha256(json.dumps([key, kind, signature, task.get('turnId', task.get('token', '')), self.transitions.get(key, 0)]).encode()).hexdigest()
                with self.lock:
                    fresh = self.db.execute('INSERT OR IGNORE INTO notices VALUES(?,?,?,?,?,?,?,?,?,?)',
                        (identity, backend, sid, str(task.get('title') or '会话'), kind, 'local', time.time(), 1, '', 0)).rowcount
                    self.db.commit()
                if fresh:
                    # External delivery is opt-in and has a bounded, visible receipt.
                    threading.Thread(target=self.deliver, args=(identity,), daemon=True).start()
            self.observed[key] = (status, signature)

    @staticmethod
    def request_json(url, payload=None, headers=None, method=None, timeout=12):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('localhost','127.0.0.1','::1')):
            raise ValueError('服务地址需要 HTTPS')
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
        request = urllib.request.Request(url, data=data, headers={'Accept':'application/json','User-Agent':'coding-remote/'+VERSION,
            **({'Content-Type':'application/json'} if data is not None else {}), **(headers or {})}, method=method)
        # Do not follow a redirect carrying a private credential to a different host.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs): return None
        with urllib.request.build_opener(NoRedirect).open(request, timeout=timeout) as response:
            blob = response.read(4*1024*1024+1)
            if len(blob)>4*1024*1024: raise ValueError('服务响应过大')
            if not blob: return {}
            try: return json.loads(blob)
            except ValueError: return {'message': blob.decode('utf-8','replace')[:200]}

    def configure_push(self, body):
        if body.get('type') not in ('Webhook','ntfy','Bark','PushPlus'):
            raise ValueError('通知服务不支持')
        url = str(body.get('url') or '').strip()
        if body.get('enabled') and urllib.parse.urlsplit(url).scheme != 'https':
            raise ValueError('通知地址需要 HTTPS')
        with self.lock:
            settings = self.secrets.read()
            previous=settings.get('push',{})
            if not body.get('token') and previous.get('type')==body.get('type') and previous.get('url')==url:body=dict(body,token=previous.get('token',''))
            settings['push'] = {k: body.get(k) for k in ('type','url','token','enabled')}
            self.secrets.write(settings)
        return {'status': 'saved'}

    def push_settings(self):
        with self.lock:config=self.secrets.read().get('push',{})
        return {key:config.get(key) for key in ('type','url','enabled')} | {'tokenConfigured':bool(config.get('token'))}

    def notifications(self, body=None):
        if body:
            action = body.get('action')
            if action == 'read':
                with self.lock:
                    self.db.execute('UPDATE notices SET unread=0'+(' WHERE id=?' if body.get('noticeId') else ''), (body['noticeId'],) if body.get('noticeId') else ())
                    self.db.commit()
            elif action == 'test':
                identity = str(uuid.uuid4())
                with self.lock:
                    self.db.execute('INSERT INTO notices VALUES(?,?,?,?,?,?,?,?,?,?)',
                        (identity,'','','测试通知','test','local',time.time(),1,'',0));self.db.commit()
                self.deliver(identity)
            elif action == 'retry':
                self.deliver(str(body.get('noticeId') or ''))
            else: raise ValueError('通知操作不支持')
        with self.lock:
            return {'items': [dict(row) for row in self.db.execute('SELECT * FROM notices ORDER BY created DESC LIMIT 200')]}

    def deliver(self, identity):
        if self.closed:return
        with self.lock:
            row = self.db.execute('SELECT * FROM notices WHERE id=?', (identity,)).fetchone()
            if not row:return
            config = self.secrets.read().get('push', {})
        if not config.get('enabled'):return
        url, token = config['url'], config.get('token') or ''
        title = row['title'];text = {'attention':'有待处理的审批或问题','failed':'任务需要查看','completed':'任务已完成'}.get(row['kind'],'coding远程测试通知')
        payload = {'title':title, 'body':text, 'backend':row['backend'], 'sessionId':row['session'], 'id':identity}
        headers = {}
        if config['type']=='ntfy':
            payload={'topic':urllib.parse.urlsplit(url).path.strip('/'), 'title':title,'message':text}
            url=urllib.parse.urlunsplit((*urllib.parse.urlsplit(url)[:2], '', '', ''))
            if token:headers['Authorization']='Bearer '+token
        elif config['type']=='Bark':payload={'title':title,'body':text,**({'device_key':token} if token else {})}
        elif config['type']=='PushPlus':payload={'token':token,'title':title,'content':text,'template':'txt'}
        elif token:headers['Authorization']='Bearer '+token
        error=''
        try:
            result=self.request_json(url,payload,headers)
            if isinstance(result,dict) and 'code' in result and result['code'] not in (0,200):raise ValueError('通知服务拒绝投递')
        except Exception as exc:error=type(exc).__name__+': 通知投递失败，可重试'
        with self.lock:
            if not self.closed:
                self.db.execute('UPDATE notices SET state=?,error=?,attempts=attempts+1 WHERE id=?',('failed' if error else 'sent',error,identity));self.db.commit()

    def environments(self):
        with self.lock:
            accounts=self.secrets.read().get('accounts', {})
        return {'accounts':[{'name':name,'url':value['url'],'models':value.get('models',[])} for name,value in accounts.items()],
                'desktopAccounts':'使用已登录的 Claude / ChatGPT / ChatGPT Desktop / DeepSeek 桌面会话',
                'ssh':shutil.which('ssh'), 'wsl':shutil.which('wsl'),
                'cli':{name:shutil.which(name) for name in ('codex','claude','git','powershell')},
                'version':VERSION}

    def account(self, body):
        name=str(body.get('name') or '').strip()
        if not 0<len(name)<100:raise ValueError('请输入连接名称')
        url=str(body.get('url') or '').rstrip('/')
        key=str(body.get('key') or '')
        result=self.request_json(url+'/models',headers={'Authorization':'Bearer '+key} if key else {})
        if not isinstance(result,dict) or not isinstance(result.get('data'),list):raise ValueError('此入口没有返回可用的模型列表')
        models=[str(row.get('id')) for row in result['data'] if isinstance(row,dict) and row.get('id')][:300]
        with self.lock:
            settings=self.secrets.read();settings.setdefault('accounts',{})[name]={'url':url,'key':key,'models':models};self.secrets.write(settings)
        return {'status':'saved','models':models}

    def terminal(self, backend, sid, body):
        row=self.server.features.session_row(backend,sid)
        cwd=Path(row.get('cwd') or '').resolve()
        if not cwd.is_dir():raise ValueError('项目目录不可用')
        rid=str(uuid.UUID(body.get('id','')))
        command=str(body.get('command') or '').strip()
        if not command or len(command)>32000:raise ValueError('请输入有效命令')
        environment=body.get('environment','local')
        host=str(body.get('host') or '')
        if environment not in ('local','wsl','ssh'):raise ValueError('终端环境不支持')
        if environment=='ssh' and not re.fullmatch(r'[A-Za-z0-9_.@:-]{1,200}',host):raise ValueError('SSH 主机无效')
        timeout=min(120,max(1,int(body.get('timeout',30))))
        fingerprint=hashlib.sha256(json.dumps([backend,sid,str(cwd),command,environment,host]).encode()).hexdigest()
        with self.lock:
            old=self.db.execute('SELECT * FROM commands WHERE id=?',(rid,)).fetchone()
            if old:
                if old['fingerprint']!=fingerprint:raise ValueError('请求编号已使用')
                return json.loads(old['result'])
            self.db.execute('INSERT INTO commands VALUES(?,?,?)',(rid,fingerprint,json.dumps({'status':'running','id':rid})));self.db.commit()
        if environment=='wsl':args=['wsl.exe','--cd',str(cwd),'--','sh','-lc',command]
        elif environment=='ssh':
            args=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=5','--',host,command]
        else:args=['powershell.exe','-NoProfile','-NonInteractive','-Command',command] if os.name=='nt' else ['sh','-lc',command]
        process=None
        with tempfile.TemporaryFile() as output:
            try:
                process=subprocess.Popen(args,cwd=cwd,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                with self.lock:self.processes[rid]=process
                try:code=process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:process.kill();process.wait();code=-1
                output.seek(0);blob=output.read(512001)
                result={'id':rid,'status':'completed' if code==0 else 'failed','exitCode':code,'output':blob[:512000].decode('utf-8','replace'),'truncated':len(blob)>512000}
            except Exception as exc:result={'id':rid,'status':'failed','output':str(exc)[:500]}
            finally:
                with self.lock:self.processes.pop(rid,None)
        with self.lock:
            if not self.closed:
                self.db.execute('UPDATE commands SET result=? WHERE id=?',(json.dumps(result),rid));self.db.commit()
        return result

    def updates(self):
        value=self.request_json('https://api.github.com/repos/2389859005/coding-mobile/releases/latest')
        assets=[]
        for row in value.get('assets',[]):
            if row.get('name','').endswith(('.exe','.apk','.txt')):
                assets.append({'name':row['name'],'url':row.get('browser_download_url'),'size':row.get('size'),'sha256':str(row.get('digest') or '').removeprefix('sha256:')})
        return {'current':VERSION,'latest':value.get('tag_name'),'url':value.get('html_url'),'assets':assets,'notes':value.get('body','')[:5000]}

    def diagnostics(self, path):
        state={'version':VERSION,'python':sys.version.split()[0],'time':time.time(),
               'boundDevices':len(self.server.pairing.grouped_devices()),
               'sources':list(self.server.live.snapshots),
               'files':{name:(self.directory/name).stat().st_size for name in ('application.log','temporary-link.log') if (self.directory/name).exists()}}
        state.update(listener={'port':self.server.server_port,'active':self.server.gateway_active},
                     clients={name:bool(shutil.which(name)) for name in ('codex','claude','git','ssh','wsl')},
                     recentErrorTypes=[])
        for name in ('application.log','temporary-link.log'):
            path=self.directory/name
            if path.is_file():
                with path.open('rb') as stream:stream.seek(max(0,path.stat().st_size-256000));tail=stream.read().decode('utf-8','replace')
                state['recentErrorTypes'].extend(re.findall(r'\b[A-Za-z]+(?:Error|Exception)\b',tail)[-30:])
        with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('diagnostics.json',json.dumps(state,ensure_ascii=False,indent=2))
            archive.writestr('README.txt','仅包含服务统计、版本与日志大小。不包含凭据、聊天正文或完整文件路径。')
        return str(path)

    def download_update(self, asset):
        name=str(asset.get('name') or '')
        digest=str(asset.get('sha256') or '')
        url=str(asset.get('url') or '')
        if not re.fullmatch(r'[A-Za-z0-9._-]+\.(exe|apk)',name):raise ValueError('安装包文件名无效')
        if not re.fullmatch(r'[a-fA-F0-9]{64}',digest):raise ValueError('发布未提供 SHA-256，请先核对发布页')
        parsed=urllib.parse.urlsplit(url)
        if parsed.scheme!='https' or parsed.hostname!='github.com':raise ValueError('更新来源无效')
        folder=self.directory/'updates';folder.mkdir(exist_ok=True);target=folder/name;temporary=folder/(name+'.partial')
        expected=int(asset.get('size') or 0);received=0;checksum=hashlib.sha256()
        try:
            with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'coding-remote/'+VERSION}),timeout=30) as response,temporary.open('wb') as stream:
                while True:
                    chunk=response.read(256*1024)
                    if not chunk:break
                    received+=len(chunk)
                    if received>500*1024*1024:raise ValueError('更新包过大')
                    stream.write(chunk);checksum.update(chunk)
            if expected and received!=expected:raise ValueError('安装包下载不完整')
            if checksum.hexdigest().lower()!=digest.lower():raise ValueError('安装包校验失败，已丢弃下载')
            temporary.replace(target)
            target.with_suffix(target.suffix+'.verified.json').write_text(json.dumps({'sha256':checksum.hexdigest(),'size':received}),encoding='utf-8')
        finally:temporary.unlink(missing_ok=True)
        return {'path':str(target),'sha256':checksum.hexdigest(),'size':received,'status':'verified'}

    def prepare_install(self, path, rollback=False):
        current=Path(sys.executable).resolve();folder=(self.directory/'updates').resolve();source=Path(path).resolve()
        if not getattr(sys,'frozen',False) or not current.name.startswith('CodingRemoteAssistant'):
            raise ValueError('请在打包的 Windows 助手中安装更新')
        if not source.is_file() or not source.is_relative_to(folder) or source.suffix.lower()!='.exe':raise ValueError('更新包路径无效')
        if not rollback:
            manifest=source.with_suffix(source.suffix+'.verified.json')
            if not manifest.is_file():raise ValueError('安装包没有校验记录，请重新下载')
            metadata=json.loads(manifest.read_text('utf-8'))
            if source.stat().st_size!=metadata['size'] or hashlib.sha256(source.read_bytes()).hexdigest()!=metadata['sha256']:raise ValueError('安装包已变更，请重新下载')
            backup=folder/'rollback.exe';shutil.copyfile(current,backup)
            (folder/'rollback.json').write_text(json.dumps({'sha256':hashlib.sha256(backup.read_bytes()).hexdigest(),'version':VERSION}),encoding='utf-8')
        else:
            metadata=json.loads((folder/'rollback.json').read_text('utf-8'))
            if hashlib.sha256(source.read_bytes()).hexdigest()!=metadata['sha256']:raise ValueError('回滚包校验失败')
        script=folder/'apply-update.ps1'
        script.write_text('param([int]$Owner,[string]$Source,[string]$Target)\n$ErrorActionPreference="Stop"\nWait-Process -Id $Owner -ErrorAction SilentlyContinue\ntry { Copy-Item -LiteralPath $Source -Destination $Target -Force; Start-Process -FilePath $Target -WindowStyle Hidden } catch { $Backup=Join-Path $PSScriptRoot "rollback.exe"; if ((Test-Path -LiteralPath $Backup) -and $Source -ne $Backup) { Copy-Item -LiteralPath $Backup -Destination $Target -Force; Start-Process -FilePath $Target -WindowStyle Hidden }; $_ | Out-File -LiteralPath (Join-Path $PSScriptRoot "install-error.txt") }\n',encoding='utf-8-sig')
        subprocess.Popen(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(script),'-Owner',str(os.getpid()),'-Source',str(source),'-Target',str(current)],creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        return {'status':'ready'}

    def close(self):
        with self.lock:
            self.closed=True
            for process in self.processes.values():
                try:process.terminate()
                except OSError:pass
            self.db.close()

