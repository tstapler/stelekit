# ADR-001: Use kotlin-multiplatform-diff for "View Full" Comparison, Rendered as a Full-Screen Route

**Status**: Accepted
**Date**: 2026-07-03
**Context**: disk-conflict-dialog-ux — Gap #1 (mismatched preview granularity) and Gap #2 (hard truncation with no escape hatch)

---

## Context

`DiskConflictDialog.kt` truncates both the "Your edit" and "Disk version" previews at 200
characters (`DiskConflictDialog.kt` L52, L69) with no way to see the full content. Requirements
call for a "view full" expansion reachable before the user commits to a resolution.

Two open questions:

1. **How to render a comparison** — flat before/after blobs (as the existing
   `BlockDiffView.kt` in `ui/screens/llm/` already does — it is a plain two-panel
   before/after view, NOT a line diff, despite living in a file named "DiffView"), or an
   actual line-level diff with add/remove/unchanged highlighting?
2. **Where to render it** — inline-expand inside the existing `AlertDialog`'s scrollable
   `Column` (`DiskConflictDialog.kt` L35-38, already `.verticalScroll`), or a separate
   surface?

`kmp/build.gradle.kts` L83 already declares
`implementation("io.github.petertrr:kotlin-multiplatform-diff:1.3.0")` in `commonMain`,
with an inline comment "used for conflict hunk display" — but a repo-wide search turns up
**zero actual usages**. It is dead weight today.

## Decision

1. **Use the already-declared `kotlin-multiplatform-diff` library** (`DiffUtils.diff(original,
   revised) -> Patch<T>` over `String.lines()` lists) to compute a real line-level diff for the
   "view full" comparison, rendered as add/remove/unchanged highlighted spans.
2. **Render "view full" as a separate full-screen composable** (`DiskConflictFullScreen.kt`),
   shown via a new `AppState.diskConflictViewFullVisible: Boolean` flag and a `Scaffold`-based
   layout modeled directly on the existing `ConflictResolutionScreen.kt`
   (`ui/screens/git/ConflictResolutionScreen.kt` L72, git-merge-conflict resolution — already a
   proven full-screen, non-dialog pattern in this codebase) — **not** an inline expansion inside
   `DiskConflictDialog`'s `AlertDialog` Column.
   - **Amendment (post-review)**: "closing the full-screen view returns to the still-open
     `DiskConflictDialog`" refers to *state*, not continuous *rendering*. `appState.diskConflict`
     stays non-null the entire time (state survives the round trip), but `DiskConflictDialog`'s
     render call at `GraphDialogLayer.kt` L274 must be explicitly gated on
     `!appState.diskConflictViewFullVisible`. Without this gate, Compose's `AlertDialog` (backed
     by `Dialog`, which opens its own platform window/Popup that always draws above regular
     composed content on every target platform) would render on top of the full screen the moment
     it's opened, making "View full comparison" appear to do nothing. This was independently
     flagged as a BLOCKER by all three review passes (adversarial, architecture, UX) against the
     first draft of this plan, which specified the full-screen render as an unconditional sibling
     block rather than a mutually-exclusive one. See `plan.md` Task 3.1.1e.
3. Extract the diff computation into a pure, non-Composable function (`computeDiskDiffState`)
   returning a small sealed result type, so the identical-content and blank-content edge cases
   are unit-testable without a Compose test harness.

## Rationale

**Why the diff library, not flat before/after panels:**
- Zero new dependency — it is already on the `commonMain` classpath and already declared with
  exactly this intent in the build file's own comment.
- Pure-Kotlin, KMP-safe (works identically on JVM/Android/iOS/WASM) — no platform-specific diff
  implementation needed.
- A real line diff is what gap #1 (mismatched preview granularity) actually needs: the user
  needs to see *what changed*, not two independently-truncated blobs they must manually compare.
  `BlockDiffView.kt`'s existing before/after pattern is proven insufficient for this — it's
  literally the anti-pattern gap #1 complains about, just without the 200-char truncation.
- Avoids hand-rolling a diff algorithm, which the research explicitly flags as unnecessary
  given the library is one `implementation()` line away from being usable.

**Why a separate full-screen route, not inline-expand:**
- `Text` in Compose has no virtualization; a full whole-file dump inside the existing
  `AlertDialog`'s `verticalScroll` Column does a synchronous full-string layout pass on the UI
  thread and creates scroll-within-scroll gesture conflicts, especially on Android (per
  `research/pitfalls.md`).
- The codebase already has a proven full-screen (Scaffold, not Dialog) pattern for exactly this
  shape of problem — reviewing/resolving N conflicting text regions — in
  `ConflictResolutionScreen.kt`. Reusing it is lower-risk than inventing a new dialog-expansion
  interaction pattern.
- Matches the desktop-vs-mobile layout guidance from research (desktop can go side-by-side,
  mobile can stack/tab) more naturally than a modal dialog can.

## Consequences

- New file `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/DiskConflictFullScreen.kt`.
- New `AppState.diskConflictViewFullVisible: Boolean` field, toggled the same way
  `conflictResolutionVisible` already is (`StelekitViewModel.kt` L225, L284).
- The "Manual resolve" and other resolution buttons in `DiskConflictDialog` remain unchanged;
  "View full comparison" is an additional, non-destructive button that does not resolve the
  conflict — it only opens a read-only view.
- `BlockDiffView.kt` is left as-is (different domain: LLM suggestion review, not disk conflicts)
  — this ADR does not migrate it to the new diff library, to avoid scope creep into an unrelated
  feature.
- `DiskConflictDialog`'s render call in `GraphDialogLayer.kt` gains an explicit
  `!appState.diskConflictViewFullVisible` guard (see Amendment above and `plan.md` Task 3.1.1e).
  This makes `DiskConflictDialog` and `DiskConflictFullScreen` mutually exclusive in composition
  at all times, at the cost of one additional conditional in `GraphDialogLayer.kt` — a cheap,
  structural way to guarantee only one of the two is ever visible, without needing to relitigate
  whether `DiskConflictFullScreen` should instead be rendered as its own `Dialog` window (an
  alternative considered and rejected below).

## Alternatives Considered

- **Hand-rolled diff (e.g. simple LCS)**: rejected — reinvents what the declared dependency
  already provides, for no benefit.
- **Inline expand-in-dialog** (grow the `AlertDialog` or add a "Show more" toggle inside the
  scrollable Column): rejected per the scroll-within-scroll and large-text-on-UI-thread risks
  noted above.
- **Reuse `BlockDiffView.kt` as-is**: rejected — it does not diff, it only shows two panels
  side by side, which does not solve gap #1's "cannot actually compare" problem.
- **Render `DiskConflictFullScreen` inside its own full-screen `Dialog` with
  `DialogProperties(usePlatformDefaultWidth = false)`** (raised during review as an alternative to
  the `!diskConflictViewFullVisible` gate): would also achieve "renders on top," since it would
  join the same platform window layer as `DiskConflictDialog`. Rejected in favor of the explicit
  boolean gate because it's a smaller diff against the plan's existing `Scaffold`-based
  `DiskConflictFullScreen` design (Decision #2), doesn't introduce a second `Dialog`-hosted
  full-screen surface pattern into the codebase, and makes the mutual-exclusivity invariant
  visible as a single `if` at the call site rather than implicit in two independently-configured
  `Dialog`s.
