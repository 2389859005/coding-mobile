import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
import subprocess
from coding_remote.git_project import GitProjects


class GitTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)/'repo';self.root.mkdir()
        self.git=GitProjects(Path(self.temp.name))
        self.git.run(self.root,['init','-b','main'])
        self.git.run(self.root,['config','user.name','Fixture'])
        self.git.run(self.root,['config','user.email','fixture@example.invalid'])
        self.git.run(self.root,['config','core.autocrlf','false'])
    def tearDown(self):self.git.close();self.temp.cleanup()
    def action(self,operation,**body):return self.git.mutate(self.root,{'id':str(uuid.uuid4()),'operation':operation,**body})
    def test_stage_diff_commit_graph_and_shared_project_root(self):
        path='文件 [draft].txt';(self.root/path).write_text('first\n',encoding='utf-8')
        self.assertEqual(self.git.snapshot(self.root)['files'][0]['path'],path)
        self.assertIn('+first',self.git.diff(self.root,path)['diff'])
        self.assertEqual(self.action('stage',paths=[path])['status'],'accepted')
        self.assertIn('+first',self.git.diff(self.root,path,True)['diff'])
        self.assertEqual(self.action('commit',message='first commit')['status'],'accepted')
        page=self.git.snapshot(self.root);self.assertEqual(page['commits'][0]['subject'],'first commit');self.assertFalse(page['files'])
        (self.root/'sub').mkdir();self.assertEqual(self.git.snapshot(self.git.root(self.root/'sub'))['projectKey'],page['projectKey'])
        self.assertEqual(self.action('branch',branch='feature/mobile')['status'],'accepted')
        self.assertEqual(self.git.snapshot(self.root)['branch'],'feature/mobile')
        self.assertEqual(self.action('switch',branch='main')['status'],'accepted')
    def test_unstage_initial_commit_retains_file_and_literal_path(self):
        (self.root/'-option.txt').write_text('keep')
        self.assertEqual(self.action('stage',paths=['-option.txt'])['status'],'accepted')
        self.assertEqual(self.action('unstage',paths=['-option.txt'])['status'],'accepted')
        self.assertEqual((self.root/'-option.txt').read_text(),'keep')
        self.assertEqual(self.action('stage',paths=['../outside'])['status'],'rejected')
        self.assertEqual(self.action('branch',branch='--detach')['status'],'rejected')
    def test_receipt_prevents_duplicate_commit_and_timeout_replay(self):
        (self.root/'file').write_text('x');self.action('stage',paths=['file'])
        body={'id':str(uuid.uuid4()),'operation':'commit','message':'once'}
        first=self.git.mutate(self.root,body);self.assertEqual(first['status'],'accepted')
        self.assertEqual(self.git.mutate(self.root,body),first)
        self.assertEqual(len(self.git.snapshot(self.root)['commits']),1)
        body={'id':str(uuid.uuid4()),'operation':'fetch','confirmed':True}
        with patch.object(self.git,'run',side_effect=subprocess.TimeoutExpired('git',90)) as run:
            self.assertEqual(self.git.mutate(self.root,body)['status'],'unknown')
            self.assertEqual(self.git.mutate(self.root,body)['status'],'unknown');run.assert_called_once()
    def test_remote_requires_confirmation_and_pull_is_fast_forward_only(self):
        self.assertEqual(self.action('push')['status'],'rejected')
        with patch.object(self.git,'run',return_value=('',0)) as run:
            self.assertEqual(self.action('pull',confirmed=True)['status'],'accepted')
            self.assertEqual(run.call_args.args[1],['pull','--ff-only'])
