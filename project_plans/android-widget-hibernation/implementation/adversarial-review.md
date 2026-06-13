# Adversarial Review: Android Widget Hibernation Recovery Plan

**Reviewer role**: Adversarial — searching for BLOCKED-level issues only (correctness bugs,
data loss, concurrency hazards, Android lifecycle violations). Concerns that are merely
suboptimal are noted but do not block.

**Verdict**: CONCERNS

---

## Attack Surface Examined

Five specific attack vectors were analyzed:

1. `init` auto-restore: initialization order hazard in `switchGraph()`
2. Double-`switchGraph` race: MainActivity completes before `init`'s coroutine
3. `awaitPendingMigration()` in `provideGlance`: Glance-specific timeout risk
4. `collectAsState()` in `CaptureActivity`: recomposition loop / memory leak
5. `AtomicBoolean` guard in `VoiceCaptureWidgetViewModel`: TOCTOU issue

---

## Finding 1: `switchGraph` Accesses `_graphRegistry.value.activeGraphId` — Could Differ from the Argument

**Severity**: CONCERN (not BLOCKED)

**Attack**: `switchGraph(id)` reads `_graphRegistry.value` at line:

```kotlin
fun switchGraph(id: String) {
    val registry = _graphRegistry.value           // read #1
    val currentGraphId = registry.activeGraphId   // the CURRENT active (may differ from `id`)
    currentGraphId?.let { activeGraphJobs.remove(it)?.cancel() }
    // ...
    val updatedRegistry = registry.copy(activeGraphId = id)
    _graphRegistry.value = updatedRegistry        // write: sets active to `id`
    saveRegistry()
}
```

When called from `init`:
- `_graphRegistry.value.activeGraphId` == `id` (the same graph from `loadRegistry()`).
- `registry.activeGraphId` and the `id` argument are the same value.
- `currentGraphId` == `id` → `activeGraphJobs.remove(id)?.cancel()` — removes and
  cancels the job for `id`. But `activeGraphJobs` is empty at `init` time! So this is
  a no-op. Safe.

**Verdict on this attack**: Not a bug. All field accesses inside `switchGraph()` are
initialized before `init` runs. The `activeGraphJobs` map is empty; the cancellation
line is a no-op. No initialization order hazard exists.

---

## Finding 2: Double-`switchGraph` Race — MainActivity COMPLETES Before `init` Coroutine Starts

**Severity**: CONCERN (not BLOCKED)

**Attack**: The most dangerous scenario is:

1. `GraphManager.init` calls `switchGraph("abc")`.
   - Sets `_pendingMigration = deferred1`.
   - Puts `graphScope1` in `activeGraphJobs["abc"]`.
   - Launches `coroutine1` on `PlatformDispatcher.IO` (not yet started).

2. `StelekitApp` (via MainActivity) calls `switchGraph("abc")` immediately:
   - `activeGraphJobs.remove("abc")?.cancel()` — cancels `graphScope1` before `coroutine1` ever runs.
   - `currentFactory?.close()` — `currentFactory` is still null (coroutine1 never ran). No-op.
   - `_activeRepositorySet.value = null` — already null. No-op.
   - Sets `_pendingMigration = deferred2`.
   - Puts `graphScope2` in `activeGraphJobs["abc"]`.
   - Launches `coroutine2`.

3. `coroutine1` starts (scheduled after the main thread yields):
   - Its scope (`graphScope1`) is already cancelled.
   - `preFlightJob?.await()` throws `CancellationException`.
   - `finally { deferred1.complete(Unit) }` runs — completes the OLD deferred.
   - No `RepositorySet` is ever produced from `coroutine1`.

4. `coroutine2` runs normally, produces a `RepositorySet`, sets
   `_activeRepositorySet.value`.

**Is there a problem?** Any caller that called `awaitPendingMigration()` after step 1
but before step 2 got `deferred1`. `deferred1` completes correctly in step 3. But when
they proceed to call `getActiveRepositorySet()` after `deferred1.complete(Unit)`, the
`RepositorySet` may not be ready yet (it's set by `coroutine2`, which may still be
running). This is actually the correct behavior documented in the research: callers must
null-check after `awaitPendingMigration()`. No bug.

**The real race to check**: Can `coroutine2` complete and set `_activeRepositorySet.value`
BEFORE `coroutine1` throws `CancellationException` and tries to set
`_activeRepositorySet.value`? No — `coroutine1`'s scope is cancelled at step 2 before
it runs, so it never reaches the `_activeRepositorySet.value = repoSet` line.

**What about `_pendingMigration` replacement at step 2?** `_pendingMigration` is a
`var`, not `volatile` or `@Volatile`. Reads/writes to `_pendingMigration` happen on the
main thread (from `switchGraph()` calls) and from background coroutines via
`awaitPendingMigration()`. **This is a real concurrency concern.**

- Write: `_pendingMigration = deferred` — from `switchGraph()`, called on the main thread.
- Read: `_pendingMigration.await()` — from `awaitPendingMigration()`, potentially called
  from any coroutine (e.g., `provideGlance` on the Glance dispatcher).

In the JVM memory model, a non-`@Volatile` var accessed from multiple threads without
synchronization is a data race. If the Glance coroutine dispatcher thread reads
`_pendingMigration` between step 1 and step 2, it may get `deferred1`. Then in step 2,
the main thread replaces `_pendingMigration` with `deferred2`. The widget awaited the
old `deferred1`, which eventually completes (step 3), then reads `getActiveRepositorySet()`.
At that point, `coroutine2` may still be running and `_activeRepositorySet.value` may
still be null. The widget then shows `NoGraphContent` even though the DB will be ready
soon.

**Is this BLOCKED?** No — the plan already handles this: after `awaitPendingMigration()`
returns, callers must null-check `getActiveRepositorySet()`. The widget plan shows
`NoGraphContent` on null, which is the correct graceful degradation. The user may see
a stale "no graph" widget for a brief extra period before the second DB open completes
and a new `provideGlance` is triggered. This is a UX imperfection, not a correctness bug.

**Recommendation (CONCERN)**: Mark `_pendingMigration` as `@Volatile` to eliminate the
JVM data race. This is a one-character fix and removes a theoretical JVM visibility bug:

```kotlin
@Volatile
private var _pendingMigration: Deferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }
```

This does not affect correctness of the fix as designed, but it is the correct practice
for a `var` accessed from multiple threads.

---

## Finding 3: `awaitPendingMigration()` in `provideGlance` — Glance Timeout

**Severity**: CLEAN (no issue)

**Attack**: The plan claims `awaitPendingMigration()` is safe in `provideGlance`.

**Analysis**:
- `provideGlance` runs in a Glance-managed coroutine, not on the main thread. ANR risk: none.
- Android's App Standby scheduler may kill the process if widget update takes too long,
  but `awaitPendingMigration()` is bounded by DB open + migration (≤5 s typical, ≤30 s
  theoretical for very large graphs). The `goAsync()` in `CaptureWidgetReceiver` provides
  ~30 s budget.
- `finally { deferred.complete(Unit) }` in `switchGraph`'s IO coroutine guarantees
  `awaitPendingMigration()` always resolves — even if DB init fails.
- If the `graphScope` is cancelled before the IO coroutine runs, `CancellationException`
  propagates to `coroutine.finally`, which calls `deferred.complete(Unit)`. The deferred
  completes; `awaitPendingMigration()` returns; `getActiveRepositorySet()` returns null.
  Widget shows `NoGraphContent`. Correct graceful degradation.

**Verdict**: No Glance-specific timeout that silently drops widget updates was found. CLEAN.

---

## Finding 4: `collectAsState()` in `CaptureActivity` — Recomposition Loop / Memory Leak

**Severity**: CLEAN (no issue)

**Attack**: The plan uses `collectAsState()` on `activeRepositorySet` inside `setContent`.

**Recomposition loop analysis**: A recomposition loop requires the composable to trigger
state changes that cause it to recompose infinitely. `activeRepositorySet` is a
`StateFlow<RepositorySet?>`. It only emits when `_activeRepositorySet.value` is assigned
in `switchGraph`. Normal recomposition of `CaptureActivity` (e.g., keyboard appearing,
theme change) does NOT cause `activeRepositorySet` to emit. No loop possible.

**Memory leak analysis**: `collectAsState()` inside `setContent` attaches the collector
to the Compose `LifecycleOwner`. The `ComponentActivity` implements `LifecycleOwner`.
Compose's internal `LaunchedEffect`-based collector is cancelled when the composition is
disposed. `CaptureActivity` is a single-instance, translucent overlay — when `finish()`
is called, the Activity is destroyed, composition is disposed, the collector is cancelled.
No leak.

**The `?: remember { MutableStateFlow(null) }` fallback**: If `app.graphManager` is null
(Application init failed), this creates a stable never-emitting `MutableStateFlow(null)`.
The `collectAsState()` on this flow will always return null (its initial value). The `remember`
has no key, so the fallback flow is stable across recompositions. This is correct.

**Subtle concern**: `remember` inside `setContent` without a key captures the first-evaluated
value permanently for the lifetime of the composition. If `app.graphManager` is somehow null
at first composition and then becomes non-null later (it's a `var` on the Application),
the `remember` would stick with the null fallback. However: `graphManager` is assigned in
`Application.onCreate()` before any Activity is created, and it's never reassigned after
that. `CaptureActivity.onCreate()` runs after `Application.onCreate()`. So
`app.graphManager` is always in its final state when `setContent` is first called. No issue.

**Verdict**: No recomposition loop or memory leak. CLEAN.

---

## Finding 5: `AtomicBoolean` Guard — TOCTOU Issue in `VoiceCaptureWidgetViewModel`

**Severity**: CLEAN (no issue)

**Attack**: Is there a TOCTOU window between `compareAndSet` and `viewModelScope.launch`?

```kotlin
fun initialize(requestMicPermission: suspend () -> Boolean) {
    if (inner != null) return                                    // (A)
    if (!initializing.compareAndSet(false, true)) return         // (B) — atomic
    viewModelScope.launch {                                      // (C)
        try { ... inner = vm ... }
        finally { initializing.set(false) }
    }
}
```

The `compareAndSet` at (B) is atomic on the JVM (`AtomicBoolean` uses `CAS` instruction).
Only one thread can win the `CAS`. Any other thread calling `initialize()` concurrently will
find `initializing = true` and return at (B). The `viewModelScope.launch` at (C) happens
on the calling thread synchronously (it enqueues a coroutine and returns immediately). There
is no window between (B) and (C) where a second caller could slip through.

**Could the Activity call `initialize()` from multiple threads?** `initialize()` is called
from `VoiceCaptureActivity.onCreate()` which runs on the main thread. Android guarantees
Activity lifecycle callbacks run on the main thread. So in practice, `initialize()` is
only ever called from the main thread — the `AtomicBoolean` is defensive but harmless.

**The actual TOCTOU to check**: The guard at (A) `if (inner != null) return` is NOT
thread-safe (plain read of a non-volatile `var`). However, since `initialize()` is only
called from the main thread (Android lifecycle guarantee), and `inner` is only written
from the `viewModelScope.launch` coroutine which runs on `Dispatchers.Main.immediate` by
default, the main-thread ordering is: (A) read → `launch` enqueued → coroutine runs
(which writes `inner`). By the time `inner != null`, any subsequent `initialize()` call
on the main thread will see the updated `inner` because both reads and writes happen on
the same thread (main). No TOCTOU.

**Verdict**: The `AtomicBoolean` guard is correct and there is no TOCTOU. CLEAN.

---

## Finding 6: `VoiceCaptureWidgetViewModel` — Inner `viewModelScope.launch` Inside Outer Launch

**Severity**: CONCERN

**Attack**: The plan contains a nested `viewModelScope.launch` inside the outer
`viewModelScope.launch`:

```kotlin
viewModelScope.launch {  // outer
    ...
    val vm = VoiceCaptureViewModel(...)
    viewModelScope.launch {          // inner — for state collection
        vm.state.collect { _state.value = it }
    }
    inner = vm
    vm.onMicTapped()
}
```

This structure is present in the CURRENT code (before the fix) as well. The inner
`launch` is launched from within the outer launch. When the outer launch completes,
the inner `launch` (the state collector) continues running independently on
`viewModelScope`. This is intentional — the state collection is a long-lived job.

**Concern**: When `onCleared()` calls `inner?.close()`, does `close()` cancel the inner
`vm.state` flow? If `vm.state` is a `StateFlow` backed by an internal `CoroutineScope`,
`close()` should cancel that scope, causing `vm.state.collect { }` to complete. The inner
launch then finishes. `viewModelScope` is cancelled by Android when the ViewModel is
cleared (after `onCleared()`). Any remaining child jobs are cancelled. This is safe.

**The concern**: if `close()` does NOT cancel the internal scope of `VoiceCaptureViewModel`,
the state collector inner launch becomes a zombie coroutine running on a cancelled
`viewModelScope`. However, `viewModelScope` cancellation propagates to all children —
the collector is cancelled regardless. No permanent leak.

**Verdict**: CONCERN-level. The plan inherits this structure from the existing code. It
should be verified that `VoiceCaptureViewModel.close()` cancels its internal scope (which
the plan assumes but does not verify). If it does not, there is a minor leak window between
`onCleared()` and `viewModelScope` cancellation. This is not a correctness bug in the
hibernation fix itself — it pre-exists the change.

---

## Finding 7: `CaptureActivity` — `CaptureViewModel` Reads `graphManager` at `viewModels()` Init Time

**Severity**: CONCERN

**Attack**: The plan fixes `CaptureActivity.setContent` to use `collectAsState()`.
However, `CaptureActivity` also has:

```kotlin
private val viewModel: CaptureViewModel by viewModels()
```

The `CaptureViewModel` is initialized when first accessed (lazy delegation). In `onCreate`:

```kotlin
val shareContent = parseShareIntent(intent)
if (shareContent.imageLocalPath != null) {
    viewModel.initializeText(...)    // <-- first access: ViewModel created here
} else {
    viewModel.initializeText(...)
}
```

The `viewModel` is accessed before `setContent`. If `CaptureViewModel.init {}` or its
factory reads `graphManager.getActiveRepositorySet()` synchronously, it would still race
with the DB open, regardless of the `collectAsState()` fix in `setContent`.

**Mitigation needed**: Check `CaptureViewModel` for any synchronous `getActiveRepositorySet()`
calls in its `init` block or constructor. The plan notes this: "Note on `CaptureViewModel`:
This was not observed in the provided code, but should be checked." This is the right flag —
but the plan does not explicitly verify this file and does not include it as a task.

**Verdict**: CONCERN. The plan's acceptance criteria and test plan do not include verifying
`CaptureViewModel`'s own initialization. This is a gap that could leave the fix incomplete
if `CaptureViewModel` has its own synchronous graph access.

**Recommended addition to plan**:
- Add Task 3.0: Read `CaptureViewModel.kt` and verify no synchronous `getActiveRepositorySet()`
  call exists in its `init` or at ViewModel creation time. If found, apply same
  `awaitPendingMigration()` pattern.

---

## Summary Table

| Attack | Severity | Finding |
|---|---|---|
| `init` initialization order hazard | CLEAN | All fields accessed by `switchGraph` are initialized before `init` runs |
| Double-`switchGraph` state corruption | CONCERN | `_pendingMigration` is non-`@Volatile`; should be marked `@Volatile` |
| `provideGlance` timeout / silent drop | CLEAN | `finally` guarantee + bounded migration time; no ANR path |
| `collectAsState()` recomposition loop | CLEAN | `StateFlow` only emits on explicit assignment; no loop possible |
| `AtomicBoolean` TOCTOU | CLEAN | `compareAndSet` is atomic; `initialize()` is main-thread-only in practice |
| Nested `viewModelScope.launch` state leak | CONCERN | Pre-existing issue; verify `VoiceCaptureViewModel.close()` cancels internal scope |
| `CaptureViewModel` sync init gap | CONCERN | Plan does not verify `CaptureViewModel.kt`; should be checked before implementing |

---

## Verdict: CONCERNS

No BLOCKED issues found. The core fix strategy is sound. Three CONCERN-level items require
attention before shipping:

**C-1 (High priority)**: Mark `_pendingMigration` as `@Volatile` in `GraphManager.kt`.
One-character fix; eliminates a real JVM memory model data race.

**C-2 (Medium priority)**: Verify `CaptureViewModel.kt` does not call
`getActiveRepositorySet()` synchronously at init time. If it does, apply the same
`awaitPendingMigration()` pattern. The plan's fix is incomplete if this file is not checked.

**C-3 (Low priority)**: Confirm `VoiceCaptureViewModel.close()` cancels the `vm.state`
collector's internal scope. The `viewModelScope` cancellation is a safety net but
`close()` should be the primary cleanup path.

None of these are BLOCKED. The fix can proceed after addressing C-1 and C-2.
