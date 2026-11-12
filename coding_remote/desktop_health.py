"""Incremental, bounded log tails and entrance handshake measurements."""
import os
from pathlib import Path
import re
import socket
import ssl
import time
from urllib.parse import urlsplit


class LogReader:
    def __init__(self):
        self.positions={};self.lines=[]
    def read(self, directory, query='', level='', module='', limit=2000, since='', until=''):
        for name in ('application.log','claude-keyboard.log','temporary-link.log','ssh-tunnel-server.log'):
            path=Path(directory)/name
            if not path.exists():continue
            stat=path.stat();old=self.positions.get(name,(0,0))
            position=old[1] if old[0]==stat.st_ino and old[1]<=stat.st_size else max(0,stat.st_size-256000)
            with path.open('rb') as stream:
                stream.seek(position);blob=stream.read(256000);position=stream.tell()
            self.positions[name]=(stat.st_ino,position)
            for line in blob.decode('utf-8','replace').splitlines():self.lines.append((name,line))
        self.lines=self.lines[-10000:]
        def in_time(line):
            stamp=re.search(r'\d{4}-\d\d-\d\d[ T]\d\d:\d\d:\d\d',line)
            if not since and not until:return True
            if not stamp:return False
            value=stamp.group().replace('T',' ')
            return (not since or (value[11:] if len(since)<=8 else value)>=since.replace('T',' ')) and (not until or (value[11:] if len(until)<=8 else value)[:len(until)]<=until.replace('T',' '))
        rows=[name+' | '+line for name,line in self.lines if (not module or module==name) and (not level or level in line) and query.casefold() in line.casefold() and in_time(line)]
        return '\n'.join(rows[-limit:]) or '暂无匹配日志'


class ConnectionHealth:
    def __init__(self):self.rows={}
    def probe(self,name,origin):
        start=time.monotonic();previous=self.rows.get(name,{})
        value={'name':name,'origin':origin,'latencyMs':None,'lastSuccess':previous.get('lastSuccess'),'failures':previous.get('failures',0),'error':''}
        try:
            target=urlsplit(origin);host=target.hostname
            if not host:raise ValueError('入口未就绪')
            with socket.create_connection((host,target.port or (443 if target.scheme=='https' else 80)),timeout=3) as connection:
                if target.scheme=='https':
                    with ssl.create_default_context().wrap_socket(connection,server_hostname=host):pass
            elapsed=round((time.monotonic()-start)*1000)
            value.update(latencyMs=elapsed,lastSuccess=time.time(),failures=0,quality='正常' if elapsed<800 else '延迟')
        except Exception as exc:value.update(failures=value['failures']+1,quality='重连中' if origin else '离线',error=type(exc).__name__)
        value['cooldownUntil']=time.time()+min(30,2**min(value['failures'],4)) if value['failures'] else 0
        self.rows[name]=value
        return dict(value)

