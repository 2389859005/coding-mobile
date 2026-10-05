"""Authenticated local renderer transport; no project or mailbox session required."""
import asyncio
import json
import secrets
import threading
import time

from coding_remote.cdp import CDP
from .errors import BridgeUnavailable


class CdpDesktop:
    def __init__(self, token):
        self.token = token
        self.generation = secrets.token_hex(16)
        self.url = None
        self.surfaces = {}
        self.last_seen = 0
        self.message = 'Claude 未连接，点击页面标题后选择启动'
        self.lock = threading.Lock()
        self.seq = 0

    @property
    def connected(self):
        return self.url is not None and time.monotonic() - self.last_seen < 20

    @property
    def capabilities(self):
        return self.surfaces if self.connected else {}

    def attach(self, url, surfaces):
        self.url, self.surfaces, self.last_seen = url, surfaces, time.monotonic()
        self.message = ''

    def disconnected(self, message):
        self.url = None
        self.message = message

    async def call(self, surface, method, *args, timeout=30):
        return await asyncio.to_thread(self._call, surface, method, args, timeout)

    def _call(self, surface, method, args, timeout):
        with self.lock:
            if not self.connected or method not in self.capabilities.get(surface, []):
                raise BridgeUnavailable(self.message or '当前 Claude 不支持此操作')
            self.seq += 1
            packet = {'generation': self.generation, 'seq': self.seq,
                      'expires': time.time() + timeout, 'surface': surface,
                      'method': method, 'args': list(args), 'token': self.token}
            client = None
            try:
                client = CDP(self.url)
                client.socket.settimeout(timeout)
                result = client.call('Runtime.evaluate', {
                    'expression': 'window.__claudeMobileBridge.invoke(' + json.dumps(packet) + ')',
                    'returnByValue': True, 'awaitPromise': True,
                }).get('result', {}).get('value')
                if not isinstance(result, dict):
                    raise BridgeUnavailable('Claude 连接已重载，请稍后重试')
                if 'error' in result:
                    raise BridgeUnavailable(str(result['error']))
                return result.get('result')
            except BridgeUnavailable:
                raise
            except Exception as exc:
                raise BridgeUnavailable('Claude 连接中断，操作结果待核对') from exc
            finally:
                if client is not None:
                    client.close()

    def close(self):
        self.disconnected('助手已关闭')
