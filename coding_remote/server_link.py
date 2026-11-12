"""A fixed HTTPS entrance backed by a supervised reverse SSH tunnel."""
import json
import re
import threading
import time
import urllib.request

from .gateway_ingress import normalize_origin
from .ingress_ssh import SSHTunnel


def validate_config(value):
    if not isinstance(value, dict):
        raise ValueError('请先在应用设置中配置SSH连接')
    origin = normalize_origin(value.get('origin', ''))
    target = str(value.get('sshTarget', '')).strip()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@:-]{0,199}', target):
        raise ValueError('请填写已配置密钥的 SSH 主机别名或 user@host')
    try:
        port = int(value.get('remotePort', 18787))
    except (TypeError, ValueError):
        raise ValueError('远端转发端口需要在 1024–65535 之间') from None
    if not 1024 <= port <= 65535:
        raise ValueError('远端转发端口需要在 1024–65535 之间')
    return {'origin': origin, 'sshTarget': target, 'remotePort': port}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class ServerLink:
    def __init__(self, server, config, directory, startup_timeout=30):
        self.server = server
        self.config = validate_config(config)
        self.origin = self.config['origin']
        self.tunnel = SSHTunnel(self.config['sshTarget'], self.config['remotePort'],
                                server.server_port, directory, 'server')
        self.stopped = threading.Event()
        self.lock = threading.Lock()
        self.healthy = False
        self.message = '正在连接SSH连接…'
        self.thread = None
        self.startup_timeout = startup_timeout
        self.opener = urllib.request.build_opener(NoRedirect)

    def check(self):
        request = urllib.request.Request(self.origin + '/api/gateway',
                                         headers={'Accept': 'application/json'})
        with self.opener.open(request, timeout=3) as response:
            value = json.loads(response.read(16001))
        if (not isinstance(value, dict) or value.get('service') != 'coding-remote'
                or value.get('computerId') != self.server.pairing.computer_id):
            raise ValueError('HTTPS 入口未指向当前电脑，请检查服务器转发端口')

    def set_health(self, healthy, message=''):
        with self.lock:
            self.healthy, self.message = healthy, message

    def snapshot(self):
        with self.lock:
            return {'healthy': self.healthy, 'message': self.message}

    def start(self):
        # Register Host before the HTTPS identity probe reaches the local API.
        self.server.allow_origin(self.origin)
        self.tunnel.start()
        deadline = time.monotonic() + self.startup_timeout
        try:
            while not self.stopped.is_set():
                try:
                    if self.tunnel.state != 'connected':
                        raise OSError('SSH reverse forwarding is not ready')
                    self.check()
                except (OSError, ValueError) as error:
                    if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError):
                        raise
                    if time.monotonic() >= deadline:
                        raise ValueError('入口检测失败：请检查 HTTPS 证书、80/443 端口和 SSH 配置；详情见 ssh-tunnel-server.log') from None
                    self.stopped.wait(.8)
                    continue
                if self.stopped.is_set():
                    break
                self.server.pairing.remember_server_origin(self.origin)
                self.set_health(True)
                self.thread = threading.Thread(target=self._watch, daemon=True, name='server-entrance')
                self.thread.start()
                return self.origin
            raise ValueError('SSH连接连接已取消')
        except Exception:
            self.close()
            raise

    def _watch(self):
        while not self.stopped.wait(10):
            try:
                if self.tunnel.state != 'connected':
                    raise OSError('SSH reverse forwarding is not ready')
                self.check()
                self.set_health(True)
            except ValueError:
                self.set_health(False, 'HTTPS 入口未指向当前电脑，请检查转发配置')
            except OSError:
                self.set_health(False, 'SSH连接入口暂不可达，正在自动重连')

    def close(self):
        self.stopped.set()
        self.set_health(False, 'SSH连接连接已关闭')
        self.tunnel.close()
        if self.thread and self.thread is not threading.current_thread():
            self.thread.join(timeout=4)
