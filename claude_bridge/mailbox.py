"""Plain JSON, single-slot local IPC using Desktop's workspace file APIs.

The directory is private local runtime data, not a network share. Each server
start has a fresh generation; requests expire and are never retransmitted.
"""
import asyncio
import json
import secrets
import time
import threading
from pathlib import Path

from .errors import BridgeUnavailable

MAX_PACKET = 32 * 1024 * 1024


class FileDesktop:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()
        self.generation = secrets.token_hex(16)
        self.seq = 0
        self.lock = asyncio.Lock()
        self.cached = {}
        self.stamp = None
        self.message = ''
        # A validated workspace session avoids getAll(), which checks every
        # historical folder (including unavailable network drives) on cold open.
        self.session_path = self.directory / 'bridge-session.json'
        try:self.bridge_session = json.loads(self.session_path.read_text('utf-8')).get('sessionId', '')
        except (OSError, ValueError, AttributeError):self.bridge_session = ''
        self.write({'generation': self.generation, 'seq': 0, 'type': 'idle'})
        (self.directory / 'response.json').write_text('{}', encoding='utf-8')

    def pack(self, value):
        return json.dumps(value, ensure_ascii=False, separators=(',', ':'))

    def write(self, value):
        target = self.directory / 'request.json'
        temp = self.directory / 'request.tmp'
        temp.write_text(self.pack(value), encoding='utf-8')
        for attempt in range(40):
            try:
                temp.replace(target)
                return
            except PermissionError:
                # Windows can briefly deny rename while Electron reads the file.
                if attempt == 39:
                    raise
                time.sleep(.025)

    def read(self):
        path = self.directory / 'response.json'
        try:
            stat = path.stat()
            stamp = (stat.st_mtime_ns, stat.st_size)
            if stamp == self.stamp:
                return self.cached
            if stat.st_size > MAX_PACKET:
                return {}
            value = json.loads(path.read_text(encoding='utf-8'))
            if not isinstance(value, dict) or value.get('generation') != self.generation:
                return {}
            self.cached, self.stamp = value, stamp
            session_id = value.get('bridgeSessionId')
            if isinstance(session_id,str) and session_id.startswith('local_') and len(session_id)<100 and session_id!=self.bridge_session:
                self.bridge_session=session_id
                try:self.session_path.write_text(json.dumps({'sessionId':session_id}),encoding='utf-8')
                except OSError:pass
            return value
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            # Desktop writes are not atomic; partial packets must never dispatch.
            return self.cached

    @property
    def connected(self):
        value = self.read()
        timestamp = value.get('timestamp', 0)
        return (isinstance(timestamp, (int, float)) and 0 <= time.time() - timestamp < 20
                and value.get('connected') is True)

    @property
    def capabilities(self):
        return self.read().get('surfaces', {}) if self.connected else {}

    def disconnected(self, message):
        self.message = message

    async def call(self, surface, method, *args, timeout=30):
        deadline = time.monotonic() + timeout
        try:await asyncio.wait_for(self.lock.acquire(), timeout=timeout)
        except asyncio.TimeoutError as exc:raise BridgeUnavailable("Claude 正忙，请稍后刷新选项") from exc
        try:
            if not self.connected or method not in self.capabilities.get(surface, []):
                raise BridgeUnavailable('Claude Desktop 文件桥接未连接或不支持此操作')
            self.seq += 1
            seq = self.seq
            try:
                await asyncio.to_thread(self.write,
                    {'type': 'request', 'generation': self.generation, 'seq': seq,
                     'expires': time.time() + timeout, 'surface': surface,
                     'method': method, 'args': list(args)})
            except OSError as exc:
                raise BridgeUnavailable('无法写入本地桥接文件，请检查文件占用和目录权限') from exc
            while time.monotonic() < deadline:
                reply = self.read()
                if reply.get('seq') == seq and reply.get('done') is True:
                    if 'error' in reply:
                        error = str(reply['error'])
                        raise BridgeUnavailable({'1': '桌面操作超时；请核对原会话，不要重复提交',
                                                 '2': '请求已过期，未执行'}.get(error, error))
                    return reply.get('result')
                if not self.connected:
                    raise BridgeUnavailable('Claude Desktop 文件桥接已断开；操作结果可能不确定')
                await asyncio.sleep(.04)
            raise BridgeUnavailable('Claude Desktop 响应超时；请检查原会话，不要重复提交')

        finally:self.lock.release()

    def close(self):
        if hasattr(self,'event_feed'):self.event_feed.close()
        self.write({'generation': self.generation, 'seq': self.seq + 1, 'type': 'stop'})

    def start_events(self,changed):
        from coding_remote.change_feed import ChangeFeed
        self.event_revision=None
        retry_lock=threading.Lock()
        retry=None
        def update():
            value=self.read()
            revision=(value.get('generation'),value.get('eventRevision',0),value.get('connected'))
            if revision!=self.event_revision:
                self.event_revision=revision;changed()
        def after_write():
            nonlocal retry
            with retry_lock:retry=None
            stop=getattr(self.event_feed,"stop",None)
            if stop is None or not stop.is_set():update()
        def notified():
            nonlocal retry
            update()
            # A file notification can arrive between truncation and completion.
            # Coalesce one follow-up read of this local packet, never a network poll.
            with retry_lock:
                if retry is None:
                    retry=threading.Timer(.08,after_write);retry.daemon=True;retry.start()
        self.event_feed=ChangeFeed(self.directory,notified,paths=[(self.directory,False)])
        self.event_feed.start();update()
        return self.event_feed
