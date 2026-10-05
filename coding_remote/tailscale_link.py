"""Private, stable Tailscale ingress. Never enables Funnel or replaces other apps."""
import json
import os
import shutil
import subprocess
import ipaddress
import select
import socket
import socketserver
import threading
from pathlib import Path


class TailscaleLink:
    def __init__(self, port, directory, allow_origin, run=subprocess.run):
        self.port = port
        self.path = Path(directory) / 'tailscale-link.json'
        self.allow_origin = allow_origin
        self.run = run
        self.cli = shutil.which('tailscale') or str(Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'Tailscale/tailscale.exe')
        self.proxy = None

    def command(self, *args):
        result = self.run([self.cli, *args], capture_output=True, text=True, encoding='utf-8',
                          errors='replace', timeout=25, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if result.returncode:
            raise RuntimeError((result.stderr or result.stdout or 'Tailscale 连接失败').strip()[:900])
        return result.stdout

    def start(self):
        state=json.loads(self.command('status','--json'))
        if state.get('BackendState')!='Running':raise RuntimeError('已安装的 Tailscale 尚未连接；可连接 Tailscale，或在设置使用内置网络。临时扫码连接仍可使用。')
        addresses=[a for a in state.get('TailscaleIPs',[]) if ':' not in a and ipaddress.ip_address(a) in ipaddress.ip_network('100.64.0.0/10')]
        if not addresses:raise RuntimeError('未找到 Tailscale 设备地址，请检查登录状态。')
        address=addresses[0]
        try:saved=json.loads(self.path.read_text('utf-8'))
        except (OSError,ValueError):saved={}
        upstream=self.port
        class Proxy(socketserver.BaseRequestHandler):
            def handle(self):
                try:
                    with socket.create_connection(('127.0.0.1',upstream),timeout=10) as remote:
                        self.request.settimeout(120);remote.settimeout(120)
                        while True:
                            ready,_,_=select.select([self.request,remote],[],[],120)
                            if not ready:return
                            for source in ready:
                                data=source.recv(65536)
                                if not data:return
                                (remote if source is self.request else self.request).sendall(data)
                except OSError:pass
        class Server(socketserver.ThreadingTCPServer):
            daemon_threads=True
            allow_reuse_address=False
        ports=[saved['privatePort']] if isinstance(saved.get('privatePort'),int) and 1024<=saved['privatePort']<=65535 else [18787,18788,18789]
        for port in ports:
            if not isinstance(port,int) or not 1024<=port<=65535:continue
            try:self.proxy=Server((address,port),Proxy);break
            except OSError:continue
        if self.proxy is None:raise RuntimeError('Tailscale 地址暂不可用或绑定端口已占用。请退出旧版助手，保持原端口后重试。')
        origin=f'http://{address}:{self.proxy.server_address[1]}'
        self.allow_origin(origin)
        threading.Thread(target=self.proxy.serve_forever,daemon=True).start()
        temp=self.path.with_suffix('.tmp');temp.write_text(json.dumps({'privatePort':self.proxy.server_address[1]}),'utf-8');temp.replace(self.path)
        return origin

    def close(self):
        if self.proxy:
            self.proxy.shutdown();self.proxy.server_close();self.proxy=None
