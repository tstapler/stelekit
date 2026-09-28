# UX Review: disk-conflict-dialog-ux

**Date**: 2026-07-03
**Verdict**: CONCERNS

## Prior Blocker Resolution

1. **Full-screen "View full comparison" rendering behind the still-open dialog** — RESOLVED —
   Task 3.1.1e (`plan.md` L444-483) adds an explicit `!appState.diskConflictViewFullVisible` guard
   around the `DiskConflictDialog(...)` call at `GraphDialogLayer.kt` L274-275 (line numbers
   verified against the current file). `appState.diskConflict` itself stays non-null the whole
   time (state, not rendering, survives the round trip), so closing the full screen correctly
   re-shows the `AlertDialog` with the same conflict. ADR-001 documents the fix as an amendment
   and explicitly records the rejected alternative (wrapping `DiskConflictFullScreen` in its own
   `Dialog`), showing the trade-off was actually considered, not just patched reflexively. This is
   a structurally sound, minimal fix that makes the two surfaces mutually exclusive at the single
   call site where they were colliding.

2. **Sidebar indicator only reaching Favorites/Recent, leaving most deferred conflicts with zero
   indicator** — RESOLVED (coverage gap closed; fix quality raises new concerns, see below) —
   ADR-003 + Story 5.1.4 add an always-visible `PendingConflictsBanner` in `LeftSidebar`'s
   persistent chrome, gated only on `pendingConflictFilePaths.isNotEmpty()` (not favorite/recency
   membership), reusing the exact same `pendingConflictFilePaths` set (Task 5.1.3a:
   `appState.pendingConflicts.keys + listOfNotNull(appState.diskConflict?.filePath)`) that also
   feeds the per-row markers — so the two tiers cannot disagree on count. Every page with a
   pending conflict now has *some* persistent, always-reachable signal in the running session.
   Verified `Screen.AllPages` already exists and is already wired through `LeftSidebar`'s
   `onNavigate` (`Sidebar.kt` L167, `ScreenRouter.kt` L171), so the click target is real, not
   speculative. However, the *quality* of this fix introduces two new concerns (visual prominence
   vs. the cited VS Code badge precedent, and zero conflict-specific affordance on the landing
   screen it navigates to) — see Concerns below.

## Blockers

None. Both prior blockers are resolved at the level of "does every code path have a working,
non-colliding UI surface." Remaining issues are quality/polish concerns, not correctness breaks.

## Concerns

- [ ] **`PendingConflictsBanner` is heavier than the "badge" it's named/framed as, and sits
  directly adjacent to `SyncStatusBadge`'s near-identical amber warning treatment — worsens the
  original icon-collision concern rather than resolving it.** Task 5.1.4b renders the banner
  "immediately after the `SyncStatusBadge(...)` call ... and before the 'Navigation' section
  header" (`Sidebar.kt` L146-152 today). `SyncStatusBadge`'s `ConflictPending` state already
  renders `Icons.Default.Warning` + `Color(0xFFF59E0B)` + a "Conflict" text label for git-merge
  conflicts (`SyncStatusBadge.kt` L157-169). The new banner uses the *same* icon and *same* exact
  color for a different conflict type, as a full-width `Surface` row with descriptive sentence
  text, placed directly beneath it. If both are visible at once (a git-merge conflict and a disk
  conflict pending simultaneously — a realistic co-occurrence, not an edge case), a user sees two
  stacked, visually near-identical amber alert rows at the very top of the sidebar with different
  text, which reads as duplicated/redundant rather than as two distinct problem classes. This is
  also a mismatch with the research grounding this ADR cites: `research/features.md`'s VS Code
  Source Control precedent describes "a numeric badge... on a fixed chrome element, not a toast" —
  i.e., a small always-visible count marker, not a full-width sentence banner. A persistent,
  non-dismissible, sentence-length banner that can remain up for an entire multi-hour/day session
  (per ADR-002, session-scoped) risks banner blindness / alarm fatigue (well-established in
  usability literature — over-alerting reduces the salience of the alert it's trying to preserve,
  the "cry wolf" effect) for what may be a low-urgency background item (e.g. a page nobody has
  opened in weeks). — **Recommendation**: shrink to an actual small numeric badge (e.g., a chip on
  the "All Pages" `NavigationItem`, or a corner badge near `SyncStatusBadge` rather than a second
  stacked row), and/or give it a visually distinct glyph/tint from `SyncStatusBadge`'s conflict
  treatment so the two conflict types are told apart at a glance, consistent with the plan's own
  stated intent ("keep it a *separate* indicator... don't conflate two different conflict-type
  counts/badges").

- [ ] **ADR-002 commits to surfacing the session-scoped (non-restart-durable) trade-off "in the PR
  description / UI copy," but no actual UI copy does this.** ADR-002's Rationale states: "This
  trade-off is called out in the PR description / UI copy rather than silently underdelivering on
  the word 'persistent' in the requirements doc." Checked every user-facing string the patched plan
  adds — Task 5.1.4a's banner text ("N pages have unresolved conflicts"), Task 5.1.1a's
  `contentDescription` ("Unresolved disk conflict"), Task 4.1.2a's snackbar — none mention that the
  indicator will disappear on restart/graph-switch even if the conflict is still unresolved. This
  matters specifically because the project's own stated purpose is preventing users from losing
  track of unresolved conflicts ("easy to forget and lose track of pending, unresolved external
  changes") — a banner that looks durable but silently vanishes on next launch, with no on-screen
  hint that it will, risks the exact false-security failure mode ADR-002 itself flags as a concern
  worth calling out. If "PR description" is meant to satisfy this alone (not UI copy), that's fine,
  but should be made explicit rather than left as an "either/or" that the implementation quietly
  resolves by doing neither. — **Recommendation**: either add a one-line clarification (e.g. in a
  tooltip on the banner, or accept that only the PR description will cover it and update ADR-002 to
  say so unambiguously), so the commitment ADR-002 makes is actually kept somewhere.

- [ ] **Full-screen "Close comparison" affordance doesn't signal that closing returns to a
  mandatory, still-open decision — not an exit from the conflict flow.** Task 3.1.2a specifies a
  generic `TopAppBar` back/close `IconButton` (`contentDescription = "Close comparison"`) modeled
  on `ConflictResolutionScreen.kt`. Standard back/close iconography conventionally means "leave
  this view, return to what I was doing" — but here, closing it re-triggers the still-blocking
  `AlertDialog` (correct behavior per the Blocker 1 fix), which may read as a surprising "gotcha"
  to a user who expected to simply back out. Given this project's explicit framing of the dialog as
  "the single most anxiety-inducing moment in the editor," an unexpected re-appearance of a
  blocking modal right after a user thought they'd dismissed a view works against the anxiety-
  reduction goal rather than for it. — **Recommendation**: no functional change needed, but
  consider copy that sets expectations (e.g. a subtitle under "Compare versions" — "Choose a
  resolution below, or close to return to your options") so the round-trip isn't a surprise.

- [ ] **Per-row sidebar marker (Task 5.1.1a) remains icon-only/boolean with no visible tooltip for
  sighted users — only half of the original recommendation was implemented.** The prior review's
  Concern #1 asked for both (a) a tooltip on the per-row icon and (b) a global count-based
  affordance "in addition to (not instead of) the per-row marker." Part (b) is now delivered via
  the `PendingConflictsBanner` (ADR-003), which is a real improvement — a count is now visible
  somewhere. Part (a) was not: Task 5.1.1a adds a `contentDescription` (good — this fixes the
  accessibility/screen-reader half of the original concern, see below) but still no `Tooltip`
  wrapper or hover/long-press affordance for sighted mouse/touch users encountering the bare amber
  triangle in a row for the first time. — **Recommendation**: wrap the icon in a
  `TooltipBox`/`PlainTooltip` ("Unresolved disk conflict — click to review") to close the gap for
  sighted users, matching what screen-reader users already get from the added
  `contentDescription`.

- [ ] **Manual-resolve caption still explains conflict markers by repeating them, with no
  documented trade-off note.** Task 4.1.1a's caption text is byte-identical to the pre-patch
  version — the patch summary at the top of `plan.md` (L59-77) does not list Phase 4/Epic 4.1 as
  touched at all, and no ADR exists for this concern (only `decisions/ADR-001`, `ADR-002`,
  `ADR-003` exist on disk — none address Gap #3/marker explanation). The prior recommendation was
  either fold in the VS Code labeled-panes pattern research already surfaced, or explicitly write
  up a short trade-off note (the way ADR-002 does for session-scoping) so this isn't later read as
  "Gap #3 solved to best-practice." Neither happened. — **Recommendation**: still open; at minimum
  add the trade-off note this review has now asked for twice, even if the deeper fix stays
  deferred.

- [ ] **No Android back-button/predictive-back handling specified for `DiskConflictFullScreen`.**
  Confirmed: zero occurrences of "BackHandler" or any back-gesture handling anywhere in the patched
  `plan.md`. Task 3.1.2a only wires the `TopAppBar` `IconButton`. Without an explicit `BackHandler`
  (or platform equivalent), Android's system back gesture/button will fall through to default
  back-stack behavior instead of calling `onDismiss()` — inconsistent with the fact that the
  underlying conflict is still logically open and must not be silently abandoned via a route the
  plan never tested. — **Recommendation**: still open; add the task.

- [ ] **"Second change arrives while the dialog is already open" is now its own bullet, but the
  categorization argument is unchanged.** The item now has its own list entry under "Deliberately
  Deferred" (previously it was folded inside the four-tier-protection bullet) — this is a real,
  if small, improvement in visibility. But the justification is still "the `shouldProtect`
  dialog-open path... is part of the four-tier protection check, which `requirements.md`'s Out of
  Scope section explicitly excludes" — the same reasoning this review previously argued conflates
  "whether to show the dialog" (legitimately out of scope) with "the content already being shown
  silently going stale" (arguably in-scope, since it's about *what's shown*, which is explicitly
  in-scope). The underlying data-safety risk (user picks a resolution against silently-stale disk
  content) is unchanged. — **Recommendation**: the visibility fix is fine as a minimum bar; the
  categorization question is still open for the project owner to make a final call on, not
  something this patch resolved.

## Minors

- **AllPages fallback screen has no conflict-specific affordance.** ADR-003 explicitly and
  knowingly documents this ("does not filter or highlight which pages specifically have
  conflicts... a real but accepted limitation of the minimal fix, not silently hidden"). Confirmed
  `AllPagesScreen.kt` is not touched by any task in the patched plan — `pendingConflictFilePaths`
  is threaded only into `LeftSidebar`'s two existing loops, not into `AllPagesScreen`. On a large
  graph (this repo's own `CLAUDE.md` cites 8,000+ page graphs as a real scale target), clicking the
  banner sends an already-anxious user to hunt through thousands of undifferentiated rows for the
  one with a conflict. This is honestly disclosed as a trade-off, not a hidden gap, so it stays a
  minor rather than a concern — but it's worth flagging that the disclosed limitation is more
  painful at this repo's actual data scale than the ADR's prose implies.
- **No transition specified for the `AlertDialog` → `DiskConflictFullScreen` swap.** Task 3.1.1e's
  gating is a plain `if`, with no `Crossfade`/`AnimatedContent` mentioned. Since the platform
  `Dialog` scrim disappears and an ordinary full-screen composable appears in the same frame or
  two, this will likely read as an abrupt cut rather than a smooth transition. Low severity (it's a
  deliberate, user-initiated action, not an unsolicited state change), but worth a small polish
  pass (fade/slide) given the project's anxiety-reduction framing.
- Carried forward, still unaddressed: the five dialog buttons (Keep/Use Disk/Save-as-new/View
  Full/Manual Resolve) still render as one visually homogeneous button stack — Task 3.1.1c only
  fixed *ordering* ("View full" before "Manual resolve"), not visual differentiation (divider,
  distinct style, icon).
- Carried forward, still unaddressed: Task 3.1.2d's diff rendering still distinguishes
  added/removed lines by background tint alone, no non-color cue (+/- glyph or "Added"/"Removed"
  text) for WCAG "use of color" compliance.
- ADR-002 (session-scoped indicator) remains well-reasoned and appropriately documented as a
  scoping decision on its own terms — the concern above is specifically about the gap between what
  ADR-002 *says* it will surface in UI copy and what actually got built, not about the underlying
  decision to session-scope.
