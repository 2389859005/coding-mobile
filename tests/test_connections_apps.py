import base64
import hashlib
import json
import socket
import struct
import tempfile
import threading
import types
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch,MagicMock
from coding_remote.pairing import Pairing
from coding_remote.apps import Applications
from coding_remote.cdp import CDP
from coding_remote.api import NativeServer
from claude_bridge.packaged import APIError


class DualConnectionTests(unittest.TestCase):
    def test_pairing_lost_reply_receipt_is_scoped_and_revocable(self):
        with tempfile.TemporaryDirectory() as d:
            pairing=Pairing(d);code=json.loads(pairing.issue('https://fixture.trycloudflare.com'))
            rid=str(uuid.uuid4());metadata={'installationId':'one-phone'}
            first=pairing.pair(code['token'],'phone','address-a',metadata,rid)
            self.assertEqual(first,pairing.pair(code['token'],'phone','address-b',metadata,rid))
            self.assertEqual(len(pairing.devices),1)
            with self.assertRaises(PermissionError):pairing.pair(code['token'],'phone','address-b',{'installationId':'other'},rid)
            with self.assertRaises(PermissionError):pairing.pair(code['token'],'phone','address-b',metadata,str(uuid.uuid4()))
            pairing.revoke(first['deviceId'])
            with self.assertRaises(PermissionError):pairing.pair(code['token'],'phone','address-b',metadata,rid)

    def test_one_device_row_keeps_independent_credentials(self):
        with tempfile.TemporaryDirectory() as d:
            p=Pairing(d);tokens=[]
            for origin in ['http://100.64.1.2:18787','https://fixture.trycloudflare.com','http://100.64.1.2:18787']:
                code=json.loads(p.issue(origin));tokens.append(p.pair(code['token'],'Phone','local',{'installationId':'one-device'})['token'])
            self.assertEqual(len(p.grouped_devices()),1)
            self.assertEqual(set(p.grouped_devices()[0]['modes']),{'temporary','permanent'})
            self.assertTrue(all(p.authenticate(token) for token in tokens))
            p.close_temporary('https://fixture.trycloudflare.com')
            self.assertTrue(p.authenticate(tokens[0]));self.assertIsNone(p.authenticate(tokens[1]))
            code=json.loads(p.issue('http://100.64.1.2:18787'));p.pair(code['token'],'Phone','local',{'installationId':'different-device'})
            self.assertEqual(len(p.grouped_devices()),2)
    def test_independent_qr_credentials_and_restart(self):
        with tempfile.TemporaryDirectory() as d:
            p=Pairing(d)
            private=json.loads(p.issue('http://100.64.1.2:18787','permanent'))
            public=json.loads(p.issue('https://fixture.trycloudflare.com','temporary'))
            bound=p.pair(private['token'],'phone','local');temp=p.pair(public['token'],'phone','local')
            self.assertEqual(bound['computerId'],temp['computerId'])
            self.assertTrue(p.authenticate(bound['token']));self.assertTrue(p.authenticate(temp['token']))
            p.close_temporary(public['origin'])
            self.assertIsNone(p.authenticate(temp['token']));self.assertTrue(p.authenticate(bound['token']))
            public=json.loads(p.issue(public['origin'],'temporary'));temp=p.pair(public['token'],'phone','local')
            reloaded=Pairing(d)
            self.assertEqual(reloaded.computer_id,p.computer_id)
            self.assertTrue(reloaded.authenticate(bound['token']));self.assertIsNone(reloaded.authenticate(temp['token']))
    def test_refresh_only_invalidates_same_mode_and_temporary_expires(self):
        with tempfile.TemporaryDirectory() as d:
            p=Pairing(d);a=json.loads(p.issue('http://100.64.1.2:18787'));b=json.loads(p.issue('https://fixture.trycloudflare.com'));p.issue(b['origin'])
            with self.assertRaises(PermissionError):p.pair(b['token'],'phone','local')
            self.assertTrue(p.pair(a['token'],'phone','local'))
            b=json.loads(p.issue(b['origin']));credential=p.pair(b['token'],'phone','local')
            with patch('coding_remote.pairing.time.time',return_value=credential['expires']+1):self.assertIsNone(p.authenticate(credential['token']))


class ApplicationTests(unittest.TestCase):
    def test_codex_start_activates_existing_desktop_and_connects(self):
        row={'ProcessId':42,'ExecutablePath':'C:/WindowsApps/OpenAI.Codex/app/ChatGPT.exe'}
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',return_value=[row]),patch('coding_remote.apps.os.startfile') as activate:
            app=self.app(d);app.codex=MagicMock()
            self.assertTrue(app.execute('codex','start')['connected'])
            activate.assert_called_once_with('shell:AppsFolder\\OpenAI.Codex_2p2nqsd0c76g0!App')
            app.codex.ipc.connect.assert_called_once();app.close()

    def test_codex_restart_closes_old_pid_and_activates_packaged_app(self):
        row={'ProcessId':42,'ExecutablePath':'C:/WindowsApps/OpenAI.Codex/app/ChatGPT.exe'}
        new={**row,'ProcessId':43}
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',side_effect=[[row],[],[new],[new]]),patch('coding_remote.apps.close_process') as close,patch('coding_remote.apps.os.startfile') as activate,patch('coding_remote.apps.time.sleep'):
            app=self.app(d);app.codex=MagicMock();app.codex.ipc.connect.side_effect=RuntimeError('handshake failed')
            result=app.execute('codex','restart',True)
            close.assert_called_once_with(42);activate.assert_called_once();app.codex.ipc.close.assert_called_once()
            self.assertFalse(result['connected']);self.assertIn('handshake failed',result['message']);app.close()

    def test_codex_list_does_not_confuse_database_with_connection(self):
        with tempfile.TemporaryDirectory() as d:
            bridge=MagicMock();bridge.list.return_value=[{'id':'stored'}];bridge.ipc.connect.side_effect=RuntimeError('offline')
            server=NativeServer(bridge,None,Pairing(d),d)
            try:
                result=server.dispatch('codex',None,None,{})
                self.assertFalse(result['connected']);self.assertEqual(len(result['sessions']),1)
            finally:server.server_close()

    def test_archived_sessions_are_returned_with_explicit_flag(self):
        with tempfile.TemporaryDirectory() as d:
            bridge=MagicMock();bridge.list.side_effect=lambda **q:[] if q['working'] else [{'id':'archived' if q['archived'] else 'active'}]
            server=NativeServer(bridge,None,Pairing(d),d)
            try:
                rows=server.dispatch('codex',None,None,{})['sessions']
                self.assertEqual([(r['id'],r['archived']) for r in rows],[('active',False),('archived',True)])
            finally:server.server_close()

    def setUp(self):
        self.settings_patch=patch('coding_remote.apps.keyboard.enable_devtools',return_value=False)
        self.settings_patch.start();self.addCleanup(self.settings_patch.stop)
        self.tools_patch=patch('coding_remote.apps.keyboard.close_devtools')
        self.close_tools=self.tools_patch.start();self.addCleanup(self.tools_patch.stop)
    def test_claude_starts_normally_without_debug_flags(self):
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',return_value=[]),patch('coding_remote.apps.executable',return_value=Path('C:/Claude.exe')),patch('coding_remote.apps.subprocess.Popen') as launch,patch('coding_remote.apps.time.sleep'):
            app=self.app(d);result=app.execute('claude','start');app.close()
            self.assertEqual(result['status'],'accepted');self.assertEqual(launch.call_args.args[0],[str(Path('C:/Claude.exe'))])
            self.assertFalse((Path(d)/'claude-cdp.json').exists())
    def test_deepseek_harness_executable_and_process_name(self):
        from coding_remote.apps import executable,main_processes
        row={'ProcessId':7,'ExecutablePath':'D:/Program Files/deepseek/DeepSeek Harness.exe','CommandLine':'Harness'}
        with patch('coding_remote.apps.processes',return_value=[row]):
            self.assertEqual(main_processes('deepseek'),[row])
            self.assertEqual(executable('deepseek').name,'DeepSeek Harness.exe')
        with patch('coding_remote.apps.processes',return_value=[]),patch.object(Path,'is_file',lambda p:p.name=='DeepSeek Harness.exe'):
            self.assertEqual(executable('deepseek').name,'DeepSeek Harness.exe')
    def app(self,d):
        with patch.object(Applications,'monitor'):return Applications(d,'(async()=>{})();',MagicMock())
    def test_allowlist_and_confirmation_never_kill_without_confirmation(self):
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',return_value=[{'ProcessId':42,'ExecutablePath':'C:/Claude.exe','CommandLine':'Claude.exe'}]),patch('coding_remote.apps.close_process') as close:
            app=self.app(d)
            with self.assertRaises(ValueError):app.execute('arbitrary','start')
            with self.assertRaises(ValueError):app.execute('claude','stop')
            self.assertEqual(app.execute('claude','start')['status'],'accepted')
            close.assert_not_called();app.close()
    def test_assistant_window_remains_remotely_reopenable(self):
        with tempfile.TemporaryDirectory() as d:
            app=self.app(d);self.assertTrue(app.execute('assistant','stop',True)['background']);self.assertFalse(app.window_open)
            app.execute('assistant','start');self.assertTrue(app.window_open);app.window.assert_called_with(True);app.close()
    def test_claude_launch_arguments_and_restart_path(self):
        row={'ProcessId':42,'ExecutablePath':'C:/exact/Claude.exe','CommandLine':'Claude.exe'}
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',side_effect=[[row],[]]),patch('coding_remote.apps.close_process') as close,patch('coding_remote.apps.subprocess.Popen') as launch,patch('coding_remote.apps.executable') as lookup:
            app=self.app(d);app.execute('claude','restart',True);close.assert_called_once_with(42);lookup.assert_not_called()
            args=launch.call_args.args[0];self.assertEqual(args[0],'C:\\exact\\Claude.exe');self.assertEqual(len(args),1);self.assertFalse(any('devtools' in a.lower() for a in args));app.close()
    def test_keyboard_handshake_reloads_and_failure_throttle(self):
        row={'ProcessId':42,'ExecutablePath':'C:/Claude.exe','CommandLine':'Claude.exe'}
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',return_value=[row]),patch('coding_remote.apps.keyboard.inject') as inject:
            app=self.app(d);app.desktop=types.SimpleNamespace(connected=False,disconnected=lambda m:None);app.on_connected=MagicMock()
            def connect(*args):
                self.assertEqual(self.close_tools.call_count,app.on_connected.call_count)
                app.desktop.connected=True
            inject.side_effect=connect
            app.inject();app.inject();self.assertEqual(inject.call_count,1);self.assertTrue(app.injected)
            self.close_tools.assert_called_once_with(42,Path(d));app.on_connected.assert_called_once()
            app.desktop.connected=False;app.inject();self.assertEqual(inject.call_count,2)
            self.assertEqual(app.on_connected.call_count,2)
            app.desktop.connected=False;inject.side_effect=RuntimeError('Console not focused')
            with self.assertRaises(RuntimeError):app.inject()
            app.inject();self.assertEqual(inject.call_count,3,'failed UI attempt is not repeated by polling')
            app.execute('claude','start')
            app.next_attempt=0
            with self.assertRaises(RuntimeError):app.inject()
            self.assertEqual(inject.call_count,4,'explicit Start allows a retry without restarting')
            self.assertEqual(app.on_connected.call_count,2,'failed connection leaves the window open')
            app.close()
    def test_connected_startup_closes_tools_once_without_reinjecting(self):
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',return_value=[{'ProcessId':42}]),patch('coding_remote.apps.keyboard.inject') as inject:
            app=self.app(d);app.desktop=types.SimpleNamespace(connected=True);app.on_connected=MagicMock()
            app.inject();app.inject();inject.assert_not_called();self.close_tools.assert_called_once_with(42,Path(d));app.on_connected.assert_called_once();app.close()
    def test_no_receipt_does_not_close_tools_or_minimize(self):
        row={'ProcessId':42,'ExecutablePath':'C:/Claude.exe'}
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',return_value=[row]),patch('coding_remote.apps.keyboard.inject'),patch('coding_remote.apps.time.monotonic',side_effect=[0,0,20]):
            app=self.app(d);app.desktop=types.SimpleNamespace(connected=False,disconnected=MagicMock());app.on_connected=MagicMock()
            with self.assertRaisesRegex(RuntimeError,'连接回执'):app.inject()
            self.close_tools.assert_not_called();app.on_connected.assert_not_called();app.close()
    def test_tools_close_failure_preserves_connection_and_diagnostic_window(self):
        with tempfile.TemporaryDirectory() as d,patch('coding_remote.apps.main_processes',return_value=[{'ProcessId':42}]):
            app=self.app(d);app.desktop=types.SimpleNamespace(connected=True,disconnected=MagicMock());app.on_connected=MagicMock();self.close_tools.side_effect=RuntimeError('window busy')
            app.inject();self.assertTrue(app.injected);app.desktop.disconnected.assert_not_called();app.on_connected.assert_not_called();self.assertIn('window busy',app.error);app.close()
    def test_lifecycle_receipt_prevents_duplicate_execution(self):
        with tempfile.TemporaryDirectory() as d:
            server=NativeServer(None,None,Pairing(d),d);server.apps=MagicMock();server.apps.execute.return_value={'status':'accepted'}
            try:
                body={'id':str(uuid.uuid4()),'confirmed':True}
                first=server.application_action('codex','stop',body);self.assertEqual(first,server.application_action('codex','stop',body));server.apps.execute.assert_called_once()
                with self.assertRaises(APIError):server.application_action('claude','stop',body)
            finally:server.server_close()
    def test_codex_running_ids_replace_missing_status_without_attaching(self):
        with tempfile.TemporaryDirectory() as d:
            bridge=MagicMock();bridge.list.side_effect=lambda **kw:[{'id':'active'}] if kw['working'] else [{'id':'active','title':'Running'},{'id':'idle','title':'Done'}]
            server=NativeServer(bridge,None,Pairing(d),d)
            try:
                rows=server.dispatch('codex',None,None,{})['sessions'];self.assertEqual([r['status'] for r in rows],['active','idle']);bridge.view.assert_not_called()
            finally:server.server_close()


class CDPTransportTests(unittest.TestCase):
    def test_masked_large_request_ping_and_fragmented_response(self):
        listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();port=listener.getsockname()[1];errors=[];received=[]
        def server():
            try:
                with listener.accept()[0] as c:
                    c.settimeout(5)
                    def exact(n):
                        b=b''
                        while len(b)<n:b+=c.recv(n-len(b))
                        return b
                    header=b''
                    while not header.endswith(b'\r\n\r\n'):header+=exact(1)
                    key=next(l.split(b': ',1)[1] for l in header.split(b'\r\n') if l.startswith(b'Sec-WebSocket-Key:'))
                    accept=base64.b64encode(hashlib.sha1(key+b'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest())
                    c.sendall(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: '+accept+b'\r\n\r\n')
                    def frame():
                        a,b=exact(2);self.assertTrue(b&128);n=b&127
                        if n==126:n=struct.unpack('!H',exact(2))[0]
                        elif n==127:n=struct.unpack('!Q',exact(8))[0]
                        mask=exact(4);payload=exact(n);return a&15,bytes(v^mask[i%4] for i,v in enumerate(payload))
                    _,payload=frame();request=json.loads(payload);received.append(request)
                    c.sendall(b'\x89\x02ok');opcode,pong=frame();self.assertEqual((opcode,pong),(10,b'ok'))
                    result=json.dumps({'id':request['id'],'result':{'result':{'value':True}}}).encode();cut=10
                    c.sendall(bytes([1,cut])+result[:cut]+bytes([128,len(result)-cut])+result[cut:])
            except Exception as e:errors.append(e)
        thread=threading.Thread(target=server);thread.start()
        try:
            client=CDP(f'ws://127.0.0.1:{port}/devtools/page/test');self.assertTrue(client.evaluate('x'*70000)['result']['value']);client.close()
        finally:thread.join(6);listener.close()
        self.assertFalse(errors,errors);self.assertEqual(received[0]['method'],'Runtime.evaluate');self.assertNotIn('awaitPromise',received[0]['params'])
    def test_remote_debug_endpoint_rejected(self):
        with self.assertRaises(ValueError):CDP('ws://example.com:9222/devtools/page/test')
