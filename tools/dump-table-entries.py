"""Dump the packaged table entry sizes and the entry stub's trace lines."""
import marshal
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'packaging'))
from build_common import unpack

ROOT = Path(__file__).resolve().parents[1]
target = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'dist/CodingRemoteAssistant-Windows-2.1.1.exe'
prefix, entries, version, dll = unpack(target.read_bytes())

for name, payload, raw, compressed, kind in entries:
    if name in ('coding_remote.bytecode', 'coding_remote_loader.pyc', 'coding_remote_entry'):
        blob = payload if not compressed else zlib.decompress(payload)
        print(name, 'raw', raw, 'stored', len(payload), 'decompressed', len(blob))

print('--- loads ---')
handles = {name: (payload if not compressed else zlib.decompress(payload))
           for name, payload, raw, compressed, kind in entries
           if name in ('coding_remote.bytecode', 'coding_remote_loader.pyc', 'coding_remote_entry')}
for name, blob in handles.items():
    try:
        value = marshal.loads(blob)
        detail = len(value) if isinstance(value, dict) else value.co_name
        print(name, '->', type(value).__name__, detail)
    except Exception as exc:
        print(name, '-> FAILED:', type(exc).__name__, exc)
