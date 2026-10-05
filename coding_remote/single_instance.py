"""Per-user Windows event: relaunch shows the resident assistant instead of binding twice."""
import ctypes
from ctypes import wintypes
import hashlib
import threading

class SingleInstance:
    def __init__(self,directory):
        self.kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        self.kernel.CreateMutexW.argtypes=[ctypes.c_void_p,wintypes.BOOL,wintypes.LPCWSTR];self.kernel.CreateMutexW.restype=wintypes.HANDLE
        self.kernel.CreateEventW.argtypes=[ctypes.c_void_p,wintypes.BOOL,wintypes.BOOL,wintypes.LPCWSTR];self.kernel.CreateEventW.restype=wintypes.HANDLE
        self.kernel.SetEvent.argtypes=[wintypes.HANDLE];self.kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD];self.kernel.CloseHandle.argtypes=[wintypes.HANDLE]
        name='Local\\CodingRemoteAssistant-'+hashlib.sha256(str(directory).lower().encode()).hexdigest()[:24]
        self.mutex=self.kernel.CreateMutexW(None,False,name)
        if not self.mutex:raise ctypes.WinError(ctypes.get_last_error())
        self.existing=ctypes.get_last_error()==183
        self.event=self.kernel.CreateEventW(None,False,False,name+'-activate')
        if not self.event:raise ctypes.WinError(ctypes.get_last_error())
        self.stop=threading.Event();self.thread=None
        if self.existing:self.kernel.SetEvent(self.event)
    def watch(self,callback):
        def loop():
            while not self.stop.is_set():
                if self.kernel.WaitForSingleObject(self.event,500)==0 and not self.stop.is_set():callback()
        self.thread=threading.Thread(target=loop,daemon=True);self.thread.start()
    def close(self):
        self.stop.set();self.kernel.SetEvent(self.event)
        if self.thread:self.thread.join(2)
        self.kernel.CloseHandle(self.event);self.kernel.CloseHandle(self.mutex)
