"""Native provider callbacks and filesystem notifications wake the shared event bus."""
from pathlib import Path
from .change_feed import ChangeFeed


class SourceEvents:
    def __init__(self,server):
        self.server=server;self.feeds=[];self.closed=False

    def changed(self,backend):
        if not self.closed:self.server.live.invalidate(backend=backend)

    def watch(self,root,backend,paths=None):
        root=Path(root)
        if not root.is_dir():return
        feed=ChangeFeed(root,lambda:self.changed(backend),paths=paths)
        self.feeds.append(feed);feed.start()

    def start(self):
        bridge=getattr(self.server,'bridge',None)
        if bridge is not None:
            bridge.live_changed=lambda:self.changed('codex')
            root=getattr(getattr(bridge,'store',None),'home',None)
            if root:self.watch(root,'codex')
        desktop=getattr(getattr(self.server,'claude',None),'desktop',None)
        if callable(getattr(desktop,'start_events',None)):
            self.feeds.append(desktop.start_events(lambda:self.changed('claude')))
        deepseek=getattr(self.server,'deepseek',None)
        root=getattr(deepseek,'home',None)
        if root:
            root=Path(root);paths=[(root/'sessions',True),(root/'storages',True)]
            self.watch(root,'deepseek',paths if all(p.is_dir() for p,_ in paths) else [(root,True)])
        directory=getattr(deepseek,'directory',None)
        if directory:self.watch(directory,'deepseek',[(Path(directory),False)])

    def close(self):
        self.closed=True
        bridge=getattr(self.server,'bridge',None)
        if bridge is not None:bridge.live_changed=None
        for feed in self.feeds:feed.close()
