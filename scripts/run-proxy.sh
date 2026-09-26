#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ "${1:-}" == --no-build ]]; then shift; else bash scripts/build.sh; fi
exec java -cp target/classes com.example.proxy.Main "${1:-config/application.properties}"
