# ADR-005: Move `freshProcess` Flag from Companion Object to `ShadowFileCache` Instance

## Status
Accepted

## Context

`ShadowFileCache` performs a full purge of its on-disk shadow directory on the first access after process start, then switches to mtime-based incremental invalidation. The "first access" detection relied on:

```kotlin
companion object {
    private val freshProcess = AtomicBoolean(true)
}
```

This flag is process-wide: once `freshProcess.compareAndSet(true, false)` fires for any `ShadowFileCache` instance, all subsequent instances in the same process see `freshProcess = false` and skip the full purge.

When a user switches graphs mid-session, `PlatformFileSystem` creates a new `ShadowFileCache` for the new graph ID. Because `freshProcess` had already been cleared by the first graph's cache, the new cache skipped its initial purge. Any external writes to the second graph's shadow directory during the backgrounded period were served from stale shadow files without being re-synced from SAF.

Additionally, the companion flag semantics are misleading: the flag is intended to express "this cache instance has not yet been initialised from SAF," not "this process has not yet been started."

## Decision

`ShadowFileCache` owns a per-instance freshness flag:

```kotlin
class ShadowFileCache(private val context: Context, private val graphId: String) {
    private val freshInstance = AtomicBoolean(true)

    fun isFirstAccess(): Boolean = freshInstance.compareAndSet(true, false)
}
```

`PlatformFileSystem.invalidateStaleShadow` calls `cache.isFirstAccess()` to decide between full purge (first access) and mtime-based invalidation (subsequent accesses):

```kotlin
suspend fun invalidateStaleShadow(graphId: String) {
    if (cache.isFirstAccess()) {
        cache.deleteAll()
        cache.syncFromSaf(treeUri)
    } else {
        cache.invalidateByMtime(treeUri)
    }
}
```

The `freshProcess` companion field is removed entirely.

## Alternatives Considered

**Keep process-wide flag, add per-graph `Set<String>` of initialised graph IDs in companion**: A `companion object { val initialisedGraphIds = ConcurrentHashMap.newKeySet<String>() }` tracks which graph IDs have been initialised. On first access for a given graph ID, full purge; on subsequent access, incremental. Correctly handles graph switches. Rejected because it introduces a global mutable collection that grows unboundedly across graph switches and is never cleared (even after the corresponding `ShadowFileCache` instance is garbage collected). The per-instance flag is simpler and has no unbounded-growth concern.

**Re-purge on every graph switch (unconditional `deleteAll` on construction)**: Always performs a full purge when a new `ShadowFileCache` is created. Correct, but wasteful for the common case where the second graph was also active in a previous session and its shadow is already coherent. The `AtomicBoolean` approach purges only once per instance, not on every access. Rejected as overly conservative.

**External lifecycle signal from `GraphManager`**: `GraphManager.addGraph` signals to `ShadowFileCache` that a fresh sync is needed. Requires `GraphManager` to know about `ShadowFileCache`, introducing an undesirable coupling across abstraction layers. Rejected.

## Consequences

- Each `ShadowFileCache` instance independently tracks its own first-access state via `freshInstance: AtomicBoolean(true)`.
- `isFirstAccess()` returns `true` exactly once per instance (on the first call) and `false` on all subsequent calls.
- Two `ShadowFileCache` instances for the same graph ID (e.g., in tests, or in a hypothetical multi-`PlatformFileSystem` scenario) would each independently trigger a full purge. The second `deleteAll()` finds nothing to delete (the first already purged the directory), and the subsequent `syncFromSaf` repopulates it correctly. No data corruption — `deleteAll()` is idempotent.
- The `freshProcess` companion field is removed. Any existing references to `ShadowFileCache.freshProcess` (in tests or reflection) must be updated to `cache.isFirstAccess()`.
- `ShadowFileCacheTest` must verify that `isFirstAccess()` is per-instance: two independent instances both return `true` on their first call and `false` on subsequent calls, with no cross-instance interference (TR-6 in requirements).
