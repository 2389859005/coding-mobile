"""Package-level plugin selection and native mention inputs over the desktop owner."""
import copy
import hashlib
import json
import threading
import time
from pathlib import Path

from .codex_rpc import CodexRPC


def package_manifest(skill):
    if not skill.get('path'): return None
    for parent in Path(skill['path']).parents:
        manifest = parent / '.codex-plugin/plugin.json'
        if not manifest.is_file(): continue
        try:
            data = json.loads(manifest.read_text('utf-8'))
            return parent, data
        except (OSError, ValueError): return None
    return None


def grouped_catalog(raw, installed):
    packages, skills = {}, []
    for market in installed.get('marketplaces', []):
        for plugin in market.get('plugins', []):
            if not plugin.get('installed'): continue
            interface = plugin.get('interface') or {}
            pid = plugin['id']
            packages[pid] = {'id': 'plugin:'+pid, 'pluginId': pid, 'name': plugin['name'],
                'displayName': interface.get('displayName') or plugin['name'], 'kind': 'plugin',
                'description': interface.get('shortDescription') or '', 'memberIds': [],
                'selectable': bool(plugin.get('enabled')) and plugin.get('availability') != 'DISABLED_BY_ADMIN',
                'status': '可选' if plugin.get('enabled') else '已停用'}
    for skill in raw.get('skills', []):
        manifest = package_manifest(skill)
        if manifest:
            root, data = manifest
            candidates = [p for p in packages.values() if p.get('pluginId') and p['name'] == data.get('name')]
            # The cache path identifies the marketplace when names collide.
            exact = [p for p in candidates if p['pluginId'].rsplit('@', 1)[-1] in root.parts]
            group = exact[0] if len(exact) == 1 else candidates[0] if len(candidates) == 1 else None
            if group:
                group['memberIds'].append(skill['id']); continue
            # An unavailable inventory never silently enables an unverified package.
            key = 'local:'+hashlib.sha256(str(root).encode()).hexdigest()
            if key not in packages:
                packages[key] = {'id': key, 'name': data.get('name', root.name),
                    'displayName': (data.get('interface') or {}).get('displayName') or data.get('name', root.name),
                    'kind': 'plugin', 'selectable': False, 'status': '请刷新插件清单', 'memberIds': []}
            packages[key]['memberIds'].append(skill['id'])
        else: skills.append({**skill, 'kind': 'skill', 'selectable': True})
    return sorted(packages.values(), key=lambda p: p['displayName'].lower()) + skills


class PluginBridgeMixin:
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._plugin_lock, self._plugin_cache = threading.Lock(), {}
        portable = self.store.home / 'plugins/.plugin-appserver/codex.exe'
        if portable.is_file(): self.catalog_reader.executable = portable

    def plugin_inventory(self, sid, refresh=False):
        if not hasattr(self, '_plugin_lock'):
            self._plugin_lock, self._plugin_cache = threading.Lock(), {}
        cwd = self.store.get(sid)['cwd']
        with self._plugin_lock:
            cached = self._plugin_cache.get(cwd)
            if cached and time.monotonic()-cached[0] < (15 if refresh else 120): return cached[1]
            with CodexRPC(self.store.home) as rpc:
                value = rpc.request('plugin/installed', {'cwds': [cwd]})
                value['modes'] = rpc.request('collaborationMode/list', {}).get('data', [])
            self._plugin_cache[cwd] = time.monotonic(), value
            return value

    def _resolve_skills(self, session, ids):
        if not isinstance(ids, list) or len(ids) > 32 or any(not isinstance(v, str) for v in ids):
            raise ValueError('最多选择 32 个插件或技能')
        package_ids = [v for v in ids if v.startswith('plugin:')]
        plain = [v for v in ids if not v.startswith('plugin:')]
        selected = super()._resolve_skills(session, plain)
        if package_ids:
            inventory = self.plugin_inventory(session.id)
            available = {p['id']: p for p in grouped_catalog({}, inventory)}
            for pid in sorted(set(package_ids)):
                plugin = available.get(pid)
                if not plugin or not plugin['selectable']: raise ValueError('插件已停用或不可用，请刷新列表')
                selected.append({'id': pid, 'name': plugin['name'], 'path': 'plugin://'+plugin['pluginId']})
        return selected

    def _call(self, session, method, params, **kwargs):
        if method in ('thread-follower-start-turn', 'thread-follower-steer-turn'):
            params = copy.deepcopy(params)
            def convert(value):
                if isinstance(value, dict):
                    if value.get('type') == 'skill' and str(value.get('path', '')).startswith('plugin://'):
                        value['type'] = 'mention'
                    for child in value.values(): convert(child)
                elif isinstance(value, list):
                    for child in value: convert(child)
            convert(params)
        return super()._call(session, method, params, **kwargs)
