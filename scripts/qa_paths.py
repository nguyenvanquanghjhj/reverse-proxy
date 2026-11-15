"""One location policy for local live acceptance; no QA payloads inside the checkout."""
from datetime import datetime, timezone
import os
from pathlib import Path
import uuid

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
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    return evidence_path(parent / (label + '-' + stamp + '-' + uuid.uuid4().hex[:8]))
