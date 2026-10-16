# Validation: platform, performance and Google integration test coverage gaps

**Project:** platform-perf-google-coverage  
**Date:** 2026-10-14  

---

## Adversarial review of the plan

### Challenge 1: `DesktopFileSystem.homeDir` — plan says "no source change needed"

**Plan claim:** Use `Files.createTempDirectory(Paths.get(System.getProperty("user.home")), "dfs-test")` to keep temp dirs under real home, so `validatePath`'s `startsWith(homePath)` check passes without modifying source.

**Adversarial check:** `DesktopFileSystem.validatePath` reads:
```kotlin
val homePath = Paths.get(homeDir).toAbsolutePath().normalize()
require(absolutePath.startsWith(homePath)) { "Path must be within user's home directory" }
```
And `homeDir` is `val homeDir: String by lazy { System.getProperty("user.home") }`.

If `java.io.tmpdir` is `/tmp` (common on Linux), test temp dirs created under `$HOME` (e.g., `/home/tstapler/dfs-test-abc`) will pass the check. **But** if tests call `writeFile` with a path like `/tmp/...`, `validatePath` will reject it. The plan is correct: **always create temp dirs under `user.home`**. CI typically runs as a real user with a home directory. ✅ No source change needed.

**However:** GitHub Actions runners may set `HOME=/github/home` or similar, and `user.home` may differ from `HOME`. Verify `System.getProperty("user.home")` is consistent with where temp directories land on the CI runner. Mitigation: the test can dynamically use `System.getProperty("user.home")` for its temp dir root.

### Challenge 2: OTel `InMemorySpanExporter` — is it on the classpath?

**Plan claim:** Use `InMemorySpanExporter` from `opentelemetry-sdk-testing`.

**Adversarial check:** `InstrumentedPageRepository` imports `io.opentelemetry.api.trace.Tracer` (the API artifact, not the SDK). The production code doesn't need the SDK. The test needs `opentelemetry-sdk-testing` which provides `InMemorySpanExporter`. 

Look for this in `kmp/build.gradle.kts`:
```
testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
```

If absent, the implementer must add it. This is a low-risk addition (test-scoped, no production impact). The plan should note this as a prerequisite check, not an assumption.

**Resolution:** Plan updated to say "add if absent" — this is correctly flagged in the risk table. ✅

### Challenge 3: `JvmGoogleAuthManager.extractCodeFromRequestLine` — private method

**Plan claim:** "test via a test subclass that exposes the private method, or by calling authenticate with a mock."

**Adversarial check:** `extractCodeFromRequestLine` is `private fun`. Kotlin `private` cannot be subclass-overridden or accessed from test without reflection. The method is called only inside `waitForOAuthCallback`, which requires a real socket.

**More precise solution:** The `authenticate()` method body returns `Left` on blank `clientId` before ever calling `waitForOAuthCallback`. This alone covers lines 48–53. The `extractCodeFromRequestLine` logic (lines 147–154) remains uncovered unless we either:
- Make it `internal` and test it directly, OR
- Accept it is untested (it's 8 lines of URL parsing)

The plan's language is slightly hand-wavy here. **Recommendation:** Accept that `extractCodeFromRequestLine` stays untested for now; document this as a known gap. The 5 testable behaviors in Task 2.2 still provide meaningful coverage of the file's main guard paths.

### Challenge 4: `QueryStatsRepository` — `RestrictedTelemetryQueries` requires a `Mutex`

**Adversarial check:** `QueryStatsRepository` constructor is:
```kotlin
class QueryStatsRepository(
    private val database: TelemetryDatabase,
    writeMutex: Mutex = Mutex(),
)
```
The default `Mutex()` is fine for tests. But `upsertBatch` uses `withContext(PlatformDispatcher.DB)` — on JVM this is `Dispatchers.IO`. In `runTest`, coroutine dispatchers are TestCoroutineScheduler-driven; the actual `Dispatchers.IO` is not virtualized by default. This can cause test instability if the test doesn't `advanceUntilIdle()` after the call.

**Mitigation:** Use `runTest(UnconfinedTestDispatcher())` which uses the actual dispatcher, or use `runBlocking` for DB-write tests (acceptable for repository tests that don't need time virtualization). Alternatively, the `PlatformDispatcher.DB` indirection may already be interceptable for JVM tests.

### Challenge 5: `PerfExporter.detectRegressions` — not directly accessible

**Adversarial check:** `detectRegressions` is `private fun`. It is called from `buildReportJson()` which is called from `export()`. 

To test regression detection, the test must drive `export()` with a real `SpanRepository` (fake) containing spans across two versions. The fake `SpanRepository.getRecentSpans()` must return spans with `attributes["app.version"]` set to two different values.

**Assessment:** This is the correct indirect approach already implied by the plan. `export()` requires a real (fake) `FileSystem`, `SpanRepository`, and `HistogramWriter` — all already faked in `PerfExporterPickerTest`. The plan is feasible. ✅

### Challenge 6: `PhotosPickerApiClient` — `GoogleAuthClient` interface

**Adversarial check:** `PhotosPickerApiClient` takes a `GoogleAuthClient` (not `GoogleTokenStore`). `GoogleApiClient` implements `GoogleAuthClient` (via `GoogleApiClient` extending it). For direct `PhotosPickerApiClient` tests, a fake `GoogleAuthClient` is needed:
```kotlin
private val unauthClient = object : GoogleAuthClient {
    override suspend fun getValidToken(): Either<DomainError, String> =
        DomainError.NetworkError.HttpError(401, "Not authenticated").left()
}
```
This is straightforward. ✅

### Challenge 7: Does `GoogleTokenRefresher` test belong in `businessTest` or `jvmTest`?

`refreshGoogleToken` is in `commonMain`. Ktor `MockEngine` is available in `commonTest`? Let me check: Ktor mock engine (`ktor-client-mock`) is JVM-only in the KMP context unless specifically included for commonTest. The existing `GoogleApiClientTest` is in `jvmTest`. **Conclusion:** `GoogleTokenRefresherTest` should also be in `jvmTest` even though the source is in `commonMain`. ✅

---

## Plan adjustments after adversarial review

1. **Task 2.2** — Explicitly state that `extractCodeFromRequestLine` is accepted as untested gap; 5 other test cases give adequate coverage.
2. **Task 3.1** — Add prerequisite: check for `opentelemetry-sdk-testing` in `jvmTest` deps and add if absent.
3. **Task 5.1** — Note: use `runBlocking` or `UnconfinedTestDispatcher` to avoid `Dispatchers.IO` virtualization issues in `upsertBatch`.
4. **Task 6.1** — Drive `detectRegressions` via `export()` with a fake `SpanRepository` returning spans tagged with two app versions.

---

## Coverage target feasibility check

| File | Current | Target | Reachable by plan? |
|---|---|---|---|
| `DesktopFileSystem.kt` | 0% | ≥60% | ✅ 12 tests cover 8/10 public methods |
| `GoogleTokenRefresher.kt` | 0% | ≥60% | ✅ 5 tests cover all branches of the suspend function |
| `JvmGoogleAuthManager.kt` | 0% | ≥60% | ⚠️ ~45% reachable without socket; explicit gap for `waitForOAuthCallback` |
| `InstrumentedPageRepository.kt` | 0% | ≥60% | ✅ 6 tests cover 7 of 10 `@DirectRepositoryWrite` methods |
| `DesktopFilePicker.kt` | 0% | ≥30% | ⚠️ Headless guard alone is ~30% — target lowered from 60% for this file |
| `PhotosPickerApiClient.kt` | 48% | ≥70% | ✅ 6 new tests fill the unauthenticated + edge branches |
| `QueryStatsRepository.kt` | 38% | ≥70% | ✅ 8 tests cover all public methods and percentile estimation |
| `PerfExporter.kt` (branch) | 24% | ≥50% | ✅ 4 new tests cover the major detection branches |

`JvmGoogleAuthManager` and `DesktopFilePicker` are the two files where the 60% target is not fully achievable by unit tests alone due to infrastructure requirements (live socket, AWT). The plan should declare the achievable target honestly:
- `JvmGoogleAuthManager`: target ≥ 45%
- `DesktopFilePicker`: target ≥ 30%

These two files contain pure UI/OS interaction code that is conventionally excluded from unit test coverage targets. The remaining 8 files meet or exceed their targets.

---

## Risk summary

| Risk | Severity | Status |
|---|---|---|
| `homeDir` non-injectable | Medium | Mitigated — use `user.home`-rooted temp dirs |
| OTel SDK testing jar missing | Low | Flag in implementation notes; trivial to add |
| `waitForOAuthCallback` untestable | Medium | Accepted — 45% coverage still meaningful |
| `Dispatchers.IO` in `runTest` | Low | Use `runBlocking` for DB tests |
| `extractCodeFromRequestLine` private | Low | Accepted gap — 8 lines |

---

## Verdict: plan is VIABLE

The plan is internally consistent, uses established test patterns in this codebase, and does not require production source changes. All identified risks have clear mitigations or explicit accepted gaps. The adversarial review found no blocking issues.

Suggested priority adjustment: move `GoogleTokenRefresher` and `QueryStatsRepository` to the front of the implementation order since they have the highest risk-to-test ratio (auth correctness and DB stats correctness).
