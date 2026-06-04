# Architecture Research: All Android Entry Points Calling `getActiveRepositorySet()`

## Files Examined
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/VoiceCaptureWidgetViewModel.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/tile/CaptureTileService.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/widget/CaptureWidget.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/widget/VoiceWidget.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/widget/CaptureWidgetReceiver.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/widget/VoiceWidgetReceiver.kt`

---

## Entry Point Survey

### 1. `CaptureWidget.kt`

**Location**: `provideContent {}` Composable lambda inside `provideGlance` (a `suspend fun`)

**Current code**:
```kotlin
override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent {
        val ctx = LocalContext.current
        val hasGraph = (ctx.applicationContext as? SteleKitApplication)
            ?.graphManager?.getActiveRepositorySet() != null
        // ...
    }
}
```

**Context**: Inside `provideContent {}` — this is a Glance Composable context. Runs after `Application.onCreate()` but the call to `getActiveRepositorySet()` is a one-shot synchronous read at the moment of first composition.

**The Problem**: If `switchGraph()` hasn't been called yet (e.g., after process kill), `_activeRepositorySet.value` is `null` at composition time, so `hasGraph = false` and the widget shows `NoGraphContent`. Even if `switchGraph` was auto-called from `init`, the IO coroutine may not have finished by the time `provideContent` composes (it's launched on a background thread concurrently with the widget update path).

**Correct Fix Pattern**:
Use `collectAsState()` on the `StateFlow`:
```kotlin
override suspend fun provideGlance(context: Context, id: GlanceId) {
    val graphManager = (context.applicationContext as? SteleKitApplication)?.graphManager
    graphManager?.awaitPendingMigration()  // wait for IO coroutine to finish
    provideContent {
        val ctx = LocalContext.current
        val repoSet by (ctx.applicationContext as? SteleKitApplication)
            ?.graphManager?.activeRepositorySet
            ?.collectAsState()
            ?: remember { mutableStateOf(null) }
        val hasGraph = repoSet != null
        // ...
    }
}
```

Or more simply: await before `provideContent`, then the one-shot read is safe:
```kotlin
override suspend fun provideGlance(context: Context, id: GlanceId) {
    (context.applicationContext as? SteleKitApplication)
        ?.graphManager?.awaitPendingMigration()
    provideContent {
        val hasGraph = (LocalContext.current.applicationContext as? SteleKitApplication)
            ?.graphManager?.getActiveRepositorySet() != null
        // ...
    }
}
```

Both approaches work. The `awaitPendingMigration()` approach is simpler; `collectAsState()` is more reactive (handles multi-switch scenarios but is overkill for the widget use case).

**Verdict**: **Await before `provideContent`** is the correct and minimal fix.

---

### 2. `VoiceWidget.kt`

**Location**: `provideContent {}` Composable lambda inside `provideGlance`

**Current code**:
```kotlin
override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent {
        val ctx = LocalContext.current
        val hasGraph = (ctx.applicationContext as? SteleKitApplication)
            ?.graphManager?.getActiveRepositorySet() != null
        // ...
    }
}
```

**Context**: Identical structure to `CaptureWidget`. Same one-shot synchronous read problem.

**Correct Fix Pattern**: Same as `CaptureWidget` — `awaitPendingMigration()` before `provideContent {}`.

```kotlin
override suspend fun provideGlance(context: Context, id: GlanceId) {
    (context.applicationContext as? SteleKitApplication)
        ?.graphManager?.awaitPendingMigration()
    provideContent {
        val hasGraph = (LocalContext.current.applicationContext as? SteleKitApplication)
            ?.graphManager?.getActiveRepositorySet() != null
        // ...
    }
}
```

**Verdict**: Same fix as `CaptureWidget`.

---

### 3. `CaptureActivity.kt`

**Location**: `setContent {}` Composable lambda in `onCreate()`

**Current code**:
```kotlin
setContent {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        if (app.graphManager?.getActiveRepositorySet() == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NoGraphPlaceholderContent()
            }
        } else {
            CaptureScreen(...)
        }
    }
}
```

**Context**: `setContent {}` is a Compose `@Composable` invocation on the Activity's main thread. `app.graphManager?.getActiveRepositorySet()` is a one-shot read at first composition. This is the most problematic entry point for the planned fix.

**The Problem**: Even if `switchGraph()` is auto-called from `GraphManager.init`, the IO coroutine runs on `Dispatchers.IO`. When `CaptureActivity.onCreate()` is called, `Application.onCreate()` has run (creating `GraphManager`, starting the `switchGraph` IO coroutine), but by the time `setContent` composes the first frame, the IO coroutine may or may not have completed. On a fast device with an already-migrated database, it likely completes in <100ms. On a first-launch or after hibernation with the write-behind flush, it could take longer.

**Correct Fix Pattern**: Observe `activeRepositorySet` as a `StateFlow`:
```kotlin
setContent {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        val repoSet by app.graphManager?.activeRepositorySet?.collectAsState()
            ?: remember { mutableStateOf(null) }
        if (repoSet == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NoGraphPlaceholderContent()
            }
        } else {
            CaptureScreen(viewModel = viewModel, onSaved = { ... }, onDismiss = { finish() })
        }
    }
}
```

This pattern correctly handles the async window: the activity initially shows `NoGraphPlaceholderContent` but recomposes to `CaptureScreen` as soon as `switchGraph`'s IO coroutine sets `_activeRepositorySet.value`.

An alternative: use `LaunchedEffect` to await and then call `CaptureViewModel.initializeGraph(repoSet)`.

**Note on `CaptureViewModel`**: `CaptureViewModel` reads `graphManager` internally (via `viewModels()`). The ViewModel is initialized before `setContent` runs. If `CaptureViewModel` itself reads `getActiveRepositorySet()` at init time, it also needs fixing. This was not observed in the provided code, but should be checked.

**Verdict**: Change the `if (app.graphManager?.getActiveRepositorySet() == null)` check from a one-shot read to `collectAsState()` on `activeRepositorySet`.

---

### 4. `VoiceCaptureWidgetViewModel.kt`

**Location**: `initialize()` function, called from `VoiceCaptureActivity`

**Current code**:
```kotlin
fun initialize(requestMicPermission: suspend () -> Boolean) {
    if (inner != null) return

    val steleApp = getApplication<SteleKitApplication>()
    val repoSet = steleApp.graphManager?.getActiveRepositorySet()
    if (repoSet == null) {
        _state.value = VoiceCaptureState.Error(
            PipelineStage.RECORDING,
            "No graph configured — open SteleKit first",
            VoiceErrorKind.NO_GRAPH,
        )
        return
    }
    // ... build voice pipeline with repoSet
}
```

**Context**: `initialize()` is a regular (non-suspend) function. It is called from the Activity (presumably `VoiceCaptureActivity`). The function body is synchronous — there is no `suspend` context. `viewModelScope` is available but not used in `initialize()`.

**The Problem**: `getActiveRepositorySet()` is a synchronous one-shot read. If called before the `switchGraph` IO coroutine completes, it returns `null` and shows an error.

**Correct Fix Pattern**: Make `initialize()` a suspend fun, or launch a coroutine on `viewModelScope` that awaits the migration:

**Option A — Launch on viewModelScope (non-breaking signature)**:
```kotlin
fun initialize(requestMicPermission: suspend () -> Boolean) {
    if (inner != null) return
    viewModelScope.launch {
        val steleApp = getApplication<SteleKitApplication>()
        steleApp.graphManager?.awaitPendingMigration()
        val repoSet = steleApp.graphManager?.getActiveRepositorySet()
        if (repoSet == null) {
            _state.value = VoiceCaptureState.Error(...)
            return@launch
        }
        // ... rest of init
    }
}
```

**Option B — collectAsState on StateFlow in Activity**: The calling Activity observes `graphManager.activeRepositorySet.collectAsState()` and calls `viewModel.initialize(repoSet)` only when non-null.

Option A is cleaner as it keeps the initialization logic inside the ViewModel.

**viewModelScope lifecycle concern**: `viewModelScope` is cancelled when the `ViewModel` is cleared (Activity destroyed). Awaiting `awaitPendingMigration()` inside `viewModelScope.launch` is safe — if the Activity is destroyed before the migration completes, the coroutine is cancelled cleanly.

**Verdict**: Wrap `initialize()` body in `viewModelScope.launch { awaitPendingMigration(); ... }`.

---

### 5. `CaptureTileService.kt`

**Location**: `onClick()` — a regular (non-suspend, non-coroutine) Android framework callback

**Current code**:
```kotlin
override fun onClick() {
    super.onClick()
    val app = applicationContext as? SteleKitApplication
    val targetClass = if (app?.graphManager?.getActiveRepositorySet() != null) 
        CaptureActivity::class.java
    else 
        MainActivity::class.java
    val intent = Intent(this, targetClass).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // ... startActivityAndCollapse(...)
}
```

**Context**: `onClick()` is called on the main thread synchronously. It cannot `suspend` or `await` anything. It must make a synchronous decision about which Activity to launch.

**The Problem**: `getActiveRepositorySet()` returns `null` if `switchGraph`'s IO coroutine hasn't finished. This could cause the tile to launch `MainActivity` even when a graph is configured.

**The Trade-off**: `onClick()` must return quickly (tile UI responsiveness). It cannot block awaiting the migration.

**Fallback Behavior Analysis**:
- If `getActiveRepositorySet()` returns `null` incorrectly, the user is sent to `MainActivity` instead of `CaptureActivity`. `MainActivity` will call `switchGraph()` via `StelekitApp`, so the user eventually gets the right experience — just with a slower path.
- This is an acceptable degraded experience, not a crash. The tile is the entry point least likely to have the race (it requires the Quick Settings shade to be open and the user to tap quickly).

**Possible Mitigations**:
1. **Check `activeGraphId` instead**: `graphManager.getActiveGraphId()` reads from `_graphRegistry` (set during `loadRegistry()`, which IS synchronous), so it's always accurate even before `switchGraph` completes. If `activeGraphId != null`, launch `CaptureActivity`; let `CaptureActivity` handle the "wait for graph" state internally (via `collectAsState()`).
2. **Accept the degraded path**: Launch `MainActivity` if `getActiveRepositorySet() == null` — this is already the current behavior. As long as `CaptureActivity` correctly waits for the graph (via `collectAsState()`), the tile can remain as-is.

**Recommended fix**: Use `getActiveGraphId() != null` as the check in `onClick()`. This is always synchronously correct because `_graphRegistry` is populated in `loadRegistry()` which runs in `init`.

```kotlin
override fun onClick() {
    val app = applicationContext as? SteleKitApplication
    val targetClass = if (app?.graphManager?.getActiveGraphId() != null)
        CaptureActivity::class.java
    else
        MainActivity::class.java
    // ...
}
```

**Verdict**: Replace `getActiveRepositorySet() != null` with `getActiveGraphId() != null` in `onClick()`. No await/suspend needed. `CaptureActivity` handles the async initialization internally.

---

## State Read Pattern Summary

| Entry Point | Current Pattern | Problem | Recommended Fix |
|---|---|---|---|
| `CaptureWidget.provideContent` | One-shot `getActiveRepositorySet()` in Composable | Race: IO coroutine may not be done | `awaitPendingMigration()` before `provideContent`, OR `collectAsState()` inside |
| `VoiceWidget.provideContent` | One-shot `getActiveRepositorySet()` in Composable | Same race | Same fix as `CaptureWidget` |
| `CaptureActivity.setContent` | One-shot `getActiveRepositorySet()` in Composable | Race: IO coroutine may not be done | `collectAsState()` on `activeRepositorySet` StateFlow |
| `VoiceCaptureWidgetViewModel.initialize()` | One-shot `getActiveRepositorySet()` in non-suspend fun | Race: IO coroutine may not be done | `viewModelScope.launch { awaitPendingMigration(); ... }` |
| `CaptureTileService.onClick()` | One-shot `getActiveRepositorySet()` | Cannot await; wrong target when null | Replace with `getActiveGraphId() != null`; let `CaptureActivity` handle async init |

---

## Cross-Cutting Observation: `CaptureTileService.onStartListening()`

`onStartListening()` is called when the Quick Settings panel is opened (the tile becomes visible). This is also a non-coroutine callback. It currently only updates the tile icon/label — it does not check `getActiveRepositorySet()`. This is correct behavior and does not need changes.

## Cross-Cutting Observation: `VoiceCaptureActivity` (not in provided files)

`VoiceCaptureActivity` is referenced in `VoiceWidget.kt` as the launch target. It presumably creates a `VoiceCaptureWidgetViewModel`. If it calls `viewModel.initialize()` directly in `onCreate()`, the race exists there too. After fixing `VoiceCaptureWidgetViewModel.initialize()` to use `viewModelScope.launch { awaitPendingMigration() }`, the Activity can continue calling `initialize()` without changes.
