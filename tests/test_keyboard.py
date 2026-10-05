import json
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch

from coding_remote import keyboard


class KeyboardTests(unittest.TestCase):
    def test_documented_setting_preserves_existing_config(self):
        with tempfile.TemporaryDirectory() as folder,patch.dict(os.environ,{'APPDATA':folder,'LOCALAPPDATA':folder}):
            path=Path(folder)/'Claude/developer_settings.json';path.parent.mkdir()
            path.write_text('{"existing": 7}')
            keyboard.enable_devtools('C:/Programs/Claude.exe')
            self.assertEqual(json.loads(path.read_text()),{'existing':7,'allowDevTools':True})
            keyboard.enable_devtools('C:/Program Files/WindowsApps/Claude_2.1.0.0_x64__test/app/Claude.exe')
            self.assertTrue(json.loads((Path(folder)/'Packages/Claude_test/LocalCache/Roaming/Claude/developer_settings.json').read_text())['allowDevTools'])
            self.assertFalse(keyboard.enable_devtools('C:/Programs/Claude.exe'))

    def test_direct_script_no_eval_clipboard_or_commandline_secret(self):
        script='/* coding remote script */(()=>{window.fixture="test"})();'
        with tempfile.TemporaryDirectory() as folder,patch.object(Path,'is_file',return_value=True),patch.object(keyboard.subprocess,'run') as run:
            def result(args,**kwargs):
                self.assertEqual(kwargs['timeout'],240)
                expression=Path(args[2]).read_text('utf-8')
                self.assertEqual(expression,script);self.assertNotIn('eval(',expression)
                self.assertEqual(args[1],'42');self.assertNotIn(script,str(args))
                return types.SimpleNamespace(returncode=0,stdout='submitted')
            run.side_effect=result;keyboard.inject(42,script,folder)
            self.assertFalse((Path(folder)/'keyboard-connect.js').exists())

    def test_failure_and_timeout_cleanup(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(Path,'is_file',return_value=True),patch.object(keyboard.subprocess,'run') as run:
            run.return_value=types.SimpleNamespace(returncode=1,stdout='Console not focused')
            with self.assertRaisesRegex(RuntimeError,'Console not focused'):keyboard.inject(42,'/* coding remote script */test',folder)
            run.side_effect=subprocess.TimeoutExpired('helper',90)
            with self.assertRaises(subprocess.TimeoutExpired):keyboard.inject(42,'/* coding remote script */test',folder)
            self.assertFalse((Path(folder)/'keyboard-connect.js').exists())

    def test_rejects_multiline_or_unbuilt_script(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(Path,'is_file',return_value=True),patch.object(keyboard.subprocess,'run') as run:
            for script in ('test','/* coding remote script */\nalert(1)'):
                with self.assertRaises(ValueError):keyboard.inject(42,script,folder)
            run.assert_not_called()
