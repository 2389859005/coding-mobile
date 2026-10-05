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
        self.stop = threading.Event()
        self.worker = None
        self.db = sqlite3.connect(Path(directory)/'task-queue.sqlite', check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS queue (id TEXT PRIMARY KEY, backend TEXT, sid TEXT,
            body TEXT, state TEXT, created REAL, message TEXT DEFAULT '');
          CREATE TABLE IF NOT EXISTS gates (backend TEXT, sid TEXT, signature TEXT,
            active INTEGER DEFAULT 0, PRIMARY KEY(backend,sid));
          CREATE TABLE IF NOT EXISTS queue_actions (id TEXT PRIMARY KEY, fingerprint TEXT, result TEXT);
        ''')
        self.db.execute("UPDATE queue SET state='unknown', message='助手在提交期间重启，请核对原会话' WHERE state='sending'")
        self.db.commit()

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
            return [{**dict(row), 'body': json.loads(row['body'])} for row in self.db.execute(
                "SELECT * FROM queue WHERE backend=? AND sid=? AND state NOT IN ('sent','removed','recalled') ORDER BY created,id",
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
            session={}
            try:
                session = self.server.dispatch(backend,sid,None,{})['session']
                with self.lock:
                    self.db.execute('INSERT OR REPLACE INTO gates VALUES (?,?,?,0)',(backend,sid,self.signature(session)))
                    self.db.commit()
            except Exception:
                pass
            result = self._dispatch(dict(row), 'steer' if session.get('status') in ('active','running','working','inProgress') else 'send')
            with self.lock:
                self.db.execute('UPDATE queue_actions SET result=? WHERE id=?',(json.dumps(result),rid))
                self.db.commit()
        return result

    @staticmethod
    def signature(session):
        return json.dumps([session.get('turnId'),session.get('completedTurnId'),session.get('turnCount'),session.get('updatedAt')])

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
                session = detail['session']
            except Exception:
                continue
            active = session.get('status') in ('active','running','working','inProgress')
            signature = self.signature(session)
            with self.lock:
                gate = self.db.execute('SELECT * FROM gates WHERE backend=? AND sid=?',(backend,sid)).fetchone()
                if active:
                    if gate:
                        self.db.execute('UPDATE gates SET active=1 WHERE backend=? AND sid=?',(backend,sid));self.db.commit()
                    continue
                if gate and not gate['active'] and gate['signature'] == signature:
                    continue  # The previous send is accepted but not reflected by Desktop yet.
                if session.get('requests'):
                    continue
                # A failed/uncertain head blocks later entries until the user resolves it.
                rows = self.listing(backend,sid)
                if not rows or rows[0]['state'] != 'waiting':
                    continue
                row = rows[0]
                row['body'] = json.dumps(row['body'])
                self.db.execute("UPDATE queue SET state='sending' WHERE id=?",(row['id'],))
                self.db.execute('INSERT OR REPLACE INTO gates VALUES (?,?,?,0)',(backend,sid,signature))
                self.db.commit()
            self._dispatch(row,'send')

    def close(self):
        self.stop.set()
        if self.worker:
            self.worker.join(timeout=2)
        # An in-flight Desktop request may finish after shutdown begins.
        if self.worker is None or not self.worker.is_alive():
            with self.lock:
                self.db.close()
