# Backend protection validation — 2026-10-14

Baseline: `e044c1f Harden backend lifecycle and failure handling`, clean `main`
tracking `origin/main` at the initial validation. No UI, load-balancing
score, dependency, existing benchmark workload or transport reuse changes.

Implemented per-backend circuit breaker with epoch-safe completion and single
half-open probe; optional bounded token buckets by peer IP; JSON/Prometheus metrics;
three new Java test mains and an additional downstream-disconnect assertion in the
existing hardening test. Pooling and retry are deferred as described in ARCHITECTURE.

## Verification actually run

- `scripts/test.ps1`: build and all **10 test mains PASS**, Temurin JDK
  **21.0.12.1+1**, Windows. Compilation uses `--release 21`. Includes HTTP framing,
  deadline, lifecycle, health, all balancing strategies, breaker HTTP fail/recover,
  64 concurrent half-open contenders, old completion isolation, cancelled probe,
  per-backend isolation, limiter concurrency/refill/expiry/capacity, spoofed headers,
  JSON 429/Retry-After/request ID, management exemption and resource accounting.
- `python -m unittest discover -s scripts -p 'test_*.py'`: **16 tests PASS**.
- `scripts/benchmark_resilience.py --seed 2026 --repeats 2`: **72 requests PASS**
  across eight isolated JVM clusters, JDK 21. Every circuit-enabled arm dispatched
  3/9 requests and refused six while open; every rate-enabled arm admitted 3/9 and
  returned six HTTP 429. Disabled controls dispatched 9/9. Both circuit arms
  recovered to CLOSED with zero backend in-flight leases.
- Artifacts (generated, ignored): `target/feature-test-jdk21.log`,
  `target/test-reports/`, `benchmarks/results/resilience-20261014-2/` containing raw
  requests, snapshots, frozen sources/classes/config, logs, metadata/checksums and
  `validation.json` with `passed=true`. These are behavior checks, not a claim of
  production capacity or superior algorithm performance.

JDK 21 was found in the VS Code Java extension after initial download attempts.
Only child-process JAVA_HOME/PATH was changed. The initial fallback JDK 26 run
compiled successfully but failed the existing oversized-upload HTTP test; it is
not counted as a passing validation and JDK 26 compatibility is not claimed.
The first resilience run failed because its cleanup check expected zero active
handlers while its own monitoring request was active. The check now accounts for
that request and independently requires zero backend in-flight leases. The failed
artifact folder `resilience-20261014` was retained; no requests or timings were
filtered to make the successful run pass.

Maven was not installed; the dependency-free PowerShell build and the same TestSuite
used by Maven were run. No commit, push, pooling or retry was performed.

## Follow-up validation before commit (2026-10-14)

The results above describe the first implementation. Follow-up review corrected
two behaviors: proxy body/header policy limits no longer trip a CLOSED circuit or
reset its failure count; an inconclusive/cancelled HALF_OPEN probe releases its
ticket and stays HALF_OPEN, without incrementing open count. Adaptive learning
and exchange error metrics still record policy-rejected exchanges. HTTP regression
tests cover repeated oversized bodies/headers as well as backend status errors.

Pooling remains deferred after reading the entire transport. The explicit
`proxy.connection-pool.enabled=false` flag rejects true at startup. The alternative
is an optional loopback-only management listener with its own bounded ingress
executor, while preserving the legacy monitoring routes. Readiness includes data
ingress capacity; JSON and Prometheus expose both listener task counts.

On Temurin **21.0.12.1+1**, `java -version`, `javac -version`, `jar --version`,
`scripts/build.ps1` and `scripts/test.ps1` were actually run. Final suite: **11 test
mains PASS** (`target/backend-followup-tests-3.log`). The new ManagementListenerTest
checks data/management saturation independently, slow-header expiry, bounded task
rejection, rate exemption, legacy routes, readiness recovery, HEAD/405/404, disabled
mode, close-before-start, shutdown with both parsers occupied and occupied-port
rollback. A real startup cleanup issue was found: a bound but unstarted JDK HTTP
server could retain its selector registration after stop. Rollback now starts the
dispatcher solely for cleanup with executor admission already disabled, then stops
it. The regression verifies the data port can immediately be rebound.

The first management test also closed its partial client request early: JDK treated
EOF as header completion and consumed a token. The fixture now keeps the connection
open until its ingress deadline, matching the slow-header scenario being tested.
Failed logs are retained in target. An intermediate single-file test invocation was
affected by IDE-generated classes; the passing result is from a full clean script
build, not that invocation.

Python unit tests: **16 PASS**. Strict JSON parsing of both monitoring artifacts and
a syntax/type/finite-value check of **20 Prometheus samples PASS**; only configured
backend labels are emitted. Resilience experiment (`--seed 2026 --repeats 2`):
**72 requests PASS** with the same on/off workload and dispatch assertions. This
is still behavior validation, not a throughput improvement claim. Generated
artifacts from the final source snapshot are in
`benchmarks/results/backend-followup-final-20261014/` (72 requests PASS) and
`target/test-reports/` (including `management-metrics.json` and `management.prom`).

The completed source, tests, configuration and documentation are intended for a
single backend commit after the final diff checks; commit/push status is reported
separately in the task response. No UI changes or dependency additions are included.
