# ADR-001: Math Rendering Strategy — Platform-Specific Renderers vs Shared WebView

**Status**: Accepted
**Date**: 2026-04-16
**Feature**: LaTeX / Math Rendering

---

## Context

SteleKit needs to render inline `$...$` and block `$$...$$` LaTeX math across four targets: Desktop JVM, Android, iOS, and Web (JS). The AST already has `LatexInlineNode` in `InlineNodes.kt` and a corresponding monospace fallback in `MarkdownEngine.kt` with a `TODO(latex-phase2)` comment. A block-level `LatexBlockNode` does not yet exist.

Four strategies were evaluated:

- **Option A — Single WebView per math node**: Each `LatexInlineNode` / `LatexBlockNode` spawns a tiny WebView that loads KaTeX's JS bundle from an asset and renders the formula. Works on all platforms including iOS and Web.
- **Option B — Platform-specific renderers via `expect/actual`**: `commonMain` declares `expect fun MathRenderer(formula: String, displayMode: Boolean, modifier: Modifier)`. `jvmMain` + `androidMain` (i.e. `jvmCommonMain`) implement rendering via `jlatexmath` (pure-Java, no WebView). `jsMain` calls `katex.renderToString()` directly in the DOM. `iosMain` uses WKWebView with a bundled KaTeX asset.
- **Option C — Pre-render SVG at parse time**: A background JS worker renders formulas to SVG strings that are stored in the DB and displayed as `Image` composables. Avoids WebView in the hot render path but requires a worker infrastructure and complicates offline graphs.
- **Option D — Compose Canvas path renderer**: Implement a minimal TeX→glyph path engine in pure Kotlin. Near-impossible to maintain; not viable.

## Decision

**Option B — Platform-specific renderers via `expect/actual`, with `jlatexmath` on JVM/Android and KaTeX on JS. iOS deferred (WebView fallback).**

New files:
- `commonMain/ui/components/MathRenderer.kt` — `expect` composable + fallback inline `SpanStyle` for the `AnnotatedString` path
- `jvmCommonMain/ui/components/MathRenderer.jvm.kt` — renders via `jlatexmath` to an offscreen `BufferedImage`, converts to `ImageBitmap`, draws with `Image()` composable; caches rendered bitmaps in a `LruCache` keyed on `(formula, displayMode, textSizePx)`
- `jsMain/ui/components/MathRenderer.js.kt` — calls `katex.renderToString(formula, {displayMode, throwOnError: false})`, injects into a `<span>` / `<div>` via `ComposeHTML`, or wraps in a `WebComponent`
- `iosMain/ui/components/MathRenderer.ios.kt` — initial `actual` shows monospace fallback text; WKWebView-backed rendering is a follow-up
- `commonMain/parsing/ast/BlockNodes.kt` — add `LatexBlockNode`
- `commonMain/parsing/BlockParser.kt` — detect `$$...$$` fenced blocks

Modified files:
- `commonMain/parsing/InlineParser.kt` — add `$...$` tokenizer rule (new `DOLLAR` token in `TokenType`)
- `commonMain/parsing/lexer/Token.kt` — add `DOLLAR` token type
- `commonMain/parsing/lexer/Lexer.kt` — emit `DOLLAR` for `$` chars
- `commonMain/ui/components/MarkdownEngine.kt` — replace `TODO(latex-phase2)` stub with `MathRenderer` delegation note; inline math handled via a custom `InlineContent` map in `BasicText` on JVM
- `commonMain/parser/MarkdownParser.kt` — `reconstructContent` already handles `LatexInlineNode`; add `LatexBlockNode` case
- `kmp/build.gradle.kts` — add `jlatexmath` to `jvmCommonMain`, `katex` npm dep to `jsMain`

## Rationale

| Criterion | Option A (WebView/node) | Option B (expect/actual) | Option C (SVG pre-render) |
|---|---|---|---|
| Render quality | KaTeX-grade on all targets | KaTeX (JS) / jlatexmath (JVM+Android) | KaTeX-grade but async |
| Layout integration | Poor — WebView in AnnotatedString is unsupported; requires `Box` overlay with fixed height | Native — `Image` in `Box`; inline math uses `InlineContent` slot in `BasicText` | Requires `AsyncImage`; SVG size unknown at layout time |
| Offline | Requires bundled KaTeX asset (~300 KB) on every platform | jlatexmath is pure-Java (no network); JS bundles KaTeX once | Worker needs KaTeX; SVGs stored in DB |
| Cold-start cost | WebView init per formula (>100 ms each) | jlatexmath render ~2-10 ms per formula, cached | Pre-rendered, zero hot cost |
| iOS support | WKWebView required (same as Option B deferred path) | Monospace fallback now, WKWebView follow-up | Same as Option A |
| JS/Web support | Circular — WebView inside a browser page | Direct KaTeX DOM call — ideal | Requires Node worker in browser context |
| Maintenance | KaTeX API changes in one place | jlatexmath + KaTeX — two APIs to track | Three APIs |

Option B eliminates the WebView overhead on the two primary targets (Desktop JVM, Android), uses battle-tested `jlatexmath` (Apache 2.0, maintained, used by IntelliJ IDEA), and gives the JS target a first-class KaTeX integration. iOS deferred to a follow-up with a no-op `actual` keeps the iOS build green without blocking the feature.

## Consequences

**Positive:**
- Desktop and Android get sub-10 ms cached formula rendering with no WebView spin-up.
- Web target gets native KaTeX rendering — identical to Logseq's own rendering.
- `AnnotatedString` path (in `MarkdownEngine.kt`) can use `InlineContent` map with a `@Composable` lambda to slot the `MathRenderer` composable inline for short formulas.
- `jlatexmath` is already used in IntelliJ IDEA, so KMP ecosystem precedent exists.

**Negative / Watch-outs:**
- `jlatexmath` is a JVM library — it cannot be placed in `commonMain`. The `jvmCommonMain` intermediate source set (already present in `build.gradle.kts`) is the correct location.
- `BasicText` does not natively support `@Composable` inline content — inline math on JVM requires using `androidx.compose.foundation.text.BasicText` with the `inlineContent` parameter (available in Compose 1.5+). The `WikiLinkText` composable in `BlockViewer.kt` will need extension.
- Bitmap cache must be bounded (`LruCache(maxSize = 50)`) to avoid unbounded memory on pages with hundreds of formulas.
- `$$...$$` blocks that span multiple lines in the block parser need special handling — `BlockParser.kt` does not currently track multi-line fenced constructs beyond code fences.

## Patterns Applied

- **expect/actual** (KMP): Composable rendering differs genuinely by platform; pure-Kotlin abstraction boundary lets `commonMain` remain platform-agnostic.
- **Strategy Pattern**: `MathRenderer` is the strategy interface; `jvmCommonMain` / `jsMain` / `iosMain` are concrete strategies.
- **Flyweight / LRU Cache**: Rendered bitmaps are expensive; caching by `(formula, displayMode, textSizePx)` key amortizes repeated renders of the same formula across blocks.
- **Null Object Pattern**: iOS `actual` is a no-op `actual` that renders a styled monospace fallback — prevents iOS build failure while the full WKWebView path is deferred.
