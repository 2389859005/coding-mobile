import asyncio
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from claude_bridge.mailbox import FileDesktop
from claude_bridge.errors import BridgeUnavailable


class MailboxTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.desktop = FileDesktop(self.root, 'test-key')

    async def asyncTearDown(self):
        self.tmp.cleanup()

    def reply(self, **values):
        value = {'generation': self.desktop.generation, 'connected': True,
                 'timestamp': time.time(), 'surfaces': {'code': ['getAll']}, **values}
        (self.root / 'response.json').write_text(self.desktop.pack(value), encoding='utf-8')

    async def test_handshake_call_and_wrong_sequence(self):
        self.reply()
        self.assertTrue(self.desktop.connected)
        call = asyncio.create_task(self.desktop.call('code', 'getAll', timeout=2))
        for _ in range(100):
            if self.desktop.seq: break
            await asyncio.sleep(.01)
        self.reply(seq=self.desktop.seq + 1, done=True, result=['wrong'])
        await asyncio.sleep(.05)
        self.assertFalse(call.done())
        self.reply(seq=self.desktop.seq, done=True, result=[])
        self.assertEqual(await call, [])

    async def test_tampered_expired_and_old_generation_cannot_connect(self):
        self.reply(timestamp=time.time()-25)
        self.assertFalse(self.desktop.connected)
        self.reply(generation='previous-process')
        self.assertFalse(self.desktop.connected)
        self.reply()
        p = self.root / 'response.json'
        value = json.loads(p.read_text('utf-8'))
        value['signature'] = '0' * 64
        p.write_text(json.dumps(value), encoding='utf-8')
        self.assertFalse(self.desktop.connected)

    async def test_partial_heartbeat_uses_only_last_verified_packet(self):
        self.reply()
        self.assertTrue(self.desktop.connected)
        (self.root / 'response.json').write_text('{', encoding='utf-8')
        self.assertTrue(self.desktop.connected)
        self.desktop.cached['timestamp'] = time.time()-25
        self.assertFalse(self.desktop.connected)

    async def test_timeout_does_not_resubmit(self):
        self.reply()
        with self.assertRaises(BridgeUnavailable):
            await self.desktop.call('code', 'getAll', timeout=.1)
        self.assertEqual(self.desktop.seq, 1)
        value = json.loads(json.loads((self.root/'request.json').read_text('utf-8'))['payload'])
        self.assertEqual(value['seq'], 1)

    async def test_windows_transient_file_lock_retries_only_file_publish(self):
        original = Path.replace
        attempts = []
        def replace(path, target):
            attempts.append(path)
            if len(attempts) < 3: raise PermissionError('sharing violation')
            return original(path, target)
        with patch.object(Path, 'replace', replace):
            self.desktop.write({'generation': self.desktop.generation, 'seq': 1})
        self.assertEqual(len(attempts), 3)

    async def test_catalog_waiting_for_busy_slot_has_bounded_timeout(self):
        self.reply()
        await self.desktop.lock.acquire()
        try:
            with self.assertRaises(BridgeUnavailable):
                await self.desktop.call('code','getAll',timeout=.02)
            self.assertEqual(self.desktop.seq,0)
        finally:self.desktop.lock.release()
