# ADR-003: ImportService as Pure Domain Function Reusing AhoCorasickMatcher

**Status**: Accepted
**Date**: 2026-04-14
**Deciders**: Tyler Stapler

---

## Context

The core task of the import feature — scanning raw text for existing page names and inserting `[[wiki links]]` — requires a matching algorithm. SteleKit already has `AhoCorasickMatcher` (O(n) multi-pattern search with word-boundary enforcement) and `PageNameIndex` (a `StateFlow<AhoCorasickMatcher?>` that rebuilds the trie when the page set changes). These are in production use for the unlinked-references feature.

The design question is where to place the link-insertion logic and how to wire it with the existing primitives.

Two options were evaluated:

**Option A — `ImportService` as a pure `object` with a single `scan()` function**: The service is a pure function: `scan(rawText: String, matcher: AhoCorasickMatcher, maxSuggestions: Int): ImportResult`. It calls `matcher.findAll(rawText)`, normalizes CRLF, caps results, rewrites the text with `[[canonicalName]]` substitutions, and returns `ImportResult(linkedText, matchedPageNames)`. It has no coroutine scope, no repository access, and no state.

**Option B — Import logic embedded in `ImportViewModel`**: The scan loop runs inline inside the ViewModel's coroutine, without a dedicated service class. Fewer files, but the logic is not independently testable.

**Option C — Extend `StelekitViewModel` with an `importTextAsPage()` method**: Adding import to the existing 1 100-line ViewModel. Maximum code reuse via proximity to existing primitives, but degrades separation of concerns and makes the method hard to test in isolation.

---

## Decision

**Option A: `ImportService` as a pure `object` with a stateless `scan()` function.**

```kotlin
// kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ImportService.kt
object ImportService {
    fun scan(
        rawText: String,
        matcher: AhoCorasickMatcher,
        maxSuggestions: Int = 50
    ): ImportResult
}

data class ImportResult(
    val linkedText: String,
    val matchedPageNames: List<String>
)
```

---

## Rationale

1. **Maximum testability**: A pure function with no dependencies can be tested without any ViewModel, coroutine, or repository setup. `ImportServiceTest` constructs a real `AhoCorasickMatcher` from a small page name list and calls `ImportService.scan()` directly. This is the same pattern as `ExportService` in the export feature.

2. **Reuse without modification**: `AhoCorasickMatcher.findAll()` already enforces word boundaries, handles overlapping patterns, and returns `List<MatchSpan>` with canonical names. `ImportService` consumes it as-is; no changes to the matcher are required.

3. **Clean separation**: The `ImportViewModel` is responsible for state management, debouncing, and persistence coordination. `ImportService` is responsible solely for text transformation. This matches the established `ExportService` precedent where the domain service is a pure computation and the ViewModel orchestrates async concerns.

4. **Background dispatch stays in the ViewModel**: `ImportService.scan()` is synchronous and runs wherever it is called. The ViewModel is responsible for dispatching it to `Dispatchers.Default` via `withContext`. This keeps the service free of coroutine coupling while ensuring it never runs on the main thread.

5. **Reject Option B**: Embedding the scan loop in the ViewModel makes the text-transformation logic impossible to test without constructing a full ViewModel with its repository dependencies. It also makes the logic harder to read, since state mutation and computation are interleaved.

6. **Reject Option C**: `StelekitViewModel` is already 1 100+ lines. Adding import logic there compounds the existing single-responsibility violation. `ExportService` was deliberately extracted as a separate service for the same reason.

---

## Consequences

**Positive**:
- `ImportServiceTest` can be written entirely in `businessTest` with no mocking framework
- The scan function is reusable from any call site (future: background indexing, CLI tools)
- `ImportResult` is a simple data class with no platform dependencies, usable in `commonMain` tests

**Negative / Risks**:
- An additional file is created (`ImportService.kt`) versus embedding logic in the ViewModel
- The `maxSuggestions` cap is a parameter rather than a graph-level setting — callers must pass it consistently. For v1, the ViewModel hard-codes 50; a graph settings integration is deferred to v2
