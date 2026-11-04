#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# setup-toolchain.sh — fetch a JDK 17 and the Kotlin compiler so tools/jvm.sh
# can compile and run the core modules.
#
# Only needed where a JDK is not already installed (a bare container, say).
# Both artefacts come from mirrors of the upstream releases:
#
#   jdk4py           https://pypi.org  (Temurin 17, manylinux wheel)
#   kotlin-compiler  https://registry.npmjs.org  (JetBrains' own package)
#
# Usage:
#   tools/setup-toolchain.sh                 install into ~/.toolchain
#   TOOLCHAIN_DIR=/path tools/setup-toolchain.sh
# ---------------------------------------------------------------------------
set -euo pipefail

TC="${TOOLCHAIN_DIR:-$HOME/.toolchain}"
JDK4PY_VERSION="${JDK4PY_VERSION:-17.0.9.2}"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.4.21}"

JAVA_BIN="$TC/jdkw/jdk4py/java-runtime/bin/java"
KOTLINC="$TC/kc/package/bin/kotlinc"

mkdir -p "$TC"

# ---------------------------------------------------------------------- JDK
if [[ -x "$JAVA_BIN" ]]; then
  echo "== JDK already present at $JAVA_BIN =="
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME:-}/bin/java" ]]; then
  echo "== using existing JAVA_HOME=$JAVA_HOME =="
else
  echo "== fetching jdk4py $JDK4PY_VERSION =="
  url="$(curl -sS "https://pypi.org/pypi/jdk4py/$JDK4PY_VERSION/json" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for u in d["urls"]:
    if "manylinux" in u["filename"] and "x86_64" in u["filename"]:
        print(u["url"])
        break
')"
  if [[ -z "$url" ]]; then
    echo "ERROR: could not resolve a jdk4py wheel URL for $JDK4PY_VERSION" >&2
    exit 1
  fi
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/jdk.whl" "$url"
  mkdir -p "$TC/jdkw"
  (cd "$TC/jdkw" && unzip -qo "$tmp/jdk.whl")
  rm -rf "$tmp"
fi

# ------------------------------------------------------------------ kotlinc
if [[ -x "$KOTLINC" ]]; then
  echo "== kotlinc already present at $KOTLINC =="
else
  echo "== fetching kotlin-compiler $KOTLIN_VERSION =="
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/kc.tgz" \
    "https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-$KOTLIN_VERSION.tgz"
  mkdir -p "$TC/kc"
  tar xzf "$tmp/kc.tgz" -C "$TC/kc"
  rm -rf "$tmp"
fi

# ------------------------------------------------------------------- verify
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  export JAVA_HOME="$TC/jdkw/jdk4py/java-runtime"
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo
echo "== verifying =="
if [[ ! -x "$JAVA_BIN" && ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "ERROR: no usable java found" >&2
  exit 1
fi
java -version 2>&1 | head -1
if [[ ! -x "$KOTLINC" ]]; then
  echo "ERROR: kotlinc missing at $KOTLINC" >&2
  exit 1
fi
echo "kotlinc: $KOTLINC"
echo
echo "Toolchain ready. Run tools/jvm.sh to compile and test the core modules."
