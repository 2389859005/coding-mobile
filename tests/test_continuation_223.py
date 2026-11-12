import os
import tempfile
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from coding_remote.change_feed import ChangeFeed
from coding_remote.git_project import GitProjects
from coding_remote.live_hub import LiveHub
from coding_remote.native_features import NativeFeatures


class ContinuationTests(unittest.TestCase):
    def test_git_uses_verified_project_when_session_cwd_is_not_a_repository(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);project=root/'project';project.mkdir();scratch=root/'scratch';scratch.mkdir()
            GitProjects.run(project,['init'])
            server=types.SimpleNamespace(bridge=types.SimpleNamespace(store=types.SimpleNamespace(get=lambda sid:{'cwd':str(scratch),'projectCwd':str(project)})))
            features=NativeFeatures(server,root)
            try:self.assertEqual(Path(features.git('codex','fixture',{})['root']),project.resolve())
            finally:features.git_projects.close()

    def test_plain_directory_explains_git_is_unavailable(self):
        with tempfile.TemporaryDirectory() as folder:
            projects=GitProjects(folder)
            with self.assertRaisesRegex(ValueError,'尚未使用 Git'):projects.root(folder)

    def test_invalidation_during_refresh_is_not_lost(self):
        hub=LiveHub(types.SimpleNamespace())
        class Observer:
            topics={'status'}
            def push(self,event):pass
            def close(self):pass
        hub.subscribers.add(Observer())
        def status():hub.invalidate(topic='status');return {'tasks':[]}
        hub.server.features=types.SimpleNamespace(task_status=status)
        try:
            hub.refresh('status')
            self.assertEqual(hub.due['status'],0)
        finally:hub.close()

    @unittest.skipUnless(os.name=='nt','Windows change notification API')
    def test_native_notification_observes_transcript_append(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);sessions=root/'sessions';sessions.mkdir();transcript=sessions/'fixture.jsonl';transcript.write_text('{}\n')
            changed=threading.Event();feed=ChangeFeed(root,changed.set);feed.start()
            try:
                self.assertTrue(feed.ready.wait(2));self.assertTrue(feed.available)
                with transcript.open('a') as stream:stream.write('{"text":"new content"}\n')
                self.assertTrue(changed.wait(2),'append should trigger an OS notification')
            finally:feed.close();feed.thread.join(2)

    def test_file_event_refreshes_body_even_with_stable_metadata(self):
        text=['first']
        server=types.SimpleNamespace(features=types.SimpleNamespace(stream_token=lambda *args:'same'),queue=types.SimpleNamespace(listing=lambda *args:[]),dispatch=lambda *args:{'session':{'id':'a'},'messages':[{'role':'assistant','text':text[0]}]})
        hub=LiveHub(server);topic='session:codex:a'
        class Observer:
            topics={topic}
            def push(self,event):pass
            def close(self):pass
        hub.subscribers.add(Observer())
        try:
            hub.refresh(topic);text[0]='second';hub.invalidate(backend='codex');hub.refresh(topic)
            self.assertEqual(hub.snapshots[topic]['data']['messages'][0]['text'],'second')
        finally:hub.close()
