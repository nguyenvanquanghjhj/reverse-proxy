# Containerized installation

Requires Docker Compose V2 and recent Docker Engine. This is **not** a production Internet-facing service: proxy exposes HTTP on 127.0.0.1:8080 only; PostgreSQL is internal. The DB and files are durable named volumes; `down -v` deletes them.

1. `cd deploy/compose`.
2. Copy `.env.example` to `.env`, generate independent random 32+-character password and 64-character tokens, edit `.env`. Do not commit it.
3. `docker compose config` (preflight), `docker compose build` (Java 21 compile), `docker compose up -d`.
4. `docker compose ps`; check `http://127.0.0.1:8080/shop`.
5. Once, optionally provision an admin via:
   `docker compose run --rm -e STORE_ADMIN_EMAIL=admin@example.test -e STORE_ADMIN_PASSWORD='<random password 12+ chars>' init java com.example.store.StoreAdmin admin`
   Note: this uses the init service image/environment but overrides its command; do not run it concurrently with database migrations.
6. `docker compose stop`; `docker compose start` retains data. Never run `down -v` unless you explicitly intend to delete persistent data.

For multi-machine network tests use `../lan`; Compose isolates the stack on a single host. To allow LAN clients use a VPN/reverse proxy providing TLS and authentication; do not publish PostgreSQL or operator routes.

`PBL4_CONTROL_TOKEN` enables operator authentication on loopback management POST routes; existing dashboard POST buttons will require operator-token support if the token is set. Use `java com.example.proxy.server.OperatorCli` **inside** the proxy container to operate it. Environment variables containing secrets must not appear in screenshots or report artifacts.

Docker health dependency ordering is not equivalent to a full distributed failover guarantee.

## Host-to-container operator control

The public data listener keeps /__proxy management loopback-only **inside the proxy container**. A host request to the published 8080 port gets 403 by design: Docker bridge source is not container loopback. Do **not** disable that restriction. Instead run: `python scripts/ops/docker_operator.py --compose-file deploy/compose/compose.yaml metrics` (or `strategy`, `drain backend1:9001`, `resume backend1:9001`). The script executes OperatorCli inside the proxy container without publishing any management port. Only trusted Docker operators can run it.
