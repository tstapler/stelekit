# Stack Research: journal-watch-fix

## Kotlin Multiplatform Coroutines for Timer Scheduling

### Library versions in use
- `kotlinx-coroutines-core:1.10.2` — all platforms
- `kotlinx-coroutines-test:1.10.2` — jvmTest, androidUnitTest, commonTest
- `kotlinx-datetime:0.7.1` — all platforms
- `kotlin.time.Clock` — already imported in `JournalService.kt` and `StelekitViewModel.kt` via `import kotlin.time.Clock`

### Midnight-boundary coroutine loop pattern

The coroutine for REQ-01 uses only stdlib already in the classpath. Key APIs:

```kotlin
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
```

Midnight-delay computation:
```kotlin
fun millisUntilNextMidnight(clock: Clock = Clock.System): Long {
    val tz = TimeZone.currentSystemDefault()
    val now = clock.now()
    val today = now.toLocalDateTime(tz).date
    val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
    return (tomorrowMidnight - now).inWholeMilliseconds
}
```

`kotlinx-datetime` `LocalDate.plus(1, DateTimeUnit.DAY)` correctly handles DST transitions because `atStartOfDayIn` resolves the instant at which midnight actually occurs in the given timezone — which may be at 01:00 UTC offset due to a spring-forward transition. `kotlinx.datetime.TimeZone.currentSystemDefault()` delegates to `java.util.TimeZone.getDefault()` on JVM and `NSTimeZone.localTimeZone` on iOS/Darwin; on Android it reads the system timezone property.

### Clock injection for tests

`kotlin.time.Clock` is an interface with a single method `now(): Instant`. The test library provides `kotlinx.coroutines.test.TestCoroutineScheduler` (and `StandardTestDispatcher`) but no fake `Clock` out of the box. The project already wraps `Clock.System` in `JournalService` — tests can inject a fake via a constructor parameter or lambda.

Minimal fake clock for test injection:
```kotlin
class FakeClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
    fun advance(duration: Duration) { instant += duration }
}
```

The `kotlinx-coroutines-test` `runTest` with `TestCoroutineScheduler.advanceTimeBy()` controls `delay()` without real wall-clock time. This is what the `GraphLoaderProgressiveTest` already uses. Combining a `FakeClock` for `Clock.System.now()` calls with `advanceTimeBy()` for `delay()` gives complete control over both time axes.

### Shadow Cache Architecture

**Android only (`androidMain`)**

`ShadowFileCache` (`androidMain/.../platform/ShadowFileCache.kt`) is an internal read cache stored under `context.filesDir/graphs/<graphId>/shadow/`. It maps SAF relative paths (e.g. `pages/Foo.md`, `journals/2026_05_29.md`) to local `File` objects.

Key methods on `ShadowFileCache`:
- `resolve(relativePath)` — returns `File?` if shadow exists and is non-empty
- `update(relativePath, content)` — writes content to shadow after successful SAF write
- `invalidate(relativePath)` — deletes the shadow file at that path
- `invalidateStale(subdir, fileModTimes)` — batch invalidation: deletes shadow files whose shadow mtime < SAF mtime (used at startup)
- `syncFromSaf(subdir, fileModTimes, readSafFile)` — warms the cache by reading SAF for stale entries

**SAF read path:**
`PlatformFileSystem.readFile(path)` for `saf://` paths:
1. Checks `shadowCache?.resolve(relativePath)` — if found, reads from local `File`
2. Falls through to `safReadContent(path)` — Binder IPC via `ContentResolver.openInputStream`

**Invalidation surface:**
- `FileSystem.invalidateShadow(path)` — public no-op default; Android overrides to call `shadowCache?.invalidate(relativePath)`
- `FileSystem.invalidateStaleShadow(graphPath)` — batch form called at startup via `GraphLoader.invalidateStaleShadowNonFatal`
- `GraphLoader.loadFullPage()` already calls `fileSystem.invalidateShadow(filePath)` before reading

### FileRegistry / FileSystem Abstraction

`FileRegistry` is a platform-agnostic class in `commonMain/db/` that takes a `FileSystem` interface. `FileSystem` is defined in `commonMain/platform/FileSystem.kt` with default no-op implementations for all shadow methods. This means:

- On JVM: `invalidateShadow` is a no-op, `readFile` goes straight to disk — no shadow layer at all. The REQ-02 fix (`invalidateShadow` before `readFile` in `detectChanges`) is zero-cost on JVM.
- On Android SAF mode: `invalidateShadow` deletes the local shadow file so the next `readFile` falls through to SAF and returns fresh content.
- On Android direct-access mode (MANAGE_EXTERNAL_STORAGE granted): `readFile` skips shadow and goes to `legacyReadFile`, same as JVM. `invalidateShadow` still clears the `knownExistingFiles` cache entry.

### RepositorySet / Graph Scope

`GraphLoader` owns `private val parallelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` for background work. The midnight-boundary coroutine must be launched on this scope (or an equivalent long-lived graph-tied scope), not on a composable-supplied scope.

`StelekitViewModel` stores `private val scope = deps.scope` and uses it to launch all long-lived coroutines (autosave, sync observation). The `onPhase1Complete` callback is already used to launch `journalService.ensureTodayJournal()` on this scope.

### Build constraints

No new dependencies are needed for either fix:
- REQ-01: `kotlin.time.Clock`, `kotlinx.datetime.TimeZone`/`LocalDate`/`DateTimeUnit`, `kotlinx.coroutines.delay` — all already imported in `JournalService.kt`
- REQ-02: `FileSystem.invalidateShadow` — already defined in `FileSystem.kt`, already called in `GraphFileWatcher.checkDirectoryForChanges` (just in the wrong order relative to `detectChanges`)
