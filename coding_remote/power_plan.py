"""One-shot shutdown after observed work ends; driven by the shared status feed."""
import threading


class PowerPlan:
    ACTIVE = {'active', 'running', 'working', 'inProgress'}

    def __init__(self, server):
        self.server = server
        self.lock = threading.RLock()
        self.enabled = False
        self.watched = set()
        self.subscription = None
        self.message = ''

    def snapshot(self):
        with self.lock:
            return {'shutdownWhenIdle': self.enabled, 'powerMessage': self.message}

    def set_enabled(self, enabled):
        with self.lock:
            if self.enabled == enabled:
                return {'status': 'accepted', **self.snapshot()}
            self.enabled = enabled
            self.watched.clear()
            if self.subscription:
                self.subscription.close()
                self.subscription = None
            self.message = '已开启，任务结束后将在 60 秒后关机' if enabled else '已关闭任务结束自动关机'
            if enabled:
                self.subscription = self.server.live.subscribe({'status'})
                threading.Thread(target=self.run, args=(self.subscription,), daemon=True, name='power-plan').start()
            return {'status': 'accepted', 'message': self.message, **self.snapshot()}

    def observe(self, status):
        with self.lock:
            if not self.enabled or status.get('error'):
                return False
            tasks = {(t['backend'], t['id']): t for t in status.get('tasks', [])}
            active = {key for key, task in tasks.items() if task.get('status') in self.ACTIVE or task.get('attention') or task.get('requests')}
            self.watched.update(active)
            if not self.watched or active:
                return False
            unavailable = set(status.get('unavailable', []))
            if any(key not in tasks or key[0] in unavailable for key in self.watched):
                return False
            # Pending/uncertain writes are work too, even between two queued turns.
            with self.server.queue.lock:
                if self.server.queue.db.execute("SELECT 1 FROM queue WHERE state IN ('waiting','sending','unknown') LIMIT 1").fetchone():
                    return False
            self.enabled = False
            try:
                self.message = self.server.apps.power('shutdown', True)['message']
            except Exception as exc:
                self.message = '自动关机失败：' + str(exc)[:160]
            return True

    def run(self, sub):
        try:
            while not sub.closed:
                event = sub.read(1)
                with self.lock:
                    if sub is not self.subscription or not self.enabled:
                        break
                    if event and self.observe(event['data']):
                        break
        finally:
            sub.close()

    def close(self):
        self.set_enabled(False)
