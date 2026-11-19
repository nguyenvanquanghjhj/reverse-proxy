# Operations tools

Run these commands from the checkout root. Private environment files, database
clusters, backup archives and acceptance output belong outside the checkout.
Pass private env paths explicitly; scripts resolve application resources from
their own location. Live acceptance defaults to sibling `evidence/` with unique
run directories named `<label>-run-<random-id>` without dates (`PBL4_EVIDENCE_ROOT` can select another absolute root). An
explicit `--out` inside the checkout is rejected before payload/database work.
No tool requires a particular Windows account or drive letter.

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
| Preserved stopped QA datasets | `python scripts/ops/qa_snapshot.py --help` | List/verify/restore the workspace recovery package; restore requires a new destination and verifies every selected SHA before writing. |

Java source and launcher tests remain in `src/main` and `src/test`. Native and
Maven builds write regenerable output to `target`; do not clean classes while an
application JVM or IDE uses them. Python operations regression is
`python -m unittest discover -s scripts/ops -p "test_*.py" -v`.

The maintained application lifecycle is `scripts/store.py` / `scripts/run.ps1`;
both use `app_runtime.py`. Preparation is `prepare_runtime.py` and never initializes
a database. `shop_demo.py` / `shop_prepare.py` only preserve old imports/commands.
Use explicit private env and an absolute `PBL4_RUNTIME_DIR` when testing lifecycle
entrypoints; the default `.shop` belongs to the actual local store.

Keep raw PASS and FAIL logs, CSV, JSON and checksums. On NTFS, offline QA files
may be compressed losslessly after inventory and hash verification; this preserves
paths and content. Report logical and allocated bytes separately. Do not deduplicate
referenced payloads by replacing them with links: the file endpoint rejects links.

After streaming acceptance PASS and JVM shutdown, the default policy validates
the recorded size/SHA of exactly five generated inputs and releases them (489 MiB).
`fixture-inputs.json` keeps their recipe, checksums and removal receipt. Use
`--retain-fixtures` to keep inputs; failed runs always retain them. Database,
store images/PDFs, backup and raw measurements remain. Other tools retain output
until an explicit reviewed cleanup; no recursive cleanup runs automatically.

See [deployment](../../deploy/README.md), [maintenance audit](../../docs/maintenance/STORAGE_AUDIT.md),
[operations contract](../../docs/deployment/OPERATIONS_UPGRADE.md) and
[streaming contract](../../docs/architecture/HTTP_STREAMING.md).
