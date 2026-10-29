#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/java21.sh
require_java21
if [[ "${1:-}" == --no-build ]]; then shift; else bash scripts/build.sh; fi
pids=()
cleanup() {
  for backend_pid in "${pids[@]}"; do kill "$backend_pid" 2>/dev/null || true; done
  for backend_pid in "${pids[@]}"; do wait "$backend_pid" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$java21_java" -cp target/reverse-proxy-demo.jar com.example.backend.DemoBackendServer 9001 8 20 0 & pids+=("$!")
"$java21_java" -cp target/reverse-proxy-demo.jar com.example.backend.DemoBackendServer 9002 8 80 0 & pids+=("$!")
"$java21_java" -cp target/reverse-proxy-demo.jar com.example.backend.DemoBackendServer 9003 8 240 0 & pids+=("$!")
echo 'Backends: port 9001=20ms, 9002=80ms, 9003=240ms (8 slots each). Ctrl+C to stop.'
while true; do
  for backend_pid in "${pids[@]}"; do
    if ! kill -0 "$backend_pid" 2>/dev/null; then
      echo "Backend process $backend_pid exited." >&2
      exit 1
    fi
  done
  sleep 1
done
