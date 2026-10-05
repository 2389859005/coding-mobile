"""Own one Windows tray icon, forwarding commands onto the Tk event queue."""
import logging
from pathlib import Path
import subprocess
import threading


class Tray:
    def __init__(self, assets, callback):
        self.process=None;self.ready=False;self.closed=False
        try:
            self.process=subprocess.Popen([str(Path(assets)/'bin/coding-tray.exe'),str(Path(assets)/'assets/codex-mobile.ico')],
                stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                text=True,encoding='utf-8',creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            def read():
                for line in self.process.stdout:
                    action=line.strip()
                    if action=='ready':self.ready=True
                    elif action in ('show','minimize','settings','logs','quit') and not self.closed:callback(action)
                self.ready=False
            threading.Thread(target=read,daemon=True).start()
        except OSError:logging.exception('Tray startup failed')

    def close(self):
        self.closed=True;self.ready=False
        if self.process and self.process.poll() is None:
            self.process.stdin.close()
            try:self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:self.process.terminate()
