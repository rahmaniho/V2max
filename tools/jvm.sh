#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# jvm.sh — compile and run the pure-JVM `core` modules without Gradle/Android.
#
# The Android SDK is not reachable from this environment (dl.google.com and
# services.gradle.org both refuse connections), so the platform modules cannot
# be built here. The core modules are deliberately free of Android imports so
# they CAN be compiled and executed, which keeps the highest-risk logic
# (link parsing, Xray config generation, server selection) under real test.
#
# Usage:
#   tools/jvm.sh            compile core + run the test suite
#   tools/jvm.sh --compile  compile only
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="${TOOLCHAIN_DIR:-$HOME/.toolchain}"
JAVA_HOME="${JAVA_HOME:-$TC/jdkw/jdk4py/java-runtime}"
KOTLINC="$TC/kc/package/bin/kotlinc"
OUT="$ROOT/build/jvm"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "ERROR: no JDK at $JAVA_HOME" >&2
  exit 1
fi
if [[ ! -x "$KOTLINC" ]]; then
  echo "ERROR: no kotlinc at $KOTLINC" >&2
  exit 1
fi

SOURCES=()
while IFS= read -r -d '' f; do SOURCES+=("$f"); done < <(
  find "$ROOT/core" -path '*/src/main/kotlin/*' -name '*.kt' -print0
)
TESTS=()
while IFS= read -r -d '' f; do TESTS+=("$f"); done < <(
  find "$ROOT/core" -path '*/src/test/kotlin/*' -name '*.kt' -print0
)

if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo "ERROR: no core sources found under $ROOT/core" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/test-classes"

echo "== kotlinc: ${#SOURCES[@]} main source file(s) =="
"$KOTLINC" -nowarn -jvm-target 17 -d "$OUT/classes" "${SOURCES[@]}" 2>&1 |
  grep -vE '^warning: ' || true

if [[ "${1:-}" == "--compile" ]]; then
  echo "== compiled to $OUT/classes =="
  find "$OUT/classes" -name '*.class' | wc -l | xargs echo "   class files:"
  exit 0
fi

if [[ ${#TESTS[@]} -eq 0 ]]; then
  echo "no test sources; compiled OK"
  exit 0
fi

echo "== kotlinc: ${#TESTS[@]} test source file(s) =="
"$KOTLINC" -nowarn -jvm-target 17 -cp "$OUT/classes" -d "$OUT/test-classes" "${TESTS[@]}" 2>&1 |
  grep -vE '^warning: ' || true

echo "== running tests =="
# The stdlib must be on the runtime classpath: kotlinc does not bundle it into
# plain output directories the way `-include-runtime` does for a fat jar.
STDLIB="$TC/kc/package/lib/kotlin-stdlib.jar"
if [[ ! -f "$STDLIB" ]]; then
  echo "ERROR: kotlin-stdlib.jar not found at $STDLIB" >&2
  exit 1
fi
# Force UTF-8 so the Persian strings in the fixtures print correctly; Java 17
# otherwise falls back to the platform encoding.
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$OUT/classes:$OUT/test-classes:$STDLIB" \
  ir.v2max.core.test.MainKt
