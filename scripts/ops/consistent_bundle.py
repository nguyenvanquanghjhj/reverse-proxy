#!/usr/bin/env python3
"""PBL4 coherent *offline* PostgreSQL + shared-files backup and restore.

A database-only pg_dump cannot restore PDFs/images referenced by SQL. This tool
archives both and checks each artifact before restoring into a NEW database and a
NEW files root. All writers MUST be stopped for the paired filesystem snapshot.
No source database, original storage root or existing target is ever overwritten.

Examples (run with all backend JVM writers stopped, PostgreSQL still online):
  python scripts/ops/consistent_bundle.py backup-offline --out ../private-backups/snapshot.tar.gz --ack STOPPED_ALL_WRITERS
  python scripts/ops/consistent_bundle.py verify ../private-backups/snapshot.tar.gz
  python scripts/ops/consistent_bundle.py restore-new ../private-backups/snapshot.tar.gz --target-db pbl4_restore_20261114 --new-files-root ../private-restored-files --ack CREATE_NEW_DATABASE_AND_FILES
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import socket
import stat
import sys
import tarfile
import tempfile
import importlib.util
from typing import Any

HERE=Path(__file__).resolve().parent
MAX_ENTRIES=100000
MAX_UNCOMPRESSED_BYTES=4 * 1024**3  # guard against malicious metadata and extraction bombs
BUSY_PORTS=(8080,9001,9002,9003,18080,19001,19002,19003)


def pg_module():
    spec=importlib.util.spec_from_file_location('pbl4_pg_safeguard',HERE/'pg_safeguard.py')
    if not spec or not spec.loader: raise RuntimeError('Missing pg_safeguard.py')
    mod=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def sha_stream(reader) -> str:
    digest=hashlib.sha256()
    while True:
        chunk=reader.read(1024*1024)
        if not chunk: break
        digest.update(chunk)
    return digest.hexdigest()


def sha_file(path: Path) -> str:
    with path.open('rb') as stream:return sha_stream(stream)


def no_symlink_ancestors(path: Path):
    absolute=path.absolute()
    for parent in (absolute,*absolute.parents):
        if is_link(parent): raise ValueError('Symlink or junction forbidden in filesystem backup path')


def is_link(path: Path) -> bool:
    return path.is_symlink() or getattr(path, 'is_junction', lambda: False)()


def source_tree(root: Path):
    if not root.is_dir():raise FileNotFoundError('SHOP_FILES_DIR does not exist; refuse incomplete backup')
    no_symlink_ancestors(root)
    items=[];total=0
    for current,dirs,files in os.walk(root, followlinks=False):
        folder=Path(current)
        for name in dirs:
            p=folder/name
            if is_link(p): raise ValueError('Source directory symlink or junction is forbidden')
        for name in files:
            path=folder/name
            if is_link(path) or not path.is_file(): raise ValueError('Non-regular storage file encountered')
            rel=path.relative_to(root).as_posix()
            # Ensure the whole file tree is under the legitimate root with no absolute paths.
            if not rel or '..' in PurePosixPath(rel).parts:raise ValueError('Unsafe relative path')
            size=path.stat().st_size
            total+=size
            if len(items)>=MAX_ENTRIES or total>MAX_UNCOMPRESSED_BYTES:
                raise ValueError('Storage snapshot safety limit exceeded')
            items.append((rel,path,size))
    return sorted(items)


def probe_writer_ports():
    occupied=[]
    for port in BUSY_PORTS:
        with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as sock:
            sock.settimeout(.12)
            if sock.connect_ex(('127.0.0.1',port)) == 0:occupied.append(port)
    return occupied


def add_file(archive:tarfile.TarFile,path:Path,relative:str):
    info=archive.gettarinfo(str(path),arcname=relative)
    if not info.isfile():raise ValueError('Only regular files are permitted in the bundle')
    info.mode=0o600
    info.uid=0;info.gid=0;info.uname='';info.gname=''
    with path.open('rb') as body:archive.addfile(info,body)


def add_bytes(archive:tarfile.TarFile,data:bytes,relative:str):
    info=tarfile.TarInfo(relative)
    info.size=len(data);info.mode=0o600;info.mtime=int(datetime.now(timezone.utc).timestamp())
    archive.addfile(info,io.BytesIO(data))


def read_manifest(tar:tarfile.TarFile):
    member=tar.getmember('manifest.json')
    if not member.isfile() or member.size>2*1024*1024:raise ValueError('Invalid manifest member')
    stream=tar.extractfile(member)
    if stream is None:raise ValueError('No manifest bytes')
    return json.load(stream)


def check_layout(tar:tarfile.TarFile) -> dict[str,Any]:
    members=tar.getmembers()
    if len(members)<3 or len(members)>MAX_ENTRIES+3:raise ValueError('Invalid bundle entry count')
    index={};total=0
    for member in members:
        rel=PurePosixPath(member.name)
        if rel.is_absolute() or '..' in rel.parts or not member.isfile() or rel.as_posix()!=member.name:
            raise ValueError('Unsafe or non-file archive member')
        if member.name in index:raise ValueError('Duplicate archive member')
        total+=member.size
        if member.size<0 or total>MAX_UNCOMPRESSED_BYTES:raise ValueError('Archive exceeds maximum extracted bytes')
        index[member.name]=member
    manifest=read_manifest(tar)
    if manifest.get('version')!=1 or manifest.get('format')!='PBL4_DATABASE_AND_FILES_OFFLINE':
        raise ValueError('Unsupported backup manifest format')
    db=manifest.get('database_archive')
    if not isinstance(db,dict) or not isinstance(db.get('path'),str) or not db['path'].startswith('postgres/') or not db['path'].endswith('.dump'):
        raise ValueError('Invalid database archive metadata')
    pgmeta=manifest.get('database_manifest')
    if not isinstance(pgmeta,str) or not pgmeta.startswith('postgres/') or not pgmeta.endswith('.json'):
        raise ValueError('Invalid PostgreSQL manifest path')
    files=manifest.get('files')
    if not isinstance(files,list):raise ValueError('Files list missing')
    expected={'manifest.json',db['path'],pgmeta}
    for entry in files:
        if not isinstance(entry,dict) or not isinstance(entry.get('path'),str) or not entry['path'].startswith('files/'):
            raise ValueError('Invalid file path metadata')
        path=PurePosixPath(entry['path'])
        if len(path.parts)<2 or '..' in path.parts or path.as_posix()!=entry['path']:
            raise ValueError('Unsafe file metadata path')
        if entry['path'] in expected:raise ValueError('Duplicate file metadata')
        expected.add(entry['path'])
    if set(index)!=expected:raise ValueError('Archive members do not match declared manifest')
    for item in [db, *files]:
        if not isinstance(item.get('sha256'),str) or len(item['sha256'])!=64:
            raise ValueError('Missing SHA-256 metadata')
        if not isinstance(item.get('size_bytes'),int) or item['size_bytes']!=index[item['path']].size:
            raise ValueError('Entry size mismatch')
        handle=tar.extractfile(index[item['path']])
        if handle is None or sha_stream(handle)!=item['sha256']:
            raise ValueError('Archive SHA-256 mismatch: '+item['path'])
    pg_file=tar.extractfile(index[pgmeta])
    if pg_file is None:raise ValueError('Postgres manifest missing')
    inner=json.load(pg_file)
    if inner.get('sha256')!=db['sha256'] or inner.get('size_bytes')!=db['size_bytes']:
        raise ValueError('Mismatch between pg_dump manifest and bundle manifest')
    if 'postgres/'+inner.get('filename','')!=db['path']:
        raise ValueError('Unexpected pg_dump archive filename')
    return manifest


def verify(path:Path) -> dict[str,Any]:
    if not path.is_file() or is_link(path):raise ValueError('Not a regular backup archive')
    with tarfile.open(path,'r:gz') as archive:
        return check_layout(archive)


def files_root_from_env(path:Path) -> Path:
    pg=pg_module()
    raw=pg.load_env(path).get('SHOP_FILES_DIR')
    if not raw:raise ValueError('SHOP_FILES_DIR missing in environment file')
    root=Path(raw)
    if not root.is_absolute():raise ValueError('SHOP_FILES_DIR must be absolute')
    return root


def backup_offline(env_file:Path,out:Path,ack:str):
    if ack!='STOPPED_ALL_WRITERS':raise ValueError('Explicit --ack STOPPED_ALL_WRITERS is required')
    busy=probe_writer_ports()
    if busy:raise RuntimeError('Local backend/proxy ports still accept connections: '+str(busy))
    root=files_root_from_env(env_file)
    if out.exists():raise ValueError('Backup destination already exists; use a new path')
    no_symlink_ancestors(out.parent)
    items=source_tree(root)
    # Reuse the original pg_dump + validated archive machinery, never parse binary dump as SQL.
    pg=pg_module();conn=pg.connect_info(env_file)
    out.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='pbl4-offline-pg-') as temp:
        temporary=Path(temp)
        pg.backup(conn,temporary)
        pg_manifests=list(temporary.glob('*.json'))
        if len(pg_manifests)!=1:raise RuntimeError('Expected precisely one PostgreSQL manifest')
        pg_file,meta=pg.validated_archive(pg_manifests[0],conn)
        paths=[]
        for relative,file,size in items:
            paths.append({'path':'files/'+relative,'size_bytes':size,'sha256':sha_file(file)})
        manifest={'version':1,'format':'PBL4_DATABASE_AND_FILES_OFFLINE',
                  'created_utc':datetime.now(timezone.utc).isoformat(),
                  'database':conn['database'],
                  'database_archive':{'path':'postgres/'+pg_file.name,'sha256':meta['sha256'],'size_bytes':meta['size_bytes']},
                  'database_manifest':'postgres/'+pg_manifests[0].name,
                  'files':paths,
                  'consistency':'all app writers stopped by operator; external writers not detected by loopback check',
                  'restore_policy':'NEW DATABASE AND NEW FILES ROOT ONLY'}
        part=out.with_name('.'+out.name+'.partial')
        if part.exists():raise ValueError('Partial backup path already exists')
        try:
            with tarfile.open(part,'w:gz',compresslevel=6) as archive:
                add_file(archive,pg_file,'postgres/'+pg_file.name)
                add_file(archive,pg_manifests[0],'postgres/'+pg_manifests[0].name)
                for entry,(_,file,_) in zip(paths,items):
                    # Detect a mid-snapshot file replacement despite operator acknowledgement.
                    if sha_file(file)!=entry['sha256']:raise RuntimeError('Shared file changed during archive: '+entry['path'])
                    add_file(archive,file,entry['path'])
                add_bytes(archive,(json.dumps(manifest,sort_keys=True,ensure_ascii=False,indent=2)+'\n').encode('utf-8'),'manifest.json')
            verify(part) # full readback before publishing the final file
            os.replace(part,out)
            try: os.chmod(out,stat.S_IRUSR|stat.S_IWUSR)
            except OSError: pass
        finally:part.unlink(missing_ok=True)
    return {'backup':str(out),'sha256':sha_file(out),'files':len(paths),'database':conn['database'],
            'important':'archive contains sensitive order/customer data; store privately'}


def safe_copy_member(tar:tarfile.TarFile,name:str,destination:Path):
    member=tar.getmember(name)
    if not member.isfile():raise ValueError('Not a regular file')
    destination.parent.mkdir(parents=True,exist_ok=True)
    stream=tar.extractfile(member)
    if stream is None:raise ValueError('Cannot read archive entry')
    with destination.open('xb') as file:shutil.copyfileobj(stream,file,1024*1024)


def restore_new(env_file:Path,archive_file:Path,target_db:str,new_files_root:Path,ack:str):
    if ack!='CREATE_NEW_DATABASE_AND_FILES':raise ValueError('Explicit restore acknowledgement required')
    if new_files_root.exists() or is_link(new_files_root):
        raise ValueError('Destination storage root must not exist')
    no_symlink_ancestors(new_files_root.parent)
    original_root=files_root_from_env(env_file).absolute()
    if new_files_root.absolute()==original_root or original_root in new_files_root.absolute().parents:
        raise ValueError('Restore destination must be separate from the source storage tree')
    manifest=verify(archive_file)
    pg=pg_module();con=pg.connect_info(env_file)
    with tempfile.TemporaryDirectory(prefix='pbl4-verified-restore-') as temp:
        stage=Path(temp)
        with tarfile.open(archive_file,'r:gz') as tar:
            safe_copy_member(tar,manifest['database_archive']['path'],stage/Path(manifest['database_archive']['path']).name)
            safe_copy_member(tar,manifest['database_manifest'],stage/Path(manifest['database_manifest']).name)
            for item in manifest['files']:
                safe_copy_member(tar,item['path'],stage/item['path'])
        # Validate every extracted file a second time; archive metadata alone is not enough.
        for item in manifest['files']:
            target=stage/item['path']
            if target.stat().st_size!=item['size_bytes'] or sha_file(target)!=item['sha256']:
                raise RuntimeError('Extracted file verification failed')
        pg_manifest=stage/Path(manifest['database_manifest']).name
        # pg_safeguard independently checks SHA, TOC and target database nonexistence.
        pg.restore(con,pg_manifest,target_db,'CREATE_NEW_DATABASE_ONLY')
        new_files_root.parent.mkdir(parents=True,exist_ok=True)
        # Never expose a partially copied target. Stage on the same filesystem,
        # verify a second time, then atomically rename into the NEW destination.
        with tempfile.TemporaryDirectory(prefix='.pbl4-files-restore-',dir=new_files_root.parent) as staged_files:
            candidate=Path(staged_files)/'files'
            if manifest['files']:
                shutil.copytree(stage/'files',candidate,symlinks=False)
            else:
                candidate.mkdir()
            for item in manifest['files']:
                rel=PurePosixPath(item['path']).relative_to('files')
                present=candidate/str(rel)
                if present.stat().st_size!=item['size_bytes'] or sha_file(present)!=item['sha256']:
                    raise RuntimeError('Restored file verification failed before publish')
            if new_files_root.exists():raise RuntimeError('Files destination was concurrently created')
            os.replace(candidate,new_files_root)
        return {'new_database':target_db,'new_files_root':str(new_files_root),
                'file_count':len(manifest['files']),
                'next_action':'Point a separate, nonproduction config to the new DB and directory; run StoreAdmin audit.'}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env',type=Path,default=HERE.parents[1]/'.env.store')
    sub=parser.add_subparsers(dest='action',required=True)
    a=sub.add_parser('backup-offline');a.add_argument('--out',type=Path,required=True);a.add_argument('--ack',required=True)
    v=sub.add_parser('verify');v.add_argument('archive',type=Path)
    r=sub.add_parser('restore-new');r.add_argument('archive',type=Path)
    r.add_argument('--target-db',required=True);r.add_argument('--new-files-root',type=Path,required=True);r.add_argument('--ack',required=True)
    opts=parser.parse_args(argv)
    if opts.action=='backup-offline':ans=backup_offline(opts.env.resolve(),opts.out.absolute(),opts.ack)
    elif opts.action=='verify':
        m=verify(opts.archive.resolve());ans={'verified':str(opts.archive),'files':len(m['files']),
            'database':m['database'],'manifest_version':m['version']}
    else:ans=restore_new(opts.env.resolve(),opts.archive.resolve(),opts.target_db,opts.new_files_root.absolute(),opts.ack)
    print(json.dumps(ans,ensure_ascii=False,indent=2))

if __name__=='__main__':
    try:main()
    except (OSError,RuntimeError,ValueError,KeyError,tarfile.TarError) as exc:
        print('BACKUP/RESTORE BLOCKED:',type(exc).__name__,str(exc),file=sys.stderr)
        sys.exit(2)
