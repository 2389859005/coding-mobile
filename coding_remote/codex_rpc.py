"""Short lived, narrowly scoped Codex metadata requests; never starts a turn."""
import json
import os
import queue
import subprocess
import threading
import time
from pathlib import Path

from . import VERSION


class CodexRPC:
    METHODS = {'initialize', 'plugin/installed', 'collaborationMode/list',
               'thread/goal/get', 'thread/goal/set', 'thread/goal/clear'}

    def __init__(self, home, executable=None, timeout=8):
        self.home, self.executable, self.timeout = Path(home), executable, timeout

    def __enter__(self):
        executable = self.executable
        if not executable:
            portable = self.home / 'plugins/.plugin-appserver/codex.exe'
            if portable.is_file(): executable = portable
            else:
                from bridge.catalog import Catalog
                executable = Catalog.find_runtime()
        if not executable: raise ValueError('找不到 Codex 运行时')
        self.process = subprocess.Popen([str(executable), 'app-server', '--listen', 'stdio://'],
            cwd=self.home, env=dict(os.environ, CODEX_HOME=str(self.home), CODEX_INTERNAL_ORIGINATOR_OVERRIDE='Codex Desktop'),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        self.messages, self.counter = queue.Queue(), 0
        def read():
            try:
                for line in self.process.stdout:
                    try: self.messages.put(json.loads(line))
                    except ValueError: pass
            finally: self.messages.put(None)
        self.reader = threading.Thread(target=read, daemon=True); self.reader.start()
        try:
            self.request('initialize', {'clientInfo': {'name': 'coding_remote_metadata', 'version': VERSION}, 'capabilities': {'experimentalApi': True}})
            self.process.stdin.write('{"method":"initialized"}\n'); self.process.stdin.flush()
        except Exception:
            self.__exit__(); raise
        return self

    def request(self, method, params):
        if method not in self.METHODS: raise ValueError('不支持的元数据操作')
        self.counter += 1
        self.process.stdin.write(json.dumps({'id': self.counter, 'method': method, 'params': params})+'\n'); self.process.stdin.flush()
        deadline = time.monotonic()+self.timeout
        while time.monotonic() < deadline:
            try: value = self.messages.get(timeout=max(.01, deadline-time.monotonic()))
            except queue.Empty: break
            if value is None: raise RuntimeError('Codex 元数据连接已断开，请核对结果')
            if value.get('id') != self.counter: continue
            if 'error' in value: raise ValueError(value['error'].get('message', 'Codex 未接受操作'))
            return value['result']
        raise TimeoutError('Codex 元数据响应超时，请核对结果')

    def __exit__(self, *args):
        self.process.stdin.close()
        try: self.process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try: self.process.wait(timeout=2)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
        self.reader.join(timeout=1); self.process.stdout.close()
