"""Pinned, verified SSH dependencies for the native Android client."""
import hashlib
from pathlib import Path
import urllib.request
import zipfile
from build_common import ROOT

ARTIFACTS = [
    ('com/github/mwiede/jsch/0.2.25/jsch-0.2.25.jar', '069a4e86fe1e5dbfe7bb504a6e4f973659898933e560e6649ae40da8c7070d1c'),
    ('net/i2p/crypto/eddsa/0.3.0/eddsa-0.3.0.jar', '4dda1120db856640dbec04140ed23242215a075fe127bdefa0dcfa29fb31267d'),
]

def dependencies():
    directory=ROOT/'.local/ssh-libs';directory.mkdir(exist_ok=True)
    paths=[]
    for artifact, digest in ARTIFACTS:
        target=directory/Path(artifact).name
        if not target.is_file():
            data=urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+artifact,timeout=30).read()
            if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('SSH dependency checksum mismatch')
            target.write_bytes(data)
        if hashlib.sha256(target.read_bytes()).hexdigest()!=digest:raise ValueError('SSH dependency checksum mismatch: '+target.name)
        # D8 needs base Java classes, not optional JDK 15+ multi-release entries.
        android=directory/(target.stem+'-android.jar')
        with zipfile.ZipFile(target) as src,zipfile.ZipFile(android,'w') as out:
            for item in src.infolist():
                if not item.filename.startswith('META-INF/versions/') and item.filename!='module-info.class':out.writestr(item,src.read(item.filename))
        paths.append(android)
    return paths
