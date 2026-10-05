import unittest
from coding_remote.api import codex_detail
from coding_remote.api import NativeServer
from types import SimpleNamespace

class SessionIdentityTests(unittest.TestCase):
    def test_list_and_attached_view_use_same_saved_metadata(self):
        row={'id':'fixture','name':'最新名称','title':'旧正文摘要','cwd':'D:/fixture'}
        server=NativeServer.__new__(NativeServer)
        server.bridge=SimpleNamespace(
            list=lambda **options: [] if options['working'] else [row],
            view=lambda sid,**options: {'id':sid,'title':'未刷新的实时缓存','turns':[]},
            store=SimpleNamespace(get=lambda sid: row))
        server.features=SimpleNamespace(context_usage=lambda backend,sid: None)
        listing=server.dispatch('codex',None,None,{})
        detail=server.dispatch('codex','fixture',None,{})
        self.assertEqual(listing['sessions'][0]['title'],detail['session']['title'])
        self.assertEqual(detail['session']['title'],'最新名称')

    def test_custom_name_wins_over_legacy_first_message_title(self):
        value={'id':'fixture','name':'用户重命名','title':'旧名称或首条消息','cwd':'D:/fixture','turns':[]}
        self.assertEqual(codex_detail(value)['session']['title'],'用户重命名')

    def test_blank_name_uses_title_and_never_message_body(self):
        value={'id':'fixture','name':'','title':'会话标题','turns':[],'messages':[{'text':'不属于基础信息的正文'}]}
        self.assertEqual(codex_detail(value)['session']['title'],'会话标题')
        value['title']=None
        self.assertEqual(codex_detail(value)['session']['title'],'未命名会话')
