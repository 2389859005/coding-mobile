import threading
import time
import types
import unittest
from unittest.mock import patch

from coding_remote.live_hub import LiveHub, Subscription
from coding_remote.native_features import NativeFeatures


class FreshnessTests(unittest.TestCase):
    def test_source_event_refreshes_content_with_unchanged_metadata(self):
        body=['first']
        server=types.SimpleNamespace(features=types.SimpleNamespace(stream_token=lambda *a:'same'),
            queue=types.SimpleNamespace(listing=lambda *a:[]),dispatch=lambda *a:{'session':{'id':'a','status':'active'},'messages':[{'role':'assistant','text':body[0]}]})
        hub=LiveHub(server)
        try:
            with patch('coding_remote.live_hub.time.monotonic',return_value=100):hub.refresh('session:codex:a')
            body[0]='second'
            with patch('coding_remote.live_hub.time.monotonic',return_value=102):hub.refresh('session:codex:a')
            self.assertEqual(hub.snapshots['session:codex:a']['data']['messages'][0]['text'],'second')
            self.assertEqual(hub.due['session:codex:a'],float('inf'))
        finally:hub.close()

    def test_idle_source_does_not_poll_or_publish_checkpoints(self):
        calls=[]
        server=types.SimpleNamespace(features=types.SimpleNamespace(task_status=lambda:(calls.append(1) or {'tasks':[]})))
        hub=LiveHub(server);sub=hub.subscribe({'status'})
        try:
            self.assertIn('data',sub.read(1))
            self.assertIsNone(sub.read(1.2))
            self.assertEqual(len(calls),1)
            hub.publish('status',{'tasks':[]})
            self.assertIsNone(sub.read(0))
            hub.invalidate(topic='status')
            end=time.monotonic()+1
            while len(calls)<2 and time.monotonic()<end:time.sleep(.01)
            self.assertEqual(len(calls),2)
        finally:hub.close()

    def test_slow_provider_does_not_hold_other_task_updates(self):
        release=threading.Event();started=threading.Event();calls=[];version=[1]
        features=object.__new__(NativeFeatures);features.status_lock=threading.Lock()
        def status(backend):
            if backend=='claude':calls.append(1);started.set();release.wait(5)
            return {'tasks':[{'backend':backend,'version':version[0]}],'unavailable':[]}
        features._backend_task_status=status
        try:
            begin=time.monotonic();first=features._task_status_uncached()
            self.assertLess(time.monotonic()-begin,1.2)
            self.assertTrue(started.is_set());self.assertIn('claude',first['unavailable'])
            self.assertEqual(next(t for t in first['tasks'] if t['backend']=='codex')['version'],1)
            version[0]=2
            with features.status_lock:features.status_versions['codex']=1;features.status_versions['deepseek']=1
            second=features._task_status_uncached()
            self.assertEqual(next(t for t in second['tasks'] if t['backend']=='codex')['version'],2)
            self.assertEqual(len(calls),1,'one outstanding request per provider')
        finally:release.set();features.close()


if __name__=='__main__':unittest.main()
