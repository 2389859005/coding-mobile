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
# Short-lived process-scan cache; see processes() for why it exists.
_process_lock=threading.Lock()
_process_cache=[None,0.0]
# One snapshot at a time: without it, concurrent callers could each expire the
# same entry and start their own PowerShell scan for the same answer.
_snapshot_lock=threading.Lock()
ALIASES={'claude':{'claude.exe'},'codex':{'chatgpt.exe'},'deepseek':{'deepseek.exe','deepseek harness.exe'}}
# The Codex desktop ships inside an MSIX package whose declared entry point is
# app/ChatGPT.exe, so it must be activated through the shell. The codex.exe found
# under %LOCALAPPDATA% is the assistant's own app-server child process and must
# never be treated as the desktop application.
PACKAGE_NAME={'codex':'OpenAI.Codex'}
# Fallback only. The live application id is resolved from the installed package:
# a pinned publisher hash or application id silently stops activating the client
# after a repackaging, and the phone then reports success while nothing opens.
PACKAGE_APP={'codex':'OpenAI.Codex_2p2nqsd0c76g0!App'}
_package_lock=threading.Lock()
_package_cache={}
# A missing or newly (un)installed package is a slow-changing fact. Caching only
# positive results meant every snapshot of a client that is not installed paid a
# fresh PowerShell 5.1 start-up, which is the expensive part of the call.
PACKAGE_MISS_TTL=300.0
_package_miss={}

def package_aumid(app):
    """Shell activation id ('PackageFamilyName!ApplicationId') for a packaged client."""
    name=PACKAGE_NAME.get(app)
    if not name:raise ValueError('不支持的应用')
    with _package_lock:
        cached=_package_cache.get(app)
        if cached:return cached
        if app in _package_miss and time.monotonic()-_package_miss[app]<PACKAGE_MISS_TTL:return PACKAGE_APP.get(app,'')
    script=("$p=Get-AppxPackage -Name '"+name+"' | Sort-Object -Property Version -Descending | Select-Object -First 1;"
            "if($p){$a=($p | Get-AppxPackageManifest).Package.Applications.Application;"
            "if($a -isnot [array]){$a=@($a)};$id=$a[0].Id;if($id){$p.PackageFamilyName+'!'+$id}}")
    value=''
    try:
        result=subprocess.run(['powershell','-NoProfile','-Command',script],capture_output=True,
                              text=True,encoding='utf-8',errors='replace',timeout=15,
                              creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        candidate=next((line.strip() for line in (result.stdout or '').splitlines() if '!' in line and ' ' not in line.strip()),'')
        if candidate:value=candidate
    except (OSError,subprocess.SubprocessError):value=''
    with _package_lock:
        if value:_package_cache[app]=value
        else:_package_miss[app]=time.monotonic()
    return value or PACKAGE_APP.get(app,'')

def processes(fresh=False):
    """Every allowlisted client process.

    The scan is a PowerShell Get-CimInstance call costing a few hundred
    milliseconds, and a single UI refresh asks for it several times, so results are
    shared for a short window. Lifecycle code that polls for a process actually
    exiting must pass fresh=True, or it would keep seeing the cached pre-stop view.
    """
    with _process_lock:
        if not fresh and _process_cache[0] is not None and time.monotonic()-_process_cache[1] < 2:
            return _process_cache[0]
    script="Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('Claude.exe','ChatGPT.exe','deepseek.exe','DeepSeek Harness.exe')} | Select-Object ProcessId,ExecutablePath,CommandLine | ConvertTo-Json -Compress"
    out=subprocess.run(['powershell','-NoProfile','-Command',script],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=12,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    value=json.loads(out.stdout or '[]')
    rows=value if isinstance(value,list) else [value]
    # An empty result is a fact worth caching too: a repeated poll while no client
    # runs would otherwise start a PowerShell process on every refresh.
    with _process_lock:
        _process_cache[0]=rows
        _process_cache[1]=time.monotonic()
    return rows

def desktop_rows(rows,app):
    rows=[p for p in rows if Path(p.get('ExecutablePath') or '').name.lower()in ALIASES[app]]
    if app=='codex':
        # Only the packaged desktop counts; an unrelated ChatGPT.exe on PATH does not.
        rows=[p for p in rows if 'windowsapps' in (p.get('ExecutablePath') or '').lower()]
    return rows

def app_processes(app,fresh=False):
    """Allowlisted processes of the client, including its renderer/helper children."""
    try:rows=processes(fresh)
    except Exception:logging.debug('Process scan failed',exc_info=True);rows=[]
    return desktop_rows(rows,app)

def main_processes(app,fresh=False):
    return [p for p in app_processes(app,fresh) if '--type=' not in (p.get('CommandLine') or '')]

def codex_connected(bridge):
    """A missing IPC client is an ordinary 'not connected' state, never a 503."""
    try:
        ipc=getattr(bridge,'ipc',None)
        return bool(bridge and ipc and ipc.client_id and ipc.socket)
    except Exception:return False

def executable(app):
    running=main_processes(app)
    if running:return Path(running[0]['ExecutablePath'])
    if app=='deepseek':
        # LOCALAPPDATA can be absent in a stripped or redirected environment; the
        # install directory is just one candidate, so skip it rather than raising.
        local=os.environ.get('LOCALAPPDATA')
        roots=[Path(r'D:\Program Files\deepseek')]
        if local:roots += [Path(local)/'Programs/deepseek',Path(local)/'Programs/DeepSeek Harness']
        for p in [root/name for root in roots for name in ['DeepSeek Harness.exe','deepseek.exe']]:
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
        # One unreachable client must never make the whole control panel unusable.
        # A single scan per snapshot: the two windows below used to ask for the same
        # process table again inside the same loop.
        with _snapshot_lock:
            rows=[]
            try:rows=processes()
            except Exception:logging.debug('Process scan failed for the application snapshot',exc_info=True)
            if not rows:
                try:rows=processes(fresh=True)
                except Exception:logging.debug('Fresh process scan failed',exc_info=True)
        result=[]
        for app,name in NAMES.items():
            try:
                running=any('--type=' not in (p.get('CommandLine') or '') for p in desktop_rows(rows,app))
                connected=bool(self.desktop and self.desktop.connected) if app=='claude' else codex_connected(self.codex) if app=='codex' else False
            except Exception:running,connected=False,False
            result.append({'id':app,'running':running,'connected':running and connected})
        result.append({'id':'assistant','running':self.window_open,'background':True})
        return {'apps':result,'claudeError':self.error}
    def execute(self,app,action,confirmed=False):
        if app not in (*NAMES,'assistant') or action not in ('start','stop','restart','shutdown','cancel'):raise ValueError('不支持的应用操作')
        with self.app_locks[app]:
            if app=='assistant':
                if action in ('shutdown','cancel'):return self.power(action,confirmed)
                if action=='stop' and not confirmed:raise ValueError('请确认关闭助手窗口')
                self.window_open=action!='stop';self.window(self.window_open)
                return {'status':'accepted','background':True}
            current=main_processes(app,True)
            path=Path(current[0]['ExecutablePath']) if current else None
            if action in ('stop','restart'):
                if not confirmed:raise ValueError('关闭会中断该应用任务，请确认')
                for row in current:close_process(row['ProcessId'])
                deadline=time.monotonic()+12
                while current and time.monotonic()<deadline:
                    time.sleep(.2);current=main_processes(app,True)
                if current:raise ValueError('应用仍未退出，请在电脑检查')
                # Packaged clients leave renderer and helper children running, and
                # they can bring the main window back. Stop the whole app tree.
                remaining=app_processes(app,True)
                for row in remaining:close_process(row['ProcessId'])
                if remaining:
                    time.sleep(1.0);remaining=app_processes(app,True)
                if remaining:raise ValueError('应用仍有后台进程未退出，请在电脑检查')
                if app=='codex' and self.codex:
                    try:self.codex.ipc.close()
                    except Exception:logging.debug('Codex IPC close failed',exc_info=True)
                if app=='claude':self.injected=False
                if action=='stop':return {'status':'accepted'}
                # A packaged client cannot be reactivated the instant it was killed.
                time.sleep(1.0)
            if current:
                if app=='codex':
                    os.startfile('shell:AppsFolder\\'+package_aumid(app))
                    return self.connect_codex()
                if app=='claude':
                    if keyboard.enable_devtools(path):
                        return {'status':'needs_restart','message':'已启用 Claude 开发者工具，已有实例需要正常重启一次才能生效'}
                    self.attempted_pid=None;self.next_attempt=time.monotonic()+1;self.wake.set()
                return {'status':'accepted','message':'正在连接 Claude…' if app=='claude' else '应用已启动'}
            log_path=self.directory/(app+'-startup.log')
            if app in PACKAGE_NAME:
                # A raw Popen of a packaged binary returns WinError 5 because the
                # package only lets the shell start its declared entry point.
                log_path.write_bytes(b'')
                deadline=time.monotonic()+25
                # Reactivating right after a restart can be ignored by the shell, so
                # ask again instead of reporting a failed start.
                running=[]
                for attempt in range(3):
                    os.startfile('shell:AppsFolder\\'+package_aumid(app))
                    settle=time.monotonic()+8
                    while not running and time.monotonic()<settle:
                        running=main_processes(app,True)
                        if not running:time.sleep(.4)
                    if running or time.monotonic()>deadline:break
                    time.sleep(1.5)
                if not running:
                    raise ValueError('启动后未检测到 '+NAMES[app]+'，请在电脑确认该应用可以正常打开')
            else:
                path=path or executable(app);args=[str(path)]
                if app=='claude':keyboard.enable_devtools(path)
                startup=subprocess.STARTUPINFO();startup.dwFlags|=subprocess.STARTF_USESHOWWINDOW;startup.wShowWindow=1
                # One retry: a relaunch immediately after a restart can be dropped
                # by Windows while the previous instance is still settling.
                for attempt in range(2):
                    with log_path.open('wb') as log:
                        process=subprocess.Popen(args,startupinfo=startup,stdout=log,stderr=log)
                    time.sleep(.5);code=process.poll()
                    if not isinstance(code,int) or code==0:break
                    if attempt:break
                    time.sleep(1.0)
                if isinstance(code,int) and code!=0:
                    reason=log_path.read_text('utf-8',errors='replace')
                    raise ValueError('应用启动失败，退出码 '+str(code)+'；请查看 '+app+'-startup.log')
            if app=='claude':
                self.attempted_pid=None;self.next_attempt=time.monotonic()+3;self.wake.set()
            return self.connect_codex() if app=='codex' else {'status':'accepted'}
    # Fixed, allowlisted power commands. Never an arbitrary shell string, and the
    # delay leaves room to cancel from either the phone or the computer.
    POWER_COMMANDS={'shutdown':['/s','/t','60','/c','coding远程：来自手机的关机请求'],'cancel':['/a']}

    def power(self,action,confirmed=False):
        if not confirmed:raise ValueError('关机或取消关机需要确认')
        try:
            result=subprocess.run(['shutdown',*self.POWER_COMMANDS[action]],capture_output=True,text=True,
                                  encoding='utf-8',errors='replace',timeout=15,
                                  creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        except (OSError,subprocess.SubprocessError) as error:
            raise ValueError('无法执行关机命令：'+str(error)[:200])
        if result.returncode:
            raise ValueError((result.stderr or result.stdout or '关机命令被系统拒绝').strip()[:200])
        return {'status':'accepted','background':True,
                'message':'电脑将在 60 秒后关机；可在电脑执行 shutdown /a 取消，或在此点击取消关机' if action=='shutdown' else '已取消关机'}

    def connect_codex(self):
        try:
            ipc=getattr(self.codex,'ipc',None)
            if ipc is None:raise RuntimeError('Codex 桌面连接尚未准备好')
            ipc.connect()
            if not ipc.client_id or not ipc.socket:raise RuntimeError('未收到 Codex 连接回执')
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
            # Never queue a phone-triggered start/stop behind a long injection
            # attempt; skip this cycle and retry on the next one.
            if not self.lock.acquire(blocking=False):continue
            try:self.inject()
            except Exception as exc:
                self.injected=False;self.error=str(exc)[:300]
                if self.desktop:self.desktop.disconnected('Claude 自动连接失败：'+self.error)
                if self.error!=self.last_log:logging.warning('Claude keyboard connection: %s',self.error);self.last_log=self.error
            finally:self.lock.release()
    def close(self):self.stopped.set();self.wake.set()
