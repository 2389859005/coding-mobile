"""Shared bounded live subscriptions for desktop, Android and task monitoring."""
import hashlib
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor

BACKENDS = ('claude', 'codex', 'deepseek')


class Subscription:
    def __init__(self, hub, topics):
        self.hub, self.topics = hub, frozenset(topics)
        self.pending = {}
        self.condition = threading.Condition()
        self.closed = False

    def push(self, event):
        with self.condition:
            if self.closed or event['topic'] not in self.topics:
                return
            # A slow reader keeps the latest snapshot of each topic, never an
            # unbounded transcript queue.
            # A checkpoint cannot replace the payload a slow client has not read yet.
            previous = self.pending.get(event['topic'])
            if event.get('type') == 'checkpoint' and previous and 'data' in previous:
                return
            self.pending[event['topic']] = event
            self.condition.notify()

    def read(self, timeout=12):
        with self.condition:
            if not self.pending and not self.closed:
                self.condition.wait(timeout)
            if not self.pending:
                return None
            key = next(iter(self.pending))
            return self.pending.pop(key)

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()
        self.hub.unsubscribe(self)


class LiveHub:
    def __init__(self, server, interval=.25):
        self.server, self.interval = server, interval
        self.lock = threading.RLock()
        self.wake = threading.Condition(self.lock)
        self.sources = None
        self.subscribers = set()
        self.snapshots, self.inflight, self.due = {}, set(), {}
        self.sequence = 0
        self.invalidations = {}
        self.stop = threading.Event()
        self.pool = ThreadPoolExecutor(max_workers=6, thread_name_prefix='live-source')
        self.session_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix='live-session')
        self.thread = None

    @staticmethod
    def topics(backends=BACKENDS, sessions=()):
        topics = {'status','notifications'}
        for backend in backends:
            if backend not in BACKENDS:
                raise ValueError('不支持的客户端')
            topics.add('list:' + backend)
        for value in sessions:
            backend, sep, sid = value.partition(':')
            if backend not in BACKENDS or not sep or not sid or len(sid) > 200 or '/' in sid:
                raise ValueError('会话订阅无效')
            topics.add('session:' + backend + ':' + sid)
        if len(topics) > 32:
            raise ValueError('同时订阅的会话过多')
        return topics

    def subscribe(self, topics):
        sub = Subscription(self, topics)
        with self.lock:
            if self.stop.is_set():
                raise RuntimeError('实时服务已停止')
            existing=set().union(*(s.topics for s in self.subscribers)) if self.subscribers else set()
            self.subscribers.add(sub)
            for topic in topics:
                if topic in self.snapshots:
                    sub.push(self.snapshots[topic])
            if self.thread is None:
                self.start_sources()
                self.thread = threading.Thread(target=self.run, daemon=True, name='live-bus')
                self.thread.start()
            for topic in topics:
                # Rejoining after a disconnect must collect a current snapshot.
                if topic not in existing or topic not in self.snapshots:
                    self.invalidations[topic]=self.invalidations.get(topic,0)+1
                    self.due[topic]=0
            self.wake.notify_all()
        return sub

    def start_sources(self):
        with self.lock:
            if self.sources is None:
                from .source_events import SourceEvents
                self.sources=SourceEvents(self.server)
                self.sources.start()

    def unsubscribe(self, sub):
        with self.lock:
            self.subscribers.discard(sub)
            self.wake.notify_all()

    def invalidate(self, backend=None, topic=None):
        if backend:
            signal=getattr(getattr(self.server,'queue',None),'wake',None)
            if signal:signal.set()
        features=getattr(self.server,'features',None)
        if backend and callable(getattr(features,'source_changed',None)):features.source_changed(backend)
        with self.lock:
            topics=set(self.snapshots).union(*(sub.topics for sub in self.subscribers))
            for key in topics:
                if topic is not None and key!=topic:continue
                if backend and key not in ('status','apps','list:'+backend) and not key.startswith('session:'+backend+':'):continue
                self.invalidations[key]=self.invalidations.get(key,0)+1
                # Coalesce a burst without postponing its first deadline. Native
                # streams can emit many events per frame; readers need the newest
                # snapshot, not a full provider scan for each token.
                self.due[key]=min(self.due.get(key,float('inf')),time.monotonic()+self.interval)
            self.wake.notify_all()

    def publish(self, topic, data):
        revision = hashlib.sha256(json.dumps(data, ensure_ascii=False, sort_keys=True, default=str).encode()).hexdigest()
        with self.lock:
            old = self.snapshots.get(topic)
            if old and old['revision'] == revision:return
            self.sequence += 1
            event = {'topic': topic, 'revision': revision, 'sequence': self.sequence,
                     'time': time.time(), 'data': data}
            self.snapshots[topic] = event
            subscribers = tuple(self.subscribers)
        for sub in subscribers:
            sub.push(event)

    def run(self):
        while not self.stop.is_set():
            with self.lock:
                topics = set().union(*(sub.topics for sub in self.subscribers)) if self.subscribers else set()
                # Purge old transcripts after the last consumer releases them.
                for key in set(self.snapshots) - topics:
                    self.snapshots.pop(key, None)
                    self.due.pop(key, None)
                now = time.monotonic()
                ready = [t for t in topics if t not in self.inflight and now >= self.due.get(t, 0)]
                if not ready:
                    deadlines=[self.due.get(t,0) for t in topics if t not in self.inflight]
                    next_due=min(deadlines,default=float('inf'))
                    self.wake.wait(None if next_due==float('inf') else max(.01,next_due-now))
                    continue
                self.inflight.update(ready)
            for topic in ready:
                if self.stop.is_set():
                    break
                try:(self.session_pool if topic.startswith('session:') else self.pool).submit(self.refresh, topic)
                except RuntimeError:
                    if self.stop.is_set():break
                    raise

    def refresh(self, topic):
        delay = float('inf')
        started=time.monotonic()
        with self.lock:generation=self.invalidations.get(topic,0)
        try:
            if topic == 'apps':
                value=self.server.apps.snapshot()

            elif topic == 'status':
                value = self.server.features.task_status()

                if hasattr(self.server,'workspace'):
                    self.server.workspace.observe(value)
                    self.publish('notifications',self.server.workspace.notifications())
            elif topic == 'notifications':
                if not hasattr(self.server,'workspace'):return
                value=self.server.workspace.notifications()

            elif topic.startswith('list:'):
                backend = topic.split(':', 1)[1]
                value = self.server.features.list_snapshot(backend)
                pins=self.server.workspace.state('pins')['value'] if hasattr(self.server,'workspace') else {}
                value = dict(value, sessions=[dict(row, backend=backend,
                    pinned=bool(pins.get(backend+':'+str(row.get('id')),row.get('pinned',False))),
                    source=row.get('source') or backend,
                    sessionId=row.get('id'), identity=backend+':'+str(row.get('id')))
                    for row in value.get('sessions', [])])
            else:
                _, backend, sid = topic.split(':', 2)
                # Source invalidation always reads content, even when metadata is unchanged.
                token = self.server.features.stream_token(backend, sid)
                draft=self.server.workspace.state('draft:'+backend+':'+sid) if hasattr(self.server,'workspace') else {'value':{},'revision':0}
                token=token+':'+str(draft['revision'])
                from .api import message_window
                value = message_window(self.server.dispatch(backend, sid, None, {}), limit=60)
                value.update(token=token.partition(':')[0], queue=self.server.queue.listing(backend, sid),draft=draft)
            self.publish(topic, value)
        except Exception as exc:
            delay = 2
            self.publish(topic, {'error': str(exc)[:200], 'connected': False})
        finally:
            with self.lock:
                self.inflight.discard(topic)
                self.due[topic] = max(time.monotonic(),started+self.interval) if generation!=self.invalidations.get(topic,0) else time.monotonic() + delay
                self.wake.notify_all()

    def close(self):
        self.stop.set()
        if self.sources:self.sources.close()
        with self.lock:self.wake.notify_all()
        with self.lock:
            subscribers = tuple(self.subscribers)
        for sub in subscribers:
            sub.close()
        self.pool.shutdown(wait=False, cancel_futures=True)
        self.session_pool.shutdown(wait=False, cancel_futures=True)

