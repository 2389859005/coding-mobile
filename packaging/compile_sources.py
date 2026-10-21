"""Byte-compile the shipped sources at build time.

The packaged assistant loads its own modules on every launch. Compiling them here
keeps the interpreter from parsing ~210 KB of source, and the archive stores a
single entry for the whole package instead of one header per module.

The build runs on whatever Python launched build.cmd, but the bytecode has to come
from the interpreter frozen inside the EXE, so this module is executed by the
bundled 3.13 (see build_native_exe.build, which runs this file as a script).
"""
import marshal
from pathlib import Path
import sys

assert sys.version_info[:2] == (3, 13)


def compile_sources(pairs, output):
    """Compile `pairs` of (module name, source path) into `output` as marshal.

    `optimize=2` drops docstrings, which the packaged runtime never reads but which
    are a measurable part of this codebase's source text.
    """
    payload = {}
    for name, source in pairs:
        text = Path(source).read_text('utf-8')
        payload[name] = marshal.dumps(compile(text, str(source), 'exec', dont_inherit=True, optimize=2))
    Path(output).write_bytes(marshal.dumps(payload))
    return len(payload)


def coding_remote_sources(project, modules=None):
    """(module name, path) pairs for the shipped coding_remote package."""
    root = Path(project) / 'coding_remote'
    if modules is None:
        modules = sorted(path.stem for path in root.glob('*.py'))
    return [('coding_remote.' + name, root / (name + '.py')) for name in modules]


if __name__ == '__main__':
    project = Path(__file__).resolve().parents[1]
    target = Path(sys.argv[1]).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    pairs = coding_remote_sources(project) + [('bridge.store', project / 'packaging/bridge/store.py')]
    count = compile_sources(pairs, target)
    print(target, count, 'modules', target.stat().st_size, 'bytes')
