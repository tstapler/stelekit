# Tag Suggestion Trigger — Compose UI Wiring Pitfalls

## 1. Focus Stealing

**Finding: `focusProperties { canFocus = false }` on the Surface IS sufficient — no additional work needed.**

`MobileBlockToolbar.kt` line 57 applies `focusProperties { canFocus = false }` to the outermost `Surface`. In Compose, `focusProperties` propagates to all children unless overridden; since no child button uses `.focusProperties { canFocus = true }` to override it, all `IconButton` and `TextButton` children inherit the `canFocus = false` restriction. This is exactly how the existing image-attach and link-picker buttons already work without stealing focus. Adding the tag-suggest `IconButton` inside `primaryActions` at the same level as the existing buttons will have the same behavior automatically.

**Risk: None.** No extra `focusProperties` modifier is needed on the new button.

---

## 2. Lambda Stability / Unnecessary Recompositions

**Finding: The `onSuggestTags` lambda WILL be a new instance on every recomposition of `EditorToolbar` unless wrapped in `remember`.**

The existing `onAttachImage` lambda in `EditorToolbar.kt` (lines 73–79) demonstrates the pattern:

```kotlin
onAttachImage = run {
    val attachFn = capabilities.onAttachImage
    val targetUuid = editingBlockUuid
    if (attachFn != null && targetUuid != null) {
        { attachFn.invoke(targetUuid) }
    } else null
},
```

`run { }` is not equivalent to `remember { }`. This block executes on every recomposition and produces a new lambda instance each time. Because `EditorToolbar` collects five `StateFlow`s (`editingBlockUuid`, `editingCursorIndex`, `isInSelectionMode`, `selectedBlockUuids`, `allBlocks`), any change to any of them recomposes `EditorToolbar` and produces a new `onAttachImage` lambda, which in turn recomposes `MobileBlockToolbar`.

An `onSuggestTags` lambda built the same way — capturing `editingBlockUuid` and looking up content from `allBlocks` — will have the same instability. The consequence is that every block edit (which updates `allBlocks`) triggers a recomposition of `MobileBlockToolbar`, even if the editing block UUID and its content have not changed.

**Mitigation options:**

- **`remember(editingBlockUuid, allBlocks) { ... }`** — only recomputes when inputs change. However `allBlocks` is a `Map<String, List<Block>>` and equality comparison on it is reference equality (new map object per emission), so this may not actually reduce recompositions if `blockStateManager.blocks` emits new map instances frequently.
- **Derive content lazily inside the lambda** — compute `allBlocks.values.flatten().find { ... }` inside the `onClick` callback (not during composition). This means the lambda only captures `editingBlockUuid` and `blockStateManager`, both of which are stable references. This is the lowest-risk approach:

```kotlin
onSuggestTags = if (editingBlockUuid != null) {
    {
        // Capture blockStateManager (stable) and editingBlockUuid (read at call time)
        val uuid = editingBlockUuid ?: return@MobileBlockToolbar
        val block = blockStateManager.blocks.value.values.flatten().find { it.uuid == uuid }
        tagSuggestionViewModel.requestSuggestions(uuid, block?.content ?: "")
    }
} else null,
```

This keeps the lambda referentially stable across `allBlocks` emissions and is consistent with how `onLinkPicker` works (lines 81–97 of EditorToolbar.kt): that lambda also reads `allBlocks` and `editingCursorIndex` at call time, not at composition time.

---

## 3. Toolbar Icon Placement

**Finding: The best placement is inside `primaryActions` (the editing-only row, lines 170–208), adjacent to the existing `onAttachImage` / `onCaptureImage` buttons. Do NOT add it to the formatting overflow row.**

Layout structure of `MobileBlockToolbar`:

```
Column
├── formattingExpanded row (Bold / Italic / Strikethrough / Code / Highlight / Quote / 1. / H1)  [conditional]
├── primaryActions row  (Outdent | Indent | [[]] | AttachFile? | CameraAlt?)                      [editingBlockId != null]
└── second row          (Undo/Redo) | (MoveUp / MoveDown / Add)                                   [always visible / editingBlockId != null]
```

- The formatting overflow row (`formattingExpanded && editingBlockId != null`) is for inline text formatting. Tag suggestion is a block-level action; it does not belong there.
- The second row already contains structural block actions (MoveUp, MoveDown, Add). Tag suggestion is semantically closer to "block-level tools" than "structural manipulation," but the second row is already full on small screens.
- **Primary actions row is the correct location**: it is the row that already hosts the [[]] link button, attach-image, and capture-photo — all "enrichment" actions for the current block. Adding the tag button after `onCaptureImage` follows the existing pattern. It is gated on `editingBlockId != null` (the same guard used by the whole row) and optionally on `onSuggestTags != null` (matching the pattern of `onAttachImage` and `onCaptureImage`).

**Crowding concern**: On small screens with all optional buttons shown (attach + capture + suggest), the `primaryActions` row can overflow. The existing buttons (`onAttachImage`, `onCaptureImage`) are already conditionally shown. The tag button should follow the same nullable-lambda pattern so callers can suppress it when the feature is unavailable or screen space is constrained.

---

## 4. Content Retrieval — `allBlocks.values.flatten().find { ... }`

**Finding: Two correctness risks — stale content and O(N) cost — both solvable with the lazy-capture pattern.**

**Risk A — Stale content at composition time**: `allBlocks` in `EditorToolbar` is collected via `collectAsState()` (line 28). The collected snapshot may lag behind the user's in-progress edit by up to 500 ms (the debounce in `GraphWriter.saveBlock`). If content is read from `allBlocks` at composition time (i.e., captured into the lambda at the `run { }` block), the lambda will hold the last-saved content, not the live text in the `BasicTextField`. The user's current unsaved edits will be silently dropped from the suggestion request.

**Mitigation**: Read content at call time from `blockStateManager.blocks.value` (the current `StateFlow` value), not from the composed snapshot. Even better: pass `blockStateManager.editingBlockContent` directly if such a property exists, or have `tagSuggestionViewModel.requestSuggestions` accept the block UUID and fetch content itself through `blockStateManager`.

**Risk B — Null block**: `allBlocks.values.flatten().find { it.uuid == editingBlockUuid }` returns `null` if the editing block's page has not yet been loaded into `blockStateManager.blocks` (e.g., during initial load or after a graph switch). The call site must guard against `null`:

```kotlin
val block = ...find { it.uuid == uuid }
tagSuggestionViewModel.requestSuggestions(uuid, block?.content ?: "")
```

Passing an empty string is safe — the ViewModel should handle it gracefully (e.g., show an error or no-op). Crashing on null would be a regression.

**Risk C — O(N) flatten on every recomposition**: `allBlocks.values.flatten()` allocates a new list proportional to total block count on every call. If computed at composition time (inside `run { }`), this runs on every recomposition of `EditorToolbar`. Lazy capture (inside the `onClick` lambda) confines this cost to button taps only, which is acceptable.
