"""Virtualised session rows; only the visible slice is painted."""
import tkinter as tk
import customtkinter as ctk
from pathlib import PureWindowsPath
from datetime import datetime
from .appearance import BG,CARD,INK,MUTED,TINT,LINE,RUN,WAIT,WAIT_BG,DANGER,color

def state(row):
    if row.get('requests') or row.get('attention'):return 'waiting'
    return row.get('status','idle')

def state_color(value):
    return WAIT if value=='waiting' else DANGER if value in ('error','failed') else RUN if value in ('active','running','inProgress','working') else LINE

def state_label(value):
    return '待处理' if value=='waiting' else '出错' if value in ('error','failed') else '执行中' if value in ('active','running','inProgress','working') else ''

class SessionRows(ctk.CTkFrame):
    HEIGHT=60
    def __init__(self,parent,select):
        super().__init__(parent,fg_color=CARD,corner_radius=0)
        self.select=select;self.rows=[];self.items=[];self.current=None;self.locations={};self.animation=None
        self.canvas=tk.Canvas(self,highlightthickness=0,background=color(CARD),takefocus=True)
        self.canvas.pack(side='left',fill='both',expand=True)
        self.bar=ctk.CTkScrollbar(self,width=8,command=self.scroll);self.bar.pack(side='right',fill='y')
        self.canvas.configure(yscrollcommand=self.bar.set)
        self.canvas.bind('<Configure>',lambda _:self.draw())
        self.canvas.bind('<MouseWheel>',self.wheel)
        self.canvas.bind('<Button-1>',self.click)
        self.canvas.bind('<Up>',lambda _:self.move(-1));self.canvas.bind('<Down>',lambda _:self.move(1))
        self.canvas.bind('<Return>',lambda _:self.select(self.current) if self.current else None)
    def update_rows(self,rows,current):
        self.rows=rows;self.current=current;self.items=[];group=None;y=0
        for key,row in rows:
            title='需要你处理' if state(row)=='waiting' else PureWindowsPath(row.get('cwd') or '其他会话').name
            if title!=group:self.items.append((y,30,None,title));y+=30;group=title
            self.items.append((y,self.HEIGHT,key,row));y+=self.HEIGHT
        self.total_height=y;self.draw()
    def wheel(self,event):self.scroll('scroll',-int(event.delta/120)*2,'units');return 'break'
    def scroll(self,*args):self.canvas.yview(*args);self.draw()
    def move(self,direction):
        keys=[key for key,row in self.rows];i=keys.index(self.current) if self.current in keys else 0
        if keys:self.current=keys[max(0,min(len(keys)-1,i+direction))];self.select(self.current);self.draw()
    def click(self,event):
        self.canvas.focus_set();y=self.canvas.canvasy(event.y)
        for top,height,key,row in self.items:
            if key and top<=y<top+height:self.current=key;self.select(key);self.draw();break
    def fit(self,text,width,font):
        from tkinter.font import Font
        measure=Font(font=font)
        if measure.measure(text)<=width:return text
        while text and measure.measure(text+'…')>width:text=text[:-1]
        return text+'…'
    def draw(self):
        c=self.canvas;c.delete('all');c.configure(background=color(CARD),scrollregion=(0,0,max(1,c.winfo_width()),max(1,getattr(self,'total_height',0))))
        width=max(80,c.winfo_width());first=c.canvasy(0)-self.HEIGHT;last=first+c.winfo_height()+2*self.HEIGHT
        for y,height,key,row in self.items:
            if y+height<first or y>last:continue
            if key is None:
                c.create_text(6,y+19,text=row,anchor='w',fill=color(MUTED),font=('Microsoft YaHei UI',11));continue
            value=state(row);pending=value=='waiting';fill=TINT if key==self.current else CARD
            c.create_polygon(10,y+2,width-10,y+2,width,y+12,width,y+48,width-10,y+58,10,y+58,0,y+48,0,y+12,smooth=True,fill=color(fill),outline='')
            c.create_line(5,y+10,5,y+50,fill=color(state_color(value)),width=3)
            status=state_label(value)
            if not status:
                try:status=datetime.fromtimestamp(float(row.get('updatedAt',0))).strftime('%m/%d') if row.get('updatedAt') else ''
                except (ValueError,TypeError,OSError):status=''
            c.create_text(width-10,y+21,text=status,anchor='e',fill=color(state_color(value) if state_label(value) else MUTED),font=('Microsoft YaHei UI',10))
            title=('· ' if row.get('pinned') else '')+row.get('title','未命名会话')
            c.create_text(16,y+21,text=self.fit(title,width-88,('Microsoft YaHei UI',12)),anchor='w',fill=color(INK),font=('Microsoft YaHei UI',12))
            project=PureWindowsPath(row.get('cwd') or '未指定项目').name
            provider={'codex':'ChatGPT','claude':'Claude','deepseek':'DeepSeek'}.get(key.split(':')[0],'')
            c.create_text(16,y+43,text=self.fit(provider+' · '+project,width-26,('Microsoft YaHei UI',10)),anchor='w',fill=color(MUTED),font=('Microsoft YaHei UI',10))
        if not self.rows:c.create_text(16,30,text='没有找到会话',anchor='w',fill=color(MUTED),font=('Microsoft YaHei UI',12))
