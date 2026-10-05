"""Session identity and transcript projection used by the native Claude adapter."""
import json
import unittest
import uuid

from claude_bridge import model


class ModelTests(unittest.TestCase):
    def test_synthetic_retry_is_status_not_a_user_or_assistant_reply(self):
        retry={'type':'user','isSynthetic':True,'message':{'role':'user','content':[{'type':'text','text':model.EMPTY_RETRY}]}}
        events=[{'type':'user','message':{'role':'user','content':'hello'}},retry]
        self.assertEqual([m['text'] for m in model.transcript(events)],['hello'])
        self.assertIn('重试',model.transcript_notice(events))
        events.append({'type':'assistant','message':{'role':'assistant','content':'Visible answer'}})
        self.assertEqual(model.transcript_notice(events),'')
        real={'type':'user','isSynthetic':False,'message':{'role':'user','content':model.EMPTY_RETRY}}
        self.assertEqual(model.transcript([real])[0]['text'],model.EMPTY_RETRY)

    def test_ids_are_stable_and_namespaced(self):
        one = model.gateway_id('code', 'local_abc')
        self.assertEqual(one, model.gateway_id('code', 'local_abc'))
        self.assertNotEqual(one, model.gateway_id('cowork', 'local_abc'))
        self.assertEqual(str(uuid.UUID(one)), one)

    def test_configuration_and_system_messages_are_not_published(self):
        raw = {'sessionId':'local_x', 'title':'test', 'systemPrompt':'PRIVATE',
               'oauthToken':'SECRET', 'lifecycleState':'running',
               'pendingToolPermissions':[{'requestId':'p', 'toolName':'Read', 'input':{'file_path':'a'}}]}
        row = model.session('cowork', raw)
        self.assertEqual(row['status'], 'active')
        self.assertEqual(row['requests'][0]['id'], 'p')
        self.assertNotIn('PRIVATE', json.dumps(row))
        self.assertNotIn('SECRET', json.dumps(row))
        messages = model.transcript([
            {'type':'system', 'content':'SECRET'},
            {'type':'assistant','uuid':'a','message':{'role':'assistant','content':[{'type':'text','text':'OK'}]}},
            {'type':'user','message':{'role':'user','content':'hello'}},
        ])
        self.assertEqual([m['text'] for m in messages], ['OK','hello'])

    def test_unknown_shape_fails_instead_of_empty_success(self):
        with self.assertRaises(ValueError):
            model.rows({'unexpected': []})
