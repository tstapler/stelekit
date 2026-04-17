# Findings: Stack — Pure-Kotlin NLP + Claude API in KMP

## Summary

Topic/noun-phrase extraction can be delivered entirely in `commonMain` pure Kotlin with no bundled model files, no native binaries, and no new runtime dependencies beyond what is already on the classpath. Three heuristic layers combine to give acceptable extraction quality for PKM text (technical docs, research summaries, articles): regex-based capitalization / POS-approximation, TF-IDF-style frequency scoring, and stopword filtering. The existing `ksoup:0.2.6` and `kotlinx-serialization-json:1.10.0` libraries cover HTML stripping and JSON serialization. The already-present `ktor-client-core:3.1.3` (with platform engines already wired for JVM/Android/iOS) is sufficient to call the Anthropic Messages API; no unofficial KMP SDK is needed or recommended. The Claude API path is gated entirely by a user-supplied API key, satisfying the zero-cost-by-default constraint.

---

## Options Surveyed

### Local NLP Approaches

#### Option A — Regex heuristics (capitalization + boundary patterns)
Extract candidates by matching sequences of capitalized words and known technical-term patterns using `Regex` in `commonMain`. Extend with a curated stopword list to prune false positives (articles, conjunctions, common verbs). Score by raw frequency in the imported text.

**Candidate shape**: `[A-Z][a-z]+(\s[A-Z][a-z]+)*` (title-case noun phrases) plus `[A-Z]{2,}` (acronyms) plus domain-aware patterns (`[A-Z][a-zA-Z0-9]*[A-Z][a-z]+` for camelCase proper nouns like `TensorFlow`).

#### Option B — Frequency analysis (TF-IDF approximation)
Tokenize text into words/bigrams/trigrams, compute within-document term frequency, then down-weight against a small built-in common-word corpus (the ~500 most frequent English words). Candidates with high relative frequency and low common-word overlap are ranked higher.

No external corpus file needed — the stopword list (~200–500 words) is embedded as a `Set<String>` constant in source. [TRAINING_ONLY — verify size is within the "pure Kotlin, no model files" constraint intent]

#### Option C — ksoup-based structure extraction
When import source is HTML (URL import path), `ksoup` already parses the DOM. `<h1>`–`<h3>` content, `<strong>` / `<em>` spans, and `<code>` blocks are structurally emphasized and reliably topic-bearing. Extracting these before plain-text heuristics runs gives a high-precision seed list at near-zero cost.

This is a zero-overhead option since `ksoup:0.2.6` is already a `commonMain` dependency.

#### Option D — Simple POS approximation (bigram/trigram chunking)
Without a POS tagger model, approximate noun phrases by: (1) split on sentence-ending punctuation, (2) strip leading verbs using a hardcoded list of ~50 common English verb forms, (3) greedily take 1–3 consecutive capitalized/content tokens. This is a degenerate form of the chunking step in classical NLP pipelines. Accuracy on technical text is moderate; false positives are common for sentence-initial capitalized words.

#### Option E — Full POS/NER library (ruled out)
Libraries like Stanford NLP, spaCy, and OpenNLP require: (a) bundled model files (100 MB+), (b) JVM-only APIs, (c) native binaries. All three violate the "no bundled ML model files, no native binaries" hard constraint. Ruled out.

There is no KMP-compatible pure-Kotlin NLP library with POS tagging as of early 2026. [TRAINING_ONLY — verify no new library has appeared in 2025]

#### Option F — Apache Lucene / Elasticsearch analyzers (ruled out)
Lucene's `StandardAnalyzer` and related token filters run on JVM only, cannot be used in `commonMain`, and would add a large JVM-only dependency. Ruled out.

### Claude API Approaches

#### Option G — Direct ktor HTTP call to Anthropic Messages API
Anthropic publishes a REST API (`POST https://api.anthropic.com/v1/messages`). The request body is JSON; the response is JSON. `ktor-client-core:3.1.3` (already in `commonMain`) plus `ktor-client-content-negotiation` + `ktor-serialization-kotlinx-json` (both available as KMP artifacts from the same 3.1.3 release) handle serialization. Platform HTTP engines are already wired: `ktor-client-okhttp` for JVM/Android, `ktor-client-darwin` for iOS. A single `ClaudeApiClient` object in `commonMain` calls the API with a prompt containing the heuristic candidate list and the raw import text, asking Claude to rank, filter, and add missing candidates.

Dependencies to add to `commonMain`:
- `io.ktor:ktor-client-content-negotiation:3.1.3`
- `io.ktor:ktor-serialization-kotlinx-json:3.1.3`

Both are already-available KMP artifacts; `kotlinx-serialization-json:1.10.0` is already present.

Required Anthropic API headers: `x-api-key`, `anthropic-version: 2023-06-01`, `Content-Type: application/json`. [TRAINING_ONLY — verify `anthropic-version` string for current API]

#### Option H — Unofficial KMP Anthropic SDK (e.g. `anthropic-sdk-java` unofficial KMP port)
As of early 2026, Anthropic has no official KMP SDK. Community ports exist but are unmaintained, have no release cadence guarantees, and would add an untrusted third-party artifact to the dependency graph. Using a raw ktor call (Option G) gives full control over the request shape and API version header without opaque transitive dependencies.

#### Option I — Other LLM APIs (OpenAI, Gemini, local Ollama)
Requirements do not mention these. The feature plan is Claude-API-specific. OpenAI and Gemini APIs would work technically (same ktor pattern) but are out of scope. Ollama/local LLM requires a running server on the user's machine — incompatible with iOS/Android and the "no native binaries" constraint.

---

## Trade-off Matrix

| Option | KMP compatibility | Binary size impact | Extraction quality | Offline capability | Notes |
|--------|------------------|-------------------|-------------------|-------------------|-------|
| A — Regex heuristics | Full (commonMain) | Negligible (~5 KB source) | Moderate; good for proper nouns and acronyms; misses complex noun phrases | Full | Best for single-word proper nouns and acronyms |
| B — TF-IDF frequency | Full (commonMain) | Negligible (stopword set ~10 KB) | Moderate; rewards rare technical terms; penalizes common words | Full | Complements A well; adds ranking signal |
| C — ksoup structure | Full (commonMain, already in deps) | Zero (already on classpath) | High precision for HTML imports; irrelevant for plain-text paste | Full | Free win for URL imports |
| D — POS approximation | Full (commonMain) | Negligible | Low–moderate; high false-positive rate for sentence-initial words | Full | Adds marginal quality over A+B; consider as tie-breaker only |
| E — Full NLP library | JVM-only | 100–500 MB | High | Full | Hard constraint violation — ruled out |
| F — Lucene analyzers | JVM-only | 5–50 MB | High (for English) | Full | JVM-only — ruled out |
| G — ktor Claude API | Full (commonMain) | ~200 KB for content-negotiation artifacts | Very high (semantic understanding) | None (requires network + key) | Two small ktor deps needed; opt-in only |
| H — Unofficial KMP SDK | Uncertain | Unknown | Same as G | None | Untrusted artifact; worse than G |
| I — Other LLMs | Full (ktor pattern) | Same as G | Similar to G | Varies | Out of scope |

---

## Risk and Failure Modes

**Heuristic layer (A+B+C)**

- **False positives from sentence-initial capitalization**: The first word of every sentence is capitalized in English. Without POS context, "The" starting a sentence will not be caught by stopword filtering if it appears mid-text in a technical context. Mitigation: strip leading stopwords and apply minimum frequency threshold (>1 occurrence) or minimum phrase length (2+ tokens) before surfacing single-word candidates.
- **Camel-case splitting**: `TensorFlow`, `GraphQL`, `SQLDelight` will not match a naive `[A-Z][a-z]+` pattern. A secondary regex for camelCase split (`[A-Z][a-z0-9]+`) or whole-token matching for all-caps or mixed-case tokens is needed.
- **Performance on very large imports**: Regex with global flags on 10 KB text is well under 500ms on JVM (typically <5ms). Risk is negligible for the stated constraint.
- **Stopword list maintenance**: A hardcoded list covers common English but will have gaps for domain-specific non-topics ("using", "based", "approach"). This is a quality issue, not a correctness issue.

**Claude API layer (G)**

- **API key absent**: Must short-circuit to heuristic-only result with zero HTTP calls. A null/empty key check at the call site is sufficient.
- **Network latency**: Anthropic API round-trip from EU/US is typically 500ms–3s for a short prompt. The UI must show the heuristic results immediately and update when the API response arrives (progressive enhancement pattern). Blocking the review UI on the API response would violate the <500ms requirement for local detection.
- **Rate limiting (429)**: Anthropic enforces per-minute token limits. For a solo user this is unlikely to trigger, but the client should handle 429 with a user-visible "API limit reached" message and fall back to heuristic results, not crash.
- **API version mismatch**: The `anthropic-version` header must match a supported version. Anthropic announces deprecations with months of notice. Storing the version as a constant with a comment referencing the Anthropic changelog is sufficient. [TRAINING_ONLY — verify current supported version strings]
- **Cost surprises**: Each import scan with the API key configured sends the full import text to Claude. A 10 KB import is ~2,500 tokens. At Haiku pricing (~$0.25/MTok input as of 2025), this is ~$0.0006 per import. Not a material cost for a solo user, but the feature design must inform users that the API key enables paid calls.
- **iOS App Store review**: Sending user content to a third-party API may require disclosure in the App Store privacy nutrition label. This is a legal/compliance concern, not a technical one, but worth noting.

---

## Migration and Adoption Cost

**Adding ktor content-negotiation** requires two additional `commonMain` dependencies:
- `io.ktor:ktor-client-content-negotiation:3.1.3`
- `io.ktor:ktor-serialization-kotlinx-json:3.1.3`

Both are published to Maven Central as KMP artifacts. They share the same version pinning as the existing `ktor-client-core:3.1.3`. Binary size impact is approximately 150–250 KB across the `commonMain` jar [TRAINING_ONLY — verify exact sizes]. No platform-specific engine changes are needed; OkHttp (JVM/Android) and Darwin (iOS) are already wired.

The heuristic extractor is pure Kotlin with no new dependencies. It can be added as a single file in `commonMain` (e.g. `domain/TopicExtractor.kt`) with a companion test in `businessTest`.

The `ImportService.scan()` method currently returns `ScanResult`. Adding `topicSuggestions: List<TopicSuggestion>` to `ScanResult` is a non-breaking extension (default empty list). The Claude API call is a separate suspend function that can be called after `scan()` returns, keeping the synchronous heuristic path fast.

---

## Operational Concerns

- **API key storage**: The key must be stored in user preferences (expect a `Settings` abstraction already exists in the codebase or can be added in `platform/`), not in code or version control. On Desktop, the OS keychain or a local config file is appropriate. On Android, `EncryptedSharedPreferences` is standard. On iOS, `Keychain` is standard. A platform-expect/actual abstraction (`SecureStorage`) would keep the domain code clean.
- **No telemetry on API calls**: The feature must not log or report the user's API key or import text to any service other than Anthropic.
- **Prompt engineering**: The Claude prompt should instruct the model to return a structured JSON array of topic candidates with confidence scores. Using `kotlinx-serialization` to parse the response is straightforward. The prompt should instruct Claude to avoid markdown wrapping of the JSON to simplify parsing. [TRAINING_ONLY — verify Anthropic JSON mode / structured output availability for the target model]

---

## Prior Art and Lessons Learned

- **Logseq** (the upstream inspiration for SteleKit) added "unlinked references" detection in v0.6 using simple word-boundary regex matching — the same approach as Option A. They later added an AI-powered "Smart Search" using OpenAI embeddings, but this required a server-side component and an API key, exactly the same pattern as Option G.
- **Obsidian** surfaces "unlinked mentions" using exact-string matching (like the existing `AhoCorasickMatcher`) but has never shipped automatic noun-phrase extraction in the core product. Third-party plugins exist (e.g. `obsidian-text-extractor`, `nlp-inside-obsidian`) but require Node.js plugins — not portable to KMP.
- **Roam Research** uses an LLM-powered "AI assist" for page suggestions, gated behind a paid tier — identical architecture to the heuristic-first + optional-Claude design in this feature.
- **The existing `AhoCorasickMatcher`** in this codebase demonstrates that the project is already comfortable with custom pure-Kotlin string-algorithm implementations in `commonMain`. The same confidence applies to a regex+frequency extractor.
- **Ktor ktor-client-content-negotiation** is already used indirectly (Coil 3's `coil-network-ktor3` uses ktor internally), so the dependency is likely already transitively present in the resolution graph [TRAINING_ONLY — verify if content-negotiation artifact is already transitive].

---

## Open Questions

1. Does `coil-network-ktor3:3.2.0` transitively pull `ktor-client-content-negotiation` into the resolution graph, or must it be declared explicitly? If transitive, it can be used without adding a new dependency line (though explicit declaration is still best practice).
2. What Anthropic model should be targeted for the Claude API call? `claude-haiku-3-5` (fast, cheap) or `claude-sonnet-4` (better quality)? The prompt is short, so Haiku is likely sufficient. Should this be user-configurable?
3. Should the Claude API call be cancellable (e.g. if the user dismisses the import dialog before the response arrives)? Ktor's `HttpClient` calls are coroutine-based and cancellable via `CoroutineScope` cancellation, so this is technically straightforward but must be wired into the ViewModel lifecycle.
4. Is there a `SecureStorage` or `UserPreferences` abstraction already in the codebase for storing the API key? If not, one must be designed as part of this feature or a prerequisite.
5. For iOS, does the Darwin ktor engine support the TLS configuration required by `api.anthropic.com`? (Expected: yes, standard TLS; [TRAINING_ONLY — verify no pinning issues])

---

## Recommendation

**Primary approach: Options A + B + C combined, with Option G as the opt-in enhancement.**

1. Implement a `TopicExtractor` in `commonMain` (pure Kotlin, no new deps) that:
   - Strips HTML via `ksoup` if the input came from a URL import (Option C)
   - Applies regex matching for title-case noun phrases, acronyms, and camelCase technical terms (Option A)
   - Scores candidates by in-document term frequency normalized against a ~300-word embedded stopword set (Option B)
   - Filters out candidates already in `PageNameIndex`
   - Returns `List<TopicSuggestion>` sorted by score descending

2. Add a `ClaudeTopicEnhancer` suspend function in `commonMain` that:
   - Returns immediately (no-op) if no API key is configured
   - POSTs a compact JSON prompt to `https://api.anthropic.com/v1/messages` via `ktor-client-core` + `ktor-client-content-negotiation` + `ktor-serialization-kotlinx-json`
   - Merges the Claude response into the heuristic candidate list, updating confidence scores and adding net-new candidates Claude identified
   - Handles 429, network errors, and JSON parse failures gracefully (log and return heuristic results)

3. Wire both into `ImportService` or a new `TopicSuggestionService` that `ImportViewModel` calls:
   - `scan()` (existing, fast, synchronous) runs first
   - `extractTopics()` (new, heuristic, <500ms) runs immediately after
   - `enhanceWithClaude()` (new, suspend, ~1–3s) runs concurrently if key present; updates UI via StateFlow when complete

4. Add `ktor-client-content-negotiation:3.1.3` and `ktor-serialization-kotlinx-json:3.1.3` to `commonMain` dependencies (if not already transitively present).

Do not add Option D (POS approximation) unless quality testing shows unacceptable false-positive rates from A+B alone. It adds code complexity for marginal quality gain on typical PKM text.

---

## Pending Web Searches

The following searches should be run to verify training-knowledge claims before finalizing the plan:

1. `site:search.maven.org "ktor-client-content-negotiation" "3.1.3" kotlin multiplatform` — verify KMP artifact publication for 3.1.3
2. `site:search.maven.org "ktor-serialization-kotlinx-json" "3.1.3"` — verify KMP publication
3. `anthropic API version header "anthropic-version" 2024 2025 supported values` — verify current supported `anthropic-version` header strings ✅ verified below
4. `anthropic claude haiku model id 2025 latest` — verify current fast/cheap model ID for the API call ✅ verified below
5. `coil3 "coil-network-ktor3" transitive dependencies ktor-client-content-negotiation` — verify whether content-negotiation is already transitive
6. `kotlin multiplatform NLP library noun phrase extraction 2025` — verify no new KMP-compatible NLP library has shipped since training cutoff
7. `anthropic messages API JSON mode structured output 2025` — verify JSON output mode availability and prompt pattern
8. `ktor client darwin iOS TLS anthropic api.anthropic.com` — verify no certificate pinning or TLS issues with the Darwin engine

## Web Search Results

**Item 3 — `anthropic-version` header** (searched 2026-04-17):
- Verified: `anthropic-version: 2023-06-01` is the current required value. All official Anthropic examples use this string. Previous versions deprecated.
- Sources: https://docs.anthropic.com/en/api/versioning, https://platform.claude.com/docs/en/api/versioning

**Item 4 — Current Claude model IDs** (searched 2026-04-17):
- Fast/cheap model: `claude-haiku-4-5-20251001` (Haiku 4.5 — fastest, near-frontier)
- Default/balanced: `claude-sonnet-4-6` (Sonnet 4.6 — frontier at scale)
- Best quality: `claude-opus-4-6` (Opus 4.6 — most capable)
- Note: `claude-3-7-sonnet-20250219` and `claude-3-5-haiku-20241022` are **retired** and return errors — do not use.
- Recommendation for topic extraction: `claude-haiku-4-5-20251001` — short prompt, cost-sensitive, speed matters
- Sources: https://platform.claude.com/docs/en/about-claude/models/overview
