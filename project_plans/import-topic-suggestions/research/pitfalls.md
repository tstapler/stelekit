# Findings: Pitfalls — False Positives, Latency, UX Overload

**Date**: 2026-04-16
**Feature**: Import Topic Suggestions (heuristic + optional Claude API)
**Research method**: Codebase reading + training knowledge

---

## Summary

Six distinct failure modes span the two-layer extraction pipeline (local heuristics first, Claude API optionally second):

1. **False positives from heuristics** — regex and frequency-based noun-phrase extraction on technical text produces many non-page-worthy candidates: section headers ("Introduction", "Conclusion"), ubiquitous acronyms ("API", "URL", "HTTP"), determiner phrases ("the following", "a simple"), and generic nouns ("data", "system", "method"). On a 5 KB technical article the raw candidate list routinely exceeds 50 items.
2. **Claude API latency** — a claude-haiku-class topic-extraction prompt over a 2–5 KB article takes 1–3 seconds on a cold start [TRAINING_ONLY — verify]. Without progressive disclosure the UI appears frozen while the Claude call is in flight.
3. **Suggestion list overload** — the raw heuristic pass on a 5 KB article easily yields 40–80 candidates before any filtering; users faced with a 50-item checklist abandon the review step entirely.
4. **Claude API failure modes** — rate limiting (429), context window exceeded, malformed JSON in the response, and API key not configured are each plausible and must each degrade gracefully.
5. **Accepting a suggestion that already exists** — race condition: `PageNameIndex` is rebuilt asynchronously after every page-set change. A suggestion produced when no matching page existed may become invalid before the user clicks Accept.
6. **Stub page pollution** — bulk-accept creates pages immediately; the user may later regret accepting 10–20 low-quality suggestions and face a litter of empty stub pages with no easy cleanup path.

The two highest-impact / highest-likelihood risks are **suggestion list overload** (almost certain on any real-world import) and **Claude API failure modes** (affects every user who configures an API key). Both are mitigable with bounded complexity.

---

## Options Surveyed

### FM-1: False Positives from Heuristics

The heuristic pipeline must produce candidates without a trained POS tagger (KMP constraint). Common approaches and their false-positive profiles:

| Option | Description | False-positive rate on technical text |
|--------|-------------|---------------------------------------|
| **A — Capitalized n-gram extraction** | Extract sequences of capitalized words (≥2 chars each). Captures "Machine Learning", "TensorFlow", "Apache Kafka". | Medium — section headers ("Introduction", "Related Work"), sentence-start bigrams ("The Algorithm", "This Paper") are false positives. |
| **B — Frequency × capitalization scoring** | Score candidates by (raw frequency) × (capitalization bonus) × (length bonus). Apply a minimum score threshold. | Lower than A alone — common generic capitalized words score low due to frequency (if they appear on almost every line, they are likely structural rather than topic words). |
| **C — Stopword + banned-phrase filter** | Maintain a static list of stopwords AND a list of structural phrases to never suggest ("introduction", "conclusion", "abstract", "section", "figure", "table", "et al.", "ibid."). | Lowers false positives for standard section headers but does not catch domain-specific noise ("the model", "our approach"). |
| **D — Novel-only filter via `PageNameIndex`** | After candidate extraction, filter out any candidate whose lowercase form appears in `PageNameIndex._canonicalNames` (already a page). This is required by spec. | Orthogonal to false-positive rate; reduces duplicate suggestions but does not reduce noise for non-existing terms. |
| **E — Candidate cap with score-based truncation** | Compute scores for all candidates, sort descending, emit only the top N (e.g., 15). Remainder are silently discarded. | Does not reduce false-positive rate per slot, but limits the blast radius so a few false positives are tolerable. |
| **F — Claude API re-ranking pass** | Send local candidates to Claude for semantic filtering: "Which of these are genuinely page-worthy concepts? Return a JSON array." Claude acts as a quality gate, not a generator. | Low — Claude reliably distinguishes "machine learning" (page-worthy) from "the following" (noise) in a single short prompt. |

**Recommended**: B + C + D + E locally (score, filter, cap at 15). F (Claude re-rank) as the optional enhancement pass.

---

### FM-2: Claude API Latency

| Option | Description |
|--------|-------------|
| **A — Block UI until Claude responds** | Show a spinner in the suggestion panel; no local suggestions visible until Claude finishes. | Worst UX — 1–3 s apparent freeze. |
| **B — Local-first, Claude augments** | Show the local top-N suggestions immediately (<500 ms). Claude call runs in a background coroutine. When Claude responds, update/extend the list with higher-quality candidates. New suggestions are visually distinguished ("AI-enhanced") or the list is silently refined. | Good UX — user can begin reviewing immediately; Claude result arrives as a non-disruptive update. |
| **C — Claude call fires before review screen** | Start the Claude call as soon as the raw text is available (during the debounce window), before the user navigates to the review stage. Increases the probability that the Claude result arrives by the time the user sees the suggestion panel. | Reduces perceived latency; wastes API calls if the user abandons the import. |
| **D — Timeout + partial result** | Set a hard 8-second timeout on the Claude call. If it times out, keep the local results and surface a non-blocking notice: "AI enhancement timed out — showing local suggestions only." | Necessary safety net for options B and C regardless. |

**Recommended**: B + C + D. Start the Claude call eagerly (C), present local results immediately (B), impose an 8-second timeout (D). This matches the "local-first, Claude augments" mental model in the requirements.

---

### FM-3: Suggestion List Overload

| Option | Description |
|--------|-------------|
| **A — Hard cap at N suggestions (e.g. 15)** | Only the top 15 scored candidates are shown. Others are silently dropped. | Simple; loses some good candidates if scoring is imperfect. |
| **B — Progressive disclosure ("Show more")** | Show top 8 by default; a "Show 7 more" link reveals the next tier. | Keeps the initial view manageable while allowing power users to see lower-ranked candidates. |
| **C — Confidence tiers (High / Medium / Low)** | Group suggestions into three tiers. High tier (≤5 items) shown expanded by default; lower tiers collapsed. | Requires a reliable confidence score — if scoring is weak, grouping is misleading. |
| **D — Bulk-dismiss low-confidence tier** | Add "Dismiss all low-confidence suggestions" button so the user can clear noise in one tap without reviewing each item. | Reduces friction when the low-confidence tier is long; safe because individual High/Medium items are still reviewed. |

**Recommended**: A + B + C for v1. Cap total at 15 (A). Show top 8 expanded with a "Show more" link (B). If Claude API is enabled, use Claude confidence scores for tiers (C). Without Claude, use a two-tier split: score ≥ threshold = High, remainder = Medium.

---

### FM-4: Claude API Failure Modes

| Failure sub-mode | Root cause | Graceful degradation |
|---|---|---|
| **API key not configured** | User has not provided a key | Skip Claude call silently; show local suggestions only. No error message shown unless user explicitly visits API settings. |
| **Rate limiting (HTTP 429)** | Too many requests per minute | Retry once after a 1-second delay; if still 429, fall back to local suggestions. Surface a subtle badge: "AI enhancement unavailable (rate limited)." |
| **Context window exceeded** | Input text + prompt > model limit | Truncate the input text to a safe limit (e.g. 3 000 tokens ≈ 12 KB of text) before sending. Never send a request that will certainly fail. |
| **Malformed JSON response** | Claude did not follow the JSON schema | Catch `JsonDecodingException`; fall back to local suggestions. Log the raw response for debugging. |
| **HTTP 5xx / network error** | Server-side transient failure or no connectivity | Fall back to local suggestions; surface a non-blocking "AI enhancement unavailable" badge. |
| **Unexpected response shape** | Claude returns valid JSON but not the expected schema (e.g., a string instead of an array) | Wrap deserialization in a `runCatching`; fall back to local suggestions. |
| **API key revoked / 401** | Invalid or expired key | Same fallback as "key not configured" for v1; optionally surface a targeted "API key invalid — check Settings" notice. |

**Recommended**: Wrap all Claude calls in a sealed `ClaudeResult { Success(suggestions) / Failure(reason) }`. All `Failure` branches degrade to local-only mode. Never throw unhandled exceptions to the ViewModel.

---

### FM-5: Accepting a Suggestion That Already Exists (Race Condition)

The `PageNameIndex` is rebuilt asynchronously whenever `PageRepository.getAllPages()` emits. If the user:

1. Opens the review stage (suggestions computed against index version N)
2. Another coroutine creates a page with the same name as a suggestion (e.g., the user accepts a suggestion in a concurrent import, or the file system is updated externally)
3. The user clicks Accept on the now-stale suggestion

`ImportViewModel.confirmImport()` already calls `pageRepository.getPageByName(normalizedName).first()` before saving each imported page — but that guard is for the *import page itself*, not for stub pages created from accepted suggestions. The stub creation path (not yet implemented) will need its own guard.

| Option | Description |
|--------|-------------|
| **A — Pre-accept existence check** | Before creating the stub, call `pageRepository.getPageByName(suggestion.name).first()`. If the page already exists, dismiss the suggestion chip with a toast: "Page already exists — suggestion removed." | Low cost; eliminates the TOCTOU window for the common case. |
| **B — Re-validate all suggestions on focus return** | When the import screen regains focus (user navigates away and back), re-run the novelty filter against the current `PageNameIndex` and dismiss suggestions that are no longer novel. | Handles the broader case where index changes while the user is on another screen. |
| **C — Atomicity via `saveMutex`** | The existing `GraphWriter.saveMutex` serializes all file writes. The existence check + stub creation can be placed inside the mutex to eliminate the TOCTOU window. | Strongest correctness guarantee; slightly increases complexity since the check moves into `GraphWriter`. |

**Recommended**: A + B for v1. C is desirable but higher complexity; defer to v2 if needed.

---

### FM-6: Stub Page Pollution

| Option | Description |
|--------|-------------|
| **A — No-op undo (no mitigation)** | Accepted stubs are permanent. User must manually delete each. | Worst case — 20 empty pages cluttering the graph. |
| **B — Undo within session** | After bulk-accept, show a snackbar "20 pages created · Undo" with a 10-second window. Clicking Undo calls `GraphWriter.deletePage` on each stub. | Low complexity; handles the most common regret scenario (immediate "oops"). |
| **C — Staged acceptance (confirm before write)** | Accepting a suggestion marks it "pending"; a final "Create pages" button commits. The user reviews the pending set before any writes. | Prevents regret by requiring an extra confirmation step. Adds UI complexity. |
| **D — Empty-page cleanup command** | Provide a separate "Clean up empty pages" action in settings that lists all pages with no blocks and no backlinks. | Addresses stub pollution holistically, not just from topic suggestions. Useful general feature. |
| **E — Limit bulk-accept quantity** | Cap single bulk-accept at 10 items. For larger selections, require two confirm gestures. | Reduces blast radius without extra undo machinery. |

**Recommended**: B (undo snackbar) + E (bulk-accept cap of 10). D is a good general hygiene feature but separate from this feature's scope.

---

## Trade-off Matrix

| Failure Mode | Impact Severity | Likelihood | Mitigation Complexity | Recommended Mitigation |
|---|---|---|---|---|
| False positives from heuristics | High — noisy suggestions undermine user trust | Very High — unavoidable without POS tagger | Medium — multi-signal scoring + structural stopword list + cap | Score + structural stoplist + cap at 15; Claude re-ranks when available |
| Claude API latency | Medium — UI feels sluggish | High — any user with an API key hits this | Low — coroutine + local-first presentation | Local results first (<500 ms); Claude updates list asynchronously; 8 s timeout |
| Suggestion list overload | High — users abandon review if list is long | Very High — almost certain on any real article | Low — cap + progressive disclosure | Cap at 15; show top 8 expanded; "Show more" for remainder |
| Claude API failure modes (all sub-modes) | Medium — AI enhancement lost; local suggestions still shown | Medium — rate limits and key errors are routine | Low — sealed result type + uniform fallback | `ClaudeResult` sealed class; all failures fall back to local-only, never throw |
| Accepting already-existing suggestion (race) | Low-Medium — user sees confusing "page already exists" error | Low — requires concurrent page creation during review | Low — pre-accept existence check | `getPageByName` guard before each stub creation; re-validate on focus return |
| Stub page pollution | Medium — graph cluttered with empty pages | Medium — bulk-accept is designed to be fast and easy | Low — undo snackbar + bulk-accept cap | 10-second undo snackbar after bulk-accept; max 10 items per bulk-accept gesture |

---

## Risk and Failure Modes

### FM-1: False Positives from Heuristics

**What can go wrong**: A pure heuristic pipeline on technical text without a POS tagger or trained model produces candidates that fall into several noise categories:

- **Structural section headers**: "Introduction", "Background", "Related Work", "Conclusion", "Acknowledgements" — capitalized, low-frequency, not page-worthy.
- **Ubiquitous acronyms**: "API", "URL", "HTTP", "JSON", "REST" — appear many times, technically capitalized proper nouns, but are almost never meant as personal knowledge-graph pages by a solo note-taker.
- **Determiner + noun bigrams**: "the following", "a simple", "our approach", "this paper" — capitalized at sentence start, extracted as bigrams. Word-boundary heuristics do not help here.
- **Generic technical nouns**: "data", "model", "system", "method", "result", "function" — even when capitalized, these are background vocabulary, not topics.
- **Author names in citations**: "Smith et al.", "Johnson (2019)" — appear once, often capitalized. May be page-worthy for some users (tracking literature) but not others.

**Quantitative expectation** [TRAINING_ONLY — verify]: Precision of capitalized-bigram extraction on technical prose is typically 20–40% without filtering; applying frequency thresholding and structural stopwords raises precision to roughly 50–65% [TRAINING_ONLY — verify]. At 60% precision and 25 raw candidates, 10 of the 25 shown suggestions are false positives — tolerable if Claude re-ranks them away.

**Code impact**: The topic-extraction function (not yet implemented) will run after `ImportService.scan()` returns. It operates on `rawText`, not `linkedText`, to avoid confusing wiki-link syntax with word boundaries. The existing `AhoCorasickMatcher.isWordBoundary` logic is correct for the matching pass but cannot be reused for extraction since extraction needs to *produce* candidates rather than *match* known patterns.

**Mitigation**:
- Build a `TopicExtractor` that applies: (1) capitalized-sequence detection, (2) frequency scoring, (3) structural stopword filter (section headers, generic tech terms), (4) novelty filter via `PageNameIndex._canonicalNames`.
- Cap output at 15 candidates before any Claude pass.
- Claude API (when available) acts as a high-precision second filter, not a generator.

---

### FM-2: Claude API Latency

**What can go wrong**: The Claude API call is a network round-trip. Typical latency for a short prompt (topic list from a 2 KB article) on claude-haiku-class models is 800 ms–2 500 ms on a warm connection [TRAINING_ONLY — verify]; cold-start (first request after inactivity) can reach 3–5 s [TRAINING_ONLY — verify].

If the ViewModel awaits the Claude result before showing the suggestion panel, the review screen appears frozen for 1–3 seconds after import confirmation. On mobile networks or from regions far from Anthropic's edge this is worse.

**Progressive-result architecture**: The `ImportViewModel` state flow needs to model "local suggestions available" and "Claude suggestions available" as separate events:

```
ImportSuggestionState:
  localSuggestions: List<TopicSuggestion>      // populated after heuristic pass
  claudeSuggestions: List<TopicSuggestion>?    // null = not yet available / not configured
  claudeStatus: ClaudeStatus                  // Idle | Loading | Done | Failed(reason)
```

The UI renders `localSuggestions` immediately. When `claudeStatus` transitions from `Loading` to `Done`, the list is updated with a brief animated refinement. When `claudeStatus` is `Failed`, the list stays as-is with a small "AI unavailable" notice in the panel header.

**Mitigation**:
- Start the Claude coroutine in parallel with the local heuristic pass (or during the 300 ms debounce), not after.
- Never block the review stage transition on the Claude coroutine.
- Impose an 8-second timeout via `withTimeout { … }` in the Claude call coroutine.
- Model Claude status separately in `ImportSuggestionState` so the UI can react independently.

---

### FM-3: Suggestion List Overload

**What can go wrong**: A typical 5 KB article contains ~800 words. Even after aggressive filtering, a capitalized-sequence extractor will surface 30–60 candidates. Displaying all of them in the review stage as individual accept/dismiss chips creates an overwhelming UI:

- Users skip the review and dismiss the entire panel.
- Bulk-accept of 30 items causes FM-6 (stub pollution).
- The cognitive load of evaluating 30 items outweighs the benefit of the feature.

**Evidence from related work**: Obsidian's "Linked Mentions" panel shows all unlinked occurrences and users frequently report ignoring it on large vaults [TRAINING_ONLY — verify]. Logseq's community `autolink-autotag` plugin (confirmed in prior research) does not implement any suggestion cap, and user issues frequently mention "too many suggestions" [TRAINING_ONLY — verify].

**Recommended cap derivation**: The sweet spot for a "quick review" cognitive task is 5–8 items [TRAINING_ONLY — verify: Miller's law and PKM UX research]. For topic suggestions where some false positives are expected, 10–15 is a reasonable v1 cap — large enough to surface genuine novelty, small enough to be reviewable in under 30 seconds.

**Mitigation**:
- Hard cap: emit at most 15 suggestions from the local pass, 15 from the Claude pass (post-merge deduplication → ≤15 total).
- Default display: show top 8, collapsed "Show 7 more" section for the remainder.
- Confidence tiers: High (score ≥ 0.7) expanded by default; Medium/Low collapsed or hidden until "Show more".

---

### FM-4: Claude API Failure Modes

**What can go wrong — sub-mode breakdown**:

**Key not configured**: The most common case. Every user who has not set up an API key. Must be completely silent — no error, no prompt to configure. The feature spec requires "zero API calls unless user has configured a key"; the corollary is "zero error messages for unconfigured keys."

**Rate limiting (HTTP 429)**: Anthropic's API rate limits on the free and Tier-1 tiers are low enough that a developer testing their own app could hit them [TRAINING_ONLY — verify: Anthropic rate limit tiers]. A solo knowledge worker importing 10–20 articles per day is unlikely to hit limits on Tier-2+, but it must be handled.

**Context window exceeded**: claude-haiku has a 200 000 token context window [TRAINING_ONLY — verify], making this extremely unlikely for a reasonable topic-extraction prompt + 10 KB article. However, the prompt construction code must enforce a hard truncation limit (e.g., 15 000 tokens / ~60 KB of text) to be safe regardless of which model the user has configured.

**Malformed JSON**: Claude API responses are not guaranteed to be valid JSON even when instructed. JSON mode (if available for the configured model) reduces but does not eliminate this risk [TRAINING_ONLY — verify: Anthropic JSON mode availability]. The deserialization must be wrapped in `runCatching`.

**Network unavailable**: Common on mobile (background → foreground transitions). The import itself (already saved) is unaffected; only the Claude enhancement is lost.

**Code impact**: The Claude API client (not yet implemented) must expose a function returning `ClaudeResult<List<TopicSuggestion>>`. The `ImportViewModel` must handle all `ClaudeResult.Failure` variants by updating `claudeStatus` to `Failed(reason)` without modifying `localSuggestions`.

**Mitigation summary**: Single sealed result type. All failure variants → fall back to local mode. Use a `withTimeout(8_000)` coroutine timeout. Truncate input at 15 000 tokens before sending. Wrap JSON parsing in `runCatching`.

---

### FM-5: Accepting a Suggestion That Already Exists (Race Condition)

**What can go wrong**: The sequence:

1. Heuristic pass produces suggestion "Vector Database" (not in graph at scan time T₀).
2. User takes 30 seconds reading the suggestions.
3. A background graph reload fires (external file change, another import, index rebuild).
4. A new page "Vector Database" is created by the graph reload at time T₁.
5. User clicks Accept on "Vector Database" at T₂.
6. Stub creation calls `GraphWriter.savePage("Vector Database", emptyBlocks, path)` — silently overwrites or errors.

The existing `ImportViewModel.confirmImport()` guards the *import page itself* against collision (line 210 of `ImportViewModel.kt`) but there is no equivalent guard for stub pages from topic suggestions.

**Additional sub-risk**: `PageNameIndex` maintains a `StateFlow<AhoCorasickMatcher?>` rebuilt via `flowOn(Dispatchers.Default)`. The novelty filter runs against the index snapshot at scan time. If the index rebuilds between scan and accept, a suggestion that was novel at scan time may no longer be novel at accept time. This is the same stale-state pattern that motivated ADR-002 (version stamp for `SuggestionState`) in multi-word-term-highlighting.

**Mitigation**:
- Before each stub creation: call `pageRepository.getPageByName(suggestion.name).first()`. If the page exists, dismiss the suggestion chip with a toast and skip creation.
- On re-entering the suggestion panel (e.g., navigating back from another screen): re-filter suggestions against the current `PageNameIndex.matcher.value` and dismiss any that are no longer novel.
- This pattern directly mirrors ADR-002's `capturedContent` guard: the check happens at *apply* time, not at *scan* time.

---

### FM-6: Stub Page Pollution

**What can go wrong**: The bulk-accept flow is designed to be low-friction. A user importing a dense technical article may bulk-accept all 10–15 suggestions in one click. Two hours later they realize half the suggestions were noise. They now have 5–7 empty stub pages with no content and no backlinks (since the retroactive wiki-link insertion is the only reference).

The existing `GraphWriter.deletePage` function (if it exists — not confirmed in code review) is the delete primitive, but there is no UI path to "delete all recently created empty pages."

**Evidence from related tools**: Roam Research users report "ghost page" proliferation as a known pain point with any auto-creation feature [TRAINING_ONLY — verify]. Logseq's namespace feature creates stub pages for namespace components, and users periodically run "re-index" to clean up orphaned ones [TRAINING_ONLY — verify].

**Mitigation**:
- Undo snackbar: after any acceptance (single or bulk), show "N page(s) created · Undo" for 10 seconds. Clicking Undo calls `GraphWriter.deletePage` (or equivalent) for each stub and removes the inserted `[[wiki links]]` from the import content.
- Bulk-accept cap: limit single bulk-accept to 10 items. Selecting more than 10 requires two confirm gestures ("Accept these 10" then "Accept 5 more").
- Wiki-link removal on undo: when a stub is deleted, the `[[wiki link]]` that was retroactively inserted into the imported content should also be reverted to plain text. This is the trickiest part of undo; it may be acceptable in v1 to leave the wiki links in place (they become unlinked references) and only delete the stub page, with a note that the link text remains.

---

## Migration and Adoption Cost

N/A — not applicable. This is a new feature with no migration path.

---

## Operational Concerns

- **Claude API cost per import**: A topic-extraction prompt for a 5 KB article with 15 local candidates will consume roughly 1 500–2 000 input tokens and 100–200 output tokens on a claude-haiku-class model [TRAINING_ONLY — verify]. At Anthropic's current pricing this is well under $0.01 per import. Cost is not a concern for individual users; the API key is the user's own.
- **Rate limit accumulation**: A user importing 50 articles in a single session could hit per-minute rate limits. The retry-once-then-fall-back strategy handles this without blocking the import flow.
- **Coroutine lifecycle**: The Claude API call is a long-running coroutine that must be cancelled if the user navigates away from the import screen before it completes. The `ImportViewModel`'s coroutine scope must be tied to the screen's lifecycle (already the case for `scanJob`); the Claude job must follow the same pattern.
- **No local model storage**: The constraints prohibit bundled ML model files. This rules out any on-device transformer for topic extraction, confirming that the heuristic-only path must be robust enough to stand alone.
- **`PageNameIndex` staleness window**: As noted in the existing import pitfalls document, there is a window between a page being created and the `PageNameIndex` rebuilding its matcher. The novelty filter for topic suggestions shares this window. Suggestions produced during the window may include terms that were just added to the graph. The pre-accept existence check (FM-5 mitigation) is the backstop.

---

## Prior Art and Lessons Learned

- **Logseq `autolink-autotag` community plugin**: Confirmed (prior research) that Logseq has no built-in new-page suggestions on import. The community plugin's GitHub issues include multiple reports of suggestion list overload and false positives for common tech terms — direct evidence that an uncapped, unfiltered heuristic approach is unusable in practice. [TRAINING_ONLY — verify: specific issue numbers]
- **Obsidian Web Clipper (late 2024)**: Confirmed (prior research) does not auto-link to existing vault notes. The AI summarization feature uses OpenAI/Anthropic APIs client-side, demonstrating that API-key-optional AI integration in a desktop PKM is an accepted UX pattern.
- **ADR-002 (multi-word-term-highlighting)**: The `capturedContent` version-stamp pattern is the direct precedent for FM-5. The same "validate at apply time, not at scan time" principle must be applied to topic suggestion acceptance.
- **Existing import pitfalls (FM-1 in `stelekit-import/research/pitfalls.md`)**: The stopword list already implemented in `PageNameIndex.DEFAULT_STOPWORDS` (37 function words) is insufficient for topic *extraction* — it was designed to reduce false *matches* against existing pages, not to filter *new* candidates. The extraction stoplist must be broader: it needs structural academic/blog terms ("introduction", "conclusion", "section", "figure"), pervasive tech acronyms ("api", "url", "http", "json"), and generic nouns.
- **Roam Research "ghost pages"**: Auto-creation of pages from `[[]]` syntax in Roam is well-documented as a source of page proliferation. The undo snackbar pattern (FM-6 mitigation) is the standard counter-measure in PKM tools. [TRAINING_ONLY — verify]
- **Progressive enhancement pattern**: Showing local results first and updating them when the API responds is the standard pattern for AI-assisted features in consumer tools (e.g., GitHub Copilot completion streaming, Notion AI inline suggestions). The pattern is well-understood and does not require novel UX design. [TRAINING_ONLY — verify: specific Notion/Copilot implementation details]

---

## Open Questions

1. **What confidence score threshold distinguishes High from Medium suggestions?** The score formula (frequency × capitalization × length bonus) needs calibration against a test corpus of typical user imports. Without calibration, any threshold is a guess. A good v1 heuristic: terms appearing ≥3 times with all-caps-initial-word = High; ≤2 times or mixed capitalization = Medium.

2. **Should the heuristic extraction stoplist be user-configurable?** A developer note-taker may want "API" suggested (they are building an API reference). A non-technical user would not. A graph-level settings entry "Additional stopwords" is the right long-term answer; v1 should hardcode a conservative list.

3. **How does the retroactive wiki-link insertion interact with HTML-imported content?** `ImportViewModel.confirmImport()` has two paths: HTML blocks (via `HtmlBlockConverter`) and plain text split on `\n\n`. The retroactive `[[wiki link]]` insertion from accepted topic suggestions must operate on the *stored block content*, not on `rawText`. This means the topic-suggestion acceptance step must run *after* block creation, patching each block's content. The current `ImportViewModel` does not have this hook.

4. **What is the right model for prompting Claude for topic extraction?** Two strategies: (a) send raw text and ask Claude to identify page-worthy topics, (b) send local candidates and ask Claude to filter/rank them. Option (b) is cheaper (fewer input tokens if the candidate list is short) and more controllable (Claude only evaluates pre-filtered candidates). Option (a) may surface topics that the local heuristic missed entirely.

5. **Should the Claude API call use JSON mode or structured outputs?** Anthropic's tool-use / structured-output features are more reliable than prompt-instructed JSON [TRAINING_ONLY — verify: Anthropic structured output availability in KMP ktor client]. However, the KMP ktor-based client will need to implement the tool-use API path separately from a simple messages request.

6. **What is the UX for "Claude unavailable" when the user has configured a key?** The current spec says API key configured → Claude is called. If Claude is rate-limited or down, is the panel badge enough, or should there be a retry button? A retry button is better UX but adds state complexity.

7. **Does `GraphWriter.deletePage` exist?** The undo snackbar (FM-6 mitigation) requires deleting stub pages programmatically. A quick grep of `GraphWriter.kt` was not performed in this research pass — this must be confirmed before designing the undo path.

---

## Recommendation

Prioritized v1 mitigations:

**[P0] Cap suggestions at 15 total (local + Claude merged).** Emit at most 15 from the local heuristic pass. Never show more than 15 in the UI. A suggestion list longer than 15 is the single most likely reason users will ignore the feature entirely.

**[P0] Structural stoplist for topic extraction.** Maintain a separate, broader stoplist for extraction (vs. the existing `PageNameIndex.DEFAULT_STOPWORDS` for matching). Include: academic/blog section headers ("introduction", "conclusion", "abstract", "methodology"), pervasive tech acronyms ("api", "url", "http", "json", "rest", "html", "css", "sdk", "cli"), and high-frequency generic nouns ("data", "model", "system", "method", "result", "function", "approach", "paper", "work").

**[P0] All Claude API errors → silent fallback to local suggestions.** Implement `ClaudeResult<T>` sealed class. No uncaught exceptions from the Claude call coroutine. No error dialogs for Claude failures. Only a small "AI enhancement unavailable" badge in the suggestion panel header when the failure is worth surfacing (rate limit or key invalid).

**[P1] Local-first presentation.** Local suggestions appear in the review stage within 500 ms. The Claude coroutine starts eagerly (during or before the debounce window) but the review stage does not wait for it. Claude result updates the list non-disruptively when it arrives.

**[P1] Pre-accept existence check for stub creation.** Before each `GraphWriter.savePage` call for a stub, call `pageRepository.getPageByName(name).first()`. If the page exists, dismiss the chip with a toast. This directly mirrors the existing collision check in `confirmImport()` (line 210 of `ImportViewModel.kt`).

**[P1] Undo snackbar after acceptance.** Show "N page(s) created · Undo" for 10 seconds after any acceptance. Clicking Undo deletes stubs (confirm `GraphWriter.deletePage` availability first). Cap bulk-accept at 10 items.

**[P2] Claude prompt strategy: re-rank local candidates.** Send local candidates to Claude for filtering/ranking rather than asking Claude to generate from scratch. Cheaper, more predictable output, and avoids Claude introducing candidates that don't appear in the text.

**[P2] 8-second Claude timeout.** Enforce via `withTimeout(8_000)` in the Claude call coroutine. After timeout, transition `claudeStatus` to `Failed(Timeout)` and keep local results.

**[P3] Re-validate suggestions on focus return.** When the import screen regains focus, re-run the novelty filter against the current `PageNameIndex.matcher.value` and dismiss any suggestion chips whose names now exist as pages.

---

## Pending Web Searches

The following searches were not performed. They should be run before finalizing the architecture plan:

1. `anthropic claude api rate limits tier 1 tier 2 requests per minute 2025` — confirm rate limit tiers to size the retry strategy.
2. `anthropic claude haiku latency typical p50 p95 2025` — confirm typical latency numbers for the short topic-extraction prompt size.
3. `anthropic structured outputs json mode tool use kotlin ktor kmp` — confirm whether Anthropic's structured output / tool-use API is accessible via a plain ktor HTTP client in KMP without the official SDK.
4. `kotlin multiplatform topic extraction noun phrase NLP pure kotlin 2024 2025` — verify whether any maintained KMP-compatible NLP library has emerged since the requirements were written.
5. `obsidian roam logseq ghost pages stub page cleanup` — confirm prior art claims about page proliferation in competing PKM tools.
6. `miller's law working memory limit UX suggestion list cognitive load` — verify the 7±2 guideline as applied to suggestion list design.

## Web Search Results

**Claude model IDs** (searched 2026-04-17): Recommended model for topic extraction: `claude-haiku-4-5-20251001`. Retired models (`claude-3-7-sonnet-20250219`, `claude-3-5-haiku-20241022`) now return errors — do not use. `anthropic-version: 2023-06-01` is the current required header.

**Rate limits** (searched 2026-04-17): Exact Tier 1 numbers not returned by search; see https://docs.anthropic.com/en/api/rate-limits. Architecture implication: the token-bucket model means continuous replenishment rather than hard per-minute resets. For a single-user desktop app making one API call per import, rate limiting is unlikely to be a practical concern. Implement a simple exponential-backoff retry (max 2 retries, 2s cap) behind the `ClaudeTopicEnricher`; treat a final 429 as `Failed.RateLimited` → degrade silently to local suggestions.
