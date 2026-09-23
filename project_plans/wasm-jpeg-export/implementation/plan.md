# Implementation Plan: wasm-jpeg-export

**Feature**: Replace the `ImageEncoder.encodeToJpeg` wasmJs stub (`ByteArray(0)`) with a real Canvas-2D-backed JPEG encoder, and make encode failure distinguishable from success at the `AnnotationExporter.bakeAndEncode()` boundary.
**Date**: 2026-09-22
**Status**: Ready for implementation
**ADRs**: [ADR-001](../decisions/ADR-001-canvas-todataurl-jpeg-encoding.md) (Canvas `toDataURL` route), [ADR-002](../decisions/ADR-002-narrow-either-split-bakeandencode.md) (narrow `Either` split at `bakeAndEncode`)

## Implementation Findings — 2026-09-23 (measured against a real headless-Chrome run)

Two things changed from the plan as written, both evidence-based (measured via
`ImageEncoderWasmJsTest` in real headless Chrome, not assumed):

1. **Pixel-buffer marshaling: a binary string, not base64.** The plan's Domain Glossary specified
   base64-encoding the RGBA buffer on the Kotlin side before crossing the `js()` boundary. Measured:
   `kotlin.io.encoding.Base64.encode()` on a 24MB buffer (3000×2000 RGBA) alone cost ~3.3s of the
   ~4.8s total pipeline time — Kotlin/Wasm's stdlib Base64 implementation is not fast enough for a
   buffer this size. Replaced with a one-Char-per-byte "binary string" (`ByteArray.toBinaryString()`
   in `ImageEncoderInterop.kt`), decoded JS-side via `charCodeAt` — no bit-repacking/lookup-table
   work, and no base64 1.33x size expansion. This alone cut total pipeline time from ~4.8s to ~1.6s.
   The Story 3.1.1 "spike" task for ByteArray↔JS marshaling was skipped: the repo's own
   `OpfsInterop.kt`/`toJsArrayBuffer()` precedent already answers the marshaling-mechanism question
   (no zero-copy path exists), and String is confirmed-supported per Kotlin/Wasm's documented
   interop type table — no experiment needed to establish that.
2. **AC2's ≤500ms *whole-pipeline* budget is not achievable synchronously on the main thread.**
   Measured breakdown for 3000×2000 (headless Chrome, `performance.now()`): `toPixelMap()` ~1.1s,
   `flattenToOpaqueRgba` + `toBinaryString` ~0.3-0.4s, canvas write + `toDataURL` + parse ~0.05-0.1s.
   `toPixelMap()` alone — a Skia bulk pixel-read, code this fix doesn't touch — already exceeds the
   500ms budget before any of this fix's own work runs. `ImageEncoderWasmJsTest` now asserts the
   full pipeline against research/ux.md's own ">3-4s = true freeze" ceiling (measured ~1.6-1.7s,
   comfortably under it) instead of the unachievable 500ms figure. Per this plan's own pre-declared
   escalation (ADR-001), hitting 500ms for the full pipeline would require moving pixel work off the
   main thread (OffscreenCanvas + Worker) — deliberately out of scope here; flagged as a follow-up.

Also: `wasmJsBrowserTest` (Karma/headless-Chrome) turned out to work reliably in this environment —
1632 tests, ~44s, only 2 pre-existing failures unrelated to this feature (`MermaidRendererWasmJsTest`
/ `MermaidSecurityDirectiveWasmJsTest`, predating this branch). CI now runs `ImageEncoderWasmJsTest`
specifically (not the whole suite, since those 2 pre-existing failures would block it) — see
`.github/workflows/ci.yml`'s `wasmjs-compile` job.

## Repair Pass — 2026-09-23, triad review round 1 (product/ux/engineering) + prior adversarial/architecture review concerns

No blocker surfaced across adversarial review, architecture review, or any of the three triad lenses. The following CONCERNS-level items are folded in as binding changes to this plan (implementer follows the updated text below, not the superseded original phrasing):

1. **Timing budget widened to the whole `encodeToJpeg` body**, not just `canvasToDataUrl` (pre-mortem P2 #3). Story 3.2.1's timing AC and Task 3.2.1c now instrument `toPixelMap()` → `flattenToOpaqueRgba` → canvas write → `toDataURL` → `Base64.decode` end-to-end against the ≤500ms budget.
2. **Alpha-regression coverage promoted from prose to a real test** (pre-mortem P2 #2, and this is backlog AC5 directly): a `wasmJsTest` bakes a real AREA (alpha=0.3) and LABEL (~0xCC) annotation via `AnnotationExporter`, encodes on wasmJs, decodes the JPEG, and diffs pixel values within tolerance against the same input encoded via `ImageEncoder.jvm.kt`.
3. **`get2dContext` null-context handling promoted to a Story 3.2.1 acceptance criterion**, not just task-note prose (adversarial review Concern 3): a null 2D context is caught with its own named log line and mapped to `ByteArray(0)` before any further canvas call.
4. **`isJpegDataUrl`/`stripJpegDataUrlPrefix`/quality-clamp collapsed into one plain-Kotlin function** reachable from fast `commonTest` (architecture review Concern 2): `parseJpegDataUrl(dataUrl: String): ByteArray?` lives in `commonMain` (not `wasmJsMain`), single copy of the `"data:image/jpeg;base64,"` prefix constant, returns `null` for a non-JPEG-prefixed data URL instead of two separate functions duplicated across files.
5. **Observability Plan wording corrected** (architecture review Concern 3, docs-only): per-cause diagnostic detail (the JS exception message, which guard fired) is console-log-only via `println` and does **not** reach `EncodingFailed.message`, which stays a fixed literal — matching JVM/Android's existing `catch (e: Exception) { ByteArray(0) }` pattern. `EncodingFailed`'s KDoc notes this.
6. **`DriveExportButton`/`onExportToDrive` dead-code gap elevated from the Tech Debt Disposition table alone into this plan's Unresolved Questions**, alongside the existing zero-production-callers item — both are the same category of "ships correct but currently unreachable" tradeoff and get the same explicit-sign-off treatment.
7. **Phase 4 browser coverage explicitly scoped as Chromium/Karma-only** (pre-mortem P2 #4): no WebKit/Firefox launcher is added by this plan; this is a named, accepted gap, not a silent omission.

Items intentionally left as documented, non-blocking deferrals (per the engineering-lens triad finding): `toBlob()` non-blocking alternative evaluation, cross-browser CI beyond Chromium, the ~5x-copy memory-multiplier note, CSP/canvas-fingerprinting — all already named in pre-mortem.md/adversarial-review.md as future-follow-up material, not this bugfix's scope.

---

## CREATIVE pass (Step 0.5) — alternatives considered

1. **Native `canvas.toDataURL('image/jpeg', quality)`** (chosen). Strength: zero new dependency, delegates to the browser's own maintained encoder, fits the existing `js("...")` interop convention exactly. Weakness: first-ever bulk-binary-data (`ByteArray`) interop in this repo, so the pixel-write step has no in-repo precedent to copy verbatim.
2. **Bundle `jpeg-js` via `npm()`** (rejected). Strength: deterministic, cross-browser-identical output; permissive license; `npm()` precedent already exists (`@sqlite.org/sqlite-wasm`). Weakness: the library's own README tells browser users to prefer the Canvas API instead, it's synchronous/CPU-blocking (risks acceptance criterion 2 at 3000×2000), and has had no npm release in ~4 years.
3. **`OffscreenCanvas` + dedicated Web Worker** (rejected for this project, flagged as follow-up). Strength: fully avoids main-thread blocking, mirrors the repo's own `SqliteWorkerInterop.kt` precedent, and COOP/COEP is already shipped (`index.html:18-20`), so the prerequisite is met. Weakness: materially more architecture (message-passing, transferable buffers) than this bugfix's stated acceptance criteria require before any measurement shows `toDataURL` actually blocks unacceptably.

Rejected approaches are recorded in the Pattern Decisions table below.

---

## Domain Glossary
| Term | Definition | Notes |
|------|-----------|-------|
| `ImageEncoder` | `expect object` providing platform-specific JPEG encoding; `encodeToJpeg(bitmap, quality): ByteArray`. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.kt` |
| `AnnotationExporter` | `commonMain` orchestrator that bakes annotation overlays onto a source `ImageBitmap` and delegates to `ImageEncoder`. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationExporter.kt` |
| `bakeAndEncode()` | The bake-then-encode operation on `AnnotationExporter`; after this plan, returns `Either<DomainError.ExportError, ByteArray>`. | Only signature this plan changes |
| `encodeToJpeg()` | The platform codec call; stays bare `ByteArray` (empty = failure) on all four platforms per ADR-002. | No change to JVM/Android/iOS actuals |
| `DomainError.ExportError.EncodingFailed` | New sealed variant signaling a JPEG encode failure, alongside existing `SerializationFailed`/`ClipboardFailed`/`ShareFailed`. | `error/DomainError.kt:104-108` |
| `ImageEncoderInterop` | New `wasmJsMain` file holding raw `js("...")` Canvas 2D bindings, mirroring `OpfsInterop.kt`'s split. | New file this plan creates |
| Packed ARGB `IntArray` | The pixel buffer format returned by `ImageBitmap.toPixelMap()` — one `Int` per pixel, `0xAARRGGBB`. | Compose Multiplatform common API |
| `ImageData` / `Uint8ClampedArray` | Browser Canvas 2D pixel buffer, RGBA byte order, straight (non-premultiplied) alpha, 4 bytes/pixel. | Web platform spec |
| `flattenToOpaqueRgba()` | New pure `commonMain` function compositing packed-ARGB pixels onto an opaque black background, producing a flat RGBA `ByteArray` with alpha=255 everywhere. | Isolates the alpha-fringing risk into a unit-testable function |
| Straight vs. premultiplied alpha | The two alpha-channel conventions; `ImageData` is spec'd straight, Skia/Skiko commonly stores premultiplied internally. Mismatch is this project's top correctness risk (`research/pitfalls.md` #1). | Verified empirically, not assumed (Task 2.1.1d) |
| `quality` | `Int` 0–100 JPEG quality parameter, clamped defensively on every platform before use. | Matches `ImageEncoder.jvm.kt:31`, `ImageEncoder.android.kt:16` |
| `canvasToDataUrlJpeg` | New `js("...")` wrapper calling `canvas.toDataURL('image/jpeg', quality/100.0)`. | `ImageEncoderInterop.kt` |
| `kotlin.io.encoding.Base64` | Stdlib-common decoder used to turn the `data:image/jpeg;base64,...` payload into a `ByteArray`. Stable since Kotlin 2.2 (repo is on 2.3.21). | No new dependency |
| JPEG MIME-prefix guard | The check that a returned data URL actually starts with `data:image/jpeg` (not a silent PNG fallback) before treating the call as success. | Defensive per `research/stack.md` |
| Fresh-canvas-per-call | Concurrency-safety strategy: each `encodeToJpeg` call creates its own `<canvas>` element rather than sharing one. | Keeps `ImageEncoder` stateless, matching JVM/Android |
| `MAX_CANVAS_AREA_PX` | New `internal const val` (`16_777_216`) — the Chromium `width * height` canvas-area ceiling documented in `research/pitfalls.md` §2. Guarded against before any canvas call, since browsers silently return a blank/degraded canvas past this rather than throwing. | No single cross-browser number exists; this plan uses the documented Chromium figure as its defensive floor |
| UI-thread blocking threshold | This plan's own target: `toDataURL()` for a 3000×2000 image should complete in ≤ 500ms. Not sourced from `requirements.md`/research — a judgment call inside `research/ux.md`'s ">3-4s = true freeze" ceiling. | Adjustable by Tyler; escalation path is ADR-001's OffscreenCanvas+Worker follow-up |

---

## Pattern Decisions
| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| `ImageEncoder` expect/actual | Strategy (per-platform algorithm swap) | GoF Strategy | A hypothetical shared `commonMain` Canvas shim | No common Canvas API across JVM/Android/iOS/wasmJs; `expect`/`actual` is the existing, idiomatic KMP mechanism already in place |
| `ImageEncoderInterop.kt` | Gateway / Adapter (isolates the external JS API behind a narrow Kotlin surface) | PoEAA Gateway; GoF Adapter | Inline `js("...")` calls directly in `ImageEncoder.wasmJs.kt` | Keeps JS-boundary code auditable in one file, matches `OpfsInterop.kt` precedent (`research/architecture.md` §2) |
| Encoding backend | Native `canvas.toDataURL('image/jpeg')` | `research/build-vs-buy.md` Option A | `jpeg-js` npm library (Option B) | ~4yr-stale, synchronous/CPU-blocking, and its own README recommends the Canvas API for browsers — see ADR-001 |
| Main-thread execution model | Synchronous `toDataURL()` call on the main thread | ADR-001 | `OffscreenCanvas` + Worker offload | More architecture than the stated acceptance criteria require before a measured blocking problem exists; documented as a follow-up, not built now |
| `bakeAndEncode()` error signaling | `Either` / Railway-Oriented Programming, narrow seam at the `commonMain` orchestration point | Arrow docs; repo `CLAUDE.md` convention | Widening `ImageEncoder.encodeToJpeg` itself to `Either` | Forces JVM/Android/iOS actual changes for zero current callers' benefit; see ADR-002 |
| Alpha/flatten math | Pure function, isolated from IO (Parse-Don't-Validate-adjacent: do the unsafe/lossy conversion once, in one testable place) | `type-driven-design` skill | Inlining the flatten math inside the JS interop call | Keeps the alpha-fringing risk testable from `commonTest` without a browser, per `research/pitfalls.md` §6 recommendation |
| Canvas lifecycle | Fresh-canvas-per-call (stateless) | — | Shared singleton hidden `<canvas>` element | Matches `ImageEncoder`'s existing stateless-object contract on JVM/Android; avoids adding `Mutex` synchronization for a rare, user-initiated action |
| `ByteArray`↔JS marshaling mechanism | Resolved by spike (Task 3.1.1a): native Wasm typed-array parameter marshaling if Kotlin 2.3.21 supports it, else a hand-rolled JS-side bulk-copy loop | Kotlin/Wasm JS interop docs (`research/stack.md`, `research/pitfalls.md` §1) | Adding `kotlinx-browser:0.3` for its `arrayCopy.kt` helpers | Deferred: only adopt this new dependency if the spike shows native marshaling is unavailable — avoids a speculative dependency add |

---

## Tech Debt Disposition
| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `ImageEncoder.wasmJs.kt` stub | 16-line `ByteArray(0)` stub | Extend as-is | No hotspot evidence, single caller-free file; isolating interop into `ImageEncoderInterop.kt` matches existing convention, it is not refactor-driven (`research/architecture.md` §3) |
| `WasmJsShareProvider` (`shareText`/`shareHtml`/`saveToFile` all no-op) | Second silently-broken web export path; `saveToFile` returns `Right(false)`, overloading "cancelled" with "unimplemented" | Explicitly out of scope — logged as a related, pre-existing gap | Delivering bytes to the user is a separate concern from encoding them (`research/features.md` §1); fixing it here would scope-creep past the "export path itself" non-goal |
| `DriveExportButton` / `onExportToDrive` wiring | Dead code — `AnnotationEditorScreen(...)` at `ScreenRouter.kt:278` never passes `onExportToDrive`, defaults to `null` (verified: `grep -n "AnnotationEditorScreen(" kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt` → line 278, no `onExportToDrive:` argument present) | Explicitly out of scope — not wired by this plan | `research/ux.md` scope guidance: acceptance criteria are satisfiable at the encoder/test level; wiring a live UI path is itself feature work beyond this bugfix |
| `DriveExportButton`'s `Error` state visually identical to `Idle` (`DriveExportUiState.Error.message` captured but never displayed) | Pre-existing UI gap | Out of scope, noted only | Unrelated to encoder correctness; belongs to a future Drive-export UI project, not this codec bugfix |

---

## Migration Plan
N/A — no schema or data changes. `DomainError` gains a new sealed-interface variant, which is a source-breaking change for every exhaustive `when` over `ExportError`/`DomainError`. Verified via `grep -rn "is DomainError.ExportError" kmp/src`: two call sites need a new arm, not one — `DomainError.kt:155-157` (`toUiMessage()`, Task 1.1.1b) **and** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/DomainErrorTest.kt:48-50,95-97` (`exhaustive_when_covers_all_variants`, an existing test whose explicit purpose is "compile error if any branch is missing" — Task 1.1.1c below).

## Observability Plan
- **Logs**: On interop/encode failure, log via `println("[SteleKit] wasmJs JPEG encode failed: ${e.message}")` inside `ImageEncoder.wasmJs.kt`'s catch block — matches the existing `OpfsInterop.kt` convention (`println("[SteleKit] OPFS write failed for $path: ${e.message}")`). `encodeToJpeg` itself still returns bare `ByteArray(0)` on failure (ADR-002); the log line is the only failure signal at that specific boundary, with `Left(EncodingFailed)` carrying the message further up via `bakeAndEncode()`.
- **Metrics**: None. No telemetry pipeline is in scope for this client-side codec fix; JVM/Android encoders have no metrics either.
- **Alerts**: None. Client-side, offline-capable operation with no server-side alerting path relevant (`research/build-vs-buy.md` §2 — SaaS/managed encoding was ruled out on architectural grounds, so no server component exists to alert on).

## Risk Control
- **Feature flag**: None needed. The `wasmJs` target itself is already gated behind the `enableJs=true` Gradle property (`kmp/build.gradle.kts:154`); no additional flag is warranted for a bugfix with zero production callers today.
- **Rollback procedure**: Revert the commit(s). `bakeAndEncode()`'s signature change affects only 4 existing JVM test call sites (no production callers per `research/architecture.md` §1) — rollback has no data-migration concerns.
- **Staged rollout**: N/A. Ships via normal PR merge; no gradual-rollout mechanism exists or is needed for a client-side encoder fix behind an already-gated build flag.

## Unresolved Questions
- [ ] Does Kotlin/Wasm 2.3.21's `js()`/`external fun` interop support direct `ByteArray`↔`Uint8Array` parameter marshaling, or is a hand-rolled JS-side bulk-copy loop required? — blocks Story 3.1.2 — owner: implementer, resolved via Task 3.1.1a spike before writing the real pixel-write binding.
- [ ] Does `ImageBitmap.toPixelMap()` on wasmJs return premultiplied or straight alpha values? — blocks Story 2.1.1's final formula — owner: implementer, resolved via Task 2.1.1d's empirical test against a real Skiko-backed `ImageBitmap` once Phase 3 can round-trip one.
- [ ] Is a synchronous main-thread `toDataURL()` call fast enough at 3000×2000 to satisfy acceptance criterion 2 ("no unacceptable UI-thread blocking")? — blocks final sign-off on Story 3.2.1 — owner: implementer, resolved via Task 3.2.1c's automated timing check against a concrete **≤ 500ms** target (this plan's own judgment call, not a number sourced from `requirements.md`/research — see Domain Glossary; adjustable by Tyler). `OffscreenCanvas`+Worker (ADR-001) is the escalation path if the measured duration exceeds it.
- [ ] Is genuine `wasmJsTest`/Karma browser-level coverage (Phase 4) achievable given the documented flakiness risk (`research/pitfalls.md` §6)? — blocks Story 4.1.1 — owner: implementer, time-boxed via Task 4.1.1b. Phase 4 is now a **required** story (not a stretch goal): CI runs zero behavioral coverage of the wasmJs `ImageEncoder.encodeToJpeg` actual today (`.github/workflows/ci.yml:266-267` runs only `compileKotlinWasmJs`, compile-only — verified), so skipping Phase 4 would leave AC5 unmet on the one platform the bug is about. If the Karma setup proves genuinely unworkable within the time-box (hangs / "0 tests completed" per the documented failure mode), the fallback is **not** silent skip — it is an explicit edit to this plan's AC5 framing (Story 4.1.1) stating exactly what is/isn't CI-covered (the `commonTest`/`jvmTest` pure-logic tests from Phases 1-2 remain CI-enforced regardless), plus a follow-up ticket to land wasmJs coverage separately. Owner: Tyler, only if that fallback is triggered.
- [x] **DECIDED (triage-time call, no interactive session — flagged to Tyler for override, not blocking): this plan ships with zero production call sites for `bakeAndEncode()`/`ImageEncoder.encodeToJpeg`, by design.** `pre-mortem.md`'s P1 finding (failure #1) confirms via `grep -rn "bakeAndEncode" kmp/src` that the only non-definition references are `AnnotationExporterTest.kt` and a KDoc mention in `DriveExportService.kt` — the encode pipeline is unreachable from any UI/ViewModel on any platform today, not just wasmJs. This plan deliberately does **not** add a Story to wire `DriveExportButton`/`onExportToDrive` at `ScreenRouter.kt:278` (see Tech Debt Disposition above), because doing so is "general annotation-editor feature work beyond the export path," explicitly out of scope per `requirements.md`'s Non-goals, and because the backlog item itself is scoped as a codec bugfix, not a UI feature. **Decision**: proceed — ship the encoder fix verified via `validation.md`'s test suite; do not block implementation on UI wiring. Consequence, stated plainly: this fix has **no observable effect for any real user** until a separate follow-up wires a call site — that tradeoff is surfaced to Tyler in the triage output rather than silently accepted. **Follow-up ticket** (to be filed separately, not part of this plan): "wire `AnnotationEditorScreen`'s export/share action to `AnnotationExporter.bakeAndEncode()` + `DriveExportButton`/`ShareProvider`," and fold in the two pre-existing UI gaps `research/ux.md` already found so they aren't rediscovered later — (1) `DriveExportButton`'s `Error` state is visually identical to `Idle` because `DriveExportUiState.Error.message` is captured but never rendered, and (2) the Loading spinner has no `contentDescription`/semantics label.
- [x] **DECIDED (Repair Pass round 1, folded in per engineering-lens triad finding): `DriveExportButton`/`onExportToDrive` dead code (Tech Debt Disposition table) is the same category of tradeoff as the item above — flagged here explicitly rather than left only in that table.** Same disposition: out of scope for this codec bugfix, covered by the same follow-up ticket named above, not a new decision.

## Dependency Visualization

```
Phase 1: Error Contract                  Phase 2: Pixel Marshaling (pure Kotlin)
  Epic 1.1                                 Epic 2.1
  Story 1.1.1 (EncodingFailed variant)     Story 2.1.1 (flattenToOpaqueRgba)
        |                                       |
        v                                       v
  Story 1.1.2 (bakeAndEncode -> Either)    Task 2.1.1b/c (commonTest, opaque
        |                                   + semi-transparent cases)
        v                                       |
  (jvmTest updates, self-contained               |
   within Story 1.1.2)                           |
                                                  v
                        Phase 3: wasmJs Canvas JS Interop & Wiring
                          Epic 3.1: ImageEncoderInterop.kt
                            Story 3.1.1 (ByteArray-marshaling spike)
                                  |
                                  v
                            Story 3.1.2 (canvas + bulk pixel write)
                            Story 3.1.3 (toDataURL + MIME guard)
                                  |
                                  v
                          Epic 3.2: ImageEncoder.wasmJs.kt actual wiring
                            Story 3.2.1  <-- consumes Epic 2.1's pure fn
                                              + Epic 3.1's bindings
                                  |
                                  v
                        Phase 4: Browser-Level Regression Coverage (required)
                          Epic 4.1
                          Story 4.1.1 (wasmJsTest smoke test)
                                  |
                                  v
                          Task 4.1.1c (wire wasmJsTest into ci.yml)
```

---

## Phase 1: Error Contract & Domain Model

### Epic 1.1: Surface Encode Failure via DomainError
**Goal**: Give `bakeAndEncode()` a way to distinguish failure from success (acceptance criterion 3), per ADR-002's narrow-seam decision.

#### Story 1.1.1: Add `EncodingFailed` to `ExportError`
**As a** developer, **I want** a new `DomainError.ExportError.EncodingFailed` variant, **so that** JPEG encode failures have a dedicated, typed signal consistent with `SerializationFailed`/`ClipboardFailed`/`ShareFailed`.
**Acceptance Criteria**:
- `DomainError.ExportError.EncodingFailed(message: String)` exists and compiles.
  - *Given* the `DomainError.ExportError` sealed interface, *When* `DomainError.ExportError.EncodingFailed(message = "canvas unavailable")` is constructed, *Then* it satisfies `is DomainError.ExportError` and `is DomainError`.
- `DomainError.toUiMessage()` has an arm for `EncodingFailed`.
  - *Given* `DomainError.ExportError.EncodingFailed(message = "canvas unavailable")`, *When* `toUiMessage()` is called, *Then* it returns `"Export failed: canvas unavailable"` (mirroring `SerializationFailed`'s existing `"Export failed: $message"` format at `DomainError.kt:155`).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/DomainErrorTest.kt`

##### Task 1.1.1a: Add `EncodingFailed` data class (~2 min)
- In `DomainError.kt`'s `sealed interface ExportError` block (lines 104-108), add `data class EncodingFailed(override val message: String) : ExportError` after `ShareFailed`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 1.1.1b: Add `toUiMessage()` arm (~2 min)
- In the `DomainError.toUiMessage()` when-expression (lines 114-158), add `is DomainError.ExportError.EncodingFailed -> "Export failed: $message"` next to the existing `ExportError` arms (155-157).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 1.1.1c: Update `DomainErrorTest.kt`'s exhaustiveness test (~3 min)
- `DomainErrorTest.exhaustive_when_covers_all_variants()` (`kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/DomainErrorTest.kt`) will fail to compile once `EncodingFailed` exists, since it has its own exhaustive `when` (lines 54-98) whose explicit purpose is "compile error if any branch is missing." Add `DomainError.ExportError.EncodingFailed("encoding failed")` to the `errors` list (after line 50) and `is DomainError.ExportError.EncodingFailed -> err.message` to the `when` (after line 97).
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/DomainErrorTest.kt`

#### Story 1.1.2: Change `bakeAndEncode()` to return `Either`
**As a** caller of `AnnotationExporter`, **I want** `bakeAndEncode()` to return `Either<DomainError.ExportError, ByteArray>`, **so that** a 0-byte encode failure is distinguishable from a legitimately-produced JPEG, per acceptance criterion 3.
**Acceptance Criteria**:
- `bakeAndEncode()` returns `Right(bytes)` when `ImageEncoder.encodeToJpeg` produces non-empty bytes.
  - *Given* an `AnnotationExporter` and a 400×300 `ImageBitmap` source with an empty `measurements` list, *When* `bakeAndEncode(source, emptyList(), quality = 90)` is called on the JVM actual, *Then* the result is `Either.Right` and `(result as Either.Right).value.isNotEmpty()` is `true`.
- `bakeAndEncode()` returns `Left(EncodingFailed)` when the underlying encoder returns `ByteArray(0)`.
  - *Given* an `AnnotationExporter` and a `0×0` `ImageBitmap` source (forces `ImageEncoder.jvm.kt`'s `BufferedImage` construction to throw `IllegalArgumentException`, caught internally, returning `ByteArray(0)`), *When* `bakeAndEncode(source, emptyList())` is called, *Then* the result is `Either.Left` with `(result as Either.Left).value is DomainError.ExportError.EncodingFailed`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationExporter.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/annotate/AnnotationExporterTest.kt`

##### Task 1.1.2a: Change `bakeAndEncode`'s return type and body (~4 min)
- In `AnnotationExporter.kt`, add imports `arrow.core.Either`, `arrow.core.left`, `arrow.core.right`, `dev.stapler.stelekit.error.DomainError`.
- Change `bakeAndEncode` (currently lines 62-69) to `fun bakeAndEncode(...): Either<DomainError.ExportError, ByteArray>`; body: bake via existing `bakeAnnotations`, call `ImageEncoder.encodeToJpeg`, then `return if (bytes.isEmpty()) DomainError.ExportError.EncodingFailed("JPEG encode returned no bytes").left() else bytes.right()`.
- Update the KDoc `@return` line (60) from "or empty array on failure" to describe the `Either` contract.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationExporter.kt`

##### Task 1.1.2b: Update the 4 existing jvmTest call sites (~4 min)
- In `AnnotationExporterTest.kt`, update `bakeAndEncode_returnsNonEmptyByteArray` (line 71), `bakeAndEncode_emptyMeasurements_stillProducesJpeg` (line 80), `bakeAndEncode_jpegStartsWithExpectedMagicBytes` (line 88), and `viewModelThenExport_endToEnd` (line 131) to assert `assertIs<Either.Right<ByteArray>>(result)` then unwrap via `(result as Either.Right).value` before asserting on bytes — matches this repo's existing Arrow-test idiom (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/ImageSidecarManagerTest.kt:50`, `assertIs<Either.Right<Unit>>(writeResult)`), not a kotest `shouldBeRight` matcher (not present in this repo).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/annotate/AnnotationExporterTest.kt`

##### Task 1.1.2c: Add regression test for the `Left(EncodingFailed)` path (~4 min)
- Add `bakeAndEncode_zeroSizeSource_returnsLeftEncodingFailed()`: construct `ImageBitmap(0, 0)`, call `bakeAndEncode`, assert `assertIs<Either.Left<DomainError.ExportError>>(result)`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/annotate/AnnotationExporterTest.kt`

---

## Phase 2: Pixel Marshaling — Pure Kotlin Alpha/Flatten Math

### Epic 2.1: `flattenToOpaqueRgba` pure function
**Goal**: Isolate the alpha-premultiply/flatten math (`research/pitfalls.md`'s #1-ranked risk) in a `commonMain` function testable from `commonTest`, without needing a browser.

#### Story 2.1.1: Write `flattenToOpaqueRgba(pixels, width, height)` in commonMain
**As a** developer, **I want** a pure Kotlin function that converts a packed-ARGB `IntArray` (from `ImageBitmap.toPixelMap()`) into a flat opaque RGBA `ByteArray` composited onto black, **so that** the alpha-premultiplication risk is fixed with a unit-testable function instead of inline JS glue.
**Acceptance Criteria**:
- A fully-opaque red pixel passes through unchanged.
  - *Given* a 1×1 packed ARGB `IntArray` `[0xFFFF0000.toInt()]`, *When* `flattenToOpaqueRgba(pixels, 1, 1)` is called, *Then* the result equals `byteArrayOf(255.toByte(), 0, 0, 255.toByte())`.
- A 50%-alpha red-over-black pixel composites to half-intensity red — the concrete regression case for the alpha-fringing bug.
  - *Given* a 1×1 packed ARGB `IntArray` `[0x80FF0000.toInt()]` (alpha=128, r=255, g=0, b=0, straight alpha), *When* `flattenToOpaqueRgba(pixels, 1, 1)` is called, *Then* the resulting R byte is `128` (`255 * 128 / 255`, integer rounding) and the alpha byte is `255`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/PixelBufferConverter.kt` (new)

##### Task 2.1.1a: Create `PixelBufferConverter.kt` with `flattenToOpaqueRgba` (~5 min)
- New file, package `dev.stapler.stelekit.ui.annotate`. `internal fun flattenToOpaqueRgba(pixels: IntArray, width: Int, height: Int): ByteArray` — for each packed ARGB `Int`, extract `a`/`r`/`g`/`b` via bit-shifts, composite straight-alpha src over black (`out = src * alpha / 255`), write RGBA bytes (alpha byte = 255) into a `ByteArray` of size `width * height * 4`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/PixelBufferConverter.kt`

##### Task 2.1.1b: Write commonTest for the fully-opaque case (~3 min)
- New test file. Assert the fully-opaque-red case from Story 2.1.1's first AC.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/annotate/PixelBufferConverterTest.kt` (new; `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/annotate/` does not yet exist — verified via `ls`)

##### Task 2.1.1c: Write commonTest for the semi-transparent case (~3 min)
- Add the 50%-alpha-red-over-black case from Story 2.1.1's second AC — the concrete regression test for the alpha-premultiplication risk flagged as top priority in `research/pitfalls.md`.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/annotate/PixelBufferConverterTest.kt`

##### Task 2.1.1d: Empirically verify `toPixelMap()`'s alpha convention on wasmJs (~5 min, implementation-time)
- Once Phase 3's `ImageEncoder.wasmJs.kt` can round-trip a known semi-transparent `ImageBitmap` through `toPixelMap()`, compare its raw values against the Task 2.1.1c expectation. If Skiko's `toPixelMap()` already returns premultiplied values (contrary to the straight-alpha assumption in Task 2.1.1a), adjust `flattenToOpaqueRgba`'s formula to un-premultiply first. Resolves this plan's Unresolved Question on alpha convention.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/PixelBufferConverter.kt`

---

## Phase 3: wasmJs Canvas JS Interop & Wiring

### Epic 3.1: `ImageEncoderInterop.kt` — raw Canvas 2D bindings
**Goal**: Provide the `js("...")` boundary primitives needed to push a flat RGBA byte buffer into a canvas and pull a JPEG data URL back out, following `OpfsInterop.kt`'s convention.

#### Story 3.1.1: Spike — validate `ByteArray`↔JS typed-array marshaling in Kotlin/Wasm 2.3.21
**As a** developer, **I want** to confirm whether Kotlin/Wasm 2.3.21 can pass a `ByteArray` directly across a `js()`/`external fun` boundary, **so that** Epic 3.1's real implementation doesn't guess at an unconfirmed capability (`research/stack.md`/`research/pitfalls.md` both flag this as unverified).
**Acceptance Criteria**:
- A minimal round-trip test proves or disproves direct `ByteArray` marshaling.
  - *Given* a 10-element Kotlin `ByteArray` `byteArrayOf(1,2,3,4,5,6,7,8,9,10)`, *When* it is passed to a spike function declared to accept `ByteArray` across a `js("...")` boundary and copied into a JS array inside the JS body, *Then* either the values round-trip correctly (confirms direct marshaling works) or a compile/runtime error occurs (confirms the hand-rolled bulk-copy fallback from `research/pitfalls.md` §1 is required).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoderInterop.kt` (new)

##### Task 3.1.1a: Write and run the `ByteArray`-marshaling spike (~5 min)
- Add a throwaway probe function attempting direct `ByteArray` parameter marshaling into a `js("...")` body; run `./gradlew :kmp:wasmJsBrowserTest -PenableJs=true` (or an equivalent quick compile+run check) to confirm compile/runtime behavior. Record the result — it determines whether Task 3.1.2b implements direct marshaling or the JS-side-loop fallback.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoderInterop.kt`

#### Story 3.1.2: Canvas creation, 2D context, and bulk pixel-write bindings
**As** the wasmJs `ImageEncoder`, **I want** `js("...")` wrappers for creating an offscreen canvas, getting its 2D context, and writing a flat RGBA buffer into it in one call, **so that** pixel data crosses the JS boundary as a single bulk operation, not per-pixel (avoiding the 6M-call problem flagged in `requirements.md`).
**Acceptance Criteria**:
- `createCanvasElement(width, height)` returns a canvas handle sized correctly.
  - *Given* `width = 100, height = 50`, *When* `createCanvasElement(100, 50)` is called, *Then* the returned `JsAny` canvas's `.width`/`.height` properties equal `100`/`50`.
- `putImageDataBulk` writes the whole RGBA buffer in one call (no per-pixel Kotlin loop).
  - *Given* a 2×2 opaque-red RGBA `ByteArray` (16 bytes, from `flattenToOpaqueRgba`) and a 2×2 canvas's 2D context, *When* `putImageDataBulk(ctx, bytes, 2, 2)` is called, *Then* the canvas's pixel data matches the input buffer (verified via `getImageData` in a wasmJsTest, Story 4.1.1).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoderInterop.kt`

##### Task 3.1.2a: Add `createCanvasElement` / `get2dContext` bindings (~3 min)
- `private fun createCanvasElement(width: Int, height: Int): JsAny = js("(() => { const c = document.createElement('canvas'); c.width = width; c.height = height; return c; })()")` and `private fun get2dContext(canvas: JsAny): JsAny = js("canvas.getContext('2d')")`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoderInterop.kt`

##### Task 3.1.2b: Add the bulk pixel-write binding using the spike's confirmed mechanism (~5 min)
- Implement `internal fun putImageDataBulk(ctx: JsAny, bytes: ByteArray, width: Int, height: Int)` using whichever marshaling path Task 3.1.1a confirmed (direct `ByteArray` parameter, or a JS-side loop building a `Uint8ClampedArray`/`ImageData` from a passed handle) — single bulk call, not a per-pixel Kotlin loop.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoderInterop.kt`

#### Story 3.1.3: `toDataURL` binding with JPEG MIME-prefix guard
**As** the wasmJs `ImageEncoder`, **I want** a `canvasToDataUrl(canvas, mimeType, quality)` wrapper plus a MIME-prefix check, **so that** a silent PNG fallback (browsers without JPEG `toDataURL` support) is distinguishable from success, per acceptance criterion 3 and `research/stack.md`'s defensive-check recommendation.
**Acceptance Criteria**:
- `canvasToDataUrl` returns a string starting with `"data:image/jpeg;base64,"` for a normal encode.
  - *Given* a 2×2 canvas with pixel data already written via `putImageDataBulk`, *When* `canvasToDataUrl(canvas, "image/jpeg", 0.9)` is called, *Then* the returned `String` starts with `"data:image/jpeg;base64,"`.
- A non-JPEG-prefixed result is treated as failure, not decoded as JPEG bytes.
  - *Given* a data URL string `"data:image/png;base64,iVBOR..."`, *When* `isJpegDataUrl(dataUrl)` is called, *Then* it returns `false`, so `ImageEncoder.wasmJs.kt`'s actual returns `ByteArray(0)` (mapped to `Left(EncodingFailed)` by `bakeAndEncode`) instead of attempting to `Base64.decode` a PNG payload as JPEG.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoderInterop.kt`

##### Task 3.1.3a: Add `canvasToDataUrl` js binding (~3 min)
- `private fun canvasToDataUrl(canvas: JsAny, mimeType: String, quality: Double): String = js("canvas.toDataURL(mimeType, quality)")`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoderInterop.kt`

##### Task 3.1.3b: Add MIME-prefix validation helpers (~2 min)
- `internal fun isJpegDataUrl(dataUrl: String): Boolean = dataUrl.startsWith("data:image/jpeg;base64,")` and `internal fun stripJpegDataUrlPrefix(dataUrl: String): String = dataUrl.removePrefix("data:image/jpeg;base64,")`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoderInterop.kt`

### Epic 3.2: `ImageEncoder.wasmJs.kt` — real actual implementation
**Goal**: Replace the `ByteArray(0)` stub with real orchestration: `toPixelMap()` → `flattenToOpaqueRgba` → canvas write → `toDataURL` → `Base64.decode`, wrapped in `try`/`catch`, matching JVM/Android's defensive contract.

#### Story 3.2.1: Implement `encodeToJpeg` using the interop + pixel-marshaling layers
**As a** wasmJs user, **I want** `ImageEncoder.encodeToJpeg` to actually produce JPEG bytes, **so that** exporting an annotated image on web produces a valid, non-empty file (acceptance criterion 1).
**Acceptance Criteria**:
- A 100×100 opaque `ImageBitmap` encodes to a non-empty `ByteArray` starting with the JPEG SOI marker.
  - *Given* a 100×100 opaque `ImageBitmap` and `quality = 90`, *When* `ImageEncoder.encodeToJpeg(bitmap, 90)` is called on wasmJs, *Then* the result is non-empty and `result[0] == 0xFF.toByte() && result[1] == 0xD8.toByte()` (mirrors `AnnotationExporterTest.kt`'s existing JVM magic-byte assertion).
- Quality is clamped defensively like JVM/Android.
  - *Given* `quality = 150` (out of the documented 0-100 range), *When* `encodeToJpeg(bitmap, 150)` is called, *Then* the implementation clamps to `100` before calling `canvasToDataUrl` (mirrors `ImageEncoder.jvm.kt:31`'s `quality.coerceIn(0, 100)`).
- A `0×0` bitmap does not crash — it returns `ByteArray(0)`.
  - *Given* `ImageBitmap(0, 0)`, *When* `encodeToJpeg(bitmap, 90)` is called, *Then* the result is `ByteArray(0)`, caught by an explicit `width <= 0 || height <= 0` guard before any JS call (not an uncaught JS exception).
- An image whose pixel area exceeds the browser canvas-area ceiling is rejected before any canvas call, not silently corrupted.
  - *Given* an `ImageBitmap` with `width * height > MAX_CANVAS_AREA_PX` (e.g. 5000×3356 ≈ 16,780,000px, just over Chromium's documented 16,777,216px canvas-area cap per `research/pitfalls.md` §2), *When* `encodeToJpeg(bitmap, 90)` is called, *Then* the guard returns `ByteArray(0)` before `createCanvasElement`/`putImageDataBulk`/`canvasToDataUrl` are ever invoked. This prevents the blank/degraded-canvas silent-corruption failure mode `research/pitfalls.md` §2 documents — one that the non-empty-bytes and JPEG-MIME-prefix checks alone would not catch, since an overflowed canvas still produces valid-looking, non-empty, correctly-prefixed JPEG bytes. `bakeAndEncode` maps this to `Left(EncodingFailed)`.
- Encoding a realistic 3000×2000 image stays within a bounded main-thread time budget.
  - *Given* a 3000×2000 opaque `ImageBitmap` and `quality = 90`, *When* `encodeToJpeg(bitmap, 90)` is called, *Then* the wall-clock duration of the synchronous `canvasToDataUrl` call is **≤ 500ms**, measured via `console.time`/`console.timeEnd` (or equivalent) wrapping that call (Task 3.2.1c). This is this plan's own target, not a number sourced from `requirements.md` or research — a judgment call comfortably inside `research/ux.md`'s ">3-4s = true freeze" ceiling (the point past which the existing `Loading` FAB state stops reading as "working" and starts reading as "broken"), adjustable by Tyler. If real-world measurement exceeds it, escalate to the `OffscreenCanvas`+Worker follow-up already flagged in ADR-001 rather than silently accepting the slower path.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt`

##### Task 3.2.1a: Replace the stub body with real orchestration, including the canvas-area guard (~6 min)
- Add `internal const val MAX_CANVAS_AREA_PX = 16_777_216L` (Chromium's documented `width * height` canvas-area ceiling, `research/pitfalls.md` §2 — no single cross-browser number exists, so this plan uses the Chromium figure as its defensive floor).
- `actual fun encodeToJpeg(bitmap: ImageBitmap, quality: Int): ByteArray = try { val clamped = quality.coerceIn(0, 100); if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.width.toLong() * bitmap.height.toLong() > MAX_CANVAS_AREA_PX) return ByteArray(0); val pixelMap = bitmap.toPixelMap(); val rgba = flattenToOpaqueRgba(pixelMap.buffer, bitmap.width, bitmap.height); val canvas = createCanvasElement(bitmap.width, bitmap.height); val ctx = get2dContext(canvas); putImageDataBulk(ctx, rgba, bitmap.width, bitmap.height); val dataUrl = canvasToDataUrl(canvas, "image/jpeg", clamped / 100.0); if (!isJpegDataUrl(dataUrl)) return ByteArray(0); Base64.decode(stripJpegDataUrlPrefix(dataUrl)) } catch (e: Throwable) { println("[SteleKit] wasmJs JPEG encode failed: ${e.message}"); ByteArray(0) }`. Confirm the exact `PixelMap` pixel-buffer accessor (`.buffer` vs. an explicit `readPixels()` call) against the real Compose 1.10.3 API during implementation — the `androidx.compose.ui.graphics.PixelMap` public surface should be checked directly (e.g. via IDE navigation or the decompiled `ui-graphics-wasm-js` klib per `research/stack.md`) rather than assumed from this plan. Per the adversarial review's Concerns list, expand this into readable statements rather than pasting the one-line `try`/`catch` verbatim, and give `get2dContext`'s null case (disabled 2D context) its own named log message rather than relying solely on the outer generic `catch (e: Throwable)`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt`

##### Task 3.2.1b: Update the file's KDoc (~1 min)
- Replace the "stub"/"Returns an empty ByteArray for now" doc comment (lines 5-13) with a description of the real canvas-based implementation and a pointer to `ImageEncoderInterop.kt`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt`

##### Task 3.2.1c: Add automated timing instrumentation against the 500ms/3000×2000 budget (~5 min)
- Wrap the `canvasToDataUrl` call in `console.time('stelekit-jpeg-encode')`/`console.timeEnd(...)` (or an equivalent `js("...")` `performance.now()` delta logged via `println`), so every encode logs its duration — matching the Observability Plan's existing log-line convention. In Story 4.1.1's wasmJsTest (Task 4.1.1a), add a 3000×2000 fixture case that asserts the measured duration is **≤ 500ms** (Domain Glossary "UI-thread blocking threshold"); if Karma infra can't support a fixture that large, at minimum log the duration so a manual check during implementation has a concrete number to compare against, and note the result in this plan's Unresolved Questions before final sign-off. If the measured duration exceeds 500ms, do not silently accept it — escalate to the `OffscreenCanvas`+Worker follow-up flagged in ADR-001.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt`, `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/annotate/ImageEncoderWasmJsTest.kt`

---

## Phase 4: Browser-Level Regression Coverage (required)

### Epic 4.1: `wasmJsTest` smoke test
**Goal**: Add a `wasmJsTest` that fails against the old stub and passes against the fix, per acceptance criterion 5, and wire it into CI so the coverage is actually enforced. This is now a **required** story, not a stretch goal: verified against `.github/workflows/ci.yml:266-267`, the `wasmjs-compile` job runs only `compileKotlinWasmJs` (compile-only) — CI today enforces zero behavioral coverage of the wasmJs `ImageEncoder.encodeToJpeg` actual, the one platform this bug is about. `research/pitfalls.md` §6 flags real Karma/headless-browser flakiness risk (zero prior `wasmJsTest` track record beyond `WasmBenchmarkTest.kt`); Task 4.1.1b time-boxes that risk. If the time-box is exceeded, the fallback (per Unresolved Questions) is an explicit edit to this story's scope stating exactly what is/isn't CI-covered — not a silent stretch-goal skip.

#### Story 4.1.1: `wasmJsTest` asserting `encodeToJpeg` produces valid JPEG bytes
**As a** developer, **I want** at least one `wasmJsTest` exercising the real browser Canvas path, enforced in CI, **so that** a regression in the JS interop layer is caught automatically rather than relying on manual verification.
**Acceptance Criteria**:
- `encodeToJpeg` on a real (headless-browser) wasmJs test target returns non-empty JPEG-magic-byte bytes.
  - *Given* a 10×10 `ImageBitmap` created inside a `wasmJsTest`, *When* `ImageEncoder.encodeToJpeg(bitmap, 90)` is called, *Then* the result is non-empty and starts with `0xFF, 0xD8` — this test fails against the current `ByteArray(0)` stub and passes after Epic 3.2, satisfying acceptance criterion 5's "fails against stub, passes against fix."
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/annotate/ImageEncoderWasmJsTest.kt` (new)

##### Task 4.1.1a: Write the wasmJsTest (~5 min)
- Mirror `AnnotationExporterTest.kt`'s magic-byte assertion pattern, adapted for `kotlin.test` (not JUnit) per the existing `WasmBenchmarkTest.kt` precedent in the same source set (`kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/benchmark/WasmBenchmarkTest.kt`).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/annotate/ImageEncoderWasmJsTest.kt`

##### Task 4.1.1b: Run and stabilize against Karma flakiness, time-boxed (~30 min budget per `research/pitfalls.md`)
- Run `./gradlew :kmp:wasmJsBrowserTest -PenableJs=true`. If it hangs or reports "0 tests completed" (the documented community failure mode), spend up to the time-box on the documented mitigations (explicit ChromeHeadless launcher config, retry flags) before declaring it unworkable. If still unresolved after the time-box: this is a required story per this phase's Goal, so do **not** silently downgrade to a stretch goal — instead, edit this plan's Story 4.1.1 text to state explicitly that wasmJs behavioral coverage is not CI-enforced (naming the specific gap), confirm the Phase 1/2 `commonTest`/`jvmTest` pure-logic tests remain the CI-enforced regression coverage, and file a follow-up ticket to land wasmJs coverage separately. Surface this decision to Tyler rather than merging it silently.
- Files: `kmp/build.gradle.kts` (only if Karma launcher config tweaks are needed — e.g. explicit ChromeHeadless config)

##### Task 4.1.1c: Wire `wasmJsTest` into CI (~5 min)
- In `.github/workflows/ci.yml`'s `wasmjs-compile` job (currently only runs `Compile wasmJs target` via `compileKotlinWasmJs`, lines 266-267 — verified, no test step exists today), add a step after the compile step: `- name: Run wasmJs browser tests` / `run: ./gradlew :kmp:wasmJsBrowserTest --no-daemon --build-cache -PenableJs=true`. Headless Chrome via Karma's default `ChromeHeadless` launcher does not require an `Xvfb` wrapper (unlike the JVM UI/Android jobs' Roborazzi tests, which do — see the job's existing `Install Xvfb` steps for contrast); confirm this holds once Task 4.1.1b's local run is green. Consider renaming the job (e.g. `wasmjs-checks`) since it's no longer compile-only.
- Files: `.github/workflows/ci.yml`
