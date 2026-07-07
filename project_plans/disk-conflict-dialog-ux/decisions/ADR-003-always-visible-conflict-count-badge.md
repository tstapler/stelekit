# ADR-003: Always-Visible Sidebar Conflict-Count Badge as the Fallback Indicator Surface

**Status**: Accepted
**Date**: 2026-07-03
**Context**: disk-conflict-dialog-ux — Gap #4 (no persistent indicator for a deferred conflict),
post-review patch (UX + Adversarial review BLOCKER: sidebar coverage gap)

---

## Context

The original Phase 5 design (Story 5.1.1/5.1.2) wired `hasPendingConflict` into `SidebarItem` at
exactly the two loops `LeftSidebar` renders: Favorites (`Sidebar.kt` L210-218) and Recent
(`Sidebar.kt` L231-239, a bounded list). Both independent UX and adversarial reviews flagged this
as a BLOCKER: a page with an entry in `AppState.pendingConflicts` that is neither favorited nor
still within the bounded "Recent" window — the common case, since most pages in a graph are
neither favorited nor recently visited, and a conflict can originate from a tier such as a
pending-actor-write on a page the user hasn't opened in a while — gets **zero visual indicator
anywhere in the app**. This directly undercuts the requirement this project exists to fix ("no
persistent marker... easy to forget and lose track of pending, unresolved external changes") for
what is likely the majority of real-world cases.

Two candidate fixes were considered:

1. Extend `AppState.regularPages` (the paginated all-pages list, `AppState.kt` L113-115) into a
   full page-list surface with per-row indicators, matching the Favorites/Recent treatment.
2. Add a small, always-visible **count** badge in existing sidebar chrome, independent of
   favorites/recency, that the user can click to reach an existing page-discovery surface.

Per `research/architecture.md`, `AppState.regularPages` exists but **has no consuming screen
today** — there is no rendered "all pages" list backed by it in the current codebase for option 1
to extend. Building one would mean building new UI surface area, which risks growing into the
explicitly out-of-scope "full Conflicts list/inbox screen" this project's own "Deliberately
Deferred" section already rules out.

## Decision

Implement option 2: a small, always-visible `PendingConflictsBanner` in `LeftSidebar`'s
persistent chrome (rendered near `SyncStatusBadge`, above the Favorites/Recent lists, gated only
on `pendingConflictFilePaths.isNotEmpty()` — not on favorite/recency membership), showing a
plain count ("N pages have unresolved conflicts"). Clicking it navigates to the existing
`Screen.AllPages` route via `LeftSidebar`'s already-present `onNavigate` callback — no new screen,
no new navigation target, no bulk-action UI.

This guarantees every page with a pending conflict has *some* persistent, always-reachable signal
in the running session, closing the coverage gap, while keeping the fix to a single new
composable and one new prop-free wiring path (it reuses `pendingConflictFilePaths`, already
threaded by Task 5.1.2a/5.1.3a, and `onNavigate`, already a `LeftSidebar` parameter).

## Rationale

- **Minimal, proportionate scope.** No new screen, no new `AppState` fields beyond what Phase 5
  already added, no new navigation target. `Screen.AllPages` already exists and is already
  reachable from the sidebar's "Navigation" section — this decision only adds a second, seeded
  entry point to it.
- **Does not silently under-deliver against the coverage requirement.** The two-tier design
  (per-row marker for Favorites/Recent + fallback count badge for everything else) is the
  documented answer to "does every page with a pending conflict have an indicator" — yes, at two
  different levels of specificity (which page + a link to go find it, vs. a page's own row).
- **Consistent with ADR-002's session-scoped-only decision.** The badge reads the same
  session-scoped `pendingConflictFilePaths` set ADR-002 already established as the source of
  truth — no new persistence model, no cross-restart durability implied or promised.
- **Does not expand into the deferred "Conflicts list" screen.** `requirements.md` and this
  plan's own "Deliberately Deferred" section explicitly rule out a full inbox-style screen with
  bulk actions as a separate follow-up project. Routing the badge's click to the existing
  `Screen.AllPages` (rather than building a new filtered/dedicated view) keeps this fix inside
  that boundary — the user still has to locate the specific page themselves on that screen, which
  is a real but accepted limitation of the minimal fix, not silently hidden (this ADR documents
  it explicitly).

## Consequences

- New composable `PendingConflictsBanner` in `Sidebar.kt` (Task 5.1.4a), rendered unconditionally
  in `LeftSidebar`'s chrome whenever the pending-conflict set is non-empty (Task 5.1.4b).
- The badge's click target is `Screen.AllPages`, an undifferentiated page list — it does not
  filter or highlight which pages specifically have conflicts. A user with multiple deferred
  conflicts must still visually scan or use the per-row marker (Story 5.1.1) once they navigate to
  a favorited/recent page, or otherwise locate the affected page themselves. A dedicated,
  filtered "pages with conflicts" view is exactly the deferred "Conflicts inbox" follow-up project
  already noted in `plan.md`'s "Deliberately Deferred" section — not built here.
- Reuses the same amber `Color(0xFFF59E0B)` treatment as the per-row indicator (Task 5.1.1a) for
  visual consistency between the two tiers of the same feature — this is intentionally different
  from the "don't reuse the *composable*" guidance for `SyncStatusBadge` (a different conflict
  *type* entirely); reusing the color here signals "these are the same kind of thing, shown at two
  different granularities," which is exactly what they are.

## Alternatives Considered

- **Extend `AppState.regularPages` into a full indicator-bearing page list**: rejected for this
  project — no consuming screen exists today to extend, and building one is out of proportion to
  a UX bug-fix project; would also risk scope creep into the deferred "Conflicts inbox."
- **Do nothing beyond Story 5.1.1/5.1.2 (favorites/recent only)**: rejected — this is the gap the
  review blocked on; silently shipping partial coverage against a requirement whose entire point
  is "don't lose track of conflicts" was judged unacceptable by two independent reviews.
- **A dedicated filtered "Conflicts" screen reachable from the badge**: rejected — this is
  precisely the "full Conflicts list/inbox screen" `requirements.md` and this plan's own
  "Deliberately Deferred" section already scope out as a separate follow-up project.
