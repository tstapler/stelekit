# Research Plan: Import Topic Suggestions

**Date**: 2026-04-16
**Input**: `project_plans/import-topic-suggestions/requirements.md`

## Subtopics

### 1. Stack — Pure-Kotlin NLP + Claude API in KMP
**File**: `research/stack.md`
**Search cap**: 5 searches
**Key questions**:
- What pure-Kotlin / KMP-compatible NLP approaches exist for noun-phrase extraction without bundled models?
- How do you call the Anthropic Claude API from a Kotlin Multiplatform app (ktor-based client)?
- Are there lightweight KMP tokenizer or POS-tagging libraries that ship no native binaries?
**Axes for trade-off matrix**: KMP compatibility, binary size impact, extraction quality, API dependency, offline capability

### 2. Features — PKM Topic Suggestion UX
**File**: `research/features.md`
**Search cap**: 4 searches
**Key questions**:
- How do Logseq, Obsidian, Roam, Tana surface "new concept" suggestions during import/paste?
- What UX patterns work for "here are 8 suggested pages — which do you want?" without overwhelming users?
- How do tools distinguish signal (page-worthy) from noise (common words) in suggestions?
**Axes**: Suggestion quality, UX friction, false-positive handling, discoverability

### 3. Architecture — Integration with ImportService pipeline
**File**: `research/architecture.md`
**Search cap**: 3 searches
**Key questions**:
- Where in the existing ImportService/ImportViewModel pipeline should topic extraction run?
- How to keep Claude API opt-in without polluting pure domain logic?
- What is the right state model for "suggestions with per-item accept/dismiss" in ImportViewModel?
- How to handle async Claude API calls in the middle of the review stage?
**Axes**: Code reuse, separation of concerns, testability, async complexity

### 4. Pitfalls — False positives, latency, UX overload
**File**: `research/pitfalls.md`
**Search cap**: 3 searches
**Key questions**:
- What false-positive rates are typical for regex/frequency-based noun-phrase extraction on technical text?
- What are Claude API rate limits and typical latency for a short topic-extraction prompt?
- How do you prevent the suggestion list from overwhelming users (e.g. 30+ candidates)?
- What happens if the Claude API call fails mid-review — how to degrade gracefully?
**Axes**: Impact severity, likelihood, mitigation complexity

## Synthesis Target
`research/synthesis.md` — ADR-Ready summary for `/plan:adr`
