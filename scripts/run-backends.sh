#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ "${1:-}" == --no-build ]]; then shift; else bash scripts/build.sh; fi
pids=()
cleanup() {
  for backend_pid in "${pids[@]}"; do kill "$backend_pid" 2>/dev/null || true; done
  for backend_pid in "${pids[@]}"; do wait "$backend_pid" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
java -cp target/classes com.example.backend.DemoBackendServer 9001 8 20 0 & pids+=("$!")
java -cp target/classes com.example.backend.DemoBackendServer 9002 8 20 0 & pids+=("$!")
java -cp target/classes com.example.backend.DemoBackendServer 9003 8 20 0 & pids+=("$!")
echo 'Backends: 9001, 9002, 9003 (each: 8 slots / 20 ms). Ctrl+C to stop.'
while true; do
  for backend_pid in "${pids[@]}"; do
    if ! kill -0 "$backend_pid" 2>/dev/null; then
      echo "Backend process $backend_pid exited." >&2
      exit 1
    fi
  done
  sleep 1
done
