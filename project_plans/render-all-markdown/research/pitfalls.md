# Pitfalls Research: KMP Markdown Block Rendering

**Date**: 2026-04-13
**Branch**: stelekit-render-all-markdown
**Scope**: Known failure modes and edge cases for each markdown block type across KMP targets (Desktop JVM, Android, iOS, Web/WASM).

---

## Math Rendering on iOS/Web

### The Core Problem

There is no first-party KaTeX or MathJax implementation for Kotlin Multiplatform. The JavaScript versions cannot be called from Compose Multiplatform's Kotlin/WASM or iOS targets without bridging.

### Current SteleKit State

`MarkdownEngine.kt` handles `LatexInlineNode` with a simple monospace fallback:

```kotlin
is LatexInlineNode ->
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.formula) }
```

There is no block-level math renderer at all. Display math (` $$...$$` blocks) produces raw text.

### Available Options

1. **`github.com/huarangmeng/latex`** — A KMP library that renders LaTeX using KaTeX font files as a custom Compose `Canvas` draw. Supports Android, iOS, Desktop JVM, and Web (WASM). The rendering logic is pure Kotlin; it ships KaTeX `.ttf` font assets. This is the only known pure-KMP math renderer that covers all four targets.

2. **RaTeX (`github.com/erweixin/RaTeX`)** — Pure Rust implementation with a C ABI, targeting iOS, Android, Flutter, and Web via WASM. Requires JNI/FFI bindings from Kotlin; there is no native KMP artifact yet.

3. **WebView bridge (iOS/Web)** — Inject a KaTeX HTML string into a `WKWebView` (iOS) or an HTML `<div>` (Web). This works but violates the requirements constraint against platform-specific branches and adds significant complexity.

4. **Monospace plain-text fallback** — Render the raw LaTeX source in `FontFamily.Monospace`. Zero dependencies, always correct on all targets, but not visually rendered. Acceptable as a Phase 1 fallback for uncommon formulas.

### Recommended Approach

Use the monospace fallback initially for both inline (`$...$`) and display (`$$...$$`) math, with a clearly marked `// TODO: replace with KMP math renderer` comment. Evaluate `huarangmeng/latex` when math is promoted to "Must Have" priority. Before adding it, verify:
- It is published to Maven Central with KMP metadata (not Android-only).
- It includes Web/WASM artifact.
- Font asset bundling works in the KMP resources pipeline (`commonMain/composeResources`).

### Edge Cases to Handle

- `$$` display blocks that span multiple lines — the parser needs to capture the full body, not just the first line.
- Inline `$...$` that appears inside a `CodeFenceBlockNode` should not be parsed as math (the code fence content is `rawContent`, not `InlineNode` list — this is already safe in the current AST).
- Dollar signs used as currency (e.g., `$10`) — the parser must require at least one non-space character between the delimiters and avoid triggering on lone `$`.

---

## Table Overflow / Horizontal Scroll

### The Core Problem

A `TableBlockNode` inside a `LazyColumn` item must scroll horizontally when its total column width exceeds the viewport. Compose's `Modifier.horizontalScroll` can do this, but placing a horizontally scrollable child inside a vertically scrollable parent creates a **nested scroll conflict**.

### Known Compose Issue

`JetBrains/compose-multiplatform#4395` — "Nested Scroll Connection blocks scrolling after upgrade to Compose Multiplatform 1.6.0". A `NestedScrollConnection` on a parent can swallow horizontal scroll events that the child needs.

On **iOS specifically**, `JetBrains/compose-multiplatform#4279` — horizontal/vertical scroll disambiguation inside nested scroll containers can get stuck because the iOS UIScrollView interop layer handles both axes.

### The Standard Fix

Wrap the table in a `Box` with `Modifier.horizontalScroll(rememberScrollState())` and use `Modifier.wrapContentWidth(unbounded = true)` on the inner table layout to allow it to measure wider than the parent. Without `wrapContentWidth(unbounded = true)`, the inner content is forcibly constrained to the parent width and the scroll never activates.

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
) {
    TableContent(
        headers = node.headers,
        rows = node.rows,
        modifier = Modifier.wrapContentWidth(unbounded = true)
    )
}
```

Scrollbars: On Desktop JVM, attach `ScrollbarAdapter` from `androidx.compose.foundation` to render a visible scrollbar. On mobile targets the platform scrollbar is not shown by default; that is expected.

### Column Width Measurement

Pipe tables have no explicit column widths. The correct approach is to measure the maximum cell content width in each column and use that as the fixed column width. This requires a `SubcomposeLayout` or a two-pass measure: first pass collects intrinsic text widths, second pass assigns them. The simpler approach is to give every column a minimum width (e.g., 80.dp) and let text wrap, which avoids the two-pass cost.

### Alignment

`TableAlignment.LEFT / RIGHT / CENTER` from `TableBlockNode.alignments` should map to `TextAlign` on each cell's `Text` composable.

### Edge Cases

- Tables with 0 rows (header-only) — render only the header row with a bottom divider.
- Cells containing inline markdown (bold, code spans, links) — cell content must go through `parseMarkdownWithStyling`, not plain `Text`.
- A table inside a blockquote — the horizontal scroll container must be inside the blockquote's indented `Box`, not wrapping it.
- Very wide tables (20+ columns) on a phone — performance is fine since it is not a `LazyRow`; all cells are composed at once. Only matters if cells contain heavy composables.

---

## Fenced Code Blocks

### Monospace Font

`FontFamily.Monospace` maps to different system fonts per platform:
- **Android**: Droid Mono / Noto Sans Mono
- **Desktop JVM**: DejaVu Sans Mono (Linux), Menlo (macOS), Consolas (Windows)
- **iOS**: Courier New (in older Compose for iOS versions; now uses SF Mono via system font resolution)
- **Web**: `"monospace"` CSS generic family — the browser decides

There is a **known bug** (`JetBrains/compose-multiplatform#3915`: "Web: Don't use Monospace font by default") where the Compose for Web target did not resolve `FontFamily.Monospace` correctly before being fixed in a later release. If targeting Kotlin/JS or WASM, verify the resolved font in a browser dev tool.

For consistent rendering across targets, bundle a specific monospace font (e.g., JetBrains Mono) in `commonMain/composeResources/font/` and use `FontFamily(Font(Res.font.jetbrains_mono_regular))`. This eliminates platform variance.

### Horizontal Scroll for Long Lines

Same pattern as tables: wrap the code block in `Modifier.horizontalScroll(rememberScrollState())` and set the inner `Text` to `softWrap = false`. Without `softWrap = false`, long lines wrap even when a scroll container is present.

```kotlin
Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
    Text(
        text = node.rawContent,
        fontFamily = FontFamily.Monospace,
        softWrap = false,
        modifier = Modifier.wrapContentWidth(unbounded = true)
    )
}
```

### Syntax Highlighting

Highlighting options for KMP:
- **KodeView** (`github.com/SnipMeDev/KodeView`) — KMP-compatible, built on the `Highlights` library. Supports Android, iOS, JVM, and JS.
- **Highlight.js via WebView** — not usable without platform-specific code.
- **Regex-based per-language** — minimal dependency, but only practical for a small set of languages.

Performance concern: Regex-based highlighting runs on every recomposition unless the result is `remember`ed keyed on `rawContent` and `language`. For large code blocks (500+ lines), build the highlighted `AnnotatedString` lazily and cache it.

### Selection and Copy

`BasicTextField` in read-only mode (`readOnly = true`) allows text selection and system copy on all targets. `Text`/`BasicText` does **not** support selection by default. To allow copying from code blocks, use `SelectionContainer { Text(...) }` (available in `androidx.compose.foundation.text.selection`). Note that `SelectionContainer` and `clickable` modifiers conflict — a tap to copy and a tap to enter edit mode need careful gesture disambiguation.

### Edit/View Mode for Code Fences

See the dedicated section below.

### Edge Cases

- Code fences with no language tag (```` ``` ```` with no info string) — render as plain monospace, no highlighting attempt.
- Language tags with options (e.g., ```` ```kotlin {line-numbers} ````) — `CodeFenceBlockNode.options` captures these; ignore options for now.
- Nested backtick fences (a code fence delimited by `~~~~` containing ` ``` `) — the parser handles this; the renderer just uses `rawContent` verbatim.
- Empty code fences — render as an empty monospace box with a minimum height so the block is visible.

---

## Image Loading (Async / File Paths)

### Library Support Matrix (Coil 3 vs Kamel)

| Feature | Coil 3 | Kamel |
|---|---|---|
| Android | Yes | Yes |
| iOS | Yes (Coil 3.x) | Partial (Issue #113) |
| Desktop JVM | Yes | Yes |
| Web/WASM | Yes (since 3.x) | Partial |
| Local file path | Via custom fetcher | Via platform impl |
| Compose resources | `Res.getUri(...)` | Not natively |

**Coil 3** is the recommended choice. It has full KMP artifact support since version 3.0 and is published with KMP metadata. Kamel's iOS support has unresolved issues (Kamel#113: image from device filesystem doesn't display on iOS).

### Local File Path Loading

`AsyncImage(model = "https://...")` works out-of-the-box. Loading a file-system path (e.g., a Logseq asset like `../assets/photo.png`) requires a **custom fetcher**.

On Android: `File("/path/to/file")` is a valid Coil model.
On iOS: The path must be converted to `NSURL.fileURLWithPath(path)` via a platform-specific `expect/actual` helper.
On Desktop JVM: `File` works.
On Web: File system access is not available in the browser sandbox; relative asset paths must be re-mapped to served URLs (e.g., via a local HTTP server or Compose resources).

The `ImageNode` in `MarkdownEngine.kt` currently adds a link annotation but does not render the image inline — it just shows the alt text. A proper inline image renderer needs a `Composable` path, not an `AnnotatedString` span, which means images cannot be rendered inside `BasicText`/`AnnotatedString`. They require a separate `Composable` block that intercepts `ImageNode` before the text span is built.

### Relative Path Resolution

Logseq stores assets relative to the graph root (e.g., `../assets/image.png` from a page file). The renderer needs access to the graph root path to resolve relative URLs. This requires passing the graph's base directory down to the block renderer, either through the `ViewModel` state or via `CompositionLocal`.

### Placeholder and Error States

- Use `placeholder` and `error` parameters of `AsyncImage` for loading/failure states.
- A broken image should show a small "broken image" icon + alt text, not crash.
- On first render inside a `LazyColumn`, images that haven't loaded yet cause layout jumps as the placeholder has a different size than the loaded image. Use `Modifier.size()` or aspect-ratio constraints when the dimensions are known (from markdown attributes or EXIF).

### Edge Cases

- Images inside blockquotes — the image composable must be embeddable inside the blockquote's indented layout.
- Animated GIF support — Coil 3 supports GIF animation on Android and Desktop; iOS GIF support is limited to the first frame unless `coil-gif` is added.
- Very large images — use `Modifier.fillMaxWidth().wrapContentHeight()` and let Coil downsample to avoid OOM. Set `ImageRequest.Builder.size(Size.ORIGINAL)` only when zoom is required.

---

## Headings Typography

### MaterialTheme Mapping

The natural mapping for ATX headings to Material 3 type scale:

| Heading Level | Material 3 Style |
|---|---|
| H1 | `headlineLarge` |
| H2 | `headlineMedium` |
| H3 | `headlineSmall` |
| H4 | `titleLarge` |
| H5 | `titleMedium` |
| H6 | `titleSmall` |

### Scaling and Accessibility

On Android, the user's **Font Size** accessibility setting applies a `fontScale` multiplier to `sp`-based text. Material 3 type scale values are defined in `sp`, so they scale with system font size. This is correct behavior, but H1 (`headlineLarge` at 32sp default) can reach 48sp+ at 1.5x scale, causing very large headings that break layout if the container has a fixed height.

Mitigation: Avoid fixed-height containers for heading blocks. Let `Text` wrap freely.

On iOS, Compose Multiplatform 1.8.0 (stable) still uses `sp`-equivalent sizing but does not fully integrate with iOS Dynamic Type. Apple guidelines expect headings to scale with Dynamic Type categories; this is a known gap in CMP for iOS. Pure Compose-rendered text does not automatically participate in Dynamic Type unless the font is resolved through the iOS UIFont system. For now this is acceptable since Compose draws its own text.

On Web, `sp` units are translated to `px` (not `em`/`rem`), so browser zoom may not scale Compose text in the same way HTML text scales. This is a fundamental CMP for Web limitation.

### Desktop JVM

Desktop does not have system font-scale in the same sense as mobile. The `LocalDensity` DPI multiplier affects `dp` but not `sp` directly. Heading sizes should feel correct without special treatment.

### Edge Cases

- Headings with inline markdown (`# **Bold Title**`) — the `HeadingBlockNode.content` is a `List<InlineNode>`, so it must go through `parseMarkdownWithStyling` rather than plain `Text`. The `SpanStyle` for bold inside a heading must merge with the heading's base `TextStyle`.
- Heading inside an outliner block (`- ## Subheading`) — `BlockParser` produces a `BulletBlockNode` whose content begins with a heading. The renderer needs to detect the leading `HeadingBlockNode` in the inline content and apply heading styling to the whole text run. This is ambiguous: the current `BlockNodes.kt` models headings as a separate top-level `BlockNode`, not as inline content. If the parser wraps a `- ## ...` line as a `HeadingBlockNode` at the top level, this works naturally. If it parses it as a `BulletBlockNode`, the renderer is left with raw `## ` characters that `InlineParser` won't strip.

---

## Blockquote Nesting

### Rendering Strategy

CommonMark blockquotes are block-level containers. The standard Compose approach is a `Box` with a left-border drawn via `drawBehind` or a `Canvas` modifier, plus horizontal padding:

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()
        .drawBehind {
            drawLine(
                color = borderColor,
                start = Offset(0f, 0f),
                end = Offset(0f, size.height),
                strokeWidth = 4.dp.toPx()
            )
        }
        .padding(start = 12.dp)
) {
    // Recursive block rendering for children
}
```

### Infinite Nesting Depth

`BlockquoteBlockNode` contains `children: List<BlockNode>`, and each child can itself be a `BlockquoteBlockNode`. Deep nesting (e.g., `>>>>` 4 levels) will recurse into a new `Box` at each level, each adding `12.dp` of left padding. At 8+ levels the content is essentially invisible on a phone-width screen.

Mitigation: Clamp nesting depth to a maximum (e.g., 4), and stop adding extra indentation beyond that. The rendered content is still shown; only the visual nesting stops growing.

### Callout / Admonition Support

Logseq-style callouts are blockquotes with a special first line: `> [!NOTE]`, `> [!WARNING]`, `> [!TIP]`, etc. These are represented as `BlockquoteBlockNode` where the first child's content begins with the `[!TYPE]` marker. The renderer should detect this pattern and apply a colored background + icon instead of (or in addition to) the left bar:

- `[!NOTE]` → blue tint, info icon
- `[!WARNING]` → amber tint, warning icon
- `[!TIP]` → green tint, lightbulb icon
- `[!IMPORTANT]` → purple tint
- `[!CAUTION]` → red tint

Detection should be done at render time, not in the parser, to avoid coupling the parser to Logseq-specific semantics that are not in the CommonMark or GFM spec.

### Edge Cases

- Blockquote containing a `TableBlockNode` — the table's horizontal scroll container must be nested inside the blockquote's padding `Box`. The scroll works but the left-border line needs to span the full height including the table.
- Blockquote containing a `CodeFenceBlockNode` — same concern.
- Empty blockquote (`>` with no content) — render the left bar with a minimum height (e.g., `1.em`).
- `DrawModifierNode` sync issue (`JetBrains/compose-multiplatform#4740`) — if the left bar is drawn with `drawBehind` inside a `LazyColumn`, the draw position may lag one frame behind scrolling. Use `Modifier.background` with a left-side `PaddingValues` approach or draw inside the item's own layout instead.

---

## Ordered Lists (Numbering)

### SQLDelight Schema vs. AST

The `SteleDatabase.sq` schema stores blocks with a `position INTEGER NOT NULL` column and a `parent_uuid` column. There is no `list_number` or `list_type` column. The `OrderedListItemBlockNode` in `BlockNodes.kt` carries a `number: Int` field parsed from the markdown source (`1.`, `2.`, `3.`, ...).

**Critical question**: When blocks are loaded from disk, the `number` field in `OrderedListItemBlockNode` comes from the raw markdown content (`BlockParser` reads `1. item` → `number = 1`). This number is stored in the block's `content` field in the database (the raw markdown string, e.g., `"1. First item"`), not as a separate column.

Consequence: The displayed list number for an `OrderedListItemBlockNode` is always the number parsed from the block's own content string. If the user reorders blocks (drag-and-drop changes `position`), the `position` column updates but the content string still says `"3. Old third item"` which now renders as `3.` even if it's visually in position 1.

### Rendering Strategies

**Option A — Trust the parsed number**: Display whatever `number` the `BlockParser` extracted. Simple; matches CommonMark behavior where `1.` on every item is valid (the spec says numbers are "advisory"). Logseq itself uses this model.

**Option B — Recompute from position**: At render time, count sibling blocks that are `OrderedListItemBlockNode` above the current block in the same parent group, and use that as the display number. Requires the parent's full sibling list to be available to the renderer.

**Option C — Store number in a block property**: Add a `list_number` property to the block's `properties` map (JSON-stored in the DB). Updated on reorder. Adds complexity.

**Recommended approach for Phase 1**: Option A. It matches Logseq's own rendering behavior and requires no schema changes. Document that drag-reorder does not renumber ordered lists (known limitation, same as Logseq desktop).

### Edge Cases

- List starting at a number other than 1 (e.g., `5. item`) — `OrderedListItemBlockNode.number = 5`. Render as `5.`, which is correct.
- Interleaved block types between ordered items — `OrderedListItemBlockNode` siblings can have non-list blocks between them (e.g., a paragraph). CommonMark calls this a "loose list". Render each item with its own parsed number; don't try to auto-increment across gaps.
- Ordered list inside a blockquote inside a `LazyColumn` — no special issue, just composable nesting.

---

## Edit/View Mode Transitions

### Current Architecture

`BlockItem.kt` holds a `TextFieldValue` state and toggles between `BlockEditor` (a `BasicTextField`) and `BlockViewer` (a `BasicText` with tap-to-edit). The entire block content is a single raw markdown string.

### Code Fence Edit Mode

When a `CodeFenceBlockNode` is tapped to edit, `BlockEditor` opens with the **raw markdown text** of the entire block, including the ` ``` ` fences. This means the user sees:

```
```kotlin
val x = 1
```
```

This is correct for an outliner model — the block's `content` is the raw string. The `BasicTextField` style (via `applyMarkdownStylingForEditor`) does not currently apply code-fence styling to the editor; it only handles inline patterns.

### UX Implications

- **Good**: The raw ` ``` ``` ` fences are always visible in edit mode, making it clear to the user what syntax is controlling the rendering. This is the Logseq desktop behavior.
- **Problem 1: No syntax highlighting in edit mode**. The editor shows monospace text without per-token coloring. Users editing large code blocks have no syntax feedback.
- **Problem 2: Soft keyboard on mobile**. On iOS/Android, tapping a code fence block raises the soft keyboard. The `BasicTextField` is a plain multi-line field; there is no code-specific keyboard (e.g., no dedicated `{`, `}` keys). This is a known limitation of Compose's `BasicTextField` — you cannot inject a custom key layout.
- **Problem 3: IME and newlines**. On iOS, `BasicTextField` with `KeyboardOptions(imeAction = ImeAction.Default)` allows newlines. On Android, the default `imeAction` may be `Done` which closes the keyboard on Enter. Set `KeyboardOptions(imeAction = ImeAction.Default, keyboardType = KeyboardType.Text)` explicitly for code blocks.
- **Problem 4: Loss of rendered view during edit**. The code block renderer (syntax-highlighted `Box`) disappears and is replaced by a plain `BasicTextField`. If the user accidentally taps inside a code block while scrolling, they lose their place. Mitigation: require a deliberate long-press or a dedicated "Edit" button to enter edit mode for code fences (different tap behavior from prose blocks).

### Table Edit Mode

Tables in the outliner model are a single block whose `content` is the raw GFM pipe syntax. Editing a table opens `BasicTextField` with something like:

```
| Col A | Col B |
|-------|-------|
| a1    | b1    |
```

This is the correct behavior for round-trip fidelity but is awkward to edit by hand. A future enhancement would be a cell-by-cell table editor, but that is out of scope for this feature.

### Transition Animation

There is currently no animation on the edit/view toggle — the composable tree switches instantly between `BlockViewer` and `BlockEditor`. A cross-fade (`AnimatedContent`) would reduce visual jarring, but adds complexity and can cause focus-request races (the `FocusRequester` in `BlockItem.kt` must fire after the animation completes). Leave as-is for Phase 1.

### Heading Edit Mode

Headings are rendered with large `MaterialTheme.typography.headlineLarge` text in view mode but the editor shows the raw `# Heading text` syntax in a body-sized `BasicTextField`. The size mismatch is visually abrupt. One mitigation: apply the heading `TextStyle` to the `BasicTextField` as well, stripping the `#` markers from the display (but keeping them in the value). This is a non-trivial UX enhancement and is out of scope for Phase 1.

---

## Sources

- [GitHub: huarangmeng/latex — KMP LaTeX renderer](https://github.com/huarangmeng/latex)
- [GitHub: erweixin/RaTeX — Pure Rust KaTeX-compatible renderer](https://github.com/erweixin/RaTeX)
- [JetBrains/compose-multiplatform#4279 — iOS horizontal scroll inside HorizontalPager bug](https://github.com/JetBrains/compose-multiplatform/issues/4279)
- [JetBrains/compose-multiplatform#4395 — NestedScrollConnection blocks scrolling after 1.6.0](https://github.com/JetBrains/compose-multiplatform/issues/4395)
- [JetBrains/compose-multiplatform#3915 — Web: Monospace font not resolved correctly](https://github.com/JetBrains/compose-multiplatform/issues/3915)
- [JetBrains/compose-multiplatform#4740 — DrawModifierNode not synced with LazyColumn scroll](https://github.com/JetBrains/compose-multiplatform/issues/4740)
- [GitHub: mikepenz/multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
- [GitHub: SnipMeDev/KodeView — KMP syntax highlighting](https://github.com/SnipMeDev/KodeView)
- [Kamel-Media/Kamel#113 — iOS file loading issue](https://github.com/Kamel-Media/Kamel/issues/113)
- [Coil: How to load local files in Compose Multiplatform](https://github.com/coil-kt/coil/discussions/2039)
- [Coil: Load image stored in device using URI](https://github.com/coil-kt/coil/discussions/2582)
- [Coil 3 Compose documentation](https://coil-kt.github.io/coil/compose/)
- [Android Developers: Nested scrolling in Compose](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/nested-scroll)
- [Medium: Rendering Markdown with Code Syntax Highlighting in Compose/Android](https://levelup.gitconnected.com/rendering-markdown-with-code-syntax-highlighting-in-compose-android-f8cda0647c87)
- [Compose Multiplatform 1.8.0 iOS Stable release notes](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)
