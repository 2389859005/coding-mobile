import hashlib
import hmac
import json
import secrets
import threading
import time
from pathlib import Path


class Pairing:
    def __init__(self, directory):
        self.path = Path(directory)/'devices.json'
        self.lock = threading.RLock()
        self.pending = None
        self.codes = {}
        self.temporary_origins = set()
        identity=Path(directory)/'computer-id.txt'
        if not identity.exists():identity.write_text(secrets.token_hex(16),'utf-8')
        self.computer_id=identity.read_text('utf-8').strip()
        self.failures = {}
        self.receipts = {}
        self.grant_states = {}
        self.login_path=Path(directory)/'gateway-login.json'
        try:
            self.devices = json.loads(self.path.read_text('utf-8'))
        except FileNotFoundError:
            self.devices = {}

    def save(self):
        temp = self.path.with_suffix('.tmp')
        temp.write_text(json.dumps(self.devices, ensure_ascii=False), encoding='utf-8')
        temp.replace(self.path)

    def issue(self, origin, mode=None):
        with self.lock:
            mode=mode or ('permanent' if origin.startswith('http://100.') else 'temporary')
            if mode not in ('temporary','permanent'):raise ValueError('连接模式无效')
            token = secrets.token_urlsafe(32)
            self.pending = {'id':secrets.token_hex(16),'token': token, 'expires': time.time()+300, 'origin': origin,'mode':mode}
            self.codes={k:v for k,v in self.codes.items() if v['expires']>time.time() and v['origin']!=origin}
            self.codes[token]=self.pending
            if mode=='temporary':self.temporary_origins.add(origin)
            return json.dumps({'app': 'coding-remote', 'v': 1, 'origin': origin,
                               'mode':mode,'computerId':self.computer_id,'token': token, 'expires': self.pending['expires']}, separators=(',', ':'))

    def grant(self,origin):
        """Match the reference gateway: a per-entry, five-minute sign-in URL."""
        from .gateway_ingress import normalize_origin
        origin=normalize_origin(origin,private_http=True)
        with self.lock:
            self.grant_states={key:row for key,row in self.grant_states.items() if row['expires']+300>time.time()}
            for row in self.grant_states.values():
                if row['origin']==origin and row['state']=='active':row['state']='revoked'
            value=json.loads(self.issue(origin,'permanent'));identifier=self.pending['id']
            self.grant_states[identifier]={'origin':origin,'expires':value['expires'],'state':'active'}
            return {'id':identifier,'url':origin+'/#pair='+value['token'],'origin':origin,'expires':value['expires'],'state':'active'}

    def grant_status(self,identifier):
        with self.lock:
            row=self.grant_states.get(identifier)
            return {'state':'expired' if not row or row['expires']<=time.time() else row['state'],'expires':row['expires'] if row else 0}

    def revoke_grant(self,identifier):
        with self.lock:
            self.codes={key:value for key,value in self.codes.items() if value.get('id')!=identifier}
            if identifier in self.grant_states:self.grant_states[identifier]['state']='revoked'

    def configure_login(self,username,password):
        username=str(username).strip()
        if not 1<=len(username)<=80 or any(ord(char)<32 for char in username):raise ValueError('请输入有效的网关用户名')
        if not isinstance(password,str) or not 8<=len(password)<=256:raise ValueError('网关密码需要 8–256 个字符')
        salt=secrets.token_hex(16);digest=hashlib.pbkdf2_hmac('sha256',password.encode(),bytes.fromhex(salt),260000).hex()
        value={'username':username,'salt':salt,'digest':digest,'iterations':260000}
        with self.lock:
            temporary=self.login_path.with_suffix('.tmp');temporary.write_text(json.dumps(value),'utf-8');temporary.chmod(0o600);temporary.replace(self.login_path)
        return {'configured':True,'username':username}

    def login_settings(self):
        try:value=json.loads(self.login_path.read_text('utf-8'));return {'configured':True,'username':value['username']}
        except (OSError,ValueError,KeyError):return {'configured':False,'username':''}

    def login(self,username,password,origin,label,address,metadata=None,request_id=None):
        from .gateway_ingress import normalize_origin
        origin=normalize_origin(origin,private_http=True,allow_loopback=True)
        if not isinstance(password,str) or len(password)>256:raise PermissionError('用户名或密码错误')
        with self.lock:
            now=time.time();recent=[stamp for stamp in self.failures.get(address,[]) if now-stamp<60]
            if len(recent)>=8:raise PermissionError('登录尝试过多，请稍后重试')
            self.failures[address]=recent+[now]
            try:value=json.loads(self.login_path.read_text('utf-8'))
            except (OSError,ValueError):raise PermissionError('请先在电脑网关配置登录密码')
            digest=hashlib.pbkdf2_hmac('sha256',password.encode(),bytes.fromhex(value['salt']),value['iterations']).hex()
            if not hmac.compare_digest(str(username).encode(),value['username'].encode()) or not hmac.compare_digest(digest,value['digest']):raise PermissionError('用户名或密码错误')
            self.failures.pop(address,None)
            # Use the same durable, revocable phone credential and request receipt.
            if request_id:
                import uuid
                request_id=str(uuid.UUID(request_id))
            receipt=hashlib.sha256(json.dumps(['login',request_id,username,origin,label,metadata],sort_keys=True).encode()).hexdigest()
            old=self.receipts.get(receipt)
            if request_id and old and old['expires']>now and self.authenticate(old['result']['token']):return dict(old['result'])
            code=secrets.token_urlsafe(32)
            self.codes[code]={'token':code,'expires':now+30,'origin':origin,'mode':'permanent'}
            result=self.pair(code,label,address,metadata,origin=origin)
            if request_id:self.receipts[receipt]={'expires':now+300,'result':result}
            return result

    def clear_code(self):
        with self.lock:
            self.pending = None
            self.codes.clear()

    def retain_temporary(self,origin):
        """Keep already paired temporary credentials when a route drops on its own.

        Revoking them turns every tunnel hiccup into a re-scan. The credential
        still expires by itself, and the user can still close the connection.
        """
        with self.lock:
            self.codes={k:v for k,v in self.codes.items() if v['origin']!=origin}

    def close_temporary(self,origin):
        with self.lock:
            self.temporary_origins.discard(origin)
            self.codes={k:v for k,v in self.codes.items() if v['origin']!=origin}
            self.devices={k:v for k,v in self.devices.items() if not(v.get('mode')=='temporary' and v.get('origin')==origin)}
            self.save()

    def clear_mode(self,mode):
        with self.lock:
            self.codes={k:v for k,v in self.codes.items() if v['mode']!=mode}
            if self.pending and self.pending['mode']==mode:self.pending=None

    def clear_origin(self,origin):
        """Stopping one entrance preserves other entrances' pending QR codes."""
        with self.lock:
            self.codes={k:v for k,v in self.codes.items() if v['origin']!=origin}
            if self.pending and self.pending['origin']==origin:self.pending=None

    def remember_server_origin(self, origin):
        """Upgrade legacy temporary grants for a verified, configured SSH entry.

        Only the SSH supervisor calls this after checking the computer identity.
        Expired or revoked credentials are never brought back to life.
        """
        with self.lock:
            changed=False
            for value in self.devices.values():
                expires=value.get('expires')
                if (value.get('origin')==origin and value.get('mode')=='temporary'
                        and (expires is None or expires>time.time())):
                    value.update(mode='permanent',expires=None)
                    changed=True
            if changed:self.save()

    def pair(self, token, label, address, metadata=None, request_id=None,origin=None):
        with self.lock:
            now = time.time()
            # Short-lived in-memory receipt only; permanent device secrets are
            # still stored as hashes. A lost HTTPS response can be recovered.
            self.receipts={k:v for k,v in self.receipts.items() if v['expires']>now}
            info = metadata if isinstance(metadata, dict) else {}
            receipt_key=None
            if request_id is not None:
                import uuid
                request_id=str(uuid.UUID(request_id))
                if not isinstance(token,str):raise ValueError('配对令牌无效')
                receipt_key=hashlib.sha256((token+request_id).encode()).hexdigest()
                fingerprint=hashlib.sha256(json.dumps([label,info,origin],sort_keys=True).encode()).hexdigest()
                old=self.receipts.get(receipt_key)
                if old:
                    if old['fingerprint']!=fingerprint:raise PermissionError('配对请求信息不一致')
                    if not self.authenticate(old['result']['token']):raise PermissionError('设备连接已撤销，请重新扫码')
                    return dict(old['result'])
            recent = [t for t in self.failures.get(address, []) if now-t < 60]
            if len(recent) >= 10:
                raise PermissionError('配对尝试过多，请稍后重试')
            pending=self.codes.get(token) if isinstance(token,str) else None
            if pending and pending.get('id') in self.grant_states and pending['origin']!=origin:raise PermissionError('配对码不属于当前入口，请使用电脑显示的完整地址')
            if (not pending or pending['expires'] <= now):
                self.failures[address] = recent + [now]
                raise PermissionError('二维码已过期或已使用，请刷新电脑上的二维码')
            secret = secrets.token_urlsafe(32)
            key = hashlib.sha256(secret.encode()).hexdigest()
            device = {'id': secrets.token_hex(8), 'name': str(label or 'Android')[:80],
                      'expires': now+86400 if pending['mode']=='temporary' else None, 'pairedAt': now, 'lastSeen': now,
                      'mode':pending['mode'],'origin':pending['origin'],'computerId':self.computer_id,
                      'installationId': str(info.get('installationId', ''))[:80],
                      'manufacturer': str(info.get('manufacturer', ''))[:80],
                      'model': str(info.get('model', ''))[:80], 'android': str(info.get('android', ''))[:32]}
            self.devices[key] = device
            try:
                self.save()
            except OSError:
                self.devices.pop(key, None)
                raise
            self.codes.pop(token,None)
            if pending.get('id') in self.grant_states:self.grant_states[pending['id']]['state']='used'
            if self.pending is pending:self.pending = None
            result={'token': secret, 'deviceId': device['id'], 'expires': device['expires'],'mode':device['mode'],'computerId':self.computer_id}
            if receipt_key:self.receipts[receipt_key]={'expires':now+300,'fingerprint':fingerprint,'result':result}
            return result

    def authenticate(self, token):
        if not isinstance(token, str) or len(token) > 256:
            return None
        with self.lock:
            value = self.devices.get(hashlib.sha256(token.encode()).hexdigest())
            if not value or (value.get('expires') is not None and value.get('expires', 0) <= time.time()):
                return None
            if value.get('mode')=='temporary' and value.get('origin') not in self.temporary_origins:return None
            if time.time()-value.get('lastSeen', 0) > 60:
                value['lastSeen'] = time.time()
                self.save()
            return dict(value)

    def list_devices(self):
        with self.lock:
            return [dict(v) for v in self.devices.values()
                    if (v.get('expires') is None or v.get('expires',0)>time.time())
                    and (v.get('mode')!='temporary' or v.get('origin') in self.temporary_origins)]

    def grouped_devices(self):
        """A physical installation has several independent connection credentials."""
        grouped = {}
        for device in self.list_devices():
            key = device.get('installationId') or device['id']
            if key not in grouped:
                grouped[key] = {**device, 'credentialIds': [], 'modes': []}
            row = grouped[key]
            row['credentialIds'].append(device['id'])
            if device.get('mode') not in row['modes']:
                row['modes'].append(device.get('mode'))
            row['lastSeen'] = max(row.get('lastSeen', 0), device.get('lastSeen', 0))
        return list(grouped.values())

    def revoke(self, device_id):
        with self.lock:
            self.devices = {k:v for k,v in self.devices.items() if v['id'] != device_id}
            self.save()
