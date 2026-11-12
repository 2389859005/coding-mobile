import os
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from claude_bridge.mailbox import FileDesktop
from coding_remote.change_feed import ChangeFeed
from coding_remote.codex_plugins import PluginBridgeMixin
from coding_remote.live_hub import LiveHub
from coding_remote.native_features import NativeFeatures
from coding_remote.task_queue import TaskQueue


class SourceEventTests(unittest.TestCase):
    def test_metadata_only_events_reach_live_sessions_and_bursts_are_coalesced(self):
        calls=[];state={'model':'old','status':'active'}
        server=types.SimpleNamespace(features=types.SimpleNamespace(stream_token=lambda *a:'same'),
            queue=types.SimpleNamespace(listing=lambda *a:[]),
            dispatch=lambda *a:(calls.append(1) or {'connected':True,'session':dict(state,id='fixture'),'messages':[]}))
        hub=LiveHub(server,interval=.1);sub=hub.subscribe({'session:deepseek:fixture'})
        try:
            self.assertEqual(sub.read(2)['data']['session']['model'],'old')
            state.update(model='new',status='idle')
            for _ in range(100):hub.invalidate(backend='deepseek')
            event=sub.read(2)['data']['session']
            self.assertEqual((event['model'],event['status']),('new','idle'))
            self.assertLessEqual(len(calls),3)
            self.assertIsNone(sub.read(.25))
        finally:hub.close()

    def test_stream_token_includes_model_and_effort_without_new_messages(self):
        features=object.__new__(NativeFeatures);row={'status':'idle','model':'old','effort':'low'}
        features.session_row=lambda *a:row
        features.server=types.SimpleNamespace(queue=types.SimpleNamespace(listing=lambda *a:[]))
        first=features.stream_token('claude','fixture');row['model']='new'
        second=features.stream_token('claude','fixture');self.assertNotEqual(first,second)
        row['effort']='high';self.assertNotEqual(second,features.stream_token('claude','fixture'))

    @unittest.skipUnless(os.name == 'nt', 'Windows native notifications')
    def test_native_notification_sees_new_nested_session_without_polling(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);changed=threading.Event()
            feed=ChangeFeed(root,changed.set);feed.start()
            try:
                self.assertTrue(feed.available)
                session=root/'sessions'/'new';session.mkdir(parents=True)
                (session/'turn.jsonl').write_text('one')
                self.assertTrue(changed.wait(2))
                changed.clear()
                (session/'turn.jsonl').write_text('two')
                self.assertTrue(changed.wait(2))
            finally:feed.close()

    def test_codex_callback_runs_after_stream_patch_and_on_disconnect(self):
        events=[]
        class Base:
            def _event(self,message):events.append(message['method'])
            def _disconnected(self):events.append('closed')
        class Bridge(PluginBridgeMixin,Base):pass
        bridge=object.__new__(Bridge);bridge.live_changed=lambda:events.append('wake')
        bridge._event({'method':'thread-stream-state-changed'})
        bridge._event({'method':'unrelated'})
        bridge._disconnected()
        self.assertEqual(events,['thread-stream-state-changed','wake','unrelated','closed','wake'])

    def test_claude_semantic_event_ignores_heartbeat_and_command_reply(self):
        class Feed:
            def __init__(self,root,callback,**kw):self.update=callback
            def start(self):pass
            def close(self):pass
        with tempfile.TemporaryDirectory() as folder:
            desktop=FileDesktop(folder);changes=[]
            with patch('coding_remote.change_feed.ChangeFeed',Feed):
                feed=desktop.start_events(lambda:changes.append(1))
            def emit(revision,stamp,seq=0):
                value={'generation':desktop.generation,'connected':True,'eventRevision':revision,'timestamp':stamp,'seq':seq}
                (Path(folder)/'response.json').write_text(desktop.pack(value),encoding='utf-8')
                desktop.stamp=None;feed.update()
            try:
                emit(0,1);count=len(changes)
                emit(0,2);emit(0,3,1)
                self.assertEqual(len(changes),count)
                emit(1,4,1)
                self.assertEqual(len(changes),count+1)
            finally:desktop.close()

    def test_event_during_collection_is_not_lost(self):
        entered=threading.Event();release=threading.Event();calls=[]
        def read():
            calls.append(1);version=len(calls)
            if version==1:entered.set();release.wait(2)
            return {'version':version,'tasks':[]}
        hub=LiveHub(types.SimpleNamespace(features=types.SimpleNamespace(task_status=read)))
        sub=hub.subscribe({'status'})
        try:
            self.assertTrue(entered.wait(1));hub.invalidate(topic='status');release.set()
            deadline=time.monotonic()+2;latest=None
            while time.monotonic()<deadline:
                event=sub.read(.2)
                if event:latest=event['data']['version']
                if latest==2:break
            self.assertEqual(latest,2)
            second=hub.subscribe({'status'})
            self.assertEqual(second.read(1)['data']['version'],2)
            self.assertIsNone(sub.read(.4));self.assertEqual(len(calls),2)
        finally:release.set();hub.close()

    def test_queue_waits_for_source_event(self):
        with tempfile.TemporaryDirectory() as folder:
            queue=TaskQueue(types.SimpleNamespace(),folder);calls=[];called=threading.Event()
            queue.tick=lambda:(calls.append(1),called.set())
            try:
                queue.start();self.assertTrue(called.wait(1));called.clear()
                self.assertFalse(called.wait(2.2));self.assertEqual(len(calls),1)
                queue.wake.set();self.assertTrue(called.wait(1));self.assertEqual(len(calls),2)
            finally:queue.close()

    def test_busy_source_keeps_completed_progress_while_newer_read_is_pending(self):
        from concurrent.futures import Future
        features=object.__new__(NativeFeatures);job=Future();wakes=[]
        features.status_lock=threading.Lock();features.status_versions={'codex':2}
        features.status_jobs={'codex':job};features.status_applied={};features.status_sources={}
        features.server=types.SimpleNamespace(live=types.SimpleNamespace(invalidate=lambda **kw:wakes.append(kw)))
        job.set_result({'tasks':[{'id':'fixture','progress':'chunk 1'}],'unavailable':[]})
        features._status_finished('codex',1,job)
        self.assertEqual(features.status_sources['codex']['tasks'][0]['progress'],'chunk 1')
        self.assertLess(features.status_applied['codex'],features.status_versions['codex'])
        self.assertEqual(wakes,[{'topic':'status'}])
