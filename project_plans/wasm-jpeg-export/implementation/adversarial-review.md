# Adversarial Review: wasm-jpeg-export

**Date**: 2026-09-22
**Verdict**: CONCERNS

## Re-Review of Iteration-1 Blockers

All three prior blockers are **RESOLVED** in the current `plan.md`. No code/CI changes were expected or found at this phase — verified `git status --porcelain` shows only the untracked `project_plans/wasm-jpeg-export/` directory, no modifications to any tracked file, so `.github/workflows/ci.yml` is confirmed unchanged (`wasmjs-compile` job still runs only `compileKotlinWasmJs` at lines 266-267, matching the plan's own citation of that fact). The review below checks whether the plan's *description* of each fix is concrete and actionable, not whether the repo already reflects it.

1. **AC2 threshold — RESOLVED.** The plan now states a concrete number: "toDataURL() for a 3000×2000 image should complete in ≤ 500ms" (Domain Glossary, "UI-thread blocking threshold"), repeated as a binding acceptance criterion in Story 3.2.1 ("Encoding a realistic 3000×2000 image stays within a bounded main-thread time budget... ≤ 500ms"). Critically, "manual timing check" language is gone entirely (confirmed via grep — zero hits) and is replaced by Task 3.2.1c, which wires the measurement into the required Phase 4 `wasmJsTest` as an automated 3000×2000 fixture case asserting the threshold, becoming a real regression gate rather than a one-off developer observation — exactly the fix the original blocker recommended. The escalation path (OffscreenCanvas+Worker per ADR-001) if the budget is exceeded is also stated. Minor fallback: if Karma can't support a fixture that large, the plan permits falling back to logging the duration for a manual check — but this is explicitly gated behind "genuinely unworkable" and requires noting the gap in Unresolved Questions before sign-off, not a silent revert to manual-only.

2. **Phase 4 required + CI wiring — RESOLVED.** Phase 4's heading is now literally "Browser-Level Regression Coverage (**required**)" and its Epic 4.1 goal explicitly states "This is now a **required** story, not a stretch goal," citing the same `ci.yml:266-267` compile-only fact from the original blocker. Task 4.1.1c adds a concrete new CI step to the `wasmjs-compile` job (`run: ./gradlew :kmp:wasmJsBrowserTest --no-daemon --build-cache -PenableJs=true`) and suggests renaming the job since it's no longer compile-only. The Dependency Visualization diagram was updated to show Phase 4 flowing into "Task 4.1.1c (wire wasmJsTest into ci.yml)" as its final node. The fallback if Karma proves genuinely unworkable is no longer a silent skip: Task 4.1.1b requires an explicit edit to Story 4.1.1's text naming the specific CI gap, confirms Phase 1/2 `commonTest`/`jvmTest` coverage remains enforced, and requires a follow-up ticket plus explicit surfacing to Tyler — addressing the "silently narrow AC5" complaint directly.

3. **Canvas area-overflow guard — RESOLVED.** A new `MAX_CANVAS_AREA_PX` constant (16,777,216, the documented Chromium ceiling) is defined in the Domain Glossary and implemented in Task 3.2.1a's orchestration sketch as a guard (`bitmap.width.toLong() * bitmap.height.toLong() > MAX_CANVAS_AREA_PX`) checked *before* `createCanvasElement`/`putImageDataBulk`/`canvasToDataUrl` are ever called, returning `ByteArray(0)` (→ `Left(EncodingFailed)` via `bakeAndEncode`) instead of silently proceeding. Story 3.2.1 has a dedicated acceptance criterion with a concrete overflow fixture (5000×3356 ≈ 16.78M px) explicitly asserting the guard fires before any canvas call — directly closing the "looks successful, is actually wrong" gap the original blocker described.

No new blocker was introduced by these edits.

## Sanity Pass: Internal Consistency

Plan remains internally consistent after the edits:
- Task numbering is unbroken and non-duplicated across all four phases (1.1.1a-c, 1.1.2a-c, 2.1.1a-d, 3.1.1a, 3.1.2a-b, 3.1.3a-b, 3.2.1a-c, 4.1.1a-c).
- The Dependency Visualization diagram's Phase 4 box label ("required") matches the prose heading exactly, and its terminal node (Task 4.1.1c, CI wiring) matches the new Task 4.1.1c text.
- No leftover "stretch goal" language contradicts the new "required" framing — the only remaining occurrences of "stretch" are negations ("not a stretch goal") that reinforce the fix.
- `MAX_CANVAS_AREA_PX` is referenced consistently between the Domain Glossary, Story 3.2.1's AC, and Task 3.2.1a's implementation sketch (same name, same value, same guard condition).

One incidental fix found during this pass: iteration 1's Concern "no automated coverage at realistic image scale (~3000×2000)" is now substantively addressed as a side effect of the Blocker 1/2 fixes — Task 3.2.1c explicitly adds a 3000×2000 fixture to the now-required Phase 4 `wasmJsTest`. It is not carried forward below (its own fallback-to-manual-logging path, gated on Karma feasibility, is the same caveat noted under Blocker 1 above).

## Concerns
*(carried forward unchanged from iteration 1, except where noted above; not re-litigated this pass)*

- [ ] **AC1's UI-reachability is still resolved only inside the Tech Debt Disposition table, not elevated to an Unresolved Question / Tyler sign-off.** The `DriveExportButton`/`onExportToDrive` dead-code row (Tech Debt Disposition) still just says "Explicitly out of scope — not wired by this plan," with no corresponding entry in Unresolved Questions. — **Recommendation**: move this to Unresolved Questions for explicit sign-off, matching the treatment Phase 4 got.
- [ ] **`toBlob()` as a non-blocking middle ground is still never evaluated or explicitly rejected.** ADR-001 is unchanged (verified: no `toBlob` mentions in either ADR file) — the plan still escalates straight from synchronous `toDataURL()` to the full OffscreenCanvas+Worker rearchitecture with nothing in between. — **Recommendation**: add one line to ADR-001 stating why `toBlob()` was or wasn't considered.
- [ ] **`get2dContext`/`canvas.getContext('2d')` null-handling is a directive, not a binding acceptance criterion.** Task 3.2.1a's text now explicitly instructs the implementer to "give `get2dContext`'s null case its own named log message rather than relying solely on the outer generic `catch (e: Throwable)`" — a direct response to this concern — but the orchestration sketch itself (the literal one-line `try`/`catch`) still has no such check, and no Story 3.2.1 acceptance criterion tests for it. — **Recommendation**: promote this from prose-in-a-task-note to an actual AC bullet under Story 3.2.1, so it isn't lost if the sketch is copied as-is.

## Minors
- Tainted-canvas `SecurityError` is still structurally avoided (unchanged pipeline: `putImageData`, never `drawImage`) but still never stated in ADR-001 — reads as an omission rather than a considered non-issue.
- CSP-blocks-canvas and privacy-mode canvas-fingerprint-noising remain undiscussed anywhere in plan/research (confirmed via grep — zero hits for "CSP"/"fingerprint" in plan.md or either ADR).
- Task 3.2.1a's implementation sketch is still a single semicolon-chained one-line `try`/`catch` expression — now longer than in iteration 1 (it also carries the new canvas-area guard inline). The task text explicitly tells the implementer to expand it into readable statements rather than paste it verbatim, which is a reasonable mitigation, but the sketch-as-written still cuts against this repo's `CLAUDE.md` comment/readability conventions if copied literally.
