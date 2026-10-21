"""Import the compiled coding_remote modules inside the frozen EXE.

The packaged entry stub runs this before anything imports the package, so the
interpreter never parses the shipped sources. `bridge.store` is loaded through the
same table, which is why the stub no longer reads its source from the archive.
"""
import marshal
import sys
from importlib.abc import Loader, MetaPathFinder
from importlib.machinery import ModuleSpec


def read_bytes(path):
    """Read a file without pulling the io stack into the start-up import graph.

    O_BINARY is mandatory on Windows: without it the CRT opens the file in text
    mode and reports EOF at the first 0x1A byte, which truncates the marshalled
    blob and surfaces as `EOFError: marshal data too short` before the window
    ever exists.
    """
    os_module = __import__('os')
    handle = os_module.open(path, getattr(os_module, 'O_RDONLY', 0) | getattr(os_module, 'O_BINARY', 0))
    blob = b''
    while True:
        chunk = os_module.read(handle, 1 << 20)
        if not chunk:
            break
        blob += chunk
    os_module.close(handle)
    return blob


class BytecodeLoader(Loader):
    def __init__(self, fullname, code, path):
        self.fullname = fullname
        self.code = code
        self.path = path

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        # exec() of a bare code object does not populate __file__ the way the
        # standard source loader does, and desktop.py resolves its asset root
        # through it, so set it before the module body runs.
        module.__file__ = self.path
        exec(self.code, module.__dict__)

    def get_filename(self, fullname):
        return self.path

    def get_code(self, fullname):
        return self.code

    def is_package(self, fullname):
        return False


class BytecodeFinder(MetaPathFinder):
    def __init__(self, sources, root):
        self.sources = sources
        self.root = root

    def find_spec(self, fullname, path=None, target=None):
        # The table keys the package init as `<pkg>.__init__`, while the import
        # system asks for the bare package name; map one onto the other and mark
        # the module as a package so submodule imports keep working.
        payload = self.sources.get(fullname)
        is_package = False
        if payload is None:
            payload = self.sources.get(fullname + '.__init__')
            is_package = payload is not None
        if payload is None:
            return None
        # The table stores one marshalled code object per module; decode on first
        # import and cache it, so start-up never pays for modules that stay unused.
        code = payload if hasattr(payload, 'co_code') else marshal.loads(payload)
        self.sources[fullname] = code
        loader = BytecodeLoader(fullname, code, self.root + '/coding_remote.bytecode')
        return ModuleSpec(fullname, loader, origin=loader.get_filename(fullname), is_package=is_package)


def install(root):
    """Prepend the finder for the modules compiled into the archive.

    `coding_remote.bytecode` is the module table (one marshalled code object per
    module); `coding_remote_loader.pyc` only carries this module's own code object,
    so it must not be unmarshalled a second time here.
    """
    sources = marshal.loads(read_bytes(root + '/coding_remote.bytecode'))
    sys.meta_path.insert(0, BytecodeFinder(sources, root))
    return len(sources)
