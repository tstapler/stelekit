# UX Design: Capture-Sheet Auto-Enrichment

**Feature**: Auto-Link + Tag-Suggest for Share-Sheet Capture
**Date**: 2026-08-10
**Status**: Draft — for `sdd:4-validate` / implementation review
**Inputs**: `../requirements.md`, `../research/ux.md`,
`../../import-topic-suggestions/decisions/ADR-004-suggestion-chip-tray-ux.md`,
`../implementation/plan.md` (Epic 3.1/3.2), `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:205-333`

## Design principle (from research/ux.md, carried through every surface below)

The capture sheet's whole reason for existing is sub-second capture-and-close. Enrichment is
**ambient, not interactive**, wherever confidence allows it (existing-page links), and
**visible-but-skippable**, never blocking, where it isn't (new-page suggestions). Every surface
below is designed so that a user who never looks at it gets exactly today's save behavior.

---

## Surface inventory (9)

| # | Surface | New / modified | Section |
|---|---------|----------------|---------|
| 1 | Baseline capture sheet (idle, no enrichment yet) | Unmodified baseline | §1 |
| 2 | Silent auto-link (existing-page match) | New, invisible-by-design | §2 |
| 3 | Scanning / pending state | New, invisible-by-design | §3 |
| 4 | Suggestion chip tray (new-page candidates) | New | §4 |
| 5 | Chip accept interaction | New | §5 |
| 6 | Chip dismiss interaction | New | §6 |
| 7 | Timeout / fallback (silent degrade) | New, invisible-by-design | §7 |
| 8 | Post-save gate — unresolved suggestions ("Done" state) | New | §8 |
| 9 | Stub-page-creation failure | New — **not yet in implementation/plan.md**, designed here | §9 |

---

## §1 — Baseline capture sheet (reference, unmodified)

```
┌─────────────────────────────────────────┐
│              (dim overlay, tap=dismiss)   │
│                                            │
│  ┌──────────────────────────────────────┐│
│  │              ▬▬▬▬                     ││ ← drag handle
│  │  Today's Journal                      ││
│  │  ┌────────────────────────────────┐  ││
│  │  │ Capture a note…                 │  ││ ← OutlinedTextField,
│  │  │ |                                │  ││   autofocused on open
│  │  │                                  │  ││
│  │  └────────────────────────────────┘  ││
│  │                                        ││
│  │                    [Dismiss]  [Save]  ││
│  └──────────────────────────────────────┘│
└─────────────────────────────────────────┘
```

This is `CaptureScreen` as it exists today (`CaptureActivity.kt:239-331`). Every surface below is
an *addition* to this layout, not a replacement — the drag handle, label, text field, and
Dismiss/Save row never move or resize because of enrichment.

**Exit path**: tap Dismiss, tap the dim overlay, or system Back — all present today, all unchanged.

---

## §2 — Silent auto-link (existing-page match)

**Trigger**: `CaptureEnrichmentCoordinator`'s local scan (`ImportService.scan`) finds a term in
the typed text that already matches a page name in the graph (`matchedPageNames`).

**What the user sees**: nothing changes in the visible text field. The `[[wiki link]]` rewrite
lives only in `EnrichmentState.Ready.linkedText` — a value the text field never displays — and is
applied to the persisted block content at save time (`resolveForSave`, plan Task 2.1.2a). This is
intentional, not a rendering gap: requirements.md's Out-of-Scope section already rules that
existing-page matches "already auto-apply in in-app Import today" and should get the same
silent-apply treatment here, and research/ux.md's mental-model analysis confirms this doesn't ask
the user to make a new decision (they already decided the page mattered when they created it).

```
Before save:  "Meeting notes about Kubernetes"     ← what the field shows, unchanged
After save:   "Meeting notes about [[Kubernetes]]"  ← what lands in the journal (invisible until
                                                         the user later opens the journal page)
```

**Should-Have distinction (requirements.md)**: a *haptic-only* signal — a single short tick
(`HapticFeedbackType.TextHandleMove`-equivalent, one-shot) the instant `EnrichmentState`
transitions into `Ready` with a non-empty `matchedPageNames` — is the recommended way to satisfy
"visual/haptic distinction between matched and new" without adding any visible chrome to a sheet
that has no space budget for it. No badge, no snackbar, no color change. This is optional polish,
not gating — the sheet must work identically (silently) with the haptic omitted.

**Error/edge case**: matcher not ready / scan fails → falls through to §7 (identical to timeout).

**Exit path**: same as §1 — this surface has no additional UI, so it inherits §1's exits.

---

## §3 — Scanning / pending state

**Trigger**: text becomes non-blank; coordinator enters `EnrichmentState.Scanning` during the
300ms debounce + local-scan window.

**What the user sees**: **nothing** — no spinner, no "checking for links…" text, no skeleton chip
placeholders.

```
┌──────────────────────────────────────┐
│  Today's Journal                      │
│  ┌────────────────────────────────┐  │
│  │ Meeting notes about Kubernetes  │  │  ← identical to §1; Scanning is not rendered
│  │ |                                │  │
│  └────────────────────────────────┘  │
│                    [Dismiss]  [Save]  │
└──────────────────────────────────────┘
```

**Rationale**: `CaptureSuggestionTray` (plan Task 3.2.1a) only renders for `EnrichmentState.Ready`
with a non-empty undismissed `topicSuggestions` list — `Idle` and `Scanning` both draw zero pixels
by construction. This is deliberate per research/ux.md's Cross-Cutting Recommendation ("the tray
must be capable of rendering zero suggestions... as a fully normal, silent state... the 'nothing
to review' state must not draw any attention"). A visible loading indicator would imply the user
is waiting on something, which contradicts the "Save is never blocked" contract — showing a
spinner the user doesn't have to wait for is worse than showing nothing.

**Focus behavior**: the text field keeps focus and the cursor position throughout — nothing in
this state competes for `focusRequester` (`CaptureActivity.kt:228-230`).

**Exit path**: transitions automatically to either §4 (chips appear), §2 (silent auto-link,
nothing visibly changes), or §7 (silent timeout) — user has no action to take and no dead end is
possible, because there is no UI to be stuck in.

---

## §4 — Suggestion chip tray (new-page candidates)

**Trigger**: `EnrichmentState.Ready` with ≥1 undismissed `topicSuggestion`.

**Placement**: between the `OutlinedTextField` and the Dismiss/Save row (plan Task 3.2.1b) — top
to bottom reading order: label → text field → tray → action row. This ordering is load-bearing
for keyboard/TalkBack focus order (§10).

```
┌──────────────────────────────────────────┐
│  Today's Journal                          │
│  ┌──────────────────────────────────────┐│
│  │ Meeting notes about Kubernetes and    ││
│  │ Terraform for the new cluster.        ││
│  │ |                                      ││
│  └──────────────────────────────────────┘│
│                                            │
│  ┌────────────┐ ┌────────────┐  ▸ scroll ││ ← CaptureSuggestionTray:
│  │●  Terraform ⓧ│ │◐  Cluster  ⓧ│          ││   single-row LazyRow,
│  └────────────┘ └────────────┘            ││   horizontally scrollable,
│                                            ││   no header, no "Accept All",
│                    [Dismiss]      [Save]  ││   no AI-status badge
└──────────────────────────────────────────┘
```

**Chip anatomy (redesigned — see §10 for the accessibility rationale)**:

```
   ┌───────────────────────────────┐
   │  ●   Terraform          ⓧ     │
   └───────────────────────────────┘
     ▲     ▲                 ▲
     │     │                 └─ dismiss control, 44×44dp touch target
     │     │                    (visual glyph stays ~16dp, centered;
     │     │                     padding — not glyph size — provides
     │     │                     the target, so the chip's visual
     │     │                     footprint does not grow)
     │     └─ term text — tapping anywhere in the dot+term region
     │        accepts (44dp min height on the whole tappable region,
     │        not just the icon)
     └─ confidence indicator: shape + fill, not color alone
        (solid = high ≥0.7, half-fill = medium 0.4–0.69,
         hollow ring = low 0.2–0.39) — screen-reader label states
         the tier in words regardless of the visual glyph
```

**Confidence tiers** (unchanged thresholds from ADR-004, redesigned signal):

| Tier | Score | Visual (shape, not color-only) | Screen-reader label |
|------|-------|-------------------------------|---------------------|
| High | ≥0.7 | Solid filled dot | "high confidence" |
| Medium | 0.4–0.69 | Half-filled dot (ring + inner fill) | "medium confidence" |
| Low | 0.2–0.39 | Hollow ring dot | "low confidence" |
| (suppressed) | <0.2 | — | never reaches the UI (TopicExtractor threshold) |

Color may still be layered on top (e.g. primary/secondary/error tint) as reinforcement, but the
shape difference alone must be legible in grayscale — this satisfies WCAG 1.4.1 (Use of Color)
without adding a text label that would widen every chip.

**Divergences from ADR-004's Import-screen tray** (per research/ux.md's "diverge" list, already
reflected in plan Task 3.2.1a — listed here for completeness of this UX spec):
- No "Suggested new pages (N)" header, no "Accept All" button, no AI-status badge.
- No 8-visible/"Show N more" pagination — single horizontally-scrollable row, since the bottom
  sheet has no vertical scroll budget.
- Empty/all-dismissed tray renders nothing (§3's silent-state rule applies here too).

**Plan gap flagged for implementation**: plan Task 3.2.2c explicitly defers resizing the shared
`TopicSuggestionChip`'s touch targets ("its existing 20dp IconButton sizing is a pre-existing
gap... not something to regress-fix silently here"). This UX design requires the resize
(§10 — WCAG 2.5.8, 44×44dp) as part of Story 3.1.1's promotion to `ui/components/`, because the
promoted component is shared by both `ImportScreen` and `CaptureScreen` — fixing it once, at
promotion time, fixes both surfaces; deferring it ships the new Capture surface with the same
known accessibility violation research/ux.md identified before this feature even had a UI to
regress. Recommend converting this from "explicitly out of scope" to an in-scope task before
Story 3.1.1 is implemented.

**Exit path**: user can ignore the tray entirely and tap Save — the tray never blocks or gates
Save itself (only the *post-save* dismiss behavior is gated, see §8).

---

## §5 — Chip accept interaction

**Trigger**: tap anywhere in a chip's dot+term region (not the dismiss control).

**Flow**:
1. User taps the chip body.
2. **Immediately** (same frame, no network/DB round-trip — ADR-002 fold-before-save): the chip
   transitions to its accepted visual state (checkmark replaces the tappable dot+term region,
   muted/outlined background) and the `[[wiki link]]` is folded into the coordinator's in-memory
   `linkedText` — but, per §2, **the visible text field does not change**. The accepted state is
   only visible in the chip itself.
3. Chip remains in the tray (does not disappear) as a record of what will be created — matches
   ADR-004's "accepted chips remain visible" rationale (user needs to see the pending set without
   relying on memory).
4. Nothing is written to disk at this point (ADR-002) — no stub page exists yet, no `PageSaver`
   call happens. Accept-then-immediately-close-the-app or accept-then-Dismiss both leave the graph
   untouched (plan Story 2.2.2).

```
Before:  ┌────────────┐          After tap:  ┌────────────┐
         │●  Terraform ⓧ│                     │✓  Terraform │   (dismiss control removed —
         └────────────┘                       └────────────┘    nothing left to dismiss)
```

**When the write actually happens**: at Save (§8's underlying `performSave()` call), via
`createAcceptedStubPages` — one live DB read per accepted term (`getPageByName`) immediately
before the write, so two rapid captures suggesting the same new page never create duplicates
(plan Story 1.3.2). This is invisible to the user; from their perspective, tapping the chip
already "did the thing" — the deferred write is a correctness detail, not a UX-visible step.

**Content description**: `"Create page {term}, {confidence tier} confidence. Double tap to
accept."` — read on the whole tappable region, distinct from the dismiss control's description
(§10).

**Exit path**: accept is reversible only by never tapping Save (fold-before-save means nothing is
committed) — there is no "undo" affordance in v1 (per plan's Pattern Decisions table, explicitly
logged as an Unresolved Question, not silently dropped). If the user regrets accepting before
Save, Dismiss/closing the sheet without saving is the exit path, and it is a true no-op (§2.2.2).

---

## §6 — Chip dismiss interaction

**Trigger**: tap the dismiss control (ⓧ, right edge of the chip, 44×44dp touch target).

**Flow**:
1. Tap registers on the dismiss control specifically (distinct target from the accept region,
   with enough gap between them — minimum 8dp — that a mis-tap near the boundary doesn't trigger
   the wrong action).
2. Chip is removed from the tray immediately, no confirmation (low-stakes, reversible-by-doing-
   nothing action — dismissing a *suggestion* is not a destructive write, unlike Import's
   "Accept All" which does get a confirmation dialog because it creates pages).
3. Dismissal is permanent for that capture session — a later-arriving LLM-tier suggestion for the
   same term is not re-shown, even at higher confidence (mirrors ADR-004: "user dismissal is
   authoritative").

**Content description**: `"Dismiss suggestion: {term}"` — distinct from the accept region's label
so TalkBack users don't have to infer which glyph does which action from position alone.

**Exit path**: dismissing a chip has no failure mode (pure in-memory state mutation, no I/O) — it
cannot dead-end.

---

## §7 — Timeout / fallback (silent degrade)

**Trigger**: any of — local scan exceeds the 500ms/10KB budget, matcher never resolves before
Save is tapped, `ImportService.scan()` throws, `PageNameIndex` isn't ready for the active graph.

**What the user sees**: **exactly §1** — the raw typed text saves unchanged, the sheet closes
(or shows the normal "Saved" transition) exactly as it does today, no snackbar, no "suggestions
timed out" message, no chip tray (because none ever reached `Ready`).

```
┌──────────────────────────────────────┐
│  Today's Journal                      │
│  ┌────────────────────────────────┐  │
│  │ Quick note, no time to enrich   │  │   ← saves as typed, no link/chip changes —
│  │ |                                │  │      indistinguishable from a graph with
│  └────────────────────────────────┘  │      auto-link disabled entirely
│                    [Dismiss]  [Save]  │
└──────────────────────────────────────┘
        ↓ tap Save
   sheet closes, block persisted with raw text — same as pre-feature behavior
```

**Rationale (research/ux.md §4)**: this is a background, best-effort feature the user never
explicitly requested for *this* capture — a visible error on an invisible process reads as
alarming for what is, from the user's point of view, a successful save. Every failure mode in
this row resolves identically: raw save, `SaveState.Saved`, no distinct UX treatment.

**Exit path**: identical to §1's baseline — Save, Dismiss, dim-layer tap, or Back.

---

## §8 — Post-save gate: unresolved suggestions ("Done" state)

**Trigger**: `saveState` becomes `Saved` while ≥1 suggestion is neither `accepted` nor
`dismissed` (plan Story 3.2.2).

**Why this exists**: if the sheet auto-closed (`finish()`) the instant Save succeeds — today's
behavior — any suggestion the user hadn't yet looked at would vanish along with the sheet,
silently discarding the chance to accept it. Gating auto-close on "no unresolved suggestions"
keeps the sheet open just long enough for the user to make a final call, without ever having
blocked the *save* itself (the block is already persisted by this point).

```
┌──────────────────────────────────────────┐
│  Today's Journal                          │
│  ┌──────────────────────────────────────┐│
│  │ Meeting notes about Kubernetes and    ││
│  │ Terraform for the new cluster.        ││   ← Saved. Text field now read-only/
│  └──────────────────────────────────────┘│      dimmed (block is already persisted;
│                                            │      further edits here would not
│  ┌────────────┐ ┌────────────┐            │      re-save — avoid implying they would)
│  │✓  Terraform │ │●  Cluster  ⓧ│  ← still unresolved
│  └────────────┘ └────────────┘            │
│                              [Done]        │  ← Save button's role changes to
│                                            │     "acknowledge and close" (plan
└──────────────────────────────────────────┘     Task 3.2.2b)
```

**Flow**:
1. Save completes → block is persisted (with whatever was already accepted at that instant,
   §5's fold-before-save).
2. If unresolved suggestions remain, the Save button becomes a "Done" button; Dismiss is hidden
   or disabled (the save already happened — "Dismiss" would be a misleading label for a no-op).
3. User can still tap remaining chips (accept/dismiss) — but see the caveat below.
4. Tapping "Done" closes the sheet, calling `onSaved()`.

**Caveat requiring an explicit answer (flagged, not resolved by this design)**: accepting a chip
*after* Save has already completed cannot fold into `linkedText` for a save that already
happened — ADR-002's fold-before-save-only rule means there is no second write cycle. The
UX-safe interpretations are either (a) post-save chip taps in this state create the stub page
immediately (a narrow, explicit exception to fold-before-save, justified because the block save
that "at accept time" normally worries about has already completed) or (b) post-save chips
should be visually locked to acknowledge-only (no further accept/dismiss, chips shown for
awareness only, "Done" is the sole action). This design recommends **(a)**: accepting a
post-save chip calls `createAcceptedStubPages` directly (skipping the "fold into block content"
half, which is now moot — the block's already saved without that link) so the user's explicit tap
still results in a real page, and the block content itself simply won't retroactively contain the
`[[link]]` for that specific term (a known, acceptable v1 limitation — the stub page still exists
and is linkable from elsewhere). **This needs an explicit implementation-plan story before Epic
3.2 ships** — it is not currently covered by Story 2.1.2/2.2.1's "before Save" framing.

**Exit path**: "Done" is always present and always closes the sheet — there is no state in which
the user is stuck post-save with no way to leave.

---

## §9 — Stub-page-creation failure (new surface — not yet in implementation/plan.md)

**Trigger**: user tapped Accept on a chip (an explicit action with an explicit promise — "yes,
create this page"), and `createAcceptedStubPages`'s underlying `GraphWriter.savePage` call fails
during `performSave()` (disk full, permission error, concurrent write conflict, etc).

**Why this is the one error state that must be visible**: every other failure mode in this
feature (§7) is an invisible background process the user never asked to see — silence is correct
there. This is different: the user took an explicit action and has a reasonable expectation of
feedback. Silence here produces **silent data loss the user cannot detect** — they would see
`[[Terraform]]` rendered as a wiki-link-style token pointing at a page that was never actually
created, with no way to know it's broken until they click through to it later, out of context
from the moment they'd remember why.

**Critical constraint carried over from requirements.md's "no partial/stalled saves"**: this
failure must **not** roll back or block the journal block's own save. The block write and the
stub-page write are independent (mirrors `ImportViewModel.confirmImport()`) — the user's note is
never lost because a secondary, best-effort page creation failed.

```
┌──────────────────────────────────────────┐
│  Today's Journal                          │
│  ┌──────────────────────────────────────┐│
│  │ Meeting notes about Kubernetes and    ││   ← block still saved successfully;
│  │ Terraform for the new cluster.        ││      this text is not touched or rolled
│  └──────────────────────────────────────┘│      back by the stub-page failure
│                                            │
│  ┌────────────┐ ┌────────────┐            │
│  │●  Terraform ⓧ│ │✓  Cluster   │  ← reverted to pre-accept state (not "accepted"
│  └────────────┘ └────────────┘     limbo) so the user can see it needs another tap
│                                            │
│  ⚠ Couldn't create page "Terraform" —     │  ← snackbar, scoped to the failing term,
│    tap to try again                       │     auto-dismisses after ~4s but is not
│                              [Done]        │     the only way to retry (chip itself
└──────────────────────────────────────────┘     is re-tappable)
```

**Flow**:
1. Save proceeds; the journal block persists regardless of what happens next.
2. `createAcceptedStubPages` attempts the write for each accepted term; on a per-term failure,
   that term's chip **reverts to its pre-accept (unaccepted) visual state** — not left as
   "accepted but not actually created," which would be indistinguishable from success.
3. A snackbar surfaces, scoped to the specific term(s) that failed: `Couldn't create page
   "{term}" — tap to try again`. If multiple terms fail in the same save, one snackbar per term
   (queued, Material3 `SnackbarHost` default behavior) rather than a single vague "some pages
   failed" message — the user needs to know *which* link is broken.
4. Retry path: tapping the snackbar action, or simply re-tapping the reverted chip and hitting
   "Done"/Save again, re-attempts `createAcceptedStubPages` for that term only.
5. If the user taps "Done" without retrying, the sheet closes anyway — the failure is
   *recoverable later* (the term is still visible as plain text in the saved block; the user can
   manually create the page or re-trigger enrichment on a future capture that mentions the same
   term), not a blocking condition.

**Exit path**: "Done" always closes the sheet regardless of retry outcome — a failed stub-page
creation is never a dead end, only a degraded-but-recoverable outcome (plain text instead of a
linked stub page).

**Implementation note**: this surface requires `createAcceptedStubPages` to return a per-term
result (success/failure), not just the current `List<Page>` of created pages, so the coordinator
can drive the revert-and-snackbar behavior above. Flag for `implementation/plan.md` Story 2.1.2 or
a new story under Epic 2.2 before this ships — the current plan's `createAcceptedStubPages`
signature (Task 1.3.2a) returns only successes, with no failure channel back to the UI.

---

## §10 — Accessibility specification (applies across §4–§9)

| Requirement | Spec | Source |
|---|---|---|
| Touch target size | Accept region and dismiss control each ≥44×44dp (achieved via padding around a smaller visual glyph — chip's visual footprint does not grow); ≥8dp gap between the two targets within one chip | research/ux.md §3 (WCAG 2.5.8 floor is 24×24 CSS px; this design uses the touch-surface best-practice 44dp, not the bare floor) |
| Confidence signal | Shape (solid/half-fill/hollow), not color alone, per §4's table; color may reinforce but is never the sole differentiator | WCAG 1.4.1 (Use of Color); research/ux.md §3 |
| Color contrast | All chip text/icon-on-background pairs ≥4.5:1 (inherit `MaterialTheme.colorScheme` tokens already contrast-audited by the app theme — do not hardcode custom colors for chip states) | WCAG 1.4.3 |
| Focus order | Text field → tray chips (left to right) → Dismiss/Done → Save/Done, matching top-to-bottom visual order; chips appearing asynchronously never steal focus from an in-progress keystroke | research/ux.md §3; `CaptureActivity.kt:228-230`'s existing autofocus must survive async tray population |
| Async announcement | `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the tray container — TalkBack announces new suggestions without interrupting typing or an in-progress Save announcement | research/ux.md §3; plan Task 3.2.2c |
| Chip semantics | Accept and dismiss are two distinct semantic nodes/actions per chip, each with its own `contentDescription` (§5, §6) — never inferred from icon position alone | research/ux.md §3 |
| Keyboard navigation | Every interactive element (text field, each chip's accept/dismiss, Dismiss/Save/Done, retry snackbar action) reachable via Tab/D-pad in the focus order above; no element is mouse/touch-only | Standard a11y baseline for this codebase |

---

## UX Acceptance Criteria

Each is independently testable by a human on-device.

**Speed / non-blocking**
1. Typing and tapping Save with zero suggestion chips visible completes the save and closes the
   sheet in the same number of steps as today (2 actions: type, tap Save) — no added step is ever
   required to save.
2. From sheet-open to first frame with the keyboard focused, there is no perceptible delay
   attributable to enrichment (matcher construction happens off the render path — §3 renders
   nothing while scanning).
3. No capture ever waits on network/LLM latency: Save completes within the sheet's existing
   responsiveness budget regardless of whether an LLM provider is configured, slow, or offline.

**Chip tray interaction**
4. A user can accept a suggestion in 1 tap (chip body) and dismiss one in 1 tap (dismiss
   control), with no intermediate confirmation dialog for either single-item action.
5. Accepting a chip produces visible feedback (checkmark, muted background) within one frame —
   no spinner, no perceptible delay, because the action is a pure in-memory state change (§5).
6. Dismissing a chip removes it from the tray permanently for that capture — a later-arriving
   LLM-tier suggestion for the same term is never re-shown.
7. Zero suggestions produces zero visible UI — no "no suggestions" placeholder text, no empty
   tray outline (§3, §4).

**Error / edge-case handling — no dead ends**
8. Enrichment timeout, matcher failure, and no-provider-configured (§7) are indistinguishable
   from each other and from a normal save with no chips — no error text, no snackbar, no icon —
   verified by confirming the sheet's visual state is byte-for-byte identical to §1 in all three
   cases.
9. Stub-page-creation failure (§9) shows the message `Couldn't create page "{term}" — tap to try
   again`, reverts the affected chip to its unaccepted state, and does **not** roll back or delay
   the already-persisted journal block.
10. Every surface in this document (§1–§9) has at least one always-available exit action (Save,
    Dismiss, Done, dim-layer tap, or Back) that closes the sheet regardless of enrichment state —
    verified per-surface in the "Exit path" line of each section above.
11. A user who accepts a chip and then closes the sheet without tapping Save/Done leaves zero
    writes on disk (§5's fold-before-save guarantee) — confirmed via the coordinator-level test
    already specified in `implementation/plan.md` Story 2.2.2.

**Accessibility**
12. Every interactive control in the tray (chip accept region, chip dismiss control, Done/Save/
    Dismiss buttons, snackbar retry action) has a hit target ≥44×44dp, measured on-device with
    the Android accessibility scanner or equivalent.
13. Confidence tier is distinguishable in grayscale (screenshot desaturated, tiers still visually
    distinct by shape) and is announced in words by TalkBack on chip focus.
14. TalkBack, focused on the Save button while a background scan completes and populates the
    tray, receives a polite live-region announcement without its current focus or an in-progress
    announcement being interrupted.
15. Full keyboard/switch-access navigation reaches every control in this document in the focus
    order specified in §10, with no touch-only interactive element.

---

## Open items for the planning/validation phase

- **§8's post-save accept semantics** (accepting a chip after Save has already completed) is not
  covered by the current `implementation/plan.md` — needs an explicit story before Epic 3.2 ships
  (see §8's caveat).
- **§9's per-term failure channel** requires `createAcceptedStubPages`'s signature to expose
  failures, not just successes (see §9's implementation note) — not covered by plan Task 1.3.2a
  as written.
- **§4's touch-target fix** is currently scoped *out* by plan Task 3.2.2c ("not something to
  regress-fix silently here") — this design recommends moving it into Story 3.1.1 instead of
  leaving it deferred, since the promoted component is shared by both Import and Capture.
- **§2's haptic signal** for silent auto-link is optional polish (Should-Have, not Must-Have) —
  fine to cut from v1 if implementation time is tight; omitting it does not create a dead end or
  regress any acceptance criterion above.
