#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$repo_root"
source scripts/java21.sh
require_java21
if [[ -L target || -L target/standalone || -L target/standalone/classes || -L target/standalone/test-classes || -L target/reverse-proxy-demo.jar ]]; then
  echo 'Refusing to clean linked build outputs.' >&2
  exit 1
fi
# Fixed paths under the verified checkout; never delete the repository itself.
mkdir -p target
[[ "$(cd target && pwd -P)" == "$repo_root/target" ]] || exit 1
mkdir -p target/standalone
[[ "$(cd target/standalone && pwd -P)" == "$repo_root/target/standalone" ]] || exit 1
rm -rf -- "$repo_root/target/standalone/classes" "$repo_root/target/standalone/test-classes"
rm -f -- "$repo_root/target/reverse-proxy-demo.jar"
mkdir -p target/standalone/classes
find src/main/java -name '*.java' -type f -print | LC_ALL=C sort | sed 's/^/"/; s/$/"/' > target/standalone/main-sources.txt
[[ -s target/standalone/main-sources.txt ]] || { echo 'No main Java sources found.' >&2; exit 1; }
"$java21_javac" --release 21 -encoding UTF-8 -d target/standalone/classes @target/standalone/main-sources.txt
"$java21_jar" --create --file target/reverse-proxy-demo.jar --main-class com.example.proxy.Main -C target/standalone/classes .
echo 'Built target/reverse-proxy-demo.jar (Java 21 bytecode).'
