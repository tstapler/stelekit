# Stack Research: web-local-folder-livesync

**Date**: 2026-07-17
**Scope**: Technology choices for bidirectional live-sync between a picked local host
directory and SteleKit's web (Kotlin/Wasm) build.

## 1. Existing codebase patterns this feature must follow

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt` — the established
  house style for all File System / OPFS interop: small `private fun ...(): kotlin.js.Promise<JsAny> =
  js("...")` wrappers, paired with an `internal suspend fun` that calls `.await()`. No
  interop wrapper library is used anywhere in this codebase for File System Access API or
  OPFS — everything is hand-rolled `js()`/`external`. New code for
  `FileSystemObserver`/directory-handle persistence should follow the exact same idiom for
  consistency, not introduce a new interop-generation tool.
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` —
  `pickDirectoryAsync()` (line 324) already gates on `showDirectoryPickerSupported()` (line
  323, `typeof window.showDirectoryPicker === 'function'`) and calls `importUserDirToCache()`
  (line 341) for the one-shot import this feature must upgrade to live sync. The dirty-set
  tracking (`dirtySet`, `.stele-dirty-set.json`, lines 28–178) is a second, independent
  consumer of "what changed locally" that this feature's write-through/external-change
  detection must not corrupt — reuse `recordDirty()`/`getDirtySnapshot()` rather than adding a
  parallel tracking mechanism.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt:433` —
  `externalFileChanges: SharedFlow<ExternalFileChange>` and `emitExternalFileChange()` (line
  423) are the desktop file-watcher's public surface. The web implementation should emit into
  this exact same flow (via a wasmJs-specific watcher/poller that calls
  `emitExternalFileChange` or an equivalent constructor path) so `ConflictResolutionScreen`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreen.kt`)
  and `SyncState` (`git/model/SyncState.kt`) need no new UI — this is an explicit constraint
  in requirements.md.
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt` — already
  wraps `kotlinx.browser.localStorage` for simple string key/value persistence. **Not
  sufficient for this feature**: `FileSystemDirectoryHandle` objects are not
  JSON-serializable, only structured-cloneable, so they cannot go through `localStorage`
  (string-only) — they require IndexedDB, which is untouched anywhere in this codebase today
  (`grep -rl indexedDB kmp/src` — zero hits). This is new interop surface, greenfield.
- `kotlinx.browser` (and `org.w3c.dom.*`) is already available on the wasmJs classpath with
  **no explicit Gradle dependency line** in `kmp/build.gradle.kts` — it ships bundled with the
  Kotlin/Wasm browser target since Kotlin 2.x. No new dependency is needed just to reach
  `localStorage`/`window`/`document`; the same should hold for reaching `navigator.locks` and
  `indexedDB` via `external`/`js()` declarations, since those are plain global objects.
- Kotlin version in this repo: **2.3.21** (`kmp/build.gradle.kts:204`,
  `kotlin-reflect:2.3.21`) — current enough for all Kotlin/Wasm interop features referenced
  below (`external`, `@JsFun`, `JsAny`/`JsAny?` subtyping, try/catch over JS exceptions).

## 2. File System Access API — current state (verified July 2026)

Browser support remains **Chromium-only** (Chrome/Edge/Opera); no Firefox/Safari support as
of this writing — matches the requirements.md constraint and needs no design change.
[MDN File System API](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API),
[File System Access explainer](https://wicg.github.io/file-system-access/).

### FileSystemObserver — now shipped, not experimental

This is the single biggest update since prior SteleKit research (`stelekit-web-opfs`
explicitly deferred file-picker work; the requirements.md "Rabbit Holes" section assumes "no
native filesystem-watch API in browsers"). That assumption is **now partially outdated**:

- `FileSystemObserver` ran an origin trial Chrome 129→134 (Sep 2024–Feb 2025) and **shipped to
  stable in Chrome 133 (Jan 29, 2025)**. It is a normal, non-flagged API in current Chrome/Edge.
  [Chrome blog](https://developer.chrome.com/blog/file-system-observer),
  [MDN FileSystemObserver](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemObserver),
  [Intent to Ship thread](https://groups.google.com/a/chromium.org/g/blink-dev/c/6oOaFmia2dc).
- API shape: `new FileSystemObserver(callback)`, then
  `observer.observe(handle, { recursive: true })` on a `FileSystemDirectoryHandle` or
  `FileSystemFileHandle` (works for both the user-granted local directory handle **and** OPFS
  handles). `recursive: true` is required to get changes in subdirectories — default is
  shallow (directory itself + direct children only).
  [observe() docs](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemObserver/observe).
- Callback receives an array of `FileSystemChangeRecord`: `changedHandle`,
  `relativePathComponents` (path from observed root to the changed entry),
  `type` (`"appeared"`, `"disappeared"`, `"modified"`, `"moved"`, `"errored"`), and
  `relativePathMovedFrom` for `"moved"` records.
  [FileSystemChangeRecord](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemChangeRecord).
- Firefox/Safari: not implemented (consistent with the rest of File System Access API).
  [caniuse](https://caniuse.com/mdn-api_filesystemobserver_observe).

**Implication for the plan**: this converts external-change detection from "must be
best-effort polling/focus-recheck" (as the requirements' Feasibility Risks assumed) into "use
`FileSystemObserver` as the primary mechanism in Chromium, with polling-on-focus as the
fallback for anything not covered" (moves outside the observed tree, browsers where the API
is entirely absent — none among the in-scope Chromium set, but defense-in-depth is still
warranted since this is a young API). This should be flagged back to the planning phase as a
significant simplification opportunity vs. what requirements.md assumed.

### Persisting `FileSystemDirectoryHandle` across sessions — IndexedDB

- `FileSystemFileHandle`/`FileSystemDirectoryHandle` are **structured-cloneable**, so they can
  be stored directly as IndexedDB values (put the handle object itself, not a JSON
  serialization of it) and retrieved intact in a later session — this is the standard,
  documented pattern.
  [Persistent file handling with FSA API](https://transloadit.com/devtips/persistent-file-handling-with-the-file-system-access-api/),
  [queryPermission() docs](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemHandle/queryPermission).
- Retrieval flow: read the handle back from IndexedDB → call
  `handle.queryPermission({ mode: 'readwrite' })` → if `"granted"`, resume immediately with no
  UI; if `"prompt"`, show the "one click to resume access" affordance from requirements.md and
  call `handle.requestPermission({ mode: 'readwrite' })` inside a user-activation event
  handler (click); if `"denied"`, fall back to re-picking. This directly satisfies the
  "Reopening the app in a new tab/session ... requires at most one click" success metric.
- **Persistent permissions** (no per-session re-grant at all) shipped in Chrome 122 (Feb 2024)
  but require the site to be an **installed PWA** — un-installed tab-based use still gets the
  session-scoped grant and reverts to `"prompt"` on next launch.
  [Chrome blog: Persistent permissions](https://developer.chrome.com/blog/persistent-permissions-for-the-file-system-access-api).
  SteleKit's web build is not currently distributed as an installed PWA (no manifest/service
  worker referenced in the explored files) — until/unless that changes, the one-click re-grant
  UX is the correct baseline, matching the requirements' stated constraint exactly. Worth a
  requirements-clarifying note for planning: shipping a PWA manifest later would let this
  feature "upgrade" to zero-click resume for free.

### Kotlin/Wasm interop shape for the above (matches existing house style)

```kotlin
// IndexedDB open/get/put — hand-rolled, matching OpfsInterop.kt's style
private fun idbOpenPromise(name: String, version: Int): kotlin.js.Promise<JsAny> =
    js("new Promise(function(res, rej) { var r = indexedDB.open(name, version); r.onupgradeneeded = function(e) { e.target.result.createObjectStore('handles'); }; r.onsuccess = function(e) { res(e.target.result); }; r.onerror = function(e) { rej(e); }; })")

private fun idbPutHandlePromise(db: JsAny, key: String, handle: JsAny): kotlin.js.Promise<JsAny> =
    js("new Promise(function(res, rej) { var tx = db.transaction('handles', 'readwrite'); tx.objectStore('handles').put(handle, key); tx.oncomplete = function() { res(handle); }; tx.onerror = function(e) { rej(e); }; })")

private fun idbGetHandlePromise(db: JsAny, key: String): kotlin.js.Promise<JsAny?> =
    js("new Promise(function(res) { var tx = db.transaction('handles', 'readonly'); var req = tx.objectStore('handles').get(key); req.onsuccess = function() { res(req.result || null); }; req.onerror = function() { res(null); }; })")

private fun queryPermissionPromise(handle: JsAny, mode: String): kotlin.js.Promise<JsAny> =
    js("handle.queryPermission({ mode: mode })")
private fun requestPermissionPromise(handle: JsAny, mode: String): kotlin.js.Promise<JsAny> =
    js("handle.requestPermission({ mode: mode })")

// FileSystemObserver
private fun newFileSystemObserver(callback: (JsAny) -> Unit): JsAny =
    js("new FileSystemObserver(function(records) { callback(records); })")
private fun observePromise(observer: JsAny, handle: JsAny, recursive: Boolean): kotlin.js.Promise<JsAny> =
    js("observer.observe(handle, { recursive: recursive })")
```

Notes:
- A library exists (`com.juul.indexeddb:core`, added Kotlin/Wasm support in **v0.12.0**,
  removing `dynamic` in favor of `JsAny`) if a typed coroutine wrapper is preferred over
  hand-rolled `js()`. Given this codebase's consistent choice to hand-roll every File
  System/OPFS interop point rather than pull in a wrapper, **recommend continuing that
  pattern** for IndexedDB too — it is a small, well-bounded surface (open DB, put/get one
  handle, one permission-state string) and avoids a new external dependency for a project that
  currently has zero IndexedDB usage anywhere. If a wrapper is wanted anyway,
  `com.juul.indexeddb:core` is the actively-referenced option in the Kotlin/Wasm community
  (JetBrains Slack, kotlin-wrappers issue tracker); a newer fork,
  `com.eygraber.indexeddb:core-wasm-js`, also exists but has a much thinner adoption trail
  (single 0.0.1-era release visible) — lower confidence, not recommended over hand-rolled
  interop or JuulLabs' library.
  [JuulLabs/indexeddb](https://github.com/JuulLabs/indexeddb).
- Kotlin/Wasm interop constraints to respect throughout (per current Kotlin docs): JS interop
  signatures (`external`, `= js("...")`, `@JsExport`) are restricted to `JsAny`/subtypes and a
  narrow set of primitives; wrap raw JS exceptions with Kotlin `try/catch` at the interop
  boundary exactly as `OpfsInterop.kt` already does (`catch (e: Throwable)` around every
  `.await()`), since unguarded JS promise rejections crash through as uncaught `Throwable` —
  directly relevant to this repo's CLAUDE.md rule about uncaught coroutine `Throwable`s.
  [Kotlin/Wasm JS interop docs](https://kotlinlang.org/docs/wasm-js-interop.html).

## 3. Web Locks API — cross-tab coordination

- `navigator.locks.request(name, options?, callback)` — standard, broadly supported (not
  Chromium-only; also in Firefox/Safari), stable API, last MDN update reviewed April 2025, no
  material changes expected.
  [MDN Web Locks API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Locks_API).
- Locks are **origin-scoped** and shared across tabs/workers of the same origin — exactly the
  primitive needed for the requirements' "Cross-tab coordination" in-scope item: only one tab
  should hold the live "write-through + observe" role for a given picked directory at a time
  (leader election), while other tabs of the same origin either defer to the leader or queue.
  The canonical pattern is `navigator.locks.request('graph-sync-<graphId>', { ifAvailable:
  true }, callback)` to attempt non-blocking leader election, falling back to steady-state
  `navigator.locks.request(name, callback)` (blocks until acquired, held until the async
  callback resolves) for serializing writes to the same file across tabs.
  [w3c/web-locks explainer](https://github.com/w3c/web-locks/blob/main/EXPLAINER.md).
- Kotlin/Wasm interop: same hand-rolled `js()` + `Promise<JsAny>.await()` pattern; `request()`'s
  callback-based API (lock auto-releases when the callback's promise resolves) maps cleanly
  onto a Kotlin `suspend` lambda wrapped in a `js("navigator.locks.request(name, function(lock)
  { return __kotlinAsyncBridge(lock); })")`-style bridge, mirroring how this codebase already
  bridges JS iterators/promises in `OpfsInterop.kt` (`listOpfsEntries`).

## 4. Fallback path for non-Observer / degraded cases

Even with `FileSystemObserver` available in the whole in-scope browser set, keep a
belt-and-suspenders fallback consistent with the existing `jsVisibilityHiddenPromise()`
pattern (`OpfsInterop.kt:163`) that already listens for `visibilitychange`:
- Re-check dirty/changed state (`handle.getFile()` → compare `lastModified`/size, or content
  hash) on tab focus/visibility-restore, independent of the observer — cheap insurance against
  observer bugs/gaps in a young API, and the natural place to re-run `queryPermission()` in
  case the OS revoked access while the tab was hidden.
- This also directly addresses the "external-change detection should not require a full
  directory re-scan if perceptibly slow on a large graph" NFR: `FileSystemObserver` change
  records are already scoped to the changed entries, so no full-tree diff is needed in the
  common case; the focus-recheck fallback should be similarly scoped (compare only files with
  observer-reported changes since last observer-confirmed-healthy checkpoint, not a full
  re-walk) — full re-scan should be reserved for "observer errored" recovery only.

## 5. Paranoid mode / byte-level I/O compatibility

`writeFileBytes`/`readFileBytes` (`PlatformFileSystem.kt:299`, `opfsWriteFileBytes` in
`OpfsInterop.kt:87`) already use `createWritable()` + `writable.write(buffer)` +
`writable.close()` against a `FileSystemFileHandle` obtained via `getFileHandle()` — this is
the *same* `FileSystemWritableFileStream` API surface used against OPFS handles today, and it
is identical when the handle instead comes from the user's picked local directory tree
(`FileSystemFileHandle` from a `FileSystemDirectoryHandle.getFileHandle()` walk is
API-identical whether the directory is OPFS-backed or host-filesystem-backed). No new I/O
primitive is needed for paranoid-mode `.md.stek` blobs over this path — the existing
`opfsWriteFileBytes`/pattern can be generalized to accept any `FileSystemDirectoryHandle` root
(OPFS root today, host directory root for this feature) rather than always calling
`getOpfsRoot()`.

## Summary of concrete recommendations

| Concern | Recommendation |
|---|---|
| Directory handle persistence | IndexedDB, store `FileSystemDirectoryHandle` directly (structured clone), hand-rolled `js()`/`external` interop matching `OpfsInterop.kt` style — no new dependency required |
| Permission resume | `queryPermission()` on load → if `"prompt"`, one-click re-grant button → `requestPermission()` inside the click handler (user-activation requirement) |
| External-change detection | `FileSystemObserver` (shipped Chrome 133+, Jan 2025) as primary; `visibilitychange`-triggered scoped recheck as fallback/insurance, following the existing `jsVisibilityHiddenPromise()` idiom |
| Write-through | Reuse `writeFile`/`writeFileBytes`'s existing `createWritable()`/`write()`/`close()` pattern against the retained host-directory handle instead of (or in addition to) the OPFS handle |
| Conflict surfacing | Emit into existing `GraphLoader.externalFileChanges` / `emitExternalFileChange()` so `ConflictResolutionScreen`/`SyncState.ConflictPending` need no changes |
| Cross-tab coordination | `navigator.locks` (`navigator.locks.request`), origin-scoped, broadly supported, leader-election pattern (`ifAvailable: true`) for "who owns the live directory handle" |
| Dependency additions | None strictly required. Kotlin 2.3.21 already on classpath; `kotlinx.browser` already available without an explicit Gradle line. Optional: `com.juul.indexeddb:core` (0.12.0+) if a typed wrapper over hand-rolled IndexedDB interop is preferred — not recommended given the codebase's consistent hand-rolled-interop convention |
| Browser support ceiling | Unchanged from requirements: Chromium-only (Chrome/Edge/Opera) for the whole File System Access API family, including `FileSystemObserver` |

## Open questions to carry into planning (Phase 3)

1. Should SteleKit ship a PWA manifest to unlock zero-click persistent permissions (Chrome
   122+), or is the one-click re-grant acceptable long-term UX? Requirements already treats
   one-click as acceptable — flagging only because it's now cheaper than previously assumed.
2. `FileSystemObserver`'s `"errored"` record type and its recovery semantics need a design
   decision (full re-scan vs. re-`observe()` vs. surface as a new `ExternalFileChange`
   variant) — not fully specified by MDN docs at the level of detail this feature needs;
   worth a short spike during planning.
3. Exact `navigator.locks` naming/scoping strategy per-graph (multi-graph support already
   exists in `GraphManager`) needs to be nailed down so two picked directories for two
   different graphs in two tabs don't contend on the same lock name.
