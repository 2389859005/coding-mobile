"""Windows widget regression with synthetic adapters; never starts or controls real apps."""
import importlib
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch,MagicMock

@unittest.skipUnless(os.name=='nt','Windows desktop only')
class DesktopWindowTests(unittest.TestCase):
    def test_repeated_qr_modes_pairing_and_hidden_window(self):
        try:import customtkinter
        except ImportError:self.skipTest('customtkinter not installed')
        assets=Path(os.environ.get('CODING_TEST_ASSETS','C:/Users/MMM/AppData/Local/Temp/_MEI00001ef82'))
        if not (assets/'assets/codex-mobile.ico').exists():self.skipTest('packaged test assets missing')
        service=types.ModuleType('bridge.service');service.Bridge=lambda *args:MagicMock()
        with patch.dict(sys.modules,{'bridge':types.ModuleType('bridge'),'bridge.service':service}):desktop=importlib.import_module('coding_remote.desktop')
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);controller=MagicMock();controller.workspace=directory;controller.script='(async()=>{})();';controller.desktop.connected=False
            applications=MagicMock();applications.error=''
            network=MagicMock();network.status.return_value={'state':'Running','origin':'http://100.64.1.2:18787'}
            with patch.object(desktop,'ASSETS',assets),patch.object(desktop,'DATA',directory),patch.object(desktop,'Controller',return_value=controller),patch.object(desktop,'Applications',return_value=applications),patch.object(desktop,'Tray'),patch.object(desktop,'EmbeddedNetwork',return_value=network),patch.object(desktop.Desktop,'start_route'),patch('coding_remote.deepseek.DeepSeek.install'):
                window=desktop.Desktop();window.withdraw()
                try:
                    self.assertEqual(window.current_page,'设备连接')
                    self.assertFalse(hasattr(window,'tabs'))
                    window.settings_button.invoke();window.update_idletasks();self.assertEqual(window.current_page,'应用设置')
                    self.assertEqual(window.page_title.cget('text'),'设置')
                    window.logs_button.invoke();window.update_idletasks();self.assertEqual(window.current_page,'查看日志')
                    window.back_button.invoke();window.update_idletasks();self.assertEqual(window.current_page,'设备连接')
                    window.routes['temporary']['origin']='https://fixture.trycloudflare.com';window.routes['permanent']['origin']='http://100.64.1.2:18787'
                    for i in range(16):
                        window.change_mode('临时扫码连接' if i%2 else 'Tailscale 永久绑定');window.refresh_qr();window.update_idletasks()
                        self.assertIsNotNone(window.qr_image);self.assertEqual(len(window.qr_holder.winfo_children()),1)
                    codes=list(window.pairing.codes.values());self.assertEqual({c['mode'] for c in codes},{'temporary','permanent'})
                    for code in codes:window.pairing.pair(code['token'],'synthetic phone','test')
                    window.tick();window.update_idletasks();self.assertIsNone(window.qr_image)
                    window.change_mode('临时扫码连接');window.update_idletasks();self.assertIsNotNone(window.qr_image)
                    for i in range(8):
                        window.routes['permanent']['origin']=None
                        window.change_mode('Tailscale 永久绑定');window.refresh_qr();window.update_idletasks()
                        self.assertIsNone(window.qr_image);self.assertEqual(len(window.qr_holder.winfo_children()),1)
                        window.change_mode('临时扫码连接');window.refresh_qr();window.update_idletasks()
                        self.assertIsNotNone(window.qr_image)
                    window.hide_window();self.assertEqual(window.state(),'withdrawn');window.events.put(('window',True));window.tick();window.update_idletasks();self.assertNotEqual(window.state(),'withdrawn')
                    self.assertFalse(controller.open.called)
                    window.settings['auto_connect']=False
                    generation=window.route_generation['permanent']
                    window.events.put(('network_health',(generation,{'state':'Running','origin':'http://100.64.1.2:18787'})))
                    window.tick();self.assertEqual(window.routes['permanent']['origin'],'http://100.64.1.2:18787')
                    window.events.put(('route',('permanent',None,None,'old Tailscale error',generation-1)))
                    window.tick();self.assertEqual(window.routes['permanent']['origin'],'http://100.64.1.2:18787')
                    # Saved embedded binding survives an unavailable preferred
                    # external VPN and auto_connect=False, without a QR action.
                    window.settings['external_tailscale']=True
                    network.has_identity.return_value=True
                    window.routes['permanent'].update(origin=None,link=None,error='external VPN unavailable')
                    self.assertTrue(window.check_embedded_network())
                    window.events.put(('network_health',(generation,{'state':'Running','origin':'http://100.64.1.2:18787'})))
                    window.tick();self.assertEqual(window.routes['permanent']['origin'],'http://100.64.1.2:18787')
                    window.events.put(('route',('permanent',None,None,'late external VPN error',generation)))
                    window.tick();self.assertEqual(window.routes['permanent']['origin'],'http://100.64.1.2:18787')
                    external=object.__new__(desktop.TailscaleLink)
                    window.routes['permanent'].update(origin='http://100.64.2.3:18787',link=external)
                    window.events.put(('network_health',(generation,{'state':'Running','origin':'http://100.64.1.2:18787'})))
                    window.tick();self.assertEqual(window.routes['permanent']['origin'],'http://100.64.2.3:18787')
                    window.routes['permanent']['link']=None
                    window.disabled_routes.add('permanent');self.assertFalse(window.check_embedded_network())
                    window.disabled_routes.clear()
                    window.tray_action('settings');self.assertEqual(window.current_page,'应用设置')
                    window.tray_action('logs');self.assertEqual(window.current_page,'查看日志')
                    window.tray_action('show');self.assertNotEqual(window.state(),'withdrawn')
                finally:window.close()

    def test_duplicate_instance_signals_existing_window(self):
        from coding_remote.single_instance import SingleInstance
        import threading
        with tempfile.TemporaryDirectory() as d:
            first=SingleInstance(d);received=threading.Event();first.watch(received.set)
            try:
                self.assertFalse(first.existing);second=SingleInstance(d)
                try:self.assertTrue(second.existing);self.assertTrue(received.wait(2))
                finally:second.close()
            finally:first.close()
