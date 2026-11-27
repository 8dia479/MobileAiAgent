#!/usr/bin/env bash
# Type-checks the ENTIRE app (core + data + service + plugins + UI) offline.
#
# Google Maven and Maven Central are unreachable in this sandbox, so AndroidX,
# Room, DataStore, WorkManager and Ktor cannot be resolved. verify/stubs/ provides
# faithful-enough declarations of exactly the API surface this app uses, and
# android.jar provides the real Android platform classes.
#
# This catches: wrong member names, wrong argument types/names, missing imports,
# nullable misuse, impossible casts, non-exhaustive `when`, bad override
# signatures — i.e. everything the compiler normally catches in a Gradle build.
#
# It cannot catch: resource/manifest errors, annotation-processor output (Room DAO
# SQL, KSP), runtime behaviour, or rendering.
#
# Usage: ./verify/typecheck-all.sh [--keep]
set -uo pipefail
cd "$(dirname "$0")/.."
TC="${MAA_TOOLCHAIN_ROOT:-/home/user/toolchain}"
if [ ! -f "$TC/env.sh" ]; then ./verify/bootstrap.sh; fi
. "$TC/env.sh"

OUT=verify/out/all
mkdir -p "$OUT"

SOURCES=$(find app/src/main/kotlin -name '*.kt')
STUBS=$(find verify/stubs verify/stubs_support -name '*.kt')

kotlinc -nowarn -language-version 2.2 -api-version 2.2 \
  $SOURCES $STUBS \
  -cp "$COROUTINES_JAR:$ANDROID_JAR" -d "$OUT/app.jar" 2> "$OUT/errors.txt"

ERRS=$(grep -c 'error:' "$OUT/errors.txt" 2>/dev/null || true)
ERRS=${ERRS:-0}
if [ "$ERRS" != "0" ]; then
  echo "== $ERRS type errors =="
  grep 'error:' "$OUT/errors.txt" | sed 's#app/src/main/kotlin/com/mobileaiagent/##' | sort | uniq -c | sort -rn | head -${HEAD:-40}
  echo
  echo "full log: $OUT/errors.txt"
  exit 1
fi
echo "full app type-check OK ($(echo $SOURCES | wc -w) sources)"
