# Features Research: GraphManager Initialization Gap Analysis

## Relevant File Examined
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

---

## 1. What `loadRegistry()` Does vs What `switchGraph()` Does

### `loadRegistry()` (called from `init`)

`loadRegistry()` restores the **configuration** of the graph registry:

1. Reads the JSON from `platformSettings.getString("graph_registry", "")`.
2. Deserializes into a `GraphRegistry` (contains `activeGraphId: String?` and `graphs: List<GraphInfo>`).
3. Refreshes display names via `fileSystem.displayNameForPath()`.
4. Sets `_graphRegistry.value = refreshed`.
5. If no registry exists, calls `migrateFromSingleGraph()` which checks for `lastGraphPath` and constructs a `GraphRegistry` with that path as the active graph.

**What `loadRegistry()` does NOT do:**
- Does not create a `RepositoryFactory` or open any database.
- Does not set `_activeRepositorySet.value` (remains `null`).
- Does not set `currentFactory` (remains `null`).
- Does not create `graphScope` or launch any coroutines.
- Does not await `preFlightJob`.

### `switchGraph(id: String)` (must be called explicitly)

`switchGraph()` actually opens the database and creates the usable repository layer:

1. Cancels previous graph's coroutine scope and shuts down git sync.
2. Calls `currentFactory?.close()` and sets `_activeRepositorySet.value = null` (clears old state).
3. Creates a new `graphScope = CoroutineScope(coroutineScope.coroutineContext)`.
4. Creates `deferred = CompletableDeferred<Unit>()` and assigns `_pendingMigration = deferred`.
5. Launches an IO coroutine on `graphScope` that:
   - Awaits `preFlightJob` (write-behind flush).
   - Creates `RepositoryFactoryImpl` with the database URL.
   - Calls `factory.createRepositorySet(...)` which triggers `SteleDatabase` lazy init, which calls `DriverFactory.createDriver()`, which runs `MigrationRunner` via `Schema.create()`.
   - Sets `currentFactory = factory` and `_activeRepositorySet.value = repoSet`.
   - Runs `UuidMigration` and `MigrationRunner`.
   - In `finally`: completes `deferred`.
6. Updates `_graphRegistry.value` with the new `activeGraphId` and persists to `SharedPreferences`.

### The Gap

After `loadRegistry()` completes:
- `_graphRegistry.value.activeGraphId` = the stored graph ID (e.g., `"abc123..."`)
- `_activeRepositorySet.value` = `null`
- `currentFactory` = `null`
- `_pendingMigration` = a pre-completed `CompletableDeferred` (the default from the class init)

After `switchGraph(id)` completes its IO coroutine:
- `_activeRepositorySet.value` = a fully initialized `RepositorySet`
- `currentFactory` = a live `RepositoryFactoryImpl`

**The bug**: `getActiveRepositorySet()` returns `_activeRepositorySet.value` which is `null` until `switchGraph()` is called. Only `MainActivity` → `StelekitApp` calls `switchGraph()` (via `StelekitViewModel.navigateTo()` or similar). Widget/tile/share entry points that start a new process get a `GraphManager` that knows the active graph ID but has not opened the database.

---

## 2. Would Auto-Calling `switchGraph()` from `init` Break the Normal `MainActivity` Flow?

### Analysis of the Proposed Change

```kotlin
init {
    loadRegistry()
    // NEW: auto-restore active graph
    val activeId = _graphRegistry.value.activeGraphId
    if (activeId != null) switchGraph(activeId)
}
```

### Does `switchGraph` Have Idempotency?

Examining `switchGraph()`:

```kotlin
fun switchGraph(id: String) {
    // ...
    currentFactory?.close()   // closes old factory (null-safe)
    currentFactory = null
    _activeRepositorySet.value = null
    // creates new scope, launches IO coroutine
}
```

`switchGraph()` is **not idempotent** in the sense that calling it a second time with the same ID:
1. Calls `currentFactory?.close()` — closes the database connection opened by the first call.
2. Sets `_activeRepositorySet.value = null` — briefly invalidates the repository set.
3. Launches a new IO coroutine — opens a second database connection.

### Impact on `MainActivity` Flow

`MainActivity` does not call `switchGraph()` directly. It passes `graphManager` to `StelekitApp`, which calls `switchGraph()` via the graph management UI (e.g., when `StelekitApp` detects the active graph path and calls `graphManager.switchGraph(graphId)`).

Looking at `MainActivity.onCreate()`:
```kotlin
StelekitApp(
    graphPath = ...,
    graphManager = app.graphManager,
    ...
)
```

Inside `StelekitApp` (not read here, but inferred from architecture), `switchGraph()` is called when a graph path is provided. If `init` already called `switchGraph(activeId)`, then `StelekitApp` calling it again with the same ID will:
- Close the in-flight or completed database connection.
- Re-open it.
- Run migrations again (idempotent by design — `MigrationRunner` checks which have already run).

**This is wasteful but not broken.** The second `switchGraph()` call will briefly set `_activeRepositorySet.value = null`, which could cause a UI flash (composables observing it would temporarily see `null`). In practice, the IO coroutine completes very quickly on second call (database is already migrated), so the null window is short.

### Safer Alternative: Guard in `switchGraph`

To make double-calls truly safe, add an idempotency guard:

```kotlin
fun switchGraph(id: String) {
    // If already open for this ID, skip re-initialization
    if (_graphRegistry.value.activeGraphId == id && _activeRepositorySet.value != null) return
    // ... rest of current code
}
```

This prevents the null flash and the double database open. However, this guard must be carefully designed — if `switchGraph(id)` was called but its IO coroutine is still in flight (factory not yet set), `activeRepositorySet` is still null, and the guard should NOT skip the second call.

A more robust guard: track whether a `switchGraph` is in progress for a given ID.

### `preFlightJob` Behavior When Called from `Application.onCreate()`

`preFlightJob` is created in `SteleKitApplication.onCreate()` before `GraphManager` is constructed:

```kotlin
startupFlushJob = appScope.async(Dispatchers.IO) {
    try { fileSystem.flushPendingWrites() }
    catch (e: Exception) { ... }
}
graphManager = GraphManager(
    ...
    preFlightJob = startupFlushJob,
)
```

`GraphManager.init` runs synchronously inside the `GraphManager` constructor call. At that point, `startupFlushJob` has already been created (as an `async` coroutine on `appScope`). The `preFlightJob` is `Deferred<Unit>`, and `switchGraph()` awaits it inside its IO coroutine — not in `init` itself. So calling `switchGraph()` from `init` is safe: the `graphScope.launch(PlatformDispatcher.IO)` coroutine will simply await the deferred when it runs, which will be after `Application.onCreate()` returns (coroutines are scheduled, not run eagerly in the constructor).

**Conclusion**: `preFlightJob` is correctly awaited in the IO coroutine, not synchronously. Calling `switchGraph()` from `init` does not create any deadlock or ordering issue with `preFlightJob`.

---

## 3. What `awaitPendingMigration()` Guarantees

```kotlin
suspend fun awaitPendingMigration() {
    _pendingMigration.await()
}
```

`_pendingMigration` is initialized to a pre-completed `CompletableDeferred`:
```kotlin
private var _pendingMigration: Deferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }
```

After `switchGraph()` is called, `_pendingMigration` is replaced with a new incomplete `CompletableDeferred`. The `switchGraph` IO coroutine always calls `deferred.complete(Unit)` in a `finally` block:

```kotlin
graphScope.launch(PlatformDispatcher.IO) {
    try {
        // ... all initialization ...
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("switchGraph initialization failed for graph $id", e)
    } finally {
        deferred.complete(Unit)  // ALWAYS called
    }
}
```

### Guarantees:
1. `awaitPendingMigration()` **always completes** — the `finally` block ensures `deferred.complete(Unit)` is called even if an exception occurs during factory creation or migration.
2. `awaitPendingMigration()` completing does **not guarantee** that `_activeRepositorySet.value` is non-null — if factory creation failed, `_activeRepositorySet.value` may still be `null` after `awaitPendingMigration()` returns. Callers must still null-check after awaiting.
3. Before `switchGraph()` is ever called, `_pendingMigration` is the pre-completed deferred, so `awaitPendingMigration()` returns immediately without suspending. This is the correct behavior for the "no active graph" case.
4. If `switchGraph()` was called from `init` and the IO coroutine has not yet run, `awaitPendingMigration()` will suspend until it does. This is the correct blocking behavior for widgets.

### Race Condition: `_pendingMigration` Replacement

There is a subtle race: `switchGraph()` sets `_pendingMigration = deferred` before launching the coroutine. If `switchGraph()` is called a second time before the first coroutine completes, the old `deferred` is replaced. Any caller that called `awaitPendingMigration()` and got the old deferred reference is fine (they await the old deferred, which will eventually complete when the old coroutine finishes or is cancelled). The new `deferred` is what future callers of `awaitPendingMigration()` will get.

However, because `switchGraph()` cancels the old `graphScope` at the start (`activeGraphJobs.remove(it)?.cancel()`), the old IO coroutine is cancelled — meaning it may throw `CancellationException` in the `finally` block, and `deferred.complete(Unit)` is still called (because `CancellationException` re-throws but `finally` still runs). So even the old deferred completes correctly.

---

## Summary

| Question | Answer |
|---|---|
| What `loadRegistry()` does | Restores graph registry metadata from SharedPreferences; does NOT open database or set `activeRepositorySet` |
| What `switchGraph()` does | Opens the database connection, runs migrations, sets `activeRepositorySet`; always async via IO coroutine |
| Does double `switchGraph()` break things? | Not catastrophically, but it causes a brief null flash on `activeRepositorySet` and wastes a db open/close cycle. A guard is recommended. |
| Is `switchGraph()` from `init` safe re: `preFlightJob`? | Yes — `preFlightJob` is awaited inside the IO coroutine, not synchronously |
| Does `awaitPendingMigration()` always complete? | Yes — guaranteed by `finally { deferred.complete(Unit) }` in `switchGraph`'s IO coroutine |
| Does completion mean `activeRepositorySet` is non-null? | No — if initialization failed, it may still be null. Always null-check after awaiting. |
