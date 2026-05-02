# Git Integration — Implementation Plan

_Plan date: 2026-05-02_
_Based on: requirements.md, research/{stack,features,architecture,pitfalls}.md_

---

## 1. Technology Decisions

### 1.1 Git Library: JGit on JVM (Desktop + Android), kgit2 on iOS

| Platform | Library | Version | Notes |
|----------|---------|---------|-------|
| Desktop JVM (`jvmMain`) | `org.eclipse.jgit:org.eclipse.jgit` | 7.x (latest) | Full Java 21 compatibility — no constraints |
| Android (`androidMain`) | `org.eclipse.jgit:org.eclipse.jgit` | **5.13.x** | Android-safe; Java 11 APIs with `coreLibraryDesugar` |
| iOS (`iosMain`) | `kgit2` (libgit2 via Kotlin/Native) | latest main | Only viable pure-C git library with iOS support |

**Rationale for JGit 5.x on Android instead of 7.x:** JGit 7.x requires Java 17 APIs not guaranteed by Android ART even with desugaring. JGit 5.13.x is proven by Android Password Store and Orgzly in production. The API surface we need (clone, fetch, merge, push, status, log, add, commit) is unchanged between 5.x and 7.x for our use case.

**iOS risk acceptance:** kgit2 is early-stage with low adoption. Mitigation: implement the `GitRepository` interface on iOS as a stub that returns `DomainError.GitError.NotSupported` in an initial release, then replace with kgit2 when integration is validated in a dedicated iOS integration task (Task 3.4). This unblocks Android and Desktop while iOS git ships as a follow-up.

### 1.2 SSH Library

| Platform | Library | Notes |
|----------|---------|-------|
| Desktop JVM | `org.eclipse.jgit:org.eclipse.jgit.ssh.apache` (Apache MINA SSHD) | Bundled with JGit 7.x; Java 11+ NIO, no extra dep |
| Android | `com.github.mwiede:jsch:0.2.x` | Drop-in `com.jcraft:jsch` replacement; ED25519, ECDSA, OpenSSH format support; Android API 21+ |
| iOS | libssh2 (bundled with libgit2/kgit2) | kgit2 links libssh2 transitively |

**Why not MINA SSHD on Android:** MINA SSHD NIO requires API 26+ and still has reported issues with Android's BouncyCastle version. The `mwiede/jsch` fork is battle-tested for Android git apps at our target SDK range.

### 1.3 Diff Library

`io.github.petertrr:kotlin-multiplatform-diff:1.3.0`

Covers all KMP targets (JVM, Android, iOS Native, WASM). Apache 2.0. Active maintenance as of December 2025. Used for conflict hunk display in `ConflictResolutionScreen`.

### 1.4 Secure Credential Storage

```
expect class CredentialStore { ... }
```

| Platform | Backend |
|----------|---------|
| Android (`androidMain`) | `EncryptedSharedPreferences` (androidx.security:security-crypto already in deps) |
| iOS (`iosMain`) | iOS Keychain via `multiplatform-settings:KeychainSettings` |
| Desktop JVM (`jvmMain`) | `javax.crypto` AES-GCM encrypted file at `~/.config/stelekit/credentials.enc` |

`KVault` is excluded because it has no Desktop JVM target. `multiplatform-settings` is preferred for iOS because it has broader platform coverage and active maintenance.

### 1.5 Background Sync Scheduler

| Platform | Mechanism |
|----------|-----------|
| Android | `WorkManager` (periodic work, `NetworkType.CONNECTED` constraint) + in-process coroutine timer when app is foregrounded |
| iOS | `BGProcessingTask` (re-scheduled after each execution) + on-foreground-launch sync as primary path |
| Desktop JVM | `CoroutineScope` with `PlatformDispatcher.IO` + `delay()` loop owned by `GitSyncService` |

### 1.6 GitConfig Persistence

SQLDelight (new table `GitConfig` in `SteleDatabase.sq`), not DataStore. Rationale: GitConfig is graph-scoped and the database is already the per-graph persistence layer. Using a separate DataStore would require cross-platform coordination for a tiny payload; SQLDelight keeps it in one place and gets Arrow Either for free via the existing `DatabaseWriteActor`.

### 1.7 Summary of Changes from Research Recommendations

| Research Recommendation | Plan Decision | Reason |
|-------------------------|---------------|--------|
| JGit 5.x on Android | JGit 5.13.x on Android | Confirmed as research rec; explicitly pin 5.13.x |
| kgit2 for iOS | kgit2 for iOS, stub-first | Add stub phase before kgit2 integration to unblock Desktop/Android delivery |
| `multiplatform-settings` for iOS keychain | Adopted | Broader platform support than KVault |
| Apache MINA SSHD on Desktop | Adopted (via jgit.ssh.apache) | Already bundled in JGit 7.x |
| Apache MINA SSHD on Android | Replaced with mwiede/jsch | BouncyCastle conflict risk on Android |

---

## 2. New Domain Model

### 2.1 GitConfig

```kotlin
// commonMain: dev/stapler/stelekit/git/model/GitConfig.kt

@Serializable
data class GitConfig(
    val graphId: String,
    val repoRoot: String,           // Absolute path to the git repo root (NOT the wiki subdir)
    val wikiSubdir: String,         // Relative path from repoRoot to wiki root (e.g. "wiki" or "notes")
    val remoteName: String = "origin",
    val remoteBranch: String = "main",
    val authType: GitAuthType,
    val sshKeyPath: String? = null, // Android/iOS: user-configured path; Desktop: ~/.ssh/id_ed25519
    val sshKeyPassphraseKey: String? = null, // Key into CredentialStore for passphrase
    val httpsTokenKey: String? = null,       // Key into CredentialStore for PAT
    val pollIntervalMinutes: Int = 5,
    val autoCommit: Boolean = true,
    val commitMessageTemplate: String = "SteleKit: {date}",
)

// wikiRoot is a computed property, not stored
val GitConfig.wikiRoot: String get() = if (wikiSubdir.isEmpty()) repoRoot else "$repoRoot/$wikiSubdir"

enum class GitAuthType { NONE, SSH_KEY, HTTPS_TOKEN }
```

### 2.2 SyncState

```kotlin
// commonMain: dev/stapler/stelekit/git/model/SyncState.kt

sealed class SyncState {
    data object Idle : SyncState()
    data object Fetching : SyncState()
    data class MergeAvailable(val commitCount: Int) : SyncState()
    data object Merging : SyncState()
    data object Pushing : SyncState()
    data object Committing : SyncState()
    data class ConflictPending(val conflicts: List<ConflictFile>) : SyncState()
    data class Error(val error: DomainError.GitError) : SyncState()
    data class Success(
        val localCommitsMade: Int,
        val remoteCommitsMerged: Int,
        val lastSyncAt: Long,       // epoch ms
    ) : SyncState()
}
```

### 2.3 DomainError.GitError

Extends the existing `DomainError` sealed interface in `error/DomainError.kt`:

```kotlin
// Addition to DomainError.kt

sealed interface GitError : DomainError {
    data class CloneFailed(override val message: String) : GitError
    data class FetchFailed(override val message: String) : GitError
    data class PushFailed(override val message: String) : GitError
    data class AuthFailed(override val message: String) : GitError
    data class MergeConflict(val conflicts: List<ConflictFile>) : GitError {
        override val message: String = "Merge conflict in ${conflicts.size} file(s)"
    }
    data class CommitFailed(override val message: String) : GitError
    data class NotAGitRepo(val path: String) : GitError {
        override val message: String = "Not a git repository: $path"
    }
    data class DetachedHead(val path: String) : GitError {
        override val message: String = "Repository is in detached HEAD state: $path"
    }
    data class StaleLockFile(val lockPath: String) : GitError {
        override val message: String = "Stale git lock file found: $lockPath"
    }
    data class NotSupported(val platform: String) : GitError {
        override val message: String = "Git integration not yet supported on $platform"
    }
    data object Offline : GitError {
        override val message: String = "No network connection available"
    }
    data object EditingInProgress : GitError {
        override val message: String = "Cannot sync while editing is in progress"
    }
}
```

Also add `GitError` to `DomainError.toUiMessage()`.

### 2.4 ConflictFile and ConflictHunk

```kotlin
// commonMain: dev/stapler/stelekit/git/model/ConflictModels.kt

data class ConflictFile(
    val filePath: String,       // Absolute path of conflicted file
    val wikiRelativePath: String, // Path relative to wiki root (for display)
    val hunks: List<ConflictHunk>,
)

data class ConflictHunk(
    val id: String,             // Stable ID for persistence
    val localLines: List<String>,
    val remoteLines: List<String>,
    val resolution: HunkResolution = HunkResolution.Unresolved,
    val manualContent: String? = null, // Set when user edits manually
)

sealed class HunkResolution {
    data object Unresolved : HunkResolution()
    data object AcceptLocal : HunkResolution()
    data object AcceptRemote : HunkResolution()
    data object Manual : HunkResolution()
}

// Persisted resolution state so partial progress survives app kill
@Serializable
data class ConflictResolutionState(
    val graphId: String,
    val conflictFiles: List<ConflictFile>,
    val startedAt: Long,        // epoch ms
)
```

### 2.5 EditLock

```kotlin
// commonMain: dev/stapler/stelekit/git/EditLock.kt

class EditLock {
    private val _editingCount = MutableStateFlow(0)

    fun beginEdit() { _editingCount.update { it + 1 } }
    fun endEdit()   { _editingCount.update { maxOf(it - 1, 0) } }

    /** Suspends until no blocks are in edit mode. Called by GitSyncService before merge. */
    suspend fun awaitIdle() {
        _editingCount.first { it == 0 }
    }

    val isEditing: StateFlow<Boolean> get() = _editingCount
        .map { it > 0 }
        .stateIn(editLockScope, SharingStarted.Eagerly, false)

    private val editLockScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```

`BlockEditor` composable calls `editLock.beginEdit()` on focus and `editLock.endEdit()` on blur (after the 500ms debounce in `BlockStateManager` fires and clears the pending write). `EditLock` is created inside `GraphManager` and injected into `GitSyncService`.

---

## 3. New Services and Classes

### 3.1 `GitRepository` (expect/actual)

**Package:** `dev/stapler/stelekit/git/`

**commonMain** — interface:

```kotlin
// commonMain: dev/stapler/stelekit/git/GitRepository.kt
interface GitRepository {
    suspend fun isGitRepo(path: String): Boolean
    suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit>
    suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit>
    suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult>
    suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus>
    suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> // returns commit SHA
    suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult>
    suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun log(config: GitConfig, maxCount: Int = 50): Either<DomainError.GitError, List<GitCommit>>
    suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit>
    suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit>
    suspend fun hasDetachedHead(config: GitConfig): Boolean
    suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit>
}

data class FetchResult(val hasRemoteChanges: Boolean, val remoteCommitCount: Int)
data class GitStatus(val hasLocalChanges: Boolean, val untrackedFiles: List<String>, val modifiedFiles: List<String>)
data class MergeResult(val hasConflicts: Boolean, val conflicts: List<ConflictFile>, val changedFiles: List<String>)
data class GitCommit(val sha: String, val shortMessage: String, val authorName: String, val timestamp: Long)
enum class MergeSide { LOCAL, REMOTE }
sealed class GitAuth {
    data class SshKey(val keyPath: String, val passphraseProvider: suspend () -> String?) : GitAuth()
    data class HttpsToken(val username: String, val tokenProvider: suspend () -> String?) : GitAuth()
    data object None : GitAuth()
}
```

**`jvmMain`** — `JvmGitRepository`: JGit 7.x implementation. Uses Apache MINA SSHD via `org.eclipse.jgit.ssh.apache.SshdSessionFactoryBuilder`. `stageSubdir` uses `AddCommand.addFilepattern(config.wikiSubdir + "/")`. `fetch` uses `FetchCommand` and compares `FETCH_HEAD` to current HEAD. `status` uses `StatusCommand.addPath(config.wikiSubdir)`.

**`androidMain`** — `AndroidGitRepository`: JGit 5.13.x implementation. SSH uses `mwiede/jsch` via a custom `JschConfigSessionFactory`. Constructor accepts `sshKeyProvider: () -> ByteArray?` to support user-configured key path.

**`iosMain`** — `IosGitRepository`: Initially a stub that returns `DomainError.GitError.NotSupported("iOS")` for all operations. Replaced with kgit2 implementation in Task 3.4.

**Integration with GraphManager/GraphLoader/GraphWriter:** `GitRepository` is a pure I/O class. It does NOT touch the database or the repository layer. Changes to disk (merge, checkout) are always followed by explicit `GraphLoader.reloadFiles()` calls, never via the file watcher.

### 3.2 `GitSyncService`

**Package:** `dev/stapler/stelekit/git/`  
**Source set:** `commonMain`  
**Owned by:** `GraphManager` (one per active graph, created in `switchGraph`)

```kotlin
class GitSyncService(
    private val gitRepository: GitRepository,
    private val graphLoader: GraphLoader,
    private val graphWriter: GraphWriter,
    private val editLock: EditLock,
    private val configRepository: GitConfigRepository,
    private val networkMonitor: NetworkMonitor,  // expect/actual
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Owns its own scope — NEVER accept rememberCoroutineScope()
    private val scope = CoroutineScope(SupervisorJob() + PlatformDispatcher.IO)

    suspend fun sync(graphId: String): Either<DomainError.GitError, SyncState.Success>
    suspend fun fetchOnly(graphId: String): Either<DomainError.GitError, FetchResult>
    suspend fun commitLocalChanges(graphId: String): Either<DomainError.GitError, String?>
    suspend fun resolveConflict(graphId: String, resolution: ConflictResolution): Either<DomainError.GitError, Unit>
    fun startPeriodicSync(graphId: String, intervalMinutes: Int)
    fun stopPeriodicSync()
    fun shutdown()
}
```

**Full sync sequence** (`sync()`):
1. Check `networkMonitor.isOnline` → `DomainError.GitError.Offline` if false
2. Load `GitConfig` from `configRepository`; return if not configured
3. Check for detached HEAD → `DomainError.GitError.DetachedHead`
4. Remove stale `.git/index.lock` if present (older than 60s)
5. `graphWriter.flush()` — flush any pending debounced saves to disk
6. `editLock.awaitIdle()` — block until no blocks are being edited
7. `_syncState.value = SyncState.Committing`; `gitRepository.stageSubdir(config)` + `gitRepository.commit()` if local changes exist
8. `_syncState.value = SyncState.Fetching`; `gitRepository.fetch(config)` → `FetchResult`
9. If `FetchResult.hasRemoteChanges`:
   - Call `graphLoader.beginGitMerge(config.changedFiles)` to suppress file watcher
   - `_syncState.value = SyncState.Merging`; `mergeResult = gitRepository.merge(config)`
   - Call `graphLoader.endGitMerge()` to restore watcher
   - If `mergeResult.hasConflicts`: persist `ConflictResolutionState` to SQLDelight; `_syncState.value = SyncState.ConflictPending(mergeResult.conflicts)`; return `MergeConflict` error
   - Reload changed files: `graphLoader.reloadFiles(mergeResult.changedFiles)`
10. `_syncState.value = SyncState.Pushing`; `gitRepository.push(config)`
11. `_syncState.value = SyncState.Success(...)`

All steps use `withContext(PlatformDispatcher.IO)` for network I/O. DB writes after file reload go through `DatabaseWriteActor` as always (no special handling needed — `reloadFiles` calls `parseAndSavePage` which uses the actor internally).

### 3.3 `GitConfigRepository`

**Package:** `dev/stapler/stelekit/git/`  
**Source set:** `commonMain`

```kotlin
interface GitConfigRepository {
    suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?>
    suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit>
    suspend fun deleteConfig(graphId: String): Either<DomainError, Unit>
    fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>>
}
```

Implementation: `SqlDelightGitConfigRepository`. New SQLDelight table `git_config` in `SteleDatabase.sq`. All writes route through `DatabaseWriteActor`. Schema:

```sql
-- in SteleDatabase.sq
CREATE TABLE IF NOT EXISTS git_config (
    graph_id TEXT NOT NULL PRIMARY KEY,
    repo_root TEXT NOT NULL,
    wiki_subdir TEXT NOT NULL DEFAULT '',
    remote_name TEXT NOT NULL DEFAULT 'origin',
    remote_branch TEXT NOT NULL DEFAULT 'main',
    auth_type TEXT NOT NULL DEFAULT 'NONE',
    ssh_key_path TEXT,
    ssh_key_passphrase_key TEXT,
    https_token_key TEXT,
    poll_interval_minutes INTEGER NOT NULL DEFAULT 5,
    auto_commit INTEGER NOT NULL DEFAULT 1,
    commit_message_template TEXT NOT NULL DEFAULT 'SteleKit: {date}'
);
```

**`CredentialStore`** (expect/actual) is a separate class for SSH passphrases and HTTPS tokens:

```kotlin
// commonMain
expect class CredentialStore {
    fun store(key: String, value: String)
    fun retrieve(key: String): String?
    fun delete(key: String)
}
```

### 3.4 `ConflictResolver`

**Package:** `dev/stapler/stelekit/git/`  
**Source set:** `commonMain`

```kotlin
class ConflictResolver {
    /**
     * Parses a file containing git conflict markers into a list of ConflictHunks.
     * Returns an error if the file has no markers (caller should not call unnecessarily).
     */
    fun parseConflictFile(
        filePath: String,
        content: String,
        wikiRoot: String,
    ): Either<DomainError.GitError, ConflictFile>

    /**
     * Applies a list of resolved hunks to produce a merged file content string.
     * Caller writes the result to disk and calls gitRepository.markResolved().
     */
    fun applyResolutions(
        originalContent: String,
        hunks: List<ConflictHunk>,
    ): Either<DomainError.GitError, String>
}
```

**Parsing algorithm:** Scan for `<<<<<<<` / `=======` / `>>>>>>>` line triplets. Each triplet becomes one `ConflictHunk`. Lines between `<<<<<<<` and `=======` are `localLines`; lines between `=======` and `>>>>>>>` are `remoteLines`. Context lines outside hunks are tracked so `applyResolutions` can reconstruct the full file.

**Block-level display:** The `ConflictResolutionScreen` passes `ConflictHunk.localLines` and `remoteLines` through `kotlin-multiplatform-diff` to produce a visual diff. The ConflictResolver itself operates on raw strings; block-level semantic diff is a UI concern.

### 3.5 `BackgroundSyncScheduler` (expect/actual)

**Package:** `dev/stapler/stelekit/git/`

```kotlin
// commonMain
interface BackgroundSyncScheduler {
    fun schedule(intervalMinutes: Int)
    fun cancel()
}
```

**`androidMain`** — `WorkManagerSyncScheduler`:
- Uses `PeriodicWorkRequestBuilder<GitSyncWorker>` with `RepeatInterval(15, MINUTES)` minimum (Android system enforced)
- For sub-15-minute intervals when app is foregrounded: `GitSyncService` runs its own coroutine timer
- `NetworkType.CONNECTED` + `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` for user-triggered syncs
- `GitSyncWorker: CoroutineWorker` calls `gitSyncService.fetchOnly()` (not full sync) in the background to check for remote changes; only fetches, does not merge

**`iosMain`** — `BgTaskSyncScheduler`:
- Registers `BGProcessingTask` with identifier `dev.stapler.stelekit.gitsync`
- Re-schedules after each execution
- On foreground launch: `AppDelegate` calls `gitSyncService.fetchOnly()` directly

**`jvmMain`** — `DesktopSyncScheduler`:
- `CoroutineScope(SupervisorJob() + PlatformDispatcher.IO)`; `delay(intervalMinutes.minutes)` loop
- Owned by `GitSyncService`; lifecycle tied to `GraphManager.shutdown()`

### 3.6 `NetworkMonitor` (expect/actual)

**Package:** `dev/stapler/stelekit/platform/`

```kotlin
// commonMain
expect class NetworkMonitor {
    val isOnline: Boolean
    fun observeConnectivity(): Flow<Boolean>
}
```

**`androidMain`**: `ConnectivityManager.NetworkCallback`  
**`iosMain`**: `NWPathMonitor` (Network framework)  
**`jvmMain`**: `InetAddress.getByName("8.8.8.8").isReachable(1000)` polled or always-true default

### 3.7 Integration with GraphManager

`GraphManager.switchGraph()` is extended to:
1. Create `EditLock()` for the new graph
2. Create `GitSyncService(gitRepository, graphLoader, graphWriter, editLock, gitConfigRepository, networkMonitor)` 
3. Store it in `activeGitSyncService: GitSyncService?`
4. On graph close/switch: call `gitSyncService?.shutdown()`

```kotlin
// GraphManager additions
val activeGitSyncService: StateFlow<GitSyncService?> = _activeGitSyncService.asStateFlow()
```

`StelekitViewModel` accesses `GitSyncService` via `GraphManager.activeGitSyncService`.

### 3.8 GraphLoader additions for merge suppression

Two new methods on `GraphLoader`:

```kotlin
// GraphLoader new methods
fun beginGitMerge(pathsBeingMerged: List<String>) {
    // Add all paths to suppressedFiles so checkDirectoryForChanges ignores them
    synchronized(suppressedFiles) {
        suppressedFiles.addAll(pathsBeingMerged)
    }
}

fun endGitMerge() {
    // Clear suppression; watcher resumes normal operation
    synchronized(suppressedFiles) {
        suppressedFiles.clear()
    }
}

suspend fun reloadFiles(filePaths: List<String>) {
    for (path in filePaths) {
        val content = fileSystem.readFile(path) ?: continue
        // ConflictMarkerDetector guard already in parseAndSavePage
        parseAndSavePage(path, content, ParseMode.FULL, DatabaseWriteActor.Priority.HIGH)
    }
}
```

Note: `suppressedFiles` already exists in `GraphLoader` as `private val suppressedFiles = mutableSetOf<String>()`. The new `beginGitMerge`/`endGitMerge` methods use it; the existing `suppress()` callback on `ExternalFileChange` remains unchanged.

---

## 4. UI Changes

### 4.1 SyncState in AppState and StelekitViewModel

Add to `AppState.kt`:

```kotlin
data class AppState(
    // ... existing fields ...
    val syncState: SyncState = SyncState.Idle,
    val gitConfig: GitConfig? = null,
    val gitSetupVisible: Boolean = false,
    val conflictResolutionVisible: Boolean = false,
    val gitLogVisible: Boolean = false,
)
```

Add to `StelekitViewModel`:

```kotlin
// Collect from active GitSyncService, null when no git is configured
val syncState: StateFlow<SyncState> = graphManager.activeGitSyncService
    .flatMapLatest { it?.syncState ?: flowOf(SyncState.Idle) }
    .stateIn(scope, SharingStarted.Eagerly, SyncState.Idle)

fun triggerSync() { scope.launch { graphManager.activeGitSyncService.value?.sync(activeGraphId) } }
fun triggerFetchOnly() { scope.launch { graphManager.activeGitSyncService.value?.fetchOnly(activeGraphId) } }
fun openGitSetup() { _uiState.update { it.copy(gitSetupVisible = true) } }
fun dismissConflictResolution() { _uiState.update { it.copy(conflictResolutionVisible = false) } }
```

`StelekitViewModel` observes `syncState` and when `SyncState.ConflictPending` is emitted, sets `conflictResolutionVisible = true`.

### 4.2 Sync Status Badge

**Location:** In the sidebar / graph header area (near the graph name), not the navigation tabs. Specifically: after the graph display name in the `SidebarHeader` composable.

**Badge content:**
- `SyncState.Idle`: grey circular icon (no text) showing last sync time on hover/long-press
- `SyncState.MergeAvailable(n)`: blue badge "↓ n"
- `SyncState.Fetching / Merging / Pushing / Committing`: animated spinner
- `SyncState.ConflictPending`: amber badge "⚠ Conflict"
- `SyncState.Error`: red badge "⚠ Sync Error"
- `SyncState.Success`: brief green checkmark (fades after 3s)

**Manual sync button:** Always-visible sync icon button in the top-right of the sidebar header. Triggers `viewModel.triggerSync()`.

### 4.3 New Screens

**`GitSetupScreen`** (`commonMain/ui/screens/git/GitSetupScreen.kt`):
- Step 1: Choose "Use existing clone" vs "Clone new repo"
- Step 2: Configure repo root path (folder picker) + wiki subdirectory
- Step 3: Choose auth type (SSH / HTTPS / None); SSH: configure key path; HTTPS: enter token (stored in `CredentialStore`)
- Step 4: Configure branch name and poll interval
- Step 5: Test connection button (calls `gitRepository.fetch()`) → success or error message
- On save: calls `gitConfigRepository.saveConfig()` and `gitSyncService.fetchOnly()` immediately

**`ConflictResolutionScreen`** (`commonMain/ui/screens/git/ConflictResolutionScreen.kt`):
- Header: number of conflicted files; progress indicator (N of M resolved)
- File picker row: tappable list of conflicted file names; selected file's hunks shown below
- Per-hunk card:
  - "Mine" (left column, green tint) vs "Theirs" (right column, blue tint) side-by-side
  - Three action buttons: **Accept Mine** / **Accept Theirs** / **Edit Manually**
  - "Edit Manually" opens a simple text field pre-filled with `localLines` joined
  - Resolved hunks collapse with a checkmark
- Quick-resolve controls: **Accept All Mine** / **Accept All Theirs** per file
- Synchronized scroll between columns via `LazyListState` bridge
- **Finish Merge** button (enabled when all hunks in all files are resolved): calls `GitSyncService.resolveConflict()`
- **Abort Merge** button: calls `gitRepository.abortMerge()`
- Partial resolution state auto-saved to `ConflictResolutionState` in SQLDelight on each hunk resolution

**`GitLogScreen`** (`commonMain/ui/screens/git/GitLogScreen.kt`):
- Lists last 50 commits via `gitRepository.log(config, maxCount = 50)`
- Each row: short SHA, message, author, relative timestamp
- Accessible from Settings or via sync badge long-press

### 4.4 Settings Screen Changes

Add a **"Git Sync"** section to the existing settings screen:
- Current repo and remote URL (read-only display)
- Poll interval selector (Off / 5m / 15m / 30m / 1h)
- Auth method (SSH key / HTTPS token) with ability to change
- "View Git Log" link
- "Disconnect Git" button (calls `gitConfigRepository.deleteConfig()`)

---

## 5. File Watcher Safety

### 5.1 The Problem

`GraphLoader.startWatching()` polls every 5 seconds and calls `checkDirectoryForChanges()`. When a git merge rewrites `.md` files in the wiki, the existing `fileRegistry.detectChanges()` sees modified timestamps and emits `ExternalFileChange` events — potentially causing false `DiskConflict` events or double-loading.

### 5.2 The Solution: Explicit Suppression via `beginGitMerge` / `endGitMerge`

The existing `suppressedFiles: mutableSetOf<String>()` in `GraphLoader` already handles per-file suppression (used by `GraphWriter.savePageInternal` → `onFileWritten` callback). The git merge path uses the same mechanism, extended with two new coordinated entry points.

**Sequence in `GitSyncService.sync()`:**

```
1. mergeResult = gitRepository.merge(config)       // git modifies files on disk
   (cannot suppress before — we need merge to complete to know which files changed)

2. graphLoader.beginGitMerge(mergeResult.changedFiles)  // suppress watcher for affected files
   // This adds changedFiles to suppressedFiles

3. graphLoader.reloadFiles(mergeResult.changedFiles)    // explicit, controlled reload
   // parseAndSavePage is called directly; watcher events for these paths ignored

4. graphLoader.endGitMerge()   // suppress cleared
```

**Race condition mitigation:** The 5-second polling watcher may fire between step 1 and step 2. The suppression window is small (milliseconds for in-memory set addition). Any watcher events that sneak through before `beginGitMerge` will see the same post-merge content as `reloadFiles` — resulting in a redundant but harmless reload (the `fileModTime >= page.updatedAt` freshness check in `parseAndSavePage` will skip re-parsing on the second call).

**Conflict-marker guard:** If the merge produced conflict markers in a file, `parseAndSavePage` already returns early via `ConflictMarkerDetector.hasConflictMarkers(content)` check (already in codebase). The file is NOT loaded into the database. `GitSyncService` detects the conflict via `MergeResult.hasConflicts` and routes to `ConflictPending` state before calling `reloadFiles`.

**File watcher and `fileRegistry.updateModTime`:** After `reloadFiles` calls `parseAndSavePage`, the existing `fileRegistry.updateModTime(filePath, updatedModTime)` call at the end of `parseAndSavePage` updates the watcher's known-good timestamp. Subsequent watcher polls see a matching mtime and skip the file. No additional work needed.

---

## 6. Implementation Epics and Tasks

### Epic 1: Foundation — Git Library Integration

**Goal:** Git operations work on Desktop and Android. iOS compiles with a stub. No UI yet.

- **Task 1.1:** Add JGit 7.x to `jvmMain` and JGit 5.13.x to `androidMain` in `kmp/build.gradle.kts`. Add `kotlin-multiplatform-diff` to `commonMain`. Verify build compiles on all targets.
- **Task 1.2:** Define `GitRepository` interface, `GitAuth`, `FetchResult`, `MergeResult`, `GitStatus`, `GitCommit`, `MergeSide` in `commonMain/git/`. Create `IosGitRepository` stub (`iosMain`).
- **Task 1.3:** Implement `JvmGitRepository` in `jvmMain` (JGit 7.x): `isGitRepo`, `status`, `stageSubdir`, `commit`, `fetch`, `merge`, `push`, `log`, `abortMerge`, `removeStaleLockFile`. SSH via Apache MINA SSHD.
- **Task 1.4:** Implement `AndroidGitRepository` in `androidMain` (JGit 5.13.x + mwiede/jsch). Include `JschConfigSessionFactory` with configurable SSH key path.
- **Task 1.5:** Add `GitConfig`, `SyncState`, `DomainError.GitError`, `ConflictFile`, `ConflictHunk`, `HunkResolution`, `EditLock` data classes to `commonMain`.
- **Task 1.6:** Write unit tests for `JvmGitRepository` against a real temp git repo (no mocking). Cover: clone, status, stage, commit, fetch with local "remote" bare repo, merge with no conflict, merge with conflict, push.

### Epic 2: Domain Services

**Goal:** `GitSyncService`, `GitConfigRepository`, `ConflictResolver`, `CredentialStore` are complete and tested.

- **Task 2.1:** Add `git_config` table to `SteleDatabase.sq` and `RestrictedDatabaseQueries`. Implement `SqlDelightGitConfigRepository`.
- **Task 2.2:** Implement `CredentialStore` expect/actual: Android (`EncryptedSharedPreferences`), iOS (Keychain via `multiplatform-settings`), Desktop JVM (AES-GCM file).
- **Task 2.3:** Add `GraphLoader.beginGitMerge()`, `endGitMerge()`, `reloadFiles()` to `GraphLoader`. Verify existing `suppressedFiles` mechanism works for batch suppression.
- **Task 2.4:** Implement `ConflictResolver` — parse conflict markers into `ConflictFile`/`ConflictHunk` list; implement `applyResolutions()` to produce merged content.
- **Task 2.5:** Implement `GitSyncService` (full sync cycle, `fetchOnly`, `commitLocalChanges`, `resolveConflict`). Wire `EditLock` into `BlockStateManager` callbacks.
- **Task 2.6:** Implement `NetworkMonitor` expect/actual (Android, iOS, Desktop).
- **Task 2.7:** Write unit tests for `ConflictResolver` covering: no conflicts, single hunk, multiple hunks, nested conflict blocks, manual edit resolution. Write unit tests for `GitSyncService` using a fake `GitRepository`.

### Epic 3: Background Sync Scheduler

**Goal:** Periodic sync works on all three platforms.

- **Task 3.1:** Implement `BackgroundSyncScheduler` interface + `DesktopSyncScheduler` (`jvmMain`). Wire into `GraphManager.switchGraph()`.
- **Task 3.2:** Implement `WorkManagerSyncScheduler` + `GitSyncWorker` (`androidMain`). Register `GitSyncWorker` in the Android manifest/initialization. Use `fetchOnly()` for background work, not full sync.
- **Task 3.3:** Implement `BgTaskSyncScheduler` (`iosMain`): register `BGProcessingTask`, re-schedule after each execution, call `gitSyncService.fetchOnly()` from the task handler. Add `NSBackgroundModes` to `Info.plist`.
- **Task 3.4 (iOS kgit2):** Replace `IosGitRepository` stub with kgit2 implementation. Evaluate kgit2 API surface for `clone`, `fetch`, `merge`, `push`. Write iOS integration tests against a local bare repo. (This task may slip to a follow-up release if kgit2 API gaps are found.)

### Epic 4: UI — Setup and Configuration

**Goal:** Users can attach a git repo to a graph via the UI.

- **Task 4.1:** Add `SyncState` + `gitConfig` + `gitSetupVisible` fields to `AppState`. Add `syncState: StateFlow<SyncState>` and sync-related actions to `StelekitViewModel`.
- **Task 4.2:** Build `GitSetupScreen` — multi-step wizard: path selection, subdirectory config, auth config, test connection, save.
- **Task 4.3:** Add sync status badge to sidebar header. Add manual sync icon button. Add "Git Sync" section to Settings screen.
- **Task 4.4:** Add `GitLogScreen` — paginated commit list. Wire to Settings "View Git Log" link.

### Epic 5: Conflict Resolution UI

**Goal:** Users can resolve merge conflicts inside the app.

- **Task 5.1:** Build `ConflictResolutionScreen` skeleton: file list, per-hunk card layout, synchronized scroll between two `LazyColumn` instances.
- **Task 5.2:** Implement hunk resolution actions: Accept Mine, Accept Theirs. Wire to `ConflictResolver.applyResolutions()` + `gitRepository.markResolved()`. Implement "Finish Merge" flow (calls `GitSyncService.resolveConflict()` which commits the merge).
- **Task 5.3:** Implement "Edit Manually" inline text editor for a hunk. Add "Accept All Mine" / "Accept All Theirs" quick-resolve buttons per file.
- **Task 5.4:** Implement persistence of partial resolution state to `ConflictResolutionState` in SQLDelight. On launch, detect in-progress merge (`gitRepository.status()` shows merge state) and restore `ConflictResolutionScreen`.
- **Task 5.5:** Abort Merge flow: "Abort Merge" button calls `gitRepository.abortMerge()` and clears persisted state. Test that graph reloads cleanly to pre-merge state.

### Epic 6: Integration, Hardening, and Edge Cases

**Goal:** All pitfalls from research are addressed; the full workflow is tested end-to-end.

- **Task 6.1:** On-launch safety checks: detect detached HEAD (`DomainError.GitError.DetachedHead` warning in UI), detect stale `.git/index.lock` (auto-remove if older than 60s), detect and warn if wiki root equals repo root.
- **Task 6.2:** Validate `.gitattributes` on first git operation: if missing `* text=auto`, offer to add it. Validate `.gitignore` contains `*.db` (existing `checkGitignoreForDatabase` already covers DB files; extend to log a warning for `.stelekit/` sidecar files).
- **Task 6.3:** Implement incremental commit message: default template `"SteleKit: {date} ({n} files changed)"` using `GitConfig.commitMessageTemplate`. Include changed file names in body for legible git log.
- **Task 6.4:** Error mapping layer: map all JGit / kgit2 exceptions to `DomainError.GitError` subtypes. Ensure `AuthFailed` surfaces a human-readable message in the UI, not a raw SSH exception stack trace.
- **Task 6.5:** End-to-end integration test: two "devices" (two temp directories with a shared bare repo as remote) — simulate Device A edits journal, commits; Device B opens SteleKit, triggers sync, sees Device A's entry. Simulate conflict (both edit same journal file), resolve via API.
- **Task 6.6:** Large repo performance: scope `git status` to wiki subdirectory via `StatusCommand.addPath(config.wikiSubdir)`. Add `--max-count=50` to all `log` calls. Document sparse checkout as advanced option in settings UI help text.

---

## 7. Risk Mitigations

### 7.1 JGit vs Native

**Risk:** No single library covers all three platforms.

**Mitigation:** JGit 5.13.x (Android) + JGit 7.x (Desktop JVM) for two platforms on day one; iOS ships as a stub (`DomainError.GitError.NotSupported`). This unblocks primary use cases (Android Termux sync, Desktop sync) immediately. iOS kgit2 integration is a separate task (Task 3.4) that can ship in a follow-up.

**Fallback:** If kgit2 proves unworkable (API gaps, link errors), iOS stays on stub indefinitely and the feature ships as "Desktop + Android" only.

### 7.2 SSH on Android

**Risk:** Original JSch does not support ED25519 or modern key exchange; fails with GitHub post-2021.

**Mitigation:** Use `com.github.mwiede:jsch:0.2.x` as the SSH provider. This is the same fix used by Android Password Store (production app with thousands of users). Integration point: `JschConfigSessionFactory` override in `AndroidGitRepository`. Tested against GitHub, GitLab, and Gitea in Task 1.6.

### 7.3 File Watcher Interaction During Merge

**Risk:** `GraphLoader`'s 5-second polling watcher emits `ExternalFileChange` for files rewritten by git merge, causing false `DiskConflict` events or double-reloads.

**Mitigation:** `GraphLoader.beginGitMerge(paths)` adds all merge-affected paths to `suppressedFiles` before `reloadFiles()` is called. `endGitMerge()` clears suppression. The existing `ConflictMarkerDetector` guard in `parseAndSavePage` prevents conflicted files from entering the DB regardless.

**Secondary defense:** `GraphWriter.flush()` is called before the merge sequence begins, ensuring all debounced saves are on disk and the local commit is clean before git touches any files.

### 7.4 Conflict Markers in Parser

**Risk:** Parser ingests file with `<<<<<<<` markers; corrupts block tree and database.

**Mitigation:** `ConflictMarkerDetector.hasConflictMarkers(content)` is **already implemented** in the codebase (found in `GraphLoader.parseAndSavePage()`). It returns early with a `WriteError` if markers are found. The git layer additionally ensures it never calls `reloadFiles()` on a conflicted file — `MergeResult.hasConflicts` check routes to `ConflictPending` state first.

### 7.5 iOS Background Limits

**Risk:** iOS `BGProcessingTask` has a ~1-2 minute budget and is not guaranteed to run at the requested interval.

**Mitigation:**
1. **On-foreground-launch sync is mandatory** (FR-2.3) — this is the primary sync point
2. Use `BGProcessingTask` (not `BGAppRefreshTask`) for the larger time budget
3. Background task does **only `fetchOnly()`** — check for remote changes, emit `SyncState.MergeAvailable(n)` notification; actual merge deferred to user-initiated action
4. Re-schedule after each task execution via `scheduleNextBgSync()`
5. Persist `lastSyncAttempt` timestamp; on foreground launch, detect if last background check was >30min ago and trigger immediate fetch

### 7.6 Battery Drain on Android

**Risk:** OEM battery savers (Xiaomi, Huawei, Samsung) kill WorkManager jobs; git operations killed mid-flight corrupt `.git/` state.

**Mitigations:**
1. WorkManager with `NetworkType.CONNECTED` constraint handles Doze mode automatically
2. `GitSyncWorker` does `fetchOnly()` only; merge is user-initiated — reduces per-sync work
3. On startup, check for stale `.git/index.lock` (Task 6.1) and clean it up before any git operation
4. JGit's `FetchCommand` is idempotent — re-running after a partial fetch is safe (re-downloads from the last good packfile boundary)

### 7.7 Large Repos

**Risk:** `git status` on a large repo takes 2-10 seconds, blocking sync.

**Mitigation:** Scope all git status and staging operations to `wikiSubdir`: `StatusCommand.addPath(config.wikiSubdir)` in `JvmGitRepository`/`AndroidGitRepository`. Log queries always use `LogCommand.setMaxCount(50)`.

---

## 8. Out of Scope Confirmation

The following are explicitly deferred to v2 and will NOT be implemented:

| Feature | Rationale |
|---------|-----------|
| Branch management (create/switch branches) | Single-branch personal wiki is the core use case |
| Rebase workflow | Merge-only for simplicity; rebase requires conflict model changes |
| Git blame / line history viewer | Nice to have; no conflict with current architecture |
| Multiple remotes per repo | Single `origin` covers all user scenarios |
| Submodule support | Adds significant complexity; no user demand identified |
| Automatic semantic three-way merge | Git's line-level merge is used; block-level diff is display-only |
| In-app new repo initialization from scratch | FR-1.1 covers existing clone; init added as a convenience but not a blocker |
| iOS kgit2 implementation (initial release) | Delivered as stub; kgit2 production readiness needs validation |
| Sparse checkout | Advanced option for large monorepos; documented, not implemented |
| Rebase merge strategy as a user-selectable option | Failure mode documented; merge-only in v1 |
| Windows-native DPAPI for Desktop credential storage | AES-GCM encrypted file is sufficient for v1 |
