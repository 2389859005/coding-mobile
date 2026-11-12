"""Windows file notifications for desktop transcripts and the session database."""
import ctypes
import os
import threading
from pathlib import Path


class ChangeFeed:
    def __init__(self, root, changed, paths=None):
        self.root, self.changed = Path(root), changed
        self.paths=paths
        self.stop = threading.Event()
        self.ready = threading.Event()
        self.available = False
        self.thread = threading.Thread(target=self.run, daemon=True, name='desktop-changes')

    def start(self):
        self.thread.start()
        self.ready.wait(1)

    def run(self):
        if os.name != 'nt' or not self.root.is_dir():
            self.ready.set()
            return
        from ctypes import wintypes
        api = ctypes.WinDLL('kernel32', use_last_error=True)
        api.FindFirstChangeNotificationW.argtypes = [wintypes.LPCWSTR, wintypes.BOOL, wintypes.DWORD]
        api.FindFirstChangeNotificationW.restype = wintypes.HANDLE
        api.FindNextChangeNotification.argtypes = [wintypes.HANDLE]
        api.FindCloseChangeNotification.argtypes = [wintypes.HANDLE]
        api.WaitForMultipleObjects.argtypes = [wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE), wintypes.BOOL, wintypes.DWORD]
        api.WaitForMultipleObjects.restype = wintypes.DWORD
        handles=[]
        try:
            paths=self.paths or ((self.root, not all((self.root/p).is_dir() for p in ('sessions','archived_sessions'))), (self.root/'sessions', True), (self.root/'archived_sessions', True))
            for path, recursive in paths:
                if not path.is_dir():continue
                handle=api.FindFirstChangeNotificationW(str(path), recursive, 0x1|0x2|0x8|0x10)
                if handle and handle != ctypes.c_void_p(-1).value:handles.append(handle)
            self.available=bool(handles);self.ready.set()
            if not handles:return
            array=(wintypes.HANDLE*len(handles))(*handles)
            while not self.stop.is_set():
                result=api.WaitForMultipleObjects(len(handles),array,False,500)
                if result == 258:continue
                if result >= len(handles):break
                if not api.FindNextChangeNotification(handles[result]):break
                self.changed()
        finally:
            self.available=False;self.ready.set()
            for handle in handles:api.FindCloseChangeNotification(handle)

    def close(self):
        self.stop.set()
        if self.thread.ident and self.thread is not threading.current_thread():self.thread.join(timeout=1)
