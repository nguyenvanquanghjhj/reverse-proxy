#!/usr/bin/env bash
# Resolve every tool from the same JDK. Java 26 is outside this project's tested contract.
require_java21() {
  java21_javac="$(command -v javac)"
  local jdk_bin compiler_version runtime_version
  jdk_bin="$(dirname -- "$java21_javac")"
  java21_java="$jdk_bin/java"
  java21_jar="$jdk_bin/jar"
  compiler_version="$("$java21_javac" -version 2>&1)"
  [[ "$compiler_version" =~ ^javac\ 21(\.|[[:space:]]|$) ]] || {
    echo "JDK 21 required; found $compiler_version. Set JAVA_HOME and PATH." >&2; return 1;
  }
  runtime_version="$("$java21_java" -version 2>&1)"
  [[ "$runtime_version" =~ version\ \"21(\.|\"|-) ]] || {
    echo "JDK 21 runtime required beside javac; found $runtime_version." >&2; return 1;
  }
  [[ -x "$java21_jar" || -x "$java21_jar.exe" ]] || { echo 'JDK jar tool missing.' >&2; return 1; }
}
