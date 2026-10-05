import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from coding_remote.embedded_network import EmbeddedNetwork


class EmbeddedNetworkStateTests(unittest.TestCase):
    def network(self, state):
        network=EmbeddedNetwork.__new__(EmbeddedNetwork)
        network.status=MagicMock(return_value=state)
        network.request=MagicMock()
        return network

    def test_slow_saved_identity_is_not_forced_to_log_in_or_waited_on(self):
        state={'state':'NoState','authUrl':''}
        network=self.network(state)
        with patch('coding_remote.embedded_network.time.sleep') as sleep:
            self.assertEqual(network.login(),state)
            sleep.assert_not_called()
        network.request.assert_not_called()
        network.status.return_value={'state':'Running','origin':'http://100.80.0.1:18787'}
        self.assertEqual(network.login()['state'],'Running')
        network.request.assert_not_called()

    def test_no_state_auth_url_is_usable_without_restarting_login(self):
        state={'state':'NoState','authUrl':'https://login.tailscale.com/a/fixture'}
        network=self.network(state)
        self.assertEqual(network.login_url(network.login()),state['authUrl'])
        network.request.assert_not_called()

    def test_login_requests_are_coalesced_while_auth_url_is_pending(self):
        network=self.network({'state':'NeedsLogin','authUrl':''})
        with patch('coding_remote.embedded_network.time.monotonic',return_value=100):
            network.login();network.login()
        network.request.assert_called_once_with('/login',True)
        network.status.return_value={'state':'NeedsLogin','authUrl':'https://login.tailscale.com/a/fixture'}
        with patch('coding_remote.embedded_network.time.monotonic',return_value=200):network.login()
        self.assertEqual(network.request.call_count,1)

    def test_auth_url_is_scoped_and_never_reopened_once_running(self):
        for url in ['http://login.tailscale.com/a','https://login.tailscale.com.example/a','https://x@login.tailscale.com/a','https://login.tailscale.com:8080/a','https://login.tailscale.com:bad/a']:
            self.assertEqual(EmbeddedNetwork.login_url({'state':'NoState','authUrl':url}),'')
        self.assertEqual(EmbeddedNetwork.login_url({'state':'Running','authUrl':'https://login.tailscale.com/a/fixture'}),'')

    def test_network_states_have_user_facing_progress(self):
        for state in ['NoState','Starting','Running','NeedsLogin','NeedsMachineAuth','Stopped']:
            self.assertNotIn(state,EmbeddedNetwork.describe({'state':state}))


class EmbeddedNetworkBinaryTests(unittest.TestCase):
    def source(self, root, name, content=b'bundled network'):
        assets=root/name
        (assets/'bin').mkdir(parents=True)
        (assets/'bin/tailnet.exe').write_bytes(content)
        return assets

    def test_different_extraction_directories_launch_same_binary_without_rewriting(self):
        with tempfile.TemporaryDirectory() as folder,patch('coding_remote.embedded_network.subprocess.Popen') as launch:
            root=Path(folder);data=root/'data'
            process=MagicMock();process.stdout=io.BytesIO(b'{"port":1234}\n');process.poll.return_value=0;launch.return_value=process
            first=EmbeddedNetwork(self.source(root,'_MEI_first'),data,8080,'fixture')
            first.start();first_path=launch.call_args.args[0][0]
            os.utime(first.binary,ns=(1000000000,1000000000))
            process.stdout=io.BytesIO(b'{"port":1235}\n')
            second=EmbeddedNetwork(self.source(root,'_MEI_second'),data,8080,'fixture')
            second.start()
            self.assertEqual(first_path,str(data/'bin/tailnet.exe'))
            self.assertEqual(launch.call_args.args[0][0],first_path)
            self.assertEqual(second.binary.stat().st_mtime_ns,1000000000)
            self.assertEqual(second.port,1235)

    def test_upgrade_and_repair_keep_path_and_network_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);assets=self.source(root,'assets');network=EmbeddedNetwork(assets,root/'data',8080,'fixture')
            state=network.directory/'tailscaled.state';state.write_bytes(b'existing identity')
            network.prepare_binary();original=network.binary
            (assets/'bin/tailnet.exe').write_bytes(b'new bundled version')
            network.prepare_binary()
            self.assertEqual(network.binary,original)
            self.assertEqual(original.read_bytes(),b'new bundled version')
            original.write_bytes(b'damaged')
            network.prepare_binary()
            self.assertEqual(original.read_bytes(),b'new bundled version')
            self.assertEqual(state.read_bytes(),b'existing identity')

    def test_failed_copy_keeps_previous_binary_and_never_launches(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);network=EmbeddedNetwork(self.source(root,'assets'),root/'data',8080,'fixture')
            network.prepare_binary();network.source.write_bytes(b'new version')
            def corrupt(source,target):Path(target).write_bytes(b'truncated')
            with patch('coding_remote.embedded_network.shutil.copyfile',side_effect=corrupt),patch('coding_remote.embedded_network.subprocess.Popen') as launch:
                with self.assertRaisesRegex(ValueError,'校验失败'):network.start()
                launch.assert_not_called()
            self.assertEqual(network.binary.read_bytes(),b'bundled network')
            self.assertEqual(list(network.binary.parent.glob('*.tmp')),[])

    def test_locked_update_never_falls_back_to_temporary_executable(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);network=EmbeddedNetwork(self.source(root,'assets'),root/'data',8080,'fixture')
            network.prepare_binary();network.source.write_bytes(b'new version')
            with patch('coding_remote.embedded_network.os.replace',side_effect=PermissionError('locked')),patch('coding_remote.embedded_network.subprocess.Popen') as launch:
                with self.assertRaisesRegex(ValueError,'正常退出'):network.start()
                launch.assert_not_called()
            self.assertEqual(network.binary.read_bytes(),b'bundled network')
            self.assertEqual(list(network.binary.parent.glob('*.tmp')),[])
