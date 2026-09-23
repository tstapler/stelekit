# UX Design: web-git-writeback

**Date**: 2026-07-14
**Feature**: `WasmGitWriteService` + `WasmGitRepository` — makes git sync actually push web edits to GitHub/GitLab (closes BUG-005 Phase 2, per ADR-013/ADR-015/ADR-017).
**Inputs**: `requirements.md`, `research/ux.md`, `implementation/plan.md` (Epics 1.3, 3.4, 3.5, 5.1; `SyncState`/`GitError` additions).
**Principle carried through every decision below** (per `research/ux.md` §5): *would this design have made the original bug ("my edits never reached the remote, and I couldn't tell") detectable to the user sooner?* Every wireframe, copy string, and acceptance criterion is evaluated against that test.

This is UI-*reuse*, not UI-*creation* — per ADR-015's own rationale, confirmed by research. No new screens are introduced. Four existing surfaces are touched: `SyncStatusBadge.kt`, `ConflictResolutionScreen.kt`, `GitSetupScreen.kt` (confirmed: no new surface needed — see Surface 3), and `DomainError.kt`'s error-copy layer. One new lightweight interaction (a toast-class disclosure) is added for auto-merge, following the `JournalMergeReviewScreen.kt` precedent. One new browser-level mechanism (`beforeunload`) is added with no dedicated visual surface.

---

## Surface inventory

| # | Surface | File | Change type |
|---|---|---|---|
| 1 | Sync status badge | `ui/components/SyncStatusBadge.kt` + `git/model/SyncState.kt` | New state (`LocalChangesPending`) rendered; existing states reused as-is |
| 2 | Conflict resolution screen | `ui/screens/git/ConflictResolutionScreen.kt` | Reused unmodified — new consumer (web git-merge conflicts), not new UI |
| 3 | Git setup screen (PAT entry) | `ui/screens/git/GitSetupScreen.kt` | Confirmed no new surface needed; two copy-only follow-ups noted, out of this project's committed Scope |
| 4 | Auto-merge disclosure | `SyncStatusBadge.kt` (`Success` state, enriched) | New toast-class disclosure, no new screen |
| 5 | Rate-limit / partial-failure / offline error copy | `SyncStatusBadge.kt` `Error` branch + `DomainError.kt` | Copy-only — reuses existing `Error` rendering, new `GitError` variants |
| 6 | Tab-close warning | Browser-level `beforeunload`, no Compose surface | New mechanism, no new screen |

Five visual surfaces plus one non-visual browser mechanism. Surfaces 4 and 5 are copy/state extensions of Surface 1, not independent screens — they're broken out separately below because they have distinct interaction flows and edge cases the requirements call out by name.

---

## Surface 1: Sync status badge — `LocalChangesPending`

### Why this is the highest-leverage surface (per research §2)
Desktop's `Idle` state renders nothing because idle means "fully synced, nothing to say." On web, `Idle` today conflates two different states: "OPFS-saved and fully pushed" and "OPFS-saved but not yet pushed" — the exact ambiguity that hid the original bug. `LocalChangesPending(fileCount: Int)` (plan Epic 1.3, Story 1.3.2) closes this gap.

### Wireframe — sidebar header region (reusing `SyncStatusBadge`'s existing `Row` layout)

```
Existing Idle (desktop, and web when fully synced):
┌─────────────────────────────┐
│  [Sidebar header]      ⟳    │   ← only the manual "Sync now" IconButton, no badge
└─────────────────────────────┘

New: LocalChangesPending(fileCount = 2) (web only, dirty set non-empty, no op in progress):
┌─────────────────────────────┐
│  [Sidebar header]  ☁↑ 2  ⟳  │   ← visible badge + label, distinct from Idle's blank
└─────────────────────────────┘
     icon: cloud-upload / outline-sync glyph (NOT the red Error icon, NOT green Success check)
     label: "2 unsynced"
     tint: MaterialTheme.colorScheme.onSurfaceVariant (neutral — informational, not alarming)
     contentDescription: "2 unsynced changes — tap to sync"
     clickable region → onSyncClick() (same as every other non-idle state)

Transition, same session:
  edit page → PlatformFileSystem.writeFile → dirtyFileCountFlow: 0 → 1
    → StelekitViewModel.syncState: Idle → LocalChangesPending(1)   [badge appears]
  user taps ⟳ or auto-sync fires → Committing → Pushing → Success  [badge shows spinner, then green check]
  push succeeds → dirtyFileCountFlow: 1 → 0 → syncState: Idle       [badge disappears again — correctly, now it's true]
```

### Interaction flow
1. User edits a page. `PlatformFileSystem.writeFile` records the dirty entry (Epic 2.1). `dirtyFileCountFlow` increments.
2. `StelekitViewModel.syncState` combine (Task 4.3.2c) recomputes: raw state is `Idle` and count > 0 → emits `LocalChangesPending(count)`. Badge renders immediately (no debounce needed — `dirtyFileCountFlow` is already in-memory-first, per plan Task 2.1.1d).
3. Badge shows `"N unsynced"` with a neutral cloud-upload glyph. Tapping it or the adjacent `⟳` button triggers `onSyncClick()` — identical affordance to every other state, per research's accessibility note (no new interaction pattern to learn).
4. Sync runs (`Committing` → `Pushing` spinner states, unchanged). On success, `dirtyFileCountFlow` returns to 0 (cleared last, after ref update — Task 2.1.2d), state resolves to `Idle` (badge disappears) or, if a merge occurred, to the enriched `Success` disclosure (Surface 4).
5. If the user closes the tab while `LocalChangesPending` is active, see Surface 6.

### Error / edge cases
- **Multiple edits before first sync fires**: count keeps incrementing (`"2 unsynced"` → `"5 unsynced"`) — never spawns multiple badges or resets misleadingly.
- **User is offline while editing**: `LocalChangesPending` still shows correctly (it reflects the *local* dirty set, not remote reachability) — this is the correct behavior per research §2's durability-class distinction; a separate `GitError.Offline` only appears once a sync is *attempted* and fails.
- **A sync is already in progress when a new edit lands**: per Task 4.3.2c's acceptance criteria, `LocalChangesPending` only overrides `Idle` — an in-progress state (`Fetching`/`Merging`/`Pushing`/`Committing`) is never interrupted or replaced by it. The spinner keeps showing; the new edit is simply included in the next sync's dirty-set read (dirty-set is re-derived from scratch, per ADR-015).
- **JVM/Android**: `localChangesCountFlow` is `null` → `LocalChangesPending` is structurally unreachable (Task 4.3.2c's first acceptance criterion) — zero visual change on those platforms.

### UX acceptance criteria — Surface 1
1. On web, editing a page with git sync configured shows a visible, non-blank badge within one recomposition frame of the write — user can complete "confirm my edit is tracked as unsynced" in 0 extra steps (it's ambient, not a screen to visit).
2. The `LocalChangesPending` badge is visually distinct from `Idle` (blank), `Error` (red), `ConflictPending` (amber), and `Success` (green check) — distinguishable by color alone AND by icon shape (redundant coding, not color-only, per WCAG 1.4.1).
3. Tapping the badge or the `⟳` button in the `LocalChangesPending` state triggers a sync in exactly 1 click — same click count as every other actionable badge state.
4. No dead end: `LocalChangesPending` always has the same tap-to-sync exit path as every other state; there is no state where the badge is visible but inert.
5. Accessibility: `contentDescription` on the new badge region reads `"N unsynced changes — tap to sync"` (states the count AND the action, not just an icon name). The region carries `Modifier.semantics { role = Role.Button }` (verify — `research/ux.md` flagged that bare `clickable` `Row`s in this file need this explicitly checked, and this is a newly-added `Row` so it must get it correctly from day one, not inherit a pre-existing gap). Color contrast of the neutral badge text against the sidebar background ≥ 4.5:1 (verify against `MaterialTheme.colorScheme.onSurfaceVariant` in both light and dark theme — do not assume the existing token passes without checking, since this is new text at `labelSmall` size, which is more contrast-sensitive than body text).
6. State-transition live-region announcement: `Idle → LocalChangesPending → Committing → Pushing → Success/Error` transitions are each announced to screen readers via `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the badge's dynamic content region — a screen-reader user who isn't focused on the sidebar still learns their edit became unsynced and later became synced, without having to poll by re-focusing the region. (This closes a pre-existing gap `research/ux.md` §3 flagged as inherited debt; since this project adds the state that makes the gap user-visible for the first time on web, fixing it is in scope here.)
7. Minimum touch-target size: the `LocalChangesPending` badge's clickable region (Surface 1) and the `RateLimited` badge region (Surface 5, same sidebar badge slot — see that surface's table) each meet a minimum interactive-target size of ≥44×44pt (iOS HIG) / ≥48×48dp (Material/Android), achieved via padding/`Modifier.sizeIn(...)` around the region rather than by enlarging the visible icon or text — the web target is reachable from mobile browsers (per requirements.md), where an undersized tap target is the dominant mis-tap failure mode. `RateLimited`'s region has no click handler (Story 5.1.2 — the tap is intentionally inert), but is still sized to the same minimum so the badge slot doesn't visually jump size between states and any assistive-tech targeting of the region stays consistent. Cross-reference: `plan.md` Task 5.1.1a (and Task 5.1.2a for the non-interactive `RateLimited` case) apply the corresponding Compose modifier.

---

## Surface 2: Conflict resolution screen — git-merge conflicts on web

### What's reused, unmodified
`ConflictResolutionScreen.kt` already renders exactly what web needs: a per-file `Card` with `wikiRelativePath`/`filePath`, two `FilterChip`s ("Keep mine" / "Use remote"), an "Abort merge" confirm dialog, and a subtitle ("A background sync pulled remote changes that conflict with your local edits"). Per plan Pattern Decision "Conflict representation," `ConflictFile.hunks` stays `emptyList()` for all web conflicts — confirmed by tracing the actual consumer: **no code path in this screen reads `hunks`.** No diff view is needed or built.

### Wireframe — identical to existing desktop screen, new trigger source only

```
┌──────────────────────────────────────────────────────┐
│ ← Resolve Merge Conflicts                     Cancel │
├──────────────────────────────────────────────────────┤
│ A background sync pulled remote changes that conflict │
│ with your local edits.                                 │
│ 1 file(s) need resolution. Choose which version to    │
│ keep for each.                                          │
│                                                          │
│ ┌────────────────────────────────────────────────────┐│
│ │ pages/Foo.md                                        ││
│ │  [💻 Keep mine ✓]      [☁ Use remote]              ││
│ └────────────────────────────────────────────────────┘│
│                                                          │
├──────────────────────────────────────────────────────┤
│                          [ ⟳ Finish Merge ]            │
└──────────────────────────────────────────────────────┘
```

### Interaction flow (web-specific trigger, screen unchanged)
1. `WasmGitWriteService.partitionConflicts()` finds a file-path overlap between the local dirty set and the remote compare delta (plan Story 3.3.2). `WasmGitRepository.merge()` returns `MergeResult(hasConflicts = true, conflicts = [ConflictFile(path, path, hunks = emptyList())])`.
2. `GitSyncService.sync()` (unmodified) sets `syncState = ConflictPending(conflicts)`. Badge (Surface 1) shows the amber "Conflict" chip — same as desktop.
3. User taps the badge → `ConflictResolutionScreen` opens, defaulting every file's selection to `LOCAL` ("Keep mine"), matching desktop's existing default.
4. Per file, user picks "Keep mine" or "Use remote". Web-specific behind the scenes: "Use remote" triggers `WasmGitRepository.checkoutFile(REMOTE)`, which fetches the file's remote content over the network (there's no local working-tree checkout on web) and writes it via the normal dirty-tracked `writeFile` path (Task 3.3.2b) — this is invisible to the user; the screen doesn't need to show a fetch-in-progress spinner per file since the fetch is fast (single small file) and `resolving` already covers the aggregate "Finish Merge" tap.
5. User taps "Finish Merge" → `onResolve(selections)` → `resolveConflictBySide` → re-derives the dirty set and calls `commit()` again (does not push yet, matches desktop). Screen dismisses on success; `syncState` returns to `LocalChangesPending` (there's a new pending commit, not yet pushed) — **not** `Idle` and **not** `Success`. This is a correctness-critical distinction: the merge commit exists locally/as a pending GitHub commit object but the ref hasn't moved — the badge must keep showing unsynced state, not falsely imply completion. This transition is now explicitly traced and locked in by `plan.md` Story 3.3.2's added acceptance criterion (raw `Idle` + nonzero dirty count → `StelekitViewModel.syncState` derives `LocalChangesPending`) plus Task 4.3.2e's dedicated regression test — no longer an unverified assumption.
6. A subsequent sync (auto or manual) actually pushes the merge commit, landing on `Success`.

### Error / edge cases
- **PAT invalid/expired mid-resolution**: if `checkoutFile(REMOTE)`'s fetch or the follow-up `commit()` hits a 401, the "Finish Merge" button's existing `error` `Text` slot (already wired, line ~88-95 of the screen) shows `"Resolution failed: <message>"`. Recommend the message text specifically route through `CredentialExpired` copy ("GitHub authentication expired — tap to re-connect") rather than a bare exception string, so the user isn't left in the merge screen with no route to fixing the real cause — see acceptance criterion 4 below.
- **Deleted-locally-but-edited-remotely** (Task 3.3.2d): the file still appears as a normal conflict card. "Keep mine" = stays deleted (no-op). "Use remote" = the file is resurrected (re-fetched and re-written). The card gives no special affordance distinguishing this from a normal edit-vs-edit conflict — acceptable per research (file-path-level granularity, not content-level), but the card's title should still show the path so the user isn't confused why a "deleted" file has a conflict at all. No copy change needed — `wikiRelativePath` display already covers this; flagging only as a verify-in-implementation item, not a new design element.
- **Abort merge**: existing dialog's copy ("Canceling will undo the merge attempt. Your local changes will be preserved and the remote changes discarded.") is already accurate for the web path — `abortMerge` clears the pending-merge SHA state without touching the dirty set (Task 3.3.2c), so local edits truly are preserved. No copy change needed.
- **Previously-outstanding bug** (`docs/tasks/git-sync-ux.md` line 7): "Cancel leaves git in MERGING state" — verified against current source: this is already fixed (`ConflictResolutionScreen.kt`'s Cancel routes through an "Abort merge?" confirm dialog → `onAbortMerge` → `GitSyncService.abortActiveMerge` → `gitRepository.abortMerge(config)`, landed in `533fbbae03`). What was still missing — a regression test for the happy/failure path, and the stale unchecked checklist item — is now closed by `plan.md` Story 3.3.3. This was a pre-existing defect, not new scope, but shipping web reachability is what makes it reachable by a new population of users for the first time, which is why locking in the fix belongs in this project.

### UX acceptance criteria — Surface 2
1. User can resolve a single-file conflict and complete the merge in 2 clicks (1 `FilterChip` selection defaulting correctly + 1 "Finish Merge" tap) — for N files, N+1 clicks (one selection per file is only required to *change* the default; if all defaults are acceptable, it's still just 1 click for "Finish Merge").
2. No dead end: "Abort merge" is reachable from the top app bar at every point during resolution, confirmed via a dialog that states the exact consequence (local preserved, remote discarded) before committing to it.
3. After "Finish Merge" succeeds, the sync badge does NOT show a state implying full completion (`Idle`/`Success`) until the merge commit is actually pushed — this transition is now explicitly specified and tested by `plan.md` Story 3.3.2's added acceptance criterion and Task 4.3.2e (previously flagged above as unverified).
4. Error state during resolution shows a specific, actionable message — not a bare exception string — and specifically routes PAT-expiry failures to "tap to re-connect" framing consistent with the `CredentialExpired` badge state, not a generic "Resolution failed: <raw error>".
5. Accessibility: unchanged from existing screen (already has decorative `contentDescription = null` on chip icons since the adjacent text label already names the choice — correct as-is, verify no regression). `FilterChip`'s built-in Compose Material3 selection semantics already announce selected/unselected state — no new work needed here since this screen is reused, not modified.

---

## Surface 3: Git setup screen (PAT entry) — confirmed, no new surface

Per the requirements' Step 1 instruction to "confirm no new surface needed there per research": confirmed. `GitSetupScreen.kt` Step 3 already has masked PAT entry (`PasswordVisualTransformation` + visibility toggle, verified at lines 735-743), an OAuth device-flow alternative, and per-platform security copy. This project does not touch this screen's structure.

Two copy-level gaps research identified (scope-specific guidance + deep-linked token creation URL) are **not** committed acceptance criteria for this project — `requirements.md`'s Scope section does not name them, and `plan.md`'s Unresolved Questions explicitly defers UI polish items not in Scope. Recorded here as a **fast-follow recommendation**, not a blocking requirement:
- Add inline text naming the minimum required scope (`repo` for classic PAT / Contents read+write for fine-grained PAT) next to the token field.
- Add a "Create token" link pre-filled via `github.com/settings/tokens/new?scopes=repo&description=SteleKit` (GitLab: `personal_access_tokens/new?scopes[]=api`).

**If picked up in this project's implementation pass anyway** (cheap, one-line addition, high trust payoff per research's emotional-JTBD framing), the acceptance criterion would be: *given the PAT field is focused, a visible link/text names the exact scope needed and, when tapped, opens a token-creation page with that scope pre-selected.* Not required for this design doc's sign-off since it's out of committed Scope.

---

## Surface 4: Successful auto-merge disclosure

### Design decision (per research §3, "silent-and-correct but not silent-and-unauditable")
Per `plan.md`'s Unresolved Questions, the **UI enrichment** of `SyncState.Success` with an `autoMergedCount` field is explicitly **not in this project's committed Scope** (only `LocalChangesPending` and `RateLimited` are named in requirements.md's Scope). The plan implements only the *logging* requirement (Story 3.5.1). This design doc records the UX-recommended shape for when that fast-follow ships, so the disclosure is designed once rather than improvised later, but does **not** treat it as a blocking acceptance criterion for this project.

### Wireframe (fast-follow, not required for this project's sign-off)
```
Current Success (unchanged, ships as-is this project):
  Green checkmark icon, "Sync complete" contentDescription, fades after 3s.

Recommended future enrichment (NOT built this project):
  Green checkmark icon + label "Synced (2 auto-merged)"
  contentDescription: "Sync complete — 2 files auto-merged with remote changes"
  Same 3-second fade — non-blocking, no click-through required (nothing to decide).
```

### What DOES ship this project
- Every auto-merge outcome is logged via `Logger("WasmGitWriteService")` with the file list (Story 3.5.1, Task 3.5.1a) — auditable via dev tools/console, not surfaced in the UI.
- The plain `SyncState.Success` checkmark fires identically whether or not an auto-merge occurred underneath — **this is a known, accepted UX gap for this project's scope**, not an oversight; call it out explicitly to product if user reports of "my remote content looks different than I expected" surface post-ship, since the trace exists (in logs) but isn't user-visible without opening dev tools.

### UX acceptance criteria — Surface 4
1. (This project) Auto-merge outcomes are present in the browser console/log output with the affected file list and are never silently dropped — verifiable by a developer, not by an end user, this project.
2. (Fast-follow, recorded not required) When built: the enriched Success state is dismissible/non-blocking (no forced click-through, since nothing needs deciding per ADR-015), announced via the same live-region mechanism as Surface 1's criterion 6, and readable at ≥4.5:1 contrast (green-on-background, verify existing `Color(0xFF047857)` against both theme backgrounds since it's about to carry more information-bearing text than a bare icon).

---

## Surface 5: Rate-limit, partial-failure, network-offline error copy

All three route through the **existing** `SyncStatusBadge.Error` rendering (red icon + `toSyncErrorMessage()` text, tap-to-retry or tap-to-reauth per `AuthFailed` branching) — no new visual surface, only new `GitError` variants and copy strings (plan Epic 1.3 Story 1.3.1, Epic 3.4).

### Wireframe — all three share the existing `Error` badge layout
```
┌─────────────────────────────┐
│  [Sidebar header]  ⚠ <msg>  ⟳│   ← existing red Error icon + text, unchanged layout
└─────────────────────────────┘
```
Only the `<msg>` text and tap-target behavior differ per case:

| Case | `GitError` variant | Badge text | Tap behavior |
|---|---|---|---|
| Rate-limited | `RateLimited(retryAfterSeconds)` | `"Rate limited — retrying automatically"` | **No manual retry framing** — tapping does not re-trigger an immediate retry (would just re-hit the limit); auto-backoff-retry runs regardless of taps (Ktor `HttpRequestRetry`, Task 3.4.1a) |
| Network offline | `Offline` (existing, reused) | `"Offline — sync will resume when connected"` (existing copy, unchanged) | Passive — resumes automatically on reconnect; tapping is harmless but not required |
| Partial-failure (5-step sequence failed before ref update) | `PushFailed`/`CommitFailed` (existing variants, **new copy**) | `"Sync failed partway through — nothing was changed on GitHub. Your local changes are safe; sync will retry."` | Tap-to-retry (standard `PushFailed` behavior — retry re-derives the dirty set from scratch per ADR-015, safe to repeat) |
| PAT invalid/expired mid-session | `CredentialExpired` (existing, reused) | `"GitHub authentication expired — tap to re-connect"` (existing copy) — **verify tap target opens the PAT field specifically for web's HTTPS-token auth mode, not an OAuth flow that may not apply** (research §4 gap) | Tap → re-auth surface matching whichever credential mode is actually configured |
| File too large | `FileTooLarge(path, size, max)` | `"<path> is too large to sync (<size> > <max> limit)"` | Tap-to-retry is not useful here (the file will always be too large) — recommend the tap instead does nothing destructive (falls through to generic retry harmlessly) since no dedicated "acknowledge and skip this file" flow exists in scope |

### Interaction flow — rate-limited (the one genuinely new *behavioral* pattern here)
1. A blob/tree/commit POST returns `429` or `403`-with-`Retry-After`. Ktor's `HttpRequestRetry` (Task 3.4.1a) retries automatically with exponential backoff, honoring the header.
2. If retries exhaust (Task 3.4.1b), `WasmGitWriteService` surfaces `GitError.RateLimited(retryAfterSeconds)`. Badge shows the amber-adjacent-but-still-red `Error` state (reusing existing red tint — rate-limiting is still a failure state, just a self-resolving one, so keeping it in the `Error` family rather than inventing a new tint is correct per Pattern Decision reuse).
3. Badge text explicitly says "retrying automatically" — **the single most important copy decision in this table**, since a user tapping "retry" immediately on a rate-limited error would just re-trigger the limit. This directly implements research §4's flagged gap.
4. When the retry eventually succeeds (in the background, no user action needed), badge transitions `Error(RateLimited) → Committing/Pushing → Success`, same as any other successful sync.

### Interaction flow — partial-failure reassurance
1. GitHub's 5-step sequence fails between step 4 (commit object created) and step 6 (ref update) — e.g., network drops between `commit()` and `push()`, or the final PATCH 500s.
2. Per ADR-015's atomicity analysis: from the remote's perspective (GitHub's UI, collaborators, `git log`), **nothing changed** — the commit object exists but is unreferenced by any branch, invisible until garbage-collected.
3. The badge must not describe this ambiguously ("sync failed" alone could read as "maybe half my files made it"). Copy is explicit: **"nothing was changed on GitHub"** — stated as fact, not hedged — because it structurally cannot have partially applied (only an orphaned, unreferenced object could exist, and no reader of the remote repo can see it).
4. Retry is safe and correctly re-derives the dirty set from scratch (not a resume-from-where-it-failed attempt) — the copy's "sync will retry" implicitly promises this, and the implementation (plan's Rabbit Holes section) explicitly guarantees it.

### UX acceptance criteria — Surface 5
1. Rate-limited state's badge text never contains "tap to retry" or implies manual action is useful — testable by literal string check against the shipped copy.
2. Partial-failure copy contains the literal reassurance "nothing was changed on GitHub" (or GitLab-equivalent host-neutral phrasing, e.g. "on the remote") and does not contain hedging language ("may have", "possibly") that would imply uncertain partial application.
3. No error state in this table is a dead end: every row has either an automatic resolution path (rate-limited, offline) or an explicit tap-to-retry/tap-to-reauth action (partial-failure, credential-expired) — `FileTooLarge` is the one exception, documented above as accepted (out-of-scope for a per-file-skip flow) rather than silently missed.
4. Copy is host-neutral by construction — no hardcoded "GitHub" in a message a GitLab user could see (verify `RateLimited`/`FileTooLarge`/partial-failure copy against this; existing copy already passes per research's audit, new strings must maintain it — use "the remote" or "GitHub/GitLab" only where the error is structurally host-specific, e.g. never for these three).
5. Color contrast: red `Error` text/icon combination is pre-existing and presumed already ≥4.5:1 (not re-verified here since layout/color is unchanged — only new copy strings are added to an existing, already-shipped visual treatment).

---

## Surface 6: "Don't close this tab" warning

**Status: committed** (per triad UX review — previously recorded here as "recommend product decide before Phase 7, not committed"; now committed as `plan.md` Story 5.1.3, since it's cheap to build and is this design doc's own stated closing piece for the project's core "can I tell my data is safe" goal).

### Design decision (per research §1b)
A native `beforeunload` confirmation, gated strictly on `dirtyFileCountFlow.value > 0` — **the same flow the sync badge itself reads**, never a separate derived flag (research's explicit warning against staleness). No Compose UI — this is a browser-native dialog SteleKit does not control the copy or layout of.

### Flow
```
User has LocalChangesPending(2) showing → attempts to close tab / navigate away
   → browser's native "Leave site? Changes you made may not be saved." dialog fires
   → user cancels → stays on page, badge still shows "2 unsynced"
   → user confirms leave → tab closes; the 2 dirty files remain recorded in
     .stele-dirty-set.json (OPFS), so on next visit LocalChangesPending(2)
     reappears immediately (no silent loss of tracking, per Story 2.1.2's
     crash-safe restore-on-preload guarantee)
```

Gated strictly on the boolean derived from `dirtyFileCountFlow.value > 0` — never fires when the dirty set is empty (avoids the overuse-trains-dismissal failure mode research flagged: firing on every navigation regardless of risk teaches users to reflexively click through, defeating the warning the one time it matters).

### UX acceptance criteria — Surface 6
1. Closing/navigating away from the tab while `dirtyFileCountFlow.value > 0` triggers the browser's native unload-confirmation dialog; closing while it's `0` does not (verified via a scripted test toggling the flow value and asserting the `beforeunload` listener's `preventDefault`/return-value behavior matches).
2. The gating flag is read directly from `PlatformFileSystem.dirtyFileCountFlow` (or an equivalent single source) at unload time — not a separately-maintained boolean that could drift from the real dirty set (structural check: grep the implementation for a second "hasUnsavedChanges"-shaped field; there should be none).
3. Reloading/reopening the tab after a forced close with pending changes restores `LocalChangesPending(N)` with the correct count within one page-load cycle (covered functionally by Story 2.1.2's `preload()` restore acceptance criteria — this is the UX-visible consequence of that already-planned behavior, not new implementation work).
4. This mechanism is a backstop, not the primary signal — Surface 1's badge must already have communicated unsynced state before a close attempt ever happens; `beforeunload` firing should never be a user's first indication that something was unsynced.

**Note**: this surface originated as a `research/ux.md` §1b/§5 recommendation, not a `requirements.md` Scope item. It is now committed — `plan.md` Story 5.1.3 implements criteria 1-4 below as a blocking deliverable for this project's ship gate, not a fast-follow. (Alongside Surfaces 1, 2, and the logging half of Surface 4, all directly backed by named Stories in `plan.md`.)

---

## Cross-surface accessibility summary

| Requirement | Surfaces affected | Status |
|---|---|---|
| Keyboard-navigable (Tab focus + Enter/Space activation) | 1 (new `LocalChangesPending` region), 2 (unchanged, already compliant) | New region (Surface 1) must get `role = Role.Button` explicitly from creation — do not inherit the pre-existing bare-`clickable` gap research flagged in the *other* states; verify at implementation. |
| Screen-reader labels (`contentDescription`) | 1, 2, 5 | Surface 1's new state needs a description naming both count and action (`"N unsynced changes — tap to sync"`). Surfaces 2 and 5 reuse existing, already-correct descriptions/text. |
| Live-region announcement of state changes | 1, 4 (fast-follow) | Net-new requirement this project should add for Surface 1 (Acceptance Criterion 6) since `LocalChangesPending` is the first state whose *appearance* (not just error resolution) is time-sensitive information a screen-reader user could otherwise miss entirely. |
| Color contrast ≥ 4.5:1 | 1 (new neutral badge text), 2 (unchanged), 4 (fast-follow), 5 (unchanged, new copy only) | Only Surface 1's new neutral-tint text is genuinely unverified — flagged explicitly for implementation-time contrast check against both light and dark theme, since it's new usage of an existing token at a size (`labelSmall`) more sensitive to contrast failure. |
| No color-only signaling | 1 | New `LocalChangesPending` badge uses a distinct icon shape (cloud-upload-class glyph) AND distinct text label AND distinct tint from every other state — not relying on tint alone to distinguish it from, e.g., `ConflictPending`'s amber. |
| Minimum touch-target size (≥44×44pt / ≥48×48dp) | 1 (`LocalChangesPending`), 5 (`RateLimited`, same badge slot) | Net-new requirement (Surface 1 Acceptance Criterion 7) — both badge regions in this sidebar slot must meet the minimum, including `RateLimited`'s non-interactive region, so the slot's size stays consistent across states. Web's mobile-browser reachability makes this newly relevant here. |

---

## Summary of committed vs. recommended acceptance criteria

**Committed** (directly backed by a named Story in `plan.md`'s Scope):
- Surface 1 (all 7 criteria, including the touch-target criterion below) — Story 1.3.2, Task 4.3.2c, Epic 5.1.
- Surface 2 (all 5 criteria) — screen reused unmodified; criteria verify web's new trigger path behaves per existing contract; criterion 3's transition is now explicitly locked in by `plan.md` Story 3.3.2's added acceptance criterion + Task 4.3.2e's dedicated test (previously flagged here as unverified); criterion 4's error-routing remains an **implementation-time verification item**.
- Surface 5 (all 5 criteria, plus the touch-target criterion cross-referenced from Surface 1) — Story 1.3.1, Epic 3.4.
- Surface 6's `beforeunload` warning (all 4 criteria) — `plan.md` Story 5.1.3, per triad UX review's recommendation to commit now rather than defer.

**Recommended, not committed** (research-sourced, not named in requirements.md's Scope or plan.md's Stories):
- Surface 3's PAT scope-guidance + deep link.
- Surface 4's UI-visible auto-merge count (logging-only is committed; the visual disclosure is explicitly deferred per `plan.md`'s Unresolved Questions).

Recommend product/planning make an explicit call on the two remaining "recommended" items before Phase 7 close-out — each is cheap relative to the trust payoff research identifies. With Surface 6 now committed, "badge shows unsynced" plus "browser warns before you can lose track of it" together close the throughline goal of this entire project.
