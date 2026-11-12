import json
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from claude_bridge.mailbox import FileDesktop
from coding_remote.apps import Applications
from coding_remote.deepseek import DeepSeek
from coding_remote.pairing import Pairing


class Startup225Tests(unittest.TestCase):
    def test_reconnect_does_not_wait_for_console_lock_or_duplicate_bootstrap(self):
        with tempfile.TemporaryDirectory() as directory,patch('coding_remote.apps.threading.Thread'):
            apps=Applications(directory,'',lambda _:None,desktop=types.SimpleNamespace(connected=False))
            apps.lock.acquire()
            try:
                self.assertEqual(apps.execute('claude','reconnect')['status'],'accepted')
            finally:apps.lock.release()
            apps.attempted_pid=42;apps.receipt_deadline=float('inf')
            self.assertEqual(apps.execute('claude','reconnect')['status'],'accepted')
            self.assertEqual(apps.attempted_pid,42)
            apps.receipt_deadline=0
            self.assertEqual(apps.execute('claude','reconnect')['status'],'accepted')
            self.assertIsNone(apps.attempted_pid)
            self.assertTrue(apps.wake.is_set())
            apps.close()

    def test_reconnect_refreshes_clients_without_stopping_application_processes(self):
        with tempfile.TemporaryDirectory() as directory,patch('coding_remote.apps.threading.Thread'),patch('coding_remote.apps.close_process') as close:
            ipc=MagicMock(client_id='client',socket=object())
            deepseek=MagicMock(connected=True)
            apps=Applications(directory,'',lambda _:None,codex=types.SimpleNamespace(ipc=ipc),deepseek=deepseek)
            self.assertTrue(apps.execute('codex','reconnect')['connected'])
            ipc.close.assert_called_once();ipc.connect.assert_called_once()
            self.assertTrue(apps.execute('deepseek','reconnect')['connected'])
            deepseek.install.assert_called_once();close.assert_not_called();apps.close()

    def test_bridge_session_survives_restart_only_from_current_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            desktop=FileDesktop(directory)
            path=Path(directory)/'response.json'
            path.write_text(json.dumps({'generation':'old','bridgeSessionId':'local_wrong'}))
            desktop.read()
            self.assertEqual(desktop.bridge_session,'')
            path.write_text(json.dumps({'generation':desktop.generation,'bridgeSessionId':'local_workspace'}))
            desktop.read()
            restored=FileDesktop(directory)
            self.assertEqual(restored.bridge_session,'local_workspace')

    def test_old_fixed_ssh_grant_survives_restart_without_reviving_expired_grants(self):
        with tempfile.TemporaryDirectory() as directory:
            pairing=Pairing(directory)
            origin='https://relay.example.com'
            qr=json.loads(pairing.issue(origin,'temporary'))
            live=pairing.pair(qr['token'],'phone','local')
            qr=json.loads(pairing.issue('https://temporary.trycloudflare.com','temporary'))
            temporary=pairing.pair(qr['token'],'phone','local')
            pairing.devices['expired']={'id':'expired','origin':origin,'mode':'temporary','expires':1}
            pairing.remember_server_origin(origin)
            restored=Pairing(directory)
            self.assertIsNone(restored.authenticate(live['token'])['expires'])
            self.assertIsNone(restored.authenticate(temporary['token']))
            self.assertEqual(restored.devices['expired']['expires'],1)
            restored.revoke(live['deviceId']);restored.remember_server_origin(origin)
            self.assertIsNone(restored.authenticate(live['token']))

    def test_harness_health_is_authenticated_and_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            adapter=DeepSeek(directory)
            with patch.object(adapter,'call',return_value={'connected':True}) as call:
                self.assertTrue(adapter.connected)
                call.assert_called_once_with('health',timeout=1.5)
            with patch.object(adapter,'call',side_effect=ValueError('old plugin')):
                self.assertFalse(adapter.connected)
            with patch.object(adapter,'call',side_effect=[ValueError('unknown health'),{'connected':True}]):
                self.assertTrue(adapter.connected)

    def test_live_harness_overrides_process_name_and_reports_connected(self):
        with tempfile.TemporaryDirectory() as directory,patch('coding_remote.apps.threading.Thread'),patch('coding_remote.apps.processes',return_value=[]):
            apps=Applications(directory,'',lambda _:None,deepseek=types.SimpleNamespace(connected=True))
            try:
                row=next(row for row in apps.snapshot()['apps'] if row['id']=='deepseek')
                self.assertTrue(row['running']);self.assertTrue(row['connected'])
                apps.deepseek.connected=False
                self.assertFalse(next(row for row in apps.snapshot()['apps'] if row['id']=='deepseek')['connected'])
            finally:apps.close()
