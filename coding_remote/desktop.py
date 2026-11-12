import json
import gc
import logging
import os
import queue
import sys
import threading
import time
from pathlib import Path

import customtkinter as ctk
from PIL import Image, ImageDraw
from qrcodegen import QrCode

from .apps import Applications
from .pairing import Pairing
from .tray import Tray
from . import VERSION

ASSETS = Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parents[1]))
DATA = Path(os.environ.get('LOCALAPPDATA', Path.home()))/'CodingRemoteAssistant'

# One visual language for the Windows window: canvas, card, ink and accent with a
# single type scale, so the pages cannot drift apart as they are edited.
from . import appearance
from .appearance import BG as UI_BG,CARD as UI_CARD,SOFT as UI_SOFT,TINT as UI_TINT,LINE as UI_LINE,INK as UI_INK,MUTED as UI_MUTED,ACCENT as UI_ACCENT,ACCENT_DARK as UI_ACCENT_DARK,DANGER as UI_DANGER

def FONT(size,weight='normal'):return ('Microsoft YaHei UI',size,weight)

CONNECTION_MODES={'临时连接':'temporary','永久连接':'permanent','SSH连接':'server'}


class Desktop(ctk.CTk):
    def __init__(self):
        super().__init__()
        # Tk image/font cycles must be finalized by the Tk thread. Automatic GC
        # in a busy adapter worker otherwise waits on Tk (especially during a
        # native window drag). Keep collection on this window's event loop.
        self.gc_enabled=gc.isenabled();gc.disable();self.gc_ticks=0
        self.after(5000,self.collect_ui)
        self.title('coding远程')
        self.geometry('780x700')
        self.minsize(700,650)
        self.configure(fg_color=UI_BG)
        self.iconbitmap(str(ASSETS/'assets/codex-mobile.ico'))
        self.events=queue.Queue(); self.stopping=False;self.network_check_at=0;self.network_checking=False;self.disabled_routes=set();self.route_generation={mode:0 for mode in CONNECTION_MODES.values()}
        self.mode='temporary';self.routes={m:{'link':None,'origin':None,'connecting':False,'error':''} for m in CONNECTION_MODES.values()}
        self.config_path=DATA/'settings.json'
        try:self.settings=json.loads(self.config_path.read_text('utf-8'))
        except FileNotFoundError:self.settings={'auto_connect':True}
        preferred=self.settings.get('preferred_connection','temporary')
        if preferred in self.routes:self.mode=preferred
        if self.settings.get('connection_version')!=4:
            self.settings.update(connection_version=4,auto_connect=True,lan_enabled=False)
            self.settings.setdefault('http_port',8787)
            self.config_path.write_text(json.dumps(self.settings),encoding='utf-8')
        appearance.apply(self.settings.get("appearance","system"), self.settings.get("accent","mist"), self.settings.get('visualStyle','classic'))
        self.ingress=None
        self.pairing=Pairing(DATA)
        workspace=Path(self.settings.get('claude_workspace',DATA/'bridge')).resolve()
        self.claude_workspace=workspace
        self.claude=None
        self.server=None
        self.network=None
        self.apps=None
        self.in_controls_ready=True
        self.controls_ready=False
        self.pending_routes=set()
        self.ingress_lock=threading.Lock()
        self.link_lock=threading.Lock();self.owned_links=set()
        self.ingress_checked=0
        self.tray=None
        self.protocol('WM_DELETE_WINDOW',self.hide_window)
        self.bind('<Unmap>',self.on_minimize,add='+')
        heading=ctk.CTkFrame(self,fg_color='transparent')
        self.logo=ctk.CTkImage(Image.open(ASSETS/'assets/codex-mobile.png'),size=(34,34))
        ctk.CTkLabel(heading,text='',image=self.logo).pack(side='left',padx=(0,10))
        self.page_title=ctk.CTkLabel(heading,text='coding远程',font=FONT(20,'bold'),text_color=UI_INK);self.page_title.pack(side='left')
        self.content=ctk.CTkFrame(self,fg_color=UI_BG,corner_radius=0,border_width=0)
        self.content.pack(fill='both',expand=True)
        self.page_host=ctk.CTkFrame(self.content,fg_color='transparent');self.page_host.pack(fill='both',expand=True)
        self.frames={};self.current_page='';self.page_animation=None;self.page_epoch=0
        for name in ['概览','查看日志','应用设置']:
            self.frames[name]=(ctk.CTkScrollableFrame if name in ('应用设置','概览') else ctk.CTkFrame)(self.page_host,fg_color='transparent')
        self.build_access();self.build_logs();self.build_settings()
        from .desktop_workbench import Workbench
        self.workbench=Workbench(self,DATA)
        self.apply_design()
        self.show_tab('概览')
        self.after(300,self.tick)
        # The window is on screen before the controller, the server and the two
        # network routes are built, so launching the assistant is not gated on any
        # of them. refresh_route() repaints whatever the user is looking at when a
        # worker needs a control that does not exist yet.
        self.after(1,self.start_services)

    def start_services(self):
        if self.stopping:return
        threading.Thread(target=self.build_services,daemon=True,name="desktop-services").start()

    def collect_ui(self):
        if self.stopping:return
        self.gc_ticks+=1;gc.collect(2 if self.gc_ticks%12==0 else 0)
        self.after(5000,self.collect_ui)

    def build_services(self):
        try:
            from claude_bridge.packaged import Controller
            self.claude=Controller(self.claude_workspace/'.coding-remote',reconnect=True,transport='file')
            self.server=self.create_server()
            self.network=self.create_network()
            self.apps=Applications(DATA,lambda:self.claude.console_script,lambda visible:self.events.put(('window',visible)),self.claude.desktop,on_connected=lambda:self.events.put(('claude_connected',None)),codex=self.server.bridge,deepseek=self.server.deepseek)
            self.server.apps=self.apps
            self.server.queue.start()
            if self.stopping:
                self.server.server_close();self.server.bridge.close();self.claude.close()
                if self.apps:self.apps.close()
                if self.network:self.network.close()
                return
            self.events.put(('services_ready',True))
        except Exception:
            logging.exception('Assistant services failed to start')
            self.events.put(('services_ready',False))

    def create_server(self):
        from bridge.service import Bridge
        from .api import NativeServer
        from .codex_plugins import PluginBridgeMixin
        bridge=type('MobileBridge',(PluginBridgeMixin,Bridge),{})(Path(os.environ.get('CODEX_HOME',Path.home()/'.codex')),DATA/'codex')
        server=NativeServer.bind_available(bridge,self.claude,self.pairing,DATA,self.settings.get('http_port',0))
        self.settings['http_port']=server.server_port
        self.config_path.write_text(json.dumps(self.settings,ensure_ascii=False),encoding='utf-8')
        server.refresh_ingress=self.refresh_ingress
        return server

    def create_network(self):
        from .embedded_network import EmbeddedNetwork
        return EmbeddedNetwork(ASSETS,DATA,self.server.server_port,self.pairing.computer_id,self.server.allow_origin)

    def finish_services(self):
        if self.stopping:return
        try:self.server.deepseek.install()
        except Exception:logging.exception('DeepSeek adapter setup failed')
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        self.tray=Tray(ASSETS,lambda action:self.events.put(('tray',action)))
        # A fresh install immediately offers a temporary QR. Existing permanent
        # bindings restore the original userspace Tailscale route independently.
        devices=self.pairing.list_devices();modes={row.get('mode') for row in devices}
        server_origin=self.settings.get('server_connection',{}).get('origin')
        if (self.settings.get('auto_connect',True) and self.mode=='temporary') or 'temporary' in modes:self.start_route('temporary')
        if any(row.get('mode')=='permanent' and row.get('origin')!=server_origin for row in devices) or self.network.has_identity():self.start_route('permanent')
        if server_origin and (self.settings.get('auto_connect',True) or any(row.get('origin')==server_origin for row in devices)):self.start_route('server')

    def refresh_route(self):
        """Repaint the current page from the data that exists right now."""
        if self.stopping:return
        self.state_label.configure(text='已绑定 %d 台设备'%len(self.pairing.grouped_devices()) if self.controls_ready else '正在准备服务…')
        if self.mode=='permanent' and self.controls_ready:self.render_route()

    def show_tab(self,name):
        if name==self.current_page:return
        self.current_page=name
        frame=self.frames[name]
        if not getattr(frame,'_page_placed',False):
            frame.configure(fg_color=UI_BG)
            frame.place(x=0,y=0,relwidth=1,relheight=1)
            frame._page_placed=True
        frame.lift()
        self.page_title.configure(text='coding远程' if name=='概览' else '设置' if name=='应用设置' else '日志' if name=='查看日志' else name)
        if hasattr(self,'workbench'):
            for page,button in self.workbench.nav.items():button.configure(fg_color=UI_CARD if page==name else 'transparent',text_color=UI_INK if page==name else UI_MUTED)
        if name=='查看日志':self.refresh_logs()
        if name=='应用设置' and self.controls_ready:self.refresh_devices()

    def build_access(self):
        f=self.frames['概览'];f.grid_columnconfigure(0,weight=1);f.configure(fg_color=UI_BG)
        ctk.CTkLabel(f,text='扫码连接',font=FONT(20,'bold'),text_color=UI_INK).grid(row=0,column=0,sticky='w',padx=24,pady=(24,8))
        self.pair_card=ctk.CTkFrame(f,fg_color=UI_CARD,corner_radius=14,border_width=1,border_color=UI_LINE);self.pair_card.grid(row=1,column=0,sticky='nsew',padx=24,pady=8)
        ctk.CTkLabel(self.pair_card,text='连接手机',font=FONT(20,'bold'),text_color=UI_INK).pack(pady=(16,12))
        self.connection_modes=ctk.CTkOptionMenu(self.pair_card,values=list(CONNECTION_MODES),width=270,fg_color=UI_SOFT,button_color=UI_TINT,text_color=UI_INK,command=self.change_mode);self.connection_modes.pack(padx=12,pady=8);self.connection_modes.set(next(label for label,mode in CONNECTION_MODES.items() if mode==self.mode))
        self.status=ctk.CTkLabel(self.pair_card,text='正在准备服务…',font=FONT(13),text_color=UI_MUTED,wraplength=280);self.status.pack(padx=12,pady=8)
        self.qr_holder=ctk.CTkFrame(self.pair_card,fg_color='transparent');self.qr_holder.pack()
        self.qr=ctk.CTkLabel(self.qr_holder,text='等待连接',width=270,height=270,fg_color=UI_CARD,corner_radius=14,text_color=UI_MUTED,font=FONT(14));self.qr.pack()
        self.hint=ctk.CTkLabel(self.pair_card,text='二维码 5 分钟有效，单次使用',font=FONT(12),text_color=UI_MUTED,wraplength=280);self.hint.pack(padx=12,pady=12)
        self.connect_button=ctk.CTkButton(self.pair_card,text='开启设备连接',command=self.connect,height=40,corner_radius=20,font=FONT(14),fg_color=UI_ACCENT,hover_color=UI_ACCENT_DARK,text_color=('#FFFFFF','#15181D'));self.connect_button.pack(fill='x',padx=20,pady=4)
        self.refresh_button=ctk.CTkButton(self.pair_card,text='刷新二维码',command=lambda:self.refresh_qr(force=True),height=40,corner_radius=20,font=FONT(14),fg_color='transparent',hover_color=UI_TINT,text_color=UI_INK);self.refresh_button.pack(fill='x',padx=20,pady=4)
        self.state_label=ctk.CTkLabel(self.pair_card,text='尚未连接手机',font=FONT(12),text_color=UI_MUTED);self.state_label.pack(pady=(8,16))
        self.qr_serial=0;self.qr_hidden=False;self.qr_pending=None

    def control_app(self,key,action):
        if action not in ('启动','关闭','重启'):return
        if not self.controls_ready:
            # The panel can be opened before the services exist; saying so beats a
            # thread that would fail on a missing controller.
            self.status.configure(text='正在准备服务，请稍候重试');return
        if action!='启动':
            from tkinter import messagebox
            if not messagebox.askyesno(action+' '+key,'这会中断当前任务，是否继续？',parent=self):return
        def work():
            try:
                result=self.apps.execute(key,{'启动':'start','关闭':'stop','重启':'restart'}[action],action!='启动')
                self.events.put(('claude_tools',result.get('message','已提交')))
            except Exception:logging.exception('Application control failed');self.events.put(('claude_tools','操作失败，请查看日志'))
        threading.Thread(target=work,daemon=True).start()


    def build_logs(self):
        f=self.frames['查看日志']
        ctk.CTkLabel(f,text='应用日志',font=FONT(21,'bold'),text_color=UI_INK).pack(anchor='w',pady=(4,0))
        self.logs=ctk.CTkTextbox(f,font=('Consolas',12),fg_color=UI_SOFT,text_color=UI_INK,corner_radius=12,border_width=1,border_color=UI_LINE,scrollbar_button_color=UI_LINE,scrollbar_button_hover_color=UI_MUTED)
        self.logs.pack(fill='both',expand=True,pady=(12,12))
        actions=ctk.CTkFrame(f,fg_color='transparent');actions.pack(fill='x')
        ctk.CTkButton(actions,text='重新连接 Claude',height=40,corner_radius=10,font=FONT(13,'bold'),command=self.open_claude).pack(side='left')
        ctk.CTkButton(actions,text='刷新日志',width=120,height=40,corner_radius=10,font=FONT(13),fg_color=UI_TINT,hover_color=UI_SOFT,text_color=UI_INK,command=self.refresh_logs).pack(side='right')

    def refresh_logs(self):
        if hasattr(self,'workbench'):self.workbench.refresh_logs()

    def build_settings(self):
        self.settings_body=f=ctk.CTkFrame(self.frames['应用设置'],fg_color='transparent')
        f.pack(fill='x',padx=24,pady=(20,24))
        self.settings_notes=[]
        group=None
        def section(title):
            nonlocal group
            spacing=0 if group is None else 16
            group=ctk.CTkFrame(f,fg_color='transparent');group.pack(fill='x',pady=(spacing,0))
            header=ctk.CTkFrame(group,fg_color='transparent');header.pack(fill='x',pady=(0,8))
            ctk.CTkFrame(header,fg_color=UI_ACCENT,width=3,height=14,corner_radius=2).pack(side='left',padx=(0,8))
            ctk.CTkLabel(header,text=title,height=24,font=FONT(13,'bold'),text_color=UI_MUTED).pack(side='left')
        def row(title,entry=False):
            frame=ctk.CTkFrame(group,fg_color=UI_CARD,corner_radius=12,border_width=1,border_color=UI_LINE,height=52)
            frame.pack(fill='x',pady=(0,8));frame.grid_propagate(False)
            frame.grid_rowconfigure(0,weight=1)
            frame.grid_columnconfigure(0,weight=0 if entry else 1,minsize=152 if entry else 0)
            frame.grid_columnconfigure(1,weight=1 if entry else 0)
            ctk.CTkLabel(frame,text=title,font=FONT(14),text_color=UI_INK).grid(row=0,column=0,sticky='w',padx=(16,12))
            return frame
        def note(parent,after,text=''):
            label=ctk.CTkLabel(parent,text='',height=1,anchor='w',justify='left',font=FONT(12),text_color=UI_MUTED,wraplength=500)
            label._status_after=after;self.settings_notes.append(label)
            self.set_settings_status(label,text)
            return label
        def toggle(parent,variable):
            return ctk.CTkSwitch(parent,text='',width=48,height=28,switch_width=42,switch_height=24,border_width=0,button_length=0,corner_radius=12,variable=variable,command=self.save_settings,progress_color=UI_TINT,fg_color=UI_LINE,button_color=UI_ACCENT,button_hover_color=UI_ACCENT_DARK)
        def action(parent,label,command,color=UI_INK):
            return ctk.CTkButton(parent,text=label,width=118,height=34,corner_radius=10,font=FONT(13),fg_color=UI_TINT,hover_color=UI_SOFT,text_color=color,command=command)
        section('通用')
        self.auto=ctk.BooleanVar(value=self.settings.get('auto_connect',True))
        toggle(row('断线自动重连'),self.auto).grid(row=0,column=1,padx=16)
        self.minimize_tray=ctk.BooleanVar(value=self.settings.get('minimize_to_tray',False))
        toggle(row('最小化到托盘'),self.minimize_tray).grid(row=0,column=1,padx=16)
        section('永久连接')
        network_row=row('网络账户')
        action(network_row,'连接 / 查看',self.login_network).grid(row=0,column=1,padx=16)
        self.network_status=note(group,network_row)
        self.external_network=ctk.BooleanVar(value=self.settings.get('external_tailscale',False))
        toggle(row('使用已安装的网络客户端'),self.external_network).grid(row=0,column=1,padx=16)
        # Claude feedback belongs beside its action on the log page.
        self.copy_status=note(self.frames['查看日志'],self.logs)
        section('SSH连接')
        server_config=self.settings.get('server_connection',{})
        self.server_fields={}
        for title,key,placeholder in [('HTTPS 地址','origin','https://your-server.example.com'),('SSH 主机别名','sshTarget','coding-remote-azure'),('远端转发端口','remotePort','18787')]:
            field=ctk.CTkEntry(row(title,entry=True),width=160,height=34,corner_radius=8,border_width=1,border_color=UI_LINE,font=FONT(13),placeholder_text=placeholder,fg_color=UI_SOFT,text_color=UI_INK)
            field.grid(row=0,column=1,sticky='ew',padx=(0,16))
            field.insert(0,str(server_config.get(key,18787 if key=='remotePort' else '')))
            self.server_fields[key]=field
        note(group,field.master,'手机只需扫码。电脑使用系统 SSH 配置中的密钥连接服务器。')
        server_row=row('服务器连接')
        action(server_row,'保存并连接',self.save_server_connection).grid(row=0,column=1,padx=16)
        self.server_config_status=note(group,server_row)
        section('已绑定设备')
        self.device_frame=ctk.CTkFrame(group,fg_color='transparent');self.device_frame.pack(fill='x')
        f.bind('<Configure>',self.resize_settings,add='+')

    def set_settings_status(self,label,text):
        label.configure(text=text)
        if text:label.pack(fill='x',after=label._status_after,padx=16,pady=(0,8))
        else:label.pack_forget()

    def resize_settings(self,event):
        width=max(160,int(event.width/self.settings_body._get_widget_scaling())-32)
        if width==getattr(self,'settings_note_width',None):return
        self.settings_note_width=width
        for label in self.settings_notes:label.configure(wraplength=width)

    def save_server_connection(self):
        from .server_link import validate_config
        from .ingress_common import write_json
        try:
            config=validate_config({key:field.get().strip() for key,field in self.server_fields.items()})
            settings={**self.settings,'server_connection':config,'preferred_connection':'server'}
            write_json(self.config_path,settings)
        except (ValueError,OSError) as error:
            self.set_settings_status(self.server_config_status,str(error));return
        self.settings=settings
        route=self.routes['server'];old_link=route['link'];old_origin=route['origin']
        if old_origin:self.pairing.clear_origin(old_origin)
        self.route_generation['server']+=1;generation=self.route_generation['server']
        self.disabled_routes.discard('server')
        route.update(link=None,origin=None,error='',connecting=True)
        self.mode='server';self.connection_modes.set('SSH连接')
        self.clear_qr('正在连接服务器');self.render_route();self.show_tab('概览')
        self.set_settings_status(self.server_config_status,'配置已保存')
        def restart():
            if old_link:self.close_link(old_link)
            self.events.put(('server_restart',generation))
        threading.Thread(target=restart,daemon=True).start()

    def login_network(self):
        if getattr(self,'network_login_active',False):return
        if not self.controls_ready:
            self.set_settings_status(self.network_status,'正在准备服务，请稍候重试');return
        self.network_login_active=True
        cancel=threading.Event();self.network_login_cancel=cancel
        self.set_settings_status(self.network_status,'正在连接设备网络…')
        def work():
            opened=set();selected=False
            try:
                while not self.stopping and not cancel.is_set():
                    try:
                        status=self.network.login();url=self.network.login_url(status)
                        if cancel.is_set():return
                        if not selected:self.events.put(('network_selected',cancel));selected=True
                        if url and url not in opened:
                            import webbrowser
                            webbrowser.open(url);opened.add(url)
                        self.events.put(('network_progress',self.network.describe(status)))
                        if status.get('state') in ('Running','Stopped'):return
                    except Exception:
                        self.events.put(('network_progress','设备网络暂时不可用，正在重试；请检查互联网连接'))
                    cancel.wait(2)
            finally:self.events.put(('network_login_done',None))
        threading.Thread(target=work,daemon=True).start()

    def open_claude(self):
        self.run_claude('start')

    def restart_claude(self):
        from tkinter import messagebox
        if messagebox.askyesno('重启 Claude','重启会中断 Claude 的当前任务。确定继续？',parent=self):self.run_claude('restart')

    def run_claude(self,action):
        if not self.controls_ready:
            self.set_settings_status(self.copy_status,'正在准备服务，请稍候重试');return
        self.set_settings_status(self.copy_status,'正在连接 Claude…')
        def work():
            try:
                result=self.apps.execute('claude',action,action=='restart')
                if result.get('status')=='needs_restart':self.events.put(('claude_restart',None))
                self.events.put(('claude_tools',result.get('message','Claude 已启动，正在自动连接…')))
            except Exception:
                logging.exception('Claude keyboard startup failed')
                self.events.put(('claude_tools','Claude 启动失败，请查看日志'))
        threading.Thread(target=work,daemon=True).start()

    def change_appearance(self,mode):
        self.settings['appearance']=mode
        if hasattr(self,'theme_choice'):self.theme_choice.set({'system':'跟随系统','light':'浅色','dark':'深色'}.get(mode,'跟随系统'))
        self.config_path.write_text(json.dumps(self.settings),encoding='utf-8')
        appearance.apply(mode, self.settings.get('accent','mist'),self.settings.get('visualStyle','classic'))
        def repaint(widget):
            if isinstance(widget, ctk.CTkBaseClass):widget._set_appearance_mode(ctk.get_appearance_mode())
            for child in widget.winfo_children():repaint(child)
        repaint(self)
        if hasattr(self,'workbench'):self.workbench.style_tree()
        self.apply_design()

    def change_design(self,label):
        self.settings['visualStyle']=appearance.DESIGNS[label]
        self.design_choice.set(label)
        self.change_appearance(self.settings.get('appearance','system'))

    def apply_design(self):
        design=self.settings.get('visualStyle','classic')
        def walk(widget):
            if isinstance(widget,(ctk.CTkFrame,ctk.CTkButton,ctk.CTkEntry)):
                if not hasattr(widget,'_original_radius'):widget._original_radius=widget.cget('corner_radius')
                radius=widget._original_radius
                widget.configure(corner_radius=min(radius,4 if design=='compact' else 8) if design!='classic' else radius)
            if isinstance(widget,ctk.CTkLabel):
                current=widget.cget('font')
                if isinstance(current,tuple) and current[0] not in ('Consolas','Courier New'):
                    widget.configure(font=(('SimSun' if design=='paper' else 'Microsoft YaHei UI'),*current[1:]))
            for child in widget.winfo_children():walk(child)
        walk(self)

    def change_accent(self, label):
        self.settings['accent']=appearance.STYLES[label]
        if hasattr(self,'accent_choice'):self.accent_choice.set(label)
        self.change_appearance(self.settings.get('appearance','system'))

    def save_settings(self):
        self.settings['auto_connect']=bool(self.auto.get())
        self.settings['minimize_to_tray']=bool(self.minimize_tray.get())
        self.settings['open_claude']=False
        previous=self.settings.get('external_tailscale',False)
        self.settings['external_tailscale']=bool(self.external_network.get())
        if self.settings['external_tailscale'] and getattr(self,'network_login_cancel',None):self.network_login_cancel.set()
        if previous!=self.settings['external_tailscale']:
            self.route_generation['permanent']+=1
            route=self.routes['permanent']
            if route['link']:self.close_link(route['link'])
            route.update(link=None,origin=None,connecting=False,error='')
            self.start_route('permanent')
        self.config_path.write_text(json.dumps(self.settings),encoding='utf-8')

    def refresh_devices(self):
        devices=self.pairing.grouped_devices()
        signature=[(d['id'],d['name'],d['credentialIds'],time.time()-d.get('lastSeen',0)<90) for d in devices]
        if signature==getattr(self,'device_signature',None):return
        self.device_signature=signature
        for w in self.device_frame.winfo_children():w.destroy()
        if not devices:ctk.CTkLabel(self.device_frame,text='暂无已配对设备',font=FONT(13),text_color=UI_MUTED).pack(anchor='w',pady=(0,8))
        for device in devices:
            row=ctk.CTkFrame(self.device_frame,fg_color=UI_CARD,corner_radius=12,border_width=1,border_color=UI_LINE);row.pack(fill='x',pady=(0,8))
            row.grid_columnconfigure(0,weight=1);row.grid_rowconfigure(0,minsize=52)
            online=time.time()-device.get('lastSeen',0)<90
            ctk.CTkLabel(row,text=device['name'],anchor='w',justify='left',wraplength=240,font=FONT(14,'bold'),text_color=UI_INK).grid(row=0,column=0,sticky='w',padx=(16,12),pady=10)
            ctk.CTkLabel(row,text='在线' if online else '等待连接',font=FONT(12),text_color=UI_ACCENT if online else UI_MUTED).grid(row=0,column=1,padx=(0,12))
            ctk.CTkButton(row,text='解除配对',width=96,height=34,corner_radius=10,font=FONT(13),fg_color=UI_TINT,hover_color=UI_SOFT,text_color=UI_DANGER,command=lambda d=device:self.revoke(d['credentialIds'])).grid(row=0,column=2,padx=(0,16))

    def revoke(self,device_id):
        for key in device_id if isinstance(device_id,list) else [device_id]:self.pairing.revoke(key)
        self.refresh_devices()
        self.refresh_qr()

    def change_mode(self,label):
        self.mode=CONNECTION_MODES[label]
        self.settings['preferred_connection']=self.mode
        self.config_path.write_text(json.dumps(self.settings,ensure_ascii=False),encoding='utf-8')
        self.render_route()
        self.refresh_qr()

    def connect(self):
        route=self.routes[self.mode]
        if route['origin']:self.disconnect()
        else:
            self.disabled_routes.discard(self.mode)
            # The button still responds before the services are up: the request is
            # queued rather than dropped, and start_route() reports "connecting".
            if not self.controls_ready:self.render_route()
            self.start_route(self.mode)

    def start_route(self,mode):
        route=self.routes[mode]
        if self.stopping or mode in self.disabled_routes or route['connecting'] or route['origin']:return
        if not self.controls_ready:
            # start_services() runs this once the server port exists; a button press
            # before then must not build a tunnel against a listener that is not up.
            self.pending_routes.add(mode)
            return
        route['connecting']=True;route['error']=''
        generation=self.route_generation[mode]
        if mode==self.mode:self.render_route()
        def work():
            link=None
            try:
                if mode=='server':
                    from .server_link import ServerLink
                    link=ServerLink(self.server,self.settings.get('server_connection'),DATA)
                elif mode=='permanent':
                    from .tailscale_link import TailscaleLink
                    from .embedded_network import EmbeddedLink
                    link=TailscaleLink(self.server.server_port,DATA,self.server.allow_origin) if self.settings.get('external_tailscale',False) else EmbeddedLink(self.network,self.server.allow_origin)
                else:
                    from .temporary_link import TemporaryLink
                    link=TemporaryLink(self.server.server_port,DATA,ASSETS,self.server.allow_origin)
                with self.link_lock:
                    if self.stopping or generation!=self.route_generation[mode]:return
                    self.owned_links.add(link)
                origin=link.start()
                if self.stopping or generation!=self.route_generation[mode]:self.close_link(link);return
                self.events.put(('route',(mode,link,origin,'',generation)))
            except Exception as exc:
                if link:self.close_link(link)
                if getattr(self,'last_route_error',{}).get(mode)!=str(exc):
                    logging.warning('%s connection unavailable: %s',mode,exc)
                    if not hasattr(self,'last_route_error'):self.last_route_error={}
                    self.last_route_error[mode]=str(exc)
                self.events.put(('route',(mode,None,None,str(exc),generation)))
        threading.Thread(target=work,daemon=True).start()

    def close_link(self,link):
        try:link.close()
        except Exception:logging.exception('Could not close owned connection')
        finally:
            with self.link_lock:self.owned_links.discard(link)

    def refresh_ingress(self):
        if not self.controls_ready or not self.check_embedded_network():return
        with self.ingress_lock:
            if time.monotonic()-self.ingress_checked<2:return
            self.ingress_checked=time.monotonic()
            try:self.network.status()
            except Exception:pass

    def check_embedded_network(self):
        if 'permanent' in self.disabled_routes:return False
        if not self.settings.get('external_tailscale',False):return True
        # Existing bindings can belong to the embedded node even when the
        # external VPN is preferred. Restore its saved identity without login.
        return self.network.has_identity() and any(d.get('mode')=='permanent' for d in self.pairing.list_devices())

    def render_route(self):
        route=self.routes[self.mode]
        self.connect_button.configure(state='disabled' if route['connecting'] else 'normal',text='连接中…' if route['connecting'] else '关闭此连接' if route['origin'] else '开启此连接')
        self.refresh_button.configure(state='disabled' if route['connecting'] else 'normal')
        label=next(label for label,mode in CONNECTION_MODES.items() if mode==self.mode)
        self.status.configure(text=route['error'][:260] or (label+'已开启' if route['origin'] else '正在建立连接…' if route['connecting'] else '此连接已关闭'))
        hints={'server':'通过固定 HTTPS 地址永久绑定；手机无需安装额外网络客户端。','permanent':'绑定后自动重连；手机和电脑需登录同一 连接网络。','temporary':'临时连接无需额外网络客户端；有效期 24 小时，关闭助手或此入口后失效。'}
        self.hint.configure(text=hints[self.mode])

    def disconnect(self):
        if self.mode=='permanent' and getattr(self,'network_login_cancel',None):self.network_login_cancel.set()
        self.pending_routes.discard(self.mode)
        self.disabled_routes.add(self.mode)
        self.route_generation[self.mode]+=1
        route=self.routes[self.mode];origin=route['origin'];link=route['link']
        route.update(origin=None,link=None,error='',connecting=False)
        if origin:self.pairing.clear_origin(origin)
        if self.mode=='temporary' and origin:self.pairing.close_temporary(origin)
        if link:threading.Thread(target=self.close_link,args=(link,),daemon=True).start()
        if self.mode=='permanent' and self.controls_ready and not self.settings.get('external_tailscale',False):
            threading.Thread(target=self.network.close,daemon=True).start()
        self.clear_qr('连接已关闭')
        self.render_route()

    def clear_qr(self, text):
        self.qr_serial+=1;self.qr_pending=None
        # CTkLabel retains a Tcl image name when image=None; destroy it before
        # releasing its PhotoImage, including disconnected/unavailable routes.
        # CTkLabel.destroy does not detach the CTkImage callback. Break that
        # cycle here so Tk PhotoImages are released on the UI thread.
        if self.qr.cget('image') is not None:self.qr.configure(image=None)
        self.qr.destroy()
        self.qr_image=None
        self.qr=ctk.CTkLabel(self.qr_holder,text=text,width=270,height=270,fg_color=UI_SOFT,corner_radius=14,text_color=UI_MUTED,font=FONT(14))
        self.qr.pack()

    @staticmethod
    def set_label(widget,text):
        if widget.cget('text')!=text:widget.configure(text=text)

    def sync_pairing_ui(self):
        route=self.routes[self.mode];origin=route['origin']
        paired=bool(origin and any(d.get('origin')==origin for d in self.pairing.list_devices()))
        self.set_label(self.state_label,'已绑定 '+str(len(self.pairing.grouped_devices()))+' 台设备' if self.controls_ready else '正在准备服务…')
        if paired!=self.qr_hidden:
            self.qr_hidden=paired;self.qr_serial+=1;self.qr_pending=None
            if paired:
                self.pairing.clear_origin(origin)
                self.clear_qr('手机已连接')
                self.qr_holder.pack_forget();self.hint.pack_forget();self.refresh_button.pack_forget()
            else:
                self.qr_holder.pack(before=self.connect_button)
                self.hint.pack(before=self.connect_button,padx=12,pady=12)
                self.refresh_button.pack(before=self.state_label,fill='x',padx=20,pady=4)
        if paired and not route['error']:self.set_label(self.status,'手机已连接，当前线路可自动重连')
        return paired

    @staticmethod
    def qr_bitmap(payload):
        code=QrCode.encode_text(payload,QrCode.Ecc.MEDIUM)
        size=code.get_size();scale=max(3,270//(size+8));image=Image.new('RGB',((size+8)*scale,)*2,'white');draw=ImageDraw.Draw(image)
        for y in range(size):
            for x in range(size):
                if code.get_module(x,y):draw.rectangle(((x+4)*scale,(y+4)*scale,(x+5)*scale-1,(y+5)*scale-1),fill='#171717')
        return image

    def refresh_qr(self,force=False):
        if self.sync_pairing_ui():return
        self.disabled_routes.discard(self.mode)
        mode=self.mode;route=self.routes[mode]
        if not route['origin']:
            self.clear_qr('等待连接' if self.controls_ready else '正在准备服务…')
            if self.controls_ready:self.start_route(mode)
            return
        if route['error']:self.clear_qr('入口未就绪');return
        origin=route['origin']
        with self.pairing.lock:pending=next((v for v in self.pairing.codes.values() if v['origin']==origin and v['expires']>time.time()),None)
        if not force and pending and self.qr_pending==(mode,origin,pending['token']):return
        payload=self.pairing.issue(origin,'temporary' if mode=='temporary' else 'permanent')
        self.clear_qr('正在生成二维码…')
        self.qr_serial+=1;serial=self.qr_serial;self.qr_pending=(mode,origin,json.loads(payload)['token'])
        def render():
            try:self.events.put(('qr_ready',(serial,self.qr_bitmap(payload))))
            except Exception:logging.exception('QR image generation failed')
        threading.Thread(target=render,daemon=True,name='pairing-qr').start()

    def show_qr(self,serial,image):
        pending=self.qr_pending
        route=self.routes[self.mode]
        if serial!=self.qr_serial or not pending or pending[:2]!=(self.mode,route['origin']) or route['error'] or self.sync_pairing_ui():return
        if self.qr.cget('image') is not None:self.qr.configure(image=None)
        self.qr.destroy()
        self.qr_image=ctk.CTkImage(light_image=image,dark_image=image,size=(270,270))
        self.qr=ctk.CTkLabel(self.qr_holder,image=self.qr_image,text='',width=270,height=270,fg_color=UI_CARD);self.qr.pack()
        logging.info('Pairing QR refreshed successfully')
        self.set_label(self.status,'设备连接已开启，请使用手机 App 扫码')

    def tick(self):
        if self.stopping:return
        if self.controls_ready and self.check_embedded_network() and not self.network_checking and time.monotonic()-self.network_check_at>3:
            self.network_checking=True;self.network_check_at=time.monotonic()
            generation=self.route_generation['permanent']
            def check_network():
                try:
                    state=self.network.status()
                    self.events.put(('network_health',(generation,state)))
                except Exception:self.events.put(('network_health',(generation,{})))
            threading.Thread(target=check_network,daemon=True).start()
        while not self.events.empty():
            kind,value=self.events.get()
            if kind=='qr_ready':self.show_qr(*value)
            elif kind=='services_ready':
                self.controls_ready=bool(value);self.in_controls_ready=False;self.refresh_route()
                if value:
                    pending,self.pending_routes=self.pending_routes,set()
                    for mode in pending:self.start_route(mode)
                    self.after(1,self.finish_services)
                else:
                    self.workbench.banner.configure(text='启动失败 · 请查看日志')
                    self.status.configure(text='服务启动失败，请查看日志。')
            elif kind=='server_restart':
                if value==self.route_generation['server'] and 'server' not in self.disabled_routes:
                    self.routes['server']['connecting']=False;self.start_route('server')
            elif kind=='network_health':
                self.network_checking=False
                generation,state=value
                if generation!=self.route_generation['permanent'] or 'permanent' in self.disabled_routes:continue
                value=state.get('origin','') if state.get('state')=='Running' else ''
                route=self.routes['permanent']
                # Let the preferred external VPN finish its initial attempt.
                # Its absence must not strand a saved embedded-device binding.
                from .tailscale_link import TailscaleLink
                from .embedded_network import EmbeddedLink
                if self.settings.get('external_tailscale',False) and (isinstance(route['link'],TailscaleLink) or (not route['origin'] and not route['error'])):continue
                if value and value!=route['origin']:
                    self.server.allow_origin(value);route.update(origin=value,link=EmbeddedLink(self.network,self.server.allow_origin),error='',connecting=False)
                    logging.info('Saved private device network restored')
                    if self.mode=='permanent':self.render_route();self.refresh_qr()
                elif not value and route['origin']:
                    route.update(origin=None,error='设备网络重连中',connecting=False)
                    if self.mode=='permanent':self.render_route()
            elif kind=='tray':
                self.tray_action(value)
                if self.stopping:return
            elif kind=='network_progress':
                if self.controls_ready:self.set_settings_status(self.network_status,value)
            elif kind=='network_login_done':self.network_login_active=False
            elif kind=='network_selected':
                if value is not None and value.is_set():continue
                self.external_network.set(False);self.save_settings()
                self.disabled_routes.discard('permanent');self.start_route('permanent')
            elif kind=='window':
                self.apps.window_open=value
                if value:self.show_window()
                else:self.withdraw()
            elif kind=='route':
                mode,link,origin,error,generation=value
                if generation!=self.route_generation[mode] or mode in self.disabled_routes:
                    if link:threading.Thread(target=self.close_link,args=(link,),daemon=True).start()
                    continue
                if error and self.routes[mode]['origin']:
                    # A late startup error must not replace a recovered node.
                    continue
                self.routes[mode].update(link=link,origin=origin,error=error,connecting=False)
                if mode==self.mode:
                    self.render_route()
                    if origin:self.refresh_qr()
                if error and (mode=='permanent' or self.settings.get('auto_connect',True)):self.after(15000,lambda m=mode:self.start_route(m))
            elif kind=='claude_tools':
                if self.controls_ready:self.set_settings_status(self.copy_status,value)
            elif kind=='claude_connected':
                if self.controls_ready:self.set_settings_status(self.copy_status,'Claude 已连接')
                if self.state()!='withdrawn':self.iconify()
            elif kind=='claude_restart':self.restart_claude()
        for mode,connection in self.routes.items():
            link=connection['link']
            if mode=='server' and link:
                health=link.snapshot();error='' if health['healthy'] else health['message']
                if error!=connection['error']:
                    connection['error']=error
                    if self.mode==mode:
                        self.render_route()
                        if error:self.clear_qr('入口重连中')
                        else:self.refresh_qr()
            if mode=='temporary' and link and link.process and link.process.poll() is not None:
                # The tunnel died on its own; keep the paired credentials so an
                # assistant or tunnel restart does not force another scan.
                self.pairing.retain_temporary(connection['origin'])
                threading.Thread(target=self.close_link,args=(link,),daemon=True).start()
                connection.update(link=None,origin=None,error='临时连接已中断，正在重新建立')
                if mode==self.mode:self.clear_qr('连接已中断');self.render_route()
                if self.settings.get('auto_connect',True):self.start_route(mode)
        paired=self.sync_pairing_ui()
        route=self.routes[self.mode]
        if route['origin'] and not paired:
            with self.pairing.lock:pending=next((v for v in self.pairing.codes.values() if v['origin']==route['origin']),None)
            if pending and pending['expires']<time.time():self.set_label(self.hint,'二维码已过期，请点击刷新二维码')
            elif pending and self.state()=='normal':self.set_label(self.hint,'二维码剩余 '+str(max(0,int(pending['expires']-time.time())))+' 秒 · 单次使用')
        self.after(700,self.tick)

    def show_window(self):
        # deiconify() alone leaves the assistant behind other windows on Windows.
        self.deiconify();self.state('normal');self.lift()
        try:
            self.attributes('-topmost',True);self.after(500,lambda:self.attributes('-topmost',False))
        except Exception:pass
        try:self.focus_force()
        except Exception:pass

    def hide_window(self):
        if self.apps:self.apps.window_open=False
        if getattr(self,'tray',None) and self.tray.ready:self.withdraw()
        else:self.iconify()

    def on_minimize(self,event):
        if event.widget is self and not self.stopping and self.state()=='iconic' and self.settings.get('minimize_to_tray',False) and getattr(self,'tray',None) and self.tray.ready:
            self.after_idle(self.withdraw)

    def tray_action(self,action):
        if action.startswith('notice:'):
            _,backend,sid=action.split(':',2)
            if backend in ('codex','claude','deepseek'):self.show_window();self.workbench.select(backend+':'+sid)
            return
        if action=='quit':self.close();return
        if action=='minimize':self.iconify();return
        if self.apps:self.apps.window_open=True
        self.show_window()
        if action in ('settings','logs'):self.show_tab('应用设置' if action=='settings' else '查看日志')

    def report_callback_exception(self,exc_type,exc_value,tb):
        logging.error('Desktop callback failed',exc_info=(exc_type,exc_value,tb))
        if hasattr(self,'status'):self.status.configure(text='界面操作失败，请重试；详情已写入应用日志')

    def close(self):
        self.stopping=True;self.withdraw()
        if hasattr(self,'workbench'):self.workbench.save()
        if self.ingress:self.ingress.close()
        if hasattr(self,'workbench'):
            if self.workbench.sub:self.workbench.sub.close()
            self.workbench.pool.shutdown(wait=False,cancel_futures=True)
        try:
            if self.tray:self.tray.close()
            if self.apps:self.apps.close()
            if self.network:self.network.close()
            with self.link_lock:links=set(self.owned_links)
            links.update(route['link'] for route in self.routes.values() if route['link'])
            for link in links:self.close_link(link)
            if self.server:
                self.server.shutdown();self.server.server_close();self.server.bridge.close()
            if self.claude:self.claude.close()
        finally:
            self.destroy();gc.collect()
            if self.gc_enabled:gc.enable()


def main():
    if len(sys.argv)==3 and sys.argv[1]=='--ui-self-check':
        from .verification import packaged_ui_checks
        Path(sys.argv[2]).write_text(json.dumps({'ok':True,'version':VERSION,'checks':packaged_ui_checks()},ensure_ascii=False,indent=2),'utf-8')
        return
    if len(sys.argv)==3 and sys.argv[1]=='--self-check':
        import hashlib
        from . import conversation,deepseek,tailscale_link,native_content,native_features,apps,cdp,temporary_link,embedded_network,codex_plugins,codex_rpc,git_project,live_hub,workspace,document_preview,desktop_workbench,desktop_health
        modules=[conversation,deepseek,tailscale_link,native_content,native_features,apps,cdp,temporary_link,embedded_network,codex_plugins,codex_rpc,git_project,live_hub,workspace,document_preview,desktop_workbench,desktop_health]
        from .verification import packaged_checks
        # A compiled module has no source file; its digest is taken over the bytecode
        # table it was loaded from, which is what the assistant actually runs.
        sources={}
        for module in modules:
            path=Path(getattr(module,'__file__','') or '')
            loaded_from=getattr(getattr(module,'__loader__',None),'archive_path',None)
            readable=path if path.is_file() else Path(loaded_from) if loaded_from and Path(loaded_from).is_file() else None
            sources[module.__name__]=hashlib.sha256(readable.read_bytes()).hexdigest() if readable else path.name or getattr(module,'__name__','')
        result={'ok':True,'version':VERSION,'checks':packaged_checks(),'python':sys.version.split()[0], 'sources':sources}
        Path(sys.argv[2]).write_text(json.dumps(result,indent=2),'utf-8')
        return
    DATA.mkdir(parents=True,exist_ok=True)
    (DATA/'codex').mkdir(exist_ok=True)
    from .single_instance import SingleInstance
    instance=SingleInstance(DATA)
    if instance.existing:
        instance.close();return
    logging.basicConfig(filename=DATA/'application.log',level=logging.INFO,format='%(asctime)s %(levelname)s %(message)s',encoding='utf-8',force=True)
    from bridge.service import Bridge
    logging.info('Starting coding远程 %s; source adapter: %s',VERSION,Bridge.__init__.__globals__['SessionStore'].__module__)
    ctk.set_appearance_mode('light');ctk.set_default_color_theme('blue')
    appearance.apply()

    try:
        desktop=Desktop();instance.watch(lambda:desktop.events.put(('window',True)));desktop.mainloop()
    except Exception:
        logging.exception('Application startup failed')
        from tkinter import messagebox
        messagebox.showerror('coding远程','应用启动失败，请查看 '+str(DATA/'application.log'))
    finally:instance.close()
