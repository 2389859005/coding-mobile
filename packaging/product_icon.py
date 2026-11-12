"""Replace the launcher icon before appending the PyInstaller archive."""
import ctypes
import struct
import tempfile
from pathlib import Path

def version_resource(version):
    numbers=tuple(int(part) for part in version.split('.'))+(0,)
    major,minor,patch,build=numbers[:4]
    def block(key,value=b'',children=(),kind=1):
        data=bytearray(b'\0'*6+(key+'\0').encode('utf-16le'));data.extend(b'\0'*(-len(data)%4));data.extend(value)
        for child in children:data.extend(b'\0'*(-len(data)%4));data.extend(child)
        struct.pack_into('<HHH',data,0,len(data),len(value)//2 if kind else len(value),kind)
        return bytes(data)
    fields={'FileDescription':'coding远程助手','FileVersion':version+'.0','InternalName':'CodingRemoteAssistant',
            'OriginalFilename':'CodingRemoteAssistant-Windows-'+version+'.exe','ProductName':'coding远程','ProductVersion':version+'.0'}
    strings=block('StringFileInfo',children=[block('040904B0',children=[block(key,(value+'\0').encode('utf-16le')) for key,value in fields.items()])])
    translation=block('VarFileInfo',children=[block('Translation',struct.pack('<HH',0x409,1200),kind=0)])
    fixed=struct.pack('<13I',0xFEEF04BD,0x10000,(major<<16)|minor,(patch<<16)|build,(major<<16)|minor,(patch<<16)|build,0x3F,0,0x40004,1,0,0,0)
    return block('VS_VERSION_INFO',fixed,[strings,translation],kind=0)


def replace_icon(prefix, icon, version=None):
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
            if version:put(16,1,version_resource(version))
        except BaseException:
            kernel.EndUpdateResourceW(handle,True);raise
        if not kernel.EndUpdateResourceW(handle,False):raise ctypes.WinError(ctypes.get_last_error())
        return path.read_bytes()
