#!/usr/bin/env python3
"""Verified PostgreSQL backup + read-only verification + restore to NEW database.

This never drops, truncates or overwrites an existing database. Uses pg_dump/pg_restore,
not SQL text export; credentials are passed via PGPASSWORD, never command arguments.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]

def load_env(file):
    result = {}
    for row in file.read_text(encoding="utf-8-sig").splitlines():
        line = row.strip()
        if not line or line.startswith("#"): continue
        if "=" not in line: raise ValueError("Malformed environment file line")
        key, val = line.split("=", 1)
        result[key.strip()] = val.strip()
    return result

def connect_info(file):
    env = load_env(file)
    raw = env.get("SHOP_DB_URL", "")
    if not raw.startswith("jdbc:postgresql://"):
        raise ValueError("Expected JDBC PostgreSQL URL in .env.store")
    parsed = urlsplit(raw[5:])
    if parsed.username or parsed.password or not parsed.hostname:
        raise ValueError("Credentials belong in SHOP_DB_USER / SHOP_DB_PASSWORD")
    db = parsed.path.lstrip("/")
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_-]{0,62}", db):
        raise ValueError("Unsupported database name")
    port = parsed.port or 5432
    user = env.get("SHOP_DB_USER", "")
    if not user: raise ValueError("Missing SHOP_DB_USER")
    if not env.get("SHOP_DB_PASSWORD"): raise ValueError("Missing SHOP_DB_PASSWORD")
    return {"host": parsed.hostname, "port": str(port), "database": db,
            "user": user, "password": env["SHOP_DB_PASSWORD"]}

def tool(name):
    local = ROOT / ".runtime" / "postgres" / "bin" / (name + (".exe" if os.name == "nt" else ""))
    if local.is_file(): return str(local)
    candidate = shutil.which(name)
    if not candidate: raise FileNotFoundError(name + " not found in PATH or .runtime/postgres/bin")
    return candidate

def pg_args(con, database=None):
    return ["--host", con["host"], "--port", con["port"], "--username", con["user"],
            "--no-password", "--dbname", database or con["database"]]

def run_pg(args, con, capture=False):
    environment = os.environ.copy()
    environment["PGPASSWORD"] = con["password"]
    environment["PGCONNECT_TIMEOUT"] = "5"
    process = subprocess.run(args, env=environment, capture_output=capture, text=capture,
                             timeout=240, check=False)
    if process.returncode:
        # Never include full command because credentials might be embedded by other callers.
        detail = (process.stderr or "").strip()[:400] if capture else "See PostgreSQL tool output"
        raise RuntimeError("PostgreSQL command failed (exit " + str(process.returncode) + "): " + detail)
    return process.stdout if capture else ""

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""): digest.update(chunk)
    return digest.hexdigest()

def secure_write_json(path, obj):
    # JSON metadata intentionally excludes all passwords/connection secret parameters.
    tmp = path.with_name("." + path.name + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try: os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    except OSError: pass
    os.replace(tmp, path)

def backup(con, out):
    out.mkdir(parents=True, exist_ok=True)
    base = "pbl4-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    dest = out / (base + ".dump")
    fd, path = tempfile.mkstemp(dir=out, prefix=".pbl4-backup-", suffix=".partial")
    os.close(fd)
    part = Path(path)
    try:
        try: os.chmod(part, stat.S_IRUSR | stat.S_IWUSR)
        except OSError: pass
        run_pg([tool("pg_dump"), *pg_args(con), "--format=custom", "--file", str(part),
                "--no-owner", "--no-privileges"], con)
        if part.stat().st_size < 1: raise RuntimeError("Empty archive")
        # Listing the archive verifies it has a recognized pg_dump format and TOC.
        run_pg([tool("pg_restore"), "--list", str(part)], con, capture=True)
        digest = sha256(part)
        size = part.stat().st_size
        os.replace(part, dest)
        manifest = {"format": "pg_dump_custom", "created_utc": datetime.now(timezone.utc).isoformat(),
                    "filename": dest.name, "database": con["database"], "host": con["host"],
                    "size_bytes": size, "sha256": digest,
                    "restore_policy": "create new database only; no overwrite"}
        secure_write_json(out / (base + ".json"), manifest)
        print(json.dumps({"backup": str(dest), "manifest": str(out / (base + '.json'))}, ensure_ascii=False))
    finally:
        part.unlink(missing_ok=True)

def validated_archive(manifest_file, con):
    meta = json.loads(manifest_file.read_text(encoding="utf-8"))
    name = meta.get("filename", "")
    if not isinstance(name, str) or Path(name).name != name or not name.endswith(".dump"):
        raise ValueError("Invalid archive filename in manifest")
    archive = manifest_file.parent / name
    if not archive.is_file(): raise FileNotFoundError("Backup file missing")
    if archive.stat().st_size != meta.get("size_bytes") or sha256(archive) != meta.get("sha256"):
        raise RuntimeError("Checksum or size mismatch; restore refused")
    run_pg([tool("pg_restore"), "--list", str(archive)], con, capture=True)
    return archive, meta

def restore(con, manifest_file, target, acknowledge):
    if not re.fullmatch(r"[a-z][a-z0-9_]{2,62}", target) or target in {"postgres", "template0", "template1", con["database"]}:
        raise ValueError("Target must be a NEW, safe lowercase database name, different from source")
    if acknowledge != "CREATE_NEW_DATABASE_ONLY":
        raise ValueError("Explicit --ack CREATE_NEW_DATABASE_ONLY is required")
    archive, meta = validated_archive(manifest_file, con)
    # Check existence before creating; createdb itself also fails if the name already exists.
    rows = run_pg([tool("psql"), *pg_args(con, "postgres"), "--tuples-only", "--no-align",
                   "--command", "SELECT 1 FROM pg_database WHERE datname='" + target + "'"], con, capture=True)
    if rows.strip(): raise RuntimeError("Target DB already exists; no overwrite permitted")
    run_pg([tool("createdb"), "--host", con["host"], "--port", con["port"],
            "--username", con["user"], "--no-password", target], con, capture=True)
    try:
        run_pg([tool("pg_restore"), *pg_args(con, target), "--exit-on-error",
                "--no-owner", "--no-privileges", str(archive)], con, capture=True)
    except Exception:
        print("RESTORE INCOMPLETE: new database was retained for inspection; source was NOT modified.", file=sys.stderr)
        raise
    print(json.dumps({"restored_new_database": target, "source_backup": meta.get("filename"),
                      "verified_sha256": meta.get("sha256")}, ensure_ascii=False))

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", type=Path, default=ROOT / ".env.store")
    sub = parser.add_subparsers(dest="action", required=True)
    backup_cmd = sub.add_parser("backup", help="Create pg_dump custom archive plus SHA-256 manifest")
    backup_cmd.add_argument("--out", type=Path, default=ROOT.parent / "pbl4-backups")
    verify_cmd = sub.add_parser("verify", help="Read-only checksum + pg_restore TOC verification")
    verify_cmd.add_argument("manifest", type=Path)
    restore_cmd = sub.add_parser("restore-new", help="Restore only into an UNUSED newly created database")
    restore_cmd.add_argument("manifest", type=Path)
    restore_cmd.add_argument("--target-db", required=True)
    restore_cmd.add_argument("--ack", default="")
    args = parser.parse_args(argv)
    con = connect_info(args.env)
    if args.action == "backup": backup(con, args.out.resolve())
    elif args.action == "verify":
        file, meta = validated_archive(args.manifest.resolve(), con)
        print(json.dumps({"verified": str(file), "bytes": file.stat().st_size,
                          "sha256": meta["sha256"]}, ensure_ascii=False))
    elif args.action == "restore-new": restore(con, args.manifest.resolve(), args.target_db, args.ack)

if __name__ == "__main__":
    try: main()
    except (ValueError, RuntimeError, FileNotFoundError, subprocess.TimeoutExpired) as exc:
        print("ERROR:", str(exc), file=sys.stderr)
        sys.exit(2)
