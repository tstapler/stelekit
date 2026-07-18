# Build vs. Buy: web-local-folder-livesync

**Date**: 2026-07-17
**Scope**: `PlatformFileSystem.kt` (wasmJsMain), `OpfsInterop.kt`, `GraphFileWatcher.kt` (commonMain), `GitWriteLock.kt` (wasmJsMain)

## Codebase orientation (baseline facts used below)

- `wasmJsMain/platform/PlatformFileSystem.kt:324` `pickDirectoryAsync()` calls `showDirectoryPicker()` once, then `importUserDirToCache()` (line 341) copies every file into an in-memory `cache`/`bytesCache` map + OPFS mirror. The `FileSystemDirectoryHandle` is never retained past that function call — it's a local var, not a field. This is the entire gap the feature closes.
- `OpfsInterop.kt` (180 lines) is 100% hand-written `js()` / `external` top-level functions — no interop wrapper library, no `@JsModule` bindings beyond raw strings. This is the established idiom for *every* browser API this codebase touches (OPFS, Web Locks, visibility events).
- `git/GitWriteLock.kt` (wasmJsMain) already implements `navigator.locks.request()` cross-tab mutual exclusion via the same hand-written `js()` pattern, using an "acquire-now, release-later" Promise idiom, scoped narrowly to the git-push critical section. This is the direct precedent for this feature's cross-tab coordination requirement.
- `db/GraphFileWatcher.kt` (commonMain, 49 constructor params documented) is **already platform-agnostic**: it runs a 5-second poll loop via `FileRegistry` mod-time comparison, plus an optional platform-native fast path via `FileSystem.startExternalChangeDetection`. It emits `ExternalFileChange` on a `SharedFlow` that `GraphLoader.externalFileChanges` (`GraphLoader.kt:433`) already exposes to the UI/conflict machinery. `PlatformFileSystem.kt:365` on wasmJs currently returns `getLastModifiedTime(path) = null` — the poll fallback is a no-op on web today purely because that one hook is unimplemented, not because the watcher architecture needs rework.
- `build.gradle.kts:212` already imports one npm package for wasmJs (`@sqlite.org/sqlite-wasm`) via a custom worker script — so npm packages are not unprecedented in this build, but that one is a WASM binary + glue script, not a Kotlin-interop wrapper library. No interop wrapper library (idb-keyval, browser-fs-access, kotlin-wrappers browser bindings, etc.) is used anywhere in `kmp/src`.
- No IndexedDB usage exists anywhere in `wasmJsMain` today — this feature is the first consumer.

---

## 1. Existing OSS library / framework (JS npm packages via interop)

**Candidates evaluated:**

| Package | Purpose | Maturity/maintenance | License |
|---|---|---|---|
| `browser-fs-access` (GoogleChromeLabs) | Unified picker + legacy fallback wrapper over FSA API | 364k weekly downloads, widely used (Excalidraw uses it), but Snyk flags maintenance as **inactive** — v0.38.0 is ~1 year stale | Apache-2.0 |
| `idb-keyval` (jakearchibald) | Tiny promise-based IndexedDB key-value store | Actively maintained (v6.3.0, updated days before this research), 295 bytes, well regarded (Google/web.dev's own recommended pattern) | Apache-2.0 |
| `idb` (jakearchibald) | Fuller promise wrapper over IndexedDB | Actively maintained, same author | ISC |
| `JuulLabs/indexeddb` | **Kotlin Multiplatform** coroutines wrapper over IndexedDB, Kotlin/JS + Kotlin/Wasm (WasmGC support since v0.12.0, replaced `dynamic` with `JsAny`) | Actively maintained, real production users (JUUL Labs Bluetooth stack), documented Slack community usage in 2025 | Apache-2.0 |
| `use-strict/file-system-access` | Ponyfill implementing the FSA API surface over pluggable storage adapters (IndexedDB, Cache API, memory) | Smaller/niche project, useful mainly as a *fallback shim* not as a wrapper for the real API | MIT |

**Pros:**
- `idb-keyval`/`idb` solve exactly the one genuinely fiddly piece — persisting a `FileSystemDirectoryHandle` through IndexedDB's callback-based, non-Promise-native API — with a battle-tested 5-minute integration in JS.
- `JuulLabs/indexeddb` is the one candidate that's actually *Kotlin*, not JS-to-wrap: it removes the need to hand-write `js()` IndexedDB glue at all, and it specifically targets Kotlin/Wasm (matching this codebase's target exactly). It would be a genuine architectural first — the project's first "interop wrapper library" — but for IndexedDB specifically that tradeoff looks favorable: IndexedDB's raw API (nested callback-based transactions, `onupgradeneeded`, cursor iteration) is meaningfully worse to hand-roll in `js()` string blocks than OPFS or Web Locks were, and there's no existing internal pattern to reuse (unlike file-watching or locking).
- `browser-fs-access` mainly wraps *picking* files, which this codebase already does directly and successfully (`showDirectoryPicker()` in `OpfsInterop.kt:6-7`) — it adds least value here since the picker call itself is not the hard part.

**Cons:**
- All JS packages (`browser-fs-access`, `idb-keyval`, `idb`, the ponyfill) still require the same amount of hand-written `@JsModule`/`external` boundary code this codebase already writes for OPFS/Web Locks — Kotlin/Wasm has no ambient `dynamic` typing like Kotlin/JS, so "importing a JS library" doesn't remove interop work, it just moves what's being wrapped. The net line-count savings vs. writing five more `js()` functions (open DB, put, get by key) directly are marginal.
- `browser-fs-access`'s maintenance signal (Snyk: inactive) is a real risk for a "Large / 3-6 week" feature meant to be trustworthy for months; and it targets *TypeScript/JS consumers*, so its type surface doesn't map cleanly onto `external`/`JsAny` declarations anyway — you'd still be writing your own signatures against its runtime behavior, sacrificing most of the "already typed" benefit.
- `JuulLabs/indexeddb` is a genuine new build dependency category for this codebase (first Kotlin-side interop wrapper) — introduces a new upgrade-tracking surface, and the project's own architecture note ("no interop wrapper libraries currently used anywhere in this platform layer") suggests this was a deliberate choice worth revisiting explicitly rather than defaulting into.

**Verdict:**
- `browser-fs-access` — **Not recommended**. Inactive maintenance, and it wraps the one part (picker) this codebase already does trivially by hand.
- `idb-keyval`/`idb` (JS, hand-wrapped via `js()`) — **Viable**. Only worth it if the team wants to stay JS-only rather than add `JuulLabs/indexeddb`; either way the amount of custom `js()` needed for a 2-3 key/value IndexedDB store is small enough that writing it directly (following the existing `OpfsInterop.kt` idiom) is also reasonable.
- `JuulLabs/indexeddb` — **Viable, worth a deliberate look**. Best type-safety/maintenance combination of anything surveyed, but is a first-of-its-kind dependency for this codebase; the ADR for this feature should explicitly decide "hand-written `js()` IndexedDB calls, matching existing OpfsInterop idiom" vs. "adopt JuulLabs/indexeddb as the project's first Kotlin/Wasm interop wrapper" rather than let it default silently.
- `use-strict/file-system-access` ponyfill — **Not recommended**. Solves graceful-fallback-on-unsupported-browsers (in scope), but the existing `supportsNativeDirectoryPicker` / `showDirectoryPickerSupported()` capability check already covers that requirement more simply; a full ponyfill (with its own IndexedDB-backed virtual filesystem) is disproportionate.

---

## 2. SaaS / managed API

Not applicable in the normal sense — this is local-device filesystem access, not a hosted service, and there's no server-side component in scope (explicitly out of scope per requirements). Two adjacent angles worth naming and dismissing:

- **A local companion agent/daemon** (a small native process the browser talks to over `localhost`, e.g. via WebSocket or native messaging) is the pattern some competing tools use to bypass FSA API limitations entirely (arbitrary path access, true OS-level file watching via inotify/FSEvents instead of polling). This is explicitly against the grain of the requirements ("no new server-side component") and against the appetite (adds an installer/packaging burden orthogonal to a 3-6 week appetite). **Not recommended** for this cycle — worth flagging as a *future* option if FSA API polling-based change detection proves too laggy in practice, since it's the only way to get real push-based OS file-change events in a browser context.
- **Managed sync services** (Dropbox/Google Drive API, a hosted CRDT sync backend like Automerge's sync server, etc.) solve a different problem — remote multi-device sync, not local-folder live write-through — and are out of scope; `web-git-writeback` already covers the "sync to a remote" story via git. **Not applicable.**

---

## 3. LLM-generated implementation vs. reusing this codebase's own battle-tested patterns

This is the highest-leverage question for this feature, because the codebase already contains working solutions to two of the three "trickiest pieces" named in scope:

### a) Conflict/merge detection → reuse `GraphFileWatcher` + `GraphLoader.externalFileChanges`
`GraphFileWatcher` is written as a **platform-agnostic dispatcher**: it doesn't know or care whether "check for external changes" means a JVM `WatchService`, an Android `ContentObserver`, or (for this feature) polling `FileSystemDirectoryHandle` entries / comparing `File.lastModified`. The entire `DiskConflict`/`ConflictResolutionScreen` UX, the `onDirtyFile`/`activePageFilePaths` skip-if-actively-editing logic, and the single-shot `suppress()` mechanism for git-merge-triggered reloads already exist and are exercised by `GraphFileWatcherTest`. **The only new work required is a wasmJs implementation of the `readFile`/mod-time hooks the watcher already calls** — i.e. wiring `FileSystemFileHandle.getFile().lastModified` into `getLastModifiedTime()` (currently a hardcoded `null` at `PlatformFileSystem.kt:365`) and/or implementing `FileSystem.startExternalChangeDetection` for the native fast path. This is adaptation of existing, tested logic, not new conflict-detection logic.
- **Risk of custom-from-scratch here**: high and unnecessary — conflict detection semantics (what counts as "changed," how it interacts with active edits, how suppression avoids reload storms during git merges) took real design iteration to get right (`Epic 2.x` notes referenced in the watcher's docstring) and reimplementing that state machine bespoke for web risks silently diverging from desktop/Android behavior the user already relies on.
- **Verdict**: reuse `GraphFileWatcher` unmodified — implement the two `FileSystem` hooks it already expects. **Recommended.**

### b) Cross-tab coordination → reuse the `GitWriteLock` (`navigator.locks.request()`) idiom
`GitWriteLock.kt` already solved "how do multiple browser tabs cooperate over a single logical resource" for git pushes, with a documented, deliberately-scoped "acquire-now, release-later" idiom and an explicit written note about what it does *not* guarantee (no cross-tab fetch/merge read-modify-write protection, no same-tab reentrancy). That tradeoff analysis is directly transferable: local-folder write-through has the same shape of problem (two tabs both writing to the same `FileSystemFileHandle`-backed file).
- **Risk of custom-from-scratch here**: writing fresh Web-Locks glue is low-risk technically (the API is small), but **skipping the existing idiom's documented scope discipline is the real risk** — the previous implementation explicitly rejected holding the lock across multiple suspend calls because of leak risk. An LLM asked to "add cross-tab locking" with no awareness of that prior decision could easily reintroduce the rejected pattern (e.g. holding a lock across a whole `write→verify→ack` sequence spanning multiple awaits).
- **Verdict**: copy the `jsRequestLockHandle` "acquire-now, release-later" idiom and its documented scope boundaries, don't design cross-tab locking from first principles. **Recommended.**

### c) Dirty-tracking sharing between two consumers (web-git-writeback's `dirtySet` vs. this feature's write-through) → the one piece that's genuinely new
`PlatformFileSystem.dirtySet` (`PlatformFileSystem.kt:29`) currently means "changed since last git push, needs re-committing" and is checkpointed to `.stele-dirty-set.json`. This feature introduces a second, related-but-distinct meaning of "dirty": "changed on the host directory since the app last wrote it," which needs to interoperate with, not collide with, the existing set — the requirement explicitly calls out "no regression to `web-git-writeback`'s dirty-file tracking."
- There is **no existing internal pattern to lift wholesale** for this one — it's genuinely new integration surface between two features that were built independently and are now being asked to share state safely.
- **This is exactly the situation where bespoke design (not LLM-improvised, not blindly reused) is warranted**: it should get its own small ADR / explicit merge semantics (e.g., "host-directory external-change events feed `GraphFileWatcher`'s existing dirty-set hook, which is a *different* map than `PlatformFileSystem.dirtySet`'s git-push-dirty map; the two must be reconciled only at write time, never merged into one collection") rather than either an LLM improvising from scratch or forcing an ill-fitting reuse of the git dirty-set structure.
- **Verdict**: custom design required, but scoped tightly and specified in an ADR before implementation — not "write it and see," and not "reuse `dirtySet` as-is" either. **Recommended path: bespoke design, spec'd first.**

### General principle observed from this codebase
Custom/bespoke code is worth it here specifically *because* the codebase already has two working precedents (`GraphFileWatcher`, `GitWriteLock`) whose designs were hard-won (documented rejected alternatives, documented known gaps) — reusing them is lower risk than any external library *and* lower risk than fresh LLM-generated logic, since neither an external library nor a fresh implementation would know about this codebase's specific active-edit-suppression rules or its git-push lock-scope constraints. The one place genuinely worth new bespoke design is the dirty-set interop question, precisely because no existing pattern covers two independent "dirty" concepts needing to coexist — and that's a design/spec task, not a "let the LLM figure it out while coding" task.

---

## 4. Fork or adapt — prior art worth studying (not forking)

- **Logseq itself** (`logseq/logseq`, the app SteleKit is migrating from) already ships this exact feature in its web build: "Logseq has pioneered... using the File System Access API for out-of-sandbox storage... The web version does not support [remote] sync, only local directory [access]." This is the single most relevant prior art available, since SteleKit is a direct KMP port of Logseq's data model and UX. Its implementation is ClojureScript (`frontend/fs/*` namespaces, e.g. an `nfs`/native-filesystem layer) — literal code reuse is impossible across the Kotlin/Wasm boundary, but its **behavioral decisions** (what "local directory" mode does and doesn't support, e.g. explicitly no sync in that mode) are worth reading as a UX/scope reference before finalizing this feature's own scope boundaries. Recommend a follow-up research pass that clones `logseq/logseq` and greps its `fs` namespace specifically for permission-recheck and change-detection logic, rather than relying on secondary sources (the one GitHub issue checked here turned out to have no implementation detail).
- **vscode.dev** is the other frequently-cited example of a production web app doing FSA-API live local-folder sync ("all changes... are automatically reflected in your project's folder"). VS Code's implementation is TypeScript/Electron-adjacent and enormous in scope (full workspace/file-provider abstraction) — not a fit to adapt directly, but its publicly documented pattern of "store the handle in IndexedDB, `queryPermission()` on load, `requestPermission()` only on demand" (confirmed independently via Chrome for Developers' own blog on persistent FSA permissions) matches the "at most one click to resume access" requirement and is the standard, not vscode-specific, pattern — no need to study VS Code's source specifically since the pattern is well documented at the platform level.
- **Excalidraw** uses `browser-fs-access` for picker unification but does not do live write-through/sync in the way this feature needs (it's closer to "open/save," not "keep a directory live") — lower relevance than initially assumed from its association with the `browser-fs-access` library.

**Verdict**: **Study, don't fork.** Logseq's own behavioral scope decisions for "local directory" mode are worth a targeted look (follow-up task, not blocking this research), specifically to sanity-check this feature's scope boundaries (e.g., does Logseq's local-directory web mode support cross-tab usage at all, or does it explicitly warn against opening the same folder in two tabs — informs whether "cross-tab coordination" in this feature's scope is solving a real observed problem or a hypothetical one). No code or library from either project is reusable given the Kotlin/Wasm target.

---

## Summary recommendation

| Piece | Approach | Verdict |
|---|---|---|
| Directory handle persistence (IndexedDB) | Hand-written `js()` IndexedDB calls (matches existing `OpfsInterop.kt` idiom) **or** adopt `JuulLabs/indexeddb` — decide explicitly via ADR, don't default | Viable (either); **not** `browser-fs-access` |
| Picker / permission re-grant | Extend existing `showDirectoryPicker()`/`OpfsInterop.kt` hand-written interop with `queryPermission()`/`requestPermission()` calls, following the platform-documented handle-persist-then-requestPermission pattern | Recommended — build directly, no library needed |
| Write-through to host directory | New wasmJs `FileSystem` methods using the existing `createWritable()`/`writableWrite()` idiom already in `OpfsInterop.kt` | Recommended — build directly |
| External-change detection / conflict UX | Wire wasmJs into existing `GraphFileWatcher` (`getLastModifiedTime` + `startExternalChangeDetection` hooks) | Recommended — reuse, do not reimplement |
| Cross-tab coordination | Copy `GitWriteLock`'s `navigator.locks.request()` "acquire-now/release-later" idiom and its documented scope discipline | Recommended — reuse, do not reimplement |
| Dirty-set interop between write-through and `web-git-writeback` | Bespoke design, specified in an ADR before coding — two distinct "dirty" concepts reconciled only at write time | Recommended — custom, but spec-first |
| Fallback on unsupported browsers | Existing `supportsNativeDirectoryPicker`/`showDirectoryPickerSupported()` capability check, no ponyfill needed | Recommended — build directly |

Overall: **build from scratch, reusing this codebase's own `GraphFileWatcher` and `GitWriteLock` patterns for the two hardest sub-problems**, with one narrow, deliberate library-adoption decision (IndexedDB wrapper) to make explicitly via ADR rather than by default, and one narrow genuinely-new bespoke design (dirty-set interop) that should be spec'd before implementation. No external library changes the shape of this feature meaningfully; the main research payoff is confirming that two of the three "trickiest pieces" named in the requirements are *already solved* in this codebase and just need a wasmJs adapter, not new design.
