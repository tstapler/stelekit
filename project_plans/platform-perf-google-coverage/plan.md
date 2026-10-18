# Implementation Plan: platform, performance and Google integration test coverage gaps

**Project:** platform-perf-google-coverage  
**Date:** 2026-10-14  

---

## Approach

Write targeted unit tests for eight files that are at 0% or critically low JaCoCo coverage. No production source changes unless a test reveals an actual bug. All tests use the existing `MockEngine` + in-memory DB + fake-delegate pattern already established in the codebase.

---

## Phase 1: Desktop I/O layer (0% → target ≥ 60%)

### Task 1.1 — `DesktopFileSystemTest` (jvmTest)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/platform/DesktopFileSystemTest.kt`

Create a subclass that roots `homeDir` under a real temp directory created with:
```kotlin
val tempHome = Files.createTempDirectory(Paths.get(System.getProperty("user.home")), "dfs-test")
```
so `validatePath`'s `startsWith(homePath)` check passes. Clean up in `@AfterTest`.

Test cases (12):
1. `expandTilde replaces leading ~ with homeDir`
2. `expandTilde leaves non-tilde paths unchanged`
3. `readFile returns content for existing file`
4. `readFile returns null for missing file`
5. `readFile returns null for directory`
6. `readFile returns null for file over MAX_FILE_SIZE`
7. `writeFile creates parent directories and writes content`
8. `writeFile returns false for content exceeding MAX_FILE_SIZE`
9. `listFiles returns sorted file names only`
10. `listDirectories returns sorted directory names only`
11. `validatePath rejects path with ..`
12. `validatePath rejects path outside home dir`

### Task 1.2 — `DesktopFilePickerTest` (jvmTest)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/platform/sensor/DesktopFilePickerTest.kt`

Test cases (2):
1. `pickImageFile returns HardwareUnavailable in headless environment` — Bazel/Gradle CI is headless; `GraphicsEnvironment.isHeadless()` returns true, so this test is deterministic.
2. Verify the return type is `Either.Left<DomainError.SensorError.HardwareUnavailable>`.

---

## Phase 2: Google auth and token layer (0% → target ≥ 60%)

### Task 2.1 — `GoogleTokenRefresherTest` (jvmTest)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/google/GoogleTokenRefresherTest.kt`

Use Ktor `MockEngine`. Create a `FakeGoogleTokenStore` that records `saveTokens` calls.

Test cases (5):
1. `refreshGoogleToken success path saves new token and returns accessToken`
2. `refreshGoogleToken HTTP 400 returns Left(HttpError(400))`
3. `refreshGoogleToken body error field returns Left even when HTTP 200`
4. `refreshGoogleToken network exception returns Left(HttpError(-1))`
5. `refreshGoogleToken rotates refresh token when response includes new one`

### Task 2.2 — `JvmGoogleAuthManagerTest` (jvmTest)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/google/JvmGoogleAuthManagerTest.kt`

No `ServerSocket` needed — test only the short-circuit and delegation paths.

Test cases (5):
1. `authenticate returns Left when clientId is blank`
2. `signOut calls tokenStore.clearTokens`
3. `isAuthenticated delegates to tokenStore`
4. `getConnectedEmail delegates to tokenStore`
5. `extractCodeFromRequestLine parses code from well-formed GET request line` (test via a test subclass that exposes the private method, or by calling `authenticate` with a mock that intercepts before the socket step — blank clientId is sufficient to exercise the early-return guard)

Note: `waitForOAuthCallback` is excluded from coverage scope (requires live `ServerSocket`).

---

## Phase 3: Performance instrumentation (0% → target ≥ 60%)

### Task 3.1 — `InstrumentedPageRepositoryTest` (jvmTest)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/performance/InstrumentedPageRepositoryTest.kt`

Use OTel SDK testing artifacts:
```kotlin
val exporter = InMemorySpanExporter.create()
val provider = SdkTracerProvider.builder()
    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
    .build()
val tracer = provider.get("test-scope")
```

Create a `FakePageRepository` delegate that returns pre-configured `Either` values.

Test cases (6):
1. `savePage creates span named page.save with page.uuid attribute`
2. `savePage marks span ERROR and rethrows when delegate throws`
3. `savePages creates span with page.count attribute`
4. `deletePage creates span named page.delete`
5. `toggleFavorite creates span named page.toggleFavorite`
6. `getPageByUuid does not create a span (read-only delegation)`

---

## Phase 4: Google Photos Picker client (48% → target ≥ 70%)

### Task 4.1 — `PhotosPickerApiClientTest` additions (jvmTest)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/google/PhotosPickerApiClientTest.kt`

These augment or companion the existing `GoogleApiClientTest`. Use `PhotosPickerApiClient` directly (not via `GoogleApiClient`) to test the remaining branches.

Test cases (6):
1. `createPhotosPickerSession returns Left when not authenticated`
2. `getPickerSession returns Left on HTTP 500`
3. `downloadPickerMedia appends =d suffix when not already present`
4. `downloadPickerMedia does not double-append =d when already present`
5. `listPickerMediaItems skips items with null mediaFile`
6. `deletePickerSession returns Left on HTTP 500`

---

## Phase 5: Query stats repository (38% → target ≥ 70%)

### Task 5.1 — `QueryStatsRepositoryTest` (businessTest)

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/QueryStatsRepositoryTest.kt`

Set up in-memory `TelemetryDatabase`:
```kotlin
val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
TelemetryDatabase.Schema.create(driver)
val db = TelemetryDatabase(driver)
val repo = QueryStatsRepository(db)
```

Test cases (8):
1. `upsertBatch with empty map is a no-op`
2. `upsertBatch inserts row retrievable by getStatsByVersion`
3. `upsertBatch merges calls on second batch for same key`
4. `getTopByTotalMs returns rows ordered by total_ms descending`
5. `getTopByCalls returns rows ordered by calls descending`
6. `getAllVersions returns distinct version strings`
7. `deleteVersion removes all rows for that version`
8. `QueryStat.estimatePercentile returns correct bucket boundary`

---

## Phase 6: PerfExporter branch coverage (branch 24% → target ≥ 50%)

### Task 6.1 — `PerfExporterBranchTest` additions (businessTest)

Augment `PerfExporterPickerTest.kt` or create a companion `PerfExporterRegressionTest.kt`.

Use the existing fake-delegate pattern from `PerfExporterPickerTest`.

Test cases (4):
1. `detectRegressions returns empty list when no previous version exists`
2. `detectRegressions skips operation with fewer than MIN_REGRESSION_SAMPLES`
3. `detectRegressions skips regression below MIN_REGRESSION_ABS_MS threshold`
4. `detectRegressions handles zero baseline p95 without division error`

---

## Ordering and dependencies

```
Phase 1 (I/O)     ──┐
Phase 2 (Auth)    ──┤──→  All independent; run in parallel
Phase 3 (Perf)    ──┤
Phase 4 (Photos)  ──┤
Phase 5 (Stats)   ──┤
Phase 6 (Export)  ──┘
```

All phases are independent. A single developer can work top-to-bottom, or two developers can split (Phases 1–3 / Phases 4–6).

---

## Verification

After completing all tasks:

```bash
./gradlew :kmp:jvmTest
./gradlew :kmp:jacocoTestReport
# Open kmp/build/reports/jacoco/testJvm/html/index.html
# Verify each targeted file meets its coverage threshold
```

Or with Bazel:
```bash
scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests
```

---

## Files to create

| New test file | Source set | Phase |
|---|---|---|
| `platform/DesktopFileSystemTest.kt` | jvmTest | 1.1 |
| `platform/sensor/DesktopFilePickerTest.kt` | jvmTest | 1.2 |
| `google/GoogleTokenRefresherTest.kt` | jvmTest | 2.1 |
| `google/JvmGoogleAuthManagerTest.kt` | jvmTest | 2.2 |
| `performance/InstrumentedPageRepositoryTest.kt` | jvmTest | 3.1 |
| `google/PhotosPickerApiClientTest.kt` | jvmTest | 4.1 |
| `performance/QueryStatsRepositoryTest.kt` | businessTest | 5.1 |
| `performance/PerfExporterBranchTest.kt` | businessTest | 6.1 |

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| `DesktopFileSystem.homeDir` non-injectable | Use `Files.createTempDirectory` under `user.home`; no source change needed |
| OTel `InMemorySpanExporter` not in testImplementation | Add `opentelemetry-sdk-testing` to `jvmTest` deps if missing |
| `JvmGoogleAuthManager.waitForOAuthCallback` blocks on port | Only test paths that never reach `waitForOAuthCallback` (blank clientId / null httpClient) |
| `DesktopFilePicker` tests pass locally but skip on display | Headless guard is the only tested path; this is the correct behavior |
| `QueryStatsRepository.upsertBatch` uses `RestrictedTelemetryQueries` with a `Mutex` | Construct with `Mutex()` inline — no special setup needed |
