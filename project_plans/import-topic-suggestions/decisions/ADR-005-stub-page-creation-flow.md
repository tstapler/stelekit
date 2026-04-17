# ADR-005: Stub Page Creation Flow

**Date**: 2026-04-17
**Status**: Accepted

## Context

Accepting a topic suggestion must create an empty stub page and insert `[[wiki link]]` syntax into the imported content. This involves three sub-problems:

1. **Pre-accept race condition**: A suggestion may have been novel at scan time but the named page may now exist (another import, external file change, concurrent page creation).
2. **Wiki-link insertion**: The linked text already has Aho-Corasick-matched links. Accepted topic suggestions need their own `[[term]]` insertions — for occurrences in `linkedText` that are not already bracketed.
3. **Undo window**: Bulk-accept can create up to 10 stub pages in one gesture. A user who immediately regrets this needs a short window to undo all creations at once.

The existing `confirmImport()` in `ImportViewModel` already performs a pre-save collision check at line ~210 using `pageRepository.getPageByName(normalizedName).first()`. The same pattern must be applied to each stub. `GraphWriter.deletePage(page: Page)` exists and is confirmed functional (returns `Boolean`).

## Decision

**Pre-accept existence check**: Before creating each stub page in `confirmImport()`, call `pageRepository.getPageByName(suggestion.term).first().getOrNull()`. If a page already exists, skip creation silently — the `[[wiki link]]` insertion still proceeds because the page now exists and the link is valid. This mirrors the existing collision-guard pattern exactly.

**`insertWikiLinks` helper**: Add a pure static function `ImportService.insertWikiLinks(text: String, terms: List<String>): String`. It iterates over `terms` and wraps each plain-text occurrence of `term` (case-insensitive, word-boundary match, not already inside `[[…]]`) with `[[Term]]` using the display-form capitalization from `TopicSuggestion.term`. This function is called in `onSuggestionAccepted()` to update `linkedText` in state immediately (so the preview reflects the link before confirm), and again on the full set of accepted terms just before saving the import page to produce the final `finalText`.

**Stub creation order in `confirmImport()`**: Accepted stubs are created before the import page is saved. Order: (1) create all accepted stub pages via `pageSaver.save(stubPage, emptyList(), graphPath)`, (2) compute `finalText = ImportService.insertWikiLinks(importState.linkedText, acceptedTerms)`, (3) save the import page using `finalText` as block content source. This ordering ensures that if step 3 fails, the stubs still exist and the wiki links in the file will resolve when the import is retried.

**Undo snackbar**: After any acceptance event (single-chip or Accept All) that results in stub creation, `ImportViewModel` stores the list of created stub pages in a transient `undoBuffer: List<Page>` field in `ImportState`. The ViewModel exposes a `showUndoSnackbar: Boolean` flag that `ImportScreen` uses to show `"N page(s) created · Undo"` for 10 seconds. Clicking Undo calls `onUndoStubCreation()`, which:
  - Calls `pageSaver.deletePage(page)` for each stub (requires a `PageDeleter` seam, defined analogously to `PageSaver`).
  - Reverts `linkedText` to the pre-acceptance text stored in the undo buffer.
  - Clears `undoBuffer` and resets `showUndoSnackbar = false`.

Note: The undo reverts `linkedText` in the ViewModel's in-memory state. The undo snackbar is available only while the user is on the review screen; after `confirmImport()` completes, there is no undo path. This is the same limitation as the rest of the import flow.

**Bulk-accept cap**: `onAcceptAllSuggestions()` accepts at most 10 non-dismissed suggestions in a single gesture. If more than 10 are pending, the confirmation dialog message reads `"Create 10 stub pages? (N more can be accepted individually)"`.

**Stub page structure**: Each stub is `Page(name = suggestion.term, ...)` with `emptyList<Block>()`. No properties are set. UUIDs are generated via `UuidGenerator.generateV7()`. This matches the minimal stub structure implied by the requirements ("title-only").

**`PageDeleter` seam**: To enable undo without a direct `GraphWriter` dependency in tests, define a second functional interface:

```kotlin
fun interface PageDeleter {
    suspend fun delete(page: Page): Boolean
    companion object {
        fun from(writer: GraphWriter): PageDeleter = PageDeleter { page -> writer.deletePage(page) }
    }
}
```

Inject into `ImportViewModel` as `pageDeleter: PageDeleter = NoOpPageDeleter()`, where `NoOpPageDeleter` returns `false` and logs a warning.

## Rationale

**Pre-accept existence check at `confirmImport()` time, not at `onSuggestionAccepted()` time.** The accept action updates in-memory state synchronously. The actual file write happens only at `confirmImport()`. Checking existence at that point — when all writes are about to occur — is the latest safe moment, minimizing the TOCTOU window without adding async operations to the accept gesture.

**`insertWikiLinks` as a pure `ImportService` function.** Keeping it in `ImportService` means it is testable in `businessTest` alongside the existing `scan()` tests, with no ViewModel infrastructure needed.

**`PageDeleter` seam rather than `GraphWriter` injection.** `GraphWriter` is a `final` class with platform filesystem dependencies. It cannot be mocked in `commonTest` or `businessTest`. The seam pattern (matching `PageSaver`) keeps `ImportViewModel` fully testable.

**Undo reverts `linkedText` but does not attempt to patch block content retroactively.** Since `confirmImport()` has not run yet at the time undo is available, no markdown file has been written. The `linkedText` revert is purely in-memory. This is the simplest correct behavior.

**Undo is not available after `confirmImport()`.** The requirements do not specify post-confirm undo. Adding it would require a transactional rollback across multiple written files, which is a separate feature. The 10-second pre-confirm undo window covers the primary regret scenario (immediate "I accepted too many").

## Consequences

- `ImportViewModel` gains a `pageDeleter: PageDeleter` constructor parameter alongside `pageSaver`.
- `ImportState` gains `undoBuffer: List<Page> = emptyList()`, `showUndoSnackbar: Boolean = false`, and `undoLinkedText: String = ""` (the `linkedText` snapshot before acceptance, used for revert).
- `ImportViewModel` gains `onUndoStubCreation()` function.
- `ImportScreen.ReviewStage` shows a `Snackbar` when `showUndoSnackbar` is true.
- Existing `ImportViewModel` tests do not need a `PageDeleter` in non-undo scenarios (default `NoOpPageDeleter` is sufficient).
- The `insertWikiLinks` function requires a word-boundary regex that correctly skips already-linked occurrences (text inside `[[…]]`). This is the trickiest part of the implementation and must have thorough unit tests in `businessTest`.

## Patterns Applied

- Seam pattern (`PageDeleter` mirrors `PageSaver` for testability)
- Pre-condition check (existence guard before each stub write — mirrors existing collision guard in `confirmImport()`)
- Command-with-undo (acceptance is reversible via undo buffer within a bounded window)
- Pure function for text transformation (`insertWikiLinks` in `ImportService`)
