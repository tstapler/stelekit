# Phase 2 Research: Pitfalls and Risks — app-owned-storage-clone

Research question: what commonly goes wrong with "app-owned storage as an explicit,
selectable destination + relocate/link between storage backends" on Android (SAF ↔
`filesDir`) and Web (real folder ↔ OPFS), and what should this feature be explicitly
designed against.

Method: repo archaeology (`git log`, `Read`, `Grep` against this codebase) for
platform-specific and web-general pitfalls (items 1–5), where sourced. Confidence
labels follow this repo's Evidence and Claims discipline: **VERIFIED** (source opened
or command run), **INFERRED** (reasoned from verified facts but not directly
confirmed), **UNVERIFIED** (asserted by a secondary source, not independently
confirmed).

---

## 1. Android SAF pitfalls

### 1.1 `content://` URI permission revocation

- The system can revoke a `takePersistableUriPermission()` grant at any time, even
  though it's documented as the durable-grant API — [Storage updates in Android 11](https://developer.android.com/about/versions/11/privacy/storage).
  Android also auto-resets an app's runtime/SAF-adjacent grants if the app hasn't been
  opened in months — a background revocation vector with no in-app trigger.
- Grant count is capped (128 pre-Android 11, 512 on 11+) — [CommonsWare: Count Your SAF Uri Persisted Permissions!](https://commonsware.com/blog/2020/06/13/count-your-saf-uri-permission-grants.html).
  An app that accumulates grants across repeated relocate/link operations without
  calling `releasePersistableUriPermission()` on ones no longer needed can start
  silently failing new grants once the cap is hit. **Design implication**: release the
  old grant once a relocate away from a SAF folder completes and the user confirms
  cleanup.
- Detection: `ContentResolver.persistedUriPermissions` lists currently-live grants —
  check membership before starting a relocate/link rather than assuming a stored URI
  is still valid.
- **UNVERIFIED**: no single authoritative source names the exact exception thrown on
  access to a revoked-but-previously-persisted grant (`SecurityException` vs
  `FileNotFoundException` appears to vary by provider — [Medium writeup](https://medium.com/@LuvTyagi.Android/eacces-permission-denied-file-not-found-exception-b482e4b97d55)).
  Design implication: catch both broadly around any SAF access during a relocate, and
  treat either as "grant may be gone" rather than a generic I/O error.
- Cloud-backed providers are documented as extra-fragile: a persisted tree URI can
  become invalid after an app restart as if `takePersistableUriPermission` never took
  effect at all — [nextcloud/android#8336](https://github.com/nextcloud/android/issues/8336).
  This is a *provider* reliability problem, not just an OS revocation — a relocate
  *from* such a provider must re-validate the grant immediately before copying, not
  rely on a grant check performed earlier in the flow.

### 1.2 `ACTION_OPEN_DOCUMENT_TREE` granting an incompletely-writable tree

- Real-world reports of `DocumentFile.createFile()` failing with "Permission Denied"
  on some SD-card-backed trees despite a completed grant exist ([b4x forum](https://www.b4x.com/android/forum/threads/how-to-access-the-whole-of-external-sd-card.89798/)),
  but **UNVERIFIED**: no official Google source enumerates which providers/OEMs are
  affected or under what API-level conditions. Treat any specific OEM/version claim as
  unconfirmed. CommonsWare's independent "Scoped Storage Stories" series is the
  deepest treatment found, but wasn't read in full this pass — [Trees](https://www.goodreads.com/author_blog_posts/19065823-scoped-storage-stories-trees?tab=author),
  [Problems with SAF](https://www.goodreads.com/author_blog_posts/19151279-scoped-storage-stories-problems-with-saf?tab=author).
- **Design implication**: the relocate flow's verification step (copy-then-verify
  before repoint, per requirements.md §Risk Control) must attempt an actual
  write+read+delete probe on the destination tree *before* starting the bulk copy, not
  just trust that the grant callback succeeded — a tree can grant access and still
  reject writes on specific subpaths.

### 1.3 `DocumentFile` bulk-copy performance — already documented in this repo

This repo has independently discovered and documented the exact cost this research
question asks about, before this project started:

- `androidApp/src/main/AndroidManifest.xml:7-10` declares `MANAGE_EXTERNAL_STORAGE`
  with the comment: *"when granted, replaces SAF with direct java.io.File POSIX
  access for orders-of-magnitude lower write latency."*
- `PlatformFileSystem.kt` implements an `isDirectAccess()` fast path
  (`Environment.isExternalStorageManager()`) used at essentially every I/O call site,
  with comments like *"Skip DocumentFile.exists() IPC for files known to exist from
  this session"* and *"Two IPC calls: mtime + size from a single SAF cursor per
  directory"* — i.e., per-call Binder IPC cost is already a first-class, measured
  design constraint here, not a hypothetical.
- `kmp/src/androidMain/AndroidManifest.xml:52-55` even declares a benchmark-only
  `FileProvider` "to measure Binder IPC overhead vs direct file I/O," confirming this
  was empirically benchmarked in-repo (see `git log` hits `perf(android): SAF
  performance stories 1-3` (`d2ab2521`), `perf(android): eliminate redundant SAF
  Binder IPC calls per save` (`7eb24a3b`), `perf(android): SAF shadow copy eliminates
  Binder IPC for Phase 3 reads` (`e26fa891`)).
- External corroboration: `DocumentFile.findFile()` scans all children rather than
  querying by name, making it O(n) per lookup — a concrete before/after benchmark
  (48s → 3.5s) is reported after switching to `DocumentsContract` directly —
  [mihonapp/mihon#705](https://github.com/mihonapp/mihon/issues/705). Confirms the
  general shape of the problem class this repo already engineered around.
- **Design implication**: the new relocate/copy path for "SAF → app storage" (or the
  reverse) must reuse the existing `isDirectAccess()`/`resolveToRealPath()` fast path
  where available, and for the plain-SAF case must batch metadata calls (existence,
  mtime) rather than adding a naive per-file `DocumentFile` loop — the exact anti-
  pattern this repo has already paid down elsewhere.

---

## 2. Android `filesDir` pitfalls when promoted to primary storage

- **VERIFIED — `allowBackup="false"` on the canonical app**:
  `androidApp/src/main/AndroidManifest.xml:26` (the Bazel-canonical app, package
  `dev.stapler.stelekit.SteleKitApplication`) sets `android:allowBackup="false"`. No
  `fullBackupContent`/`dataExtractionRules` XML exists anywhere outside vendored
  third-party test fixtures. This means **Android's Auto Backup does not cover
  `filesDir` at all today** — an uninstall or `clearApplicationUserData()` wipes it
  with zero recovery path, exactly as the research question anticipated, and nothing
  today partially mitigates that risk.
  - Note: a separate, legacy `android/app/src/main/AndroidManifest.xml`
    (`com.logseq.app.MainActivity` — the pre-KMP Logseq-fork app, not the current
    SteleKit app) has `allowBackup="true"`, but this is not the shipping app and is
    irrelevant to current risk.
- Per Android's own data-storage guidance, `Context.clearApplicationUserData()` and an
  uninstall both delete all of `filesDir`/`cacheDir`/internal DB storage
  unconditionally — this is standard, well-documented platform behavior, not a
  surprise; the surprise is only that this project's `allowBackup=false` means no
  automatic mitigation exists once `filesDir` stops being "just a cache" and becomes
  a user's only copy of their data.
- **Design implication — this is the single highest-severity finding in this
  research.** Promoting `filesDir` from "SAF acceleration cache + DB" (today, always
  reconstructable from the SAF folder or the DB) to "primary storage for a plain
  (non-git) graph with no SAF backing at all" means an accidental uninstall becomes
  unrecoverable data loss with **zero** existing safety net (no backup, no
  git-remote copy for non-git graphs). This should be called out explicitly in
  Phase 3 planning — e.g., a "no full backup exists for app-only storage" warning
  shown at selection time, and/or scoping Phase 3 to require at least an export/backup
  affordance before shipping app-storage-only graphs as a first-class option. This is
  a new risk surface, not a preexisting one: today `filesDir` is always redundant with
  the SAF folder (Android) precisely because it's "just a cache."
- For **git-cloned** graphs specifically, the risk is partially mitigated by the
  existing git remote (per requirements.md's "cross-device sync/cloud backup ... git
  remote already serves this purpose" out-of-scope note) — but only if the user has
  actually pushed. A relocate-to-app-storage of a repo with unpushed commits, followed
  by an uninstall, is real data loss even for git-backed graphs. Worth surfacing in
  the confirmation dialog (requirements.md §Risk Control) when the destination is
  app-storage and the source has unpushed commits.

---

## 3. JGit-specific pitfalls relocating a live `.git` working tree

- **File-handle/mmap leaks**: JGit's `WindowCache` (pack-file window cache) does not
  mmap by default — [WindowCacheConfig docs](https://archive.eclipse.org/jgit/docs/jgit-2.0.0.201206130900-r/apidocs/org/eclipse/jgit/storage/file/WindowCacheConfig.html)
  — but even with `Repository.close()` called, JGit has shipped real regressions
  leaving `.pack` file handles open, breaking rename/delete **on Windows**
  ([eclipse-jgit/jgit#185](https://github.com/eclipse-jgit/jgit/issues/185),
  [#183](https://github.com/eclipse-jgit/jgit/issues/183)). **UNVERIFIED for
  Linux/Android** specifically — POSIX semantics generally tolerate rename/delete of
  a file with an open fd (inode persists until last close), so a leaked handle there
  is a resource leak, not necessarily a blocking copy failure, but this is INFERRED
  from general POSIX behavior, not a JGit-specific citation.
  - **Design implication**: always explicitly `Repository.close()` (and consider
    forcing `WindowCache` eviction) before any copy of the shadow worktree's `.git`
    directory, on the belief that "probably fine on Linux" is not the same as
    "verified fine," especially given this repo's Android target device diversity.
- **Lock files** (`index.lock`, `refs/heads/*.lock`, `packed-refs.lock`): the only
  documented-safe staleness check found is "no process still holds the lock" — on
  desktop that means checking for other OS processes; on Android, where a single JVM
  process owns the repository, this reduces to an **in-process** check: is there a
  live `GitShadowFlushActor` coroutine/job still running against this shadow key.
  This repo already has the right primitive for this — `GitWorktreeLocks.lockFor(shadowKey)`
  (used by both `GitShadowFlushActor.flush()` and, per its doc comment,
  `GitShadowWorktree.syncFromSafRoot()` and `PlatformFileSystem.flushPendingWrites()`)
  is a coroutine `Mutex` keyed by shadow key that already serializes exactly the
  operations a relocate would need to exclude. **A relocate/copy of a shadow worktree
  should acquire this same lock** rather than inventing a new one — this is the
  existing quiesce mechanism the requirements doc's Open Questions section asks
  about ("whether relocating a git-cloned graph needs to pause/drain
  `GitShadowFlushActor` mid-flight, and how to detect 'safe to move now'" — answer:
  yes, and the detection/exclusion primitive already exists as `GitWorktreeLocks`).
- **Non-atomic copy during concurrent fetch/push**: a documented JGit-specific
  packed-refs race exists — pack-refs can resurrect a ref that was concurrently
  deleted as a loose ref — [eclipse-jgit/jgit#152](https://github.com/eclipse-jgit/jgit/issues/152).
  More generally, git writes new pack files via temp-name-then-atomic-rename; copying
  mid-write risks picking up a torn pack file or a packed-refs/loose-refs mismatch.
  **No documented "safe live copy" pattern exists** for git repos generally — the
  only robust answer found in this research is quiesce-then-copy (hold
  `GitWorktreeLocks.lockFor(shadowKey)` for the duration of the copy, ensuring no
  fetch/push/flush can run concurrently), not any kind of "copy while live" trick.

---

## 4. Web-specific pitfalls (OPFS / File System Access API)

- **OPFS quota exhaustion**: a write beyond origin quota throws `QuotaExceededError`
  on the write call itself — [MDN: Storage quotas and eviction criteria](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria).
  `navigator.storage.estimate()` is advisory only, not a pre-write guarantee.
  Chrome's eviction under storage pressure is LRU-across-origins;
  `navigator.storage.persist()` (if granted) exempts an origin from that eviction —
  same MDN source. **Design implication**: relocate-to-OPFS must catch
  `QuotaExceededError` explicitly (not treat it as a generic I/O failure) and should
  request persistent storage before starting a large copy into OPFS, since an
  un-persisted origin can be evicted under pressure even without hitting a hard quota
  number.
- **File System Access API permission persistence — already handled in this repo**:
  a `FileSystemDirectoryHandle` rehydrated from IndexedDB after a restart typically
  reports `"prompt"` from `queryPermission()`, not `"granted"`, and a write requires a
  fresh `requestPermission()` gated on a user gesture ([MDN queryPermission()](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemHandle/queryPermission),
  [MDN requestPermission()](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemHandle/requestPermission)).
  **`HostDirectorySync.kt` already implements exactly this pattern**: it proactively
  re-queries handle permission on a poll tick (`queryHandlePermission`, around
  `HostDirectorySync.kt:592-601`) and mirrors the result into `hostAccessStateFlow`
  via `mapPermissionResultToAccessState`, rather than waiting for a write to fail. A
  "link" mode built on this mechanism should reuse `hostAccessStateFlow`'s
  granted/degraded state machine rather than adding a second, parallel permission
  check — this is the concrete answer to requirements.md's rabbit hole "a link that
  silently breaks because Chrome revoked directory access needs to be surfaced, not
  silently fail": the surfacing mechanism already exists for the *existing* livesync
  feature and should be extended, not rebuilt, for the new relocate/link flows.
- **Browser support (confirms prior research, no new gotcha)**: Chrome/Edge/Opera
  ship the full picker API; Firefox and Safari support OPFS only, with no directory-
  or file-picker methods at all — [caniuse: File System Access API](https://caniuse.com/native-filesystem-api).
  This means "pick a real folder" is a Chromium-only destination/source on Web; OPFS
  must remain the only option on Firefox/Safari, consistent with the existing
  fallback behavior at `getDefaultGraphPath()`.
- **Crash-safety mid-operation — a real gap, not just a theoretical one**: no
  primary source (MDN, spec, Chrome docs) was found guaranteeing per-`write()`
  atomicity in OPFS, nor documenting recovery behavior for a half-written file after
  a killed tab/worker. This should be treated as **unverified, assume unsafe**: design
  the relocate's OPFS-writing step as write-to-a-temp-path → verify → rename/move as a
  distinct final step the app's own state machine can detect as "interrupted" on next
  launch, rather than relying on any implicit browser durability guarantee.

---

## 5. General relocate/migration pitfalls (cross-checked against this repo's own patterns)

- **Idempotency of a resumed/retried move**: standard pattern is a persisted state
  machine (`pending → copying → verifying → verified → repointed →
  source-cleanup-pending`) with per-chunk/per-file checkpointing so a retry can skip
  already-verified content via content-addressed comparison rather than re-copying
  everything. For a single-user client app (this project's actual shape), this
  simpler durable-state-machine approach is sufficient — no distributed-transaction
  machinery needed.
- **The "two sources of truth" window**: for a single-user, single-device app (no
  concurrent multi-writer access), a **freeze-writes final cutover** (deny new writes,
  run a final incremental delta, then repoint) is simpler and safer than a dual-write
  strategy, whose main benefit — availability under concurrent writers — doesn't
  apply here. This matches requirements.md's own stated safety net (verify-before-
  repoint) and this repo's existing `GitWorktreeLocks` pattern (hold a lock for the
  duration rather than reconcile two live writers).
- **Orphaned partial-copy directories**: rsync's own interrupted-transfer temp files
  are the canonical cautionary example — they are not automatically rediscovered on
  retry unless a specific staging convention is used. **This repo already has the
  right precedent to imitate**: `GitShadowWorktree.sweepOrphans()`
  (`GitShadowWorktree.kt:405-423`) is a startup sweep that deletes shadow-tree
  directories whose `.last-used` marker is older than a 60-day grace period, and
  explicitly treats *ambiguous absence* of that marker as non-evidence of staleness
  (never eagerly deletes a marker-less directory). A relocate's staging/partial-copy
  directory should follow the same shape: a distinct staging path (not the final
  destination), a marker file recording "in-progress since T," and a startup sweep
  that only cleans up entries clearly past a grace period — never on ambiguous
  absence.
- **Race between a background sync actor and a foreground relocate copy**: standard
  mitigation is quiesce-before-copy (signal the writer to stop, wait for idle, copy,
  resume/reconcile). **This repo already has the correct primitive for the Android
  side**: `GitWorktreeLocks.lockFor(shadowKey)` (§3 above). For the Web side,
  `HostDirectorySync`'s existing write-suppression/re-poll pattern (see git log hits
  `fix(sync): re-poll host path after own-write suppression clears` (`79724e1f`),
  `fix(sync): close retryStuckHostWrites' suppression-repoll gap` (`7e55c286`)) shows
  this exact class of race has already been hit and fixed once for the *existing*
  livesync feature — a new "link" mode extending `FolderSyncSettings`/
  `HostDirectorySync` must reuse this suppression/re-poll mechanism rather than
  re-deriving it, given it has already had at least two follow-up fixes
  (`ad0dc53bb7`, `7e55c28632`) to close gaps in the first attempt.

---

## 6. This repo's own storage-bug history — direct precedent

`git log --oneline --all` against the relevant files surfaces a dense history of
exactly this class of bug, which any relocate/link design must not reintroduce:

- **`e087959cb1` — "phantom conflict auto-resolve for underscore/encoded page
  titles" (2026-09-11, one commit before this research)**: `observeExternalFileChanges`
  derived a page title from a filename using an ad-hoc `.replace("_", " ")` instead of
  the canonical `FileUtils.decodeFileName` (the true inverse of
  `FileUtils.sanitizeFileName`). The mismatch made a lookup silently return null,
  which was interpreted as "page didn't exist locally," which silently discarded a
  real conflict with no dialog. **Direct precedent for requirements.md's stated rabbit
  hole**: "partial writes, symlinks-via-SAF-URIs, and encoding differences ... are
  exactly the kind of edge case that already caused a real bug in this codebase's
  path-handling." Any relocate integrity check that reconstructs page identity from a
  filename (rather than using the canonical encode/decode helpers) is at risk of the
  same class of silent-false-negative bug — verification-before-delete must use
  `FileUtils.sanitizeFileName`/`decodeFileName` consistently, never a bespoke
  string transform.
- **`project_observer_backoff_left_as_is` (memory, 2026-08-23)**: a real bug was found
  in `DatabaseWriteActor.processExecute`, which emitted its invalidation signal
  *before* the write actually landed (unlike every other write arm), letting a
  subscriber re-query the DB and read stale content. **Direct precedent for
  verification design**: a relocate's "verify destination" step must query the
  *actual post-write state* (file listing, hash, git object count) after the copy
  operation's own completion signal, never trust an actor's "done" signal as proof the
  underlying I/O is durably visible — this is the exact ordering bug class already
  found once in this codebase's write pipeline.
- **`stelekit_pages_backlink_count_migration_crash` (memory, 2026-07-18)**: a DB
  migration failure was silently swallowed into an infinite "Initializing…" screen
  with no user-facing error — indistinguishable from a real hang, diagnosable only via
  log files. **Direct precedent for observability design**: this project's stated
  observability requirement (log start/verification-result/completion of every
  relocate/link) must translate into a **visible** failure state in the UI, not just a
  log line — a relocate that silently hangs on a verification failure would repeat
  this exact bug class.
- **`da00bae141` "feat(web): local folder live sync"** and its two follow-on fixes
  (`dcf43a988a` "Epic 3.1-3.3 reconciliation pass (Critical Finding fix)",
  `ac0ee59e53` detekt cleanup) confirm requirements.md's Feasibility Risk that
  `FolderSyncSettings`/`HostDirectorySync` "was built for a fixed 'connect once'
  flow" — its commit history shows it needed a dedicated "reconciliation pass" fix
  after initial ship, i.e., generalizing it to a reconfigurable, user-invocable "link"
  action (this project's scope) should budget for a similar non-trivial
  reconciliation-correctness pass, not treat it as a thin UI wrapper over existing
  code.
- No hits for "corrupt" anywhere in `CHANGELOG.md` (checked directly) — i.e., no
  prior *data corruption* incident is on record for this codebase's git-sync or OPFS
  paths; the risk here is precedented for *sync/permission/race* bugs, not for actual
  on-disk corruption. Worth stating explicitly: this project would be the first
  feature in this codebase's history to introduce a bulk bytes-level copy operation
  over a live git object store, so the "no prior corruption incident" fact is not
  strong evidence of safety here — it reflects that no prior feature did this kind of
  operation at all.

---

## Summary of design implications to carry into Phase 3 planning

1. **Reuse `GitWorktreeLocks.lockFor(shadowKey)`** as the quiesce mechanism for any
   Android relocate/copy touching a git shadow worktree — do not build a second lock.
2. **Reuse `HostDirectorySync`'s `hostAccessStateFlow`/`queryHandlePermission`
   pattern** for Web "link" mode's permission-loss detection — do not build a second
   permission-polling mechanism.
3. **State explicitly that promoting `filesDir` to primary (non-cache) storage
   removes the last implicit safety net** (`allowBackup=false`, confirmed) for plain
   (non-git) graphs — Phase 3 should decide whether to require an export/backup
   affordance before shipping "app storage as primary for plain graphs," or to scope
   that combination out.
4. **Verification-before-delete must use `FileUtils.sanitizeFileName`/
   `decodeFileName`**, never a bespoke filename→title transform, per the `e087959cb1`
   precedent.
5. **A staging-directory + marker-file + startup-sweep pattern**, modeled directly on
   `GitShadowWorktree.sweepOrphans()`'s "ambiguous absence is never treated as
   staleness" rule, should back the relocate's partial-copy cleanup.
6. **Verification must re-query real post-copy state**, never trust a copy
   operation's own "done" callback, per the `DatabaseWriteActor` invalidation-ordering
   precedent.
7. **Failure/stuck states must be UI-visible**, not just logged, per the
   backlink-count-migration-crash precedent.
8. Treat OPFS write atomicity and DocumentFile subtree-write-completeness as
   **unverified-by-platform** — build the app's own verify step to catch both rather
   than assuming either guarantee exists.
