"""DeepSeek Harness adapter using an allowlisted, local Host plugin."""
import hashlib
import difflib
import json
import os
import secrets
import shutil
import threading
import urllib.request
import urllib.error
import uuid
from pathlib import Path
from claude_bridge.errors import BridgeUnavailable


class DeepSeek:
    def __init__(self, directory, home=None):
        self.directory=Path(directory)/'deepseek';self.directory.mkdir(parents=True,exist_ok=True)
        self.config=self.directory/'connection.json';self.endpoint=self.directory/'endpoint.json'
        self.home=Path(home or os.environ.get('DSH_HOME',Path.home()/'.dsh'))
        self.lock=threading.Lock()
        if not self.config.exists():
            self.config.write_text(json.dumps({'token':secrets.token_urlsafe(32),'endpoint':str(self.endpoint)}),'utf-8')
        self.token=json.loads(self.config.read_text('utf-8'))['token']

    def install(self):
        patch=self.home/'profiles/desktop/cordis.patch.yml'
        if not patch.is_file():return False
        target=self.directory/'mobile-host.mjs'
        source=Path(__file__).with_name('deepseek-host.mjs')
        if not target.exists() or target.read_bytes()!=source.read_bytes():shutil.copyfile(source,target)
        marker='# coding-remote-assistant bridge'
        text=patch.read_text('utf-8')
        if marker not in text:
            # Append one loader insertion; preserve the user's entire patch layer verbatim.
            shutil.copyfile(patch,self.directory/'cordis.patch.before-mobile.yml')
            entry={'insert':[{'id':'coding-remote-mobile','name':target.as_uri(),'config':{'configPath':str(self.config)}}]}
            suffix='\n'+marker+'\n- '+json.dumps(entry,ensure_ascii=False)+'\n'
            temp=patch.with_suffix('.mobile.tmp');temp.write_text(text+suffix,'utf-8');temp.replace(patch)
        return True

    def call(self, action, sid=None, body=None):
        try:
            port=json.loads(self.endpoint.read_text('utf-8'))['port']
            if not isinstance(port,int) or not 1<=port<=65535:raise ValueError()
            payload=json.dumps({'action':action,'sid':sid,'body':body or {}}).encode()
            request=urllib.request.Request(f'http://127.0.0.1:{port}/mobile',data=payload,headers={'Authorization':'Bearer '+self.token,'Content-Type':'application/json'})
            # Local adapter traffic must never pass through system HTTP proxies.
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(request,timeout=30) as response:
                raw=response.read(32*1024*1024+1)
                if len(raw)>32*1024*1024:raise ValueError('DeepSeek 会话过大')
                result=json.loads(raw)
            if action=='detail':result=self.detail(result)
            return result
        except urllib.error.HTTPError as exc:
            try:message=json.loads(exc.read(4096)).get('error','DeepSeek 操作失败')
            except (ValueError,KeyError):message='DeepSeek 操作失败'
            raise ValueError(message) from exc
        except (OSError,KeyError) as exc:
            raise BridgeUnavailable('DeepSeek Harness 未连接，请启动 Harness；首次接入后需重新打开 Harness。') from exc

    @staticmethod
    def detail(value):
        messages=[];turn=0;steps=value.get('steps',[]);calls={}
        for record in value.pop('records',[]):
            event=record.get('event',{});kind=event.get('type');data=event.get('data') or {};eid=str(event.get('seq'))
            if kind=='turn/start':turn+=1
            elif kind in ('assistant/message','user/message','tool/result'):
                message=data.get('message',data);content=message.get('content',[])
                if isinstance(content,str):content=[{'type':'text','text':content}]
                for part,block in enumerate(content if isinstance(content,list) else []):
                    if not isinstance(block,dict):continue
                    text=block.get('text') or block.get('thinking') or ('[图片]' if block.get('type')=='image' else '')
                    if not text:continue
                    role='activity' if kind=='tool/result' else 'user' if kind=='user/message' else 'assistant'
                    thought=block.get('type') in ('reasoning','thinking')
                    messages.append({'id':eid+':'+str(part),'eventId':eid,'turnId':str(turn),'role':'reasoning' if thought else role,'kind':'reasoning' if thought else 'step' if role=='activity' else '', 'title':'思考摘要' if thought else '执行结果' if role=='activity' else '', 'text':text})
                if kind=='tool/result' and not message.get('isError') and not data.get('error'):
                    meta=data.get('meta') or {};diffs=meta.get('diffs',[]) if isinstance(meta,dict) else []
                    call=calls.get(str((message.get('source') or {}).get('callId') or message.get('toolCallId')),{});args=call.get('arguments',{})
                    if not diffs and isinstance(args,dict):
                        name=call.get('name')
                        if name=='write' and meta.get('operation')=='create':diffs=[{'path':args.get('file_path'),'oldText':None,'newText':args.get('content')}]
                        elif name=='str_replace_editor' and args.get('command') in ('create','str_replace','insert'):
                            diffs=[{'path':args.get('path'),'oldText':args.get('old_str'),'newText':args.get('file_text',args.get('new_str',''))}]
                    for index,diff in enumerate(diffs if isinstance(diffs,list) else []):
                        if not isinstance(diff,dict) or not isinstance(diff.get('path'),str) or not isinstance(diff.get('newText'),str):continue
                        old=diff.get('oldText') or '';new=diff['newText']
                        if not isinstance(old,str):continue
                        patch='\n'.join(difflib.unified_diff(old.splitlines(),new.splitlines(),fromfile=diff['path'],tofile=diff['path'],lineterm=''))
                        if patch:messages.append({'id':eid+':diff:'+str(index),'turnId':str(turn),'role':'activity','kind':'code','title':diff['path'],'language':'diff','text':patch})
            elif kind=='tool/call':
                name=data.get('name','工具');args=data.get('arguments','')
                try:parsed=json.loads(args) if isinstance(args,str) else args
                except ValueError:parsed={}
                calls[str(data.get('callId'))]={'name':name,'arguments':parsed}
                messages.append({'id':eid,'turnId':str(turn),'role':'activity','kind':'step','title':name,'text':args if isinstance(args,str) else json.dumps(args,ensure_ascii=False,indent=2)})
            elif kind=='todo/write':steps=data.get('todos',[])
        return {**value,'session':{**value['session'],'turnCount':turn},'messages':messages,'steps':steps}

    def receipt(self,rid,body=None):
        path=self.directory/('request-'+str(uuid.UUID(rid))+'.json')
        if body is None:return json.loads(path.read_text('utf-8')) if path.is_file() else {'status':'missing'}
        temp=path.with_suffix('.tmp');temp.write_text(json.dumps(body),'utf-8');temp.replace(path)
