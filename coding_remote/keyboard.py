"""Launch the bundled Console-only keyboard injector; no clipboard or CDP."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def _env_dir(name):
    """Path from an environment variable, with a clear error when it is unset.

    A bare os.environ[...] on a stripped-down process raised KeyError before any
    useful message, and on a redirected profile the variable can genuinely be
    missing.
    """
    value = os.environ.get(name)
    if not value:
        raise RuntimeError('环境变量 %s 未设置，无法定位 Claude 配置目录' % name)
    return Path(value)


def enable_devtools(executable):
    """Change only the documented developer setting, preserving other settings."""
    executable = Path(executable)
    if 'WindowsApps' in executable.parts:
        package = next(p for p in executable.parts if p.startswith('Claude_'))
        family = 'Claude_' + package.rsplit('__', 1)[-1]
        directory = _env_dir('LOCALAPPDATA')/'Packages'/family/'LocalCache/Roaming/Claude'
    else:
        directory = _env_dir('APPDATA')/'Claude'
    directory.mkdir(parents=True, exist_ok=True)
    path = directory/'developer_settings.json'
    value = json.loads(path.read_text('utf-8-sig')) if path.exists() else {}
    if value.get('allowDevTools') is True:
        return False
    value['allowDevTools'] = True
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), 'utf-8')
    temporary.replace(path)
    return True


def helper_path():
    assets = Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parents[1]))
    helper = assets/'bin/claude-keyboard.exe' if getattr(sys, 'frozen', False) else assets/'.local/claude-keyboard.exe'
    if not helper.is_file():
        raise RuntimeError('缺少 Claude 键盘连接组件，请更新 Windows 助手')
    return helper


def close_devtools(pid, directory):
    result = subprocess.run([str(helper_path()), str(int(pid)), '--close-devtools'], capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=15,
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    with (Path(directory)/'claude-keyboard.log').open('a', encoding='utf-8') as out:
        out.write(time.strftime('%Y-%m-%d %H:%M:%S')+' close-devtools exit='+str(result.returncode)+' '+result.stdout.strip()[:300]+'\n')
    if result.returncode:
        raise RuntimeError(result.stdout.strip()[-300:] or 'Claude 开发者工具关闭失败')


def inject(pid, script, directory):
    helper = helper_path()
    # Direct Console source. Dynamic eval after an await is blocked by Claude's
    # CSP. Build-time minification removes newlines without changing that policy.
    if not script.startswith('/* coding remote script */') or '\n' in script or '\r' in script:
        raise ValueError('Console 连接脚本必须由构建工具生成')
    expression=script
    path = Path(directory)/'keyboard-connect.js'
    path.write_text(expression, encoding='utf-8')
    try:
        log=Path(directory)/'claude-keyboard.log'
        with log.open('a',encoding='utf-8') as out:out.write(time.strftime('%Y-%m-%d %H:%M:%S')+' launching Console injector for PID '+str(pid)+'\n')
        result = subprocess.run([str(helper), str(int(pid)), str(path)], capture_output=True,
                                text=True, encoding='utf-8', errors='replace', timeout=240,
                                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        with log.open('a',encoding='utf-8') as out:out.write(time.strftime('%Y-%m-%d %H:%M:%S')+' exit='+str(result.returncode)+' '+result.stdout.strip()[:600]+'\n')
        if result.returncode:
            raise RuntimeError((result.stdout.strip().splitlines()[-1] if result.stdout.strip() else '无法确认 Claude Console 焦点，未输入脚本')[:300])
    except subprocess.TimeoutExpired as error:
        output=error.stdout or b''
        if isinstance(output,bytes):output=output.decode('utf-8',errors='replace')
        with (Path(directory)/'claude-keyboard.log').open('a',encoding='utf-8') as out:out.write('Timed out: '+output[:600]+'\n')
        raise
    finally:
        path.unlink(missing_ok=True)
