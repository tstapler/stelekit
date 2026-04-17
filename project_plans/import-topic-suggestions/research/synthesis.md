# Research Synthesis: Import Topic Suggestions

**Date**: 2026-04-17
**Decision Required**: How to implement noun-phrase/concept extraction for new-page suggestions during import, including heuristic extraction, Claude API opt-in, and the architectural extension point for future plugin support.

---

## Context

SteleKit's import feature (ADR-005 deferral) detects links to *existing* pages but has no mechanism for suggesting new pages. This feature adds a hybrid detection layer: local heuristics run synchronously within the review stage, with optional Claude API enrichment arriving after the review UI is already visible. The user explicitly requested that the architecture expose a plugin API so third parties can implement custom topic detection strategies.

---

## Options Considered

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| Full NLP pipeline (Stanford NLP, OpenNLP) | Trained POS tagger + chunker | Ruled out — requires bundled model files, JVM-only |
| Pure-Kotlin regex heuristics only | Regex + frequency + stoplist, no external deps | Fast, offline, acceptable precision at 50–65% after filtering |
| Claude API only (no local pass) | All extraction via LLM | Violates offline-first and API-cost constraints |
| Hybrid: local heuristics + optional Claude | Local pass runs first; Claude enriches if key configured | Recommended — satisfies all constraints |
| Third-party plugin (custom implementation) | `TopicEnricher` interface injected at construction | Enabled by the same interface used for Claude integration |

---

## Dominant Trade-off

**Local correctness vs. extraction quality.** The KMP constraint rules out all trained NLP libraries, which forces regex-based extraction with inherently higher false-positive rates (raw precision ~20–40%). The Claude API option produces near-human quality but introduces API cost, latency, and an internet dependency. The hybrid approach accepts lower-quality local results in exchange for zero runtime dependencies, while offering a quality upgrade path via the `TopicEnricher` interface.

The plugin architecture amplifies this: by designing `TopicEnricher` as a first-class interface from day one, the system can accommodate future implementations (third-party NLP libraries for JVM-only, on-device models when they exist, user-written custom extractors) without changing the ViewModel or domain layer.

---

## Recommendation

**Choose**: Hybrid local heuristics + `TopicEnricher` interface for Claude (and plugins)

**Because**: The pure-Kotlin heuristic approach (Options A+B+C+E from the stack research) satisfies the <500ms and no-binary constraints with 50–65% precision after score-based filtering and a structural stoplist. The `TopicEnricher` interface is a zero-cost abstraction over the Claude API client, satisfying the opt-in / zero-cost-by-default requirement and simultaneously acting as the plugin extension point the user requested. No third-party NLP library has shipped a KMP-compatible version as of the research date (verified via web search).

**Accept these costs**:
- Local-only precision is 50–65%; some false positives will appear in the suggestion tray on every import
- Adding Claude support requires two small ktor artifacts (`ktor-client-content-negotiation:3.1.3` and `ktor-serialization-kotlinx-json:3.1.3`) in `commonMain` (if not already transitive via coil)
- The `TopicEnricher` interface is a seam, not a full plugin registry — registering and discovering third-party implementations requires a future plugin system design (out of scope for v1)

**Reject these alternatives**:
- Full NLP pipeline: ruled out by KMP + binary size constraints; no KMP-compatible library exists
- Claude-only: violates local-first and zero-API-cost constraints
- ViewModel-only logic (Option 3): untestable, non-reusable, not suitable for plugin extension point

---

## Concrete Architecture

### Domain layer

```kotlin
// domain/TopicSuggestion.kt
data class TopicSuggestion(
    val term: String,
    val confidence: Float,          // 0.0–1.0 from local scoring
    val source: Source,             // LOCAL | AI_ENHANCED
    val accepted: Boolean = false,
    val dismissed: Boolean = false,
) {
    enum class Source { LOCAL, AI_ENHANCED }
}

// domain/TopicExtractor.kt  (pure Kotlin object, no deps)
object TopicExtractor {
    fun extract(rawText: String, existingNames: Set<String>): List<TopicSuggestion>
    // Uses: regex capitalized n-grams (2–4 tokens), frequency × capitalization × length scoring,
    //       structural stoplist (broader than PageNameIndex.DEFAULT_STOPWORDS),
    //       novel-only filter against existingNames,
    //       caps at 15 candidates sorted by score desc
}

// domain/TopicEnricher.kt  ← PLUGIN INTERFACE
interface TopicEnricher {
    suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>): List<TopicSuggestion>
}
class NoOpTopicEnricher : TopicEnricher {
    override suspend fun enhance(...) = localSuggestions
}
```

### ImportService changes

```kotlin
// Extend ImportResult:
data class ImportResult(
    val linkedText: String,
    val matchedPageNames: List<String>,
    val topicSuggestions: List<TopicSuggestion> = emptyList(),  // NEW
)

// scan() gains existingNames param (default empty for backward compat):
fun scan(rawText: String, matcher: AhoCorasickMatcher,
         existingNames: Set<String> = emptySet()): ImportResult

// New pure helper — rewrite text to wrap accepted terms as [[term]]:
fun insertWikiLinks(text: String, acceptedTerms: List<String>): String
```

### ViewModel injection

```kotlin
class ImportViewModel(
    private val pageSaver: PageSaver,
    private val pageRepository: PageRepository,
    private val urlFetcher: UrlFetcher = NoOpUrlFetcher(),
    private val topicEnricher: TopicEnricher = NoOpTopicEnricher(),  // NEW — PLUGIN POINT
    private val scanDispatcher: CoroutineDispatcher = Dispatchers.Default,
) { ... }
```

### Async flow

```
runScan() {
    1. ImportService.scan(rawText, matcher, existingNames)  // <500ms, sets linkedText + topicSuggestions
    2. updateState(scanResult)                              // UI shows local suggestions immediately
    3. if (topicEnricher is not NoOp) {
         launch {
             try {
                 withTimeout(8_000) {
                     val enriched = topicEnricher.enhance(rawText, state.topicSuggestions)
                     mergeEnrichedSuggestions(enriched)    // merge, not replace; never re-show dismissed
                 }
             } catch (e: TimeoutCancellationException) { setClaudeStatus(Failed.Timeout) }
             catch (e: Exception) { setClaudeStatus(Failed.Error) }
         }
       }
}
```

### Claude implementation

```kotlin
// Claude API constants (VERIFIED via web search 2026-04-17):
// anthropic-version header: "2023-06-01"
// Model for topic extraction: "claude-haiku-4-5-20251001" (fast, cheap, short context)
class ClaudeTopicEnricher(private val apiKey: String, private val httpClient: HttpClient) : TopicEnricher {
    // POST https://api.anthropic.com/v1/messages
    // Headers: x-api-key, anthropic-version: 2023-06-01, content-type: application/json
    // Prompt: send rawText + localSuggestions, ask Claude to re-rank and add up to 5 new concepts
    // Returns: merged List<TopicSuggestion> with source = AI_ENHANCED
}
```

### UX: Chip tray (ImportScreen review stage)

- **Placement**: Between the matched-pages chips row and the Confirm button
- **Each chip**: `[confidence dot] [term] [×]` — color-coded dot (green = high, yellow = medium)
- **Section header**: "Suggested new pages" + "Accept All" button
- **Accept All flow**: Confirmation dialog "Create N stub pages?" → OK → creates stubs
- **Cap**: Maximum 15 chips displayed; confidence threshold 0.2 minimum
- **Claude status**: "AI-enhanced" badge in header when enrichment arrives; "AI unavailable" if failed
- **No re-show**: Dismissed chips never reappear even if Claude rates them highly

### confirmImport() stub creation

```kotlin
// Before saving the import page:
acceptedSuggestions.forEach { suggestion ->
    val existing = pageRepository.getPageByName(suggestion.term).first()
    if (existing == null) {
        pageSaver.save(Page(name = suggestion.term), emptyList(), graphPath)
    }
    // If page now exists (race condition), just skip — the wiki link is still valid
}
// Then rewrite linkedText with accepted terms:
val finalText = ImportService.insertWikiLinks(importState.linkedText, acceptedTerms)
// Then save the import page with finalText
```

---

## Plugin Architecture Note

The user requested that this feature expose APIs for third-party plugin implementation. The `TopicEnricher` interface above is the v1 plugin extension point. It is intentionally minimal:

- **No plugin discovery/registry in v1** — plugins are injected at construction; the host app controls which implementation is active
- **Future work**: A `PluginRegistry` that auto-discovers `TopicEnricher` implementations (via `ServiceLoader` on JVM, or explicit registration) should be a separate MDD project (`stelekit-plugin-api`)
- **The interface design is stable**: `enhance(rawText, localSuggestions) → List<TopicSuggestion>` is the correct API surface; it gives plugins the full text and the local candidates, and lets them return any modified list. This surface should not change when the registry is added.

---

## Critical Constraints (from verified web searches)

| Claim | Verified Value |
|-------|---------------|
| `anthropic-version` header | `2023-06-01` |
| Recommended model for extraction | `claude-haiku-4-5-20251001` |
| Retired models to avoid | `claude-3-7-sonnet-20250219`, `claude-3-5-haiku-20241022` (return errors) |
| KMP NLP library available? | None found as of research date |

---

## Open Questions Before Committing

- [ ] Does `GraphWriter.deletePage` exist? — blocks undo snackbar design (pitfalls FM-6 mitigation)
- [ ] Does `coil-network-ktor3` transitively include `ktor-client-content-negotiation`? — if yes, one fewer explicit dependency
- [ ] Is `SecureStorage`/`UserPreferences` already in the codebase for storing the API key? — if not, must be designed before `ClaudeTopicEnricher` is wired in
- [ ] What is the full plugin architecture scope? — user requested plugin APIs broadly; `stelekit-plugin-api` should be started as a separate MDD project before or alongside this implementation

If open questions 1 and 3 are not confirmed via codebase grep, spike them before writing the ADR.

---

## Sources

- `project_plans/import-topic-suggestions/research/stack.md`
- `project_plans/import-topic-suggestions/research/features.md`
- `project_plans/import-topic-suggestions/research/architecture.md`
- `project_plans/import-topic-suggestions/research/pitfalls.md`
- Web: https://docs.anthropic.com/en/api/versioning
- Web: https://platform.claude.com/docs/en/about-claude/models/overview
