# Implementation Plan: mermaid-diagrams

**Feature**: Render ` ```mermaid ` fenced code blocks as diagrams (Desktop, Android, Web in v1; iOS deferred to raw fallback), with graceful fallback to the existing `CodeFenceBlock` raw view on any parse/render/platform failure.
**Date**: 2026-09-15
**Status**: Ready for implementation
**ADRs**: `project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md`

---

## Creative Pass — Alternatives Considered

Three high-level architectures were brainstormed before committing (see ADR-001 for full rationale):

1. **WebView everywhere** (JCEF/KCEF on Desktop, `android.webkit.WebView` on Android, WKWebView on iOS). Strength: one conceptual rendering mechanism to reason about across all platforms. Weakness: JCEF bundles 100+ MB of Chromium into the desktop distributable, and its most common KMP wrapper's desktop backend (KCEF) was archived by its maintainer ~Nov 2025 — a real maintenance risk to build a default on.
2. **Per-platform-optimized** (wasmJs direct DOM call, JVM spike between GraalJS and JCEF, Android native WebView, iOS deferred). Strength: matches this repo's own ADR-001 (LaTeX) precedent of picking the cheapest correct mechanism per target, and avoids the Chromium-bundle tax on Desktop when a lighter path exists. Weakness: three distinct render mechanisms to maintain instead of one.
3. **Shared pure-Kotlin Compose Canvas Mermaid-compatible renderer** (`cmp-mermaid`/`mermaid-native`-style reimplementation, one codepath for all four targets, zero WebView/JS engine). Strength: no WebView/JS-engine dependency anywhere. Weakness: every such project found in research is single-digit-star and days old with no CI/adoption evidence — betting on one directly undermines the "renders the same everywhere" parity goal this feature exists to satisfy.

**Chosen: Option 2 (per-platform-optimized)**, recorded in ADR-001. Options 1 and 3 are recorded as rejected alternatives in the Pattern Decisions table below.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `MermaidBlock` | Composable dispatched from `BlockItem.kt` when a fenced code block's language is `"mermaid"`; renders the diagram or falls back to `CodeFenceBlock`. Takes an injectable `renderer` parameter (default `::renderMermaid`) so tests can substitute a fake. | New file, `ui/components/MermaidBlock.kt` |
| `MermaidDiagramSurface` | `expect @Composable fun MermaidDiagramSurface(result: MermaidRenderResult.Rendered, modifier: Modifier)` — the platform-specific diagram display surface `MermaidBlock` delegates to on a successful render, since `commonMain` cannot reference `MermaidSvgCanvas`/`MermaidSvgOverlay`/`MermaidWebViewHost` directly. | One `actual` per target (jvmMain, wasmJsMain, androidMain, and a trivial iOS actual) |
| `MermaidRenderResult` | Sealed interface: `Rendered(svg: String)`, `Failed(reason: String)`, `UnsupportedPlatform` — the outcome of one render attempt. | Sum type; UI-local, not `Either<DomainError, T>` |
| `MermaidRenderKey` | Data class `(sourceText: String, theme: ThemeFingerprint, widthPx: Int)`; both the cache key and the render-invocation payload. | Structural equality drives cache hits |
| `ThemeFingerprint` | Value type derived from `MaterialTheme.colorScheme` (light/dark flag + a hash of key colors); changes whenever the active theme changes. | Invalidates stale-colored cached diagrams |
| `renderMermaid` | `expect suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult` — the per-platform rendering entry point. | One `actual` per target source set |
| `mermaidRenderCache` | `SteleLruCache<MermaidRenderKey, MermaidRenderResult.Rendered>(maxWeight = 50)` — reuses this repo's existing `dev.stapler.stelekit.cache.SteleLruCache`. | Not a new cache class |
| `MAX_MERMAID_SOURCE_LENGTH` | Character-count ceiling (2000, per GitLab's precedent) above which rendering is skipped and the raw fallback shown directly. | `ui/components/MermaidLimits.kt` |
| `MERMAID_RENDER_TIMEOUT_MS` | Watchdog timeout (independent of Mermaid's own character cap) after which an in-flight render is treated as `Failed`. | Guards against algorithmic-hang inputs |
| `MERMAID_SECURITY_LEVEL` | The hardened Mermaid `securityLevel` value (`"strict"`) applied at every JS-execution site. | Never left at Mermaid's `"loose"` default |
| `MermaidJsBindings` | `wasmJsMain` `external`/`JsAny` interop declarations wrapping the global `mermaid` object's `initialize()`/`render()`. | Kotlin/Wasm interop, not Kotlin/JS `dynamic` |
| `MermaidSvgOverlay` | `wasmJsMain` composable positioning a rendered SVG as a DOM element synced to the Compose layout bounds (since this build's Compose-for-Web renders via `jscanvas`, not DOM). | Gated on Story 2.1.2's go/no-go spike (Task 2.1.2a) before the full implementation (Task 2.1.3a) proceeds — pre-mortem.md Failure #1 |
| `MermaidJvmEngine` | `jvmMain` wrapper owning a GraalJS `Context` + bundled `mermaid.js` + an Apache Batik `SVGTextElement.getBBox()` shim, turning source text into an SVG string. Never called concurrently from more than one thread — see `MermaidEngineActor`. | Default JVM engine per ADR-001 |
| `MermaidEngineActor` | `jvmMain` singleton mirroring `db/DatabaseWriteActor.kt`'s pattern: owns a dedicated single-threaded `CoroutineDispatcher` and serializes every `MermaidJvmEngine.render()` call through it. | Required because GraalJS `Context` rejects concurrent multi-thread access by default; also keeps CPU-bound render work off `PlatformDispatcher.IO` |
| `MermaidSvgCanvas` | `jvmMain` composable parsing an SVG string via Skia's `SVGDOM` and drawing it on a Compose `Canvas`. | No new Gradle dependency expected (verify at implementation time) |
| `MermaidWebViewBridge` | `androidMain` class owning its own `CoroutineScope`, exposing exactly one `@JavascriptInterface fun onRenderResult(json: String)` callback. | Minimal bridge surface, per pitfalls.md §3 |
| `MermaidWebViewHost` | `androidMain` composable wrapping `android.webkit.WebView` with internal scrolling disabled, reloading cached SVG directly on cache hits. | Avoids re-running `mermaid.render()` on scroll-recycle |
| `codeFenceLanguage` | Existing `BlockItem.kt` helper (unchanged); its return value gates the `MermaidBlock` vs. `CodeFenceBlock` dispatch. | No signature change |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Overall per-platform rendering architecture | Strategy + KMP expect/actual (`renderMermaid`) | ADR-001 (this project), mirrors LaTeX ADR-001 | WebView everywhere (JCEF/KCEF + Android WebView + WKWebView) | Pays a 100+ MB Chromium bundle-size and >100ms cold-start cost on Desktop even though a lighter GraalJS path exists there; KCEF (the common desktop WebView backend) was archived by its maintainer ~Nov 2025 |
| Overall per-platform rendering architecture | Strategy + KMP expect/actual (`renderMermaid`) | ADR-001 (this project) | Shared pure-Kotlin Compose Canvas Mermaid-compatible renderer (`cmp-mermaid`/`mermaid-native`) | All such projects found are single-digit-star, days-old, with no CI/adoption evidence; betting on one undermines the "renders the same everywhere" parity goal in requirements.md |
| Language dispatch site (`BlockItem.kt`) | Extend existing UI-layer language-sniff branch | architecture.md §1 | New `MermaidBlockNode` AST type (mirroring LaTeX ADR's `LatexBlockNode`) | Mermaid fences already round-trip through `CodeFenceBlockNode`/`BlockTypes.CODE_FENCE`; a new node type would duplicate existing structure for no semantic gain |
| Render failure handling | UI-local sum type (`MermaidRenderResult` sealed interface) | Type-driven design; architecture.md §4 | Arrow `Either<DomainError, T>` | This repo's `Either` convention is scoped to repository/service boundaries per `CLAUDE.md`; mermaid rendering has no DB/repository call and no matching `DomainError` variant |
| Render output caching | Flyweight via existing `SteleLruCache` | ADR-001 (LaTeX) `LruCache` precedent; existing `cache/LruCache.kt` | New bespoke cache class | An LRU cache utility already exists in this repo (used by SQLDelight repositories); reusing it avoids duplicating eviction/thread-safety logic and the documented K2-compiler naming workaround |
| JVM/Desktop render engine | Adapter (GraalJS + bundled `mermaid.js`), spike-gated | stack.md, build-vs-buy.md | JCEF (full Chromium via `compose-webview-multiplatform`/KCEF) | JCEF adds a 100+ MB bundle; KCEF's own maintainer archived the desktop WebView wrapper (~Nov 2025) — a real, documented maintenance risk. GraalJS runs on stock JDK 21 with no separate runtime |
| wasmJs DOM overlay (`MermaidSvgOverlay`) | Adapter (DOM-node overlay + `BlendMode.Clear`), spike-gated | pre-mortem.md Failure #1 | Ship the overlay technique directly with no go/no-go gate | Least-precedented piece of the whole plan — borrowed from a single external repo (Hamamas/Kotlin-Wasm-Html-Interop), never proven in this codebase, and one of only two AC1-mandatory v1 targets; treating it identically to the JVM engine spike (Story 3.1.1) closes a pre-mortem P1 gap |
| Android render engine | Adapter (native `android.webkit.WebView`) | build-vs-buy.md, pitfalls.md §4 | GraalJS on Android | Confirmed incompatible with ART (`oracle/graaljs#514`); native WebView also keeps the `fdroid.yml` pipeline free of a bundled Chromium component |
| Android JS↔native bridge | Facade (single typed callback `onRenderResult(json)`, own `CoroutineScope`) | pitfalls.md §3 | General-purpose eval/exec bridge | Untrusted diagram content + `addJavascriptInterface` is a documented RCE-class surface; a minimal exposed method bounds the blast radius |
| iOS v1 | Null Object (`actual` always returns `UnsupportedPlatform`) | LaTeX ADR-001 iOS precedent | WKWebView-backed renderer now | requirements.md AC5 explicitly allows deferral; this repo has zero existing WebView infrastructure to build a net-new WKWebView integration from in v1 |
| Render trigger/gating | View-mode-only composition + `remember(content, theme, width)` keying | architecture.md §3 | Reusing `GraphWriter`'s 500ms save-debounce | That debounce is a persistence concern (disk writes), orthogonal to view rendering; `MermaidBlock` isn't even mounted during active typing, so no extra debounce timer is needed |
| JVM engine concurrency | Actor (`MermaidEngineActor`, dedicated single-threaded dispatcher), mirroring `db/DatabaseWriteActor.kt` | architecture-review Blocker 1, adversarial-review Blocker 2, pitfalls.md §3 | Dispatching `engine.render()` via `PlatformDispatcher.IO` directly | GraalJS `Context` rejects concurrent multi-thread access by default and throws `IllegalStateException`; `PlatformDispatcher.IO` is a multi-threaded pool on JVM, so two concurrently-visible mermaid blocks would crash. A dedicated single-threaded dispatcher also avoids tying up an `IO`-pool thread with CPU-bound work |
| Platform diagram surface dispatch (`MermaidBlock`'s `Rendered` branch) | `expect`/`actual` (`MermaidDiagramSurface`), mirroring `renderMermaid`'s own seam | adversarial-review Blocker 1 | `MermaidBlock` referencing `MermaidSvgCanvas`/`MermaidSvgOverlay`/`MermaidWebViewHost` directly from `commonMain` | `commonMain` cannot reference symbols declared only in `jvmMain`/`wasmJsMain`/`androidMain` source sets — the as-specified direct reference would not compile |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `BlockItem.kt`'s `CODE_FENCE` dispatch arm / `CodeFenceBlock.kt` | architecture.md flags this as the *first* special case the fenced-code path would acquire — no accumulated complexity yet | **Extend as-is (confirmed)** | `CodeFenceBlock.kt` is a single 79-line composable with no branching; `BlockItem.kt`'s dispatch is one flat `when` table. Adding one `codeFenceLanguage(...) == "mermaid"` branch that delegates to a new sibling `MermaidBlock` composable (itself wrapping `CodeFenceBlock` for fallback) is proportional to the change. No refactor-first work or isolating seam is justified for a single new branch |

---

## Observability Plan

- **Logs**: `MermaidBlock`/each platform `renderMermaid` actual logs a structured entry at render start (source length, platform, cache hit/miss) and at the error path (failure reason + source length, never the full diagram source, to avoid dumping user content into logs), reusing the existing `io.opentelemetry:opentelemetry-api` dependency already present on `jvmCommonMain`/`androidMain`.
- **Metrics**: `mermaid.render.duration_ms` (per-platform render latency — the only new operation plausibly >100ms, per ADR-001's WebView cold-start finding); `mermaid.render.cache_hit_ratio` derived from `mermaidRenderCache`'s existing `SteleLruCache.snapshotAndReset()` hit/miss/eviction counters (no new instrumentation needed for this one).
- **Alerts**: no new alerts required — single-user desktop/mobile notes app with no on-call rotation.

## Risk Control

- **Feature flag**: not gated. The fallback-to-raw-code path is the safety net at every failure point (syntax error, timeout, unsupported platform, missing engine) — mirrors ADR-001 (LaTeX), which also shipped without a flag — so a separate kill switch would duplicate existing protection.
- **Rollback procedure**: standard revert via PR close + revert commit.
- **Staged rollout**: full rollout on merge (matches ADR-001 precedent; this app has no user-cohorting infrastructure).

## Unresolved Questions

- [ ] **JVM/Desktop render engine**: GraalJS+`mermaid-java`-style vs. JCEF — blocks Story 3.2.1 (Epic 3.2 as a whole). Default planning assumption is GraalJS-primary per stack.md/build-vs-buy.md's recommendation. — resolved by: Task 3.1.1a's spike, recorded as an addendum to ADR-001.
- [ ] **Exact bundled Mermaid version per platform** (wasmJs can track latest npm `mermaid` 12.0.0; a GraalJS wrapper likely vendors an older version, e.g. 11.4.1; the Android-bundled asset is a free choice) — a real version-skew risk per stack.md. — blocks final dependency pinning in Tasks 2.1.1a, 3.2.1a, 4.1.1a — owner: implementer, record final versions in ADR-001's Open Items.
- [ ] **Whether Skia's `SVGDOM` is already transitively available via Compose Desktop/skiko** with no new Gradle dependency, as assumed in Task 3.2.2a. — blocks Task 3.2.2a — owner: implementer, verify at implementation time; add `org.jetbrains.skiko:skiko-awt` explicitly if it's not already resolved on the classpath.
- [ ] **Android cached-SVG WebView reload path**: confirm `loadDataWithBaseURL` with a pre-rendered SVG string genuinely skips re-invoking `mermaid.render()` JS cost on `LazyColumn` scroll-recycle (vs. accidentally re-executing the full shim page's `<script>` tag). — blocks Task 4.1.3a — owner: implementer, validate during Android renderer implementation.
- [ ] **wasmJs DOM-overlay technique go/no-go** (Task 2.1.2a spike): if the spike returns "no-go" (the `MermaidSvgOverlay` DOM-node-synced-to-canvas-composable technique doesn't survive scroll/resize/theme-toggle without desync), requirements.md AC1 requires diagram rendering on **both** Desktop (JVM) and Web (JS) — this cannot be resolved by silently dropping wasmJs from v1's scope. Task 2.1.2a's fallback plan is to escalate to a human decision-maker (accept imprecise positioning, invest in a follow-up spike for an alternative technique, or descope AC1's Web requirement with explicit stakeholder sign-off) — not a unilateral downgrade. — blocks Task 2.1.3a — owner: implementer, record verdict in ADR-001.
- [x] **Accepted risk (not blocking): wasmJs has no render-hang watchdog.** `wasmJs`'s single-threaded JS event loop means a coroutine-based `withTimeoutOrNull` cannot preempt a synchronous/tight-loop `mermaid.render()` hang — the timeout callback needs the same blocked event loop to fire that the hang is blocking. Task 2.1.1c therefore does not attempt one. Mitigation (isolating `mermaid.render()` in a Web Worker) is deferred to a future follow-up; v1 accepts the risk given Mermaid's hang bugs (mermaid-js#1216, #1060, per pitfalls.md) are rare and mostly triggered by pathological/adversarial input, which `MAX_MERMAID_SOURCE_LENGTH` already substantially reduces. Recorded as an addendum to ADR-001 (Consequences → Negative/Watch-outs).

## Dependency Visualization

```
Phase 1: Core Rendering Abstraction (commonMain contracts)
  Epic 1.1 (types+cache) ─┬─▶ Epic 1.2 (limits/security consts)
                          │
        ┌─────────────────┼─────────────────┬─────────────────┐
        ▼                 ▼                 ▼                 ▼
Phase 2: wasmJs         Phase 3: JVM       Phase 4: Android   Phase 5: iOS
  Epic 2.1 (renderer)     Epic 3.1 (spike)    Epic 4.1           Epic 5.1
  Story 2.1.2 (spike)      │  gates ▼          (WebView +        (Null Object,
    │  gates ▼           Epic 3.2 (engine)     bridge +           no deps)
  Story 2.1.3 (SVG         Epic 3.2.2 (SVG     scroll fix)
   overlay)                 canvas draw)
        │                 │                 │                 │
        └────────┬────────┴────────┬────────┴────────┬────────┘
                  ▼                 ▼                 ▼
           Phase 6: UI integration (MermaidBlock, BlockItem dispatch, ThemeFingerprint)
                  │
                  ▼
           Phase 7: Testing & CI safety (unit, smoke, fallback, Roborazzi)
```

---

## Phase 1: Core Rendering Abstraction

### Epic 1.1: Domain types & cache
**Goal**: Establish the shared `commonMain` contract every platform `actual` and the UI layer depend on, before any platform-specific work starts.

#### Story 1.1.1: Define MermaidRenderResult, MermaidRenderKey, ThemeFingerprint, and the expect renderMermaid function
**As a** SteleKit contributor implementing a platform renderer, **I want** a single shared result type and render-key contract, **so that** every platform's `actual` and the UI layer agree on success/failure/unsupported semantics without duplicating a sealed type per platform.
**Acceptance Criteria**:
- A successful render produces `MermaidRenderResult.Rendered` with a non-blank SVG string.
  - *Given* `MermaidRenderKey(sourceText = "graph TD; A-->B", theme = ThemeFingerprint(isLight = true, colorHash = 1), widthPx = 600)`, *When* a platform's `renderMermaid(key)` completes successfully, *Then* it returns `MermaidRenderResult.Rendered(svg)` with `svg.isNotBlank()` true.
- A failed render never throws — it returns `Failed`.
  - *Given* `sourceText = "graph TD; A --> "` (malformed), *When* `renderMermaid` is invoked, *Then* it returns `MermaidRenderResult.Failed(reason = "...")` and the call does not throw an exception.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderResult.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.kt`

##### Task 1.1.1a: Create MermaidRenderResult sealed interface (~4 min)
- Create `MermaidRenderResult.kt` with `sealed interface MermaidRenderResult { data class Rendered(val svg: String) : MermaidRenderResult; data class Failed(val reason: String) : MermaidRenderResult; data object UnsupportedPlatform : MermaidRenderResult }` plus a `data class ThemeFingerprint(val isLight: Boolean, val colorHash: Int)`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderResult.kt`

##### Task 1.1.1b: Create MermaidRenderKey + expect renderMermaid (~4 min)
- Create `MermaidRenderer.kt` with `data class MermaidRenderKey(val sourceText: String, val theme: ThemeFingerprint, val widthPx: Int)` and `expect suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.kt`

##### Task 1.1.1c: Add mermaidRenderCache singleton (~3 min)
- Create `MermaidCache.kt` with `val mermaidRenderCache = SteleLruCache<MermaidRenderKey, MermaidRenderResult.Rendered>(maxWeight = 50)`, importing the existing `dev.stapler.stelekit.cache.SteleLruCache` — no new cache class.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidCache.kt`

### Epic 1.2: Security & size/timeout limits
**Goal**: Centralize the hardened security posture and worst-case-input guards so every platform applies the same values.

#### Story 1.2.1: Define shared constants for security level, size ceiling, and render timeout
**As a** SteleKit maintainer, **I want** a single source of truth for the Mermaid security level, max source length, and render timeout, **so that** no platform accidentally renders with Mermaid's unsafe `"loose"` default or hangs on a pathological diagram.
**Acceptance Criteria**:
- Oversized mermaid source skips rendering entirely and shows the raw fallback.
  - *Given* a mermaid fence body of 5,000 characters (`> MAX_MERMAID_SOURCE_LENGTH = 2000`), *When* `MermaidBlock` composes, *Then* it renders `CodeFenceBlock` directly without ever calling `renderMermaid`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidLimits.kt`

##### Task 1.2.1a: Create MermaidLimits constants (~3 min)
- Create `MermaidLimits.kt` with `const val MAX_MERMAID_SOURCE_LENGTH = 2000`, `const val MERMAID_RENDER_TIMEOUT_MS = 4000L`, `const val MERMAID_SECURITY_LEVEL = "strict"`, each with a one-line comment citing the GitLab 2K-char precedent / CVE findings from pitfalls.md.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidLimits.kt`

---

## Phase 2: Web/Wasm Renderer (wasmJsMain)

### Epic 2.1: Direct mermaid.js DOM call
**Goal**: Ship the cheapest, highest-fidelity platform first — the web target already runs inside a real DOM, so this validates the shared contract (Phase 1) end-to-end before tackling JVM/Android.

#### Story 2.1.1: wasmJsMain actual renderer + JS interop
**As a** Web/Wasm user, **I want** mermaid fences to render as real diagrams, **so that** parity with GitHub/Obsidian holds on the web build.
**Acceptance Criteria**:
- `mermaid.initialize` is called with the hardened security level before every render.
  - *Given* `MermaidRenderKey(sourceText = "pie title Pets\n  \"Dogs\" : 5", theme = ThemeFingerprint(true, 1), widthPx = 400)` on the wasmJs `actual`, *When* `renderMermaid(key)` executes, *Then* `mermaid.initialize` is called with `securityLevel: "strict"` before `mermaid.render`, and the function returns `Rendered(svg)` with `svg.contains("<svg")` true.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/MermaidJsBindings.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.wasmJs.kt`, `kmp/build.gradle.kts`

##### Task 2.1.1a: Add mermaid npm dependency (~5 min)
- Add `implementation(npm("mermaid", "12.0.0"))` to the existing `wasmJsMain` dependency block (guarded by `enableJs=="true"`, `kmp/build.gradle.kts:154-166`).
- Files: `kmp/build.gradle.kts`

##### Task 2.1.1b: Write MermaidJsBindings external interop (~5 min)
- Write `external`/`JsAny`-based declarations for the global `mermaid` object's `initialize(config: JsAny)` and `render(id: JsString, source: JsString): JsAny` (Promise-returning), per Kotlin/Wasm's `external fun`/`JsAny` interop model — not classic Kotlin/JS `dynamic`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/MermaidJsBindings.kt`

##### Task 2.1.1c: Implement wasmJs actual renderMermaid (~5 min)
- Implement `actual suspend fun renderMermaid`: guard empty/oversized source → `Failed`; call `mermaid.initialize` with `securityLevel = MERMAID_SECURITY_LEVEL`; await `mermaid.render`; wrap in try/catch → `Failed(reason)`; return `Rendered(svg)` on success.
- **No render-hang watchdog on this platform, by design — do not add a `withTimeoutOrNull`.** `wasmJs` runs on a single-threaded JS event loop; a coroutine-based timeout's own callback needs that same event loop to fire, so it cannot preempt a synchronous/tight-loop `mermaid.render()` hang the way JVM's and Android's `withTimeoutOrNull` genuinely can. This is a documented, accepted v1 risk (see Unresolved Questions and ADR-001's Consequences → Negative/Watch-outs), not an oversight — a real mitigation (Web Worker isolation) is deferred to a follow-up.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.wasmJs.kt`

#### Story 2.1.2: Spike — go/no-go on the MermaidSvgOverlay DOM-overlay + BlendMode.Clear technique
**As a** SteleKit maintainer, **I want** a measured go/no-go on the DOM-node-synced-to-canvas-composable overlay technique before building the full wasmJs overlay integration, **so that** Story 2.1.3 isn't built on an unvalidated, single-external-repo-sourced technique for one of only two AC1-mandatory v1 targets (pre-mortem.md Failure #1 — this technique is, unlike the JVM engine choice, not spike-gated anywhere else in the plan).
**Acceptance Criteria**:
- The spike's verdict is recorded in ADR-001 before Story 2.1.3 (Task 2.1.3a) starts.
  - *Given* a prototype overlay positioning a rendered SVG DOM node over a `MermaidBlock`-sized composable, *When* the composable is scrolled, resized, and the window DPI/theme is toggled, *Then* whether the DOM node stays synced to the Compose layout bounds (no visible desync, lag, stale position, or blank gap) is recorded as a go/no-go verdict, appended to `project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md`'s Open Items before Task 2.1.3a starts.
**Files**: `project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md`

##### Task 2.1.2a: Spike — prototype MermaidSvgOverlay DOM-node-synced-to-canvas-composable technique, record decision (time-boxed to one work session, not 2-5 min like other tasks)
- Prototype the DOM-overlay technique (Hamamas/Kotlin-Wasm-Html-Interop precedent, stack.md) positioning a rendered SVG DOM node over a Compose-for-Web composable's on-screen bounds, with a `BlendMode.Clear` region punched into the canvas layer beneath it. Required outputs, all appended to ADR-001's Open Items before Story 2.1.3 starts:
  1. The go/no-go verdict: does the technique survive scroll, resize, and window-DPI/theme changes without visible desync (lag, stale position, or a blank gap where the overlay should be)?
  2. If "no-go": an honest fallback plan, not a silent scope cut. requirements.md AC1 requires rendering on **both** Desktop (JVM) *and* Web (JS) — a no-go here cannot be resolved by dropping wasmJs from v1. The fallback is to **escalate to a human decision-maker** (options: accept imprecise/non-synced positioning, invest in a follow-up spike for an alternative technique, or descope AC1's Web requirement with explicit stakeholder sign-off).
- **This task blocks Task 2.1.3a (Story 2.1.3's `MermaidSvgOverlay` implementation)** — see Unresolved Questions.
- Files: `project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md` (addendum only; no source files)

#### Story 2.1.3: DOM overlay for canvas-rendered Compose Web
**As a** Web/Wasm user, **I want** the rendered SVG to actually appear on screen, **so that** Compose-for-Web's canvas-based rendering (`jscanvas`) doesn't silently drop it.
**Acceptance Criteria**:
- The SVG is positioned as a DOM overlay synced to the composable's on-screen bounds.
  - *Given* `MermaidRenderResult.Rendered(svg)` and a `MermaidBlock` composable measuring 600×300px on screen, *When* the wasmJs `MermaidSvgOverlay` composable is invoked, *Then* an absolutely-positioned DOM node containing `svg` is created at that composable's on-screen bounds, with a matching `BlendMode.Clear` region punched into the canvas layer beneath it.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/MermaidSvgOverlay.kt`

##### Task 2.1.3a: Implement MermaidSvgOverlay composable (~5 min)
- **Do not start this task until Task 2.1.2a's spike has recorded a "go" verdict** — it is a required spike gate, not an afterthought, mirroring Task 3.2.1b's relationship to Task 3.1.1a.
- Implement `MermaidSvgOverlay(svg: String, modifier: Modifier)` using `onGloballyPositioned` to track layout bounds, injecting/removing a DOM node via wasmJs interop, and punching a `BlendMode.Clear` rect in the canvas layer per the DOM-overlay pattern confirmed by Task 2.1.2a's spike.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/MermaidSvgOverlay.kt`

---

## Phase 3: JVM/Desktop Renderer

### Epic 3.1: Engine spike/decision
**Goal**: Resolve the one genuinely open engineering question (GraalJS vs. JCEF) before committing implementation effort, per ADR-001's Open Items.

#### Story 3.1.1: Time-boxed spike — GraalJS+mermaid-java vs. JCEF
**As a** SteleKit maintainer, **I want** a measured go/no-go on GraalJS-hosted mermaid.js before building the full JVM renderer, **so that** Epic 3.2 isn't built on an unvalidated assumption.
**Acceptance Criteria**:
- The spike's verdict is recorded in ADR-001 before Epic 3.2 starts.
  - *Given* a flowchart fixture (`graph TD; A-->B-->C`) rendered once via a GraalJS + `mermaid-java`-style prototype, *When* cold-render and warm-render latency are measured, *Then* the results (ms) and a go/no-go verdict are appended to `project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md`'s Open Items before Task 3.2.1a starts.
**Files**: `project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md`

##### Task 3.1.1a: Spike — prototype GraalJS+mermaid-java, record decision (time-boxed to one work session, not 2-5 min like other tasks)
- Prototype rendering one flowchart fixture via GraalJS + a bundled `mermaid.js` + Apache Batik text-measurement shim (mirroring `aresstack/mermaid-java`'s approach); measure cold-start and cached-render latency. The spike's required outputs, all appended to ADR-001's Open Items before Epic 3.2 starts:
  1. The go/no-go verdict (confirm GraalJS-primary, or flip to JCEF-fallback).
  2. **Confirmation the Apache Batik `SVGTextElement.getBBox()` shim actually works** against the bundled `mermaid.js`'s layout pass (text-heavy diagrams render correctly) — a required output, not an afterthought; Epic 3.2 (specifically Task 3.2.1b) does not proceed until this is confirmed.
  3. The measured cost of serializing renders through a dedicated single-threaded dispatcher (`MermaidEngineActor`, Task 3.2.1d) vs. the naive (broken) concurrent-`PlatformDispatcher.IO` approach, so Epic 3.2 is built on a measured number rather than an assumption.
- **This task blocks all of Epic 3.2** — see Unresolved Questions.
- Files: `project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md` (addendum only; no source files)

### Epic 3.2: Implement JVM renderer
**Goal**: Ship the Desktop path using the spike's chosen engine (default: GraalJS + bundled mermaid.js, per stack.md/build-vs-buy.md's recommendation).

#### Story 3.2.1: GraalJS-hosted mermaid.js renderer
**As a** Desktop user, **I want** mermaid fences to render as diagrams, **so that** parity with the web build holds without a Chromium-sized dependency.
**Acceptance Criteria**:
- Rendering happens within the timeout, with the hardened security level, and is serialized so concurrent renders never touch the shared GraalJS `Context` from more than one thread at a time.
  - *Given* `MermaidRenderKey(sourceText = "sequenceDiagram\n  A->>B: Hi", theme = ThemeFingerprint(true, 1), widthPx = 500)`, *When* the jvmMain `actual renderMermaid` runs, *Then* the call is routed through `MermaidEngineActor`'s dedicated single-threaded dispatcher (never `PlatformDispatcher.IO` directly), returns `Rendered(svg)` within `MERMAID_RENDER_TIMEOUT_MS`, and the GraalJS context was initialized via `mermaid.initialize({securityLevel: "strict"})`.
  - *Given* two `MermaidRenderKey`s for two mermaid blocks visible on screen at once, *When* both `LaunchedEffect`s fire concurrently, *Then* `MermaidEngineActor` serializes the two `engine.render()` calls onto its single dedicated thread — no `IllegalStateException: Multi threaded access requested` is possible because only one coroutine at a time ever runs on that dispatcher.
- Text-heavy diagrams lay out correctly under headless GraalJS.
  - *Given* a diagram fixture containing text labels (e.g. a flowchart with node labels), *When* it renders via `MermaidJvmEngine`, *Then* the Apache Batik `SVGTextElement.getBBox()` shim (confirmed working by Task 3.1.1a's spike) supplies the bounding-box measurements mermaid.js's layout pass needs, and the call does not throw.
**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidJvmEngine.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidEngineActor.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.jvm.kt`, `kmp/build.gradle.kts`

##### Task 3.2.1a: Add GraalJS dependency (~5 min)
- Add `org.graalvm.js:js` (or the artifact chosen by Task 3.1.1a's spike) to the `jvmMain` dependency block.
- Files: `kmp/build.gradle.kts`

##### Task 3.2.1b: Add Apache Batik dependency and wire the BBox shim (~5 min)
- Add the Apache Batik dependency (e.g. `org.apache.xmlgraphics:batik-bridge`/`batik-anim`, per the artifact confirmed by Task 3.1.1a's spike) to the `jvmMain` dependency block, and implement the shim wiring that exposes a `getBBox()`-compatible text-measurement callback into the GraalJS `Context` so mermaid.js's layout pass can call it — headless GraalJS has no DOM API providing this natively (mirrors `aresstack/mermaid-java`'s approach, per ADR-001). **Do not start this task until Task 3.1.1a's spike has confirmed the shim works** — it is a required spike output, not an afterthought.
- Files: `kmp/build.gradle.kts`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidJvmEngine.kt`

##### Task 3.2.1c: Implement MermaidJvmEngine (~5 min)
- Implement `MermaidJvmEngine` owning its own lazily-initialized GraalJS `Context` (never a `rememberCoroutineScope()`-derived object per this repo's coroutine-scope rule), loading the bundled mermaid.js asset and the Batik BBox shim from Task 3.2.1b, calling `mermaid.initialize({securityLevel: MERMAID_SECURITY_LEVEL})` once, exposing `fun render(source: String): String` wrapped in `catch (e: Throwable)` per this repo's native-load-failure rule. This class has no internal concurrency guard of its own — callers (only `MermaidEngineActor`, Task 3.2.1d) are responsible for never invoking `render()` from more than one thread.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidJvmEngine.kt`

##### Task 3.2.1d: Implement MermaidEngineActor (~5 min)
- Implement `MermaidEngineActor`, mirroring `db/DatabaseWriteActor.kt`'s pattern for "one stateful resource, many concurrent coroutine callers": owns a dedicated single-threaded `CoroutineDispatcher` (e.g. `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`, never `PlatformDispatcher.IO` — GraalJS `Context` rejects concurrent multi-thread access by default, and this also keeps CPU-bound render work off the IO pool per architecture-review's Concern) and its own `CoroutineScope` (never a caller-supplied `rememberCoroutineScope()`, per this repo's coroutine-scope-ownership rule). Exposes `suspend fun render(key: MermaidRenderKey): MermaidRenderResult` that hops onto the dedicated dispatcher and delegates to `renderMermaidWith(sharedEngine, key)` (Task 3.2.1e) — every call is serialized because only one coroutine at a time runs on a single-threaded dispatcher.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidEngineActor.kt`

##### Task 3.2.1e: Implement jvmMain actual renderMermaid + internal renderMermaidWith (~4 min)
- Implement `internal suspend fun renderMermaidWith(engine: MermaidJvmEngine, key: MermaidRenderKey): MermaidRenderResult`: empty/oversized-source guard; `withTimeoutOrNull(MERMAID_RENDER_TIMEOUT_MS) { engine.render(source) }` → `Failed("timeout")` on null; try/catch around the call → `Failed(reason)`; else `Rendered(svg)`. This function takes the engine as an explicit parameter (not a global singleton reference) specifically so `MermaidRendererFallbackTest` (Task 7.1.3a) can call it directly with a fake engine, bypassing the actor. Implement `actual suspend fun renderMermaid(key) = mermaidEngineActor.render(key)`, delegating to the shared `MermaidEngineActor` instance (Task 3.2.1d), which itself calls `renderMermaidWith(sharedJvmEngine, key)` on its dedicated dispatcher.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.jvm.kt`

#### Story 3.2.2: SVG-to-Compose rendering on Desktop
**As a** Desktop user, **I want** the rendered SVG to appear as a drawn diagram, **so that** the render pipeline is end-to-end without adding a heavyweight rendering dependency.
**Acceptance Criteria**:
- The SVG draws via Compose `Canvas` with no new heavyweight dependency.
  - *Given* `Rendered(svg = "<svg>...circle...</svg>")`, *When* `MermaidSvgCanvas` parses it via `org.jetbrains.skia.svg.SVGDOM` and draws it in a Compose `Canvas`, *Then* the composable renders without throwing (verify `SVGDOM` is already resolvable via the existing Compose Desktop/skiko classpath — see Unresolved Questions).
**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidSvgCanvas.kt`

##### Task 3.2.2a: Implement MermaidSvgCanvas composable (~5 min)
- Implement `MermaidSvgCanvas(svg: String, modifier: Modifier)` parsing `svg` via `org.jetbrains.skia.svg.SVGDOM` and rendering it inside a Compose `Canvas`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidSvgCanvas.kt`

---

## Phase 4: Android Renderer

### Epic 4.1: WebView-hosted renderer with minimal bridge
**Goal**: Use Android's platform-shipped WebView (zero extra bundle cost, no F-Droid conflict) with the smallest possible JS bridge surface, given the untrusted-input XSS risk.

#### Story 4.1.1: Bundled mermaid.js asset + local-only WebView shim
**As a** SteleKit maintainer, **I want** the Android WebView to load only a local bundled asset, **so that** an attacker-controlled diagram source can never trigger a remote navigation.
**Acceptance Criteria**:
- The WebView never loads a remote URL.
  - *Given* the Android render shim is loaded, *When* `MermaidWebViewBridge` initializes its `WebView`, *Then* it calls `loadDataWithBaseURL(null, htmlShim, "text/html", "UTF-8", null)` or loads `file:///android_asset/mermaid/render.html`, and no `webView.loadUrl("http://...")`/`loadUrl("https://...")` call exists anywhere in the class.
**Files**: `kmp/src/androidMain/assets/mermaid/mermaid.min.js`, `kmp/src/androidMain/assets/mermaid/render.html`

##### Task 4.1.1a: Bundle mermaid.min.js asset (~4 min)
- Add a bundled `mermaid.min.js` (version pinned per ADR-001's Open Items) to the Android assets directory.
- Files: `kmp/src/androidMain/assets/mermaid/mermaid.min.js`

##### Task 4.1.1b: Write local HTML render shim (~4 min)
- Write `render.html` containing a `<div id="d" class="mermaid">` container, a `<script>` tag loading the bundled `mermaid.min.js`, a `mermaid.initialize({securityLevel: "strict"})` call, and an override of `mermaid.parseError` to suppress Mermaid's built-in red error box (ux.md: no error UI should leak through — the raw-fallback path handles that instead).
- Files: `kmp/src/androidMain/assets/mermaid/render.html`

#### Story 4.1.2: Minimal JS↔native bridge and render invocation
**As a** SteleKit maintainer, **I want** the smallest possible JS-to-native bridge surface, **so that** the untrusted-diagram-content + `addJavascriptInterface` RCE-class risk pitfalls.md flags is minimized.
**Acceptance Criteria**:
- Exactly one `@JavascriptInterface` method is exposed.
  - *Given* `MermaidWebViewBridge` is attached via `webView.addJavascriptInterface(bridge, "AndroidBridge")`, *When* the diagram's JS calls back into Kotlin, *Then* the only `@JavascriptInterface`-annotated method reachable is `onRenderResult(json: String)` — `grep -c "@JavascriptInterface" MermaidWebViewBridge.kt` returns `1`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewBridge.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.android.kt`

##### Task 4.1.2a: Implement MermaidWebViewBridge (~5 min)
- Implement `MermaidWebViewBridge` owning its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (never a caller-supplied `rememberCoroutineScope()`, per this repo's coroutine-scope-ownership rule), exposing one `@JavascriptInterface fun onRenderResult(json: String)` that parses `{svg}`/`{error}` and completes a pending `CompletableDeferred<MermaidRenderResult>`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewBridge.kt`

##### Task 4.1.2b: Implement androidMain actual renderMermaid (~5 min)
- Implement `actual suspend fun renderMermaid`: `withContext(PlatformDispatcher.IO)`; empty/oversized guard; `withTimeoutOrNull(MERMAID_RENDER_TIMEOUT_MS)` around `bridge.render(source)`; `catch (e: Throwable)` around WebView/provider init failures (missing/broken WebView provider) → `Failed`/`UnsupportedPlatform`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.android.kt`

#### Story 4.1.3: Scroll isolation and cache-backed WebView reuse
**As a** mobile user scrolling a page full of blocks, **I want** the LazyColumn to remain smoothly scrollable and diagrams not to re-render on every scroll-back, **so that** a mermaid block doesn't degrade the whole page's scroll performance.
**Acceptance Criteria**:
- A cache hit reloads the SVG directly, without re-invoking `mermaid.render()`.
  - *Given* a mermaid block whose `MermaidRenderKey` has a cache hit in `mermaidRenderCache`, *When* the block's WebView-hosting composable recomposes after scroll-back, *Then* it calls `webView.loadDataWithBaseURL` with the cached SVG string directly (no `mermaid.render()` JS call), and the WebView's vertical scroll/touch handling is disabled so it never captures the parent `LazyColumn`'s drag gesture.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewHost.kt`

##### Task 4.1.3a: Implement MermaidWebViewHost (~5 min)
- Implement `MermaidWebViewHost(result: MermaidRenderResult.Rendered, modifier: Modifier)` — an `AndroidView { WebView(context) }` wrapper that disables internal scrolling and loads the cached SVG string directly via `loadDataWithBaseURL`, skipping the bridge/JS-render path entirely on cache hits.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewHost.kt`

---

## Phase 5: iOS Deferred Stub

### Epic 5.1: Null Object actual
**Goal**: Keep the iOS build green without a WebView integration, per ADR-001 and requirements.md AC5.

#### Story 5.1.1: iOS returns UnsupportedPlatform
**As an** iOS user, **I want** mermaid fences to show as raw code (never blank/broken), **so that** the app doesn't crash or show nothing where a diagram would be.
**Acceptance Criteria**:
- iOS always returns `UnsupportedPlatform`, never attempts a render.
  - *Given* any `MermaidRenderKey` on iOS, *When* `renderMermaid(key)` is called, *Then* it returns `MermaidRenderResult.UnsupportedPlatform` synchronously with no WebView/JS engine invoked.
**Files**: `kmp/src/iosMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.ios.kt`

##### Task 5.1.1a: Implement iOS Null Object actual (~2 min)
- Implement `actual suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult = MermaidRenderResult.UnsupportedPlatform`.
- Files: `kmp/src/iosMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderer.ios.kt`

---

## Phase 6: UI Integration

### Epic 6.1: MermaidBlock composable & dispatch
**Goal**: Wire the shared contract (Phase 1) and all four platform renderers (Phases 2–5) into the actual block viewer, with the fallback, focus, and accessibility behavior research called for.

#### Story 6.1.1: MermaidBlock with fallback, focus, and accessibility
**As a** SteleKit user, **I want** mermaid fences to render as diagrams while preserving tap-to-edit and never showing a broken/blank view, **so that** the feature matches this repo's existing block-interaction model.
**Acceptance Criteria**:
- A valid mermaid block renders as a diagram, not raw text.
  - *Given* `Block(blockType = BlockTypes.CODE_FENCE, content = "```mermaid\ngraph TD; A-->B\n```")` in view mode, *When* `MermaidBlock` composes and `renderMermaid` returns `Rendered(svg)`, *Then* the composable calls the `expect` `MermaidDiagramSurface(result, modifier)` (Task 6.1.1a), whose platform `actual` displays `MermaidSvgCanvas`/`MermaidSvgOverlay`/`MermaidWebViewHost` respectively, instead of `CodeFenceBlock`'s monospace text.
- Invalid syntax falls back to the raw code block, not a crash or blank view (requirements.md AC2).
  - *Given* the same block with content `"```mermaid\ngraph TD; A --> \n```"` (malformed), *When* `renderMermaid` returns `Failed("parse error")`, *Then* `MermaidBlock` renders `CodeFenceBlock(content, language = "mermaid", onStartEditing)` — identical to today's behavior for any other fenced block — with no error banner or red box shown.
- Tap-to-edit parity is preserved, keyboard-activatable (requirements.md AC4; ux.md).
  - *Given* a successfully rendered diagram, *When* the user Tab-focuses the diagram surface then presses Enter, *Then* `onStartEditing()` is invoked, matching `CodeFenceBlock.kt:48`'s `.clickable { onStartEditing() }` behavior.
- A generic accessibility label is present when the source has no `accTitle`/`accDescr`.
  - *Given* mermaid source with no `accTitle`/`accDescr` directives, *When* `MermaidBlock` composes the rendered surface, *Then* `Modifier.semantics { contentDescription = "Mermaid diagram — tap to view source" }` is applied (leaving the SVG's own `<title>`/`<desc>` un-stripped when present).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidBlock.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.jvm.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.wasmJs.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.android.kt`, `kmp/src/iosMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.ios.kt`

##### Task 6.1.1a: Define expect/actual MermaidDiagramSurface (~5 min)
- `commonMain` cannot reference `MermaidSvgCanvas`/`MermaidSvgOverlay`/`MermaidWebViewHost` directly — they live in `jvmMain`/`wasmJsMain`/`androidMain` respectively — so `MermaidBlock`'s `Rendered` branch needs its own expect/actual seam, mirroring `renderMermaid`'s pattern (Task 1.1.1b). Declare `expect @Composable fun MermaidDiagramSurface(result: MermaidRenderResult.Rendered, modifier: Modifier)` in `commonMain`, with `actual` implementations: jvmMain delegates to `MermaidSvgCanvas(result.svg, modifier)`, wasmJsMain to `MermaidSvgOverlay(result.svg, modifier)`, androidMain to `MermaidWebViewHost(result, modifier)`, and iosMain to a trivial actual (e.g. rendering nothing/a placeholder) — Kotlin requires an `actual` for every declared target even though iOS's `renderMermaid` never returns `Rendered`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.jvm.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.wasmJs.kt`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.android.kt`, `kmp/src/iosMain/kotlin/dev/stapler/stelekit/ui/components/MermaidDiagramSurface.ios.kt`

##### Task 6.1.1b: Implement MermaidBlock core composable (~5 min)
- Implement `MermaidBlock(content, onStartEditing, modifier, renderer: suspend (MermaidRenderKey) -> MermaidRenderResult = ::renderMermaid)`: extract code body (mirroring `CodeFenceBlock`'s `extractCodeBody`); build a `MermaidRenderKey` via `remember(content, currentThemeFingerprint(), boxWidthPx)`; check `MAX_MERMAID_SOURCE_LENGTH` first; check `mermaidRenderCache` before calling `renderer(key)` (not the global `renderMermaid` directly) in a `LaunchedEffect`; `when` on the result — `Rendered` → `MermaidDiagramSurface(result, modifier)` (Task 6.1.1a), `Failed`/`UnsupportedPlatform` → `CodeFenceBlock(content, "mermaid", onStartEditing)`. The `renderer` default parameter exists specifically so `MermaidBlockScreenshotTest` (Task 7.2.1a) can substitute a fake forced to `Failed` without a later signature change.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidBlock.kt`

##### Task 6.1.1c: Add focus, keyboard activation, and accessibility label (~4 min)
- Add `Modifier.focusable()` + `Modifier.onKeyEvent` (Enter/Space → `onStartEditing()`) and the `contentDescription` fallback to `MermaidBlock`'s rendered-diagram branch; verify the platform diagram surface (WebView/Skia canvas, via `MermaidDiagramSurface`) doesn't swallow focus.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidBlock.kt`

#### Story 6.1.2: Dispatch from BlockItem.kt
**As a** SteleKit maintainer, **I want** the existing `CODE_FENCE` dispatch to route mermaid fences to `MermaidBlock` and everything else unchanged, **so that** non-mermaid fenced code blocks are unaffected (requirements.md AC3).
**Acceptance Criteria**:
- Non-mermaid languages are unaffected; mermaid routes to the new composable.
  - *Given* `block.content = "```kotlin\nval x = 1\n```"`, *When* `BlockItem`'s view-mode `when` evaluates `BlockTypes.CODE_FENCE`, *Then* it still calls `CodeFenceBlock` exactly as today. *Given* `block.content = "```mermaid\ngraph TD; A-->B\n```"` instead, *When* the same arm evaluates, *Then* it calls `MermaidBlock` instead.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`

##### Task 6.1.2a: Extend CODE_FENCE dispatch arm (~3 min)
- In `BlockItem.kt`'s `BlockTypes.CODE_FENCE ->` arm (line 372), branch on `codeFenceLanguage(block.content).equals("mermaid", ignoreCase = true)` to call `MermaidBlock(...)`, else keep the existing `CodeFenceBlock(...)` call. `CodeFenceBlock.kt` itself is not modified.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`

### Epic 6.2: Theme fingerprinting
**Goal**: Ensure cached diagrams never show stale colors after a light/dark theme toggle.

#### Story 6.2.1: Derive ThemeFingerprint from MaterialTheme.colorScheme
**As a** SteleKit user, **I want** diagrams to re-render with correct colors after switching themes, **so that** I don't see a stale-colored cached diagram.
**Acceptance Criteria**:
- A theme change invalidates the cache and triggers a fresh render.
  - *Given* a cached `Rendered` entry keyed on `ThemeFingerprint(isLight = true, ...)`, *When* the app switches to dark mode and the same block recomposes, *Then* `MermaidBlock` computes a new `ThemeFingerprint(isLight = false, ...)`, misses the cache, and calls `renderMermaid` again with mermaid's `theme`/`themeVariables` config derived from the new `MaterialTheme.colorScheme`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderResult.kt`

##### Task 6.2.1a: Implement currentThemeFingerprint() (~4 min)
- Implement `@Composable fun currentThemeFingerprint(): ThemeFingerprint` deriving `isLight` and a stable hash of `colorScheme.{surface, onSurface, primary}` from `MaterialTheme.colorScheme`; called by `MermaidBlock` (Task 6.1.1a) when building its `MermaidRenderKey`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderResult.kt`

---

## Phase 7: Testing & CI Safety

### Epic 7.1: Unit and smoke tests
**Goal**: Verify the shared contract's correctness properties and guard against future Mermaid-version regressions per pitfalls.md.

#### Story 7.1.1: Cache/result-type unit tests
**As a** SteleKit maintainer, **I want** `MermaidRenderKey` equality verified, **so that** the cache correctly misses on any key-component change (especially theme).
**Acceptance Criteria**:
- Keys differing only by theme are unequal.
  - *Given* `key1 = MermaidRenderKey("graph TD;A-->B", ThemeFingerprint(true, 1), 400)` and `key2 = key1.copy(theme = ThemeFingerprint(false, 1))`, *When* `key1 == key2` is evaluated, *Then* it is `false`.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderKeyTest.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderResultTest.kt`

##### Task 7.1.1a: Write MermaidRenderKeyTest and MermaidRenderResultTest (~4 min)
- Write tests verifying data-class equality/hashCode behavior across `sourceText`/`theme`/`widthPx` variations, and that `Failed`/`UnsupportedPlatform`/`Rendered` are distinguishable via exhaustive `when`.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderKeyTest.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRenderResultTest.kt`

#### Story 7.1.2: JVM smoke-test fixture per diagram type
**As a** SteleKit maintainer, **I want** one fixture per supported diagram type verified on JVM, **so that** a future Mermaid version bump that breaks a diagram type is caught in CI (pitfalls.md).
**Acceptance Criteria**:
- Every one of 7 diagram types renders successfully.
  - *Given* fixtures for flowchart, sequence, class, state, ER, pie, and gantt diagrams, *When* `MermaidRenderer.jvm`'s `renderMermaid` is called for each, *Then* every call returns `Rendered` (not `Failed`) within `MERMAID_RENDER_TIMEOUT_MS`.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRendererSmokeTest.kt`

##### Task 7.1.2a: Write MermaidRendererSmokeTest (~5 min)
- Write one small fixture string per diagram type (flowchart, sequence, class, state, ER, pie, gantt), asserting `renderMermaid(...) is Rendered`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRendererSmokeTest.kt`

#### Story 7.1.3: Fallback and timeout behavior
**As a** SteleKit maintainer, **I want** malformed input and pathological hangs both verified to degrade to `Failed`, **so that** requirements.md AC2 ("falls back... instead of crashing or showing a blank/broken view") holds even for the hang case, not just the syntax-error case.
**Acceptance Criteria**:
- Malformed syntax returns `Failed`, never throws.
  - *Given* `sourceText = "graph TD; A --> "`, *When* `renderMermaid` is called on JVM, *Then* it returns `MermaidRenderResult.Failed(reason)` and `renderMermaid` itself never throws.
- A render exceeding the timeout is treated as `Failed`, not a hang.
  - *Given* a fake `MermaidJvmEngine` stub whose `render()` blocks indefinitely, *When* the test calls `renderMermaidWith(fakeEngine, key)` directly (Task 3.2.1e's internal, injectable function — bypassing `MermaidEngineActor` entirely, since the test needs no real serialization), *Then* the `withTimeoutOrNull(MERMAID_RENDER_TIMEOUT_MS)` inside it fires, the test completes within `MERMAID_RENDER_TIMEOUT_MS + 500ms`, and asserts `Failed`.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRendererFallbackTest.kt`

##### Task 7.1.3a: Write MermaidRendererFallbackTest (~5 min)
- Write tests covering malformed-syntax → `Failed` and a timeout-simulation (fake slow `MermaidJvmEngine` passed directly to `renderMermaidWith`) → `Failed`, per pitfalls.md's watchdog requirement.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRendererFallbackTest.kt`

#### Story 7.1.4: wasmJs smoke test (requirements.md AC1 — Web target)
**As a** SteleKit maintainer, **I want** the wasmJs renderer verified with an automated smoke test, **so that** requirements.md AC1's Web (JS) rendering target has the same automated regression coverage the JVM smoke test (Story 7.1.2) already gives Desktop — closing the gap where AC1 had no Phase 7 test at all despite being one of only two v1-mandatory rendering targets.
**Acceptance Criteria**:
- A valid pie-chart source renders successfully via the wasmJs actual.
  - *Given* `MermaidRenderKey(sourceText = "pie title Pets\n  \"Dogs\" : 5", theme = ThemeFingerprint(true, 1), widthPx = 400)`, *When* the wasmJs `actual renderMermaid` is called, *Then* it returns `Rendered(svg)` with `svg.contains("<svg")` true.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRendererWasmJsTest.kt`

##### Task 7.1.4a: Write MermaidRendererWasmJsTest (~4 min)
- Write `renderMermaid_should_returnRenderedSvg_when_pieChartSourceValid`, mirroring Story 2.1.1's own Given/When/Then, asserting the wasmJs `actual renderMermaid` returns `Rendered(svg)` with `svg.contains("<svg")` true for a valid pie-chart fixture. Run via `./gradlew wasmJsTest`.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/components/MermaidRendererWasmJsTest.kt`

#### Story 7.1.5: Interaction and focus tests (requirements.md AC4)
**As a** SteleKit maintainer, **I want** click/tap activation, keyboard activation, and focus-trap-freedom verified for the rendered diagram surface, **so that** requirements.md AC4's tap-to-edit parity and ux.md's keyboard-reachability acceptance criteria (AC9, AC11) have automated regression coverage, not just a manual/UX-review pass.
**Acceptance Criteria**:
- Clicking the rendered diagram invokes `onStartEditing()`.
  - *Given* `MermaidBlock` composed with a fake renderer returning `Rendered(svg)`, *When* the test clicks the rendered `MermaidDiagramSurface` node, *Then* `onStartEditing()` is invoked exactly once — matching `CodeFenceBlock.kt:48`'s existing `.clickable { onStartEditing() }` behavior.
- Enter or Space while focused invokes `onStartEditing()` with no pointer event.
  - *Given* the same composable Tab-focused, *When* the test sends a `Key.Enter` event (and separately a `Key.Spacebar` event) with no click, *Then* `onStartEditing()` is invoked in both cases.
- Tabbing through the diagram surface does not trap or double-stop focus.
  - *Given* a `LazyColumn` with a plain block above and below a `MermaidBlock`, *When* the test tabs from the block above, through the diagram, to the block below, *Then* focus passes through in exactly one tab stop with no loss — exercising the real `MermaidDiagramSurface` host (Skia canvas on JVM) per ux.md's explicit non-goal ("WebView/canvas captures its own focus").
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidBlockInteractionTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidBlockFocusTest.kt`

##### Task 7.1.5a: Write MermaidBlockInteractionTest (~5 min)
- Write `` `clicking rendered diagram invokes onStartEditing` `` and `` `pressing Enter while focused invokes onStartEditing without a pointer event` `` (plus a Space-key variant, per ux.md AC9), using `createComposeRule` + `performClick`/`performKeyInput`, matching validation.md's REQ-4 mapping.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidBlockInteractionTest.kt`

##### Task 7.1.5b: Write MermaidBlockFocusTest (~5 min)
- Write `` `tabbing through the diagram surface does not trap or double-stop focus` ``, matching validation.md's REQ-4 mapping.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidBlockFocusTest.kt`

#### Story 7.1.6: BlockItem CODE_FENCE dispatch regression test (requirements.md AC3)
**As a** SteleKit maintainer, **I want** an automated regression test proving non-mermaid fenced code blocks are unaffected by the new dispatch branch, **so that** requirements.md AC3 has a test rather than relying on code review alone.
**Acceptance Criteria**:
- A `kotlin`-tagged fence still dispatches to `CodeFenceBlock`, never `MermaidBlock`.
  - *Given* `block.content = "```kotlin\nval x = 1\n```"`, *When* `BlockItem`'s `CODE_FENCE` dispatch arm (Task 6.1.2a) evaluates, *Then* `CodeFenceBlock` is called and `MermaidBlock` is not.
- A fence with no language tag doesn't accidentally match the new `"mermaid"` branch.
  - *Given* a fence with no language tag, *When* the same dispatch arm evaluates, *Then* `CodeFenceBlock` is called, not `MermaidBlock`.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/BlockItemCodeFenceDispatchTest.kt`

##### Task 7.1.6a: Write BlockItemCodeFenceDispatchTest (~4 min)
- Write `blockItem_should_dispatchToCodeFenceBlock_when_languageIsKotlin` and `blockItem_should_dispatchToCodeFenceBlock_when_languageTagAbsent`, matching validation.md's REQ-3 mapping — the regression guard for Task 6.1.2a's new dispatch branch.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/BlockItemCodeFenceDispatchTest.kt`

#### Story 7.1.7: Security-directive override resistance, per JS-execution platform
**As a** SteleKit maintainer, **I want** every JS-execution platform verified against an embedded `securityLevel` override directive, **so that** the CVE-class risk pitfalls.md flags (gogs GHSA-26gq-grmh-6xm6, docmost GHSA-r4hj-mc62-jmwj; pre-mortem.md Failure #3) is closed by a test, not just a hardened default that a future edit could silently regress.
**Acceptance Criteria**:
- An embedded `%%{init: {"securityLevel":"loose"}}%%` directive does not weaken the app's strict setting on any JS-execution platform.
  - *Given* a fixture diagram prefixed with `%%{init: {"securityLevel":"loose"}}%%` and a script-injection payload in a node label (e.g. `A["<img src=x onerror=alert(1)>"]`), *When* `renderMermaid` is called on JVM/GraalJS, Android/WebView, and wasmJs in turn, *Then* the injection payload does not execute or appear unescaped in the rendered output on any of the three — verified via a behavior that would differ if the override succeeded (e.g. the payload string appears HTML-escaped in the returned SVG/DOM content, not as a live `<img>` tag with a firing `onerror`).
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidSecurityDirectiveTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewSecurityDirectiveTest.kt`, `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/components/MermaidSecurityDirectiveWasmJsTest.kt`

##### Task 7.1.7a: Write MermaidSecurityDirectiveTest — JVM/GraalJS (~5 min)
- Write a test asserting `renderMermaid`/`MermaidJvmEngine.render()` on the `%%{init: {"securityLevel":"loose"}}%%`-prefixed, injection-payload fixture never re-enables `securityLevel: "loose"` — assert the app's `mermaid.initialize({securityLevel: "strict"})` call is not overridden and the payload is HTML-escaped in the returned SVG string.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidSecurityDirectiveTest.kt`

##### Task 7.1.7b: Write MermaidWebViewSecurityDirectiveTest — Android/WebView (~5 min)
- Write the equivalent Robolectric test against `MermaidWebViewBridge`/`render.html`'s shim, asserting the same directive-override fixture does not re-enable `"loose"` semantics and the payload does not execute — assert via `onRenderResult`'s returned SVG/error payload, since a Robolectric WebView can't observe a live script-execution channel.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewSecurityDirectiveTest.kt`

##### Task 7.1.7c: Write MermaidSecurityDirectiveWasmJsTest — wasmJs (~5 min)
- Write the equivalent wasmJs test against the real `mermaid.js` DOM call, asserting the directive-override fixture's rendered SVG does not contain a live/unescaped injection payload.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/components/MermaidSecurityDirectiveWasmJsTest.kt`

#### Story 7.1.8: Android WebView bridge/host tests (triad engineering-review gap)
**As a** SteleKit maintainer, **I want** `MermaidWebViewBridge`'s minimal-JS-surface invariant and `MermaidWebViewHost`'s cache-hit/scroll-isolation behavior verified by automated tests, **so that** the "exactly one `@JavascriptInterface` method" security invariant (pitfalls.md — an RCE-class surface if a second method is ever added) and the cache-hit-skips-render/no-scroll-capture behavior (Story 4.1.3) have CI coverage instead of only a manual `grep` instruction in the AC text — validation.md's REQ-11/REQ-12 already designed these tests; this story schedules the task that was missing from Phase 7's original list (found by the Phase 4 triad engineering-lens review).
**Acceptance Criteria**:
- The bridge exposes exactly one `@JavascriptInterface`-annotated method.
  - *Given* `MermaidWebViewBridge::class.java`, *When* its methods are inspected via reflection for `@JavascriptInterface`, *Then* exactly one is found — regressing to two or more fails the test, not just a code-review grep.
- A malformed JS→native payload never crashes the bridge.
  - *Given* `onRenderResult` invoked with malformed JSON, *When* the bridge processes it, *Then* the pending `CompletableDeferred` completes with `Failed` and no exception propagates.
- A cache hit skips the `mermaid.render()` JS call and doesn't re-capture scroll.
  - *Given* `MermaidWebViewHost` with a `mermaidRenderCache` hit for the current `MermaidRenderKey`, *When* the host composes, *Then* it calls `loadDataWithBaseURL` with the cached SVG directly (no `mermaid.render()` JS invocation) and the host's internal `WebView` has scrolling disabled so it never captures the parent `LazyColumn`'s scroll gesture.
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewBridgeTest.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewHostTest.kt`

##### Task 7.1.8a: Write MermaidWebViewBridgeTest (~5 min)
- Write `bridge_should_exposeExactlyOneJavascriptInterfaceMethod_when_classInspected` (reflection-based, mirroring plan.md's own `grep -c "@JavascriptInterface"` == 1 verification but enforced in CI) and `bridge_should_completeDeferredWithFailed_when_onRenderResultReceivesMalformedJson`, matching validation.md's REQ-12 mapping.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewBridgeTest.kt`

##### Task 7.1.8b: Write MermaidWebViewHostTest (~5 min)
- Write `mermaidWebViewHost_should_loadDataDirectly_when_cacheHitExists` and `mermaidWebViewHost_should_disableInternalScroll_when_hostedInLazyColumn` (Robolectric), matching validation.md's REQ-11 mapping. Note the real-device scroll-recycle instrumented test (`MermaidWebViewHostInstrumentedTest`, validation.md REQ-11's third row) remains a separate Unresolved Question per plan.md — it needs an emulator/device, not a unit test, and is out of scope for this story.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/MermaidWebViewHostTest.kt`

### Epic 7.2: Roborazzi screenshot-test safety
**Goal**: Add `MermaidBlock` screenshot coverage without introducing the async/live-JS-render flakiness pitfalls.md warns against — confirmed no existing screenshot fixture currently touches `CodeFenceBlock`/`BlockItem`/`CODE_FENCE` (verified via repo search), so there is nothing to retrofit, only a new test to write carefully.

#### Story 7.2.1: New MermaidBlock screenshot test avoids live-render flakiness
**As a** SteleKit maintainer, **I want** the new screenshot test to capture only the deterministic fallback path, **so that** it never depends on live WebView/JS-engine render timing inside the already-fragile Roborazzi/Xvfb CI pipeline.
**Acceptance Criteria**:
- The screenshot test captures the `Failed`/raw-fallback branch only, never a live async render.
  - *Given* a `MermaidBlockScreenshotTest` modeled on `TableBlockScreenshotTest.kt`'s pattern, *When* it calls `captureRoboImage`, *Then* the composable under test is forced into the `Failed`/`CodeFenceBlock` fallback branch by passing a fake to `MermaidBlock`'s `renderer` parameter (Task 6.1.1b) that always returns `Failed` — never a real JS/WebView-driven `Rendered` branch.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidBlockScreenshotTest.kt`

##### Task 7.2.1a: Write MermaidBlockScreenshotTest (~5 min)
- Write a new screenshot test (modeled on `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/TableBlockScreenshotTest.kt`) that renders `MermaidBlock(..., renderer = { _ -> MermaidRenderResult.Failed("test") })` using the injectable `renderer` parameter added in Task 6.1.1b, asserting it visually matches `CodeFenceBlock`'s existing raw rendering.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MermaidBlockScreenshotTest.kt`
