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
        self.creates = {}
        owner = self
        class Bridge:
            store = types.SimpleNamespace(get=lambda sid: {'cwd': str(owner.root)})
            live = types.SimpleNamespace(condition=threading.RLock(), action_lock=threading.Lock(), state={'cwd': str(owner.root), 'latestThreadSettings': {'sandboxPolicy': {'type': 'readOnly'}}})
            def list(self, **kwargs): return [{'id': owner.sid, 'cwd': str(owner.root)}]
            def view(self, sid, **kwargs): return {'id': sid, 'cwd': str(owner.root), 'turns': []}
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
        listing = self.features.files('codex', self.sid, {})
        self.assertEqual(listing['absolutePath'],str(self.root.resolve()))
        self.assertTrue(all(Path(e['absolutePath']).is_absolute() for e in listing['entries']))
        self.assertIn('result.md', [e['name'] for e in listing['entries']])
        preview = self.features.file('codex', self.sid, {'path': ['result.md']})
        self.assertEqual(preview['text'], '完成\n')
        self.assertEqual(base64.b64decode(preview['data']).decode(), '完成\n')
        self.assertEqual(self.features.file('codex', self.sid, {'path': ['result.png']})['mime'], 'image/png')
        with self.assertRaises(ValueError): self.features.file('codex', self.sid, {'path': ['../outside.txt']})
        with self.assertRaises(ValueError): self.features.files('codex', self.sid, {'path': ['../../']})

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
