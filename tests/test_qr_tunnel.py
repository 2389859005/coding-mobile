import io
import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from coding_remote.temporary_link import TemporaryLink


class QrTunnelTests(unittest.TestCase):
    def test_close_during_startup_prevents_orphan_tunnel(self):
        started=threading.Event();release=threading.Event();errors=[]
        with tempfile.TemporaryDirectory() as folder:
            link=TemporaryLink(8787,folder,folder,lambda value:None)
            def binary():started.set();release.wait(3);return Path('cloudflared.exe')
            def run():
                try:link.start()
                except RuntimeError as error:errors.append(str(error))
            with patch.object(link,'verified_binary',side_effect=binary),patch('coding_remote.temporary_link.subprocess.Popen') as launch:
                thread=threading.Thread(target=run);thread.start()
                try:
                    self.assertTrue(started.wait(2));link.close()
                finally:release.set();thread.join(4)
                self.assertFalse(thread.is_alive());launch.assert_not_called()
                self.assertTrue(errors)

    def test_owned_config_ignores_ambient_tunnel_target_and_token(self):
        class Process:
            stdout=io.StringIO('https://synthetic.trycloudflare.com\nRegistered tunnel connection\n')
            def poll(self):return 0
        with tempfile.TemporaryDirectory() as folder:
            link=TemporaryLink(8787,folder,folder,lambda value:None)
            with patch.object(link,'verified_binary',return_value=Path('cloudflared.exe')),patch.object(link,'verify_origin') as verify,patch('coding_remote.temporary_link.subprocess.Popen',return_value=Process()) as launch,patch.dict(os.environ,{'TUNNEL_URL':'http://wrong-origin.invalid','TUNNEL_TOKEN':'test-token'}):
                self.assertEqual(link.start(),'https://synthetic.trycloudflare.com')
            args=launch.call_args.args[0];environment=launch.call_args.kwargs['env']
            self.assertEqual(args[args.index('--url')+1],'http://127.0.0.1:8787')
            self.assertEqual(Path(args[args.index('--config')+1]).read_text().strip(),'{}')
            self.assertNotIn('TUNNEL_URL',environment);self.assertNotIn('TUNNEL_TOKEN',environment)
            verify.assert_called_once_with('https://synthetic.trycloudflare.com')

    def test_public_origin_must_match_local_gateway_before_qr(self):
        requests=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                requests.append((self.path,self.headers.get('Authorization')))
                wrong=self.headers.get('Host','').startswith('localhost:')
                body=json.dumps({'service':'coding-remote','computerId':'other' if wrong else 'this-computer'}).encode()
                self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            link=TemporaryLink(server.server_port,Path('.'),Path('.'),lambda value:None)
            with self.assertRaises(RuntimeError):link.verify_origin('http://localhost:'+str(server.server_port))
            self.assertEqual(requests,[('/api/gateway',None),('/api/gateway',None)])
        finally:server.shutdown();server.server_close();thread.join()
