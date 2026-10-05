"""Shared subprocess and archive helpers for native Windows / Android builds."""
import struct
import subprocess
from pathlib import Path

MAGIC = b'MEI\x0c\x0b\x0a\x0b\x0e'
COOKIE = struct.Struct('!8sIIII64s')
ENTRY = struct.Struct('!iIIIBc')
EXPECTED_EXE = 'bf273d3e5541b1143a41935b63e439fa12fb136393abf62d325f2ca45e5cbbf6'
EXPECTED_APK = '172281a5c89b54b991d012bbc08dc98bb6fbb263810fd61dc71820d8b7ee0bd7'
ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def unpack(blob):
    end = blob.rfind(MAGIC)
    magic, size, toc_offset, toc_size, version, dll = COOKIE.unpack_from(blob, end)
    start = end + COOKIE.size - size
    assert start > 0 and end + COOKIE.size == len(blob), 'Unexpected archive or signed overlay'
    entries = []
    pos = start + toc_offset
    while pos < start + toc_offset + toc_size:
        length, offset, packed, raw, compressed, kind = ENTRY.unpack_from(blob, pos)
        name = blob[pos+ENTRY.size:pos+length].split(b'\0')[0].decode('utf-8')
        payload = blob[start+offset:start+offset+packed]
        entries.append((name, payload, raw, compressed, kind))
        pos += length
    return blob[:start], entries, version, dll
