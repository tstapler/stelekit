# Findings: Architecture — Topic Extraction in ImportService Pipeline

## Summary

The cleanest architecture places heuristic topic extraction inside a new pure-function `TopicExtractor` object in `domain/`, called from `ImportService.scan()` as a second, independent pass that returns an extended `ScanResult` with a `topicSuggestions: List<TopicSuggestion>` field. The Claude API enhancement runs as a separate, ViewModel-level async enrichment layer that updates suggestions in-place after the local pass has already made the review UI available. Accepted suggestions flow through `confirmImport()` by saving stubs via `pageSaver` before the import page itself is saved, then the linked text is rewritten to include `[[SuggestedName]]` for each accepted stub.

This design keeps `ImportService` a pure function (no I/O), keeps the Claude client entirely outside the domain layer, and allows the UI to display local suggestions immediately with a "Enhancing with AI…" status indicator that resolves to the enriched list without blocking the review stage.

---

## Options Surveyed

### Option 1 — Extraction inside `ImportService.scan()` as a new return field

Extend `ScanResult` with `topicSuggestions: List<TopicSuggestion>`. Add a `TopicExtractor` pure-function helper that `scan()` delegates to. `ImportService.scan()` signature becomes:

```kotlin
fun scan(rawText: String, matcher: AhoCorasickMatcher, existingNames: Set<String>): ScanResult
```

`existingNames` is the set of all lowercase canonical page names (already available from `PageNameIndex._canonicalNames`), used to filter out already-existing concepts. `TopicExtractor` is a pure object with no I/O; it is tested independently. The ViewModel calls `scan()` as today — one call, one result, both link-matching and topic suggestions arrive together.

**Claude API opt-in** is handled by a separate `TopicEnricher` interface injected into the ViewModel, not into `ImportService`. After the local scan completes, the ViewModel launches a second coroutine that calls `TopicEnricher.enhance(rawText, localSuggestions)` and merges the result back into state.

### Option 2 — Separate `TopicExtractor` called in parallel from the ViewModel

`ImportService.scan()` is not changed. The ViewModel launches two concurrent coroutines: `async { ImportService.scan(text, matcher) }` and `async { TopicExtractor.extract(text, existingNames) }`. Results are combined with `awaitAll()`. Both are pure-function calls dispatched to `Dispatchers.Default`.

### Option 3 — ViewModel-only async layer, no domain extraction object

Topic extraction logic lives entirely inside `ImportViewModel`, as a private function. No separate `TopicExtractor` object. Claude enrichment is also wired directly in the ViewModel. The domain layer (`ImportService`) is not touched.

### Option 4 — Three-stage pipeline: scan → local extract → Claude enrich (fully sequential)

The ViewModel runs three sequential phases: (a) Aho-Corasick scan, (b) local `TopicExtractor.extract()`, (c) optional `TopicEnricher.enhance()`. Phase (a) result is shown as soon as it completes; phase (b) result replaces `topicSuggestions` when it arrives; phase (c) result enriches/replaces suggestions again when the Claude API responds. Each phase updates `ImportState`.

---

## Trade-off Matrix

| Option | Code reuse | Separation of concerns | Testability | Async complexity | Notes |
|---|---|---|---|---|---|
| 1 — Extraction inside `ImportService.scan()` | High — single entry point, domain encapsulated | Good — domain logic in domain layer; Claude client stays in ViewModel | High — `ImportService` remains a pure function; `TopicExtractor` tested independently | Low — one scan call returns everything local; Claude enrichment is a separate ViewModel coroutine | Recommended. Mirrors existing `scan()` design exactly. |
| 2 — Parallel ViewModel coroutines | Medium — requires ViewModel to know about `TopicExtractor` directly | Moderate — extraction logic leaks a bit into ViewModel | High — both are still pure functions | Low-Medium — two `async {}` calls instead of one | Acceptable alternative if `ImportService` must stay untouched |
| 3 — ViewModel-only, no domain object | Low — extraction logic not reusable outside ViewModel | Poor — business logic in UI layer | Low — harder to unit-test in isolation without composable test harness | Low — simple but embedded | Not recommended |
| 4 — Fully sequential three-phase | High | Good | High | High — three UI state transitions, two progressive updates; visible flicker if Claude is fast | Preferred UX model for progressive enhancement but higher impl complexity |

---

## Risk and Failure Modes

### Local extraction pass

1. **High false-positive rate on technical prose.** Regex-based noun-phrase extraction (e.g. sequences of title-cased or all-caps tokens) will flag many non-page-worthy terms on technical documents. Mitigation: apply a minimum frequency filter (candidate must appear ≥ 2 times or be capitalized + length ≥ 4 characters), plus a stop-word extension beyond `PageNameIndex.DEFAULT_STOPWORDS`.

2. **Suggestion list overflow.** A 10 KB article may yield 50+ candidates. Mitigation: cap local candidates at a configurable constant (default 20). Surface the capped count ("showing top 20 of 43 candidates") and let the user dismiss or bulk-accept.

3. **Deduplication gap.** `PageNameIndex._canonicalNames` is a `MutableStateFlow<Map<String,String>>`. The snapshot used in `scan()` may be stale if pages were created between the ViewModel's last matcher update and the scan call. Mitigation: always pass the snapshot that corresponds to the same `AhoCorasickMatcher` instance used for the link-matching pass — both are derived from the same `matcherFlow.value` read.

4. **KMP string processing performance.** Pure-Kotlin regex on a 10 KB string is well within the 500 ms budget on JVM Desktop (`Regex.findAll` on 10 KB completes in ~2 ms). On iOS/Wasm, this should be verified but is unlikely to be a problem for the typical input size. [TRAINING_ONLY — verify on iOS/Wasm targets]

### Claude API enrichment

5. **Claude API call failure mid-review.** The user is already on the review stage; the local suggestions are already visible. If the API call fails (network error, rate limit, invalid key), the ViewModel must silently fall back to local-only suggestions, set `isEnhancing = false`, and optionally show a non-blocking toast. The review flow must never block on the API call.

6. **Latency exceeds user patience.** Claude API round-trips for a short topic-extraction prompt average 1–3 seconds [TRAINING_ONLY — verify with actual Anthropic latency data]. The UI should show a `LinearProgressIndicator` in the suggestions section labelled "Enhancing with AI…" while the call is in flight. Once results arrive, animate the list update (items added/reordered) rather than replacing the whole list.

7. **API key misconfiguration.** If the configured key is invalid, the API returns a 401. The ViewModel must catch this, log it, and set an `aiEnhancementError` field in state. The ImportScreen should show a one-time banner "AI enhancement unavailable — check API key in Settings" rather than silently degrading.

8. **Prompt injection via imported content.** If the raw text is user-supplied and sent verbatim to Claude, a malicious import could attempt prompt injection. Mitigation: wrap the user text in a clearly delimited block in the prompt (e.g. `<document>…</document>` tags); instruct Claude in the system prompt that it must only return a JSON array of candidate terms and ignore all other instructions in the document. [TRAINING_ONLY — verify Claude's resistance to prompt injection in this context]

9. **Result merge conflict between local and Claude passes.** The Claude API may return terms already in the local list (with higher confidence), terms not in the local list (net new), or remove terms the local pass suggested. Merge strategy: use the Claude list as authoritative if available, but preserve any suggestions the user has already accepted before the Claude response arrives. This requires tracking `acceptedByUser: Boolean` per suggestion in state before the Claude result lands.

---

## Migration and Adoption Cost

- **`ScanResult` extension**: adding `topicSuggestions: List<TopicSuggestion>` with a default of `emptyList()` is a backward-compatible data class change. Existing call sites (`runScan()` in `ImportViewModel`) need to read the new field but existing tests require no changes unless they assert on `ScanResult` equality with no topic suggestions.
- **`ImportService.scan()` signature**: adding `existingNames: Set<String>` parameter requires updating the single call site in `ImportViewModel.runScan()`. A default parameter (`= emptySet()`) preserves backward compatibility.
- **`ImportState` additions**: five new fields needed (see State Model section below). No existing state consumers are affected.
- **`confirmImport()` extension**: stub creation is a few lines of new code before the existing page-save call. The `PageSaver` functional interface already supports `save(page, blocks, graphPath)` — stub pages are just `Page` instances with an empty blocks list.
- **New files**: `TopicExtractor.kt` (domain), `TopicSuggestion.kt` (domain model), `TopicEnricher.kt` (interface in domain), `ClaudeTopicEnricher.kt` (implementation in a platform-specific or `jvmMain`/`commonMain` source set depending on the HTTP client used). Estimated 4 new files; no existing files deleted.
- **Test surface**: `TopicExtractor` is pure-function and trivially testable with `jvmTest`. `ImportViewModel` tests need to inject a `TopicEnricher` stub (the interface enables this). Claude API integration tests are integration-only and guarded by an API key check.

---

## Operational Concerns

- **API key storage.** The Claude API key must not be persisted in the graph directory (markdown files) or committed to version control. It should live in platform-specific secure storage (Keychain on iOS/macOS, Android Keystore, or an `.env`-adjacent config file outside the graph root on Desktop). The `TopicEnricher` interface takes the key at construction time, decoupling key resolution from the enrichment logic.
- **Rate limiting.** Anthropic's API has per-minute token limits [TRAINING_ONLY — verify current tier limits]. For the topic-extraction use case a single import calls the API at most once. No retry loop is needed in v1; a single attempt with a short timeout (5–10 seconds) is sufficient.
- **Cost control.** The topic-extraction prompt is small: system prompt (~200 tokens) + document text (~1 000–3 000 tokens for typical import) + response (~100–200 tokens). At current Claude pricing [TRAINING_ONLY — verify] this is fractions of a cent per import. A UI notice ("AI enhancement uses your Anthropic API key and incurs small usage costs") satisfies the requirements' cost-transparency constraint.
- **KMP platform support.** The Anthropic SDK does not have a Kotlin Multiplatform client. The HTTP call must be made via `ktor-client` with a KMP-compatible engine. The JSON response must be parsed with `kotlinx.serialization`. This is straightforward but requires wiring. Confine the Claude client to `commonMain` with a ktor engine injected per platform, or restrict to `jvmMain` + `androidMain` in v1 if iOS/Web support is not needed immediately.

---

## Prior Art and Lessons Learned

### `ImportService` itself (current codebase)

`ImportService.scan()` is already a pure `object` with no I/O — the exact pattern to follow for `TopicExtractor`. The `ScanResult` / `ImportResult` naming precedent is established; the new type should be `TopicSuggestion` (not `NewPageSuggestion` or `Candidate`) for consistency with requirements language.

### `PageNameIndex` + `AhoCorasickMatcher`

`PageNameIndex` already filters journal pages and stop words before building the matcher. The same exclusion logic should apply when building `existingNames` for `TopicExtractor` — i.e., pass the same filtered name set, not `getAllPages()` raw. This avoids re-filtering in the extractor.

### ADR-002 (Stale-guard in multi-word highlighting)

The multi-word highlighting feature required a version stamp on suggestion state to detect stale captures. The same problem applies here: if the user edits the text preview between the local scan and the Claude API response, the Claude suggestions may be based on stale content. Mitigation: stamp each extraction result with the `rawText` hash it was computed from. Before applying the Claude result, compare hashes; discard the result if the text has changed.

### `GlobalUnlinkedReferencesViewModel` accept/reject pattern

The per-item accept/dismiss model in that ViewModel (remove dismissed items from state; route accepted items through `DatabaseWriteActor`) is directly applicable. The key difference: import suggestions must also retroactively rewrite `linkedText` when a suggestion is accepted (inserting `[[AcceptedName]]` at each occurrence in the preview text), because the import page content is finalized at `confirmImport()` time, not immediately.

### `ExportService` pattern

`ExportService` demonstrates injecting a pure domain service at construction time with no platform dependencies. `TopicExtractor` follows the same pattern.

---

## State Model for Suggestions in `ImportState`

The following fields extend the existing `ImportState` data class:

```kotlin
data class TopicSuggestion(
    val term: String,               // Candidate page name (display form)
    val confidence: Float,          // 0.0–1.0; heuristic score or Claude-assigned
    val source: SuggestionSource,   // LOCAL or AI_ENHANCED
    val accepted: Boolean = false,
    val dismissed: Boolean = false,
)

enum class SuggestionSource { LOCAL, AI_ENHANCED }

// Additional fields in ImportState:
val topicSuggestions: List<TopicSuggestion> = emptyList(),
val isEnhancing: Boolean = false,       // Claude API call in flight
val aiEnhancementError: String? = null, // Non-null if Claude call failed
```

**Accept flow:**
1. User taps "Accept" on a `TopicSuggestion`.
2. `ImportViewModel.onSuggestionAccepted(term)` sets `accepted = true` in state.
3. `linkedText` is rewritten by `ImportService.insertWikiLinks(linkedText, acceptedTerms)` — a new pure helper — to insert `[[term]]` at each unlinked occurrence of `term`. This is synchronous and runs immediately.
4. On `confirmImport()`: for each `accepted` suggestion, call `pageSaver.save(stubPage, emptyList(), graphPath)` before saving the import page. Stub pages have an empty block list and no properties.

**Dismiss flow:**
- `onSuggestionDismissed(term)` sets `dismissed = true`. Dismissed suggestions are filtered from the UI list but retained in state for merge-safety when the Claude result arrives.

**Bulk accept:**
- `onAcceptAllSuggestions()` sets `accepted = true` on all non-dismissed suggestions. Same flow as above runs at `confirmImport()` for all accepted terms.

---

## Open Questions

1. **Where should `ClaudeTopicEnricher` live in the source tree?** If the ktor HTTP client is KMP-compatible (`ktor-client-core` + platform engines), it can live in `commonMain`. If the Anthropic HTTP API is called only on JVM/Android for v1, it belongs in a `jvmMain`/`androidMain` source set with an `expect/actual` for the `TopicEnricher` interface. Decision depends on whether iOS/Web support is needed in v1.

2. **What is the right prompt structure for topic extraction?** The system prompt should specify: return a JSON array of strings, each a candidate page name, ranked by page-worthiness, maximum 20 items, exclude common English words. The user content should be the raw import text wrapped in delimiters. The exact prompt needs empirical tuning and is not architecturally blocking.

3. **Should `TopicExtractor.extract()` receive the raw text or the already-linked text?** The raw text is preferable: it contains the original capitalization and phrasing before wiki-link brackets were inserted, which is cleaner input for regex and Claude.

4. **How should confidence scores from the heuristic pass be normalized alongside Claude-assigned scores?** Claude can return a ranked list (position = confidence proxy) or explicit scores if the prompt asks for them. Normalizing heuristic frequency-based scores against Claude-ranked ordinals requires a documented convention. Simplest approach: heuristic yields `0.5` for all LOCAL suggestions; Claude pass assigns `0.6–1.0` based on rank position, so AI-enhanced suggestions always surface above local-only ones unless dismissed.

5. **Should accepted stubs be created as empty pages or with a generated first block?** Requirements say "title-only" stubs, which means empty blocks list. The `GraphWriter.savePageInternal` safety check is bypassed for new pages (no existing file), so empty-blocks saves are safe. Confirm this assumption against `GraphWriter.kt` line ~172 before implementation.

---

## Recommendation

Implement **Option 1** (extraction inside `ImportService.scan()` as a new return field) with the progressive-enhancement pattern from Option 4 for the Claude API layer.

Concretely:

1. **Add `TopicSuggestion` domain model** and `TopicExtractor` pure-function object in `domain/`. `TopicExtractor.extract(rawText, existingNames)` uses a two-pass heuristic: (a) capitalized multi-word n-grams (2–4 tokens) filtered against `existingNames`, (b) high-frequency single-word terms with length ≥ 5 not in an extended stop-word list. Cap at 20 candidates; score by frequency × capitalization weight.

2. **Extend `ScanResult`** with `topicSuggestions: List<TopicSuggestion> = emptyList()`. Update `ImportService.scan()` to accept `existingNames: Set<String> = emptySet()` and call `TopicExtractor.extract()` internally. `ImportViewModel.runScan()` passes `matcherFlow._canonicalNames.keys` as the existing-names set.

3. **Define `TopicEnricher` interface** in `domain/` (no I/O; takes `rawText` and `localSuggestions`, returns `List<TopicSuggestion>`). Provide a `NoOpTopicEnricher` default that returns the local list unchanged. Wire a `ClaudeTopicEnricher` implementation only when an API key is configured; inject it into `ImportViewModel` at construction.

4. **Extend `ImportState`** with the fields defined above. In `runScan()`, after the local result is applied, launch a second coroutine that calls `topicEnricher.enhance()` and merges results into state (with the raw-text hash guard from ADR-002 prior art).

5. **Extend `confirmImport()`** to save accepted stub pages before the import page. Stub creation: `pageSaver.save(Page(name = suggestion.term, ...), emptyList(), graphPath)`.

6. **Add `ImportService.insertWikiLinks(text, terms)`** pure helper that rewrites `text` to wrap each occurrence of an accepted `term` (not already inside `[[…]]`) as `[[term]]`. Call from `onSuggestionAccepted()` to update the preview immediately.

This keeps `ImportService` and `TopicExtractor` as pure functions, confines all I/O to the ViewModel and the `TopicEnricher` implementation, and allows progressive enhancement without blocking the review UI.

---

## Pending Web Searches

- "Anthropic Claude API KMP ktor integration example" — verify whether a working KMP ktor-based client for the Anthropic Messages API exists as a library or requires custom implementation. [TRAINING_ONLY — search needed]
- "Kotlin Multiplatform regex performance iOS" — confirm regex noun-phrase extraction is within 500 ms budget on iOS Simulator for 10 KB inputs. [TRAINING_ONLY — search needed]
- "Anthropic API rate limits tier 1 2026" — verify current per-minute token limits and latency for short prompts. [TRAINING_ONLY — search needed]

## Web Search Results

**Rate limits** (searched 2026-04-17): Specific Tier 1 numerical values not returned by search. Official page is https://docs.anthropic.com/en/api/rate-limits — check before implementation. Key architectural implication: rate limits are continuous token-bucket replenishment (not hard per-minute reset). Only uncached input tokens count toward ITPM limits. For a single-user desktop app with occasional imports this is unlikely to be a practical constraint.

**Current model IDs** (from stack.md web search): Use `claude-haiku-4-5-20251001` for the topic enrichment call — fast, cheap, short context. Use `anthropic-version: 2023-06-01` header (current required value). Retired models (`claude-3-7-sonnet-20250219`, `claude-3-5-haiku-20241022`) must not be used — they return errors.
