import hashlib
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
            self.pending = {'token': token, 'expires': time.time()+300, 'origin': origin,'mode':mode}
            self.codes={k:v for k,v in self.codes.items() if v['expires']>time.time() and v['mode']!=mode}
            self.codes[token]=self.pending
            if mode=='temporary':self.temporary_origins.add(origin)
            return json.dumps({'app': 'coding-remote', 'v': 1, 'origin': origin,
                               'mode':mode,'computerId':self.computer_id,'token': token, 'expires': self.pending['expires']}, separators=(',', ':'))

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

    def pair(self, token, label, address, metadata=None, request_id=None):
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
                fingerprint=hashlib.sha256(json.dumps([label,info],sort_keys=True).encode()).hexdigest()
                old=self.receipts.get(receipt_key)
                if old:
                    if old['fingerprint']!=fingerprint:raise PermissionError('配对请求信息不一致')
                    if not self.authenticate(old['result']['token']):raise PermissionError('设备连接已撤销，请重新扫码')
                    return dict(old['result'])
            recent = [t for t in self.failures.get(address, []) if now-t < 60]
            if len(recent) >= 10:
                raise PermissionError('配对尝试过多，请稍后重试')
            pending=self.codes.get(token) if isinstance(token,str) else None
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
