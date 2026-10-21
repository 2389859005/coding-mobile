"""Persistent per-session queue. Dispatch once; uncertain outcomes never replay."""
import hashlib
import json
import sqlite3
import threading
import time
import uuid
from pathlib import Path


class TaskQueue:
    def __init__(self, server, directory):
        self.server = server
        self.lock = threading.RLock()
        # Serialises "pick the next entry and dispatch it" as one step. Without it
        # request(sendnow) and tick() could each move a different head to 'sending'
        # and dispatch both, since the row check and the dispatch were not atomic.
        # Kept separate from self.lock so a slow send does not block polling.
        self.dispatch_lock = threading.RLock()
        self.stop = threading.Event()
        self.worker = None
        self.path = Path(directory)/'task-queue.sqlite'
        self.connection = None
        self.connections_lock = threading.Lock()
        self.closed = False
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS queue (id TEXT PRIMARY KEY, backend TEXT, sid TEXT,
            body TEXT, state TEXT, created REAL, message TEXT DEFAULT '');
          CREATE TABLE IF NOT EXISTS gates (backend TEXT, sid TEXT, signature TEXT,
            active INTEGER DEFAULT 0, PRIMARY KEY(backend,sid));
          CREATE TABLE IF NOT EXISTS queue_actions (id TEXT PRIMARY KEY, fingerprint TEXT, result TEXT);
        ''')
        self.db.execute("UPDATE queue SET state='unknown', message='助手在提交期间重启，请核对原会话' WHERE state='sending'")
        self.db.commit()

    @property
    def db(self):
        """The shared connection, created on first use.

        A single connection is kept deliberately: every write below happens inside
        `with self.lock`, which serialises the read-modify-commit sequences that
        would otherwise interleave. Per-thread connections were tried and dropped,
        because the extra handles outlive close() on Windows and keep the database
        path locked.
        """
        with self.connections_lock:
            if self.connection is None:
                self.connection = sqlite3.connect(self.path, check_same_thread=False, timeout=15)
                self.connection.row_factory = sqlite3.Row
                self.connection.execute('PRAGMA busy_timeout=15000')
            return self.connection

    def start(self):
        if self.worker is None:
            self.worker = threading.Thread(target=self._run, daemon=True, name='task-queue')
            self.worker.start()

    def _run(self):
        while not self.stop.wait(2):
            try:
                self.tick()
            except Exception:
                import logging
                logging.exception('Queue worker failed')

    def listing(self, backend, sid):
        with self.lock:
            # rowid is the insertion order. created alone is not enough: entries
            # queued in the same millisecond fell back to ordering by id, which is
            # a random uuid, so the head of the queue could change between reads.
            return [{**dict(row), 'body': json.loads(row['body'])} for row in self.db.execute(
                "SELECT * FROM queue WHERE backend=? AND sid=? AND state NOT IN ('sent','removed','recalled') ORDER BY rowid",
                (backend, sid))]

    def request(self, backend, sid, body):
        rid = str(uuid.UUID(body.get('id', '')))
        fingerprint = hashlib.sha256(json.dumps([backend,sid,body],sort_keys=True).encode()).hexdigest()
        with self.lock:
            old = self.db.execute('SELECT * FROM queue_actions WHERE id=?',(rid,)).fetchone()
            if old:
                if old['fingerprint'] != fingerprint:
                    raise ValueError('队列请求 ID 已用于其他操作')
                return json.loads(old['result'])
            operation = body.get('operation')
            if operation == 'enqueue':
                message = body.get('message')
                if not isinstance(message,dict) or not isinstance(message.get('text'),str) or (not message['text'].strip() and not message.get('images')):
                    raise ValueError('排队消息不能为空')
                if len(message['text']) > 100000:
                    raise ValueError('消息过长')
                message = {k:message[k] for k in ('text','images','plugins','attachments') if k in message}
                self.db.execute('INSERT INTO queue(id,backend,sid,body,state,created) VALUES (?,?,?,?,?,?)',
                                (rid,backend,sid,json.dumps(message),'waiting',time.time()))
                result = {'status':'queued','queueId':rid}
            elif operation in ('remove','recall','sendnow'):
                qid = str(uuid.UUID(body.get('queueId','')))
                row = self.db.execute('SELECT * FROM queue WHERE id=? AND backend=? AND sid=?',(qid,backend,sid)).fetchone()
                acknowledged = row and row['state']=='unknown' and operation=='remove' and body.get('confirmed') is True
                if not row or (row['state'] not in ('waiting','rejected') and not acknowledged):
                    raise ValueError('消息已开始提交或已移出队列，请刷新')
                state = {'remove':'removed','recall':'recalled','sendnow':'sending'}[operation]
                self.db.execute('UPDATE queue SET state=? WHERE id=?',(state,qid))
                if acknowledged:
                    self.db.execute('DELETE FROM gates WHERE backend=? AND sid=?',(backend,sid))
                result = {'status':'accepted','queueId':qid}
                if operation == 'recall':
                    result['messageBody'] = json.loads(row['body'])
                if operation == 'sendnow':
                    result = {'status':'unknown','queueId':qid}
            else:
                raise ValueError('不支持的队列操作')
            self.db.execute('INSERT INTO queue_actions VALUES (?,?,?)',(rid,fingerprint,json.dumps(result)))
            self.db.commit()
        if operation == 'sendnow':
            # Held across the whole verify-then-dispatch sequence so tick() cannot
            # observe the freed head and send a different entry concurrently.
            with self.dispatch_lock:
                session={}
                try:
                    session = self.server.dispatch(backend,sid,None,{})['session'] or {}
                    with self.lock:
                        self.db.execute('INSERT OR REPLACE INTO gates VALUES (?,?,?,0)',(backend,sid,self.gate_value(session)))
                        self.db.commit()
                except Exception:
                    # Falling through with an empty session downgrades the request from
                    # an interrupting steer to an ordinary send, so it must be visible.
                    import logging
                    logging.exception('sendnow could not read session %s/%s',backend,sid)
                result = self._dispatch(dict(row), 'steer' if session.get('status') in ('active','running','working','inProgress') else 'send')
                with self.lock:
                    self.db.execute('UPDATE queue_actions SET result=? WHERE id=?',(json.dumps(result),rid))
                    self.db.commit()
        return result

    @staticmethod
    def signature(session):
        return json.dumps([session.get('turnId'),session.get('completedTurnId'),session.get('turnCount'),session.get('updatedAt')])

    @staticmethod
    def gate_value(session):
        """Signature plus the dispatch time, used only to bound how long a gate holds."""
        return TaskQueue.signature(session)+'|'+str(time.time())

    @staticmethod
    def gate_signature(entry):
        return str(entry).split('|',1)[0]

    @staticmethod
    def gate_age(entry):
        try:return time.time()-float(str(entry).split('|',1)[1])
        except (IndexError,ValueError,TypeError):return 0

    def _dispatch(self, row, mode):
        body = {**json.loads(row['body']), 'id':row['id'], 'mode':mode}
        try:
            if row['backend'] in ('claude','deepseek'):
                body['resolvedImages'] = self.server.features.images(row['backend'],row['sid'],body.get('images',[]))
            result = self.server.dispatch(row['backend'],row['sid'],'send',body)
            state = 'sent' if result.get('status') in ('accepted','queued','ready','completed') else 'rejected' if result.get('status') == 'rejected' else 'unknown'
        except (ValueError,KeyError) as exc:
            state,result = 'rejected',{'status':'rejected','message':str(exc)}
        except Exception:
            # The outcome is genuinely unknown, so the entry is never replayed, but
            # the stack is the only way to tell a bridge fault from a real timeout.
            import logging
            logging.exception('Queue dispatch failed for %s/%s',row['backend'],row['sid'])
            state,result = 'unknown',{'status':'unknown','message':'提交结果待核对，不会自动重发'}
        with self.lock:
            self.db.execute('UPDATE queue SET state=?,message=? WHERE id=?',(state,result.get('message',''),row['id']))
            if state == 'rejected':
                self.db.execute('DELETE FROM gates WHERE backend=? AND sid=?',(row['backend'],row['sid']))
            self.db.commit()
        return {**result,'queueId':row['id']}

    def tick(self):
        with self.lock:
            groups = list(self.db.execute("SELECT DISTINCT backend,sid FROM queue WHERE state='waiting'"))
        for group in groups:
            if self.stop.is_set():
                return
            backend,sid = group['backend'],group['sid']
            try:
                detail = self.server.dispatch(backend,sid,None,{})
                if detail.get('connected') is False:
                    continue
                session = detail.get('session') or {}
            except Exception:
                # Without a log line the queue silently stops advancing for this
                # session for the lifetime of the process.
                import logging
                logging.exception('Queue could not read session %s/%s',backend,sid)
                continue
            active = session.get('status') in ('active','running','working','inProgress')
            signature = self.signature(session)
            with self.lock:
                gate = self.db.execute('SELECT * FROM gates WHERE backend=? AND sid=?',(backend,sid)).fetchone()
                if active:
                    if gate:
                        self.db.execute('UPDATE gates SET active=1 WHERE backend=? AND sid=?',(backend,sid));self.db.commit()
                    continue
                if gate and not gate['active'] and self.gate_signature(gate['signature']) == signature and self.gate_age(gate['signature']) < 8:
                    # The previous send is accepted but Desktop has not shown it yet.
                    # The grace period matters for providers whose session rows carry
                    # no turn id (Claude, DeepSeek): without it the gate never opened
                    # again and the rest of the queue could never be sent.
                    continue
                if session.get('requests'):
                    continue
                # A failed/uncertain head blocks later entries until the user resolves it.
                with self.dispatch_lock:
                    rows = self.listing(backend,sid)
                    if not rows or rows[0]['state'] != 'waiting':
                        continue
                    row = rows[0]
                    row['body'] = json.dumps(row['body'])
                    self.db.execute("UPDATE queue SET state='sending' WHERE id=?",(row['id'],))
                    self.db.execute('INSERT OR REPLACE INTO gates VALUES (?,?,?,0)',(backend,sid,self.gate_value(session)))
                    self.db.commit()
                    self._dispatch(row,'send')

    def close(self):
        """Stop the worker and release every connection, without raising.

        This runs from the shutdown path, where an exception would skip the rest of
        This runs from the shutdown path, where an exception would skip the rest of
        the cleanup. A dispatch that outlives the join timeout used to leave the
        write connection open; closing them all under the lock releases the file
        handle whether or not the worker stopped in time.
        """
        self.stop.set()
        if self.worker:
            self.worker.join(timeout=2)
            # A worker still inside tick() can open a connection after the drain
            # below; wait for it to actually leave before releasing the handles.
            if self.worker.is_alive():
                self.worker.join(timeout=3)
        with self.connections_lock:
            self.closed = True
            connection = self.connection
            self.connection = None
        if connection is not None:
            try: connection.close()
            except sqlite3.Error: pass
        for suffix in ('-wal', '-shm'):
            try: Path(str(self.path)+suffix).unlink(missing_ok=True)
            except OSError: pass
