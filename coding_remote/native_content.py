"""Keep the structured parts of desktop transcripts available to native clients."""
import json
import re
from urllib.parse import unquote,urlsplit


def image_paths(message):
    """Accept explicit image-view arguments, including orchestration wrappers."""
    name=re.sub(r'[^a-z0-9]','', ' '.join(str(message.get(k,'')) for k in ('title','name','kind','type')).lower())
    source=text(message.get('text'))
    if not any(n in name for n in ('viewimage','imageview')) and not re.search(r'(?:view_image|imageview|image_view|viewImage)\s*\(',source):return []
    values=[]
    def visit(value):
        if isinstance(value,dict):
            for k,v in value.items():
                if k in ('path','image_path','file_path','localPath','imagePath') and isinstance(v,str):values.append(v)
                elif isinstance(v,(dict,list)):visit(v)
        elif isinstance(value,list):
            for v in value:visit(v)
    visit(message)
    try:
        parsed=json.loads(source);visit(parsed)
        if isinstance(parsed,dict) and isinstance(parsed.get('code'),str):source=parsed['code']
    except (ValueError,TypeError):pass
    # tools.view_image({path:"..."}) inside a JavaScript orchestration cell.
    for match in re.finditer(r'(?:["\']?(?:path|image_path|file_path)["\']?\s*:\s*)("(?:\\.|[^"\\])*"|\'[^\']*\')',source):
        raw=match.group(1)
        try:values.append(json.loads(raw) if raw.startswith('"') else raw[1:-1].replace('\\\\','\\'))
        except ValueError:pass
    if any(n in name for n in ('viewimage','imageview')):
        values += re.findall(r'(?im)(?:^|[\s`"\'])((?:[A-Z]:[/\\]|/)[^\r\n`"\'<>]*?\.(?:png|jpe?g|webp|gif|bmp))(?=$|[\s`"\'])',source)
    out=[]
    for value in values:
        if value.startswith('file:'):
            value=unquote(urlsplit(value).path)
            if re.match(r'^/[A-Za-z]:/',value):value=value[1:]
        if re.search(r'\.(png|jpe?g|webp|gif|bmp)$',value,re.I) and value not in out:out.append(value)
    return out


def text(value):
    if isinstance(value, str): return value
    if value is None: return ''
    return json.dumps(value, ensure_ascii=False, indent=2)


def codex_messages(value):
    rows = []
    for turn in value.get('turns', []):
        tid = str(turn.get('id', ''))
        for index, message in enumerate(turn.get('messages', [])):
            kind = message.get('kind', message.get('type', ''))
            if kind in ('commandExecution', 'exec_command', 'bash', 'terminalCommand'):
                continue
            base = {'id': str(message.get('id') or f'{tid}:{index}'), 'turnId': tid,
                    'role': message.get('role', 'activity'), 'kind': kind,
                    'title': message.get('title', ''), 'status': message.get('status', '')}
            body = text(message.get('text'))
            images=image_paths(message)
            if images:
                for n,path in enumerate(images):rows.append({**base,'id':base['id']+':image:'+str(n),'kind':'image','role':'activity','title':'查看图片','text':'![图片](<'+path+'>)'})
                continue
            for attachment in message.get('attachments') or []:
                if not isinstance(attachment,dict):continue
                path=attachment.get('path') or attachment.get('localPath') or attachment.get('url')
                if isinstance(path,str) and re.search(r'\.(png|jpe?g|webp|gif|bmp)$',path,re.I) and not re.match(r'^[a-z]+://',path,re.I):
                    body+='\n![图片](<'+path+'>)'
            if not body and message.get('attachments'): body = '[图片附件]'
            if kind in ('storedToolEvent','mcpToolCall','dynamicToolCall'):
                name=str(message.get('title') or message.get('name') or '').lower()
                if name.endswith(('view_image','image')):
                    try:params=json.loads(body)
                    except (ValueError,TypeError):params={}
                    path=params.get('path') if isinstance(params,dict) else None
                    if isinstance(path,str):rows.append({**base,'kind':'image','title':'图片','text':'![图片](<'+path+'>)'})
                elif name.endswith(('update_plan','plan')):
                    try:params=json.loads(body)
                    except (ValueError,TypeError):params={}
                    plan=params.get('plan') if isinstance(params,dict) else None
                    if isinstance(plan,list):
                        tasks=[{'content':p.get('step',''),'status':p.get('status','pending')} for p in plan if isinstance(p,dict)]
                        rows.append({**base,'kind':'step','title':'任务步骤','text':'\n'.join(p['content'] for p in tasks),'tasks':tasks})
                elif name.endswith('apply_patch') and not any(m.get('kind')=='fileChange' for m in turn.get('messages',[])):
                    try:
                        params=json.loads(body)
                        patch=params.get('patch',params.get('input',body)) if isinstance(params,dict) else body
                    except (ValueError,TypeError):patch=body
                    paths=re.findall(r'^\*\*\* (?:Update|Add|Delete) File: (.+)$',patch,re.M)
                    if paths:rows.append({**base,'kind':'code','title':paths[0],'paths':paths,'language':'diff','text':patch})
                # Desktop command and tool wrappers are transport details, not chat replies.
                continue
            if kind == 'fileChange':
                changes = message.get('changes')
                if changes is None:
                    try: changes = json.loads(body)
                    except (ValueError, TypeError): changes = None
                if isinstance(changes, dict):
                    changes = [{'path': path, **(change if isinstance(change, dict) else {'diff': str(change)})} for path, change in changes.items()]
                if isinstance(changes, list):
                    for i, change in enumerate(changes):
                        if not isinstance(change, dict): continue
                        patch = change.get('diff') or change.get('patch') or change.get('unified_diff')
                        content = patch or change.get('newContent') or change.get('content')
                        if content is not None:
                            path = change.get('path') or change.get('filePath') or '文件变更'
                            rows.append({**base, 'id': base['id']+f':file:{i}', 'kind': 'code',
                                         'title': path, 'language': 'diff' if patch else path.rsplit('.', 1)[-1], 'text': text(content)})
                    if any(r['id'].startswith(base['id']+':file:') for r in rows): continue
                # The packaged Bridge normalizes file changes as "path\ndiff".
                # It no longer retains the original changes array.
                if body:
                    rows.append({**base, 'kind': 'code', 'title': body.split('\n', 1)[0] or '文件变更',
                                 'language': 'diff', 'text': body})
                    continue
            if message.get('output'): body += '\n'+text(message['output'])
            if body: rows.append({**base, 'text': body, **({'tasks': message['plan']} if isinstance(message.get('plan'), list) else {})})
        if turn.get('diff') and not any(r.get('turnId') == tid and r.get('kind') == 'code' for r in rows):
            rows.append({'id': tid+':diff', 'turnId': tid, 'role': 'activity', 'kind': 'code',
                         'title': '本轮代码变更', 'language': 'diff', 'text': text(turn['diff'])})
    return rows


def fenced_messages(messages):
    """Split fenced Markdown before paging so long code never becomes plain prose."""
    for index, message in enumerate(messages):
        source = str(message.get('text') or '')
        if message.get('role') != 'assistant' or message.get('kind') in ('code', 'snippet') or '```' not in source:
            yield message; continue
        lines = source.splitlines(keepends=True); body = []; language = None; block = 0
        def part():
            return {**message, 'id': str(message.get('id') or index)+':block:'+str(block),
                    'text': ''.join(body).rstrip('\n') if language is not None else ''.join(body),
                    **({'kind': 'snippet', 'language': language} if language is not None else {})}
        for line in lines:
            if line.startswith('```'):
                if body: yield part(); block += 1; body = []
                language = line[3:].strip() if language is None else None
            else: body.append(line)
        if body: yield part()


def permission_selection(settings):
    policy = settings.get('sandboxPolicy') or settings.get('sandbox') or {}
    kind = policy.get('type') if isinstance(policy, dict) else policy
    return {'dangerFullAccess': 'full-access', 'danger-full-access': 'full-access',
            'workspaceWrite': 'workspace-write', 'workspace-write': 'workspace-write',
            'readOnly': 'read-only', 'read-only': 'read-only'}.get(kind, 'desktop')


def permission_settings(mode, cwd):
    modes = {'read-only': {'type': 'readOnly', 'networkAccess': False},
             'workspace-write': {'type': 'workspaceWrite', 'writableRoots': [cwd] if cwd else [],
                                 'networkAccess': False, 'excludeSlashTmp': False, 'excludeTmpdirEnvVar': False},
             'full-access': {'type': 'dangerFullAccess'}}
    if mode not in modes: raise ValueError('请选择有效的访问权限')
    if mode == 'workspace-write' and not cwd: raise ValueError('当前会话没有工作目录')
    return {'sandboxPolicy': modes[mode], 'approvalPolicy': 'never' if mode == 'full-access' else 'on-request',
            'approvalsReviewer': 'user', 'runtimeWorkspaceRoots': [cwd] if cwd else [],
            'permissions': None, 'activePermissionProfile': {'id': {'read-only': ':read-only', 'workspace-write': ':workspace', 'full-access': ':danger-full-access'}[mode], 'extends': None}}


def referenced_paths(messages):
    paths = []
    for row in messages:
        source = row.get('text', '')
        for match in re.finditer(r'!?\[[^\]\n]*\]\((?:<([^>]+)>|([^\n)]+))\)', source):
            value = match.group(1) or match.group(2)
            if not re.match(r'^[a-z]+://', value, re.I): paths.append(re.sub(r':\d+$', '', value))
        if row.get('kind') == 'code' and row.get('title'): paths.append(row['title'])
    return list(dict.fromkeys(paths))
