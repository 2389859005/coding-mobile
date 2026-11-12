import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from coding_remote.deepseek import DeepSeek


class DeepSeekInstallTests(unittest.TestCase):
    def test_frozen_bundle_resolves_packaged_asset_and_preserves_patch(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            bundle=root/'bundle'
            source=bundle/'coding_remote/deepseek-host.mjs'
            source.parent.mkdir(parents=True)
            source.write_text('export const fixture = true;', 'utf-8')
            home=root/'home'
            config=home/'profiles/desktop/cordis.patch.yml'
            config.parent.mkdir(parents=True)
            original='- modify: {id: user-plugin, config: {enabled: true}}\n'
            config.write_text(original,'utf-8')
            adapter=DeepSeek(root/'data',home)
            with patch('coding_remote.deepseek.__file__',str(bundle/'deepseek.py')),patch('coding_remote.deepseek.sys._MEIPASS',str(bundle),create=True):
                self.assertTrue(adapter.install())
                self.assertTrue(adapter.install())
            self.assertEqual((adapter.directory/'mobile-host.mjs').read_bytes(),source.read_bytes())
            patched=config.read_text('utf-8')
            self.assertTrue(patched.startswith(original))
            self.assertEqual(patched.count('# coding-remote-assistant bridge'),1)
            self.assertEqual((adapter.directory/'cordis.patch.before-mobile.yml').read_text('utf-8'),original)
            insertion=json.loads(patched.split('# coding-remote-assistant bridge\n- ',1)[1])['insert'][0]
            self.assertEqual(insertion['config']['configPath'],str(adapter.config))

    def test_existing_loader_gets_updated_host_without_duplicate_registration(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            config=root/'home/profiles/desktop/cordis.patch.yml'
            config.parent.mkdir(parents=True)
            config.write_text('[]\n','utf-8')
            adapter=DeepSeek(root/'data',root/'home')
            adapter.install()
            original=config.read_bytes()
            (adapter.directory/'mobile-host.mjs').write_text('outdated','utf-8')
            adapter.install()
            self.assertEqual(config.read_bytes(),original)
            source=Path(__file__).resolve().parents[1]/'coding_remote/deepseek-host.mjs'
            self.assertEqual((adapter.directory/'mobile-host.mjs').read_bytes(),source.read_bytes())
