"""Reference-style LAN gateway and independently supervised public entrances."""
from concurrent.futures import ThreadPoolExecutor
import ipaddress
import json
from pathlib import Path
import re
import socket
import threading
import time
from urllib.parse import urlsplit
import urllib.request
import uuid
from . import VERSION
from .ingress_common import write_json

MODES={'quick':'临时 HTTPS · Cloudflare','cloudflare':'固定域名 · Cloudflare Tunnel','server':'SSH连接 · SSH 转发','external':'已有穿透 / 反向代理','tailscale':'固定 HTTPS · 永久连接'}


def normalize_origin(value,private_http=False,allow_loopback=False):
    if not isinstance(value,str) or len(value)>2048 or any(char.isspace() or ord(char)<32 for char in value):raise ValueError('请输入完整入口地址')
    if '://' not in value:value='https://'+value
    try:
        parsed=urlsplit(value);port=parsed.port;host=parsed.hostname
    except ValueError:raise ValueError('入口地址或端口无效') from None
    if not host or '\\' in value or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment or parsed.path not in ('','/') or port==0:raise ValueError('地址不能包含账号、路径或参数')
    try:
        ip=ipaddress.ip_address(host);local=ip.version==4 and any(ip in ipaddress.ip_network(net) for net in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','100.64.0.0/10'));loopback=ip.is_loopback
    except ValueError:
        if not re.fullmatch(r'[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?',host) or '..' in host:raise ValueError('域名无效') from None
        local=False;loopback=host=='localhost' or host.endswith('.localhost')
    if loopback and not allow_loopback:raise ValueError('手机不能使用电脑回环地址，请选择局域网或 HTTPS 入口')
    if parsed.scheme!='https' and not (parsed.scheme=='http' and private_http and (local or loopback)):raise ValueError('公网入口必须使用 HTTPS；HTTP 仅用于局域网')
    return parsed.scheme+'://'+parsed.netloc.lower()


class GatewayIngress:
    def __init__(self,server,directory,assets,workspace):
        self.server,self.directory,self.assets,self.workspace=server,Path(directory),Path(assets),workspace
        self.path=self.directory/'gateway-connections.json';self.lock=threading.RLock();self.runtime={};self.generations={};self.closed=False
        self.pool=ThreadPoolExecutor(max_workers=4,thread_name_prefix='gateway-entry')
        try:self.entries=json.loads(self.path.read_text('utf-8'))
        except (OSError,ValueError):self.entries=[]
        if not isinstance(self.entries,list):self.entries=[]

    def save(self,value):
        identifier=str(uuid.UUID(value.get('id'))) if value.get('id') else str(uuid.uuid4())
        mode=value.get('mode')
        if mode not in MODES:raise ValueError('连接类型无效')
        entry={'id':identifier,'mode':mode,'name':str(value.get('name') or MODES[mode])[:80],'enabled':bool(value.get('enabled',True))}
        if mode!='quick':entry['origin']=normalize_origin(value.get('origin',''))
        if mode=='server':
            target=str(value.get('sshTarget') or '')
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@:-]{0,199}',target):raise ValueError('填写已配置的 SSH 主机别名或 user@host')
            port=int(value.get('remotePort',18787))
            if not 1024<=port<=65535:raise ValueError('SSH 远端端口需要在 1024–65535 之间')
            entry.update(sshTarget=target,remotePort=port)
        if mode=='tailscale':
            https_port=int(value.get('httpsPort',443));scope=value.get('scope','serve')
            if https_port not in (443,8443,10000) or scope not in ('serve','funnel'):raise ValueError('永久连接 模式或端口无效')
            entry.update(httpsPort=https_port,scope=scope,nodeId=str(value.get('nodeId',''))[:128])
        with self.lock:
            if self.runtime.get(identifier,{}).get('state') not in (None,'stopped','failed'):raise ValueError('请先停止此入口再修改连接配置')
            entries=[row for row in self.entries if row['id']!=identifier]
            if any(row.get('origin')==entry.get('origin') and entry.get('origin') for row in entries):raise ValueError('这个地址已存在')
            if mode=='cloudflare':
                token=str(value.get('token') or '').strip()
                with self.workspace.lock:
                    secrets=self.workspace.secrets.read();tokens=secrets.setdefault('entrances',{})
                    if token:tokens[identifier]=token;self.workspace.secrets.write(secrets)
                    if not tokens.get(identifier):raise ValueError('请填写 Cloudflare Tunnel Token')
            entries.append(entry);write_json(self.path,entries);self.entries=entries
        return entry

    def remove(self,identifier):
        self.stop(identifier)
        with self.lock:
            self.entries=[row for row in self.entries if row['id']!=identifier];write_json(self.path,self.entries);self.runtime.pop(identifier,None)
        with self.workspace.lock:
            value=self.workspace.secrets.read();value.get('entrances',{}).pop(identifier,None);self.workspace.secrets.write(value)

    def _entry(self,identifier):
        with self.lock:
            return dict(next(row for row in self.entries if row['id']==identifier))

    def start_enabled(self):
        for entry in self.entries:
            if entry.get('enabled'):self.start(entry['id'])

    def start(self,identifier):
        entry=self._entry(identifier)
        with self.lock:
            if self.closed:return
            if self.runtime.get(identifier,{}).get('state') in ('connecting','connected'):return
            generation=self.generations[identifier]=self.generations.get(identifier,0)+1
            self.runtime[identifier]={'state':'connecting','message':'正在建立入口…','origin':entry.get('origin',''),'link':None}
        def work():
            link=None
            def begin(candidate):
                nonlocal link
                link=candidate
                with self.lock:
                    if self.closed or self.generations.get(identifier)!=generation:return False
                    self.runtime[identifier]['link']=link
                    (self.directory/('ssh-status-'+identifier+'.json')).unlink(missing_ok=True)
                    return True
            try:
                mode=entry['mode'];origin=entry.get('origin','')
                if mode=='quick':
                    from .temporary_link import TemporaryLink
                    if not begin(TemporaryLink(self.server.server_port,self.directory,self.assets,self.server.allow_origin)):return
                    origin=link.start()
                elif mode=='cloudflare':
                    from .temporary_link import TemporaryLink
                    from .ingress_cloudflare import NamedTunnel
                    binary=TemporaryLink(self.server.server_port,self.directory,self.assets,self.server.allow_origin).verified_binary()
                    with self.workspace.lock:token=self.workspace.secrets.read().get('entrances',{}).get(identifier,'')
                    if not begin(NamedTunnel(binary,{'id':identifier},self.directory,token)):return
                    link.start()
                elif mode=='server':
                    from .ingress_ssh import SSHTunnel
                    if not begin(SSHTunnel(entry['sshTarget'],entry['remotePort'],self.server.server_port,self.directory,identifier)):return
                    link.start()
                elif mode=='tailscale':
                    from .ingress_tailscale import TailscaleTunnel
                    config={'id':identifier,'publicUrl':origin,'tailscaleMode':entry['scope'],'tailscalePort':entry['httpsPort'],'tailscaleNodeId':entry['nodeId']}
                    if not begin(TailscaleTunnel(config,self.server.server_port,self.directory)):return
                    link.start()
                with self.lock:
                    if self.closed or self.generations.get(identifier)!=generation:
                        if link:link.close()
                        return
                    self.server.allow_origin(origin)
                    self.runtime[identifier]={'state':'connected' if mode in ('external','quick') else 'connecting','message':'入口已配置，请检测地址是否指向当前电脑','origin':origin,'link':link}
            except Exception as exc:
                if link:link.close()
                with self.lock:
                    if self.generations.get(identifier)==generation:self.runtime[identifier]={'state':'failed','message':str(exc)[:250],'origin':entry.get('origin',''),'link':None}
        self.pool.submit(work)

    def stop(self,identifier):
        with self.lock:
            self.generations[identifier]=self.generations.get(identifier,0)+1
            row=self.runtime.get(identifier,{})
            link=row.get('link');origin=row.get('origin')
            self.runtime[identifier]={'state':'stopped','message':'入口已停止','origin':origin or '', 'link':None}
        if link:link.close()
        if origin:
            with self.server.pairing.lock:
                for key,value in tuple(self.server.pairing.codes.items()):
                    if value['origin']==origin:self.server.pairing.revoke_grant(value.get('id'))
        # Phone login remains valid; stopping a transport never revokes its device.

    def snapshot(self):
        with self.lock:
            rows=[]
            for entry in self.entries:
                state=dict(self.runtime.get(entry['id'],{'state':'stopped','message':'尚未启动'}));state.pop('link',None)
                if state['state'] not in ('stopped','failed') and entry['mode'] not in ('quick','external'):
                    path=self.directory/('ssh-status-'+entry['id']+'.json')
                    try:state.update({key:value for key,value in json.loads(path.read_text('utf-8')).items() if key in ('state','message','authUrl')})
                    except (OSError,ValueError):pass
                rows.append({**entry,**state,'label':MODES[entry['mode']]})
            return rows

    @staticmethod
    def inspect_tailscale(scope='serve',port=443):
        from .ingress_tailscale import inspect
        return inspect({'tailscaleMode':scope,'tailscalePort':port})

    def check(self,origin):
        origin=normalize_origin(origin,private_http=True,allow_loopback=True);started=time.monotonic()
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs):return None
        try:
            request=urllib.request.Request(origin+'/api/gateway',headers={'Accept':'application/json','User-Agent':'coding-remote/'+VERSION})
            handlers=[NoRedirect]
            if origin.startswith('http:'):handlers.append(urllib.request.ProxyHandler({}))
            with urllib.request.build_opener(*handlers).open(request,timeout=5) as response:value=json.loads(response.read(16001))
            if not isinstance(value,dict) or value.get('computerId')!=self.server.pairing.computer_id or value.get('service')!='coding-remote' and value.get('name')!='coding远程':raise ValueError('入口未指向当前电脑助手，请将转发目标设为网关端口 '+str(self.server.server_port))
        except ValueError:raise
        except Exception:raise ValueError('入口暂不可达，请检查网络、证书和转发配置') from None
        return {'origin':origin,'latencyMs':round((time.monotonic()-started)*1000),'state':'connected','message':'已核验为当前电脑网关'}

    def addresses(self):
        with self.lock:
            values=[row['origin'] for row in self.runtime.values() if row.get('origin') and row.get('state')!='stopped']
        if self.server.server_address[0]=='0.0.0.0':
            try:values.extend('http://'+address+':'+str(self.server.server_port) for address in socket.gethostbyname_ex(socket.gethostname())[2] if not address.startswith('127.'))
            except OSError:pass
        return list(dict.fromkeys(values))

    def close(self):
        self.closed=True
        for identifier in tuple(self.runtime):self.stop(identifier)
        self.pool.shutdown(wait=False,cancel_futures=True)
