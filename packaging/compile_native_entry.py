"""Run with the bundled build-time Python 3.13 to match the original EXE."""
import marshal
from pathlib import Path
import sys
assert sys.version_info[:2] == (3, 13)
source='''import marshal
import sys
import os
# coding_remote and bridge.store ship as one compiled table inside
# coding_remote.bytecode. The loader module below installs the meta path finder
# that serves them, so the rest of this file never parses them from source.
root=sys._MEIPASS
sys.path.insert(0, root)
def _read(path):
    # O_BINARY is mandatory on Windows: in text mode the CRT reports EOF at the
    # first 0x1A byte, which truncates the marshalled blobs and fails with
    # `EOFError: marshal data too short` before the window ever exists.
    handle=os.open(path, os.O_RDONLY | os.O_BINARY)
    blob=b''
    while True:
        chunk=os.read(handle, 1 << 20)
        if not chunk: break
        blob+=chunk
    os.close(handle)
    return blob
_namespace={'marshal':marshal, 'sys':sys}
exec(marshal.loads(marshal.loads(_read(root+'/coding_remote_loader.pyc'))['coding_remote_loader']), _namespace)
_namespace['install'](root)
# PyInstaller prefers its frozen PYZ module even when a replacement source is
# bundled beside it, so the reviewed schema adapter is imported through the table
# rather than through the archive's bridge package.
import bridge
if 'bridge.store' not in sys.modules:
    import bridge.store
bridge.store=sys.modules['bridge.store']
from coding_remote.desktop import main
main()
'''
Path(sys.argv[1]).write_bytes(marshal.dumps(compile(source,'coding_remote_entry.py','exec')))
