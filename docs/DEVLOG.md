# MobileAIAgent — Development Log

A general-purpose Android AI workspace: persistent background execution, a hybrid
local/remote agent, structured planning, and real file/CAD/3D tooling.

This log records what was **implemented and verified**, what could **not** be
verified in this environment, and what remains.

---

## Verification status at a glance

| Check | Command | Result |
|---|---|---|
| Agent core tests | `./verify/run.sh` | **119 passed, 6 skipped**¹ |
| Full app type-check (all 79 sources) | `./verify/typecheck-all.sh` | **0 errors** |
| Gradle/APK build | GitHub Actions `build` job | **built in CI** (not in the sandbox) |

¹ The 6 skipped tests spawn real processes. This sandbox blocks `posix_spawn`
(`Permission denied`), so they are skipped here and run in CI, where
`MAA_FAIL_ON_SKIP=1` turns any skip into a failure.

### Where the APK is built

**GitHub Actions builds it**, because a CI runner reaches Google Maven and Maven
Central. The workflow is `.github/workflows/build.yml`:

| Job | What it proves | Needs network |
|---|---|---|
| `verify` | 119 JVM tests + whole-app type-check + Room/AGP/Kotlin pre-flight | no (only npm/PyPI/GitHub) |
| `build` | Real Gradle: KSP/Room, Compose, **debug APK**, JUnit tests | yes |

**Current status: both jobs green, APK built and published** (run 45976509830).

Download: **<https://github.com/8dia479/MobileAiAgent/releases/tag/debug-apk>**
(the `debug-apk` release → `app-debug-<sha>.apk`, 21.1 MB). The repository is
private, so you must be signed in to GitHub to fetch it.

### Why Artifacts is empty even on a green build

This is a **GitHub account limit, not a build problem**. Verified across five
consecutive runs (46022378168 is the latest):

| Step | Outcome |
|---|---|
| `Assemble debug APK` | **success** — `app-debug.apk`, 21.1 MB |
| `Publish APK to a rolling release` | **success** |
| `Upload APK` | reports success, stores nothing |

Every run, including green ones, contributes **0 artifacts to the repository**
(`actions/artifacts?per_page=100` → `total_count: 0`). The annotations say why:

```
Failed to CreateArtifact: Artifact storage quota has been hit.
Unable to upload any new artifacts. Usage is recalculated every 6-12 hours.
```

Because `upload-artifact` is `continue-on-error` the step shows green, which makes
the empty Artifacts tab look like a broken upload rather than a full quota.

* It resets every 6–12 h; the upload starts working again by itself, no code
  change needed.
* **Release assets are not covered by that quota**, so that is where the APK is.
* The run summary states this explicitly so the empty tab is not misread.

### What CI caught that the offline checks could not

Series of four real failures, each fixed:

1. `bootstrap.sh` requested `jdk4py==25.0.5.1.1` (a version that does not exist)
   and used `pip`, which is not always on a runner's PATH.
2. `KtorHttpTransport` called `client.get`/`post` without importing them — the
   stubs had declared them as members of `HttpClient`, so the call compiled
   offline and failed against real Ktor.
3. `Card(onClick = …, Modifier…)` positionally, and `Alignment.Vertical.Top` /
   `Horizontal.Start`, which **do not exist** in Compose. The stub had invented
   those members. Verified against
   `androidx-main .../compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/Alignment.kt`.
4. `MeshRendererTest` used `javax.imageio`, which is not on the Android classpath.

Each one is now covered by a check that runs without network access
(`check_gradle_preflight.py`, `check_kotlin_language.py`, faithful stubs).

### Why the sandbox has no APK

This sandbox reaches only `github.com`, `codeload.github.com`, `api.github.com`,
`registry.npmjs.org`, `pypi.org` and `files.pythonhosted.org`. Google Maven and
Maven Central are **blocked**, so Gradle cannot resolve AGP, Kotlin, Compose,
Room, Ktor or WorkManager, and the Android module cannot be assembled.

That does not leave the project unverified:

* **`verify/run.sh`** compiles the framework-free core with plain `kotlinc` and
  runs 97 real JVM tests. The core deliberately avoids Android/Compose/Room so
  this is possible.
* **`verify/typecheck-all.sh`** compiles **every** source file (core, data,
  service, providers, plugins, UI, `MainActivity`) against faithful API stubs in
  `verify/stubs/` plus the real `android.jar`. This catches wrong argument names,
  wrong types, missing imports, wrong packages, nullable misuse and
  non-exhaustive `when` — the errors a first Gradle build would surface.

It caught **30+ genuine defects** that would otherwise have appeared only at the
user's first build. Examples: `Card(onClick = …)` called with a positional
`Modifier` first; `padding(horizontally = …)` instead of `horizontal`; `Column(
horizontalAlignment = Alignment.End)` instead of `Alignment.Horizontal.End`;
an early `return` inside a `@Composable`; `Modifier.clickable(...)` used as a
factory; `NewTaskParams`, `rememberSaveable` and `ModelCapabilities` not imported
(or imported from the wrong package).

**`verify/bootstrap.sh`** rebuilds the entire toolchain from PyPI (jdk4py) and npm
(kotlin-compiler), because `/home/user/toolchain` lives outside the workspace
snapshot and is lost on sandbox restart.

---

## Phase status

The specification's roadmap has six phases. Delivered so far:

### Phase 1 — Foundation (complete)
* Kotlin/Compose project scaffold, `minSdk 26`, `compileSdk 35`, JDK 17,
  desugaring, version catalog.
* **Dependency-free JSON** (`core/json`): parser, writer, pretty printer, depth
  limits, surrogate handling. No `org.json`, so it behaves identically on JVM and
  Android (4 test classes).
* Domain model: tasks, runs, events, messages, tool journal, checkpoints, plans,
  models, projects, plugins, settings, `ReasoningEffort`, `ExecutionMode`.
* **Persistent background execution** as a first-class citizen, never an
  afterthought:
  * `TaskManager` owns the lifecycle; the Chat screen never does.
  * One task at a time, queued FIFO, dispatched through a mutex
    (`TaskManagerTest.queuedTasksAreOrderedAndRunOneAtATime` proves
    single-threaded execution and ordering).
  * Foreground service (`dataSync`) with an ongoing notification and Pause/Stop
    actions.
  * `BootReceiver` + `WorkManager` watchdog; `RecoveryEngine` marks interrupted
    runs `INTERRUPTED` rather than pretending they finished, and never blindly
    replays mutagenic tool calls.
  * Room schema: 10 entities, 9 DAOs, `RoomStores`, migrations.
* Settings via DataStore; API keys via `EncryptedSharedPreferences`/Keystore.
* Tool registry with permissions, cancellation, structured results; 40+ built-in
  tools across files, terminal, archive, text, research, productivity, geometry,
  creative and PNG.
* Path-escape and shell-whitelist enforcement (tested).

### Phase 2 — Agent core (complete)
* `AgentLoop` (ReAct): model → tool calls → real execution → results → model.
* `DefaultTaskRunner` orchestrates a run: recovery context, plan injection,
  delegation, checkpoints, token accounting, error classification
  (`AUTH`/`NETWORK`/`RATE_LIMIT`/`CANCELLED`/…), retry/backoff, `WAITING_NETWORK`
  instead of failure.
* Providers: NVIDIA NIM (default), OpenAI, Anthropic, Gemini,
  OpenAI-compatible, custom. Reasoning effort maps to each provider's native
  parameter and is **omitted rather than silently substituted** when unsupported.
* Streaming SSE reader + tool-call fragment accumulator (tested).
* Delegation: a single `use_model` tool; one Secondary invocation at a time;
  secondaries get no app tools. Independent reasoning effort per model.

### Phase 3 — Planning (complete)
* `PlanManager`: `Projects/<name>/Plans/active.plan.json` plus revision history.
* Structured plans (`plan_version`, `plan_id`, `title`, `status`, timestamps,
  `tasks[subtasks]`, statuses pending/in_progress/completed/failed/blocked/
  cancelled).
* The model **cannot** mark a task complete without verification evidence
  (`PlanManagerTest.modelCannotSelfMarkCompletedAtCreation`,
  `AgentLoopIntegrationTest.planGenerationWritesStructuredJsonOnDisk`).
* Replanning preserves completed work and bumps the revision.
* Three execution modes enforced by filtering the tool manifest (PLAN mode hides
  every mutagenic tool).

### Phase 4 — Local tools (complete)
File read/write/append/list/search/delete/move, archive, text utilities, terminal
sandbox, research (HTTP), productivity (CSV/JSON/markdown), geometry.

### Phase 5 — Creative tools (complete)
Mesh generation and serialization (binary/ASCII STL, OBJ+MTL, glTF-binary),
primitives, booleans-by-merge, transforms; DXF writer; PNG encoder.

### Phase 6 — Remote & plugins (complete, with stated limits)
* Remote NIM/OpenAI-compatible gateways, custom base URLs, search endpoint.
* Plugin system: declarative manifests, per-plugin permission grants, SHA-256
  integrity, remote and sandboxed-script kinds.

### UI (Phase 1–6 screens)
Home, Chat, Workspace, Task Manager, Tool Manager, Plugin Manager, Model Manager,
Settings, Advanced. Adaptive shell: bottom bar on phones, `NavigationRail` at
≥720 dp.

---

## Viewers — what is real and what is not

| Format | Status |
|---|---|
| STL / OBJ / GLB | **Real 3D viewer.** Pure-Kotlin software rasterizer: Z-up turntable camera, per-pixel 1/z depth buffer, two-light Lambert shading, orbit/zoom/pan/spin, auto-suppressed wireframe. Verified by rendering to PNG and inspecting the images. |
| PNG / JPEG / WebP / GIF / BMP | **Real viewer.** Subsampled `BitmapFactory` decode (memory-guarded and unit-tested). |
| PDF | **Real text extraction** (FlateDecode, `Tj`/`TJ`, escapes), page count. Encrypted files and scans are reported honestly. No page rasterisation. |
| DOCX | **Real** paragraphs + table rows from `word/document.xml`. |
| XLSX / CSV / TSV | **Real** cell grid; shared strings, column gaps, sheet tabs. |
| STEP / IGES | **Not previewed.** Needs a B-rep kernel; the UI says so and offers conversion instead. |
| Images/media playback, PPTX | Not implemented; reported as unsupported. |

---

## Known issues and limits

### Android/OS limits (stated, not worked around)
* **Foreground service types.** `dataSync` FGS has runtime limits on Android 14+
  (`minSdk 26` here). Long runs are additionally backed by WorkManager, and the
  watchdog re-dispatches queued work.
* **Battery optimisation.** OEM killers (especially aggressive ones) can still
  stop a service; recovery on next launch is the mitigation, and the app never
  hides the ongoing notification.
* **No dynamic code loading.** External plugins are declarative (remote endpoint
  or sandboxed script) because loading arbitrary DEX/JAR at runtime is a security
  hazard. Stated in the Plugins screen too.
* **Background starts** require a user-visible trigger on Android 12+.

### Not verifiable in this environment
* Actual rendering/layout of the Compose UI (it type-checks; nobody has seen it
  on a device here).
* Room DAO SQL (KSP runs only in a real build).
* Foreground service behaviour, notifications, boot recovery, Keystore,
  `EncryptedSharedPreferences` — all need a device/emulator.
* `KtorHttpTransport` against a live network.

### Known gaps
* No OCR for scanned PDFs.
* No video/audio playback.
* No PDF page rasterisation.
* `MeshRenderer` is CPU-only: meshes above ~60 k triangles take notice.
* Git history was reset twice by sandbox restarts; the working tree is the source
  of truth and has been re-committed each time.

---

## Test inventory (97)

| Suite | Count | Covers |
|---|---|---|
| `JsonParserTest` / `JsonAndWireTest` | 10 | JSON round-trip, escapes, depth limits |
| `OpenAiWireTest` | 6 | Request encoding, streaming accumulation, effort omission |
| `SseReaderTest` | 5 | SSE framing, CRLF, multiline data |
| `ReasoningEffortTest` | 3 | Six exact levels, no silent mapping |
| `RecoveryEngineTest` | 7 | Interrupted tasks, orphaned runs, no blind replay |
| `PlanManagerTest` | 6 | Plan files, revisions, evidence-gated completion |
| `FileToolsTest` | 10 | Path traversal, shell policy, CRUD |
| `AgentLoopIntegrationTest` | 6 | Real tool execution end-to-end, plan generation |
| `TaskExecutionTest` | 4 | Runner outcomes, network deferral, cancellation |
| `TaskManagerTest` | 7 | Queueing, ordering, pause/cancel/resume, no duplicate runs |
| `MeshRendererTest` | 12 | Projection, shading, depth, zoom, PNG validity |
| `DocumentReaderTest` | 12 | CSV/DOCX/XLSX/PDF parsing from real byte streams |
| `ImageSamplingTest` | 4 | Subsampling memory guard |
| `TerminalPolicyTest` | 21 | Allow-list, chaining, substitution, real process execution |

---

## How to work on this project

```bash
./verify/bootstrap.sh      # rebuild the offline toolchain (once per sandbox)
./verify/run.sh            # compile core + run 97 tests
./verify/typecheck-all.sh  # type-check all 78 sources
```

On a machine with network access to Google Maven, the normal path also works:

```bash
./gradlew assembleDebug    # APK
./gradlew test             # unit tests
```

---

## Immediate next steps

1. Build with real Gradle once Maven access exists; fix KSP/Room and Compose
   resource issues.
2. On-device pass: notifications, boot recovery, process death and resume.
3. Replace the type-check stubs with the real artifacts (delete `verify/stubs/`)
   once Gradle resolves dependencies, keeping `verify/run.sh` for the core.
4. Add OCR and PDF page rendering if required by users.
