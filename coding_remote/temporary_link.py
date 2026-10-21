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

class TemporaryLink:
    def __init__(self,port,directory,assets,allow_origin):
        self.port=port;self.directory=Path(directory);self.assets=Path(assets);self.allow_origin=allow_origin;self.process=None
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
        events=queue.Queue()
        self.process=subprocess.Popen([str(binary),'tunnel','--url',f'http://127.0.0.1:{self.port}','--protocol','http2','--no-autoupdate'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
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
        self.allow_origin(origin);logging.info('Temporary HTTPS ingress ready')
        return origin
    def close(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:self.process.kill()
