# Deployment

`compose/` contains the maintained Dockerfile and Compose topology: PostgreSQL,
three backends, shared storage and the proxy. See
[the operations guide](../docs/deployment/OPERATIONS_UPGRADE.md) for secrets and control-plane
commands. Keep the private Compose environment outside Git. Validate syntax with
`docker compose -f deploy/compose/compose.yaml config --quiet` after providing the
required variables.

Compose launches `com.example.backend.BackendApplicationServer`; the old Java
name is a compatibility adapter for archived experiments. Maven/native artifacts
are named `reverse-proxy`; image runtime still uses `/app/classes:/app/lib/*`.
`STORE_ENABLED=true` enables the Mộc Store interface. Legacy product/order/document
APIs remain separately compatible and retain their schema and transactions.

Runtime volumes are data, not build cache. Do not remove volumes during cleanup.
The `docker-live` CI job builds Java 21, runs the complete TestSuite, tests real
control-plane authentication, streaming, orders and stock, then restarts the stack
and verifies persistence. It uses a new project and retains its data volumes.

Host-side operational and acceptance tools are indexed in
[`scripts/ops/README.md`](../scripts/ops/README.md). Local Docker Engine availability
and CI Docker runtime results must be reported separately.
