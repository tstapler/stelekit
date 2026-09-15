# ADR-001: Mermaid Rendering Strategy — Per-Platform Renderers, No New Parser Node

**Status**: Accepted (JVM/Desktop engine sub-decision pending a time-boxed spike — see Open Items)
**Date**: 2026-09-15
**Feature**: Mermaid Diagram Rendering

---

## Context

SteleKit needs to render ` ```mermaid ` fenced code blocks as diagrams across Desktop (JVM), Android, iOS, and Web (Wasm) — the same four-target problem `project_plans/latex-math-rendering/decisions/ADR-001-math-rendering-strategy.md` ("the LaTeX ADR") solved for `$...$`/`$$...$$` math. Unlike LaTeX, **no maintained pure-JVM Mermaid-compatible rendering library exists** (confirmed: `research/stack.md`, `research/build-vs-buy.md`) — Mermaid is a JS library (Jison parser + dagre/ELK layout) with no equivalent to `jlatexmath`. This forecloses the LaTeX ADR's Option B ("native library on JVM+Android, DOM call on JS, defer iOS") from applying unmodified: at least one of {embedded JS engine, WebView} is unavoidable on JVM and Android.

Also unlike LaTeX, mermaid fences already round-trip cleanly through the existing `CodeFenceBlockNode`/`BlockTypes.CODE_FENCE` path (confirmed: `research/architecture.md` §1) — no new AST node is needed, in contrast to the LaTeX ADR's `LatexBlockNode`.

Repo facts verified before deciding (not assumed):
- `grep -rli "webview\|jcef\|wkwebview" kmp/src kmp/build.gradle.kts` → no hits. Zero WebView/JS-bridge infrastructure exists today.
- No classic Kotlin/JS target exists — the web target is `wasmJs` (`kmp/build.gradle.kts:32-38`, gated on `-PenableJs=true`), using Kotlin/Wasm's `external`/`JsAny` interop, not Kotlin/JS `dynamic` types.
- Compose Multiplatform's web target here renders via a single `<canvas>` (`org.jetbrains.compose.experimental.jscanvas.enabled=true`, `gradle.properties:16`) — a rendered SVG must be layered on as a DOM overlay, it cannot become a native composable node.
- A generic LRU cache utility already exists in this repo: `SteleLruCache<K, V>` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/LruCache.kt`), used today by the SQLDelight repositories. Reused here rather than building a new cache class.
- `PlatformDispatcher` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/coroutines/PlatformDispatcher.kt`) already exposes `IO`/`Default`/`Main`/`DB` per platform; mermaid rendering uses `IO`, never `DB`.

Three architectural shapes were considered:

- **Option A — WebView everywhere** (JCEF on Desktop via `compose-webview-multiplatform`/KCEF, `android.webkit.WebView` on Android, WKWebView on iOS): one conceptual rendering mechanism across every platform.
- **Option B — Per-platform-optimized** (direct `mermaid.js` DOM call on `wasmJs`; JVM/Desktop via a time-boxed spike choosing between GraalJS-hosted `mermaid.js` and JCEF; Android via native `android.webkit.WebView`; iOS deferred to the raw-fallback path): mirrors the LaTeX ADR's shape of picking the cheapest correct mechanism per target.
- **Option C — Shared pure-Kotlin Compose Canvas renderer** (`cmp-mermaid`/`mermaid-native`-style reimplementation of Mermaid's parser+layout in Kotlin, one implementation for all four targets, no WebView/JS engine anywhere).

## Decision

**Option B — per-platform-optimized renderers**, via `expect suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult` in `commonMain`:

| Target | Mechanism | Rationale |
|---|---|---|
| `wasmJsMain` | Direct `mermaid.js` DOM call via hand-written `external`/`JsAny` interop (`mermaid.initialize()` + `mermaid.render()`), SVG output layered as a DOM overlay on the `jscanvas` layer | No embedding problem — the target already runs inside a real browser DOM. Mirrors the LaTeX ADR's KaTeX-on-JS precedent exactly. |
| `jvmMain` (Desktop) | **Default**: GraalJS hosting the real, unmodified `mermaid.js` (`mermaid-java`-style: GraalJS + Apache Batik for `SVGTextElement.getBBox()` shimming), output parsed via Skia's `SVGDOM` (already on the classpath via Compose Desktop/skiko) and drawn on a Compose `Canvas`. All calls to the shared GraalJS `Context` are serialized through a `MermaidEngineActor` on its own dedicated single-threaded dispatcher — GraalJS `Context` rejects concurrent multi-thread access by default, and `PlatformDispatcher.IO` (a multi-threaded pool) would otherwise throw `IllegalStateException` under two concurrently-visible mermaid blocks. **Fallback**: JCEF (full Chromium embed) only if the spike (see Open Items) finds GraalJS+mermaid-java fidelity/stability gaps. | Avoids the 100+ MB Chromium bundle and >100 ms WebView cold-start cost the LaTeX ADR already ruled out for KaTeX; runs on stock JDK 21 with no extra runtime. JCEF's most common KMP wrapper (`compose-webview-multiplatform`) delegates to KCEF, whose maintainer archived the project (~Nov 2025) — a real, documented maintenance risk to avoid defaulting into. |
| `androidMain` | `android.webkit.WebView` (system-provided, zero extra bundle weight) hosting a bundled local `mermaid.js` asset + minimal single-method JS bridge | GraalJS is confirmed incompatible with ART (`oracle/graaljs#514`), foreclosing the JVM approach here. Android's own WebView is platform-shipped — no F-Droid/reproducible-build conflict, unlike a bundled Chromium component. |
| `iosMain` | Null Object `actual` returning `MermaidRenderResult.UnsupportedPlatform` (raw fallback) | Mirrors the LaTeX ADR's iOS deferral precedent exactly; `requirements.md` AC5 explicitly allows this. WKWebView-in-Compose is technically viable per `research/stack.md` but is net-new integration work with zero existing WebView infra in this repo to build from — deferred to a follow-up. |

**Dispatch**: extend `BlockItem.kt`'s existing `BlockTypes.CODE_FENCE ->` arm to check `codeFenceLanguage(block.content).equals("mermaid", ignoreCase = true)` and route to a new `MermaidBlock` composable; `CodeFenceBlock.kt` itself is unmodified. `MermaidBlock` composes `CodeFenceBlock` internally as its loading/failure/unsupported-platform fallback (research/ux.md: reuse the raw-block chrome as both loading and error state — no spinner, no red error box).

**Failure semantics**: a UI-local `sealed interface MermaidRenderResult { Rendered(svg: String); Failed(reason: String); UnsupportedPlatform }` — not Arrow's `Either<DomainError, T>`, since this repo's `Either` convention is scoped to repository/service boundaries (per this repo's `CLAUDE.md`) and mermaid rendering has no DB/repository call.

**Security**: `securityLevel: "strict"` is set explicitly at every JS-execution site (wasmJs `mermaid.initialize`, Android WebView shim's `mermaid.initialize`, JVM GraalJS context's `mermaid.initialize`) — never left at Mermaid's `"loose"` default. Real CVEs exist for unhardened Mermaid embeddings (gogs GHSA-26gq-grmh-6xm6, docmost GHSA-r4hj-mc62-jmwj); mermaid fence content is markdown-portable and therefore not guaranteed to be authored by the viewer.

**Caching**: `SteleLruCache<MermaidRenderKey, MermaidRenderResult.Rendered>(maxWeight = 50)`, keyed on `(sourceText, ThemeFingerprint, widthPx)` — extends the LaTeX ADR's `(formula, displayMode, textSizePx)` cache-key shape with a theme fingerprint, since a light/dark toggle must invalidate a diagram's cached colors.

## Rationale

| Criterion | Option A (WebView everywhere) | Option B (per-platform, chosen) | Option C (shared Kotlin Canvas renderer) |
|---|---|---|---|
| Render fidelity | Full mermaid.js on every platform | Full mermaid.js everywhere except iOS (deferred) | Only whatever the reimplementation's parser/layout subset covers |
| Bundle cost (Desktop) | JCEF/Chromium, 100+ MB | GraalJS on stock JDK 21, no extra runtime (default path) | None |
| Maintenance risk | KCEF (compose-webview-multiplatform's desktop backend) archived by its maintainer ~Nov 2025 | Two engines to track (GraalJS+mermaid-java, Android WebView shim) — same order as the LaTeX ADR's "two APIs" tradeoff | Depends on single-digit-star, days-old community reimplementations with no CI/adoption evidence |
| "Renders the same everywhere" parity (requirements.md's stated goal) | Yes, in principle | Yes, on 3 of 4 targets (iOS deferred, consistent with non-goals) | No — a hand-rolled subset cannot guarantee parity with imported Logseq/Obsidian/GitHub mermaid content |
| iOS | Requires new WKWebView integration now | Deferred, Null Object (per this project's AC5) | Would work if the reimplementation is mature — it isn't |
| Android engine risk | WebView (low risk) | WebView (low risk) — same as A | Reimplementation risk |

Option A was rejected primarily on the Desktop bundle-size/KCEF-maintenance axis — the same axis the LaTeX ADR already used to reject a shared-WebView approach for KaTeX, and one that recurs here even without a pure-JVM fallback. Option C was rejected because every KMP-native "no WebView, no JS" Mermaid reimplementation found in research (`cmp-mermaid`, `mermaid-native`, `Mermaid-Android`, `mermaid-rust-kmp`) is a single-digit-star project pushed within days of this research, with no CI, releases, or third-party adoption evidence — adopting one would trade a real correctness/parity risk for implementation convenience, directly undermining the "renders the same everywhere" goal `requirements.md` opens with.

## Consequences

**Positive:**
- `wasmJs` and Android get real, spec-complete `mermaid.js` rendering (all upstream diagram types) with no new correctness risk.
- Desktop avoids the Chromium-bundle tax that the LaTeX ADR already ruled unacceptable for a much higher-frequency (per-formula) render surface; mermaid diagrams are far less numerous per page, but the WebView-per-diagram-block pitfall (community-documented against `compose-webview-multiplatform`) is sidestepped entirely on the platform GraalJS covers.
- The existing `SteleLruCache` and `PlatformDispatcher` utilities are reused as-is — no new cross-cutting infrastructure invented.
- `CodeFenceBlock.kt` and `BlockItem.kt`'s existing dispatch table remain untouched apart from one new branch — no refactor debt introduced (see plan.md's Tech Debt Disposition).

**Negative / Watch-outs:**
- JVM's GraalJS+`mermaid-java`-style path is immature (single-digit-star libraries per `research/build-vs-buy.md`) — the time-boxed spike (Open Items) must validate render fidelity and cold-start latency before this is fully locked in; JCEF remains the documented fallback.
- Three distinct render mechanisms (wasmJs DOM call, JVM GraalJS, Android WebView) means three failure-mode/version-pinning stories to maintain, not one — mitigated by the shared `MermaidRenderResult`/`MermaidRenderKey` contract and a per-diagram-type smoke-test fixture (plan.md Story 7.1.2) that catches version-skew regressions.
- Mermaid's bundled version differs per platform until unified (wasmJs can track latest npm `mermaid`; the JVM GraalJS path inherits whatever a `mermaid-java`-style wrapper last vendored, e.g. 11.4.1) — a real, accepted version-skew risk, tracked as an Unresolved Question in plan.md.
- Android's WebView-hosted render must not re-invoke `mermaid.render()` on every `LazyColumn` scroll-recycle; the cached SVG string must be reloaded directly (`loadDataWithBaseURL`) on a cache hit — implemented in plan.md Story 4.1.3, called out here because getting it wrong reintroduces the exact per-item-WebView-lifecycle cost this ADR rejected Option A to avoid.
- **Accepted risk, v1: wasmJs has no render-hang watchdog.** JVM (`withTimeoutOrNull`) and Android (`withTimeoutOrNull`) can genuinely preempt a hung render because their dispatchers run on separate threads from the caller. `wasmJs` cannot: it runs on a single-threaded JS event loop, so a coroutine-based timeout's own callback needs that same event loop to fire — it cannot preempt a synchronous/tight-loop `mermaid.render()` hang (mermaid-js#1216, #1060, per `research/pitfalls.md` §2). Rather than ship a `withTimeoutOrNull` that would not actually work, plan.md's Task 2.1.1c explicitly omits one. Mitigation (isolating `mermaid.render()` in a Web Worker, which has its own event loop and can be terminated) is deferred to a future follow-up. v1 accepts this risk because Mermaid's hang bugs are rare and mostly triggered by pathological/adversarial input, and `MAX_MERMAID_SOURCE_LENGTH` (plan.md Epic 1.2) already substantially reduces that input space.

## Patterns Applied

- **Strategy + expect/actual** (GoF / KMP): `renderMermaid` is the strategy interface; `wasmJsMain`/`jvmMain`/`androidMain`/`iosMain` are concrete strategies, mirroring the LaTeX ADR's `MathRenderer`.
- **Null Object** (GoF): iOS `actual` always returns `UnsupportedPlatform` — same role as the LaTeX ADR's iOS monospace-fallback `actual`.
- **Adapter** (GoF): `MermaidJvmEngine` (GraalJS) and `MermaidWebViewBridge` (Android) each adapt an external JS-execution environment to the common `MermaidRenderResult` contract.
- **Actor**: `MermaidEngineActor` serializes all access to the shared GraalJS `Context` through a dedicated single-threaded dispatcher, mirroring `db/DatabaseWriteActor.kt`'s existing "one stateful resource, many concurrent coroutine callers" pattern in this repo — required because GraalJS `Context` is not safe for concurrent multi-thread access.
- **Facade** (GoF): the Android JS↔native bridge exposes exactly one `@JavascriptInterface` method (`onRenderResult(json: String)`), not a general eval/exec surface — narrows the RCE-class blast radius `addJavascriptInterface` is documented to carry when combined with untrusted (mermaid-source) script execution.
- **Flyweight / LRU Cache**: `SteleLruCache<MermaidRenderKey, MermaidRenderResult.Rendered>` amortizes repeated renders of the same diagram, reusing this repo's existing cache utility rather than the LaTeX ADR's bespoke one.

## Open Items

- [ ] **JVM engine spike** (plan.md Task 3.1.1a): time-boxed prototype of GraalJS + a `mermaid-java`-style bundled `mermaid.js` rendering one flowchart fixture, measuring cold-start and cached-render latency. Default assumption for planning purposes is GraalJS-primary; append the measured verdict here (and flip the default to JCEF in the table above) if the spike finds blocking fidelity or stability gaps. Blocks plan.md Epic 3.2.
- [ ] Exact Mermaid version pinned per platform (wasmJs vs. whatever a GraalJS-hosted wrapper vendors vs. the Android-bundled asset) — record final pinned versions here once Tasks 2.1.1a/3.2.1a/4.1.1a land.
