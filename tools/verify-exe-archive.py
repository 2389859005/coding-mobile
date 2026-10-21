"""Verify the packaged EXE layout without launching it."""
import hashlib
import marshal
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'packaging'))
from build_common import unpack

ROOT = Path(__file__).resolve().parents[1]
target = ROOT / 'dist/CodingRemoteAssistant-Windows-2.1.1.exe'
prefix, entries, version, dll = unpack(target.read_bytes())
rows = {name: (payload, raw, compressed) for name, payload, raw, compressed, kind in entries}
print('archive entries', len(rows), 'overlay bytes', len(prefix))

def payload(name):
    blob, raw, compressed = rows[name]
    return blob if not compressed else zlib.decompress(blob)

print('bytecode table', len(payload('coding_remote.bytecode')), 'bytes')
table = marshal.loads(payload('coding_remote.bytecode'))
print('compiled modules', len(table), 'names:', sorted(table)[:4], '...')

loader = payload('coding_remote_loader.pyc')
print('loader head', loader[:16].hex())
try:
    code = marshal.loads(loader)
    print('loader loads as', type(code).__name__, getattr(code, 'co_name', '?'))
except Exception as exc:
    print('LOADER BROKEN:', exc)

entry = payload('coding_remote_entry')
print('entry head', entry[:16].hex())
try:
    marshal.loads(entry)
    print('entry loads as code object')
except Exception as exc:
    print('ENTRY BROKEN:', exc)

print('stray source entries:', [n for n in rows if n.startswith('coding_remote\\') and n.endswith('.py')])
print('bridge/store.py present:', 'bridge\\store.py' in rows)
