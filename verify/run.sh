#!/usr/bin/env bash
# Offline verification harness for the agent core.
#
# Why this exists
# ---------------
# This sandbox cannot reach Maven Central / Google Maven (only github, npm and
# PyPI), so Gradle cannot resolve AndroidX, Compose, Room or Ktor. That does not
# stop us from verifying the important logic: the agent core is deliberately free
# of Compose/Room/Ktor so it compiles against plain `android.jar` + coroutines and
# runs on a normal JVM.
#
# What is covered here: JSON, provider wire protocol & SSE, reasoning-effort
# mapping, plan manager, recovery engine, task lifecycle/queueing, tool sandbox
# (path traversal, shell policy), mesh/PNG writers, markdown parsing.
#
# What is NOT covered here (needs a real device/emulator + Gradle): Room DAOs,
# Compose UI, foreground service, notifications, WorkManager, Keystore.
#
# Usage:  ./verify/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."
# The toolchain lives outside the workspace, so a sandbox restart wipes it.
# verify/bootstrap.sh recreates it from PyPI + npm (github/google-maven are blocked).
TC="${MAA_TOOLCHAIN_ROOT:-/home/user/toolchain}"
if [ ! -f "$TC/env.sh" ]; then
  echo "toolchain missing — running verify/bootstrap.sh"
  ./verify/bootstrap.sh
fi
. "$TC/env.sh"
: "${JAVA_HOME:?JAVA_HOME missing}"
: "${ANDROID_JAR:?ANDROID_JAR missing}"
KOTLIN_STDLIB=${KOTLIN_STDLIB:-$TC/kotlin/lib/kotlin-stdlib.jar}

OUT=verify/out
rm -rf "$OUT"; mkdir -p "$OUT"

# Platform-independent core: no Compose, no Room, no Ktor, no WorkManager.
CORE_DIRS="app/src/main/kotlin/com/mobileaiagent/core app/src/main/kotlin/com/mobileaiagent/domain app/src/main/kotlin/com/mobileaiagent/tools app/src/main/kotlin/com/mobileaiagent/agent"
CORE_FILES="app/src/main/kotlin/com/mobileaiagent/providers/Provider.kt \
app/src/main/kotlin/com/mobileaiagent/providers/impl/OpenAiCompatibleProvider.kt \
app/src/main/kotlin/com/mobileaiagent/providers/impl/NvidiaNimProvider.kt \
app/src/main/kotlin/com/mobileaiagent/providers/impl/OpenAiProvider.kt \
app/src/main/kotlin/com/mobileaiagent/providers/impl/AnthropicProvider.kt \
app/src/main/kotlin/com/mobileaiagent/providers/impl/GeminiProvider.kt \
app/src/main/kotlin/com/mobileaiagent/providers/transport/HttpTransport.kt \
app/src/main/kotlin/com/mobileaiagent/providers/transport/OpenAiWire.kt \
app/src/main/kotlin/com/mobileaiagent/providers/transport/JsonParserRef.kt \
app/src/main/kotlin/com/mobileaiagent/service/TaskQueue.kt \
app/src/main/kotlin/com/mobileaiagent/plugins/BuiltInRegistrar.kt \
app/src/main/kotlin/com/mobileaiagent/ui/viewer/MeshRenderer.kt \
app/src/main/kotlin/com/mobileaiagent/ui/viewer/DocumentReader.kt \
app/src/main/kotlin/com/mobileaiagent/ui/viewer/ImageLoader.kt"

MAIN_FILES=$(find $CORE_DIRS -name '*.kt')$(printf ' %s' $CORE_FILES)

cat > "$OUT/BuildConfigStub.kt" <<'KTS'
package com.mobileaiagent
object BuildConfig {
  const val DEFAULT_MAIN_MODEL = "meta/llama-3.3-70b-instruct"
  const val DEFAULT_NIM_BASE_URL = "https://integrate.api.nvidia.com/v1"
  const val VERSION_NAME = "0.1.0"
  const val VERSION_CODE = 1
  const val APPLICATION_ID = "com.mobileaiagent"
}
KTS

echo "== compiling agent core ($(echo $MAIN_FILES | wc -w) files)"
kotlinc -nowarn -language-version 2.2 -api-version 2.2 \
  $MAIN_FILES "$OUT/BuildConfigStub.kt" verify/stubs/org/junit/Junit.kt verify/runner/TestRunner.kt \
  -cp "$COROUTINES_JAR:$ANDROID_JAR" -d "$OUT/harness.jar" 2>&1 | grep -v '^warning:' || true

if [ ! -f "$OUT/harness.jar" ]; then echo "COMPILE FAILED"; exit 1; fi

echo "== compiling tests"
TEST_FILES=$(find app/src/test/kotlin -name '*.kt')
kotlinc -nowarn -language-version 2.2 -api-version 2.2 \
  $TEST_FILES \
  -cp "$OUT/harness.jar:$COROUTINES_JAR:$ANDROID_JAR" -d "$OUT/tests.jar" 2>&1 | grep -v '^warning:' || true

if [ ! -f "$OUT/tests.jar" ]; then echo "TEST COMPILE FAILED"; exit 1; fi
TEST_CLASSES=$(cd "$OUT" && unzip -Z1 tests.jar 2>/dev/null | grep 'Test\.class$' | grep -v '\$' | sed 's#/#.#g; s#\.class$##' || true)
if [ -z "$TEST_CLASSES" ]; then echo "NO TESTS FOUND"; exit 1; fi

echo "== checking Room schema (KSP cannot run offline)"
python3 verify/check_room_schema.py || { echo "ROOM SCHEMA CHECK FAILED"; exit 1; }

echo "== running tests"
java -cp "$OUT/harness.jar:$OUT/tests.jar:$COROUTINES_JAR:$ANDROID_JAR:$KOTLIN_STDLIB" verify.TestRunner $TEST_CLASSES
