import json
import tempfile
import types
import unittest
from unittest.mock import MagicMock, patch

from coding_remote.pairing import Pairing
from coding_remote.server_link import ServerLink, validate_config


class ServerConnectionTests(unittest.TestCase):
    config = {'origin': 'https://relay.example.com', 'sshTarget': 'azure-relay', 'remotePort': 18787}

    def make_link(self, **kwargs):
        server = types.SimpleNamespace(server_port=9876, pairing=types.SimpleNamespace(computer_id='this-computer',remember_server_origin=MagicMock()),
                                       allow_origin=MagicMock())
        with patch('coding_remote.server_link.SSHTunnel') as tunnel:
            link = ServerLink(server, self.config, '.', **kwargs)
            tunnel.assert_called_once_with('azure-relay', 18787, 9876, '.', 'server')
        link.tunnel.state = 'connected'
        return link

    def test_public_url_and_ssh_arguments_are_validated(self):
        self.assertEqual(validate_config(self.config), self.config)
        for change in ({'origin': 'http://relay.example.com'}, {'origin': 'https://localhost'},
                       {'origin': 'https://relay.example.com/api'}, {'origin': 'https://user:pass@relay.example.com'},
                       {'sshTarget': '-oProxyCommand=bad'}, {'sshTarget': 'name;command'},
                       {'remotePort': 80}, {'remotePort': 65536}, {'remotePort': 'invalid'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_config({**self.config, **change})

    def test_gateway_must_be_this_computer(self):
        link = self.make_link()
        for response in ({'service': 'coding-remote', 'computerId': 'other-computer'},
                         {'service': 'unrelated', 'computerId': 'this-computer'}, []):
            link.opener = MagicMock()
            link.opener.open.return_value.__enter__.return_value.read.return_value = json.dumps(response).encode()
            with self.subTest(response=response), self.assertRaises(ValueError):
                link.check()

    def test_start_registers_origin_then_verifies_and_closes(self):
        link = self.make_link()
        def verify():
            link.server.allow_origin.assert_called_once_with(self.config['origin'])
        with patch.object(link, 'check', side_effect=verify):
            try:
                self.assertEqual(link.start(), self.config['origin'])
                self.assertTrue(link.snapshot()['healthy'])
                link.server.pairing.remember_server_origin.assert_called_once_with(self.config['origin'])
            finally:
                link.close()
        link.tunnel.close.assert_called_once()
        self.assertFalse(link.snapshot()['healthy'])
        self.assertFalse(link.thread.is_alive())

    def test_failed_forward_cannot_reuse_an_existing_gateway(self):
        link = self.make_link(startup_timeout=0)
        link.tunnel.state = 'retrying'
        with patch.object(link, 'check') as check, self.assertRaises(ValueError):
            link.start()
        check.assert_not_called()
        link.tunnel.close.assert_called_once()

    def test_unreachable_or_wrong_gateway_closes_owned_tunnel(self):
        for error in (OSError('TLS or network failure'), ValueError('wrong gateway')):
            link = self.make_link(startup_timeout=0)
            with self.subTest(error=error), patch.object(link, 'check', side_effect=error), self.assertRaises(ValueError):
                link.start()
            link.tunnel.close.assert_called_once()
            self.assertFalse(link.snapshot()['healthy'])

    def test_two_permanent_routes_have_independent_codes_and_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            pairing = Pairing(directory)
            tailnet = json.loads(pairing.issue('http://100.64.1.2:18787', 'permanent'))
            fixed = json.loads(pairing.issue(self.config['origin'], 'permanent'))
            credential = pairing.pair(fixed['token'], 'fixture', 'local', origin=fixed['origin'])
            pairing.clear_origin(fixed['origin'])
            self.assertIn(tailnet['token'], pairing.codes)
            self.assertIsNone(credential['expires'])
            restored = Pairing(directory)
            self.assertEqual(restored.authenticate(credential['token'])['origin'], fixed['origin'])


if __name__ == '__main__':
    unittest.main()
