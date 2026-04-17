# ADR-002: Add Version Stamp to SuggestionState to Guard Against Position Drift

**Status**: Accepted  
**Date**: 2026-04-14  
**Feature**: Multi-Word Term Highlighting & Unlinked References

---

## Context

`SuggestionState` in `BlockItem.kt` captures `contentStart` and `contentEnd` at the moment the user clicks a suggestion highlight. The confirmation popup (or context menu) appears, and the user can still interact with the block editor before clicking "Link". If any editing occurs between the click and the confirm, the stored offsets point to the wrong position in the now-modified content string.

The current code applies `.coerceAtMost` / `.coerceIn` bounds checks before the wrap, preventing crashes but not preventing semantically wrong link insertion. Example: content `"Learn Kotlin today"` → user clicks "Kotlin" (offsets [6,12]) → types to change content to `"Learn Python today"` → clicks Link → result is `"Learn [[Kotlin]] today"` with "Kotlin" wrapping "Python" at those offsets.

Three mitigation options were evaluated:

**Option A — Re-scan on confirm**: Before wrapping, re-run `extractSuggestions` on the current block content and find the match for `canonicalName`. Use the freshly found offsets. Problem: if the page name appears multiple times, ambiguous which occurrence to use. Also adds matcher invocation cost on every confirm.

**Option B — Version stamp (selected)**: Add a `blockContentHash` (or sequence counter) to `SuggestionState` captured at click time. On confirm, compare against the current block content's hash. If they differ, dismiss the popup with an explanatory message ("Block was edited — please click the suggestion again").

**Option C — Dismiss popup on any edit**: If `block.content` changes while the popup is open, close the popup immediately. Achieves the same safety as Option B but is more disruptive — the popup disappears silently if the user fat-fingers a key while reading.

---

## Decision

Implement **Option B**: add a `capturedContent` field to `SuggestionState` holding the block content string at click time. On confirm, compare `pending.capturedContent == block.content`. If they do not match, dismiss the popup and show a brief snackbar: "Block was edited — tap the suggestion to re-link."

`SuggestionState` becomes:

```kotlin
private data class SuggestionState(
    val canonicalName: String,
    val contentStart: Int,
    val contentEnd: Int,
    val capturedContent: String,   // block.content at click time
)
```

Confirmation guard in `onConfirm`:

```kotlin
onConfirm = {
    if (pending.capturedContent != block.content) {
        suggestionState = null
        showStaleMessage = true
    } else {
        val safeEnd = pending.contentEnd.coerceAtMost(block.content.length)
        val safeStart = pending.contentStart.coerceIn(0, safeEnd)
        val newContent = block.content.substring(0, safeStart) +
            "[[${pending.canonicalName}]]" +
            block.content.substring(safeEnd)
        onContentChange(newContent, block.version + 1)
        suggestionState = null
    }
}
```

Option C (auto-dismiss on content change) is added as a secondary guard via `LaunchedEffect`: if `block.content` changes while `suggestionState != null`, clear `suggestionState` immediately. This handles the case where an external edit (e.g., disk-conflict resolution) changes the block while the popup is open.

---

## Consequences

**Positive**:
- Eliminates semantically wrong link insertion without complicating the re-scan logic.
- The `capturedContent` field is the smallest safe snapshot — no index structures, no version counter infrastructure required.
- Secondary `LaunchedEffect` guard handles the external-edit case cleanly.

**Negative / Risk**:
- `SuggestionState` now holds a copy of the block content string. For very large blocks (>10 KB) this doubles memory while the popup is open. Acceptable for the expected block size distribution (median <500 chars in practice).
- If two users edit the same block concurrently (future multi-user scenario), the simple string comparison may produce a false mismatch. This is out of scope for the current single-user model.

**Validation**:
- New test: capture a `SuggestionState` for a block, mutate `block.content`, call `onConfirm`, assert the link is NOT inserted and the state is cleared.
- New test: capture a `SuggestionState`, do NOT mutate content, call `onConfirm`, assert the link is inserted at the correct position.
