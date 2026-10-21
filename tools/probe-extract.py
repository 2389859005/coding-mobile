"""Build a diagnostic EXE that reports what the frozen runtime sees.

Reuses the real packer with the probe stub substituted for the entry script, so the
question "is the archive laid out correctly at runtime?" is answered without guessing
at bootloader behaviour.

    python tools/probe-extract.py                                   # .local payload
    python tools/probe-extract.py --from-exe dist/<build>.exe       # shipped payload
"""
import argparse
import hashlib
import marshal
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'packaging'))
from build_common import unpack, COOKIE, ENTRY, MAGIC, EXPECTED_EXE as EXPECTED
from product_icon import replace_icon

BASE = ROOT / '.local/native-base-windows-1.6.23.exe'
TARGET = ROOT / 'dist/CodingRemoteAssistant-probe.exe'
SHIPPED = ROOT / 'dist/CodingRemoteAssistant-Windows-2.1.1.exe'

parser = argparse.ArgumentParser(description='Build the frozen-runtime probe EXE.')
parser.add_argument('--from-exe', help='take the module table, loader and entry stub from this EXE instead of .local')
options = parser.parse_args()

assert hashlib.sha256(BASE.read_bytes()).hexdigest() == EXPECTED, 'base image digest mismatch'
prefix, entries, version, dll = unpack(BASE.read_bytes())
prefix = replace_icon(prefix, ROOT / 'packaging/product/icon.ico')

# The bootloader unmarshals 's' entries directly (PyMarshal_ReadObjectFromString),
# so the probe stub must ship as a marshalled code object, not as source text.
stub = marshal.dumps(compile((ROOT / 'packaging/probe_stub.py').read_bytes(), 'probe_stub.py', 'exec'))
rows = []
for name, payload, raw, compressed, kind in entries:
    if name == '固定域名入口':
        rows.append(('coding_remote_entry', zlib.compress(stub, 9), len(stub), 1, b's'))
    else:
        rows.append((name, payload, raw, compressed, kind))

sources = {}
if options.from_exe:
    _, shipped_entries, _, _ = unpack(Path(options.from_exe).read_bytes())
    for name, payload, raw, compressed, kind in shipped_entries:
        if name in ('coding_remote.bytecode', 'coding_remote_loader.pyc', 'coding_remote_entry'):
            sources[name] = payload if not compressed else zlib.decompress(payload)
    missing = [name for name in ('coding_remote.bytecode', 'coding_remote_loader.pyc') if name not in sources]
    if missing:
        raise SystemExit('source EXE is missing %s' % ', '.join(missing))
else:
    for name in ('coding_remote.bytecode', 'coding_remote_loader.pyc'):
        sources[name] = (ROOT / '.local' / ('coding-remote.marshal' if name.endswith('bytecode') else 'coding-remote-loader.pyc')).read_bytes()
    if SHIPPED.is_file():
        _, shipped_entries, _, _ = unpack(SHIPPED.read_bytes())
        payload = next(p for n, p, r, c, k in shipped_entries if n == 'coding_remote_entry')
        sources['coding_remote_entry'] = zlib.decompress(payload)

for name, content in sources.items():
    rows.append((name if name != 'coding_remote_entry' else 'entry_probe.bin',
                 zlib.compress(content, 9), len(content), 1, b'b'))

data = bytearray()
toc = []
for name, payload, raw, compressed, kind in rows:
    toc.append((name, len(data), len(payload), raw, compressed, kind))
    data.extend(payload)
offset = len(data)
for name, position, packed, raw, compressed, kind in toc:
    encoded = name.encode() + b'\0'
    size = (ENTRY.size + len(encoded) + 15) // 16 * 16
    data.extend(ENTRY.pack(size, position, packed, raw, compressed, kind))
    data.extend(encoded.ljust(size - ENTRY.size, b'\0'))
size = len(data) - offset
data.extend(COOKIE.pack(MAGIC, len(data) + COOKIE.size, offset, size, version, dll))
TARGET.write_bytes(prefix + bytes(data))
print(TARGET, hashlib.sha256(TARGET.read_bytes()).hexdigest()[:16], len(rows), 'entries',
      'payload from', options.from_exe or '.local')
