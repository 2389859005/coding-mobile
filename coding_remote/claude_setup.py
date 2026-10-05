"""Open the installed Claude app's normal DevTools; never type or execute code."""
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import time


def open_developer_tools():
    user = ctypes.WinDLL('user32', use_last_error=True)
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    user.GetForegroundWindow.restype = wintypes.HWND
    user.SetForegroundWindow.argtypes = [wintypes.HWND]
    user.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def windows():
        found = []
        @callback_type
        def visit(hwnd, _):
            if not user.IsWindowVisible(hwnd): return True
            pid = wintypes.DWORD()
            user.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            process = kernel.OpenProcess(0x1000, False, pid.value)
            if not process: return True
            try:
                path = ctypes.create_unicode_buffer(32768); size = wintypes.DWORD(len(path))
                if not kernel.QueryFullProcessImageNameW(process, 0, path, ctypes.byref(size)): return True
                if Path(path.value).name.lower() != 'claude.exe': return True
            finally: kernel.CloseHandle(process)
            title = ctypes.create_unicode_buffer(1024)
            user.GetWindowTextW(hwnd, title, len(title))
            found.append((hwnd, title.value))
            return True
        user.EnumWindows(visit, 0)
        return found

    matches = windows()
    if not matches:
        # Windows activates its registered packaged application, preserving login.
        os.startfile(r'shell:AppsFolder\Claude_pzs8sxrjxfjjc!Claude')
        deadline = time.monotonic()+25
        while time.monotonic()<deadline and not matches:
            time.sleep(.5); matches = windows()
    devtools = next((w for w in matches if 'devtools' in w[1].lower() or '开发者工具' in w[1]), None)
    window = devtools or next((w for w in matches if w[1].strip().lower() == 'claude'), None)
    if not window:
        return '请打开 Claude 主窗口后点击「打开开发者工具」。'
    user.ShowWindow(window[0], 9)
    user.SetForegroundWindow(window[0])
    time.sleep(.25)
    if user.GetForegroundWindow() != window[0]:
        return 'Claude 已打开。请切到 Claude，按 Ctrl+Alt+I 打开开发者工具。'
    if not devtools:
        # Only the normal UI shortcut. Clipboard and paste-protection are untouched.
        for key in (0x11,0x12,0x49): user.keybd_event(key,0,0,0)
        for key in (0x49,0x12,0x11): user.keybd_event(key,0,2,0)
    return '开发者工具已打开。复制脚本，在 Claude Console 粘贴并按回车。'
