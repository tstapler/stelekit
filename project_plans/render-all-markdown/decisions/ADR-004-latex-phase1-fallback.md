# ADR-004: Math/LaTeX Rendering — Phase 1 Fallback

**Status**: Accepted
**Date**: 2026-04-13
**Feature**: Render All Markdown Blocks

---

## Context

`LatexInlineNode` in `MarkdownEngine.kt` currently renders as plain monospace text with no delimiter stripping. The formula `$x^2 + y^2 = z^2$` renders literally as `$x^2 + y^2 = z^2$`.

Full LaTeX rendering requires a math typesetting library. The options evaluated for KMP are:

**Option A — `MathView` / WebView bridge**

Render LaTeX via a `WebView` with MathJax or KaTeX injected. This works on Android and JVM but requires platform-specific composables and a WebView host, breaking the shared Compose UI model. Not viable for a KMP-first codebase.

**Option B — `compose-math` / `latex-jvm` native Kotlin library**

No mature, actively maintained, pure-Kotlin, KMP-compatible LaTeX rendering library exists as of April 2026. The closest candidates (`compose-math`, `AndroidMathView`) are Android-only or require JVM-native rendering engines.

**Option C — Monospace fallback with delimiter stripping (chosen for Phase 1)**

Strip `$...$` and `$$...$$` delimiters and render the inner formula in `FontFamily.Monospace` with italic style. This is not visually equivalent to typeset math but:
1. Makes the formula readable without the noise of raw `$` characters
2. Clearly signals "this is a formula" via monospace + italic
3. Requires zero new dependencies
4. Defers the hard problem (Phase 2) without blocking Phase 1 delivery

---

## Decision

Phase 1: improve `LatexInlineNode` handling in `MarkdownEngine.kt` to strip delimiters and apply `FontFamily.Monospace` + `FontStyle.Italic` + code background. Mark the implementation with a `TODO(latex-phase2)` comment.

Phase 1 implementation:

```kotlin
is LatexInlineNode -> {
    // TODO(latex-phase2): replace with full LaTeX renderer when a KMP-compatible library ships
    val formula = node.formula
        .removePrefix("$$").removeSuffix("$$")
        .removePrefix("$").removeSuffix("$")
        .trim()
    withStyle(SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontStyle = FontStyle.Italic,
        background = codeBackground,
    )) {
        append(formula)
    }
}
```

Phase 2 trigger: when a viable pure-KMP LaTeX library ships (e.g., a Compose-native port of `MathJax` or `katex-kotlin`), replace the `TODO` site with it. No model changes are needed — `LatexInlineNode` already carries `node.formula`.

---

## Consequences

**Positive**:
- Zero new dependencies for Phase 1
- Formulas become readable (delimiters stripped)
- Clear visual differentiation from regular inline code (italic distinguishes math from code)
- `TODO` comment creates a discoverable upgrade path

**Negative**:
- Not visually equivalent to typeset math; power users writing heavy LaTeX notes will see degraded rendering
- Phase 2 is unbounded — no KMP LaTeX library exists today

**Neutral**:
- This decision is fully contained in `MarkdownEngine.kt`; no other files are affected
- Block-level LaTeX (`$$...$$` as a standalone block) is treated as a `ParagraphBlockNode` and routed through the same `MarkdownEngine` inline path, so the same fallback applies automatically
