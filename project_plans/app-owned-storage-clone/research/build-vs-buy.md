# Build vs. Buy: Unified Storage Location Picker + Relocate/Link

Phase 2 research for `app-owned-storage-clone`. Question: for the unified
"storage location" picker (Android SAF vs. app storage; Web OPFS vs. real
folder) and the relocate/link operations, should any part be sourced from an
existing library/service, or built entirely on what's already in this repo?

**Bottom line: build on existing in-repo mechanisms for all four areas.** No
external library or service clears the bar of "saves more integration cost
than it adds," mainly because (a) the hard part — a *unified* picker across
Android SAF + app storage + Web OPFS + real folder, wired to this app's own
`GitShadowWorktree`/`HostDirectorySync` state machines — is product-specific
glue no library provides, and (b) this codebase already carries
purpose-built, working versions of every generic piece a library would offer
(`ContentHasher` for cross-platform hashing, `GitWorktreeLocks` for JGit lock
coordination, Okio for JVM/Android path handling, direct File System Access
API/OPFS interop in `HostDirectorySync`).

---

## 1. Existing OSS library or framework

### 1a. Android: SAF + `filesDir` unification

**Candidates found:**

| Library | Status | License | KMP? |
|---|---|---|---|
| [`google/modernstorage`](https://github.com/google/modernstorage) | **Archived 2026-03-15**, never reached a stable 1.0 (last alpha: `1.0.0-alpha06`) | Apache-2.0 | No (Android-only) |
| [`anggrayudi/SimpleStorage`](https://github.com/anggrayudi/SimpleStorage) | Actively maintained — latest stable `2.3.0`, `3.0.0-beta01` in progress (StorageFile API, API 37 support) | Apache-2.0 | No (Android-only, JVM+Android artifact) |

Google's own attempt at this exact abstraction (`ModernStorage`) shipped,
went nowhere, and was archived — that's a signal the "unify SAF + filesDir"
problem doesn't compress well into a generic library even with first-party
resources behind it.

`SimpleStorage` is real and well-maintained, but:
- It's Android-only (no `commonMain`/wasmJs target), so it cannot back the
  "one picker component per platform" requirement's *shared logic* — at
  best it would replace the Android-specific half of an already
  platform-split picker, with zero reuse toward the Web half.
- This repo already depends directly on `androidx.documentfile:documentfile:1.0.1`
  (`kmp/build.gradle.kts:260`) and has a working `PlatformFileSystem.kt`
  (Android, 1170 lines) that already implements SAF browsing, `filesDir`
  access, and the `MANAGE_EXTERNAL_STORAGE` direct-access fast path
  (`isDirectAccess()`/`resolveToRealPath()`, cited in requirements.md's
  Feasibility Risks). SimpleStorage's own abstraction (`DocumentFileCompat`,
  `SimpleStorageHelper`, Flow-based copy/move) would sit *beside* this
  existing code, not replace it — the picker still needs to expose "app
  storage" as a peer option inside the same UI surface as SAF browsing,
  which SimpleStorage doesn't model (it's SAF/MediaStore-focused, no
  concept of "our own app-storage row in the picker").
- Given this repo's `Either<DomainError, T>` convention (CLAUDE.md), every
  SimpleStorage call (`Flow`-based, throws on error) would need a wrapping
  adapter anyway — the "adopt a library" version still requires writing
  and testing a full Either-returning facade, at which point most of the
  claimed integration savings are gone.

**Verdict: Not recommended.** Keep extending `PlatformFileSystem.kt` +
`androidx.documentfile` directly.

### 1b. Web: File System Access API + OPFS unification

**Candidates found:**

| Library | Status | License | KMP? |
|---|---|---|---|
| [`GoogleChromeLabs/browser-fs-access`](https://github.com/GoogleChromeLabs/browser-fs-access) | Maintained by Chrome DevRel; latest `0.38.0`, ~1 year old at last check | Apache-2.0 | No — plain JS/npm, no Kotlin/Wasm bindings |
| `opfs-js` | Small, latest `1.0.1`, ~1 year old | — | No |
| `idb-keyval` (surfaced researching handle persistence) | Maintained, tiny | Apache-2.0 | No |

`browser-fs-access`'s entire scope is "open a file/directory picker, and
fall back to `<input type=file>` when the File System Access API is
unsupported." That is a *strict subset* of what `HostDirectorySync.kt`
(1871 lines) already does in this repo: livesync reconciliation, dirty-set
tracking, cross-tab coordination, session-resume via IndexedDB-persisted
handles (`idbOpenHandleDb`/`idbPutHandle`, `kmp/src/wasmJsMain/.../HostDirectorySync.kt:774-848`),
and conflict buffering. Pulling in `browser-fs-access` would only replace
the initial `showDirectoryPicker()` call site — a few lines — while adding
a JS-interop dependency with no Kotlin/Wasm-idiomatic API, no `Either`
integration, and (per npm) no update in about a year. `idb-keyval` is
similarly a thin wrapper over IndexedDB that this repo has already
hand-rolled the exact subset of (typed put/get for one object store) as
part of `HostDirectorySync`.

**Verdict: Not recommended.** No Web library unifies "OPFS vs. real folder"
either (that unification is exactly the product-specific picker work this
project has to do) — extend `HostDirectorySync`/`PlatformFileSystem.kt`
(wasmJs) directly.

### 1c. File/directory copy-with-verification

**Candidates found:**

| Library | Notes |
|---|---|
| Apache Commons IO `FileUtils` | `copyDirectory`, `checksum(File, Checksum)`, `contentEquals` — JVM-only, no resumability |
| Okio `FileSystem` (already a dependency, `com.squareup.okio:okio:3.17.0`) | Cross-platform path/IO abstraction, but currently used in this repo only for attachment path naming (`AttachmentFileNaming.kt`); Okio has no OPFS `FileSystem` implementation for wasmJs, so it can't be the *shared* copy primitive across Android+Web anyway |
| `java.security.MessageDigest` | JVM/Android stdlib, not available on wasmJs |

None of these solve the actual constraint: the copy-then-verify step must
run identically in spirit on Android (JGit working tree + markdown files
via `java.io.File`/SAF) and Web (OPFS ↔ real folder via File System Access
API), and this repo has already made the KMP-portability call once before
— see §3 below. Commons IO is JVM-only and would only ever cover the
Android side; Okio's cross-platform `FileSystem` doesn't reach wasmJs OPFS
in this codebase's current wiring, so adopting it buys nothing for the half
of the matrix that actually needs a shared abstraction.

**Verdict: Not recommended** — see §3 for the concrete alternative (extend
`ContentHasher`, don't import a library).

---

## 2. SaaS / managed API

**Not applicable**, confirmed rather than assumed. Searched for any
"storage picker as a service" or managed local-storage-migration concept —
none exists, because the entire feature is on-device, offline-capable
file/directory management (SAF, OPFS, `filesDir`, a real folder handle).
There is no server component in this project's architecture for this
feature (git remotes are the closest thing to a hosted dependency, and
credential handling for those is explicitly out of scope per
requirements.md). No further action needed here.

---

## 3. LLM-generated implementation vs. battle-tested library (verification step)

### Content hashing for verify-before-delete

This repo has **already made this exact build-vs-buy decision once**, for a
different feature, and the answer is on record in
[`kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/ContentHasher.kt`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/ContentHasher.kt):
a from-scratch, pure-Kotlin SHA-256 implementation (FIPS 180-4), specifically
because `java.security.MessageDigest` is JVM/Android-only and this app also
targets wasmJs, where there is no equivalent stdlib crypto API reachable
from Kotlin without additional JS interop. `ContentHasher.sha256(ByteArray)`
already returns a 64-char hex digest and is unit-testable in `commonTest`
across every target at once.

- **Verdict on hand-rolling vs. a dedicated hashing library: hand-roll is
  correct and already done.** SHA-256 is exactly the "~20 lines on top of
  stdlib" case the research question anticipates — except stdlib isn't
  uniformly available across this app's targets, so the ~150-line pure-Kotlin
  implementation *is* the stdlib-equivalent for KMP purposes. Reuse
  `ContentHasher` for the relocate/verify step (hash source + destination
  file bytes, compare digests) rather than reaching for a library — a
  hashing library (e.g. a KMP wrapper over each platform's native crypto)
  would need to be at least as portable as what's already shipped and unit
  tested, and none of the candidates surfaced in research (all Android-only
  or JVM-only) clear that bar.
- One gap to close in Phase 3: `ContentHasher` currently hashes `String`
  content (with whitespace normalization semantics tuned for block
  dedup — not what a byte-exact file-integrity check wants).
  The relocate/verify step needs a **raw-bytes, no-normalization** entry
  point (`sha256(ByteArray)` already exists and is normalization-free — use
  that overload directly, not `sha256ForContent`/`normalizeForHash`,
  which would incorrectly treat a byte-different file as identical).

### JGit: safely relocating `.git` without corruption

No JGit documentation or API describes a single blessed "relocate a
repository" sequence — JGit's own mailing list
([eclipse.org/lists/jgit-dev, "Files remaining locked after repository close
in Windows"](https://www.eclipse.org/lists/jgit-dev/msg01951.html)) and
downstream issue trackers (e.g.
[archi-modelrepository-plugin#131](https://github.com/archimatetool/archi-modelrepository-plugin/issues/131),
[grgit#33](https://github.com/ajoberstar/grgit/issues/33)) confirm the
practical pattern is exactly what this repo already assumes: **close the
`Repository` first, then move/copy the directory tree**, with the caveat
that lock-file/handle-release timing is a known sharp edge (Windows-specific
in the reports found, since file handles there are exclusive — Android's
Linux-based FS doesn't have that failure mode, but a `.lock` file left
behind by an interrupted JGit operation is still possible and must be
detected before a move, not assumed absent).

This repo already has bespoke lock coordination for exactly this class of
problem: `GitWorktreeLocks.kt` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/GitWorktreeLocks.kt`)
maintains a `ConcurrentHashMap<String, Mutex>` keyed by shadow-worktree ID,
used by `GitShadowWorktree.kt` to serialize access per graph. There is no
known-correct third-party API sequence beyond "close, then check for
`.lock` files, then move" — the actual safety work is bespoke retry/lock
detection logic layered on `Repository.close()`, and this repo already has
the coordination primitive (`GitWorktreeLocks`) that a relocate operation
should acquire before moving a `.git` directory, plus `GitShadowFlushActor`
(the write-back queue) to drain first.

- **Verdict: hand-roll, on top of existing `GitWorktreeLocks`.** No
  library or documented JGit recipe exists to replace this; the research
  question's premise (in case a known-correct sequence existed) doesn't
  hold — cite the sources above as evidence there's no shortcut here, only
  confirmation that "close first" is necessary but insufficient without the
  repo's own lock/drain logic.

---

## 4. Fork or adapt from an existing OSS app

Searched Obsidian's plugin ecosystem and Joplin (both have public source and
solve adjacent problems).

- **Obsidian "Move vault"** (native desktop feature, not a plugin) — UX
  precedent worth citing: it shows old/new location and performs the move
  as a guided, named action. But Obsidian's vault storage is always a plain
  OS filesystem folder (Electron/Node); it has no SAF/OPFS/app-storage
  concept at all, so there's no architecture to adapt, only a UX confirmation
  pattern that this project's requirements.md already independently
  specifies (Risk Control: "explicit confirmation naming exact
  source/destination").
- **Obsidian sync plugins** (Remote Vault Sync, Sync Engine, Local Sync,
  Self-Hosted Vault Sync) — all TypeScript/Electron, all solve
  *cloud/network* sync, not local storage-backend relocation. Not
  applicable.
- **Joplin's sync-target driver architecture**
  ([joplinapp.org/help/dev/spec/architecture](https://joplinapp.org/help/dev/spec/architecture/),
  [.../spec/sync](https://joplinapp.org/help/dev/spec/sync/)) — the one
  genuinely relevant pattern found: Joplin defines sync as talking to any
  backend through a small filesystem-like driver interface (read/write/
  delete/list), letting users switch sync targets (filesystem, S3, WebDAV,
  Dropbox, ...) without touching the rest of the app. This validates, at
  the *pattern* level, the direction requirements.md is already pointed —
  a unified storage-location abstraction behind one small interface, with
  Android SAF/app-storage and Web OPFS/real-folder as interchangeable
  backends — but it's a design-pattern citation, not adaptable code: Joplin
  is JavaScript/TypeScript/Electron with a completely different runtime
  model (no SAF, no OPFS, no JGit-shadow-worktree concept), and this repo
  already has the equivalent seam (`PlatformFileSystem` expect/actual +
  `GraphManager`'s per-graph `RepositorySet`) that the new location kinds
  need to plug into.

**Verdict: Not recommended for code reuse (no viable source); Viable as a
pattern reference only** — Joplin's driver abstraction is worth a one-line
citation in the Phase 3 design doc as prior art for "storage backend behind
a uniform interface," nothing more.

---

## Summary table

| Option | Verdict |
|---|---|
| Android SAF/filesDir library (SimpleStorage, ModernStorage) | Not recommended |
| Web FS-Access/OPFS library (browser-fs-access, opfs-js, idb-keyval) | Not recommended |
| Copy+verify library (Commons IO, Okio-as-shared-copy) | Not recommended |
| SaaS storage-picker service | Not applicable |
| Hand-rolled SHA-256 (`ContentHasher`) vs. hashing library | Recommended (already built — reuse `sha256(ByteArray)`) |
| Hand-rolled JGit lock/drain logic vs. "known-correct" JGit API | Recommended (no such API exists — extend `GitWorktreeLocks`) |
| Fork/adapt Obsidian or Joplin | Not recommended for code; Viable as UX/pattern reference only |

**Net recommendation:** build entirely on this repo's existing mechanisms —
`PlatformFileSystem.kt` (both platforms), `GitShadowWorktree`/
`GitShadowFlushActor`/`GitWriteBackQueue`/`GitWorktreeLocks` (Android),
`HostDirectorySync`/`FolderSyncSettings` (Web), and `ContentHasher` (shared)
— per requirements.md's own instruction to extend, not replace, these
systems. No external dependency addition is justified by this research.
