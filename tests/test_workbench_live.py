import json
import queue
import tempfile
import threading
import time
import types
import unittest
import uuid
import importlib.util
import http.client
import sqlite3
import io
import hashlib
from contextlib import closing
from pathlib import Path
from unittest.mock import patch
from coding_remote.live_hub import LiveHub
from coding_remote.workspace import Workspace, SecretStore
from coding_remote.document_preview import DocumentPreview


class LiveHubTests(unittest.TestCase):
    def test_shared_source_and_latest_snapshot_bound(self):
        calls=[]
        server=types.SimpleNamespace(features=types.SimpleNamespace(task_status=lambda:(calls.append(1) or {'tasks':[]})))
        hub=LiveHub(server,interval=.02)
        a=hub.subscribe({'status'});b=hub.subscribe({'status'})
        try:
            first=a.read(1);second=b.read(1)
            self.assertEqual(first['revision'],second['revision'])
            self.assertEqual(len(calls),1)
            for n in range(40):hub.publish('status',{'tasks':[n]})
            self.assertEqual(len(a.pending),1)
            self.assertEqual(a.read(0)['data']['tasks'],[39])
            a.close();b.close()
            self.assertFalse(hub.subscribers)
        finally:hub.close()

    def test_failed_source_recovers_and_has_one_inflight(self):
        server=types.SimpleNamespace()
        hub=LiveHub(server,interval=.01)
        values=iter([ValueError('offline'), {'tasks':[]}])
        def status():
            value=next(values, {'tasks':[]})
            if isinstance(value,Exception):raise value
            return value
        server.features=types.SimpleNamespace(task_status=status)
        sub=hub.subscribe({'status'})
        try:
            hub.refresh('status');self.assertFalse(sub.read(0)['data']['connected'])
            hub.refresh('status');self.assertEqual(sub.read(0)['data'],{'tasks':[]})
        finally:sub.close();hub.close()


class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        server=types.SimpleNamespace(features=types.SimpleNamespace(session_row=lambda b,s:{'cwd':str(self.root)}))
        self.workspace=Workspace(server,self.root)
    def tearDown(self):
        self.workspace.close();self.temp.cleanup()
    def test_shared_draft_conflict_keeps_existing_value(self):
        saved=self.workspace.state('draft:codex:test',{'revision':0,'value':{'text':'phone'}})
        self.assertEqual(saved['revision'],1)
        conflict=self.workspace.state('draft:codex:test',{'revision':0,'value':{'text':'desktop'}})
        self.assertEqual(conflict['status'],'conflict')
        self.assertEqual(conflict['value']['text'],'phone')
    def test_command_receipt_cannot_execute_twice(self):
        body={'id':str(uuid.uuid4()),'command':'Write-Output receipt-test'}
        first=self.workspace.terminal('codex','test',body)
        self.assertEqual(first['status'],'completed')
        with patch('coding_remote.workspace.subprocess.Popen',side_effect=AssertionError('duplicated')):
            self.assertEqual(self.workspace.terminal('codex','test',body),first)
        with self.assertRaises(ValueError):self.workspace.terminal('codex','test',{**body,'command':'changed'})
    def test_notification_dedupe_and_initial_snapshot_is_quiet(self):
        def value(state):return {'tasks':[{'backend':'codex','id':'test','title':'Fixture','status':state}]}
        with patch.object(self.workspace,'deliver'):
            self.workspace.observe(value('idle'));self.assertEqual(self.workspace.notifications()['items'],[])
            self.workspace.observe(value('active'));self.workspace.observe(value('completed'));self.workspace.observe(value('completed'))
            self.assertEqual(len(self.workspace.notifications()['items']),1)
            self.workspace.notifications({'action':'read'})
            self.assertEqual(self.workspace.notifications()['items'][0]['unread'],0)
            self.workspace.observe(value('active'));self.workspace.observe(value('completed'))
            self.assertEqual(len(self.workspace.notifications()['items']),2)

    def test_invalid_terminal_environment_does_not_create_receipt(self):
        rid=str(uuid.uuid4())
        with self.assertRaises(ValueError):self.workspace.terminal('codex','test',{'id':rid,'command':'echo hello','environment':'ssh','host':'bad;host'})
        self.assertIsNone(self.workspace.db.execute('SELECT id FROM commands WHERE id=?',(rid,)).fetchone())

    def test_installer_rechecks_download_before_replacing_program(self):
        folder=self.root/'updates';folder.mkdir();source=folder/'CodingRemoteAssistant-2.2.3.exe';source.write_bytes(b'changed')
        source.with_suffix('.exe.verified.json').write_text(json.dumps({'sha256':'0'*64,'size':7}))
        with patch('coding_remote.workspace.sys.frozen',True,create=True),patch('coding_remote.workspace.sys.executable',str(self.root/'CodingRemoteAssistant.exe')):
            with self.assertRaisesRegex(ValueError,'已变更'):self.workspace.prepare_install(source)
    def test_secrets_are_dpapi_encrypted(self):
        self.workspace.secrets.write({'key':'private-fixture-key'})
        self.assertNotIn(b'private-fixture-key',self.workspace.secrets.path.read_bytes())
        self.assertEqual(self.workspace.secrets.read()['key'],'private-fixture-key')

    def test_push_adapters_and_failure_retry_have_visible_receipts(self):
        for kind in ('Webhook','ntfy','Bark','PushPlus'):
            with self.subTest(kind=kind):
                self.workspace.configure_push({'type':kind,'url':'https://push.example/topic','token':'fixture-secret','enabled':True})
                with patch.object(self.workspace,'request_json',return_value={'code':400}):value=self.workspace.notifications({'action':'test'})
                row=value['items'][0];self.assertEqual(row['state'],'failed');self.assertNotIn('fixture-secret',row['error'])
                with patch.object(self.workspace,'request_json',return_value={'code':200}) as delivery:
                    value=self.workspace.notifications({'action':'retry','noticeId':row['id']})
                    request=delivery.call_args.args
                    self.assertTrue(request[0].startswith('https://push.example'))
                    self.assertEqual(next(item for item in value['items'] if item['id']==row['id'])['state'],'sent')

    def test_update_download_checks_hash_and_installer_only_prepares_helper(self):
        content=b'synthetic executable fixture';digest=hashlib.sha256(content).hexdigest()
        asset={'name':'CodingRemoteAssistant-2.2.3.exe','url':'https://github.com/fixture/download.exe','size':len(content),'sha256':digest}
        with patch('coding_remote.workspace.urllib.request.urlopen',return_value=io.BytesIO(content)):result=self.workspace.download_update(asset)
        self.assertEqual(result['status'],'verified')
        current=self.root/'CodingRemoteAssistant.exe';current.write_bytes(b'old executable')
        with patch('coding_remote.workspace.sys.frozen',True,create=True),patch('coding_remote.workspace.sys.executable',str(current)),patch('coding_remote.workspace.subprocess.Popen') as launch:
            self.assertEqual(self.workspace.prepare_install(result['path'])['status'],'ready');launch.assert_called_once()
            self.assertEqual(current.read_bytes(),b'old executable')


class DocumentPreviewTests(unittest.TestCase):
    def test_cached_pages_and_revision_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            service=DocumentPreview(folder);source=Path(folder)/'report.docx';source.write_bytes(b'fixture')
            revision=service.revision(source);pdf=service.root/(revision+'.pdf');pdf.write_bytes(b'%PDF-fixture')
            try:
                value=service.request(source,{})
                self.assertTrue(value['rendered']);self.assertEqual(value['mime'],'application/pdf')
                self.assertTrue(service.request(source,{'known':[value['revision']]})['unchanged'])
                self.assertTrue(service.request(source,{'known':[revision]})['rendered'],'a cached text fallback must not suppress the formatted pages')
                source.write_bytes(b'updated fixture')
                self.assertNotEqual(service.revision(source),revision)
            finally:service.close()

    def test_large_rendered_document_uses_stream_instead_of_base64(self):
        with tempfile.TemporaryDirectory() as folder:
            service=DocumentPreview(folder);source=Path(folder)/'large.pptx';source.write_bytes(b'fixture')
            pdf=service.root/(service.revision(source)+'.pdf')
            with pdf.open('wb') as stream:stream.write(b'%PDF-');stream.truncate(5*1024*1024)
            try:
                value=service.request(source,{})
                self.assertTrue(value['download']);self.assertNotIn('data',value);self.assertEqual(service.cached_path(source),pdf)
            finally:service.close()


class LogReaderTests(unittest.TestCase):
    def test_large_log_is_bounded_and_filters_incremental_lines_by_time(self):
        from coding_remote.desktop_health import LogReader
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'application.log';path.write_text(('2026-11-07 09:00:00 INFO old\n')*100000)
            reader=LogReader();reader.read(folder);self.assertLessEqual(len(reader.lines),10000)
            with path.open('a') as stream:stream.write('2026-11-07 10:01:00 ERROR new error\n2026-11-07 10:10:00 INFO later\n')
            text=reader.read(folder,level='ERROR',since='10:00',until='10:05')
            self.assertIn('new error',text);self.assertNotIn('later',text);self.assertNotIn(' old',text)


class ChatGPTDiscoveryTests(unittest.TestCase):
    def test_list_and_detail_agree_for_legacy_and_current_schemas(self):
        path=Path(__file__).resolve().parents[1]/'packaging/bridge/store.py'
        spec=importlib.util.spec_from_file_location('release_session_store',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        for originator in (True,False):
            with self.subTest(originator=originator),tempfile.TemporaryDirectory() as folder:
                database=Path(folder)/'state_5.sqlite'
                with closing(sqlite3.connect(database)) as conn,conn:
                    conn.execute('CREATE TABLE threads(id TEXT,title TEXT,cwd TEXT,updated_at INTEGER,archived INTEGER,source TEXT,thread_source TEXT'+(',originator TEXT' if originator else '')+')')
                    rows=[('chat','ChatGPT','.',1,0,'ChatGPT_Desktop','user'),('mcp','MCP','.',2,0,'mcp','user'),('child','Child','.',3,0,'{"subagent":{}}','subagent'),('cli','CLI','.',4,0,'cli','user')]
                    for row in rows:conn.execute('INSERT INTO threads VALUES('+','.join('?' for _ in range(8 if originator else 7))+')',row+(('ChatGPT Desktop' if row[0]=='chat' else 'cli',) if originator else ()))
                store=module.SessionStore(folder);listed={row['id'] for row in store.list()}
                self.assertEqual(listed,{'chat','mcp'})
                for sid in listed:self.assertEqual(store.get(sid)['id'],sid)
                for sid in ('child','cli'):
                    with self.assertRaises(KeyError):store.get(sid)


class WorkbenchHttpTests(unittest.TestCase):
    def setUp(self):
        from coding_remote.api import NativeServer
        from coding_remote.pairing import Pairing
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.pairing=Pairing(self.root)
        self.server=NativeServer(types.SimpleNamespace(),types.SimpleNamespace(),self.pairing,self.root)
        self.server.features.task_status=lambda:{'tasks':[]}
        self.server.features.list_snapshot=lambda backend:{'sessions':[{'id':'chat','source':'chatgpt'}]}
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        code=json.loads(self.pairing.issue('http://127.0.0.1:'+str(self.server.server_port)))
        self.auth=self.pairing.pair(code['token'],'fixture','local')
        self.connection=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
    def tearDown(self):
        self.connection.close();self.server.shutdown();self.server.server_close();self.thread.join();self.temp.cleanup()
    def test_unified_stream_shares_identity_and_stops_after_revocation(self):
        self.connection.request('GET','/api/native/events?backends=codex',headers={'Authorization':'Bearer '+self.auth['token']})
        response=self.connection.getresponse();self.assertEqual(response.status,200)
        self.assertEqual(response.getheader('X-Computer-Id'),self.pairing.computer_id)
        events=[]
        while not any(event['topic']=='list:codex' for event in events):
            line=response.readline().decode().strip()
            if line.startswith('data:'):events.append(json.loads(line[5:]))
        listing=next(event for event in events if event['topic']=='list:codex')
        self.assertEqual(listing['data']['sessions'][0]['identity'],'codex:chat')
        self.pairing.revoke(self.auth['deviceId']);self.server.live.publish('status',{'tasks':[],'changed':True})
        # Complete frames can already be in the socket before revocation. Drain
        # those and require EOF; the post-revocation marker must never arrive.
        remaining=response.read()
        self.assertNotIn(b'"changed": true',remaining)
        self.assertNotIn(b'"changed":true',remaining)
    def test_binary_download_etag_and_authorisation(self):
        source=self.root/'图片.png';source.write_bytes(b'fixture-binary')
        self.server.features.resolve_file=lambda backend,sid,query:source
        self.connection.request('GET','/api/native/codex/chat/download?path=file')
        response=self.connection.getresponse();self.assertEqual(response.status,401);response.read()
        self.connection.request('GET','/api/native/codex/chat/download?path=file',headers={'Authorization':'Bearer '+self.auth['token']})
        response=self.connection.getresponse();self.assertEqual(response.status,200);etag=response.getheader('ETag');self.assertEqual(response.read(),b'fixture-binary')
        self.connection.request('GET','/api/native/codex/chat/download?path=file',headers={'Authorization':'Bearer '+self.auth['token'],'If-None-Match':etag})
        response=self.connection.getresponse();self.assertEqual(response.status,304);response.read()

