"""Mobile projections from actual conversation content, before transport paging."""
import json


def summarize(value):
    messages = value.get('messages', [])
    turns = set()
    steps = []
    added = deleted = 0
    files = set()
    for index, row in enumerate(messages):
        if row.get('role') == 'user':
            turns.add(row.get('turnId') or row.get('eventId') or row.get('id') or str(index))
        if 'tasks' in row:
            steps = row['tasks']
        if row.get('kind') == 'code':
            body = str(row.get('text', ''))
            if row.get('language') == 'diff':
                added += sum(line.startswith('+') and not line.startswith('+++') for line in body.splitlines())
                deleted += sum(line.startswith('-') and not line.startswith('---') for line in body.splitlines())
            elif row.get('changeType') == 'write':
                added += len(body.splitlines())
            if body:
                files.update(row.get('paths') or [row.get('title') or row.get('id')])
    session = value.get('session', {})
    session = {**session, 'turnCount': session.get('turnCount', len(turns))}
    if session.get('status') not in ('active','running','inProgress') and not session.get('completedTurnId'):
        session['completedTurnId']=str(next((m.get('turnId') or m.get('eventId') or m.get('id') for m in reversed(messages)), '') or '')
    # Keep complete changes available in the detail object, outside ordinary chat rows.
    changes = {'added': added, 'deleted': deleted, 'files': len(files)}
    return {**value, 'session': session, 'steps': value.get('steps', steps),
            'changes': value.get('changes', changes)}


def validate_answers(questions, supplied):
    if not isinstance(supplied, dict):
        raise ValueError('请填写提问的答案')
    result = {}
    for index, question in enumerate(questions):
        key = str(question.get('id') or question.get('question') or index)
        value = supplied.get(key)
        if isinstance(value, dict): value = value.get('answers')
        if isinstance(value, str): value = [value]
        if not isinstance(value, list) or not value or not all(isinstance(x, str) and 0 < len(x.strip()) <= 20000 for x in value):
            raise ValueError('请回答每一项提问')
        if len(value) > 30: raise ValueError('选择项过多')
        result[key] = {'answers': value}
    if not result: raise ValueError('当前提问没有可填写的问题')
    return result


def codex_response(permission, body):
    method = permission.get('method', '')
    params = permission.get('input') or {}
    if method in ('item/tool/requestUserInput', 'tool/requestUserInput') or params.get('questions'):
        answers=validate_answers(params.get('questions', []), body.get('answers'))
        # Bridge.respond adds the protocol's {answers:[...]} wrapper itself.
        return {'answers': {key:value['answers'] for key,value in answers.items()}}
    if method == 'mcpServer/elicitation/request':
        content = body.get('content')
        if body.get('decision') == 'decline': return {'action': 'decline', 'content': None}
        if not isinstance(content, dict): raise ValueError('请填写表单')
        return {'action': 'accept', 'content': content}
    decision = body.get('decision')
    if decision not in ('accept', 'decline'): raise ValueError('仅支持本次允许或拒绝')
    if permission.get('needsInput'): raise ValueError('当前桌面提问格式暂不支持，请在电脑处理')
    return {'decision': decision}
