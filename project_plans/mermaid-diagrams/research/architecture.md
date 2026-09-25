# Architecture Research: Mermaid Diagram Rendering

Agent 3 (Architecture). Builds on `project_plans/latex-math-rendering/decisions/ADR-001-math-rendering-strategy.md` ("ADR-001") per the divergence called out in `requirements.md` — Mermaid has no pure-JVM renderer, so ADR-001's Option B (expect/actual with a native JVM library) does not carry over unmodified to JVM/Android.

## 1. Integration point: UI-layer language sniffing, not a new parser node

**Decision: sniff the language tag in the UI layer, the same way `codeFenceLanguage()` already does. Do not add a `MermaidBlockNode`.**

Evidence from the parse pipeline (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt:52-90`):

- The AST already has a dedicated `CodeFenceBlockNode(language: String?, rawContent: ...)` (`parsing/ast/BlockNodes.kt:46`) and a parse-time `BlockType.CodeFence(language: String)` sealed variant (`model/ParsedModels.kt:21`).
- But when that AST is converted to the persisted `Block` for storage, the language is **not** carried through as structured data — `blockType` collapses to the flat string constant `BlockTypes.CODE_FENCE` (`model/BlockTypes.kt:8`), and the fence is reconstructed back into a single raw markdown string (`` ```$lang\n$rawContent\n``` ``, `MarkdownParser.kt:81-85`).
- The UI re-derives the language at render time by re-parsing the first line of that raw string — `codeFenceLanguage()` in `BlockItem.kt:547-550` (`content.lines().first().trimStart('`', '~').trim()`).

This is a deliberate two-pass design already documented in-repo (`MarkdownParser.kt:64-65`: "Serialize AST back to a raw string for storage; the UI re-parses on render... see project_plans/render-all-markdown/decisions/"). Mermaid fences already round-trip perfectly through this path today (confirmed: a ` ```mermaid ` block is parsed as `CodeFenceBlockNode(language = "mermaid")` and stored/reconstructed identically to any other fenced block) — nothing is lost that requires new parser support.

**Contrast with ADR-001's `LatexBlockNode`:** ADR-001 needed a new block node because `$$...$$` was *unrecognized syntax* — no existing block type matched it, so the parser had to learn a new construct (new lexer token, new `BlockParser.kt` rule, ADR-001 §Decision). Mermaid fences are already fully-formed, valid `CodeFenceBlockNode`s; the only gap is that the UI's dispatch `when` (`BlockItem.kt:352-...`) doesn't yet look at the language string it already computes. Adding a parser-level `MermaidBlockNode` here would duplicate `CodeFenceBlockNode` for no semantic gain and would fight the existing "generic fenced block, language-tagged" model that `requirements.md`'s non-goals section explicitly wants to preserve ("this stays a fenced-code-block renderer... consistent with the existing `CodeFenceBlock` extension point").

**Where the branch goes:** `BlockItem.kt`'s `BlockTypes.CODE_FENCE ->` arm (line 372) already computes `codeFenceLanguage(block.content)` to pass as a label. Extend that arm to dispatch to a new sibling composable, `MermaidBlock`, when the language equals `"mermaid"` (case-insensitive), falling back to the existing `CodeFenceBlock` call otherwise. `MermaidBlock` itself composes `CodeFenceBlock` internally as its failure/unsupported-platform fallback (see §4), rather than duplicating the raw-rendering chrome (label, monospace text, horizontal scroll, `onStartEditing` click target) from `CodeFenceBlock.kt:36-78`.

## 2. Rendering pattern: expect/actual survives, but JVM and Android converge on WebView

**Decision: keep ADR-001's expect/actual shape (`expect fun MermaidRenderer(...)`), but change what JVM/Android's `actual` does — no pure-JVM option exists, so both converge on WebView rather than splitting `jvmMain`/`androidMain` behavior the way ADR-001's `jlatexmath` did.**

ADR-001's Option B specifically split `jvmCommonMain` (native `jlatexmath`) from `iosMain`/`jsMain` because a pure-JVM library existed and was fast (`ADR-001 §Rationale`: "jlatexmath render ~2-10ms per formula, cached"). That axis of variation collapses for Mermaid: `requirements.md`'s prior-art section confirms "there is no pure-Kotlin/JVM Mermaid-compatible rendering library." So the JVM/Android split from ADR-001's Option A-vs-B tradeoff doesn't apply the same way — both platforms need *some* web engine to execute Mermaid's JS.

Given that, per-platform shape:

- **`jvmMain`** and **`androidMain`**: WebView-backed `actual`. Android already has a system `WebView`; JVM/Desktop has no vendored web-engine dependency yet (confirmed: `requirements.md` notes "zero existing WebView/JS-bridge dependencies" — JCEF, Compose Multiplatform's experimental WebView, or a GraalJS-hosted headless Mermaid are the open options, left to the planning phase to pick one). Converging both on "load Mermaid JS in a web engine, extract rendered SVG" gives one conceptual implementation and one failure-mode/caching story to maintain, even if the underlying engine differs (real WebView vs. JCEF/CEF chromium embed) — this is the "does the calculus change" question from the research brief, and the answer is yes: with no fast native path, consistency between JVM and Android is worth more than it was in ADR-001, where jlatexmath gave JVM+Android a shared *native* implementation for free.
- **`jsMain`**: direct `mermaid.js` DOM call, no WebView — same shape as ADR-001's KaTeX path (`ADR-001 §Decision`, `jsMain/.../MathRenderer.js.kt`). Rejecting a WebView here for the same reason ADR-001 rejected Option A ("Option A... Circular — WebView inside a browser page", `ADR-001 §Rationale` table, "JS/Web support" row): a WebView hosted inside a browser tab is redundant with the DOM already available.
- **`iosMain`**: deferred, Null Object `actual` per ADR-001's iOS precedent (`ADR-001 §Consequences`, `§Patterns Applied` — "iOS `actual` is a no-op... renders a styled monospace fallback"). `requirements.md` AC #5 explicitly allows this ("Platforms without a v1 Mermaid renderer... show the raw code block"). WKWebView is the eventual follow-up, same as ADR-001 left for LaTeX.

This is *not* ADR-001's rejected Option A ("single WebView per math node... works on all platforms including iOS and Web") — Option A was rejected because it pays WebView cold-start cost even where a fast native/DOM path exists (JVM/Android had jlatexmath; JS had DOM KaTeX). For Mermaid, JS still has its own fast DOM-native path (no WebView), so it stays out of the WebView tier; only JVM and Android — which have no native alternative — share the WebView tier. iOS is deferred rather than given a third implementation, again mirroring ADR-001.

One mitigating factor on WebView cost that didn't apply to ADR-001: Mermaid diagrams are far less numerous per page than inline LaTeX formulas (ADR-001's bitmap cache was sized for "hundreds of formulas" per page, `§Consequences`); a page typically has zero to a handful of mermaid fences, each a full block-level element already paying for one `CodeFenceBlock`-sized composable. WebView-per-diagram-block is a much smaller total cost than would-be WebView-per-inline-formula, which is what made ADR-001 reject Option A outright.

## 3. Data flow: render on content change, not on recomposition or a save-debounce

**Render trigger:** `MermaidBlock` only mounts in the "view mode" branch of `BlockItem.kt`'s dispatch (line 351: `} else { // View mode`) — the editor's own text field renders instead while a block is being edited. That means the render trigger is naturally "on block-exit-edit-mode," matching `requirements.md`'s open question, with no extra state needed: recomposition of `MermaidBlock` only happens when `block.content` (or theme) changes between renders, not on every keystroke, because keystrokes only mutate content while the sibling editor composable is mounted instead. The existing pattern to copy is `CodeFenceBlock.kt:42`'s `remember(content) { extractCodeBody(content) }` — extend the key to `remember(content, theme, sizeParams) { renderMermaid(...) }` so a re-render is skipped unless source, theme, or available width actually changed.

GraphWriter's 500ms save-debounce (`db/GraphWriter.kt`, per `CLAUDE.md`'s data-flow summary) is an orthogonal persistence concern — it debounces disk writes, not view rendering — and shouldn't be reused here; there's no analogous "rendering hot-path" risk to justify emulating it, since the composable that would re-render isn't even mounted during active typing.

**Caching:** keyed on `(sourceText, theme, sizeConstraint)`, same shape as ADR-001's `LruCache((formula, displayMode, textSizePx))` (`ADR-001 §Decision`, `jvmCommonMain/.../MathRenderer.jvm.kt`). What's cached differs by platform's output shape:
- JS: the SVG string `mermaid.render()` produces — cheap to cache as a `String`.
- JVM/Android (WebView): caching is less about avoiding recompute and more about avoiding a WebView reload/`evaluateJavascript` round-trip; cache the extracted SVG/rendered-bitmap result, and skip re-injecting JS into the WebView when the cache key is unchanged since the last composition.
- Bound the cache size the same way ADR-001 did (`LruCache(maxSize = 50)`) — diagram counts per graph are smaller than formula counts, so this is a conservative, not a tight, bound.

## 4. Failure semantics: a UI-local sealed result type, not `Either<DomainError, T>`

**Decision: a simple sealed class/interface local to the rendering component, not Arrow's `Either<DomainError, T>`.**

This repo's `CLAUDE.md` states the `Either<DomainError, T>` convention applies at "repository and service methods" — i.e., domain/persistence error boundaries (`DomainError.DatabaseError.WriteFailed`, etc.). Mermaid rendering is a pure UI-layer, synchronous-or-composition-scoped concern: it has no database write, no repository call, and no failure mode that maps to an existing `DomainError` subtype (parse-time SQL failure, closed-DB race, etc. — see `repository/DbFlowExtensions.kt`'s `catchDbError()` guard, which exists specifically for DB-closed races that don't apply here). Forcing a `DomainError` variant for "Mermaid syntax error" or "platform unsupported" would pull a repository-layer type into `ui/components` and invert the layering the `Either` convention exists to protect.

Instead, mirror ADR-001's own precedent for platform-unsupported states — the iOS Null Object `actual` (`ADR-001 §Patterns Applied`) — with an explicit sealed result:

```kotlin
sealed interface MermaidRenderResult {
    data class Rendered(val output: /* SVG string or bitmap handle */ Any) : MermaidRenderResult
    data class Failed(val reason: String) : MermaidRenderResult   // syntax error, JS exception
    data object UnsupportedPlatform : MermaidRenderResult          // iOS v1, Null Object
}
```

`MermaidBlock` `when`s on this result: `Rendered` shows the diagram; `Failed` and `UnsupportedPlatform` both delegate to `CodeFenceBlock`'s existing raw rendering (satisfying `requirements.md` AC #2 and #5 with one fallback path, not two). This keeps the result type as cheap, synchronous UI state — consistent with how `BlockItem.kt`'s other view-mode composables (`HeadingBlock`, `BlockquoteBlock`, etc.) take plain `String`/`Int` parameters rather than `Either`-wrapped ones; none of the existing UI-layer composables in this file use `Either` today, which is corroborating evidence that `Either` is not the house style at this layer.

## 5. Tech debt disposition: Extend as-is

**Extend as-is.** `CodeFenceBlock.kt` is a single 79-line composable with no branching complexity, and `BlockItem.kt`'s `CODE_FENCE` dispatch is one arm in a flat `when` table (`BlockTypes.CODE_FENCE`, `BlockTypes.BLOCKQUOTE`, etc., lines 352-390+) — this is the *first* special case the fenced-code path would acquire, so there's no existing seam to isolate and no accumulated complexity to justify a refactor-first pass. Adding one `if (language == "mermaid")`-shaped branch in `BlockItem.kt` that dispatches to a new sibling `MermaidBlock` composable (which itself composes `CodeFenceBlock` for its fallback path, per §4) is proportional to the change and keeps `CodeFenceBlock.kt` itself untouched.

## Summary of divergences from ADR-001

| Axis | ADR-001 (LaTeX) | Mermaid (this feature) |
|---|---|---|
| New parser node? | Yes — `LatexBlockNode`, new lexer token (unrecognized syntax) | No — reuses `CodeFenceBlockNode`/`BlockTypes.CODE_FENCE`, language already extractable |
| JVM/Android renderer | Native `jlatexmath`, no WebView | No pure-JVM equivalent — WebView (JCEF/Compose WebView/GraalJS, TBD in planning) |
| JVM vs. Android implementation | Shared native lib, same code path | Converge on shared WebView *approach* (engine choice may still differ by platform) |
| JS renderer | Direct KaTeX DOM call | Direct `mermaid.js` DOM call — same shape |
| iOS | Deferred, monospace fallback (Null Object) | Deferred, raw code block fallback (same pattern) |
| Cache key | `(formula, displayMode, textSizePx)` | `(sourceText, theme, sizeConstraint)` |
| Error type | N/A (ADR-001 didn't need one — iOS used Null Object, not a Result type) | New UI-local sealed `MermaidRenderResult`, not `Either<DomainError, T>` |
| Dispatch site | New AST node type | Existing `BlockItem.kt` `CODE_FENCE` arm, extended by language sniff |
