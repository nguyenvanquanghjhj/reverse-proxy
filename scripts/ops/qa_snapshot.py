"""Verify or restore an offline QA data package into a NEW directory only."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import zipfile


def entries(archive, dataset=None):
    manifest = json.loads(archive.read('manifest.json'))
    if manifest.get('format') != 1:
        raise ValueError('Unsupported snapshot format')
    selected = [row for row in manifest['files'] if dataset is None or row['dataset'] == dataset]
    if not selected:
        raise ValueError('No matching dataset')
    targets = set()
    for row in selected:
        relative = PurePosixPath(row['relative'])
        if (relative.is_absolute() or not relative.parts or '..' in relative.parts
                or '\\' in row['relative'] or ':' in row['relative']
                or not re.fullmatch(r'[a-z]+-[a-f0-9]{12}', row['dataset'])
                or not re.fullmatch(r'[a-f0-9]{64}', row['sha256'])):
            raise ValueError('Unsafe snapshot entry')
        key = (row['dataset'], str(relative))
        if key in targets:
            raise ValueError('Duplicate restore path')
        targets.add(key)
        if archive.getinfo('objects/' + row['sha256']).file_size != row['bytes']:
            raise ValueError('Snapshot length mismatch')
    return manifest, selected


def verify(path, dataset=None):
    with zipfile.ZipFile(path) as archive:
        manifest, selected = entries(archive, dataset)
        seen = set()
        for row in selected:
            digest = row['sha256']
            if digest in seen:
                continue
            with archive.open('objects/' + digest) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
                    raise ValueError('Snapshot SHA-256 mismatch')
            seen.add(digest)
    return {'verified': True, 'datasets': len({row['dataset'] for row in selected}),
            'files': len(selected), 'unique_objects': len(seen)}


def restore(path, target, dataset=None):
    target = Path(target).absolute()
    try:
        target.lstat()
    except FileNotFoundError:
        pass
    else:
        raise ValueError('Restore destination must not exist')
    verify(path, dataset)  # Validate every selected byte before creating any output.
    with zipfile.ZipFile(path) as archive:
        _, selected = entries(archive, dataset)
        target.mkdir(parents=True, exist_ok=False)
        paths = []
        for row in selected:
            relative = Path(*PurePosixPath(row['relative']).parts)
            output = target / relative if dataset else target / row['dataset'] / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            with archive.open('objects/' + row['sha256']) as source, output.open('xb') as dest:
                shutil.copyfileobj(source, dest, 1048576)
            paths.append({'original': row['path'], 'restored': str(output), 'sha256': row['sha256']})
        (target / 'RESTORED_PATHS.json').write_text(json.dumps(paths, ensure_ascii=False, indent=2), encoding='utf-8')
    return {'restored': True, 'files': len(paths), 'destination': str(target)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['list', 'verify', 'restore'])
    parser.add_argument('archive', type=Path)
    parser.add_argument('--dataset', help='Dataset ID from the list command; omit to restore all into separate ID folders')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    if args.action == 'list':
        with zipfile.ZipFile(args.archive) as archive:
            print(json.dumps(json.loads(archive.read('manifest.json'))['datasets'], ensure_ascii=False, indent=2))
    elif args.action == 'verify':
        print(json.dumps(verify(args.archive, args.dataset)))
    else:
        if args.out is None:
            parser.error('--out is required for restore')
        print(json.dumps(restore(args.archive, args.out, args.dataset)))


if __name__ == '__main__':
    main()
