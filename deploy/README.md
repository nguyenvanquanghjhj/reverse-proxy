# Deployment

`compose/` contains the maintained Dockerfile and Compose topology: PostgreSQL,
three backends, shared storage and the proxy. See
[the operations guide](../docs/OPERATIONS_UPGRADE.md) for secrets and control-plane
commands. Keep the private Compose environment outside Git. Validate syntax with
`docker compose -f deploy/compose/compose.yaml config --quiet` after providing the
required variables.

Runtime volumes are data, not build cache. Do not remove volumes during cleanup.
The `docker-live` CI job builds Java 21, runs the complete TestSuite, tests real
control-plane authentication, streaming, orders and stock, then restarts the stack
and verifies persistence. It uses a new project and retains its data volumes.

Host-side operational and acceptance tools are indexed in
[`scripts/ops/README.md`](../scripts/ops/README.md). Local Docker Engine availability
and CI Docker runtime results must be reported separately.
