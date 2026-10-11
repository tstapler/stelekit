# UX Research: Wiki Subdir UX

## 1. Comparable UX Patterns in Similar Products

### Logseq (origin product)
- Subfolder detection is entirely manual — the user must discover the problem and
  navigate to "Git Setup → Step 2" to configure it. No proactive surfacing.
- The existing `GitDetectionBanner` fires only when git config is absent — it goes
  silent permanently once any config is saved (even wrong), creating a false-negative
  state.
- Conflict resolution during file moves uses a simple overwrite-or-skip model with no
  diff preview or dry-run manifest.

### Obsidian
- Auto-detects vault configuration and surfaces mismatches via a settings page that
  shows the actual content root alongside the configured one.
- Uses a "safe mode" approach — when a config mismatch is detected, it shows a banner
  with a "Fix" button that opens a guided resolution flow.
- File conflict resolution shows a side-by-side diff with rename/overwrite/merge options.

### Notion (workspace structure)
- Surfaces path/location issues as persistent banners in the workspace settings
  sidebar, not just in the setup flow.
- Uses a three-way choice pattern (move/rename/merge) for folder restructure operations.

## 2. User Mental Models and Expectations

Users expect:
- **Proactive detection** — the app should notice when content doesn't match the
  configured location and tell them, not wait for them to notice an empty graph.
- **Transparency** — the configured repo root and subfolder should be visible from
  a settings page outside the multi-step wizard, so they can check/tweak without
  re-entering the whole flow.
- **Safety** — destructive operations (move/merge) must show a dry-run preview first,
  and the old copy should not be deleted until the user confirms it's safe.
- **Recovery** — if the app crashes mid-move, reopening should detect the partial
  state and offer to resume, not leave the graph in a corrupted state.
- **Trust** — after fixing the misconfiguration, the user needs to *see* that the fix
  worked (page/journal counts updated) before closing the dialog.

The current gap between expectation and reality is stark: the user sees an empty graph
with zero indication of why, and the fix is buried in a multi-step wizard they only
open if they suspect a config problem.

## 3. Accessibility Requirements

- **Keyboard navigation** — all dialogs must be fully navigable via Tab/Enter/Escape,
  with visible focus rings. The banner "Fix" button and dialog buttons must all be
  keyboard-accessible.
- **Screen reader** — the mismatch banner should announce as an alert role
  (`role="alert"`) so screen readers pick it up immediately. Dialog titles must be
  properly labeled.
- **Color + text** — don't rely on color alone for error/warning states. The banner
  and path-validation icons (doesn't exist / exists-empty / exists-with-conflicts)
  must use distinct text + icon combinations.
- **Contrast** — the banner text and dialog content must meet WCAG AA contrast ratios
  (4.5:1 for normal text, 3:1 for large text).

## 4. Error States and Graceful UX Handling

### Mismatch not auto-detectable
If the downward candidate scan finds no obvious `pages/`+`journals/` structure, the
banner should still show with a manual-entry fallback: "Notes not found at <path>.
Specify the subfolder manually."

### SAF move partially fails
During a move on SAF, if the copy succeeds for some files but fails for others (e.g.
a permission error on a specific file), the dialog must:
- Show exactly which files succeeded and which failed
- Offer to retry the failed ones, skip them, or abort the whole operation
- Leave the successful copies in place (don't roll back)

### Crash during move
If the app is killed mid-move:
- The marker file (`.stelekit/move-in-progress.json`) records what has been moved
- On next launch, detect the marker and offer "Resume move" or "Cancel and leave as-is"
- The soft-delete trash holds the pre-move state for recovery

### Git sync races
If an auto-sync fires while a move is in progress:
- Queue the sync until the move commits (using `GitSyncBusyCounter` as the
  synchronization primitive)
- Show a non-blocking notification that sync is paused pending the move

## 5. Jobs-to-be-Done Analysis

| Job | Functional | Emotional | Social |
|-----|------------|-----------|--------|
| **Fix an empty graph after clone** | Detect the mismatch, offer to fix it | Relief from confusion/anxiety ("why is my graph empty?") | Share that the tool "just works" with colleagues |
| **Change the notes subfolder** | Show dry-run, let user choose move/merge/leave | Confidence that nothing will be lost | N/A (personal tool) |
| **Review changes after the fix** | Show a verify-after-fix result with counts | Trust that the fix actually worked | N/A |
| **Recover from a crash mid-operation** | Detect partial state, offer resume | Confidence to use the tool even when things go wrong | N/A |

## Key Design Recommendations

1. **Live mismatch banner** — extending `GitDetectionBanner`'s pattern in
   `GraphContentMainArea.kt:130-174`, fires when warm reconcile finds near-zero
   content at the configured root while a sibling folder has both `pages/` and
   `journals/`.

2. **Three-way move/merge dialog** — sibling to `StorageMoveChoiceDialog.kt`,
   following the same AlertDialog + stacked-outlined-button shape but with
   Move / Merge / Leave-as-is options and a dry-run manifest.

3. **Persistent repo/subfolder display** — a "Repository" row in graph settings
   outside the wizard (brainstorm idea A.2), so the user can see and tweak the
   configured root + subfolder without re-entering the multi-step Git Setup flow.

4. **Content-path breadcrumb** — `"<repo name> / <wikiSubdir>"` in the content
   area's top bar (idea A.8), a passive always-visible reminder of where content
   is being read from.

5. **Three-way path validation on save** (idea D.25):
   - Path doesn't exist → "likely typo" warning
   - Path exists and is empty → "probably fine" confirmation
   - Path exists with content that conflicts with the old location → triggers the
     move/merge dialog

6. **Verify-after-fix** (idea C.20) — after Move/Merge/Leave, re-scan and show
   the result ("12 pages, 13 journals now found") so the fix is proven immediately.
