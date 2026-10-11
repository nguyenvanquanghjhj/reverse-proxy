#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$repo_root"
bash scripts/build.sh
mkdir -p target/test-classes
find src/test/java -name '*.java' -type f -print | LC_ALL=C sort | sed 's/^/"/; s/$/"/' > target/test-sources.txt
find src/test/java -name '*Test.java' -type f -print | LC_ALL=C sort > target/test-mains.txt
[[ -s target/test-mains.txt ]] || { echo 'No *Test.java test mains found.' >&2; exit 1; }
javac --release 21 -encoding UTF-8 -cp target/classes -d target/test-classes @target/test-sources.txt
classpath='target/classes:target/test-classes'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) classpath='target/classes;target/test-classes';; esac
java -ea -cp "$classpath" com.example.proxy.TestSuite
