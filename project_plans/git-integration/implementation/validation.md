# Git Integration — Validation Plan

_Plan date: 2026-05-02_
_Based on: requirements.md, implementation/plan.md, research/pitfalls.md, kmp/TESTING_README.md_

---

## 1. Test Infrastructure Requirements

### 1.1 FakeGitRepository (unit tests)

A `FakeGitRepository : GitRepository` in `jvmTest/kotlin/dev/stapler/stelekit/git/fixtures/` that:
- Holds a `MutableMap<String, FetchResult>` keyed by `graphId` for scripted fetch results
- Exposes a `var nextMergeResult: MergeResult` for controlling merge outcomes
- Exposes a `var nextPushError: DomainError.GitError?` to simulate push failures
- Records all method calls in a `callLog: List<String>` for assertion
- Returns `isGitRepo = true` for any path by default (override per test)

This follows the existing `FakePageRepository` / `FakeBlockRepository` pattern in `jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/`.

### 1.2 LocalBareRepoFixture (integration tests)

A JUnit5 `@TempDir`-backed helper in `jvmTest/kotlin/dev/stapler/stelekit/git/fixtures/LocalBareRepoFixture.kt` that:
- Creates a bare repo (`git init --bare`) in a temp directory using JGit `InitCommand`
- Creates a working clone in a second temp directory
- Exposes `fun commitFile(relativePath: String, content: String, message: String)` to add commits
- Exposes `fun wikiSubdir: String` (hardcoded `"wiki"` for tests; configurable)
- Exposes `fun bareRepoUri: String` (file URI of the bare repo, usable as JGit remote URL)
- Tears down both directories via `@AfterEach`

This avoids any external network calls; all integration tests run with `./gradlew jvmTest`.

### 1.3 ConflictFileBuilder

A DSL helper in `jvmTest/kotlin/dev/stapler/stelekit/git/fixtures/ConflictFileBuilder.kt`:

```kotlin
fun conflictContent(block: ConflictBuilder.() -> Unit): String
class ConflictBuilder {
    fun context(vararg lines: String)
    fun hunk(local: List<String>, remote: List<String>)
}
```

Generates valid git conflict marker syntax (`<<<<<<<` / `=======` / `>>>>>>>`) for parser tests.

### 1.4 FakeNetworkMonitor

A `FakeNetworkMonitor : NetworkMonitor` that exposes `var isOnline: Boolean = true`, covering the offline path in `GitSyncService` tests without platform-specific connectivity code.

### 1.5 FakeGraphLoader / FakeGraphWriter additions

Extend the existing `FakeFileSystem` pattern to add:
- `var reloadFilesCallLog: List<List<String>>` to assert that `reloadFiles()` is called with the expected paths after merge
- `var beginGitMergeCallCount: Int` / `var endGitMergeCallCount: Int` for suppression lifecycle assertions

---

## 2. Unit Tests (businessTest / jvmTest)

Test class locations follow existing conventions: `businessTest` for pure domain logic with no JVM dependencies; `jvmTest` for JVM-specific classes.

### 2.1 GitSyncService

**Class:** `dev.stapler.stelekit.git.GitSyncService`  
**Source set:** `jvmTest` (uses `runTest` + `TestScope`)  
**Dependencies mocked:** `FakeGitRepository`, `FakeNetworkMonitor`, in-memory `GitConfigRepository`

- TC-001: Idle to Committing transition — Given local changes exist, when `sync()` is called, then `_syncState` emits `Committing` before `Fetching` — maps to FR-2.1
- TC-002: Committing to Fetching transition — Given `stageSubdir` + `commit` succeed, when sync proceeds, then `_syncState` transitions from `Committing` to `Fetching` — maps to FR-2.1
- TC-003: Fetching to MergeAvailable without auto-merge — Given fetch returns `hasRemoteChanges=true`, when sync is `fetchOnly()`, then `_syncState` emits `MergeAvailable(n)` and no merge is attempted — maps to FR-3.1
- TC-004: MergeAvailable to Merging on explicit sync — Given state is `MergeAvailable`, when `sync()` is called, then `_syncState` transitions to `Merging` — maps to FR-2.1
- TC-005: Successful sync end state — Given fetch+merge+push all succeed, when sync completes, then `_syncState` emits `Success` with correct `remoteCommitsMerged` count — maps to FR-2.1
- TC-006: Conflict produces ConflictPending state — Given merge returns `hasConflicts=true`, when sync runs, then `_syncState` emits `ConflictPending` and `Either.Left(MergeConflict)` is returned — maps to FR-4.1
- TC-007: EditLock blocks sync — Given `editLock.beginEdit()` was called, when `sync()` is invoked, then `editLock.awaitIdle()` suspends until `endEdit()` is called, and sync proceeds only after — maps to FR-3.3
- TC-008: EditLock not blocking when idle — Given no active edit, when `sync()` is called, then `editLock.awaitIdle()` returns immediately without suspension — maps to FR-3.3
- TC-009: Offline error — Given `networkMonitor.isOnline = false`, when `sync()` is called, then returns `Either.Left(DomainError.GitError.Offline)` and state transitions to `Error` — maps to NFR-Offline
- TC-010: Auth failure propagation — Given `FakeGitRepository.nextPushError = AuthFailed("...")`, when sync reaches push step, then returns `Either.Left(AuthFailed)` and `_syncState` emits `Error(AuthFailed)` — maps to FR-5.1, FR-6.2
- TC-011: DetachedHead abort — Given `gitRepository.hasDetachedHead()` returns `true`, when `sync()` is called, then returns `Either.Left(DetachedHead)` without performing any write operations — maps to plan §6.1
- TC-012: Stale lock file is removed before sync — Given `removeStaleLockFile()` call order, when sync runs and lock file is present, then `removeStaleLockFile()` is called before `stageSubdir()` — maps to plan §6.1
- TC-013: No-config returns early — Given no `GitConfig` stored for the graph, when `sync()` is called, then returns `Either.Right` with no operations performed and state stays `Idle` — maps to FR-1.4
- TC-014: `fetchOnly()` does not push — Given `FakeGitRepository` tracks calls, when `fetchOnly()` is called, then `push()` is never called — maps to FR-2.3, FR-3.2
- TC-015: `startPeriodicSync` fires at interval — Given interval = 1 second in `TestScope`, when `startPeriodicSync(1)` is called and time is advanced by 3 seconds, then `fetchOnly()` is called at least 3 times — maps to FR-2.4
- TC-016: `stopPeriodicSync` cancels timer — Given periodic sync is running, when `stopPeriodicSync()` is called, then no further `fetchOnly()` calls occur after cancellation — maps to FR-2.4
- TC-017: `shutdown()` cancels coroutine scope cleanly — Given `GitSyncService` has a running periodic timer, when `shutdown()` is called, then the internal scope is cancelled and no coroutine leaks — maps to plan §3.2
- TC-018: `graphWriter.flush()` called before merge — Given `FakeGraphWriter` tracks calls, when `sync()` runs, then `flush()` is called before `gitRepository.merge()` — maps to pitfall §2
- TC-019: `beginGitMerge` / `endGitMerge` pair called around merge — Given merge succeeds, when sync runs, then `beginGitMerge(changedFiles)` is called before `reloadFiles()` and `endGitMerge()` is called after — maps to FR-3.1, pitfall §6
- TC-020: `reloadFiles` called with merge-changed files — Given merge returns `changedFiles = listOf("wiki/journal.md")`, when sync completes merge, then `reloadFiles(["wiki/journal.md"])` is called exactly once — maps to pitfall §6

### 2.2 ConflictResolver

**Class:** `dev.stapler.stelekit.git.ConflictResolver`  
**Source set:** `businessTest` (pure Kotlin, no JVM deps)

- TC-021: Single hunk parsed correctly — Given content with one `<<<<<<<`/`=======`/`>>>>>>>` block, when `parseConflictFile()` is called, then returns `ConflictFile` with exactly one `ConflictHunk` with correct `localLines` and `remoteLines` — maps to FR-4.2
- TC-022: Multiple hunks parsed correctly — Given content with three conflict blocks separated by context lines, when parsed, then returns `ConflictFile` with three hunks in order — maps to FR-4.2
- TC-023: Context lines preserved — Given a file with conflict markers and surrounding context lines, when `parseConflictFile()` is called, then context lines are tracked for round-trip reconstruction — maps to FR-4.4
- TC-024: No conflict markers returns error — Given clean markdown content with no markers, when `parseConflictFile()` is called, then returns `Either.Left` (not `ConflictFile`) — maps to plan §3.4
- TC-025: Empty file returns error — Given an empty string, when `parseConflictFile()` is called, then returns `Either.Left` — maps to plan §3.4
- TC-026: `applyResolutions` with AcceptLocal — Given one hunk with `resolution = AcceptLocal`, when `applyResolutions()` is called, then output contains only `localLines` for that hunk, markers removed — maps to FR-4.3
- TC-027: `applyResolutions` with AcceptRemote — Given one hunk with `resolution = AcceptRemote`, when `applyResolutions()` is called, then output contains only `remoteLines` for that hunk — maps to FR-4.3
- TC-028: `applyResolutions` with Manual — Given one hunk with `resolution = Manual` and `manualContent = "edited"`, when applied, then output contains `"edited"` in place of the hunk — maps to FR-4.3
- TC-029: `applyResolutions` with Unresolved hunk returns error — Given a hunk with `resolution = Unresolved`, when `applyResolutions()` is called, then returns `Either.Left` preventing completion — maps to FR-4.4
- TC-030: Mixed resolutions across multiple hunks — Given three hunks with AcceptLocal, AcceptRemote, Manual respectively, when applied, then each hunk is resolved by its own resolution strategy in correct order — maps to FR-4.3
- TC-031: Conflict in first lines of file — Given conflict markers at line 1 (no leading context), when parsed, then hunk is correctly identified without off-by-one error — maps to plan §3.4
- TC-032: Conflict in last lines of file — Given conflict markers at end of file (no trailing context), when parsed, then hunk is correctly identified — maps to plan §3.4
- TC-033: Binary file content is handled — Given content that is non-UTF-8 parseable, when `parseConflictFile()` is called, then returns `Either.Left` (binary file guard, not a crash) — maps to pitfall §3
- TC-034: Round-trip fidelity — Given any conflict file content, when parsed and all hunks resolved as AcceptLocal, then output equals the original local-side content — maps to FR-4.4
- TC-035: `wikiRelativePath` is computed relative to wikiRoot — Given `filePath = "/repo/wiki/journals/2026-05-02.md"` and `wikiRoot = "/repo/wiki"`, when parsed, then `ConflictFile.wikiRelativePath = "journals/2026-05-02.md"` — maps to FR-4.2

### 2.3 GitConfigRepository (SqlDelightGitConfigRepository)

**Class:** `dev.stapler.stelekit.git.SqlDelightGitConfigRepository`  
**Source set:** `jvmTest` (requires SQLDelight in-memory database)

- TC-036: Save and load round-trip — Given a `GitConfig` with all fields set, when `saveConfig()` then `getConfig()`, then returned config equals original — maps to FR-1.4
- TC-037: Missing config returns null — Given no config saved for a graphId, when `getConfig(graphId)` is called, then returns `Either.Right(null)` — maps to FR-1.4
- TC-038: Update overwrites previous config — Given an existing config, when `saveConfig()` with different `remoteBranch`, then subsequent `getConfig()` returns updated branch — maps to FR-1.4
- TC-039: Delete removes config — Given a saved config, when `deleteConfig(graphId)`, then `getConfig(graphId)` returns `Either.Right(null)` — maps to FR-1.4, plan §4.4
- TC-040: `observeConfig` emits on save — Given a `Flow` collector on `observeConfig(graphId)`, when `saveConfig()` is called, then the flow emits the new config — maps to FR-1.4
- TC-041: Default values are preserved — Given a config saved with only required fields, when loaded, then `pollIntervalMinutes = 5`, `autoCommit = true`, `remoteName = "origin"` — maps to FR-2.4
- TC-042: SSH config persisted correctly — Given `authType = SSH_KEY` and `sshKeyPath = "/path/key"`, when saved and loaded, then `authType` and `sshKeyPath` are correctly stored — maps to FR-5.1
- TC-043: HTTPS token key persisted correctly — Given `authType = HTTPS_TOKEN` and `httpsTokenKey = "mykey"`, when saved and loaded, then fields are preserved — maps to FR-5.2
- TC-044: wikiRoot computed property — Given `repoRoot = "/repo"` and `wikiSubdir = "wiki"`, then `GitConfig.wikiRoot == "/repo/wiki"` — maps to FR-1.3
- TC-045: wikiRoot with empty subdir equals repoRoot — Given `wikiSubdir = ""`, then `GitConfig.wikiRoot == repoRoot` — maps to FR-1.3

### 2.4 EditLock

**Class:** `dev.stapler.stelekit.git.EditLock`  
**Source set:** `businessTest`

- TC-046: `awaitIdle` returns immediately when count is 0 — Given no `beginEdit()` calls, when `awaitIdle()` is called, then it completes without suspension — maps to FR-3.3
- TC-047: `awaitIdle` suspends while editing — Given `beginEdit()` called once, when `awaitIdle()` is called in a separate coroutine, then it suspends until `endEdit()` is called — maps to FR-3.3
- TC-048: Multiple concurrent edits — Given `beginEdit()` called twice, when one `endEdit()` is called, then `awaitIdle()` still suspends; after second `endEdit()`, it completes — maps to FR-3.3
- TC-049: `isEditing` StateFlow reflects count — Given `beginEdit()` called, then `isEditing.value == true`; after `endEdit()`, `isEditing.value == false` — maps to FR-3.3
- TC-050: `endEdit` below zero is clamped — Given no active edits, when `endEdit()` is called, then `_editingCount` stays at 0 (does not go negative) — maps to plan §2.5

### 2.5 GitConfig Model Validation

**Source set:** `businessTest`

- TC-051: `GitAuthType` covers all three types — Given `NONE`, `SSH_KEY`, `HTTPS_TOKEN` enum values exist, then they are correctly serialized/deserialized with `@Serializable` — maps to FR-5.1, FR-5.2
- TC-052: `SyncState.Success` stores correct fields — Given `localCommitsMade=2`, `remoteCommitsMerged=3`, `lastSyncAt=1000L`, then `SyncState.Success` holds all values — maps to FR-6.1
- TC-053: `HunkResolution` sealed class completeness — Given a `when` expression on `HunkResolution`, then all four branches (`Unresolved`, `AcceptLocal`, `AcceptRemote`, `Manual`) compile without `else` — maps to FR-4.3

### 2.6 DesktopSyncScheduler

**Class:** `dev.stapler.stelekit.git.DesktopSyncScheduler`  
**Source set:** `jvmTest`

- TC-054: Timer fires at configured interval — Given a `TestScope` with `advanceTimeBy(intervalMs)`, when `schedule(1)` is called, then the callback is invoked after the interval elapses — maps to FR-2.4
- TC-055: Timer fires repeatedly — Given `schedule(1)` and `advanceTimeBy(3 * intervalMs)`, then callback is invoked at least 3 times — maps to FR-2.4
- TC-056: `cancel()` stops further firings — Given a running schedule, when `cancel()` is called and time is advanced, then no further callback invocations occur — maps to FR-2.4
- TC-057: `cancel()` on unstarted scheduler is a no-op — Given `cancel()` called without prior `schedule()`, then no exception is thrown — maps to FR-2.4

---

## 3. Integration Tests (jvmTest)

All integration tests use `LocalBareRepoFixture` and the real `JvmGitRepository` (JGit 7.x). No network calls; all remotes are `file://` URIs pointing to the bare repo in a temp directory. Run with `./gradlew jvmTest`.

**Test class:** `dev.stapler.stelekit.git.JvmGitRepositoryIntegrationTest`

- IT-001: `isGitRepo` true for valid clone — Given a cloned working directory from `LocalBareRepoFixture`, when `isGitRepo(workingDir)` is called, then returns `true` — maps to FR-1.1
- IT-002: `isGitRepo` false for non-git directory — Given a plain temp directory with no `.git/` folder, when `isGitRepo(dir)` is called, then returns `false` — maps to FR-1.1
- IT-003: Clone creates wiki subdirectory — Given `LocalBareRepoFixture` with a `wiki/` commit, when `clone(bareUri, localPath, auth=None, ...)` is called, then `wiki/` subdirectory exists in `localPath` — maps to FR-1.2
- IT-004: Fetch detects new remote commits — Given a clone, when `LocalBareRepoFixture.commitFile("wiki/page.md", ...)` adds a commit to the bare repo and `fetch(config)` is called, then `FetchResult.hasRemoteChanges = true` and `remoteCommitCount >= 1` — maps to FR-2.1, FR-3.1
- IT-005: Fetch reports no changes when up to date — Given a clone that is already up to date, when `fetch(config)` is called, then `FetchResult.hasRemoteChanges = false` — maps to FR-2.1
- IT-006: Fast-forward merge succeeds — Given a clone behind by 1 commit (no local commits), when `merge(config)` is called after `fetch()`, then `MergeResult.hasConflicts = false` and the new file appears on disk — maps to FR-2.1
- IT-007: Three-way merge with non-overlapping changes succeeds — Given two diverged branches that both modified different files in `wiki/`, when `merge(config)` is called, then `MergeResult.hasConflicts = false` and both files contain their respective changes — maps to FR-2.1
- IT-008: Three-way merge with overlapping changes produces conflict — Given both local and remote edits to the same lines of `wiki/journal.md`, when `merge(config)` is called, then `MergeResult.hasConflicts = true` and `MergeResult.conflicts` contains the conflicted file — maps to FR-4.1
- IT-009: Conflict markers written to disk — Given a conflicting merge, after `merge(config)` returns, then reading `wiki/journal.md` from disk contains `<<<<<<<`, `=======`, `>>>>>>>` markers — maps to FR-4.1, FR-4.2
- IT-010: `ConflictMarkerDetector` guards GraphLoader after conflict — Given a conflicted file on disk, when `GraphLoader.parseAndSavePage()` is called with the conflicted content, then it returns early without storing marker content in the database (using existing `ConflictMarkerDetector` guard) — maps to FR-4.1, pitfall §3
- IT-011: Push local commits to bare repo — Given a local commit exists in the working clone, when `push(config)` is called, then `LocalBareRepoFixture` bare repo contains the commit (verifiable via JGit `RevWalk`) — maps to FR-2.2
- IT-012: Push is rejected on non-fast-forward — Given the bare repo has commits the local clone does not have (and local has diverging commits), when `push(config)` is called without first merging, then returns `Either.Left(PushFailed)` — maps to FR-2.2
- IT-013: Auth failure produces AuthFailed error — Given a repo URL with intentionally wrong credentials (wrong password for HTTPS, nonexistent key for SSH), when `fetch(config)` is attempted, then returns `Either.Left(AuthFailed)` — maps to FR-5.1, FR-5.2, FR-6.2
- IT-014: `stageSubdir` only stages wiki files — Given uncommitted changes in both `wiki/` and a root-level file, when `stageSubdir(config)` is called, then only `wiki/` changes appear in the staged index — maps to FR-2.1, plan §3.1
- IT-015: `status` scoped to wiki subdir — Given modifications in `wiki/` and `src/` (outside wiki), when `status(config)` is called with `wikiSubdir = "wiki"`, then `GitStatus.modifiedFiles` contains only wiki files — maps to plan §6.6, pitfall §7
- IT-016: `abortMerge` restores pre-merge state — Given a conflicted merge in progress, when `abortMerge(config)` is called, then `wiki/journal.md` is restored to the pre-merge HEAD version and `MERGE_HEAD` no longer exists — maps to plan §5.5
- IT-017: `log` returns up to maxCount commits — Given a bare repo with 60 commits, when `log(config, maxCount = 50)` is called, then at most 50 `GitCommit` entries are returned — maps to FR-6.3, pitfall §7
- IT-018: `hasDetachedHead` detection — Given a repo checked out at a specific commit (detached HEAD), when `hasDetachedHead(config)` is called, then returns `true` — maps to plan §6.1, pitfall §8
- IT-019: Stale lock file is removed — Given a manually created `.git/index.lock` file older than 60 seconds, when `removeStaleLockFile(config)` is called, then the file is deleted — maps to plan §6.1, pitfall §8
- IT-020: Offline / network unreachable returns graceful error — Given `FakeNetworkMonitor.isOnline = false` in `GitSyncService`, when `sync()` is called, then `DomainError.GitError.Offline` is returned and no JGit operations are attempted — maps to NFR-Offline
- IT-021: Full two-device sync simulation (Device A → Device B) — Given two working clones sharing a bare repo, when "Device A" commits and pushes a journal entry, then "Device B" calls `fetch()` + `merge()` and the entry appears in the merged wiki — maps to SC-2, FR-2.1
- IT-022: Full conflict resolution E2E — Given both "devices" commit conflicting edits to the same journal file and Device B runs `sync()`, then `ConflictPending` state is produced; applying `AcceptRemote` via `ConflictResolver.applyResolutions()` + `markResolved()` + committing produces a valid git state with no conflict markers — maps to SC-4, FR-4.4
- IT-023: File watcher suppression during merge — Given `GraphLoader` with watcher active and `beginGitMerge(paths)` called, when the merge rewrites files on disk, then `externalFileChanges` does NOT emit for the suppressed paths during the suppression window — maps to FR-3.1, pitfall §6
- IT-024: File watcher resumes after `endGitMerge` — Given suppression active, when `endGitMerge()` is called and then an external write occurs, then `externalFileChanges` emits normally — maps to pitfall §6
- IT-025: `wikiSubdir` detection from git clone — Given a bare repo where wiki files are in `notes/` subdirectory and the clone is attached with `wikiSubdir = "notes"`, then only `notes/` files are staged and fetched — maps to FR-1.3

---

## 4. UI Tests (jvmTest with Compose Testing)

UI tests use `ComposeUITestBase` (extends `BlockHoundTestBase`, creates `ComposeTestRule`) following the existing pattern in `jvmTest/kotlin/dev/stapler/stelekit/ui/`.

**Test class for conflict UI:** `dev.stapler.stelekit.ui.ConflictResolutionScreenTest`  
**Test class for sync badge:** `dev.stapler.stelekit.ui.SyncStatusBadgeTest`  
**Test class for setup screen:** `dev.stapler.stelekit.ui.GitSetupScreenTest`

- UT-001: ConflictResolutionScreen renders two columns — Given `ConflictPending` state with one conflict file containing one hunk, when `ConflictResolutionScreen` is composed, then nodes with test tags `"mine-column"` and `"theirs-column"` are both displayed — maps to FR-4.2
- UT-002: ConflictResolutionScreen shows local and remote lines — Given a hunk with `localLines = ["mine"]` and `remoteLines = ["theirs"]`, when rendered, then `"mine"` appears in the local column and `"theirs"` in the remote column — maps to FR-4.2
- UT-003: Accept Remote button marks hunk resolved — Given a rendered hunk with `resolution = Unresolved`, when the user clicks "Accept Theirs", then the hunk card collapses with a checkmark and `resolution` transitions to `AcceptRemote` in the ViewModel — maps to FR-4.3
- UT-004: Accept Local button marks hunk resolved — Given an unresolved hunk, when the user clicks "Accept Mine", then `resolution` transitions to `AcceptLocal` — maps to FR-4.3
- UT-005: Finish Merge button disabled when unresolved hunks remain — Given a `ConflictPending` state with at least one unresolved hunk, when the screen is rendered, then the "Finish Merge" button has `isEnabled = false` — maps to FR-4.4
- UT-006: Finish Merge button enabled when all hunks resolved — Given all hunks in all files have a non-Unresolved resolution, when the screen is rendered, then the "Finish Merge" button has `isEnabled = true` — maps to FR-4.4
- UT-007: Sync badge shows commit count for MergeAvailable — Given `SyncState.MergeAvailable(3)` in ViewModel, when the sidebar is composed, then the badge displays text containing `"3"` — maps to FR-3.1, FR-6.1
- UT-008: Sync badge shows spinner during Fetching — Given `SyncState.Fetching`, when sidebar is composed, then a loading/spinner indicator is visible — maps to FR-6.1
- UT-009: Sync badge shows amber conflict indicator — Given `SyncState.ConflictPending(...)`, when sidebar is composed, then the badge contains conflict-indicating text or icon with amber semantic — maps to FR-6.1, FR-4.1
- UT-010: Sync badge shows red error indicator — Given `SyncState.Error(AuthFailed(...))`, when sidebar is composed, then the badge shows an error state — maps to FR-6.2
- UT-011: Manual sync button triggers sync — Given `StelekitViewModel` with `FakeGitRepository`, when the sync icon button in the sidebar header is clicked, then `viewModel.triggerSync()` is called (observable via spy or state change) — maps to FR-2.5
- UT-012: GitSetupScreen URL field validation — Given `GitSetupScreen` is rendered, when an invalid URL is entered (e.g., `"not a url"`), then a validation error message is displayed — maps to FR-1.2
- UT-013: GitSetupScreen shows auth method picker — Given `GitSetupScreen` step 3 is active, when rendered, then radio buttons or dropdown for "SSH Key" and "HTTPS Token" and "None" are present — maps to FR-5.1, FR-5.2, FR-5.3
- UT-014: ConflictResolutionScreen file list shows all conflicted files — Given `ConflictPending` with 3 conflicted files, when the screen is rendered, then all 3 file names appear in the file list — maps to FR-4.1
- UT-015: ConflictResolutionScreen per-file progress — Given 2 hunks in a file, 1 resolved, when rendered, then a progress indicator shows `"1 / 2"` or equivalent — maps to FR-4.4

---

## 5. Acceptance Tests (manual or E2E)

These scenarios map directly to the five Success Criteria in `requirements.md`. They require physical or emulated devices and cannot run in `./gradlew jvmTest`.

### SC-1: Open a Termux-cloned repo on Android without leaving the app

**Setup:**
1. On an Android device, use Termux to clone a test git repo: `git clone https://github.com/your/wiki.git ~/wiki`
2. Open SteleKit on Android.

**Steps:**
1. In SteleKit, open the Git Setup screen (Settings > Git Sync > Set Up).
2. Choose "Use existing clone".
3. Select the `wiki/` folder via the folder picker (or enter the path manually).
4. Set subdirectory to the wiki folder within the repo (if using root, leave blank).
5. Choose "None" for auth (for public repos) or "SSH Key" and select the key via the file picker.
6. Tap "Test Connection" — verify success message appears.
7. Tap "Save".

**Expected outcome:** SteleKit opens the wiki and shows pages. The sync status badge is visible. No terminal commands were required after the initial Termux clone. Maps to SC-1.

### SC-2: Second machine push detected within poll interval

**Setup:**
- Machine A: SteleKit running with git sync configured, poll interval = 5 minutes.
- Machine B: Clone of same repo in a terminal.

**Steps:**
1. On Machine B, create a new journal entry file: `echo "- Machine B entry" > wiki/journals/2026-05-01.md && git add . && git commit -m "B entry" && git push`
2. On Machine A, wait up to 5 minutes (or trigger manual fetch by tapping sync icon).

**Expected outcome:** Within the poll interval, SteleKit on Machine A shows the sync badge with "↓ 1 new commit". No notification or alert is required; the badge update is sufficient. Maps to SC-2.

### SC-3: Draft preserved when remote push arrives while editing

**Setup:**
- Machine A: SteleKit running with git sync configured.
- Machine B: Another terminal with a clone of the same repo.

**Steps:**
1. On Machine A, open a journal page and start typing a block. Do NOT tap away from the block — keep it in active editing state.
2. On Machine B, push a new commit to the repo.
3. On Machine A, wait for the poll interval to fire (or trigger a manual fetch).

**Expected outcome:**
- SteleKit on Machine A shows the "↓ N new commits" badge but does NOT apply the merge automatically.
- The user's draft text in the block is NOT lost.
- No merge occurs until the user explicitly taps the sync icon and confirms.
Maps to SC-3, FR-3.1, FR-3.2, FR-3.3.

### SC-4: Conflicting journal entries produce side-by-side diff; resolved state is valid git

**Setup:**
- Two working clones sharing a bare repo.
- Both have edited the same journal file with different content (e.g., line 3 differs).

**Steps:**
1. Commit and push from both clones (second push will be rejected; first succeeds).
2. Open SteleKit on the second machine and trigger a sync.
3. SteleKit shows the Conflict Resolution screen with the conflicted file.
4. Verify the left column shows "Mine" content and right column shows "Theirs" content.
5. Click "Accept Theirs" for the conflicted hunk.
6. Click "Finish Merge".

**Expected outcome:**
- After "Finish Merge", the repo is in a clean merged state (no conflict markers in files, `git status` clean).
- The accepted content is present in the file on disk.
- The database contains the resolved content (no conflict markers visible in the SteleKit UI).
Maps to SC-4, FR-4.1–FR-4.4.

### SC-5: Zero terminal commands — full "remote has new commits → merged and synced" flow

**Setup:**
- One device with SteleKit running with git sync configured.
- A second machine has pushed a non-conflicting commit to the shared repo.

**Steps:**
1. Open SteleKit — observe sync badge shows "↓ 1" (detected on app launch per FR-2.3).
2. Tap the manual sync button in the sidebar header.
3. Observe status: badge transitions through spinner (Fetching → Merging → Pushing) to green checkmark (Success).

**Expected outcome:**
- No terminal was opened at any point.
- The remote commit's content is now visible as a page in SteleKit.
- Last sync time is updated in the status badge tooltip / long-press.
Maps to SC-5, FR-2.1, FR-2.5, FR-6.1.

---

## 6. Requirement Coverage Matrix

| Requirement | Test IDs | Coverage |
|---|---|---|
| FR-1.1 (attach existing clone) | TC-001, IT-001, IT-002, AT-SC-1 | ✅ |
| FR-1.2 (clone from URL in-app) | IT-003, UT-012, AT-SC-1 | ✅ |
| FR-1.3 (subdirectory selection) | TC-044, TC-045, IT-025, AT-SC-1 | ✅ |
| FR-1.4 (persist config per-graph) | TC-036–TC-043 | ✅ |
| FR-2.1 (pull: fetch+merge) | TC-002, TC-003, TC-004, IT-004–IT-009, IT-021 | ✅ |
| FR-2.2 (push local changes) | TC-005, IT-011, IT-012 | ✅ |
| FR-2.3 (fetch on launch) | TC-014, TC-003, AT-SC-5 | ✅ |
| FR-2.4 (background polling) | TC-015, TC-016, TC-054–TC-057 | ✅ |
| FR-2.5 (manual sync button) | UT-011, AT-SC-5 | ✅ |
| FR-3.1 (show badge, no auto-merge) | TC-003, IT-004, UT-007, AT-SC-2, AT-SC-3 | ✅ |
| FR-3.2 (explicit trigger required) | TC-003, TC-014, AT-SC-3 | ✅ |
| FR-3.3 (EditLock during active edit) | TC-007, TC-008, TC-046–TC-050, AT-SC-3 | ✅ |
| FR-3.4 (commit per session) | TC-001, TC-002, TC-018 | ✅ |
| FR-4.1 (conflict resolution screen) | IT-008, IT-009, UT-001, UT-009, UT-014 | ✅ |
| FR-4.2 (side-by-side diff) | TC-021–TC-024, UT-001, UT-002, UT-014, AT-SC-4 | ✅ |
| FR-4.3 (accept local/remote/edit) | TC-026–TC-030, UT-003, UT-004, AT-SC-4 | ✅ |
| FR-4.4 (confirm completes merge) | TC-029, TC-034, IT-022, UT-005, UT-006, AT-SC-4 | ✅ |
| FR-4.5 (persistence of partial resolution) | TC-038, IT-022 | ✅ |
| FR-5.1 (SSH key auth) | TC-013, TC-042, IT-013, UT-013, AT-SC-1 | ✅ |
| FR-5.2 (HTTPS + PAT auth) | TC-010, TC-043, IT-013, UT-013 | ✅ |
| FR-5.3 (auth configured per repo) | TC-036–TC-043 | ✅ |
| FR-5.4 (SSH key path on Android) | TC-042, AT-SC-1 | ✅ |
| FR-6.1 (sync status display) | UT-007, UT-008, UT-009, AT-SC-2, AT-SC-5 | ✅ |
| FR-6.2 (actionable error notifications) | TC-010, IT-013, UT-010 | ✅ |
| FR-6.3 (git log viewable) | IT-017 | ✅ |
| NFR-Platform scope | IT-001–IT-025 (JVM), AT-SC-1 (Android) | Partial (iOS stub only) |
| NFR-Safety (no silent overwrite) | TC-007–TC-008, TC-018–TC-020, IT-023, AT-SC-3 | ✅ |
| NFR-Performance (off main thread) | TC-009, IT-020, TC-054 | ✅ |
| NFR-Offline (graceful skip) | TC-009, TC-013, IT-020 | ✅ |

---

## 7. Risk-Based Test Priorities

Ordered from highest to lowest risk based on `research/pitfalls.md`:

### Priority 1: Edit loss during merge (Pitfall §2 — Race condition)
The most dangerous failure: user's unsaved edit is overwritten by a git merge. Tests: TC-007, TC-008, TC-018, TC-019, TC-020, IT-023, IT-024, AT-SC-3. All must pass before shipping.

### Priority 2: Conflict markers in database (Pitfall §3 — Corrupted block tree)
`ConflictMarkerDetector` guard must be verified to block markers from reaching SQLDelight. Tests: IT-009, IT-010, TC-021–TC-035. Existing `ConflictMarkerDetectorTest` covers detection; IT-010 verifies the integration with `parseAndSavePage`.

### Priority 3: File watcher false DiskConflict events (Pitfall §6 — GraphLoader interaction)
`beginGitMerge` / `endGitMerge` suppression must work correctly. Tests: TC-019, TC-020, IT-023, IT-024. Without this, every sync produces spurious "File changed externally" dialogs.

### Priority 4: SSH on Android — modern key support (Pitfall §4 — JSch)
`AndroidGitRepository` must use `mwiede/jsch` and succeed with ED25519 keys. Tests: TC-042, IT-013. Manual test required against GitHub with an ED25519 key. Automated test with a local SSH server (e.g., Apache MINA SSHD as a test server) is preferred but may be deferred.
**Manual only if local SSH server setup is impractical in CI.**

### Priority 5: Auth failure surfacing (Pitfall §4 + FR-6.2)
Raw SSH/HTTPS exceptions must not surface to the user. Tests: TC-010, IT-013, UT-010. The error mapping layer (plan §6.4) must translate `TransportException` / `NotAuthorizedException` to `DomainError.GitError.AuthFailed`.

### Priority 6: Stale lock file and detached HEAD (Pitfall §8)
On-launch safety checks must prevent cryptic "Unable to lock index" errors. Tests: TC-011, TC-012, IT-018, IT-019. These are startup-path checks; failure would block all git operations after a forced kill.

### Priority 7: Large repo `git status` performance (Pitfall §7)
`StatusCommand.addPath(wikiSubdir)` must scope the operation. Test: IT-015. Without this, sync on a monorepo could hang the UI thread if the dispatcher is misconfigured.
**Manual performance test recommended with a repo containing >10,000 files; automated scoping correctness covered by IT-015.**

### Priority 8: iOS background execution limits (Pitfall §1 — iOS BGTaskScheduler)
iOS ships as a stub in v1; background scheduling is `BgTaskSyncScheduler`. The on-foreground-launch sync path (FR-2.3) is the primary iOS sync path and must be tested manually on a physical iOS device.
**Manual only — iOS background scheduling cannot be reliably automated in jvmTest.**

### Priority 9: Android battery saver / Doze mode (Pitfall §1 — Android)
`WorkManagerSyncScheduler` handles Doze via `NetworkType.CONNECTED` constraint. Unit test: TC-054–TC-057 cover the `DesktopSyncScheduler` coroutine timer. Android WorkManager behavior under Doze requires a physical device test.
**Manual only for OEM battery saver testing; automated for interval logic.**

### Priority 10: CRLF line endings / `.gitattributes` (Pitfall §8 — CRLF)
Validation (plan §6.2) should warn if `.gitattributes` with `* text=auto` is missing. No dedicated automated test beyond the integration test that verifies the warning appears (included in IT-022 scenario notes).
**Manual only for cross-platform line ending verification.**
