# Requirements: Mermaid Diagram Rendering

**Backlog item**: `306bbc57-db05-426a-98be-c4a3574dcf18` — feat: render Mermaid diagrams in code blocks
**Status**: Draft (non-interactive — generated from backlog item, no ideation interview run)

## Problem

Fenced code blocks tagged ` ```mermaid ` render as plain text today. Verified: `grep -ri mermaid kmp/src` returns zero hits anywhere in the codebase. GitHub, GitLab, Obsidian, and Logseq (via plugin) all render `mermaid` fences as diagrams; SteleKit does not, which breaks "renders the same everywhere" parity for any graph that uses them.

## Why this matters

- SteleKit already parses and renders generic fenced code blocks (`CodeFenceBlock.kt`, `BlockItem.kt:372`) — this is "detect language tag, dispatch to a renderer" on an existing extension point, not a new note type or schema change.
- Markdown portability: users who bring in existing Logseq/Obsidian graphs with mermaid fences currently see raw text instead of diagrams.

## Non-goals (v1)

- A dedicated "diagram" note type (Trilium's model) — out of scope; this stays a fenced-code-block renderer, consistent with the existing `CodeFenceBlock` extension point.
- Editing diagrams visually — v1 renders from the existing text/markdown source; editing stays the current raw-markdown code editor.
- Full interactivity (pan/zoom, clickable nodes) — nice-to-have, not required for v1.

## Prior art in this codebase

The `latex-math-rendering` project (`project_plans/latex-math-rendering/decisions/ADR-001-math-rendering-strategy.md`) tackled a structurally identical problem: render a non-standard, JS/library-driven visual format inline in the block viewer across JVM/Android/iOS/JS. That ADR chose platform-specific `expect/actual` renderers over a shared WebView specifically to avoid WebView cold-start cost and layout-integration problems, using a pure-JVM library (`jlatexmath`) where one existed.

**Key difference for Mermaid**: unlike LaTeX, there is no pure-Kotlin/JVM Mermaid-compatible rendering library — Mermaid is a JS library with no maintained JVM port. This forecloses the ADR-001 "Option B" path for Mermaid on JVM/Desktop and Android; some form of JS-engine or WebView execution is required on at least those two targets. This should be revisited explicitly in research/ADR rather than assumed away — see Suggestions.

Note: `MathRenderer.kt`/`jlatexmath`/`katex` referenced in ADR-001 do not exist in the tree yet (`find kmp/src -iname "*MathRenderer*" -o -iname "*Latex*"` → no results; no WebView usage anywhere in `kmp/src`) — that decision was recorded but never implemented. This project cannot assume any WebView/JS-bridge infrastructure already exists to build on.

## Suggested scope (from backlog item)

1. Detect ` ```mermaid ` fenced code blocks in the block renderer (extend `codeFenceLanguage()` / the `BlockTypes.CODE_FENCE` dispatch in `BlockItem.kt`).
2. Render via an embeddable Mermaid renderer appropriate to each KMP target:
   - **JVM/Desktop**: likely a WebView (JCEF or similar — none currently vendored in this repo) or a JS engine (e.g. GraalJS) hosting Mermaid.
   - **Android**: Android `WebView`.
   - **WASM/Web** (`jsMain`, `enableJs=true` in `gradle.properties`): direct `mermaid.js` DOM call, no WebView needed — same shape as ADR-001's KaTeX/JS path.
   - **iOS**: WKWebView, or deferred fallback (raw code block) consistent with ADR-001's iOS deferral precedent.
3. Fall back to the current raw fenced-code-block rendering if:
   - The diagram source fails to parse/render (Mermaid syntax error).
   - The platform doesn't yet support Mermaid rendering (e.g. iOS in v1).

## Acceptance Criteria

1. A ` ```mermaid ` fenced code block in a page renders as a diagram (not raw text) on at least Desktop (JVM) and Web (JS) targets.
2. A ` ```mermaid ` block with invalid Mermaid syntax falls back to displaying the raw code block (current behavior) instead of crashing or showing a blank/broken view.
3. Non-mermaid fenced code blocks (e.g. ` ```kotlin `, ` ```bash `) are unaffected — still render via the existing `CodeFenceBlock` path.
4. Tapping/clicking a rendered mermaid diagram still allows entering edit mode to see/edit the raw markdown source (parity with `CodeFenceBlock`'s existing `onStartEditing` behavior).
5. Platforms without a v1 Mermaid renderer (e.g. iOS, if deferred) show the raw code block rather than a broken or missing view.
6. A short design note / ADR exists recording the chosen per-platform rendering strategy (WebView vs JS-engine vs deferred) and its rationale, given the backlog item's own ask for "a short design note before implementation."

## Open questions

- Which JVM-side embedding mechanism (JCEF, Compose Multiplatform's experimental WebView support, GraalJS-hosted Mermaid, pre-render-to-SVG via a headless browser at save time) is acceptable given this repo has zero existing WebView/JS-bridge dependencies?
- Is Android `WebView` acceptable for v1, or does it need to match whatever JVM approach is chosen for consistency?
- Should iOS be deferred (raw fallback) in v1, mirroring the ADR-001 precedent for LaTeX?
- Should rendered diagrams be cached (bitmap/SVG) keyed on source text, similar to ADR-001's `LruCache` for LaTeX bitmaps, to avoid re-rendering on every recomposition?
