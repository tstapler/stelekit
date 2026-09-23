# Architecture Review: tag-suggestion-trigger
**Date**: 2026-06-22
**Verdict**: CONCERNS (0 blockers, 3 concerns, 3 nitpicks)

> No architecture constitution (`docs/adr/ADR-000-architecture-constitution.md`) was found; review is based on CLAUDE.md constraints and existing codebase conventions.

---

## Blockers

None.

---

## Concerns

- [ ] **Story 1.1.3 / 1.1.4 — `alreadyLinkedTerms` silently dropped in the toolbar path**
  The existing `JournalsView` and `PageView` call sites pass `alreadyLinkedTerms = WikiLinkExtractor.extractPageNames(content)` to `requestSuggestions`. The plan's `onSuggestTags` lambda (Task 1.1.3a, 1.1.4a) calls `tagSuggestionViewModel.requestSuggestions(blockUuid, content)` — two-arg form — which defaults `alreadyLinkedTerms` to `emptySet()`. This means the LLM will suggest tags that are already linked in the block, producing duplicate recommendations. Since `WikiLinkExtractor.extractPageNames` is already available at both call sites, the toolbar lambda should compute and forward it:
  ```kotlin
  onSuggestTags = if (tagSuggestionViewModel != null) { blockUuid, content ->
      val alreadyLinked = WikiLinkExtractor.extractPageNames(content)
      tagSuggestionViewModel.requestSuggestions(
          blockUuid = blockUuid,
          blockContent = content,
          alreadyLinkedTerms = alreadyLinked,
      )
  } else null,
  ```
  Remediation: update Tasks 1.1.3a and 1.1.4a to match the three-arg pattern used by the existing `onRequestTagSuggestions` wiring in those same screens.

- [ ] **Story 1.1.2 — `EditorToolbar.onSuggestTags` callback type leaks raw `String` UUID where a domain type exists**
  The proposed `EditorToolbar` parameter is `onSuggestTags: ((blockUuid: String, content: String) -> Unit)?`. Inside `EditorToolbar`, `editingBlockUuid` is typed `BlockUuid?`, and the derivation block calls `suggestFn(targetUuid.value, content)` — explicitly unwrapping the domain type to a raw `String`. The call sites in `JournalsView` and `PageView` then receive a raw `String` and pass it straight to `tagSuggestionViewModel.requestSuggestions(blockUuid: String, ...)`.
  This is consistent with `TagSuggestionViewModel.requestSuggestions` already taking `String`, so no type is being introduced. However, the intermediate `EditorToolbar` callback `((blockUuid: String, content: String) -> Unit)?` mirrors an existing raw-`String` surface that was a prior design choice rather than a deliberate one. No action required today, but note that if `requestSuggestions` ever moves to `BlockUuid`, the callback shape will break at two call sites rather than one.
  Remediation: no change required for this PR; record as technical debt. The concern is INFORMATIONAL unless the team is actively introducing `BlockUuid` at the ViewModel boundary.

- [ ] **Story 1.1.5 — Plan does not address the `onRequestTagSuggestions` parameter on `BlockList`**
  The plan specifies deleting the dead `DropdownMenu` block in `BlockItem.kt` (lines 499–516) and removing `onRequestTagSuggestions` from `BlockItem` if no remaining callers exist. However, `BlockList.kt` also declares and threads `onRequestTagSuggestions: ((blockUuid: String, content: String) -> Unit)?` (line 93, forwarded at line 249). `JournalsView.kt` passes it at line 414 (to the `JournalEntry` composable's `BlockList`). After Story 1.1.5 removes the DropdownMenu from `BlockItem`, `onRequestTagSuggestions` will exist as a parameter on `BlockItem`, `BlockList`, `BlockRenderer`, and the inner `JournalEntry` composable in `JournalsView`, but it will have no rendering surface — the parameter chain becomes dead code rather than being cleaned up. The plan's Task 1.1.5a says to "verify if the parameter has no remaining usages, remove it and update callers," but does not enumerate the full propagation chain through `BlockRenderer → BlockList → JournalEntry`. Without an explicit scope, the implementer may stop at `BlockItem` and leave a silent dead-parameter chain across four files.
  Remediation: explicitly scope Story 1.1.5 to also remove `onRequestTagSuggestions` from `BlockRenderer`, `BlockList`, and the `JournalEntry` private composable parameter list (and its pass-through at line 414 in `JournalsView`), after the `BlockItem` DropdownMenu is deleted. `PageView` wires its `onRequestTagSuggestions` directly to `BlockList` (line 361–368) and that site also becomes dead.

---

## Nitpicks

- **Task 1.1.1b — `contentDescription` placement is inconsistent with toolbar peers.** The plan puts `contentDescription` in `Modifier.semantics { contentDescription = "Suggest tags" }` on the `IconButton`, while the `Icon` inside has `contentDescription = null`. The existing toolbar buttons (Outdent, Indent, AttachImage, CaptureImage) use the same pattern, so this is internally consistent — no change needed. However, `onLinkPicker`'s `TextButton` uses `contentDescription = "Insert wiki link"` on the button's modifier. Both styles work; just noting no deviation from precedent.

- **Story 1.1.2 — The `run { }` block for deriving the suggest lambda is an unusual pattern.** The `onAttachImage` precedent in `EditorToolbar` captures locals with a `run { val attachFn = ...; val targetUuid = ...; if (...) { { ... } } else null }` block. The plan copies this exactly. It is valid and avoids lambda reallocation on every recomposition, but the pattern is not self-documenting. Consider a named helper or at minimum an inline comment explaining the capture intent, consistent with the docstring already present on `EditorToolbar`.

- **Performance SLO note (non-functional requirement):** The plan notes "the callback is stable" to prevent recompositions on every keystroke. The derived lambda inside `run { }` is a new object created on each recomposition of `EditorToolbar` (since it is not wrapped in `remember` or `rememberUpdatedState`). This matches how `onAttachImage` is derived today, so it is consistent — but the SLO claim is technically only met because Compose's lambda equality check sees a new object each time, which will not trigger downstream recompositions of `MobileBlockToolbar` unless `MobileBlockToolbar` is marked `@Stable` or its parent's recomposition scope is isolated. At this feature scale this is acceptable; no change required but the SLO wording in the requirements is slightly optimistic.
