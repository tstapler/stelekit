# Architecture Research — Git Integration for SteleKit KMP

_Research date: 2025-05-02_

---

## 1. Background Sync in KMP

### The Core Problem

Android and iOS have fundamentally different background execution models. KMP provides shared business logic but cannot unify the platform scheduling APIs. The correct pattern is:

```
commonMain
  └─ GitSyncScheduler (expect interface)
androidMain
  └─ GitSyncScheduler actual: WorkManager
iosMain
  └─ GitSyncScheduler actual: BGTaskScheduler
jvmMain (Desktop)
  └─ GitSyncScheduler actual: CoroutineScope periodic timer
```

### Android: WorkManager

**Recommended choice for Android background git sync.**

```kotlin
// AndroidGitSyncWorker.kt (androidMain)
class GitSyncWorker(context: Context, params: WorkerParameters) 
    : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val result = gitSyncService.syncAll()
        return result.fold(
            ifLeft = { Result.retry() },
            ifRight = { Result.success() }
        )
    }
}

// Schedule periodic work
val syncRequest = PeriodicWorkRequestBuilder<GitSyncWorker>(
    repeatInterval = 5,
    repeatIntervalTimeUnit = TimeUnit.MINUTES
)
    .setConstraints(Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
    .build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "git_sync",
    ExistingPeriodicWorkPolicy.KEEP,
    syncRequest
)
```

**Why WorkManager over foreground service:**
- Respects Doze mode and App Standby automatically
- Survives process death and device reboot
- Network constraint prevents battery drain from offline retry loops
- No need for persistent notification (foreground service requirement)

**WorkManager minimum period:** 15 minutes on Android (system enforced). If the user sets a 5-minute interval, use an in-process coroutine timer when the app is in the foreground, and WorkManager for background.

### iOS: BGTaskScheduler

**BGProcessingTask** (for longer sync operations, ~minutes) vs **BGAppRefreshTask** (for short checks, ~30 seconds).

For git sync, `BGProcessingTask` is appropriate since fetch+merge can take multiple seconds.

```swift
// In AppDelegate (iosMain platform code)
BGTaskScheduler.shared.register(
    forTaskWithIdentifier: "dev.stapler.stelekit.gitsync",
    using: nil
) { task in
    task.expirationHandler = { task.setTaskCompleted(success: false) }
    // Call shared KMP sync logic
    sharedGitSyncService.syncAll { success in
        task.setTaskCompleted(success: success)
        // Re-schedule for next run
        scheduleNextSync()
    }
}
```

**iOS constraints:**
- iOS controls actual execution timing; the developer cannot guarantee frequency
- The system may not run background tasks if the device is in Low Power Mode
- Background tasks have a finite time budget (~1-2 minutes for processing tasks)
- Must re-schedule after each execution (does not automatically repeat)

**Practical implication for SteleKit:** On iOS, background sync is best-effort. The app must sync on foreground launch (FR-2.3) as the primary sync point, with background sync as a supplementary mechanism.

### Desktop JVM: Coroutine Supervisor Scope

No OS scheduling API needed. Use a long-running coroutine with periodic delay:

```kotlin
// DesktopGitSyncScheduler.kt (jvmMain)
class DesktopGitSyncScheduler(
    private val syncService: GitSyncService,
    private val intervalMinutes: Long = 5
) {
    private val scope = CoroutineScope(
        SupervisorJob() + PlatformDispatcher.IO
    )
    
    fun start() {
        scope.launch {
            while (isActive) {
                delay(intervalMinutes.minutes)
                syncService.syncAll()
                    .onLeft { error -> logger.warn("Sync failed: ${error.message}") }
            }
        }
    }
    
    fun stop() { scope.cancel() }
}
```

**Note:** Never use `rememberCoroutineScope()` for this — the scheduler must outlive any composable. It belongs in GraphManager alongside the graph's CoroutineScope, per SteleKit's existing pattern.

### Shared KMP Interface

```kotlin
// commonMain
interface GitSyncScheduler {
    fun startPeriodicSync(intervalMinutes: Int)
    fun stopPeriodicSync()
    fun triggerImmediateSync(): Flow<SyncProgress>
}

// GitSyncService.kt (commonMain) — used by all platform schedulers
class GitSyncService(
    private val repo: GitRepository,      // expect/actual
    private val graphWriter: GraphWriter,
    private val editingState: EditingState
) {
    suspend fun syncAll(): Either<DomainError.GitError, SyncResult> = either {
        ensureNotEditing().bind()          // FR-3.3
        val localChanges = commitLocalChanges().bind()   // FR-3.4
        val fetchResult = repo.fetch().bind()             // FR-2.1
        if (fetchResult.hasRemoteChanges) {
            val mergeResult = repo.merge().bind()         // FR-2.1
            if (mergeResult.hasConflicts) {
                raise(DomainError.GitError.MergeConflict(mergeResult.conflicts))
            }
            reloadChangedFiles(mergeResult.changedFiles).bind()
        }
        repo.push().bind()                                // FR-2.2
        SyncResult.Success(localChanges, fetchResult)
    }
}
```

---

## 2. Conflict Detection and Three-Way Merge at the Block Level

### The Challenge

Git operates on lines of text. Markdown-based outliners like SteleKit operate on logical blocks (paragraphs, headings, bullets, tasks). A git merge conflict in a `.md` file produces line-level conflict markers. SteleKit's block tree is built from those lines. The question is: how do you map conflict hunks to blocks?

### Approach A: File-Level Conflicts → Block Resolution Screen

The simplest approach that aligns with git's model:

1. After `git merge` produces a conflict, read the conflicted file as raw text
2. Parse conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) to identify conflict regions
3. Map each conflict region to the block(s) it spans using SteleKit's parser
4. Present a side-by-side diff at the block level (not line level)

```
Raw conflict:
<<<<<<< HEAD
- TODO: finish report
- TODO: call Alice
=======
- TODO: finish report
- TODO: call Bob
>>>>>>> origin/main

Mapped to blocks:
  Block "TODO: call Alice" (local) vs Block "TODO: call Bob" (remote)
```

**Parser note:** The SteleKit outliner already parses Markdown into blocks. The conflict resolver can use the same parser on the `HEAD` and `FETCH_HEAD` versions of the file to produce two block trees, then diff them at the block level using `kotlin-multiplatform-diff`.

### Approach B: Pre-merge Semantic Diff

Before running `git merge`, compute a semantic diff at the block level:
1. Parse current HEAD file into block tree
2. Parse FETCH_HEAD file into block tree
3. Use diff-at-block-level to find changed blocks
4. Present diff pre-merge for user approval

**Trade-off:** More complex, but avoids the conflict marker injection problem entirely. Closer to what Notion/Linear do for collaborative editing.

### Recommendation

**Approach A** for the first version — it aligns with git semantics, requires less novel code, and handles the actual conflict representation. The block-level display is an enhancement over raw line-level diff.

### Block-Level Diff with `kotlin-multiplatform-diff`

**Library:** https://github.com/petertrr/kotlin-multiplatform-diff  
**Version:** 1.3.0 (December 2025)  
**Targets:** JVM, JS, WebAssembly, Native (all KMP targets)  
**License:** Apache 2.0  
**Based on:** java-diff-utils 4.15  
**Algorithms:** Myers diff (default), HistogramDiff  

```kotlin
// Diff at block level using kotlin-multiplatform-diff
import io.github.petertrr.diffutils.diff

fun diffBlocks(localBlocks: List<Block>, remoteBlocks: List<Block>): Patch<Block> {
    return diff(localBlocks, remoteBlocks) { a, b -> a.content == b.content }
}
```

**Alternative:** `dev.gitlive:kotlin-diff-utils` (GitLive fork of java-diff-utils, pure Kotlin). Version 4.1.4 — last updated 2019, lower maintenance activity. Prefer `petertrr/kotlin-multiplatform-diff` (v1.3.0, active).

---

## 3. Subdirectory Git Repos

SteleKit's requirement: the wiki lives in a **subdirectory** of a git repo (e.g., `~/repos/my-notes/wiki/`). The graph root is `wiki/`, not the repo root.

### Option A: No Sparse Checkout — Just Open the Subdirectory

**Simplest approach.** Clone the full repo. Pass the subdirectory path as the wiki root to `GraphLoader`. Git operations (`fetch`, `merge`, `push`) are run at the repo root level.

```kotlin
data class GraphConfig(
    val repoRoot: Path,        // e.g., ~/repos/my-notes
    val wikiSubdir: String,    // e.g., "wiki"
    val wikiRoot: Path get() = repoRoot.resolve(wikiSubdir)
)
```

**GraphLoader alignment:** `GraphLoader` already accepts a root path. No changes needed — just configure it to point to `wikiRoot` instead of the repo root.

**Commit scope:** When committing local changes, stage only files under `wikiSubdir`:
```bash
git -C <repoRoot> add <wikiSubdir>/
git -C <repoRoot> commit -m "..."
```

**Pros:** Simple. No git feature dependencies. Works with all git versions.  
**Cons:** Non-wiki files in the repo are cloned (may be large for mixed repos). Status checks include all repo files.

### Option B: Sparse Checkout

Git sparse checkout populates only the specified directories in the working tree:

```bash
git clone --no-checkout <url> <repoRoot>
git -C <repoRoot> sparse-checkout set <wikiSubdir>
git -C <repoRoot> checkout
```

**Cone mode** (Git 2.27+) is dramatically faster for large repos. It limits to entire directories (not file globs), making pattern matching O(n) instead of O(n²).

**JGit sparse checkout support:** JGit has limited native sparse checkout support. The `git sparse-checkout` command is not fully implemented in JGit as of 7.x. Sparse checkout via JGit would require shelling out or manipulating `.git/config` and `.git/info/sparse-checkout` directly.

**Recommendation for SteleKit:** Use **Option A** (no sparse checkout) for the first version. The use case is a personal wiki, not a giant monorepo. If the repo has thousands of non-wiki files, document sparse checkout as an advanced configuration option.

### Option C: Partial Clone

`git clone --filter=blob:none` defers downloading file blobs until needed. Only metadata is cloned initially. This can reduce clone time for large repos significantly.

**Relevance:** If the git repo contains large binary files (images, PDFs) outside the wiki, partial clone + sparse checkout together would be valuable. Not needed for a wiki-focused repo.

---

## 4. Compose Multiplatform Diff UI

### Library Recommendation: `kotlin-multiplatform-diff`

**GitHub:** https://github.com/petertrr/kotlin-multiplatform-diff  
**Version:** 1.3.0 (December 2025)  
**Supports:** JVM 1.8+, JS (browser + Node.js), WebAssembly, all Native targets including iOS

The library computes diffs (insertion/deletion operations) between two sequences. It does NOT render UI — the Compose rendering is SteleKit's responsibility.

### Compose Rendering Pattern

Use `AnnotatedString` with `SpanStyle` to highlight changes:

```kotlin
@Composable
fun DiffView(
    localLines: List<String>,
    remoteLines: List<String>
) {
    val patch = remember(localLines, remoteLines) {
        diff(localLines, remoteLines)
    }
    
    Row {
        // Left column: local version
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(buildAnnotatedDiff(localLines, patch, side = LOCAL)) { line ->
                DiffLine(line)
            }
        }
        // Right column: remote version
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(buildAnnotatedDiff(remoteLines, patch, side = REMOTE)) { line ->
                DiffLine(line)
            }
        }
    }
}

@Composable
fun DiffLine(line: DiffAnnotatedLine) {
    Text(
        text = line.content,
        modifier = Modifier.background(
            when (line.type) {
                ADDED -> Color(0xFF1B4332).copy(alpha = 0.3f)
                REMOVED -> Color(0xFF7B2D30).copy(alpha = 0.3f)
                UNCHANGED -> Color.Transparent
            }
        )
    )
}
```

### Synchronizing Scroll Position

For side-by-side diff, the two `LazyColumn` instances need synchronized scroll. Use `LazyListState` with a custom `derivedStateOf` bridge:

```kotlin
val localScrollState = rememberLazyListState()
val remoteScrollState = rememberLazyListState()

LaunchedEffect(localScrollState.firstVisibleItemIndex) {
    remoteScrollState.scrollToItem(
        localScrollState.firstVisibleItemIndex,
        localScrollState.firstVisibleItemScrollOffset
    )
}
```

### Conflict Resolution Controls per Chunk

For each conflict chunk, render accept-local / accept-remote / edit-manually controls:

```kotlin
@Composable
fun ConflictChunk(
    conflict: ConflictHunk,
    onAcceptLocal: () -> Unit,
    onAcceptRemote: () -> Unit,
    onEdit: () -> Unit
) {
    Column {
        Row {
            Button(onClick = onAcceptLocal) { Text("Accept Local") }
            Button(onClick = onAcceptRemote) { Text("Accept Remote") }
            OutlinedButton(onClick = onEdit) { Text("Edit") }
        }
        DiffChunkView(conflict.localLines, conflict.remoteLines)
    }
}
```

### Alternative Libraries

- **`dev.gitlive:kotlin-diff-utils` (GitLive):** Pure Kotlin fork of java-diff-utils. Version 4.1.4, last updated 2019 — less maintained than `petertrr`. Uses Myers and Histogram algorithms. Avoid due to age.
- **`com.github.andrewbailey:difference`:** Kotlin Multiplatform (JVM, JS, Native). Simpler API, list-based only. Limited to insert/delete, no replace. Could work for block-level diff.

**Recommendation:** Use `io.github.petertrr:kotlin-multiplatform-diff:1.3.0`. It is the most actively maintained true-KMP diff library with full platform coverage.

---

## 5. KMP Coroutine Architecture for GitSyncService

### Integrating with Existing SteleKit Architecture

The `GitSyncService` should be a peer of `GraphLoader` and `GraphWriter`, owned by `GraphManager`:

```
GraphManager
  ├── GraphLoader        (existing)
  ├── GraphWriter        (existing)
  ├── RepositorySet      (existing)
  ├── DatabaseWriteActor (existing)
  └── GitSyncService     (new)
       ├── GitRepository      (expect/actual)
       ├── GitSyncScheduler   (expect/actual)
       └── ConflictResolver   (commonMain)
```

### Arrow Either Integration

Follow the existing SteleKit pattern exactly:

```kotlin
// DomainError additions (commonMain)
sealed class DomainError {
    // existing subclasses...
    
    sealed class GitError : DomainError() {
        data class CloneFailed(val reason: String) : GitError()
        data class FetchFailed(val reason: String) : GitError()
        data class MergeConflict(val conflicts: List<ConflictFile>) : GitError()
        data class PushFailed(val reason: String) : GitError()
        data class AuthFailed(val reason: String) : GitError()
        data object NotAGitRepo : GitError()
        data object Offline : GitError()
    }
}
```

```kotlin
// GitSyncService.kt (commonMain)
class GitSyncService(
    private val repo: GitRepository,
    private val graphLoader: GraphLoader,
    private val graphWriter: GraphWriter,
    private val editLock: EditLock
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    suspend fun sync(): Either<DomainError.GitError, SyncResult> = 
        withContext(PlatformDispatcher.IO) {
            either {
                // 1. Check network availability
                ensure(networkMonitor.isOnline) { DomainError.GitError.Offline }
                
                // 2. Wait for edit lock to clear (FR-3.3)
                editLock.awaitIdle()
                
                // 3. Commit local changes
                val localCommit = commitLocalChanges().bind()
                
                // 4. Fetch remote
                _syncState.value = SyncState.Fetching
                val fetchResult = repo.fetch().bind()
                
                // 5. Check for conflicts before merge
                if (fetchResult.hasRemoteChanges) {
                    _syncState.value = SyncState.Merging
                    val mergeResult = repo.merge().bind()
                    
                    ensure(!mergeResult.hasConflicts) { 
                        DomainError.GitError.MergeConflict(mergeResult.conflicts) 
                    }
                    
                    // 6. Reload changed files into repositories
                    graphLoader.reloadFiles(mergeResult.changedFiles).bind()
                }
                
                // 7. Push local commits
                if (localCommit.hasChanges) {
                    _syncState.value = SyncState.Pushing
                    repo.push().bind()
                }
                
                _syncState.value = SyncState.Idle
                SyncResult(localCommit, fetchResult)
            }
        }
}
```

### Dispatcher Usage

Follow SteleKit's existing dispatcher matrix:

| Operation | Dispatcher |
|---|---|
| Git fetch / push / merge (network I/O) | `PlatformDispatcher.IO` |
| File read/write during commit | `PlatformDispatcher.IO` |
| Conflict file parsing | `PlatformDispatcher.Default` (CPU) |
| DB writes after file reload | `PlatformDispatcher.DB` via `DatabaseWriteActor` |

### EditLock Design

```kotlin
// commonMain — wraps the existing editing state
class EditLock {
    private val _editingCount = MutableStateFlow(0)
    
    fun beginEdit() { _editingCount.update { it + 1 } }
    fun endEdit() { _editingCount.update { (it - 1).coerceAtLeast(0) } }
    
    // Suspend until no blocks are in edit mode
    suspend fun awaitIdle() {
        _editingCount.first { it == 0 }
    }
    
    val isEditing: StateFlow<Boolean> = 
        _editingCount.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)
}
```

`BlockEditor` composable calls `editLock.beginEdit()` on focus and `editLock.endEdit()` on blur (or after the debounced save in `BlockStateManager`).

### Conflict State Machine

```kotlin
sealed class SyncState {
    object Idle : SyncState()
    object Fetching : SyncState()
    object Merging : SyncState()
    object Pushing : SyncState()
    data class ConflictPending(val conflicts: List<ConflictFile>) : SyncState()
    data class Error(val error: DomainError.GitError) : SyncState()
    object Success : SyncState()
}
```

The `StelekitViewModel` observes `syncState` and routes to the conflict resolution screen when `ConflictPending` is emitted.

---

## 6. Open Questions / Unresolved Items

- **UNRESOLVED:** How does `GraphLoader.externalFileChanges` (the file watcher SharedFlow) interact with a git merge rewriting multiple files? Need to ensure the file watcher events triggered by a git merge are suppressed or coalesced, not processed as "external edits". See pitfalls.md.
- **UNRESOLVED:** JGit's `git merge` API — does it expose the conflict file list programmatically, or must we parse `.git/MERGE_HEAD` and `git status` output?
- **UNRESOLVED:** kgit2's API surface for merge operations — needs hands-on evaluation.
- **UNRESOLVED:** How to handle a merge that succeeds but produces a file that can no longer be parsed by the SteleKit Markdown parser (e.g., deeply nested conflict markers from a previous unresolved merge). Need a parser fallback mode.

---

## Sources

- [kotlin-multiplatform-diff](https://github.com/petertrr/kotlin-multiplatform-diff)
- [Background Sync KMP — WorkManager + iOS](https://medium.com/@ignatiah.x/background-sync-in-kotlin-multiplatform-workmanager-android-background-tasks-ios-1f92ad56d84b)
- [Cross-Platform Background Sync with KMP](https://medium.com/@kmpbits/sleeping-but-working-cross-platform-background-sync-with-kmp-70811e1dbd90)
- [KMP WorkManager: Enterprise-Grade Background Tasks](https://dev.to/brewkits/kmp-workmanager-enterprise-grade-background-tasks-for-kotlin-multiplatform-3cl2)
- [Arrow — Working with Typed Errors](https://arrow-kt.io/learn/typed-errors/working-with-typed-errors/)
- [Arrow — Either and Ior](https://arrow-kt.io/learn/typed-errors/wrappers/either-and-ior/)
- [Git Sparse Checkout Documentation](https://git-scm.com/docs/git-sparse-checkout)
- [GitHub Blog — Bring Your Monorepo Down to Size](https://github.blog/open-source/git/bring-your-monorepo-down-to-size-with-sparse-checkout/)
- [GitLive kotlin-diff-utils](https://github.com/GitLiveApp/kotlin-diff-utils)
- [BGTaskScheduler Apple Documentation](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler)
