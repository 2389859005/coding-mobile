"""Diagnostic stub: report what the frozen runtime sees, trapping import failures.

Built into a throwaway EXE by tools/probe-extract.py, because the packaged runtime
gives no traceback for a failure before the window exists. Writes a report file next
to nothing else: it must not need the modules under test to describe them.
"""
import os
import sys
import traceback

report = []
report.append('_MEIPASS=%s' % sys._MEIPASS)
report.append('frozen=%s python=%s' % (getattr(sys, 'frozen', False), sys.version.split()[0]))
# Disassemble the real (shipped) entry stub with the interpreter that will run it,
# to see its true statements/line numbers. The stub is attached as entry_probe.bin.
probe_path = os.path.join(sys._MEIPASS, 'entry_probe.bin')
if os.path.exists(probe_path):
    import dis
    import marshal
    with open(probe_path, 'rb') as handle:
        probe_code = marshal.loads(handle.read())
    report.append('=== shipped entry disassembly ===')
    report.append('filename %s firstlineno %d' % (probe_code.co_filename, probe_code.co_firstlineno))
    for instruction in dis.get_instructions(probe_code):
        if instruction.starts_line is not None:
            report.append('--- line %s' % instruction.starts_line)
        report.append('    %-24s %s' % (instruction.opname, instruction.argrepr))
else:
    report.append('entry_probe.bin: MISSING')
# Compare text-mode vs binary reads of the same file: os.open(path, 0) without
# O_BINARY stops at the first 0x1A byte on Windows, which truncates the marshal.
for name in ('coding_remote.bytecode', 'coding_remote_loader.pyc'):
    path = os.path.join(sys._MEIPASS, name)
    if not os.path.exists(path):
        continue
    for flags, label in ((0, 'os.open(path, 0)'), (os.O_RDONLY | getattr(os, 'O_BINARY', 0), 'os.open O_BINARY')):
        handle = os.open(path, flags)
        blob = b''
        while True:
            chunk = os.read(handle, 1 << 20)
            if not chunk:
                break
            blob += chunk
        os.close(handle)
        report.append('%s via %s: read %d bytes' % (name, label, len(blob)))
try:
    listing = sorted(os.listdir(sys._MEIPASS))
except OSError as exc:
    listing = ['<listdir failed: %s>' % exc]
wanted = [name for name in listing if 'coding_remote' in name or name.startswith('bridge')]
report.append('root entries: %d' % len(listing))
report.append('relevant: %s' % ', '.join(wanted))
# The real stub resolves its inputs as root+'/coding_remote.bytecode' etc.; also
# report what landed in _internal, where the rest of the payload was extracted.
internal = os.path.join(sys._MEIPASS, '_internal')
if os.path.isdir(internal):
    inner = sorted(os.listdir(internal))
    report.append('_internal entries: %d' % len(inner))
    report.append('_internal relevant: %s' % ', '.join(
        name for name in inner if 'coding_remote' in name or name.startswith('bridge')))
for name in ('coding_remote.bytecode', 'coding_remote_loader.pyc', 'coding_remote_entry'):
    path = os.path.join(sys._MEIPASS, name)
    if not os.path.exists(path):
        report.append('%s: MISSING' % name)
        continue
    size = os.path.getsize(path)
    handle = os.open(path, 0)
    blob = b''
    while True:
        chunk = os.read(handle, 1 << 20)
        if not chunk:
            break
        blob += chunk
    os.close(handle)
    line = '%s: size=%d read=%d' % (name, size, len(blob))
    try:
        import marshal
        value = marshal.loads(blob)
        line += ' marshal=%s' % (len(value) if isinstance(value, dict) else 'code:' + value.co_name)
    except Exception as exc:
        line += ' marshal failed: %s: %s' % (type(exc).__name__, exc)
    report.append(line)
for module in ('marshal', 'importlib.abc', 'importlib.machinery', 'importlib.util', 'os', 'sys', 'pathlib'):
    try:
        __import__(module)
        report.append('import %s: ok' % module)
    except Exception:
        report.append('import %s FAILED:\n%s' % (module, traceback.format_exc()))

# Rehearse the repaired start-up chain with the shipped loader and table: binary
# reads, marshal both blobs, install the finder, then import the real entrypoint
# WITHOUT calling main(), so the rehearsal never opens a window.
import marshal as _marshal


def _read_binary(path):
    handle = os.open(path, os.O_RDONLY | getattr(os, 'O_BINARY', 0))
    blob = b''
    while True:
        chunk = os.read(handle, 1 << 20)
        if not chunk:
            break
        blob += chunk
    os.close(handle)
    return blob


report.append('=== repaired chain rehearsal ===')


def flush():
    """Persist after every step: a native crash would otherwise lose the report."""
    with open(sys.argv[1], 'w', encoding='utf-8') as handle:
        handle.write('\n'.join(report))


flush()
try:
    sources = _marshal.loads(_read_binary(os.path.join(sys._MEIPASS, 'coding_remote.bytecode')))
    report.append('table loads: %d modules' % len(sources))
    flush()
    namespace = {'marshal': _marshal, 'sys': sys}
    loader_code = _marshal.loads(_marshal.loads(_read_binary(os.path.join(sys._MEIPASS, 'coding_remote_loader.pyc')))['coding_remote_loader'])
    report.append('loader code object unmarshalled, co_code %d bytes, firstlineno %d' % (len(loader_code.co_code), loader_code.co_firstlineno))
    flush()
    try:
        import dis as _dis
        instructions = list(_dis.get_instructions(loader_code))
        report.append('runtime disassembly: %d instructions' % len(instructions))
        for instruction in instructions:
            report.append('    %-26s %s' % (instruction.opname, instruction.argrepr))
    except Exception:
        report.append('dis failed: ' + traceback.format_exc())
    flush()
    exec(loader_code, namespace)
    report.append('loader module executed, names: %s' % ', '.join(sorted(k for k in namespace if not k.startswith('__'))))
    flush()
    sys.meta_path.insert(0, namespace['BytecodeFinder'](sources, sys._MEIPASS))
    report.append('finder installed manually')
    flush()
    sys.meta_path.pop(0)
    report.append('install(root) served %d modules' % namespace['install'](sys._MEIPASS))
    flush()
    import bridge
    report.append('bridge ok, bridge.store preloaded: %s' % ('bridge.store' in sys.modules))
    flush()
    import bridge.store
    bridge.store = sys.modules['bridge.store']
    report.append('bridge.store resolved')
    flush()
    import coding_remote.desktop as desktop
    report.append('coding_remote.desktop ok, main=%s' % callable(getattr(desktop, 'main', None)))
    flush()
except Exception:
    report.append(traceback.format_exc())
    flush()
with open(sys.argv[1], 'w', encoding='utf-8') as handle:
    handle.write('\n'.join(report))
