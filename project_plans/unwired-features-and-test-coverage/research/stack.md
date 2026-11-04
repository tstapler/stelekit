# Stack research: unwired-features-and-test-coverage

Labels: VERIFIED = file opened or command run this session; INFERRED = from tool knowledge, not run here.
Paths relative to repo root. Date: 2026-10-27.

## (a) Coverage and mutation per module

**Existing tooling (VERIFIED, `kmp/build.gradle.kts`)**
- JaCoCo 0.8.12 (`:1518`). `jacocoTestReport` (JVM: `commonMain`, `jvmCommonMain`, `jvmMain` classes) and `jacocoAndroidTestReport` (Robolectric `testDebugUnitTest.exec`, `:1686`). XML out: `kmp/build/reports/jacoco/jacoco.xml` and `jacoco-android.xml`.
- `diffCoverage` task (`:1703`): `diff-cover` run through `uvx`, threshold 80, unions the two XMLs. Test selection (`-PdiffCoverage.selectTests`) runs only tests tied to changed classes, because the canonical suite is Bazel (no JaCoCo) and full Gradle `jvmTest` hangs/OOMs on 2-core runners (comment `:1541`).
- `mutationTest` (`:1738`): PIT `pitest-command-line:1.17.4`, JVM bytecode only (androidMain is never a mutation target, `:1615`), classes default to those changed vs base, `mutate.maxClasses` 40.
- CI: `ci.yml` job `diff-coverage` (`:180`) gates coverage, runs mutation report-only (`-Pmutate.threshold=0`, `:226-232`). Detekt 1.23.7 job at `ci.yml:133` with a baseline ratchet (`:157-166`). `bazel-ci.yml` has no coverage steps (grep for jacoco/pitest/coverage returned only ci.yml hits; `.bazelrc` has no `coverage` config).
- Toolchain: Kotlin 2.4.10 (`settings.gradle.kts:9`), Gradle 9.5.0 wrapper.

**Real current numbers (VERIFIED, parsed from existing XMLs; STALE and PARTIAL)**
- `jacoco.xml` and `jacoco-android.xml` are dated 2026-10-16 and come from change-scoped test selection, not a full suite. Treat as "what the last selected run touched", not a repo map. No `kmp/build/reports/pitest` exists, so there is **no mutation number on this machine**.
- JVM report totals: lines 23,489 / 69,331 covered (33.9%), branches 11,969 / 63,106 (19.0%).
- Android (Robolectric) report totals: lines 8,479 / 70,498 (12.0%), branches 6,089 / 63,876 (9.5%).
- Highest-covered JVM packages: `lexer` 99% lines, `merge` 93%, `util` 91%, `theme` 90%, `components/tags` 88%, `cache` 83%, `capture` 80%. Large zero-or-near-zero packages (JVM report): `annotate` 0/2,649, `screens/git` 6/2,158, `assets` 0/1,078, `git` 84/1,731 (5%), `merge` (UI, 712) 0, `google` 0/619, `vault` 4/620, `asset` 0/328, `pipeline` 0/171, `rtc` 0/26. Package grouping in my parse is by directory depth, so same-named packages in different trees (e.g. `merge` vs `dev/stapler/stelekit/merge`) are separate rows.
- Last fresh test runs on disk: `kmp/build/test-results/jvmTest` has 4 tests (single class, 2026-10-27), `testDebugUnitTest` 0. I did not run a full suite: `jvmTest` has OOM/hang history and needs a display (`scripts/jvm-display-check.sh`), so a full run is a multi-minute-to-hour job with a wall-clock blocker, not a cheap command.

**Recommendation (INFERRED)**
1. Repo-wide map = one scheduled (nightly/weekly) full JaCoCo run split by tier: `businessTest` + `commonTest` on JVM (fast, headless), `testDebugUnitTest` on Robolectric (headless), `jvmTest` UI under `jvm-display-check.sh`. Merge the XMLs with a small script grouping by package; the parse above is the starting point.
2. Mutation: keep PIT 1.17.4 per-PR on changed classes; add a nightly tiered run over `db/`, `repository/`, `parser/`, `merge/`, `git/` only (pure/business logic). Do not mutate Compose UI. PIT has no KMP/Android story; androidMain gets coverage only.
3. Kover (JetBrains) is the KMP-native alternative to JaCoCo and instruments Android+JVM in one report, but would replace working gates; not recommended unless the XML union becomes painful (INFERRED, not evaluated).
4. Bazel `bazel coverage //kmp:jvm_tests` produces LCOV but is not configured; adding it is unproven here (UNVERIFIED).

## (b) Detecting unreferenced/unwired code

**What I ran (VERIFIED)**: a token-index scan (`scratchpad/scan2.py`) over `kmp/src` + `androidApp/src`, excluding generated, build and test dirs. For every non-private top-level `class/object/interface`, it checks whether the name appears as a token in any *other* production file.
- Result: 1,460 non-private top-level declarations, **199 not referenced from any other production file**. 61 of those 199 are referenced by tests; 4 are named in manifests/build files; 133 are referenced nowhere else.
- All requirement-listed items reproduce: `AssetIndexService`, `AssetMoveService`, `AssetPipelineService` (tests only); `OcrPlugin`, `CloudClaudePlugin`, `CloudVisionPlugin`, `PdfTextPlugin`, `OnDeviceLabelingPlugin`, `RTCManager`, `RTCOp`, `VectorSearch`, `InferenceBridge`, `InferenceWorker`, `PlaceholderInferenceWorker`, `RemoteInferenceWorker`, `DatalogEngine`, `VisualQueryBuilder`, `JsPluginBridge`, `DesktopSyncScheduler` (no test, no prod reference).
- Still noisy: includes DTOs/serialization models, Android `Activity`/`Receiver`/widget classes (manifest-registered), SQLDelight generated-table types, iOS/wasm `actual` classes resolved by `expect`, and benchmark mains. Output list: `scratchpad/scan2.out` (session scratchpad, not committed).

**Why it is imprecise and what is better**
| Tool | Verdict |
|---|---|
| Name/token scan (above) | Cheap triage only. Misses reachability: a class referenced only by another dead class counts as live; cannot see `expect/actual`, DI by reflection, manifest entries. |
| Detekt `UnusedPrivateMember`/`UnusedParameter`/`UnusedImports` (all `active: true`, `kmp/config/detekt/detekt.yml:318-328`) | Already on, but file-local only. Detekt 1.23.x has no unused-public-declaration rule. Keep; does not answer this question. |
| Kotlin compiler warnings | Same: private/local only. |
| IntelliJ "Unused declaration" inspection, headless (`idea.sh inspect`) | Cross-file, understands `expect/actual` and entry-point annotations; best precision for a one-off audit. Needs an IDE install and project import (INFERRED; not available/run here). |
| R8 `-printusage` | True reachability from entry points, but release minify is off (`androidApp/build.gradle.kts:60`, `isMinifyEnabled = false`; benchmark proguard file exists at `:73`). A one-off R8 run with `-dontobfuscate -dontoptimize -printusage` on the Android release variant would list unreachable classes and give Android-only ground truth (INFERRED). Fastest precise option for androidMain+commonMain. |
| Runtime evidence: JaCoCo 0% classes | A class with 0% in both prod-like and test runs is dead or untested; combining the XMLs with the scan separates "unwired but tested" (T- rows) from "dead and untested" (-- rows). Zero-coverage `rtc` (0/26) fits. |
| ast-grep (`sg` at `~/.cargo/bin/sg`) | Good for *confirming* a candidate's wiring points (e.g. constructor call sites `AssetPipelineService($$$)`), not for global reachability. Use per-candidate. |

Recommended pipeline: scan2 (candidate set) minus manifest/expect-actual/DTO classes, then confirm each with `sg` call-site query, then one R8 `-printusage` pass for Android reachability. Re-run scan as a CI ratchet (count of "T-"/"--" rows must not grow).

**Finding for scheduler question (VERIFIED)**: `DesktopSyncScheduler` has no caller anywhere. `GitSyncService.startPeriodicSync` (`commonMain/.../git/GitSyncService.kt:751`, fetch-only loop) also has **no production caller** (only a doc mention in `WorkManagerSyncScheduler.kt:34`). So desktop currently has no scheduled fetch at all; Android has WorkManager only.

## (c) SQL console per driver (existing stack)

VERIFIED from code and `project_plans/dev-console-git-sync-fix/research/stack.md` §3:
- JVM: `PooledJdbcSqliteDriver` (`jvmMain/.../db/PooledJdbcSqliteDriver.kt`), pool of 8 for file DBs, 1 for `:memory:` (`DriverFactory.jvm.kt:94`); props applied per connection (WAL, `busy_timeout=10000`, `DriverFactory.jvm.kt:47-52`). sqlite-jdbc via `app.cash.sqldelight:sqlite-driver:2.3.2` (`build.gradle.kts:173`, comment says 3.51.3.0).
- Android: `app.cash.sqldelight:android-driver:2.3.2` over `com.github.requery:sqlite-android:3.49.0` (`:272-276`); `createReadDriver()` returns a second read connection (`DriverFactory.android.kt:174`); `PRAGMA journal_mode=WAL`, `busy_timeout=5000`.
- `ReadWriteRouterDriver` routes queries to read driver and `execute` to the write driver; `TimingDriverWrapper` wraps both.
- Wasm: `@sqlite.org/sqlite-wasm 3.46.1-build1` OPFS SAH pool (`:245`), async driver. No read-only second connection (matches the plan's "Web has no read-only connection" risk).
- No existing use of `query_only` or read-only open flags (grep over `kmp/src` returned nothing). Pooled JVM connections make `PRAGMA query_only` unreliable per the prior research; the SQL statement classifier (ADR-002) stays the primary guard. Defence in depth options: on Android set `PRAGMA query_only=ON` on the dedicated read driver; on JVM open an extra single non-pooled connection with `SQLiteConfig.setReadOnly(true)` (xerial `org.sqlite.SQLiteConfig`, INFERRED API, verify before use).
- Raw `driver.executeQuery(null, sql, mapper, params)` is established practice (`MigrationRunner.kt`, `performance/QueryPlanRepository.kt:23`); writes must go through `DatabaseWriteActor.execute` (`db/DatabaseWriteActor.kt:761`) with `@OptIn(DirectSqlWrite::class)`.
- Cancel: SQLDelight does not expose `Statement.cancel()`; use `runInterruptible` on `PlatformDispatcher.DB` + LIMIT cap + deadline (UNVERIFIED for requery).

## (d) Desktop scheduling

VERIFIED: `DesktopSyncScheduler` (`jvmMain/.../git/DesktopSyncScheduler.kt`, 46 lines) implements `BackgroundSyncScheduler` (`commonMain/.../git/BackgroundSyncScheduler.kt`: `schedule(intervalMinutes)`, `cancel()`), owns `CoroutineScope(SupervisorJob() + PlatformDispatcher.IO)` and a `delay` loop calling `onTick`. Gaps against the requirements: no `CoroutineExceptionHandler` (a throwing `onTick` ends the loop silently), no per-tick logging or last-success time, no guard for `intervalMinutes <= 0` (a zero interval busy-loops: `delay(0)`), `delay`-first so no tick at schedule time, and it duplicates `GitSyncService.startPeriodicSync`. ADR-004 (`dev-console-git-sync-fix/decisions/ADR-004-scheduled-full-sync.md`) decides the desktop timer is `startPeriodicSync` inside `GitSyncService`, scheduled through `ScheduledSyncPolicy`, with a 15-minute floor on Android only. Stack choice: kotlinx-coroutines (already present, with `coroutines-swing 1.10.2`, `build.gradle.kts:171`) is sufficient; no Quartz/`java.util.Timer` needed. Test with `kotlinx-coroutines-test` virtual time (`runTest` + `advanceTimeBy`) in `commonTest` for the policy and in `jvmTest` for the loop. Decision needed: delete `DesktopSyncScheduler` or make it the single desktop driver of `startPeriodicSync`.

## (e) Image pipeline plugin dependencies

VERIFIED versions (`kmp/build.gradle.kts`, `MODULE.bazel`):
| Capability | JVM | Android | Notes |
|---|---|---|---|
| PDF text | `org.apache.pdfbox:pdfbox:3.0.4` (`:195`; `MODULE.bazel:296`) | `com.tom-roush:pdfbox-android:2.0.27.0` (`:303`; `MODULE.bazel:449`) | Impl: `JvmPdfTextExtractor`, `AndroidPdfTextExtractor`, `IosPdfTextExtractor`; common port `PdfTextExtractor`, `NoOpPdfTextExtractor` in `MlServiceStubs.kt`. Different PDFBox major lines (3.x vs 2.0.27), so behavior may differ. |
| Image labeling | `com.microsoft.onnxruntime:onnxruntime:1.26.0` (`:198`) via `OnnxImageLabeler` | `com.google.mlkit:image-labeling:17.0.9` (`:309`) via `MlKitImageLabeler` | ONNX versions differ: JVM 1.26.0 vs Android `onnxruntime-android:1.20.0` (`:346`, used for depth: `OnnxMonocularDepthEstimator`, wired in `SteleKitApplication.kt:198`). |
| OCR | none | `com.google.mlkit:text-recognition:16.0.1` (`:312`) | **Declared but unused**: no Kotlin file imports `TextRecognition`. `OcrPlugin` takes an `ImageLabeler` and joins label strings (`OcrPlugin.kt:20-27`) and returns `OcrText` - that is labeling, not OCR. Real OCR needs a new `TextRecognizer` port + ML Kit impl (Android) and a JVM decision (e.g. Tesseract/ONNX), not just wiring. |
| Cloud | `CloudClaudePlugin`, `CloudVisionPlugin` with `CloudEnrichmentConfig` | same | Need Ktor/HTTP client (already in repo), consent UX, cost caps; opt-in per requirements. |
| GenAI | - | `mlkit:genai-prompt:1.0.0-beta2` (`MODULE.bazel:429`) | Voice formatter only; beta. |

Other: `MlKitImageLabeler`, `AndroidPdfTextExtractor`, `JvmPdfTextExtractor`, `OnnxImageLabeler` are themselves in the unreferenced-outside-own-file scan (wired only if a composition root constructs them; none found in `androidApp/` besides the depth estimator). Bazel mirrors the Gradle versions in `MODULE.bazel:296,429-449`; `image-pipeline/implementation/plan.md` covers the display-side image framework (Coil 3.2.0, okio 3.17.0), not these ML plugins, so the plugin deps have no plan yet.

## Open gaps
- No fresh full-suite coverage or any mutation score exists locally; needs a scheduled run (wall-clock blocker, not a command).
- R8 `-printusage` and IntelliJ headless inspection are untried here.
- xerial read-only connection API and requery cancel behavior are unverified.
