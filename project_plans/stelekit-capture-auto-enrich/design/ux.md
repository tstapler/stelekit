# UX Design: stelekit-capture-auto-enrich

**Date**: 2026-08-27
**Inputs**: `../requirements.md`, `../research/ux.md` (authoritative research — not re-derived here),
`../implementation/plan.md` (Phase 3/4 UI decisions), current
`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt` (`CaptureScreen` composable,
lines 230-357).

This doc turns `research/ux.md`'s recommendations into concrete wireframes/flows/acceptance
criteria for what `plan.md` is actually building, and flags every place the plan diverges from
(or under-specifies) the research. It does not re-litigate decisions research already made —
see the Cross-Check Findings section at the end for what changed or needs a decision.

Layout baseline for every wireframe below (unchanged from today, `CaptureActivity.kt:264-356`):
full-screen `Box` → 40%-alpha black scrim (tap-to-save-or-dismiss) → bottom-anchored `Surface`
(rounded top corners, `navigationBarsPadding()` + `imePadding()`) containing, top to bottom: drag
handle, "Today's Journal" label, `OutlinedTextField`, **[new content inserts here]**, Dismiss/Save
button row, with a `SnackbarHost` overlaid at the bottom of the full-screen `Box`.

---

## Surface 1 — Zero suggestions (unchanged baseline)

Text has no matcher hits and no topic candidates (or the scan hasn't resolved — `ScanState ==
NotReady`, per plan Task 3.1.2b). Per `research/ux.md` §3a, this renders **nothing extra** — no
spinner, no empty-state label.

```
┌─────────────────────────────────────┐
│ ░░░░░░░░░░░░ (scrim, tap = save) ░░░ │
│                                       │
│  ┌─────────────────────────────────┐ │
│  │            ▬▬▬                  │ │ drag handle
│  │ Today's Journal                 │ │
│  │ ┌─────────────────────────────┐ │ │
│  │ │ Quick note about lunch      │ │ │ OutlinedTextField
│  │ │                              │ │ │ (editable, focused)
│  │ └─────────────────────────────┘ │ │
│  │                    [Dismiss][Save]│ │ ← nothing between field and buttons
│  └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

**Flow**: user types/pastes → share intent or manual entry → scan runs debounced in the
background (300ms, Task 2.1.2b) → no visible change until/unless suggestions land. Save is
always enabled the instant text is non-blank (`saveState == Idle && captureText.isNotBlank()`,
unchanged condition).

**Edge case**: if the scan legitimately never resolves (budget exceeded, cold-start matcher not
built) the sheet stays in this exact state forever for this capture — that is correct, not a
bug (AC #4/#5).

---

## Surface 2 — Auto-link preview line showing (plan Epic 3.2, AC #1/#6)

At least one existing-page mention was matched. A **read-only** caption renders below the text
field showing the linked form — the editable field itself is never rewritten (Pattern Decisions
table, plan.md:48).

```
│  │ ┌─────────────────────────────┐ │ │
│  │ │ Reading about Kotlin        │ │ │ OutlinedTextField (still raw,
│  │ │ Multiplatform today         │ │ │  unmodified, still editable)
│  │ └─────────────────────────────┘ │ │
│  │ Reading about [[Kotlin          │ │ ← read-only caption, bodySmall,
│  │ Multiplatform]] today           │ │   onSurfaceVariant, maxLines=2
│  │                    [Dismiss][Save]│ │
```

**Flow**: `ScanState.Ready(text, result)` lands with `result.linkedText != freeText` → caption
fades in (per research §3a, ~150ms) below the field, pushing only the button row down, never the
field itself → user reads it as confirmation, or ignores it and taps Save → whichever text state
existed at tap time (`Ready` matching current text, or raw) is what's written (Story 2.3.1).

**Edge case — stale scan**: user keeps typing after the caption appears. The caption is driven
by `ScanState.Ready.text == _captureText.value`; once they diverge, per Task 2.1.2b's
`collectLatest`, a **new** scan supersedes the old one — until it resolves, the plan does not
specify hiding the now-stale caption. **Flag**: recommend the caption disappear (not linger) the
moment `scanState is Ready && result.text != captureText.value`, mirroring the chip-tray's own
staleness handling — otherwise the user reads a `[[link]]` preview computed against text they've
already edited away from, which is misleading (the same "actively misleading" concern
`research/ux.md` §4 raises for the rapid-re-share case, just for the preview line instead of the
chip tray). This is a one-line `if` condition, not a new mechanism.

---

## Surface 3 — 1–4 pending suggestion chips (plan Epic 3.1, AC #2/#6)

```
│  │ Reading about [[Kotlin          │ │  (preview line, if present)
│  │ Multiplatform]] today           │ │
│  │ ┌────┐┌──────────┐┌──────┐      │ │ ← LazyRow, capped at 4, sorted
│  │ │●Zet││●Note-taki││●KMP  │      │ │   by confidence, silent truncation
│  │ │kast││ng      × │└──────┘      │ │   beyond 4 (no "show more")
│  │ │en ×│└──────────┘              │ │
│  │ └────┘                          │ │
│  │                    [Dismiss][Save]│ │
```

Each chip: `[● confidence dot][term][×]`, structurally copied from `ImportScreen.kt:551-620`'s
`TopicSuggestionChip` at reduced padding (plan Task 3.1.1a). Confidence-dot color mapping
(unchanged from Import, `ImportScreen.kt:551-554`): ≥0.7 → `colorScheme.primary`, ≥0.4 →
`colorScheme.secondary`, else → `colorScheme.error`.

**Interaction flow, per chip**:
- **Tap the term/dot region → accept**: haptic `HapticFeedbackType.Confirm` fires synchronously
  in the click handler (Task 3.1.3a), *before* the async stub-page write starts. Chip disappears
  from the pending row once `accepted = true` (filter in Task 3.1.2a already excludes it — there
  is no "accepted, linked" retention chip in capture, unlike Import).
- **Tap × → dismiss**: light/no haptic (`SegmentTick` or omit). Chip disappears immediately,
  synchronously, no write (`dismissSuggestion()`, Task 4.1.2a).
- **Live region**: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the tray
  container — new suggestions are announced by TalkBack only at the next natural pause, never
  interrupting active typing (Task 3.1.2a).

**Edge case — rapid re-share (`onNewIntent`)**: per `research/ux.md` §4 and plan Task 2.1.2b's
`collectLatest`, a new share intent's text supersedes the in-flight scan; the *previous* tray
must not linger showing suggestions computed against text no longer on screen. Confirm this is
enforced structurally: `pendingSuggestions` is derived from `scanState` (Task 3.1.2a), and
`initializeText()` resets `captureText` — the moment `captureText` changes, `ScanState.Ready`'s
stored `text` no longer matches, so `Task 2.3.1`'s staleness check already applies to display,
not just to save. **This works only if the tray's `pendingSuggestions` computation is also
gated on `scanState.text == captureText.value`** — Task 3.1.2a's current code
(`(scanState as? ScanState.Ready)?.result?.topicSuggestions`) does **not** check `text` equality,
only `save()` does (Story 2.3.1). **Flag**: this is a real gap — the chip tray can display
suggestions from a stale scan for up to one debounce cycle (300ms) after new text arrives,
which is exactly the "actively misleading" case §4 warns against. Recommend gating
`pendingSuggestions` the same way `textToSave` is gated: `(scanState as? Ready)?.takeIf { it.text
== captureText }?.result?.topicSuggestions`.

---

## Surface 4 — Post-save "Done" state, zero pending chips (plan Epic 4.3, AC #9)

No new UI at all. `saveState` becomes `Saved`, `pendingSuggestions.isEmpty()` at that instant →
`onSaved()` fires immediately (Task 4.3.1a) → `finish()`, exactly as today. Zero added latency,
zero added taps (Success Metrics line, requirements.md:191).

```
(sheet closes — no intermediate frame is user-perceptible)
```

This is the overwhelmingly common path (any capture with no matcher/topic hits, or one where the
user already resolved every chip before tapping Save) and must not regress.

---

## Surface 5 — Post-save "Done" state, ≥1 pending chip (plan Epic 4.3, AC #9)

```
│  │ ┌─────────────────────────────┐ │ │
│  │ │ Reading about [[Kotlin      │ │ │ text field — RECOMMEND dimmed/
│  │ │ Multiplatform]] today       │ │ │ disabled here, see Flag below
│  │ └─────────────────────────────┘ │ │
│  │ ┌────┐┌──────┐                  │ │ ← chip row STILL LIVE/tappable
│  │ │●Zet││●Note- │                  │ │
│  │ │kast││taking×│                  │ │
│  │ │en ×│└──────┘                  │ │
│  │            ✓ Saved              │ │ ← button row REPLACED by this
│  └─────────────────────────────────┘ │
```

**Flow**: `saveState == Saved` + pending chips exist → sheet does **not** call `onSaved()`;
Dismiss/Save button row is replaced by a compact "✓ Saved" confirmation label; the chip row stays
fully interactive; a resettable **~2.75s** timer starts (`delay(2_750)`, Task 4.3.1b — matches
Android `Snackbar.LENGTH_LONG`, a convention users already have a mental model for).

- **Tap a chip (accept or dismiss)** → `resetKey++` restarts the timer to its full 2.75s (Task
  4.3.1b) — the user is actively engaging, the surface doesn't get yanked away.
- **Tap the scrim, or system back-press** → finishes **immediately**, regardless of pending
  chips — explicit dismissal always wins (Task 4.3.1c).
- **Timer expires with no interaction** → `onSaved()` fires → `finish()`. Any suggestions still
  pending at that point are discarded silently, matching the pre-save-dismiss precedent
  (`research/ux.md` §4, "no confirmation prompt").

**No-dead-end check (explicit, per task instructions)**: this state has **two** independent
exits — (1) the guaranteed unattended exit is the 2.75s auto-finish timer (Task 4.3.1b), and (2)
the immediate-exit path is scrim-tap/back-press (Task 4.3.1c), which bypasses the timer entirely.
Both are represented in the flow above. There is no state reachable from here with only one or
neither exit.

**Flag — text field state during "Done"**: the plan does not specify whether the
`OutlinedTextField` stays interactively editable during this window. Editing it does nothing (no
re-save is wired to post-save text changes — Epic 4.2's second write only ever touches
`ctx.block.content` via `insertWikiLinks`, never re-reads `captureText`), so a still-focused,
still-editable field that silently discards keystrokes is a confusing dead end of its own kind
(not a *sheet*-exit dead end, but an *input* one — the user's typing goes nowhere and no error
tells them so). **Recommend**: set the field to `enabled = false` (or `readOnly = true`) the
moment `isDone == true`, matching the already-established pattern of disabling the Dismiss button
during `Saving` (`CaptureActivity.kt:330`). This is a one-line addition to an existing composable
parameter, not a new mechanism.

---

## Surface 6 — Per-chip failure state (AC #7)

**Cross-check finding — the plan does not currently implement a visible failure signal.**
`research/ux.md` §4 is explicit: a failed accept "must not silently revert the chip to its
pre-tap pending state with no signal (that reads as 'did my tap even register?')" and recommends
either an inline chip error affordance or a snackbar naming the failed term. But plan Tasks
4.1.1a and 4.2.1a both do exactly the thing the research warned against:

```kotlin
writer.savePage(stubPage, emptyList(), graphPath).onLeft {
    logger.error("Stub page save failed for '$term': $it")
    return@launch   // ← chip silently stays pending, no UI signal fires
}
```

AC #7's literal bar ("surfaced — at minimum logged") is met by the `logger.error` call. The UX
bar the research set is not. Recommended design, chosen for lowest implementation cost against
the "no new abstractions" constraint: **reuse the existing `SnackbarHostState`** already
instantiated in `CaptureScreen` (`CaptureActivity.kt:238`, already wired for save failures at
lines 244-248) rather than adding new chip-level visual state (border/tint) to
`TopicSuggestion`.

```
│  │ ┌────┐┌──────┐                  │ │
│  │ │●Zet││●Note- │                  │ │ ← chip stays exactly as it was
│  │ │kast││taking×│                  │ │   before the tap: pending, tappable
│  │ │en ×│└──────┘                  │ │   again (re-tap = retry, for free)
│  │                    [Dismiss][Save]│ │
├─────────────────────────────────────┤
│ Couldn't create page for "Zettelkasten" │ ← transient snackbar, same
└─────────────────────────────────────┘    channel as "Save failed — …"
```

**Interaction flow**:
1. User taps chip → haptic `Confirm` fires immediately (per plan, unconditionally — Task 3.1.3a
   explicitly says the haptic fires "not after the async write resolves", so the confirm haptic
   plays even on a tap that will end in failure — this is a deliberate research call, §3e: "a
   failed write... should surface via the existing snackbar/error channel, not by 'un-confirming'
   the haptic after the fact").
2. `writer.savePage(...)` returns `Either.Left` → `acceptSuggestion`'s `onLeft` branch fires.
3. **Recommend adding**: alongside the existing `logger.error(...)`, emit the failure through a
   new `CaptureViewModel` `_transientError: MutableSharedFlow<String>` (or equivalent one-shot
   event channel — a `StateFlow` would re-fire on recomposition, so a `SharedFlow`/`Channel` is
   the correct shape here), collected in `CaptureScreen` via `LaunchedEffect` to call
   `snackbarHostState.showSnackbar("Couldn't create page for \"$term\"")`.
4. Chip is left exactly as it was pre-tap: not `accepted`, not `dismissed`, still rendered,
   still tappable — a second tap is a free retry with no additional wiring needed.

**Isolation (AC #7, unaffected by the above)**: this failure must never abort the block write or
the Bug-8 markdown flush — plan Tasks 4.1.1a/4.2.1a already structure this correctly (`return` /
`return@launch` only exits the one suggestion's coroutine branch, nothing else). The snackbar
recommendation above is additive UI polish, not a change to that isolation guarantee.

**Post-save failure (AC #9's write, Epic 4.2)** follows the identical pattern — the same
snackbar channel, message scoped to naming the term, e.g. `"Couldn't link \"$term\" — the graph
changed"` for the `ClosedSendChannelException` branch (Task 4.2.1b, whose existing log message
already names a distinct string per PF-5's requirement — only the user-facing surfacing is the
gap, not the message content itself).

---

## Surface 7 — Screen-reader / accessibility-focus interaction (AC #9, research §3d)

Applies across Surfaces 3 and 5 (chip tray and post-save "Done" window).

**Chip tray, TalkBack reading order**: tray container has `liveRegion = Polite` (Task 3.1.2a);
each chip's `contentDescription` is exactly:

```
"Suggested page, <term>, confidence <high|medium|low>. Double-tap to accept."
```

(Task 3.1.1a, thresholds 0.7/0.4 matching `ImportScreen.kt:551-554` — spelled-out word, never a
numeric score or color-only signal, per research §3d.)

**Flag — dismiss is not reachable from this description.** The `contentDescription` above is
applied to the whole chip (per Task 3.1.1a's `Modifier.semantics { contentDescription = ... }`
on the composable root), and its only stated affordance is "Double-tap to accept." Visually,
sighted users have *two* separate tap targets (term-body-accept vs. `×`-dismiss); a screen-reader
user who only hears "double-tap to accept" has no announced way to invoke dismiss at all — there
is no `customActions` entry in the plan's semantics block. This is a genuine parity gap, not a
style nit: it means a TalkBack user can accept a chip but cannot dismiss one through the same
mechanism sighted users use casually and frequently (research §3e calls chip-dismiss "low-stakes,
frequently-repeated"). **Recommend**: add a second semantics action —
`Modifier.semantics { contentDescription = "..."; customActions = listOf(CustomAccessibilityAction("Dismiss suggestion") { onDismiss(); true }) }`
— exposed to TalkBack via its actions menu (double-tap-and-hold / explore-by-touch rotor). This
does not change the double-tap default action (still accept, preserving today's Import-anatomy
precedent) — it only restores the missing second affordance.

**Touch targets**: plan Task 3.1.1a applies `Modifier.minimumInteractiveComponentSize()` "on the
dismiss `IconButton`". **Flag**: `ImportScreen.kt:607-610`'s accept `IconButton` (the checkmark,
`Modifier.size(20.dp)`, well under Material3's 48×48dp minimum) is the composable being
structurally copied — the plan's wording only calls out the dismiss target, not the accept
target, for the touch-target fix. Both are independent `IconButton`s in the copied anatomy and
both need `minimumInteractiveComponentSize()` (or equivalent explicit padding) — recommend making
this explicit rather than assuming "the dismiss IconButton" phrase covers both.

**Post-save "Done" window, focus-pause**: `hasAccessibilityFocus` (tracked via
`Modifier.onFocusEvent`, Task 4.3.1c) gates the second `LaunchedEffect` in Task 4.3.1b — while
true, the `delay(2_750)` effect does not run at all (the effect key includes
`hasAccessibilityFocus`, so a `true→false` transition restarts the delay from its full duration,
not from wherever it left off — functionally equivalent to "paused," since no partial elapsed
time is preserved across the transition either way). Scrim-tap and back-press remain immediate
exits regardless of focus state (Task 4.3.1c) — a TalkBack user is never trapped.

**Reduced motion**: the ~150ms fade/expand for the tray and preview line (research §3a) should
degrade to instant appear/disappear under the system "remove animations" setting — not called
out as a task in `plan.md`. **Flag** (minor, likely covered by Compose's platform-level animation
scaling rather than needing bespoke code — verify at implementation time rather than assuming a
gap).

---

## UX Acceptance Criteria

### Task completion / responsiveness

1. **Zero-friction save**: with zero suggestions, a user can capture and save a note in the
   existing number of taps (1 tap on Save, or 0 additional taps beyond typing) — no regression
   from today's flow (Success Metrics, requirements.md:189-192; Surface 1/4).
2. **Save is never gated**: at any scan/enrichment state (`NotReady`, in-flight, `Ready`,
   enrichment pending), tapping Save produces a save within one frame — no spinner-wait, no
   disabled state tied to scan progress (AC #4/#5; Surface 1).
3. **Chip accept is 1 tap**: accepting a suggestion (pre- or post-save) requires exactly one tap
   on the chip's accept region — no confirmation dialog (requirements.md's explicit rejection of
   Accept-All; Surface 3/5).
4. **Chip dismiss is 1 tap**: dismissing a suggestion requires exactly one tap on `×` — no
   confirmation, no write (Surface 3).
5. **No visible latency before Save is tappable**: no loading indicator, skeleton, or disabled
   state ever gates the Save button itself, regardless of scan/enrichment progress (research §5,
   ranked #1 failure mode; Surface 1).

### Visual/interaction states

6. **Scanning state is invisible under ~200ms**; if a scan is still running past that threshold,
   nothing appears except (optionally, if implemented) a thin indeterminate line under the text
   field — never a spinner (research §3a; Surface 1).
7. **No suggestions found renders nothing** — no empty-state label, no collapsed tray (Surface
   1).
8. **Suggestion tray never displaces the text field** — only the button row shifts down when the
   tray/preview appear; the text field's position and focus are never disturbed mid-type
   (research §5, ranked #2 failure mode; Surface 2/3).
9. **Chip cap is 3–4 visible, no disclosure control** — truncation beyond the cap is silent, by
   descending confidence; no "Show more" affordance anywhere in the capture sheet (Surface 3).
10. **Auto-applied links are communicated inline via `[[bracket]]` syntax** in a read-only
    preview line, never by rewriting the live editable field's content (Surface 2).
11. **Auto-applied links carry no chip/tray affordance** — they are not tappable, dismissible, or
    otherwise interactive; only new-page suggestions render as chips (AC #6; Surface 2 vs. 3).

### Post-save "Done" window (AC #9)

12. **Zero pending chips → immediate finish**, byte-for-byte the same behavior as today, no added
    frame, no added tap (Surface 4).
13. **≥1 pending chip → "✓ Saved" state, ~2.75s auto-finish**, resettable on any chip
    interaction, resetting to full duration each time (Surface 5).
14. **No dead ends — every state has an exit path.** Explicitly verified for the highest-risk
    state (post-save "Done" with pending chips, Surface 5): it has both (a) a guaranteed
    unattended exit — the 2.75s auto-finish timer — and (b) an immediate voluntary exit — scrim
    tap or system back-press, which bypasses the timer unconditionally. Both are required; a
    design with only one does not satisfy this criterion.
15. **The "Done" window's text field does not silently discard input** — either it is disabled
    (`enabled = false`)/read-only during this window (recommended, Surface 5's Flag), or, if left
    editable, edits made during the window are verified (at implementation time) to have some
    observable effect — an editable-but-inert field that looks live is not acceptable.

### Failure / error states (AC #7)

16. **A failed chip accept is surfaced to the user**, not only to logs — minimum bar: a transient
    snackbar naming the specific term that failed (e.g. `Couldn't create page for "<term>"`),
    using the existing `SnackbarHostState` channel already wired for save failures (Surface 6).
17. **A failed chip remains tappable after failure** — it returns to exactly its pre-tap pending
    state (not `accepted`, not silently removed), so a second tap is a working retry with no
    additional UI needed (Surface 6).
18. **A chip failure never blocks or delays** the block write, the markdown flush, or any other
    pending suggestion's accept/dismiss (AC #7, structural — verified in plan Tasks 4.1.1a/
    4.2.1a's isolated `return`/`return@launch` scoping; Surface 6).
19. **The confirm haptic is not "walked back" on failure** — it already fired at tap time as
    confirmation of the user's *intent*, independent of whether the write later succeeds
    (research §3e; Surface 6).

### Accessibility

20. **Touch targets ≥48×48dp** on every independently-tappable chip element — both the accept
    region and the dismiss `×` — even though each one's visible glyph is smaller (8–20dp). Verify
    at implementation time that `minimumInteractiveComponentSize()` (or equivalent explicit
    padding) is applied to **both** `IconButton`s copied from `ImportScreen.kt:596-617`, not only
    the dismiss one (Surface 7's Flag).
21. **Chip `contentDescription` is exactly**: `"Suggested page, <term>, confidence
    <high|medium|low>. Double-tap to accept."` — confidence spelled out as a word at the 0.7/0.4
    thresholds (matching `ImportScreen.kt:551-554`), never a numeric score, never color-only
    (Surface 7).
22. **A screen-reader-reachable dismiss action exists** for every chip — via a `customActions`
    semantics entry (e.g. "Dismiss suggestion") surfaced in TalkBack's actions menu, not only via
    the visual `×` `IconButton` — closing the parity gap flagged in Surface 7.
23. **New suggestions use a polite (non-interrupting) live region** — `LiveRegionMode.Polite`,
    never `Assertive`, so TalkBack never interrupts active typing to announce a new chip (Surface
    3).
24. **The post-save auto-finish timer is paused, not merely extended, while accessibility focus
    is anywhere inside the sheet** — verified via the `hasAccessibilityFocus`-gated
    `LaunchedEffect` never starting its `delay(2_750)` while focus is present (Task 4.3.1c;
    Surface 7).
25. **Color contrast for the confidence dot**: WCAG SC 1.4.11 (Non-text Contrast, **≥3:1**) is
    the applicable criterion for this element — it is a small graphical status indicator, not
    text, so the 4.5:1 text-contrast threshold (SC 1.4.3) over-specifies it. **Verification
    status**: `LightColorScheme`/`DarkColorScheme`/`StoneColorScheme` in
    `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt` explicitly define
    `primary` for all three modes (`DeepPatina` 0xFF458588 light, `PatinaAccent` 0xFF83A598
    dark/stone) — the high-confidence (≥0.7) dot's contrast is checkable directly from source.
    **`secondary` and `error` are not overridden in `DarkColorScheme` or `StoneColorScheme`**
    (only `LightColorScheme` sets a custom `secondary` — `0xFF535F70`), so the medium-confidence
    (`secondary`) and low-confidence (`error`) dot colors in dark/stone mode fall back to Compose
    Material3's baseline default tonal palette — not sourced from this repo. **This must be
    verified with an actual contrast-checker run against the rendered app in all three theme
    modes at implementation/QA time** — it cannot be confirmed from source alone, and there is a
    real risk the unthemed default `error`/`secondary` values clash visually with the custom
    warm-stone palette even if they pass the numeric contrast bar.
26. **Reduced-motion setting is respected** for the tray/preview fade-in and any auto-link
    signal — degrades to instant appear/disappear rather than being skipped outright (Surface 7).

---

## Cross-Check Findings Summary (plan vs. research vs. this doc)

| # | Finding | Severity | Where |
|---|---|---|---|
| 1 | Chip tray's `pendingSuggestions` is not gated on `scanState.text == captureText.value`, unlike `save()`'s staleness check — can show suggestions computed against superseded text for one debounce cycle | Real gap | Surface 3 |
| 2 | No visible failure signal for a failed chip accept (`onLeft { logger.error(...) }` only) — contradicts `research/ux.md` §4's explicit "must not silently revert with no signal" | Real gap (AC #7's literal text is satisfied by logging; the UX bar research set is not) | Surface 6 |
| 3 | No screen-reader-reachable dismiss action — chip semantics only expose "Double-tap to accept" | Real accessibility parity gap | Surface 7 |
| 4 | Touch-target fix (`minimumInteractiveComponentSize()`) named only for the dismiss `IconButton` in plan Task 3.1.1a; the accept `IconButton` (copied from `ImportScreen.kt:607-610`, `size(20.dp)`) needs the identical fix | Real gap if literally read; likely just under-specified wording | Surface 7 |
| 5 | Post-save "Done" window leaves the text field's enabled/editable state unspecified — an editable-but-inert field is a soft dead end for input, distinct from the sheet-exit dead end AC #9 already covers | Recommendation, not yet a decision in plan.md | Surface 5 |
| 6 | Preview-line (Epic 3.2) staleness handling not specified — could show a `[[link]]` preview for text the user has already edited past | Minor gap, same shape as Finding 1 | Surface 2 |
| 7 | AC's suggested "4.5:1" contrast threshold is the text-contrast SC (1.4.3); the confidence dot is a non-text graphical indicator, so SC 1.4.11 (3:1) is the correct bar | Correction, not a blocker | AC #25 |
| 8 | `secondary`/`error` colors (medium/low confidence dots) are unthemed M3 defaults in dark/stone mode — not sourced from this repo's `Theme.kt`, contrast unverifiable from source | Verification requirement, not a code gap | AC #25 |

None of findings 1–6 require new abstractions — each is a one-condition gate, a semantics
addition, or a `Modifier` parameter, consistent with the plan's own "no new abstractions beyond
what the task requires" constraint.
