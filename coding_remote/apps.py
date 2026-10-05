"""Fixed application lifecycle actions, independent of session RPCs."""
import ctypes
from ctypes import wintypes
import json
import logging
import os
from pathlib import Path
import subprocess
import threading
import time
from . import keyboard

NAMES={'claude':'Claude.exe','codex':'ChatGPT.exe','deepseek':'DeepSeek Harness.exe'}
ALIASES={'claude':{'claude.exe'},'codex':{'chatgpt.exe'},'deepseek':{'deepseek.exe','deepseek harness.exe'}}
# The Codex desktop ships inside an MSIX package whose declared entry point is
# app/ChatGPT.exe, so it must be activated through the shell. The codex.exe found
# under %LOCALAPPDATA% is the assistant's own app-server child process and must
# never be treated as the desktop application.
PACKAGE_APP={'codex':'OpenAI.Codex_2p2nqsd0c76g0!App'}

def processes():
    script="Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('Claude.exe','ChatGPT.exe','deepseek.exe','DeepSeek Harness.exe')} | Select-Object ProcessId,ExecutablePath,CommandLine | ConvertTo-Json -Compress"
    out=subprocess.run(['powershell','-NoProfile','-Command',script],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=12,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    value=json.loads(out.stdout or '[]');return value if isinstance(value,list) else [value]

def main_processes(app):
    rows=[p for p in processes() if Path(p.get('ExecutablePath') or '').name.lower()in ALIASES[app] and '--type=' not in (p.get('CommandLine') or '')]
    if app=='codex':
        # Only the packaged desktop counts; an unrelated ChatGPT.exe on PATH does not.
        rows=[p for p in rows if 'windowsapps' in (p.get('ExecutablePath') or '').lower()]
    return rows

def executable(app):
    running=main_processes(app)
    if running:return Path(running[0]['ExecutablePath'])
    if app=='deepseek':
        for p in [root/name for root in [Path(r'D:\Program Files\deepseek'),Path(os.environ['LOCALAPPDATA'])/'Programs/deepseek',Path(os.environ['LOCALAPPDATA'])/'Programs/DeepSeek Harness'] for name in ['DeepSeek Harness.exe','deepseek.exe']]:
            if p.is_file():return p
    package='Claude' if app=='claude' else 'OpenAI.Codex'
    script=f"Get-AppxPackage -Name '{package}' | Select-Object -ExpandProperty InstallLocation"
    result=subprocess.run(['powershell','-NoProfile','-Command',script],capture_output=True,text=True,timeout=10,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    for location in result.stdout.splitlines():
        for p in [Path(location)/'app'/NAMES[app],Path(location)/NAMES[app]]:
            if p.is_file():return p
    raise ValueError('未找到 '+app+' 安装位置')

def close_process(pid):
    # Exact PID obtained from the allowlisted executable; never an arbitrary command.
    user=ctypes.WinDLL('user32',use_last_error=True)
    callback=ctypes.WINFUNCTYPE(wintypes.BOOL,wintypes.HWND,wintypes.LPARAM)
    user.GetWindowThreadProcessId.argtypes=[wintypes.HWND,ctypes.POINTER(wintypes.DWORD)]
    user.PostMessageW.argtypes=[wintypes.HWND,wintypes.UINT,wintypes.WPARAM,wintypes.LPARAM]
    @callback
    def close_window(hwnd,_):
        owner=wintypes.DWORD();user.GetWindowThreadProcessId(hwnd,ctypes.byref(owner))
        if owner.value==pid:user.PostMessageW(hwnd,0x0010,0,0)
        return True
    user.EnumWindows(close_window,0)
    kernel=ctypes.WinDLL('kernel32',use_last_error=True);kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD]
    kernel.TerminateProcess.argtypes=[wintypes.HANDLE,wintypes.UINT];kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD]
    handle=kernel.OpenProcess(0x100001,False,pid)
    if not handle:return
    try:
        if kernel.WaitForSingleObject(handle,3000)==0:return
        if not kernel.TerminateProcess(handle,0):raise OSError('关闭应用失败')
    finally:kernel.CloseHandle(handle)

class Applications:
    def __init__(self,directory,script,window,desktop=None,on_connected=None,codex=None):
        self.codex=codex
        self.desktop=desktop
        self.on_connected=on_connected or (lambda:None)
        self.directory=Path(directory);self.script=script;self.window=window;self.window_open=True
        self.stopped=threading.Event();self.wake=threading.Event();self.lock=threading.Lock()
        self.app_locks={key:threading.Lock() for key in (*NAMES,'assistant')}
        self.app_locks['claude']=self.lock
        self.error='';self.injected=False;self.attempted_pid=None;self.next_attempt=0;self.last_log=''
        threading.Thread(target=self.monitor,daemon=True).start()
    def snapshot(self):
        rows=processes();result=[]
        for app,name in NAMES.items():
            running=any(Path(p.get('ExecutablePath') or '').name.lower()in ALIASES[app] and '--type=' not in (p.get('CommandLine') or '') and (app!='codex' or 'windowsapps' in (p.get('ExecutablePath') or '').lower()) for p in rows)
            connected=bool(self.desktop and self.desktop.connected) if app=='claude' else bool(self.codex and self.codex.ipc.client_id and self.codex.ipc.socket) if app=='codex' else False
            result.append({'id':app,'running':running,'connected':running and connected})
        result.append({'id':'assistant','running':self.window_open,'background':True})
        return {'apps':result,'claudeError':self.error}
    def execute(self,app,action,confirmed=False):
        if app not in (*NAMES,'assistant') or action not in ('start','stop','restart'):raise ValueError('不支持的应用操作')
        with self.app_locks[app]:
            if app=='assistant':
                if action=='stop' and not confirmed:raise ValueError('请确认关闭助手窗口')
                self.window_open=action!='stop';self.window(self.window_open)
                return {'status':'accepted','background':True}
            current=main_processes(app)
            path=Path(current[0]['ExecutablePath']) if current else None
            if action in ('stop','restart'):
                if not confirmed:raise ValueError('关闭会中断该应用任务，请确认')
                for row in current:close_process(row['ProcessId'])
                deadline=time.monotonic()+8
                while current and time.monotonic()<deadline:
                    time.sleep(.2);current=main_processes(app)
                if current:raise ValueError('应用仍未退出，请在电脑检查')
                if app=='codex' and self.codex:self.codex.ipc.close()
                if app=='claude':self.injected=False
                if action=='stop':return {'status':'accepted'}
            if current:
                if app=='codex':
                    os.startfile('shell:AppsFolder\\'+PACKAGE_APP[app])
                    return self.connect_codex()
                if app=='claude':
                    if keyboard.enable_devtools(path):
                        return {'status':'needs_restart','message':'已启用 Claude 开发者工具，已有实例需要正常重启一次才能生效'}
                    self.attempted_pid=None;self.next_attempt=time.monotonic()+1;self.wake.set()
                return {'status':'accepted','message':'正在连接 Claude…' if app=='claude' else '应用已启动'}
            log_path=self.directory/(app+'-startup.log')
            if app in PACKAGE_APP:
                # A raw Popen of a packaged binary returns WinError 5 because the
                # package only lets the shell start its declared entry point.
                log_path.write_bytes(b'')
                os.startfile('shell:AppsFolder\\'+PACKAGE_APP[app])
                deadline=time.monotonic()+25
                while time.monotonic()<deadline and not main_processes(app):
                    time.sleep(.4)
                if not main_processes(app):
                    raise ValueError('启动后未检测到 '+NAMES[app]+'，请在电脑确认该应用可以正常打开')
            else:
                path=path or executable(app);args=[str(path)]
                if app=='claude':keyboard.enable_devtools(path)
                startup=subprocess.STARTUPINFO();startup.dwFlags|=subprocess.STARTF_USESHOWWINDOW;startup.wShowWindow=1
                with log_path.open('wb') as log:
                    process=subprocess.Popen(args,startupinfo=startup,stdout=log,stderr=log)
                time.sleep(.5);code=process.poll()
                if isinstance(code,int) and code!=0:
                    reason=log_path.read_text('utf-8',errors='replace')
                    raise ValueError('应用启动失败，退出码 '+str(code)+'；请查看 '+app+'-startup.log')
            if app=='claude':
                self.attempted_pid=None;self.next_attempt=time.monotonic()+3;self.wake.set()
            return self.connect_codex() if app=='codex' else {'status':'accepted'}
    def connect_codex(self):
        try:
            if self.codex is None:raise RuntimeError('Codex 桌面连接尚未准备好')
            self.codex.ipc.connect()
            if not self.codex.ipc.client_id or not self.codex.ipc.socket:raise RuntimeError('未收到 Codex 连接回执')
            return {'status':'accepted','connected':True,'message':'Codex 已打开，桌面连接已建立'}
        except Exception as exc:
            return {'status':'accepted','connected':False,'message':'Codex 已打开，但桌面未连接：'+str(exc)[:200]}
    def connection_ready(self,pid=None):
        first=not self.injected
        self.injected=True;self.error='';self.attempted_pid=None
        if not first:return
        logging.info('Claude keyboard bridge connected')
        try:
            current=main_processes('claude') if pid is None else []
            pid=pid if pid is not None else (current[0]['ProcessId'] if current else None)
            if pid is not None:keyboard.close_devtools(pid,self.directory)
            self.on_connected()
            logging.info('Claude connection ready; DevTools closed; assistant minimize requested')
        except Exception as exc:
            # UI cleanup must never invalidate an already authenticated bridge.
            self.error='Claude 已连接，但窗口收起失败：'+str(exc)[:200]
            logging.warning(self.error)

    def inject(self):
        if self.desktop and self.desktop.connected:
            if not self.injected:self.connection_ready()
            return
        current=main_processes('claude')
        self.injected=False
        if not current:
            self.attempted_pid=None;self.error='Claude 未启动，点击页面标题选择启动'
            if self.desktop:self.desktop.disconnected(self.error)
            return
        pid=current[0]['ProcessId']
        # One automatic attempt per failed process. Explicit Start retries without
        # restarting Claude; an established bridge reload gets one new attempt.
        if self.attempted_pid==pid or time.monotonic()<self.next_attempt:return
        self.attempted_pid=pid;self.error='正在通过键盘连接 Claude…'
        if self.desktop:self.desktop.disconnected(self.error)
        if keyboard.enable_devtools(current[0]['ExecutablePath']):
            raise RuntimeError('已启用开发者工具；当前 Claude 在设置更新前已运行，需要通过手机标题选择重启一次')
        keyboard.inject(pid,self.script,self.directory)
        deadline=time.monotonic()+15
        while not self.stopped.wait(.25) and time.monotonic()<deadline:
            if self.desktop and self.desktop.connected:
                self.connection_ready(pid);return
        raise RuntimeError('未收到 Claude 连接回执，请检查 Console 中的错误或项目目录信任；点击标题选择启动可重试')
    def monitor(self):
        while not self.stopped.is_set():
            self.wake.wait(4);self.wake.clear()
            if self.stopped.is_set():break
            try:
                with self.lock:self.inject()
            except Exception as exc:
                self.injected=False;self.error=str(exc)[:300]
                if self.desktop:self.desktop.disconnected('Claude 自动连接失败：'+self.error)
                if self.error!=self.last_log:logging.warning('Claude keyboard connection: %s',self.error);self.last_log=self.error
    def close(self):self.stopped.set();self.wake.set()
