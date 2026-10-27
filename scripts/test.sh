#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$repo_root"
source scripts/java21.sh
require_java21
bash scripts/build.sh
mkdir -p target/standalone/test-classes
find src/test/java -name '*.java' -type f -print | LC_ALL=C sort | sed 's/^/"/; s/$/"/' > target/standalone/test-sources.txt
find src/test/java -name '*Test.java' -type f -print | LC_ALL=C sort > target/standalone/test-mains.txt
[[ -s target/standalone/test-mains.txt ]] || { echo 'No *Test.java test mains found.' >&2; exit 1; }
"$java21_javac" --release 21 -encoding UTF-8 -cp target/standalone/classes -d target/standalone/test-classes @target/standalone/test-sources.txt
classpath='target/standalone/classes:target/standalone/test-classes'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) classpath='target/standalone/classes;target/standalone/test-classes';; esac
"$java21_java" -ea -cp "$classpath" com.example.proxy.TestSuite
