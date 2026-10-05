import json
import threading
import types
import unittest
from unittest.mock import MagicMock

from coding_remote.native_features import NativeFeatures
from coding_remote.context_usage import normalize


class AttentionTests(unittest.TestCase):
    def test_context_occupancy_uses_last_request_not_cumulative_usage(self):
        self.assertEqual(normalize({'modelContextWindow':200000,'last':{'totalTokens':50000},'total':{'totalTokens':950000}}),
            {'available':True,'usedTokens':50000,'maxTokens':200000,'percent':25.0})
        self.assertEqual(normalize({'model_context_window':100,'last_token_usage':{'total_tokens':120}})['percent'],100)
        self.assertEqual(normalize({'totalTokens':20,'maxTokens':100},'claude')['percent'],20)
        for value in (None,{}, {'modelContextWindow':200,'total':{'totalTokens':100}}, {'modelContextWindow':0,'last':{'totalTokens':1}}, {'modelContextWindow':100,'last':{'totalTokens':-1}}, {'modelContextWindow':100,'last':{'totalTokens':float('nan')}}):
            self.assertFalse(normalize(value)['available'])
    def test_classifies_questions_and_approvals_without_keyword_guessing(self):
        rows = NativeFeatures.attention([
            {'id': 0, 'method': 'item/commandExecution/requestApproval', 'needsInput': True, 'params': {'command': ['git', 'status']}},
            {'id': 'q', 'method': 'bridge/requestUserInputAsync', 'params': {'questions': [{'question': '选择方案'}]}},
            {'id': 'f', 'method': 'item/fileChange/requestApproval', 'params': {'reason': '修改文件'}},
            {'id': 'c', 'tool': 'AskUserQuestion', 'needsInput': True, 'input': {'questions': [{'question': '继续？'}]}},
        ])
        self.assertEqual([r['kind'] for r in rows], ['approval','question','approval','question'])
        self.assertEqual(rows[0]['id'], '0')
        self.assertEqual(rows[0]['preview'], 'git status')
        self.assertEqual(rows[1]['preview'], '选择方案')
        self.assertEqual(NativeFeatures.attention([]), [])

    def test_status_retains_idle_pending_requests_for_all_providers(self):
        bridge=MagicMock()
        bridge.task_statuses.return_value={'tasks':[{'id':'c','status':'idle','requests':[7]}]}
        bridge.view.return_value={'requests':[{'id':7,'method':'item/commandExecution/requestApproval','params':{'command':'echo fixture'}}]}
        question={'id':'q','needsInput':True,'input':{'questions':[{'question':'Which?'}]}}
        claude=types.SimpleNamespace(listing=lambda:None,run=lambda _:([{'id':'a','title':'Claude','status':'idle','archived':False,'requests':[question]}],[]))
        deepseek=MagicMock();deepseek.call.return_value={'sessions':[{'id':'d','title':'DeepSeek','status':'idle','requests':[question]}]}
        features=object.__new__(NativeFeatures);features.server=types.SimpleNamespace(bridge=bridge,claude=claude,deepseek=deepseek);features.status_lock=threading.Lock();features.status_cache=(0,{})
        status=features.task_status()
        self.assertEqual(len(status['tasks']),3)
        self.assertEqual([t['attention'][0]['kind'] for t in status['tasks']],['approval','question','question'])
        bridge.view.assert_called_once_with('c',attach=False,background=True)
        self.assertEqual(features.task_status(),status)
        bridge.task_statuses.assert_called_once()

    def test_read_settings_uses_next_turn_state_and_current_permissions(self):
        target=types.SimpleNamespace(condition=threading.RLock(),state={'latestModel':'old','latestReasoningEffort':'low','currentPermissions':{'sandboxPolicy':{'type':'readOnly'}},'latestThreadSettings':{'model':'new','effort':'high'}})
        features=object.__new__(NativeFeatures);features.server=types.SimpleNamespace(bridge=types.SimpleNamespace(session=lambda *a,**k:target))
        self.assertEqual(features.codex_settings('s'),{'model':'new','effort':'high','sandboxPolicy':{'type':'readOnly'}})
