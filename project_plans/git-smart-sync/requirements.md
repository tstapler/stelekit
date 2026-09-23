# Git Smart Sync — Requirements

## Problem Statement

SteleKit's git integration has three gaps that surface in real multi-device use:

1. **Post-hoc repo detection**: When a user opens a folder that is already inside a git repository, SteleKit does not detect this and offers no path to configure sync. The user must manually navigate to git settings.

2. **Dumb conflict resolution**: Background sync auto-resolves merge conflicts without user awareness. For journal files — where two devices both added entries on the same date while one was offline — "accept remote" or "accept local" always loses data. The correct resolution is to combine both sets of entries.

3. **No CLI entry point**: There is no way to trigger a sync cycle from a shell script, cron job, or CI pipeline. This blocks automation workflows (e.g., syncing before a backup, or triggering sync from Termux on Android without opening the full app UI).

---

## Target Users

- **Primary**: Single user across 2–4 devices (desktop + Android, occasionally offline) keeping a daily journal in Logseq/SteleKit format. Conflicts are most common on journal pages when both a desktop and a phone logged entries on the same day while one device was offline.
- **Secondary**: Power user who wants to integrate SteleKit sync into shell scripts, cron jobs, or dotfile management workflows.

---

## Priority

**P0** — data loss prevention. The current auto-resolve strategy silently discards journal entries when two devices diverge. Every conflict is a potential lost thought.

---

## Functional Requirements

### FR-1: Git Repository Auto-Detection on Graph Add

**FR-1.1** When `GraphManager.addGraph(path)` is called, the app traverses parent directories (up to filesystem root or a depth limit of 10) looking for a `.git` directory.

**FR-1.2** If a `.git` directory is found:
- `GraphInfo` is annotated with `detectedRepoRoot: String?` and `detectedWikiSubdir: String?` (the relative path from repo root to graph root).
- This detection is non-blocking and does not delay graph loading.

**FR-1.3** After the graph finishes loading (and the user is in the main editor view), the app shows a one-time dismissible bottom sheet / banner: *"This folder is inside a git repository at `<repo-root>`. Set up sync to keep it in sync across devices."* with two actions: **Set up sync** (opens `GitSetupScreen` pre-filled with detected values) and **Dismiss** (suppresses for this graph permanently via a `gitDetectionDismissed` flag in `GraphInfo`).

**FR-1.4** The banner is only shown once per graph. If the user has already configured a `GitConfig` for this graph, or has dismissed, no banner appears.

**FR-1.5** On Android, the detected repo root path is the SAF-resolved path or the app-private path — detection uses `FileSystem.directoryExists("$parentPath/.git")` walking upward, which works on both JVM and Android.

---

### FR-2: LLM-Assisted Semantic Merge for Journal Conflicts

**FR-2.1** When a git merge produces conflicts, SteleKit classifies each conflicted file:
- **Journal file**: filename matches `\d{4}-\d{2}-\d{2}\.md` (Logseq daily journal format).
- **Non-journal file**: everything else.

**FR-2.2 Journal conflict resolution — LLM semantic merge**:
1. The conflict is NOT surfaced to the ConflictResolutionScreen by default.
2. SteleKit extracts the LOCAL version and REMOTE version of the file (from git conflict markers).
3. Both versions are sent to an LLM (configurable: Claude API via Anthropic SDK, or a local Ollama model) with a system prompt instructing it to produce a merged document that preserves ALL unique journal entries from both versions, deduplicates entries that appear in both, and maintains Logseq outliner indentation.
4. The LLM response is treated as a proposed merge. It is NOT auto-applied.
5. The proposed merge is shown in a **Journal Merge Review** screen (new screen): three-panel view (local | proposed merge | remote). The user can accept the proposed merge, edit it, or fall back to the manual side-by-side resolver.
6. After approval, SteleKit writes the resolved content, marks the file as resolved in git, and commits the merge.

**FR-2.3 Non-journal conflict resolution**:
- Falls through to the existing `ConflictResolutionScreen` (side-by-side diff, accept local/remote/manual edit).
- No change to existing behavior.

**FR-2.4 LLM merge prompt**:
- System: *"You are merging two versions of a Logseq daily journal page. Each version was created on a different device that was temporarily offline. Your task: produce a single merged document that contains every unique bullet point from both versions. Do not lose any entry. Do not duplicate entries that appear verbatim in both. Preserve Logseq's indented outline structure. Output only the merged markdown — no explanation."*
- User message includes both versions delimited by `--- LOCAL ---` and `--- REMOTE ---`.
- Max input tokens: configurable, default 8 000 per version. If either version exceeds this limit, fall back to `ConflictResolutionScreen`.

**FR-2.5 LLM provider configuration**:
- Configurable per-graph in `GitConfig`: `llmMergeProvider: LlmMergeProvider` (sealed class: `Disabled`, `AnthropicClaude(model, apiKey)`, `Ollama(baseUrl, model)`).
- Default: `Disabled` — no LLM calls without explicit opt-in.
- When `Disabled`, journal conflicts fall back to `ConflictResolutionScreen`.

**FR-2.6 Merge result confidence**:
- After LLM merge, count lines in LOCAL-only, REMOTE-only, and MERGED. If the merged line count is less than `max(local, remote) * 0.9`, surface a warning: *"The merged result is shorter than expected — some entries may have been lost. Review carefully."*

**FR-2.7 Undo / safety**:
- Before applying any LLM merge, SteleKit creates a git stash of the conflicted state (`MERGE_HEAD` preserved) so the user can abort and recover even after accepting.
- A "View original conflict" option remains accessible from the Journal Merge Review screen.

---

### FR-3: CLI Sync Command

**FR-3.1** A new Gradle task `:kmp:runSync` (JVM) and a packaged CLI binary `stelekit-sync` (produced by `packageDistributionForCurrentOS`) that accepts:

```
stelekit-sync [options]

Options:
  --graph <path>        Path to the graph directory (default: last opened graph from app settings)
  --commit-only         Stage and commit local changes, do not fetch or push
  --fetch-only          Fetch from remote and print pending commit count; do not merge or push
  --dry-run             Print what would happen without executing any git operations
  --json                Output structured JSON instead of human-readable text
  --help
```

**FR-3.2** Exit codes:
- `0` — sync completed successfully (or nothing to do)
- `1` — sync completed but merge conflicts remain (conflict file paths written to stderr)
- `2` — authentication failure
- `3` — network error
- `4` — no git config found for the specified graph
- `5` — other error (message on stderr)

**FR-3.3** stdout format (human-readable, default):
```
[stelekit-sync] Graph: ~/notes (main)
[stelekit-sync] Committed 1 local change
[stelekit-sync] Fetched: 3 new commits from origin/main
[stelekit-sync] Merged successfully
[stelekit-sync] Pushed to origin/main
[stelekit-sync] Sync complete
```

**FR-3.4** `--json` output:
```json
{
  "graph": "/home/user/notes",
  "branch": "main",
  "localCommits": 1,
  "remoteCommits": 3,
  "conflicts": [],
  "status": "success"
}
```

**FR-3.5** The CLI reuses the same `GitSyncService`, `JvmGitRepository`, and `CredentialStore` as the app. It does NOT open a database connection (it only runs git operations) — graph path is the only required input.

**FR-3.6** The CLI is a thin main() entry point in `jvmMain` that calls `GitSyncService` directly. It does not start Compose or any UI. It exits when the sync coroutine completes.

---

## Non-Functional Requirements

- **Offline safety**: Auto-detection of git repos must not add any latency to graph loading. Detection runs as a background coroutine after loading completes.
- **LLM calls are optional and never block sync**: If the LLM call fails (network error, rate limit, API key invalid), the journal conflict falls back to `ConflictResolutionScreen` with a warning toast.
- **LLM API key security**: API keys are stored in `CredentialStore` (same as HTTPS tokens), never in plaintext in `GitConfig`.
- **CLI on all JVM targets**: The CLI works on Linux, macOS, and Windows. It uses the same `JvmCredentialStore` as the desktop app.
- **No data loss**: A git merge conflict must NEVER be auto-resolved without either (a) user approval of a proposed merge or (b) explicit user choice in `ConflictResolutionScreen`. The existing silent auto-resolve behavior is removed.

---

## Out of Scope

- LLM merge for non-journal markdown files (properties pages, topic pages)
- Multi-model consensus (using multiple LLMs and comparing)
- iOS CLI (iOS does not have a terminal entry point)
- Streaming LLM responses in the merge review UI (batch response only for v1)
- Auto-detection in subdirectory graphs where the `.git` is more than 10 levels up

---

## Success Criteria

1. Opening a git-tracked folder shows the sync setup banner; tapping it opens `GitSetupScreen` pre-filled with the detected repo root and subdirectory
2. When two devices both add journal entries offline and then sync, the LLM-merged result contains entries from both devices
3. No journal conflict is silently discarded — every conflict produces either an LLM-proposed merge or a `ConflictResolutionScreen`
4. `stelekit-sync --graph ~/notes` exits 0 after a full sync cycle in a terminal
5. `stelekit-sync --fetch-only --json` outputs valid JSON with `remoteCommits` count
6. Running `stelekit-sync` when there are conflicts exits with code 1 and prints the conflicting paths to stderr
