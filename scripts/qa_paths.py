"""One location policy for local live acceptance; no QA payloads inside the checkout."""
import hashlib
import json
import os
from pathlib import Path
import uuid
import stat

ROOT = Path(__file__).resolve().parents[1]


def evidence_path(value):
    path = Path(value).resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError('Live QA output must be outside the checkout, runtime and shared storage')
    return path


def new_evidence_path(label):
    if not label or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in label):
        raise ValueError('Evidence label must contain lowercase letters, digits or hyphens')
    parent = Path(os.environ.get('PBL4_EVIDENCE_ROOT', str(ROOT.parent / 'evidence')))
    if not parent.is_absolute():
        raise ValueError('PBL4_EVIDENCE_ROOT must be absolute')
    return evidence_path(parent / (label + '-run-' + uuid.uuid4().hex[:12]))


def release_generated_inputs(directory, artifacts, recipe):
    """Release only new-run temporary inputs after writers stop; keep the receipt.

    artifacts maps relative paths to the bytes/SHA recorded by their generator.
    Validate the whole plan before removing any entry. Evidence, DBs and shared
    files are never discovered/deleted recursively by this function.
    """
    original = Path(directory).absolute()
    if original.is_symlink() or getattr(original.lstat(), 'st_file_attributes', 0) & 0x400:
        raise ValueError('Refusing linked fixture root')
    root = evidence_path(original)
    plan = []
    for relative, expected in artifacts.items():
        relative = Path(relative)
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Fixture path escapes its run directory')
        path = root / relative
        cursor = path
        while cursor != root:
            info = cursor.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
                raise ValueError('Refusing linked fixture input')
            cursor = cursor.parent
        info = path.stat()
        if not stat.S_ISREG(info.st_mode):
            raise ValueError('Fixture input must be a regular file')
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if info.st_size != expected['bytes'] or digest != expected['sha256']:
            raise ValueError('Fixture changed; retaining every input')
        plan.append({'path': relative.as_posix(), 'bytes': info.st_size, 'sha256': digest,
                     'inode': info.st_ino, 'mtime_ns': info.st_mtime_ns})
    receipt = {'status': 'validated', 'recipe': recipe, 'files': plan, 'removed_logical_bytes': 0}
    target = root / 'fixture-inputs.json'
    if target.exists():
        raise ValueError('Fixture cleanup receipt already exists')
    target.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    for item in plan:
        path = root / item['path']; info = path.lstat()
        if info.st_ino != item['inode'] or info.st_size != item['bytes'] or info.st_mtime_ns != item['mtime_ns']:
            raise ValueError('Fixture identity changed during release')
        path.unlink()
        receipt['removed_logical_bytes'] += item['bytes']
    receipt['status'] = 'removed'
    target.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    return receipt
