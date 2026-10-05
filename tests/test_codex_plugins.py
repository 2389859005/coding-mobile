import json
import tempfile
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from coding_remote.codex_plugins import grouped_catalog, PluginBridgeMixin
from coding_remote.native_features import NativeFeatures


class PluginTests(unittest.TestCase):
    def test_package_grouping_is_manifest_based_and_retains_documents_and_standalone(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'nature-skills/nature-skills/0.1'
            (root/'.codex-plugin').mkdir(parents=True)
            (root/'.codex-plugin/plugin.json').write_text(json.dumps({'name':'nature-skills','interface':{'displayName':'Nature Skills'}}),'utf-8')
            raw={'skills':[{'id':'one','name':'writer','path':str(root/'skills/writer/SKILL.md')},
                           {'id':'two','name':'reader','path':str(root/'skills/reader/SKILL.md')},
                           {'id':'independent','name':'personal','path':str(Path(folder)/'personal/SKILL.md')}]}
            installed={'marketplaces':[{'plugins':[
                {'id':'nature-skills@nature-skills','name':'nature-skills','enabled':True,'installed':True,'interface':{'displayName':'Nature Skills'}},
                {'id':'documents@openai-primary-runtime','name':'documents','enabled':True,'installed':True},
                {'id':'pdf@openai-primary-runtime','name':'pdf','enabled':False,'installed':True}]}]}
            entries=grouped_catalog(raw,installed)
            self.assertEqual(len(entries),4)
            nature=next(p for p in entries if p['displayName']=='Nature Skills')
            self.assertEqual(nature['memberIds'],['one','two'])
            self.assertEqual(nature['id'],'plugin:nature-skills@nature-skills')
            self.assertFalse(next(p for p in entries if p['name']=='pdf')['selectable'])
            fallback=grouped_catalog(raw,{})
            self.assertEqual(len(fallback),2)
            self.assertFalse(fallback[0]['selectable'])

    def test_package_selection_becomes_native_mention_and_never_expands_all_skills(self):
        class Parent:
            def _resolve_skills(self, session, ids): return [{'id':s,'name':s,'path':'/skills/'+s} for s in ids]
            def _call(self, session, method, params, **kwargs): return params
        class Bridge(PluginBridgeMixin,Parent):
            def plugin_inventory(self,*a,**k):
                return {'marketplaces':[{'plugins':[{'id':'nature@market','name':'nature','enabled':True,'installed':True}]}]}
        bridge=object.__new__(Bridge);session=types.SimpleNamespace(id='s')
        selected=bridge._resolve_skills(session,['plugin:nature@market','personal'])
        self.assertEqual(len(selected),2)
        request={'turnStart':{'request':{'input':[{'type':'skill','name':s['name'],'path':s['path']} for s in selected]}}}
        result=bridge._call(session,'thread-follower-start-turn',request)
        self.assertEqual(result['turnStart']['request']['input'][-1],{'type':'mention','name':'nature','path':'plugin://nature@market'})
        self.assertEqual(request['turnStart']['request']['input'][-1]['type'],'skill')
        with self.assertRaises(ValueError):bridge._resolve_skills(session,['plugin:foreign@market'])
        steer=bridge._call(session,'thread-follower-steer-turn',{'input':request['turnStart']['request']['input'],'restoreMessage':request['turnStart']})
        self.assertEqual(steer['input'][-1]['type'],'mention')
        self.assertEqual(steer['restoreMessage']['request']['input'][-1]['type'],'mention')

    def test_plan_change_preserves_model_effort_and_uses_desktop_owner(self):
        features=object.__new__(NativeFeatures);calls=[]
        features.catalog=lambda *a,**k:{'currentModel':'current','currentEffort':'high','models':[{'id':'current','efforts':['medium','high'],'defaultEffort':'medium'}]}
        target=types.SimpleNamespace(action_lock=threading.Lock())
        features.server=types.SimpleNamespace(bridge=types.SimpleNamespace(_target=lambda s:target,_call=lambda t,m,p:calls.append((m,p)) or {'ok':True}))
        result=features.settings('codex','sid',{'collaborationMode':'plan'})
        self.assertEqual(result['collaborationMode'],{'mode':'plan','settings':{'model':'current','reasoning_effort':'high','developer_instructions':None}})
        self.assertEqual(calls[0][0],'thread-follower-update-thread-settings')
        with self.assertRaises(ValueError): features.settings('codex','sid',{'collaborationMode':'invented'})

    def test_goal_uses_real_rpc_and_does_not_overwrite_unfinished_goal(self):
        calls=[];previous=[None]
        class RPC:
            def __init__(self,*a):pass
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def request(self,method,params):
                calls.append((method,params))
                if method=='thread/goal/get':return {'goal':previous[0]}
                if method=='thread/goal/clear':previous[0]=None;return {'cleared':True}
                previous[0]={**(previous[0] or {}),**params};return {'goal':previous[0]}
        target=types.SimpleNamespace(action_lock=threading.Lock(),condition=threading.Lock(),state={'latestModel':'current','latestReasoningEffort':'high'})
        bridge=types.SimpleNamespace(store=types.SimpleNamespace(get=lambda sid:{},home=Path('.')),session=lambda *a,**k:target,_target=lambda sid:target,_call=lambda *a:{'ok':True})
        features=object.__new__(NativeFeatures);features.server=types.SimpleNamespace(bridge=bridge)
        with patch('coding_remote.codex_rpc.CodexRPC',RPC):
            features.goal('sid',{'objective':'Finish work'})
            self.assertNotIn('tokenBudget',calls[-1][1])
            with self.assertRaises(ValueError):features.goal('sid',{'objective':'Replace work'})
            features.goal('sid',{'operation':'pause'});self.assertEqual(previous[0]['status'],'paused')
            features.goal('sid',{'operation':'resume'});self.assertEqual(previous[0]['status'],'active')
            features.goal('sid',{'operation':'clear'});self.assertIsNone(previous[0])
