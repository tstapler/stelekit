# LLM-Assisted Tag Suggestion — Feature Research

**Date**: 2026-06-13
**Feature**: Auto-suggest tags (page links) for a block of text from the user's existing page vocabulary

---

## Codebase Baseline

The codebase already has a solid Tier 1 (local) matching foundation:

| Component | File | Role |
|---|---|---|
| `PageNameIndex` | `domain/PageNameIndex.kt` | Maintains a reactive Aho-Corasick matcher over `getPageNameEntries()` (names-only projection). Debounced rebuild at 500 ms. Excludes journal pages and stopwords. |
| `AhoCorasickMatcher` | `domain/AhoCorasickMatcher.kt` | O(text) multi-pattern scan. Supports stem variants with `reportedBaseLength`. Returns `MatchSpan(start, end, canonicalName)`. |
| `TopicExtractor` | `domain/TopicExtractor.kt` | Heuristic NLP extractor (capitalized phrases, CamelCase, sentence-position logic). Returns `List<TopicSuggestion>` with 0–1 confidence. Source = `LOCAL`. |
| `TopicSuggestion` | `domain/TopicSuggestion.kt` | `data class TopicSuggestion(term, confidence, source, accepted, dismissed)`. `Source` = `LOCAL \| AI_ENHANCED`. |
| `TopicEnricher` | `domain/TopicEnricher.kt` | `fun interface TopicEnricher { suspend fun enhance(rawText, localSuggestions) }`. `NoOpTopicEnricher` is the default. |
| `ClaudeTopicEnricher` | `domain/ClaudeTopicEnricher.kt` | Existing Claude Haiku integration — re-ranks `localSuggestions` and adds up to 5 net-new terms via a freeform text prompt. Uses Ktor + kotlinx.serialization. |

The tag-suggestion feature extends this existing pipeline. The `ClaudeTopicEnricher` currently receives `TopicSuggestion` (candidates from `TopicExtractor`) and does **not** receive the live page vocabulary. The new LLM tier for tag suggestion needs to pass the page vocabulary (constrained to existing page names) as an additional input so the model can suggest existing tags rather than invent new ones.

---

## 1. LLM API Comparison for Tag/Label Suggestion

### Claude API (Anthropic)

**Strengths for this use case:**
- Native tool-use / JSON-mode output eliminates post-processing parse failures
- `claude-haiku-4-5` is the cost/speed optimum for short, structured classification tasks (~$0.80/M input, ~$4/M output). Already used in `ClaudeTopicEnricher`.
- Extended thinking (Sonnet/Opus) unnecessary — tag suggestion is low-reasoning, high-throughput
- `claude-haiku-4-5` context window is 200K tokens; a 5000-tag vocabulary list fits comfortably

**Recommended model**: `claude-haiku-4-5-20251001` (current value in `ClaudeTopicEnricher.CLAUDE_MODEL`)

**Recommended parameters**:
```
model: claude-haiku-4-5-20251001
max_tokens: 256      # a JSON array of ~10 tag names is well under 256 tokens
temperature: 0       # classification task — determinism preferred
```

### OpenAI and OpenAI-Compatible Endpoints

**Why to support them (open question #2 from requirements.md)**:
- Users with existing OpenAI subscriptions avoid a second API key
- Self-hosted / local-inference endpoints (Ollama, vLLM, LM Studio, llama.cpp server) all expose the OpenAI `/v1/chat/completions` schema — one abstraction covers all of them
- Many enterprise deployments prefer Azure OpenAI or Vertex AI (Gemini) which are also OpenAI-schema-compatible

**Recommended model tier equivalents**:
| Provider | Equivalent to Haiku |
|---|---|
| OpenAI | `gpt-4o-mini` |
| Ollama (local) | `llama3.2:3b`, `gemma3:4b` |
| Groq | `llama-3.1-8b-instant` |

**Structural output for OpenAI-compatible**: Use `response_format: { "type": "json_object" }` (widely supported) or a `tools` call with a schema. The tool-use approach is more portable and works with Ollama and most local runtimes that mirror OpenAI's function-calling API.

### Recommendation

Support two provider shapes behind the `TopicEnricher` interface:

1. **`ClaudeTopicEnricher`** (already exists) — Anthropic Messages API with `x-api-key` + `anthropic-version` headers
2. **`OpenAiCompatibleTopicEnricher`** (new) — OpenAI Chat Completions schema with a configurable `baseUrl` and `apiKey`; covers OpenAI, Groq, Ollama, Azure, and any other endpoint

Both share identical prompt templates; only the HTTP wire format differs.

---

## 2. Prompt Engineering for Constrained Tag Suggestion

### Core problem: vocabulary-constrained classification

The model must choose only from existing page names (the user's tag vocabulary). This is a **closed-set classification** task, not open generation. The prompt must:

1. Provide the vocabulary list explicitly
2. Instruct the model to output only names from that list
3. Use structured output to eliminate hallucinated names

### Recommended prompt template (constrained vocabulary)

```
System: You are a tag-suggestion assistant for a personal knowledge graph.
Your job is to identify which of the provided page names are relevant to the given text.
RULES:
- Only suggest tags from the provided "Available tags" list.
- Do not invent new tag names or rephrase existing ones.
- Suggest 1–8 tags, ordered by relevance (most relevant first).
- If fewer than 3 tags are clearly relevant, suggest only the ones that are clearly relevant.
- Output a JSON object: {"tags": ["TagA", "TagB"]}
- No explanation. No markdown. Only the JSON object.

Available tags:
<tags>
{vocabulary_list}
</tags>

Text to tag:
<text>
{block_text}
</text>
```

**Why this structure:**
- `<tags>` / `<text>` XML delimiters prevent injection confusion and align with Claude's training on structured prompts
- "Only suggest tags from the provided list" placed near the top and reinforced by the JSON schema prevents the most common failure mode (hallucinated tags)
- `"tags": [...]` schema is simpler than asking for confidence scores when the vocabulary is constrained — the order acts as an implicit confidence ranking
- Asking for 1–8 tags with a lower bound escape ("only clearly relevant ones") avoids padding with weak suggestions

### Confidence scoring in constrained mode

When the output is a ranked list from a closed vocabulary, inject confidence after the fact rather than asking the model to estimate it:

```
Position 1 → confidence 0.95
Position 2 → confidence 0.85
Position n → confidence max(0.95 - (n-1) * 0.10, 0.50)
```

This is more reliable than asking a small model to produce calibrated probabilities, and avoids the extra tokens.

### Prompt variant: hybrid constrained + open (for open-question #1)

If the product decides to allow the LLM to propose new tag names not in the vocabulary, use a two-section output:

```json
{
  "from_vocabulary": ["ExistingTag1", "ExistingTag2"],
  "new_suggestions": ["PotentialNewTag"]
}
```

New suggestions get a fixed confidence cap (e.g. 0.60) and are always shown in the "low-confidence" suggestion UI, never auto-applied.

---

## 3. Handling a Large Tag Vocabulary

Graphs can have thousands of page names. Sending the full vocabulary in every prompt is expensive and may exceed practical prompt limits for small models on self-hosted endpoints.

### Strategy A: Pre-filter using local matching (recommended for MVP)

Run the Aho-Corasick and `TopicExtractor` passes first. Collect all candidate terms (direct matches + heuristic extractions). Build the vocabulary list for the LLM prompt from only the pages that were **not** matched locally but are semantically related — or send only the top-N local candidates as the vocabulary, asking the LLM to re-rank and fill gaps.

This is the approach `ClaudeTopicEnricher` already uses: it receives `localSuggestions` (already extracted candidates) rather than the full page list.

For tag suggestion (closed-vocabulary mode), adapt this to:
1. Aho-Corasick scan → exact matches → high-confidence, no LLM needed
2. `TopicExtractor` → heuristic candidates → send as the candidate vocabulary to LLM
3. Additionally include pages from semantic neighborhood: pages whose names share tokens with the block text (simple token overlap). This can be computed in O(V) locally before the API call.

### Strategy B: Chunked vocabulary batching

If the full vocabulary must be sent (e.g. for comprehensive coverage), partition into batches of 200–500 names and make parallel requests. Merge results by confidence. This is expensive and not recommended for real-time suggestion.

### Strategy C: Embedding-based pre-filtering (future / offline)

Pre-compute embeddings for all page names using a small local model or the same remote API's embedding endpoint. At suggestion time, embed the block text and retrieve the top-K nearest page names by cosine similarity. Send only those K names to the LLM.

**Not recommended for MVP** — adds complexity, requires embedding storage, and the simpler token-overlap pre-filter achieves 80% of the benefit.

### Practical vocabulary sizes

| Graph size | Vocabulary size | Tokens (est. at 2 tokens/name avg) | Fits in Haiku context? |
|---|---|---|---:|
| Small (< 500 pages) | ~400 non-journal pages | ~800 tokens | Yes |
| Medium (2 000 pages) | ~1 600 non-journal pages | ~3 200 tokens | Yes |
| Large (8 000 pages) | ~5 000 non-journal pages | ~10 000 tokens | Yes (200K limit) |

Even a 5 000-page vocabulary list fits comfortably in a single Haiku call. The pre-filter strategy (sending only candidates, not the full list) is still preferred for latency and cost — but falling back to full vocabulary is viable for Haiku.

---

## 4. Structured Output for Reliable Tag Extraction

### Claude API: tool use (preferred)

Tool use forces the model to produce a schema-validated response. Parser failures drop to near-zero.

```json
{
  "name": "suggest_tags",
  "description": "Return the relevant tags from the provided vocabulary",
  "input_schema": {
    "type": "object",
    "properties": {
      "tags": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Page names from the vocabulary, ordered by relevance"
      }
    },
    "required": ["tags"]
  }
}
```

Request: `tool_choice: { "type": "tool", "name": "suggest_tags" }` forces a tool call response.

This replaces the current freeform JSON parsing in `ClaudeTopicEnricher.parseResponse()`.

### OpenAI-compatible: structured output / response_format

For OpenAI `gpt-4o-mini` and compatible endpoints that support it:
```json
"response_format": { "type": "json_schema", "json_schema": { ... } }
```

For endpoints that only support `json_object` mode (Ollama, older vLLM):
```json
"response_format": { "type": "json_object" }
```

The prompt must still explicitly describe the schema when using `json_object` mode since the endpoint only guarantees valid JSON, not schema conformance.

### Fallback parsing

The existing `runCatching { lenientJson.decodeFromString(...) }.getOrElse { fallback }` pattern in `ClaudeTopicEnricher.parseResponse()` is a reasonable safety net for non-tool-use paths. When tool use is active, add a secondary check that the returned tag names are actually in the vocabulary list (substring match against `Set<String>`), since even tool-use output can contain casing variants or minor mutations.

---

## 5. Combining Local Matching with LLM Suggestions

### Recommended pipeline

```
Block text
    │
    ├─► AhoCorasickMatcher.scan(text)
    │       → List<MatchSpan>                        confidence = 1.0, source = LOCAL
    │
    ├─► TopicExtractor.extract(text, alreadyLinked)
    │       → List<TopicSuggestion>                  confidence = 0.2–1.0, source = LOCAL
    │
    └─► [if LLM configured] TopicEnricher.enhance(rawText, localCandidates, pageVocabulary)
            → List<TopicSuggestion>                  confidence = derived, source = AI_ENHANCED
```

**Merge rules:**
1. Exact `AhoCorasickMatcher` hits → confidence 1.0; auto-apply if `confidence >= AUTO_APPLY_THRESHOLD` (0.95 suggested)
2. LLM suggestions for terms already found by local matching → take the higher confidence of the two, keep `source = LOCAL` (since local confirmed it)
3. LLM-only suggestions (not found by local scan) → confidence from positional scoring, source = `AI_ENHANCED`, show in suggestion UI
4. Deduplicate by lowercased term name before presenting to the user

### Confidence thresholds

| Confidence | Action |
|---|---|
| ≥ 0.95 (exact Aho-Corasick hit) | Auto-apply — insert `[[PageName]]` into block content |
| 0.60–0.95 | Show in suggestion chip row; one-tap to accept |
| < 0.60 | Omit, or show only if the user opens an expanded list |

These are initial values; expose them as tunable settings.

---

## 6. How Similar Apps Implement Auto-Tagging

### Logseq (plugins)

- The community `logseq-plugin-link-hint` plugin does client-side substring matching of page names in block text — equivalent to the Aho-Corasick pass already in SteleKit.
- The `logseq-chatgpt-plugin` sends block text + a manually curated tag list to GPT-4 and pastes the result back. No structured output; relies on prompt formatting.
- No official auto-tag feature; all community solutions use freeform prompts and regex post-processing.

### Obsidian

- The "Natural Language Dates" plugin uses a simple regex approach for date-page linking.
- "Smart Connections" plugin: embeds all notes, finds semantic neighbors, suggests links. Uses OpenAI embeddings API. Expensive but thorough.
- No constrained-vocabulary LLM approach in popular plugins — most either do exact match or freeform LLM suggestions that require user review of every suggestion.

### Notion AI

- Proprietary. Uses a freeform "add tags" prompt that can propose new tags not in the workspace database. Constrained-vocabulary version is available in database views via AI autofill with a pre-set list of options.
- Architecture insight: Notion sends the property schema (with existing options) as context. This matches the constrained-vocabulary approach recommended above.

### Roam Research

- No native auto-tag. Community scripts scan for existing page names using simple `.includes()` substring matching — again, equivalent to Aho-Corasick but O(N×M).

**Key takeaway**: No existing tool combines the Aho-Corasick local exact-match with constrained-vocabulary LLM suggestion in a tiered pipeline. SteleKit's approach is more principled than what community plugins do.

---

## 7. Adapting `ClaudeTopicEnricher` for Tag Suggestion

The existing `ClaudeTopicEnricher` serves the import-time `TopicExtractor` workflow (suggest new page names to create). The tag-suggestion feature requires a **different mode**:

| Dimension | Existing `ClaudeTopicEnricher` (import) | New tag-suggestion mode |
|---|---|---|
| Vocabulary source | `localSuggestions` (extracted candidates) | `pageVocabulary` (all existing page names, pre-filtered) |
| Output constraint | May propose net-new concepts | Must only return names from vocabulary |
| Source field | `AI_ENHANCED` | `AI_ENHANCED` |
| Confidence | Model self-reported | Positional rank |
| Entry point | Import screen | Block editor, share widget, voice capture |

### Recommended interface extension

Add a second method to `TopicEnricher` (or create a new `TagSuggester` interface):

```kotlin
fun interface TagSuggester {
    /**
     * Suggest tags from [pageVocabulary] that are relevant to [blockText].
     * [localMatches] contains terms already identified by local matching —
     * they can be passed as hints but should not be re-suggested.
     */
    suspend fun suggestTags(
        blockText: String,
        pageVocabulary: List<String>,
        localMatches: Set<String>,
    ): List<TopicSuggestion>
}
```

The `ClaudeTopicEnricher` can implement both `TopicEnricher` and `TagSuggester`, or the tag suggestion path can be a separate class (`ClaudeTagSuggester`) to keep the two prompts independent.

---

## 8. Vocabulary Delivery to the LLM

### Context window budget example (Haiku, 200K limit)

```
System prompt:   ~300 tokens
Block text:      ~200 tokens (typical block)
Vocabulary list: ~3 200 tokens (1 600 page names × 2 tokens avg)
Output budget:   256 tokens
────────────────────────────────
Total:           ~3 956 tokens   ← well within 200K
```

At $0.80/M input tokens, a single tag suggestion call at 4K tokens costs ~$0.003 — negligible.

### Vocabulary formatting

Plain newline-separated list is the most token-efficient:
```
Architecture
Deep Learning
Kotlin Multiplatform
Meeting Notes
Project Alpha
...
```

Avoid JSON arrays for the vocabulary list — the brackets and quotes add ~30% token overhead.

### Pre-filter to reduce vocabulary size

Before sending, exclude from the vocabulary:
1. Pages already linked in the block (`[[PageName]]` present in content)
2. Journal pages (already excluded by `PageNameIndex.excludeJournalPages`)
3. Pages shorter than `PageNameIndex.MIN_NAME_LENGTH` (3 chars)
4. Stopwords (`PageNameIndex.DEFAULT_STOPWORDS`)
5. Pages whose names share no tokens with the block text (simple tokenization: split on whitespace + punctuation, check intersection)

Step 5 alone typically reduces a 5 000-page vocabulary to 50–200 candidates for any given block, making the prompt cost and latency equivalent to a small call regardless of graph size.

---

## 9. Example Prompt (Full, Production-Ready)

```
System:
You are a tag-suggestion assistant for a personal knowledge base.
Given a block of text and a list of page names from the user's knowledge graph,
identify which page names are topically relevant to the text.

RULES:
1. Only suggest tags from the "Available page names" list below.
2. Do not invent new names or alter the capitalisation of existing ones.
3. Return 0–8 suggestions, ordered by relevance (most relevant first).
4. If no page names are clearly relevant, return an empty array.
5. Return ONLY a JSON object: {"tags": ["Name1", "Name2"]}

Available page names:
Kotlin Multiplatform
Coroutines
Android
Jetpack Compose
SQLDelight
Arrow
GraphQL
Meeting Notes
Project Alpha
Deep Learning

User:
<text>
Spent the morning debugging a crash in the SQLDelight write actor on Android.
The issue was a missing withContext(DB) in the repository layer.
Fixed and covered with a new coroutine test.
</text>
```

Expected output:
```json
{"tags": ["SQLDelight", "Android", "Coroutines", "Kotlin Multiplatform"]}
```

---

## Summary of Recommendations

| Question | Recommendation |
|---|---|
| Which LLM API? | Claude Haiku 4.5 for Anthropic path; OpenAI-compatible schema for self-hosted / OpenAI path |
| Prompt style? | Constrained vocabulary list in `<tags>` block + tool use for structured output |
| Large vocabulary? | Token-overlap pre-filter reduces 5 000 pages to ~100–200 candidates before the API call |
| Structured output? | Claude tool use (`suggest_tags` tool); OpenAI `response_format` + fallback `runCatching` parse |
| Local + LLM combination? | Aho-Corasick exact hits at confidence 1.0 (auto-apply); LLM suggestions at positional confidence (show in UI) |
| Confidence scoring? | Position-based decay (0.95 → 0.85 → … → 0.50) rather than asking model to self-report |
| Interface change needed? | Add `TagSuggester` interface or extend `TopicEnricher` with `pageVocabulary` parameter |
