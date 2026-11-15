# Bounded large-file route

`GET /api/pbl4/files/<name>` and `HEAD` serve public immutable `.bin`, `.pdf`, `.zip`
files from `PBL4_LARGE_FILES_ROOT`, independently of store transactions and their
existing file limits. Both relay and backend use a 32 KiB buffer, with a 1 GiB file
limit. No HTTP upload, redirect, transaction replay, or scheduler replacement is added.

Defaults remain `proxy.streaming.enabled=false`, upstream connection pool false,
and QoS reserved slots zero. Enable streaming only in an explicit test/deployment
profile with `proxy.pdf.bulkhead.max.inflight=2` (smaller than `proxy.max.inflight`),
and a shared, administrator-owned immutable files root on every backend.

`proxy.streaming.timeout.ms=120000` is a separate route budget, capped at 120 seconds.
Header parsing retains its original short ingress deadline. Only after parsing an
eligible read route can its executor timer be promoted once. Orders, ordinary HTTP,
and management requests retain their original deadlines. Backend workers apply the
same separate maximum only to this file route. The streaming bulkhead reserves
space for other work; QoS can be tested independently with the existing flags.

Publish offline by writing a temporary file, flushing it, and atomically renaming it
into the public root. Never modify an existing file in place. The backend opens one
no-follow descriptor for SHA-256 and transfer, checks identity/stat after hashing,
and rejects linked/reparse roots. On Windows, publication can be refused while a
reader holds the old descriptor: retry publication after that reader completes.
Do not place customer documents, credentials, or private application files here.

The endpoint supports single byte ranges, open-ended and suffix ranges, strong
ETag `If-Range`, `If-Match`, and weak `If-None-Match` for GET/HEAD. HEAD ignores Range;
preconditions take precedence. Unsupported/malformed/multipart ranges return 416.
The relay preserves HEAD length and Content-Range. File responses must have a known
Content-Length: unknown-length/chunked file responses fail before downstream headers.
This makes truncated transfers observable at the client. The existing buffered
routes continue handling informational, chunked, EOF, 204 and 304 responses under
their existing strict framing tests. See [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html)
and [RFC 9112](https://www.rfc-editor.org/rfc/rfc9112.html).

After headers commit, a failure terminates the transfer and never sends a second
502. Client write failure and local deadline cancellation release scheduler, QoS,
download and body resources without condemning healthy backend circuits. Upstream
failures remain separately visible. A file-storage 503 is scoped to the service.

`/__proxy/metrics.streaming` and Prometheus expose started/completed/failed/active,
bytes, open bodies, upstream failures, downstream failures and timeouts. This read
route owns a JDK HTTP/1.1 client independently of the ordinary upstream pool flag.
Its actual socket counts are measured with OS process sampling in acceptance;
openBodies is a body ownership gauge, not a socket gauge. Shutdown closes the client.

Run the real acceptance after a Java 21 build:

```powershell
python scripts/ops/acceptance_streaming.py --env-file <private-QA.env> --out <new-directory>
```

It uses a new PostgreSQL schema, three owned JVMs, new public files and a 64 MiB
proxy heap; preserves database/store datasets and raw evidence; checks 32/100/256 MiB SHA, resume,
conditions, path safety, slow clients, disconnects, backend death, storage faults,
atomic replacement, deadlines, QoS/order isolation and actual handles/sockets.
`psutil` and the existing Lab/browser dependencies are required. Multi-host LAN
requires a real second host; loopback and a single Docker bridge do not qualify.

After PASS and JVM shutdown, exactly five reproducible generated inputs (489 MiB)
are size/SHA-verified and released. Their recipe and checksum/removal receipt stay
in `fixture-inputs.json`; measurements and store files remain. `--retain-fixtures`
keeps inputs, and failed runs always retain them for investigation.
