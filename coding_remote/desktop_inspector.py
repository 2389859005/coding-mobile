"""The right-hand tools stay separate from the conversation and its draft."""
import customtkinter as ctk
from .appearance import BG,CARD,SOFT,TINT,INK,MUTED,LINE,ACCENT

class InspectorTabs(ctk.CTkFrame):
    def __init__(self,parent,command):
        super().__init__(parent,fg_color='transparent',corner_radius=0)
        self.value='文件';self.buttons={};self.lines={}
        for index,label in enumerate(['文件','Git','终端','智能体','侧聊']):
            self.grid_columnconfigure(index,weight=1)
            button=ctk.CTkButton(self,text=label,width=42,height=34,corner_radius=0,font=('Microsoft YaHei UI',12),fg_color='transparent',hover_color=SOFT,text_color=MUTED,command=lambda v=label:command(v));button.grid(row=0,column=index,sticky='ew');self.buttons[label]=button
            line=ctk.CTkFrame(self,height=2,corner_radius=0,fg_color=LINE);line.grid(row=1,column=index,sticky='ew');self.lines[label]=line
        self.set('文件')
    def get(self):return self.value
    def set(self,value):
        self.value=value
        for label,button in self.buttons.items():
            button.configure(text_color=INK if value==label else MUTED)
            self.lines[label].configure(fg_color=ACCENT if value==label else LINE)

def build(work,parent):
    f=ctk.CTkFrame(parent,width=300,fg_color=CARD,corner_radius=0,border_width=1,border_color=LINE);work.inspector=f
    f.grid(row=0,column=2,sticky='nsew');f.grid_propagate(False);f.grid_columnconfigure(0,weight=1);f.grid_rowconfigure(2,weight=1)
    head=ctk.CTkFrame(f,fg_color='transparent');head.grid(row=0,column=0,sticky='ew',padx=8,pady=4)
    work.tabs=InspectorTabs(head,work.switch_panel);work.tabs.pack(fill='x',expand=True)
    work.path=ctk.CTkEntry(f,placeholder_text='项目内文件路径');work.path.grid(row=1,column=0,sticky='ew',padx=8,pady=4)
    work.inspector_body=ctk.CTkTextbox(f,wrap='word',font=('Consolas',12),fg_color=CARD,text_color=INK);work.inspector_body.grid(row=2,column=0,sticky='nsew',padx=8,pady=4);work.inspector_body.bind('<Double-Button-1>',work.body_open)
    tools=ctk.CTkFrame(f,fg_color='transparent');tools.grid(row=3,column=0,sticky='ew',padx=8,pady=4)
    work.read_button=work.button(tools,'读取',work.run_panel);work.open_button=work.button(tools,'打开文件',work.open_file)
    work.side_selector=ctk.CTkOptionMenu(f,values=['选择侧聊'],command=work.choose_side,fg_color=SOFT,text_color=INK,button_color=SOFT)
    work.terminal_controls=ctk.CTkFrame(f,fg_color='transparent')
    work.terminal_environment=ctk.CTkOptionMenu(work.terminal_controls,values=['local','wsl','ssh'],width=90,fg_color=SOFT,button_color=SOFT,text_color=INK);work.terminal_environment.pack(side='left')
    work.terminal_host=ctk.CTkEntry(work.terminal_controls,placeholder_text='SSH user@host');work.terminal_host.pack(side='left',fill='x',expand=True)
    work.git_controls=ctk.CTkFrame(f,fg_color='transparent')
    for label,operation in [('暂存','stage'),('取消暂存','unstage')]:work.button(work.git_controls,label,lambda op=operation:work.git_action(op)).pack(side='left',padx=2)
    work.git_menu=ctk.CTkOptionMenu(work.git_controls,width=90,values=['同步…','获取','拉取','推送','新建分支','切换分支'],fg_color=SOFT,button_color=SOFT,text_color=INK,command=lambda label:work.git_menu_action(label));work.git_menu.pack(side='left',padx=2)
    work.commit_row=ctk.CTkFrame(f,fg_color='transparent');work.commit_message=ctk.CTkEntry(work.commit_row,placeholder_text='提交说明');work.commit_message.pack(side='left',fill='x',expand=True);work.button(work.commit_row,'提交',lambda:work.git_action('commit')).pack(side='right')
    work.side_composer=ctk.CTkEntry(f,placeholder_text='向侧聊补充消息');work.side_composer.bind('<Return>',lambda _:work.send_side())
    work.context_panel=ctk.CTkFrame(f,fg_color='transparent');work.context_panel.grid(row=8,column=0,sticky='ew',padx=8,pady=8)
    work.inspector_rail=ctk.CTkFrame(parent,width=40,fg_color=BG)
    for label in ['文件','Git','终端','智能体','侧聊']:ctk.CTkButton(work.inspector_rail,text=label[:1],width=36,height=40,fg_color='transparent',text_color=MUTED,command=lambda page=label:work.show_inspector(page)).pack(pady=4)
