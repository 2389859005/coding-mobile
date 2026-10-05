import json
import tempfile
import unittest
import uuid
from types import SimpleNamespace
from coding_remote.task_queue import TaskQueue


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.status='active';self.turn='one';self.sent=[];self.outcome='accepted'
        self.server=SimpleNamespace(dispatch=self.dispatch,features=SimpleNamespace(images=lambda *args:[]))
        self.queue=TaskQueue(self.server,self.tmp.name)

    def tearDown(self):
        self.queue.close();self.tmp.cleanup()

    def dispatch(self,backend,sid,action,body):
        if action is None:
            return {'connected':True,'session':{'status':self.status,'turnId':self.turn}}
        self.sent.append((backend,sid,body))
        return {'status':self.outcome}

    def add(self,text='next'):
        body={'id':str(uuid.uuid4()),'operation':'enqueue','message':{'text':text}}
        result=self.queue.request('codex','session',body)
        self.assertEqual(result,self.queue.request('codex','session',body))
        return result['queueId']

    def action(self,qid,operation,**extra):
        return self.queue.request('codex','session',{'id':str(uuid.uuid4()),'operation':operation,'queueId':qid,**extra})

    def test_completion_stale_snapshots_and_serial_dispatch(self):
        self.add('first');self.add('second');self.queue.tick();self.assertEqual(self.sent,[])
        self.status='idle';self.queue.tick();self.assertEqual(len(self.sent),1)
        self.queue.tick();self.assertEqual(len(self.sent),1)
        self.status='active';self.queue.tick();self.status='idle';self.queue.tick()
        self.assertEqual([r[2]['text'] for r in self.sent],['first','second'])

    def test_quick_task_completion_without_observed_active(self):
        self.add();self.add();self.status='idle';self.queue.tick();self.turn='two';self.queue.tick()
        self.assertEqual(len(self.sent),2)

    def test_recall_remove_and_restart_keep_queue(self):
        a=self.add('editable');b=self.add('delete');c=self.add('persist')
        self.assertEqual(self.action(a,'recall')['messageBody']['text'],'editable')
        self.action(b,'remove');self.queue.close();self.queue=TaskQueue(self.server,self.tmp.name)
        self.assertEqual([r['id'] for r in self.queue.listing('codex','session')],[c]);self.assertEqual(self.sent,[])

    def test_send_now_is_explicit_and_idempotent(self):
        qid=self.add();body={'id':str(uuid.uuid4()),'operation':'sendnow','queueId':qid}
        first=self.queue.request('codex','session',body)
        self.assertEqual(first,self.queue.request('codex','session',body))
        self.assertEqual(len(self.sent),1);self.assertEqual(self.sent[0][2]['mode'],'steer')

    def test_unknown_never_replays_and_blocks_following_until_acknowledged(self):
        first=self.add();self.add();self.status='idle';self.outcome='unknown';self.queue.tick()
        self.turn='changed';self.queue.tick();self.assertEqual(len(self.sent),1)
        with self.assertRaises(ValueError):self.action(first,'remove')
        self.action(first,'remove',confirmed=True);self.outcome='accepted';self.queue.tick();self.assertEqual(len(self.sent),2)

    def test_operations_cannot_target_another_session(self):
        qid=self.add()
        with self.assertRaises(ValueError):self.queue.request('claude','other',{'id':str(uuid.uuid4()),'operation':'remove','queueId':qid})
        self.assertEqual(len(self.queue.listing('codex','session')),1)
