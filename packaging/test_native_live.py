"""Run the Android supervisor against a real, isolated synthetic Windows gateway."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import types

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from coding_remote.api import NativeServer,Handler
from coding_remote.pairing import Pairing


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--serial',required=True);args=parser.parse_args()
    if not args.serial.startswith('emulator-'):raise ValueError('Only an isolated Android emulator is supported')
    with tempfile.TemporaryDirectory(prefix='live-fixture-',dir=ROOT/'.local') as folder:
        root=Path(folder);pairing=Pairing(root);server=NativeServer(types.SimpleNamespace(),types.SimpleNamespace(desktop=types.SimpleNamespace(connected=False)),pairing,root)
        origin='http://10.0.2.2:'+str(server.server_port);server.allow_origin(origin)
        code=json.loads(pairing.issue(origin,'permanent'));credential=pairing.pair(code['token'],'synthetic emulator','local')
        auth={'origin':origin,'token':credential['token'],'computerId':pairing.computer_id,'mode':'permanent'}
        state={'step':0,'sse':True,'freeze':False,'wrongGateway':False,'pairPosts':0,'upgrades':0,'nativeReads':0};connections=set();lock=threading.Lock()
        def listing(backend):return {'sessions':[{'id':'fixture','title':'Live fixture','cwd':str(root),'source':'chatgpt'}],'connected':True}
        def detail(backend,sid,action,body):
            if sid is None:return listing(backend)
            return {'session':{'id':sid,'title':'Live fixture','cwd':str(root),'status':'idle'},'messages':[{'role':'assistant','text':'Step '+str(state['step'])}],'connected':True}
        server.dispatch=detail;server.features.task_status=lambda:{'step':state['step'],'tasks':[{'id':'fixture','backend':'codex','title':'Live fixture','status':'active','progress':'Background step '+str(state['step'])}]}
        server.features.list_snapshot=listing;server.features.stream_token=lambda backend,sid:'stable-metadata'
        class FixtureHandler(Handler):
            def event(self,value=None,comment=None):
                if state['freeze'] and value and value.get('topic') in ('status','session:codex:fixture'):
                    return super().event(comment='transport-alive-source-stalled')
                return super().event(value,comment)
            def handle_api(self,write):
                if not write and self.path.startswith('/api/native/') and '/events' not in self.path:state['nativeReads']+=1
                if self.headers.get('Upgrade'):state['upgrades']+=1
                if write and self.path=='/api/pair':state['pairPosts']+=1
                return super().handle_api(write)
            def do_GET(self):
                path=self.path.split('?')[0]
                if path=='/fixture/qr':return self.output(200,json.loads(pairing.issue(origin,'permanent')))
                if path=='/fixture/wrong-gateway':state['wrongGateway']=True;return self.output(200,{'ok':True})
                if path=='/fixture/restore-gateway':state['wrongGateway']=False;return self.output(200,{'ok':True})
                if path=='/fixture/metrics':return self.output(200,state)
                if path=='/fixture/freeze':state['freeze']=True;return self.output(200,{'ok':True})
                if path=='/fixture/unfreeze':state['freeze']=False;return self.output(200,{'ok':True})
                if path=='/api/gateway' and state['wrongGateway']:return self.output(400,{'error':'Only HTTP POST Responses is supported; WebSocket is not supported'})
                if path=='/fixture/auth':return self.output(200,auth)
                if path=='/fixture/advance':
                    state['step']+=1;server.live.invalidate();return self.output(200,{'step':state['step']})
                if path in ('/fixture/drop','/fixture/restore'):
                    state['sse']=path.endswith('restore')
                    if not state['sse']:
                        with lock:active=list(connections)
                        for connection in active:
                            try:connection.shutdown(socket.SHUT_RDWR)
                            except OSError:pass
                    return self.output(200,{'ok':True})
                return super().do_GET()
            def unified_events(self,query):
                if not state['sse']:return self.output(404,{'error':'synthetic legacy gateway'})
                with lock:connections.add(self.connection)
                try:return super().unified_events(query)
                finally:
                    with lock:connections.discard(self.connection)
        server.RequestHandlerClass=FixtureHandler
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            for suite in ('GatewayRegression','RealtimeRegression','BackgroundRealtimeRegression'):
                subprocess.run([sys.executable,str(ROOT/'packaging/test_native_ui.py'),'--serial',args.serial,'--suite',suite,'--live-origin',origin],check=True)
        finally:server.shutdown();server.server_close();thread.join()


if __name__=='__main__':main()
