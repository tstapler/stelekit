# Architecture Review: mermaid-diagrams
**Date**: 2026-09-15
**Verdict**: CONCERNS

## Blockers
(none — all 3 previously-BLOCKED items from the prior pass are resolved in the current plan.md)

Re-review of the 3 targeted items only (full lens sweep not re-run):

- **RESOLVED — GraalJS `Context` concurrency (Epic 3.2).** `MermaidEngineActor` (Task 3.2.1d, plan.md:244-246) owns a dedicated single-threaded `CoroutineDispatcher` (`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`, never `PlatformDispatcher.IO`) and its own `CoroutineScope`, mirroring `db/DatabaseWriteActor.kt`. `MermaidJvmEngine` (Task 3.2.1c, plan.md:240-242) is documented as having no internal concurrency guard, relying entirely on the actor being its only caller. `actual renderMermaid` (Task 3.2.1e, plan.md:248-250) delegates through `mermaidEngineActor.render(key)`, so no path bypasses the actor. Serialization cost is also folded into the Task 3.1.1a spike (plan.md:215). Structurally sound — a single-threaded dispatcher fully resolves GraalJS's single-thread `Context` constraint.

- **RESOLVED — `MermaidBlock` injection seam (Task 6.1.1a vs 7.2.1a).** Task 6.1.1b (renumbered from 6.1.1a; plan.md:353-355) now specifies `MermaidBlock(content, onStartEditing, modifier, renderer: suspend (MermaidRenderKey) -> MermaidRenderResult = ::renderMermaid)`, calling `renderer(key)` rather than the global function directly. Task 7.2.1a's screenshot test substitutes a fake `renderer` forced to `Failed`. Domain Glossary (plan.md:26) and the task body agree. Resolves the DIP/testability gap as requested.

- **RESOLVED — JVM engine layer injection seam (Task 3.2.1c vs 7.1.3a).** Task 3.2.1e (plan.md:248-250) defines `internal suspend fun renderMermaidWith(engine: MermaidJvmEngine, key: MermaidRenderKey): MermaidRenderResult`, taking the engine as an explicit parameter specifically so `MermaidRendererFallbackTest` (Task 7.1.3a, plan.md:420-422) can call it directly with a fake slow engine, bypassing `MermaidEngineActor` entirely. `actual renderMermaid` delegates to the actor, which internally calls `renderMermaidWith(sharedJvmEngine, key)` on its dedicated dispatcher. Matches the requested pattern exactly.

## Concerns

- [ ] **Task 1.1.1a / Task 6.2.1a (`ThemeFingerprint.colorHash`)** — a single `Int` hash of `{surface, onSurface, primary}` is a probabilistic equality check used as a cache key component. A hash collision between two distinct color schemes (e.g. two light-mode variants, if the app ever supports more than one) would serve a stale-colored `Rendered` SVG from `mermaidRenderCache` with no way to detect the mismatch — a real, if low-probability, correctness bug (wrong diagram colors shown), not just an extra cache miss.
  **Recommendation**: store the actual color components (e.g. `ARGB` ints of `surface`/`onSurface`/`primary`) as fields on `ThemeFingerprint` and derive `equals`/`hashCode` from the data class as normal, rather than pre-collapsing to one lossy `Int` before construction.

- [ ] **Tasks 1.2.1a, 2.1.1c, 3.2.1c, 4.1.2b — size-guard duplicated four times.** `MAX_MERMAID_SOURCE_LENGTH` is checked once in `MermaidBlock` (Task 6.1.1a's story) and then again independently inside each of the three platform `actual renderMermaid` implementations ("guard empty/oversized source" appears in all three). This is a parse-at-boundary violation in spirit (Lens 2.7): the invariant "source is within bounds" is re-validated ad hoc at four call sites instead of being enforced once at construction.
  **Recommendation**: centralize the guard in a single shared `commonMain` helper (e.g. a `MermaidRenderKey` factory or a `guardMermaidSize(source): MermaidRenderResult.Failed?` extension) that all four sites call, so a future change to `MAX_MERMAID_SOURCE_LENGTH` or its semantics can't drift out of sync on one platform.

- [x] **RESOLVED — Task 3.2.1c dispatcher choice.** GraalJS execution now runs on `MermaidEngineActor`'s dedicated single-threaded `CoroutineDispatcher` (Task 3.2.1d), never `PlatformDispatcher.IO` — see the first Blocker's resolution above, which fixed this concern as a side effect. Left unmarked in the original re-review pass since this pass was scoped to Blockers only; flagged during the Phase 4 triad engineering-lens review as stale and corrected here rather than left to mislead a future reader.

## Nitpicks

- ADR-001's "Patterns Applied" section labels `SteleLruCache` usage here as **Flyweight** — GoF Flyweight is about sharing fine-grained objects with common intrinsic state to reduce memory, not memoizing computed results by key. This is a memoization/cache pattern, not Flyweight; naming-only, no structural impact.
- Task 6.1.1b flags "verify the platform diagram surface (WebView/Skia canvas) doesn't swallow focus" as something to check during implementation, but this genuine platform risk (Android `WebView` is known to intercept touch/focus) isn't tracked in plan.md's "Unresolved Questions" list alongside the other four implementer-owned open items — worth adding there for consistency.
- `MermaidRenderResult.Rendered(svg: String)` doesn't prevent constructing `Rendered("")` even though Story 1.1.1's acceptance criterion requires `svg.isNotBlank()` — a soft illegal state (Lens 2.6) with low practical impact since it's covered by the story's own test, not enforced by the type.
- `MermaidWebViewBridge`'s "pending `CompletableDeferred`" (Task 4.1.2a) is described in the singular; if Compose's `AndroidView` ever recycles/reuses a `WebView` instance across different `LazyColumn` items (rather than one `WebView` per composable instance, which Task 4.1.3a seems to assume), a stale pending deferred could be left dangling. Worth a one-line note in Task 4.1.2a about clearing/cancelling any pending deferred on host disposal.
