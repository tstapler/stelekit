# Findings: UX — Capture-Sheet Auto-Enrichment

**Date**: 2026-08-10
**Feature**: Auto-Link + Tag-Suggest for Share-Sheet Capture
**Research method**: Codebase reading (`CaptureActivity.kt`, `ADR-004-suggestion-chip-tray-ux.md`) + web search + training knowledge

---

## Summary

The capture sheet (`CaptureActivity.kt`'s `CaptureScreen`, [`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:205-333`](../../../androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt)) is a bottom sheet over a translucent tap-to-dismiss dim layer, auto-focused on open, with a two-button (Dismiss/Save) footer. It has no scroll container and no space budget reserved for anything beyond a single `OutlinedTextField`. The five questions below converge on one design tension: **the sheet's entire reason for existing is sub-second capture-and-close, but ADR-004's chip tray was designed for a full-screen review stage where users are already committed to reviewing content.** The chip tray cannot be transplanted wholesale — it must be compressed and made skippable-by-default.

Key findings:
1. Every comparable app in this space (Apple Notes Quick Note, Obsidian quick-capture plugins, Readwise Reader) separates the **capture reflex** from the **linking/organizing reflex** — capture is a single action with zero required decisions, and organization happens later, asynchronously, in a different UI context. None of them puts a decision tray in the capture path itself.
2. The existing ADR-004 "accept before write" rule is directly reusable *only* for new-page creation (stub pages). Existing-page auto-linking already happens silently and immediately in the in-app Import flow's matched-page row (no confirmation) per ADR-004 — the same silent-auto-apply precedent should extend to the capture sheet's `[[wiki link]]` rewrite, per Should-Have in `requirements.md`.
3. WCAG 2.5.8 (Target Size Minimum, AA) sets a 24×24 CSS px floor for the chip dismiss `×` — the existing dim-layer/bottom-sheet pattern already establishes large touch targets elsewhere in this screen, so the chip tray should match, not use ADR-004's original (smaller, desktop-oriented) sizing verbatim. [TRAINING_ONLY — verify against ADR-004's actual chip dp sizing before implementation; ADR-004 itself does not specify chip dimensions in dp.]
4. All four failure modes (timeout, no provider, matcher failure, stub-creation failure) should degrade toward **exactly today's behavior** (raw text saved, no chips) rather than surfacing a distinct error state — this matches the requirements.md "no partial/stalled saves" success criterion and avoids adding a new error-state vocabulary to `SaveState` for a best-effort feature.
5. The JTBD split is stark: the functional job (link now vs. link later) and the emotional job (frictionless capture, "I didn't lose the thought") are in direct tension with the social/identity job (a knowledge graph that stays connected, not an ever-growing pile of orphaned blobs) — resolved by making enrichment *ambient* (happens without being asked) rather than *interactive* (requires a decision) wherever confidence allows it.

---

## 1. Comparable UX Patterns — Fast Capture + Optional Enrichment

| App / Flow | Capture mechanism | Enrichment mechanism | Timing relationship |
|---|---|---|---|
| **Apple Notes Quick Note** | System-wide gesture/hotkey, opens a note in <1s, zero required fields, autosaves to iCloud | None built-in; linking (`Notes` app "Add Link" and highlighted-term suggestions) happens only when the user later opens/edits the note | Fully decoupled — capture has no enrichment step at all |
| **Obsidian quick-capture plugins** (Quick Capture, Fleeting Notes, Quick Note Widget) | Widget/shortcut writes directly to inbox or daily note; explicitly designed to avoid vault/file decisions at capture time | Backlinking, tagging, and `[[wiki links]]` are added later, either manually or during a scheduled "inbox processing" review session | Decoupled by design — the community consensus (Obsidian forum "Quick Capture and Inbox Processing" workflow) is that linking mid-thought fractures attention; link during a separate reflection pass |
| **Roam Research daily notes** | Typing directly into today's page, no modal, no save step (autosave per keystroke) | `#tag` and `[[link]]` are typed inline by the user as part of writing, or added via block references later; Roam does not auto-suggest links at capture time | Enrichment is manual and synchronous with typing, but entirely user-initiated — never system-suggested mid-capture |
| **Readwise Reader / Instapaper save-for-later** | One-tap "Save" from share sheet; the save itself never blocks on categorization | Tagging happens either inline via typed shorthand tags (trained shorthand → full tag expansion) during *reading*, or via later bulk-organize passes; no auto-classification blocks the save action | Fully decoupled — save is instant, tagging is a separate, later, opt-in action during reading/review |

**Convergent pattern**: every comparable product treats "capture" and "enrich" as two different modes with two different attention budgets, not two steps of one flow. The save/capture action is never gated on, or visually cluttered by, enrichment UI. Where enrichment *does* appear at capture time (none of the surveyed apps do this for text; closest analog is Readwise's inline `.tag` shorthand), it is opt-in syntax the user types, not a system-generated decision tray.

This is the strongest constraint on this feature: **the chip tray, if shown in the capture sheet at all, must be visually and interactionally subordinate to Save** — present but skippable, never blocking, and ideally collapsed/hidden until suggestions actually exist (which per the requirements.md timeout budget may be never, for a given capture).

Sources:
- [Apple Notes vs Obsidian for Quick Idea Capture on iOS](https://lifetips.alibaba.com/tech-efficiency/apple-notes-vs-obsidian-for-quick-idea-capture-on-ios)
- [Quick Capture (mac/iOS) and Inbox Processing — Obsidian Forum](https://forum.obsidian.md/t/quick-capture-mac-ios-and-inbox-processing/21808)
- [Put quick notes into Obsidian from anywhere — Fleeting Notes](https://www.fleetingnotes.app/posts/put-quick-notes-into-obsidian-from-anywhere)
- [How to Tag Your Highlights While You Read with Inline Tagging in Readwise](https://blog.readwise.io/tag-your-highlights-while-you-read/)
- [Readwise Reader](https://readwise.io/read)

---

## 2. User Mental Models — "Linked Now" vs. "Reviewed Later"

**What does a user expect when sharing a page into a note app?** Per the comparable-pattern survey above, the dominant mental model across quick-capture tools is **"saved now, organized later."** The Obsidian community's own framing — "bidirectional linking belongs in reflection, not reaction... trying to link mid-thought fractures attention, so it's better to save linking for a scheduled synthesis window" — is a direct, named articulation of this. Users sharing into a capture sheet are in a "don't lose this" mindset, not a "curate my graph" mindset.

This creates real tension with the feature's premise (auto-link + tag-suggest *at* capture time, not later). Two mitigating facts from this codebase narrow the gap:

- **Existing-page auto-linking is not "organizing" from the user's perspective — it's free.** `ImportService.scan()`'s `AhoCorasickMatcher` only rewrites text into `[[wiki links]]` when the term already matches a page name in `PageNameIndex`. The user already decided that page mattered (they created it). Auto-applying that link at capture time doesn't ask the user to make a new decision — it silently completes one they already made, matching the same silent-auto-apply behavior the in-app Import review stage uses for matched-page chips (ADR-004: "a `LazyRow` of matched existing-page chips" shown as already-applied, contrasted with the new-page suggestion tray which requires explicit accept). **This satisfies requirements.md's own accept-before-write carve-out**: the constraint says "no silent auto-linking beyond existing-page-name matches which already auto-apply in in-app Import today" (Out of Scope section) — so the mental-model risk here is already adjudicated by the spec, not open.
- **New-page suggestion chips are the part that collides with the capture mental model**, because accepting one is a real decision (creates a stub page, permanent per ADR-004's own rationale for gating Accept All behind a confirmation dialog). This is where "accept before write" and "capture sheet speed" genuinely compete.

**Resolution implied by the research**: keep the sheet's default path exactly as fast as today (type → Save → dismiss, zero decisions) and treat the chip tray as an *optional, skippable affordance* that appears only if suggestions have already arrived by the time the user's attention would naturally land there (i.e., never gate focus or Save on it). This mirrors Readwise's "no auto-classification blocks the save action" and Apple Notes' "zero required fields" precedent, while still meeting the requirements.md Must-Have to surface chips when a local heuristic pass *does* complete in time.

A user who wants zero organizing overhead can always tap Save without ever looking at the tray — this is the same escape hatch ADR-004 already relies on for Import (dismissed/ignored chips don't block Confirm).

Sources:
- [Quick Capture (mac/iOS) and Inbox Processing — Obsidian Forum](https://forum.obsidian.md/t/quick-capture-mac-ios-and-inbox-processing/21808)
- `project_plans/import-topic-suggestions/decisions/ADR-004-suggestion-chip-tray-ux.md` (this repo)
- `project_plans/capture-auto-enrich/requirements.md` (this repo, Out of Scope section)

---

## 3. Accessibility — Chips in a Bottom Sheet Over a Dim Overlay

Current screen already has two accessibility-relevant patterns worth preserving, per [`CaptureActivity.kt:239-262`](../../../androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt):
- The dim layer is a full-screen `clickable` `Box` with `indication = null` — it is currently **not** marked as a semantic dismiss action distinct from the sheet content, which is already a pre-existing gap (not introduced by this feature) worth flagging but out of scope to fix here.
- The sheet `Surface` consumes clicks (`clickable(enabled = false, ...)`) purely to block propagation to the dim layer — it has no scrollable container, so anything added (the chip tray) either fits in the sheet's existing wrap-content height budget or forces the sheet to become taller/scrollable, which changes the dismiss-layer tap target geometry.

Considerations for the chip tray specifically:

- **Touch target size**: WCAG 2.2 SC 2.5.8 (Target Size Minimum, Level AA) requires interactive targets ≥24×24 CSS px, or ≥24px of surrounding spacing if smaller. Chip dismiss `×` icons are commonly implemented undersized (12–16dp icon with no padding) — the accessibility literature specifically calls out close/dismiss icons as a common violation and recommends erring toward the 44×44dp "best practice" size on touch surfaces, not the 24px floor. On a phone capture sheet (thumb-reachable, often one-handed while holding the share source app), err toward 44dp min touch target per chip, achieved via padding around a smaller visual glyph if needed — do not shrink the chip's visual footprint to fit more per row at the cost of the tap target.
- **Focus order**: The sheet currently auto-focuses the text field on open (`LaunchedEffect(Unit) { focusRequester.requestFocus() }`, line 228-230). If chips render asynchronously after the field is focused and the user is mid-keystroke, focus must **not** be moved off the text field when chips appear — this would interrupt typing (a keyboard/screen-reader user's cursor position must survive an async chip-tray render). Chips should be reachable via forward Tab/swipe navigation *after* the text field and *before* the Dismiss/Save buttons (matching visual top-to-bottom order), not injected ahead of the field.
- **Screen reader announcements for async-appearing content**: chips populate after a network/compute delay (local heuristic pass, or slower LLM tier). A screen-reader user focused elsewhere (e.g., on the Save button) gets no signal that new interactive content just appeared unless it's in an `assertive`/`polite` live region. Recommended: a `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the chip tray container so TalkBack announces "3 suggestions available" (or similar) without interrupting current focus/typing — polite (not assertive) because this is optional, non-urgent content and must not preempt an in-progress Save action announcement.
- **Chip semantics**: each chip needs a content description distinguishing accept vs. dismiss actions from each other and from plain text (e.g., "Create page: Kubernetes, double tap to accept" vs. a separate, clearly-labeled dismiss control) — TalkBack users cannot rely on the visual `[×]` glyph position to know two actions exist on one chip. ADR-004's existing chip anatomy (`[confidence dot] [term] [×]`) needs distinct semantic nodes/actions for term-tap vs. `×`-tap, or a `customActions` semantics API so a single swipe gesture can expose "Accept" and "Dismiss" as named actions.
- **Color-only confidence signal**: ADR-004's confidence dot is color-coded (green/yellow/orange) with no additional shape/text differentiator. This fails WCAG 1.4.1 (Use of Color) for colorblind users if color is the *only* signal. ADR-004's own chip anatomy doesn't specify a redundant cue — this is a pre-existing gap in the Import screen's tray (not this feature's to fix), but if this feature reuses the same composable, worth a two-line accessibility fix (e.g., differing dot fill pattern, or an accessible label that states the confidence tier in words) rather than propagating the gap to a second surface.

Source: [WCAG 2.5.8 Target Size (Minimum): Complete Implementation Guide](https://www.allaccessible.org/blog/wcag-258-target-size-minimum-implementation-guide), [All touch targets must be 24px large, or leave sufficient space — Deque](https://dequeuniversity.com/rules/axe/4.6/target-size)

---

## 4. Error / Edge-Case UX

Requirements.md's success criteria are explicit that enrichment must never produce a partial or stalled save — this dictates that every failure mode below resolves to **exactly today's save behavior**, distinguished only by whether/how the *absence* of chips is communicated (silently vs. explicitly).

| Case | Recommended UX | Rationale |
|---|---|---|
| **Enrichment times out** (local scan exceeds the <500ms/10KB budget, or LLM tier exceeds its own best-effort window) | Silent — raw text saves as today, no chip tray shown, no error snackbar. Do not show a "suggestions timed out" message. | A capture-sheet user did not ask for enrichment explicitly; a timeout on an invisible background feature is not user-actionable and would read as an alarming error for what is, from the user's POV, a successful save. This matches Readwise/Instapaper's pattern of never surfacing background-classification failures at save time. |
| **No LLM provider configured** | Silent no-op, confirmed correct per requirements.md ("Zero-API-key/no-provider-configured users still get the local heuristic tier unchanged") — local heuristic chips still appear if the local pass succeeded; no "AI unavailable" badge is *required* in the capture sheet (unlike ADR-004's Import screen, which has room and audience-expectation for a persistent status badge). Given the sheet's minimal chrome, recommend omitting the `"AI unavailable"` badge here even though ADR-004 shows it in Import — the capture sheet's smaller stakes and space budget don't justify the extra label a user didn't ask about. | Consistent with "no partial/stalled saves" and with comparable apps never showing a "no AI configured" notice on a plain save action. |
| **Matcher fails** (`AhoCorasickMatcher`/`ImportService.scan()` throws or the `PageNameIndex` isn't ready yet for the active graph) | Fall back to raw, unlinked text — same as timeout. No error surfaced. | Matching is optimization, not a contract with the user; the save must never depend on it succeeding. |
| **Chip's stub-page creation fails** (`GraphWriter.savePage` errors after the user already tapped Accept on a chip) | This is the one case that **should** surface an error, because the user took an explicit action (tapped Accept) and has a reasonable expectation of feedback — unlike the above three, which are invisible background processes the user never asked to see. Recommend a snackbar consistent with the existing `SaveState.Error` pattern ("Save failed — {message}") but scoped to the chip, e.g. "Couldn't create page '{term}' — try again", and revert the chip to its pre-accept (unaccepted) visual state rather than leaving it in an ambiguous "accepted but not actually created" limbo. Critically: **this failure must not roll back or block the block's own Save** — the two writes (block save, stub page save) are independent per `GraphWriter.savePage` mirroring `ImportViewModel.confirmImport()`, and the journal block should still save even if a single stub-page creation fails. | This is the one path with an explicit user action and an explicit promise (accepting a chip implies "yes, create this page") — silence here would produce silent data loss the user has no way to detect (they'd see `[[wiki link]]` text pointing at a page that was never created). |

One additional edge case worth flagging for the architecture-phase research (not fully a UX question, cross-referencing requirements.md's pitfalls list): **accept-then-dismiss race** — if the user taps Accept on a chip and then immediately taps Save/Dismiss before the stub-page write completes, the UX contract should be "the block save does not wait for the stub-page write" (per the above independence), but the user should not lose the `[[wiki link]]` text fold-in that was supposed to happen "if the accept happens before save" (requirements.md Must-Have). This needs an architecture-level answer (e.g., optimistic fold-in of the link text immediately on Accept-tap, independent of whether the stub-page write has completed yet) rather than a UX-level one; flagging here so it isn't lost between research docs.

---

## 5. Jobs-to-be-Done — Solo Knowledge Worker

Per requirements.md, the sole target user is a solo knowledge worker (no team/social features in scope). JTBD framing still separates functional, emotional, and social/identity jobs even for a single-user product — the "social" job here is the relationship between present-you and future-you, not other people.

**Functional job**: "When I encounter something worth keeping while I'm doing something else (browsing, reading an article, mid-conversation), let me capture it in one motion, and connect it to what I already know without making me stop and think about where it goes." This is two sub-jobs in tension:
- *Speed*: capture must complete in the time it takes to tap Save — any friction here and the job reverts to "don't bother capturing, it'll interrupt me too much" (the exact failure mode requirements.md's Problem Statement describes: "the fastest capture path is also the one that contributes least to the graph" — currently because contributing more costs speed).
- *Completeness*: the captured note should already be positioned in the graph (linked to related pages) so that future-you doesn't have to rediscover the connection from scratch during a later review pass, or worse, never revisits the raw text at all and the connection is lost permanently.

**Emotional job**: "I don't want to feel like I'm doing homework every time I save something." The existing capture sheet's zero-decision flow (type, tap Save, done) is already emotionally frictionless — this is the thing most at risk of regression from this feature. A secondary emotional job: "I want to trust that what I capture will actually become useful later" — an unlinked, untagged blob buried in a journal entry erodes trust in the capture habit itself over time (if past captures are never found again, the user stops bothering to capture). Auto-linking directly serves this second job without asking anything of the user in the moment.

**Social/identity job** (solo-user framing: self-relationship, not interpersonal): "I want to be the kind of person whose notes are actually connected, not a junk drawer." This is the job the in-app Import flow already serves deliberately (ADR-004's whole design — confidence-scored, reviewable, undoable suggestion tray) but the capture sheet's speed constraint means this feature can only serve it *partially*, and must not pretend otherwise: partial/best-effort auto-linking now, with the option to do a fuller review later (in-app Import or a future "review recent captures" surface) is the honest scope, and matches requirements.md's explicit Out-of-Scope boundary ("Auto-accepting suggestions without user review").

**Speed vs. completeness — the resolvable tension**: the JTBD analysis suggests these aren't actually in conflict if enrichment is split by confidence into two tiers already implicit in the requirements:
1. **High-confidence, low-risk actions (existing-page matches) → fully automatic, invisible, zero cost to speed.** This serves both functional (completeness) and emotional (frictionless) jobs simultaneously, with no social/identity risk because it's not creating new pages, just connecting to ones the user already decided mattered.
2. **Lower-confidence, higher-commitment actions (new-page suggestions) → visible but skippable, deferred to a moment that doesn't block Save.** This serves the social/identity job ("connected graph, not a junk drawer") for users who have a spare two seconds to glance at the tray, without imposing that cost on users who don't.

This two-tier split is already implicit in requirements.md's Must-Have/Should-Have distinction (auto-apply existing-page links vs. dismissible chips for new pages) — the JTBD analysis independently confirms this is the right shape, not an accidental byproduct of reusing existing machinery.

---

## Cross-Cutting Recommendation for Planning Phase

The capture sheet should treat the ADR-004 chip tray as a **pattern to adapt, not a component to embed verbatim**:
- Reuse: chip anatomy (confidence dot + term + dismiss), accept = create stub page via `GraphWriter.savePage`, dismiss = permanent per-session suppression, incremental non-destructive merge when a slower (LLM) tier's results arrive after the local tier's.
- Diverge: no "Accept All" confirmation dialog (out of place in a single-note capture context — there's no batch to confirm), no persistent AI-status badge (no space, no need), tray defaults to a single-row/compact horizontal scroll rather than ADR-004's 8-visible/"show more" pagination (the sheet has no scroll container budget for a tall list), and the tray must be capable of rendering **zero suggestions** (no chips shown at all) as a fully normal, silent state — unlike Import, where the user has explicitly opened a review screen and an empty tray needs no special handling either, but here the "nothing to review" state must not draw any attention (no "no suggestions found" text) since the user never asked to see this UI in the first place.
