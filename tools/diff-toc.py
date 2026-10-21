"""Drill into the archive failure: prepare, then extract, then diff.

The failure happens before the entry stub runs, so this reproduces the bootloader's
job from outside: decompress each entry and report the first byte that differs from
the file that was packed.
"""
import hashlib
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'packaging'))
from build_common import unpack

ROOT = Path(__file__).resolve().parents[1]
target = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'dist/CodingRemoteAssistant-Windows-2.1.1.exe'
prefix, entries, version, dll = unpack(target.read_bytes())

extracted = {}
for name, payload, raw, compressed, kind in entries:
    try:
        blob = payload if not compressed else zlib.decompress(payload)
    except Exception as exc:
        print('%s: DECOMPRESS FAILED (%d stored bytes): %s' % (name, len(payload), exc))
        continue
    if len(blob) != raw:
        print('%s: length mismatch, declared %d got %d' % (name, raw, len(blob)))
        continue
    extracted[name] = blob
print('entries decompressed:', len(extracted), 'of', len(entries))

sources = {
    'coding_remote.bytecode': ROOT / '.local/coding-remote.marshal',
    'coding_remote_loader.pyc': ROOT / '.local/coding-remote-loader.pyc',
}
for name, source in sources.items():
    if name not in extracted:
        print(name, 'MISSING from extraction')
        continue
    packed = source.read_bytes() if source.exists() else None
    if packed is None:
        print(name, 'no local copy to compare against')
        continue
    got = extracted[name]
    same = got == packed
    print('%s: local=%d extracted=%d identical=%s' % (name, len(packed), len(got), same))
    if not same:
        first = next((i for i in range(min(len(packed), len(got))) if packed[i] != got[i]), None)
        print('   first differing offset:', first)
        print('   local     :', packed[max(0, (first or 0) - 8):(first or 0) + 16].hex())
        print('   extracted :', got[max(0, (first or 0) - 8):(first or 0) + 16].hex())
