# BUG-003: ImageEncoder.encodeToJpeg Returns Empty Bytes on WASM [SEVERITY: High]

**Status**: Open
**Discovered**: 2026-06-29 during WASM platform gap closure session
**GitHub Issue**: [#187](https://github.com/stapler/stelekit/issues/187)
**Impact**: Annotation export on web silently produces a 0-byte file. Users who annotate
an image and trigger export on the web platform receive a corrupt/empty download with no
error message.

## Problem Description

`ImageEncoder.encodeToJpeg` in the `wasmJs` source set has no working implementation.
The WASM/JS platform does not expose Skia internals (used on JVM via `org.jetbrains.skia`),
and the Compose `ImageBitmap` type has no built-in serialization on WASM. The current
stub returns an empty `ByteArray`, which the calling code treats as a valid result and
writes to a download without validation.

## Reproduction Steps

1. Open the web app (`bazel build //kmp:web_app`).
2. Navigate to any page containing an image asset.
3. Trigger image annotation and export (annotation toolbar → Export JPEG).
4. Expected: A valid JPEG file downloads.
5. Actual: A 0-byte file downloads with no error shown to the user.

## Root Cause

No WASM-compatible JPEG encoder exists in the project. The viable implementation path is:

1. Call `ImageBitmap.toPixelMap()` to extract raw RGBA pixel data on the Kotlin side.
2. Write pixels to a hidden `<canvas>` element via Canvas2D using batched `putImageData`
   calls to avoid O(n) per-pixel JS interop overhead.
3. Call `canvas.toDataURL("image/jpeg", quality)` and decode the resulting data URL to
   a `ByteArray`.

The batching step in (2) is non-trivial: `ImageBitmap.toPixelMap()` returns a
`PixelMap` that must be transferred to JS as a typed array (e.g., `Uint8ClampedArray`)
in a single interop call rather than pixel-by-pixel, or performance will be
unacceptable for typical annotation canvas sizes (1000x1000+).

## Files Affected (1 file)

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt`
  — WASM actual; currently returns `ByteArray(0)`

## Fix Approach

Replace the empty stub with a Canvas2D-based encoder:

```kotlin
// Pseudocode — actual JS interop types depend on Kotlin/WASM stdlib version
actual fun encodeToJpeg(bitmap: ImageBitmap, quality: Int): ByteArray {
    val pixelMap = bitmap.toPixelMap()
    val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
    canvas.width = pixelMap.width
    canvas.height = pixelMap.height
    val ctx = canvas.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
    // Transfer all pixels in a single interop call via ImageData
    val data = UInt8ClampedArray(pixelMap.width * pixelMap.height * 4)
    // ... fill data from pixelMap ...
    ctx.putImageData(ImageData(data, pixelMap.width, pixelMap.height), 0.0, 0.0)
    val dataUrl = canvas.toDataURL("image/jpeg", quality / 100.0)
    return dataUrl.substringAfter("base64,").decodeBase64()
}
```

The fix is self-contained to a single file and requires no changes to the common
`ImageEncoder` expect declaration or the JVM/Android actuals.

## Verification

1. Build and serve the WASM app.
2. Annotate an image and export.
3. Confirm the downloaded file is a valid JPEG (non-zero size, opens in an image viewer).
4. Confirm no JS console errors during export.
5. Test with a large canvas (e.g., 2000x2000 px) to verify batched transfer performance
   is acceptable (export completes in < 5 s).

## Related Tasks

- `docs/tasks/image-meter.md` — image annotation feature that calls this encoder
