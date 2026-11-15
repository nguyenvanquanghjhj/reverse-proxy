# Operations tools

Run these commands from the checkout root. Private environment files, database
clusters, backup archives and acceptance output belong outside the checkout.
Pass their paths explicitly; scripts resolve application resources from their own
location. No tool requires a particular Windows account or drive letter.

| Task | Entry point | Conditions / evidence |
|---|---|---|
| Storage inventory | `python scripts/ops/storage_audit.py --root .. --out ../maintenance/inventory.json --exclude ../maintenance` | Read-only; hidden files included; links listed without traversal; exit 2 means incomplete measurement. |
| Database + files backup / new-target restore | `python scripts/ops/consistent_bundle.py --help` | Stop all writers; retain PostgreSQL. Restore refuses existing targets. |
| Operator actions in Docker | `python scripts/ops/docker_operator.py --help` | Uses Compose exec; operator port remains private. |
| Health, draining, watchdog | `health_snapshot.py`, `maintenance.py`, `proxy_watchdog.py` | Use explicit QA ports during acceptance. |
| Live transactions / pooling / QoS | `acceptance_live.py --env-file <private-env> --out <new-directory>` | New QA schemas/files; never use the real store env. |
| Streaming / Range / resource bounds | `acceptance_streaming.py --env-file <private-env> --out <new-directory>` | Generates 32/100/256 MiB payloads and a resumed 100 MiB copy. Budget disk space before each run. |
| Docker persistence | `acceptance_docker_ci.py --out <new-directory>` | New project/volumes; retains volumes after tests. |
| LAN measurements | `lan_benchmark.py --help` | Requires real distinct hosts; local traffic is not LAN evidence. |
| Release checks | `release_gate.py --help` | Preflight/release checks do not replace live acceptance. |

Java source and launcher tests remain in `src/main` and `src/test`. Native and
Maven builds write regenerable output to `target`; do not clean classes while an
application JVM or IDE uses them. Python operations regression is
`python -m unittest discover -s scripts/ops -p "test_*.py" -v`.

Keep raw PASS and FAIL logs, CSV, JSON and checksums. On NTFS, offline QA files
may be compressed losslessly after inventory and hash verification; this preserves
paths and content. Report logical and allocated bytes separately. Do not deduplicate
referenced payloads by replacing them with links: the file endpoint rejects links.

See [deployment](../../deploy/README.md), [maintenance audit](../../docs/maintenance/STORAGE_AUDIT.md),
[operations contract](../../docs/OPERATIONS_UPGRADE.md) and
[streaming contract](../../docs/HTTP_STREAMING.md).
