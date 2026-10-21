import hashlib
import json
import marshal
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
    # Ship coding_remote and the schema adapter as bytecode: the interpreter then
    # starts without parsing either, and the archive holds one entry for the package
    # instead of a header per module. Compiled by the bundled 3.13, because the code
    # objects have to match the frozen interpreter.
    bytecode=ROOT/'.local/coding-remote.marshal'
    subprocess.run([str(ROOT/'.local/python313/python.exe'),str(ROOT/'packaging/compile_sources.py'),str(bytecode)],check=True)
    module_count=len(marshal.loads(bytecode.read_bytes()))
    loader=ROOT/'.local/coding-remote-loader.pyc'
    # Compiled by the bundled 3.13 as well: this used to run inside the build
    # script itself (a 3.12 .venv), and the 3.12 bytecode unmarshalled fine but
    # crashed the frozen 3.13 interpreter with an access violation.
    subprocess.run([str(ROOT/'.local/python313/python.exe'),str(ROOT/'packaging/compile_loader.py'),str(loader)],check=True)
    prefix,entries,version,dll=unpack(original.read_bytes())
    from product_icon import replace_icon
    prefix=replace_icon(prefix,ROOT/'packaging/product/icon.ico')
    rows=[]
    removed=[]
    for name,payload,raw,compressed,kind in entries:
        if name=='固定域名入口':
            content=compiled.read_bytes();rows.append(('coding_remote_entry',zlib.compress(content,9),len(content),1,b's'))
        elif name=='bridge\\store.py':
            # Superseded by the compiled copy below; the executable no longer ships
            # a second copy just to satisfy the loader's source fallback.
            removed.append(name)
        elif name in ('assets\\codex-mobile.png','assets\\codex-mobile.ico'):
            content=(ROOT/('packaging/product/icon.ico' if name.endswith('.ico') else 'packaging/product/icon.png')).read_bytes();rows.append((name,zlib.compress(content,9),len(content),1,b'b'))
        elif name=='app\\run.py' or name.startswith('app\\web\\'):
            removed.append(name)
        else:rows.append((name,payload,raw,compressed,kind))
    files={'bin\\tailnet.exe':ROOT/'.local/tailnet-windows.exe','bin\\claude-keyboard.exe':ROOT/'.local/claude-keyboard.exe','bin\\coding-tray.exe':ROOT/'.local/coding-tray.exe'}
    # The bytecode table and its loader are the only entries the packer must append
    # without the source tree behind them, so they are registered before anything
    # re-reads the archive.
    extra={'coding_remote.bytecode':bytecode.read_bytes(),'coding_remote_loader.pyc':loader.read_bytes()}
    # Record the digest of the tunnel binary as it appears on disk at runtime, so
    # the helper can refuse to execute a replaced cloudflared.exe before handing it
    # the loopback URL. Entries are stored zlib-compressed; the executed file is the
    # decompressed form. A raw PE header here means the entry was stored verbatim.
    tunnel_digest=None
    for name,payload,raw,compressed,kind in rows:
        if name.lower().replace('/','\\').endswith('bin\\cloudflared.exe'):
            blob=payload if compressed==0 else zlib.decompress(payload)
            tunnel_digest=hashlib.sha256(blob).hexdigest()
            break
    if tunnel_digest is None:
        raise SystemExit('base image has no bin\\cloudflared.exe to record')
    manifest_path=ROOT/'.local/cloudflared.sha256'
    manifest_path.write_text(json.dumps({'sha256':tunnel_digest},indent=2)+'\n','utf-8')
    files['bin\\cloudflared.sha256']=manifest_path
    # coding_remote, bridge.store and the import hook ship as one compiled table,
    # loaded by the entry stub (see the `extra` entries above). The .mjs adapter
    # still ships as source because the DeepSeek host loads it as a file path.
    # Compiled code is ~60% larger on the wire than the source it replaces, so the
    # payload takes the hit and start-up stops paying to parse it.
    for path in (ROOT/'coding_remote').glob('*.mjs'):files['coding_remote\\'+path.name]=path
    for name in ['__init__.py','errors.py','model.py','mailbox.py','cdp_desktop.py','packaged.py','desktop-file.js','desktop-console.js']:
        files['claude_bridge\\'+name]=ROOT/'claude_bridge'/name
    files['CODING_REMOTE_NOTICE.txt']=ROOT/'packaging/LOCAL_ADAPTER_NOTICE.txt'
    files['TAILNET_NOTICES.txt']=ROOT/'packaging/tailnet/THIRD_PARTY_NOTICES.txt'
    for name,path in files.items():
        content=path.read_bytes();rows.append((name,zlib.compress(content,9),len(content),1,b'b'))
    for name,content in extra.items():
        rows.append((name,zlib.compress(content,9),len(content),1,b'b'))
    data=bytearray();toc=[]
    for name,payload,raw,compressed,kind in rows:
        toc.append((name,len(data),len(payload),raw,compressed,kind));data.extend(payload)
    offset=len(data)
    for name,pos,packed,raw,compressed,kind in toc:
        encoded=name.encode()+b'\0';size=(ENTRY.size+len(encoded)+15)//16*16
        data.extend(ENTRY.pack(size,pos,packed,raw,compressed,kind));data.extend(encoded.ljust(size-ENTRY.size,b'\0'))
    size=len(data)-offset;data.extend(COOKIE.pack(MAGIC,len(data)+COOKIE.size,offset,size,version,dll))
    target=ROOT/'dist/CodingRemoteAssistant-Windows-2.1.1.exe';target.parent.mkdir(exist_ok=True)
    content=prefix+data
    _,after,_,_=unpack(content);assert len(after)==len(rows)
    temporary=target.with_suffix('.tmp');temporary.write_bytes(content);temporary.replace(target)
    report={'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'removed_web_assets':removed,'added':list(files),'native_entry':True,'compiled_modules':module_count}
    target.with_suffix('.build.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(target,report['sha256'],module_count,'compiled modules')
    compiled.unlink(missing_ok=True)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--source',help='Path to the verified original Windows 1.6.23 EXE')
    build(parser.parse_args().source)
