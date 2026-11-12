import copy
import json
import tempfile
import types
import unittest
import uuid
from pathlib import Path

from coding_remote.api import NativeServer
from coding_remote.message_edit import draft, validate
from coding_remote.pairing import Pairing


class MessageEditTests(unittest.TestCase):
    def detail(self):
        return {'session': {'status': 'idle'}, 'messages': [
            {'id': 'u1', 'turnId': 't1', 'role': 'user', 'text': 'Original'},
            {'id': 'a1', 'turnId': 't1', 'role': 'assistant', 'text': 'Answer'}]}

    def test_stale_or_active_history_cannot_be_overwritten(self):
        detail=self.detail();body={**draft(detail,'u1'),'confirmed':True,'text':'Edited'}
        validate(detail,body)
        changed=copy.deepcopy(detail);changed['messages'].append({'id':'u2','role':'user','text':'New message'})
        with self.assertRaises(ValueError):validate(changed,body)
        detail['session']['status']='inProgress'
        with self.assertRaises(ValueError):validate(detail,body)
        with self.assertRaises(ValueError):draft(self.detail(),'a1')

    def test_codex_owner_edit_is_idempotent_and_preserves_settings(self):
        class Bridge:
            store=types.SimpleNamespace(get=lambda sid:{'id':sid,'cwd':'D:/fixture'})
            def __init__(self):self.calls=[]
            def view(self,*args,**kwargs):return {'id':'fixture','status':'idle','turns':[{'id':'t1','messages':[{'id':'u1','role':'user','text':'Original'}]}]}
            def _target(self,sid):return 'desktop-owner'
            def _call(self,target,method,params,**kwargs):self.calls.append((target,method,params));return {'ok':True}
        with tempfile.TemporaryDirectory() as folder:
            bridge=Bridge();server=NativeServer(bridge,types.SimpleNamespace(),Pairing(folder),Path(folder))
            try:
                detail=server.dispatch('codex','fixture',None,{})
                body={**draft(detail,'u1'),'text':'Edited','confirmed':True,'id':str(uuid.uuid4())}
                for _ in range(2):self.assertEqual(server.dispatch('codex','fixture','edit',body)['status'],'accepted')
                self.assertEqual(len(bridge.calls),1)
                target,method,params=bridge.calls[0]
                self.assertEqual(target,'desktop-owner');self.assertEqual(method,'thread-follower-edit-last-user-turn')
                self.assertEqual(params['turnId'],'t1');self.assertEqual(params['message'],'Edited')
                self.assertFalse(params['shouldSendPermissionOverrides']);self.assertNotIn('model',params)
            finally:server.server_close()

    def test_claude_image_message_cannot_lose_attachment_on_edit(self):
        from claude_bridge.model import transcript
        messages=transcript([{'uuid':'u-image','type':'user','message':{'role':'user','content':[
            {'type':'text','text':'Describe this'},
            {'type':'image','source':{'type':'base64','media_type':'image/png','data':'fixture'}}]}}])
        self.assertTrue(messages[0]['hasAttachments'])
        with self.assertRaisesRegex(ValueError,'附件'):
            draft({'session':{'status':'idle'},'messages':messages},messages[0]['id'])

