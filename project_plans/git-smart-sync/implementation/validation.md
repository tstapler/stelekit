# Git Smart Sync — Validation Plan

_Generated from: requirements.md, implementation/plan.md, research/pitfalls.md, existing test structure_

---

## Requirements Coverage Matrix

| FR-ID | Description | Test Case IDs | Coverage |
|---|---|---|---|
| FR-1.1 | Parent-directory walk up to depth 10 finds `.git` | TC-E1-001, TC-E1-002 | Full |
| FR-1.2 | `GraphInfo.detectedRepoRoot` and `detectedWikiSubdir` populated; detection non-blocking | TC-E1-003, TC-E1-004 | Full |
| FR-1.3 | Bottom sheet shown once after graph loads | TC-E1-005, TC-E1-006 | Full |
| FR-1.4 | Banner suppressed when `GitConfig` exists or `gitDetectionDismissed` | TC-E1-007, TC-E1-008 | Full |
| FR-1.5 | SAF `content://` URI skips detection; filesystem paths proceed | TC-E1-002 | Full |
| FR-2.1 | Journal filename classification: dash and underscore | TC-E2-001, TC-E2-002, TC-E2-003 | Full |
| FR-2.2 | Journal conflict → LLM path → JournalMergeReviewScreen (not auto-applied) | TC-E2-004, TC-E2-005, TC-E2-010 | Full |
| FR-2.3 | Non-journal conflicts fall through to `ConflictResolutionScreen` | TC-E2-003 | Full |
| FR-2.4 | System prompt matches spec; user message delimited with markers; token limit fallback | TC-E2-009, TC-E2-004 | Full |
| FR-2.5 | `LlmMergeProvider.Disabled` suppresses LLM; existing `GitConfig` JSON deserializes cleanly | TC-E2-004, TC-E2-011 | Full |
| FR-2.6 | Confidence warning shown when merged < `max(local, remote) * 0.9` lines | TC-E2-006 | Full |
| FR-2.7 (revised) | Backup file written before LLM merge applied; restores cleanly | TC-E2-007, TC-E2-008 | Full |
| FR-3.1 | `--graph`, `--commit-only`, `--fetch-only`, `--dry-run`, `--json`, `--help` parsed | TC-E3-006, TC-E3-007, TC-E3-008 | Full |
| FR-3.2 | Exit codes 0–5 emitted correctly | TC-E3-001, TC-E3-002, TC-E3-003, TC-E3-004, TC-E3-005 | Full |
| FR-3.3 | Human-readable stdout format with `[stelekit-sync]` prefix | TC-E3-001 | Full |
| FR-3.4 | `--json` outputs valid JSON matching `SyncResult` shape | TC-E3-006 | Full |
| FR-3.5 | CLI constructs service graph without Compose; reuses `JvmCredentialStore` | TC-E3-001, TC-E3-009 | Full |
| FR-3.6 | CLI does not open a database connection | TC-E3-001 | Partial (verify via absence of `DriverFactory` construction) |
| SC-1 | Git-tracked folder shows banner; tapping opens `GitSetupScreen` pre-filled | TC-E1-005, TC-E1-006 | Full |
| SC-2 | LLM-merged result contains entries from both devices | TC-E2-005 | Full |
| SC-3 | No journal conflict silently discarded | TC-E2-004, TC-E2-009 | Full |
| SC-4 | `stelekit-sync --graph ~/notes` exits 0 after full sync | TC-E3-001 | Full |
| SC-5 | `stelekit-sync --fetch-only --json` outputs valid JSON with `remoteCommits` | TC-E3-006 | Full |
| SC-6 | Conflicts → exit code 1, conflict paths to stderr | TC-E3-002 | Full |

---

## Test Cases

### Epic 1: Git Repo Auto-Detection

---

#### TC-E1-001 — Normal filesystem path: `.git` found within depth limit

- **Requirement:** FR-1.1, FR-1.2
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `GitDetectionTest.kt` in `dev.stapler.stelekit.db`

**Arrange:**
```
tmpDir/
  .git/           ← repo root
  notes/
    journal/      ← graphPath (depth 2 from repo root)
```
Create temp directories. Stub `FileSystem.directoryExists()` to return `true` for `tmpDir/.git` only.

**Act:**
Call `findGitRootJvm(graphPath = "$tmpDir/notes/journal", maxDepth = 10)`.

**Assert:**
- Returns a non-null `Pair`
- `first` == `tmpDir` (repo root canonical path)
- `second` == `"notes/journal"` (relative subdir)

**Edge cases:**
- `.git` at exactly depth 10: should be found
- `.git` at depth 11: should return `null`
- `.git` is a file not a directory (some git worktree setups): verify detection still works or documents graceful null return

---

#### TC-E1-002 — Android SAF path skips detection

- **Requirement:** FR-1.5
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `GitDetectionTest.kt`

**Arrange:**
Prepare a graph path string starting with `content://com.android.externalstorage/tree/...`.

**Act:**
Call `detectGitRepo(graphPath = "content://com.android.externalstorage/tree/primary%3Anotes", graphId = "test-id")`.

**Assert:**
- Returns `null` immediately (no filesystem operations attempted)
- `GraphInfo.detectedRepoRoot` remains `null` after the call
- No `IOException` or `NullPointerException` thrown

**Edge cases:**
- Path starts with `content:` but is missing `//`: still treated as SAF (prefix check is on `content://`)
- App-private path starting with `/data/user/0/`: proceeds to walk normally

---

#### TC-E1-003 — Detection runs without blocking graph loading

- **Requirement:** FR-1.2 (non-blocking)
- **Test type:** Integration
- **Source set:** `businessTest`
- **File:** `GitDetectionFlowTest.kt`

**Arrange:**
Create a `GraphManager` with a stub `FileSystem` where `directoryExists("$path/.git")` returns `true` after a 50ms delay (simulating slow filesystem).
Use `TestCoroutineScheduler` / `StandardTestDispatcher` so coroutine timing is controlled.

**Act:**
Call `graphManager.addGraph(graphPath)`. Measure the wall-clock time before `addGraph` returns vs. when `graphsFlow` emits the updated `detectedRepoRoot`.

**Assert:**
- `addGraph` returns (suspends and completes its primary work) before the detection coroutine finishes
- After advancing the scheduler, `graphsFlow` emits a `GraphInfo` with `detectedRepoRoot != null`
- The graph is navigable (loaded) immediately after `addGraph` returns; detection completes asynchronously

**Edge cases:**
- Detection throws `IOException`: `graphsFlow` never emits a bad state; original `GraphInfo` (null detection) remains

---

#### TC-E1-004 — `GraphInfo` serialization backward compatibility

- **Requirement:** FR-1.2 (safe defaults on existing JSON)
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `GraphInfoSerializationTest.kt`

**Arrange:**
Construct a JSON string representing an old-format `GraphInfo` without the three new fields:
```json
{"id":"abc","path":"/tmp/notes","name":"notes","isParanoidMode":false}
```

**Act:**
Deserialize with `Json { ignoreUnknownKeys = true }.decodeFromString<GraphInfo>(json)`.

**Assert:**
- `detectedRepoRoot == null`
- `detectedWikiSubdir == null`
- `gitDetectionDismissed == false`
- No exception thrown

**Edge cases:**
- JSON with `detectedRepoRoot: null` explicitly: still deserializes cleanly
- Round-trip: serialize a `GraphInfo` with non-null fields, deserialize, assert fields preserved

---

#### TC-E1-005 — Banner shown when git detected, no config, not dismissed

- **Requirement:** FR-1.3, SC-1
- **Test type:** UI screenshot
- **Source set:** `jvmTest`
- **File:** `GitDetectionBannerTest.kt`

**Arrange:**
Create a `GraphInfo` with `detectedRepoRoot = "/home/user/notes"`, `detectedWikiSubdir = ""`, `gitDetectionDismissed = false`. No `GitConfig` for this graph.

**Act:**
Render `GitDetectionBanner(repoRoot = "/home/user/notes", onSetupSync = {}, onDismiss = {})` in isolation using Roborazzi.

**Assert:**
- Screenshot matches baseline (banner visible)
- Banner text contains `"/home/user/notes"`
- "Set up sync" and "Dismiss" buttons are present and visible

**Edge cases:**
- Very long repo root path: text truncates with ellipsis, layout does not overflow
- Dark theme: snapshot with `darkColorScheme()` active

---

#### TC-E1-006 — Tapping "Set up sync" navigates to `GitSetupScreen` pre-filled

- **Requirement:** FR-1.3, SC-1
- **Test type:** UI / integration
- **Source set:** `jvmTest`
- **File:** `GitDetectionBannerTest.kt`

**Arrange:**
Set up a Compose test with a `NavController`. Inject `GraphInfo` with `detectedRepoRoot = "/home/user/dotfiles"` and `detectedWikiSubdir = "notes"`. Render the main screen with the banner.

**Act:**
Click "Set up sync".

**Assert:**
- Navigation occurs to `GitSetupRoute`
- `GitSetupScreen` is shown with the repo root field pre-filled with `"/home/user/dotfiles"`
- Wiki subdir field pre-filled with `"notes"`
- Both fields are editable (not locked)

---

#### TC-E1-007 — Banner hidden when `GitConfig` already exists

- **Requirement:** FR-1.4
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `GitDetectionBannerTest.kt` or `GitDetectionVisibilityTest.kt`

**Arrange:**
`GraphInfo` has `detectedRepoRoot = "/home/user/notes"`, `gitDetectionDismissed = false`. A `GitConfig` is present for this graph (non-null).

**Act:**
Evaluate the banner visibility condition: `detectedRepoRoot != null && gitConfig == null && !gitDetectionDismissed`.

**Assert:**
- Condition evaluates to `false`
- Banner composable is not rendered (verify via `onNodeWithText` not found)

---

#### TC-E1-008 — Dismiss persists across restarts

- **Requirement:** FR-1.4, FR-1.6 (plan E1-S6)
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `GraphManagerDismissTest.kt`

**Arrange:**
Create a `GraphManager` with an in-memory `Settings` stub and a graph registered with `gitDetectionDismissed = false`.

**Act:**
Call `graphManager.setGitDetectionDismissed(graphId, true)`. Then simulate a restart by re-loading the registry from the persisted JSON (call `loadRegistry()` or equivalent).

**Assert:**
- After reload, `GraphInfo.gitDetectionDismissed == true`
- Banner condition evaluates to `false` for this graph
- Other graph's `gitDetectionDismissed` is not affected

**Edge cases:**
- Calling `setGitDetectionDismissed` with an unknown `graphId`: no exception, no state corruption

---

### Epic 2: LLM-Assisted Semantic Merge

---

#### TC-E2-001 — Journal filename: dash format matches

- **Requirement:** FR-2.1
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `JournalMergeServiceTest.kt`

**Arrange:** No setup required.

**Act:**
Call `JournalMergeService.isJournalFile("2026-06-12.md")`.

**Assert:** Returns `true`.

**Edge cases:**
- `"2026-06-12.md"` ✓
- `"2000-01-01.md"` ✓
- `"9999-12-31.md"` ✓

---

#### TC-E2-002 — Journal filename: underscore format matches (default Logseq)

- **Requirement:** FR-2.1, pitfall (e)
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `JournalMergeServiceTest.kt`

**Arrange:** No setup required.

**Act:**
Call `JournalMergeService.isJournalFile("2026_06_12.md")`.

**Assert:** Returns `true`.

**Edge cases:**
- Mixed separators `"2026-06_12.md"` → should return `true` (regex `[-_]` matches each position independently; document this as acceptable)
- `"2026_6_1.md"` (no zero-padding) → returns `false` (pattern requires exactly 2 digits)

---

#### TC-E2-003 — Non-journal filename falls through to `ConflictResolutionScreen`

- **Requirement:** FR-2.1, FR-2.3
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `JournalMergeServiceTest.kt`

**Arrange:** No setup required.

**Act:**
```kotlin
assertFalse(JournalMergeService.isJournalFile("some-topic.md"))
assertFalse(JournalMergeService.isJournalFile("contents.md"))
assertFalse(JournalMergeService.isJournalFile("2026-06-12-weekly-review.md"))
assertFalse(JournalMergeService.isJournalFile("README.md"))
```

**Assert:** All return `false`.

**Edge cases:**
- `".md"` (empty stem): false
- `"2026-06-12"` (no extension): false
- `"2026-06-12.MD"` (uppercase extension): false (case-sensitive match is correct; Logseq always emits lowercase `.md`)

---

#### TC-E2-004 — LLM success path: `JournalMergeReady` state emitted

- **Requirement:** FR-2.2, FR-2.5, SC-2, SC-3
- **Test type:** Integration
- **Source set:** `businessTest`
- **File:** `GitSyncServiceJournalMergeTest.kt`

**Arrange:**
- Create a mock `GitRepository` that returns a conflict on `2026-06-12.md` with realistic conflict markers:
  ```
  <<<<<<< HEAD
  - Morning run
  =======
  - Evening yoga
  >>>>>>> origin/main
  ```
- Configure `GitConfig` with `llmMergeProvider = LlmMergeProvider.AnthropicClaude(model = "claude-sonnet-4-6", apiKeyRef = "testKey")`
- Create a mock `LlmMergeClient` that returns `"- Morning run\n- Evening yoga".right()`

**Act:**
Call `gitSyncService.sync(graphId)` and collect `syncState`.

**Assert:**
- `syncState.value` is `SyncState.JournalMergeReady`
- `proposal.localContent` contains `"Morning run"`
- `proposal.remoteContent` contains `"Evening yoga"`
- `proposal.proposedMerge` contains both entries
- The conflict was NOT auto-applied to disk

**Edge cases:**
- Two journal files in conflict: first produces `JournalMergeReady`, second queued as `ConflictPending`

---

#### TC-E2-005 — LLM failure falls back to `ConflictResolutionScreen`

- **Requirement:** FR-2.2 step 3 fallback, non-functional requirement
- **Test type:** Integration
- **Source set:** `businessTest`
- **File:** `GitSyncServiceJournalMergeTest.kt`

**Arrange:**
- Same conflict setup as TC-E2-004
- Mock `LlmMergeClient` returns `LlmError.NetworkError("connection refused").left()`

**Act:**
Call `gitSyncService.sync(graphId)`.

**Assert:**
- `syncState.value` is `SyncState.ConflictPending` (not `JournalMergeReady`)
- A warning toast message is logged (verify via captured log output or a `warningToast` callback spy)
- The conflict file is not modified on disk

**Edge cases:**
- `LlmError.ApiError(401, "unauthorized")`: same fallback behavior
- `LlmError.ProviderUnavailable`: same fallback behavior
- LLM call throws uncaught exception: wrapped and treated as `NetworkError`, fallback triggers

---

#### TC-E2-006 — Confidence warning: merged result shorter than threshold

- **Requirement:** FR-2.6
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `JournalMergeServiceTest.kt`

**Arrange:**
- Local content: 20 lines
- Remote content: 20 lines
- Mock `LlmMergeClient` returns a 15-line result (75% of max — below 90% threshold)

**Act:**
Call `journalMergeService.propose(conflictFile)`.

**Assert:**
- `proposal.confidenceWarning == true`
- `proposal.proposedMerge` contains the 15-line result (still returned, not suppressed)

**Edge cases:**
- Exactly 90%: `18 lines / 20 max = 0.9` → `confidenceWarning = false` (boundary: `< 0.9` triggers warning, `>= 0.9` does not)
- Empty LLM response (0 lines): `confidenceWarning = true`
- LLM returns more lines than either input (LLM added explanatory text): `confidenceWarning = false`

---

#### TC-E2-007 — Backup file written before LLM merge applied

- **Requirement:** FR-2.7 (revised — backup file, not git stash), pitfall (b)
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `JournalMergeServiceTest.kt`

**Arrange:**
- Create a temp directory as graph root
- Prepare a `ConflictFile` for `2026-06-12.md` with conflict markers
- Mock `LlmMergeClient` returns a merged result

**Act:**
Call `journalMergeService.propose(conflictFile)`.

**Assert:**
- A file exists at `<graphRoot>/.stelekit-backup/2026-06-12-<timestamp>.md`
- Backup file content matches the conflict-marker version (not the resolved version)
- `proposal.backupPath` equals the path of the written backup file
- Parent `.stelekit-backup/` directory was created if it did not exist

**Edge cases:**
- Graph root is read-only: backup write fails gracefully; `propose()` returns `Left(LlmError.NetworkError("backup write failed"))` or surfaces a specific `BackupWriteError`
- Backup file name collision (same timestamp): uses millisecond precision; document that sub-millisecond re-runs may overwrite (acceptable)

---

#### TC-E2-008 — Backup file restores cleanly

- **Requirement:** FR-2.7 (revised)
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `JournalMergeServiceTest.kt`

**Arrange:**
Obtain a `JournalMergeProposal` with a valid `backupPath` (from TC-E2-007 setup or equivalent). Simulate the user having accepted the merge (conflict file is now overwritten with `proposedMerge` content).

**Act:**
Copy backup file content back over the resolved file (the "Restore original conflict" action).

**Assert:**
- File content after restore exactly matches the original conflict-marker content
- Git can parse the restored file as a conflict (`git diff` output shows conflict markers)

---

#### TC-E2-009 — Token limit exceeded: fallback without LLM call

- **Requirement:** FR-2.4 token limit, pitfall (a)
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `AnthropicMergeClientTest.kt`

**Arrange:**
- `local` content: 33,000 characters (≈ 8,250 tokens at 4 chars/token — exceeds 8,000 limit)
- `remote` content: any string

**Act:**
Call `anthropicMergeClient.merge(local, remote, maxTokensPerVersion = 8000)`.

**Assert:**
- Returns `Left(LlmError.TokenLimitExceeded)`
- No HTTP call is made (mock `HttpClient` engine asserts zero requests)

**Edge cases:**
- Both versions within limit: proceeds to HTTP call
- Exactly at limit boundary (32,000 chars total): proceeds (pre-flight check is `> maxTokensPerVersion * 2`, not `>=`)
- `maxTokensPerVersion = 0`: immediately triggers fallback for any non-empty input

---

#### TC-E2-010 — Three-panel UI renders in `JournalMergeReviewScreen`

- **Requirement:** FR-2.2 step 5, SC-2
- **Test type:** UI screenshot
- **Source set:** `jvmTest`
- **File:** `JournalMergeReviewScreenTest.kt`

**Arrange:**
Create a `JournalMergeProposal` with realistic fixture content:
- `localContent`: 5-line journal with local entries
- `remoteContent`: 5-line journal with remote entries
- `proposedMerge`: 9-line combined result (above confidence threshold)
- `confidenceWarning = false`
- `backupPath = "/tmp/backup/2026-06-12-12345.md"`

**Act:**
Render `JournalMergeReviewScreen(proposal = proposal, onAccept = {}, onFallback = {})` with Roborazzi.

**Assert:**
- Screenshot matches baseline
- Local panel, proposed merge panel (editable), and remote panel all visible
- "Accept", "Edit", and "Fall back to manual" buttons present

**Edge cases:**
- `confidenceWarning = true`: screenshot baseline with warning banner visible (orange/error container color)
- Compact phone layout: panels stack vertically, all still accessible

---

#### TC-E2-011 — `GitConfig` backward compatibility: old JSON deserializes to `Disabled`

- **Requirement:** FR-2.5
- **Test type:** Unit
- **Source set:** `businessTest`
- **File:** `LlmMergeProviderSerializationTest.kt`

**Arrange:**
Old-format `GitConfig` JSON without `llmMergeProvider` key:
```json
{"repoUrl":"https://github.com/user/notes.git","branch":"main","autoSync":true}
```

**Act:**
Deserialize with `Json { ignoreUnknownKeys = true }.decodeFromString<GitConfig>(json)`.

**Assert:**
- `gitConfig.llmMergeProvider == LlmMergeProvider.Disabled`
- No exception thrown

**Edge cases:**
- `LlmMergeProvider.AnthropicClaude` round-trip: serialize and deserialize; `apiKeyRef` preserved, no raw key value stored
- `LlmMergeProvider.Ollama` round-trip: `baseUrl` and `model` preserved

---

### Epic 3: CLI Sync Command

---

#### TC-E3-001 — Exit 0: clean sync, human-readable output

- **Requirement:** FR-3.2 exit 0, FR-3.3, SC-4
- **Test type:** Integration
- **Source set:** `jvmTest`
- **File:** `SyncCliIntegrationTest.kt`

**Arrange:**
- Initialize a bare git repo and a graph directory tracked by it
- Write one file to the graph, stage and ensure it is uncommitted
- Configure a `GitConfig` for the graph (can point to a local bare repo clone as remote)
- Set up `JvmCredentialStore` to return a token from the test environment

**Act:**
Invoke `main(arrayOf("--graph", graphPath))` with stdout/stderr captured via `System.setOut` / `System.setErr`.

**Assert:**
- Process exits with code 0 (`exitProcess` is called with 0, or verify via `SyncResult.status == "success"`)
- stdout contains lines with `[stelekit-sync]` prefix
- stdout contains `"Sync complete"` (or equivalent final line)
- stderr is empty
- No `DriverFactory` or database connection opened (verify by checking no SQLite file is created in temp dir)

**Edge cases:**
- Graph already clean (nothing to commit): exits 0, output says "Nothing to commit"
- Remote already up-to-date: exits 0, output says "Already up to date"

---

#### TC-E3-002 — Exit 1: conflicts remain after sync

- **Requirement:** FR-3.2 exit 1, SC-6
- **Test type:** Integration
- **Source set:** `jvmTest`
- **File:** `SyncCliIntegrationTest.kt`

**Arrange:**
Set up a git conflict scenario (two branches modify the same file). Mock `GitSyncService.sync()` to return `SyncState.ConflictPending` with conflict file paths `["journal/2026-06-12.md"]`.

**Act:**
Invoke `runSync(arrayOf("--graph", graphPath))`.

**Assert:**
- Exit code is 1
- stderr contains the conflict file path `"journal/2026-06-12.md"`
- stdout (or JSON if `--json`) includes `"conflicts": ["journal/2026-06-12.md"]`

---

#### TC-E3-003 — Exit 2: authentication failure

- **Requirement:** FR-3.2 exit 2
- **Test type:** Unit
- **Source set:** `jvmTest`
- **File:** `SyncCliExitCodeTest.kt`

**Arrange:**
Mock `GitSyncService.sync()` to emit `DomainError.GitError.AuthenticationFailed` (or equivalent auth error type).

**Act:**
Invoke `runSync(...)` and capture exit code.

**Assert:**
- Exit code is 2
- stderr contains a message indicating authentication failure

---

#### TC-E3-004 — Exit 3: network error

- **Requirement:** FR-3.2 exit 3
- **Test type:** Unit
- **Source set:** `jvmTest`
- **File:** `SyncCliExitCodeTest.kt`

**Arrange:**
Mock `GitSyncService.sync()` to emit `DomainError.GitError.NetworkError` (or equivalent network error type).

**Act:**
Invoke `runSync(...)`.

**Assert:**
- Exit code is 3
- stderr message indicates network failure

---

#### TC-E3-005 — Exit 4: no git config for graph

- **Requirement:** FR-3.2 exit 4
- **Test type:** Unit
- **Source set:** `jvmTest`
- **File:** `SyncCliExitCodeTest.kt`

**Arrange:**
Provide a graph path with no `GitConfig` registered. `GitConfigRepository.getConfig(graphId)` returns `null`.

**Act:**
Invoke `runSync(arrayOf("--graph", graphPath))`.

**Assert:**
- Exit code is 4
- stderr contains `"no git config"` or similar message
- No git operations are attempted

---

#### TC-E3-006 — `--fetch-only --json` outputs valid JSON with `remoteCommits`

- **Requirement:** FR-3.1, FR-3.4, SC-5
- **Test type:** Integration
- **Source set:** `jvmTest`
- **File:** `SyncCliJsonOutputTest.kt`

**Arrange:**
Mock `GitSyncService.fetchOnly(graphId)` to return a result indicating 3 new remote commits. Configure `--json` and `--fetch-only` flags.

**Act:**
Invoke `runSync(arrayOf("--graph", graphPath, "--fetch-only", "--json"))`. Capture stdout.

**Assert:**
- stdout is valid JSON parseable by `Json.decodeFromString<SyncResult>`
- `result.remoteCommits == 3`
- `result.status == "success"` (fetch succeeded)
- `result.conflicts` is empty
- No `[stelekit-sync]` prefix lines emitted to stdout (JSON mode suppresses human-readable)

**Edge cases:**
- `--json` without `--fetch-only`: full sync result serialized to JSON
- `--json` with exit code 1 (conflicts): JSON is still written to stdout; conflict paths in both `result.conflicts` and stderr

---

#### TC-E3-007 — `--dry-run` performs no git operations

- **Requirement:** FR-3.1 `--dry-run`
- **Test type:** Unit
- **Source set:** `jvmTest`
- **File:** `SyncCliArgParseTest.kt` + `SyncCliIntegrationTest.kt`

**Arrange:**
Set up a graph with uncommitted local changes and 2 remote commits available. Mock `gitRepository.status()` and `gitSyncService.fetchOnly()`. Assert that `gitRepository.commit()`, `gitRepository.merge()`, and `gitRepository.push()` are never called.

**Act:**
Invoke `runSync(arrayOf("--graph", graphPath, "--dry-run"))`.

**Assert:**
- `gitRepository.commit()` is not called (verify mock call count == 0)
- `gitRepository.push()` is not called
- stdout contains a "Would commit N local changes, merge M remote commits" message
- Exit code is 0
- Local repo state unchanged (no new commit, no merge)

---

#### TC-E3-008 — `--commit-only` stages and commits, does not fetch or push

- **Requirement:** FR-3.1 `--commit-only`
- **Test type:** Integration
- **Source set:** `jvmTest`
- **File:** `SyncCliIntegrationTest.kt`

**Arrange:**
Graph has one modified file. Mock `GitSyncService.commitLocalChanges(graphId)` to return success.

**Act:**
Invoke `runSync(arrayOf("--graph", graphPath, "--commit-only"))`.

**Assert:**
- `gitSyncService.commitLocalChanges()` called exactly once
- `gitSyncService.fetchOnly()` not called
- `gitSyncService.sync()` not called
- Exit code is 0

**Edge cases:**
- `--commit-only --fetch-only` together: `ArgParser` throws `ArgParseException`; exit code 5; error message to stderr

---

#### TC-E3-009 — Headless Linux: credential store falls back to env var

- **Requirement:** FR-3.5, pitfall (c) — `BackendNotSupportedException` on headless Linux
- **Test type:** Unit
- **Source set:** `jvmTest`
- **File:** `JvmCredentialStoreHeadlessFallbackTest.kt`

**Arrange:**
- Mock the OS keychain backend to throw `BackendNotSupportedException` on any call
- Set `System.setenv("STELEKIT_GIT_TOKEN", "test-env-token")` in the test process (via reflection or test-scoped property override)

**Act:**
Call `credentialStore.retrieve("httpsToken")`.

**Assert:**
- Returns `"test-env-token"` (env var value)
- A debug-level log message is emitted: `"OS keychain unavailable, checking environment variable..."`
- No exception propagated to caller

**Edge cases:**
- Env var also unset: `retrieve()` returns `null`; a clear error-level log message is emitted: `"No credential found for httpsToken. Set STELEKIT_GIT_TOKEN."`
- Keychain succeeds on macOS/Windows: env var is never checked (keychain result returned directly)

---

## Readiness Gate

**Status: CONCERNS**

**Justification:**

The implementation plan is complete and test coverage maps cleanly to all requirements. However, two concerns must be resolved before implementation begins:

1. **ADR-A, ADR-B, ADR-C unresolved** — the plan explicitly flags these three architectural decisions as "required before" their respective stories. TC-E3-001 through TC-E3-009 depend on ADR-A (CLI subproject vs. `JavaExec`) and ADR-B (argument parsing library). TC-E2-004 and TC-E2-009 depend on ADR-C (`LlmMergeClient` placement). Tests cannot be written until the interface boundary is fixed. Recommend resolving all three ADRs as explicit pre-implementation tasks.

2. **`SyncState` sealed class shape unknown** — TC-E2-004 and TC-E2-005 assert on `SyncState.JournalMergeReady` and `SyncState.ConflictPending`. The plan calls for adding `JournalMergeReady` to an existing sealed class (`SyncState.kt`), but the current structure of that class is not confirmed in scope. If `SyncState` does not exist yet, tests cannot be written until E2-S7 defines it. This is a dependency, not a blocker, but the test for E2-S7 must be written after E2-S1/S2 are complete.

These are ordering and pre-condition concerns, not coverage gaps. All six success criteria have test cases. The plan is ready to proceed to Phase 5 once the three ADRs are decided.

---

## Regression Risk

The following existing tests may be affected by the changes and should be run after each story is completed:

| Existing Test | File | Risk | Story |
|---|---|---|---|
| `GraphManagerAddGraphTest` | `businessTest/.../db/` | `addGraph` gains a new post-completion `launch` for detection; the test's `StubFileSystem` must return safe values for the detection path or it will fail on `directoryExists` calls it does not currently expect | E1-S2 |
| `GraphManagerInitAutoRestoreTest` | `businessTest/.../db/` | `GraphManager` initialization flow changed; verify detection does not interfere with auto-restore | E1-S2, E1-S3 |
| `MigrationRunnerSchemaSyncTest` | `businessTest/.../db/` | If any new table is added to `SteleDatabase.sq` (unlikely for this feature, but verify), this test will fail unless `MigrationRunner.all` is updated | E2-S1 through E2-S7 |
| `JvmCredentialStoreTest` | `jvmTest/.../git/` | `JvmCredentialStore` is modified for headless fallback; existing round-trip and missing-key tests must still pass | E3-S5 |
| `VaultCredentialStoreTest` | `jvmTest/.../git/` | Verify `VaultCredentialStore` is not accidentally affected by the `JvmCredentialStore` fallback chain change | E3-S5 |
| `GitHubDeviceFlowClientTest` | `jvmTest/.../git/` | `GitHubDeviceFlowClient` likely constructs a `JvmCredentialStore`; headless fallback change must not alter its auth flow | E3-S5 |
| All `businessTest` tests that use `GraphManager` | `businessTest/.../db/` | `GraphInfo` gains three new fields with defaults; any test that constructs `GraphInfo` directly via named parameters may need to add the new parameters (or rely on defaults) | E1-S1 |
| `MigrationRunnerCoverageTest` | `businessTest/.../db/` | Verify no new migration steps are accidentally skipped | All epics |

Run `./gradlew ciCheck` after each completed story. Pay particular attention to the `GraphManager`-touching tests after E1-S1 and E1-S2, and the `JvmCredentialStore` tests after E3-S5.
