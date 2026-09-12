# Research: Pitfalls & Risks — Android Git SAF Shadow Worktree

Scope: known failure modes for a bidirectional shadow-tree ↔ SAF sync feeding
a real JGit working tree on Android. Each entry: pitfall, why it applies here
(codebase precedent or external source), severity, guardrail to carry into
the plan.

---

## 1. Codebase's own documented scars, applied to this feature

### 1.1 Uncaught coroutine `Throwable` kills the Android process

**Precedent**: `CLAUDE.md` "Uncaught coroutine Throwables kill the process on
Android" — an uncaught `OutOfMemoryError` (an `Error`, not `Exception`)
escaping any coroutine reaches Android's default uncaught-exception handler
and kills the process; this reproduces only on Android, never on desktop JVM.
Guarded today via `CoroutineExceptionHandler` on long-lived scopes (e.g.
`GraphLoader.parallelScope`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt:326-330`).

**Why it applies here**: A full shadow→SAF write-back after a merge, and a
full SAF→shadow resync before a git op, are exactly the kind of large,
allocation-heavy batch loops (thousands of small file reads/writes, JGit's
`RevWalk`/`DiffFormatter`/`WorkingTreeIterator` all holding tree state in
memory) that produced the OOM class of crash this rule exists for. Any new
`CoroutineScope` created for the shadow-sync pipeline (a `SyncActor`,
a background flush job, etc.) that does not attach a
`CoroutineExceptionHandler` reintroduces the exact bug class the rule is
there to prevent — and it will not reproduce in desktop-only local testing.

**Severity**: High (crash, not just data issue; documented as previously
causing "app keeps stopping" failures).

**Guardrail**: Any new long-lived scope hosting shadow-sync or git-op work
must attach a `CoroutineExceptionHandler` and surface failures as UI state,
per the existing `GraphLoader.parallelScope` pattern. Add a regression test
in the style of `LargeGraphWarmStartCrashTest` that runs the full
SAF→shadow→JGit-op→shadow→SAF cycle against an 8 000+ file synthetic tree
under a recording default uncaught-exception handler.

### 1.2 Graph-scale reads must be bounded — never O(graph)

**Precedent**: `CLAUDE.md` "Graph-scale reads must be paginated, projected,
or chunked — never O(graph)" — `PageRepository` has no unbounded
`getAllPages()` at all, by design, because a standing collector
re-materializes its entire result set per DB write burst, causing GC thrash
and OOM on an 8 000+ page graph.

**Why it applies here**: This constraint was scoped to *database* reads, but
the shadow-sync design introduces a structurally identical pattern at the
*filesystem* layer: `ShadowFileCache.syncFromSaf()`
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFileCache.kt:66-88`)
already iterates a full `fileModTimes` list per subdir per call — that's
bounded today because it only runs against `pages/`+`journals/` on a cadence
tied to indexing, not to every git operation. A shadow→SAF write-back that
walks JGit's full `DirCache`/working-tree status on every fetch/merge is the
filesystem analogue of the forbidden `getAllPages()` — O(graph) work
triggered synchronously on the git-sync hot path, potentially on every pull.

**Severity**: High on large graphs (UI hang / ANR risk during a git sync,
not a crash by itself, but likely to trip Android's "Application Not
Responding" watchdog if run on the main thread or block a foreground
operation).

**Guardrail**: Shadow→SAF write-back must operate off JGit's own diff (files
JGit reports as touched by the merge/checkout — `MergeResult`/`DirCache`
changed-paths, already partially modeled via `AndroidGitRepository.merge()`'s
`diffFormatter.scan(parentCommit.tree, headCommit.tree)`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt:244-249`),
never a full shadow-tree walk. SAF→shadow resync must stay chunked/batched
the way `ShadowFileCache.syncFromSaf` already is (one SAF cursor batch per
subdir, stale-only writes via mtime comparison) and must not be invoked more
often than the git-op cadence requires.

### 1.3 Repository Flow resilience — closed-DB / cancelled-scope guards

**Precedent**: `catchDbError()` / `asDbFlowList` / `asDbFlowOrNull`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DbFlowExtensions.kt`)
exist because `GraphManager.shutdown()`/`switchGraph()` closing the DB mid-
collection previously crashed the main thread with `IllegalStateException`.

**Why it applies here**: The equivalent hazard exists for the shadow tree
and the SAF tree URI: a graph switch or app backgrounding can tear down the
`ShadowFileCache`/JGit `Repository` (which itself holds open file handles
and possibly a `.git/index.lock`) mid-sync. Unlike the DB case there is no
`catchDbError()`-equivalent guard today for JGit operations racing a graph
switch — `AndroidGitRepository`'s `openGit(...).use { }` blocks are
synchronous and uninterruptible by a coroutine cancellation once JGit's
native `git.merge()`/`git.fetch()` call is in flight.

**Severity**: Medium-High (resource leak / corrupted `.git` state / stale
`index.lock` rather than an immediate crash, but the existing
`removeStaleLockFile()` 60-second heuristic
(`AndroidGitRepository.kt:392-410`) already shows this class of problem is
live in the current single-tree design and will get worse with a second
tree in the mix).

**Guardrail**: Design an explicit "graph is mid-git-op" guard so
`GraphManager.shutdown()`/`switchGraph()` either blocks briefly for an
in-flight git operation to reach a safe checkpoint or defers teardown; do not
let a shadow-tree JGit operation and a graph switch interleave uncontrolled.
Extend `catchDbError()`-style resilience thinking to the shadow-sync flows
that expose `Flow`s (e.g. sync-status/progress flows) so UI collectors don't
crash on a mid-sync teardown.

### 1.4 `rememberCoroutineScope` must not escape composition

**Precedent**: Compose cancels `rememberCoroutineScope()` scopes on
recomposition; any long-lived class holding one throws
`ForgottenCoroutineScopeException` on its next `launch`.

**Why it applies here**: `ConflictResolutionScreen` and any new
shadow-sync-status UI will be tempted to drive a `SyncActor`/`ShadowFlushActor`-
style class from a screen-scoped `rememberCoroutineScope()`. If the
shadow-mirror sync coordinator (which must plausibly outlive a single
screen — a background flush queued via `WorkManager`, per the existing
"WorkManager — periodic background git sync" dependency in
`kmp/build.gradle.kts:311-312`) is instantiated with a Compose-scoped
coroutine scope, it will die on the next recomposition/navigation.

**Severity**: Medium (crash on next `launch` after recomposition; well
understood and mechanically avoidable given the existing rule).

**Guardrail**: Any shadow-sync coordinator class owns its own
`CoroutineScope(SupervisorJob() + Dispatchers.Default)` internally, per the
existing `SomeManager` pattern in `CLAUDE.md`; UI only collects its exposed
`StateFlow`.

---

## 2. JGit-specific pitfalls

### 2.1 `Status`/`Add`/`Checkout` scan the entire working tree — no incremental status by default

**Verified** (WebSearch, corroborated by JGit's own `StatusCommand`
Javadoc lineage across versions): JGit's `StatusCommand` builds a
`WorkingTreeIterator` (`FileTreeIterator` by default) that walks the whole
working tree unless a custom `WorkingTreeIterator` is supplied via
`setWorkingTreeIt()`; there is no built-in incremental/watch-based status.
This mirrors native git's own behavior — full working-tree stat() on every
`status`/`add .`/`checkout` call, which becomes measurably slow past tens of
thousands of files.

**Why it applies here**: `AndroidGitRepository.status()` (`AndroidGitRepository.kt:141-164`)
and `stageSubdir()` (`AndroidGitRepository.kt:166-180`) both call
`git.status()`/`git.add()` unconditionally, and both would run against the
shadow tree. On an 8 000+ page graph (the documented scale reference,
`CLAUDE.md`'s `LargeGraphWarmStartCrashTest`), every commit/push cycle pays
a full `stat()` of every markdown file in the shadow tree — on Android's
typically slower storage (compared to desktop SSD) and without knowing
whether SAF's own indirection (`resolveSafToRealPath` note in the
requirements doc) adds further overhead. This runs on `PlatformDispatcher.IO`
already (good), but repeated on every sync cycle it's a real latency/battery
cost, and if ever accidentally invoked from a path that blocks the UI it's an
ANR risk.

**Severity**: Medium (performance/battery, not correctness — but compounds
with the other pitfalls below since it's the mechanism that makes "should we
sync now" checks expensive).

**Guardrail**: Scope every `stageSubdir()`/`status()` call with
`config.wikiSubdir`-style path filtering wherever possible (the existing
`cmd.addPath(config.wikiSubdir)` pattern already does this for `status()`);
avoid calling `status()` speculatively/on a timer — trigger git ops from
explicit user action or a debounced external-change signal, not polling.
Consider benchmarking one full `status()`/`add()` cycle against a synthetic
8 000-file shadow tree as part of validation (phase 4) rather than assuming
it is fast enough.

### 2.2 Known JGit CVE class: symlinks + case-insensitive filesystems → path traversal (CVE-2023-4759)

**Verified** (WebSearch/NVD): JGit ≤ 6.6.0 had a symlink-based path-traversal
/ RCE vulnerability on checkout/merge/pull/patch-apply when the destination
filesystem is case-insensitive; fixed in 6.6.1 / 6.7.0, with a backport in
**5.13.3.202401111512-r** — which is exactly the version this project
already pins for `org.eclipse.jgit.ssh.jsch` (see 2.3 below), and desktop/
Android core is on 7.3.0, both well past the fix.

**Why it applies here**: Android's internal `filesDir` storage (where the
shadow tree lives) is ext4/F2FS — case-sensitive — so this specific CVE's
precondition (case-insensitive destination FS) does not apply on-device.
Flagging as **verified-not-applicable** so it isn't re-litigated during
implementation, but note the shadow tree is still populated by *copying* SAF
content rather than by JGit checkout for the SAF→shadow direction — a
different code path than the one JGit's fix covers — so a hostile
`.md` file containing a symlink is not itself a JGit-checkout risk in that
direction, but is a risk for `PlatformFileSystem`'s own file-copy logic if
it ever preserves/dereferences symlinks from SAF-provided content (worth a
one-line check that shadow-copy read/write paths reject or resolve symlinks
safely).

**Severity**: Low as scoped (case-sensitive FS not present), but the
adjacent copy-path question is worth a explicit guardrail since it's cheap.

**Guardrail**: Confirm (in the plan/implementation) that `ShadowFileCache`'s
SAF→shadow copy path never creates or follows symlinks — it currently only
does `File.writeText`/`readSafFile()` (`ShadowFileCache.kt:66-88`), which is
inherently symlink-safe (plain content copy, not a filesystem-level
copy/link operation) — document this as the reason no explicit symlink
rejection is needed, rather than leaving it unstated.

### 2.3 Stale, mismatched JGit SSH module pin — pre-existing, will be touched by this project

**Verified** (`git log -S`, Maven Central query): Android's core JGit was
bumped from 5.13.3 → **7.3.0.202506031305-r** in the Bazel migration
(commit `8b4cb25af0`), matching Desktop
(`kmp/build.gradle.kts:169`/`322`). The `org.eclipse.jgit.ssh.jsch`
integration module used for SSH (`kmp/build.gradle.kts:325`) was **not**
bumped in that commit and remains pinned at **5.13.3.202401111512-r** — a
version almost two years older than the core artifact it's now loaded
alongside. This is not a hard technical necessity: Maven Central confirms a
matching `org.eclipse.jgit.ssh.jsch:7.3.0.202506031305-r` release exists
(`https://search.maven.org/solrsearch/select?q=g:org.eclipse.jgit+AND+a:org.eclipse.jgit.ssh.jsch`,
verified 2026-08-28) — the mismatch looks like a leftover of the Bazel
migration, not a deliberate Android-only pin. (JGit's own docs mark
`ssh.jsch` as deprecated in favor of `ssh.apache`/Apache MINA SSHD, which is
why Desktop uses `ssh.apache` — but Android uses `ssh.jsch` + the
mwiede/jsch fork specifically for ED25519/ECDSA/OpenSSH key support per the
existing code comment at `kmp/build.gradle.kts:328`, i.e. the *module choice*
is deliberate, only the *version* is stale.)

**Why it applies here**: This project's plan will directly modify
`AndroidGitRepository` (`resolveForJGit`, `openGit`, and every command
method) to point at the shadow tree instead of an unresolved `saf://` path.
Touching this file is the natural moment to also close the version gap,
since mixing a 7.3.0 core with a 5.13.3-compiled SSH integration module
risks subtle binary/API incompatibility in `JschConfigSessionFactory`/`FS`
surfaces that changed across two major JGit versions — a risk that gets
easier to introduce accidentally (e.g. by touching transport config code in
this same file) if left unaddressed.

**Severity**: Medium — not blocking (current pin apparently works today, no
reported incompatibility), but a latent risk this exact project increases
exposure to by editing the same file/surface, and a one-line low-cost fix.

**Guardrail**: Bump `org.eclipse.jgit.ssh.jsch` to
`7.3.0.202506031305-r` (matching core) as an early, low-risk task in this
project's implementation plan — not a separate fast-follow — since it's in
the same file this project must already modify, and verify Android SSH
clone/fetch/push still works with the mwiede/jsch fork afterward via the
existing SSH-path git tests.

### 2.4 File-mode / permission-bit churn from copy-based sync (`core.fileMode`)

**Not yet verified against this specific SAF provider** but a well-known
git/JGit class of issue: git's index tracks the executable bit per file
(`core.fileMode`), and any sync mechanism that recreates files via a
different code path than the original checkout (here: SAF `DocumentsContract`
copy → shadow `File.writeText`, and shadow → SAF write-back via
`PlatformFileSystem.writeFile`) risks producing files whose permission bits
don't match what's recorded in the git index, showing as spurious
"modified" entries on every `status()`/`add()` even when content is
identical. Markdown notes are not normally executable, so the practical risk
is low, but any future asset/attachment sync through the same shadow-tree
mechanism (scripts, shell snippets stored as attachments, etc.) would be
exposed.

**Severity**: Low today (markdown-only), but should be explicitly designed
against since the fix is nearly free.

**Guardrail**: Set `core.fileMode = false` on the shadow repository's config
at `init`/`clone` time, so file-mode differences introduced by the SAF copy
path never surface as false-positive diffs.

### 2.5 JGit `FS` auto-detection can shell out — verify it doesn't on Android

Not independently verified against JGit 7.x source in this pass (flagged as
an open verification item, not a confirmed finding): JGit's `FS.DETECTED`
historically probed for a system git binary and shell (`/bin/sh`) for some
auxiliary features (e.g. external merge/diff tool resolution, `.gitattributes`
filter execution). Android has no `/bin/sh`-compatible general-purpose shell
guaranteed present and no system git binary; if any JGit code path this
project exercises (directly or transitively via `Status`/`Add`/`Checkout`/
`Merge`) attempts process-spawning FS detection, it could throw, hang, or
silently degrade in a way that's easy to miss in emulator testing (which
often does have a shell) but fails on real hardware.

**Severity**: Unknown/low-probability but high-cost if hit (could manifest
as an intermittent, hard-to-repro failure specific to real devices).

**Guardrail**: During phase-4 validation, explicitly test the full
clone/fetch/merge/push/status cycle on a real device (not just an emulator,
which may have a more complete shell environment) before considering the
feature validated. If `FS` auto-detection issues surface, force
`FS.DETECTED` or a custom minimal `FS` implementation rather than debugging
per-symptom.

---

## 3. Android storage/lifecycle pitfalls

### 3.1 `filesDir` quota / low-storage devices

**Why it applies here**: The shadow tree duplicates the *entire* wiki
(markdown) plus a full `.git` history inside `context.filesDir`, which is
subject to whatever free space the device has — there's no OS-level quota
API guaranteeing headroom, and Android will reclaim app cache (not
`filesDir`) under storage pressure but will not proactively free
`filesDir` content. On an 8 000+ page graph, wiki content alone is likely
tens of MB of markdown; `.git` history is unbounded and grows every commit.
Requirements.md already flags this as a known constraint ("Storage is
constrained... a full growth-bounding solution can be a documented
fast-follow"); this research confirms no existing code in this codebase
currently bounds `.git` directory growth anywhere (Desktop's `JvmGitRepository`
operates on the user's own filesystem, so this problem is Android-only).

**Severity**: Medium (slow-building failure — first-clone or a large-history
graph could exhaust space over time, not immediately, so easy to miss in
short-lived manual testing).

**Guardrail**: Check available space (`StatFs`) before initiating a
shadow-tree clone/first-sync and surface a clear error rather than letting
JGit fail mid-write with a generic I/O exception; document a bounding
strategy (shallow clone depth, periodic `git gc`, or a hard size cap with
user-visible warning) even if full implementation is deferred, per the
requirements doc's explicit allowance for that.

### 3.2 Doze / App Standby killing background sync mid-operation

**Why it applies here**: The existing `WorkManager` periodic background git
sync dependency (`kmp/build.gradle.kts:311-312`) already exists, meaning
this pitfall is not new to this feature — but the shadow-mirror design adds
a *second* stateful operation (shadow↔SAF sync) that must complete
atomically relative to the git operation it brackets. If Doze/App Standby
suspends the process (or `WorkManager`'s execution window simply ends)
between "SAF→shadow synced" and "git op committed," or between "git op
committed" and "shadow→SAF written back," the shadow tree and SAF folder end
up in different states with no automatic reconciliation trigger until the
next sync attempt.

**Severity**: Medium — not silent data loss by itself (shadow tree still
has the git-committed state), but a source of "why didn't my pull show up in
my files" user confusion if shadow→SAF write-back doesn't reliably resume.

**Guardrail**: Reuse `WriteBehindQueue`'s pattern
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/WriteBehindQueue.kt`)
— a persistent, file-backed, append-only queue of pending flushes that
survives process death — for the shadow→SAF write-back direction, so a
`WorkManager` retry (or app foreground) resumes exactly where the process
left off rather than needing to detect and diff the whole tree again. Note:
`WriteBehindQueue.dequeue()` itself rewrites its backing file with a full
`writeText()` (not atomic, `WriteBehindQueue.kt:31-40`) — the new shadow↔SAF
queue implementation should improve on this with temp-file-then-rename
rather than copy this specific detail.

### 3.3 Revoked/orphaned SAF URI permission

**Why it applies here**: SAF persistable URI permissions can be revoked by
the user (deleting/moving the folder externally), by the OS on certain
Android version upgrades, or if the user clears the app's storage without
uninstalling. Today, `ShadowFileCache.deleteAll()`
(`ShadowFileCache.kt:162-165`) is explicitly documented as "called on SAF
permission revoke" — so the *shadow-tree-only* case is already handled.
This project adds a `.git` directory living in that same shadow root; a
revoke must also account for: (a) the `.git` history representing commits
that may include content no longer reachable/verifiable in the (now
inaccessible) SAF folder, and (b) any pending `WriteBehindQueue`-style
write-back entries that can never be flushed once the SAF permission is
gone.

**Severity**: Medium (orphaned local `.git` history isn't data loss of the
SAF-side content itself, but is wasted storage indefinitely if
`deleteAll()` isn't extended to also clear it, and a confusing state if the
user re-grants access to the *same* folder later expecting sync to resume).

**Guardrail**: Extend the existing SAF-permission-revoke handling to also
tear down (or explicitly quarantine, pending a user decision) the `.git`
directory and any queued write-back entries, not just the plain shadow
markdown cache. Decide explicitly (and document in the plan) what happens
if the user re-grants access to the same folder: does the shadow tree +
`.git` history get reused (risking staleness if the SAF content changed
while access was revoked) or always rebuilt from scratch.

---

## 4. Data-loss risk classes specific to bidirectional sync

### 4.1 Last-writer-wins clobbering a concurrent SAF-side edit during write-back

**Why it applies here**: This is the requirements doc's own explicit
constraint #4 ("Must not silently lose user edits made directly to the SAF
folder... that race against a git pull/merge in flight"). Concretely: if
`syncFromSaf()` runs, then a widget/share-target/other-app writes directly
to a SAF file, then shadow→SAF write-back for a *different, unrelated* file
completes and the write-back logic re-derives "what changed" from a stale
snapshot rather than from JGit's actual diff, an unrelated write-back could
overwrite the concurrent edit if the write-back path re-serializes more than
just the files JGit reports as touched.

**Severity**: High (directly named as a hard constraint in requirements.md;
silent data loss of user content is the worst failure mode this feature can
have).

**Guardrail**: Shadow→SAF write-back must be scoped to exactly the file set
JGit reports as changed by the merge/checkout (see 1.2's guardrail — reuse
the diff, don't re-walk/re-derive), and must re-check the SAF file's mtime
immediately before writing — if the SAF mtime is newer than the mtime
`syncFromSaf()` last observed for that file, treat it as a conflict (route
through the existing `DiskConflict`/`ConflictResolutionScreen` machinery)
rather than blindly overwriting.

### 4.2 Partial writes on process death — needs atomicity/idempotency

**Why it applies here**: Both directions of sync (SAF→shadow,
shadow→SAF) involve many small file writes; process death (OOM kill, Doze
kill, user force-stop) mid-batch is a normal Android occurrence, not an edge
case. `ShadowFileCache.update()`/`syncFromSaf()` currently use plain
`File.writeText()` — not atomic (no temp-file-then-rename) — so a kill
mid-write can leave a truncated/partial shadow file. For the SAF→shadow
direction this is low-severity (shadow is a derived cache; `invalidateStale`
via mtime/size mismatch will eventually catch a truncated file since its
size won't match SAF's reported size — `ShadowFileCache.kt:143-155`). For
the *new* shadow→SAF write-back direction, the same non-atomicity now risks
corrupting the user's actual SAF-side file, which has no equivalent
self-healing check today.

**Severity**: High for the shadow→SAF direction specifically (this is new
code with no existing self-healing mechanism, writing to the user's
source-of-truth files).

**Guardrail**: Shadow→SAF write-back must be atomic per file — write to a
temp location first, verify size/content, then perform the SAF-side
replace (SAF doesn't support a filesystem `rename()` across documents the
way POSIX does, so this likely means: write the full new content to a
temp SAF document or hold it in memory, then use `DocumentsContract` to
replace content only after the write fully succeeds — verify against
`PlatformFileSystem`'s existing `writeFile`/`writeFileBytes` primitives for
what atomicity guarantees, if any, the underlying `ContentResolver.openOutputStream()`
call already provides, since SAF's own semantics may already truncate-on-open).
Track in-flight write-back entries in a persistent queue (per 3.2) so a
partial write is retried/verified rather than assumed complete.

### 4.3 Silent shadow/SAF drift if `syncFromSaf()` isn't called at exactly the right points

**Why it applies here**: `ShadowFileCache`'s doc comment states its
invariant explicitly: "SAF is the only write target; shadow is a read
cache derived from SAF" (`ShadowFileCache.kt:15-20`) — a *read-only,
one-directional* invariant that this project's own requirements doc (open
question #1) flags as needing to become bidirectional. Every existing
caller of `ShadowFileCache` (background indexing, `SafChangeDetector`,
`WriteBehindQueue`) was written and tested under that one-directional
assumption. If a git operation runs against a shadow tree that wasn't
freshly re-synced from SAF immediately before, JGit could commit/diff
against stale content, and if that stale-derived commit is then pushed, a
concurrent SAF edit is permanently lost from the remote's perspective too —
this is a strictly worse variant of 4.1 because it's invisible until the
next `git log`/`git diff` review, not caught by any UI conflict flow.

**Severity**: High (silent, delayed-discovery data loss — the worst kind).

**Guardrail**: Make "shadow is fresh" a precondition JGit operations
actively verify, not just a documented calling convention. Concretely:
stamp the shadow tree with a version/hash marker (e.g. a manifest file of
per-file SAF mtimes, or a single aggregate hash) written by `syncFromSaf()`
and checked immediately before any JGit working-tree operation; if stale,
force a re-sync rather than proceeding. This turns "was resync called at
the right point" from a code-review-only invariant into a runtime-checked
one — directly answering requirements.md open question #1.

### 4.4 Duplicate/orphaned `.git` directories on graph deletion/re-setup

**Why it applies here**: `ShadowFileCache.graphIdFor()`
(`ShadowFileCache.kt:38-39`) derives a shadow directory name from the SAF
tree document ID. If a user deletes a graph from the app and re-adds the
*same* SAF folder (or the SAF provider issues a new tree doc ID for what is,
to the user, "the same folder" — e.g. after a permission re-grant flow),
nothing in the current `ShadowFileCache`/`AndroidGitRepository` pairing
guarantees the old shadow+`.git` directory for the abandoned graph ID gets
cleaned up. Today this is low-cost (shadow is just a read cache, cheap to
regenerate/orphan); once a full `.git` history lives there, an orphaned
directory is a permanent, silently-growing storage leak with no UI surface
showing it exists.

**Severity**: Medium (storage leak, not data loss, but compounds with 3.1).

**Guardrail**: Graph deletion flow must explicitly delete the shadow root
(`context.filesDir/graphs/$graphId/shadow`, including `.git`) for that
`graphId`, not rely on it being incidentally cleaned up. Consider a startup
sweep that reconciles `context.filesDir/graphs/*` against the set of
currently-configured graphs and reports (or removes) orphans, so a user
who never explicitly "deletes" a graph but instead just stops configuring
it (e.g. revokes SAF access per 3.3) doesn't accumulate leaked `.git`
histories indefinitely.

---

## 5. Security / data-sensitivity pitfalls

### 5.1 Backup/restore exposure of a full private-notes copy in app-private storage

**Fact-checked** (`androidApp/src/main/AndroidManifest.xml:26`):
`android:allowBackup="false"` is already set on the `<application>` element,
with no `android:fullBackupContent` or `android:dataExtractionRules`
override present. `allowBackup="false"` disables both classic `adb backup`
and Android's Auto Backup for Apps (cloud) for the entire app data
directory, including `filesDir` — so the shadow tree (and its `.git`
history) inherits this protection automatically; no new manifest change is
needed for this specific concern.

**Why it's still worth naming explicitly**: This project changes what lives
in `filesDir` from "a read-only cache of the user's notes" to "a read-only
cache **plus a full bidirectionally-synced git working tree and history**
of the user's notes" — i.e., it doesn't just duplicate current content, it
now also retains *historical* content (old commits) that may include text
the user has since deleted from their live notes. `allowBackup="false"`
already prevents this from leaving the device via Android's backup
mechanisms, but it does not prevent on-device exposure (e.g. `adb shell` on
a rooted/debuggable device, or malware with root) to a strictly larger
corpus of the user's private writing than existed before this feature,
including deleted-but-still-committed content.

**Severity**: Low given the existing `allowBackup="false"` fact-check
(primary risk vector already closed), but the "shadow tree now holds git
history including deleted content" fact should be stated explicitly in the
plan rather than left implicit, since it's a genuine (if secondary) change
to the app's data-sensitivity profile.

**Guardrail**: No manifest change required (verified `allowBackup="false"`
covers this). Document in the plan, as an explicit user-facing note if
appropriate, that enabling Git Sync via the shadow-mirror path retains git
history (including old versions of deleted content) in app-private storage
for as long as the graph is configured with Git Sync, distinct from the
plain shadow-cache's "read cache of current SAF content only" model.

---

## 6. Design requirements / guardrails to carry into the plan

Consolidated from the above, phrased as concrete requirements the
implementation plan (phase 3) must address:

1. **Shadow→SAF write-back is atomic per file.** Verify what atomicity
   `PlatformFileSystem.writeFile`/`writeFileBytes` already provide over SAF;
   if not sufficient, write-then-verify-then-replace, never truncate-then-
   stream-in-place for the user's SAF-side file. (§4.2)
2. **Write-back is scoped to JGit's own reported diff, never a full
   shadow-tree walk or a broader "everything that looks different"
   heuristic.** (§1.2, §4.1)
3. **Every JGit working-tree operation checks a shadow-freshness marker
   (version/hash/manifest) before proceeding, and forces a re-sync if
   stale — this is a runtime-enforced precondition, not a documented
   calling convention.** (§4.3, directly resolves requirements.md open
   question #1)
4. **A concurrent-SAF-write check immediately precedes any shadow→SAF
   overwrite** (re-check mtime right before writing) and routes detected
   conflicts through the existing `DiskConflict`/`ConflictResolutionScreen`
   machinery rather than silently overwriting. (§4.1)
5. **Pending write-back operations are tracked in a crash-safe, resumable
   queue** (extend the `WriteBehindQueue` pattern, fixing its own
   non-atomic persistence rather than copying it as-is) so a Doze kill or
   process death mid-flush is recoverable, not silently dropped. (§3.2,
   §4.2)
6. **Graph deletion and SAF-permission-revoke handling both explicitly tear
   down the shadow root including `.git`**, not just the plain markdown
   shadow cache; add a startup orphan sweep as a backstop. (§3.3, §4.4)
7. **Every new long-lived `CoroutineScope` introduced for shadow-sync work
   owns its scope internally (never a `rememberCoroutineScope()`) and
   attaches a `CoroutineExceptionHandler`**, per existing repo convention.
   (§1.1, §1.4)
8. **Shadow-tree JGit operations are scoped/path-filtered wherever the JGit
   API allows** (as `status()` already does via `config.wikiSubdir`) and are
   never invoked on a polling/speculative cadence — only on explicit user
   action or a debounced change signal — to bound the cost of JGit's
   full-working-tree scan behavior on an 8 000+ file graph. (§2.1)
9. **`core.fileMode = false` is set on shadow-tree repos** to prevent
   permission-bit churn from the SAF-copy code path from appearing as
   spurious diffs. (§2.4)
10. **Bump `org.eclipse.jgit.ssh.jsch` to match core (7.3.0.202506031305-r)**
    as part of this project's implementation, since it touches the same
    file/surface and the mismatch is a pre-existing, easily-fixed latent
    risk. (§2.3)
11. **Storage-space check (`StatFs`) before first shadow-tree clone**, with
    a clear user-facing error on insufficient space rather than a raw JGit
    I/O exception; document (even if deferred) a `.git` growth-bounding
    strategy. (§3.1)
12. **Real-device validation (not emulator-only) for the full
    clone/fetch/merge/push/status cycle** before considering the feature
    validated, to catch any JGit `FS`-detection-related surprise that an
    emulator's more complete shell environment could mask. (§2.5)
13. **State explicitly in the plan that the shadow tree now retains git
    history (including deleted content) in app-private storage** — no
    manifest change needed (`allowBackup="false"` already covers backup
    exposure) but the data-sensitivity change should be documented, not
    left implicit. (§5.1)

---

## Highest-severity risk

**§4.3 — silent shadow/SAF drift causing a stale-content commit that gets
pushed and permanently overwrites a concurrent SAF-side edit at the
remote.** This is the one failure mode in this document that is both (a)
explicitly named as a hard constraint in requirements.md and (b) invisible
at the moment it happens — unlike a crash (§1.1) or an ANR (§2.1), which are
immediately obvious, a stale-shadow commit looks like a completely normal,
successful sync from the user's perspective, and the lost edit is only
discoverable later via manual git history inspection. Guardrail #3 (runtime-
checked shadow-freshness marker) is the single highest-leverage design
requirement in this document.
