"""Exercise the Claude controller used by the native API, without a web frontend."""
import tempfile
import unittest
import uuid
from pathlib import Path

from claude_bridge import model
from claude_bridge.packaged import APIError, Controller


class FakeDesktop:
    connected = True

    def __init__(self, writes):
        self.writes = writes
        self.raw = {'sessionId': 'local_test', 'title': 'Fixture'}
        self.capabilities = {'code': ['getAll', 'getSession', 'getTranscript', 'sendMessage', 'interrupt']}

    async def call(self, kind, method, *args, **kwargs):
        if method == 'getAll':
            return [self.raw]
        if method == 'getSession':
            return self.raw
        if method == 'getTranscript':
            return []
        field = {'setPermissionMode': 'permissionMode', 'setModel': 'model', 'setEffort': 'effort'}.get(method)
        if field:
            self.raw[field] = args[1]
        self.writes.append((method, args))

    def close(self):
        pass


class ClaudeControllerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.controller = Controller(Path(self.tmp.name) / '.coding-remote')
        self.controller.desktop.close()
        self.writes = []
        self.controller.desktop = FakeDesktop(self.writes)

    def tearDown(self):
        self.controller.close()
        self.controller.loop.close()
        self.tmp.cleanup()

    def dispatch(self, path, method='GET', body=None):
        return self.controller.run(self.controller.dispatch(path, {}, method, body))

    def test_dispatch_preserves_session_ids_and_deduplicates(self):
        sid = model.gateway_id('code', 'local_test')
        self.assertEqual(self.dispatch('/sessions')['sessions'][0]['id'], sid)
        self.assertEqual(self.dispatch('/sessions/' + sid)['messages'], [])
        body = {'id': str(uuid.uuid4()), 'text': 'hello'}
        path = '/sessions/' + sid + '/send'
        for _ in range(2):
            self.assertEqual(self.dispatch(path, 'POST', body)['status'], 'accepted')
        self.assertEqual(self.writes, [('sendMessage', ('local_test', 'hello'))])
        with self.assertRaises(APIError) as conflict:
            self.dispatch(path, 'POST', {**body, 'text': 'changed'})
        self.assertEqual(conflict.exception.status, 409)

    def test_wrong_action_does_not_dispatch(self):
        sid = model.gateway_id('code', 'local_test')
        with self.assertRaises(APIError) as unknown:
            self.dispatch('/sessions/' + sid + '/unsupported', 'POST', {'id': str(uuid.uuid4())})
        self.assertEqual(unknown.exception.status, 404)
        self.assertEqual(self.writes, [])

    def test_metadata_actions_confirm_and_deduplicate(self):
        self.controller.desktop.capabilities['code'] += ['delete', 'archive', 'updateSession']
        sid = model.gateway_id('code', 'local_test')
        for action in ('delete', 'archive'):
            path = '/sessions/' + sid + '/' + action
            with self.assertRaises(ValueError):
                self.dispatch(path, 'POST', {'id': str(uuid.uuid4())})
            body = {'id': str(uuid.uuid4()), 'confirmed': True}
            for _ in range(2):
                self.assertEqual(self.dispatch(path, 'POST', body)['status'], 'accepted')
        self.assertEqual(self.writes, [('delete', ('local_test',)), ('archive', ('local_test',))])


    def test_images_use_native_third_argument_and_access_is_deduplicated(self):
        controller=self.controller
        controller.desktop.capabilities['code'].append('setPermissionMode')
        sid=model.gateway_id('code','local_test')
        self.assertIn('acceptEdits',controller.run(controller.access(sid))['modes'])
        request={'id':str(uuid.uuid4()),'text':'','resolvedImages':[{'url':'data:image/png;base64,aGVsbG8='}]}
        self.assertEqual(controller.run(controller.mutate(sid,'send',request))['status'],'accepted')
        self.assertEqual(self.writes[-1],('sendMessage',('local_test','',[{'mimeType':'image/png','base64':'aGVsbG8='}])))
        request={'id':str(uuid.uuid4()),'mode':'acceptEdits'}
        controller.run(controller.mutate(sid,'access',request))
        controller.run(controller.mutate(sid,'access',request))
        self.assertEqual([w for w in self.writes if w[0]=='setPermissionMode'],[('setPermissionMode',('local_test','acceptEdits'))])

    def test_create_is_durable_and_unknown_never_restarts(self):
        from claude_bridge.errors import BridgeUnavailable
        controller=self.controller
        controller.desktop.capabilities['code'].append('start')
        original=controller.desktop.call
        async def create(kind,method,*args,**kwargs):
            if method=='start':self.writes.append((method,args));return {'sessionId':'local_created'}
            return await original(kind,method,*args,**kwargs)
        controller.desktop.call=create
        project={'key':'fixture','cwd':'D:/fixture','surface':'code'};rid=str(uuid.uuid4())
        first=controller.run(controller.create_session(project,'Test',rid))
        self.assertEqual(first['result']['id'],model.gateway_id('code','local_created'))
        self.assertEqual(first,controller.run(controller.create_session(project,'Test',rid)))
        self.assertEqual(first,controller.run(controller.creation_status(rid)))
        self.assertEqual(len(self.writes),1)
        async def unknown(*args,**kwargs):self.writes.append(('uncertain',()));raise BridgeUnavailable('timeout')
        controller.desktop.call=unknown;rid=str(uuid.uuid4())
        first=controller.run(controller.create_session(project,'Test',rid))
        self.assertEqual(first['status'],'unknown')
        self.assertEqual(first,controller.run(controller.create_session(project,'Test',rid)))
        self.assertEqual(len(self.writes),2)

    def test_model_and_effort_are_both_applied_verified_and_deduplicated(self):
        controller=self.controller
        controller.desktop.capabilities['code'] += ['setModel','setEffort']
        async def catalog(_,section="all"):return {'models':[{'id':'fixture-model','efforts':['low','high']}]}
        controller.catalog=catalog
        sid=model.gateway_id('code','local_test');body={'id':str(uuid.uuid4()),'model':'fixture-model','effort':'high'}
        first=controller.run(controller.mutate(sid,'settings',body))
        self.assertEqual(first['status'],'accepted')
        self.assertEqual(first,controller.run(controller.mutate(sid,'settings',body)))
        self.assertEqual(self.writes,[('setModel',('local_test','fixture-model')),('setEffort',('local_test','high'))])
        with self.assertRaises(ValueError):controller.run(controller.mutate(sid,'settings',{**body,'id':str(uuid.uuid4()),'effort':'max'}))
        self.assertEqual(len(self.writes),2)
        original=controller.desktop.call
        async def clamp(kind,method,*args,**kwargs):
            result=await original(kind,method,*args,**kwargs)
            if method=='setEffort':controller.desktop.raw['effort']='low'
            return result
        controller.desktop.call=clamp
        result=controller.run(controller.mutate(sid,'settings',{**body,'id':str(uuid.uuid4())}))
        self.assertEqual(result['status'],'rejected');self.assertEqual(result['actual']['effort'],'low')

    def test_commands_use_options_for_both_surfaces(self):
        controller=self.controller
        for kind in ('code','cowork'):
            sid=model.gateway_id(kind,'local_test')
            controller.index[sid]={'backend':kind,'nativeId':'local_test','cwd':'D:/fixture'}
            controller.desktop.capabilities[kind]=['getSupportedCommands']
            async def call(surface,method,*args,**kwargs):
                self.assertEqual(surface,kind)
                self.assertEqual(method,'getSupportedCommands')
                self.assertEqual(args,({'sessionId':'local_test','cwd':'D:/fixture'},))
                return []
            controller.desktop.call=call
            controller.run(controller.catalog(sid,'plugins'))

    def test_model_catalog_does_not_wait_for_plugin_commands(self):
        controller=self.controller;sid=model.gateway_id('code','local_test')
        controller.run(controller.session(sid));controller.index[sid]['model']='desktop-model'
        controller.desktop.capabilities['code']+=['mobileCatalog','getSupportedCommands','setModel']
        requested=[]
        async def call(kind,method,*args,**kwargs):
            requested.append(method)
            if method=='getSupportedCommands':raise AssertionError('models must not load plugins')
            return {'models':[{'id':'second-model','name':'Second'}]}
        controller.desktop.call=call
        result=controller.run(controller.catalog(sid,'models'))
        self.assertEqual([m['id'] for m in result['models']],['second-model','desktop-model'])
        self.assertEqual(requested,['mobileCatalog'])
