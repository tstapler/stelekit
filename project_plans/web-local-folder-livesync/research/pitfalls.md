# Research: Known Pitfalls & Risks — web-local-folder-livesync

**Date**: 2026-07-17
**Scope**: Risks and failure modes for retaining a `FileSystemDirectoryHandle`, write-through to a host
directory, and external-change detection on the web/WASM build. Covers File System Access API (FSA)
gotchas, dual-write (OPFS + host) data-loss modes, silent-divergence risks, and polling-detection
failure modes. Cross-referenced against the existing codebase where the same class of risk is already
solved (or explicitly *not* solved) on JVM/Android.

---

## 0. Codebase baseline (what already exists to build on / must not regress)

- `kmp/src/wasmJsMain/.../PlatformFileSystem.kt`: `pickDirectoryAsync()` (line 324) calls
  `showDirectoryPicker()` **once**, then `importUserDirToCache()` (line 341) copies every file into
  the OPFS-backed `cache`/`bytesCache` maps and schedules `opfsWriteFile`/`opfsWriteFileBytes`. The
  `dirHandle` is a local variable — **never retained**. This is the gap the feature closes.
- `actual override fun getLastModifiedTime(path: String): Long? = null` (line 365) — the wasmJs
  target currently reports **no mod-time information at all**. This matters a lot: the entire existing
  change-detection engine (`FileRegistry.detectChanges`, `db/FileRegistry.kt`) is built around
  mod-time deltas (`modTime > lastKnown`, backward-mod-time handling for sync tools, etc.) plus a
  content-hash guard as a fallback. A web implementation cannot reuse this path as-is; it will need to
  either (a) synthesize a monotonic "seen" counter, or (b) use `File.lastModified` off
  `FileSystemFileHandle.getFile()`, which is only informative to millisecond resolution and, per FSA
  spec ambiguity, not guaranteed authoritative across all Chromium storage backends (e.g. some cloud
  drives mounted as native FS providers report coarse-grained timestamps).
- `GraphFileWatcher` (`db/GraphFileWatcher.kt`) already implements the **exact class of problem** this
  feature must solve for the web: a 5s polling loop + a platform "fast path" hook
  (`FileSystem.startExternalChangeDetection`), own-write suppression via a `Long.MAX_VALUE` sentinel
  (`preMarkPendingWrite` / `markWrittenByUs`), and a content-hash guard to distinguish "real external
  change" from "we just wrote this." **This is the mechanism to extend, not reinvent** — a
  browser-native poller that talks to `FileSystemDirectoryHandle.values()`/`getFile()` should plug into
  `FileRegistry` the same way Android's SAF backend does, respecting the same sentinel/hash-guard
  discipline. Skipping this and writing a bespoke web-only watcher risks re-introducing every race this
  file's comments describe having already fixed once (see lines 146-169: backward-mod-time handling for
  sync tools — the exact same class of bug a host-filesystem live-sync will hit).
- `emitExternalFileChange` / `ExternalFileChange` / `externalFileChanges: SharedFlow<...>`
  (`GraphLoader.kt:423-433`) is the existing conflict-surfacing pipeline the requirements say to reuse —
  confirmed present and generic enough (takes `filePath` + `content`, exposes a `suppress()` callback)
  to be driven from a web-native poller.
- Dirty-set tracking (`PlatformFileSystem.kt:28-174`, `dirtySet`, `.stele-dirty-set.json`) is the
  `web-git-writeback` mechanism the requirements explicitly say not to regress. It currently has a
  single writer model (nothing else calls `recordDirty`). Introducing host-directory writes means a
  **second consumer path** now mutates files that also need dirty-tracking (or explicitly must not be
  double-tracked) — this is called out as a rabbit hole in the requirements and is confirmed live in
  code, not hypothetical.

---

## 1. File System Access API gotchas

### 1.1 Permission revocation mid-session
- Permission grants for `readwrite` are **session-scoped by default**: a site can keep using a handle
  without re-prompting as long as at least one tab for the origin stays open, but the grant is dropped
  the moment all tabs close, and Chrome's "Persistent Permissions" work (behind heuristics, opt-in) is
  the only thing that survives a full close — it is not universal and can itself silently expire.
- Chromium can also **auto-revoke** an active grant when a tab is backgrounded/inactive for an extended
  period (see `developer.chrome.com/blog/persistent-permissions-for-the-file-system-access-api`), and a
  user can revoke access at any time via the page-info UI without any app-visible event firing — the
  first sign is the *next* `getFile()`/`createWritable()` call throwing.
- **Design implication**: every write and every poll tick must be wrapped to catch permission failure,
  not just the initial `showDirectoryPicker()` call. `queryPermission()` before each write-through batch
  (not just once at startup) is required, and a re-grant UI affordance (one click, per the requirement)
  must be reachable from mid-session, not just from a "reopen app" cold-start path.
- A handle persisted in IndexedDB across reloads (needed for "reopen requires ≤1 click") can be
  structurally valid but **permission-less** — `queryPermission()` on a rehydrated handle very commonly
  returns `'prompt'`, and calling `requestPermission()` **requires transient user activation** (a real
  click), so this cannot be done automatically on page load — confirms the requirement's "one-click
  re-grant per session is acceptable" is not just acceptable but the *only* legally-permitted UX for
  this API.

### 1.2 Handle staleness after external rename/move
- Chromium's FSA implementation is **path-based**: a `FileSystemHandle` internally maps to a path, so
  a `getFile()`/`createWritable()` call on a handle whose backing path was renamed/moved/deleted
  externally rejects with `NotFoundError`. Firefox's proposed implementation is reference-based and
  would instead silently re-resolve to the moved file — i.e. **behavior here is not just
  browser-different but currently underspecified across implementations** (see
  `github.com/whatwg/fs/issues/59`). Since the requirements scope Firefox/Safari out, this narrows to
  "Chromium NotFoundError is the only behavior to design against" — but it must be treated as a routine,
  expected error path (external tools rename/move `.md` files constantly — e.g. page rename in
  Logseq-compatible tools), not an exceptional crash.
- `FileSystemHandle.move()`/`.rename()` (Chrome 138+, from `chromestatus.com/feature/5640802622504960`)
  is new enough that it should not be relied on as the *only* rename mechanism; a `NotFoundError` +
  re-pick/re-resolve fallback path is required regardless.
- Chromium **locks all ancestor directories** while any file under them has an open writable stream —
  an app-held writable on `page-a.md` will make an *external* rename of a sibling directory fail (or
  vice versa: an external app holding a lock blocks the web app's write). This is a real contention
  surface for "concurrent write races" called out in the Rabbit Holes section.

### 1.3 OPFS ↔ host-directory quota/storage-pressure interaction
- OPFS usage counts against the **same per-origin storage quota** as IndexedDB/Cache API (commonly
  ~60% of free disk on desktop Chrome, much smaller on mobile). A dual-write design that keeps a full
  OPFS mirror of a host directory **doubles on-disk footprint** for every graph — for an 8,000+ page
  graph (the scale this codebase already stress-tests, see `LargeGraphWarmStartCrashTest`) this is a
  real capacity concern, not theoretical.
- Without `navigator.storage.persist()` (which itself requires a user gesture / heuristic engagement
  score and can be silently denied), the browser can **evict the entire OPFS mirror under storage
  pressure via LRU** — the host directory files are untouched, but the app's local cache (including any
  paranoid-mode key material or unflushed dirty-set) can vanish between sessions with no error surfaced
  to the app, only an empty read on next `getFile()`.
- **Design implication**: the OPFS mirror should be explicitly treated as a *disposable cache*
  reconstructable from the host directory, never as the durability source of truth once a host directory
  is attached — inverts the current one-time-import model's implicit assumption that OPFS is safe to
  treat as canonical.

### 1.4 iframe / cross-origin restrictions
- `requestPermission()` throws when called from a context that is not same-origin with the top-level
  browsing context (e.g. a cross-origin iframe embed). Not a live risk for SteleKit's own deployment
  today, but worth a guard if the desktop-shell-in-iframe or any embed/preview mode is ever added later.
- `requestPermission()`/`showDirectoryPicker()` both require **transient user activation** and are
  Window-context-only (unusable from a Worker) — rules out doing the re-grant handshake from any
  background/worker thread; it must be driven from the main UI thread on a real user gesture.

### 1.5 Browser-specific / implementation quirks
- Firefox/Safari FSA support is explicitly out of scope per requirements, but the fallback path must be
  airtight: `PlatformFileSystem.supportsNativeDirectoryPicker` (backed by `showDirectoryPickerSupported()`)
  is the existing feature-detection point — confirm every new write-through / polling code path is
  gated behind this, so unsupported browsers get the current one-shot-import behavior unchanged (this is
  the "no regression to fallback behavior" success metric).
- A historical Chromium symlink-following vulnerability (CVE-2022-3656 / crbug 1152327) shows the FSA
  implementation has had real security bugs around resolving filesystem entries outside the granted
  directory — not directly actionable for this feature, but reinforces treating all paths read back
  from the API as untrusted rather than assuming they stay inside the picked root.
- `createWritable()` gained an optional `mode` parameter in Chrome 121 (`'exclusive'` vs the default
  `'siloed'`, each writer gets its own swap file) — the default `'siloed'` mode means **two concurrent
  writers to the same file silently last-write-wins with no error**, which is exactly the kind of
  same-file race a live-sync feature will trigger under normal use (app write + external editor save
  landing close together). This should be evaluated explicitly rather than left at the default.

---

## 2. Data-loss failure modes in dual-write (OPFS + host file)

1. **Crash/tab-close between OPFS write and host write.** `createWritable()` writes to a private swap
   file and only replaces the real file on `close()` — if the tab is closed, or the write throws (quota,
   permission revoked, `NotFoundError` from external rename) between the OPFS write succeeding and the
   host `close()` completing, the two copies diverge with **no persisted record that they diverged**.
   The existing `preMarkPendingWrite`/`clearPendingWrite` saga-compensation pattern in `FileRegistry.kt`
   (lines 240-259) is the right shape to extend to a two-phase OPFS+host write, but today it guards a
   single write target — must become genuinely two-phase (mark pending → write OPFS → write host →
   confirm both → clear sentinel; on any failure, roll the *dirty-set* forward so the failed side is
   retried on next flush rather than silently dropped).
2. **Debounced write coalescing loses interleaved external edits.** Requirements ask for ~500ms
   write-through latency, implying debounce (matches `BlockEditor`'s existing 500ms debounce pattern per
   CLAUDE.md). If an external process edits the host file *during* that debounce window, a naive
   "flush local buffer to host, unconditionally overwrite" will clobber the external edit with no
   conflict surfaced — this is silent data loss, not just a UX gap. The pre-write hash check already
   used by `GraphWriter`/`emitExternalFileChange` (conflict check before write) must run **synchronously
   immediately before every debounced host flush**, not just at document-open time.
3. **Partial multi-file operations (e.g. page rename = write new file + delete old).** FSA has no
   filesystem-level transaction. A crash/permission-revocation between the "write new .md" and "delete
   old .md" steps of a rename leaves both files on the host disk — silently duplicating content rather
   than losing it, but equally corrupting: the app's dirty-set and OPFS cache may believe the rename
   completed while the host directory has two files. Needs an idempotent, resumable rename protocol
   (write-then-verify-then-delete, with the old file kept as a recovery point until the new file's
   presence and hash are confirmed).
4. **OPFS eviction under storage pressure wiping the pending-write buffer.** If debounced/queued writes
   live only in the in-memory `cache`/OPFS mirror before being flushed to host, and the browser evicts
   the OPFS store under storage pressure (§1.3) before the flush completes, that queued edit is lost
   with no error — the user sees their last saved content, not their most recent keystrokes. Any
   write-behind queue for the host directory needs its own durability story (e.g. persist the pending
   op to IndexedDB, not just OPFS) independent of the OPFS eviction policy.
5. **`.stele-dirty-set.json` becoming an unreliable source of truth once two write paths exist.** Once
   host-directory writes exist alongside `web-git-writeback`'s OPFS-only dirty-tracking, a write that
   succeeds to the host file but fails to update the dirty-set marker (or vice versa) creates silent
   divergence between "what git-writeback thinks needs pushing" and "what's actually different from the
   host directory." The existing marker write is best-effort and logs-and-continues on encode failure
   (`PlatformFileSystem.kt` ~line 174) — acceptable when there's one writer, risky once there are two
   independent consumers racing to update the same marker file.

## 3. Silent-divergence risks (one side succeeds, the other fails)

- **OPFS write succeeds, host write fails** (permission revoked mid-flush, quota exceeded on host disk,
  external rename → `NotFoundError`): the app's own in-memory state and DB now reflect content the host
  directory does not have. Unless every host-write failure is captured and surfaced (not just logged —
  `println`-style swallowing appears repeatedly in the existing wasmJs `PlatformFileSystem.kt`, e.g.
  `catch (e: Throwable) { println(...) ; null }` at line 335-338), the user has no way to know their
  edit never reached disk. This needs to become a **first-class error state** feeding the existing
  `writeErrors: SharedFlow<WriteError>` (`GraphLoader.kt:439-440`) already used for DB write failures —
  extend that channel rather than inventing a second one.
- **Host write succeeds, OPFS write fails**: less likely (OPFS writes are usually cheaper/more reliable
  than host-disk IPC) but possible under OPFS-side quota pressure. If OPFS is the read-path source of
  truth for the UI (current design), the app would keep rendering **stale content indefinitely** — the
  file on the host directory is correct, but the app never notices because nothing prompted a re-read.
  Combined with polling gaps (§4), this is a compounding risk, not an independent one.
- **Retry-without-idempotency divergence**: if the write-through retry logic re-sends a stale in-memory
  buffer after a failure, and meanwhile the external-change poller has already picked up and merged a
  newer external edit into OPFS, the retry can overwrite the merged state with older content — a
  "resurrected" stale write. Any retry path must re-check freshness (content hash) immediately before
  retrying, not just before the first attempt.
- **Cross-tab divergence**: with no cross-tab coordination, two tabs each holding their own in-memory
  dirty buffer for the same graph will both independently write-through to the host directory,
  interleaved, with neither aware of the other's edits — silent last-write-wins with no conflict UI at
  all (worse than the single-tab external-edit case, because neither write ever goes through the
  external-change/`DiskConflict` path — both look like "our own write" to each tab). `BroadcastChannel`
  is the standard mechanism for coordinating this (e.g. a single elected "leader" tab owns the write-
  through and watcher poll; follower tabs relay through it), but there's no existing precedent for this
  pattern in the codebase — this is new infrastructure, not an extension of existing patterns, and
  should be budgeted as such (matches the Rabbit Holes note flagging "concurrent write races" and
  "cross-tab coordination" as distinct scope items).

## 4. Polling-based external-change detection: known failure modes

- **No native filesystem-watch API works against a host-picked FSA directory.** The only forward-looking
  alternative is `FileSystemObserver` (Chrome 129+ origin-trial-era API, Chromium/Edge/Opera only, *not*
  standardized, explicitly "not recommended for production" per MDN as of the current search) — matches
  the requirements' own framing ("no native filesystem-watch API... needs polling or re-check-on-focus").
  It should be tracked as a possible future fast-path (mirroring how `GraphFileWatcher` already has an
  Android ContentObserver fast path alongside its polling fallback) but **not depended on** for this
  feature's initial delivery.
- **Missed rapid successive changes.** A poll interval (existing default 5s in `GraphFileWatcher`, or
  whatever web-specific interval is chosen) can miss multiple edits to the same file between ticks — the
  poller only ever sees the *last* state at tick time, so intermediate versions are invisible. This is
  usually fine (mod-time/hash diff between "last known" and "current" already coalesces multiple
  external edits into one detected change, per `FileRegistry.detectChanges`), but it means the
  granularity of conflict detection is "changed since last successful sync," not per-edit — the same
  external tool doing rapid saves (autosave loops, some sync clients) will register as one change, which
  is correct behavior but should be a documented assumption, not an implicit one.
- **False positives from the app's own writes being detected as "external."** This is the exact problem
  `FileRegistry`'s `preMarkPendingWrite`/`markWrittenByUs` sentinel + content-hash guard already solves
  for JVM/Android — but only because `getLastModifiedTime` returns a real value there. On web
  (`getLastModifiedTime` currently hardcoded `null`, §0), a naive polling implementation has **no
  mod-time to compare against** and must rely entirely on the content-hash guard, which means reading
  full file content on every poll tick for every file to detect changes — expensive at the 8,000+ page
  scale this codebase is designed around, and a direct violation of the "Graph-scale reads must be
  paginated, projected, or chunked" principle in CLAUDE.md if implemented naively (full-directory
  re-read per poll tick). A cheaper signal is needed: `File.lastModified` from `getFile()` (real
  timestamp, avoids the `null` gap) or `File.size` as a fast pre-filter before falling back to hash
  comparison only on suspected changes — mirroring the two-tier check already in `detectChanges`
  (mod-time first, hash only on mod-time delta).
- **Poll-tick races with in-flight own-writes.** The existing sentinel (`Long.MAX_VALUE`) approach
  assumes a single mod-time axis; if the web implementation instead tracks "last known `File.size` +
  hash," the equivalent sentinel needs to be a boolean/generation-counter guard ("write in flight, skip
  this poll tick for this path") rather than a magic numeric sentinel, since size/hash don't have a
  natural "impossible" value the way `Long.MAX_VALUE` does for a timestamp.
- **Re-check-on-focus is necessary but not sufficient.** Backgrounded tabs commonly get throttled timers
  (background tab timer throttling is standard Chromium behavior), so a naive `setInterval`/coroutine
  `delay()`-based poller will silently slow down or stall while the tab is backgrounded — exactly when a
  user is most likely to have switched to an external editor to make the change the app is supposed to
  detect. A `visibilitychange`/focus-triggered **immediate** re-check on tab foreground (independent of
  the regular poll cadence) is required, not optional — this is the "re-check-on-focus" the requirements
  already anticipate, but it must be understood as compensating for throttling, not just as a UX nicety.

---

## Summary of design-against list (condensed)

1. Treat every FSA call (write, read, permission query) as fallible **per-call**, not just at directory-pick time — permission can vanish mid-session with no app-visible event.
2. `requestPermission()` re-grant must be reachable from a live, in-session user gesture (button), never attempted automatically — the API forbids it.
3. Treat `NotFoundError` from a stale/moved handle as a routine, recoverable error (re-resolve or re-prompt), not a crash.
4. Never let the OPFS mirror be the sole durability source once a host directory is attached — it is evictable under storage pressure with no app-visible warning.
5. Make every host write two-phase and resumable (mirror the existing `preMarkPendingWrite`/`clearPendingWrite` saga pattern) so a mid-write crash/permission-loss is detectable and retryable, not silently divergent.
6. Re-check freshness (hash) immediately before every debounced flush and every retry — not just at file-open time — to avoid clobbering an external edit that landed during the debounce window.
7. Route every host-write failure into a first-class, user-visible error channel (extend `writeErrors`), not `println`/swallow.
8. Budget cross-tab coordination (`BroadcastChannel` + leader election) as new infrastructure, not an extension — uncoordinated tabs produce silent last-write-wins with no conflict UI at all.
9. Extend `FileRegistry`/`GraphFileWatcher`'s existing polling+sentinel+hash-guard pattern for the web watcher rather than building a parallel mechanism — but budget for the fact that `getLastModifiedTime` is currently `null` on web, so the cheap mod-time pre-filter this pattern relies on must be rebuilt using `File.lastModified`/`File.size` first, with full-content hashing only as a fallback (never a full-directory re-read+hash every tick — violates the project's graph-scale read discipline).
10. Add an immediate, focus/visibility-triggered re-check independent of the regular poll interval — background-tab timer throttling means the steady-state poll cadence cannot be trusted to catch changes made while the user was in an external editor.
