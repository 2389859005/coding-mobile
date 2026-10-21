"""Run with the bundled build-time Python 3.13 to match the original EXE.

The loader code object has to be compiled by the same interpreter family as the
frozen one: bytecode produced by any other minor version (the .venv runs 3.12)
still unmarshals, but executes as a garbled instruction stream and crashes the
frozen interpreter with an access violation.
"""
import marshal
import sys
from pathlib import Path

assert sys.version_info[:2] == (3, 13)
target = Path(sys.argv[1])
source = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).with_name('coding_remote_loader.py')
code = compile(source.read_text('utf-8'), source.name, 'exec', dont_inherit=True, optimize=2)
target.write_bytes(marshal.dumps({'coding_remote_loader': marshal.dumps(code)}))
print('loader bytecode', len(code.co_code), 'bytes')
