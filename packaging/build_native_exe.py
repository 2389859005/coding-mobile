import hashlib
import json
import struct
import subprocess
import zlib
from pathlib import Path
from build_common import unpack, COOKIE, ENTRY, MAGIC, EXPECTED_EXE as EXPECTED, ROOT


def build(source=None):
    original=Path(source) if source else ROOT/'CodexMobileAssistant-Windows-1.6.23.exe'
    assert hashlib.sha256(original.read_bytes()).hexdigest()==EXPECTED
    subprocess.run(['node',str(ROOT/'packaging/minify_connector.cjs')],cwd=ROOT,check=True)
    compiler=Path(r'C:/Windows/Microsoft.NET/Framework64/v4.0.30319')
    subprocess.run([str(compiler/'csc.exe'),'/nologo','/target:exe','/platform:x64',
                    '/reference:System.Windows.Forms.dll','/reference:System.Drawing.dll',
                    '/out:'+str(ROOT/'.local/coding-tray.exe'),str(ROOT/'packaging/coding-tray.cs')],check=True)
    subprocess.run([str(compiler/'csc.exe'),'/nologo','/target:exe','/platform:x64',
                    '/out:'+str(ROOT/'.local/claude-keyboard.exe'),
                    *['/reference:'+str(compiler/'WPF'/name) for name in ['UIAutomationClient.dll','UIAutomationTypes.dll','WindowsBase.dll']],
                    str(ROOT/'packaging/claude-keyboard.cs')],check=True)
    compiled=ROOT/'.local/native-entry.marshal'
    subprocess.run([str(ROOT/'.local/python313/python.exe'),str(ROOT/'packaging/compile_native_entry.py'),str(compiled)],check=True)
    prefix,entries,version,dll=unpack(original.read_bytes())
    from product_icon import replace_icon
    prefix=replace_icon(prefix,ROOT/'packaging/product/icon.ico')
    rows=[]
    removed=[]
    for name,payload,raw,compressed,kind in entries:
        if name=='固定域名入口':
            content=compiled.read_bytes();rows.append(('coding_remote_entry',zlib.compress(content,9),len(content),1,b's'))
        elif name=='bridge\\store.py':
            content=(ROOT/'packaging/bridge/store.py').read_bytes();rows.append((name,zlib.compress(content,9),len(content),1,b'b'))
        elif name in ('assets\\codex-mobile.png','assets\\codex-mobile.ico'):
            content=(ROOT/('packaging/product/icon.ico' if name.endswith('.ico') else 'packaging/product/icon.png')).read_bytes();rows.append((name,zlib.compress(content,9),len(content),1,b'b'))
        elif name=='app\\run.py' or name.startswith('app\\web\\'):
            removed.append(name)
        else:rows.append((name,payload,raw,compressed,kind))
    files={'bin\\tailnet.exe':ROOT/'.local/tailnet-windows.exe','bin\\claude-keyboard.exe':ROOT/'.local/claude-keyboard.exe','bin\\coding-tray.exe':ROOT/'.local/coding-tray.exe'}
    for path in (ROOT/'coding_remote').glob('*.py'):files['coding_remote\\'+path.name]=path
    for path in (ROOT/'coding_remote').glob('*.mjs'):files['coding_remote\\'+path.name]=path
    for name in ['__init__.py','errors.py','model.py','mailbox.py','cdp_desktop.py','packaged.py','desktop-file.js','desktop-console.js']:
        files['claude_bridge\\'+name]=ROOT/'claude_bridge'/name
    files['CODING_REMOTE_NOTICE.txt']=ROOT/'packaging/LOCAL_ADAPTER_NOTICE.txt'
    files['TAILNET_NOTICES.txt']=ROOT/'packaging/tailnet/THIRD_PARTY_NOTICES.txt'
    for name,path in files.items():
        content=path.read_bytes();rows.append((name,zlib.compress(content,9),len(content),1,b'b'))
    data=bytearray();toc=[]
    for name,payload,raw,compressed,kind in rows:
        toc.append((name,len(data),len(payload),raw,compressed,kind));data.extend(payload)
    offset=len(data)
    for name,pos,packed,raw,compressed,kind in toc:
        encoded=name.encode()+b'\0';size=(ENTRY.size+len(encoded)+15)//16*16
        data.extend(ENTRY.pack(size,pos,packed,raw,compressed,kind));data.extend(encoded.ljust(size-ENTRY.size,b'\0'))
    size=len(data)-offset;data.extend(COOKIE.pack(MAGIC,len(data)+COOKIE.size,offset,size,version,dll))
    target=ROOT/'dist/CodingRemoteAssistant-Windows-2.0.11.exe';target.parent.mkdir(exist_ok=True)
    content=prefix+data
    _,after,_,_=unpack(content);assert len(after)==len(rows)
    temporary=target.with_suffix('.tmp');temporary.write_bytes(content);temporary.replace(target)
    report={'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'removed_web_assets':removed,'added':list(files),'native_entry':True}
    target.with_suffix('.build.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(target,report['sha256'])
    compiled.unlink(missing_ok=True)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--source',help='Path to the verified original Windows 1.6.23 EXE')
    build(parser.parse_args().source)
