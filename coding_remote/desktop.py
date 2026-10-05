import json
import logging
import os
import queue
import sys
import threading
import time
from pathlib import Path
from tkinter import filedialog

import customtkinter as ctk
from PIL import Image, ImageDraw
from qrcodegen import QrCode

from bridge.service import Bridge
from .codex_plugins import PluginBridgeMixin

class MobileBridge(PluginBridgeMixin, Bridge):
    pass

from .tailscale_link import TailscaleLink
from .embedded_network import EmbeddedNetwork,EmbeddedLink
from claude_bridge.packaged import Controller
from .api import NativeServer
from .pairing import Pairing
from .temporary_link import TemporaryLink
from .apps import Applications
from .tray import Tray
from tkinter import messagebox

ASSETS = Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parents[1]))
DATA = Path(os.environ.get('LOCALAPPDATA', Path.home()))/'CodingRemoteAssistant'


class Desktop(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title('coding远程')
        self.geometry('780x700')
        self.minsize(700,650)
        self.configure(fg_color='#FAFAF9')
        self.iconbitmap(str(ASSETS/'assets/codex-mobile.ico'))
        self.events=queue.Queue(); self.stopping=False;self.network_check_at=0;self.network_checking=False;self.disabled_routes=set();self.route_generation={'permanent':0,'temporary':0}
        self.mode='temporary';self.routes={m:{'link':None,'origin':None,'connecting':False,'error':''} for m in ('temporary','permanent')}
        self.config_path=DATA/'settings.json'
        try:self.settings=json.loads(self.config_path.read_text('utf-8'))
        except FileNotFoundError:self.settings={'auto_connect':True}
        if self.settings.get('connection_version')!=2:
            self.settings.update(connection_version=2,auto_connect=True)
            self.config_path.write_text(json.dumps(self.settings),encoding='utf-8')
        self.pairing=Pairing(DATA)
        workspace=Path(self.settings.get('claude_workspace',DATA/'bridge')).resolve()
        self.claude=Controller(workspace/'.coding-remote',reconnect=True,transport='file')
        self.bridge=MobileBridge(Path(os.environ.get('CODEX_HOME',Path.home()/'.codex')),DATA/'codex')
        self.server=NativeServer(self.bridge,self.claude,self.pairing,DATA)
        self.network=EmbeddedNetwork(ASSETS,DATA,self.server.server_port,self.pairing.computer_id,self.server.allow_origin)
        self.ingress_lock=threading.Lock();self.ingress_checked=0
        self.server.refresh_ingress=self.refresh_ingress
        self.apps=Applications(DATA,self.claude.console_script,lambda visible:self.events.put(('window',visible)),self.claude.desktop,on_connected=lambda:self.events.put(('claude_connected',None)),codex=self.bridge)
        self.server.apps=self.apps
        self.server.queue.start()
        try:self.server.deepseek.install()
        except Exception:logging.exception('DeepSeek adapter setup failed')
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        self.protocol('WM_DELETE_WINDOW',self.hide_window)
        self.tray=Tray(ASSETS,lambda action:self.events.put(('tray',action)))
        self.bind('<Unmap>',self.on_minimize,add='+')
        heading=ctk.CTkFrame(self,fg_color='transparent');heading.pack(fill='x',padx=32,pady=(26,4))
        self.logo=ctk.CTkImage(Image.open(ASSETS/'assets/codex-mobile.png'),size=(34,34))
        ctk.CTkLabel(heading,text='',image=self.logo).pack(side='left',padx=(0,10))
        self.page_title=ctk.CTkLabel(heading,text='coding远程',font=('Microsoft YaHei UI',24,'bold'),text_color='#202020');self.page_title.pack(side='left')
        self.settings_button=ctk.CTkButton(heading,text='⚙',font=('Segoe UI Symbol',23),width=40,height=40,fg_color='transparent',hover_color='#EAEDE8',text_color='#414740',command=lambda:self.show_tab('应用设置'));self.settings_button.pack(side='right',padx=(8,0))
        self.logs_button=ctk.CTkButton(heading,text='≡',font=('Segoe UI Symbol',24),width=40,height=40,fg_color='transparent',hover_color='#EAEDE8',text_color='#414740',command=lambda:self.show_tab('查看日志'));self.logs_button.pack(side='right')
        self.back_button=ctk.CTkButton(heading,text='‹',font=('Segoe UI',28),width=40,height=40,fg_color='transparent',hover_color='#EAEDE8',text_color='#414740',command=lambda:self.show_tab('设备连接'))
        self.content=ctk.CTkFrame(self,fg_color='white',corner_radius=18,border_width=0)
        self.content.pack(fill='both',expand=True,padx=32,pady=(20,24))
        self.page_host=ctk.CTkFrame(self.content,fg_color='transparent');self.page_host.pack(fill='both',expand=True,padx=24,pady=22)
        self.frames={};self.current_page='';self.page_animation=None;self.page_epoch=0
        for name in ['设备连接','查看日志','应用设置']:
            self.frames[name]=(ctk.CTkScrollableFrame if name=='应用设置' else ctk.CTkFrame)(self.page_host,fg_color='transparent')
        self.build_access();self.build_logs();self.build_settings();self.show_tab('设备连接')
        self.after(300,self.tick)
        self.after(500,lambda:self.start_route('temporary'))
        self.after(700,lambda:self.start_route('permanent'))

    def show_tab(self,name):
        if self.page_animation:self.after_cancel(self.page_animation);self.page_animation=None
        self.page_epoch+=1;epoch=self.page_epoch;self.current_page=name
        for frame in self.frames.values():frame.place_forget()
        frame=self.frames[name];frame.place(x=0,y=6,relwidth=1,relheight=1)
        self.page_title.configure(text='coding远程' if name=='设备连接' else '设置' if name=='应用设置' else '日志')
        if name=='设备连接':self.back_button.pack_forget()
        else:self.back_button.pack(side='right',padx=8)
        if name=='查看日志':self.refresh_logs()
        if name=='应用设置':self.refresh_devices()
        start=time.monotonic()
        def animate():
            if epoch!=self.page_epoch:return
            progress=min(1,(time.monotonic()-start)/.16);frame.place(x=0,y=round(6*(1-progress)**3),relwidth=1,relheight=1)
            if progress<1:self.page_animation=self.after(16,animate)
            else:self.page_animation=None
        animate()

    def build_access(self):
        f=self.frames['设备连接']
        ctk.CTkLabel(f,text='连接手机设备',font=('Microsoft YaHei UI',21,'bold'),text_color='#202020').pack(pady=(5,3))
        self.connection_modes=ctk.CTkSegmentedButton(f,values=['临时扫码连接','Tailscale 永久绑定'],command=self.change_mode)
        self.connection_modes.pack(pady=5);self.connection_modes.set('临时扫码连接')
        self.status=ctk.CTkLabel(f,text='正在建立临时连接…',text_color='#777777',wraplength=710)
        self.status.pack(pady=(0,12))
        self.qr_holder=ctk.CTkFrame(f,fg_color='transparent');self.qr_holder.pack()
        self.qr=ctk.CTkLabel(self.qr_holder,text='等待连接',width=270,height=270,fg_color='#F7F7F8',corner_radius=12,text_color='#999999')
        self.qr.pack()
        self.hint=ctk.CTkLabel(f,text='二维码 5 分钟有效，每次仅可配对一台手机',text_color='#777777')
        self.hint.pack(pady=12)
        actions=ctk.CTkFrame(f,fg_color='transparent');actions.pack(pady=4)
        self.connect_button=ctk.CTkButton(actions,text='开启设备连接',command=self.connect,width=165,height=42)
        self.connect_button.pack(side='left',padx=6)
        self.refresh_button=ctk.CTkButton(actions,text='刷新二维码',command=self.refresh_qr,width=140,height=42,state='normal',fg_color='#F3F3F3',text_color='#333333')
        self.refresh_button.pack(side='left',padx=6)
        self.state_label=ctk.CTkLabel(f,text='尚未连接手机',text_color='#777777');self.state_label.pack(pady=(12,0))
    def control_app(self,key,action):
        if action not in ('启动','关闭','重启'):return
        if action!='启动' and not messagebox.askyesno(action+' '+key,'这会中断当前任务，是否继续？',parent=self):return
        def work():
            try:
                result=self.apps.execute(key,{'启动':'start','关闭':'stop','重启':'restart'}[action],action!='启动')
                self.events.put(('claude_tools',result.get('message','已提交')))
            except Exception:logging.exception('Application control failed');self.events.put(('claude_tools','操作失败，请查看日志'))
        threading.Thread(target=work,daemon=True).start()


    def build_logs(self):
        f=self.frames['查看日志']
        ctk.CTkLabel(f,text='应用日志',font=('Microsoft YaHei UI',21,'bold'),text_color='#202020').pack(anchor='w')
        self.logs=ctk.CTkTextbox(f,font=('Consolas',12),fg_color='#F7F7F8',text_color='#333333')
        self.logs.pack(fill='both',expand=True,pady=15)
        actions=ctk.CTkFrame(f,fg_color='transparent');actions.pack(fill='x')
        ctk.CTkButton(actions,text='重新连接 Claude',command=self.open_claude).pack(side='left')
        ctk.CTkButton(actions,text='刷新日志',command=self.refresh_logs).pack(side='right')

    def refresh_logs(self):
        chunks=[]
        for name in ['application.log','claude-keyboard.log','temporary-link.log']:
            p=DATA/name
            if p.exists():
                contents=p.read_text('utf-8',errors='replace')
                if name=='application.log' and 'Starting coding远程' in contents:
                    contents='Starting coding远程'+contents.rsplit('Starting coding远程',1)[1]
                chunks.append(name+'\n'+contents[-18000:])
        self.logs.configure(state='normal');self.logs.delete('1.0','end');self.logs.insert('1.0','\n\n'.join(chunks) or '暂无日志');self.logs.configure(state='disabled')

    def build_settings(self):
        f=self.frames['应用设置']
        def section(title):
            ctk.CTkLabel(f,text=title,font=('Microsoft YaHei UI',13),text_color='#888888').pack(anchor='w',pady=(20,8))
        def row(title):
            frame=ctk.CTkFrame(f,fg_color='transparent',height=48);frame.pack(fill='x',pady=5)
            ctk.CTkLabel(frame,text=title,font=('Microsoft YaHei UI',15),text_color='#242424').pack(side='left',padx=(0,20))
            return frame
        section('通用')
        self.auto=ctk.BooleanVar(value=self.settings.get('auto_connect',True))
        ctk.CTkSwitch(row('断线自动重连'),text='',width=50,variable=self.auto,command=self.save_settings).pack(side='right')
        self.minimize_tray=ctk.BooleanVar(value=self.settings.get('minimize_to_tray',False))
        ctk.CTkSwitch(row('最小化到托盘'),text='',width=50,variable=self.minimize_tray,command=self.save_settings).pack(side='right')
        section('设备网络')
        ctk.CTkButton(row('内置网络账户'),text='连接 / 查看',width=120,command=self.login_network).pack(side='right')
        self.network_status=ctk.CTkLabel(f,text='',text_color='#666666',wraplength=600);self.network_status.pack(anchor='w')
        self.external_network=ctk.BooleanVar(value=self.settings.get('external_tailscale',False))
        ctk.CTkSwitch(row('使用已安装的 Tailscale'),text='',width=50,variable=self.external_network,command=self.save_settings).pack(side='right')
        self.copy_status=ctk.CTkLabel(f,text='',text_color='#666666',wraplength=600)
        self.copy_status.pack(anchor='w')
        section('已绑定设备')
        self.device_frame=ctk.CTkFrame(f,fg_color='transparent');self.device_frame.pack(fill='x')
        section('应用')
        ctk.CTkButton(row('coding远程 · 2.0.11'),text='退出后台服务',width=120,command=self.close).pack(side='right')

    def login_network(self):
        if getattr(self,'network_login_active',False):return
        self.network_login_active=True
        cancel=threading.Event();self.network_login_cancel=cancel
        self.network_status.configure(text='正在连接设备网络…')
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
        if messagebox.askyesno('重启 Claude','重启会中断 Claude 的当前任务。确定继续？',parent=self):self.run_claude('restart')

    def run_claude(self,action):
        self.copy_status.configure(text='正在连接 Claude…')
        def work():
            try:
                result=self.apps.execute('claude',action,action=='restart')
                if result.get('status')=='needs_restart':self.events.put(('claude_restart',None))
                self.events.put(('claude_tools',result.get('message','Claude 已启动，正在自动连接…')))
            except Exception:
                logging.exception('Claude keyboard startup failed')
                self.events.put(('claude_tools','Claude 启动失败，请查看日志'))
        threading.Thread(target=work,daemon=True).start()

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
        for w in self.device_frame.winfo_children():w.destroy()
        devices=self.pairing.grouped_devices()
        if not devices:ctk.CTkLabel(self.device_frame,text='暂无已配对设备').pack(anchor='w')
        for device in devices:
            row=ctk.CTkFrame(self.device_frame,fg_color='#F2F5F0',corner_radius=12,height=54);row.pack(fill='x',pady=4);row.pack_propagate(False)
            ctk.CTkLabel(row,text=device['name']+' · '+('在线' if time.time()-device.get('lastSeen',0)<90 else '等待连接')).pack(side='left',padx=14)
            ctk.CTkButton(row,text='解除配对',width=90,command=lambda d=device:self.revoke(d['credentialIds'])).pack(side='right',padx=10)

    def revoke(self,device_id):
        for key in device_id if isinstance(device_id,list) else [device_id]:self.pairing.revoke(key)
        self.refresh_devices()

    def change_mode(self,label):
        self.mode='permanent' if label=='Tailscale 永久绑定' else 'temporary'
        self.render_route()
        self.refresh_qr()

    def connect(self):
        route=self.routes[self.mode]
        if route['origin']:self.disconnect()
        else:self.disabled_routes.discard(self.mode);self.start_route(self.mode)

    def start_route(self,mode):
        route=self.routes[mode]
        if self.stopping or mode in self.disabled_routes or route['connecting'] or route['origin']:return
        route['connecting']=True;route['error']=''
        generation=self.route_generation[mode]
        if mode==self.mode:self.render_route()
        def work():
            link=None
            try:
                link=(TailscaleLink(self.server.server_port,DATA,self.server.allow_origin) if self.settings.get('external_tailscale',False) else EmbeddedLink(self.network,self.server.allow_origin)) if mode=='permanent' else TemporaryLink(self.server.server_port,DATA,ASSETS,self.server.allow_origin)
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

    @staticmethod
    def close_link(link):
        try:link.close()
        except Exception:logging.exception('Could not close owned connection')

    def refresh_ingress(self):
        if not self.check_embedded_network():return
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
        self.status.configure(text=route['error'][:260] or ('Tailscale 永久绑定已开启' if self.mode=='permanent' else '临时扫码连接已开启') if route['origin'] else route['error'][:260] or ('正在建立连接…' if route['connecting'] else '此连接已关闭'))
        self.hint.configure(text='绑定后自动重连；手机和电脑需登录同一 Tailscale 网络。' if self.mode=='permanent' else '临时连接无需 Tailscale；有效期 24 小时，关闭助手或此入口后失效。')

    def disconnect(self):
        if self.mode=='permanent' and getattr(self,'network_login_cancel',None):self.network_login_cancel.set()
        self.disabled_routes.add(self.mode)
        self.route_generation[self.mode]+=1
        route=self.routes[self.mode];origin=route['origin'];link=route['link']
        route.update(origin=None,link=None,error='')
        self.pairing.clear_mode(self.mode)
        if self.mode=='temporary' and origin:self.pairing.close_temporary(origin)
        if link:threading.Thread(target=self.close_link,args=(link,),daemon=True).start()
        if self.mode=='permanent' and not self.settings.get('external_tailscale',False):threading.Thread(target=self.network.close,daemon=True).start()
        self.clear_qr('连接已关闭')
        self.render_route()

    def clear_qr(self, text):
        # CTkLabel retains a Tcl image name when image=None; destroy it before
        # releasing its PhotoImage, including disconnected/unavailable routes.
        self.qr.destroy()
        self.qr_image=None
        self.qr=ctk.CTkLabel(self.qr_holder,text=text,width=270,height=270,fg_color='#F7F7F8',corner_radius=12,text_color='#999999')
        self.qr.pack()

    def refresh_qr(self):
        self.disabled_routes.discard(self.mode)
        route=self.routes[self.mode]
        if not route['origin']:
            self.clear_qr('等待连接')
            self.start_route(self.mode)
            return
        payload=self.pairing.issue(route['origin'],self.mode)
        code=QrCode.encode_text(payload,QrCode.Ecc.MEDIUM)
        size=code.get_size();scale=max(3,270//(size+8));image=Image.new('RGB',((size+8)*scale,)*2,'white');draw=ImageDraw.Draw(image)
        for y in range(size):
            for x in range(size):
                if code.get_module(x,y):draw.rectangle(((x+4)*scale,(y+4)*scale,(x+5)*scale-1,(y+5)*scale-1),fill='#171717')
        self.qr_image=ctk.CTkImage(light_image=image,dark_image=image,size=(270,270))
        self.qr.destroy()
        self.qr=ctk.CTkLabel(self.qr_holder,image=self.qr_image,text='',width=270,height=270,fg_color='white')
        self.qr.pack()
        logging.info('Pairing QR refreshed successfully')
        self.status.configure(text='设备连接已开启，请使用手机 App 扫码')

    def tick(self):
        if self.stopping:return
        if self.check_embedded_network() and not self.network_checking and time.monotonic()-self.network_check_at>3:
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
            if kind=='network_health':
                self.network_checking=False
                generation,state=value
                if generation!=self.route_generation['permanent'] or 'permanent' in self.disabled_routes:continue
                value=state.get('origin','') if state.get('state')=='Running' else ''
                route=self.routes['permanent']
                # Let the preferred external VPN finish its initial attempt.
                # Its absence must not strand a saved embedded-device binding.
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
            elif kind=='network_progress':self.network_status.configure(text=value)
            elif kind=='network_login_done':self.network_login_active=False
            elif kind=='network_selected':
                if value is not None and value.is_set():continue
                self.external_network.set(False);self.save_settings()
                self.disabled_routes.discard('permanent');self.start_route('permanent')
            elif kind=='window':
                self.apps.window_open=value
                if value:self.deiconify();self.lift()
                else:self.withdraw()
            elif kind=='claude_tools':self.copy_status.configure(text=value)
            elif kind=='claude_connected':
                self.copy_status.configure(text='Claude 已连接')
                if self.state()!='withdrawn':self.iconify()
            elif kind=='claude_restart':self.restart_claude()
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
        for mode,connection in self.routes.items():
            link=connection['link']
            if mode=='temporary' and link and link.process and link.process.poll() is not None:
                self.pairing.close_temporary(connection['origin'])
                connection.update(link=None,origin=None,error='临时连接已中断，正在重新建立')
                if mode==self.mode:self.clear_qr('连接已中断');self.render_route()
                if self.settings.get('auto_connect',True):self.start_route(mode)
        self.state_label.configure(text='已绑定 '+str(len(self.pairing.grouped_devices()))+' 台设备')
        route=self.routes[self.mode]
        if route['origin']:
            with self.pairing.lock:pending=next((v for v in self.pairing.codes.values() if v['mode']==self.mode),None)
            if pending and pending['expires']<time.time():self.hint.configure(text='二维码已过期，请点击刷新二维码')
            elif not pending and getattr(self,'qr_image',None) is not None:
                self.clear_qr('已连接 · 刷新二维码连接其他手机')
        self.after(700,self.tick)

    def hide_window(self):
        self.apps.window_open=False
        if self.tray.ready:self.withdraw()
        else:self.iconify()

    def on_minimize(self,event):
        if event.widget is self and not self.stopping and self.state()=='iconic' and self.settings.get('minimize_to_tray',False) and self.tray.ready:
            self.after_idle(self.withdraw)

    def tray_action(self,action):
        if action=='quit':self.close();return
        if action=='minimize':self.iconify();return
        self.apps.window_open=True;self.deiconify();self.lift()
        if action in ('settings','logs'):self.show_tab('应用设置' if action=='settings' else '查看日志')

    def report_callback_exception(self,exc_type,exc_value,tb):
        logging.error('Desktop callback failed',exc_info=(exc_type,exc_value,tb))
        if hasattr(self,'status'):self.status.configure(text='界面操作失败，请重试；详情已写入应用日志')

    def close(self):
        self.stopping=True;self.withdraw()
        try:
            self.tray.close()
            self.apps.close();self.network.close()
            for route in self.routes.values():
                if route['link']:self.close_link(route['link'])
            self.server.shutdown();self.server.server_close();self.bridge.close();self.claude.close()
        finally:self.destroy()


def main():
    if len(sys.argv)==3 and sys.argv[1]=='--self-check':
        import hashlib
        from . import conversation,deepseek,tailscale_link,native_content,native_features,apps,cdp,temporary_link,embedded_network,codex_plugins,codex_rpc,git_project
        modules=[conversation,deepseek,tailscale_link,native_content,native_features,apps,cdp,temporary_link,embedded_network,codex_plugins,codex_rpc,git_project]
        from .verification import packaged_checks
        result={'ok':True,'version':'2.0.11','checks':packaged_checks(),'python':sys.version.split()[0], 'sources':{m.__name__:hashlib.sha256(Path(m.__file__).read_bytes()).hexdigest() for m in modules}}
        Path(sys.argv[2]).write_text(json.dumps(result,indent=2),'utf-8')
        return
    DATA.mkdir(parents=True,exist_ok=True)
    (DATA/'codex').mkdir(exist_ok=True)
    from .single_instance import SingleInstance
    instance=SingleInstance(DATA)
    if instance.existing:
        instance.close();return
    logging.basicConfig(filename=DATA/'application.log',level=logging.INFO,format='%(asctime)s %(levelname)s %(message)s',encoding='utf-8',force=True)
    logging.info('Starting coding远程 2.0.11; source adapter: %s',Bridge.__init__.__globals__['SessionStore'].__module__)
    ctk.set_appearance_mode('light');ctk.set_default_color_theme('blue')
    for widget in ['CTkButton','CTkSwitch']:
        ctk.ThemeManager.theme[widget].update(fg_color=['#242424','#242424'],hover_color=['#424242','#424242'])
    ctk.ThemeManager.theme['CTkSwitch']['progress_color']=['#242424','#242424']
    try:
        desktop=Desktop();instance.watch(lambda:desktop.events.put(('window',True)));desktop.mainloop()
    except Exception:
        logging.exception('Application startup failed')
        from tkinter import messagebox
        messagebox.showerror('coding远程','应用启动失败，请查看 '+str(DATA/'application.log'))
    finally:instance.close()
