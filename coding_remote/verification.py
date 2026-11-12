"""Packaged smoke checks using temporary data and a recording metadata transport."""
import json
import sqlite3
import tempfile
import threading
import types
import uuid
from contextlib import closing
from pathlib import Path

def gateway_binding_checks():
    """Real sockets reproduce an occupied Windows port without touching user apps."""
    import urllib.request
    import urllib.error
    from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
    from .api import NativeServer
    from .pairing import Pairing
    class ForeignHandler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            self.send_response(405);self.end_headers()
            self.wfile.write(b'Only HTTP POST Responses; no WebSocket')
    foreign=ThreadingHTTPServer(('127.0.0.1',0),ForeignHandler)
    foreign_thread=threading.Thread(target=foreign.serve_forever,daemon=True);foreign_thread.start()
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with tempfile.TemporaryDirectory(prefix='coding-port-check-') as folder:
            root=Path(folder);pairing=Pairing(root);bridge=types.SimpleNamespace();claude=types.SimpleNamespace()
            try:unexpected=NativeServer(bridge,claude,pairing,root,bind_port=foreign.server_port)
            except OSError:pass
            else:
                unexpected.server_close();raise AssertionError('Occupied port was accepted')
            server=NativeServer.bind_available(bridge,claude,pairing,root,foreign.server_port)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            try:
                if server.server_port==foreign.server_port:raise AssertionError('Port did not change')
                origin='http://127.0.0.1:'+str(server.server_port)
                with opener.open(origin+'/api/gateway',timeout=3) as response:identity=json.load(response)
                if identity.get('computerId')!=pairing.computer_id:raise AssertionError('Wrong gateway identity')
                code=json.loads(pairing.issue(origin,'temporary'))
                body=json.dumps({'token':code['token'],'device':'isolated fixture','requestId':str(uuid.uuid4())}).encode()
                request=urllib.request.Request(origin+'/api/pair',data=body,headers={'Content-Type':'application/json'})
                with opener.open(request,timeout=3) as response:receipt=json.load(response)
                if not receipt.get('token') or receipt.get('computerId')!=pairing.computer_id:raise AssertionError('Pairing failed on new port')
                try:opener.open('http://127.0.0.1:'+str(foreign.server_port)+'/api/gateway',timeout=3)
                except urllib.error.HTTPError as error:
                    if error.code!=405:raise
                    error.close()
                else:raise AssertionError('Existing service was changed')
            finally:server.shutdown();server.server_close();thread.join()
    finally:foreign.shutdown();foreign.server_close();foreign_thread.join()
    return {'occupiedPortRejected':True,'freePortSelected':True,'gatewayIdentity':True,'pairingPost':True,'existingServicePreserved':True}

def packaged_checks():
    import bridge.store as store
    import bridge.service as service
    import bridge.actions as actions
    from .api import NativeServer
    from .pairing import Pairing
    assert service.Bridge.__init__.__globals__['SessionStore'] is store.SessionStore, 'Frozen service captured a stale SessionStore'
    recorded=[]
    class Runtime:
        def __init__(self,*args):pass
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def request(self,method,params):recorded.append((method,params));return {}
    with tempfile.TemporaryDirectory(prefix='coding-packaged-check-') as folder:
        root=Path(folder);(root/'sessions').mkdir();sid=str(uuid.uuid4());path=root/'sessions/test.jsonl'
        path.write_text(json.dumps({'type':'response_item','payload':{'type':'message','role':'assistant','content':[{'text':'fixture response'}]}})+'\n','utf-8')
        with closing(sqlite3.connect(root/'state_1.sqlite')) as db:
            db.execute('CREATE TABLE threads(id TEXT, title TEXT, cwd TEXT, rollout_path TEXT, source TEXT, archived INTEGER, updated_at INTEGER)')
            for source in ('vscode','cli','mcp','ChatGPT_Desktop'):
                db.execute('INSERT INTO threads VALUES(?,?,?,?,?,0,1)',(sid if source=='vscode' else str(uuid.uuid4()),'fixture',str(root),str(path),source))
            db.commit()
        actual=store.SessionStore(root)
        for row in actual.list():assert actual.get(row['id'])['id']==row['id']
        assert actual.history(sid)['turns'][0]['items'][0]['text']=='fixture response'
        # Exercise the original frozen Bridge normalizer, not only synthetic
        # already-normalized messages passed directly to the native API.
        import bridge.model as bridge_model
        from .api import codex_detail
        picture=root/'read-image.png';picture.write_bytes(b'\x89PNG\r\n\x1a\nfixture')
        with path.open('a',encoding='utf-8') as transcript:
            transcript.write(json.dumps({'type':'response_item','payload':{'type':'custom_tool_call','call_id':'read-image','name':'functions.exec','input':'image((await tools.view_image({path:'+json.dumps(str(picture))+'})).image_url);'}})+'\n')
        image_rows=codex_detail(bridge_model.normalize_state(actual.history(sid),False))['messages']
        assert any(row.get('kind')=='image' and str(picture) in row['text'] for row in image_rows),'Frozen Bridge lost the image-read path'
        original=actions.MetadataRuntime;actions.MetadataRuntime=Runtime
        server=NativeServer(types.SimpleNamespace(store=actual),types.SimpleNamespace(),Pairing(root),root)
        try:
            assert server.features.read_image_path('codex',sid,str(picture))==picture.resolve()
            rid=str(uuid.uuid4());body={'id':rid,'confirmed':True}
            assert server.dispatch('codex',sid,'delete',body)['status']=='accepted'
            assert server.dispatch('codex',sid,'delete',body)['status']=='accepted'
            assert recorded==[('thread/delete',{'threadId':sid})],recorded
        finally:server.server_close();actions.MetadataRuntime=original
        from .codex_plugins import PluginBridgeMixin
        class PluginFixture(PluginBridgeMixin,service.Bridge):
            def plugin_inventory(self,*args,**kwargs):
                return {'marketplaces':[{'plugins':[{'id':'nature@fixture','name':'nature','enabled':True,'installed':True}]}]}
            def _save_ledger(self):pass
        adapter=object.__new__(PluginFixture);adapter.data_dir=root;adapter.host='local'
        captured=[]
        def request(method,params,**kwargs):captured.append((method,params));return {'result':{'ok':True}}
        adapter.ipc=types.SimpleNamespace(request=request)
        session=types.SimpleNamespace(id=sid,owner='fixture',condition=threading.RLock(),changed=lambda:None)
        selected=adapter._resolve_skills(session,['plugin:nature@fixture'])
        rid=str(uuid.uuid4());key=sid+':'+rid
        entry={'text':'fixture only','mode':'send','skills':selected,'images':[],'status':'pending'}
        adapter.submit_lock=threading.Lock();adapter.submissions={key:entry}
        adapter._dispatch(session,key,entry)
        assert captured[0][0]=='thread-follower-start-turn'
        assert captured[0][1]['turnStart']['request']['input'][-1]=={'type':'mention','name':'nature','path':'plugin://nature@fixture'}
    return {'storeSource':str(Path(store.__file__).name),'serviceUsesPatchedStore':True,'schemaWithoutOriginator':True,'transcriptRead':True,'imageReadThroughFrozenBridge':True,'deleteDispatchAndDeduplication':True,'nativePluginMentionThroughFrozenBridge':True,'gatewayBinding':gateway_binding_checks()}


def packaged_ui_checks():
    """Exercise the frozen widgets with temporary settings and no business services."""
    from . import desktop
    original=desktop.DATA
    with tempfile.TemporaryDirectory(prefix='coding-ui-check-') as folder:
        desktop.DATA=Path(folder)
        class TestDesktop(desktop.Desktop):
            def start_services(self):pass
        window=TestDesktop();window.withdraw()
        try:
            work=window.workbench
            for page in work.PAGES:window.show_tab(page);window.update_idletasks()
            assert not hasattr(work,'composer')
            assert set(work.PAGES)=={'概览','账号与模型','查看日志','应用设置'}
            assert window.qr_holder.master is window.pair_card
            assert window.pair_card.master is window.frames['概览']
            assert not hasattr(work,'pet') and not hasattr(window,'settings_button')
            assert not hasattr(window,'theme_choice')
            window.show_tab('应用设置');window.geometry('700x650');window.deiconify();window.update()
            for status in (window.network_status,window.server_config_status):assert not status.winfo_manager()
            for field in window.server_fields.values():
                assert field.winfo_width()>=160
                assert field.winfo_x()+field.winfo_width()<=field.master.winfo_width()
            window.set_settings_status(window.network_status,'验证提示');window.update_idletasks()
            assert window.network_status.winfo_manager()=='pack'
            window.set_settings_status(window.network_status,'');window.update_idletasks()
            assert not window.network_status.winfo_manager()
            return {'pages':list(work.PAGES),'workbenchRemoved':True,'qrHome':True,'desktopPetRemoved':True,'settingsFitNarrowWindow':True,'emptyStatusCollapsed':True,'businessServicesStarted':False}
        finally:window.close();desktop.DATA=original
