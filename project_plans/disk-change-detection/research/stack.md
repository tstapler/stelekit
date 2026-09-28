# Stack Research: Disk Change Detection

## 1. Android FileObserver — API 29+ File vs String Constructor

### Recursive Behavior

Neither constructor is recursive. `FileObserver` wraps a single inotify watch on one directory inode. Events from subdirectories (`pages/`, `journals/`) are **not delivered** to a watch on the parent. This is a fundamental inotify limitation: `IN_CREATE` on a parent fires only when a file is directly created in that directory, not in a nested child.

### `FileObserver(File, mask)` vs `FileObserver(String, mask)`

- `FileObserver(String, mask)` is deprecated in API 29. It takes an absolute path string. The underlying behavior is identical — both create a single inotify watch.
- `FileObserver(File, mask)` (API 29+) is the preferred form. The `File` overload accepts a `java.io.File` and is cleaner for paths resolved via `File(root, "pages")`.
- There is no difference in recursive behavior between the two forms.

### Multi-Path Constructor

Android API 29 added `FileObserver(List<File>, mask)` which creates a single `FileObserver` monitoring multiple directories in one object. It is equivalent to creating N separate single-path instances — each path still gets its own inotify watch descriptor, and none are recursive.

**Conclusion for SteleKit**: The correct approach (already implemented in `SafChangeDetector`) is to create one `FileObserver` per subdirectory (`pages/`, `journals/`), each watching its respective directory inode. Using the multi-path API 29+ constructor could replace two instances with one object, but provides no functional difference. The current two-instance pattern is correct and clearer about which directories are being watched. The `filter { it.isDirectory }` guard before calling `startWatching()` is correct — it handles the case where `pages/` or `journals/` does not exist yet (SAF-only graphs).

**Reference**: [Android FileObserver docs](https://developer.android.com/reference/android/os/FileObserver)

---

## 2. Android ContentObserver + SAF — URI Shapes and notifyForDescendants

### ExternalStorageProvider Document ID Format

`ExternalStorageProvider` uses the format `{volumeId}:{relativePath}` for document IDs. For example:
- Tree root: `primary:Notes`
- Subdirectory: `primary:Notes/pages`
- File inside subdirectory: `primary:Notes/pages/Foo.md`

The corresponding children URI for a document is built with:
```kotlin
DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
// e.g. content://com.android.externalstorage.documents/tree/primary%3ANotes/children/primary%3ANotes%2Fpages
```

### How notifyChange Works

When `ExternalStorageProvider` detects a file change in `pages/Foo.md`, it calls:
```
context.contentResolver.notifyChange(childrenUri_for_pages, null)
```
where `childrenUri_for_pages` is the children URI of the `pages/` document node.

`ContentResolver.registerContentObserver(uri, notifyForDescendants, observer)`:
- When `notifyForDescendants = true`, the resolver delivers notifications to any registered observer whose registered URI is a **string prefix** of the notification URI.
- If you register on the **root children URI** (e.g. `.../children/primary%3ANotes`), and a change fires for `.../children/primary%3ANotes%2Fpages`, the root children URI is **not** a prefix of the pages children URI — the notification is not delivered. This was the root cause of bug FR-6.
- If you register on `.../children/primary%3ANotes%2Fpages` (the pages subdirectory children URI), then `notifyForDescendants=true` ensures notifications for individual files within `pages/` are delivered to that observer.

### Correct Registration Pattern

Register directly on the children URI of each subdirectory:
```kotlin
val subdirDocId = "$treeDocId/pages"   // e.g. "primary:Notes/pages"
val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, subdirDocId)
contentResolver.registerContentObserver(childrenUri, /* notifyForDescendants= */ true, observer)
```

This is exactly what `SafChangeDetector.startContentObserversAndPoller` does (already implemented correctly). The `notifyForDescendants=true` parameter is needed so that changes to individual files within the `pages/` directory (whose notification URIs are children of the registered URI) are delivered.

**Caveat**: Not all SAF providers reliably call `notifyChange`. The 30-second polling fallback in `SafChangeDetector` covers providers that don't.

---

## 3. Kotlin `combine` and Property Initializer Ordering

### The Pitfall

In Kotlin, class property initializers run in **declaration order**. When `combine(a, b)` references `b` and `b` is declared _after_ the `combine` call, the compiler will produce a compile error: "Variable '_b' must be initialized." This is not a runtime race — it is a static initialization ordering requirement enforced by the Kotlin compiler.

### Declaration Order Requirement

The requirement stated in the constraints section of `requirements.md` (`The _dirtyBlocks declaration must precede dirtyPageUuids in BlockStateManager`) reflects this rule. Specifically:

```kotlin
// CORRECT ORDER in BlockStateManager:
private val _dirtyBlocks = MutableStateFlow<Map<String, Long>>(emptyMap())  // line N
// ...
private val _blocks = MutableStateFlow<Map<String, List<Block>>>(emptyMap())  // line N+K

val dirtyPageUuids: StateFlow<Set<String>> = combine(_dirtyBlocks, _blocks) { ... }
    .stateIn(scope, SharingStarted.Eagerly, emptySet())
// ^^^^ This must be declared AFTER _dirtyBlocks AND _blocks
```

If `dirtyPageUuids` is declared before `_blocks`, the compiler sees `_blocks` as an uninitialized backing field at the point of the `combine` call.

### Current Implementation

`BlockStateManager.kt` (lines 155–172) correctly orders the declarations: `_dirtyBlocks` (line 155), then `_blocks` (line 149 — actually declared before `_dirtyBlocks` in the file; the `combine` references both, which is valid since both are declared before `dirtyPageUuids` at line 163).

The `scope` property must also precede `dirtyPageUuids` because `stateIn(scope, ...)` captures it during initialization. `BlockStateManager.scope` is a constructor parameter, so it is always initialized before any property initializer runs — no ordering issue there.

---

## 4. `SharingStarted.Eagerly` vs `WhileSubscribed` for `dirtyPageUuids`

### `SharingStarted.Eagerly`

- Starts the upstream `combine` immediately when `stateIn` is called (at class construction time).
- Never stops the upstream collection regardless of subscriber count.
- The `StateFlow` always holds the latest computed value, even with zero subscribers.
- Correct for `dirtyPageUuids` because:
  1. `GraphLoader.setUnsavedPageUuids` reads `dirtyPageUuids.value` (or collects it in a Job) at any point after construction, not just when a UI subscriber exists.
  2. The dirty set must be current at the moment an external file change fires — a `WhileSubscribed` flow that has been paused (no active subscribers) would have stale state.
  3. `BlockStateManager` owns its own scope (`SupervisorJob() + Dispatchers.Default`), so there is no external lifecycle to "subscribe" or "unsubscribe" to.

### `SharingStarted.WhileSubscribed`

- Appropriate when the shared flow should only be active when there is at least one active subscriber (e.g. a UI screen is open). Saves CPU/memory when no UI is consuming the flow.
- Inappropriate for `dirtyPageUuids` because `GraphLoader` needs it active at all times, not just when a Compose screen is collecting it.

### Verdict

`SharingStarted.Eagerly` is correct for `dirtyPageUuids`. This matches the existing implementation at `BlockStateManager.kt` line 172.

---

## 5. Coroutine Scope Ownership for Background Collection

### The Rule (from CLAUDE.md and requirements)

Classes that do background collection must own their `CoroutineScope` internally. They must **not** accept a caller-supplied scope (especially not `rememberCoroutineScope()`) because:
- Compose cancels `rememberCoroutineScope` scopes when the composable leaves composition.
- Any class stored in `remember { }` that accepted such a scope will throw `ForgottenCoroutineScopeException` on the next `launch`.

### Recommended Pattern for Background Collection Jobs

For a class like `GraphLoader` that needs a background Job (e.g. `unsavedPageFilePathsJob`) to collect a `StateFlow`:

```kotlin
class GraphLoader(...) {
    // Owned scope — never passed from outside
    private val parallelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e -> logger.error("parallelScope exception", e) })

    @Volatile private var unsavedPageFilePaths: Set<FilePath> = emptySet()
    private var unsavedPageFilePathsJob: Job? = null

    fun setUnsavedPageUuids(uuids: StateFlow<Set<String>>?) {
        unsavedPageFilePathsJob?.cancel()
        unsavedPageFilePathsJob = null
        if (uuids != null) {
            unsavedPageFilePathsJob = parallelScope.launch {
                uuids.collect { uuidSet ->
                    unsavedPageFilePaths = uuidSet.mapNotNull { ... }.toSet()
                }
            }
        } else {
            unsavedPageFilePaths = emptySet()
        }
    }
}
```

Key points:
1. The `Job` reference (`unsavedPageFilePathsJob`) allows cancellation when the upstream `StateFlow` is replaced (graph switch) or nulled out.
2. `@Volatile` on `unsavedPageFilePaths` ensures the written value is visible to the polling coroutine on `Dispatchers.Default` without a `Mutex` (reads are always the full set reference, writes are always a new object — safe for single-writer / many-reader access pattern).
3. The `CoroutineExceptionHandler` on the scope protects against an uncaught `Throwable` from the collection body killing the process on Android.
4. `ShadowFlushActor` (FR-9) demonstrates the complementary pattern: when a class has only a one-shot `suspend fun flush()`, no scope is needed at all — the caller's suspension context provides all the lifecycle management needed.

### When to Use `suspend fun` vs Job

| Need | Pattern |
|---|---|
| One-shot async operation | `suspend fun` — no scope, caller controls lifetime |
| Long-lived background collection with replaceable upstream | Owned scope + cancellable `Job` ref |
| Long-lived reactive collection with external lifecycle (UI) | `stateIn(scope, SharingStarted.Eagerly, ...)` on owned scope |

This is the pattern applied throughout the SteleKit codebase: `GraphFileWatcher`, `BlockStateManager`, `GraphLoader` all own their scopes; `ShadowFlushActor` is purely `suspend fun`.
