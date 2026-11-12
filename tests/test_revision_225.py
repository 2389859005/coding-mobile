import tempfile
import threading
import types
import unittest
from unittest.mock import MagicMock

from claude_bridge import model
from coding_remote.api import message_window
from coding_remote.native_features import NativeFeatures
from coding_remote.power_plan import PowerPlan
from coding_remote.task_queue import TaskQueue


class RevisionTests(unittest.TestCase):
    def test_paginated_rows_preserve_edit_identity(self):
        value=message_window({'messages':[{'id':'native:text:0','role':'user','text':'hello '*5000}]},limit=60)
        self.assertGreater(len(value['messages']),1)
        self.assertTrue(all(row['sourceId']=='native:text:0' for row in value['messages']))
        self.assertEqual(len(set(row['id'] for row in value['messages'])),len(value['messages']))

    def test_idle_turn_wins_over_warm_process(self):
        for flags in ({'turnRunning':False,'isRunning':True},{'isRunning':False}):
            self.assertEqual(model.session('code',{'sessionId':'s','lifecycleState':'running',**flags})['status'],'idle')
        rows=model.transcript([{'type':'user','uuid':'stop','message':{'content':'[Request interrupted by user]'}}])
        self.assertFalse(any(row.get('editable') for row in rows))
        self.assertFalse(any(row.get('role')=='user' for row in rows))

    def test_source_change_during_read_cannot_repopulate_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            server=types.SimpleNamespace()
            features=NativeFeatures(server,directory)
            calls=[]
            def dispatch(*args):
                calls.append(1)
                if len(calls)==1:features.source_changed('claude')
                return {'sessions':[],'messages':[{'text':str(len(calls))}]}
            server.dispatch=dispatch
            for read in (lambda:features.detail('claude','s'),lambda:features.list_snapshot('claude')):
                calls.clear();read();read();self.assertEqual(len(calls),2)
            features.close()

    def test_shutdown_waits_for_observed_work_and_queue_then_runs_once(self):
        with tempfile.TemporaryDirectory() as directory:
            server=types.SimpleNamespace(apps=types.SimpleNamespace(power=MagicMock(return_value={'message':'scheduled'})))
            server.queue=TaskQueue(server,directory)
            plan=PowerPlan(server);plan.enabled=True
            def snapshot(status,**extra):return {'tasks':[{'backend':'claude','id':'s','status':status}],**extra}
            try:
                self.assertFalse(plan.observe(snapshot('idle')))
                self.assertFalse(plan.observe(snapshot('active')))
                self.assertFalse(plan.observe({'tasks':[]}))
                self.assertFalse(plan.observe(snapshot('idle',unavailable=['claude'])))
                self.assertFalse(plan.observe(snapshot('idle',error='offline')))
                self.assertFalse(plan.observe({'tasks':[{'backend':'claude','id':'s','status':'idle','requests':[{'id':'approval'}]}]}))
                with server.queue.lock:
                    server.queue.db.execute("INSERT INTO queue (id,state) VALUES ('pending','unknown')");server.queue.db.commit()
                self.assertFalse(plan.observe(snapshot('idle')))
                with server.queue.lock:
                    server.queue.db.execute("UPDATE queue SET state='removed'");server.queue.db.commit()
                self.assertTrue(plan.observe(snapshot('idle')))
                self.assertFalse(plan.observe(snapshot('idle')))
                server.apps.power.assert_called_once_with('shutdown',True)
                self.assertFalse(plan.snapshot()['shutdownWhenIdle'])
            finally:plan.close();server.queue.close()
