"""Private status-file writes for supervised, independently configured entrances."""
import json
import os
from pathlib import Path
import secrets


def write_json(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_name(path.name+'.'+secrets.token_hex(4)+'.tmp')
    try:
        with os.fdopen(os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w',encoding='utf-8') as stream:json.dump(value,stream,ensure_ascii=False)
        temporary.replace(path)
    finally:temporary.unlink(missing_ok=True)
