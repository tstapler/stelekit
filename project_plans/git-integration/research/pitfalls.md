# Pitfalls Research — Git Integration Failure Modes

_Research date: 2025-05-02_

---

## 1. Git Operations on Mobile: Battery Drain and Background Kill

### Android: Doze Mode and App Standby

**What can go wrong:**

Android's Doze mode (API 23+) defers background CPU and network activity when the device is unused. During Doze sleep: network access is blocked, wakelocks are ignored, and JobScheduler/WorkManager jobs are deferred to "maintenance windows."

In Android 14+, **adaptive restrictions** limit apps opened rarely (once or twice a month) from running background tasks, even when using WorkManager. This affects note-taking apps that users check infrequently.

**OEM-specific battery optimization:**
Manufacturers (Xiaomi, Samsung, Huawei, OnePlus, Asus) implement aggressive proprietary battery savers on top of AOSP Doze. These silently kill background tasks. The [Don't Kill My App](https://dontkillmyapp.com/) project documents OEM-specific behaviors:
- Xiaomi: "Auto-start" permission required (user must enable manually)
- Samsung: "Sleeping apps" list can kill WorkManager jobs
- Huawei: Background app kill is especially aggressive; virtually no background execution without explicit whitelist

**Git-specific risks:**
- A background git fetch that is killed mid-operation leaves the `.git/` directory in an inconsistent state (partial packfiles, stale FETCH_HEAD)
- Retrying with WorkManager's exponential backoff will re-run the entire fetch, but must handle the partial state gracefully
- Large fetches (>5MB delta) are especially vulnerable

**Mitigations:**
1. Use `WorkManager` with `NetworkType.CONNECTED` constraint — WM handles Doze maintenance windows automatically
2. Implement idempotent git operations: if `FETCH_HEAD` already matches remote, skip fetch
3. Show users a "Background Sync Disabled" warning if battery optimization is enabled for the app
4. Provide a manual "Sync Now" button as primary sync path; background sync is supplementary
5. Set WorkManager `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` for user-triggered syncs

### iOS: Background Execution Limits

**What can go wrong:**

iOS `BGTaskScheduler` provides two task types:
- `BGAppRefreshTask`: ~30 seconds execution budget
- `BGProcessingTask`: ~1-2 minutes execution budget, requires device charging + Wi-Fi

For a git fetch+merge operation:
- A small fetch (few KB delta) fits in `BGAppRefreshTask`
- A large fetch (many files changed, binary assets) will time out even in `BGProcessingTask`

When a `BGProcessingTask` expires, iOS calls the `expirationHandler`, and the task is marked failed. There is no retry automatic — the app must re-schedule the next run manually.

**iOS does not guarantee scheduling frequency.** Even if you schedule a 5-minute sync, iOS may only run it every 15-30 minutes based on device usage patterns (ML-based scheduling).

**Mitigations:**
1. **Sync on foreground launch is mandatory (FR-2.3)** — iOS background sync is best-effort
2. Use `BGProcessingTask` (not `BGAppRefreshTask`) for git operations to get the larger time budget
3. Implement incremental fetch: only fetch since the last known remote commit, not a full re-fetch
4. Split fetch and merge into separate task submissions if needed
5. Persist sync state (last successful sync timestamp, pending merge state) to survive task expiration

### Desktop JVM: No Restrictions

Desktop has no OS-level background restrictions. The `CoroutineScope`-based scheduler with `PlatformDispatcher.IO` is safe. The only risk is the user sleeping/hibernating their laptop mid-fetch — handle as a network error with retry.

---

## 2. Race Conditions: Active Editing + Fetch+Merge

**Scenario:** User is typing a block. Background sync triggers. Git fetches and merges the remote branch. The merged file is written to disk. The file watcher (`GraphLoader.externalFileChanges`) detects the change and triggers a reload. The user's in-memory edits are now diverged from disk.

**What can go wrong:**

1. **Silent edit loss:** If the file reload overwrites the in-memory block state, the user's unsaved text is gone.
2. **Double conflict:** If the user saves after the reload, their changes conflict with the just-merged version, creating a new conflict on top of a resolved one.
3. **UI flicker:** The block tree is rebuilt mid-edit, causing the cursor position to jump or the composable to recompose unexpectedly.
4. **Database desync:** The `DatabaseWriteActor` has queued a write for the user's edit. The graph reload fires new writes. Write order may be undefined, resulting in the reloaded (remote) version overwriting the user's pending edit in the database.

**Mitigations:**

**Primary defense: EditLock (see architecture.md)**
- Before any merge operation, acquire the EditLock
- `editLock.awaitIdle()` suspends until `BlockStateManager` reports no active edits
- The merge proceeds only when the editing flag is clear

**Secondary defense: File watcher suppression during merge**
When a merge is in progress, set a flag in `GraphLoader` to suppress or buffer `externalFileChanges` emissions for files being touched by the merge:

```kotlin
// In GitSyncService.sync()
graphLoader.suppressExternalChanges(mergeResult.changedFiles) {
    repo.merge() // files written here won't trigger external change events
}
```

After merge completes, reload changed files explicitly via `graphLoader.reloadFiles(changedFiles)` rather than via the watcher.

**Third defense: Debounced commit before merge**
Before fetching, run `GraphWriter.flushPendingEdits()` to ensure any debounced save (the 500ms timer in `BlockStateManager`) is flushed to disk and committed. This guarantees the local version is fully committed before the merge starts.

---

## 3. Conflict Escalation: Merge Conflict in an Open File

**Scenario:** A git merge conflict occurs in a file that SteleKit has open in the editor. Git injects `<<<<<<<`/`=======`/`>>>>>>>` markers into the file. The app reads the conflicted file through `GraphLoader`.

**What can go wrong:**

1. **Parser crash:** The SteleKit Markdown parser may throw or produce garbage when encountering conflict markers, since `<<<<<<<` is not valid Markdown.
2. **Corrupted block tree:** If the parser "succeeds" but treats conflict markers as block content, the user sees the raw marker text in their rendered wiki.
3. **Corrupted database:** If the conflicted file is loaded into the SQLDelight database with marker content, subsequent queries return conflicted data.
4. **User saves markers:** If the user edits around the conflict markers and saves, the markers are now persisted in the database and on disk without the user resolving them.

**How other apps handle this:**

- **Obsidian Git:** Does NOT handle this. Conflict markers appear as raw text in the rendered note. Users must open the file and manually remove markers. (This is the primary UX gap in the current ecosystem.)
- **Working Copy:** Never loads conflicted files into an editor. The conflict resolution screen is shown before the user can view/edit the file content. Files with unresolved conflicts are visually flagged with a conflict badge.
- **GitJournal:** Avoids by not merging while the app is open; syncs on launch.

**Mitigations for SteleKit:**

1. **Detect conflicts before loading:** After any merge operation, scan affected files for conflict markers before passing to `GraphLoader`. If markers are found, route to `ConflictResolutionScreen` rather than loading into the block editor.
2. **Parser safeguard:** Add a "conflict marker detection" pass in `GraphLoader` that returns a `Either.Left(DomainError.GitError.ConflictMarkersPresent)` if `<<<<<<<` is found. Never store conflicted content in the database.
3. **GraphLoader guard:**
```kotlin
fun loadFile(path: Path): Either<DomainError, List<Block>> {
    val content = path.readText()
    if (content.contains("<<<<<<<") && content.contains("=======")) {
        return DomainError.GitError.ConflictMarkersPresent(path).left()
    }
    // normal parsing...
}
```
4. **Lock conflicted files:** Mark all files with unresolved conflicts as read-only in the UI until resolved.

---

## 4. SSH on Android Without Root

### JSch (Original, `com.jcraft:jsch`)

**Status:** Unmaintained. Last real update circa 2016.

**Known issues on Android:**
- Does not support `diffie-hellman-group14-sha256` or `diffie-hellman-group16-sha512` (required by GitHub since 2021)
- Does not support ED25519 or OpenSSH-format private keys (the default on macOS/Linux since OpenSSH 7.8)
- Fails with "Algorithm negotiation fail" or "Auth fail" when connecting to modern servers
- Android apps using JGit + JSch have numerous open issues reporting these failures: [Android Password Store #568](https://github.com/android-password-store/Android-Password-Store/issues/568), [JGit Android issues blog](https://github.com/ythy/blog/issues/536)
- BouncyCastle version conflicts: Android ships its own BC provider; JSch's BC dependency may clash

### mwiede/jsch Fork (`com.github.mwiede:jsch`)

**Status:** Actively maintained. Version 0.2.x (2024).

**Fixes:**
- ED25519, ECDSA, RSA-SHA256/512 support
- OpenSSH new private key format (PEM with `OPENSSH PRIVATE KEY` header)
- Modern key exchange algorithms
- Drop-in replacement for `com.jcraft:jsch`

**Android-specific:**
- Works on Android API 21+ with care
- May still have BC conflicts on older Android — test with `minSdk 26`
- Security patched in 2024 (SUSE Security Update 2024:0057-1 covers eclipse-jgit + jsch CVEs)

**Migration:** JGit 6.x supports plugging in a custom SSH session factory. Replace `com.jcraft:jsch` with `com.github.mwiede:jsch:0.2.x` in Gradle; update `JschConfigSessionFactory` instantiation.

### Apache MINA SSHD (JGit's preferred transport)

**Module:** `org.eclipse.jgit:org.eclipse.jgit.ssh.apache`

**Android issues:**
- MINA SSHD requires NIO APIs that are only fully available on Android API 26+
- JGit recommends MINA SSHD for Java 11+, but on Android this means API 26+ (Android 8.0) minimum
- If SteleKit targets `minSdk 26` (Android 8.0 — ~97% of active devices as of 2024), MINA SSHD is viable

**Recommendation:** Target `minSdk 26` and use Apache MINA SSHD for Android 8.0+ users; use `mwiede/jsch` as a fallback for lower API levels.

### SSH Key Path Configuration

On Android, SSH private keys may be located at various paths depending on the user's setup:
- Termux: `/data/data/com.termux/files/home/.ssh/id_ed25519` (inaccessible without root or Termux API)
- App-managed storage: User copies key into app's private files directory
- Shared storage: Not accessible without READ_EXTERNAL_STORAGE permission (deprecated API 29+)

**SteleKit must:**
1. Provide a file picker for SSH key import (copies key to app-private storage)
2. Store the key path in settings; reload on each SSH session creation
3. Never assume a fixed key path

---

## 5. Keychain / Secure Storage on KMP: Known Issues

### KVault (`com.liftric:kvault`)

**GitHub:** https://github.com/Liftric/KVault  
**Platforms:** iOS (Keychain), Android (EncryptedSharedPreferences)  

**Known issues:**
- **No Desktop support** — KVault does not target JVM Desktop. Confirmed in community discussions (March 2024). Cannot be used for a unified KMP credential store that includes Desktop.
- Android: Uses `EncryptedSharedPreferences` which is **deprecated** in AndroidX Security Crypto as of 2023/2024. The `EncryptedSharedPreferences` API still works but receives no new features; the replacement is Jetpack DataStore + EncryptedFile.
- Potential BC conflicts on Android (same BouncyCastle issue as JSch)

### multiplatform-settings (Touchlab)

**GitHub:** https://github.com/russhwolf/multiplatform-settings  
**`KeychainSettings`:** iOS Keychain backend (annotated `@ExperimentalSettingsImplementation`)  
**`EncryptedSharedPreferencesSettings`:** Android backend  

**Advantages over KVault:**
- Supports JVM Desktop (via JVM-standard `Preferences` — NOT encrypted, but key can be wrapped)
- Broader active maintenance
- More platform targets

**Known issues:**
- `KeychainSettings` is experimental (annotation, not necessarily unstable)
- JVM backend is not encrypted by default; requires a custom `EncryptedPreferences` implementation for Desktop credential security
- No Windows-native DPAPI integration

### ksecurestorage

**GitHub:** https://github.com/AlexanderEggers/ksecurestorage  
**Platforms:** Android, iOS  

Less actively maintained than KVault. Similar limitations (no Desktop).

### Recommended Pattern for SteleKit

```kotlin
// commonMain
expect class CredentialStore {
    fun storeToken(key: String, value: String)
    fun getToken(key: String): String?
    fun delete(key: String)
}

// androidMain: EncryptedSharedPreferences (or DataStore + EncryptedFile)
// iosMain: iOS Keychain via KVault or multiplatform-settings
// jvmMain: javax.crypto AES-GCM encrypted file in app data directory
```

For Desktop JVM, use `javax.crypto` to AES-GCM encrypt credentials stored in a file at `~/.config/stelekit/credentials.enc`. The encryption key is derived from the OS user account (e.g., using `SecureRandom` seed stored in user home, or macOS Keychain via JNA).

---

## 6. GraphLoader File-Watch Interaction

**The Risk:**

SteleKit's `GraphLoader` watches for external file changes via a `SharedFlow<ExternalFileChange>` (backed by a `FileWatcher`). When a git merge rewrites one or more `.md` files in the wiki, the `FileWatcher` will emit change events for each rewritten file.

If these events are processed as normal "external edits" (the existing `DiskConflict` detection flow), the following problems arise:

1. **False `DiskConflict` events:** The git merge write is indistinguishable from a user's external editor writing the file. The app may show "File changed externally" prompts for every file touched by the merge.
2. **Double reload:** The merge already triggers an explicit `reloadFiles()` call in `GitSyncService`. The file watcher would trigger a second, redundant reload.
3. **Reload of conflicted files:** If the merge left conflict markers in a file, the file watcher would attempt to reload it, hitting the conflict marker guard (§3 above) — acceptable if the guard is in place, but potentially confusing.
4. **Write-after-watch race:** The file watcher event for a merge-written file arrives asynchronously. If the user's pending edit write arrives after the merge write but before the file watcher event processes, the file watcher might treat the user's write as the "new" external change, causing the merge result to be discarded.

**Mitigations:**

**Option A: Watcher suppression during merge (recommended)**

`GitSyncService` calls `GraphLoader.beginGitOperation()` before merge and `GraphLoader.endGitOperation()` after. During a git operation, the `FileWatcher` buffers events. After `endGitOperation()`, discard buffered events for files that were explicitly reloaded via `reloadFiles()`.

```kotlin
class GraphLoader {
    private val suppressedPaths = mutableSetOf<Path>()
    
    @Synchronized
    fun suppressWatcherFor(paths: List<Path>) {
        suppressedPaths.addAll(paths)
    }
    
    @Synchronized
    fun clearWatcherSuppression(paths: List<Path>) {
        suppressedPaths.removeAll(paths.toSet())
    }
    
    // Inside the file watcher event handler:
    private fun onFileChanged(path: Path) {
        if (path in suppressedPaths) return  // ignore merge writes
        externalFileChanges.emit(ExternalFileChange(path))
    }
}
```

**Option B: Timestamp-based origin detection**

Record the timestamp before the merge starts. File watcher events with `modifiedAt < mergeStartTimestamp` are git-originated; ignore them. This is less reliable on filesystems with 1-second timestamp precision.

**Option C: Hash-based origin detection**

Before merge, record SHA-256 of each file that will be changed. After merge, file watcher events for files whose new SHA matches the expected post-merge content are ignored. More robust but requires computing hashes.

**Recommendation:** Use **Option A** (explicit suppression list). It is deterministic, aligns with SteleKit's existing explicit reload pattern, and requires the smallest change to `GraphLoader`.

---

## 7. Large Repo Performance

**Scenario:** The git repo contains a large number of files (e.g., a monorepo with thousands of source files), but the wiki lives in a small subdirectory (e.g., 200 `.md` files).

### `git status` Performance

Running `git status` on a large repo (10,000+ files) involves:
- Index refresh (inode + mtime comparison for every tracked file)
- SHA computation for modified files

On a large repo, `git status` can take 2-10 seconds. This blocks any UI that waits for status.

**Mitigation:** Run `git status --pathspec-from-file=<wikiSubdir>` to limit status to the wiki subdirectory. In JGit, use `StatusCommand.addPath(wikiSubdir)` to scope the status check.

### `git fetch` Performance

Fetch downloads only new objects (delta-compressed). For a large repo with active non-wiki commits, each fetch may download many objects that SteleKit doesn't need.

**Mitigation:**
1. **Sparse checkout + partial clone** (see architecture.md §3) — fetch only objects in the wiki subdirectory. Requires `git clone --filter=blob:none --sparse` and `git sparse-checkout set <wikiSubdir>`.
2. **Shallow fetch:** Use `git fetch --depth=1` to fetch only the latest commit (not full history). Appropriate for sync-only use cases where full history isn't needed.

**JGit support:** JGit supports `CloneCommand.setDepth(1)` and `FetchCommand.setShallowSince()`. Sparse checkout has limited JGit support.

### `git merge` Performance

Merge on a large repo is O(changed files) not O(total files). If most recent commits are in non-wiki directories, merge is fast even on large repos.

**Mitigation:** No special handling needed for merge itself. The performance impact is on `status` and `fetch`, not merge.

### `git log` Performance

Displaying recent commits (FR-6.3) in a large repo with a long history can be slow if SteleKit traverses the entire history.

**Mitigation:** Always use `--max-count=N` (e.g., 50) when listing log entries. In JGit: `LogCommand.setMaxCount(50)`.

### Summary Table

| Operation | Large Repo Risk | Mitigation |
|---|---|---|
| `git status` | Slow (2-10s for 10K files) | Scope to wiki subdir |
| `git fetch` | Downloads non-wiki objects | Sparse checkout or shallow clone |
| `git merge` | Fast (delta-based) | None needed |
| `git log` | Slow (traverses all history) | Always use --max-count |
| `git clone` | Very slow for large repos | Progress indicator; offer sparse |

---

## 8. Additional Pitfalls (Miscellaneous)

### `.git` Directory in Wiki Root

If the user accidentally sets the wiki root to the git repo root (not a subdirectory), `GraphLoader` will try to parse `.git/` directory contents as Markdown pages. This causes errors.

**Mitigation:** Validate that the wiki root path does not equal the git repo root and does not contain a `.git` directory directly.

### Detached HEAD State

If the user (or another tool) puts the repo in detached HEAD state, git push will fail silently or produce unexpected behavior.

**Mitigation:** On attach/startup, check for detached HEAD (`git symbolic-ref HEAD`) and warn the user.

### Stale Lock File (`.git/index.lock`)

If a previous git operation was killed mid-run (battery death, force quit), a `.git/index.lock` file may be left behind. Subsequent git operations fail with "Unable to lock index."

**Mitigation:** On startup, detect stale lock files (older than 1 minute) and remove them. In JGit, check for `File(".git/index.lock").exists()`.

### Submodule Interactions

If the git repo contains submodules, `git merge` and `git status` may interact unexpectedly with submodule states.

**Mitigation:** Document that SteleKit does not support repos with submodules in the wiki subdirectory. Add a startup check.

### CRLF Line Endings on Windows/Android Cross-Platform

If users sync between Windows and Android/macOS, git's `core.autocrlf` setting can cause spurious diffs where only line endings differ. Every file shows as modified on every sync.

**Mitigation:** Recommend `.gitattributes` with `* text=auto` in the repo. Add this to the default `.gitattributes` when SteleKit initializes a new repo.

---

## Open Questions / Unresolved Items

- **UNRESOLVED:** Does JGit's `StatusCommand.addPath()` correctly scope status to a subdirectory on Android (file system case sensitivity, path separator differences)?
- **UNRESOLVED:** Does `GraphLoader`'s file watcher use inotify (Linux/Android), FSEvents (macOS/iOS), or kqueue (iOS)? The suppression mechanism must account for the watcher's delivery latency.
- **UNRESOLVED:** Can WorkManager reliably wake a KMP app (not just a pure-Android app) for git sync when the process is killed? Needs integration test.
- **UNRESOLVED:** iOS Keychain access from Kotlin/Native (kgit2 / expect/actual) — does Kotlin/Native have sufficient Keychain API exposure for SSH credential storage?

---

## Sources

- [Don't Kill My App — General](https://dontkillmyapp.com/general)
- [Android Background Limitations](https://notificare.com/blog/2024/12/13/android-background-limitations/)
- [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Future of Background Tasks on Android](https://medium.com/@androidlab/the-future-of-background-tasks-on-android-what-post-doze-evolution-means-for-developers-1225e4792863)
- [BGTaskScheduler Apple Documentation](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler)
- [Android Password Store — SSH Algorithm Negotiation Fail](https://github.com/android-password-store/Android-Password-Store/issues/568)
- [JGit Android SSH issues](https://github.com/ythy/blog/issues/536)
- [mwiede/jsch — JSch fork](https://github.com/mwiede/jsch)
- [Orgzly — Switch from Jsch to Apache MINA SSHD](https://github.com/orgzly/orgzly-android/issues/904)
- [KVault GitHub](https://github.com/Liftric/KVault)
- [Touchlab Encrypted KMP Storage](https://touchlab.co/encrypted-key-value-store-kotlin-multiplatform)
- [SUSE Security Update for eclipse-jgit/jsch](https://www.suse.com/support/update/announcement/2024/suse-su-20240057-1/)
- [Git Sparse Checkout Performance](https://github.blog/open-source/git/bring-your-monorepo-down-to-size-with-sparse-checkout/)
- [obsidian-git Conflict Handling Feature Request](https://github.com/Vinzent03/obsidian-git/issues/803)
