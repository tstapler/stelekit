# Notes-subfolder (wikiSubdir) UX — Brainstorm

## Origin

A user's cloned git graph showed 0 pages after clone. Root cause: the graph's
content actually lives at `<repo>/logseq/{pages,journals}`, but
`detectedWikiSubdir` was blank — `detectGitRoot()`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:990-1026`)
only infers a subfolder by walking *upward* from the graph path to find
`.git`, which can never produce a non-empty result when the graph path *is*
the clone root (the normal case right after a clone). The fix exists —
`GitSetupStep2RepoPath.kt:204-220`'s "Notes subfolder (optional)" field,
reachable via sidebar "Git Setup" / "Set up sync" (`GitSyncCoordinator.kt:138`,
wired in `GraphContentLeftSidebar.kt:166` and `GraphContentMainArea.kt:147`)
— but it's undiscoverable until something is visibly broken, and nothing
offers to reconcile the stray files that get written to the wrong location
in the meantime.

Two asks: (1) surface the current repo-root/subfolder configuration and any
detected mismatch in more places, proactively; (2) when the subfolder is
changed, offer to move or merge whatever was left at the old/wrong location
instead of silently orphaning it.

This doc is the exhaustive idea dump feeding `sdd:1-ideate` for this project.
Not all of these should ship — prioritization happens in the SDD phases that
follow.

---

## A. Discoverability — surface the setting and any mismatch

1. **Live mismatch banner.** Today's `GitDetectionBanner` only fires when
   `activeGraphInfo.detectedRepoRoot != null && appState.gitConfig == null`
   (`GraphContentMainArea.kt:105-107`) — it goes silent forever once *any*
   config is saved, even a wrong one. Add a second banner condition: config
   exists, but the warm reconcile found near-zero pages/journals at the
   configured root while a sibling folder has both (the same signal
   `GraphDiagnostics.kt`'s `NESTED GRAPH CANDIDATE` check already computes,
   just never surfaced live). *"No notes found at the repository root — found
   notes in `logseq/` instead."* with a button straight into Step 2.
2. **Persistent "Repository" row in graph settings**, outside the wizard —
   current repo root + subfolder shown plainly with an inline edit
   affordance, so checking/tweaking the value doesn't require re-entering the
   whole multi-step wizard.
3. **Reuse the existing subtitle string.** `GitSetupStep2RepoPath.kt:314`
   already renders `"Notes in ${entry.wikiSubdir}"` for recent-repo history.
   Put the same subtitle on the *active* graph's entry in the sidebar/graph
   switcher so it's visible during normal use, not just mid-setup.
4. **Empty-state card instead of a blank list.** If a graph loads with 0
   pages, replace the generic empty view with one that names the exact path
   it looked in plus a direct link to the subfolder field.
5. **Auto-fill on clone**, not just blank-and-hope. Run the candidate scan
   immediately after a fresh clone completes and pre-populate the subfolder
   field with the best guess (user can still override) instead of leaving it
   empty by construction.
6. **First-run coachmark** on the "Notes subfolder" field the first time a
   fresh clone finishes, calling out that most git-backed Logseq repos nest
   content in a subfolder.
7. **Inline "Validate now" action** next to the field: run the same on-disk
   existence check diagnostics uses, synchronously, with pass/fail feedback —
   no need to save, leave the screen, and reload to find out it's wrong.
8. **Breadcrumb in the content area's top bar**: `"<repo name> / <wikiSubdir>"`
   as a passive, always-visible reminder of where content is being read
   from.
9. **Reorder/highlight the diagnostics export.** `NESTED GRAPH CANDIDATE`
   lines are buried mid-file in the generated diagnostics today (see the
   file that kicked off this investigation) — promote a detected mismatch to
   a one-line summary at the very top of the export.
10. **Upgrade the log level.** Warm reconcile finding 0 content at the
    configured root *while* a nested candidate exists should log at WARN, not
    only appear in an on-demand diagnostics dump — visible in normal app
    logs without the user needing to know to export anything.
11. **Warning badge in the graph switcher** for any graph whose configured
    root doesn't match its best on-disk candidate, for users juggling
    multiple graphs.

## B. Smarter automatic detection

12. **Downward candidate scan at clone time**, not just the existing upward
    walk. After cloning, scan a couple of levels down from the repo root for
    conventional content layouts (`logseq/`, sibling `pages/`+`journals/`,
    `notes/`) and offer the best match as a default — this is the direct fix
    for the gap in `GraphManager.kt:990-1026`.
13. **Filter out app-internal folders** (`.stelekit`, `.git`, `.obsidian`,
    etc.) from candidate suggestions automatically — the diagnostics output
    that started this already flags `.stelekit` as a false-positive
    candidate (`pages=true journals=false`) alongside the real one.
14. **Re-run the candidate scan on every warm reconcile**, not only at setup
    time — catches a repo that gets restructured after initial configuration
    without requiring the user to regenerate diagnostics to notice.
15. **Cross-check against git history.** `git log --diff-filter=A --
    <subdir>` on the configured subfolder — if it has literally never had a
    commit, flag the config as a likely typo instead of trusting it blindly.

## C. Merge/move when the subfolder changes

16. **Reuse `StorageMoveChoiceDialog`'s shape**, don't invent new UI. It
    already solves a near-identical problem for storage relocation, with a
    deliberately even-handed button styling and a safety rule baked into its
    copy ("the old copy stays until you confirm it's safe to remove" —
    `StorageMoveChoiceDialog.kt:75-76`). Offer three choices on a subfolder
    change instead of its current two: **Move**, **Merge**, **Leave as-is**
    (explicit, not today's silent default).
17. **Dry-run manifest before touching disk**: *"This will move 2 files,
    merge 0, leave 11 untouched"* — derived from the same disk diff, cancelable.
18. **Per-file conflict resolution via existing machinery.** Where the same
    filename exists at both old and new locations with different content,
    hand it to `DiskConflictDialog`/`DiskConflictBlockMatcher` rather than
    building new merge logic from scratch.
19. **Lightweight diff preview** (line-count delta, first differing lines)
    before asking the user to resolve a same-filename conflict — don't make
    them resolve on faith.
20. **Verify-after-fix step.** After Move/Merge/Leave completes, re-run the
    disk scan against the new root and show the result ("12 pages, 13
    journals now found") so the fix is proven immediately, not assumed.
21. **Soft-delete instead of immediate delete** on "Move" — stage the old
    files in a short-retention holding area (e.g.
    `.stelekit/trash/<timestamp>/`) rather than deleting outright, so a bad
    move/merge isn't catastrophic.
22. **Single atomic git commit for the reconciliation** when git sync is on
    (e.g. `"stelekit: move journals into logseq/ after notes-subfolder
    change"`) so it's one clean, revertable step in history instead of
    unstaged file moves for the next auto-sync to interpret ambiguously.
23. **Resumable/idempotent move.** If the app is killed mid-move (plausible
    on Android), reopening should detect a half-done migration (a marker
    file) and offer to resume rather than leaving a silently half-migrated
    tree.
24. **Symmetric handling of the inverse case** — user flattening from
    `logseq/` back to repo root should go through the identical diff/move/merge
    flow, not a special case that only exists for "subfolder was blank."

## D. Safety / integrity guardrails

25. **Three-way validation on save**, not one: path doesn't exist yet (likely
    typo), path exists and is empty (probably fine, nothing to reconcile), path
    exists with content that conflicts with the old location (needs the
    move/merge flow). Different icon/message per case.
26. **Regression test coverage** mirroring the existing `WikiSubdirUriGuardTest`:
    clone a repo with nested content, confirm detection suggests it, confirm
    Move/Merge actually relocate files correctly on both JVM and Android
    filesystems.
27. **No implicit "Leave as-is."** Dismissing the move/merge dialog via
    back-button or outside-tap must be logged as an explicit "left in place"
    decision, not an ambiguous no-op — keeps an audit trail of what the user
    chose when support comes up later.

## E. Scope questions for the research/plan phases

28. **Is the SAF (non-git) case in scope?** `detectGitRoot` explicitly bails
    out for `saf://`/`content://` paths today
    (`GraphManager.kt:998-1001`) — the same "configured root doesn't match
    where content lives" problem isn't git-specific (one of the user's own
    registry entries has a self-referential `detectedRepoRoot=saf:/`). If
    this project only fixes the git-clone path, say so explicitly as a
    non-goal rather than leaving it ambiguous.
29. **Audit trail in diagnostics.** Record each subfolder reconfiguration
    (old root, new root, strategy chosen, files moved) as a structured entry
    in the exportable diagnostics file, so a future support conversation like
    the one that started this has history to look at instead of a single
    snapshot.
