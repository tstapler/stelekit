# ADR-004: `ShadowFlushActor` as Stateless `suspend fun`, No Owned `CoroutineScope`

## Status
Accepted

## Context

`ShadowFlushActor` is responsible for draining a queue of dirty shadow-file entries and flushing each one to SAF via `PlatformFileSystem.writeFile`. The prior implementation created a `CoroutineScope` internally:

```kotlin
class ShadowFlushActor(...) {
    private val scope = CoroutineScope(Dispatchers.IO)  // never cancelled
    fun flush() { scope.launch { /* drain queue */ } }
    fun stop() { /* not called / not implemented */ }
}
```

This scope was never cancelled. Each call to `flush()` launched a new coroutine on the orphaned scope. When the enclosing `PlatformFileSystem` was replaced (e.g., on graph switch) or when the process-scoped app singleton was garbage collected, the orphaned scope and all its running coroutines were silently leaked. On Android, coroutine leaks in long-lived singletons can prevent garbage collection of large object graphs and cause `OutOfMemoryError` under heap pressure.

Additionally, the coroutine launched by `flush()` completed its `writeFile()` call but did not call `markWrittenByUs` afterward, causing the subsequent poll cycle to treat the just-flushed SAF file as an external change (the `FileRegistry` mtime was stale). For encrypted `.md.stek` files — where the content-hash guard is disabled — this emitted spurious conflict dialogs.

## Decision

`ShadowFlushActor.flush()` is a `suspend fun` with no owned `CoroutineScope`:

```kotlin
class ShadowFlushActor(
    private val shadowCache: ShadowFileCache,
    private val fileSystem: PlatformFileSystem,
    private val onFlushed: (suspend (safPath: String) -> Unit)? = null,
) {
    suspend fun flush() = withContext(Dispatchers.IO) {
        while (true) {
            val entry = shadowCache.dequeueNextDirty() ?: break
            val content = shadowCache.read(entry.localPath) ?: continue   // shadow missing — skip, no retry
            val success = fileSystem.writeFile(entry.safPath, content)
            if (success) {
                shadowCache.stampMtime(entry.localPath, fileSystem.getLastModifiedTime(entry.safPath))
                onFlushed?.invoke(entry.safPath)
            }
            // On failure: entry stays in queue; caller retries on next flush trigger
        }
    }
}
```

`flush()` runs entirely within the caller's coroutine context. The caller (`PlatformFileSystem.flushPendingWrites`) is already a `suspend fun` invoked from the app's `onStop` / write-behind trigger — it controls the lifecycle. No `start()`/`stop()` lifecycle methods are needed; the function returns when the queue is drained.

The `onFlushed` callback is invoked after each successful `writeFile` call to allow `FileRegistry` to stamp the new SAF mtime, suppressing the subsequent poll's spurious own-write detection. It is not called on write failure or when the shadow file is missing.

## Alternatives Considered

**Actor pattern with `Channel`**: A `Channel<FlushRequest>` processed by a long-lived coroutine on an owned scope. Provides backpressure and serialisation across concurrent callers. Adds `start()`/`stop()` lifecycle management and a `Job` reference. Overkill for a drain loop that is already invoked from a single call site (`flushPendingWrites`), and reintroduces the scope-ownership problem. Rejected.

**`onStop`-scoped coroutine (caller passes scope)**: Caller passes a `CoroutineScope` tied to the `onStop` lifecycle event. Introduces the forbidden pattern of passing a caller-supplied scope to a class that may outlive it (CLAUDE.md constraint). Rejected.

**Keep owned scope, add explicit `stop()` call**: Requires every call site that tears down `PlatformFileSystem` to also call `actor.stop()`. Fragile — any missed `stop()` call on a code path (graph switch, settings change, test teardown) recreates the leak. The stateless `suspend fun` approach makes the leak structurally impossible. Rejected.

## Consequences

- `ShadowFlushActor` has no `start()` / `stop()` / `cancel()` methods. Callers do not manage its lifecycle.
- `PlatformFileSystem.flushPendingWrites()` must be a `suspend fun` (it already is in the existing codebase).
- The `onFlushed` callback wires to `graphLoader::markFileWrittenByUs` in `App.kt`, closing the mtime-staleness window for encrypted files.
- `onFlushed` is NOT called when the shadow file is missing (entry is dequeued without retry) or when `writeFile` returns false (entry remains in queue for next flush attempt).
- A narrow race exists between `writeFile()` completing and `onFlushed()` being called (~5–50 ms SAF IPC round-trip). A concurrent `onStart` scan during this window may still emit a spurious own-write event for `.md.stek` files. This window can be further reduced in a future improvement by recording the SAF path in a "flush-pending" set immediately before `writeFile()` and clearing it in `onFlushed`.
