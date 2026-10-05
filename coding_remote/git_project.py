"""Git operations scoped to the repository of an authenticated local session."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import threading
import uuid


class GitProjects:
    def __init__(self, directory):
        self.lock = threading.RLock()
        self.path = Path(directory)/'git-requests.sqlite'
        self.db = None

    def close(self):
        with self.lock:
            if self.db is not None:self.db.close();self.db=None

    @staticmethod
    def run(root, args, timeout=20, check=True):
        env = {**os.environ, 'GIT_TERMINAL_PROMPT':'0', 'GCM_INTERACTIVE':'never'}
        result = subprocess.run(['git', '--no-pager', '--literal-pathspecs', '-c', 'color.ui=false', '-C', str(root), *args],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, env=env,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if check and result.returncode:
            raise ValueError((result.stderr or result.stdout).decode('utf-8', 'replace').strip()[:2000] or 'Git 操作失败')
        if len(result.stdout)>8*1024*1024:raise ValueError('Git 输出过大，请在电脑查看')
        return result.stdout.decode('utf-8','replace'), result.returncode

    def root(self, cwd):
        path=Path(cwd).resolve()
        if not path.is_dir():raise ValueError('当前会话的项目目录不可用')
        value,_=self.run(path,['rev-parse','--show-toplevel'])
        return Path(value.strip()).resolve()

    def files(self, root):
        raw,_=self.run(root,['status','--porcelain=v1','-z','--untracked-files=normal'])
        records=raw.split('\0');files=[];i=0
        while i<len(records) and records[i]:
            record=records[i];i+=1;code=record[:2];path=record[3:];original=None
            if 'R' in code or 'C' in code:
                original=records[i];i+=1
            files.append({'path':path,'original':original,'index':code[0],'worktree':code[1],
                          'untracked':code=='??','conflict':code in ('DD','AU','UD','UA','DU','AA','UU')})
        return files

    def snapshot(self, root):
        branch,_=self.run(root,['symbolic-ref','--short','-q','HEAD'],check=False)
        head,head_code=self.run(root,['rev-parse','--verify','HEAD'],check=False)
        tracking,_=self.run(root,['rev-parse','--abbrev-ref','--symbolic-full-name','@{upstream}'],check=False)
        counts,_=self.run(root,['rev-list','--left-right','--count','HEAD...@{upstream}'],check=False)
        numbers=counts.split();ahead,behind=(map(int,numbers) if len(numbers)==2 else (0,0))
        branches,_=self.run(root,['for-each-ref','--format=%(refname:short)','refs/heads'])
        commits=[]
        if not head_code:
            history,_=self.run(root,['log','--all','--topo-order','-80','--format=%H%x00%P%x00%D%x00%s%x00%an%x00%aI%x00'])
            fields=history.split('\0')
            for i in range(0,len(fields)-6,6):
                commits.append(dict(zip(('hash','parents','refs','subject','author','date'),[fields[i].strip(),*fields[i+1:i+6]])))
            for commit in commits:commit['parents']=commit['parents'].split()
        key=hashlib.sha256(os.path.normcase(str(root)).encode()).hexdigest()[:24]
        return {'projectKey':key,'root':str(root),'branch':branch.strip() or ('分离 HEAD' if not head_code else '未提交'),
                'head':head.strip() if not head_code else '', 'upstream':tracking.strip(),'ahead':ahead,'behind':behind,
                'files':self.files(root),'commits':commits,'branches':branches.splitlines()}

    def diff(self, root, path, staged=False):
        rows=self.files(root);row=next((r for r in rows if r['path']==path),None)
        if row is None:raise ValueError('文件已无更改，请刷新')
        if row['untracked']:
            target=(root/path).resolve()
            if not target.is_relative_to(root) or not target.is_file():raise ValueError('请在电脑查看该目录或链接')
            with target.open('rb') as stream:data=stream.read(200001)
            content='二进制文件' if b'\0' in data else ''.join('+'+line+'\n' for line in data.decode('utf-8','replace').splitlines())
        else:
            content,_=self.run(root,['diff','--no-ext-diff','--no-textconv',*(['--cached'] if staged else []),'--',path])
        return {'path':path,'staged':staged,'diff':content[:200000],'truncated':len(content)>200000}

    def mutate(self, root, body):
        rid=str(uuid.UUID(body.get('id','')));operation=body.get('operation')
        if operation not in ('stage','unstage','commit','fetch','pull','push','branch','switch'):raise ValueError('不支持的 Git 操作')
        fingerprint=hashlib.sha256(json.dumps([str(root),body],sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        with self.lock:
            if self.db is None:
                self.db=sqlite3.connect(self.path,check_same_thread=False)
                self.db.execute('CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY, fingerprint TEXT, result TEXT)');self.db.commit()
            old=self.db.execute('SELECT fingerprint,result FROM requests WHERE id=?',(rid,)).fetchone()
            if old:
                if old[0]!=fingerprint:raise ValueError('请求编号已用于其他 Git 操作')
                return json.loads(old[1])
            unknown={'id':rid,'status':'unknown','message':'Git 操作结果待核对；请刷新，不会自动重复执行'}
            self.db.execute('INSERT INTO requests VALUES(?,?,?)',(rid,fingerprint,json.dumps(unknown)));self.db.commit()
            try:
                if operation in ('stage','unstage'):
                    rows=self.files(root);paths=body.get('paths')
                    if not isinstance(paths,list) or not paths or len(paths)>1000:raise ValueError('请选择文件')
                    available={r['path']:r for r in rows}
                    if any(not isinstance(p,str) or p not in available for p in paths):raise ValueError('文件列表已变化，请刷新')
                    targets=list(dict.fromkeys(p for path in paths for p in [path,available[path].get('original')] if p))
                    if operation=='stage':args=['add','--',*targets]
                    elif self.run(root,['rev-parse','--verify','HEAD'],check=False)[1]==0:args=['reset','-q','HEAD','--',*targets]
                    else:args=['rm','--cached','-r','--ignore-unmatch','--',*targets]
                elif operation=='commit':
                    message=body.get('message','')
                    if not isinstance(message,str) or not message.strip() or len(message)>8000:raise ValueError('请输入提交说明（最多 8000 字符）')
                    args=['commit','-m',message]
                elif operation in ('branch','switch'):
                    name=body.get('branch','')
                    if not isinstance(name,str) or not name or name.startswith('-'):raise ValueError('分支名称无效')
                    self.run(root,['check-ref-format','--branch',name])
                    args=['switch','-c',name] if operation=='branch' else ['switch','--',name]
                else:
                    if body.get('confirmed') is not True:raise ValueError('请确认 Git 远程操作')
                    args={'fetch':['fetch','--prune'],'pull':['pull','--ff-only'],'push':['push']}[operation]
                output,_=self.run(root,args,timeout=90)
                result={'id':rid,'status':'accepted','message':output.strip()[:2000] or 'Git 操作完成'}
            except (ValueError,FileNotFoundError) as error:result={'id':rid,'status':'rejected','message':str(error)}
            except (subprocess.TimeoutExpired,OSError):result=unknown
            self.db.execute('UPDATE requests SET result=? WHERE id=?',(json.dumps(result),rid));self.db.commit()
            return result
