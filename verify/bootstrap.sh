#!/usr/bin/env bash
# Rebuilds the offline build toolchain.
#
# Why this is needed
# ------------------
# Google Maven and Maven Central are blocked in this sandbox, so Gradle cannot
# resolve AndroidX/Compose/Room/Ktor and the Android module cannot be assembled
# here. The agent core is deliberately framework-free, so it still compiles and
# runs with plain kotlinc + android.jar + kotlinx-coroutines.
#
# The toolchain lives in /home/user/toolchain, outside the repository, so it is
# not part of the workspace snapshot and disappears when the sandbox restarts.
# This script rebuilds it from the two registries that ARE reachable:
#   * PyPI  -> jdk4py        (a full Temurin JDK)
#   * npm   -> kotlin-compiler (the official Kotlin distribution incl. coroutines)
# plus android.jar from the Sable/android-platforms GitHub mirror.
#
# Usage: ./verify/bootstrap.sh
set -euo pipefail

# Configurable so CI can place the toolchain anywhere; the sandbox default is
# outside the workspace so it is not snapshotted.
ROOT="${MAA_TOOLCHAIN_ROOT:-/home/user/toolchain}"
# Full PyPI version of the jdk4py wheel (Temurin JDK 25.0.5.1+2).
JDK_VER=25.0.5.1
KOTLIN_VER=2.4.21

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

mkdir -p "$ROOT"/{jdk,kotlin,lib,sdk/platforms/android-36}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ------------------------------------------------------------------- JDK
if [ ! -x "$ROOT/jdk/bin/java" ]; then
  log "downloading JDK $JDK_VER (jdk4py)"
  pip download "jdk4py==$JDK_VER" --no-deps -d "$TMP/jdk" >/dev/null
  python3 - "$TMP/jdk" "$TMP/jdkx" <<'PY'
import sys, zipfile, pathlib
src, dst = sys.argv[1], sys.argv[2]
whl = next(pathlib.Path(src).glob("*.whl"))
zipfile.ZipFile(whl).extractall(dst)
PY
  cp -r "$TMP"/jdkx/jdk4py/java-runtime/. "$ROOT/jdk/"
  chmod -R +x "$ROOT/jdk/bin"
fi

# ---------------------------------------------------------------- Kotlin
if [ ! -x "$ROOT/kotlin/bin/kotlinc" ]; then
  log "downloading Kotlin $KOTLIN_VER (npm kotlin-compiler)"
  curl -sL --max-time 600 -o "$TMP/kc.tgz" \
    "https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-$KOTLIN_VER.tgz"
  tar xzf "$TMP/kc.tgz" -C "$TMP"
  cp -r "$TMP"/package/. "$ROOT/kotlin/"
  chmod -R +x "$ROOT/kotlin/bin"
fi
# The distribution ships kotlinx-coroutines-core-jvm; reuse it instead of
# resolving from Maven Central, which is unreachable.
if [ ! -f "$ROOT/lib/kotlinx-coroutines-core.jar" ]; then
  log "linking coroutines from the Kotlin distribution"
  cp "$ROOT/kotlin/lib/kotlinx-coroutines-core-jvm.jar" "$ROOT/lib/kotlinx-coroutines-core.jar"
fi

# ------------------------------------------------------------- android.jar
if [ ! -s "$ROOT/sdk/platforms/android-36/android.jar" ]; then
  log "downloading android.jar (API 36)"
  curl -sL --max-time 900 -H "Accept: application/vnd.github.raw" \
    -o "$ROOT/sdk/platforms/android-36/android.jar" \
    "https://api.github.com/repos/Sable/android-platforms/contents/android-36/android.jar"
fi

cat > "$ROOT/env.sh" <<EOF
export JAVA_HOME=$ROOT/jdk
export PATH=\$JAVA_HOME/bin:$ROOT/kotlin/bin:\$PATH
export ANDROID_JAR=$ROOT/sdk/platforms/android-36/android.jar
export COROUTINES_JAR=$ROOT/lib/kotlinx-coroutines-core.jar
export KOTLIN_STDLIB=$ROOT/kotlin/lib/kotlin-stdlib.jar
EOF

log "toolchain ready"
. "$ROOT/env.sh"
java -version 2>&1 | head -1
kotlinc -version 2>&1 | head -1
