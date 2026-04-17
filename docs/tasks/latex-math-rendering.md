# Implementation Plan: LaTeX / Math Rendering

## Epic Overview

Scientific and technical users store equations, proofs, and statistical models in their Logseq graphs using KaTeX-compatible LaTeX syntax. SteleKit currently falls through both inline `$...$` and block `$$...$$` math as plain text, producing cluttered, unreadable output for these users. This epic adds full math rendering parity with Logseq across all KMP targets.

**Outcome**: Inline formulas (`$E = mc^2$`) render as typeset math within block text. Block formulas (`$$\int_0^\infty f(x)dx$$`) render as centered display equations. Both are correct in read (view) mode. Edit mode retains raw LaTeX text with monospace styling — the outliner editing model is unchanged.

### ADR References

| ADR | Decision | File |
|-----|----------|------|
| ADR-001 | Platform-specific renderers via `expect/actual`: `jlatexmath` on JVM/Android, KaTeX on JS, monospace fallback on iOS | `project_plans/latex-math-rendering/decisions/ADR-001-math-rendering-strategy.md` |
| ADR-002 | Inline math uses `BasicText` `inlineContent` map with `\uFFFD` placeholder injection; block math uses a standalone `@Composable` | `project_plans/latex-math-rendering/decisions/ADR-002-inline-math-compose-slot.md` |

---

## Success Metrics

- Inline `$...$` renders as typeset math on JVM Desktop and Android (KPI: formula visible, not raw `$` text)
- Block `$$...$$` renders as centered display equation on JVM Desktop and Android
- JS/Web target renders via native KaTeX — pixel-equivalent to Logseq's rendering
- Render latency for a cached formula: < 5 ms (JVM) / < 2 ms (JS)
- No regression in existing tests (100% pass rate)
- Memory: LRU cache bounded at 50 entries, no unbounded bitmap allocation

---

## Scope

**In scope:**
- Inline math: `$formula$` in block content
- Block math: `$$formula$$` as a standalone block
- JVM Desktop, Android (`jvmCommonMain`), and Web JS targets
- Read (view) mode rendering
- LRU bitmap cache for JVM
- Editor mode: monospace `$...$` styling (no rendered output — intentional)

**Out of scope:**
- iOS full rendering (deferred; no-op `actual` keeps build green)
- `\(...\)` / `\[...\]` alternate math delimiters (Phase 3 follow-up)
- Interactive formula editing (click-to-edit from rendered view)
- Math in search snippets or export

---

## Story Breakdown

### Story 1 — Lexer & Parser: Recognize Math Delimiters [1 week]

**User value**: Users' existing graphs stop showing raw `$` characters; the parser correctly identifies math regions.

**Acceptance criteria:**
- `TokenType.DOLLAR` is emitted by `Lexer` for `$` characters
- `InlineParser.parsePrefix` produces `LatexInlineNode(formula)` for `$...$` spans
- `BlockParser` detects `$$...$$` fenced blocks and produces a new `LatexBlockNode`
- `LogseqParser.processBlock` passes `LatexBlockNode` through without inline-parsing its content
- `MarkdownParser.reconstructContent` round-trips `LatexInlineNode` and `MarkdownParser.convertBlock` maps `LatexBlockNode` to a new `BlockType.LatexBlock`
- All existing parser tests pass; new unit tests cover the 8 math parsing cases below

---

#### Task 1.1 — Add `DOLLAR` token to Lexer [1h]

**Objective**: Emit a `DOLLAR` token for `$` characters so the inline parser can recognize math delimiters.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/lexer/Token.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/lexer/Lexer.kt`

**Prerequisites**: Understanding of the zero-copy `Token(type, start, end)` pattern.

**Implementation approach**:
1. Add `DOLLAR` to the `TokenType` enum in `Token.kt`.
2. In `Lexer.kt`, in the `when (ch)` dispatch, add a `'$'` branch that emits `Token(TokenType.DOLLAR, pos, pos+1)`.
3. Ensure `$` is not greedily merged into `TEXT` tokens by checking the existing `TEXT` accumulation logic.

**Validation**:
- Unit test: `Lexer("$E=mc^2$").tokenize()` produces `[DOLLAR, TEXT("E=mc^2"), DOLLAR, EOF]`
- Unit test: `Lexer("$$x^2$$").tokenize()` produces `[DOLLAR, DOLLAR, TEXT("x^2"), DOLLAR, DOLLAR, EOF]`
- Existing lexer tests unaffected

**INVEST check**: Independent — no parser changes yet. Single file (Token.kt) + one branch in Lexer.kt. Testable by unit test. Estimable: 1h.

---

#### Task 1.2 — Parse `$...$` inline math in `InlineParser` [2h]

**Objective**: Produce `LatexInlineNode(formula)` for inline math spans.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt`

**Prerequisites**: Task 1.1 complete. Familiarity with the backtrack/save-restore pattern already used by `parseCode`, `parseEmphasis`.

**Implementation approach**:
1. In `InlineParser.parsePrefix`, add a `TokenType.DOLLAR` branch.
2. Save lexer state (same pattern as `parseCode`). Peek: if next token is also `DOLLAR`, skip — that's block math handled at block level; return `TextNode("$")`.
3. Accumulate tokens (excluding `NEWLINE`, `EOF`) until the next `DOLLAR` token. Capture raw text via `source.subSequence(start, end)`.
4. If closing `DOLLAR` found: return `LatexInlineNode(formula = capturedText)`.
5. If no closing `DOLLAR`: restore state and return `TextNode("$")`.
6. Edge case: `$$` at inline level — two consecutive `DOLLAR` tokens should NOT trigger inline parsing (they belong to block math). Handle by checking if the saved-state next token is `DOLLAR` before entering the formula accumulation loop.

**Validation**:
- Unit test: `InlineParser("see $E=mc^2$ for details").parse()` → `[TextNode("see "), LatexInlineNode("E=mc^2"), TextNode(" for details")]`
- Unit test: Unmatched `$` → `[TextNode("$"), TextNode("price")]`
- Unit test: `$$block$$` inline → two `TextNode("$")` nodes (not `LatexInlineNode`)
- Existing inline parser tests pass

**INVEST check**: Depends on Task 1.1. Single file. Testable. Estimable: 2h.

---

#### Task 1.3 — Parse `$$...$$` block math in `BlockParser` [2h]

**Objective**: Detect `$$` fence lines and produce `LatexBlockNode(formula)`.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/BlockParser.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/BlockNodes.kt`

**Prerequisites**: Task 1.1. Understanding of the `CodeFenceBlockNode` detection pattern in `BlockParser`.

**Implementation approach**:
1. Add `LatexBlockNode(val formula: String, ...)` to `BlockNodes.kt` (same shape as `CodeFenceBlockNode` minus language/options fields).
2. In `BlockParser`, after the code-fence detection branch, add a `$$` detection branch: if a line starts with `$$` (after stripping bullet/indent), enter a multi-line accumulation mode until a closing `$$` line is found.
3. Single-line form: `$$formula$$` (open and close on same line) — detect by checking if the line ends with `$$` after stripping the opening.
4. Multi-line form: opening `$$`, accumulate lines, closing `$$` on its own line.
5. `LogseqParser.processBlock` must short-circuit for `LatexBlockNode` (no inline parsing of formula content) — add it alongside `CodeFenceBlockNode` in the `when` branch of `processBlock`.

**Validation**:
- Unit test: single-line `$$E=mc^2$$` → `LatexBlockNode("E=mc^2")`
- Unit test: multi-line `$$\n\int_0^\infty\n f(x)\n$$` → `LatexBlockNode("\int_0^\infty\n f(x)\n")`
- Unit test: unclosed `$$` → falls back to `ParagraphBlockNode` with raw text

**INVEST check**: Depends on 1.1. ~3 files. Testable. Estimable: 2h.

---

#### Task 1.4 — Wire `LatexBlockNode` through `MarkdownParser` and model layer [1h]

**Objective**: `BlockType.LatexBlock` is stored and round-trips through the `ParsedBlock`/`Block` model.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt` (add `BlockType.LatexBlock`)

**Prerequisites**: Task 1.3. Existing `BlockType` sealed class in `ParsedModels.kt`.

**Implementation approach**:
1. Add `LatexBlock` to the `BlockType` sealed class in `ParsedModels.kt`.
2. In `MarkdownParser.convertBlock`, add `is LatexBlockNode -> BlockType.LatexBlock` to the `blockType` `when`.
3. In `MarkdownParser.reconstructContent`, add `is LatexBlockNode -> "$$${block.formula}$$"` to the serialization `when`.
4. Verify `Block.init` validation accepts `"latex_block"` as a discriminator string if `blockType` is stored as a string column.

**Validation**:
- Unit test: `MarkdownParser().parsePage("$$x^2$$")` → `ParsedBlock(blockType = BlockType.LatexBlock, content = "$$x^2$$")`
- Round-trip test: parse → reconstruct → parse produces identical `ParsedBlock`

**INVEST check**: Small, bounded scope. Estimable: 1h.

---

### Story 2 — Platform Renderers: `MathRenderer` expect/actual [1 week]

**User value**: Users on Desktop and Android see properly typeset math; Web users get pixel-exact KaTeX rendering.

**Acceptance criteria:**
- `@Composable expect fun MathRenderer(formula: String, displayMode: Boolean, modifier: Modifier)` compiles for all active targets
- JVM/Android `actual` renders a formula using `jlatexmath` and displays an `ImageBitmap`; uses `LruCache(50)` keyed on `(formula, displayMode, textSizePx)`
- JS `actual` calls `katex.renderToString` and injects into a DOM element via `ComposeHtml`
- iOS `actual` shows a styled monospace fallback (no crash)
- `MathRenderer("E=mc^2", displayMode=false)` is visually correct on JVM desktop (manual screenshot test)

---

#### Task 2.1 — Add `jlatexmath` dependency and JVM `actual` MathRenderer [3h]

**Objective**: Render LaTeX formulas to `ImageBitmap` on JVM/Android using `jlatexmath`.

**Context boundary**:
- Primary: New file `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/ui/components/MathRenderer.jvm.kt`
- Supporting: `kmp/build.gradle.kts` (add `jlatexmath` dependency)

**Prerequisites**: Story 1 complete. Knowledge that `jvmCommonMain` is the intermediate source set shared by JVM and Android (already defined in `build.gradle.kts`).

**Implementation approach**:
1. In `build.gradle.kts`, add to `jvmCommonMain.dependencies`: `implementation("org.scilab.forge:jlatexmath:1.0.7")`.
2. Create `MathRenderer.jvm.kt` with `actual fun MathRenderer(formula, displayMode, modifier)`:
   a. Render formula to a `TeXFormula` then `TeXIcon` at the appropriate size.
   b. Paint `TeXIcon` onto a `BufferedImage`.
   c. Convert `BufferedImage` to `ImageBitmap` via `bufferedImageToImageBitmap()` (already exists in codebase for image rendering).
   d. Cache in a `companion object` `LruCache<Triple<String,Boolean,Int>, ImageBitmap>(50)`.
   e. Display with `Image(bitmap, contentDescription = "Math: $formula", modifier = modifier)`.
3. Error handling: catch `ParseException` from jlatexmath; on error display monospace fallback text.

**Validation**:
- Manual: run desktop app, navigate to a block with `$E=mc^2$`, confirm typeset rendering
- Unit test: `renderMathToBitmap("x^2", false, 16)` returns non-null `ImageBitmap`
- Unit test: second call with same args returns cached entry (same reference)
- Unit test: `ParseException` on invalid formula returns null without crashing

**INVEST check**: JVM-specific. 2 files max. Testable. Estimable: 3h.

---

#### Task 2.2 — `commonMain` expect declaration + iOS/fallback actual [1h]

**Objective**: Define the `expect` interface and provide a no-crash `actual` for iOS and any missing targets.

**Context boundary**:
- Primary: New file `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MathRenderer.kt`
- Supporting: New file `kmp/src/iosMain/kotlin/dev/stapler/stelekit/ui/components/MathRenderer.ios.kt`

**Prerequisites**: Task 2.1 (actual shape must match expect signature).

**Implementation approach**:
1. `MathRenderer.kt` (commonMain): `expect @Composable fun MathRenderer(formula: String, displayMode: Boolean, modifier: Modifier = Modifier)`
2. `MathRenderer.ios.kt`: `actual @Composable fun MathRenderer(...)` — renders styled `Text` with monospace font and a `$`/`$$` prefix to indicate math content.
3. Verify KMP compilation succeeds with all targets.

**Validation**:
- `./gradlew compileKotlinJvm compileKotlinAndroid` — no errors
- iOS build (if enabled): no `actual` missing errors

**INVEST check**: Independent from Story 1 logic. Very small. Estimable: 1h.

---

#### Task 2.3 — JS `actual` MathRenderer via KaTeX [2h]

**Objective**: Render LaTeX with KaTeX on the Web/JS target.

**Context boundary**:
- Primary: New file `kmp/src/jsMain/kotlin/dev/stapler/stelekit/ui/components/MathRenderer.js.kt`
- Supporting: `kmp/build.gradle.kts` (`jsMain` npm deps)

**Prerequisites**: Task 2.2. JS target enabled via `enableJs=true` in `gradle.properties`.

**Implementation approach**:
1. In `build.gradle.kts` `jsMain.dependencies`, add: `implementation(npm("katex", "0.16.11"))`.
2. `MathRenderer.js.kt`: use `@JsModule("katex")` external declaration to call `katex.renderToString(formula, options)`.
3. Inject the resulting HTML string into a `<span>` (inline) or `<div>` (display) using Compose HTML's `DomSideEffect` or a custom `HtmlRenderer` composable.
4. Load KaTeX CSS via a `<link>` tag injected once into `document.head` from a `LaunchedEffect`.
5. Error handling: `throwOnError: false` in KaTeX options; on error render raw formula text.

**Validation**:
- Manual: `enableJs=true ./gradlew jsBrowserRun`, verify `$E=mc^2$` renders in browser
- Snapshot: KaTeX renders `\frac{1}{2}` without JS console errors

**INVEST check**: JS-only. Bounded scope. Estimable: 2h.

---

### Story 3 — View Integration: Connect Renderers to Block Display [1 week]

**User value**: Math renders automatically when users open any page containing `$...$` or `$$...$$` content.

**Acceptance criteria:**
- `WikiLinkText` in `BlockViewer.kt` passes math formulas through the `inlineContent` map; inline math composables appear at the correct text position
- `BlockItem.kt`'s block-type dispatch renders `LatexBlockNode` via a new `MathBlock.kt` composable centered on screen
- Edit mode (`BlockEditor.kt`) shows styled monospace `$...$` text — no `MathRenderer` call in edit mode
- Visual regression: no layout shift in blocks without math

---

#### Task 3.1 — Inject inline math via `inlineContent` in `WikiLinkText` [3h]

**Objective**: Replace the `TODO(latex-phase2)` stub in `MarkdownEngine.kt` and wire `LatexInlineNode` into the `BasicText` `inlineContent` map.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockViewer.kt`

**Prerequisites**: Story 2 complete (MathRenderer compilable). Task 1.2 complete (InlineParser produces `LatexInlineNode`).

**Implementation approach**:
1. In `MarkdownEngine.kt` `renderNode` for `LatexInlineNode`: instead of the current monospace fallback, append `"\uFFFD"` (the placeholder character) and add `StringAnnotation("MATH_INLINE", formula, start, length)`.
2. In `WikiLinkText` (BlockViewer.kt):
   a. After building `annotatedString` via `parseMarkdownWithStyling`, scan annotations for `"MATH_INLINE"` tags.
   b. Build `inlineContent: Map<String, InlineTextContent>` — key is a unique ID (`"math_${index}"`); each entry uses `InlineTextContent(Placeholder(width = estimatedWidth, height = lineHeight, placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline)) { MathRenderer(formula, displayMode = false) }`.
   c. Pass `inlineContent` to `BasicText`.
3. Width estimation: use `lineHeightSp * formula.length * 0.5f` as a conservative initial estimate; the composable will be re-laid out once rendered.

**Validation**:
- Integration test: block content `"The formula $x^2$ is inline"` renders with a `MathRenderer` composable between "formula " and " is inline"
- Regression test (Roborazzi screenshot): block with no math renders identically to baseline
- Unit test: `parseMarkdownWithStyling("$x^2$", ...)` produces an annotation with tag `"MATH_INLINE"` and item `"x^2"` at position 0-1 (the `\uFFFD` character)

**INVEST check**: Depends on Story 2 + Task 1.2. Max 2 files. Estimable: 3h.

---

#### Task 3.2 — Add `MathBlock` composable and dispatch from `BlockItem` [2h]

**Objective**: Display block-level `$$...$$` math as a centered equation composable.

**Context boundary**:
- Primary: New file `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MathBlock.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`

**Prerequisites**: Story 2. Task 1.3 (LatexBlockNode in AST). Task 1.4 (BlockType.LatexBlock in model).

**Implementation approach**:
1. Create `MathBlock.kt`:

```kotlin
@Composable
fun MathBlock(formula: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        MathRenderer(formula = formula, displayMode = true, modifier = Modifier.padding(vertical = 8.dp))
    }
}
```

2. In `BlockItem.kt`, in the block-type dispatch (the `when (block.blockTypeEnum)` branch that currently handles `HeadingBlock`, `CodeFenceBlock`, etc.), add: `BlockType.LatexBlock -> MathBlock(formula = block.content)`.
3. Ensure `block.content` for `LatexBlockNode` is the raw formula string (verified in Task 1.4 — `reconstructContent` serializes to `$$formula$$`; strip the `$$` delimiters here before passing to `MathBlock`).

**Validation**:
- Integration test: block with `content = "$$E=mc^2$$"` and `blockType = "latex_block"` renders `MathBlock`
- Roborazzi screenshot test: `MathBlock("x^2")` renders a centered image (JVM target)
- No crash on empty formula string

**INVEST check**: 2 files. Depends on Story 2 + Tasks 1.3/1.4. Estimable: 2h.

---

#### Task 3.3 — Edit-mode monospace styling for `$...$` and `$$...$$` [1h]

**Objective**: Ensure `applyMarkdownStylingForEditor` applies monospace highlighting to math delimiters so edit mode is visually clear.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`

**Prerequisites**: None (independent of Story 2).

**Implementation approach**:
1. In `applyMarkdownStylingForEditor`, add two regex patterns to `MarkdownPatterns` object:
   - `val inlineMathPattern = Regex("""\$(?!\$)(.+?)\$(?!\$)""")`
   - `val blockMathPattern = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)`
2. Apply monospace + italic style to the entire `$...$` / `$$...$$` span (including delimiters) so the user clearly sees math regions while editing.
3. No `MathRenderer` call in editor mode — raw LaTeX text is kept, same as code fences.

**Validation**:
- Unit test: `applyMarkdownStylingForEditor("$x^2$", builder, linkColor)` adds monospace span at positions 0–5
- No regression in existing editor styling tests

**INVEST check**: Single file. Independent. Estimable: 1h.

---

### Story 4 — Quality & Polish [3 days]

**User value**: Math rendering is robust, performant, and consistent across typical Logseq graph content.

**Acceptance criteria:**
- LRU cache on JVM keeps peak heap addition under 5 MB for a page with 50 unique formulas
- Invalid LaTeX renders a styled error placeholder (not a crash or blank space)
- At least 12 round-trip parsing tests covering edge cases pass
- Roborazzi baseline screenshots committed for `MathBlock` and inline math in `WikiLinkText`

---

#### Task 4.1 — Error handling and fallback rendering [1h]

**Objective**: Invalid or unsupported LaTeX shows a styled error state rather than crashing.

**Context boundary**:
- Primary: `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/ui/components/MathRenderer.jvm.kt`

**Implementation approach**:
1. Wrap the `jlatexmath` render call in a `try-catch(ParseException, Exception)`.
2. On error, return a `Text` composable with the raw formula in a red-tinted monospace box: `Text("⚠ $formula", style = monoErrorStyle)`.
3. Log the error via `Logger("MathRenderer")` at WARN level with the formula and exception message.

**Validation**:
- Unit test: `MathRenderer("\\invalidcommand", displayMode=false)` renders without throwing
- Unit test: error fallback composable contains the raw formula text

---

#### Task 4.2 — Parser edge-case tests [2h]

**Objective**: Cover the 12 key parsing edge cases to prevent regressions.

**Context boundary**:
- Primary: New test file `kmp/src/businessTest/kotlin/dev/stapler/stelekit/parsing/MathParserTest.kt`

**Test cases to cover**:
1. `$x^2$` — basic inline
2. `$a$b$c$` — adjacent inline formulas (two formulas, not one)
3. `$$\frac{1}{2}$$` — single-line block
4. Multi-line `$$...\n...$$` block
5. `$x$` inside `**bold $x$ text**` — inline math nested in emphasis
6. Unmatched `$` falls through to `TextNode`
7. `$$ unclosed block` — no crash, falls through to paragraph
8. Empty formula `$$` — treated as two `TextNode("$")`
9. `$\text{hello world}$` — formula with spaces
10. `\(x\)` alternate delimiter — NOT matched (out of scope), becomes `TextNode`
11. `$$formula$$` inside a code fence — NOT matched (code fence takes priority)
12. Round-trip: parse → `reconstructContent` → parse produces same AST

**Validation**: All 12 tests pass in `./gradlew jvmTest --tests "*.MathParserTest"`

---

#### Task 4.3 — Roborazzi screenshot baselines [2h]

**Objective**: Commit screenshot baselines for math rendering to prevent silent visual regressions.

**Context boundary**:
- Primary: New test file `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MathRenderingTest.kt`

**Implementation approach**:
1. Write `@Composable` test harness that renders `MathBlock("E=mc^2")` and `WikiLinkText("The formula $x^2$ is", ...)` in an isolated `ComposeTestRule`.
2. Use `captureRoborazzi()` to record baseline images.
3. Add both tests to the `jvmTest` source set. Commit the resulting PNG baselines to `kmp/src/jvmTest/snapshots/`.

**Validation**:
- `./gradlew jvmTest` passes with stored baselines
- Second run with no code changes produces identical screenshots (zero diff)

---

## Known Issues

### Bug 001: `BasicText` `inlineContent` width estimation causes layout jitter [SEVERITY: Medium]

**Description**: The `InlineTextContent` placeholder width must be declared before `MathRenderer` is measured. The initial estimate (`lineHeight * formula.length * 0.5`) may be wrong, causing a one-frame layout recomposition when the actual rendered width is known.

**Mitigation**:
- Use a conservative over-estimate for initial placeholder width (2× formula character count × average glyph width).
- On JVM, render the `TeXIcon` to get its pixel dimensions before providing `Placeholder` — use a `remember { renderSize(formula) }` call that hits the LRU cache synchronously.

**Files likely affected**:
- `kmp/src/commonMain/.../ui/components/BlockViewer.kt` — `inlineContent` map construction
- `kmp/src/jvmCommonMain/.../ui/components/MathRenderer.jvm.kt` — size pre-computation

**Prevention strategy**: Implement size pre-computation in `MathRenderer.jvm.kt` as a separate `@Composable fun mathSize(formula: String, displayMode: Boolean, textSize: TextUnit): DpSize` that can be called ahead of the `InlineTextContent` declaration.

**Related tasks**: Task 3.1

---

### Bug 002: `$$...$$` block math conflicts with existing emphasis parsing [SEVERITY: Low]

**Description**: `BlockParser` detects `$$` at the start of a line as block math. However, a bullet block whose content starts with `- $$` (e.g. a list item containing a display equation) will currently trigger block math detection rather than parsing as a bullet containing a block-level formula. The block/inline boundary at the outliner level needs careful handling.

**Mitigation**:
- In `BlockParser`, treat `$$` detection as a block-level construct only when the line is not prefixed by a bullet marker (`-`, `*`, `+`).
- For bullet blocks, `$$ ... $$` spanning the entire content string is treated as inline block math — `InlineParser` produces a single `LatexInlineNode` with `displayMode=true` hint attached as metadata.

**Files likely affected**:
- `kmp/src/commonMain/.../parsing/BlockParser.kt`
- `kmp/src/commonMain/.../parsing/InlineParser.kt`

**Prevention strategy**: Task 1.3 should include a specific test case: `"- $$x^2$$"` parses as `BulletBlockNode` with content `[LatexInlineNode("x^2")]`, not as `LatexBlockNode`.

**Related tasks**: Task 1.2, Task 1.3

---

### Bug 003: `jlatexmath` produces AWT `BufferedImage` — requires `java.awt` on Android [SEVERITY: High]

**Description**: `jlatexmath` uses `java.awt.image.BufferedImage` and `java.awt.Graphics2D` internally. Android's JVM does not include `java.awt`; this will cause `ClassNotFoundException` at runtime on Android even though it compiles.

**Mitigation**:
- Place `MathRenderer.jvm.kt` in `jvmMain` only (Desktop JVM), not in `jvmCommonMain`.
- Create a separate `MathRenderer.android.kt` in `androidMain` that uses a WebView-based KaTeX renderer (the original Option A from ADR-001) as the Android-specific actual.
- This requires adding a fourth `actual` file and updating `build.gradle.kts` to add a WebView dependency to `androidMain`.

**Files likely affected**:
- `kmp/build.gradle.kts` — move `jlatexmath` from `jvmCommonMain` to `jvmMain`
- New file: `kmp/src/androidMain/.../ui/components/MathRenderer.android.kt`

**Prevention strategy**: Before Task 2.1, verify that `jlatexmath` does not transitively pull `java.awt` dependencies into Android compilation. If it does, implement the Android-specific WebView path in Task 2.1 as a parallel sub-task. ADR-001 should be updated to record this constraint.

**Related tasks**: Task 2.1, Task 2.2

---

## Dependency Visualization

```
Story 1: Lexer & Parser
  Task 1.1 (DOLLAR token)
      │
  Task 1.2 (inline $...$)   Task 1.3 (block $$...$$)
      │                           │
      └──────────────┬────────────┘
                     │
                Task 1.4 (model layer wiring)
                     │
Story 2: Platform Renderers (can begin in parallel with Story 1 Tasks 1.3/1.4)
  Task 2.1 (JVM actual)   Task 2.2 (expect + iOS fallback)   Task 2.3 (JS actual)
       │                          │                                   │
       └──────────────────────────┴───────────────────────────────────┘
                                  │
Story 3: View Integration (requires Story 1 + Story 2)
  Task 3.3 (editor styling)   ──independent──▶  (can run in parallel)
  Task 3.1 (inline in WikiLinkText)
       │
  Task 3.2 (MathBlock composable)
       │
Story 4: Quality & Polish (requires Story 3)
  Task 4.1 (error handling)
  Task 4.2 (parser edge cases)   ──independent──▶  can run in parallel with 4.1
  Task 4.3 (Roborazzi baselines)  ──after 3.1/3.2──▶  requires rendered output
```

---

## Integration Checkpoints

**After Story 1**: Parser unit tests pass. `InlineParser` produces `LatexInlineNode` for `$x^2$`. `BlockParser` produces `LatexBlockNode` for `$$x^2$$`. No UI change yet — math still shows as raw text (the `MarkdownEngine.kt` stub still applies monospace styling to `LatexInlineNode`).

**After Story 2**: `./gradlew compileKotlinJvm compileKotlinAndroid` succeed with `MathRenderer` expect/actual. JVM desktop renders typeset math when `MathRenderer("x^2", false)` is called in isolation. No wiring to the block renderer yet.

**After Story 3**: End-to-end: open a page containing `$E=mc^2$` on JVM desktop — formula renders inline. Open a page with a `$$\frac{1}{2}$$` block — centered display equation appears. Editor mode shows styled raw text.

**Final (Story 4)**: LRU cache test passes. All 12 parser edge-case tests pass. Roborazzi baselines committed. `./gradlew allTests` green.

---

## Context Preparation Guide

### Task 1.1 — Lexer DOLLAR token
- Load: `Token.kt` (TokenType enum), `Lexer.kt` (char dispatch logic)
- Understand: zero-copy token pattern; how `TEXT` accumulation works in `Lexer.kt`

### Task 1.2 — InlineParser `$...$`
- Load: `InlineParser.kt` (full file, ~444 lines), `InlineNodes.kt` (LatexInlineNode already defined)
- Understand: `parsePrefix` dispatch; save/restore backtrack pattern in `parseCode` and `parseEmphasis`

### Task 1.3 — BlockParser `$$...$$`
- Load: `BlockParser.kt` (focus on fenced-code detection ~lines 150-220), `BlockNodes.kt`
- Understand: how `CodeFenceBlockNode` detection accumulates multi-line content

### Task 2.1 — JVM MathRenderer
- Load: `build.gradle.kts` (jvmCommonMain dependencies), existing `ImageBlock.kt` (Coil/Bitmap pattern), `MathRenderer` expect signature from Task 2.2
- Understand: `jlatexmath` `TeXFormula` → `TeXIcon` → `BufferedImage` API; `LruCache` usage pattern

### Task 3.1 — WikiLinkText inlineContent
- Load: `MarkdownEngine.kt` (full file), `BlockViewer.kt` (WikiLinkText composable)
- Understand: `InlineTextContent`, `Placeholder`, `PlaceholderVerticalAlign` in Compose; existing annotation tag pattern (`WIKI_LINK_TAG`, `BLOCK_REF_TAG`)

### Task 3.2 — MathBlock dispatch
- Load: `BlockItem.kt` (block-type dispatch section), existing `CodeFenceBlock.kt` (reference for block-level composable pattern)
- Understand: how `BlockItem` dispatches to `HeadingBlock`, `CodeFenceBlock`, etc. based on `block.blockType`

---

## Success Criteria

- [ ] All atomic tasks completed and validated per their individual success criteria
- [ ] `./gradlew allTests` passes with zero failures
- [ ] `./gradlew jvmTest` Roborazzi screenshot baselines committed and passing
- [ ] JVM desktop: `$E=mc^2$` inline renders as typeset math
- [ ] JVM desktop: `$$\int_0^\infty f(x)dx$$` block renders as centered display equation
- [ ] Android: builds and runs without `ClassNotFoundException` for `java.awt`
- [ ] JS: KaTeX renders in browser (manual verification)
- [ ] Edit mode: `$...$` shows styled monospace — no `MathRenderer` call
- [ ] Bug 003 (AWT on Android) mitigated — Android `actual` uses WebView fallback
- [ ] docs/tasks/TODO.md entry added: `- [ ] LaTeX / Math Rendering (docs/tasks/latex-math-rendering.md)`
