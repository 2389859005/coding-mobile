import json
import tempfile
import unittest
import types
import base64
from pathlib import Path
from coding_remote.native_content import codex_messages,image_paths
from coding_remote.native_features import NativeFeatures
from coding_remote.api import message_window
from claude_bridge.model import transcript

class ImageViewTests(unittest.TestCase):
    def test_image_view_aliases_and_nested_orchestration(self):
        for name in ('functions.view_image','imageview','image_view','viewImage'):
            rows=codex_messages({'turns':[{'id':'t','messages':[{'kind':'storedToolEvent','title':name,'text':json.dumps({'path':'C:/Temp/result.png'})}]}]})
            self.assertEqual(rows[0]['kind'],'image');self.assertIn('result.png',rows[0]['text'])
        message={'title':'functions.exec','text':json.dumps({'code':'const r=await tools.view_image({path:"C:/Temp/result.png"});image(r.image_url);'})}
        self.assertEqual(image_paths(message),['C:/Temp/result.png'])
    def test_image_view_file_uri_and_bare_path(self):
        self.assertEqual(image_paths({'title':'imageview','input':{'path':'file:///C:/Temp/my%20image.png'}}),['C:/Temp/my image.png'])
        self.assertEqual(image_paths({'title':'imageview','text':'D:\\images\\result.png'}),['D:\\images\\result.png'])
    def test_non_image_tool_does_not_authorize_arbitrary_image_path(self):
        self.assertEqual(image_paths({'title':'exec_command','text':'{"path":"C:/Secret/private.png"}'}),[])

    def test_read_and_parallel_arguments(self):
        self.assertEqual(image_paths({'name':'Read','input':{'file_path':'C:/Temp/read.png'}}),['C:/Temp/read.png'])
        calls={'tool_uses':[{'recipient_name':'functions.view_image','parameters':{'path':'C:/Temp/view.png'}},{'recipient_name':'functions.exec_command','parameters':{'path':'C:/Secret/private.png'}}]}
        self.assertEqual(image_paths({'name':'multi_tool_use.parallel','arguments':json.dumps(calls)}),['C:/Temp/view.png'])
        self.assertEqual(image_paths({'name':'functions.exec','input':'await tools.view_image({path:"C:/Temp/view.png"}); await tools.exec_command({path:"C:/Secret/private.png"});'}),['C:/Temp/view.png'])

    def test_claude_read_becomes_visible_image(self):
        messages=transcript([{'type':'assistant','message':{'role':'assistant','content':[{'type':'tool_use','id':'read','name':'Read','input':{'file_path':'C:/Temp/image.png'}}]}}])
        self.assertEqual(messages[0]['kind'],'image');self.assertIn('C:/Temp/image.png',messages[0]['text'])

    def test_external_images_require_exact_raw_tool_call_in_this_session(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);project=root/'project';project.mkdir();image=root/'read.png';image.write_bytes(b'\x89PNG\r\n\x1a\nfixture')
            other=root/'other.png';other.write_bytes(image.read_bytes())
            raw={'type':'function_call','name':'functions.view_image','arguments':json.dumps({'path':str(image)})}
            store=types.SimpleNamespace(get=lambda sid:{'cwd':str(project)},history=lambda sid:{'turns':[{'items':[raw] if sid=='reader' else []}]})
            server=types.SimpleNamespace(bridge=types.SimpleNamespace(store=store),dispatch=lambda *a:{'messages':[{'kind':'image','text':'![image](<'+str(other)+'>)'}]})
            features=NativeFeatures(server,root/'data')
            self.assertEqual(features.resolve_file('codex','reader',{'path':[str(image)]}),image.resolve())
            self.assertEqual(base64.b64decode(features.file('codex','reader',{'path':[str(image)]})['data']),image.read_bytes())
            raw['arguments']=json.dumps({'path':'../read.png'})
            self.assertEqual(features.resolve_file('codex','reader',{'path':['../read.png']}),image.resolve())
            raw['arguments']=json.dumps({'path':str(image)})
            for sid,path in [('reader',other),('another',image)]:
                with self.assertRaises(ValueError):features.resolve_file('codex',sid,{'path':[str(path)]})
            raw.update(type='message',role='assistant',content=[{'text':'view_image({path:"'+str(other)+'"})'}])
            with self.assertRaises(ValueError):features.resolve_file('codex','reader',{'path':[str(image)]})

    def test_api_pagination_repeats_table_header_and_keeps_all_rows(self):
        header='| Item | Result |\n| --- | --- |\n'
        table=header+''.join(f'| item-{i} | complete result |\n' for i in range(500))
        page=message_window({'session':{},'messages':[{'role':'assistant','text':table}]},limit=100)
        chunks=page['messages'];self.assertGreater(len(chunks),1)
        self.assertTrue(all(m['text'].startswith(header) for m in chunks))
        self.assertEqual(sum(m['text'].count('| item-') for m in chunks),500)
