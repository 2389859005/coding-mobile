"""Own userspace Tailscale process, with state separate from any installed VPN."""
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.request
from pathlib import Path

class EmbeddedNetwork:
    def __init__(self,assets,directory,upstream,computer_id,allow_origin=None):
        root=Path(directory).resolve()
        self.source=Path(assets)/'bin/tailnet.exe';self.binary=root/'bin/tailnet.exe'
        self.directory=root/'tailnet';self.directory.mkdir(parents=True,exist_ok=True)
        self.upstream=upstream;self.hostname='coding-computer-'+computer_id[:8];self.process=None;self.port=None;self.secret=None;self.lock=threading.Lock()
        self.allow_origin=allow_origin
    def prepare_binary(self):
        # Windows firewall application rules use the executable path. One-file
        # bundles extract to a new _MEI directory on every launch, so run this
        # listener from a stable per-user path, including across upgrades.
        if not self.source.is_file():raise ValueError('安装包缺少内置网络组件')
        expected=hashlib.sha256(self.source.read_bytes()).digest()
        if self.binary.is_file() and hashlib.sha256(self.binary.read_bytes()).digest()==expected:return
        self.binary.parent.mkdir(parents=True,exist_ok=True)
        staged=None
        try:
            with tempfile.NamedTemporaryFile(prefix='tailnet-',suffix='.tmp',dir=self.binary.parent,delete=False) as out:
                staged=Path(out.name)
            shutil.copyfile(self.source,staged)
            if hashlib.sha256(staged.read_bytes()).digest()!=expected:
                raise ValueError('内置网络组件校验失败，请重新启动助手')
            os.replace(staged,self.binary)
        except PermissionError as error:
            raise ValueError('无法更新内置网络组件，请正常退出其他助手实例后重试') from error
        finally:
            if staged is not None:staged.unlink(missing_ok=True)
    def start(self):
        with self.lock:
            if self.process and self.process.poll() is None:return
            self.prepare_binary()
            self.secret=secrets.token_urlsafe(40);credential=self.directory/'local-secret';credential.write_text(self.secret,'utf-8')
            with (self.directory/'network.log').open('ab') as log:
                self.process=subprocess.Popen([str(self.binary),'--state',str(self.directory),'--hostname',self.hostname,'--upstream',f'http://127.0.0.1:{self.upstream}','--secret-file',str(credential)],stdout=subprocess.PIPE,stderr=log,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            line=self.process.stdout.readline()
            if not line:raise ValueError('内置网络启动失败，请查看 tailnet/network.log')
            self.port=json.loads(line)['port']
    def has_identity(self):
        return (self.directory/'tailscaled.state').is_file()
    def request(self,path,post=False):
        self.start();request=urllib.request.Request(f'http://127.0.0.1:{self.port}'+path,data=b'' if post else None,headers={'Authorization':'Bearer '+self.secret})
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request,timeout=8) as response:return json.load(response)
    def status(self):
        state=self.request('/status')
        if self.allow_origin and state.get('state')=='Running' and state.get('origin'):
            self.allow_origin(state['origin'])
        return state
    def login(self):
        # NoState can last while the control server is unreachable. Do not reset
        # a saved identity or hide an auth URL already returned in that state.
        state=self.status()
        if state.get('state')=='NeedsLogin' and not state.get('authUrl'):
            now=time.monotonic()
            if now-getattr(self,'last_login',-30)>=15:
                self.last_login=now;self.request('/login',True)
        return state

    @staticmethod
    def login_url(state):
        from urllib.parse import urlsplit
        url=state.get('authUrl','')
        try:
            parsed=urlsplit(url)
            return url if state.get('state')!='Running' and parsed.scheme=='https' and parsed.hostname=='login.tailscale.com' and parsed.port in (None,443) and not parsed.username and not parsed.password else ''
        except ValueError:return ''

    @staticmethod
    def describe(state):
        return {'NoState':'正在联系设备网络，连接较慢时会继续重试',
                'Starting':'正在建立设备连接', 'Running':'已连接设备网络',
                'NeedsLogin':'请在浏览器完成设备网络登录',
                'NeedsMachineAuth':'等待管理员批准此设备', 'Stopped':'设备网络已停止，请重新启动助手'}.get(state.get('state'),'正在读取设备网络状态')
    def close(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:self.process.kill()

class EmbeddedLink:
    def __init__(self,network,allow_origin):self.network=network;self.allow_origin=allow_origin
    def start(self):
        status=self.network.status();origin=status.get('origin')
        if not origin:raise ValueError('请登录设备网络' if status.get('state')=='NeedsLogin' else '设备网络正在连接')
        self.allow_origin(origin);return origin
    # The desktop owns the node for its entire lifetime. Route retries must not
    # kill it while it is loading saved keys or waiting for browser authorization.
    def close(self):pass
