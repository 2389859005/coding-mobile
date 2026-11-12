"""Independent Cloudflare Quick Tunnel, never requires Tailscale."""
import hashlib
import json
import logging
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import urllib.request

class TemporaryLink:
    def __init__(self,port,directory,assets,allow_origin):
        self.port=port;self.directory=Path(directory);self.assets=Path(assets);self.allow_origin=allow_origin;self.process=None
        self.lifecycle_lock=threading.Lock();self.stopped=threading.Event()
    def verified_binary(self):
        """Return the tunnel binary only after its bytes match the recorded digest.

        This process is handed the loopback API URL, so it is the component that
        decides what the public internet can reach. Tailnet is checked before use;
        leaving the public-facing binary unchecked made the weaker of the two the
        only one an attacker had to replace.
        """
        binary=self.assets/'bin/cloudflared.exe'
        if not binary.is_file():raise RuntimeError('临时连接组件缺失，请重新安装电脑助手')
        manifest=self.assets/'bin/cloudflared.sha256'
        if not manifest.is_file():
            # Development checkouts run from a source tree that has no packaged
            # manifest; the installer always ships one.
            logging.warning('Tunnel binary digest manifest missing; skipping verification')
            return binary
        try: expected=json.loads(manifest.read_text('utf-8'))['sha256'].lower()
        except (ValueError,KeyError,OSError):
            raise RuntimeError('临时连接组件校验信息损坏，请重新安装电脑助手')
        digest=hashlib.sha256(binary.read_bytes()).hexdigest()
        if digest!=expected:
            logging.error('Tunnel binary digest mismatch: %s != %s',digest,expected)
            raise RuntimeError('临时连接组件校验失败，请重新安装电脑助手')
        return binary
    def start(self):
        binary=self.verified_binary()
        # A model proxy's default cloudflared config or TUNNEL_* variables must
        # never replace the origin of this independently owned QR tunnel.
        config=self.directory/'temporary-cloudflared.yml'
        config.write_text('{}\n',encoding='utf-8')
        environment={key:value for key,value in os.environ.items() if not key.upper().startswith(('TUNNEL_','CLOUDFLARED_'))}
        events=queue.Queue()
        with self.lifecycle_lock:
            if self.stopped.is_set():raise RuntimeError('临时连接已取消')
            self.process=subprocess.Popen([str(binary),'tunnel','--config',str(config),'--url',f'http://127.0.0.1:{self.port}','--protocol','http2','--no-autoupdate'],env=environment,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        process=self.process
        def read():
            origin=None;registered=False
            with (self.directory/'temporary-link.log').open('a',encoding='utf-8') as log:
                for line in process.stdout:
                    log.write(line);log.flush()
                    match=re.search(r'https://[a-z0-9-]+\.trycloudflare\.com',line)
                    if match:origin=match.group()
                    if 'Registered tunnel connection' in line:registered=True
                    if origin and registered:events.put(origin);origin=None
            events.put(None)
        threading.Thread(target=read,daemon=True).start()
        try:origin=events.get(timeout=55)
        except queue.Empty:self.close();raise RuntimeError('临时连接建立超时，请检查网络并重试')
        if not origin:self.close();raise RuntimeError('临时连接启动失败，请查看 temporary-link.log')
        self.allow_origin(origin)
        try:self.verify_origin(origin)
        except Exception:
            self.close()
            raise
        logging.info('Temporary HTTPS ingress ready; computer identity verified')
        return origin
    def verify_origin(self,origin):
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs):return None
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        def identity(address):
            with opener.open(address+'/api/gateway',timeout=8) as response:
                value=json.loads(response.read(16384))
            if value.get('service')!='coding-remote' or not value.get('computerId'):raise ValueError('扫码入口指向其他服务')
            return value['computerId']
        try:
            expected=identity(f'http://127.0.0.1:{self.port}')
            last=None
            for attempt in range(3):
                try:
                    if identity(origin)!=expected:raise ValueError('扫码入口的电脑身份不匹配')
                    return
                except ValueError:raise
                except Exception as error:last=error
                if attempt<2:time.sleep(1)
            raise last
        except Exception as error:
            raise RuntimeError('临时扫码地址未到达本机助手，请检查网络后重新开启连接') from error
    def close(self):
        with self.lifecycle_lock:
            self.stopped.set();process=self.process
        if process and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
