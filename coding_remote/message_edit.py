"""Edit an existing user message through its desktop owner, with a stale-history guard."""
import hashlib
import json


def draft(detail, message_id):
    if detail.get('session', {}).get('status') in ('active', 'running', 'inProgress'):
        raise ValueError('请先停止当前任务，再撤回编辑')
    messages = detail.get('messages', [])
    target = next((row for row in messages if row.get('id') == message_id and row.get('role') == 'user'), None)
    if not target:
        raise ValueError('原消息已变化或不可用，请刷新会话后重试')
    event = target.get('eventId')
    parts = [row for row in messages if row.get('eventId') == event and row.get('role') == 'user'] if event else [target]
    if event and any(row.get('hasAttachments') or row.get('kind') == 'image' for row in parts):
        raise ValueError('含图片附件的 Claude 消息请在电脑端编辑，以保留原附件')
    original = '\n'.join(row.get('text', '') for row in parts)
    if not original or len(original) > 100000:
        raise ValueError('此消息暂不能在手机编辑')
    signature = [[row.get('id'), row.get('role'), row.get('text')] for row in messages]
    return {'text': original, 'messageId': message_id, 'eventId': event or '',
            'turnId': target.get('turnId', ''),
            'editBase': hashlib.sha256(json.dumps(signature, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()}


def validate(detail, body):
    current = draft(detail, body.get('messageId'))
    if body.get('confirmed') is not True or body.get('editBase') != current['editBase']:
        raise ValueError('会话在编辑期间发生了变化，请重新长按原消息编辑')
    text = body.get('text')
    if not isinstance(text, str) or not text.strip() or len(text) > 100000:
        raise ValueError('请输入修改后的消息，最多 100000 字')
    if body.get('images'):
        raise ValueError('编辑消息时暂不支持添加新附件')
    return current


def codex_edit(server, sid, body):
    current = validate(server.dispatch('codex', sid, None, {}), body)
    if not current['turnId']:
        raise ValueError('原消息缺少轮次标识，请刷新后重试')
    target = server.bridge._target(sid)
    # The desktop owns rollback and regeneration together, preserving the original
    # input attachments, cwd, provider and permission settings.
    result = server.bridge._call(target, 'thread-follower-edit-last-user-turn', {
        'turnId': current['turnId'], 'message': body['text'],
        'allowEarlierMessages': True, 'shouldSendPermissionOverrides': False,
    }, timeout=90)
    if not isinstance(result, dict) or result.get('ok') is not True:
        raise RuntimeError('桌面尚未确认消息编辑，请核对原会话')
    return {'status': 'accepted', 'id': body['id']}
