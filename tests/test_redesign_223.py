"""Regression for live transcripts whose desktop metadata does not change."""
import time
import types
import unittest
from unittest.mock import patch
from coding_remote.live_hub import LiveHub


class TranscriptFreshnessTests(unittest.TestCase):
    def make_hub(self,backend):
        self.text='first';self.failed=False
        def dispatch(*args):
            if self.failed:raise OSError('offline')
            return {'session':{'id':'fixture','status':'active'},'messages':[{'id':'a','role':'assistant','text':self.text}],'connected':True}
        server=types.SimpleNamespace(features=types.SimpleNamespace(stream_token=lambda *args:'stable'),queue=types.SimpleNamespace(listing=lambda *args:[]),dispatch=dispatch)
        return LiveHub(server),f'session:{backend}:fixture'

    def test_streamed_body_updates_with_unchanged_metadata(self):
        for backend in ('claude','deepseek','codex'):
            with self.subTest(backend=backend):
                hub,topic=self.make_hub(backend)
                try:
                    with patch('coding_remote.live_hub.time.monotonic',return_value=100):hub.refresh(topic)
                    self.text='new streamed text'
                    with patch('coding_remote.live_hub.time.monotonic',return_value=102):hub.refresh(topic)
                    self.assertEqual(hub.snapshots[topic]['data']['messages'][0]['text'],self.text)
                finally:hub.close()

    def test_error_cannot_hide_recovery_behind_the_old_token(self):
        hub,topic=self.make_hub('claude')
        try:
            with patch('coding_remote.live_hub.time.monotonic',return_value=100):hub.refresh(topic)
            self.failed=True
            with patch('coding_remote.live_hub.time.monotonic',return_value=102):hub.refresh(topic)
            self.assertIn('error',hub.snapshots[topic]['data'])
            self.failed=False
            with patch('coding_remote.live_hub.time.monotonic',return_value=103):hub.refresh(topic)
            self.assertIn('messages',hub.snapshots[topic]['data'])
        finally:hub.close()

    def test_unchanged_transcript_does_not_emit_repeat_events(self):
        hub,topic=self.make_hub('codex')
        try:
            with patch('coding_remote.live_hub.time.monotonic',return_value=100):hub.refresh(topic)
            first=hub.snapshots[topic]['sequence']
            with patch('coding_remote.live_hub.time.monotonic',return_value=102):hub.refresh(topic)
            self.assertEqual(first,hub.snapshots[topic]['sequence'])
        finally:hub.close()


if __name__=='__main__':unittest.main()
