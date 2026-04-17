# ADR-005: Page Name Index and Matching Strategy

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler  
**Feature**: Page Term Highlighting

---

## Context

The page-term-highlighting feature requires checking whether any plain-text substring of a block matches an existing page name. A naive approach — iterating every page name and calling `String.contains()` for each one — is O(n × m) where n is the block length and m is the number of page names. For a 5 000-page graph with 20 visible blocks, this is 100 000 string scans per render. That is unacceptable.

Three alternative matching strategies were evaluated:

| Strategy | Build cost | Match cost | KMP compatible | Notes |
|---|---|---|---|---|
| Naive `String.contains` per page | None | O(n × m) — ~100 ms for 5 000 pages × 20 blocks | Yes | Clearly too slow |
| Regex alternation `(page1\|page2\|...)` | O(m²) worst case | O(n × m) — backtracking | Yes | Compile time unbounded; backtracking risk |
| Aho-Corasick automaton | O(sum of pattern lengths) | O(n + k) where k = match count | Yes (pure Kotlin) | Best asymptotic; standard for multi-pattern search |
| SQLite FTS5 query per block (server-side) | None (index already exists) | DB round-trip per block | Yes | Latency dominated by IPC; cannot run synchronously in Compose |

Aho-Corasick is the established solution for simultaneous multi-pattern string search (Aho & Corasick, 1975). It is used in antivirus scanners, grep tools, and network intrusion detection for exactly this class of problem. The pure-Kotlin implementation is ~200 lines and carries no external dependency, satisfying the KMP `commonMain` constraint.

The index itself must be reactive. `PageRepository.getAllPages()` returns a `Flow` that SQLDelight already re-emits on mutations — wrapping it in a `PageNameIndex` class that collects this flow and rebuilds the automaton on change gives us free reactivity without polling.

---

## Decision

Use **Aho-Corasick** as the multi-pattern matching algorithm, implemented in pure Kotlin in `commonMain`.

Introduce `PageNameIndex` as a coroutine-driven wrapper around `PageRepository.getAllPages()` that:
1. Maintains a `Map<String, String>` (lowercase name → canonical name) in a `MutableStateFlow`.
2. Rebuilds an `AhoCorasickMatcher` whenever the page list changes and exposes it via a `StateFlow<AhoCorasickMatcher?>`.
3. Filters out page names shorter than 3 characters to reduce noise.
4. Optionally filters journal pages (configurable, default `true`) to prevent date-string over-matching.

The `AhoCorasickMatcher` is constructed once per page-list change (not per block render) and shared across all blocks visible on screen via a single `collectAsState()` call at the screen level.

---

## Consequences

**Positive**:
- O(n + k) matching per block — imperceptible latency for typical block lengths.
- Reactive: index updates automatically when pages are added, renamed, or deleted.
- No external dependencies; no platform code.
- The `AhoCorasickMatcher` is an immutable value after construction — safe to share across coroutines and across recompositions.

**Negative / Trade-offs**:
- The automaton must be rebuilt when the page set changes. For a 5 000-name index this takes ~5–10 ms on JVM. This is acceptable because page-set changes are infrequent (user creates/renames a page) and the rebuild happens on a background coroutine.
- A pure-Kotlin Aho-Corasick implementation adds ~200 lines of non-trivial algorithmic code that must be maintained and tested. The test suite (`AhoCorasickMatcherTest`) mitigates this.
- Memory: the trie for 5 000 names averaging 15 characters each is roughly 75 000 `HashMap<Char, Int>` entries — approximately 1–2 MB. This is acceptable for the target platforms.

**Rejected alternatives**:
- SQLite FTS5 per-block query: too slow (round-trip per block during render), and FTS5 is tokenizer-based — it cannot reliably find multi-word page names as substrings of larger sentences.
- Regex alternation: build time is O(m²) in the worst case and runtime involves backtracking; correctness is harder to reason about for arbitrary Unicode page names.

---

## Implementation Notes

- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/PageNameIndex.kt`
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcher.kt`
- Test: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/PageNameIndexTest.kt`
- Test: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcherTest.kt`
