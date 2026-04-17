# Requirements: Import Topic Suggestions

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-16

## Problem Statement

When importing text into SteleKit, the existing feature only surfaces connections to pages that already exist in the graph. Novel concepts and terminology present in imported content go undetected, requiring the user to manually read through every import and decide which new terms deserve a page. This eliminates much of the speed benefit of the import feature and leaves knowledge graph gaps — the graph only grows when the user explicitly creates pages.

Primary user: solo knowledge worker importing summaries, articles, and research notes.

## Success Criteria

- After importing text, a "Suggested new pages" section appears in the review stage listing candidate topics not already in the graph
- Each suggestion shows a confidence indicator so the user can quickly judge page-worthiness
- One-click accept creates a stub page and retroactively adds `[[wiki link]]` in the imported content
- Bulk-accept option creates multiple stubs at once
- Local heuristics complete in <500ms on a 10 KB import (review UI stays responsive)
- Claude API enhancement (when key configured) produces noticeably higher-quality suggestions with semantic understanding

## Scope

### Must Have (MoSCoW)
- **Noun-phrase extraction**: detect multi-word noun phrases (e.g. "machine learning", "vector database") as page candidates
- **Single-word concept detection**: detect meaningful proper nouns and technical terms (e.g. "Kubernetes", "TensorFlow")
- **Confidence scoring**: rank candidates by likelihood of being page-worthy (frequency, capitalization, POS context, novelty vs. existing index)
- **Deduplication against existing pages**: suggestions must exclude terms already in `PageNameIndex` (no false suggestions for existing pages)
- **One-click stub creation**: accept a suggestion → create stub page via `GraphWriter` + insert `[[wiki link]]` in the import content
- **Bulk accept**: accept multiple suggestions at once before confirming the import
- **Local-first**: heuristic detection works with no API key
- **Claude API enhancement (opt-in)**: when API key is configured, Claude is called to rank/filter/augment the local candidates

### Out of Scope (v1)
- Automatic page creation without user review (no silent stub creation)
- LLM-generated page content (stubs are empty or title-only)
- Cross-import learning (not tracking which suggestion types a user tends to accept)
- Entity linking to external knowledge bases (Wikipedia, Wikidata)
- Full NLP pipeline with POS tagging requiring bundled models or native binaries

## Constraints

- **Tech stack**: Kotlin Multiplatform only — no new runtimes, no bundled ML model files
- **Binary size**: no large model files; heuristics must be pure Kotlin
- **Performance**: local detection pass <500ms on 10 KB input on JVM Desktop
- **API cost control**: Claude API strictly opt-in — zero API calls unless user has configured a key; no cost surprises

## Context

### Existing Work

The import feature (already implemented on branch `stelekit-import`) provides:

| Existing component | Relevance |
|-------------------|-----------|
| `ImportService.scan(rawText, matcher)` | Returns `ImportResult(linkedText, matchedPageNames)` — needs a new `topicSuggestions: List<TopicSuggestion>` field |
| `ImportResult` data class | Extend with suggestions list |
| `ImportScreen` review stage | Shows matched page chips; add "Suggested new pages" section with accept/dismiss per suggestion |
| `ImportViewModel.confirmImport()` | Must create accepted stub pages via `GraphWriter.savePage` before saving the import page |
| `PageNameIndex` / `AhoCorasickMatcher` | Suggestions must be filtered against the existing index to avoid suggesting already-existing pages |
| `GraphWriter.savePage` | Used to create stub pages on acceptance |

### Key Design Decision Already Deferred
ADR-005 from the import feature explicitly deferred this feature: "new-page suggestions require noun-phrase extraction or LLM step — undesigned for v1." This feature is the designed resolution of that deferral.

### Stakeholders
- Solo knowledge worker (primary)

## Research Dimensions Needed

- [ ] Stack — pure-Kotlin NLP heuristics for noun-phrase extraction without bundled models; Claude API client integration in KMP (Anthropic SDK, ktor-based)
- [ ] Features — how other PKM tools surface new-concept suggestions; quality comparison of heuristic vs. LLM-based extraction
- [ ] Architecture — where topic extraction fits in the existing `ImportService` pipeline; how to wire Claude API opt-in without polluting domain logic; suggestion state model in `ImportViewModel`
- [ ] Pitfalls — false positive rates for heuristic NLP on technical text; rate limiting and latency for Claude API calls; suggestion list overwhelming users (too many candidates)
