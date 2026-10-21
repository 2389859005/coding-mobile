import base64
import json
import sqlite3
import tempfile
import threading
import types
import unittest
import uuid
from pathlib import Path

from coding_remote.api import NativeServer, codex_detail, message_window
from coding_remote.native_features import NativeFeatures
from coding_remote.native_content import permission_settings
from coding_remote.pairing import Pairing
from claude_bridge import model


class FeatureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sid = str(uuid.uuid4())
        self.opened = []
        self.sent = []
        self.calls = []
        self.views = []
        self.creates = {}
        owner = self
        class Bridge:
            store = types.SimpleNamespace(get=lambda sid: {'cwd': str(owner.root)})
            live = types.SimpleNamespace(condition=threading.RLock(), action_lock=threading.Lock(), state={'cwd': str(owner.root), 'latestThreadSettings': {'sandboxPolicy': {'type': 'readOnly'}}})
            def list(self, **kwargs): return [{'id': owner.sid, 'cwd': str(owner.root)}]
            def view(self, sid, **kwargs): owner.views.append(sid); return {'id': sid, 'cwd': str(owner.root), 'turns': []}
            def open_chat(self, sid): owner.opened.append(sid); return {'ok': True}
            def session(self, sid, **kwargs): return self.live
            def _target(self, sid): return self.live
            def _call(self, target, method, params): owner.calls.append((method, params)); return {'ok': True}
            def send(self, sid, text, rid, mode, skills, images): owner.sent.append((sid, text, images)); return {'status': 'accepted'}
            def projects(self): return [{'key': 'local:project', 'cwd': str(owner.root), 'host': 'local', 'name': 'Fixture'}]
            def start_creation(self, project, title, rid):
                owner.creates.setdefault(rid, {'requestId': rid, 'status': 'ready', 'result': {'id': owner.sid, 'title': title}})
                return owner.creates[rid]
            def creation_status(self, rid, **kwargs): return owner.creates[rid]
        self.server = NativeServer(Bridge(), types.SimpleNamespace(), Pairing(self.root), self.root)
        self.features = self.server.features

    def tearDown(self):
        self.server.server_close()
        self.temp.cleanup()

    def test_open_selects_exact_thread_only_on_explicit_open(self):
        other = str(uuid.uuid4())
        self.server.dispatch('codex', self.sid, None, {})
        self.server.dispatch('codex', other, 'open', {})
        self.server.dispatch('codex', other, None, {})
        self.assertEqual(self.opened, [other])

    def test_message_animation_revision_ignores_token_counts_and_settings(self):
        path=self.root/'messages.jsonl'
        self.server.bridge.store._rollout_path=lambda row:path
        first={'type':'response_item','payload':{'type':'message','role':'assistant','content':[{'text':'First'}]}}
        path.write_text(json.dumps(first)+'\n','utf-8')
        original=self.features.codex_message_revision(self.sid)
        with path.open('a',encoding='utf-8') as stream:
            stream.write(json.dumps({'type':'event_msg','payload':{'type':'token_count','tokens':50}})+'\n')
            stream.write(json.dumps({'type':'turn_context','payload':{'model':'new'}})+'\n')
        self.assertEqual(self.features.codex_message_revision(self.sid),original)
        with path.open('a',encoding='utf-8') as stream:
            stream.write(json.dumps({'type':'response_item','payload':{'type':'function_call_output','output':'Step finished'}})+'\n')
            stream.write(json.dumps({'type':'response_item','payload':{'type':'reasoning','text':'Thinking'}})+'\n')
            stream.write(json.dumps({'type':'response_item','payload':{'type':'message','role':'assistant','channel':'commentary','content':[{'text':'Running tests'}]}})+'\n')
            stream.write(json.dumps({'type':'event_msg','payload':{'type':'agent_reasoning','text':'Thinking'}})+'\n')
        self.assertEqual(self.features.codex_message_revision(self.sid),original)
        with path.open('a',encoding='utf-8') as stream:
            stream.write(json.dumps({'type':'response_item','payload':{'type':'message','role':'assistant','channel':'final','content':[{'text':'Done'}]}})+'\n')
        self.assertNotEqual(self.features.codex_message_revision(self.sid),original)

    def test_staged_image_send_is_scoped_and_deduplicated(self):
        png = b'\x89PNG\r\n\x1a\nfixture'
        body = {'mime': 'image/png', 'data': base64.b64encode(png).decode()}
        image = self.features.upload('codex', self.sid, body)
        self.assertEqual(self.features.upload('codex', self.sid, body)['id'], image['id'])
        request = {'id': str(uuid.uuid4()), 'text': '', 'images': [image['id']]}
        self.server.dispatch('codex', self.sid, 'send', request)
        self.server.dispatch('codex', self.sid, 'send', request)
        self.assertEqual(len(self.sent), 1)
        self.assertTrue(self.sent[0][2][0]['url'].startswith('data:image/png;base64,'))
        with self.assertRaises(ValueError): self.features.images('codex', str(uuid.uuid4()), [image['id']])
        with self.assertRaises(ValueError): self.features.images('claude', self.sid, [image['id']])
        with self.assertRaises(ValueError): self.features.images('codex', self.sid, ['../../secret'])
        with self.assertRaises(ValueError): self.features.upload('codex', self.sid, {**body, 'mime': 'image/jpeg'})

    def test_permissions_use_current_rpc_and_original_receipt(self):
        self.assertEqual(self.features.access('codex', self.sid)['mode'], 'read-only')
        body = {'id': str(uuid.uuid4()), 'mode': 'workspace-write'}
        self.assertEqual(self.server.dispatch('codex', self.sid, 'access', body)['status'], 'accepted')
        self.server.dispatch('codex', self.sid, 'access', body)
        self.assertEqual(len(self.calls), 1)
        method, params = self.calls[0]
        self.assertEqual(method, 'thread-follower-update-thread-settings')
        self.assertEqual(params['threadSettings']['sandboxPolicy']['writableRoots'], [str(self.root)])
        with self.assertRaises(ValueError): permission_settings('arbitrary', str(self.root))
        with self.assertRaises(ValueError): permission_settings('workspace-write', '')

    def test_directory_reads_never_rebuild_the_session_detail(self):
        (self.root / 'listing.md').write_bytes('x'.encode('utf-8'))
        listing = self.features.files('codex', self.sid, {})
        self.assertEqual(listing['absolutePath'], str(self.root.resolve()))
        self.assertIn('listing.md', [e['name'] for e in listing['entries']])
        self.assertEqual(self.features.file('codex', self.sid, {'path': ['listing.md']})['text'], 'x')
        self.assertEqual(self.views, [], 'directory and file reads must not attach a session')

    def test_create_uses_the_requested_cwd_and_reports_a_mismatch(self):
        class Claude:
            desktop = types.SimpleNamespace(capabilities={'code': ['start']})
            def __init__(self, actual): self.actual = actual; self.project = actual
            def create_session(self, project, title, rid):
                self.requested = project['cwd']
                return {'requestId': rid, 'status': 'ready', 'result': {'id': 'session-x', 'title': title, 'cwd': project['cwd']}}
            def session(self, sid): return {'id': sid, 'cwd': self.actual, 'projectCwd': self.project}
            def run(self, value):
                if value == 'create': return {'requestId': 'r', 'status': 'ready', 'result': {'id': 'session-x', 'title': 'T', 'cwd': 'D:/wanted'}}
                return value() if callable(value) else value
        for actual, expected in (('D:/wanted', True), ('D:/somewhere-else', False)):
            features = object.__new__(NativeFeatures)
            claude = Claude(actual)
            features.server = types.SimpleNamespace(claude=claude)
            features.projects = lambda backend: {'projects': [{'key': 'code|D:/wanted', 'cwd': 'D:/wanted', 'surface': 'code', 'internal': False}]}
            created = features.create('claude', {'id': str(uuid.uuid4()), 'projectKey': 'code|D:/wanted', 'cwd': 'D:/wanted', 'title': 'T', 'mode': 'code'})
            self.assertTrue(created['cwdVerified'] is expected, actual)
            if not expected: self.assertEqual(created['actualCwd'], 'D:/somewhere-else')
        # A worktree session belongs to the project even though it works elsewhere.
        features = object.__new__(NativeFeatures)
        claude = Claude('D:/wanted/.claude/worktrees/branch')
        claude.project = 'D:/wanted'
        features.server = types.SimpleNamespace(claude=claude)
        features.projects = lambda backend: {'projects': [{'key': 'code|D:/wanted', 'cwd': 'D:/wanted', 'surface': 'code', 'internal': False}]}
        self.assertTrue(features.create('claude', {'id': str(uuid.uuid4()), 'projectKey': 'code|D:/wanted', 'cwd': 'D:/wanted', 'mode': 'code'})['cwdVerified'])
        # A stale key still resolves by directory, so the user is never blocked.
        features = object.__new__(NativeFeatures)
        features.server = types.SimpleNamespace(claude=Claude('D:/wanted'))
        features.projects = lambda backend: {'projects': [{'key': 'code|D:/wanted', 'cwd': 'D:/wanted', 'surface': 'code', 'internal': False}]}
        self.assertTrue(features.create('claude', {'id': str(uuid.uuid4()), 'projectKey': 'stale', 'cwd': 'D:/wanted', 'mode': 'code'})['cwdVerified'])

    def test_claude_projects_group_by_the_project_directory(self):
        # The session may work in a worktree while still belonging to one project.
        rows = [{'id': 'work', 'backend': 'code', 'cwd': str(self.root / 'worktree'), 'projectCwd': str(self.root / 'project'),
                 'title': 'T', 'status': 'idle', 'archived': False, 'updatedAt': 3, 'requests': []}]
        self.server.claude = types.SimpleNamespace(listing=lambda: None, run=lambda value: value if isinstance(value, list) else (rows, []), workspace='')
        projects = self.features.projects('claude')['projects']
        self.assertEqual([p['cwd'] for p in projects], [str(self.root / 'project')], 'grouped by the project, not the session directory')
        self.assertTrue(projects[0]['key'].endswith(str(self.root / 'project')))

    def test_claude_projects_mark_the_bridge_workspace_internal_and_last(self):
        rows = [{'id': 'mine', 'backend': 'code', 'cwd': str(self.root), 'title': 'Mine', 'status': 'idle', 'archived': False, 'updatedAt': 9, 'requests': []},
                {'id': 'adapter', 'backend': 'code', 'cwd': str(self.root / 'bridge'), 'title': 'Adapter', 'status': 'idle', 'archived': False, 'updatedAt': 1, 'requests': []}]
        self.server.claude = types.SimpleNamespace(listing=lambda: None, run=lambda value: value if isinstance(value, list) else (rows, []), workspace=str(self.root / 'bridge'))
        projects = self.features.projects('claude')['projects']
        self.assertEqual([p['internal'] for p in projects], [False, True])
        self.assertEqual(projects[-1]['cwd'], str(self.root / 'bridge'))

    def test_status_reads_one_transcript_and_reports_running_progress(self):
        reads = []
        events = [{'uuid': 'e', 'type': 'assistant', 'message': {'role': 'assistant', 'content': [{'type': 'text', 'text': '正在读取项目结构'}]}}]
        row = {'id': 'claude-1', 'backend': 'code', 'nativeId': 'native-1', 'title': 'Claude task', 'status': 'active',
               'archived': False, 'updatedAt': 5, 'cwd': str(self.root), 'requests': []}
        class Claude:
            index = {'claude-1': row}
            desktop = types.SimpleNamespace(call=lambda *args, **kwargs: reads.append(args) or events)
            def listing(self): return None
            def run(self, value): return ([row], []) if value is None else value
        bridge = types.SimpleNamespace(task_statuses=lambda **kwargs: {'tasks': []})
        features = object.__new__(NativeFeatures)
        features.server = types.SimpleNamespace(bridge=bridge, claude=Claude(), deepseek=types.SimpleNamespace(call=lambda *args, **kwargs: {'sessions': []}))
        features.status_lock = threading.Lock(); features.status_cache = (0, {}); features.reply_revisions = {}
        status = features.task_status()
        task = next(t for t in status['tasks'] if t['id'] == 'claude-1')
        self.assertEqual(task['progress'], '正在读取项目结构')
        self.assertTrue(task['messageRevision'])
        self.assertEqual(len(reads), 1, 'revision and progress come from a single transcript read')

    def test_create_and_query_use_same_receipt(self):
        body = {'id': str(uuid.uuid4()), 'projectKey': 'local:project', 'title': 'New task'}
        first = self.features.create('codex', body)
        self.assertEqual(first, self.features.create('codex', body))
        self.assertEqual(first, self.features.creation('codex', body['id']))
        self.assertEqual(len(self.creates), 1)
        with self.assertRaises(ValueError): self.features.create('codex', {**body, 'projectKey': 'foreign'})

    def test_file_listing_preview_and_traversal_rejection(self):
        (self.root / 'result.md').write_bytes('完成\n'.encode('utf-8'))
        (self.root / 'result.png').write_bytes(b'\x89PNG\r\n\x1a\n')
        (self.root / 'big.txt').write_bytes(('行\n' * 90000).encode('utf-8'))
        listing = self.features.files('codex', self.sid, {})
        self.assertEqual(listing['absolutePath'],str(self.root.resolve()))
        self.assertTrue(all(Path(e['absolutePath']).is_absolute() for e in listing['entries']))
        self.assertIn('result.md', [e['name'] for e in listing['entries']])
        preview = self.features.file('codex', self.sid, {'path': ['result.md']})
        self.assertEqual(preview['text'], '完成\n')
        self.assertEqual(base64.b64decode(preview['data']).decode(), '完成\n', 'a small text file keeps its bytes for saving')
        # A large text preview must not pay for a second, unused base64 copy.
        bulk = self.features.file('codex', self.sid, {'path': ['big.txt']})
        self.assertNotIn('data', bulk)
        self.assertTrue(bulk['text'])
        self.assertIn('data', self.features.file('codex', self.sid, {'path': ['big.txt'], 'raw': ['1']}), 'the bytes stay available on request')
        self.assertEqual(self.features.file('codex', self.sid, {'path': ['result.png']})['mime'], 'image/png')
        with self.assertRaises(ValueError): self.features.file('codex', self.sid, {'path': ['../outside.txt']})
        with self.assertRaises(ValueError): self.features.files('codex', self.sid, {'path': ['../../']})

    def test_listing_and_preview_follow_the_directory_the_session_uses(self):
        session_dir = self.root / 'session'; project_dir = self.root / 'project'
        session_dir.mkdir(); project_dir.mkdir()
        (session_dir / 'inside.md').write_bytes(b'x')
        (project_dir / 'elsewhere.md').write_bytes(b'y')
        self.server.bridge.store.get = lambda sid: {'cwd': str(session_dir), 'projectCwd': str(project_dir)}
        listing = self.features.files('codex', self.sid, {'path': ['']})
        self.assertIn('inside.md', [e['name'] for e in listing['entries']])
        # A path that only exists in the project directory still resolves there.
        self.assertEqual(self.features.file('codex', self.sid, {'path': ['elsewhere.md']})['text'], 'y')

    def test_activity_token_is_empty_while_running_or_waiting(self):
        self.server.bridge.store.get = lambda sid: {'cwd': str(self.root), 'status': 'idle', 'updatedAt': 7}
        self.assertEqual(self.features.activity_token('codex', self.sid), 'idle:7')
        self.server.bridge.store.get = lambda sid: {'cwd': str(self.root), 'status': 'active', 'updatedAt': 7}
        self.assertEqual(self.features.activity_token('codex', self.sid), '', 'a running session always reads fully')
        self.server.bridge.store.get = lambda sid: {'cwd': str(self.root), 'status': 'idle', 'updatedAt': 7, 'requests': [{'id': 'q'}]}
        self.assertEqual(self.features.activity_token('codex', self.sid), '', 'a pending request always reads fully')

    def test_an_unchanged_poll_is_answered_without_a_transcript(self):
        self.server.bridge.store.get = lambda sid: {'cwd': str(self.root), 'status': 'idle', 'updatedAt': 5}
        answer = self.features.unchanged_answer('codex', self.sid, 'idle:5')
        self.assertTrue(answer['unchanged'])
        self.assertEqual(answer['token'], 'idle:5')
        self.assertIsNone(self.features.unchanged_answer('codex', self.sid, 'idle:4'), 'a changed token forces a full read')
        self.assertIsNone(self.features.unchanged_answer('codex', self.sid, ''), 'no token means the client wants a full read')

    def test_queued_messages_drain_when_the_session_signature_never_changes(self):
        # Claude reports no turn id, so the gate must open on its own after a while.
        from coding_remote.task_queue import TaskQueue
        import time as clock
        with tempfile.TemporaryDirectory() as d:
            sends = []
            session = {'status': 'idle', 'updatedAt': 1}
            def dispatch(backend, sid, action, body):
                if action is None: return {'session': dict(session), 'connected': True}
                sends.append(body.get('text')); return {'status': 'accepted'}
            server = types.SimpleNamespace(dispatch=dispatch, features=types.SimpleNamespace(images=lambda *args: []))
            queue = TaskQueue(server, d)
            try:
                for text in ('first', 'second'):
                    queue.request('claude', 's', {'id': str(uuid.uuid4()), 'operation': 'enqueue', 'message': {'text': text}})
                queue.tick()
                self.assertEqual(sends, ['first'])
                gate = queue.db.execute('SELECT signature FROM gates').fetchone()
                queue.db.execute('UPDATE gates SET signature=?', (queue.gate_signature(gate['signature']) + '|' + str(clock.time()-30),))
                queue.db.commit()
                queue.tick()
                self.assertEqual(sends, ['first', 'second'])
                self.assertEqual(queue.listing('claude', 's'), [])
            finally: queue.close()

    def test_agents_are_scoped_to_exact_parent(self):
        database=self.root/'agents.sqlite'
        with sqlite3.connect(database) as conn:
            conn.execute('CREATE TABLE threads (id TEXT, title TEXT, agent_nickname TEXT, source TEXT, rollout_path TEXT)')
            conn.execute('INSERT INTO threads VALUES (?,?,?,?,?)',('child','Worker','Reviewer',json.dumps({'subagent':{'spawn':{'parent_thread_id':self.sid}}}),'fixture'))
            conn.execute('INSERT INTO threads VALUES (?,?,?,?,?)',('unrelated','Worker','Other',json.dumps({'subagent':{'spawn':{'parent_thread_id':str(uuid.uuid4())}},'mention':self.sid}),'fixture'))
        conn.close()
        def connect():
            connection=sqlite3.connect(database);connection.row_factory=sqlite3.Row;return connection
        self.server.bridge.store._connect=connect
        self.server.bridge.store.latest_task_event=lambda row:{'type':'task_complete'}
        self.assertEqual(self.features.agents(self.sid)['agents'],[{'id':'child','name':'Reviewer','status':'completed'}])


class StructuredContentTests(unittest.TestCase):
    def test_packaged_codex_normalized_diff_is_code(self):
        detail = codex_detail({'id': 's', 'turns': [{'id': 't', 'messages': [
            {'id': 'm', 'role': 'activity', 'kind': 'fileChange', 'text': 'app.py\n-old\n+new'}]}]})
        row = message_window(detail)['messages'][0]
        self.assertEqual((row['kind'], row['language'], row['turnId']), ('code', 'diff', 't'))
        self.assertIn('+new', row['text'])

    def test_long_fence_keeps_language_and_prose_outside_code(self):
        code = 'print("hello")\n' * 500
        source = {'messages': [{'id': 'm', 'role': 'assistant', 'text': 'before\n```python\n'+code+'```\nafter'}]}
        rows = message_window(source)['messages']
        snippets = [r for r in rows if r.get('kind') == 'snippet']
        self.assertGreater(len(snippets), 1)
        self.assertTrue(all(r['language'] == 'python' for r in snippets))
        self.assertEqual(''.join(r['text'] for r in snippets), code.rstrip('\n'))
        self.assertEqual(rows[-1]['text'], 'after')

    def test_claude_steps_edits_results_and_ids_are_stable(self):
        event = {'uuid': 'event', 'type': 'assistant', 'message': {'role': 'assistant', 'content': [
            {'type': 'text', 'text': 'Working'},
            {'type': 'tool_use', 'name': 'TodoWrite', 'input': {'todos': [{'content': 'Read code', 'status': 'completed'}]}},
            {'type': 'tool_use', 'name': 'Edit', 'input': {'file_path': 'a.py', 'old_string': 'old', 'new_string': 'new'}},
            {'type': 'text', 'text': 'Done'}]}}
        rows = model.transcript([event])
        self.assertTrue(any('✓ Read code' in r['text'] for r in rows))
        self.assertTrue(any(r.get('kind') == 'code' and '+new' in r['text'] for r in rows))
        prefixed = model.transcript([{'uuid': 'earlier', 'type': 'user', 'message': {'role': 'user', 'content': 'Before'}}, event])
        self.assertEqual(rows, prefixed[1:])


if __name__ == '__main__': unittest.main()
