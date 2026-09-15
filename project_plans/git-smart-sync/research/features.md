# Journal Merge Review — Feature Research

_Research synthesized for the 3-panel "Journal Merge Review" screen in the SteleKit Compose Multiplatform app._

---

## (a) Logseq Merge Handling

### Official Logseq Sync

Logseq's official cloud sync ($5/month) introduced **Smart Merge** (first shipped in 0.9.14). Key properties:

- **Algorithm**: A customized adaptation of Myers' diff algorithm combined with Google's `diff-match-patch`, modified to preserve Logseq's outliner block structure. It operates at the **block level** rather than the full-file level, so two devices editing different blocks of the same journal page can be reconciled without a conflict.
- **Scope**: Designed for a single user across multiple devices, not for real-time multi-user collaboration (RTC is a separate alpha-stage feature).
- **Failure mode**: When block-level diff cannot auto-resolve (both devices changed the same block), Smart Merge presents a diff view where the user must manually choose between the two versions. No "keep both" button exists; the user must copy-paste to retain content from both sides — a widely-reported pain point (GitHub issue #437, labeled `priority-A`).
- **No LLM assistance**: Logseq has no AI-assisted conflict resolution. Smart Merge is a deterministic diff algorithm with no language model involvement.
- **RTC (alpha)**: The newer database-mode sync uses WebSocket delivery and SQLite transactions. Remote updates are applied through the outliner operations system. When a full graph divergence is detected, the system throws a special exception triggering a complete graph pull — essentially a last-write-wins at the graph level, not block level.

### Git-Based Workflows (Community Practice)

Because Logseq files are plain Markdown, many users sync via git (auto-commit every 60 minutes via a hook) or Syncthing. Both approaches have documented failure modes:

- **Syncthing**: On divergence, creates `.sync-conflict-*` files alongside the original. Logseq does not detect or merge these; users either lose the conflict file or notice it in the file browser. Community scripts exist to tag these blocks with `#Sync-Conflict` and append them to the main file.
- **Git**: Standard 3-way merge conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) are inserted inline into the Markdown. Logseq renders these as literal text, making them visible but requiring external resolution (a text editor or git mergetool).
- **Recommended workaround**: Keep a single always-on machine that auto-commits/pushes; use Syncthing to share the folder to mobile. This eliminates conflicts by serializing writes through one node.

### What Logseq Does NOT Do

- No per-hunk "accept both" UI button.
- No LLM-suggested resolution.
- No visual 3-panel merge editor.
- Smart Merge's internal conflict state is not exposed to the user as a reviewable diff — it either merges silently or forces a binary choice.

---

## (b) Other Note-Taking Apps: Offline Conflict Resolution

### Obsidian Sync

- **Algorithm**: `diff-match-patch` (the same Google library Logseq uses). Works character-level across the full note content.
- **Behavior**: Attempts automatic merge. When it fails, it either (a) duplicates both versions into the note, or (b) silently overwrites the older version with the newer one — which version "wins" has been inconsistent and is a documented user complaint.
- **UI**: When a conflict file is created, users receive a system notification. No visual diff or side-by-side review is provided. Recovery requires going into Version History.
- **Community requests**: Users have requested a side-by-side diff view similar to git mergetools, and a switch to **Automerge** (a CRDT library). Obsidian has not implemented either as of 2026. Note: `diff-match-patch` was archived by Google in August 2024, adding to community concern about this dependency.
- **Third-party sync (iCloud, Syncthing)**: Creates `-conflicted-copy` or `.sync-conflict` files; Obsidian surfaces these as separate notes in the vault with no automatic relationship to the original.

### Notion

- **Architecture**: Hybrid. Uses **Operational Transform (OT)** for performance-critical real-time editing paths (similar to Google Docs), with block-level granularity — each page, database entry, and list item is a discrete block. A block-level OT model means two users editing different blocks on the same page do not conflict at all.
- **Offline behavior**: Notion queues operations locally and replays them when connectivity returns. If the same block was mutated offline and online simultaneously, last-write-wins at the block level is applied. Notion prompts the user to resolve when it detects a diverged block version, offering a binary "keep this version / keep server version" choice.
- **No side-by-side diff**: The conflict UI is a modal with two full-block previews, not a line-level diff.

### Bear

- **Algorithm**: Not publicly disclosed; likely timestamp-based last-write-wins via iCloud CloudKit.
- **Conflict detection UI**: When a conflict occurs (e.g., editing a note on airplane-mode iPhone while also editing it on a connected Mac):
  - The conflicted note **moves to the top of the Note List**, above pinned notes.
  - A **conflict icon** appears next to the note title in all views (list, tags, search).
  - **Both versions** are preserved as separate note copies, visually connected by a line in the Notes sidebar section.
  - Users manually review, copy content between copies, and delete the unwanted version.
- **Philosophy**: "We handle conflicts this way to avoid throwing out any data by accident." — explicit no-data-loss guarantee, manual resolution required.
- **No diff view**: Bear shows the two full notes side by side in the note list but provides no line-level diff or merge tooling.

### Roam Research

- **Architecture**: Real-time collaborative by design; uses an event-sourced graph database (Datalog/Datascript). Operations are fine-grained (individual block edits, reorders, references) and timestamped. The server applies a total ordering.
- **Offline**: Limited and historically problematic; offline support has been a long-standing open feature request with no robust implementation. Without offline support, there is no offline conflict problem — writes are serialized through the server.
- **Conflict strategy**: Effectively last-write-wins at the operation level on the server, because operations are replayed in timestamp order. No user-visible merge conflict UI exists.

### Summary Table

| App | Algorithm | Granularity | User-visible conflict UI | Manual diff view |
|---|---|---|---|---|
| Logseq (Smart Merge) | Myers diff + diff-match-patch | Block | Binary choose-one | No |
| Logseq (git) | diff3 / git 3-way | Line | Inline conflict markers | Via external tool |
| Obsidian Sync | diff-match-patch | Character | Notification + version history | No |
| Notion | OT (block-level) | Block | Modal: keep mine / keep theirs | No |
| Bear | Last-write-wins (iCloud) | Full note | Conflict icon + duplicate note | No (side-by-side notes only) |
| Roam Research | Timestamp-ordered event log | Block/operation | None (server-serialized) | No |

---

## (c) What Makes a Good 3-Panel Merge Review UI

### VS Code 3-Way Merge Editor (introduced v1.69, June 2022; text labels from v1.72)

**Panel layout** (horizontal split with result below):

```
┌─────────────────────┬─────────────────────┐
│     INCOMING (left) │     CURRENT (right) │
│  read-only          │  read-only          │
├─────────────────────┴─────────────────────┤
│               RESULT (bottom)             │
│  editable                                 │
└───────────────────────────────────────────┘
```

- **Incoming**: changes from the branch being merged (remote / theirs).
- **Current**: changes from your current branch (local / ours).
- **Result**: the editable merged output that will be saved.

**Per-conflict-hunk actions** (shown as CodeLens links above each yellow-highlighted conflict block):
- Accept Incoming Change
- Accept Current Change
- Accept Combination (Incoming First) — appends both, remote first
- Accept Combination (Current First) — appends both, local first
- Ignore

**Gutter**: Yellow highlight boxes mark each conflict region. A conflict-count badge (red circle, top-right of Result panel) tracks remaining unresolved conflicts.

**Result panel**: Fully editable — users can type arbitrary text in addition to or instead of using the accept buttons. A **"Reset to base"** link resets manual changes for a given hunk.

**Completion**: **"Complete Merge"** button (bottom-right, disabled until all conflicts resolved) stages the file and closes the editor.

**Layout options** (three-dot menu): switch to vertical layout; toggle a Base view showing the file's pre-merge state.

**Keyboard shortcut gaps** (documented UX problem): The new 3-way merge editor has no stable default keyboard shortcuts for per-hunk accept actions. Commands `merge-conflict.accept.current` and `merge-conflict.accept.incoming` that existed pre-v1.14 were removed. As of May 2024 (issue #211833), expose commands for "accept left/right/combination for current selection" remains an open request. Mouse or CodeLens click is required for conflict acceptance.

### IntelliJ IDEA / JetBrains Merge Dialog

**Panel layout** (horizontal, all three panes visible):

```
┌────────────┬──────────────┬────────────┐
│  LOCAL     │   RESULT     │  REMOTE    │
│ (ours)     │  (editable)  │ (theirs)   │
│ read-only  │              │ read-only  │
└────────────┴──────────────┴────────────┘
```

- **LOCAL** (left): your local branch.
- **RESULT** (center): editable output — this is what gets written.
- **REMOTE** (right): incoming/remote branch.
- Gutter arrows (left-pointing `<<` and right-pointing `>>`) appear in the LOCAL and REMOTE panels to send that hunk to RESULT.

**Keyboard shortcuts** (List of Conflicts window):
| Action | macOS | Windows/Linux |
|---|---|---|
| Accept Yours (all conflicts) | Alt+Y | Alt+Y |
| Accept Theirs (all conflicts) | Alt+T | Alt+T |
| Merge (open dialog) | Alt+M | Alt+M |

**Keyboard shortcuts** (3-way Merge window):
| Action | macOS | Windows/Linux |
|---|---|---|
| Navigate to next conflict | F7 | F7 |
| Navigate to previous conflict | Shift+F7 | Shift+F7 |
| Focus middle (Result) column | Ctrl+Tab | Ctrl+Tab |
| Accept left change (current hunk) | Ctrl+Shift+Left | Alt+Shift+Left |
| Accept right change (current hunk) | — | Alt+Shift+Right |
| Apply / finalize | Option+Enter | Alt+Enter |

**Community-recommended workflow**: Work in the middle column; use F7 to navigate conflicts; use Alt+Shift+Left/Right to accept hunks; use Alt+Enter to finish. Fully keyboard-operable.

**No-BASE default**: IntelliJ does not show the BASE (common ancestor) by default, reducing cognitive load. The user can enable it if needed.

### Design Principles Observed Across Both Tools

1. **Read-only source panels** prevent accidental modification of "ours" or "theirs" while reviewing.
2. **Editable result panel** allows free-text override — essential for cases where neither side is fully correct.
3. **Per-hunk granularity**: Accept/reject at the individual changed block level, not file-wide.
4. **Combination / "keep both"**: Both VS Code and IntelliJ support accepting both sides (in order), critical for append-heavy journal workflows where both sides added content.
5. **Progress indicator**: Unresolved conflict count visible at all times.
6. **Navigation shortcuts** (F7/Shift+F7) that move the cursor to the next conflict hunk are expected by power users.
7. **Base view as toggle**: Showing the pre-divergence state helps understand intent but adds visual noise; keep it optional.
8. **"Complete Merge" / Apply is gated**: The finish action should be disabled until all conflicts have a decision, preventing incomplete merges.
9. **Keyboard operability is critical**: VS Code's keyboard gap is a known regression that frustrates power users. Full keyboard support (navigate + accept + finish without touching the mouse) should be a first-class requirement.

---

## (d) GitHub Copilot and Cursor: LLM-Assisted Conflict Resolution

### GitHub Copilot

- **No dedicated merge UI**: Copilot does not provide its own 3-panel merge editor. It operates within VS Code's existing editor.
- **Workflow**:
  1. Open the conflicted file in VS Code (standard inline conflict markers are visible).
  2. Manually **select / highlight** the entire conflict region (from `<<<<<<<` to `>>>>>>>`).
  3. Open the **Copilot Chat panel** and describe the intent in natural language (e.g., "The feature branch adds error handling; main adds logging. Keep both.").
  4. Copilot returns a **suggested resolved code block** with a brief plain-English explanation of what it did.
  5. User reviews, then manually pastes or applies the suggestion.
- **Pro+ "automatic"**: GitHub's marketing for Copilot Pro+ claims it can "tackle complex merge conflicts automatically," but this refers to Copilot's coding agent mode (multi-file, PR-level), not an interactive per-conflict UI. The mechanism is: assign the issue to Copilot in GitHub or trigger in VS Code; it proposes changes across multiple files and opens a PR.
- **Key limitation**: Requires manual selection of the conflict block before asking. No automatic detection or highlighting. No dedicated merge panel.

### Cursor

- **Inline editing approach**: Cursor emphasizes staying in-file rather than switching to a chat panel.
- **Workflow**:
  1. Navigate to the conflicted file; standard git conflict markers are visible.
  2. Select the conflicted code region.
  3. Press **Cmd+K** (macOS) / **Ctrl+K** (Windows/Linux) to open inline editing.
  4. Type a natural language description of the desired resolution.
  5. Cursor generates a suggestion **inline** in the file, shown as a diff preview.
  6. Accept or reject the suggestion with a keypress.
- **No dedicated 3-panel view**: Cursor works entirely within the standard single-file view. There is a community feature request (Cursor Forum, 2025) to instruct Cursor's agent to use a three-way diff for conflict resolution, indicating this is not yet natively supported.
- **Cmd+K advantage**: Keeping the resolution workflow inside the file and bound to a single shortcut reduces context switching compared to Copilot's chat-panel approach.

### CodeRabbit (reference point)

For PR-level review, CodeRabbit's "Finishing Touches" feature can detect and suggest resolutions for merge conflicts in pull requests directly in the GitHub PR interface — this is a distinct model (pre-merge, PR-level) rather than a local interactive merge tool.

### Common Pattern Across AI Tools

All current AI-assisted conflict resolution follows the same pattern:
1. **Human selects** the conflict region (AI does not yet auto-locate all conflicts reliably).
2. **Human provides intent** via natural language.
3. **AI suggests** a resolution (text output).
4. **Human verifies and accepts** — AI output is never auto-committed.

No tool as of mid-2026 presents an AI-suggested resolution inside a true 3-panel merge UI with per-hunk AI suggestions and inline accept/reject buttons.

---

## Design Implications for Compose Multiplatform Journal Merge Review Screen

### 1. Layout: Vertical Split (Top Source Panels, Bottom Result)

Adopt VS Code's layout variant with the result panel at the bottom rather than IntelliJ's center-column approach. For journal content (long, append-heavy text), a top-source/bottom-result layout maximizes vertical space for reading the result. Use a vertical drag handle to let the user resize the source vs. result areas.

Label panels clearly:
- **"Remote"** (top-left, read-only) — the incoming version from the other device.
- **"Local"** (top-right, read-only) — the version from this device.
- **"Merged Result"** (bottom, editable) — what will be saved.

Optionally expose a **"Base"** toggle that replaces one source panel with the common ancestor — keep it hidden by default to reduce cognitive load.

### 2. Journal-Specific Default: "Accept Both (Local First)"

Unlike code merges where accepting both sides can cause compile errors, journal entries are append-only by nature. The most common correct resolution is to keep all content from both sides. Pre-populate the Result panel with a combined merge (local content first, remote content second, separated by a visible divider) and let the user edit from there rather than starting with an empty result. This mirrors what Logseq issue #437's users actually want.

### 3. Block-Level Granularity, Not Line-Level

Logseq and SteleKit use an outliner block model. Conflict hunks should map to **blocks** (outline nodes), not raw lines. Showing a block-level diff (block A was changed on remote, block B was added on local) is more intelligible than character-level diffs for journal content.

### 4. Per-Hunk Actions as Composable Buttons

For each conflicting block hunk, show clearly labeled action chips:
- "Keep Remote" — replaces that hunk in Result with the remote version.
- "Keep Local" — replaces with local version.
- "Keep Both" — appends both versions (remote + local or local + remote, user-selectable order).
- "Edit" — opens the Result panel cursor at that hunk for free-text editing.

Avoid using directional arrows (left/right) without labels — screen readers and unfamiliar users cannot infer meaning.

### 5. Full Keyboard Navigation

Keyboard operability must be first-class — VS Code's gap here is the most-cited user complaint in the merge editor. Required bindings (suggested, configurable):
- **Next conflict**: `Tab` or platform-standard focus navigation.
- **Previous conflict**: `Shift+Tab`.
- **Keep Remote (current hunk)**: `R` (mnemonic: Remote) or `Alt+Left`.
- **Keep Local (current hunk)**: `L` (mnemonic: Local) or `Alt+Right`.
- **Keep Both**: `B` or `Alt+Down`.
- **Complete Merge**: `Ctrl+Enter` / `Cmd+Enter`.

On Android, surface these as a persistent bottom action bar (since keyboard shortcuts are unavailable on soft-keyboard devices).

### 6. Progress Indicator and Completion Gate

Show a progress counter ("3 of 7 conflicts resolved") in a persistent header or toolbar. Disable the **"Save Merged"** button until all conflict hunks have a decision. This mirrors VS Code's "Complete Merge" gating and prevents half-resolved saves.

### 7. Optional LLM-Assisted Suggestion

Based on the Copilot/Cursor pattern, an "Ask AI to suggest resolution" action is viable as an enhancement but should be:
- **Per-hunk**, not whole-file — give the LLM the base + remote + local for one block, ask for a suggested merged block.
- **Non-blocking** — the user can resolve without AI; AI is an optional accelerator.
- **Shown as a preview diff** in the Result panel, not auto-accepted — the user explicitly accepts or modifies.
- For journal content, the prompt template should communicate that both blocks likely contain valid daily notes that should be combined, not that one is "correct" and the other is "wrong."

### 8. No-Data-Loss Guarantee (Bear Model)

Follow Bear's philosophy: never silently discard a version. If the user hits "Cancel" or dismisses the merge screen without completing, preserve both versions as separate pages (or a "draft merge" state) rather than leaving conflict markers inline in the markdown. Conflict markers render as literal text in the outliner, creating a broken page.

### 9. Entry Point and Discoverability

Surfaces that need to trigger the merge review screen:
- Graph load when a `.sync-conflict` file is detected alongside a journal page.
- The "External Changes" conflict banner in the existing `StelekitViewModel` `DiskConflict` state (already modeled in the codebase).
- A "Review Merge" item in the page's overflow menu when the page has unresolved conflict state.

---

_Sources consulted:_
- [Logseq 0.9.14: Smart Merge for Sync](https://blog.logseq.com/logseq-0-9-14-better-sidebars-and-smart-merge-for-sync/)
- [Logseq GitHub Issue #437 — Improved handling of merge conflicts](https://github.com/logseq/logseq/issues/437)
- [Logseq Syncthing Sync Support feature request](https://discuss.logseq.com/t/syncthing-sync-support-automatically-merge-sync-conflicts/26717)
- [Obsidian Forum — Robust Sync Conflict Resolution](https://forum.obsidian.md/t/robust-sync-conflict-resolution/93544)
- [Obsidian Forum — CRDT proposal](https://forum.obsidian.md/t/conflict-free-replicated-data-type-crdt/79940)
- [Bear — How Bear Pro handles conflicted notes](https://bear.app/faq/how-bear-pro-handles-conflicted-notes/)
- [VS Code Merge Conflicts documentation](https://code.visualstudio.com/docs/sourcecontrol/merge-conflicts)
- [VS Code Issue #146091 — Explore UX for three-way merge](https://github.com/microsoft/vscode/issues/146091)
- [VS Code Issue #158523 — No keyboard shortcuts in merge view](https://github.com/microsoft/vscode/issues/158523)
- [VS Code Issue #211833 — Expose accept left/right/combination commands](https://github.com/microsoft/vscode/issues/211833)
- [IntelliJ IDEA — diff tool keyboard shortcuts](https://www.plugin-dev.com/intellij-use/version-control/diff-tool-keyboard/)
- [JetBrains — Merge files from the command line](https://www.jetbrains.com/help/idea/command-line-merge-tool.html)
- [DeployHQ — Resolving Merge Conflicts with AI: Copilot, Cursor & Claude](https://www.deployhq.com/git/resolving-merge-conflicts-with-ai)
- [Cursor Forum — Instruct agent to use three-way diff for merge conflict resolution](https://forum.cursor.com/t/instruct-agent-to-use-three-way-diff-for-merge-conflict-resolution/142445)
- [Notion System Design](https://www.educative.io/blog/notion-system-design)
- [Git conflict terminology — ours, theirs, base from diff3](https://codeinput.com/blog/git-conflict-revisions)
