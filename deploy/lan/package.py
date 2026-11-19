"""Package the current local build for three nodes; exclude all private runtime data."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
from windows_nodes import ROOT,build_id,directory

def package(out):
    directory(out.parent)
    if out.exists():raise ValueError('Không ghi đè gói đã có.')
    build=build_id();paths=[]
    for folder in ['src','scripts','docs','deploy/lan','target/standalone/classes']:
        paths.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix not in {'.pyc','.log'})
    paths.extend(ROOT/p for p in ['README.md','pom.xml','config/application.properties','lib/postgresql-42.7.14.jar'])
    manifest={'build_sha256':build,'scope':'local build; no physical LAN acceptance claimed','files':{}}
    out.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(out,'x',compression=zipfile.ZIP_DEFLATED) as archive:
        for p in sorted(set(paths)):
            if p.is_symlink():raise ValueError('Không đóng gói link.')
            data=p.read_bytes();name=p.relative_to(ROOT).as_posix();archive.writestr(name,data);manifest['files'][name]=hashlib.sha256(data).hexdigest()
        archive.writestr('BUILD-MANIFEST.json',json.dumps(manifest,indent=2))
    print(json.dumps({'zip':str(out),'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'build_sha256':build,'files':len(manifest['files'])},indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);args=p.parse_args();package(args.out.resolve())
