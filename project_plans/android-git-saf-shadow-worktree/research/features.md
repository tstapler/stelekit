# Research: Android Git SAF Shadow Worktree — Features & Edge Cases

Phase 2 research track. Scope: feature landscape, prior art, and edge cases for the
shadow-working-tree design. Does not design the solution — see `requirements.md` for
the chosen direction and rejected alternatives.

## 1. Existing SteleKit behavior (verified by reading source)

### GitSyncService / GitRepository (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/`)

- `GitSyncService.sync()` (`GitSyncService.kt:119-308`) is a fixed 9-step pipeline: network
  check → config load → detached-HEAD/stale-lock checks → `graphWriter.flush()` +
  `editLock.awaitIdle()` → commit local changes → fetch → merge (if remote changes) → reload
  merged files → push. Every step assumes `GitRepository` operates synchronously on a
  filesystem the JGit layer can already reach — there is no existing hook for an
  "SAF→shadow sync" pre-step or a "shadow→SAF sync" post-step. Both would need to be spliced
  in around step 4 (pre-op) and step 8 (post-merge reload, `GitSyncService.kt:266-272`).
- `GitRepository` (`GitRepository.kt:18-46`) is the platform-agnostic interface
  (`JvmGitRepository`, `AndroidGitRepository`, `IosGitRepository` stub implement it). It takes
  `GitConfig` with `repoRoot: String` — no distinction today between "the real working-tree
  root JGit checks out into" and "the location the user actually edits." Introducing a shadow
  tree means `repoRoot` becomes shadow-relative for Android, decoupled from the SAF path the
  user picked; every `GitRepository` caller assumes these are the same path today.
- **Critical existing-code interaction**: `AndroidGitRepository.merge()`
  (`AndroidGitRepository.kt:196-279`) builds `ConflictFile.filePath` as literally
  `"${config.repoRoot}/$filePath"` (line 217) — an absolute path under `repoRoot`. If
  `repoRoot` becomes the shadow dir, `ConflictFile.filePath` becomes a shadow-absolute path.
  But `GitSyncService.resolveConflict()` (`GitSyncService.kt:403-451`) calls
  `fileSystem.readFile(filePath)` / `fileSystem.writeFile(filePath, ...)` directly on that same
  `filePath` (lines 416, 426) — and on Android, `PlatformFileSystem.readFile`/`writeFile`
  dispatch on the `saf://` prefix, not a shadow `java.io.File` path. **This is a live bug the
  shadow design must close**: conflict resolution writes would silently miss the SAF layer
  entirely (writing only to the shadow copy, which the merge commit would then read from
  successfully — but the user's SAF-visible file would never be updated, and `graphLoader`
  would re-import the *old* SAF content on next external-change scan). `wikiRelativePath`
  (`ConflictFile`, `AndroidGitRepository.kt:218-223`) already carries the SAF-relative-looking
  path independent of `repoRoot` — this field, not `filePath`, is the natural remap target for
  `ConflictResolutionScreen`.
- `ConflictResolutionScreen` (`ui/screens/git/ConflictResolutionScreen.kt`) itself is
  presentation-only: it takes `List<ConflictFile>`, lets the user pick LOCAL/REMOTE per file,
  and forwards `Map<String, MergeSide>` keyed by whatever string is in `ConflictFile.filePath`
  straight through to `onResolve` (line 101) → `GitSyncService.resolveConflictBySide()` →
  `gitRepository.checkoutFile(config, filePath, side)` (`GitSyncService.kt:457-486`). No
  remapping logic exists in the screen today; it trusts the path it was handed is
  directly usable. Whatever remap is chosen must happen before this data reaches the screen,
  not inside it.
- `DomainError.GitError` (`error/DomainError.kt:70-104`) has no error variant for a
  shadow↔SAF sync failure distinct from generic filesystem errors — `ShadowFlushActor`
  (see below) silently retries/logs (`Log.w`) rather than surfacing a `GitError`. A git-sync
  shadow mirror will need its own explicit error case(s) (e.g.
  `ShadowSyncFailed(direction, path, cause)`) so `GitSyncService` can react distinctly from a
  network or auth failure.

### GraphLoader / external-change race pattern (`db/GraphLoader.kt`, `db/GraphFileWatcher.kt`, `db/GraphEvents.kt`)

- The existing "external edit vs. app state" mechanism is `ExternalFileChange`
  (`db/GraphEvents.kt:12-15`): `data class ExternalFileChange(filePath, content, suppress: () -> Unit)`,
  emitted on `GraphLoader.externalFileChanges: SharedFlow<ExternalFileChange>`
  (`GraphLoaderPort.kt:39`, wired at `GraphLoader.kt:469`). A subscriber can call `suppress()`
  within a 200ms window (`GraphFileWatcher.kt:235`, `withTimeoutOrNull(200L)`) to tell the
  watcher "I already know about this change, don't re-import it as a conflict." There is **no
  `DiskConflict` sealed type** in this codebase (the requirements doc's phrase is describing
  this suppress-or-accept pattern generically, not a named type) — worth correcting in the plan
  doc if it currently implies one exists by that name.
- `GraphLoader.beginGitMerge(pathsBeingMerged)` / `endGitMerge()`
  (`GraphLoader.kt:903-912`, backed by `GraphFileWatcher.kt:157-175`) add paths to a **sticky**
  suppression set (`gitMergeSuppressedFiles`) that persists across multiple watcher ticks —
  unlike the single-shot `suppress()` above. `GitSyncService` already calls this pair around
  every post-merge/resolve reload (`GitSyncService.kt:267-271`, `442-446`, `477-481`, `516-520`)
  so the file watcher doesn't treat the git layer's own writes as "external" changes and race
  itself. **This exact mechanism is the natural extension point** for suppressing
  `SafChangeDetector` (see below) during the shadow→SAF write-back window — it already solves
  "our own write shouldn't look like someone else's write" for the git-reload path; it does not
  yet cover the *new* shadow→SAF direction this feature introduces.
- `FileRegistry` (`db/FileRegistry.kt`) has an existing, separate own-write-suppression
  mechanism using a `Long.MAX_VALUE` mtime sentinel (`FileRegistry.kt:92,123,191,204,241`) —
  used by `GraphWriter` and `ShadowFlushActor`'s `onPreFlush`/`onFlushed`/`onFlushFailed`
  callbacks (see below). Two distinct suppression mechanisms already coexist
  (`GraphFileWatcher`'s sticky set + `FileRegistry`'s sentinel); a third, git-shadow-specific
  one should be avoided if either can be reused/extended instead — flag this as an open design
  question for the plan phase, not something to resolve here.

### `ShadowFileCache` / `SafChangeDetector` / `WriteBehindQueue` / `ShadowFlushActor` (`androidMain/kotlin/.../platform/`)

This is the **existing, already-shipped shadow-mirror infrastructure** for the unrelated
"background indexing without Binder IPC overhead" use case — not for git. It is directly
relevant because the new git shadow tree is proposed to reuse/extend `ShadowFileCache`
per the requirements doc, and its documented invariants are exactly what would need to change:

- `ShadowFileCache` (`ShadowFileCache.kt:1-21`) is explicitly documented today as **read-only
  and one-directional**: "SAF is the only write target; shadow is a read cache derived from
  SAF." Storage root: `File(context.filesDir, "graphs/$graphId/shadow")` (line 23) — i.e. the
  exact same `context.filesDir/graphs/$graphId/shadow` path the requirements doc proposes
  reusing for the git worktree. **This means the git feature and the existing background-index
  shadow cache would share a directory tree if literally reused as-is** — a JGit `.git`
  directory, working-tree checkout, and the index-cache's freshness/staleness bookkeping
  (`syncFromSaf`, `invalidateStale`, mtime-based staleness heuristics, lines 66-155) would all
  be operating on the same files with different assumptions about who's allowed to write. This
  is a first-order design risk to flag for the plan phase: either the git worktree needs its
  own subdirectory (`shadow/git-worktree/` vs. `shadow/index-cache/`, say) or `ShadowFileCache`
  needs an explicit ownership/locking protocol so index-cache staleness scans don't race git's
  working-tree checkout/status operations touching the same files.
- Already-shipped **write-behind path exists**, but it is per-file/BlockEditor-driven, not
  bulk/git-driven: `WriteBehindQueue` (`WriteBehindQueue.kt`) is a persistent, file-backed,
  append-only queue of pending shadow→SAF writes (survives process death by construction — it's
  just a text file, `queueFile.appendText`/`readLines`, `WriteBehindQueue.kt:22-53`).
  `ShadowFlushActor.flush()` (`ShadowFlushActor.kt:44-49`) drains it: for each queued path, reads
  shadow content, calls `onPreFlush` (sets a `FileRegistry` sentinel so `SafChangeDetector`'s
  poll doesn't treat the flush as an external change), writes to SAF via
  `fileSystem.writeFile()`, and on success dequeues + stamps the shadow mtime with the
  post-flush SAF mtime (`ShadowFlushActor.kt:71-98`) — explicitly to avoid `invalidateStale`
  later deleting the shadow because of a stale local-clock-vs-SAF-mtime skew (comment at
  lines 88-90). **This flush protocol (queue → pre-flush sentinel → write → dequeue → stamp
  mtime) is the existing idempotent/resumable pattern this codebase already uses for exactly
  the "shadow write must survive process death and must not race external-change detection"
  problem** — the git feature's shadow→SAF post-merge write-back should almost certainly reuse
  this actor/queue pair rather than inventing a second one, per file, per changed-file-in-merge.
- `SafChangeDetector` (`SafChangeDetector.kt:1-228`) is the mechanism that would fire (falsely)
  during an in-flight shadow→SAF write unless suppressed: three parallel strategies — inotify
  `FileObserver` when `MANAGE_EXTERNAL_STORAGE` is granted (line 82-98), `ContentObserver` +
  30-second poll otherwise (`159-191`), and an immediate re-scan on `ProcessLifecycleOwner`
  `ON_START` (`193-204`, so foregrounding the app after a background git sync completed would
  immediately re-trigger detection). None of these three paths currently know about
  `beginGitMerge`/`endGitMerge` suppression — that suppression lives in `GraphFileWatcher`, a
  separate object from `SafChangeDetector`. A shadow→SAF write during git merge reload would
  currently risk `SafChangeDetector` firing `onExternalChange()` mid-write, which — depending on
  how the caller wired it — could trigger a redundant/incorrect external-change scan concurrent
  with the git layer's own write-back. This wiring gap is a concrete open question for the plan
  phase (does `SafChangeDetector` need its own git-merge-suppression hook, or can it consume the
  same suppression state `GraphFileWatcher` already tracks?).
- `ShadowFileCache.invalidateStale()` (`ShadowFileCache.kt:143-155`) already encodes a real
  lesson learned the hard way (per its comment): SAF providers can report a stale mtime after an
  external write (example cited: Termux writing while the app is backgrounded), so staleness is
  checked by **both** mtime *and* file size. Any staleness/freshness check the git shadow-sync
  design adds should copy this dual-signal approach rather than trusting mtime alone.

## 2. Prior art / industry patterns

- **`tsanva/obsidian-git-android`** (github.com/tsanva/obsidian-git-android) — the closest known
  open-source precedent for exactly this problem (SAF-only vault + real git via JGit on
  Android). Its documented architecture:
  `Remote Git Repo ←→ (JGit) ←→ Internal Clone ←→ (SAF) ←→ Obsidian Vault` — i.e. the identical
  "internal file-backed clone + bidirectional SAF sync" shape SteleKit is proposing, described
  explicitly as bidirectional ("Changes sync both from vault to Git and Git to vault"). It notes
  a pull-before-push conflict strategy ("Both changed → Pull first, merge, then push") matching
  `GitSyncService.sync()`'s existing fetch-before-push ordering. It documents two real-world
  limitations worth carrying into the pre-mortem: (1) **first-time sync on a large vault is slow
  due to SAF limitations** — directly relevant to SteleKit's 8000+-page graph scale (see §3
  below); (2) **filenames with characters unsupported on Android get renamed** during the
  SAF↔internal-clone mirror — a filename-mapping edge case SteleKit's `ShadowFileCache` doesn't
  appear to handle today (`safeShadowFile`'s only special-casing is path-traversal, not
  character-set sanitization). The project's own docs explicitly do **not** detail race-condition
  or concurrent-external-edit handling — i.e. this exact race-safety question is unsolved prior
  art, not a solved problem SteleKit can just copy.
- **Working Copy (iOS)** — conceptually the same idea one platform over: an internal
  sandboxed git store, exposed to other apps via the Files app / File Provider extension rather
  than SAF. Public docs describe the user-facing integration (Files app, WebDAV, x-callback-url)
  but not internal sync-race handling — no additional technical detail was found beyond
  confirming the same "real git engine + expose via platform file-sharing API" shape.
- **Termux + git** — confirmed via search that Termux (and by extension any Termux-based git
  workflow) **does not** operate against SAF-picked folders at all; Termux uses its own
  filesystem access model (`termux-setup-storage`) and file managers that support SAF can
  expose Termux's folder outward, not the reverse. This is evidence *against* "just shell out to
  a git binary against the SAF folder" as a viable alternative — no tool in the ecosystem
  operates git directly on a `content://`-backed folder; every real solution found (JGit-in-app,
  Working Copy, Termux) works by keeping a real filesystem-backed store and syncing/exposing it
  outward through the platform's file-sharing layer, not by teaching git itself to speak SAF.
  This corroborates the three alternatives the requirements doc already rejected (forking JGit,
  homegrown git implementation, `MANAGE_EXTERNAL_STORAGE`-only) — no counter-example was found
  in prior art.
- General SAF-with-native-libraries guidance (Android developer community sources) confirms the
  root constraint driving this whole feature: libraries needing random-access file I/O
  (JGit included) are fundamentally incompatible with `content://` URIs and the standard fix
  is "copy to local scoped storage first" — i.e. exactly the shadow-mirror direction chosen.

## 3. Edge cases and failure modes

Ordered roughly by severity × likelihood; "severity" = user-visible data-loss/corruption risk,
"likelihood" = how easily it's triggered given SteleKit's actual usage patterns.

1. **[HIGH severity / MEDIUM likelihood] Conflict-resolution write silently bypasses SAF.**
   Already-identified live bug risk (§1 above): if `ConflictFile.filePath` becomes
   shadow-relative but `GitSyncService.resolveConflict()`/`applyJournalMerge()` write via
   `fileSystem.writeFile(filePath, ...)` assuming SAF semantics, the resolved content lands only
   in the shadow tree. The subsequent `git commit` would succeed (shadow and git index agree),
   masking the failure — the user's SAF-visible file, and thus everything they see in the
   editor after `graphLoader.reloadFiles()`, would silently diverge from what was just merged
   and committed. This must be closed by making the shadow→SAF write-back an explicit step in
   every conflict-resolution code path, not implied by directory reuse.

2. **[HIGH severity / MEDIUM likelihood] Cross-file consistency during shadow→SAF write-back is
   not atomic at the graph level.** `AndroidGitRepository.merge()`'s `changedFiles` list
   (`AndroidGitRepository.kt:234-266`) can span many files in one merge (journal index files,
   multiple pages). If shadow→SAF write-back is per-file (matching `ShadowFlushActor`'s
   existing per-file granularity), a process death or SAF I/O failure partway through can leave
   some merged files visible in SAF and others not — while the shadow tree and git HEAD both
   already reflect the full merge. `GraphLoader.reloadFiles()` is called with the full
   `changedFiles` list *before* this write-back would need to happen in a naive design (current
   code reloads immediately after merge, `GitSyncService.kt:266-272`) — meaning the DB could be
   reloaded from git-shadow content the SAF layer hasn't received yet, or (worse) a
   read-after-write race where a page opened mid-flush reads half-written SAF content depending
   on write ordering. The design should either (a) treat the merge's `changedFiles` batch as one
   flush unit with all-or-nothing accounting via `WriteBehindQueue` (queue is idempotent/durable
   already, so a batch drain-to-completion loop is plausible), or (b) reorder so `reloadFiles()`
   only fires after shadow→SAF write-back confirms success for that file.

3. **[MEDIUM severity / MEDIUM likelihood] External SAF write races an in-flight shadow→SAF
   flush for the *same* file.** If another SAF-aware app or a widget/share-target write lands on
   a file between the shadow tree computing new content and the write-back completing, last-writer-wins
   at the SAF layer with no detection — `ShadowFlushActor`'s existing sentinel mechanism
   (`onPreFlush`/`onFlushed`/`onFlushFailed`) only suppresses *SteleKit's own* re-detection of
   the write; it does nothing to detect or preserve a concurrent *external* write that happened
   in the same window. This is the sharpest version of the "must not silently lose user edits"
   constraint from the requirements doc. `SafChangeDetector`'s fastest path (inotify) still has
   nonzero latency plus the 200ms suppress-window design elsewhere in this codebase — a write
   landing inside that window is exactly the failure mode. No existing mechanism resolves this;
   it needs either an explicit compare-and-swap-style check (read SAF mtime/hash immediately
   before write, abort/re-detect if changed since the shadow snapshot was taken) or acceptance
   that this window exists and is narrow, documented as a residual risk.

4. **[MEDIUM severity / LOW-MEDIUM likelihood] External SAF edit races SAF→shadow sync
   immediately before a git operation.** `ShadowFileCache.syncFromSaf()` is explicitly
   documented as read-only/pull-only today (§1) — safe by construction *if* it always runs to
   completion before JGit touches the shadow tree. But if a widget or another app writes to SAF
   *during* the `syncFromSaf()` batch copy (which iterates a full file list per subdir,
   `ShadowFileCache.kt:66-88`, with no snapshot isolation), the shadow tree could end up with a
   mix of pre- and post-write file states across different files — `git commit` would then
   stage this inconsistent mix as one commit. Existing `GraphWriter.flush()` +
   `editLock.awaitIdle()` (`GitSyncService.kt:165-166`) already guards against the *app's own*
   pending edits racing sync, but there's no equivalent guard against a *third-party* SAF write
   racing the pre-sync copy.

5. **[MEDIUM severity / LOW likelihood] Merge conflicts: shadow-level vs. SAF-level resolution
   surface.** Per item 1, whichever path (`filePath` vs `wikiRelativePath`) is chosen must be
   the one both the UI *displays* and the one write-back operates against — a mismatch between
   "the path shown to the user" and "the path actually written" is a distinct, subtler bug from
   item 1 (item 1 is about correctness of the write target; this is about UI accuracy /
   user trust even if the write target were fixed). Given `wikiRelativePath` already exists and
   is SAF-relative-shaped, the natural fix is small, but it must be threaded through
   `ConflictResolutionScreen`'s `Map<String, MergeSide>` key choice deliberately, not left as an
   implicit assumption.

6. **[LOW-MEDIUM severity / HIGH likelihood on Android] Process death mid-sync.** Android kills
   backgrounded processes aggressively (explicitly called out in CLAUDE.md's coroutine-crash
   guidance as a live concern for this codebase). `GitSyncService.sync()`'s 9-step pipeline has
   no checkpointing — a kill between "merge succeeded" and "push" would leave local commits
   ahead of the last successful push, which JGit/git itself already tolerates safely (next sync
   just re-attempts push). The *new* risk is specifically in the shadow↔SAF sync steps this
   feature adds: a kill mid-write-back (item 2) is the actual novel resumability requirement.
   `WriteBehindQueue`'s existing durable-by-construction design (plain appended text file,
   survives process death, `WriteBehindQueue.kt:15`) is good evidence the write-back mechanism
   should be built the same way — enqueue-before-write, dequeue-after-confirmed-write — rather
   than an in-memory list that's lost on kill.

7. **[LOW severity / MEDIUM likelihood] `SafChangeDetector` false-positive during shadow→SAF
   write-back.** Covered above (§1) — the detector has no wiring today to the git-merge
   suppression `GraphFileWatcher` already implements. Low severity because it likely produces a
   redundant re-scan/no-op rather than data loss, but worth fixing for correctness and to avoid
   wasted SAF IPC (the codebase is already IPC-conscious per `ShadowFileCache`'s stated
   rationale for existing).

8. **[SCALE, not correctness] Bidirectional full-tree sync tension with "never O(graph)"
   discipline.** CLAUDE.md documents hard-won discipline against unbounded reads on large graphs
   (8,030-page warm-start regression test exists,
   `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/LargeGraphWarmStartCrashTest.kt`). A naive
   "mirror the whole SAF tree before every git op, mirror the whole shadow tree back after every
   merge" design directly conflicts with that discipline at graph scale — every `sync()` call
   would become O(graph) in file-system/SAF IPC terms even when only a handful of files actually
   changed. `ShadowFileCache.syncFromSaf()` already mitigates this for its existing read-only use
   case via mtime-skip (`ShadowFileCache.kt:75-77`, "skip if shadow is already fresh"), and
   `invalidateStale()`/`ShadowFlushActor` already operate incrementally (batch cursor per
   directory, not per file, per `ShadowFileCache.kt:139` comment). The git shadow-sync design
   should inherit this incremental posture explicitly — sync only files JGit's own diff/status
   report as changed, not a full-tree re-copy — and the plan phase should size this against a
   representative large graph's actual per-sync file-change count (likely small: a normal
   editing session touches a handful of pages/journal files, not thousands) versus the
   worst-case first-clone/first-run reconciliation (which *is* legitimately O(graph) once, and
   according to the obsidian-git-android prior art, is known to be slow — worth setting explicit
   user-facing progress UI expectations for that one-time case rather than trying to eliminate
   it).

## 4. Interface-boundary principles for the future in-memory multiplatform git engine

Out of scope to design, but per the requirements doc's decoupling ask, these are the observed
places where today's `GitRepository`/`GitSyncService` architecture already draws (or should
draw) a boundary that keeps shadow-mirror sync a private implementation detail:

- **`GitRepository` interface (`GitRepository.kt:18-46`) already has no knowledge of SAF, shadow
  trees, or `context.filesDir`** — it operates purely on `GitConfig.repoRoot: String` and
  domain models (`FetchResult`, `MergeResult`, `ConflictFile`, etc.). This is the correct
  boundary to preserve: shadow↔SAF sync should live entirely *inside* `AndroidGitRepository`
  (or a helper it composes), invoked before/after the `GitRepository` interface methods do their
  JGit work, never exposed through the interface itself. A future in-memory git engine
  (implementing `GitRepository` for some new platform or as an Android alternative) would then
  need zero awareness that shadow-mirroring ever existed.
- **`GitSyncService`'s 9-step pipeline is already platform-agnostic** — it calls
  `gitRepository.fetch/merge/push/...` without knowing which concrete implementation is behind
  the interface, and ADR-015 confirms this same pipeline/interface is already shared with the
  WASM platform's very different (REST-API-based) `GitRepository` implementation via the
  `WasmGitWriteService` design, and reuses `ConflictResolutionScreen` unmodified. **Do not add
  shadow-mirror-specific parameters, callbacks, or state to `GitSyncService` or `GitRepository`**
  — any pre/post-op sync hook needed should be internal to `AndroidGitRepository`'s method
  bodies (wrapping each interface method call with "sync in, do JGit op, sync out" internally),
  not threaded through the shared interface signature. This keeps a hypothetical future
  in-memory multiplatform engine's `GitRepository` implementation exactly as simple as
  `JvmGitRepository`'s today.
- **The one place genuine coupling is unavoidable: `ConflictFile` path semantics.** Per §1/§3
  item 5, whichever field (`filePath` vs `wikiRelativePath`) becomes the SAF-facing,
  UI-displayed, write-back-target path must be a *documented contract* on the `ConflictFile`
  data class itself (doc comment stating "wikiRelativePath is always relative to the user-facing
  wiki root, regardless of what backs `repoRoot` on this platform"), not an implicit
  Android-only convention — so a future in-memory engine implementing the same interface for,
  say, a different Android storage strategy or another platform entirely, has an explicit
  contract to satisfy rather than needing to reverse-engineer today's Android-specific behavior.
- **Error taxonomy**: adding shadow-sync-specific `DomainError.GitError` variants (per §1) should
  be named platform-neutrally (e.g. `WorkingTreeSyncFailed`, not `ShadowSyncFailed` or
  `SafSyncFailed`) so a future in-memory engine that has its own analogous
  "materialize into memory, then reconcile back out" step (per the WASM `WasmGitWriteService`
  precedent in ADR-015, which already has its own conflict-detection step) can reuse the same
  error type instead of the plan inventing an Android-specific one that has to be duplicated
  later.
