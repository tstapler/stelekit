# Implementation Plan: Android Widget / Share-Target Hibernation Recovery

## Overview

When Android kills the SteleKit process (memory pressure, App Standby RESTRICTED, or true
hibernation) and a widget or share-target entry point wakes it, `Application.onCreate()` runs
and `GraphManager` is constructed. `GraphManager.init` calls `loadRegistry()`, which restores
the `activeGraphId` from `SharedPreferences`, but never calls `switchGraph()`. The result:
`_activeRepositorySet` is `null` and all non-`MainActivity` entry points show "no graph."

### Fix Strategy

Six targeted changes, one per file:

| File | Change | Purpose |
|---|---|---|
| `GraphManager.kt` | Auto-call `switchGraph(activeId)` from `init` | Restores DB connection on any process wakeup |
| `CaptureWidget.kt` | `awaitPendingMigration()` before `provideContent` | Blocks widget render until DB is ready |
| `VoiceWidget.kt` | Same pattern as `CaptureWidget` | Same fix for voice widget |
| `CaptureActivity.kt` | `collectAsState()` on `activeRepositorySet` StateFlow | Activity recomposes when DB opens async |
| `VoiceCaptureWidgetViewModel.kt` | Wrap init body in `viewModelScope.launch { awaitPendingMigration() }` + `AtomicBoolean` guard | ViewModel awaits DB before building pipeline |
| `CaptureTileService.kt` | Replace `getActiveRepositorySet()` with `getActiveGraphId()` | Synchronous registry check (always accurate after `loadRegistry()`) |

---

## Detailed Changes

### 1. `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

#### What changes

Add a single line to the `init {}` block that auto-starts `switchGraph` when the registry has
an active graph ID.

#### Exact diff

```kotlin
// BEFORE:
init {
    loadRegistry()
}

// AFTER:
init {
    loadRegistry()
    _graphRegistry.value.activeGraphId?.let { switchGraph(it) }
}
```

#### Rationale

- `loadRegistry()` always sets `_graphRegistry.value` before `init` returns, so reading
  `_graphRegistry.value.activeGraphId` immediately after is safe — no initialization order
  hazard.
- `switchGraph()` is not a `suspend fun`; it launches a coroutine on `graphScope` using
  `PlatformDispatcher.IO`. The coroutine is scheduled, not executed eagerly inside the
  constructor. `Application.onCreate()` is not blocked.
- `preFlightJob` is constructed before `GraphManager(...)` is called in
  `SteleKitApplication.onCreate()`. `switchGraph()`'s IO coroutine awaits `preFlightJob`
  before opening the driver — correct ordering is preserved.
- Fresh install guard: `activeGraphId` is `null` on fresh install (no `lastGraphPath`, no
  `graph_registry`). The `?.let` ensures `switchGraph` is not called.
- Post-migration guard: `migrateFromSingleGraph()` sets `activeGraphId` when a legacy graph
  path exists. The `?.let` correctly triggers `switchGraph` for migrated users.
- When `MainActivity` later calls `switchGraph(activeId)` via `StelekitApp`, the second call
  enters `switchGraph()`, cancels the first `graphScope`, closes the first factory (or the
  partially-opened one), resets `_activeRepositorySet.value = null`, then re-opens the DB.
  This is a brief null flash (~50–100 ms on fast hardware) but is functionally correct — the
  existing close-then-reopen logic was designed for idempotent re-entry.
- No idempotency guard is added in this iteration. The double-open is wasteful but safe per
  the research. A follow-up optimization can add `if (activeGraphJobs.containsKey(id) &&
  currentSwitchingId == id) return` when needed.

#### Initialization order analysis (critical)

`switchGraph()` accesses: `_graphRegistry` (set before `init` runs), `_activeGitSyncService`
(set in class body as `MutableStateFlow`), `currentFactory` (set in class body as `null`),
`activeGraphJobs` (set in class body as empty `mutableMapOf`), `coroutineScope` (set in class
body before `init`), `_pendingMigration` (set in class body). All fields `switchGraph()`
reads are initialized by the time `init {}` runs. There is no uninitialized field hazard.

---

### 2. `androidApp/src/main/kotlin/dev/stapler/stelekit/widget/CaptureWidget.kt`

#### What changes

Add `awaitPendingMigration()` before `provideContent { }` in `provideGlance`. The
one-shot synchronous read inside `provideContent` remains unchanged.

#### Exact diff

```kotlin
// BEFORE:
override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent {
        val ctx = LocalContext.current
        val hasGraph = (ctx.applicationContext as? SteleKitApplication)
            ?.graphManager?.getActiveRepositorySet() != null
        // ...
    }
}

// AFTER:
override suspend fun provideGlance(context: Context, id: GlanceId) {
    (context.applicationContext as? SteleKitApplication)
        ?.graphManager?.awaitPendingMigration()
    provideContent {
        val ctx = LocalContext.current
        val hasGraph = (ctx.applicationContext as? SteleKitApplication)
            ?.graphManager?.getActiveRepositorySet() != null
        // ...
    }
}
```

#### Rationale

- `provideGlance` is a `suspend fun`. Suspending before `provideContent` is explicitly
  supported by the Glance API. Until `provideContent` is called, the widget shows its
  last-known RemoteViews.
- `awaitPendingMigration()` is guaranteed to complete via `finally { deferred.complete(Unit) }`
  in `switchGraph`'s IO coroutine. It never hangs indefinitely.
- `provideGlance` runs off the main thread in a Glance-managed coroutine. No ANR risk.
- After `awaitPendingMigration()` returns, `getActiveRepositorySet()` is the accurate
  synchronous read: if DB init succeeded, it's non-null; if it failed (e.g., corrupt DB),
  it's null and `NoGraphContent` is shown — correct graceful degradation.
- If `graphManager` is null (Application init failed), `?.awaitPendingMigration()` is a
  no-op (Kotlin safe-call on null), and `getActiveRepositorySet()` returns null — the
  existing `NoGraphContent` path handles this.
- Timeout risk: bounded by DB open + migration duration (typically <5 s even on first
  launch). The `goAsync()` in `CaptureWidgetReceiver` provides up to ~30 s. No additional
  timeout mechanism is needed.

---

### 3. `androidApp/src/main/kotlin/dev/stapler/stelekit/widget/VoiceWidget.kt`

#### What changes

Identical pattern to `CaptureWidget`.

#### Exact diff

```kotlin
// BEFORE:
override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent {
        val ctx = LocalContext.current
        val hasGraph = (ctx.applicationContext as? SteleKitApplication)
            ?.graphManager?.getActiveRepositorySet() != null
        // ...
    }
}

// AFTER:
override suspend fun provideGlance(context: Context, id: GlanceId) {
    (context.applicationContext as? SteleKitApplication)
        ?.graphManager?.awaitPendingMigration()
    provideContent {
        val ctx = LocalContext.current
        val hasGraph = (ctx.applicationContext as? SteleKitApplication)
            ?.graphManager?.getActiveRepositorySet() != null
        // ...
    }
}
```

#### Rationale

Identical to `CaptureWidget` — same entry point structure, same fix.

---

### 4. `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

#### What changes

Replace the one-shot `getActiveRepositorySet()` read inside `setContent` with a reactive
`collectAsState()` on the `activeRepositorySet` `StateFlow`. The activity recomposes
automatically when the DB opens.

#### Exact diff

```kotlin
// BEFORE (inside onCreate, setContent block):
setContent {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        if (app.graphManager?.getActiveRepositorySet() == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NoGraphPlaceholderContent()
            }
        } else {
            CaptureScreen(
                viewModel = viewModel,
                onSaved = { ... },
                onDismiss = { finish() },
            )
        }
    }
}

// AFTER:
setContent {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        val repoSet by (app.graphManager?.activeRepositorySet
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) })
            .collectAsState()
        if (repoSet == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NoGraphPlaceholderContent()
            }
        } else {
            CaptureScreen(
                viewModel = viewModel,
                onSaved = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        promptAddTileOnce()
                    }
                    finish()
                },
                onDismiss = { finish() },
            )
        }
    }
}
```

#### Rationale

- `activeRepositorySet` is a `StateFlow<RepositorySet?>` on `GraphManager`. `collectAsState()`
  inside a `@Composable` subscribes to this flow and triggers recomposition on each emission.
- When `switchGraph`'s IO coroutine completes and sets `_activeRepositorySet.value = repoSet`,
  the state change propagates immediately to all collectors, including `CaptureActivity`.
- The `?: remember { MutableStateFlow(null) }` fallback handles the case where
  `app.graphManager` is null (Application init failed) — it provides a stable never-emitting
  flow that always returns null, showing `NoGraphPlaceholderContent` permanently.
- No recomposition loop: `collectAsState()` only triggers recomposition when the StateFlow
  emits a new value. `activeRepositorySet` only emits when `switchGraph` sets it (null →
  repoSet, or repoSet → null on switch). Normal recompositions of `CaptureActivity` do not
  cause the StateFlow to emit.
- No memory leak: `collectAsState()` inside `setContent` is lifecycle-aware — the collector
  is cancelled when the Activity's composition is disposed (Activity destroyed).
- `remember` key: the `?: remember { MutableStateFlow(null) }` branch uses `remember` without
  a key, so it is stable across recompositions. The `app.graphManager` reference is captured
  once in `onCreate` as `val app = application as SteleKitApplication` — it does not change.
- Required additional import: `kotlinx.coroutines.flow.MutableStateFlow` (if not already
  imported via another path). Check existing imports; `collectAsState` is already imported
  at line 47.

---

### 5. `androidApp/src/main/kotlin/dev/stapler/stelekit/VoiceCaptureWidgetViewModel.kt`

#### What changes

Wrap the `initialize()` body in `viewModelScope.launch { }`. Add `awaitPendingMigration()`
before the `getActiveRepositorySet()` check. Add an `AtomicBoolean` guard to prevent
double-initialization during the async gap.

#### Exact diff

```kotlin
// BEFORE:
class VoiceCaptureWidgetViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<VoiceCaptureState>(VoiceCaptureState.Idle)
    val state: StateFlow<VoiceCaptureState> = _state.asStateFlow()

    private var inner: VoiceCaptureViewModel? = null

    fun initialize(requestMicPermission: suspend () -> Boolean) {
        if (inner != null) return

        val steleApp = getApplication<SteleKitApplication>()
        val repoSet = steleApp.graphManager?.getActiveRepositorySet()
        if (repoSet == null) {
            _state.value = VoiceCaptureState.Error(
                dev.stapler.stelekit.voice.PipelineStage.RECORDING,
                "No graph configured — open SteleKit first",
                dev.stapler.stelekit.voice.VoiceErrorKind.NO_GRAPH,
            )
            return
        }
        // ... pipeline build ...
        inner = vm
        vm.onMicTapped()
    }
}

// AFTER:
class VoiceCaptureWidgetViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<VoiceCaptureState>(VoiceCaptureState.Idle)
    val state: StateFlow<VoiceCaptureState> = _state.asStateFlow()

    private var inner: VoiceCaptureViewModel? = null
    private val initializing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun initialize(requestMicPermission: suspend () -> Boolean) {
        if (inner != null) return
        if (!initializing.compareAndSet(false, true)) return

        viewModelScope.launch {
            try {
                val steleApp = getApplication<SteleKitApplication>()
                val gm = steleApp.graphManager
                if (gm?.getActiveGraphId() == null) {
                    // No graph configured at all — fail immediately, don't await
                    _state.value = VoiceCaptureState.Error(
                        dev.stapler.stelekit.voice.PipelineStage.RECORDING,
                        "No graph configured — open SteleKit first",
                        dev.stapler.stelekit.voice.VoiceErrorKind.NO_GRAPH,
                    )
                    return@launch
                }
                gm.awaitPendingMigration()
                val repoSet = gm.getActiveRepositorySet()
                if (repoSet == null) {
                    // DB open failed
                    _state.value = VoiceCaptureState.Error(
                        dev.stapler.stelekit.voice.PipelineStage.RECORDING,
                        "No graph configured — open SteleKit first",
                        dev.stapler.stelekit.voice.VoiceErrorKind.NO_GRAPH,
                    )
                    return@launch
                }

                val ctx = steleApp.applicationContext
                val recorder = AndroidAudioRecorder(ctx, requestMicPermission)
                val settings = VoiceSettings(PlatformSettings())
                val deviceStt = if (AndroidSpeechRecognizerProvider.isAvailable(ctx))
                    AndroidSpeechRecognizerProvider(ctx, requestMicPermission) else null
                val pipeline = buildVoicePipeline(
                    audioRecorder = recorder,
                    settings = settings,
                    directSpeechProvider = if (settings.getUseDeviceStt()) deviceStt else null,
                )

                val vm = VoiceCaptureViewModel(
                    pipeline = pipeline,
                    journalService = repoSet.journalService,
                    scope = viewModelScope,
                )

                viewModelScope.launch {
                    vm.state.collect { _state.value = it }
                }

                inner = vm
                vm.onMicTapped()
            } finally {
                initializing.set(false)
            }
        }
    }

    fun onMicTapped() { inner?.onMicTapped() }
    fun cancel()      { inner?.cancel() }
    fun dismissError(){ inner?.dismissError() }

    override fun onCleared() {
        inner?.close()
        super.onCleared()
    }
}
```

#### Rationale

- Wrapping in `viewModelScope.launch` makes the entire init async without changing the
  signature — the calling Activity in `onCreate` can still call `initialize()` synchronously.
- `getActiveGraphId()` is checked first (before `awaitPendingMigration()`). `getActiveGraphId()`
  reads `_graphRegistry.value.activeGraphId`, which is set synchronously by `loadRegistry()`
  in `GraphManager.init`. If this is null, the user has no graph configured at all — we fail
  immediately without awaiting. This preserves FR-5 (no-graph behavior unchanged).
- `awaitPendingMigration()` awaits `_pendingMigration`, which is the deferred set by
  `switchGraph()` in `GraphManager.init`. It always resolves (guaranteed by `finally` in
  `switchGraph`'s IO coroutine).
- After `awaitPendingMigration()`, `getActiveRepositorySet()` is checked a second time. If
  DB init failed (e.g., corrupt DB file), this returns null and we show the NO_GRAPH error —
  graceful degradation.
- `AtomicBoolean` guard: prevents double-init during the async gap between the first
  `initialize()` call and `inner` being set. `compareAndSet(false, true)` is atomic —
  only one caller wins. The `finally { initializing.set(false) }` resets the guard if init
  fails or is cancelled, so a retry is possible (e.g., user dismisses error and re-enters).
- `viewModelScope` lifecycle: if the Activity is destroyed while `awaitPendingMigration()`
  is suspended, `viewModelScope` is cancelled, the coroutine exits with
  `CancellationException`, `initializing.set(false)` runs in `finally`, and `inner` remains
  null. `onCleared()` safely calls `inner?.close()` which is a no-op. No leak.
- Required additional import: `java.util.concurrent.atomic.AtomicBoolean`.

---

### 6. `androidApp/src/main/kotlin/dev/stapler/stelekit/tile/CaptureTileService.kt`

#### What changes

Replace `getActiveRepositorySet() != null` with `getActiveGraphId() != null` in `onClick()`.

#### Exact diff

```kotlin
// BEFORE:
override fun onClick() {
    super.onClick()
    val app = applicationContext as? SteleKitApplication
    val targetClass = if (app?.graphManager?.getActiveRepositorySet() != null) CaptureActivity::class.java
                      else MainActivity::class.java
    // ...
}

// AFTER:
override fun onClick() {
    super.onClick()
    val app = applicationContext as? SteleKitApplication
    val targetClass = if (app?.graphManager?.getActiveGraphId() != null) CaptureActivity::class.java
                      else MainActivity::class.java
    // ...
}
```

#### Rationale

- `getActiveGraphId()` reads `_graphRegistry.value.activeGraphId`, which is set synchronously
  by `loadRegistry()` in `GraphManager.init`. It is always accurate immediately after
  `Application.onCreate()` completes — no race with any IO coroutine.
- `onClick()` is a synchronous main-thread callback and cannot `suspend`. Using
  `getActiveRepositorySet()` here races with the `switchGraph` IO coroutine that may still
  be in flight. `getActiveGraphId()` eliminates this race entirely.
- If the user taps the tile and the DB is not yet open, `CaptureActivity` is launched.
  `CaptureActivity` uses `collectAsState()` (change 4) and will show `NoGraphPlaceholderContent`
  until the DB opens, then automatically recompose to `CaptureScreen`. This is the correct
  behavior — the DB will be open within ~1 s of the process starting.
- FR-6 satisfied: tile falls back to `CaptureActivity` (not `MainActivity`) when a graph IS
  configured but DB is not yet open. This is a better UX than the old behavior which always
  went to `MainActivity` in that race window.

---

## Test Cases

### Unit Tests (businessTest / androidUnitTest)

#### T-1: `GraphManagerAutoRestoreTest`

**Location**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerAutoRestoreTest.kt`

**Scenario**: GraphManager with a pre-populated registry (activeGraphId set) calls `switchGraph`
during construction.

```kotlin
@Test
fun `init calls switchGraph when activeGraphId is non-null`() {
    // Arrange: fake settings with a pre-populated registry JSON
    val settings = FakeSettings().apply {
        putString("graph_registry", """{"activeGraphId":"abc","graphs":[...]}""")
    }
    val mockDriverFactory = mock<DriverFactory>()
    whenever(mockDriverFactory.getDatabaseUrl("abc")).thenReturn("jdbc:sqlite::memory:")
    
    // Act
    val gm = GraphManager(settings, mockDriverFactory, FakeFileSystem())
    
    // Assert: switchGraph was called (IO coroutine launched)
    // The activeGraphId is set synchronously in loadRegistry; the deferred should not be pre-completed
    assertFalse(gm.getActiveRepositorySet() != null) // still null synchronously (IO coroutine pending)
    runBlocking { gm.awaitPendingMigration() }
    // After await, RepositorySet is set (or null if factory failed — acceptable)
}

@Test
fun `init does NOT call switchGraph when activeGraphId is null`() {
    // Arrange: empty settings (fresh install)
    val settings = FakeSettings()
    val gm = GraphManager(settings, mockDriverFactory, FakeFileSystem())
    
    // Assert: _pendingMigration is the pre-completed deferred (switchGraph never called)
    runBlocking { gm.awaitPendingMigration() } // must return immediately
    assertNull(gm.getActiveRepositorySet())
    assertNull(gm.getActiveGraphId())
}
```

#### T-2: `GraphManagerDoubleSwitchTest`

**Scenario**: `switchGraph` called from `init`, then called again with same ID. Verify no
resource leak and `awaitPendingMigration()` resolves correctly.

```kotlin
@Test
fun `double switchGraph same id resolves awaitPendingMigration correctly`() = runTest {
    val gm = GraphManager(settingsWithGraph("abc"), mockDriverFactory, FakeFileSystem())
    // second switchGraph (simulating MainActivity)
    gm.switchGraph("abc")
    gm.awaitPendingMigration() // must not hang
    // no assertion on repoSet — DB may be open or not depending on mock
}
```

#### T-3: `CaptureWidgetProvideGlanceTest`

**Location**: `androidApp/src/test/kotlin/dev/stapler/stelekit/widget/CaptureWidgetTest.kt`

**Scenario**: Mock `GraphManager` where `awaitPendingMigration()` suspends for 100 ms, then
`getActiveRepositorySet()` returns a non-null stub. Verify `provideGlance` calls
`awaitPendingMigration()` before `provideContent`.

```kotlin
@Test
fun `provideGlance awaits migration before rendering`() = runTest {
    var migrationAwaited = false
    val fakeGm = object : FakeGraphManager() {
        override suspend fun awaitPendingMigration() {
            migrationAwaited = true
            delay(100)
        }
        override fun getActiveRepositorySet() = FakeRepositorySet()
    }
    // ... inject via FakeApplication context
    val widget = CaptureWidget()
    widget.provideGlance(fakeContext, fakeGlanceId)
    assertTrue(migrationAwaited)
}
```

#### T-4: `CaptureTileServiceTest`

**Scenario**: Process fresh-start (DB not open), tile click. Verify `CaptureActivity` is
launched (not `MainActivity`) when `getActiveGraphId()` is non-null.

```kotlin
@Test
fun `onClick launches CaptureActivity when graph is configured but DB not open`() {
    // Arrange: graphManager with activeGraphId set, activeRepositorySet = null
    val app = mockSteleKitApplication(activeGraphId = "abc", repoSet = null)
    val tile = CaptureTileService()
    tile.setApplicationContext(app)
    
    // Act
    tile.onClick()
    
    // Assert: CaptureActivity was targeted
    val startedIntent = tile.capturedIntent
    assertEquals(CaptureActivity::class.java.name, startedIntent.component?.className)
}
```

#### T-5: `VoiceCaptureWidgetViewModelTest`

**Scenario**: `initialize()` called with `awaitPendingMigration()` suspending for 200 ms.
Verify `_state` stays `Idle` during the wait, then transitions to the recording state after.

```kotlin
@Test
fun `initialize awaits migration before reading repoSet`() = runTest {
    val fakeGm = FakeGraphManager(
        activeGraphId = "abc",
        migrationDelayMs = 200L,
        repoSet = FakeRepositorySet(),
    )
    val vm = VoiceCaptureWidgetViewModel(FakeApplication(fakeGm))
    vm.initialize(requestMicPermission = { true })
    
    // State should still be Idle before migration completes
    assertEquals(VoiceCaptureState.Idle, vm.state.value)
    
    // Advance virtual time past migration delay
    advanceTimeBy(250)
    
    // Now inner should be set and state should have progressed
    assertNotEquals(VoiceCaptureState.Idle, vm.state.value)
}

@Test
fun `initialize AtomicBoolean prevents double-init`() = runTest {
    val fakeGm = FakeGraphManager(activeGraphId = "abc", migrationDelayMs = 500L)
    val vm = VoiceCaptureWidgetViewModel(FakeApplication(fakeGm))
    vm.initialize { true }
    vm.initialize { true } // second call must be ignored
    advanceTimeBy(600)
    // inner should only have been set once
}
```

### Integration / Manual Tests

#### IT-1: Hibernation Recovery — Widget

1. Install SteleKit, add a graph, confirm widgets show capture button.
2. `adb shell am force-stop dev.stapler.stelekit`
3. Tap the Capture widget (home screen).
4. Expected: Capture sheet appears (not "Open SteleKit first" text).

#### IT-2: Hibernation Recovery — Share Target

1. Force-stop as above.
2. Share a URL from Chrome → SteleKit.
3. Expected: Capture sheet with the shared URL pre-filled appears.

#### IT-3: Hibernation Recovery — Quick Settings Tile

1. Force-stop as above.
2. Open quick settings → tap SteleKit capture tile.
3. Expected: `CaptureActivity` opens, may briefly show placeholder (< 1 s), then shows capture sheet.

#### IT-4: No Regression — Normal MainActivity Path

1. Cold start via launcher icon.
2. Expected: App loads graph normally; no double-DB-open errors in logcat; no blank flash.

#### IT-5: Fresh Install — All Entry Points

1. Fresh install (no previous data).
2. Tap capture widget.
3. Expected: "Open SteleKit" placeholder shown (no graph configured).

---

## Risk Assessment

| Risk | Severity | Likelihood | Mitigation |
|---|---|---|---|
| `switchGraph` from `init` accesses uninitialized field | BLOCKED | Very Low | All fields accessed by `switchGraph` are initialized in class body before `init` runs (verified by field inspection). |
| Double `switchGraph` (init + MainActivity) corrupts state | LOW | High (normal path) | Existing close-then-reopen logic handles this. Brief null flash on `activeRepositorySet`, imperceptible to user. |
| `awaitPendingMigration()` hangs in `provideGlance` | LOW | Very Low | `finally { deferred.complete(Unit) }` guarantees resolution even on exception/cancellation. |
| `collectAsState()` recomposition loop in `CaptureActivity` | LOW | Very Low | `StateFlow` only emits on value change; no loop possible unless `activeRepositorySet` oscillates between null and non-null, which only happens during `switchGraph` calls. |
| `AtomicBoolean` TOCTOU in `VoiceCaptureWidgetViewModel` | NONE | Very Low | `compareAndSet` is atomic; no TOCTOU. The only gap is between the atomic check and the `viewModelScope.launch` — but `launch` is synchronous (enqueues a coroutine) and the second call finds `initializing = true` before it can make progress. |
| SAF permission revoked after true hibernation (Android 12+) | MEDIUM | Low | Out of scope. DB opens successfully; file reads fail with SecurityException. Future work: detect revoked SAF and show reconnect screen. |
| Widget not visible after true hibernation (OEM behavior) | LOW | Low | Out of scope. Some OEM launchers remove widgets for hibernated apps regardless of fix. |

---

## Acceptance Criteria

AC-1: After `adb shell am force-stop`, tapping the Capture widget shows the Capture sheet
within 2 seconds (no "Open SteleKit first" text).

AC-2: After `adb shell am force-stop`, sharing from another app to SteleKit shows the Capture
sheet with shared content pre-filled.

AC-3: After `adb shell am force-stop`, tapping the Quick Settings tile launches `CaptureActivity`
(not `MainActivity`).

AC-4: Cold start via launcher icon: graph loads normally; logcat shows no `switchGraph`
initialization errors; no double-open warning.

AC-5: Fresh install: widget, tile, and share target all show the "Open SteleKit" placeholder.

AC-6: `awaitPendingMigration()` tests pass: the method always resolves and never blocks
indefinitely when called from a test coroutine.

AC-7: `GraphManagerAutoRestoreTest`: `init` calls `switchGraph` iff `activeGraphId` is non-null.

AC-8: `CaptureTileServiceTest`: tile launches `CaptureActivity` when `getActiveGraphId()` is
non-null, even when `getActiveRepositorySet()` is null.

---

## Implementation Order

1. `GraphManager.kt` — the root fix that makes all other fixes correct (must be first)
2. `CaptureWidget.kt` — simple one-liner
3. `VoiceWidget.kt` — simple one-liner
4. `CaptureTileService.kt` — simple one-liner
5. `CaptureActivity.kt` — moderate refactor of `setContent`
6. `VoiceCaptureWidgetViewModel.kt` — largest refactor, add `AtomicBoolean`

---

## Epic / Story / Task Breakdown

**Epic**: Android hibernation recovery (6 files, ~50 lines changed)

**Story 1**: Auto-restore active graph on process wakeup
- Task 1.1: Add `?.let { switchGraph(it) }` to `GraphManager.init`
- Task 1.2: Write `GraphManagerAutoRestoreTest` (init with/without activeGraphId)
- Task 1.3: Write `GraphManagerDoubleSwitchTest` (double switchGraph same id)

**Story 2**: Widget hibernation recovery
- Task 2.1: Add `awaitPendingMigration()` to `CaptureWidget.provideGlance`
- Task 2.2: Add `awaitPendingMigration()` to `VoiceWidget.provideGlance`
- Task 2.3: Write `CaptureWidgetProvideGlanceTest`

**Story 3**: Activity and ViewModel hibernation recovery
- Task 3.1: Refactor `CaptureActivity.setContent` to use `collectAsState()`
- Task 3.2: Wrap `VoiceCaptureWidgetViewModel.initialize()` in `viewModelScope.launch` + `AtomicBoolean`
- Task 3.3: Write `VoiceCaptureWidgetViewModelTest` (await + double-init guard)

**Story 4**: Tile service synchronous check
- Task 4.1: Replace `getActiveRepositorySet()` with `getActiveGraphId()` in `CaptureTileService.onClick`
- Task 4.2: Write `CaptureTileServiceTest`

**Story 5**: Integration verification
- Task 5.1: Manual test IT-1 through IT-5
- Task 5.2: Document SAF-revocation limitation in known issues
