"""Packaged smoke checks using temporary data and a recording metadata transport."""
import json
import sqlite3
import tempfile
import threading
import types
import uuid
from contextlib import closing
from pathlib import Path

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
            for source in ('vscode','cli','mcp'):
                db.execute('INSERT INTO threads VALUES(?,?,?,?,?,0,1)',(sid if source=='cli' else str(uuid.uuid4()),'fixture',str(root),str(path),source))
            db.commit()
        actual=store.SessionStore(root)
        for row in actual.list():assert actual.get(row['id'])['id']==row['id']
        assert actual.history(sid)['turns'][0]['items'][0]['text']=='fixture response'
        original=actions.MetadataRuntime;actions.MetadataRuntime=Runtime
        server=NativeServer(types.SimpleNamespace(store=actual),types.SimpleNamespace(),Pairing(root),root)
        try:
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
    return {'storeSource':str(Path(store.__file__).name),'serviceUsesPatchedStore':True,'schemaWithoutOriginator':True,'transcriptRead':True,'deleteDispatchAndDeduplication':True,'nativePluginMentionThroughFrozenBridge':True}
