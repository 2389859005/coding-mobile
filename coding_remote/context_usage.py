"""Current context occupancy; cumulative billing token totals are never used."""
import math


def normalize(value, provider='codex'):
    if not isinstance(value, dict): return {'available': False}
    if provider == 'codex':
        last = value.get('last') or value.get('last_token_usage') or {}
        used = last.get('totalTokens', last.get('total_tokens'))
        limit = value.get('modelContextWindow', value.get('model_context_window'))
    else:
        # Claude's getContextUsage summary and the normalized host adapter.
        used = value.get('usedTokens', value.get('totalTokens', value.get('total_tokens')))
        limit = value.get('contextWindow', value.get('maxTokens', value.get('max_tokens')))
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in (used, limit)):
        return {'available': False}
    if used < 0 or limit <= 0: return {'available': False}
    return {'available': True, 'usedTokens': int(used), 'maxTokens': int(limit),
            'percent': round(min(100, used / limit * 100), 1)}
