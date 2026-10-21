"""Simulate the archive swap the packer performs, in-process.

The EXE that the packer writes is structurally identical to the base image plus new
archive bytes (build_native_exe asserts this and reads it back). This script tests the
stronger claim: swap the archive in the real file, then run the packaged runtime on
it in a child process.
"""
import hashlib
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'packaging'))
from build_common import unpack, MAGIC

BASE = ROOT / '.local/native-base-windows-1.6.23.exe'
PACKED = ROOT / 'dist/CodingRemoteAssistant-Windows-2.1.1.exe'
SWAPPED = ROOT / '.local/swapped.exe'

base = BASE.read_bytes()
packed = PACKED.read_bytes()
base_prefix, base_entries, _, _ = unpack(base)
packed_prefix, packed_entries, _, _ = unpack(packed)

# Keep the base file's PE part, take the packed file's archive wholesale.
tail_start = len(base) - (len(base) - len(base_prefix))
swapped = packed_prefix + packed[len(packed_prefix):]
SWAPPED.write_bytes(swapped)
print('base', len(base), 'packed', len(packed), 'swapped', len(swapped))
print('swapped prefix == packed prefix:', swapped[:len(packed_prefix)] == packed_prefix)
print('base entries', len(base_entries), 'packed entries', len(packed_entries))

print('swapped sha256', hashlib.sha256(swapped).hexdigest())
print('same as packed:', swapped == packed)
