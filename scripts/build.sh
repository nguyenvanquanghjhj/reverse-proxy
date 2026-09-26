#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$repo_root"
if [[ -L target || -L target/classes || -L target/test-classes || -L target/reverse-proxy-demo.jar ]]; then
  echo 'Refusing to clean linked build outputs.' >&2
  exit 1
fi
# Fixed paths under the verified checkout; never delete the repository itself.
mkdir -p target
[[ "$(cd target && pwd -P)" == "$repo_root/target" ]] || exit 1
rm -rf -- "$repo_root/target/classes" "$repo_root/target/test-classes"
rm -f -- "$repo_root/target/reverse-proxy-demo.jar"
mkdir -p target/classes
find src/main/java -name '*.java' -type f -print | LC_ALL=C sort | sed 's/^/"/; s/$/"/' > target/main-sources.txt
[[ -s target/main-sources.txt ]] || { echo 'No main Java sources found.' >&2; exit 1; }
javac --release 21 -encoding UTF-8 -d target/classes @target/main-sources.txt
jar --create --file target/reverse-proxy-demo.jar --main-class com.example.proxy.Main -C target/classes .
echo 'Built target/reverse-proxy-demo.jar (Java 21 bytecode).'
