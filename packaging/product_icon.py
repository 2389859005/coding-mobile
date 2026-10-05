"""Replace the launcher icon before appending the PyInstaller archive."""
import ctypes
import struct
import tempfile
from pathlib import Path

def replace_icon(prefix, icon):
    data=Path(icon).read_bytes()
    count=struct.unpack_from('<H',data,4)[0]
    group=bytearray(data[:6])
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.BeginUpdateResourceW.argtypes=[ctypes.c_wchar_p,ctypes.c_bool]
    kernel.BeginUpdateResourceW.restype=ctypes.c_void_p
    kernel.UpdateResourceW.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_ushort,ctypes.c_void_p,ctypes.c_uint]
    kernel.UpdateResourceW.restype=ctypes.c_bool
    kernel.EndUpdateResourceW.argtypes=[ctypes.c_void_p,ctypes.c_bool]
    kernel.EndUpdateResourceW.restype=ctypes.c_bool
    with tempfile.TemporaryDirectory(prefix='coding-icon-') as folder:
        path=Path(folder)/'launcher.exe';path.write_bytes(prefix)
        handle=kernel.BeginUpdateResourceW(str(path),False)
        if not handle:raise ctypes.WinError(ctypes.get_last_error())
        try:
            def put(kind,key,blob):
                buffer=ctypes.create_string_buffer(blob)
                if not kernel.UpdateResourceW(handle,kind,key,0,buffer,len(blob)):raise ctypes.WinError(ctypes.get_last_error())
            for index in range(count):
                entry=data[6+index*16:22+index*16];size,offset=struct.unpack_from('<II',entry,8)
                put(3,index+1,data[offset:offset+size]);group.extend(entry[:12]+struct.pack('<H',index+1))
            put(14,1,bytes(group))
        except BaseException:
            kernel.EndUpdateResourceW(handle,True);raise
        if not kernel.EndUpdateResourceW(handle,False):raise ctypes.WinError(ctypes.get_last_error())
        return path.read_bytes()
