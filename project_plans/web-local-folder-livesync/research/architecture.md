# Research: Architecture & Integration — web-local-folder-livesync

Builds directly on `project_plans/web-git-writeback/research/architecture.md` ("the sibling
doc"). That doc's §2 (dirty-tracking hook placement), §4 (`.stele-dirty-set.json` schema), and §6
(recommendations) are treated as given, not re-derived. **Important state change since the sibling
doc was written**: the dirty-tracking machinery it recommended is no longer a proposal — it is
shipped code. `PlatformFileSystem.kt:28-178` (wasmJs) already implements `dirtySet`,
`recordDirty()`, `.stele-dirty-set.json` checkpointing with coalesced writes, `clearDirtySet()`,
`getBaseSha()`/`setPendingCommit()`, and `git/GitWriteLock.kt` already implements a Web-Locks
cross-tab mutex for the push critical section. This project's design must sit next to *working*
code, not a spec.

## 0. What actually exists today vs. what requirements.md assumes

Read directly from `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
(387 lines) and `OpfsInterop.kt` (181 lines):

- `pickDirectoryAsync()` (`PlatformFileSystem.kt:324-339`) calls `showDirectoryPicker()`, then
  `importUserDirToCache(dirHandle, opfsPath)` (`:341-363`), and **the `JsAny` directory handle is
  a local variable that goes out of scope the moment `pickDirectoryAsync` returns**. It is not
  stored on `this`, not passed anywhere, not persisted. There is currently no field anywhere in
  the class that could hold a retained handle — this project adds the first one.
- `importUserDirToCache` is unconditional and has no diff/merge logic: every file it encounters
  is written straight into `cache`/`blobUrlCache` and re-mirrored to OPFS
  (`:351-359`, `:355 scope.launch { opfsWriteFile(path, content) }`). It does not consult
  `dirtySet`, does not compare against existing `cache` entries, and does not ask before
  overwriting. **This is the exact mechanism that creates the migration hazard analyzed in §5.**
- `writeFile`/`writeFileBytes`/`deleteFile` (`:267-321`) already call `recordDirty(path, op)`
  (`:88-95`) for the *git*-write-back dirty set. They do **not** talk to any host directory —
  confirmed by reading the full bodies: `writeFile` does `cache[path] = content` +
  `recordDirty(...)` + `scope.launch { opfsWriteFile(...) }` and nothing else. This project must
  add a second, independent side-effect to these same three methods (host write-through) without
  disturbing the git dirty-set contract already living there.
- `getLastModifiedTime()` (`:365`) is hardcoded `null` unconditionally. `listFiles`/
  `listDirectories` (`:253-262`) enumerate `cache.keys` — an in-memory map, never the live host
  directory. This is why `GraphFileWatcher`'s poll loop (see §2) is currently a structural no-op
  on web: it has nothing live to compare against.
- `companion object` (`:370-376`) holds `githubOwner`/`githubRepo`/`githubBranch`/`githubToken`
  as static vars, matching the sibling doc's §3 observation about scattered config — same file,
  same pattern, now also the natural place a reviewer will look for "is there a retained
  directory handle" state, i.e. **do not add another parallel companion-object convention**; put
  new per-graph state (handle, host-mtime cache) in instance fields keyed by path, matching how
  `cache`/`dirtySet` are already instance state.

## 1. Where write-through and external-change-detection integrate — reuse map

The codebase already has two fully-general, platform-agnostic subsystems this feature should
plug into rather than re-invent. Both are commonMain, both are already wired end-to-end for
JVM/Android, and both are currently *inert* on web only because the wasmJs `PlatformFileSystem`
gives them no live signal:

### 1.1 `FileRegistry` + `GraphFileWatcher` — the external-change-detection architecture

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt` and
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt` already implement exactly
what requirements.md's "Detecting external file changes" scope item asks for, generically:

- `GraphFileWatcher.startWatching()` (`GraphFileWatcher.kt:111-153`) runs a 5-second polling loop
  (`pollIntervalMs = 5_000L` default) that calls `FileRegistry.detectChanges(dirPath)` for
  `pages/` and `journals/`, plus a platform-native fast-path trigger channel fed by
  `FileSystem.startExternalChangeDetection(scope, onChange)` (no-op by default, currently no-op
  on web — `FileSystem.kt:36`).
- `FileRegistry.detectChanges()` (`FileRegistry.kt:96-184`) compares
  `fileSystem.listFilesWithModTimes(dirPath)` against a remembered mod-time map, with a
  content-hash guard (`FileRegistry.kt:112-169`) to suppress the app's own writes and to catch
  timestamp-went-backward cases from sync tools. This is a **synchronous, non-suspend** call —
  `listFilesWithModTimes`'s default implementation (`FileSystem.kt:28-29`) is
  `listFiles(path).map { name -> name to (getLastModifiedTime(...) ?: 0L) }`, both non-suspend.
- `GraphFileWatcher` is instantiated unconditionally inside `GraphLoader`
  (`GraphLoader.kt:340`, confirmed by grep — not gated per platform), so **it is already running
  on web today**, once per graph, doing nothing useful because `getLastModifiedTime` is always
  `null` (`PlatformFileSystem.kt:365`) → every mod-time compares as `0 == 0` → never `>`.
- The conflict surface this feeds is fully commonMain and already live-wired: `GraphLoader`
  re-exposes `fileWatcher.externalFileChanges` as `externalFileChanges: SharedFlow<ExternalFileChange>`
  (`GraphLoader.kt:433`), and `StelekitViewModel.observeExternalFileChanges()`
  (`StelekitViewModel.kt:1424-1495+`) already implements the full four-tier "is this page
  currently being edited" protection logic, `DomainError.ConflictError.DiskConflict`
  (`error/DomainError.kt:33`), and the `PendingConflict`/snackbar/dialog UI
  (`DiskConflictDialog.kt`, `DiskConflictFullScreen.kt`, `DiskConflictBlockMatcher.kt`) — none of
  it platform-gated.

**Recommendation**: do not build a new "external change detection" subsystem. Make the wasmJs
`PlatformFileSystem`'s `listFilesWithModTimes`/`getLastModifiedTime`/`readFile` reflect the
*retained host handle's* state, and the entire existing `FileRegistry` → `GraphFileWatcher` →
`GraphLoader.externalFileChanges` → `StelekitViewModel.observeExternalFileChanges` →
`DiskConflictDialog` pipeline starts working on web for free, with zero commonMain changes — the
same "zero commonMain surface change" property the sibling doc's §2 recommendation had for the
dirty-tracking hook.

**The concrete gap that creates real work**: `listFilesWithModTimes`/`getLastModifiedTime` are
**synchronous**; every File System Access API call (`getFileHandle`, `handle.getFile()` for its
`.lastModified`) is a **Promise**. There is no synchronous bridge. This is the load-bearing
architectural decision for this sub-feature (see §2.3): `PlatformFileSystem` needs an *internal*
async poller (its own `scope.launch` loop, same idiom already used for the OPFS mirror writes)
that periodically walks the retained handle, and caches the results — content into `cache` (or
`bytesCache`), and mtimes into a new synchronous-readable map — so that the pre-existing
synchronous `FileRegistry` poll (running on its own independent 5s cadence) sees fresh data on
its *next* tick. Two independent polling loops, one feeding the other's read surface. This is not
a redesign of `FileRegistry`/`GraphFileWatcher` — it is satisfying their existing synchronous
contract from an async source, the same shape as Android's SAF `invalidateStaleShadow`/
`syncShadow` batch-mtime-then-cache pattern (`FileSystem.kt:128-145`) already does for a different
async-flavored backend.

### 1.2 Write-through as a second, independent consumer of the write call sites

The sibling doc's §2 recommendation ("hook lives entirely inside the wasmJs actual, not via a
`FileSystem.markDirty` contract change") generalizes directly: this feature adds a **third**
side-effect to the same three write methods, alongside the two that already exist there
(`cache[path] = content` and `recordDirty(path, op)` for the git dirty-set):

```kotlin
actual override fun writeFile(path: String, content: String): Boolean {
    if (path.startsWith(DOWNLOAD_PREFIX)) { /* unchanged */ }
    cache[path] = content
    recordDirty(path, DirtyOp.WRITE)              // existing — git write-back dirty-set
    scope.launch { opfsWriteFile(path, content) }  // existing — OPFS mirror
    scheduleHostWriteThrough(path, content)        // NEW — this project
    return true
}
```

**Why this must be a structurally separate call, not a piggyback on `recordDirty`**: the two
dirty-sets have different consumers, different persistence, and different failure semantics —
exactly the "conflating two unrelated concerns behind one signal" trap the sibling doc's §2
already warned about for `markDirty` vs. git dirty-tracking, now recurring one layer up:

| | git write-back dirty-set (`dirtySet`, existing) | host write-through queue (new) |
|---|---|---|
| Consumer | `WasmGitWriteService`, on explicit user-triggered `push()` | Background write-through, no user trigger |
| Persisted where | `.stele-dirty-set.json` in OPFS (`PlatformFileSystem.kt:70,163-178`) | Nothing needs persisting across reload — see below |
| Cleared by | `clearDirtySet()` after a successful GitHub/GitLab push | Cleared the instant the host `createWritable()`/`close()` succeeds for that path |
| Survives reload? | Yes — must, since a push can be minutes after the edit | No — on reload the host handle is re-acquired (or permission re-requested) and a fresh reconciliation pass (§5) supersedes any queued-but-unflushed write |
| Failure mode | Retry-from-scratch on next `push()` (per sibling doc §0/§6) | Retry on next successful host write, or surface as a persistent "not synced to folder" indicator — do not silently drop |

Concretely: a small in-memory `hostWritePending: MutableSet<String>` (or a
`MutableMap<String, DirtyEntry>`-shaped structure reusing the existing `DirtyEntry`/`DirtyOp`
types from `git/model/` for consistency, but a **separate map instance**, never the same
`dirtySet` field) plus a debounced/coalesced flush loop mirroring the existing
`markerWriteInFlight`/`markerWriteDirty` coalescing idiom already at `PlatformFileSystem.kt:41-42,
147-161` — that exact "at most one write in flight, trailing writes coalesce" pattern is directly
reusable for host write-through, just targeting `createWritable()` on a `FileSystemFileHandle`
instead of the dirty-set-marker OPFS file.

**Interaction with `recordDirty`/git dirty-set — deliberately none, by design**: a file edited by
the user gets marked dirty in the git dirty-set (existing) *and* independently queued for host
write-through (new) from the same `writeFile()` call, but the two consumers race, retry, and
clear on entirely separate schedules and neither reads the other's state. This mirrors exactly
how `applyRemoteContent()` (`PlatformFileSystem.kt:286-291`) already documents the inverse
case — auto-merged remote content must update `cache`/OPFS *without* calling `recordDirty`,
because it isn't a local edit. By the same logic, host write-through succeeding is not a git
event and must never call `recordDirty`/`clearDirtySet` — pushing to the folder is not pushing to
the git remote, and the two dirty-sets must be able to disagree (e.g., host write-through
succeeds instantly but the git push hasn't happened yet, or vice versa if the user has no git
remote configured at all, which requirements.md frames as the primary target user).

### 1.3 `hasStoragePermission()` / permission-lost UX — reuse the pattern, not the signature

`App.kt:248,266,284-285` already has an established UI pattern: `fileSystem.hasStoragePermission()`
gates a "storage permission needed" banner, checked on resume and after picking. This is exactly
the UX shape requirements.md wants for "one-click resume access" (query FS Access API's
`handle.queryPermission({mode:'readwrite'})`, and on click, `handle.requestPermission(...)`).
**But `hasStoragePermission()` is synchronous** (`FileSystem.kt:31`) and FS Access permission
queries are Promise-based — the same sync/async mismatch as §1.1. Do not try to force
`queryPermission()` through the existing synchronous method. Add a new **optional suspend**
method to the common `FileSystem` interface with a no-op default, following the exact precedent
already established by the SAF write-behind block (`FileSystem.kt:73-145`:
`markDirty`/`readShadowOnly`/`shadowExists`/`flushPendingWrites`/`setOnFlushComplete`/
`invalidateStaleShadow`/`syncShadow` — seven methods, all default no-op/false/null, all overridden
only on the one platform that needs them). E.g. `suspend fun hostDirectoryAccessState(graphPath: String): HostAccessState = HostAccessState.NotApplicable`, overridden only in wasmJs. This keeps
JVM/Android/iOS at zero cost and zero new surface, matching the codebase's established
interface-growth convention rather than inventing a wasmJs-only side channel that `App.kt` would
have to downcast to reach.

## 2. Layering `FileSystemDirectoryHandle` retention + write-through around `PlatformFileSystem`

Recommended shape, extending the existing class rather than wrapping it (matching how
`dirtySet`/`recordDirty` were added in-place rather than via a decorator):

```kotlin
actual class PlatformFileSystem actual constructor() : FileSystem {
    // existing: homeDir, cache, bytesCache, blobUrlCache, scope, dirtySet, ...

    // NEW — retained per active graph. A JsAny? holding the FileSystemDirectoryHandle for the
    // currently-open graph's picked folder, or null if this graph was never connected to one
    // (old-style one-time-import graphs, or graphs opened before a resume-access grant).
    private var hostDirHandle: JsAny? = null
    private var hostGraphOpfsPath: String? = null   // the "/stelekit/<name>" this handle backs

    // NEW — write-through queue (§1.2), structurally separate from dirtySet.
    private val hostWritePending = mutableMapOf<String, DirtyEntry>()

    // NEW — synchronous-readable cache of host mtimes, fed by the async poller (§1.1),
    // consumed synchronously by getLastModifiedTime()/listFilesWithModTimes().
    private val hostModTimes = mutableMapOf<String, Long>()
}
```

- `pickDirectoryAsync()` gains one line: after `importUserDirToCache` (first-time import, see §5
  for why this call itself needs to change), store `hostDirHandle = dirHandle` and
  `hostGraphOpfsPath = opfsPath`, then persist the handle to IndexedDB keyed by the graph's
  `graphId` (§3) so a future session can resume without re-picking.
- A new suspend entry point, e.g. `suspend fun reconnectHostDirectory(graphId: String): HostAccessState`,
  called at startup (from `Main.kt`, alongside the existing `preload()` call at `Main.kt:128`) —
  looks up IndexedDB, and if a handle is found, calls `queryPermission()`; if granted, sets
  `hostDirHandle` and starts the write-through/poll loops immediately (silent resume, no click);
  if `prompt`, surfaces the one-click "resume access" banner (§1.3) which calls
  `requestPermission()` on click.
- `getLastModifiedTime`/`listFilesWithModTimes` read `hostModTimes` when `hostDirHandle != null`
  for that path's graph, else keep today's behavior (`null`/cache-derived) — this is what makes
  §1.1's reuse of `FileRegistry` work without changing `FileRegistry` itself, and it's also what
  makes graphs with **no** retained handle (unsupported browser, or user declined resume-access)
  degrade to exactly today's one-shot-import behavior with **zero code branching** in
  `FileRegistry`/`GraphFileWatcher` — the "No regression to the existing one-time-import behavior"
  success metric falls out of this for free rather than needing a separate code path.
- Graceful fallback (`supportsNativeDirectoryPicker`, already `FileSystem.kt:18` +
  `PlatformFileSystem.kt:323` checking `showDirectoryPickerSupported()`) needs no new logic: on
  Firefox/Safari, `hostDirHandle` simply never gets set, so every new code path above is
  naturally inert — the entire feature is additive and gated by "did we successfully retain a
  handle," never by a separate browser-sniffing branch.

## 3. IndexedDB handle persistence + Web Locks cross-tab coordination

### 3.1 IndexedDB — new to this codebase

Grep confirms **no existing IndexedDB usage anywhere in `wasmJsMain`** — this project introduces
the pattern from scratch (unlike Web Locks, §3.2, which already has a working example). Design
points specific to this codebase:

- Key by `graphId` (the same `sha256(path).take(16)` id `GraphManager.graphIdFromPath()`
  produces, `GraphManager.kt:251-252`), not by the OPFS path string — `graphId` is already the
  stable identity used for `DirtySetMarker.graphId` (`git/model/DirtySetMarker`, referenced at
  `PlatformFileSystem.kt:165`) and for `WasmSectionSyncService.graphId` (`Main.kt:100`). Reusing
  it avoids introducing a third identity scheme for "which graph does this belong to."
- `FileSystemDirectoryHandle` is natively structured-clone-serializable — this is the entire
  basis for File System Access API's persistence story; store the handle object directly as an
  IndexedDB value, no custom serialization needed. Store alongside it a small envelope
  (`{ graphId, dirName, storedAtMillis }`) for debugging/display, matching this codebase's
  existing habit of wrapping persisted blobs with metadata (`DirtySetMarker`'s `version`/
  `checkpointedAtMillis` fields, `git/model/DirtySetMarker`).
- On `main()` startup (`Main.kt`), after `preload()` but structured as its own step (see §2),
  look up the handle for the current `graphId`; this is a natural, low-risk addition to
  `Main.kt`'s existing sequential startup steps (config wiring → preload → driver → ...).
- Do not persist `hostWritePending`/`hostModTimes` to IndexedDB or OPFS — per §1.2's table, these
  are reconstructable (write-through queue is superseded by the reconciliation pass on
  reconnect, §5; mtimes are re-derived by the first poll tick). Persisting them adds a second
  "what if this drifts from the git dirty-set" surface for no correctness benefit.

### 3.2 Web Locks — extend the existing idiom, do not reuse the existing lock name

`git/GitWriteLock.kt:26-81` already implements the exact `navigator.locks.request()`
"acquire-now, release-later" idiom this feature needs, including the `acquired`/`release`/`done`
handle shape and the `withLock { }` suspend wrapper. **Two things to get right, not obvious from
a shallow read:**

1. **Do not share `GitWriteLock`'s lock name.** `GitWriteLock.lockNameFor(remoteUrl)`
   (`GitWriteLock.kt:64`, delegating to commonMain `GitWriteLockNaming`) scopes a lock to *git
   push*, a completely different critical section (the GitHub/GitLab ref-update PATCH) than what
   this feature needs (host-file write-through / host-directory poll). These are two independent
   resources — a tab pushing to git while another tab writes through to the local folder is fine
   and must not block. Derive a distinct lock name from `graphId` (not the git remote), e.g.
   `"stelekit-folder-sync:$graphId"`, so the two lock namespaces cannot collide by construction.
2. **Extract the low-level `jsRequestLockHandle`/`withLock` machinery into a shared,
   name-parameterized utility** (e.g. `platform/WebLock.kt`) rather than copy-pasting
   `GitWriteLock.kt`'s private `js()` functions into a second file. `GitWriteLock` itself becomes
   a thin caller of the shared utility (`GitWriteLock.withLock(name) { }` → delegates to
   `WebLock.withLock(name) { }`), giving both features one tested implementation instead of two
   near-identical ones drifting apart — this is a low-cost, low-risk refactor (pure extraction,
   no behavior change to the existing git push path) worth doing as part of this project rather
   than duplicating ~50 lines of `js()` interop.

**What the lock should scope**, mirroring `GitWriteLock`'s narrow-scoping philosophy (its KDoc
at `:47-55` is explicit that the lock covers only the write-critical-section, not `fetch`/`merge`)
rather than wrapping the whole feature in one lock:

- The host **write-through** of a single file (`createWritable()` → `write()` → `close()`) —
  narrow, per-write, so one tab's write-through of `pages/A.md` never blocks another tab's
  write-through of `pages/B.md`.
- The host **poll cycle**'s directory walk — coarser-grained (whole-directory), held only for the
  duration of one poll tick, to prevent two tabs from both independently discovering the same
  external change and both firing a `DiskConflict`/reload sequence for it. This is the
  "leader-ish, but only for the duration of one tick" pattern — not full leader election (no
  existing precedent for that in this codebase, and requirements.md's appetite/scope doesn't
  call for one); a per-tick lock is sufficient because a tab that loses the race simply skips
  that tick's poll and picks up any resulting `cache`/OPFS change on its own next tick via the
  existing OPFS-is-shared-per-origin property (OPFS storage, unlike `cache`/`hostDirHandle`, is
  already visible across tabs of the same origin — the losing tab's `cache` map will see the
  winner's `opfsWriteFile()` result once it re-reads, so no data is lost, only a redundant
  network-equivalent host read is avoided).
- **What the lock deliberately does not cover** (mirroring `GitWriteLock`'s documented gaps at
  `:47-55`): two tabs racing to `createWritable()` the *same* file within the same tick is not
  fully closed by a per-file lock alone if both tabs' in-memory `cache` disagree about the
  content to write — that's a same-content-source race, not a lock race, and is the reason §1.2
  treats OPFS (shared across tabs) rather than each tab's private `cache` as the real source of
  truth for what gets written through. Flag this as a known-gap for `plan.md` to scope explicitly,
  same as the sibling doc flagged `GitWriteLock`'s fetch/merge window gap rather than silently
  presenting the lock as a complete solution.

## 4. External-change-detection architecture — summary recommendation

Already detailed in §1.1/§2; summarized here as the single recommendation for `plan.md`:

**Do not build a new detection subsystem.** Feed the existing `FileRegistry`/`GraphFileWatcher`
poll loop real data by having `PlatformFileSystem` run its own async host-directory poll (own
`scope.launch` loop, same coroutine idiom as the existing OPFS-mirror/marker-write launches) that
populates `hostModTimes` + refreshes `cache` for changed files, on a cadence independent of (and
likely coarser than) `GraphFileWatcher`'s existing 5-second `pollIntervalMs`. Two poll loops,
loosely coupled through `PlatformFileSystem`'s synchronous read surface, is simpler and lower-risk
than trying to make `FileRegistry`/`GraphFileWatcher` suspend-aware of an async filesystem, which
would touch JVM/Android's already-working polling loop for no reason. This also directly satisfies
the Rabbit Hole "no native filesystem-watch API in browsers — detection needs polling or
re-check-on-focus": layer a `visibilitychange`/focus-triggered immediate poll tick on top of the
timer-based one, reusing the exact `jsVisibilityHiddenPromise()`-style interop already present in
`OpfsInterop.kt:156-180` (that helper fires on hidden; this feature wants the inverse — visible —
which is the same `document.addEventListener('visibilitychange', ...)` idiom with the opposite
`visibilityState` check, trivially adjacent code).

## 5. Migration/compliance failure mode — upgrade from one-time-import to live write-through

**Yes, there is a real data-loss/silent-divergence risk, and it is triggered by the most obvious
first action a user takes after upgrading, not an edge case.**

### 5.1 The scenario, traced through actual code

1. User on the **old** build: `pickDirectoryAsync()` → `importUserDirToCache()`
   (`PlatformFileSystem.kt:341-363`) copies host → OPFS once. `dirHandle` is never retained
   (confirmed §0). User edits pages in the browser afterward: `writeFile()` updates `cache` +
   OPFS + the git dirty-set (if git is configured) — **never the host directory**, because there
   is no code path that touches it. If the user also edits `.md` files directly on disk outside
   the browser during this period, those edits are invisible to the app (no detection, per the
   Baseline section of requirements.md) — OPFS and host have now independently diverged in both
   directions, with no record of the divergence anywhere.
2. User upgrades to the **new** build. IndexedDB has no stored `FileSystemDirectoryHandle` for
   this `graphId` — the old build never wrote one. Per §2's reconnect flow, `hostDirHandle` stays
   `null` and the app continues exactly as before (OPFS-only). **This part is safe by
   construction** — no silent behavior change happens just from upgrading and reopening.
3. The hazard is the **first "connect this graph to live sync" action** the new UI offers for an
   *already-populated* graph. If that action is implemented as "call `pickDirectoryAsync()`
   again" (the obvious, minimal-code choice, since that's the only existing entry point that
   acquires a handle), it re-runs `importUserDirToCache()` verbatim — which, per §0, is
   **unconditional and overwrite-only**: every file under the picked directory gets written into
   `cache`/OPFS with no comparison against what's already there. Any browser-only edit made since
   the original one-time import (step 1) — the entire reason this feature exists — would be
   **silently destroyed the instant the user turns the feature on**, which is the worst possible
   place for this failure mode: it defeats the feature at activation time, not during steady-state
   use where the new write-through/conflict machinery would otherwise catch it.

### 5.2 Required handling

`pickDirectoryAsync` (or a new `connectHostDirectory(existingOpfsPath)` variant used specifically
for the "attach an existing graph" case, as distinct from "pick a brand-new empty graph") must not
call `importUserDirToCache` unconditionally. It needs a **reconciliation pass**, run once at
connect-time, structurally identical to what `FileRegistry.detectChanges` already does for
steady-state polling (§1.1) — walk the host directory and, for every file, compare against
`cache`'s existing OPFS-side content:

| Host directory state | OPFS/`cache` state | Action |
|---|---|---|
| File exists, content byte-identical | File exists, same content | No-op — already in sync |
| File exists | File exists, **different** content | Treat as an `ExternalFileChange` through the *existing* `DiskConflict` pipeline (§1.1) — do not auto-pick either side. This is the one case `importUserDirToCache` today gets unconditionally wrong. |
| File exists | File does not exist in `cache` (never imported, or deleted in browser since) | New file — reload into OPFS/DB via the same "new file" path `FileRegistry.detectChanges` already uses (`FileRegistry.kt:112-118`) |
| File does not exist | File exists in `cache` (created/edited in browser since original import, host never got it) | Queue for host write-through (§1.2) — this is exactly the steady-state "local edit, push to host" case, just applied retroactively for the whole backlog at connect time |

This reconciliation should be literally reusable as "run one `FileRegistry.detectChanges`-shaped
pass over the whole tree once, then hand off to the steady-state pollers" rather than a bespoke
one-off algorithm — same reasoning as §1.1's "don't build a second detection subsystem."

### 5.3 Scope note for `plan.md`

This reconciliation pass is **not optional polish** — it is the difference between this feature
being safe to enable on an existing graph and a guaranteed data-loss bug the very first time a
returning user (which, for a feature this large-appetite, is the majority of the eventual user
base — nobody has this build on day one with an empty graph) tries to turn it on. Flag it as a
required task in `plan.md`, not a stretch goal, with its own test coverage (non-empty OPFS graph +
divergent host directory → connect → assert no content loss and correct 3-way classification per
the table above).

## 6. Event-Command-Policy table (EventStorming grammar)

| Domain Event | Policy (trigger) | Command | Actor / System |
|---|---|---|---|
| `DirectoryPicked` | User invokes folder picker (new graph) | `ShowDirectoryPicker` | User → `PlatformFileSystem.pickDirectoryAsync` |
| `DirectoryImported` | After `ShowDirectoryPicker` succeeds, target graph was previously empty | `ImportUserDirToCache` (unchanged, one-time) | `PlatformFileSystem` |
| `ExistingGraphConnectRequested` | User invokes "enable live sync" on an already-populated graph | `RunReconciliationPass` (§5.2) — NOT `ImportUserDirToCache` | `PlatformFileSystem` (new) |
| `ReconciliationClassified` | After `RunReconciliationPass` walks the tree | `PartitionByFileState` (identical/conflict/new/local-only) | `PlatformFileSystem` (new, reuses `FileRegistry`-shaped diff) |
| `ReconciledConflictFound` | Whenever partition finds host content differs from OPFS content for a path | `EmitExternalFileChange` (existing) | `GraphLoader.emitExternalFileChange` (`GraphLoader.kt:423`) |
| `HandleRetained` | After successful pick/reconnect | `PersistHandleToIndexedDb(graphId, handle)` | `PlatformFileSystem` (new) → IndexedDB |
| `SessionResumed` | App startup, `graphId` has a stored handle | `QueryPermission` | `PlatformFileSystem` (new) |
| `PermissionGrantedSilently` | Whenever `QueryPermission` returns `"granted"` | `AttachHostHandle` (no user action) | `PlatformFileSystem` (new) |
| `PermissionPromptNeeded` | Whenever `QueryPermission` returns `"prompt"`/`"denied"` | `ShowResumeAccessBanner` | UI (new, mirrors `hasStoragePermission()` banner pattern, `App.kt:248`) |
| `ResumeAccessClicked` | User clicks the banner | `RequestPermission` | User → `PlatformFileSystem` (new) |
| `FileEdited` | — (raw input, unchanged) | `SaveBlock`/`SavePage` | User → `BlockEditor`/`GraphWriter` |
| `FileWritten` (OPFS) | Whenever a file is written (existing hook point) | `RecordDirtyFile(path, op)` — git dirty-set, **unchanged, existing** | `PlatformFileSystem.writeFile` |
| `FileWritten` (OPFS) | Whenever a file is written AND a host handle is attached for this graph | `EnqueueHostWriteThrough(path)` — **new, independent of the above** | `PlatformFileSystem` (new) |
| `HostWriteThroughFlushed` | Debounced/coalesced flush fires (mirrors existing marker-write coalescing, `PlatformFileSystem.kt:147-161`) | `AcquireWriteLock(graphId, path)` then `WriteHostFile` | `PlatformFileSystem` (new) → Web Locks → FS Access API |
| `HostWriteSucceeded` | After `WriteHostFile` resolves | `DequeueHostWritePending(path)` | `PlatformFileSystem` (new) |
| `HostWriteFailed` (permission revoked mid-session, disk full, etc.) | Whenever `WriteHostFile` rejects | `SurfaceSyncDegradedIndicator` + keep path queued for retry | `PlatformFileSystem` (new) → UI |
| `HostPollTickDue` | Timer tick or `visibilitychange`→visible | `AcquirePollLock(graphId)` | `PlatformFileSystem` (new) |
| `PollLockLost` (another tab holds it) | Whenever `AcquirePollLock` doesn't resolve within the tick | `SkipThisTick` | `PlatformFileSystem` (new) — safe no-op, OPFS is cross-tab-shared |
| `HostDirectoryWalked` | After `AcquirePollLock` succeeds | `CompareHostMtimesAgainstCache` | `PlatformFileSystem` (new) |
| `HostModTimesRefreshed` | After `CompareHostMtimesAgainstCache` | `UpdateHostModTimeCache` (sync-readable) | `PlatformFileSystem` (new) |
| `HostFileChangedExternally` | Whenever a walked file's content hash differs from `cache` | `WriteToCacheAndOpfs` (mirrors `readFileSuspend`'s git-fetch cache-fill, `PlatformFileSystem.kt:248-250`) | `PlatformFileSystem` (new) |
| `ExternalChangeDetected` | Whenever `FileRegistry.detectChanges`'s **existing, unmodified** 5s poll next runs and sees the mtime bump from `HostModTimesRefreshed` | `EmitExternalFileChange` (existing, unmodified) | `FileRegistry`/`GraphFileWatcher` (existing, `GraphFileWatcher.kt:193-266`) |
| `ExternalChangeSurfacedToUser` | Whenever `externalFileChanges` emits and the page is actively protected (4-tier check, existing) | `ShowDiskConflictDialog` (existing, unmodified) | `StelekitViewModel.observeExternalFileChanges` (existing) → `DiskConflictDialog`/`DiskConflictFullScreen` |
| `ExternalChangeAutoReloaded` | Whenever `externalFileChanges` emits and the page is not protected (existing) | `ReloadPageFromDisk` (existing, unmodified) | `GraphLoader` (existing) |
| `ConflictResolvedByUser` | User picks Keep Local / Keep Disk / Merge in `DiskConflictDialog` (existing UI, unmodified) | `ApplyResolution` (existing) | User → `StelekitViewModel` (existing) |
| `TabHidden` | `visibilitychange` → hidden | `FlushPendingHostWrites` (belt-and-suspenders, mirrors existing marker-flush-on-hide at `PlatformFileSystem.kt:44-58`) | `PlatformFileSystem` (new, same idiom) |
| `BrowserUnsupported` | `showDirectoryPickerSupported() == false` (existing check, `PlatformFileSystem.kt:323`) | `FallBackToOneTimeImportOnly` — no new code path needed, everything above stays inert | `PlatformFileSystem` (existing) |

## 7. Summary of concrete recommendations for `plan.md`

1. **Host write-through is a third, structurally independent side-effect** added to
   `writeFile`/`writeFileBytes`/`deleteFile`, alongside the existing `cache` mutation and
   `recordDirty` git-dirty-set call — never routed through or triggered by the existing
   `dirtySet`/`.stele-dirty-set.json` machinery (§1.2). Reuse the `DirtyEntry`/`DirtyOp` types for
   consistency but keep a **separate** map instance and separate persistence-lifetime decision
   (does not need OPFS/IndexedDB persistence across reload — see the comparison table in §1.2).
2. **External-change detection reuses `FileRegistry`/`GraphFileWatcher`/`DiskConflict` unchanged.**
   The only new code is an async host-directory poller inside `PlatformFileSystem` that keeps
   `listFilesWithModTimes`/`getLastModifiedTime`/`cache` fresh enough for the existing synchronous
   poll loop to see real changes (§1.1, §4). Do not touch `FileRegistry.kt`/`GraphFileWatcher.kt`.
3. **The requirements.md Constraints section's reference to `ConflictResolutionScreen`/
   `SyncState.ConflictPending` is very likely a copy-paste carryover from the sibling
   web-git-writeback requirements doc, not the correct mechanism for this feature.** Per the
   sibling doc's own §0.2 correction, that machinery is a git-merge line-hunk 3-way-diff UI with
   no relationship to local-disk-vs-editor-buffer conflicts. This feature's actual target — a
   local folder with no git remote required — maps to `DomainError.ConflictError.DiskConflict`
   (`error/DomainError.kt:33`) / `GraphLoader.externalFileChanges` /
   `StelekitViewModel.observeExternalFileChanges` / `DiskConflictDialog`, exactly as the
   requirements.md Success Metrics section (citing `GraphLoader.kt:433`) independently and
   correctly states. Flag this discrepancy explicitly in `plan.md` rather than silently building
   toward whichever one gets implemented first — the two mechanisms have incompatible data
   models (line-hunks vs. whole-file), and only one is real work here.
4. **`FileSystemDirectoryHandle` retention**: new instance fields on `PlatformFileSystem`
   (`hostDirHandle`, `hostGraphOpfsPath`), populated at pick time and restored from IndexedDB
   (new to this codebase) keyed by `graphId` (§3.1) — not a new identity scheme, reuse
   `GraphManager.graphIdFromPath()`'s existing id.
5. **Cross-tab coordination**: extract `GitWriteLock`'s Web Locks `js()` interop into a shared,
   name-parameterized `WebLock` utility; give this feature its own lock namespace
   (`"stelekit-folder-sync:$graphId"`, distinct from git's remote-URL-derived name) scoped
   narrowly to (a) a single host file write and (b) one poll tick's directory walk — mirroring
   `GitWriteLock`'s narrow-scoping philosophy, not a whole-feature mutex (§3.2).
6. **Migration hazard is real and must be fixed before this ships, not after**: the "connect an
   existing populated graph to live sync" action must run a `FileRegistry`-shaped reconciliation
   pass (§5.2 table), never the existing unconditional `importUserDirToCache`. This is the single
   highest-severity finding in this document — the naive implementation (reuse
   `pickDirectoryAsync` as-is for reconnect) silently destroys browser-only edits made under the
   old one-time-import behavior, at the exact moment a returning user opts into the new feature.
   Needs explicit test coverage in `validation.md`.
7. **New optional `FileSystem` interface methods should follow the existing SAF-write-behind
   convention** (`FileSystem.kt:73-145`): suspend, default no-op, overridden only in wasmJs —
   for permission-state queries (§1.3) and any other new async host-handle surface that `App.kt`
   or other commonMain UI needs to call generically rather than downcasting to the wasmJs actual.
