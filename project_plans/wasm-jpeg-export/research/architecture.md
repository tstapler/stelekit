# Architecture Research — wasm-jpeg-export

Agent 3, SDD Phase 2. All paths relative to repo root (KMP module `kmp/`). Line numbers verified 2026-09-22 against the worktree HEAD (`82287ab6`).

## 1. Integration points — who calls `bakeAndEncode()`

```
grep -rn "bakeAndEncode(" kmp/src
```

Result: **zero production call sites.** The only callers are four JVM tests:
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/annotate/AnnotationExporterTest.kt:71,80,88,131`.

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/DriveExportService.kt` mentions
`AnnotationExporter.bakeAndEncode` only in two `@param`/flow-doc comments (lines 92, 108) — it is
documentation of an *intended* future caller, not code. `DriveExportService.exportToDrive()`
(line 111) already takes a bare `imageJpegBytes: ByteArray` parameter and returns
`Either<DomainError, DriveExportResult>` for its own upload work; it does not call
`ImageEncoder`/`AnnotationExporter` at all today.

**Consequence:** changing `bakeAndEncode()`'s return type has no production blast radius right
now — no ViewModel, no screen, no export button wires into it yet. The only mechanical cost of a
signature change is updating the 4 test call sites. This removes "ripples to callers" as a reason
to avoid the Either signature; the decision in §3 turns on convention-fit and scope, not on
blast-radius risk.

## 2. Data flow for the canvas route, and interop placement

Both proposed approaches are **synchronous** in browser JS — this matters because it means the
`expect fun encodeToJpeg(...): ByteArray` signature does not need to become `suspend`, unlike
`OpfsInterop.kt`'s Promise-based File System Access API calls (`getDirectoryHandle`,
`createWritable`, etc., all wrapped in `.await()`).

- **Canvas route:** `ImageBitmap` → pixel bytes (`toPixelMap()` or `asSkiaBitmap().readPixels()`)
  → `ImageData`/`Uint8ClampedArray` → offscreen `<canvas>` via `putImageData` (synchronous) →
  `canvas.toDataURL('image/jpeg', quality)` (synchronous) → strip the `data:image/jpeg;base64,`
  prefix → `atob()` → `Uint8Array` → Kotlin `ByteArray`. No `await` anywhere in this chain.
- **npm `jpeg-js` route:** `jpeg-js`'s `encode()` is also synchronous pure-JS (no Promise). Same
  signature implication.

So the sync-vs-suspend question is a non-issue for either route; the only interop design question
is **where the `js("...")` boundary calls live**.

**Recommendation: new `ImageEncoderInterop.kt` in `wasmJsMain/.../platform/`, mirroring
`OpfsInterop.kt`'s split.** `OpfsInterop.kt` (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt`)
establishes the repo's convention: raw `external fun ... = js("...")` calls and `JsAny` marshaling
live in one file (`*Interop.kt`), while the higher-level orchestration (`opfsWriteFile`,
`opfsDeleteFile`) composes them with `try/catch` and domain-shaped return values. Applying that
split here: `ImageEncoderInterop.kt` holds `js()` primitives (`createCanvas`, `getContext2d`,
`putImageData`, `canvasToDataUrlJpeg`, `atobToBytes` or similar), and
`ImageEncoder.wasmJs.kt` (currently 16 lines) becomes the `actual object` that calls into them and
maps failure to the function's return contract (see §3). This keeps the 16-line stub file thin and
keeps JS-boundary code auditable in one place, consistent with the existing `SqliteWorkerInterop.kt`
/ `OpfsInterop.kt` precedent — do not inline the `js()` calls directly into
`ImageEncoder.wasmJs.kt`.

## 3. Disposition: Extend as-is (with a narrow seam for the Either question)

**Not a hotspot.** No prior `code-hotspot-analysis` or `architecture-review` output exists for
this area (confirmed by the task's prior-analysis input), the touched stub is 16 lines with a
single caller-free implementation, and `project_plans/image-meter/implementation/plan.md`
confirms wasmJs was never part of the original design — it's a compiler-contract placeholder, not
accumulated complexity. There is nothing to refactor; refactor-first would be solving a problem
that doesn't exist. Isolating the JS interop boundary into its own file (§2) is the only
"seam"-shaped move, and it's justified purely by matching the existing `OpfsInterop.kt` pattern,
not by hotspot pressure.

**On the Either-signature question — this is the central tension, and it resolves to a split
decision:**

Evidence against changing `ImageEncoder.encodeToJpeg`'s signature:
- JVM (`ImageEncoder.jvm.kt:41-43`) and Android (`ImageEncoder.android.kt:18-20`) — the two
  actuals explicitly called "correct" in the requirements — both already catch `Exception` and
  return `ByteArray(0)` on failure. "Empty array on failure" is not a wasmJs-stub shortcut that
  predates the Either convention; it is the *deliberate, already-shipped* contract across every
  platform, matching the `expect` doc comment's `@return encoded bytes, or an empty array on
  failure` (`ImageEncoder.kt:17`).
- The CLAUDE.md Either rule is scoped to "repository and service methods" at "repository
  boundaries." `ImageEncoder` is a pure platform codec utility (expect/actual), not a
  repository/service — it's the same category as a stdlib-adjacent conversion function, not a
  data-access boundary the convention was written for.
  Widening `encodeToJpeg` to `Either` would force JVM, Android, and iOS actuals to change too
  (to stay consistent) — that's scope creep into three files the requirements explicitly mark
  correct/out-of-scope ("JVM/Android actuals are correct... iOS is a separately-tracked stub, out
  of scope").
- §1 shows zero production callers exist yet, so there's no real caller today whose error-handling
  ergonomics are improved by the wider type — the benefit is currently theoretical.

Evidence for surfacing the error somewhere:
- Acceptance criterion 3 ("failure distinguishable from success, not silent 0 bytes") cannot be
  satisfied by a bare `ByteArray` return — `ByteArray(0)` is indistinguishable from "the source
  image happened to encode to nothing," and that's the exact defect being fixed.

**Recommendation:** change the return type of `AnnotationExporter.bakeAndEncode()` only, to
`Either<DomainError.ExportError, ByteArray>`, and leave `ImageEncoder.encodeToJpeg` (the
expect/actual boundary) as bare `ByteArray` on all four platforms, unchanged.
`bakeAndEncode()` wraps: if `ImageEncoder.encodeToJpeg(...)` returns an empty array, map to
`Left(DomainError.ExportError.EncodingFailed(...))`; otherwise `Right(bytes)`. This requires
adding one new variant to the existing `ExportError` family in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt:104-108` (which already has
`SerializationFailed` / `ClipboardFailed` / `ShareFailed` — `EncodingFailed` fits the existing
shape directly) plus a `DomainError.toUiMessage()` arm (`DomainError.kt:114-158`).

Why this split is the right seam rather than pushing `Either` all the way to `ImageEncoder`:
- `AnnotationExporter` is a `commonMain` orchestration point — the closer analog to a "service" the
  convention is actually written for — while `ImageEncoder` stays a thin platform primitive, like
  `ImageIO`/`Bitmap.compress` themselves, which also signal failure by throwing/returning nothing
  useful, not by a domain type.
- iOS needs zero code changes: its stub already returns `ByteArray(0)`, which
  `bakeAndEncode()` correctly maps to `Left(EncodingFailed)` without touching the iOS file — so
  the out-of-scope iOS boundary stays untouched in behavior *and* in code, satisfying the
  non-goal.
- JVM/Android actuals need zero code changes for the same reason — their existing
  catch-to-empty-array behavior automatically gets upgraded to a distinguishable `Left` for free,
  at the one `commonMain` call site, instead of touching three additional platform files.
- Test blast radius stays exactly the 4 `bakeAndEncode` call sites in
  `AnnotationExporterTest.kt` (§1) — no wider ripple.

## 4. EventStorming table

Skipped per task scope — this is a technical codec fix, not a multi-actor business domain.
