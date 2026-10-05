import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from coding_remote.api import NativeServer, codex_detail, message_window
from coding_remote.pairing import Pairing


class PairingTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.pairing=Pairing(Path(self.tmp.name))
    def tearDown(self):self.tmp.cleanup()
    def test_single_use_expiry_revocation_and_restart(self):
        code=json.loads(self.pairing.issue('http://100.64.1.2:18787','permanent'))
        device=self.pairing.pair(code['token'],'Android','local')
        self.assertTrue(self.pairing.authenticate(device['token']))
        with self.assertRaises(PermissionError):self.pairing.pair(code['token'],'Android','local')
        loaded=Pairing(Path(self.tmp.name));self.assertTrue(loaded.authenticate(device['token']))
        self.assertNotIn(device['token'],loaded.path.read_text('utf-8'))
        loaded.revoke(device['deviceId']);self.assertIsNone(loaded.authenticate(device['token']))
        code=json.loads(loaded.issue('https://fixture.trycloudflare.com'));loaded.pending['expires']=0
        with self.assertRaises(PermissionError):loaded.pair(code['token'],'Android','local')
    def test_concurrent_pairing_only_issues_one_credential(self):
        code=json.loads(self.pairing.issue('https://fixture.trycloudflare.com'));out=[]
        def pair():
            try:out.append(self.pairing.pair(code['token'],'Android','local'))
            except PermissionError:pass
        threads=[threading.Thread(target=pair) for _ in range(8)]
        for t in threads:t.start()
        for t in threads:t.join()
        self.assertEqual(len(out),1)


class MessageWindowTests(unittest.TestCase):
    def test_large_history_pages_preserve_text_and_bound_payload(self):
        text=('汉字😀abcdef\n'*50000)
        source={'session':{'id':'fixture'},'messages':[{'id':'m','role':'assistant','text':text}]}
        pages=[];before=None
        while True:
            page=message_window(source,before,60)
            self.assertLessEqual(len(page['messages']),60)
            self.assertTrue(all(len(m['text'])<=3000 for m in page['messages']))
            pages.insert(0,page['messages'])
            if not page['history']['hasEarlier']:break
            before=page['history']['before']
        messages=[m for p in pages for m in p]
        self.assertEqual(''.join(m['text'] for m in messages),text)
        self.assertEqual(len({m['id'] for m in messages}),len(messages))
        self.assertEqual(message_window(source)['revision'],message_window(source)['revision'])

    def test_window_does_not_change_permissions_and_rejects_negative_cursor(self):
        source={'session':{'requests':[{'id':'approve'}]},'messages':[]}
        self.assertEqual(message_window(source)['session']['requests'],source['session']['requests'])
        with self.assertRaises(ValueError):message_window(source,-1)


class NativeApiTests(unittest.TestCase):
    def setUp(self):
        import types
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.pair=Pairing(self.root)
        class Bridge:
            def list(self,**kwargs):return [{'id':'fixture','title':'Fixture','cwd':'D:/fixture'}]
            def view(self,*args,**kwargs):return {'id':'fixture','turns':[{'messages':[{'role':'user','text':'hello'}]}]}
        self.server=NativeServer(Bridge(),types.SimpleNamespace(desktop=types.SimpleNamespace(connected=False)),self.pair,self.root)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.base=f'http://127.0.0.1:{self.server.server_port}'
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join();self.tmp.cleanup()
    def call(self,path,body=None,token='',origin=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        if origin:headers['Origin']=origin
        req=Request(self.base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
        try:
            with urlopen(req) as r:return r.status,json.load(r)
        except HTTPError as e:return e.code,json.load(e)
    def test_pair_then_native_list_no_password_or_page(self):
        self.assertEqual(self.call('/api/native/codex')[0],401)
        self.assertEqual(self.call('/api/login',{'password':'anything'})[0],401)
        code=json.loads(self.pair.issue(self.base));status,value=self.call('/api/pair',{'token':code['token'],'device':'Fixture'})
        self.assertEqual(status,200);token=value['token']
        self.assertEqual(self.call('/api/native/codex',token=token)[1]['sessions'][0]['title'],'Fixture')
        self.assertEqual(self.call('/',token=token)[0],404)
        self.assertEqual(self.call('/api/native/codex',token=token,origin='https://evil.example')[0],403)
        self.assertEqual(self.call('/api/native/codex/fixture',token=token)[1]['messages'][0]['text'],'hello')
        self.assertEqual(self.call('/api/unpair',{},token=token)[0],200)
        self.assertEqual(self.call('/api/native/codex',token=token)[0],401)
    def test_approval_forms_are_not_silently_treated_as_simple_approval(self):
        value=codex_detail({'id':'x','requests':[{'id':'p','method':'item/tool/requestUserInput','params':{}}]})
        self.assertTrue(value['session']['requests'][0]['needsInput'])

    def test_late_private_listener_registers_before_host_check(self):
        import http.client
        private='http://100.117.130.112:18787'
        calls=[]
        self.server.refresh_ingress=lambda:(calls.append(True),self.server.allow_origin(private))
        connection=http.client.HTTPConnection('127.0.0.1',self.server.server_port)
        try:
            connection.request('GET','/api/native/codex',headers={'Host':'100.117.130.112:18787','Origin':private})
            response=connection.getresponse();response.read();self.assertEqual(response.status,401,'valid late ingress reaches authentication instead of 403')
            connection.request('GET','/api/native/codex',headers={'Host':'untrusted.example','Origin':private})
            response=connection.getresponse();response.read();self.assertEqual(response.status,403,'refresh does not trust request Host')
            self.assertTrue(calls)
        finally:connection.close()
