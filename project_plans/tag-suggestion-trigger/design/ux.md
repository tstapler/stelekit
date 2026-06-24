# UX Design: Suggest Tags Toolbar Button

**Feature**: Add "Suggest tags" icon button to `MobileBlockToolbar` primary actions row  
**Date**: 2026-06-22  
**Surfaces affected**: 4  
**UX acceptance criteria**: 14

---

## Surface 1: MobileBlockToolbar — Primary Actions Row (new button)

### Current state (baseline)

```
┌──────────────────────────────────────────────────────┐
│  [≡≡]  [←]  [→]  [[[]]]  [📎]  [📷]               │  ← primary row
│  [↩]  [↪]                    [↑]  [↓]  [+]           │  ← secondary row
└──────────────────────────────────────────────────────┘
```

### After change (right-handed layout)

```
┌──────────────────────────────────────────────────────┐
│  [≡≡]  [←]  [→]  [[[]]]  [🏷]  [📎]  [📷]          │  ← primary row
│  [↩]  [↪]                    [↑]  [↓]  [+]           │  ← secondary row
└──────────────────────────────────────────────────────┘
         ↑ new "Suggest tags" button inserted here
```

Left-handed layout (mirrored):

```
┌──────────────────────────────────────────────────────┐
│  [←]  [→]  [[[]]]  [🏷]  [📎]  [📷]  [≡≡]          │
│  [↑]  [↓]  [+]                       [↩]  [↪]        │
└──────────────────────────────────────────────────────┘
```

Icon: `Icons.Default.Label` (Material label/tag icon)  
Content description: `"Suggest tags"`  
Visibility: only shown when `onSuggestTags != null` (same conditional pattern as `onAttachImage`, `onCaptureImage`)

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | User taps a block to begin editing | Block gains focus; toolbar appears at bottom |
| 2 | User taps [🏷] "Suggest tags" button | `onSuggestTags()` callback fires; bottom sheet begins opening |
| 3 | User taps another toolbar button | No interference; tag button is independent |
| 4 | User taps [🏷] again while bottom sheet is open | Bottom sheet is already visible; no duplicate sheet |

### Edge cases

- Button is absent (not rendered) when `onSuggestTags` is null — e.g. on screens that have not wired the callback, or if `tagSuggestionViewModel` is null in `EditorToolbar`.
- Button does not appear in selection mode (`isInSelectionMode = true`) — the selection toolbar replaces the primary row entirely.
- Button must not steal focus from the active `BasicTextField` — enforced by the existing `focusProperties { canFocus = false }` on the `Surface`.

---

## Surface 2: SuggestionBottomSheet — Loading State

Triggered immediately after tapping [🏷]. The ViewModel transitions to `TagSuggestionState.Loading` synchronously before any async work begins.

```
╔══════════════════════════════════════════════════╗
║  ▬▬▬▬▬▬▬▬  (drag handle)                        ║
║                                                  ║
║  Suggested tags for this block         [✕]       ║
║                                                  ║
║                  ◌  (spinner)                    ║
║                                                  ║
║                                                  ║
╚══════════════════════════════════════════════════╝
```

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | (Sheet appears automatically after tap) | `CircularProgressIndicator` centered in body |
| 2 | User waits | Local suggestions resolve first; sheet transitions to Ready |
| 3 | User taps [✕] or drags sheet down | `onDismiss` fires; state resets to `Idle`; sheet closes |
| 4 | User taps anywhere outside sheet | `onDismissRequest` fires; same as tapping [✕] |

### Edge cases

- Loading state must be brief for local suggestions (synchronous scan) — the spinner should be visible only if LLM call is pending and no local results yet.
- If the user dismisses during loading, the in-flight LLM request is cancelled by the ViewModel scope.

---

## Surface 3: SuggestionBottomSheet — Ready State (normal case)

`TagSuggestionState.Ready` with one or more suggestions and `llmPending = false`.

```
╔══════════════════════════════════════════════════╗
║  ▬▬▬▬▬▬▬▬  (drag handle)                        ║
║                                                  ║
║  Suggested tags for this block         [✕]       ║
║                                                  ║
║  [Project Alpha] [Meeting Notes] [Q2 Planning]   ║
║                                                  ║
║                                                  ║
╚══════════════════════════════════════════════════╝
```

Each chip is a `FilterChip` (unselected state). Chips scroll horizontally in a `LazyRow` if they overflow.

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Sheet shows tag chips | All non-auto-applied suggestions displayed as horizontal chips |
| 2 | User taps a chip | `onAcceptTag(blockUuid, term)` fires; tag inserted as `[[term]]` in the block; chip is removed from the row |
| 3 | User swipes chips horizontally | Remaining chips scroll into view |
| 4 | User taps [✕] | Sheet dismisses; no tags are applied |
| 5 | User dismisses by dragging down | Same as tapping [✕] |

### Ready state with LLM still pending (partial results)

```
╔══════════════════════════════════════════════════╗
║  ▬▬▬▬▬▬▬▬                                       ║
║                                                  ║
║  Suggested tags for this block         [✕]       ║
║                                                  ║
║  [Meeting Notes] [Q2 Planning]  ◌                ║
║                                                  ║
╚══════════════════════════════════════════════════╝
         local chips ↑             ↑ inline spinner for LLM
```

When `llmPending = true` and `displaySuggestions` is non-empty, the inline `CircularProgressIndicator` (16dp) appears to the right of the chips to signal more may arrive.

### Edge cases

- Auto-applied tags (`autoApplied = true`) are excluded from chip display by `TagChipRow` — they are applied silently without user action.
- If the user accepts all chips and LLM results have not arrived yet, the spinner remains visible until LLM resolves or fails.

---

## Surface 4: SuggestionBottomSheet — Error State

### 4a. Full LLM failure (`TagSuggestionState.Error`)

The existing `SuggestionBottomSheet` returns early for `Error` state (the `!isVisible` guard). The sheet does not open.

There is no toast or snackbar for this case in the current implementation; it silently fails. This is acceptable per requirements ("out of scope: redesigning the SuggestionBottomSheet UI").

```
[No bottom sheet appears]
[Toolbar remains visible — user can tap [🏷] again to retry]
```

### 4b. LLM error within Ready state (`llmError != null`)

Local suggestions are available but the LLM call failed. The sheet opens with chips and an inline error label.

```
╔══════════════════════════════════════════════════╗
║  ▬▬▬▬▬▬▬▬                                       ║
║                                                  ║
║  Suggested tags for this block         [✕]       ║
║                                                  ║
║  [Meeting Notes]  Could not reach LLM            ║
║                                                  ║
╚══════════════════════════════════════════════════╝
```

The error string "Could not reach LLM" uses `MaterialTheme.typography.labelSmall` in `onSurfaceVariant` at 60% alpha (subdued; local suggestions remain actionable).

### 4c. Empty state (no suggestions found, no error)

`TagChipRow` returns early when `displaySuggestions.isEmpty() && !isLlmLoading && llmError == null`. The chip row renders nothing.

The outer `SuggestionBottomSheet` still shows the header because `state` is `Ready`. The sheet body is visually empty below the header.

```
╔══════════════════════════════════════════════════╗
║  ▬▬▬▬▬▬▬▬                                       ║
║                                                  ║
║  Suggested tags for this block         [✕]       ║
║                                                  ║
║                                                  ║  ← empty body
║                                                  ║
╚══════════════════════════════════════════════════╝
```

Note: This edge case is a minor UX gap (empty sheet with no explanation). It is out of scope per requirements but logged here for awareness.

### Interaction flow (error cases)

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Tap [🏷] when LLM is unreachable | If local suggestions exist: sheet opens with chips + error label. If no local suggestions: sheet does not open (Idle/Error). |
| 2 | User taps [🏷] again | `requestSuggestions()` fires again; a fresh loading cycle begins |
| 3 | User ignores and continues typing | Block editor retains focus; no persistent error UI remains |

---

## Callback Architecture (UX-relevant wiring)

The button is visible only when `onSuggestTags` is non-null. The full call chain:

```
[🏷 tap]
  └─► MobileBlockToolbar.onSuggestTags()           [nullable: () -> Unit]
        └─► EditorToolbar derives (blockUuid, content) from blockStateManager
              └─► EditorToolbar.onSuggestTags(blockUuid, content)   [nullable]
                    └─► JournalsView / PageView
                          └─► tagSuggestionViewModel.requestSuggestions(blockUuid, content)
                                └─► TagSuggestionState flow → SuggestionBottomSheet
```

The `MobileBlockToolbar` and `EditorToolbar` are decoupled from `TagSuggestionViewModel` — they communicate via callbacks only.

---

## UX Acceptance Criteria

### Toolbar button presence

**AC-01** — When a block is being edited and `tagSuggestionViewModel` is non-null, a "Suggest tags" icon button (label/tag icon) is visible in the `MobileBlockToolbar` primary actions row.

**AC-02** — The "Suggest tags" button does not appear in selection mode (`isInSelectionMode = true`).

**AC-03** — The "Suggest tags" button does not appear when no block is being edited (`editingBlockId == null`).

**AC-04** — Tapping "Suggest tags" does not move focus away from the active text field; the cursor position in the block is preserved.

**AC-05** — In left-handed mode, the "Suggest tags" button appears in the primary actions group on the left side of the toolbar (consistent with other primary action buttons).

### Bottom sheet trigger

**AC-06** — Tapping "Suggest tags" opens the `SuggestionBottomSheet` within one animation frame (no noticeable delay before the sheet begins sliding up).

**AC-07** — The bottom sheet title reads "Suggested tags for this block".

### Loading state

**AC-08** — While suggestions are being fetched (LLM pending, no local results yet), a centered `CircularProgressIndicator` is visible in the sheet body.

### Ready state — accepting a tag

**AC-09** — When suggestions are available, each suggestion appears as a tappable chip. Tapping a chip inserts `[[term]]` into the block content at the current cursor position (or appended if no cursor) and removes the chip from the row.

**AC-10** — If the LLM is still fetching while local suggestions are displayed, a small inline spinner (16dp) appears to the right of the chips.

### Dismissal

**AC-11** — Tapping the [✕] button or dragging the sheet down closes the sheet without applying any tags.

**AC-12** — After dismissal, the toolbar remains visible and the "Suggest tags" button is still tappable for a retry.

### Error handling

**AC-13** — When the LLM call fails but local suggestions are available, the sheet displays the local chips plus the subdued text "Could not reach LLM". The chips remain tappable.

**AC-14** — When the entire suggestion request fails (no local or LLM suggestions), the sheet does not open. The toolbar button is still tappable so the user can retry without any persistent error state blocking the UI.
