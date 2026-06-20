# Git Smart Sync — Known Failure Modes and Mitigations

## (a) LLM Context Limits for Large Journal Files

### Findings

Claude 3.5 Haiku and Claude 3.5 Sonnet are retired. The current relevant models are:

| Model | Context Window |
|---|---|
| `claude-haiku-4-5` | 200K tokens |
| `claude-sonnet-4-6` | 1M tokens |
| `claude-opus-4-8` | 1M tokens |

FR-2.4 specifies a conservative **8,000 tokens per version** as the max input limit before falling back to `ConflictResolutionScreen`. This is well within any current model's window and remains a reasonable product-level safeguard against excessive API cost, not a technical necessity.

**API behavior on overflow:** The API returns `HTTP 400` with `error.type: "invalid_request_error"` and a message like `"prompt is too long: N tokens > 200000 maximum"`. This will not occur in practice with an 8,000-token-per-version cap, but the error handling path should still be coded defensively.

**Pre-flight token estimation:** Use Anthropic's token counting endpoint (`POST /v1/messages/count_tokens`) before sending the merge request. The Java SDK exposes this as `client.messages().countTokens(MessageCountTokensParams)`. Alternatively, estimate at ~4 characters per token as a fast lower-bound check.

### Mitigation

1. Before calling the LLM, count tokens on both `localContent` and `remoteContent`.
2. If either exceeds `LlmMergeConfig.maxTokensPerVersion` (default 8,000), fall back to `ConflictResolutionScreen` with a warning: "Journal file is too large for automatic merge."
3. Catch `BadRequestError` (HTTP 400) as a safety net and route to `ConflictResolutionScreen`.
4. **Do not hardcode a specific model's context window** as the ceiling — use the configurable `maxTokensPerVersion` field so the limit can be raised without a code change.

---

## (b) Git Stash Behavior During an In-Progress Merge

### Findings

**`git stash` is blocked when `MERGE_HEAD` exists.**

Empirically verified on git 2.53.0: creating a repository, forcing a merge conflict, and running `git stash` produces:

```
error: could not write index
```

Git refuses to stash because the index is in a partially-merged state. This is consistent across git versions — the stash operation requires a clean index, which an in-progress merge does not provide.

### Impact on FR-2.7

FR-2.7 states: *"Before applying any LLM merge, SteleKit creates a git stash of the conflicted state (MERGE_HEAD preserved) so the user can abort and recover even after accepting."*

This requirement is **not achievable with standard `git stash`**. There is no git mechanism to stash a conflicted merge state while preserving `MERGE_HEAD`.

### Mitigation

Replace the `git stash` mechanism with a **backup-file approach**:

1. Before applying the LLM-proposed merge to any conflicted file, copy the conflict-marker version to a backup location: `.stelekit-backup/<filename>-<timestamp>.md`.
2. Record the backup path in the UI state so the "View original conflict" option in `JournalMergeReviewScreen` can open it.
3. If the user rejects the merge and wants to restore, they can copy the backup file back and run `git checkout --merge <file>` manually, or SteleKit can provide a "Restore original conflict" action.
4. **Do not attempt `git stash` when `MERGE_HEAD` exists.** Detect this state by checking for the presence of `.git/MERGE_HEAD` before any stash call.

---

## (c) JVM CLI and the OS Keychain

### Findings

The `java-keyring` library (and `java.awt.Desktop` for keychain dialogs) depends on:
- **macOS/Windows:** Native keychain APIs, which work reliably in GUI and CLI contexts.
- **Linux headless (no display, no libsecret daemon):** Throws `BackendNotSupportedException`. This is the common case for SSH sessions, cron jobs, Termux on Android, and CI pipelines — exactly the environments `stelekit-sync` is meant to serve.

`java.awt.headless=true` does not solve this — `BackendNotSupportedException` is thrown before any display interaction.

The Secret Service DBus interface (used by GNOME Keyring and KWallet) requires a running D-Bus session and an unlocked keyring daemon, which are not guaranteed in CLI/headless contexts.

### Impact on FR-3.5 and Non-Functional Requirements

The requirement states: "The CLI reuses the same `JvmCredentialStore` as the app." This is achievable, but the `JvmCredentialStore` must gracefully handle headless Linux, rather than crashing.

### Mitigation

Implement `JvmCredentialStore` with a priority-ordered fallback chain:

1. **OS keychain** (java-keyring): attempt first; catch `BackendNotSupportedException` silently and proceed to fallback.
2. **Environment variable**: check `STELEKIT_ANTHROPIC_KEY` (and `STELEKIT_GIT_TOKEN` for git credentials). This is the primary path for headless/CI use.
3. **Encrypted file** at `~/.config/stelekit/credentials.enc`: AES-256 with a key derived from the machine's `/etc/machine-id` (Linux) or equivalent. Acceptable for single-user desktop systems.
4. **Fail with a clear error**: if no credential source is available, exit with code `5` and print:
   ```
   Error: No credential store available. Set STELEKIT_ANTHROPIC_KEY environment variable.
   ```

Do not silently swallow the `BackendNotSupportedException` without logging a debug-level message explaining the fallback path taken.

---

## (d) Parent-Directory Walking on Android SAF Paths

### Findings

Android's Storage Access Framework (SAF) uses `content://` URIs, not filesystem paths. The `DocumentFile` API provides `getParentFile()`, but:

- It returns `null` for documents at the root of a tree grant.
- Its behavior varies across Android versions and storage providers (OEM file managers, cloud storage).
- There is **no reliable way to walk `content://` URI parents to find a `.git` directory**.

FR-1.5 states: *"On Android, detection uses `FileSystem.directoryExists("$parentPath/.git")` walking upward, which works on both JVM and Android."*

This statement is **only true for app-private paths** (e.g., `getFilesDir()`, `getExternalFilesDir()`), where the app has a real filesystem path. For SAF-managed locations selected via the system file picker, the path is a `content://` URI and filesystem walking does not apply.

### Impact on FR-1.1 and FR-1.5

`GraphManager.addGraph(path)` on Android may receive either a real filesystem path or a `content://` URI depending on how the graph was opened. Git repository auto-detection can only work for real filesystem paths.

### Mitigation

1. In `GraphLoader` or `GraphManager`, detect whether the graph root is a real filesystem path or a SAF URI.
2. **Real filesystem path** (app-private storage, paths accessible via `java.io.File`): proceed with parent-directory walking as specified in FR-1.1.
3. **SAF URI** (`content://` scheme): skip git detection entirely. Do not show the git setup banner (FR-1.3) for SAF-managed graphs. Optionally, log a debug message: "Git auto-detection skipped: SAF URI cannot be walked for .git".
4. **Update FR-1.5's claim**: the implementation note should specify that detection only works for filesystem-accessible paths, not SAF URIs. Document this as a known limitation in the feature spec.

---

## (e) Logseq Journal Filename Variations

### Findings

Logseq's **default** journal filename format uses **underscores**: `yyyy_MM_dd.md` (e.g., `2026_06_12.md`).

The **dash format** `yyyy-MM-dd.md` is opt-in, configured via `:journal/file-name-format` in `config.edn`.

Additionally, Logseq supports arbitrary custom date formats (e.g., `EEEE, MMM do yyyy` → `Friday, Jun 12th 2026.md`), which produce filenames that bear no resemblance to a date pattern.

### Impact on FR-2.1

FR-2.1's regex `\d{4}-\d{2}-\d{2}\.md` **misses the common default format** (`2026_06_12.md`). Users who have never changed their Logseq config use underscores, and their journal conflicts will be routed to `ConflictResolutionScreen` instead of the LLM merge path.

### Mitigation

Replace the FR-2.1 regex with one that matches both separators:

```kotlin
val JOURNAL_FILENAME_REGEX = Regex("""\d{4}[-_]\d{2}[-_]\d{2}\.md""")
```

This matches:
- `2026-06-12.md` (dash format)
- `2026_06_12.md` (underscore format, default)

**Do not attempt to detect arbitrary custom formats** — they are too variable to match reliably and would risk false positives. Custom-format journals will fall through to `ConflictResolutionScreen`, which is the correct behavior for unrecognized files.

**Also consider:** Reading `:journal/file-name-format` from the graph's `config.edn` at graph-load time and storing it in `GraphInfo`. This would allow exact format matching rather than a regex heuristic. However, this is an enhancement beyond the initial implementation; the updated regex is sufficient for the common case.

---

## (f) FR-2.7 Feasibility: "Git Stash of Conflicted State with MERGE_HEAD Preserved"

### Findings

This is addressed in full in section (b) above. The short summary:

**The specified mechanism is not possible.** `git stash` fails with `error: could not write index` when called during an in-progress merge. There is no git operation that snapshots a conflicted merge state to a stash while keeping `MERGE_HEAD` intact.

The requirement appears to be based on a misunderstanding of how `git stash` works. `git stash` stores the working tree and index as commits on `refs/stash`, but it requires the index to be in a committable state — which it is not when git is mid-merge.

### Conclusion

FR-2.7 must be revised. The safety mechanism should be a **backup-file copy**, not a git stash. See section (b) for the full mitigation.

### Revised FR-2.7 (proposed)

> Before applying any LLM merge to a conflicted file, SteleKit copies the conflict-marker version to `.stelekit-backup/<filename>-<timestamp>.md`. The backup path is stored in UI state and accessible via "View original conflict" in the Journal Merge Review screen. If the user needs to fully restore the conflict state, they can copy the backup file back over the resolved file and run `git merge --abort` or manually reconstruct the conflict markers.
