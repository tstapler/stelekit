# Requirements: platform, performance and Google integration test coverage gaps

**Item ID:** 9326ad08-d5dc-4a1f-b184-62603dd1c8d0  
**Project name:** platform-perf-google-coverage  
**Date:** 2026-10-14  
**Author:** Tyler Stapler (tystapler@gmail.com)

---

## Background

A JaCoCo line + branch coverage report (`:kmp:jacocoTestReport` on branch `stelekit-merges` @ `e337d58c1e`) identified that `platform*` and `performance` packages collectively sit at **53.9% line / 37.8% branch** coverage — well below the project's incremental coverage gate introduced in PR #402.

Five source files have **0% coverage**:

| File | Lines | Package |
|---|---|---|
| `DesktopFileSystem.kt` | 117 | `jvmMain/platform` |
| `InstrumentedPageRepository.kt` | 93 | `jvmCommonMain/performance` |
| `JvmGoogleAuthManager.kt` | 79 | `jvmMain/platform/google` |
| `DesktopFilePicker.kt` | 47 | `jvmMain/platform/sensor` |
| `GoogleTokenRefresher.kt` | 42 | `commonMain/platform/google` |

Three more files have critically low coverage (20–48%):

| File | Lines covered | Gap |
|---|---|---|
| `PlatformFileSystem.kt` | 20% | ~77 of 96 lines uncovered |
| `QueryStatsRepository.kt` | 38% | ~67 of 108 lines uncovered |
| `PhotosPickerApiClient.kt` | 48% | ~69 of 132 lines uncovered |

One file has acceptable line coverage but poor branch coverage:

| File | Lines | Branch |
|---|---|---|
| `PerfExporter.kt` | 69% | 24% |

---

## Problem Statement

Critical I/O and auth paths have **zero test coverage**, meaning regressions are invisible to CI. Specific risks:

1. **`DesktopFileSystem`**: Path traversal validation (`validatePath`), tilde expansion, max-size guard, directory creation — any regression in the path-safety logic could expose the user's filesystem to write operations outside `~`.
2. **`GoogleTokenRefresher` / `JvmGoogleAuthManager`**: Token refresh failure modes (HTTP 4xx, malformed JSON, network error) and the OAuth code-for-token exchange are untested. A regression here breaks Google Drive sync silently.
3. **`InstrumentedPageRepository`**: The OpenTelemetry span wrapper around every mutating repo call. Without tests, span lifecycle bugs (unclosed spans, wrong error status) go undetected.
4. **`DesktopFilePicker`**: Headless guard and cancellation path.
5. **`PhotosPickerApiClient` / `PerfExporter`**: Partial coverage leaves the token-expired 401 path, `=d` suffix append for downloads, and regression-detection logic untested.
6. **`QueryStatsRepository`**: Upsert batching, percentile bucket estimation, and version deletion are all uncovered.

---

## Goals

1. Bring all six 0%-coverage files to **≥ 60% line** coverage each.
2. Bring `PhotosPickerApiClient` and `QueryStatsRepository` to **≥ 70% line** coverage.
3. Improve `PerfExporter` branch coverage from 24% to **≥ 50%**.
4. All new tests must pass in the existing `bazel test //kmp:jvm_tests` / `./gradlew jvmTest` pipeline without requiring a live network or real filesystem.
5. No new production code changes unless a test reveals a genuine bug that must be fixed as part of this item.

---

## Non-Goals

- Reaching 100% coverage on any file.
- Adding coverage for Android-only or iOS-only variants of the same interfaces.
- Modifying `JvmGoogleAuthManager.waitForOAuthCallback()` (requires real `ServerSocket`; mock-only approach is acceptable with coverage excluded via `@ExcludeFromJacocoReport` if needed).
- Adding instrumented Android tests (out of scope for this item; JVM-only).

---

## Acceptance Criteria

1. `DesktopFileSystemTest` exists in `jvmTest` and covers: path traversal rejection, tilde expansion, `readFile` returning null for missing/oversized files, `writeFile` creating parent dirs, `listFiles`/`listDirectories` on a real temp dir, and `validatePath` rejecting paths outside home.
2. `GoogleTokenRefresherTest` exists in `businessTest` or `jvmTest` using Ktor `MockEngine` and covers: success path (token saved), HTTP 4xx path (Left returned), OAuth error field in body (Left returned), network exception (Left returned), and refresh token rotation.
3. `JvmGoogleAuthManagerTest` exists in `jvmTest` and covers: blank `clientId` → immediate Left; `signOut` delegates to `tokenStore`; `isAuthenticated` and `getConnectedEmail` delegate correctly; `extractCodeFromRequestLine` parses code from a well-formed GET line and returns null for malformed input.
4. `InstrumentedPageRepositoryTest` exists in `jvmTest` (or `businessTest`) and covers: span is started and ended on `savePage` success, span is marked ERROR on exception, `savePages` and `deletePage` spans use correct span names, and read-only delegates pass through without creating spans.
5. `DesktopFilePickerTest` notes the headless guard path returns `HardwareUnavailable` (assertable in a headless CI environment without any Swing setup).
6. `PhotosPickerApiClientTest` exists in `jvmTest` using `MockEngine` and covers: unauthenticated path (401 Left), HTTP error on `createPhotosPickerSession`, `downloadPickerMedia` appending `=d` suffix, `listPickerMediaItems` with absent `mediaFile` skipping the item, and `deletePickerSession` on 200/204.
7. `QueryStatsRepositoryTest` exists in `businessTest` using an in-memory `TelemetryDatabase` and covers: `upsertBatch` with a non-empty map, `getStatsByVersion`, `getTopByTotalMs`, `getAllVersions`, `deleteVersion`, and `QueryStat.estimatePercentile` for each bucket boundary.
8. `PerfExporterTest` covers the `detectRegressions` private method via the `export` path or via a white-box test of `computeSpanStats`, including the "fewer than MIN_REGRESSION_SAMPLES" early-return branch and the zero-previous-p95 division guard.
9. `bazel test //kmp:jvm_tests` passes green (or `./gradlew jvmTest`) with all new tests included, no skip annotations, and no real network calls.
10. JaCoCo line coverage for each of the eight targeted files moves above the thresholds stated in Goals (verified by re-running `:kmp:jacocoTestReport` and checking the HTML report).

---

## Constraints

- Use Ktor `MockEngine` for all HTTP client tests (already a `testImplementation` dependency — see `GoogleApiClientTest`).
- Use `kotlin.test` annotations (`@Test`, `runTest`) — no Kotest spec runner.
- Desktop-only classes (`DesktopFileSystem`, `DesktopFilePicker`, `JvmGoogleAuthManager`) must live in `jvmTest` source set.
- Common classes (`GoogleTokenRefresher`, `PhotosPickerApiClient`, `QueryStatsRepository`, `PerfExporter`, `InstrumentedPageRepository`) may live in `businessTest` or `jvmTest`.
- Tests must not write outside `System.getProperty("java.io.tmpdir")` (use `kotlin.io.path.createTempDirectory()`).
