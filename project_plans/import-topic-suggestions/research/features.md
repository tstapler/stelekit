# Findings: Features — PKM Topic Suggestion UX

## Summary

No major PKM tool (Logseq, Obsidian, Roam, Tana, Capacities, Mem, Notion AI) offers a fully polished "suggest new pages on import/paste" flow as a first-class native feature as of early 2026. The closest analogues are: (1) Tana's "supertag" auto-detection that prompts users to type-tag nodes during capture, (2) Mem's AI-driven concept extraction that silently creates topics without user review, and (3) Capacities's "object type" suggestion when pasting content. The field has converged on two distinct UX philosophies: *silent AI enrichment* (Mem, Notion AI) and *explicit per-item review* (Tana, Capacities). The explicit review pattern is better suited to SteleKit's local-first, user-in-control model. The optimal UX for SteleKit is a **chip tray** — a horizontal scrollable row of suggestion chips below the import preview, each with a one-click accept and a dismiss "×" — with a "Accept All" affordance at the tray header.

---

## Options Surveyed

### Logseq

Logseq does not have an import-time topic suggestion feature. The import workflow (via clipboard paste or URL import via the `logseq-plugin-url-content` ecosystem) creates a new page with raw text; no extraction occurs. The "unlinked references" panel surfaces matches for *existing* page names only — it does not propose new pages. The closest approximation is the "Slash command → Ask AI" plugin which can invoke an LLM against the active block, but this is manual and disconnected from import.

**Signal/noise distinction**: None — no suggestion system exists.

**Accept/dismiss UX**: Not applicable.

### Obsidian

Obsidian has no native import-time topic suggestion. The community plugin `Smart Connections` uses embeddings to suggest *existing* notes that are similar to the current note being edited, but it does not propose new pages. The `Omnivore` and `Readwise` importer plugins bring articles in as new notes but apply only pre-configured tag templates — no per-import topic extraction. The `Copilot` plugin (ChatGPT/Claude-backed) can be invoked manually via command palette to ask it to extract topics, but results are pasted as text, not surfaced as actionable page-creation prompts.

`Note Refactor` plugin allows splitting a block into a new page, which addresses a different but adjacent use-case (note decomposition, not topic suggestion).

**Signal/noise distinction**: None natively. The `Smart Connections` plugin uses cosine similarity on embeddings, which implicitly filters noise.

**Accept/dismiss UX**: Not applicable natively.

### Roam Research

Roam Research has no dedicated import-time topic suggestion. The `SmartBlocks` plugin system allows writing a template that fires on paste and calls the Roam API, but this requires per-user configuration and scripting. The `Roam Portal` community tool can extract named entities from pasted blocks using a configured NLP API (spaCy, OpenAI) and insert `[[...]]` wikilinks — but this is an advanced power-user workflow, not a general UX pattern. [TRAINING_ONLY — verify current SmartBlocks capabilities]

**Signal/noise distinction**: Delegated entirely to the external NLP service chosen by the user.

**Accept/dismiss UX**: The Roam Portal workflow batch-links all detected entities without per-item review — the entirety of accept/dismiss is "run the macro or not."

### Tana

Tana is the strongest example in the space for structured concept suggestion during capture. Key observations:

- Tana's data model is node-based, not free-text Markdown. Every node has a "type" (supertag). During capture (paste or quick-entry via Tana Capture), Tana's AI layer (GPT-4-backed as of 2024 [TRAINING_ONLY — verify current model]) can auto-detect which supertag fits a pasted block and *prompt* the user to apply it.
- The suggestion appears as an inline chip or tooltip beside the node, not as a separate review panel. The user taps the chip to accept; tapping elsewhere dismisses it.
- For multi-field structured types (e.g. a "Person" supertag with name/email/company fields), Tana can pre-populate the fields from pasted text and ask the user to confirm the whole structure.
- Supertag suggestions are AI-generated and can misfire on ambiguous text; Tana addresses this with low visual weight (small chips, not modal interruption) so false positives can be dismissed with minimal friction.

**Signal/noise distinction**: Relies entirely on LLM classification. Tana does not have a local heuristic fallback — users without AI features do not get suggestions. [TRAINING_ONLY — verify]

**Accept/dismiss UX**: Per-item inline chip. No bulk accept. The design principle seems to be "these are cheap to dismiss so we don't need bulk."

### Capacities

Capacities uses an "object type" system (similar to Tana supertags but less flexible). When a user pastes a URL or text block, Capacities detects the content type (article, tweet, person, etc.) and prompts: "Create as [Article]?" with accept/change-type/dismiss options. This is a coarse-grained type suggestion, not fine-grained concept extraction.

For concept extraction specifically: Capacities has an AI "Space" feature that can generate a set of "concepts" linked to a note. As of 2024, this is invoked manually per-note, not triggered automatically on import. The result is a tag cloud shown in a sidebar panel, where each concept tag can be accepted (creating a linked object) or dismissed. [TRAINING_ONLY — verify current Capacities AI capabilities]

**Signal/noise distinction**: The AI model performs the filtering. Users report that concept suggestions from the AI Space feature are generally high-quality for factual/technical text but noisy for personal journals.

**Accept/dismiss UX**: Chip-based tag cloud in sidebar. Per-item accept or dismiss. No bulk accept observed. [TRAINING_ONLY — verify]

### Mem

Mem uses fully automatic AI enrichment. When text is captured (pasted or synced), Mem's AI (previously its proprietary model, later GPT-4 integration) automatically identifies entities, topics, and people names and creates or links to corresponding "Mems" (notes) without any user review step. The result appears as linked tags in the note footer.

**Signal/noise distinction**: Entirely AI-driven, but with no user correction step at import time. Users can retroactively unlink false positives, but there is no pre-accept review.

**Accept/dismiss UX**: None — Mem is fully silent enrichment. The approach trades false positives for zero friction. User research and community feedback (Mem subreddit, ~2023) indicates this causes frustration when Mem creates hundreds of spurious topic stubs from a large article import. [TRAINING_ONLY — verify current Mem behavior post-pivot to Mem Teams]

### Notion AI

Notion AI does not have automatic topic suggestion on paste. The AI "Autofill" feature in databases can populate a "Tags" property for a new page using AI, but it requires: (a) the content already be in a database page, and (b) the user to have defined candidate tags in advance. It fills from a predefined vocabulary — it does not propose net-new concepts.

Notion AI's `/AI` block command can be asked "extract the key concepts from this page" and will output a bullet list, but there is no affordance to turn those bullets into new Notion pages with one click. [TRAINING_ONLY — verify current Notion AI autofill behavior]

**Signal/noise distinction**: Filters against a predefined tag list (closed vocabulary). No open-ended new-concept suggestion.

**Accept/dismiss UX**: Not applicable for new-concept suggestion.

---

## Trade-off Matrix

| Tool/Pattern | Suggestion quality | UX friction | False-positive handling | Discoverability | Notes |
|---|---|---|---|---|---|
| Logseq | N/A (no feature) | N/A | N/A | N/A | Unlinked refs = existing pages only |
| Obsidian | N/A (no feature) | N/A | N/A | N/A | Community plugins only, manual invoke |
| Roam (SmartBlocks) | Depends on external NLP | High — requires setup | None — batch links everything | Poor — power users only | Not a replicable pattern |
| Tana (AI supertags) | High (LLM) | Low — inline chip dismiss | Good — per-chip dismiss, low visual weight | High — inline with the node | No local fallback; entire feature requires LLM |
| Capacities (AI Space) | Medium-high (LLM) | Low-medium — sidebar chip cloud | Medium — per-chip dismiss but sidebar is separate | Medium — sidebar panel, not inline | Manual invoke per note, not triggered on import |
| Mem (silent AI) | High for proper nouns; lower for abstract concepts | Near-zero | Poor — retroactive unlink only | Low — tags appear without asking | Causes stub proliferation; no review step |
| Notion AI (autofill) | Medium — closed vocabulary | Low (database autofill) | Good — constrained by predefined tags | Low — database-specific feature only | Cannot propose net-new concepts |
| **SteleKit target** | Medium (heuristic) → High (LLM opt-in) | Low — chip tray, one-click per item | Good — per-chip dismiss + bulk accept | High — shown inline at review stage | Hybrid: heuristic always available, LLM opt-in |

---

## Risk and Failure Modes

### Suggestion overload (5–15 candidates is manageable; >20 degrades UX)

The highest risk in this design space is **list fatigue**: if the extractor surfaces 30+ candidates (plausible for a 10 KB technical article), users will cognitively disengage and hit "Accept All" indiscriminately or dismiss the entire panel. Mem's silent model demonstrates this failure mode at scale.

Mitigation: cap the displayed suggestions at 15, ranked by confidence. Provide a "Show more" affordance for the full list. Sort by frequency × confidence descending.

### False positives pollute the graph

Accepting a false-positive suggestion creates an empty stub page that will clutter the graph and show up in search and autocomplete forever. Unlike a tag or a label, a stub page in a wiki-link graph is persistent and harder to clean up than dismissing a chip.

Mitigation: confidence scoring with a minimum display threshold. Any candidate below a configurable cutoff (e.g. <0.35 normalized score) should not appear in the suggestion tray at all — not as a dismissed item, simply absent. Local heuristics are conservative by default; LLM mode can surface more candidates with semantic justification shown in a tooltip.

### Proper noun vs. common noun confusion

Heuristic extractors using capitalization as a signal will misfire on sentence-initial words ("The framework was..."), quoted speech, and German-style common noun capitalization in some domains. This is the primary source of noise for local-only extraction.

Mitigation: apply a common-word blocklist (top-1000 English words, domain-specific stop words for technical text). Require a minimum frequency of 2 for single-word candidates (appears at least twice in the imported text). Downrank sentence-initial occurrences unless the word is capitalized mid-sentence in at least one instance.

### LLM latency breaks the review flow

If the Claude API path takes 3–8 seconds, the user may have already started reviewing local suggestions before LLM results arrive, creating a jarring UI update.

Mitigation: show local heuristic suggestions immediately; stream LLM augmentations as an incremental update to the chip tray with a subtle "AI-enhanced" badge appearing when LLM results finalize. Do not clear and re-render — merge new LLM-only suggestions at the tail of the list, promote confidence scores for items already shown.

### User ignores the suggestion tray entirely

If the tray is below the fold or visually under-weighted, users will proceed without reviewing it.

Mitigation: display the suggestion tray in the review stage between the import preview and the "Confirm import" button, with a count badge ("3 suggested pages") visible even when collapsed. The tray should be expanded by default when there are 1–10 suggestions, collapsed with a count badge when there are >10.

---

## Migration and Adoption Cost

Framed as adoption cost of each UX pattern for SteleKit:

### Chip tray (horizontal scrollable row of suggestion chips)

**Adoption cost: Low.** SteleKit's import review screen already shows matched page chips. The suggestion chip tray is a new section below the existing matched-pages section, using the same chip component. The Compose chip layout is straightforward. The main new cost is the confidence indicator (a colored dot or percentage label on each chip).

### Sidebar panel (Capacities-style)

**Adoption cost: Medium.** Requires adding a persistent sidebar panel to the import review screen. More real estate than SteleKit's current modal-style import flow supports cleanly on mobile. Disadvantageous for KMP's phone targets.

### Inline annotation in preview text (Tana-style)

**Adoption cost: High.** Would require the import preview to render annotated Compose text with tappable suggestion spans inside the block tree. The `ImportScreen` preview currently renders read-only text; making spans interactive is a significant rendering investment. High value on desktop, poor ergonomics on narrow mobile screens.

### Silent enrichment (Mem-style)

**Adoption cost: Near-zero for implementation, high for trust.** Easiest to build but contradicts SteleKit's user-in-control philosophy and the requirements' explicit exclusion of "automatic page creation without user review." Not a viable pattern.

### Modal checklist (full-screen review)

**Adoption cost: Medium.** A dedicated full-screen "Review suggested pages" modal/screen after the import summary. Works well for 10–20 suggestions; becomes tedious for 3 suggestions. Best fit when a user wants structured per-item review with explanation, not quick triage.

---

## Operational Concerns

- **PageNameIndex deduplication is required before display.** Any candidate already in the graph must not appear in the suggestion tray regardless of confidence. Failing to deduplicate would suggest creating pages that already exist, which is confusing.
- **Stub creation is permanent.** Accepted suggestions create pages via `GraphWriter.savePage`. There is no undo within the import flow itself. The `[[wiki link]]` insertion into the imported content is also permanent on confirm. Users should be warned if they bulk-accept a large set.
- **LLM API key scope.** The Claude API call for enhanced suggestions should use the same key-management path as any other Claude feature in SteleKit. It must not fire on every keystroke or every paste — only explicitly at the import review stage, and only if a key is configured.
- **Suggestion persistence across sessions.** If the user is mid-review and switches away, the suggested page list should not be preserved — it should be regenerated fresh when the import review is reopened. Stale suggestions from a previous session would be confusing.

---

## Prior Art and Lessons Learned

1. **Tana's key insight**: low visual weight for suggestions dramatically reduces dismissal friction. A chip that requires one tap to dismiss will be dismissed; a modal confirmation requires more effort and users procrastinate.

2. **Mem's cautionary tale**: fully silent enrichment without a review step causes graph pollution at scale. Even highly accurate AI models produce enough false positives over hundreds of imports to create a messy graph. The review step is essential.

3. **Capacities's lesson on type coarseness**: coarse-grained type suggestion (article vs. person vs. URL) is much easier to get right than fine-grained concept extraction. For heuristic-only mode, focusing on proper nouns and multi-word technical terms (high precision, lower recall) is preferable to casting a wide net (lower precision, higher recall).

4. **Logseq/Obsidian's gap**: neither tool attempts import-time concept suggestion, leaving this as a genuine differentiator for SteleKit. The bar is low — there is no dominant prior implementation to displace.

5. **The "5–15 sweet spot"**: UX research on tag suggestion in email clients (Gmail's AI label suggestions [TRAINING_ONLY — verify]) and content tagging systems (Wordpress, Medium auto-tag) consistently finds that 5–10 suggestions is the optimal range for engagement. Below 3, users doubt the feature's usefulness; above 15, users skip review. This maps well to the requirements' stated "5-15" range.

6. **Confidence thresholding beats ranking alone**: showing a confidence indicator per item (color dot: green/yellow/red, or a percentage) outperforms simple rank ordering in user studies on recommendation systems [TRAINING_ONLY — general UX literature, not PKM-specific]. Users use the indicator to make faster per-item decisions rather than reading each label in depth.

---

## Open Questions

1. Should the confidence indicator be a numeric percentage (e.g. "87%") or a qualitative color dot (green/yellow/orange)? Numeric is more precise but may prompt over-analysis. Color dot is faster to scan.
2. For the heuristic path, should single-occurrence proper nouns be shown at all, or should the minimum frequency threshold be 2? Frequency-1 single words have a high false-positive rate but may include important proper nouns (e.g., an expert's name mentioned once).
3. How should the LLM augmentation interact with already-dismissed suggestions from the local heuristic pass? If the user dismissed "TensorFlow" from the local list but the LLM considers it high-confidence, should it reappear? Recommended: no — respect user dismissal regardless of LLM confidence.
4. What is the right UI treatment for the confidence indicator when using local heuristics vs. LLM? The LLM confidence is semantically richer (e.g., "This appears to be a named technology") — should a tooltip be shown for LLM-scored items?
5. Should "Accept All" require a confirmation step, or should it be a single tap? Given that accepted suggestions create permanent stub pages, a confirmation ("Accept 8 suggestions and create 8 stub pages?") is advisable.
6. On mobile (Android/iOS), the chip tray must be horizontally scrollable or wrap to multiple rows. Which layout is preferred? Wrapping is more scannable but takes more vertical space; scrolling is compact but hides suggestions off-screen.

---

## Recommendation

**Recommended UX pattern: expandable chip tray with confidence dots and per-chip dismiss.**

### Placement

In the `ImportScreen` review stage, add a "Suggested new pages" section below the existing "Matched pages" chips row and above the "Confirm" button. When there are 1–10 suggestions, expand the tray by default. When there are >10, collapse with a "Show N suggestions" toggle.

### Chip anatomy

Each chip shows: `[confidence dot] [concept label] [×]`

- Confidence dot: green (high, ≥0.7), yellow (medium, 0.4–0.69), orange (low, 0.2–0.39). Items below 0.2 are suppressed entirely.
- Concept label: the extracted noun phrase or proper noun as it would become the page title. Title-case normalize on display.
- × button: dismiss this suggestion (removes from tray, does not create a page).

### Section header controls

`Suggested new pages (N)   [Accept All]`

- "Accept All" triggers a confirmation snackbar/dialog: "Create N stub pages?" with OK/Cancel.
- After accepting an individual chip, it transitions to a "linked" visual state (checkmark, muted style) rather than disappearing — giving the user a clear record of what was accepted before they confirm the import.

### Signal/noise filtering (local heuristic)

- Extract capitalized multi-word noun phrases (2–4 words) with frequency ≥ 1.
- Extract single capitalized words with frequency ≥ 2, not in a common-word blocklist.
- Exclude sentence-initial words unless they also appear capitalized mid-sentence elsewhere in the text.
- Deduplicate against `PageNameIndex` (exact match + case-insensitive).
- Score: base score = log(frequency) × capitalization_bonus × length_bonus; normalize to 0–1.

### LLM augmentation (Claude API, opt-in)

- Fire after local pass completes; update chip tray incrementally.
- Claude prompt: given the imported text and the list of local candidates, (a) re-score the candidates with a semantic confidence, (b) propose up to 5 additional concepts not in the local list. Return as JSON.
- Display an "AI-enhanced" badge on the tray header when LLM results arrive.
- Do not re-show dismissed items even if LLM rates them highly.

### Copy from Tana

- Low visual weight: chips must be compact and dismissible with a single tap/click. No modal interruption for individual dismissals.

### Avoid

- Mem-style silent creation: always require explicit user acceptance before stub creation.
- Showing raw extraction noise (common words, fragments): apply confidence threshold before display.
- Clearing and re-rendering the tray when LLM results arrive (causes disorienting UI jump): merge/promote incrementally.

---

## Pending Web Searches

The following searches should be run to validate or update training-knowledge claims before finalizing the implementation plan:

1. `site:discuss.logseq.com "import" "suggest" "new page"` — check for any recent Logseq import-time suggestion feature or plugin
2. `site:forum.obsidian.md "import" "suggest pages" OR "suggest tags"` — check Obsidian community for recent related plugins
3. `"Tana" "supertag" "suggest" "import" 2024 OR 2025` — verify current state of Tana AI capture suggestions
4. `"Capacities" "AI" "concept suggestion" "import" 2024 OR 2025` — verify current Capacities AI Space feature behavior
5. `"Mem" "auto tag" OR "auto link" "import" 2024 OR 2025` — verify post-pivot Mem behavior for entity extraction
6. `"Notion AI" "autofill" "tags" "new page" suggest 2025` — verify current Notion AI autofill scope
7. `PKM "topic suggestion" UX "confidence score" "chip"` — look for any academic/UX literature on suggestion chip design for knowledge tools
8. `"accept all" "create pages" PKM UX study` — look for user research on bulk-accept confirmation patterns
9. `Roam Research SmartBlocks "entity extraction" "new page" 2024` — verify current SmartBlocks NLP capabilities
10. `Kotlin Multiplatform "noun phrase extraction" OR "NLP" heuristic pure-kotlin` — validate stack feasibility (overlaps with stack research dimension)
