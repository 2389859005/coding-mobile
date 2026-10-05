"""Loopback-only CDP client. No clipboard, DevTools window, or remote endpoint."""
import base64
import hashlib
import json
import os
import socket
import struct
import urllib.request
from urllib.parse import urlsplit

class CDP:
    def __init__(self,url):
        u=urlsplit(url)
        if u.scheme!='ws' or u.hostname not in ('127.0.0.1','localhost') or not u.port:raise ValueError('CDP 必须使用本机端口')
        self.socket=socket.create_connection(('127.0.0.1',u.port),timeout=8);self.counter=0
        key=base64.b64encode(os.urandom(16)).decode()
        self.socket.sendall((f'GET {u.path} HTTP/1.1\r\nHost: 127.0.0.1:{u.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n').encode())
        header=b''
        while not header.endswith(b'\r\n\r\n'):
            header+=self.exact(1)
            if len(header)>16384:raise ValueError('CDP 握手异常')
        expected=base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
        if b' 101 ' not in header.split(b'\r\n')[0] or expected not in header:self.close();raise ValueError('CDP 握手被拒绝')
    def exact(self,size):
        result=b''
        while len(result)<size:
            part=self.socket.recv(size-len(result))
            if not part:raise ConnectionError('CDP 已断开')
            result+=part
        return result
    def send(self,payload,opcode=1):
        mask=os.urandom(4);n=len(payload)
        header=bytes([128|opcode,128|(n if n<126 else 126 if n<65536 else 127)])
        if n>=126:header+=struct.pack('!H' if n<65536 else '!Q',n)
        self.socket.sendall(header+mask+bytes(b^mask[i%4] for i,b in enumerate(payload)))
    def receive(self):
        chunks=[];size=0
        while True:
            a,b=self.exact(2);n=b&127
            if n==126:n=struct.unpack('!H',self.exact(2))[0]
            elif n==127:n=struct.unpack('!Q',self.exact(8))[0]
            if n>8*1024*1024 or size+n>8*1024*1024:raise ValueError('CDP 消息过大')
            mask=self.exact(4) if b&128 else None;data=self.exact(n)
            if mask:data=bytes(v^mask[i%4] for i,v in enumerate(data))
            opcode=a&15
            if opcode==8:raise ConnectionError('CDP 已关闭')
            if opcode==9:self.send(data,10);continue
            if opcode==10:continue
            chunks.append(data);size+=n
            if a&128:return json.loads(b''.join(chunks))
    def call(self,method,params=None):
        self.counter+=1;rid=self.counter;self.send(json.dumps({'id':rid,'method':method,'params':params or {}}).encode())
        while True:
            reply=self.receive()
            if reply.get('id')!=rid:continue
            if 'error' in reply:raise RuntimeError(reply['error'].get('message','CDP 调用失败'))
            result=reply.get('result',{})
            if result.get('exceptionDetails'):raise RuntimeError('Claude 渲染进程执行失败')
            return result
    def evaluate(self,expression):
        return self.call('Runtime.evaluate',{'expression':expression,'returnByValue':True})
    def close(self):self.socket.close()

def targets(port):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f'http://127.0.0.1:{int(port)}/json/list',timeout=3) as response:return json.load(response)
