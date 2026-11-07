#!/usr/bin/env python3
"""Download pinned runtime/demo tools once; old javac build has no dependency on this script."""
import argparse
import hashlib
import io
from pathlib import Path
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CENTRAL = 'https://repo.maven.apache.org/maven2/'
JDBC_VERSION = '42.7.14'
JDBC_SHA256 = '73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c'

def download(relative, target, expected):
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return target
    data = urllib.request.urlopen(CENTRAL + relative, timeout=90).read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError('Download SHA-256 mismatch: ' + target.name)
    target.write_bytes(data)
    print('Prepared', target.relative_to(ROOT))
    return target

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-windows', action='store_true', help='Optional local-only PostgreSQL 16.14 binaries')
    args = parser.parse_args()
    download(f'org/postgresql/postgresql/{JDBC_VERSION}/postgresql-{JDBC_VERSION}.jar',
             ROOT / 'lib' / f'postgresql-{JDBC_VERSION}.jar', JDBC_SHA256)
    if args.postgres_windows:
        artifact = download('io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/16.14.0/embedded-postgres-binaries-windows-amd64-16.14.0.jar',
                            ROOT / '.runtime/postgres-binaries.jar', 'ce225f9a5216efabaf63cb9e0f4e68eac25f2d071113263a48a193ae5f1f5925')
        with zipfile.ZipFile(artifact) as archive:
            with tarfile.open(fileobj=io.BytesIO(archive.read('postgres-windows-x86_64.txz'))) as tar:
                tar.extractall(ROOT / '.runtime/postgres', filter='data')
        print('Prepared .runtime/postgres (third-party PostgreSQL binaries; ignored)')

if __name__ == '__main__':
    main()
