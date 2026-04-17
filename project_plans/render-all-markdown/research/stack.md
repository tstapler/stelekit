# Stack Research: KMP Markdown Rendering Libraries

Research date: 2026-04-13
Context: SteleKit — Kotlin Multiplatform (KMP) + Compose Multiplatform, targeting Desktop JVM, Android, iOS, Web (JS/WASM).

---

## Math / LaTeX

### Problem
Logseq supports inline `$...$` and display `$$...$$` math. KaTeX and MathJax are JS-only and cannot be used in a KMP/Compose context natively.

### Options Found

#### `huarangmeng/latex` (Compose LaTeX) — **Best KMP option**
- GitHub: https://github.com/huarangmeng/latex
- Pure Kotlin / Compose Multiplatform renderer.
- Targets: Android, iOS, Desktop JVM, Web (Wasm/JS) — all four required targets.
- Uses KaTeX **font files** for glyph rendering (not the JS runtime), so it is truly KMP-compatible.
- Supports line breaks at mathematically valid points; atomic structures (fractions, roots, matrices) are never split.
- Can export rendered formulas to PNG/JPEG/WEBP.
- Publishes two variants per release keyed to Kotlin version (e.g. `-kt2.1.0` suffix). Check that the variant aligns with the project's Kotlin version before adopting.
- Status: Active as of 2025; appears to be the only pure-Kotlin Canvas-based KMP LaTeX renderer publicly available.

#### `gregcockroft/AndroidMath`
- Android-only (View system). Not KMP. Not applicable.

#### Canvas-based DIY
- Could implement a subset of TeX layout (boxes, glue, scripts) on Compose Canvas using KaTeX font metrics.
- Very high engineering effort; not recommended unless `huarangmeng/latex` proves inadequate.

### Recommendation
Adopt `huarangmeng/latex`. It is the only identified library that targets all four required platforms with pure Kotlin rendering. Pin to the variant matching the project's Kotlin version.

---

## Syntax Highlighting

### Problem
Fenced code blocks with language tags (` ```kotlin `, ` ```python `, etc.) need token-level colorization.

### Options Found

#### `Highlights` library + `multiplatform-markdown-renderer-code` module — **Primary option**
- The `mikepenz/multiplatform-markdown-renderer` project ships an optional `-code` artifact that wraps the `Highlights` library.
- Provides `MarkdownHighlightedCodeBlock` and `MarkdownHighlightedCodeFence` Composables.
- Supports theme customization (e.g. Atom One Dark/Light).
- Highlights itself is a pure-Kotlin lexer/tokenizer available on KMP; it is what powers the `-code` module.

#### `SnipMeDev/KodeView`
- GitHub: https://github.com/SnipMeDev/KodeView
- Compose Multiplatform syntax-highlighting view that wraps Highlights.
- Can be used standalone (without the full markdown renderer) if SteleKit renders code blocks as an isolated component.
- Targets Android, iOS, Desktop. Web (Wasm) support — check current releases.

#### highlight.js / Prism.js
- JS-only. Not usable in shared KMP code. Could be used as a web-only fallback via `expect/actual`, but this adds complexity.

### Recommendation
Use the `multiplatform-markdown-renderer-code` module (backed by Highlights) if the project adopts `multiplatform-markdown-renderer` for full markdown rendering. If building a custom renderer, use `KodeView` directly for the code-fence composable. Both options are KMP-native.

---

## Image Loading

### Problem
Markdown image syntax `![alt](url)` requires async network image loading inside Compose.

### Options Found

#### Coil 3 — **Recommended**
- GitHub: https://github.com/coil-kt/coil
- Docs: https://coil-kt.github.io/coil/
- Coil 3.x is a full KMP rewrite. Targets: Android, JVM, iOS, macOS, JS, WASM.
- Current stable: 3.3.0 (as of 2025).
- Uses Ktor as the HTTP engine for non-Android targets (`coil-network-ktor3`); uses OkHttp on Android (`coil-network-okhttp`).
- Compose Multiplatform integration via `AsyncImage` composable (same API as Jetpack Compose).
- Disk caching and memory caching built in.
- SteleKit already targets Ktor for networking (implied by KMP patterns); Coil 3 + Ktor is the natural fit.

#### Kamel
- GitHub: https://github.com/Kamel-Media/Kamel
- KMP async media loading library built on top of Coil's multiplatform DiskLruCache.
- Simpler API surface; good for pure markdown image use cases.
- Less actively maintained than Coil 3; Coil 3 is the stronger long-term bet given Cash App backing and active development.

### Recommendation
Use Coil 3 with `coil-network-ktor3`. It is production-ready on all four platforms, has the widest community, and aligns with the KMP-first architecture. Add `coil-compose` for the `AsyncImage` composable in Compose UI.

---

## Table Rendering

### Problem
GitHub-Flavored Markdown (GFM) pipe tables need rendering as structured grids.

### Options Found

#### `multiplatform-markdown-renderer` includes table support
- The mikepenz renderer handles GFM tables natively as part of its commonmark parsing pipeline.
- Tables are rendered as Compose `Row`/`Column` composables with configurable styling.
- No separate library needed if using the full renderer.

#### Hand-rolled with Compose `Row`/`Column`
- Tables in Compose are straightforward: a `Column` of `Row`s with measured cell widths.
- The main challenge is equal-width columns and horizontal scrolling for wide tables.
- `LazyRow` inside a `Column`, or a `HorizontalScrollable` wrapper, handles overflow.
- This is viable but non-trivial to get right for complex tables (colspan, alignment).

#### No dedicated KMP table library identified
- No standalone KMP "table component" library was found in the searches. The ecosystem relies on either full markdown renderers or custom Compose layouts.

### Recommendation
If adopting `multiplatform-markdown-renderer`, use its built-in table support. If building a custom renderer (more likely for a Logseq-style app with custom block types), hand-roll with `Row`/`Column` — it is straightforward enough and avoids a dependency for a single element type.

---

## All-in-one Markdown Renderers

### `mikepenz/multiplatform-markdown-renderer` — **Most complete KMP option**
- GitHub: https://github.com/mikepenz/multiplatform-markdown-renderer
- Targets: Android, iOS, Desktop JVM, Web (Kotlin/Wasm via WASM GC, Chrome 119+, Firefox 120+).
- Supported elements: headings, paragraphs, bold/italic, links, images (via Coil), lists (ordered, unordered, checkboxes), blockquotes, horizontal rules, tables, fenced code blocks (with optional Highlights plugin).
- Math: **NOT built-in**. GitHub issue #375 tracks LaTeX support; not yet implemented as of research date. Would need to be injected via a custom element renderer.
- Plugin system allows overriding individual element renderers, so `huarangmeng/latex` could be plugged in for math nodes.
- Material 3 support via separate `-m3` artifact (core is theme-agnostic since 0.13.0).
- WASM requirements: Wasm GC + Exception Handling; works in modern browsers only.

### `compose-richtext` (halilibo/compose-richtext)
- Docs: https://halilibo.com/compose-richtext/
- Targets Android and Desktop. **iOS support is absent** per search results ("all modules except iOS").
- Fails the four-platform constraint. Not recommended.

### `MohamedRejeb/compose-rich-editor`
- GitHub: https://github.com/MohamedRejeb/compose-rich-editor
- Rich text *editor* with HTML and Markdown support, not a read-only renderer.
- KMP (Android, iOS, Desktop, Web).
- Suitable if SteleKit needs inline editing of rich content; not appropriate as a read-only block renderer.

### Feature Matrix

| Feature | multiplatform-markdown-renderer | compose-richtext | Custom Compose |
|---|---|---|---|
| Android | Yes | Yes | Yes |
| iOS | Yes | No | Yes |
| Desktop JVM | Yes | Yes | Yes |
| Web (Wasm/JS) | Yes (Wasm) | No | Yes |
| Tables | Yes | Yes | Hand-roll |
| Code highlighting | Yes (plugin) | Limited | KodeView |
| Images | Yes (Coil) | Partial | Coil 3 |
| Math / LaTeX | No (issue #375) | No | huarangmeng/latex |
| Custom block types | Via element overrides | Limited | Full control |

---

## Recommendation

SteleKit is a Logseq-style outliner with custom block semantics (properties, embeds, references, queries). A full all-in-one markdown renderer is unlikely to cover all required block types without deep forking. The recommended approach is a **layered custom renderer** with best-of-breed libraries per concern:

| Concern | Library | Notes |
|---|---|---|
| Markdown parsing | CommonMark/flexmark-java (JVM) or `commonmark-kotlin` (KMP) | Parse to AST; render blocks manually |
| Math / LaTeX | `huarangmeng/latex` | Only KMP-native option; all 4 targets |
| Syntax highlighting | `SnipMeDev/KodeView` or `Highlights` directly | Pure Kotlin, KMP |
| Image loading | Coil 3 + `coil-network-ktor3` | Full KMP, production-ready |
| Tables | Hand-rolled `Row`/`Column` | Simple enough; avoids dependency |
| Blockquotes / headings / lists | Native Compose `Text` + `Column` | No library needed |

**If** the project scope is narrower and only standard GFM rendering is needed (no custom Logseq block types), use `mikepenz/multiplatform-markdown-renderer` as the base and plug in:
- `multiplatform-markdown-renderer-code` for syntax highlighting
- A custom element override calling `huarangmeng/latex` for math nodes
- Coil 3 for image loading (the renderer already integrates with it)

This hybrid approach avoids re-implementing heading/paragraph/list rendering while retaining extensibility for Logseq-specific elements.

---

## Sources

- [GitHub - huarangmeng/latex: Compose Latex](https://github.com/huarangmeng/latex)
- [GitHub - mikepenz/multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
- [multiplatform-markdown-renderer README (develop branch)](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/develop/README.md)
- [mikepenz/multiplatform-markdown-renderer issue #375 (LaTeX)](https://github.com/mikepenz/multiplatform-markdown-renderer/issues/375)
- [Markdown Element Rendering - DeepWiki](https://deepwiki.com/mikepenz/multiplatform-markdown-renderer/3-markdown-element-rendering)
- [GitHub - SnipMeDev/KodeView: Kotlin Multiplatform syntax highlighting views](https://github.com/SnipMeDev/KodeView)
- [GitHub - coil-kt/coil: Image loading for Android and Compose Multiplatform](https://github.com/coil-kt/coil)
- [Upgrading to Coil 3.x - Coil docs](https://coil-kt.github.io/coil/upgrading_to_coil3/)
- [Multiplatform image loading: Coil 3.0 - Cash App Code Blog](https://code.cash.app/multiplatform-image-loading)
- [Coil Image Loading in KMP - Medium](https://medium.com/@santosh_yadav321/coil-image-loading-in-kotlin-multiplatform-kmp-with-compose-multiplatform-ui-android-ios-483e416af64f)
- [GitHub - Kamel-Media/Kamel](https://github.com/Kamel-Media/Kamel)
- [Compose Richtext docs](https://halilibo.com/compose-richtext/)
- [GitHub - MohamedRejeb/compose-rich-editor](https://github.com/MohamedRejeb/compose-rich-editor)
- [Compose Multiplatform 1.8.0 Released - JetBrains Blog](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)
- [Markdown Sample (Kotlin/Wasm) - mikepenz live demo](https://mikepenz.github.io/multiplatform-markdown-renderer/)
