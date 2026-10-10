# Requirements: wasm-jpeg-export

**item_id:** ee0c686a-47a0-46e8-afec-59834f9a53d6
**Source:** backlog item `bug(wasm): ImageEncoder.encodeToJpeg returns empty bytes — annotation export broken on web`

## Problem

`ImageEncoder.encodeToJpeg` in
[`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt`](../../kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt)
is a stub that unconditionally returns `ByteArray(0)`:

```kotlin
actual object ImageEncoder {
    actual fun encodeToJpeg(bitmap: ImageBitmap, quality: Int): ByteArray = ByteArray(0)
}
```

`AnnotationExporter.bakeAndEncode()`
([`kmp/src/commonMain/.../ui/annotate/AnnotationExporter.kt`](../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationExporter.kt))
calls this after compositing annotation overlays onto the source image. On the
`wasmJs` (web) target, any "export annotated image" action silently produces a
0-byte file — no error is surfaced to the user or caller, since the function
signature returns a plain `ByteArray` with no failure channel.

The JVM (`ImageEncoder.jvm.kt`, `javax.imageio.ImageIO`) and Android
(`ImageEncoder.android.kt`, `Bitmap.compress`) `actual` implementations are
already correct. iOS (`ImageEncoder.ios.kt`) is a known, separately-tracked
stub ("deferred to Epic 9" per the `expect` doc comment) — out of scope here.

## Root cause

Compose Multiplatform for wasmJs does not expose Skia's JPEG encoder to user
code, so there is no in-process Kotlin API to encode `ImageBitmap` → JPEG on
this target. Any fix must cross the JS interop boundary.

## Proposed approaches (from backlog item)

1. **Canvas 2D route**: `bitmap.toPixelMap()` → write pixels into a hidden
   `<canvas>` via `ImageData`/`Uint8ClampedArray` → `canvas.toDataURL('image/jpeg',
   quality)` → base64-decode to `ByteArray`. Must batch the pixel write as a
   single flat-array JS call (`putImageData`), not per-pixel interop calls —
   the item flags 6M interop calls for a 3000×2000 image as impractical.
2. **JS JPEG encoder library** (e.g. `jpeg-js`) bundled as an `npm()` dependency
   of the `wasmJsMain` source set (precedent: `@sqlite.org/sqlite-wasm` in
   `kmp/build.gradle.kts:157`).

The repo already has an established `js("...")` external-function interop
pattern for this kind of work (see `OpfsInterop.kt`, `SqliteWorkerInterop.kt`)
— Promise-returning JS calls bridged via `external fun ... : Promise<JsAny>`.
Whichever approach is chosen should follow that convention rather than
inventing a new one.

## Affected files

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt` — stub to replace
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationExporter.kt` — caller; may need error-surfacing contract change
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.kt` — `expect` declaration/doc comment
- `kmp/build.gradle.kts` — only if the npm-library route is chosen

## Acceptance criteria (draft — refined further in plan.md)

1. On the `wasmJs` target, exporting an annotated image produces a non-empty,
   valid JPEG byte array that a browser/image viewer can decode.
2. Encoding a realistic image size (e.g. 3000×2000) completes without
   blocking the UI thread for an unacceptable duration (needs a concrete
   threshold from research/plan).
3. Failure (e.g. canvas/JS API unavailable) is distinguishable from success —
   callers/users get a signal, not a silent 0-byte file.
4. JVM, Android behavior is unchanged; iOS stub is explicitly left alone
   (separately tracked).
5. A regression test exists that fails against the current stub and passes
   against the fix (to the extent wasmJs/browser APIs are testable in this
   repo's test infra — confirm in research).

## Non-goals

- iOS `ImageEncoder` implementation (tracked separately per existing doc
  comment).
- PNG or other export formats — JPEG only, per the existing `expect` API.
- General annotation-editor feature work beyond the export path.

## Labels

`bug`, `platform: web`, `needs-design`
