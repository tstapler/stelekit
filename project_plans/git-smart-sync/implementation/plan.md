# Git Smart Sync — Implementation Plan

_Generated from: requirements.md + research/stack.md + research/features.md + research/architecture.md + research/pitfalls.md_

---

## Architectural Decision Log

Three choices must be resolved before implementation begins. They are flagged inline where they affect task scope.

### ADR-A: CLI subproject vs. single `kmp` module with multiple `mainClass` configurations

| Option | Pros | Cons |
|---|---|---|
| **A1: Dedicated `:cli` subproject** | No Compose/Skiko/AWT on CLI classpath; clean separation of concerns; `application` plugin is self-contained | New subproject boilerplate; shared source must be a proper API boundary |
| **A2: `JavaExec` task inside `kmp`** | No new Gradle module; CLI shares `jvmMain` sources directly | Compose, Skiko, and AWT end up on the runtime classpath; no `installDist`; not redistributable as a standalone binary |

**Recommendation:** A2 for the initial iteration. A `JavaExec` task in `kmp/build.gradle.kts` is sufficient for `./gradlew :kmp:runSync` (FR-3.1). Defer the clean `:cli` subproject to a follow-up when a redistributable binary is needed. The CLI main lives in `kmp/src/jvmMain/` regardless of which option is chosen; the only difference is the Gradle task wiring.

**Decision required before E3-S1.**

### ADR-B: `kotlinx-cli` vs. manual argument parsing

`build.gradle.kts` does not currently have `kotlinx-cli` on the classpath. The CLI has only six flags (`--graph`, `--commit-only`, `--fetch-only`, `--dry-run`, `--json`, `--help`). Adding `kotlinx-cli` requires a new dependency declaration and increases cold-start JAR loading time marginally.

**Recommendation:** Manual parsing with a simple when-based loop. The flag surface is small enough that a 30-line hand-rolled parser is more transparent than a library with its own version to track. If the flag count grows past ~10, revisit.

**Decision required before E3-S2.**

### ADR-C: `LlmMergeClient` interface placement — `commonMain` vs. `jvmMain`

Ktor `HttpClient` is available in `commonMain` (platform-specific engines are in `jvmMain`/`androidMain`). The merge interface could live in `commonMain` and be called from Android too, or be JVM-only.

| Option | Pros | Cons |
|---|---|---|
| **C1: `commonMain`** | Android could use LLM merge in future | Ktor OkHttp engine wiring is JVM-specific; iOS has no engine declared yet |
| **C2: `jvmMain`** | No iOS/WASM concern; simpler engine setup | Android won't get LLM merge without porting later |

**Recommendation:** C1 (`commonMain`) for the interface and data classes; actual `HttpClient` construction via an `expect`/`actual` pattern or injected factory. The Android engine is already `ktor-client-okhttp` in `androidMain`; the Ktor client abstraction handles the platform difference transparently if engine injection is used.

**Decision required before E2-S3.**

---

## Dependency Map (cross-epic)

```
E1-S1 (GraphInfo fields)
  └── E1-S2 (detection logic in GraphManager)
        └── E1-S3 (graphsFlow wiring)
              └── E1-S4 (GitDetectionBanner composable)
                    ├── E1-S5 (Set up sync action)
                    └── E1-S6 (Dismiss action)

E2-S1 (LogseqMergeDriver + 5 strategies — pure Kotlin port)
  └── E2-S2 (JournalMergeService — classify, backup, drive algorithmic merge)
        ├── E2-S3 (LlmEnhancementClient — optional, only for residual conflict markers)
        └── E2-S4 (JournalMergeReviewScreen)
              └── E2-S5 (GitSyncService wiring + silent-resolve removal)

E3-S1 (SyncResult + SyncMain.kt skeleton)
  └── E3-S2 (argument parsing)
        └── E3-S3 (GitSyncService wiring in CLI)
              ├── E3-S4 (output formatting — human + JSON)
              └── E3-S5 (Gradle task + CredentialStore headless fallback)
```

---

## Epic 1: Git Repo Auto-Detection (FR-1)

### E1-S1 — Extend `GraphInfo` with git detection fields

**Title:** Add `detectedRepoRoot`, `detectedWikiSubdir`, `gitDetectionDismissed` to `GraphInfo`

**Acceptance criteria:**
- `GraphInfo` has three new fields with safe defaults: `detectedRepoRoot: String? = null`, `detectedWikiSubdir: String? = null`, `gitDetectionDismissed: Boolean = false`
- Existing stored `GraphInfo` JSON (missing the new keys) deserializes without error and produces instances with the default values
- `GraphRegistry` round-trips through `Json { ignoreUnknownKeys = true }` without throwing
- A unit test serializes an old-format JSON string (without the new fields) and asserts the defaults

**Tasks:**

- T1: Edit `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/GraphInfo.kt` — add the three fields with `= null` / `= false` defaults after `isParanoidMode`
- T2: Verify `GraphManager.loadRegistry()` (line ~60) uses `Json { ignoreUnknownKeys = true }` — no change needed if it does; add the flag if absent
- T3: Add a test in `businessTest` (`GraphInfoSerializationTest.kt`) that:
  - Creates a JSON string without the new fields
  - Deserializes to `GraphInfo`
  - Asserts `detectedRepoRoot == null`, `gitDetectionDismissed == false`

**Dependencies:** None

**Complexity:** XS

---

### E1-S2 — `detectGitRepo()` in `GraphManager`

**Title:** Add non-blocking git repo detection after `addGraph` completes

**Acceptance criteria:**
- `GraphManager` gains a private `suspend fun detectGitRepo(graphPath: String, graphId: String)` that runs in `PlatformDispatcher.IO`
- On JVM: uses `FileRepositoryBuilder().findGitDir(File(graphPath)).setMustExist(true).build()` wrapped in try/catch `RepositoryNotFoundException` — returns `Pair<repoRoot, wikiSubdir>?`
- On Android: skips detection if `graphPath` starts with `content://` (SAF URI guard); otherwise falls back to manual `java.io.File` walk with max depth 10 using `canonicalFile`
- Detection does not block graph loading — called in a `launch` after the `addGraph` `withContext(IO)` block returns
- If no `.git` found, `GraphInfo` fields remain null; no error is surfaced

**Tasks:**

- T1: Add private `fun findGitRootJvm(startPath: String, maxDepth: Int = 10): Pair<String, String>?` in `GraphManager.kt` (JVM-only, or extract to `JvmGitDetector.kt` in `jvmMain` if `GraphManager` is `commonMain`)
  - Use `FileRepositoryBuilder().findGitDir(File(startPath).canonicalFile).setMustExist(true).build()`
  - `repoRoot = repo.workTree.canonicalPath`
  - `wikiSubdir = File(startPath).canonicalPath.removePrefix(repoRoot).trimStart('/', File.separatorChar)`
  - Catch `RepositoryNotFoundException`, `IOException` — return null
- T2: Add SAF URI guard: `if (graphPath.startsWith("content://")) return null` before any file operation
- T3: In `GraphManager.addGraph()`, after the existing IO block at line ~244, add:
  ```kotlin
  launch(PlatformDispatcher.IO) {
      val detected = detectGitRepo(expandedPath, graphId)
      if (detected != null) updateGraphInfoDetection(graphId, detected.first, detected.second)
  }
  ```
- T4: Add `private suspend fun updateGraphInfoDetection(graphId: String, repoRoot: String, wikiSubdir: String)` that reads the current registry, updates the matching `GraphInfo`, and re-persists via `saveRegistry()`
- T5: `expect`/`actual` or `if (Platform.isAndroid)` guard for the SAF check — confirm where `Platform` detection lives in the codebase and match the existing pattern

**Dependencies:** E1-S1

**Complexity:** M

---

### E1-S3 — Emit updated `GraphInfo` on `graphsFlow` after detection

**Title:** Wire detection result into observable graph state

**Acceptance criteria:**
- After `updateGraphInfoDetection` runs, `graphsFlow` (or `graphRegistry`) emits an updated value containing the new `detectedRepoRoot`
- Observers that already collected `graphsFlow` before detection completes receive the updated emission
- No race condition if detection finishes before a collector subscribes (StateFlow semantics guarantee replay of last value)

**Tasks:**

- T1: Verify `_graphRegistry` is a `MutableStateFlow` — confirm in `GraphManager.kt`; update is automatic if `saveRegistry()` updates the flow
- T2: Confirm `updateGraphInfoDetection` calls `saveRegistry()` which internally does `_graphRegistry.value = newRegistry`
- T3: Add an integration test in `businessTest` (`GitDetectionFlowTest.kt`):
  - Mock `JvmGitRepository.isGitRepo()` to return true for a temp directory
  - Call `graphManager.addGraph(tempDir)`
  - Collect `graphsFlow` with a timeout; assert the emitted `GraphInfo` has `detectedRepoRoot != null`

**Dependencies:** E1-S2

**Complexity:** S

---

### E1-S4 — `GitDetectionBanner` composable

**Title:** Dismissible bottom banner shown when git repo detected but not configured

**Acceptance criteria:**
- Banner shown when `graphInfo.detectedRepoRoot != null && gitConfig == null && !graphInfo.gitDetectionDismissed`
- Banner text: "This folder is inside a git repository at `<repo-root>`. Set up sync to keep it in sync across devices."
- Two action buttons: "Set up sync" and "Dismiss"
- Banner is not shown if `gitConfig` for the graph is already set (user has configured sync previously)
- Screenshot test baseline captured

**Tasks:**

- T1: Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/git/GitDetectionBanner.kt`
  - Composable signature: `fun GitDetectionBanner(repoRoot: String, onSetupSync: () -> Unit, onDismiss: () -> Unit)`
  - Use a `Surface` + `Row` with `MaterialTheme.colorScheme.primaryContainer` background at the bottom of the main screen
  - `Text` component with the detection message; `TextButton("Set up sync", onClick = onSetupSync)` and `TextButton("Dismiss", onClick = onDismiss)`
- T2: In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (or the main screen composable), add the conditional banner beneath the main content area:
  ```kotlin
  val activeGraph by graphManager.activeGraph.collectAsState()
  val gitConfig by gitConfigRepository.observeConfig(activeGraph?.id).collectAsState(null)
  if (activeGraph?.detectedRepoRoot != null && gitConfig == null && activeGraph?.gitDetectionDismissed == false) {
      GitDetectionBanner(
          repoRoot = activeGraph!!.detectedRepoRoot!!,
          onSetupSync = { navController.navigate(GitSetupRoute(prefilledRepoRoot = ..., prefilledWikiSubdir = ...)) },
          onDismiss = { viewModel.dismissGitDetection(activeGraph!!.id) }
      )
  }
  ```
- T3: Add `fun dismissGitDetection(graphId: String)` to `StelekitViewModel` — delegates to `GraphManager.setGitDetectionDismissed(graphId, true)`
- T4: Add screenshot test `GitDetectionBannerTest.kt` in `jvmTest` using Roborazzi

**Dependencies:** E1-S3

**Complexity:** M

---

### E1-S5 — "Set up sync" pre-fills `GitSetupScreen`

**Title:** Navigation to `GitSetupScreen` pre-populated with detected repo root and subdirectory

**Acceptance criteria:**
- Tapping "Set up sync" opens `GitSetupScreen` with `repoRoot` field pre-filled with `graphInfo.detectedRepoRoot` and `wikiSubdir` pre-filled with `graphInfo.detectedWikiSubdir`
- User can edit both fields before confirming
- The pre-fill does not overwrite any fields if `GitSetupScreen` is opened through the settings menu (non-detection path)

**Tasks:**

- T1: Inspect `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt` — determine how it currently receives initial values (likely through `ViewModel` state or nav args)
- T2: Add optional nav arguments to the `GitSetupRoute` (or update `GitSetupScreen`'s composable parameters): `prefilledRepoRoot: String? = null`, `prefilledWikiSubdir: String? = null`
- T3: In `GitSetupScreen`'s `LaunchedEffect` (initial load), if `prefilledRepoRoot != null`, initialize the repoRoot form field to that value and wikiSubdir to `prefilledWikiSubdir ?: ""`
- T4: Update navigation call in E1-S4 T2 to pass the detected values as nav args

**Dependencies:** E1-S4

**Complexity:** S

---

### E1-S6 — Persist `gitDetectionDismissed` flag

**Title:** "Dismiss" action permanently suppresses the detection banner for this graph

**Acceptance criteria:**
- Tapping "Dismiss" sets `GraphInfo.gitDetectionDismissed = true` for the active graph
- The banner does not reappear after the user restarts the app with the same graph open
- The dismissed state is stored in `GraphRegistry` (same JSON storage as other `GraphInfo` fields)

**Tasks:**

- T1: Add `suspend fun setGitDetectionDismissed(graphId: String, dismissed: Boolean)` to `GraphManager`:
  - Read current registry, find `GraphInfo` by `graphId`, copy with `gitDetectionDismissed = dismissed`, write back via `saveRegistry()`
- T2: Call from `StelekitViewModel.dismissGitDetection()` (created in E1-S4 T3) — `viewModelScope.launch { graphManager.setGitDetectionDismissed(graphId, true) }`
- T3: Unit test in `businessTest`: call `setGitDetectionDismissed`, reload registry from persisted JSON, assert `gitDetectionDismissed == true`

**Dependencies:** E1-S4

**Complexity:** XS

---

## Epic 2: Algorithmic Journal Merge — Port of `logseq-union` merge driver (FR-2)

> **Architecture change vs. original plan**: The primary merge path is now an algorithmic Kotlin port of the
> battle-tested Python `logseq-union` merge driver at
> `/home/tstapler/Documents/personal-wiki/tools/stapler_logseq_tools/merge/`.
> LLM is an **optional secondary pass** only for the fraction of lines where the algorithm produces
> conflict markers (both sides changed the same line with no clear winner). The algorithmic path
> works fully offline, is deterministic, and requires no API key.

### ADR-C (revised): `LogseqMergeDriver` in `commonMain` — diff algorithm is pure Kotlin

The Python driver uses `difflib.SequenceMatcher.get_opcodes()`. In Kotlin commonMain we implement a
minimal LCS-based diff that produces the same opcode types (`EQUAL`, `INSERT`, `DELETE`, `REPLACE`).
A Java library (`java-diff-utils`) cannot be used in `commonMain`. The pure Kotlin LCS implementation
is ~80 lines and is fully unit-testable on all targets.

---

### E2-S1 — Port merge strategy hierarchy to Kotlin

**Title:** `LogseqMergeDriver` + 5 strategy classes (Kotlin port of Python merge driver)

**Acceptance criteria:**
- `MergeStrategy` interface: `fun canHandle(base, local, remote): Boolean` and `fun applyMerge(base, local, remote): List<String>` (all params `List<String>`)
- 5 strategy implementations, in priority order (highest first):
  1. `AndroidDataLossProtectionStrategy` — triggers when `remote.size < base.size * 0.7 && local.size >= base.size`; prioritizes local + appends remote-only new lines
  2. `SimpleAdditionMergeStrategy` — triggers when one side only appended lines at the end and the other is unchanged; returns the longer version
  3. `NonOverlappingChangeMergeStrategy` — triggers when all three files have equal line count; per-line three-way merge, emits `<<<<<<< LOCAL` markers on true conflicts
  4. `LogseqPageReferenceMergeStrategy` — triggers when any line contains `[[`, `#`, or `((` ; uses LCS diff opcodes for position-aware three-way merge (see algorithm note below)
  5. `FallbackMergeStrategy` — always handles; set-based union (local order first, then remote-only new lines appended)
- `LogseqMergeDriver` class: takes `List<MergeStrategy>` in constructor; `fun merge(base, local, remote): String` tries each in order
- All classes in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/merge/`

**Algorithm note for `LogseqPageReferenceMergeStrategy`:**
Implement `SequenceOp` sealed class `{ Equal(i1,i2,j1,j2), Insert(i1,j1,j2), Delete(i1,i2,j1), Replace(i1,i2,j1,j2) }` and `fun diff(a: List<String>, b: List<String>): List<SequenceOp>` using standard LCS dynamic programming. Map the opcodes to the same fate-map logic as the Python `apply_merge` method (local_fate, remote_fate, local_insertions, remote_insertions dictionaries → equivalent Kotlin Maps). Port the `seen_lines` deduplication set.

**Tasks:**

- T1: Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/merge/SequenceDiff.kt`:
  ```kotlin
  sealed class SequenceOp { ... }
  fun diff(a: List<String>, b: List<String>): List<SequenceOp>
  ```
  Implement LCS via `Array(a.size+1) { IntArray(b.size+1) }` DP, then traceback to produce ops.
- T2: Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/merge/MergeStrategy.kt` — interface only
- T3: Create the 5 strategy files (one file each) directly porting the Python logic line-for-line, using `diff()` from T1 wherever the Python uses `SequenceMatcher.get_opcodes()`
- T4: Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/merge/LogseqMergeDriver.kt`:
  ```kotlin
  class LogseqMergeDriver(
      private val strategies: List<MergeStrategy> = defaultStrategies()
  ) {
      fun merge(base: List<String>, local: List<String>, remote: List<String>): MergeResult
  }
  data class MergeResult(val lines: List<String>, val hasConflictMarkers: Boolean)
  ```
  `hasConflictMarkers` is `true` when any output line starts with `<<<<<<< LOCAL` — used by E2-S3 to decide whether to offer LLM enhancement.
- T5: Unit tests (`LogseqMergeDriverTest` in `businessTest`) porting the Python test fixtures:
  - `AndroidDataLossProtectionStrategy`: remote shrinks to 50% of base → returns local + remote-only new lines
  - `SimpleAdditionMergeStrategy`: remote identical to base, local has 3 new lines at end → returns local
  - `NonOverlappingChangeMergeStrategy`: equal-length files, no overlap → merged correctly
  - `LogseqPageReferenceMergeStrategy`: two devices both added journal bullets → merged, no duplicates, local-order-first
  - `FallbackMergeStrategy`: remote-only new lines appended after local
  - Journal file with identical additions on both devices → single copy in output (dedup)
  - Journal file where both sides changed the same line → conflict marker present; `hasConflictMarkers = true`

**Dependencies:** None (parallel with E1)

**Complexity:** L

---

### E2-S2 — `JournalMergeService` (algorithmic path + backup)

**Title:** Classify conflicts, run `LogseqMergeDriver`, produce `JournalMergeProposal`

**Acceptance criteria:**
- `fun isJournalFile(fileName: String): Boolean` — uses `Regex("""\d{4}[-_]\d{2}[-_]\d{2}\.md""")` (handles both `2026-06-12.md` and `2026_06_12.md`)
- `JournalMergeService.propose(conflictFile: ConflictFile, graphRoot: String): JournalMergeProposal`:
  1. Extracts LOCAL and REMOTE sides from git conflict markers
  2. Writes backup to `.stelekit-backup/<filename>-<epochMs>.md` (replaces the stash approach — git stash is blocked when MERGE_HEAD exists)
  3. Calls `LogseqMergeDriver.merge(base, local, remote)` — `base` is reconstructed from conflict markers or inferred as empty list
  4. Populates `JournalMergeProposal`
- `JournalMergeProposal` data class: `localContent: String`, `remoteContent: String`, `proposedMerge: String`, `confidenceWarning: Boolean`, `hasConflictMarkers: Boolean`, `backupPath: String`
- Confidence check: `proposedMerge.lines().size < maxOf(local.lines(), remote.lines()) * 0.9` → `confidenceWarning = true`

**Tasks:**

- T1: Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/merge/JournalMergeService.kt`
- T2: `extractConflictSides(content: String): Triple<String, String, String>` — parses `<<<<<<< `, `=======`, `>>>>>>> ` markers into `(base, local, remote)`. Base section (between `||||||| BASE` and `=======`) is optional (only present with `merge.conflictstyle=diff3`); if absent, use empty string.
- T3: `writeBackup(graphRoot: String, fileName: String, content: String): String` — writes to `$graphRoot/.stelekit-backup/<name>-<epochMs>.md`; creates parent dirs; returns the path
- T4: `propose()` implementation — extract sides, write backup, call `LogseqMergeDriver`, compute confidence, set `hasConflictMarkers` from `MergeResult.hasConflictMarkers`
- T5: Companion constant `val JOURNAL_FILENAME_REGEX = Regex("""\d{4}[-_]\d{2}[-_]\d{2}\.md""")`
- T6: Unit tests:
  - `isJournalFile` with both dash and underscore variants
  - `extractConflictSides` with a fixture containing `<<<<<<< / ======= / >>>>>>>` markers
  - `propose()` with a fixture where both devices added unique lines → `hasConflictMarkers = false`, all lines present
  - `propose()` confidence warning fires when output is < 90% of larger input

**Dependencies:** E2-S1

**Complexity:** M

---

### E2-S3 — `LlmEnhancementClient` (optional, only for residual conflict markers)

**Title:** Optional Anthropic API call to resolve lines that the algorithm couldn't merge

**Acceptance criteria:**
- `LlmEnhancementClient` interface: `suspend fun resolveConflictMarkers(textWithMarkers: String): Either<LlmError, String>`
- `AnthropicEnhancementClient(httpClient, apiKey, model)` — POST to `api.anthropic.com/v1/messages`
- System prompt: instructs the model to resolve only the `<<<<<<< LOCAL / >>>>>>> REMOTE` sections in the input, preserving all non-conflicting lines exactly
- Only called when `proposal.hasConflictMarkers == true` AND an API key is configured
- If the LLM call fails for any reason, the review screen still shows the algorithmic result with conflict markers — the LLM failure is surfaced as an informational toast, not a blocking error
- `LlmError` sealed: `TokenLimitExceeded`, `NetworkError(message)`, `ApiError(status, body)`, `Disabled`

**Tasks:**

- T1: Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/merge/LlmEnhancementClient.kt` — interface + `LlmError` + `AnthropicEnhancementClient`
- T2: Serializable request/response data classes (same shape as `AnthropicMergeClient` from the original plan — `AnthropicRequest`, `AnthropicMessage`, `AnthropicResponse`, `AnthropicContentBlock`)
- T3: `GitConfig` gains `llmApiKeyRef: String? = null` (key name in `CredentialStore` — never the key value itself); existing `GitConfig` JSON without this field deserializes safely
- T4: Unit tests with Ktor `MockEngine`: happy path, token limit, 401, network error

**Dependencies:** E2-S2

**Complexity:** M

---

### E2-S4 — `JournalMergeReviewScreen`

**Title:** 3-panel Compose review screen for algorithmically-merged journal

**Acceptance criteria:**
- Layout: LOCAL (read-only) | MERGED RESULT (editable) | REMOTE (read-only) — three equal columns on desktop, stacked on mobile
- Confidence warning banner shown when `proposal.confidenceWarning == true`: "The merged result is shorter than expected — some entries may have been lost. Review carefully."
- Conflict marker warning banner shown when `proposal.hasConflictMarkers == true`: "Some lines could not be merged automatically and are marked with <<<<<<< / >>>>>>>. Review and resolve these manually or use AI to resolve."
- "Resolve with AI" button (only when `hasConflictMarkers && llmApiKeyConfigured`) calls `LlmEnhancementClient.resolveConflictMarkers()`; replaces the merged result text on success; shows inline error on failure
- Three action buttons: "Accept", "Fall back to manual" (→ `ConflictResolutionScreen`)
- Back-gesture intercept: `AlertDialog("Abandon merge review?", "Discard" / "Continue editing")`

**Tasks:**

- T1: Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/JournalMergeReviewScreen.kt`
- T2: Implement three-column layout using `BoxWithConstraints` — if `maxWidth < 600.dp`, use `Column`; otherwise use `Row` with `weight(1f)` per panel
- T3: Conflict marker banner with "Resolve with AI" button (conditionally shown)
- T4: `BackHandler` with confirmation dialog
- T5: Wire "Accept" → `onAccept(editedMergeContent)` → `gitSyncService.resolveConflict()`
- T6: Screenshot tests: `confidenceWarning = true`, `hasConflictMarkers = true`, clean merge state

**Dependencies:** E2-S2, E2-S3

**Complexity:** L

---

### E2-S5 — Wire algorithmic path into `GitSyncService`; remove silent auto-resolve

**Title:** Route journal conflicts through `JournalMergeService`; all conflicts require user approval

**Acceptance criteria:**
- `GitSyncService.sync()` NEVER silently auto-resolves — every conflict produces `SyncState.ConflictPending` or `SyncState.JournalMergeReady`
- Journal conflicts (matching `JOURNAL_FILENAME_REGEX`) always go through `JournalMergeService.propose()` — algorithmic merge runs unconditionally (no config gate)
- `SyncState.JournalMergeReady(proposal: JournalMergeProposal)` added to `SyncState` sealed class
- Non-journal conflicts produce `SyncState.ConflictPending`
- `JournalMergeService` failure (any exception) falls back to `SyncState.ConflictPending` with a warning log

**Tasks:**

- T1: Add `SyncState.JournalMergeReady(proposal: JournalMergeProposal)` to `SyncState.kt`
- T2: Inject `JournalMergeService` into `GitSyncService`
- T3: In `GitSyncService.sync()` conflict handling:
  ```kotlin
  val journalConflicts = conflicts.filter { JournalMergeService.isJournalFile(it.fileName) }
  if (journalConflicts.isNotEmpty()) {
      val proposal = try { journalMergeService.propose(journalConflicts.first(), graphRoot) }
                     catch (e: Exception) { null }
      _syncState.value = if (proposal != null) SyncState.JournalMergeReady(proposal)
                         else SyncState.ConflictPending(conflicts)
  } else {
      _syncState.value = SyncState.ConflictPending(conflicts)
  }
  ```
- T4: Audit existing `GitSyncService` for silent conflict resolution paths — remove any `accept ours/theirs` auto-resolution
- T5: Handle `SyncState.JournalMergeReady` in `StelekitViewModel` → navigate to `JournalMergeReviewScreen`
- T6: Integration test with a mock `GitRepository` that returns a journal file conflict — assert `syncState.value is JournalMergeReady`

**Dependencies:** E2-S4

**Complexity:** L

---

## Epic 3: CLI Sync Command (FR-3)

### E3-S1 — `SyncResult` data class and `SyncMain.kt` skeleton

**Title:** Define CLI output model and entry-point file

**Acceptance criteria:**
- `SyncResult` data class matches FR-3.4 JSON shape: `graph: String`, `branch: String`, `localCommits: Int`, `remoteCommits: Int`, `conflicts: List<String>`, `status: String`
- `SyncMain.kt` compiles with a `fun main(args: Array<String>) = runBlocking { }` entry point
- The file is in package `dev.stapler.stelekit.cli`
- SIGINT shutdown hook is wired per stack research (cancel the top-level `Job`, `runBlocking` returns cleanly)

**Tasks:**

- T1: Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncMain.kt`:
  ```kotlin
  package dev.stapler.stelekit.cli

  fun main(args: Array<String>) {
      val rootJob = Job()
      Runtime.getRuntime().addShutdownHook(Thread {
          rootJob.cancel()
          runBlocking { rootJob.join() }
      })
      runBlocking(rootJob) {
          try {
              runSync(args)
          } catch (e: CancellationException) {
              // clean SIGINT path
          }
      }
  }
  ```
- T2: Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncResult.kt`:
  ```kotlin
  @Serializable
  data class SyncResult(
      val graph: String,
      val branch: String,
      val localCommits: Int,
      val remoteCommits: Int,
      val conflicts: List<String> = emptyList(),
      val status: String  // "success" | "conflicts" | "auth_error" | "network_error" | "no_config" | "error"
  )
  ```
- T3: Add stub `suspend fun runSync(args: Array<String>): Unit` in `SyncMain.kt` (implemented in E3-S2 and E3-S3)

**Dependencies:** None

**Complexity:** XS

---

### E3-S2 — Argument parsing

**Title:** Implement CLI argument parsing (manual, per ADR-B recommendation)

**Acceptance criteria:**
- Parses `--graph <path>`, `--commit-only`, `--fetch-only`, `--dry-run`, `--json`, `--help`
- `--help` prints usage to stdout and exits 0
- Unknown flag prints error to stderr and exits 5
- `--graph` without a subsequent value prints error and exits 5
- `--commit-only` and `--fetch-only` are mutually exclusive; if both present, exits 5 with an error message
- Parsed values exposed as a `SyncArgs` data class

**Tasks:**

- T1: Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncArgs.kt`:
  ```kotlin
  data class SyncArgs(
      val graphPath: String?,       // null = use last opened graph from settings
      val commitOnly: Boolean = false,
      val fetchOnly: Boolean = false,
      val dryRun: Boolean = false,
      val jsonOutput: Boolean = false,
  )
  ```
- T2: Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/ArgParser.kt` with `fun parseArgs(args: Array<String>): SyncArgs`:
  - Iterate args with an index; on `--graph` advance index to consume the next token
  - Map each flag to the corresponding field
  - Throw `ArgParseException(message: String, exitCode: Int)` for all error conditions
- T3: In `runSync()`, call `parseArgs(args)` wrapped in a try/catch for `ArgParseException`; on catch, print to `System.err` and `exitProcess(e.exitCode)`
- T4: Unit tests for `parseArgs`:
  - `--graph /tmp/notes` → `SyncArgs(graphPath = "/tmp/notes")`
  - `--commit-only --fetch-only` → throws `ArgParseException`
  - No args → `SyncArgs(graphPath = null)`

**Dependencies:** E3-S1

**Complexity:** S

---

### E3-S3 — `GitSyncService` wiring in CLI

**Title:** Construct the service graph in CLI main and call sync/commit/fetch

**Acceptance criteria:**
- CLI constructs `JvmGitRepository`, `GraphManager`, `GitConfigRepository`, `JvmCredentialStore`, `GitSyncService` without starting Compose
- `--graph` path is used directly; if absent, reads `lastOpenedGraphPath` from `platformSettings` (same settings store as the GUI app)
- `--commit-only` calls `gitSyncService.commitLocalChanges(graphId)` only
- `--fetch-only` calls `gitSyncService.fetchOnly(graphId)` only
- Default (neither flag) calls `gitSyncService.sync(graphId)`
- `--dry-run` prints what would happen (calls `fetchOnly` to get remote commit count, calls `gitRepository.status()` for local changes) but does not commit, merge, or push
- If `graphId` has no `GitConfig`, exits with code 4

**Tasks:**

- T1: In `SyncMain.kt`, implement `suspend fun buildServiceGraph(graphPath: String): CliServiceGraph` returning a data class holding `gitSyncService`, `graphId`, `gitConfigRepository`, `httpClient`
  - Construct: `platformFileSystem = PlatformFileSystem()`, `jvmGitRepository = JvmGitRepository(platformFileSystem)`, `settings = createSettings()` (match how `Main.kt` creates settings — check `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` for the pattern)
  - `graphManager = GraphManager(settings, driverFactory, fileSystem)` — note: CLI does not need a `DriverFactory` that opens a UI database; use `InMemoryDriverFactory` or pass `null` if `GraphManager` supports a read-only mode; **examine `GraphManager` constructor to determine if a full `DriverFactory` is required and whether any DB operations run before sync**
  - Construct `gitSyncService` with injected `JvmCredentialStore`
- T2: Implement the `--dry-run` path:
  - Call `gitRepository.status(config)` to count modified local files
  - Call `gitSyncService.fetchOnly(graphId)` to determine `remoteCommits`
  - Print a "Would commit N local changes, merge M remote commits" message
  - Exit 0
- T3: Handle `SyncState.ConflictPending` as exit code 1; print conflict file paths to `System.err`
- T4: Handle `DomainError.GitError` subtypes and map to exit codes per FR-3.2:
  - Auth error → exit 2
  - Network error → exit 3
  - No config → exit 4
  - Other → exit 5

**Dependencies:** E3-S2

**Complexity:** L

---

### E3-S4 — Output formatting (human-readable and JSON)

**Title:** Implement `--json` and human-readable stdout formats

**Acceptance criteria:**
- Human-readable output matches FR-3.3 format with `[stelekit-sync]` prefix on each line
- `--json` outputs a single JSON object matching FR-3.4 shape (uses `SyncResult`)
- All informational output goes to stdout; all errors and conflict paths go to stderr
- `--json` mode suppresses all human-readable lines; only the final JSON object is written to stdout

**Tasks:**

- T1: Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncOutput.kt`:
  ```kotlin
  class SyncOutput(private val jsonMode: Boolean) {
      fun info(message: String) { if (!jsonMode) println("[stelekit-sync] $message") }
      fun error(message: String) { System.err.println("[stelekit-sync] ERROR: $message") }
      fun result(result: SyncResult) {
          if (jsonMode) println(Json.encodeToString(result))
      }
  }
  ```
- T2: Thread `SyncOutput` through `runSync()` and all sub-calls; replace all `println` calls with `output.info()`
- T3: At the end of `runSync()`, construct `SyncResult` from the sync outcome and call `output.result(it)`
- T4: Integration-level test: capture stdout/stderr with `System.setOut`/`System.setErr`; assert JSON mode emits valid JSON; assert human mode emits lines with `[stelekit-sync]` prefix

**Dependencies:** E3-S3

**Complexity:** S

---

### E3-S5 — Gradle task, `JvmCredentialStore` headless fallback

**Title:** Wire `./gradlew :kmp:runSync` task and fix headless Linux credential lookup

**Acceptance criteria:**
- `./gradlew :kmp:runSync --args="--graph /tmp/test-notes"` executes the CLI and exits
- `./gradlew :kmp:runSync --args="--help"` prints usage and exits 0
- On headless Linux (no D-Bus session), `JvmCredentialStore` falls back to env var `STELEKIT_GIT_TOKEN` before failing
- `JvmCredentialStore` logs a debug-level message when falling back from OS keychain to env var (not silent)
- `JvmCredentialStore` emits a clear error message (`Error: No credential store available. Set STELEKIT_GIT_TOKEN environment variable.`) when all fallbacks are exhausted

**Tasks:**

- T1: In `kmp/build.gradle.kts`, register a `JavaExec` task (per ADR-A resolution):
  ```kotlin
  tasks.register<JavaExec>("runSync") {
      group = "application"
      description = "Run the SteleKit headless sync CLI"
      classpath = kotlin.jvm().compilations["main"].output.allOutputs +
                  kotlin.jvm().compilations["main"].runtimeDependencyFiles
      mainClass.set("dev.stapler.stelekit.cli.SyncMainKt")
      args = (project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotBlank() }
  }
  ```
- T2: Edit `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/git/JvmCredentialStore.kt`:
  - In `get(key)`, wrap the OS keychain call in `try/catch BackendNotSupportedException`
  - On catch: log `logger.debug("OS keychain unavailable, checking environment variable for key $key")`
  - Check `System.getenv("STELEKIT_GIT_TOKEN")` (and a per-key mapping if multiple credentials are stored)
  - If still null: log `logger.error("No credential found for $key. Set STELEKIT_GIT_TOKEN.")` and return `null`
- T3: Unit test `JvmCredentialStoreHeadlessFallbackTest`:
  - Mock `BackendNotSupportedException` on keychain call
  - Set `STELEKIT_GIT_TOKEN=test-token` in test environment
  - Assert `credentialStore.get("httpsToken")` returns `"test-token"`

**Dependencies:** E3-S3, E3-S4

**Complexity:** M

---

## Story Complexity Summary

| Story | Title | Complexity |
|---|---|---|
| E1-S1 | `GraphInfo` field additions | XS |
| E1-S2 | `detectGitRepo()` in `GraphManager` | M |
| E1-S3 | Emit updated `GraphInfo` on `graphsFlow` | S |
| E1-S4 | `GitDetectionBanner` composable | M |
| E1-S5 | Pre-fill `GitSetupScreen` from detection | S |
| E1-S6 | Persist `gitDetectionDismissed` flag | XS |
| E2-S1 | `LogseqMergeDriver` + 5 strategy classes (Kotlin port) | L |
| E2-S2 | `JournalMergeService` (algorithmic path + backup) | M |
| E2-S3 | `LlmEnhancementClient` (optional, conflict markers only) | M |
| E2-S4 | `JournalMergeReviewScreen` | L |
| E2-S5 | Wire algorithmic path in `GitSyncService` | L |
| E3-S1 | `SyncResult` + `SyncMain.kt` skeleton | XS |
| E3-S2 | Argument parsing | S |
| E3-S3 | `GitSyncService` wiring in CLI | L |
| E3-S4 | Output formatting | S |
| E3-S5 | Gradle task + headless credential fallback | M |

---

## Implementation Order (Critical Path)

The minimum viable path for each epic, ordered for least rework:

**Epic 1 (can start immediately):**
E1-S1 → E1-S2 → E1-S3 → E1-S4 → E1-S5 (parallel with E1-S6 from E1-S4)

**Epic 2 (no ADR-C required — algorithmic path is all in commonMain):**
E2-S1 → E2-S2 → E2-S3 (parallel with E2-S4) → E2-S5

**Epic 3 (resolve ADR-A and ADR-B first):**
E3-S1 → E3-S2 → E3-S3 (parallel with E3-S4) → E3-S5

All three epics are independent at their start. Epic 2's E2-S7 depends on the `GitSyncService` but does not depend on Epic 1 or 3.

---

## Key Files — Quick Reference

| File (new) | Epic | Description |
|---|---|---|
| `commonMain/.../model/GraphInfo.kt` | E1 | Add 3 new fields |
| `commonMain/.../db/GraphManager.kt` | E1 | Add `detectGitRepo()`, `updateGraphInfoDetection()`, `setGitDetectionDismissed()` |
| `commonMain/.../ui/components/git/GitDetectionBanner.kt` | E1 | New composable |
| `commonMain/.../git/model/LlmMergeProvider.kt` | E2 | New sealed class |
| `commonMain/.../git/model/GitConfig.kt` | E2 | Add `llmMergeProvider` field |
| `commonMain/.../git/model/SyncState.kt` | E2 | Add `JournalMergeReady` variant |
| `commonMain/.../git/llm/LlmMergeClient.kt` | E2 | New interface + `LlmError` |
| `commonMain/.../git/llm/AnthropicMergeClient.kt` | E2 | New class |
| `commonMain/.../git/llm/OllamaMergeClient.kt` | E2 | New class |
| `commonMain/.../git/llm/JournalMergeService.kt` | E2 | New class |
| `commonMain/.../ui/screens/git/JournalMergeReviewScreen.kt` | E2 | New screen |
| `commonMain/.../git/GitSyncService.kt` | E2 | Modify conflict routing |
| `jvmMain/.../cli/SyncMain.kt` | E3 | New CLI entry point |
| `jvmMain/.../cli/SyncArgs.kt` | E3 | New data class |
| `jvmMain/.../cli/ArgParser.kt` | E3 | New arg parser |
| `jvmMain/.../cli/SyncResult.kt` | E3 | New data class |
| `jvmMain/.../cli/SyncOutput.kt` | E3 | New output formatter |
| `jvmMain/.../git/JvmCredentialStore.kt` | E3 | Modify — headless fallback |
| `kmp/build.gradle.kts` | E3 | Add `runSync` task |
