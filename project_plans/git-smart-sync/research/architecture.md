# Git Smart Sync — Architecture Research

## 1. What exists today: git integration surface

### GitSyncService (`commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`)

Exists. Full-featured service that owns its own `CoroutineScope(SupervisorJob() + PlatformDispatcher.IO)`. Key methods:

- `sync(graphId)` — 9-step full cycle: network check → vault check → load config → detached-HEAD guard → remove stale lock → flush writes → commit local → fetch → merge (with conflict detection) → reload merged files → push. Returns `Either<DomainError.GitError, SyncState.Success>`.
- `fetchOnly(graphId)` — fetch without merge/push; emits `SyncState.MergeAvailable` if remote changed. Used by periodic scheduler.
- `commitLocalChanges(graphId)` — stage + commit only, no network.
- `resolveConflict(graphId, resolution)` — hunk-level resolution; writes resolved content to disk, marks resolved, commits merge, reloads files.
- `resolveConflictBySide(graphId, fileResolutions)` — coarser LOCAL/REMOTE side selection.
- `abortActiveMerge(graphId)` — calls `gitRepository.abortMerge(config)`.
- `startPeriodicSync(graphId, intervalMinutes)` / `stopPeriodicSync()`.
- `shutdown()` — cancels scope.

`GraphManager` holds `_activeGitSyncService: MutableStateFlow<GitSyncService?>` and exposes `registerGitSyncService(service)`. The service is wired after `GraphLoader`/`GraphWriter` are constructed in `GraphContent` (Compose-managed). It is nulled and shut down on `switchGraph` and `shutdown`.

### GitRepository interface (`commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt`)

Platform-agnostic interface. Operations: `isGitRepo`, `init`, `clone`, `fetch`, `status`, `stageSubdir`, `commit`, `merge`, `push`, `log`, `abortMerge`, `checkoutFile`, `markResolved`, `hasDetachedHead`, `removeStaleLockFile`, `setCredentialAccess`.

Platform implementations:
- **JVM**: `JvmGitRepository` using JGit 7.3.0 (`org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r` + `org.eclipse.jgit.ssh.apache`).
- **Android**: `AndroidGitRepository` (separate file).
- **iOS**: `IosGitRepository` (stub).
- **WASM/JS**: not present.

### GitConfigRepository (`commonMain/kotlin/dev/stapler/stelekit/git/GitConfigRepository.kt`)

Interface with `getConfig`, `saveConfig`, `deleteConfig`, `observeConfig`. Implemented by `SqlDelightGitConfigRepository`. Created by `GraphManager.createGitConfigRepository()` from `currentFactory`.

### GitConfig model (`commonMain/kotlin/dev/stapler/stelekit/git/model/GitConfig.kt`)

```kotlin
data class GitConfig(
    val graphId: String,
    val repoRoot: String,         // absolute path to git repo root
    val wikiSubdir: String,       // subdirectory within repo where the wiki lives (may be "")
    val remoteName: String = "origin",
    val remoteBranch: String = "main",
    val authType: GitAuthType,
    val sshKeyPath: String? = null,
    val sshKeyPassphraseKey: String? = null,
    val httpsTokenKey: String? = null,
    val oauthTokenKey: String? = null,
    val pollIntervalMinutes: Int = 5,
    val autoCommit: Boolean = true,
    val commitMessageTemplate: String = "SteleKit: {date}",
)
val GitConfig.wikiRoot: String get() = if (wikiSubdir.isEmpty()) repoRoot else "$repoRoot/$wikiSubdir"
```

`wikiRoot` extension property computes the actual wiki directory path.

### SyncState (`commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt`)

Sealed class: `Idle`, `Fetching`, `MergeAvailable(commitCount)`, `Merging`, `Pushing`, `Committing`, `CredentialVaultLocked`, `ConflictPending(conflicts)`, `Error(error)`, `CredentialExpired(graphId)`, `Success(localCommitsMade, remoteCommitsMerged, lastSyncAt)`.

### ConflictResolutionScreen (`commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreen.kt`)

Exists. Composable that receives `conflicts: List<ConflictFile>` and `onResolve: suspend (Map<String, MergeSide>) -> Either<DomainError.GitError, Unit>`. Renders per-file `FilterChip` LOCAL/REMOTE selection. Has abort-merge confirmation dialog wired to `onAbortMerge`.

### Other git infrastructure files

- `BackgroundSyncScheduler.kt` — interface; `DesktopSyncScheduler.kt` (JVM) and `WorkManagerSyncScheduler.kt` (Android) implement it.
- `GitHubDeviceFlowClient.kt` — GitHub Device Flow OAuth; uses Ktor HTTP client (see §4).
- `ConflictResolver.kt` — hunk-level conflict resolution logic.
- `CredentialStore.kt` / `JvmCredentialStore.kt` / `AndroidCredentialStore.kt` / `IosCredentialStore.kt` — platform keychain wrappers.
- `VaultCredentialStore.kt` — paranoid-mode vault integration.
- `EditLock.kt` — awaits idle editing state before commit.
- `ui/screens/git/GitSetupScreen.kt` — UI for initial git configuration.
- `ui/screens/git/GitHubOAuthDialog.kt` — GitHub OAuth dialog.

---

## 2. GraphInfo model and how to extend it

Current fields (`commonMain/kotlin/dev/stapler/stelekit/model/GraphInfo.kt`):

```kotlin
@Serializable
data class GraphInfo(
    val id: String,                          // sha256(canonicalPath).take(16)
    val path: String,                        // canonical absolute path to the wiki directory
    val displayName: String,                 // user-facing directory name
    val addedAt: Long,                       // epoch millis
    val isParanoidMode: Boolean = false,     // true when .stele-vault present
)
```

`GraphRegistry` wraps `List<GraphInfo>` and `activeGraphId`, serialized to JSON via `platformSettings.putString("graph_registry", ...)`.

**Extending for git smart-sync detection.** Add nullable fields with defaults so existing JSON round-trips without breakage (kotlinx.serialization ignores unknown keys on decode; missing fields get their default):

```kotlin
@Serializable
data class GraphInfo(
    val id: String,
    val path: String,
    val displayName: String,
    val addedAt: Long,
    val isParanoidMode: Boolean = false,
    // ---- git smart-sync additions ----
    val detectedRepoRoot: String? = null,       // detected git repo root, may differ from path
    val detectedWikiSubdir: String? = null,     // subdir of repo that contains the wiki (empty = repo root)
    val gitDetectionDismissed: Boolean = false, // user clicked "not now" on git-setup prompt
)
```

These fields are written in `addGraph` after the `isGitRepo` probe runs. Since `GraphInfo` is serialized to `platformSettings`, the `@Serializable` annotation and `Json { ignoreUnknownKeys = true }` in `GraphManager.loadRegistry()` (line 60) guarantee backward compatibility.

**Integration point in `addGraph`:** After the existing `withContext(PlatformDispatcher.IO)` block at line 235–244, add a second IO block that calls `gitRepository.isGitRepo(expandedPath)` and, on success, walks up parent directories to find the true repo root and computes `wikiSubdir` as the relative path.

---

## 3. JVM main() entry point and adding a second CLI main

**Existing main:** `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, package `dev.stapler.stelekit.desktop`, function `fun main()`.

It is wired as the Compose Desktop application entry point via `compose.desktop { application { mainClass = "dev.stapler.stelekit.desktop.MainKt" } }` in `kmp/build.gradle.kts` (line 745). The `./gradlew run` task invokes this class.

**Adding a CLI main.** Create a new top-level file in `jvmMain`:

```
kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/GitSyncCli.kt
```

with:

```kotlin
package dev.stapler.stelekit.cli

fun main(args: Array<String>) { ... }
```

The Gradle `application {}` block only controls `./gradlew run`. A second main is invoked by:
- Adding a dedicated `JavaExec` task in `kmp/build.gradle.kts`:
  ```kotlin
  tasks.register<JavaExec>("runGitSyncCli") {
      classpath = sourceSets["main"].runtimeClasspath  // or the equivalent KMP config
      mainClass.set("dev.stapler.stelekit.cli.GitSyncCliKt")
  }
  ```
  On KMP JVM targets the equivalent is:
  ```kotlin
  tasks.register<JavaExec>("runGitSyncCli") {
      classpath = kotlin.jvm().compilations["main"].output.allOutputs +
                  kotlin.jvm().compilations["main"].runtimeDependencyFiles
      mainClass.set("dev.stapler.stelekit.cli.GitSyncCliKt")
  }
  ```
- Or package a shadow JAR and invoke via `java -cp stelekit.jar dev.stapler.stelekit.cli.GitSyncCliKt`.

The CLI main does not enter the Compose `application {}` block; it constructs `GraphManager`, `JvmGitRepository`, and `GitSyncService` directly, calls `openGraph(path)`, then `gitSyncService.sync(graphId)`.

---

## 4. HTTP client infrastructure

**Ktor version: 3.1.3** (all modules pinned to this version).

**commonMain dependencies:**
- `io.ktor:ktor-client-core:3.1.3`
- `io.ktor:ktor-client-content-negotiation:3.1.3`
- `io.ktor:ktor-serialization-kotlinx-json:3.1.3`

**JVM engine:** `io.ktor:ktor-client-okhttp:3.1.3` (declared in `jvmMain` and `androidMain`).

**iOS engine:** not declared; would need `ktor-client-darwin`.

**Existing usage:** `GitHubDeviceFlowClient` constructs a Ktor `HttpClient` with `ContentNegotiation { json(...) }` installed. It uses `client.post(...)` and `client.get(...)` with `header`, `setBody`, and `response.body<T>()` deserialization.

No Anthropic, Claude SDK, Ollama, or other LLM client dependencies are present in `build.gradle.kts`.

For the AI-assisted conflict resolution epic (if pursued), the pattern would be:
- Add an `expect`/`actual` `fun buildHttpClient(): HttpClient` or inject a shared instance.
- Call the Anthropic Messages API at `https://api.anthropic.com/v1/messages` using the existing Ktor infrastructure; no new library needed.

---

## 5. Key integration points per epic

### Epic 1: Auto-detect git repo on graph open

**Hook:** `GraphManager.addGraph()` (lines 229–263). After the existing `withContext(PlatformDispatcher.IO)` block, probe `gitRepository.isGitRepo(expandedPath)`. If true, walk parent directories to find the `.git` root and compute `wikiSubdir`. Persist results in new `GraphInfo` fields (`detectedRepoRoot`, `detectedWikiSubdir`).

Dependency: `GitRepository` must be injected into `GraphManager`. Currently it is not — `GraphManager`'s constructor takes `Settings`, `DriverFactory`, `FileSystem`. The cleanest approach is either (a) inject `GitRepository` into `GraphManager` constructor, or (b) have the call site (App.kt / CLI main) run the detection separately after `addGraph` returns and update the registry via a new `updateGraphInfo(id, update)` method.

**Prompt surface:** After detection, `_graphRegistry` emits the updated `GraphInfo`; the UI observing `graphRegistry` can check `detectedRepoRoot != null && !gitDetectionDismissed` to show the setup prompt.

### Epic 2: Git setup prompt + GitConfig save

**Hook:** UI observes `graphManager.graphRegistry`. When `GraphInfo.detectedRepoRoot != null && gitDetectionDismissed == false && gitConfig == null`, show the setup banner. The setup dialog already exists at `ui/screens/git/GitSetupScreen.kt`.

Saving the inferred `repoRoot`/`wikiSubdir` pre-fills `GitSetupScreen` fields so the user only needs to confirm, not retype paths.

### Epic 3: Merge conflict handling improvement

**Existing surface:** `SyncState.ConflictPending(conflicts)` is emitted by `GitSyncService.sync()` at the merge step. `ConflictResolutionScreen` is already wired to `onResolve` and `onAbortMerge`.

**Hook for AI assist:** In `ConflictResolutionScreen` or a wrapping composable, detect when `conflicts` contains files with more than N hunks (or any hunks), offer an "Suggest resolution" button that calls a new `GitSmartConflictResolver` service. That service reads each conflicted file via `FileSystem.readFile()`, sends the diff to the Anthropic Messages API over Ktor, and returns a suggested `MergeSide` or hunk-level resolution.

No new infrastructure is needed beyond a new service class; Ktor and `ConflictResolution`/`ConflictFile` models are already defined.

### Epic 4: CLI entry point for headless sync

**Hook:** New file `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/GitSyncCli.kt`. Wire:
1. `PlatformFileSystem()` (already used in `Main.kt`).
2. `JvmGitRepository()`.
3. `GraphManager(settings, driverFactory, fileSystem)`.
4. `graphManager.openGraph(path)` — returns `RepositorySet`, runs migrations.
5. Construct `GraphLoader`, `GraphWriter`, `EditLock`, `GitConfigRepository` from the `RepositorySet`.
6. Construct `GitSyncService(gitRepository, graphLoader, graphWriter, editLock, configRepository, networkMonitor, fileSystem)`.
7. Call `gitSyncService.sync(graphId)` and print result / exit with appropriate code.

The CLI does not need Compose or a display. It avoids the `application {}` block entirely.

**Gradle task:** Register a `JavaExec` task pointing at `dev.stapler.stelekit.cli.GitSyncCliKt` so `./gradlew runGitSyncCli --args="--path /path/to/wiki"` works.

---

## 6. Summary of gaps

| Item | Status |
|---|---|
| `GitSyncService` | Exists — full sync cycle, conflict detection, periodic scheduling |
| `ConflictResolutionScreen` | Exists — LOCAL/REMOTE side selection + abort |
| `GitRepository` (JVM via JGit 7.x) | Exists |
| `GitConfigRepository` (SQLDelight) | Exists |
| Auto-detect `repoRoot`/`wikiSubdir` on `addGraph` | **Missing** — no detection logic yet |
| `GraphInfo.detectedRepoRoot` / `detectedWikiSubdir` / `gitDetectionDismissed` fields | **Missing** from model |
| Git-setup prompt triggered by detection | **Missing** — no observer wired |
| CLI main for headless sync | **Missing** — only GUI `Main.kt` exists |
| AI-assisted conflict resolution | **Missing** — no LLM client; Ktor infrastructure ready |
| Anthropic / Claude SDK dependency | **Not present** — would need to be added or use raw Ktor |
