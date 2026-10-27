#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/java21.sh
require_java21
if [[ "${1:-}" == --no-build ]]; then shift; else bash scripts/build.sh; fi
exec "$java21_java" -jar target/reverse-proxy-demo.jar "${1:-config/application.properties}"
