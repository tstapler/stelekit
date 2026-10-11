# Adversarial Review: mermaid-diagrams

**Date**: 2026-09-15
**Verdict**: CONCERNS

## Blockers

(none — all 4 previously-BLOCKED items resolved in this pass)

### Re-review of prior Blockers

1. **No expect/actual seam for the platform diagram surface — RESOLVED.** `MermaidDiagramSurface` is now a fully specified `expect @Composable fun MermaidDiagramSurface(result: MermaidRenderResult.Rendered, modifier: Modifier)` (Domain Glossary, plan.md:27; Pattern Decisions, plan.md:62). Task 6.1.1a (plan.md:349-351) implements the `expect` in `commonMain` plus `actual`s in `jvmMain` (→ `MermaidSvgCanvas`), `wasmJsMain` (→ `MermaidSvgOverlay`), `androidMain` (→ `MermaidWebViewHost`), and a trivial `iosMain` actual, with all five files listed under Story 6.1.1's Files (plan.md:347). Task 6.1.1b's `MermaidBlock` now delegates to `MermaidDiagramSurface(result, modifier)` instead of referencing platform composables directly — the original compile-time blocker is gone.

2. **GraalJS `Context` concurrency unaddressed — RESOLVED.** `MermaidEngineActor` (Domain Glossary, plan.md:39; Pattern Decisions row, plan.md:61) is specified as a dedicated-single-threaded-dispatcher actor mirroring `db/DatabaseWriteActor.kt`, implemented in Task 3.2.1d (`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`, own `CoroutineScope`, never `rememberCoroutineScope()`). Task 3.2.1e routes `actual renderMermaid` through `mermaidEngineActor.render(key)` rather than `PlatformDispatcher.IO` directly. Story 3.2.1's acceptance criteria (plan.md:225-227) explicitly test that two concurrent renders are serialized with no `IllegalStateException`. Task 3.1.1a's spike is required to measure the actor's serialization cost before Epic 3.2 proceeds.

3. **wasmJs render timeout — RESOLVED (as an honest risk acceptance, not a fake timeout).** Task 2.1.1c (plan.md:181-184) explicitly states "No render-hang watchdog on this platform, by design — do not add a `withTimeoutOrNull`" with the correct technical reasoning (single-threaded JS event loop can't preempt itself). This is backed by an `[x]` Unresolved Question entry (plan.md:92) and a dedicated ADR-001 Consequences → Negative/Watch-outs paragraph (ADR-001:73) stating the accepted risk, why JVM/Android's `withTimeoutOrNull` genuinely works but wasmJs's can't, the mitigation path (Web Worker isolation, deferred), and the reason the risk is acceptable now (rare hang bugs + `MAX_MERMAID_SOURCE_LENGTH` narrowing exposure). This is exactly the requested resolution shape — no broken timeout mechanism was added.

4. **Missing Apache Batik dependency/task — RESOLVED.** Task 3.2.1b (plan.md:236-238) explicitly adds the Batik dependency (`org.apache.xmlgraphics:batik-bridge`/`batik-anim`) to the `jvmMain` block and implements the `getBBox()` shim wiring into the GraalJS `Context`, gated on Task 3.1.1a's spike confirming the shim works first. Story 3.2.1's second acceptance criterion (plan.md:228-229) and the spike's required outputs (plan.md:212-214) both reference this confirmation explicitly, closing the gap between ADR-001's stated requirement and the task breakdown.

## Concerns

*(Note added during Phase 4 triad engineering-lens review: the four items below were carried forward verbatim from the pre-repair-pass version of this file per this review's own re-review-scope instructions. Three have since been resolved by later plan.md edits made in response to the Phase 4 pre-mortem and cross-artifact-consistency passes — marked RESOLVED below rather than silently left stale, since a future reader trusting this section at face value would otherwise be misled. Only the last item remains genuinely open.)*

- [x] **RESOLVED — Zero automated test coverage for the Android renderer.** Plan.md now has Story 7.1.8 (Tasks 7.1.8a/7.1.8b: `MermaidWebViewBridgeTest`, `MermaidWebViewHostTest`), added in response to this same gap being independently re-flagged by the Phase 4 Product Triad Review's engineering lens. Covers the reflection-based `@JavascriptInterface`-count assertion and cache-hit/scroll-isolation behavior.

- [x] **RESOLVED — No regression test for per-diagram `securityLevel` override resistance.** Plan.md now has Story 7.1.7 (Tasks 7.1.7a/b/c), added in response to pre-mortem.md Failure #3 (P1) — one test per JS-execution platform (JVM/GraalJS, Android/WebView, wasmJs), each asserting an embedded `%%{init: {"securityLevel":"loose"}}%%` directive with an injection payload does not execute/appear unescaped.

- [x] **RESOLVED — No test coverage at all for the wasmJs renderer.** Plan.md now has Story 7.1.4 (Task 7.1.4a: `MermaidRendererWasmJsTest`), added in response to a cross-artifact-consistency BLOCKER (AC1's Web target had no Phase 7 test task despite validation.md already designing one).

- [x] **RESOLVED — `MermaidSvgOverlay`'s DOM-overlay + `BlendMode.Clear` technique had no spike gate.** Plan.md now has Story 2.1.2 (Task 2.1.2a: timeboxed go/no-go spike, mirroring Task 3.1.1a's JVM-engine spike), added in response to pre-mortem.md Failure #1 (P1). The former direct-implementation task was renumbered to Story 2.1.3/Task 2.1.3a and now explicitly blocks on the spike's "go" verdict.

- [ ] **No verification that this repo's CI JVM/toolchain can host GraalJS's polyglot `Context` without extra flags.** Still open. ADR-001 asserts "GraalJS runs on stock JDK 21 with no separate runtime," but GraalJS-on-non-GraalVM-JDK has historically needed module-system flags depending on version, and nothing in the plan checks this against the actual CI JDK config `./gradlew` resolves. — Fold this verification into Task 3.1.1a's spike output.

## Minors

- GraalJS Community Edition licensing (UPL vs. Oracle's separate, more restrictive GraalVM Enterprise terms) is never verified in the plan/ADR/research before committing to `org.graalvm.js:js` as a dependency — worth a one-line confirmation.
- `mermaidRenderCache` uses the default entry-count weigher (50 entries × weight 1), not the byte-weighted pattern `LruCache.kt`'s own docstring recommends for size-variable payloads (SVG strings vary widely in size). Likely fine at this scale, but undocumented — worth a one-line justification in ADR-001 rather than silent reliance on the secondary constructor's default.
- Task 4.1.2b's Android timeout path returns `Failed` on watchdog expiry but never disposes/recreates the underlying `WebView` — a view stuck mid-JS-execution when Kotlin gives up waiting could remain unresponsive if reused for a later render.
