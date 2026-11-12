"""Windows widget regression with synthetic adapters; never starts or controls real apps."""
import importlib
import json
import os
import sys
import tempfile
import types
import shutil
import unittest
import time
from pathlib import Path
from unittest.mock import patch,MagicMock

@unittest.skipUnless(os.name=='nt','Windows desktop only')
class DesktopWindowTests(unittest.TestCase):
    def tearDown(self):
        # Tk image finalizers must run on the UI thread before later HTTP tests.
        import gc
        gc.collect()

    def test_repeated_qr_modes_pairing_and_hidden_window(self):
        try:import customtkinter
        except ImportError:self.skipTest('customtkinter not installed')
        service=types.ModuleType('bridge.service');service.Bridge=lambda *args:MagicMock()
        with patch.dict(sys.modules,{'bridge':types.ModuleType('bridge'),'bridge.service':service}):desktop=importlib.import_module('coding_remote.desktop')
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);controller=MagicMock();controller.workspace=directory;controller.script='(async()=>{})();';controller.desktop.connected=False
            assets=directory;(assets/'assets').mkdir()
            product=Path(__file__).resolve().parents[1]/'packaging/product'
            for suffix in ('ico','png'):shutil.copyfile(product/('icon.'+suffix),assets/('assets/codex-mobile.'+suffix))
            applications=MagicMock();applications.error=''
            network=MagicMock();network.status.return_value={'state':'Running','origin':'http://100.64.1.2:18787'}
            with patch.object(desktop,'ASSETS',assets),patch.object(desktop,'DATA',directory),patch.object(desktop.Desktop,'start_services'),patch.object(desktop.Desktop,'start_route'):
                window=desktop.Desktop();window.withdraw()
                window.claude=controller;window.apps=applications;window.network=network
                window.tray=MagicMock();window.tray.ready=True
                window.server=MagicMock()
                try:
                    self.assertEqual(window.current_page,'概览')
                    self.assertFalse(hasattr(window,'tabs'))
                    self.assertFalse(hasattr(window,'settings_button'))
                    self.assertFalse(hasattr(window,'logs_button'))
                    self.assertFalse(hasattr(window,'back_button'))
                    self.assertFalse(hasattr(window.workbench,'pet'))
                    self.assertFalse(hasattr(window.workbench,'connections'))
                    self.assertFalse(window.settings['lan_enabled'])
                    def labels(widget):
                        values=[]
                        try:values.append(widget.cget('text'))
                        except Exception:pass
                        for child in widget.winfo_children():values.extend(labels(child))
                        return values
                    settings_labels=labels(window.frames['应用设置'])
                    for removed in ('连接与网关','其他连接方式','应用','局域网直连','SSH 连接手机','外观','主题','配色','主题风格'):
                        self.assertNotIn(removed,settings_labels)
                    self.assertIn('永久连接',settings_labels)
                    self.assertNotIn('运行状态',labels(window.frames['概览']))
                    self.assertNotIn('最近活动',labels(window.frames['概览']))
                    self.assertEqual(set(window.frames),{'概览','账号与模型','查看日志','应用设置'})
                    self.assertIs(window.qr_holder.master,window.pair_card)
                    self.assertIs(window.pair_card.master,window.frames['概览'])
                    window.workbench.nav['应用设置'].invoke();window.update_idletasks();self.assertEqual(window.current_page,'应用设置')
                    self.assertEqual(window.page_title.cget('text'),'设置')
                    # Empty feedback must not reserve blank rows. Inputs and
                    # switches remain inside their cards at either sidebar width.
                    def widgets(widget):
                        yield widget
                        for child in widget.winfo_children():yield from widgets(child)
                    window.deiconify()
                    for width in (700,780,1100):
                        window.geometry(f'{width}x700');window.update()
                        for status in (window.network_status,window.server_config_status,window.copy_status):
                            self.assertEqual(status.winfo_manager(),'')
                        for control in widgets(window.settings_body):
                            if not isinstance(control,(customtkinter.CTkEntry,customtkinter.CTkSwitch,customtkinter.CTkButton)):continue
                            self.assertGreaterEqual(control.winfo_x(),0)
                            self.assertLessEqual(control.winfo_x()+control.winfo_width(),control.master.winfo_width())
                            self.assertGreaterEqual(control.winfo_y(),0)
                            self.assertLessEqual(control.winfo_y()+control.winfo_height(),control.master.winfo_height())
                            label=next(child for child in control.master.winfo_children() if isinstance(child,customtkinter.CTkLabel))
                            self.assertLess(label.winfo_x()+label.winfo_width(),control.winfo_x())
                        self.assertEqual(len({field.winfo_x() for field in window.server_fields.values()}),1)
                    window.geometry('700x700');window.update()
                    section=window.network_status.master
                    original_height=section.winfo_height()
                    window.set_settings_status(window.network_status,'暂时无法连接，请检查互联网。'*8);window.update()
                    self.assertGreater(section.winfo_height(),original_height)
                    order=section.pack_slaves()
                    self.assertEqual(order.index(window.network_status),order.index(window.network_status._status_after)+1)
                    self.assertGreater(window.network_status.winfo_height(),20)
                    window.set_settings_status(window.network_status,'');window.update()
                    self.assertEqual(section.winfo_height(),original_height)
                    window.withdraw()
                    window.workbench.nav['查看日志'].invoke();window.update_idletasks();self.assertEqual(window.current_page,'查看日志')
                    window.workbench.nav['概览'].invoke();window.update_idletasks();self.assertEqual(window.current_page,'概览')
                    work=window.workbench;cards=work.account_cards.winfo_children()
                    for page in work.PAGES:window.show_tab(page);window.update_idletasks()
                    self.assertFalse(hasattr(work,'composer'))
                    for _ in range(10):work.render_accounts({},None)
                    self.assertEqual(cards,work.account_cards.winfo_children(),'unchanged snapshots preserve account widgets')
                    window.routes['temporary']['origin']='https://fixture.trycloudflare.com';window.routes['permanent']['origin']='http://100.64.1.2:18787'
                    def wait_qr():
                        deadline=time.monotonic()+3
                        while time.monotonic()<deadline and getattr(window,'qr_image',None) is None:
                            window.tick();window.update_idletasks();time.sleep(.02)
                    for i in range(4):
                        window.change_mode('临时连接' if i%2 else '永久连接');window.refresh_qr();wait_qr();window.update_idletasks()
                        self.assertIsNotNone(window.qr_image);self.assertEqual(len(window.qr_holder.winfo_children()),1)
                    window.routes['server']['origin']='https://relay.example.com'
                    window.change_mode('SSH连接');window.refresh_qr();wait_qr();window.update_idletasks()
                    server_code=next(c for c in window.pairing.codes.values() if c['origin']=='https://relay.example.com')
                    self.assertEqual(server_code['mode'],'permanent')
                    codes=list(window.pairing.codes.values());self.assertEqual({c['mode'] for c in codes},{'temporary','permanent'})
                    for code in codes:window.pairing.pair(code['token'],'synthetic phone','test')
                    window.tick();window.update_idletasks();self.assertIsNone(window.qr_image)
                    window.change_mode('临时连接');window.refresh_qr(force=True);window.update_idletasks();self.assertIsNone(window.qr_image)
                    self.assertEqual(window.refresh_button.winfo_manager(),'')
                    self.assertEqual(window.qr_holder.winfo_manager(),'')
                    for device in window.pairing.list_devices():window.pairing.revoke(device['id'])
                    window.refresh_qr();wait_qr();self.assertIsNotNone(window.qr_image)
                    for i in range(2):
                        window.routes['permanent']['origin']=None
                        window.change_mode('永久连接');window.refresh_qr();wait_qr();window.update_idletasks()
                        self.assertIsNone(window.qr_image);self.assertEqual(len(window.qr_holder.winfo_children()),1)
                        window.change_mode('临时连接');window.refresh_qr();wait_qr();window.update_idletasks()
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
                    saved=json.loads(window.pairing.issue('http://100.64.1.2:18787','permanent'))
                    window.pairing.pair(saved['token'],'restored phone','test')
                    window.routes['permanent'].update(origin=None,link=None,error='external VPN unavailable')
                    self.assertTrue(window.check_embedded_network())
                    window.events.put(('network_health',(generation,{'state':'Running','origin':'http://100.64.1.2:18787'})))
                    window.tick();self.assertEqual(window.routes['permanent']['origin'],'http://100.64.1.2:18787')
                    window.events.put(('route',('permanent',None,None,'late external VPN error',generation)))
                    window.tick();self.assertEqual(window.routes['permanent']['origin'],'http://100.64.1.2:18787')
                    from coding_remote.tailscale_link import TailscaleLink
                    external=object.__new__(TailscaleLink)
                    window.routes['permanent'].update(origin='http://100.64.2.3:18787',link=external)
                    window.events.put(('network_health',(generation,{'state':'Running','origin':'http://100.64.1.2:18787'})))
                    window.tick();self.assertEqual(window.routes['permanent']['origin'],'http://100.64.2.3:18787')
                    window.routes['permanent']['link']=None
                    window.disabled_routes.add('permanent');self.assertFalse(window.check_embedded_network())
                    window.disabled_routes.clear()
                    window.tray_action('settings');self.assertEqual(window.current_page,'应用设置')
                    window.tray_action('logs');self.assertEqual(window.current_page,'查看日志')
                    window.tray_action('show');self.assertNotEqual(window.state(),'withdrawn')
                    pending_link=MagicMock();window.owned_links.add(pending_link)
                finally:window.close()
                pending_link.close.assert_called_once()

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
