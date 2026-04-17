# Tags Support Implementation Plan

## Objective
Enable users to use hashtags (`#tag`) to link to pages. These tags should be rendered distinctly and be clickable, navigating to the corresponding page.

## Context
Logseq treats `#tag` as a link to `[[tag]]`. They are functionally equivalent but visually different.
Currently, the KMP parser tokenizes `#` but the `InlineParser` implementation is naive.
The UI (`BlockRenderer`) parses content using Regex for view mode and needs to be updated.

## Scope (Atomic)
- **Parser**: Robustly parse `#tag` syntax (avoiding headers `# Title`).
- **UI**: Render `#tag` with specific styling (e.g., blue, clickable).
- **Navigation**: Clicking a tag navigates to the page.

## Prerequisites
- [x] Basic Page Navigation (`navigateToPageByName`)

## Atomic Steps

### 1. Parser Hardening (1h) ✅
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/InlineParser.kt`
- **Task**: Update `parseTag` to only create a `TagNode` if the `#` is immediately followed by text (no whitespace). If whitespace follows, it might be a header (though headers are usually block-level, inline `# ` is just text).
- **Constraint**: Ensure we don't consume unrelated tokens.

### 2. UI Rendering (1h) ✅
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
- **Task**: Add regex for tags: `#[^\s#.,!\[\]()]+`.
- **Logic**: Add `TAG` annotation and styling in `parseMarkdownWithStyling`.
- **Interaction**: Update `WikiLinkText` to handle `TAG` clicks by calling `onLinkClick`.

## Validation Strategy
1. Write text `#logseq` -> Should appear blue/clickable.
2. Click `#logseq` -> Should go to page "logseq".
3. Write `#` (trailing) -> Should stay as text.
4. Write `# space` -> Should stay as text.
