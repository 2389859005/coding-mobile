import json
import tempfile
import threading
import types
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from coding_remote.api import NativeServer,codex_detail,message_window
from coding_remote.conversation import codex_response,summarize
from coding_remote.pairing import Pairing
from coding_remote.deepseek import DeepSeek
from claude_bridge.model import transcript


class MobileRevisionTests(unittest.TestCase):
    def test_stored_tool_noise_preserves_plan_and_patch_only_once(self):
        patch_body='*** Begin Patch\n*** Update File: a.py\n-old\n+new\n*** Add File: b.py\n+second\n*** End Patch'
        turn={'id':'t','messages':[
            {'kind':'storedToolEvent','title':'functions.exec_command','text':'{"cmd":"private command"}'},
            {'kind':'storedToolEvent','title':'functions.update_plan','text':json.dumps({'plan':[{'step':'Verify','status':'completed'}]})},
            {'kind':'storedToolEvent','title':'functions.apply_patch','text':patch_body}]}
        result=message_window(codex_detail({'id':'s','turns':[turn]}))
        self.assertNotIn('private command',json.dumps(result))
        self.assertEqual(result['steps'],[{'content':'Verify','status':'completed'}])
        self.assertEqual(result['changes'],{'files':2,'added':2,'deleted':1})
        turn['messages'].append({'kind':'fileChange','text':'a.py\n-old\n+new'})
        result=message_window(codex_detail({'id':'s','turns':[turn]}))
        self.assertEqual(result['changes']['added'],1)

    def test_claude_task_creation_update_and_deletion_keep_full_plan(self):
        events=[{'type':'assistant','message':{'content':[{'type':'tool_use','id':'create','name':'TaskCreate','input':{'subject':'Verify layout'}}]}},
                {'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'create','content':'Task #7 created successfully: Verify layout'}]}},
                {'type':'assistant','message':{'content':[{'type':'tool_use','name':'TaskUpdate','input':{'taskId':'7','status':'completed'}},{'type':'tool_use','name':'Read','input':{'path':'a.py'}}]}}]
        result=summarize({'messages':transcript(events)})
        self.assertEqual(result['steps'],[{'content':'Verify layout','status':'completed'}])
        events.append({'type':'assistant','message':{'content':[{'type':'tool_use','name':'TaskUpdate','input':{'taskId':'7','status':'deleted'}}]}})
        self.assertEqual(summarize({'messages':transcript(events)})['steps'],[])

    def test_command_noise_removed_but_code_and_reasoning_preserved(self):
        value=codex_detail({'id':'s','turns':[{'id':'t','messages':[
            {'role':'user','text':'hello'}, {'kind':'commandExecution','text':'bash secret-command'},
            {'kind':'reasoning','role':'reasoning','text':'Visible summary'},
            {'kind':'fileChange','text':'a.py\n@@ -1 +1 @@\n-old\n+new'},
            {'role':'assistant','text':'done'}]}]})
        self.assertNotIn('secret-command',json.dumps(value))
        result=message_window(value,limit=1)
        self.assertEqual(result['session']['turnCount'],1)
        self.assertEqual(result['changes'],{'added':1,'deleted':1,'files':1})
        self.assertTrue(result['history']['hasEarlier'])

    def test_turn_count_is_before_transport_chunks_and_excludes_tools(self):
        rows=transcript([{'type':'user','uuid':'u','message':{'role':'user','content':[
            {'type':'text','text':'x'*12000},{'type':'tool_result','content':'output'},{'type':'text','text':'continued'}]}}])
        result=message_window({'session':{},'messages':rows},limit=1)
        self.assertEqual(result['session']['turnCount'],1)

    def test_sync_and_async_question_answers_match_actual_bridge_contract(self):
        for method in ('item/tool/requestUserInput','bridge/requestUserInputAsync'):
            permission={'method':method,'needsInput':True,'input':{'questions':[{'id':'q','question':'Which?'}]}}
            self.assertEqual(codex_response(permission,{'answers':{'q':{'answers':['choice','custom']}}}),{'answers':{'q':['choice','custom']}})
            with self.assertRaises(ValueError):codex_response(permission,{'answers':{}})

    def test_binding_survives_time_restart_and_explicit_revocation(self):
        with tempfile.TemporaryDirectory() as folder:
            pair=Pairing(folder);code=json.loads(pair.issue('http://100.64.1.2:18787'))
            device=pair.pair(code['token'],'Phone','local',{'installationId':'install','model':'model'})
            self.assertIsNone(device['expires'])
            with patch('coding_remote.pairing.time.time',return_value=99999999999):
                loaded=Pairing(folder);self.assertEqual(loaded.authenticate(device['token'])['installationId'],'install')
                loaded.revoke(device['deviceId']);self.assertIsNone(loaded.authenticate(device['token']))

    def test_claude_generic_tool_includes_actual_arguments_and_output(self):
        rows=transcript([{'type':'assistant','message':{'content':[{'type':'tool_use','name':'search','input':{'pattern':'needle','glob':'*.py'}}]}},
                         {'type':'user','message':{'content':[{'type':'tool_result','content':[{'type':'text','text':'found in a.py'}]}]}}])
        self.assertIn('needle',rows[0]['text']);self.assertIn('found in a.py',rows[1]['text'])

    def test_deepseek_transcript_omits_configuration_and_counts_turns(self):
        records=[{'event':{'seq':i,'type':kind,'data':data}} for i,(kind,data) in enumerate([
            ('request/header',{'token':'PRIVATE'}),('turn/start',{}),('user/message',{'content':[{'type':'text','text':'question'}]}),
            ('assistant/message',{'message':{'content':[{'type':'text','text':'answer'}]}}),('todo/write',{'todos':[{'content':'check','status':'completed'}]})])]
        value=DeepSeek.detail({'session':{'id':'s'},'records':records})
        self.assertNotIn('PRIVATE',json.dumps(value));self.assertEqual(value['session']['turnCount'],1);self.assertEqual(len(value['messages']),2)

    def test_deepseek_changes_use_successful_result_hunks(self):
        records=[{'event':{'seq':i,'type':'tool/result','data':{'message':{'content':[{'type':'text','text':'edited'}],'isError':failed},'meta':{'diffs':[{'path':'a.py','oldText':'same\nold','newText':'same\nnew\nmore'}]}}}} for i,failed in enumerate([False,True])]
        result=summarize(DeepSeek.detail({'session':{'id':'s'},'records':records}))
        self.assertEqual(result['changes'],{'files':1,'added':2,'deleted':1})

    def test_codex_running_send_steers_and_settings_accept_current_desktop_ack(self):
        sid=str(uuid.uuid4());sent=[]
        target=types.SimpleNamespace(action_lock=threading.RLock(),condition=threading.RLock(),state={})
        bridge=types.SimpleNamespace(view=lambda *a,**k:{'id':sid,'status':'active','turns':[]},
            send=lambda *args:sent.append(args) or {'status':'accepted'},catalog=lambda *a,**k:{'models':[{'id':'m','efforts':['high']}],'currentModel':'m'},
            _target=lambda s:target,session=lambda *a,**k:target,_call=lambda *a:{'ok':True})
        with tempfile.TemporaryDirectory() as folder:
            server=NativeServer(bridge,types.SimpleNamespace(),Pairing(folder),folder)
            try:
                body={'id':str(uuid.uuid4()),'text':'follow-up','plugins':['p']}
                result=server.dispatch('codex',sid,'send',body);self.assertEqual(result['status'],'accepted')
                self.assertEqual(sent[0][3],'steer');self.assertEqual(sent[0][4],['p'])
                server.dispatch('codex',sid,'send',body);self.assertEqual(len(sent),1)
                self.assertEqual(server.dispatch('codex',sid,'settings',{'id':str(uuid.uuid4()),'model':'m','effort':'high'})['status'],'accepted')
            finally:server.server_close()

if __name__=='__main__':unittest.main()
