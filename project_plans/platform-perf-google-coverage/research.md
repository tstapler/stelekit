# Research: platform, performance and Google integration test coverage gaps

**Project:** platform-perf-google-coverage  
**Date:** 2026-10-14  

---

## 1. Existing test infrastructure

### Test patterns in use

From `GoogleApiClientTest` (`jvmTest`):

- Ktor `MockEngine` pattern is well-established; all HTTP tests use it.
- Anonymous object `GoogleTokenStore` stubs are used inline (no Mockito).
- `runTest { }` from `kotlinx-coroutines-test` wraps every coroutine test.
- `assertIs<Either.Right<T>>`/`assertIs<Either.Left<T>>` from `kotlin.test` with `assertEquals` on the inner value.

From `PerfExporterPickerTest` (`businessTest`):

- In-memory fakes for `SpanRepository`, `HistogramWriter`, `FileSystem` — no mocking framework.
- Follows the pattern: create a minimal fake that records calls, then assert post-call state.

From `PlatformFileSystemPickerTest` / `ReplaceFileAtomicallyTest` (`jvmTest`):

- Uses `kotlin.io.path.createTempDirectory()` for real temp-dir I/O.
- Cleans up via `@AfterTest` or `use { }` scope.

### Available test dependencies (from `kmp/build.gradle.kts`)

- `kotlin.test` (JUnit 5 backend on JVM)
- `kotlinx-coroutines-test`
- `ktor-client-mock`
- `kotest-assertions-core`, `kotest-property` (in `commonTest`)
- SQLDelight in-memory driver for `TelemetryDatabase` tests
- OpenTelemetry SDK for `InstrumentedPageRepository` (OTel `InMemorySpanExporter` available as a test helper)

---

## 2. File-by-file analysis

### 2.1 `DesktopFileSystem` (117 lines, 0% covered)

**Testability:** High — pure JVM, no UI, no coroutines. Every public method takes a `String` path.

**Key behaviors to test:**
- `validatePath` rejects `..`, `../`, `\u0000`, paths > 4096 chars, paths outside `~`
- `expandTilde` replaces leading `~` with `System.getProperty("user.home")`
- `readFile` returns null for missing file, directory, file > 100 MB
- `writeFile` creates parent directories if absent
- `listFiles`/`listDirectories` return sorted lists; return empty on non-existent path
- `fileExists`/`directoryExists` boolean checks

**Test home:** `jvmTest` (uses `java.io.File`, JVM-only)

**Approach:** `createTempDirectory()` for write/list tests; patch `homeDir` via subclass or accept that `homeDir` reads from `System.getProperty("user.home")` (not injectable — use real temp dirs under `$HOME` or mock via reflection; simpler to test with paths already under `$TMPDIR` which is under home on most systems).

**Risk:** `validatePath` requires absolute path to start with `homeDir`. On CI, `java.io.tmpdir` may not be under `user.home`. Solution: override `homeDir` in test by subclassing `DesktopFileSystem` and overriding the `val`.

### 2.2 `GoogleTokenRefresher` (42 lines, 0% covered)

**Testability:** High — standalone suspend function, takes `HttpClient` as parameter.

**Key behaviors to test:**
- HTTP 200 with valid JSON → saves tokens, returns `Right(accessToken)`
- HTTP 4xx (e.g. 400) → returns `Left(HttpError(400, ...))`
- HTTP 200 with `error` field in body → returns `Left(HttpError(400, ...))`
- Network exception → returns `Left(HttpError(-1, ...))`
- Refresh token rotation: if response includes a new `refresh_token`, it is passed to `tokenStore.saveTokens`; if absent, original refresh token is passed

**Test home:** `jvmTest` or `businessTest` (Ktor `MockEngine` works in both)

**Pattern:** Same as `GoogleApiClientTest` — anonymous `GoogleTokenStore` stub + `MockEngine`.

### 2.3 `JvmGoogleAuthManager` (79 lines, 0% covered)

**Testability:** Partial — `waitForOAuthCallback()` requires a real `ServerSocket`. Unit-testable parts:

- `authenticate()` returns `Left` immediately when `clientId` is blank
- `authenticate()` returns `Left(HttpError(500, ...))` when `httpClient` is null (the `?: return@withContext` guard)
- `signOut()` calls `tokenStore.clearTokens()`
- `isAuthenticated()` delegates to `tokenStore.isAuthenticated()`
- `getConnectedEmail()` delegates to `tokenStore.getEmail()`
- `extractCodeFromRequestLine` (private, but testable indirectly or via reflection)

**`waitForOAuthCallback` is not unit-testable** without a real port being bound. Mark for exclusion from coverage or leave as "integration-only".

**Pattern:** Subclass `JvmGoogleAuthManager` in test with `waitForOAuthCallback` overridden to return a pre-set code string; or test only the public surface that doesn't need it (blank clientId path gives instant Left without touching the socket).

### 2.4 `InstrumentedPageRepository` (93 lines, 0% covered)

**Testability:** High — OpenTelemetry provides `InMemorySpanExporter` which can verify span names, attributes, and status codes.

**Key behaviors to test:**
- `savePage` → span named `"page.save"` with attribute `"page.uuid"` is started and ended
- Exception in `delegate.savePage` → span is marked `ERROR`, exception propagates
- `deletePage` → span named `"page.delete"`
- `savePages` → span named `"page.savePages"` with `"page.count"` attribute
- Read-only methods (`getPageByUuid`, etc.) → no spans created

**Pattern:**
```kotlin
val exporter = InMemorySpanExporter.create()
val tracer = SdkTracerProvider.builder()
    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
    .build().get("test")
val repo = InstrumentedPageRepository(fakeDelegate, tracer)
```

**Test home:** `businessTest` or `jvmTest` (OTel SDK is a JVM-only dependency per build.gradle.kts).

### 2.5 `DesktopFilePicker` (47 lines, 0% covered)

**Testability:** Limited in headless environments.

- `pickImageFile()` returns `Left(HardwareUnavailable)` when `GraphicsEnvironment.isHeadless()` — this path is testable in CI since Bazel/Gradle run headless.
- The `JFileChooser` path cannot be tested without a display.

**Test home:** `jvmTest`

**Coverage impact:** The headless branch alone covers ~30% of the file's lines. The remaining Swing code is untestable in CI; acceptable to leave it uncovered.

### 2.6 `PhotosPickerApiClient` (132 lines, 48% covered)

**Already has some coverage** (likely from `GoogleApiClientTest` which delegates through `GoogleApiClient` to `PhotosPickerApiClient`). The uncovered paths are:

- Unauthenticated path (token store returns null) for each method
- `downloadPickerMedia` with `=d` suffix already present (no double-append)
- `listPickerMediaItems` where `mediaFile` is null (item skipped)
- `deletePickerSession` 204 vs 200 paths

**Test home:** `jvmTest`

### 2.7 `QueryStatsRepository` (108 lines, 38% covered)

**Testability:** High — backed by SQLDelight, can use in-memory `TelemetryDatabase`.

**Key behaviors to test:**
- `upsertBatch` with empty map → early return, no DB call
- `upsertBatch` with non-empty map → rows appear in `getStatsByVersion`
- `upsertBatch` with duplicate key (second call merges via `mergeQueryStat`)
- `getTopByTotalMs`, `getTopByCalls`, `getAllVersions`
- `deleteVersion` removes rows for that version
- `QueryStat.estimatePercentile` — test each bucket boundary (b1=5% → p50 is the 50th bucket, etc.)

**Pattern:** Use same in-memory driver construction as other `businessTest` tests that use SQLDelight (`TelemetryDatabase.Schema.create(driver)`).

**Test home:** `businessTest`

### 2.8 `PerfExporter` (173 lines, 69% line / 24% branch)

**Already partially covered.** Uncovered branches:

- `detectRegressions` early returns: `currentIdx <= 0` (no previous version)
- `detectRegressions` threshold guards: `absDelta <= MIN_REGRESSION_ABS_MS` skips, `changePct <= MIN_REGRESSION_PCT` skips
- `detectRegressions` zero-prev-p95 division guard (`if (prevStats.p95Ms > 0) ... else 0`)
- `buildSlowCaseAttributes` with empty `slowSpans` list
- `exportWithPicker` null `pickedPath` path (falls back to `export`)

**Test home:** `businessTest` (existing `PerfExporterPickerTest` is already there)

---

## 3. Test patterns and risks

### Risk: `DesktopFileSystem.homeDir` is non-injectable

`val homeDir: String by lazy { System.getProperty("user.home") }` is `val`, not open. The `validatePath` implementation calls `expandTilde` which reads `homeDir`. On CI, `java.io.tmpdir` varies; on Linux it is typically `/tmp`, which is NOT under `/home/username`. 

**Solution:** Subclass `DesktopFileSystem` in tests, overriding `homeDir`:
```kotlin
private class TestDesktopFileSystem(tmpDir: String) : DesktopFileSystem() {
    override val homeDir: String = tmpDir
}
```
Since `homeDir` is `val` (not `open`), this requires making it `open` in source, OR using a temp dir that happens to be under the real home dir (e.g., `Files.createTempDirectory(Paths.get(System.getProperty("user.home")), "test")`). The latter requires no source changes and is simpler.

### Risk: `InMemorySpanExporter` availability

OTel SDK's `opentelemetry-sdk-testing` artifact provides `InMemorySpanExporter`. Confirm it's in `testImplementation` or add it. Check `kmp/build.gradle.kts`.

### Risk: `TelemetryDatabase` in-memory driver setup

Must use `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` and call `TelemetryDatabase.Schema.create(driver)` before constructing `QueryStatsRepository`. Follow the same pattern as existing `SqlDelightBlockRepositoryTest` / `SqlDelightPageRepositoryTest`.

---

## 4. Effort estimate

| File | New tests | Estimated complexity |
|---|---|---|
| `DesktopFileSystemTest` | ~12 | Low (pure I/O) |
| `GoogleTokenRefresherTest` | ~5 | Low (MockEngine pattern already exists) |
| `JvmGoogleAuthManagerTest` | ~5 | Low-Medium (partial testability) |
| `InstrumentedPageRepositoryTest` | ~6 | Medium (OTel SDK setup) |
| `DesktopFilePickerTest` | ~2 | Low (headless-only) |
| `PhotosPickerApiClientTest` additions | ~6 | Low (MockEngine) |
| `QueryStatsRepositoryTest` | ~8 | Medium (in-memory TelemetryDB) |
| `PerfExporterTest` additions | ~4 | Medium (branch logic) |

Total: ~48 new test cases. All in `jvmTest` or `businessTest`. No new source file changes expected.

---

## 5. References

- PR #402: incremental coverage gate — https://github.com/tstapler/stelekit/pull/402
- Baseline measurement: branch `stelekit-merges` @ `e337d58c1e`
- Existing HTTP test pattern: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/google/GoogleApiClientTest.kt`
- Existing perf test pattern: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/PerfExporterPickerTest.kt`
