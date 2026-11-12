"""Persistent Windows workbench, backed by the same event hub as Android."""
import json
import os
import queue
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import customtkinter as ctk
from tkinter import ttk, filedialog, Menu
from PIL import Image, ImageDraw

from .appearance import BG,CARD,INK,MUTED,ACCENT,SOFT,TINT,LINE,RUN,WAIT,WAIT_BG,DANGER,color
def font(size=13,weight='normal'):return ('Microsoft YaHei UI',size,weight)


def icon(name,size=22):
    image=Image.new('RGBA',(48,48))
    d=ImageDraw.Draw(image);c=color(MUTED)
    if name in ('工作台','概览'):
        for box in ((5,5,21,21),(27,5,43,21),(5,27,21,43),(27,27,43,43)):d.rounded_rectangle(box,3,outline=c,width=3)
    elif name in ('设备连接','手机设备'):
        d.rounded_rectangle((12,3,36,45),5,outline=c,width=3);d.line((20,38,28,38),fill=c,width=3)
    elif name=='应用设置':
        d.ellipse((9,9,39,39),outline=c,width=3);d.ellipse((18,18,30,30),outline=c,width=3)
    elif name=='查看日志':
        for y in (10,23,36):d.line((8,y,40,y),fill=c,width=3)
    elif name=='网络与入口':
        d.line((24,12,11,36,37,36,24,12),fill=c,width=3)
        for x,y in ((24,10),(10,37),(38,37)):d.ellipse((x-6,y-6,x+6,y+6),fill=color(BG),outline=c,width=3)
    elif name=='通知中心':
        d.arc((12,6,36,32),180,360,fill=c,width=3);d.line((12,18,10,34,38,34,36,18),fill=c,width=3);d.arc((20,34,28,42),0,180,fill=c,width=3)
    elif name=='账号与模型':
        d.ellipse((17,5,31,19),outline=c,width=3);d.arc((8,22,40,49),180,360,fill=c,width=3);d.line((8,36,40,36),fill=c,width=3)
    elif name=='更新与维护':
        d.arc((7,7,41,41),30,310,fill=c,width=3);d.line((40,6,40,19,27,17),fill=c,width=3);d.line((24,15,24,32),fill=c,width=3);d.line((18,26,24,32,30,26),fill=c,width=3)
    else:
        d.rounded_rectangle((6,7,42,39),5,outline=c,width=3);d.line((14,16,34,16),fill=c,width=3);d.line((14,26,28,26),fill=c,width=3)
    return ctk.CTkImage(image,size=(size,size))


class Workbench:
    PAGES=('概览','账号与模型','查看日志','应用设置')
    def __init__(self, app, directory):
        self.app,self.directory=app,Path(directory)
        self.pool=ThreadPoolExecutor(max_workers=3,thread_name_prefix='desktop-view')
        self.results=queue.Queue();self.sub=None;self.initialised=False
        self.details={};self.task_snapshot=[];self.app_snapshot={};self.last_tick=0
        self.sidebar=ctk.CTkFrame(app,width=184,fg_color=BG,corner_radius=0)
        self.sidebar.pack(side='left',fill='y');self.sidebar.pack_propagate(False)
        app.content.pack_forget();app.content.pack(side='left',fill='both',expand=True)
        self.brand=ctk.CTkLabel(self.sidebar,text='coding远程',image=app.logo,compound='left',padx=8,anchor='w',font=font(15,'bold'),text_color=INK)
        self.brand.pack(fill='x',padx=12,pady=(16,18))
        self.nav={};self.icons={}
        for name in self.PAGES:
            self.icons[name]=icon(name)
            self.nav[name]=ctk.CTkButton(self.sidebar,text=name,image=self.icons[name],anchor='w',height=40,font=font(),fg_color='transparent',text_color=INK,hover_color=SOFT,command=lambda n=name:app.show_tab(n))
            self.nav[name].pack(side='bottom' if name=='应用设置' else 'top',fill='x',padx=10,pady=3)
            if name not in app.frames:app.frames[name]=ctk.CTkFrame(app.page_host,fg_color='transparent')
        self.banner=ctk.CTkLabel(self.sidebar,text='服务启动中',font=font(11),text_color=MUTED,wraplength=150);self.banner.pack(side='bottom',pady=12)
        self.feedback=self.banner
        self.build_accounts()
        from .desktop_health import LogReader,ConnectionHealth
        self.log_reader=LogReader();self.health=ConnectionHealth();self.health_pending=False;self.health_at=0
        self.build_log_filters();self.style_tree()
        app.bind('<Configure>',self.resize,add='+')
        self.loop()

    def save(self):
        pass

    def select(self,key):
        backend,_,sid=key.partition(':')
        self.run(lambda:self.app.server.dispatch(backend,sid,'open',{}),lambda value,error:self.banner.configure(text=error or '已在桌面客户端打开'))

    def style_tree(self):
        self.sidebar.configure(fg_color=BG)
        for name,button in self.nav.items():
            self.icons[name]=icon(name);button.configure(image=self.icons[name])
        self.account_signature=None
        self.render_accounts(self.account_environment,None)

    def resize(self,event):
        if event.widget is not self.app:return
        narrow=event.width<900
        if narrow==getattr(self,'narrow',None):return
        self.narrow=narrow
        self.sidebar.configure(width=56 if narrow else 184)
        for name,button in self.nav.items():button.configure(text='' if narrow else name,width=36 if narrow else 160)
        self.brand.configure(text='' if narrow else 'coding远程')
        self.banner.configure(wraplength=40 if narrow else 150)

    def loop(self):
        if self.app.stopping:return
        visible=self.app.state()=='normal'
        needs_apps=visible and self.app.current_page=='账号与模型' and self.app.controls_ready
        if needs_apps and not self.sub and not getattr(self,'sub_pending',False):
            self.sub_pending=True
            self.run(lambda:self.app.server.live.subscribe({'apps'}),self.accept_subscription)
        elif not needs_apps and self.sub:self.sub.close();self.sub=None
        if needs_apps and not self.initialised:
            self.initialised=True;self.run(self.app.server.workspace.environments,self.render_accounts)
        for _ in range(20):
            try:done,value,error=self.results.get_nowait()
            except queue.Empty:break
            try:done(value,error)
            except Exception:
                import logging
                logging.exception('Desktop result could not be displayed')
        if self.sub:
            for _ in range(10):
                event=self.sub.read(0)
                if event is None:break
                topic,value=event['topic'],event['data']
                if topic=='apps':self.render_apps(value,None)
        now=time.monotonic()
        if now-self.last_tick>2:
            self.last_tick=now
            if self.app.current_page=='查看日志' and self.follow_log.get():self.refresh_logs()
        self.app.after(200 if visible else 700,self.loop)

    def accept_subscription(self,sub,error):
        self.sub_pending=False
        if error:return
        if self.app.stopping or self.app.current_page!='账号与模型' or self.app.state()!='normal':sub.close()
        else:self.sub=sub

    def button(self,parent,text,command,primary=False):
        return ctk.CTkButton(parent,text=text,font=font(),width=max(56,min(110,len(text)*12+28)),height=34,corner_radius=17,fg_color=ACCENT if primary else SOFT,text_color=('#FFFFFF','#182130') if primary else INK,command=command)

    def title(self,parent,text):
        ctk.CTkLabel(parent,text=text,font=font(20,'bold'),text_color=INK).pack(anchor='w',pady=(0,12))

    def run(self,work,done):
        if not self.app.controls_ready:done(None,'服务尚未就绪，请稍后重试');return
        def task():
            try:self.results.put((done,work(),None))
            except Exception as exc:self.results.put((done,None,str(exc)))
        self.pool.submit(task)

    @staticmethod
    def replace_text(widget,text):widget.delete('1.0','end');widget.insert('1.0',text)

    def build_accounts(self):
        f=self.app.frames['账号与模型'];self.title(f,'账号、模型与远程环境')
        self.account_cards=ctk.CTkScrollableFrame(f,fg_color=BG);self.account_cards.pack(fill='both',expand=True)
        self.account_environment={};self.app_snapshot={};self.render_accounts({},None)
        self.button(f,'读取账号与环境状态',lambda:self.run(lambda:self.app.server.workspace.environments(),self.render_accounts)).pack(anchor='w',pady=8)
        self.api_name=ctk.CTkEntry(f,placeholder_text='连接名称');self.api_name.pack(fill='x',pady=4)
        self.api_url=ctk.CTkEntry(f,placeholder_text='OpenAI 兼容 API 地址，例如 https://服务地址/v1');self.api_url.pack(fill='x',pady=4)
        self.api_key=ctk.CTkEntry(f,placeholder_text='API Key（加密保存）',show='•');self.api_key.pack(fill='x',pady=4)
        self.button(f,'保存 API 连接并读取模型',self.save_account).pack(anchor='w',pady=8)

    def render_apps(self,value,error):
        self.apps_pending=False
        if error:return
        self.app_snapshot={row['id']:row for row in value.get('apps',[])}
        self.render_accounts(self.account_environment,None)

    def render_accounts(self,value,error):
        if error:self.feedback.configure(text=error);return
        if value is not None and 'accounts' in value:self.account_environment=value
        signature=json.dumps([self.app_snapshot,self.account_environment],sort_keys=True,default=str)
        if signature==getattr(self,'account_signature',None):return
        self.account_signature=signature
        for child in self.account_cards.winfo_children():child.destroy()
        for backend,name in [('claude','Claude'),('codex','ChatGPT'),('deepseek','DeepSeek')]:
            card=ctk.CTkFrame(self.account_cards,fg_color=CARD,corner_radius=14,border_width=1,border_color=LINE);card.pack(fill='x',pady=(0,12))
            app=self.app_snapshot.get(backend,{})
            ctk.CTkLabel(card,text=name,font=font(15,'bold'),text_color=INK).pack(anchor='w',padx=16,pady=(12,4))
            status='已连接桌面客户端' if app.get('connected') else '客户端运行中' if app.get('running') else '客户端尚未连接'
            sessions=[v.get('session',{}) for k,v in self.details.items() if k.startswith(backend+':')]
            model=next((v.get('model') for v in sessions if v.get('model')),None)
            ctk.CTkLabel(card,text=status+' · '+('当前模型 '+model if model else '模型由桌面会话提供'),font=font(12),text_color=MUTED).pack(anchor='w',padx=16,pady=(0,12))
        for account in self.account_environment.get('accounts',[]):
            ctk.CTkLabel(self.account_cards,text=account['name']+' · '+str(len(account.get('models',[])))+' 个模型',font=font(13),text_color=INK).pack(anchor='w',padx=16,pady=4)

    def save_account(self):
        value={'name':self.api_name.get(),'url':self.api_url.get(),'key':self.api_key.get()}
        self.run(lambda:self.app.server.workspace.account(value),self.render_accounts)

    def save_preferences(self):
        self.app.config_path.write_text(json.dumps(self.app.settings,ensure_ascii=False),encoding='utf-8')

    def show_health(self,rows,error):
        self.health_pending=False
        if error:return
        good=sum(row.get('quality')!='不可用' for row in rows)
        self.banner.configure(text=('● 手机已绑定' if self.app.pairing.grouped_devices() else '○ 等待手机连接') if good else '连接恢复中')

    def build_log_filters(self):
        self.app.logs.configure(fg_color='#1A1E24',text_color='#D7DCE3',font=('Consolas',12))
        f=self.app.frames["查看日志"];filters=ctk.CTkFrame(f,fg_color="transparent");filters.pack(fill="x",before=self.app.logs,pady=6)
        self.log_query=ctk.CTkEntry(filters,placeholder_text="搜索日志 / 时间");self.log_query.pack(side="left",fill="x",expand=True,padx=3);self.log_query.bind("<KeyRelease>",lambda e:self.refresh_logs())
        self.log_level=ctk.CTkOptionMenu(filters,values=["全部","INFO","WARNING","ERROR"],width=100,command=lambda _:self.refresh_logs());self.log_level.pack(side="left",padx=3)
        self.log_module=ctk.CTkOptionMenu(filters,values=["全部","application.log","claude-keyboard.log","temporary-link.log"],width=140,command=lambda _:self.refresh_logs());self.log_module.pack(side="left",padx=3)
        self.follow_log=ctk.BooleanVar(value=True);ctk.CTkCheckBox(filters,text="跟随",variable=self.follow_log,width=60).pack(side="left",padx=3)
        times=ctk.CTkFrame(f,fg_color='transparent');times.pack(fill='x',before=self.app.logs,pady=3)
        self.log_since=ctk.CTkEntry(times,placeholder_text='开始时间 YYYY-MM-DD HH:MM / HH:MM');self.log_since.pack(side='left',fill='x',expand=True,padx=3)
        self.log_until=ctk.CTkEntry(times,placeholder_text='结束时间（留空不限）');self.log_until.pack(side='left',fill='x',expand=True,padx=3)
        for field in (self.log_since,self.log_until):field.bind('<KeyRelease>',lambda e:self.refresh_logs())
        actions=ctk.CTkFrame(f,fg_color="transparent");actions.pack(fill="x",pady=5)
        self.button(actions,"复制日志",self.copy_logs).pack(side="left",padx=3)
        self.button(actions,"导出日志",self.export_logs).pack(side="left",padx=3)

    def refresh_logs(self):
        if not hasattr(self,'log_query'):return
        if getattr(self,'log_pending',False):self.log_dirty=True;return
        options=(self.log_query.get(),'' if self.log_level.get()=='全部' else self.log_level.get(),'' if self.log_module.get()=='全部' else self.log_module.get())
        since,until=self.log_since.get().strip(),self.log_until.get().strip()
        self.log_pending=True;self.log_dirty=False
        def load():
            try:self.results.put((self.show_logs,self.log_reader.read(self.directory,*options,since=since,until=until),None))
            except Exception as error:self.results.put((self.show_logs,None,str(error)))
        self.pool.submit(load)

    def show_logs(self,text,error):
        self.log_pending=False
        if self.log_dirty:self.refresh_logs();return
        if error or text==getattr(self,'log_text',None):return
        self.log_text=text
        scroll=self.app.logs.yview()[0];self.app.logs.configure(state='normal');self.replace_text(self.app.logs,text);self.app.logs.configure(state='disabled')
        if self.follow_log.get():self.app.logs.see('end')
        else:self.app.logs.yview_moveto(scroll)

    def copy_logs(self):
        self.app.clipboard_clear();self.app.clipboard_append(self.app.logs.get("1.0","end-1c"))

    def export_logs(self):
        path=filedialog.asksaveasfilename(parent=self.app,defaultextension=".txt",initialfile="coding-logs.txt")
        if path:Path(path).write_text(self.app.logs.get("1.0","end-1c"),encoding="utf-8")
