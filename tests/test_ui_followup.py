import json
import tempfile
import unittest
from pathlib import Path
from coding_remote.native_content import codex_messages,image_paths
from coding_remote.native_features import NativeFeatures

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
