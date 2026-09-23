# Git Smart Sync — Adversarial Implementation Review

_Reviewer: Adversarial Technical Review_
_Date: 2026-06-12_
_Sources examined: requirements.md, plan.md, research/pitfalls.md, research/architecture.md, actual codebase (GraphInfo.kt, GitSyncService.kt, GraphManager.kt, SyncState.kt, GitConfig.kt, ConflictModels.kt, JvmGitRepository.kt, Main.kt, build.gradle.kts)_

---

## Verdict: CONCERNS

No single issue is a project-stopper, but five concerns require resolution before implementation starts to avoid mid-sprint rework. The plan is architecturally sound, but several of its assumptions about the existing codebase are wrong or incomplete.

---

## Critical Blockers (must fix before implementation starts)

### BLOCKER-1: `GraphManager` does not accept `GitRepository` as a constructor parameter — E1-S2 cannot be implemented as specified

**What it is:** E1-S2 calls for adding `detectGitRepo()` to `GraphManager`, including a `launch(PlatformDispatcher.IO)` block in `addGraph()` that calls `gitRepository.isGitRepo()`. The architecture research (§5) also describes injecting `GitRepository` into `GraphManager`.

**Why it blocks:** `GraphManager`'s actual constructor takes only `Settings`, `DriverFactory`, `FileSystem`, and optional `defaultBackend` + `preFlightJob`. `GitRepository` is used nowhere in the constructor; it appears only in `cloneAndAdd(gitRepository: GitRepository, ...)` as a method parameter. Adding detection inside `addGraph()` therefore requires either (a) adding `GitRepository` as a constructor parameter — which changes every call site (`App.kt` line 183, CLI construction, tests) — or (b) passing it into a new `addGraph(path, gitRepository)` overload, which forces every existing `addGraph()` call to change.

**How to fix:** Before E1-S1, decide and document the injection strategy:
- Option A: Add `private val gitRepository: GitRepository? = null` to the `GraphManager` constructor and update all construction sites.
- Option B: Pass detection out of `GraphManager` entirely — after `addGraph()` returns, let the call site (App.kt composable, CLI main) run detection with the `GitRepository` it already holds, then call a new `graphManager.updateGraphInfoDetection(graphId, repoRoot, wikiSubdir)` method. This is architecturally cleaner and requires zero change to `GraphManager`'s constructor.

Option B matches the architecture research's footnote ("The cleanest approach is either (a) inject... or (b) have the call site run the detection separately") but the plan body ignores the footnote and specifies option A tasks without acknowledging the constructor change required.

---

### BLOCKER-2: `ConflictFile` has no `content` field — `JournalMergeService.extractConflictSides()` cannot read conflict markers as specified

**What it is:** E2-S5 specifies `extractConflictSides(conflictContent: String)` that parses git conflict markers from `ConflictFile.content`. The actual `ConflictFile` model is:

```kotlin
data class ConflictFile(
    val filePath: String,
    val wikiRelativePath: String,
    val hunks: List<ConflictHunk>,
)
```

There is no `content` field. The conflict file's raw text with conflict markers is not stored on the model — the markers have already been parsed into `ConflictHunk` objects (each hunk has `localLines: List<String>` and `remoteLines: List<String>`).

**Why it blocks:** The entire LLM path in E2-S5/E2-S7 depends on reconstructing LOCAL and REMOTE versions from conflict markers. This either means reconstructing from `ConflictHunk.localLines` / `ConflictHunk.remoteLines` (no markers needed), or re-reading the raw file from disk. The plan's `extractConflictSides(conflictContent: String)` parsing approach is moot — the data is already parsed. All of E2-S5 T2 and T6's fixture tests need to be redesigned before work begins.

**How to fix:** Reconstruct the full LOCAL and REMOTE strings from `ConflictHunk` lists:
```kotlin
fun localContent(file: ConflictFile): String = file.hunks.joinToString("\n") { it.localLines.joinToString("\n") }
fun remoteContent(file: ConflictFile): String = file.hunks.joinToString("\n") { it.remoteLines.joinToString("\n") }
```
Or add a `rawContent: String` field to `ConflictFile` if the raw-marker version is needed for the backup copy. Either way the plan must be updated before E2-S5 starts.

---

### BLOCKER-3: `GraphManager` exposes no `activeGraph: StateFlow<GraphInfo?>` — E1-S4's banner wiring is broken

**What it is:** E1-S4 T2 references `graphManager.activeGraph.collectAsState()` to get the current `GraphInfo` and drive the banner's visibility. `GraphManager` has no such property. Its public API exposes:
- `graphRegistry: StateFlow<GraphRegistry>` — the full registry
- `getActiveGraphId(): String?` — synchronous, not a flow
- `getActiveGraphInfo(): GraphInfo?` — synchronous, not a flow

**Why it blocks:** The composable code in E1-S4 T2 as written will not compile. The developer who implements this will have to derive `activeGraph` by combining `graphRegistry` and `activeGraphId`, or a new derived flow must be added to `GraphManager`. This is a straightforward fix but it affects the composable's collection pattern and the test (E1-S3 T3) that observes the flow.

**How to fix:** Add a derived `val activeGraph: StateFlow<GraphInfo?>` to `GraphManager`:
```kotlin
val activeGraph: StateFlow<GraphInfo?> = graphRegistry
    .map { it.graphs.firstOrNull { g -> g.id == it.activeGraphId } }
    .stateIn(coroutineScope, SharingStarted.Eagerly, null)
```
Then update E1-S4 T2 to use this property.

---

## Significant Concerns (should fix before implementation, but won't block if explicitly tracked)

### CONCERN-1: CLI `buildServiceGraph()` requires a `DriverFactory` — `InMemoryDriverFactory` does not exist

E3-S3 T1 mentions passing `InMemoryDriverFactory` to `GraphManager` in the CLI. `InMemoryDriverFactory` does not exist in the codebase. `DriverFactory` is an `expect class` with platform-specific `actual` implementations. In the CLI context, `GraphManager` needs a `DriverFactory` to create `SqlDelightGitConfigRepository` (via `createGitConfigRepository()` → `currentFactory`). This means the CLI either:
1. Uses the real `DriverFactory()` and opens a full SQLite database (which then conflicts with the plan's claim that "CLI does not open a database connection").
2. Cannot call `createGitConfigRepository()` and therefore cannot read `GitConfig`, defeating the whole CLI.

The FR-3.5 claim ("it only runs git operations") contradicts the reality that `GitConfig` (stored in SQLDelight) must be read to know where the git repo is. The CLI needs the DB to read `GitConfig`. The plan must acknowledge this and wire a real `DriverFactory`, or provide an alternative config-loading path (e.g., read `GitConfig` from a separate JSON file next to the graph).

**Risk:** High. Without a real `DriverFactory`, the CLI cannot read its config and cannot sync.
**Mitigation:** Remove the "no DB connection" claim from FR-3.5. Have the CLI construct `GraphManager` with a real `DriverFactory()`, call `openGraph(path)` (which already exists and runs migrations), then call `createGitConfigRepository()`. Accept that the CLI opens a database.

---

### CONCERN-2: E2-S7 handles only the first journal conflict file — silently drops the rest as `ConflictPending`

The plan's E2-S7 pseudocode processes `journalConflicts.first()` and then emits either `JournalMergeReady` or `ConflictPending(conflicts)` with the entire original conflict list. When there are two journal conflicts, the LLM path processes only one and the other is included in the `ConflictPending` list — but the screen that handles `JournalMergeReady` has no way to know that additional conflicts are pending. After the user approves the merge and the UI clears `JournalMergeReady`, there is no code path to re-emit `ConflictPending` for the remaining files.

**Risk:** Medium. A user with two journal conflicts on the same date (e.g., morning and evening journal pages) will silently lose the second conflict or get stuck in an unresolvable state.
**Mitigation:** Queue all journal conflicts for sequential review. Add a `remainingConflicts: List<ConflictFile>` field to `JournalMergeReady`. After user accepts, re-enter the conflict routing loop. Document this explicitly as a known v1 limitation in the code.

---

### CONCERN-3: `okio` is not on the classpath — `JournalMergeService.writeBackup()` cannot use it

E2-S5 T3 specifies using `okio.FileSystem` for the backup write. A grep of `build.gradle.kts` finds no `okio` dependency. The existing platform abstraction is `dev.stapler.stelekit.platform.FileSystem` (used throughout the codebase). Using `java.io.File` directly would work on JVM but not in `commonMain` (where `JournalMergeService` is placed per the file table).

**Risk:** Medium. If the developer assumes okio is available and writes the code, it will fail to compile. If they use `java.io.File` instead, it breaks on non-JVM targets.
**Mitigation:** Use the injected `dev.stapler.stelekit.platform.FileSystem` instead of okio. `FileSystem` already supports `writeFile(path, content)`. Inject it into `JournalMergeService`.

---

### CONCERN-4: Ktor `MockEngine` module needs explicit test dependency declaration — E2-S3/E2-S4 unit tests will fail to compile

`ktor-client-mock` is declared in `build.gradle.kts` at line 147, but only for a single source set. The plan creates `AnthropicMergeClient` and `OllamaMergeClient` in `commonMain` and tests them in `businessTest`. The `ktor-client-mock` module must be declared in the correct test source set (`businessTest` or `commonTest`). If the plan assumes it's already available but it's only scoped to `jvmTest`, the `MockEngine` imports will resolve only for JVM tests, not for cross-platform business tests.

**Risk:** Low-Medium. Test compilation will fail silently on first run if the source-set scoping is wrong.
**Mitigation:** Explicitly add `ktor-client-mock` to the `businessTest` source set in the Gradle task list for E2-S3.

---

### CONCERN-5: `AnthropicMergeClient` is placed in `commonMain` but the `HttpClient` instance construction is underspecified for iOS

The plan says the `LlmMergeClient` interface and implementations live in `commonMain` per ADR-C. iOS already has `ktor-client-darwin:3.1.3` declared. However, `AnthropicMergeClient` accepts `HttpClient` by injection, and the factory for constructing it (`buildHttpClient()` via expect/actual) is marked "Decision required before E2-S3" but no actual task creates this factory. If the factory is never implemented for iOS, the iOS build will fail at the injection site.

ADR-C says "actual `HttpClient` construction via an `expect`/`actual` pattern or injected factory" but no story creates this expect/actual. The plan assumes it will "just work" because Ktor handles the platform difference — but only if the `HttpClient` is constructed with the right engine. On iOS without an explicit engine, Ktor throws `IllegalStateException: Failed to find HttpClientEngineFactory`.

**Risk:** Medium for iOS builds. Low for the v1 scope if iOS is not a target for LLM merge.
**Mitigation:** Add an explicit task in E2-S3 to either (a) create an `expect fun createLlmHttpClient(): HttpClient` with `actual` implementations in `jvmMain`/`androidMain`/`iosMain`, or (b) explicitly scope `LlmMergeClient` implementations to `jvmMain` and `androidMain` only.

---

### CONCERN-6: `resolveConflictBySide()` silently auto-resolves conflicts without user interaction — this is the "silent auto-resolve" E2-S7 claims to remove

E2-S7 T1 says to "audit `GitSyncService.kt` for any code path where conflicts are resolved without user interaction." `resolveConflictBySide(graphId, fileResolutions: Map<String, MergeSide>)` does exactly this: it accepts a `MergeSide.LOCAL` or `MergeSide.REMOTE` choice for each file and commits the merge. This is called from `ConflictResolutionScreen`, which is user-initiated, so it is not "silent." However, if any call site ever calls it programmatically (background scheduler, automated test flows), it would silently resolve.

More importantly, the E2-S7 audit must verify that this method is only reachable through explicit user action. The plan does not identify this method as requiring review, which means the audit may miss it.

**Risk:** Low. It is not currently called automatically, but the audit is incomplete as specified.
**Mitigation:** Add `resolveConflictBySide` to the explicit audit list in E2-S7 T1.

---

## Minor Issues

- **E1-S2 T5**: "confirm where `Platform` detection lives in the codebase" — this is undone research left to the implementer. The SAF check using `startsWith("content://")` is already the right pattern (confirmed in research pitfalls), but the `expect`/`actual` reference to `Platform.isAndroid` is speculative. The codebase uses `withContext(PlatformDispatcher.IO)` with target-specific `actual` classes, not a `Platform.isAndroid` flag. The SAF guard should be an `expect`/`actual` function or a simple string check; do not fabricate a `Platform` singleton.

- **E2-S3 T2**: The `AnthropicRequest` data class uses `system` as a top-level field. The comment "note: `system` is a top-level field in the messages API" is correct. However, `maxTokens = 4096` is hardcoded in T3 — this should be configurable (FR-2.4 mentions configurable max tokens per version, not max output tokens). These are different: `maxTokensPerVersion` gates input, while the API's `max_tokens` controls output length. A merged journal might easily exceed 4096 output tokens for a very active day. This should be configurable or set higher (8192 minimum).

- **E2-S1 T1**: `val model: String = "claude-sonnet-4-6"` — the pitfalls research confirms this model ID is current, but hardcoding a default that references a specific model version is fragile. Acceptable for v1 with a comment noting the model should be updated on release.

- **E3-S2**: The `ArgParseException` catching in `runSync()` uses `exitProcess(e.exitCode)` which does not give coroutines a chance to clean up. The SIGINT shutdown hook in E3-S1 uses `rootJob.cancel() + join()` for clean shutdown, but `exitProcess` bypasses this. For the error path this is acceptable (there's nothing to clean up before parsing), but it should be noted.

- **E3-S5 T1**: The `args` property split using `(project.findProperty("args") as String? ?: "").split(" ")` will break on paths with spaces (e.g., `--graph "/home/user/my notes"`). Gradle args passing has this limitation; the task implementation should note this and suggest using `--args='--graph=/home/user/my\ notes'` syntax or use `args = listOf(...)` with `project.findProperty` cast to handle arrays.

- **E1-S4 T3**: `viewModel.dismissGitDetection(activeGraph!!.id)` — the `!!` is a crash risk in a Compose lambda. By the time the dismiss lambda executes, `activeGraph` could have changed to null if the graph was switched. Use `val graphId = activeGraph?.id ?: return@GitDetectionBanner` inside the lambda instead.

- **Plan references `graphsFlow`** (E1-S3) but the actual property is `graphRegistry: StateFlow<GraphRegistry>`. The field name discrepancy will confuse implementers. All references to `graphsFlow` in the plan should be corrected to `graphRegistry`.

---

## Requirements Coverage Check

| FR | Status | Notes |
|---|---|---|
| FR-1.1 (traverse parent dirs for .git) | PARTIAL | Detection logic in E1-S2 is sound, but BLOCKER-1 means the injection point is wrong — `GraphManager.addGraph()` cannot call `gitRepository.isGitRepo()` without constructor change |
| FR-1.2 (`detectedRepoRoot`, `detectedWikiSubdir` on `GraphInfo`) | COVERED | E1-S1 adds these fields; confirmed `GraphInfo` does not have them yet |
| FR-1.3 (one-time dismissible banner) | COVERED | E1-S4 addresses this; minor wiring issue with `activeGraph` flow (BLOCKER-3) |
| FR-1.4 (banner shown only once, suppressed if GitConfig exists) | COVERED | E1-S4 condition checks both `gitConfig == null` and `!gitDetectionDismissed` |
| FR-1.5 (Android SAF guard) | PARTIAL | SAF guard is correctly specified but uses an undefined `Platform.isAndroid` reference (minor issue above) |
| FR-2.1 (classify journal vs non-journal files) | COVERED | E2-S5 uses the corrected `[-_]` regex per pitfalls research |
| FR-2.2 (journal conflict → LLM merge → review screen) | PARTIAL | BLOCKER-2 means `extractConflictSides()` from `ConflictFile.content` cannot work as specified; needs redesign |
| FR-2.3 (non-journal → ConflictResolutionScreen) | COVERED | E2-S7 routes non-journal conflicts to `ConflictPending` |
| FR-2.4 (LLM prompt specification) | COVERED | E2-S3 specifies SYSTEM_PROMPT matching FR-2.4 |
| FR-2.5 (LlmMergeProvider sealed class with Disabled default) | COVERED | E2-S1 creates the sealed class and adds to `GitConfig` |
| FR-2.6 (confidence warning if merged < 90% of max) | COVERED | E2-S5 implements the line-count check |
| FR-2.7 (undo/safety — backup file, not stash) | COVERED | Pitfalls research correctly identified stash infeasibility; E2-S5 uses backup-file approach |
| FR-3.1 (`:kmp:runSync` Gradle task + CLI flags) | COVERED | E3-S1 through E3-S5 cover all flags |
| FR-3.2 (exit codes 0–5) | COVERED | E3-S3 T4 maps error types to exit codes |
| FR-3.3 (human-readable stdout format) | COVERED | E3-S4 implements `[stelekit-sync]` prefix format |
| FR-3.4 (JSON output) | COVERED | E3-S1 defines `SyncResult`; E3-S4 emits it in JSON mode |
| FR-3.5 (CLI reuses GitSyncService, JvmGitRepository, CredentialStore) | PARTIAL | Reuse is correct, but "does not open a database connection" claim is false — CONCERN-1 |
| FR-3.6 (thin `main()` in jvmMain, no Compose) | COVERED | E3-S1/E3-S3 implement this correctly |

---

## Assumptions That Might Break

1. **"GraphManager.addGraph() at line ~244 adds detection"** — the actual `addGraph()` ends at line 263 with `return graphId`. The `withContext(PlatformDispatcher.IO)` block ends at line 244 (`isParanoidMode = isParanoidMode`). The launch point is correct, but the instruction to add a `launch` "after the existing IO block" is accurate if interpreted as after the `val (displayName, isParanoidMode)` block. No mismatch, but the line reference will mislead.

2. **"GraphManager.loadRegistry() at line ~60 uses `Json { ignoreUnknownKeys = true }`"** — VERIFIED CORRECT. Line 60: `private val json = Json { ignoreUnknownKeys = true }`. The plan's T2 check in E1-S1 can be closed as already done.

3. **"`GitSyncService.sync()` never silently auto-resolves"** — VERIFIED CORRECT. The actual `GitSyncService` emits `SyncState.ConflictPending(mergeResult.conflicts)` and returns `conflictErr.left()` when conflicts are detected (lines 163–168). There is no silent resolution in the current implementation. E2-S7 T1's audit will find nothing to remove — but should still verify `resolveConflictBySide()` (CONCERN-6).

4. **"`_graphRegistry` is a `MutableStateFlow` and `saveRegistry()` updates it"** — VERIFIED CORRECT. `saveRegistry()` at line 221 calls `json.encodeToString(_graphRegistry.value)` but does NOT update `_graphRegistry.value` — it only persists to `platformSettings`. For `updateGraphInfoDetection` to emit on the flow, it must explicitly set `_graphRegistry.value = updatedRegistry`. The plan's E1-S3 T2 says "confirm `updateGraphInfoDetection` calls `saveRegistry()` which internally does `_graphRegistry.value = newRegistry`" — this is WRONG. `saveRegistry()` does not update the flow; the caller must. Any `updateGraphInfoDetection` implementation must explicitly set `_graphRegistry.value` before calling `saveRegistry()`.

5. **"The CLI constructs `GraphManager` without a full DriverFactory"** — BROKEN. As detailed in CONCERN-1, `GitConfig` is stored in SQLDelight and requires a real database connection. The plan's claim is architecturally inconsistent with the implementation.

6. **"The `GitDetectionBanner` can observe `gitConfigRepository.observeConfig(activeGraph?.id)`"** — the plan references `gitConfigRepository` as if it is available in the composable scope. `GitConfigRepository` is created via `graphManager.createGitConfigRepository()` which requires `currentFactory != null` (i.e., a graph must be loaded). If this is called before graph load completes, it returns null. The banner's condition logic must handle a null `gitConfigRepository`.

7. **"JournalMergeReviewScreen uses `BackHandler`"** — the codebase uses `PlatformBackHandler` (an `expect`/`actual` composable defined in `commonMain/kotlin/dev/stapler/stelekit/ui/PlatformBackHandler.kt`), not `BackHandler`. Using `BackHandler` from `androidx.activity.compose` will fail on desktop. The screen must use `PlatformBackHandler` to match the existing pattern.
