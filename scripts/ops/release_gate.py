#!/usr/bin/env python3
"""Prevent accidental public publication of local database, credentials or generated files.

Static release checks and optional full existing Java test suite. Machine-readable JSON result.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SECRET_PATHS = [".env.store", ".shop", ".runtime", "shared-files", "lib", "target",
                "deploy/compose/.env", "config/lan.properties", "pbl4-backups", "pbl4-operations"]
REQUIRED = ["README.md", "pom.xml", "src/main/java/com/example/proxy/Main.java",
            "config/application.properties", "db/migrations/V001__shop.sql", "scripts/test.ps1"]

def cmd(root, *args):
    return subprocess.run(args, cwd=root, text=True, capture_output=True, timeout=600)

def gate(root, run_tests=False, stage="release"):
    checks = []
    def append(name, okay, detail): checks.append({"name":name,"ok":bool(okay),"detail":detail})
    for path in REQUIRED:
        append("required:"+path,(root/path).is_file(),"source file present")
    git = cmd(root, "git", "rev-parse", "--is-inside-work-tree")
    append("git_repository",git.returncode==0 and git.stdout.strip()=="true","Git checkout required")
    if git.returncode == 0:
        tracked = cmd(root,"git","ls-files","-z")
        paths = set(tracked.stdout.split("\0"))
        sensitive = [p for p in paths if any(p == target or p.startswith(target + "/") for target in SECRET_PATHS)]
        append("private_not_tracked", len(sensitive)==0, "unexpected tracked paths: "+str(sensitive[:20]))
        gitignore = cmd(root,"git","check-ignore","--no-index","-q",".env.store")
        append("env_ignored",gitignore.returncode==0,".env.store must not be public")
        diff = cmd(root,"git","status","--porcelain")
        append("clean_worktree",diff.returncode==0 and (stage == "preflight" or not diff.stdout.strip()),
               "dirty worktree is expected in preflight, but forbidden in release")
    if run_tests:
        runner = root / "scripts/test.ps1"
        if os.name != "nt":
            append("java_test_suite", False, "Use Windows PowerShell on the project machine")
        else:
            result = cmd(root,"powershell","-NoProfile","-ExecutionPolicy","Bypass","-File",str(runner))
            append("java_test_suite",result.returncode==0,"exit="+str(result.returncode)+"; see terminal/log")
    return {"utc":datetime.now(timezone.utc).isoformat(),"passed":all(c["ok"] for c in checks),"checks":checks}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo",type=Path,default=ROOT)
    p.add_argument("--run-tests",action="store_true")
    p.add_argument("--stage",choices=["preflight","release"],default="release")
    p.add_argument("--out",type=Path)
    a=p.parse_args()
    result=gate(a.repo.resolve(),a.run_tests,a.stage)
    print(json.dumps(result,ensure_ascii=False,indent=2))
    if a.out:
        a.out.parent.mkdir(parents=True,exist_ok=True)
        a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return 0 if result["passed"] else 1

if __name__=="__main__":raise SystemExit(main())
