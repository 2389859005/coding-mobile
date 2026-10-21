"""Decode the packaged entry stub and the compiled table from the EXE.

The entry stub is itself a marshalled code object, so the source text below is
recovered from its constants. Useful when the EXE starts but the stub fails:
the failure line can be read back without rebuilding.

    python tools/show-entry-stub.py            # from dist/*.exe
"""
import marshal
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'packaging'))
from build_common import unpack

ROOT = Path(__file__).resolve().parents[1]
target = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'dist/CodingRemoteAssistant-Windows-2.1.1.exe'

prefix, entries, version, dll = unpack(target.read_bytes())
rows = {name: (payload, compressed) for name, payload, raw, compressed, kind in entries}


def payload(name):
    blob, compressed = rows[name]
    return blob if not compressed else zlib.decompress(blob)


def source_text(code, lines):
    """Recover the stub's source: it is the one constant holding our marker."""
    for const in code.co_consts:
        if hasattr(const, 'co_code'):
            found = source_text(const, lines)
            if found:
                return found
    text = '\n'.join(line for line in lines if isinstance(line, str) and 'coding_remote.bytecode' in line)
    return text or None


entry = marshal.loads(payload('coding_remote_entry'))
print('=== entry stub ===')
print('filename', entry.co_filename, 'first line', entry.co_firstlineno, 'lines', entry.co_lines() and 'present')
import dis
for instruction in dis.get_instructions(entry):
    if instruction.starts_line:
        print('%3d %-24s %s' % (instruction.starts_line, instruction.opname, instruction.argrepr))
table = marshal.loads(payload('coding_remote.bytecode'))
loader = marshal.loads(marshal.loads(payload('coding_remote_loader.pyc'))['coding_remote_loader'])
print('=== table ===')
print(len(table), 'modules:', ', '.join(sorted(table)[:6]), '...')
print('loader module:', loader.co_name, 'from', loader.co_filename)
