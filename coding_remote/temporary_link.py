"""Independent Cloudflare Quick Tunnel, never requires Tailscale."""
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
    def start(self):
        binary=self.assets/'bin/cloudflared.exe'
        if not binary.is_file():raise RuntimeError('临时连接组件缺失，请重新安装电脑助手')
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
