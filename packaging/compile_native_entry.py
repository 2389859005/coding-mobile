"""Run with the bundled build-time Python 3.13 to match the original EXE."""
import marshal
from pathlib import Path
import sys
assert sys.version_info[:2] == (3, 13)
source='''import sys
import importlib.util
from pathlib import Path
sys.path.insert(0, sys._MEIPASS)
# PyInstaller prefers its frozen PYZ module even when a replacement source is
# bundled beside it. Load the reviewed schema adapter before bridge.service.
import bridge
spec=importlib.util.spec_from_file_location('bridge.store',str(Path(sys._MEIPASS)/'bridge/store.py'))
module=importlib.util.module_from_spec(spec)
sys.modules['bridge.store']=module
spec.loader.exec_module(module)
bridge.store=module
from coding_remote.desktop import main
main()
'''
Path(sys.argv[1]).write_bytes(marshal.dumps(compile(source,'coding_remote_entry.py','exec')))
