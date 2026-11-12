"""Disposable SSH host for emulator-only tests. Requires test dependency paramiko."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import select
import socket
import subprocess
import sys
import threading
import paramiko

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5580');parser.add_argument('--ed25519',action='store_true');args=parser.parse_args()
    if not args.serial.startswith('emulator-'):raise ValueError('Use an isolated emulator')
    host_key=paramiko.RSAKey.generate(2048);client_key=paramiko.RSAKey.generate(2048)
    key_text=io.StringIO();client_key.write_private_key(key_text)
    if args.ed25519:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        from cryptography.hazmat.primitives.serialization import Encoding,PrivateFormat,NoEncryption
        def private():return Ed25519PrivateKey.generate().private_bytes(Encoding.PEM,PrivateFormat.OpenSSH,NoEncryption()).decode()
        host_key=paramiko.Ed25519Key.from_private_key(io.StringIO(private()))
        key_text=io.StringIO(private());client_key=paramiko.Ed25519Key.from_private_key(io.StringIO(key_text.getvalue()))
    auth_count=0;transports=[];stopped=threading.Event()
    class SSH(paramiko.ServerInterface):
        def get_allowed_auths(self,user):return 'publickey,password'
        def check_auth_password(self,user,password):
            nonlocal auth_count;auth_count+=1
            return paramiko.AUTH_SUCCESSFUL if user=='fixture' and password=='disposable-test-password' else paramiko.AUTH_FAILED
        def check_auth_publickey(self,user,key):
            nonlocal auth_count;auth_count+=1
            return paramiko.AUTH_SUCCESSFUL if user=='fixture' and key==client_key else paramiko.AUTH_FAILED
        def check_channel_direct_tcpip_request(self,chanid,origin,dest):
            return paramiko.OPEN_SUCCEEDED if dest==('127.0.0.1',http.server_port) else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
    listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();listener.settimeout(.5)
    class HTTP(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path=='/fixture':value={'sshPort':listener.getsockname()[1],'httpPort':http.server_port,'privateKey':key_text.getvalue(),'authCount':auth_count}
            else:value={'host':self.headers.get('Host')}
            data=json.dumps(value).encode();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        def log_message(self,*args):pass
    http=ThreadingHTTPServer(('127.0.0.1',0),HTTP)
    def forward(channel):
        with socket.create_connection(('127.0.0.1',http.server_port)) as target:
            try:
                while not stopped.is_set():
                    readable,_,_=select.select([channel,target],[],[],.5)
                    for source in readable:
                        data=source.recv(65536)
                        if not data:return
                        (target if source is channel else channel).sendall(data)
            finally:channel.close()
    def serve_client(sock):
        transport=paramiko.Transport(sock);transports.append(transport);transport.add_server_key(host_key)
        try:
            transport.start_server(server=SSH())
            while not stopped.is_set() and transport.is_active():
                channel=transport.accept(.5)
                if channel:threading.Thread(target=forward,args=(channel,),daemon=True).start()
        except (EOFError,paramiko.SSHException,ConnectionError):pass
        finally:transport.close()
    def accept():
        while not stopped.is_set():
            try:sock,_=listener.accept()
            except socket.timeout:continue
            except OSError:return
            threading.Thread(target=serve_client,args=(sock,),daemon=True).start()
    threading.Thread(target=http.serve_forever,daemon=True).start();threading.Thread(target=accept,daemon=True).start()
    try:
        subprocess.run([sys.executable,str(Path(__file__).with_name('test_native_ui.py')),'--serial',args.serial,'--suite','SshRegression','--live-origin',f'http://10.0.2.2:{http.server_port}'],check=True)
    finally:
        stopped.set();listener.close();http.shutdown();http.server_close()
        for transport in transports:transport.close()

if __name__=='__main__':main()
