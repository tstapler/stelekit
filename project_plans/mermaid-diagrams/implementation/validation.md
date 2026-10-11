# Validation Plan: mermaid-diagrams

**Date**: 2026-09-15

## Happy Path Scenario
Given a page containing a fenced code block tagged ` ```mermaid ` with valid flowchart syntax (the Baseline in requirements.md: "fenced code blocks tagged ```mermaid render as plain text today"), when the page is opened on Desktop (JVM) or Web (JS), then the block renders as an actual diagram in place of raw text, and clicking it still opens the raw-source editor — proving the render pipeline, the fallback safety net, and edit parity all work together.

## Naming convention (confirmed from `kmp/src/jvmTest`)
Two conventions coexist in this repo, both followed below:
- Plain `kotlin.test` unit/integration tests: `methodName_should_ExpectedBehavior_when_condition` (e.g. `ImageSidecarManagerTest.writeThenRead_should_matchOriginal_when_fileSystemSucceeds`).
- JUnit4 Compose/Roborazzi tests (`TableBlockScreenshotTest.kt` precedent): backtick descriptive sentences (e.g. `` `header cells are displayed in light theme` ``).

## Migration Plan check
`grep -n -i "migration" project_plans/mermaid-diagrams/implementation/plan.md` returns zero matches — plan.md has no Migration Plan section and no `CREATE TABLE`/`MigrationRunner` references anywhere (consistent with this being a pure UI-rendering feature with no schema change). **Step 5 (migration reversibility test) is skipped per the task's own instruction**, confirmed directly rather than assumed.

## Requirement → Test Mapping

### Functional requirements (requirements.md Acceptance Criteria 1–6)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-1: Valid mermaid fence renders as diagram on Desktop(JVM) and Web(JS) | `kmp/src/jvmTest/.../MermaidRendererSmokeTest.kt` | `renderMermaid_should_returnRendered_when_flowchartSourceValid` | Unit | Happy path — one fixture per diagram type (flowchart/sequence/class/state/ER/pie/gantt) all return `Rendered` |
| REQ-1 | `kmp/src/jvmTest/.../MermaidBlockTest.kt` | `mermaidBlock_should_composeMermaidDiagramSurface_when_rendererReturnsRendered` | Integration | `MermaidBlock(content, renderer = { Rendered(fixtureSvg) })` via `ComposeTestRule`; asserts `MermaidDiagramSurface` branch is taken (not `CodeFenceBlock`) — exercises the real `LaunchedEffect`/cache/dispatch wiring, not just the pure function |
| REQ-1 | `kmp/src/wasmJsTest/.../MermaidRendererWasmJsTest.kt` | `renderMermaid_should_returnRenderedSvg_when_pieChartSourceValid` | Unit | Happy path — wasmJs `actual`, asserts `svg.contains("<svg")` per Story 2.1.1's own AC |
| REQ-2: Invalid mermaid syntax falls back to raw code block, never crashes/blanks | `kmp/src/jvmTest/.../MermaidRendererFallbackTest.kt` | `renderMermaid_should_returnFailedNotThrow_when_syntaxMalformed` | Unit | Error path — `"graph TD; A --> "` returns `Failed(reason)`, call never throws |
| REQ-2 | `kmp/src/jvmTest/.../MermaidRendererFallbackTest.kt` | `renderMermaidWith_should_returnFailed_when_engineRenderExceedsTimeout` | Unit | Error path — fake slow `MermaidJvmEngine` passed directly to `renderMermaidWith` (bypassing the actor); `withTimeoutOrNull` fires, test completes within `MERMAID_RENDER_TIMEOUT_MS + 500ms` |
| REQ-2 | `kmp/src/jvmTest/.../MermaidBlockScreenshotTest.kt` | `` `renders identically to CodeFenceBlock when renderer returns Failed` `` | Integration | `MermaidBlock(..., renderer = { Failed("test") })`; Roborazzi `captureRoboImage` — deterministic fallback branch only, never a live async render (Story 7.2.1) |
| REQ-3: Non-mermaid fenced blocks unaffected | `kmp/src/jvmTest/.../BlockItemCodeFenceDispatchTest.kt` | `blockItem_should_dispatchToCodeFenceBlock_when_languageIsKotlin` | Unit | Happy path — `` ```kotlin `` fence still calls `CodeFenceBlock`, never `MermaidBlock` |
| REQ-3 | `kmp/src/jvmTest/.../BlockItemCodeFenceDispatchTest.kt` | `blockItem_should_dispatchToCodeFenceBlock_when_languageTagAbsent` | Unit | Error/edge path — a fence with no language tag doesn't accidentally match the new `"mermaid"` branch |
| REQ-3 | — | — | Integration | N/A — pure UI dispatch logic, no data store or external call involved |
| REQ-4: Tap/click and keyboard both preserve `onStartEditing` parity | `kmp/src/jvmTest/.../MermaidBlockInteractionTest.kt` | `` `clicking rendered diagram invokes onStartEditing` `` | Unit (Compose) | Happy path — click on `MermaidDiagramSurface` fires the same `onStartEditing()` callback as `CodeFenceBlock.kt:48` |
| REQ-4 | `kmp/src/jvmTest/.../MermaidBlockInteractionTest.kt` | `` `pressing Enter while focused invokes onStartEditing without a pointer event` `` | Unit (Compose) | Error/edge path — keyboard-only activation (Enter/Space), no mouse, per ux.md AC9 |
| REQ-4 | `kmp/src/jvmTest/.../MermaidBlockFocusTest.kt` | `` `tabbing through the diagram surface does not trap or double-stop focus` `` | Integration | Exercises the real `MermaidDiagramSurface` host (Skia canvas on JVM) for focus-swallowing, per ux.md's explicit non-goal ("WebView/canvas captures its own focus") |
| REQ-5: Platforms without a v1 renderer (iOS) show raw fallback, never a broken/missing view | `kmp/src/iosTest/.../MermaidRendererIosTest.kt` | `renderMermaid_should_returnUnsupportedPlatform_when_calledOnIos` | Unit | Happy path — always returns `UnsupportedPlatform` synchronously, no WebView/JS engine invoked |
| REQ-5 | `kmp/src/jvmTest/.../MermaidBlockTest.kt` | `mermaidBlock_should_renderCodeFenceBlock_when_rendererReturnsUnsupportedPlatform` | Unit | Error/edge path — UI layer treats `UnsupportedPlatform` identically to `Failed` (no distinct "not supported" UI per ux.md Surface 4) |
| REQ-5 | — | — | Integration | N/A — Null Object `actual` has no data store or external call to integrate with |
| REQ-6: A short design note/ADR records the chosen per-platform strategy | `project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md` | — | Doc check (not a test) | Verified by file existence + content review, not an automated test — ADR-001 already exists per plan.md's header and records GraalJS/wasmJs-DOM/Android-WebView/iOS-deferral rationale |

### Supporting design requirements (plan.md Stories with their own Given/When/Then — high-risk architecture-review findings)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-7: `MermaidRenderKey` equality drives correct cache hit/miss (Story 1.1.1/7.1.1) | `kmp/src/businessTest/.../MermaidRenderKeyTest.kt` | `key_should_beEqual_when_allFieldsMatch` | Unit | Happy path |
| REQ-7 | `kmp/src/businessTest/.../MermaidRenderKeyTest.kt` | `key_should_beUnequal_when_themeDiffers` | Unit | Error/edge path — theme-only diff must miss cache (plan.md's explicit example) |
| REQ-7 | `kmp/src/jvmTest/.../MermaidBlockTest.kt` | `mermaidBlock_should_invokeRendererExactlyOnce_when_recomposedWithUnchangedKey` | Integration | Cache-backed: renderer (external call) invoked once across repeated recompositions with the same `MermaidRenderKey` |
| REQ-8: Oversized source skips rendering entirely (Story 1.2.1) | `kmp/src/jvmTest/.../MermaidBlockTest.kt` | `mermaidBlock_should_renderCodeFenceBlock_when_sourceExceedsMaxLength` | Unit | Happy path — 5,000-char body (`> MAX_MERMAID_SOURCE_LENGTH`) never calls `renderMermaid` |
| REQ-8 | `kmp/src/jvmTest/.../MermaidBlockTest.kt` | `mermaidBlock_should_callRenderer_when_sourceAtExactlyMaxLength` | Unit | Error/edge path — boundary value at exactly `MAX_MERMAID_SOURCE_LENGTH` still renders (off-by-one guard) |
| REQ-8 | — | — | Integration | N/A — pure size-gate logic, no external call on the oversized path by definition |
| REQ-9: Hardened security level applied before every render (Story 2.1.1/3.2.1/4.1.1) | `kmp/src/jvmTest/.../MermaidJvmEngineTest.kt` | `render_should_initializeWithStrictSecurityLevel_when_firstCalled` | Unit | Happy path |
| REQ-9 | `kmp/src/jvmTest/.../MermaidJvmEngineTest.kt` | `render_should_neverUseLooseSecurityLevel_when_reinitialized` | Unit | Error/edge path — guards against a future edit accidentally reverting to mermaid.js's unsafe `"loose"` default |
| REQ-9 | `kmp/src/jvmTest/.../MermaidRendererSmokeTest.kt` | `renderMermaid_should_produceRenderedSvg_when_engineConfiguredWithStrictSecurity` | Integration | End-to-end through the real GraalJS engine (external JS runtime call) |
| REQ-10: `MermaidEngineActor` serializes concurrent renders onto one thread (architecture-review Blocker 1) | `kmp/src/jvmTest/.../MermaidEngineActorTest.kt` | `render_should_completeWithoutException_when_calledOnce` | Unit | Happy path |
| REQ-10 | `kmp/src/jvmTest/.../MermaidEngineActorTest.kt` | `render_should_serializeCalls_when_invokedConcurrentlyFromTwoCoroutines` | Unit | Error/edge path — two concurrent `render()` calls never throw `IllegalStateException: Multi threaded access requested`; a shared counter/flag inside a fake engine asserts no overlapping execution |
| REQ-10 | `kmp/src/jvmTest/.../MermaidEngineActorTest.kt` | `render_should_returnDistinctResults_when_twoRealBlocksVisibleSimultaneously` | Integration | Two real `MermaidJvmEngine` instances' worth of work routed through the shared actor singleton — closest thing to the real `LazyColumn`-with-two-visible-blocks scenario |
| REQ-11: Android WebView cache hit skips `mermaid.render()` JS call and doesn't capture scroll (Story 4.1.3) | `kmp/src/androidUnitTest/.../MermaidWebViewHostTest.kt` | `mermaidWebViewHost_should_loadDataDirectly_when_cacheHitExists` | Unit | Happy path |
| REQ-11 | `kmp/src/androidUnitTest/.../MermaidWebViewHostTest.kt` | `mermaidWebViewHost_should_disableInternalScroll_when_hostedInLazyColumn` | Unit | Error/edge path — guards the documented scroll-capture regression risk |
| REQ-11 | `kmp/src/androidTest/.../MermaidWebViewHostInstrumentedTest.kt` | `mermaidWebViewHost_should_notReinvokeMermaidRenderJs_when_scrolledOffAndBackOn` | Integration | Real `WebView` + real scroll-recycle on an emulator/device — the one behavior a Robolectric fake can't fully confirm (flagged as an Unresolved Question in plan.md) |
| REQ-12: Android JS↔native bridge exposes exactly one `@JavascriptInterface` method (pitfalls.md §3, security) | `kmp/src/androidUnitTest/.../MermaidWebViewBridgeTest.kt` | `bridge_should_exposeExactlyOneJavascriptInterfaceMethod_when_classInspected` | Unit | Happy path — reflection/`grep`-style assertion mirroring plan.md's own verification (`grep -c "@JavascriptInterface"` == 1) |
| REQ-12 | `kmp/src/androidUnitTest/.../MermaidWebViewBridgeTest.kt` | `bridge_should_completeDeferredWithFailed_when_onRenderResultReceivesMalformedJson` | Unit | Error/edge path — untrusted diagram-controlled JS payload must not crash the bridge |
| REQ-12 | — | — | Integration | Covered by REQ-11's instrumented test (same bridge, same call path) — not duplicated |
| REQ-13: Theme change invalidates cache and re-renders with new colors (Story 6.2.1) | `kmp/src/businessTest/.../ThemeFingerprintTest.kt` | `currentThemeFingerprint_should_produceDifferentHash_when_colorSchemeSwitchesLightToDark` | Unit | Happy path |
| REQ-13 | `kmp/src/businessTest/.../ThemeFingerprintTest.kt` | `currentThemeFingerprint_should_produceSameHash_when_colorSchemeUnchanged` | Unit | Error/edge path — guards against over-invalidation (theme fingerprint too sensitive, causing REQ-15/ux-AC15 flicker) |
| REQ-13 | `kmp/src/jvmTest/.../MermaidBlockTest.kt` | `mermaidBlock_should_invokeRendererAgain_when_themeSwitchesAfterCacheHit` | Integration | Cache miss + real renderer re-invocation on theme toggle |

## UX Acceptance Tests

Desktop (JVM) and Android are Compose Multiplatform, not web — per the `ui-playwright`/`stelekit-ui-skill` guidance, these use the `stelekit-ui-skill` desktop-automation approach (manual-triggered, screenshot-verified) or Roborazzi screenshot tests, not Playwright. Playwright applies only to the wasmJs/Web target.

| UX Criterion (ux.md #) | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Edit reachable in 1 click/tap or 1 keyboard action | `MermaidBlockInteractionTest.kt` | `` `single click on rendered diagram opens raw-source editor` `` | Compose test (JUnit4) | Render `MermaidBlock` with a valid fixture → click once → assert `BlockEditor`/`isEditing` state flips |
| 2. Exit edit mode re-renders with zero extra clicks | stelekit-ui-skill manual scenario | "edit-exit re-render" | stelekit-ui-skill (desktop automation) | Launch app → open a page with a mermaid block → tap to edit → press Escape → screenshot confirms diagram (or fallback) reappears with no extra button press |
| 3. Invalid syntax shows exact same visual as a normal fenced code block | `MermaidBlockScreenshotTest.kt` | `` `malformed mermaid block matches CodeFenceBlock golden image` `` | Roborazzi | `captureRoboImage` on `MermaidBlock(renderer = Failed)` vs. existing `CodeFenceBlock` golden for the same content |
| 4. Unsupported-platform state indistinguishable from syntax-error/loading | `MermaidBlockScreenshotTest.kt` | `` `UnsupportedPlatform result matches Failed result visually` `` | Roborazzi | Two `captureRoboImage` calls (`renderer = Failed` vs. `renderer = { UnsupportedPlatform }`) diffed against each other, not just against `CodeFenceBlock` |
| 5. No dead ends — every state has a visible exit | stelekit-ui-skill manual scenario | "no dead end walkthrough" | stelekit-ui-skill (desktop automation) | From 1a, 1b, and edit mode in turn, confirm a documented exit exists (click/tap, Escape/tap-away) without consulting docs |
| 6. Empty mermaid block never invokes renderer, shows fallback | `MermaidBlockTest.kt` | `mermaidBlock_should_skipRenderer_when_sourceBodyIsEmpty` | Unit (Compose) | Fake `renderer` spy asserts zero invocations for `` ```mermaid\n```  `` |
| 7. Rendered + fallback share `CodeFenceBlock`'s chrome (padding, corner radius, label) | `MermaidBlockScreenshotTest.kt` | `` `rendered and fallback surfaces share CodeFenceBlock chrome` `` | Roborazzi | Golden-image diff against `CodeFenceBlock`'s existing chrome |
| 8. Non-mermaid fences pixel-identical to current rendering | `CodeFenceBlockScreenshotTest.kt` (extend existing, or new) | `` `kotlin fenced block is unchanged after mermaid dispatch is added` `` | Roborazzi | Re-run/compare existing `CodeFenceBlock` goldens post-change — regression guard for REQ-3 |
| 9. Reachable via Tab, activates on Enter and Space | `MermaidBlockInteractionTest.kt` | `` `Tab then Enter and Tab then Space both invoke onStartEditing` `` | Compose test (JUnit4), `performKeyInput` | Tab-focus the block, send Enter → assert callback; repeat with Space |
| 10. Non-empty accessible name/description always present | `MermaidBlockAccessibilityTest.kt` | `` `rendered diagram exposes accTitle as contentDescription when present` `` / `` `rendered diagram exposes generic fallback contentDescription when accTitle absent` `` | Compose test (JUnit4), semantics tree assertion | Assert `SemanticsProperties.ContentDescription` via `onNodeWithContentDescription` |
| 11. Diagram host doesn't trap focus (Tab through, above→diagram→below) | stelekit-ui-skill manual scenario / `MermaidBlockFocusTest.kt` | `` `tabbing from block above through diagram to block below has no focus loss` `` | Compose test (JUnit4) + stelekit-ui-skill confirmation on real WebView/Skia hosts | Automated for the pure-Compose seam; manual pass required for the real platform `MermaidDiagramSurface` (WebView/canvas) per plan.md's own note that this needs implementation-time verification |
| 12. Color contrast of SteleKit-rendered text meets WCAG AA | ux-review checklist (no new test — inherited unchanged) | — | Manual (design-review / `ux-expert` skill) | Confirm no new color introduced; reuse `CodeFenceBlock`'s existing `onSurfaceVariant` — a visual/contrast-tool spot check, not a unit test, since nothing new is added |
| 13. Screen readers never see mermaid's own low-quality default fallback | `MermaidBlockAccessibilityTest.kt` | `` `screen reader never encounters mermaid default node-list fallback` `` | Compose test (JUnit4) + manual screen-reader pass (stelekit-ui-skill or OS screen reader) | Automated: semantics tree exposes the injected label, not raw SVG text nodes. Manual: confirm with an actual screen reader (VoiceOver/TalkBack/desktop equivalent) |
| 14. Render failure never propagates an exception into recomposition; rest of page stays interactive | `MermaidBlockStabilityTest.kt` | `` `page with malformed mermaid block alongside editable blocks stays fully interactive` `` | Compose test (JUnit4) | Compose a `LazyColumn` with a malformed mermaid block + normal text blocks; assert no exception thrown and sibling blocks remain clickable/editable |
| 15. No flicker on repeated recomposition of unchanged source | stelekit-ui-skill manual scenario / `MermaidBlockTest.kt` | `mermaidBlock_should_notReinvokeRenderer_when_recomposedWithoutContentChange` (automated) + "scroll flicker check" (manual) | Compose test (JUnit4) for the automatable invariant; stelekit-ui-skill screenshot-diff scroll pass for the perceptual flicker claim | Automated: renderer call count == 1 across N recompositions. Manual: scroll a page with a mermaid block up/down repeatedly, screenshot before/after, confirm no visible flash |

## Test Stack
- **Unit**: `kotlin.test` (`kotlin.test.Test`/`assertEquals`/`assertIs`) for pure Kotlin logic (result types, keys, engine/actor behavior), matching existing convention in `kmp/src/jvmTest/.../db/ImageSidecarManagerTest.kt` and `kmp/src/businessTest`.
- **Compose UI unit/integration**: JUnit4 (`org.junit.Test`) + `androidx.compose.ui.test.junit4.createComposeRule`, matching `TableBlockScreenshotTest.kt`'s existing pattern; backtick test names for this style.
- **Integration**: same frameworks, exercising real collaborators (real `MermaidJvmEngine`/GraalJS, real `SteleLruCache`, real Compose recomposition) instead of fakes, per the injectable `renderer` seam plan.md deliberately added to `MermaidBlock` (Task 6.1.1b) and `renderMermaidWith` (Task 3.2.1e).
- **E2E / UX**: Roborazzi screenshot tests (`./gradlew jvmTest -Proborazzi.test.record=true` to record goldens) for visual-consistency claims (chrome parity, fallback-matches-CodeFenceBlock); `stelekit-ui-skill` desktop automation for interactive flows a screenshot diff can't capture (click-to-edit, no-dead-ends walkthrough, real focus-trapping on WebView/canvas hosts); manual screen-reader/contrast spot checks for accessibility claims that have no headless equivalent.

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM | `./gradlew jacocoTestReport` → check `kmp/build/reports/jacoco/` | ≥80% line, with `ui/components/Mermaid*.kt` and `MermaidEngineActor.kt`/`MermaidJvmEngine.kt` specifically checked (concurrency-safety code is exactly the kind of logic coverage numbers alone can hide — REQ-10's concurrent-call test is the real gate, not the percentage) |
| Android | `./gradlew testDebugUnitTest` (Robolectric) + `./gradlew connectedAndroidTest` for the one instrumented WebView test (REQ-11) | All `androidUnitTest` cases green; instrumented test run at least once per release candidate (emulator-dependent, not every CI run) |
| wasmJs | `./gradlew wasmJsTest` (if configured) or manual Web build smoke test | REQ-1's wasmJs smoke test passes; no coverage percentage tracked separately for this target per existing repo convention |

- All public service methods (`renderMermaid` actuals, `MermaidBlock`, `MermaidEngineActor`): happy path + error paths covered.
- All external integrations (GraalJS context, Android WebView, wasmJs `mermaid` npm package): unit mocked/faked (via the injectable `renderer`/engine seams) **and** at least one integration test exercising the real dependency (`MermaidRendererSmokeTest`, the instrumented WebView test).
- Every one of ux.md's 15 UX acceptance criteria has a corresponding automated test, manual step, or both — see the UX Acceptance Tests table above; none are left uncovered.
- CI safety per plan.md Epic 7.2: the only screenshot test with async/JS-render risk (`MermaidBlockScreenshotTest`) is written to force the deterministic `Failed` branch via the injectable `renderer`, never a live WebView/JS-engine render — this must hold for every new Roborazzi test added under this feature, not just the first one.
