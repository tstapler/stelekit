# Requirements: disk-conflict-dialog-ux

**Date**: 2026-07-03
**Type**: bug fix (UX correctness) in an existing project

## Problem Statement

SteleKit's `DiskConflictDialog` (built under `project_plans/disk-change-detection/`) is the
mechanism users see when a markdown file backing an open, actively-edited page changes on disk
(git pull, sync tool, another device) while local edits are unconfirmed. This is the single most
anxiety-inducing moment in the editor — it directly threatens the user's "is my work safe?"
mental model — and a UX journey-mapping pass (`docs/ux/journey-map.md`, "Disk conflict during
active edit") found it currently behaves like a bug in four concrete ways:

1. **Mismatched preview granularity.** The "Your edit" preview is scoped to a single block; the
   "Disk version" preview is scoped closer to the whole file. A user cannot actually compare them
   to understand what changed.
2. **Hard truncation with no escape hatch.** Both previews are hard-truncated at 200 characters
   with no way to view the full content, which can hide the actual differing text — the exact
   information the user needs to make a safe choice.
3. **"Manual resolve" assumes git literacy.** This option injects git-style conflict markers
   (`<<<<<<<` / `=======` / `>>>>>>>`) into the block and reopens it for editing, with no inline
   explanation. Non-technical users have no way to know what these markers mean or how to resolve
   them.
4. **No persistent indicator for a deferred conflict.** When the conflict is on a page other than
   the one currently open, it's surfaced only as a dismissible snackbar. Once dismissed, there is
   no persistent marker (e.g. a sidebar badge) reminding the user a page has an unresolved
   conflict — it's easy to forget and lose track of pending, unresolved external changes.

Users hit this whenever an external process touches a graph file they have open — expected to be
occasional but high-stakes each time it happens (real risk of perceived or actual data loss).

## Users / Consumers

Both human users (anyone editing a SteleKit graph across Desktop/Android/iOS/Web who has a page
open when an external file change lands) and the automated systems that produce those external
changes (git sync, multi-device write, any tool writing directly to the graph directory).

## Success Metrics

Bug is gone with a regression test preventing recurrence. Concretely:
- "Your edit" and "Disk version" previews are shown at comparable granularity (same scope: block
  vs. block, or whole-page vs. whole-page — resolved during planning).
- Truncated previews have a "view full" expansion reachable before the user must pick a
  resolution.
- "Manual resolve" includes an inline explanation of what the injected conflict markers mean and
  how to remove them.
- A page with an unresolved deferred conflict shows a persistent indicator (not just a
  dismissible snackbar) until the conflict is resolved or the page is revisited and handled.
- Regression/unit tests cover: preview-granularity parity, full-content reachability, and
  indicator persistence across snackbar dismissal + navigation.

## Constraints

No hard constraints (no deadline, no specific performance/SLA target, no compliance requirement).

## Scope

### In Scope
- `DiskConflictDialog` preview rendering (granularity + truncation/expansion).
- Inline help/explanation for the "Manual resolve" conflict-marker path.
- A persistent UI indicator (exact placement/mechanism TBD in planning — e.g. sidebar badge,
  page-list marker) for pages with an unresolved deferred (snackbar'd) conflict.
- Any `DomainError`/state additions needed to track "page has unresolved pending conflict" beyond
  the current dialog-open lifecycle.

### Out of Scope
- Changing the underlying conflict-resolution model itself (still last-writer-wins with four
  manual choices — no block-level or operational-transform merge). That's a larger, separately
  scoped effort noted in the journey map's cross-cutting gaps.
- The four-tier protection check logic (actively editing / dirty blocks / pending disk write /
  actor pending writes) that decides *whether* to show the dialog — only what's shown and how
  it's presented.
- Any changes to `EditorSettings.kt`, command palette, slash commands, undo/redo, or other
  cross-cutting gaps identified in the same journey-mapping pass — those are separate SDD
  projects.

## Open Questions

- What is the right granularity to align "Your edit" and "Disk version" previews to — should
  "Disk version" be narrowed to the corresponding block(s), or should "Your edit" be widened to
  page-level context? (Research/planning to resolve — likely narrow disk version to the same
  block scope, since that matches what the user is actually editing.)
- What UI mechanism should carry the persistent pending-conflict indicator — a sidebar/page-list
  badge, a dedicated "Conflicts" panel, or a toast that persists until dismissed with intent
  (not auto-dismissed)? (Research to survey existing indicator patterns already used elsewhere in
  the app, e.g. the indexing-error banner referenced in the journey map.)
- Does "view full" mean an inline expand-in-place, or a separate full-diff view/dialog?
- Should the inline explanation for conflict markers be a one-time tooltip, permanent inline text
  in the block while markers are present, or a linked help doc?
