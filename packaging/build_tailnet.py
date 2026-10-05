"""Reproducible embedded tsnet builds; Go modules/versions are pinned in tailnet/go.sum."""
import os
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
def build():
    outputs=[('windows','amd64',ROOT/'.local/tailnet-windows.exe'),('android','arm64',ROOT/'.local/tailnet/arm64-v8a/libtailnet.so'),('android','amd64',ROOT/'.local/tailnet/x86_64/libtailnet.so')]
    for system,arch,path in outputs:
        path.parent.mkdir(parents=True,exist_ok=True);env={**os.environ,'GOOS':system,'GOARCH':arch,'CGO_ENABLED':'0'}
        if system=='android':
            triple='aarch64' if arch=='arm64' else 'x86_64'
            compiler=Path('D:/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin')/(triple+'-linux-android26-clang.cmd')
            assert compiler.is_file(),'Android NDK r27c is required'
            env.update({'CGO_ENABLED':'1','CC':str(compiler)})
        flags='-s -w'+(' -extldflags=-Wl,-z,max-page-size=16384' if system=='android' else '')
        args=['go','build','-trimpath','-ldflags='+flags,'-o',str(path)]
        if system=='android':args+=['-buildmode=pie']
        subprocess.run(args+['.'],cwd=ROOT/'packaging/tailnet',env=env,check=True)
        print(system,arch,path.stat().st_size)
if __name__=='__main__':build()
