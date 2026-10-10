# BUG-004: pageDropTarget is a No-Op on WASM [SEVERITY: Medium]

**Status**: Open
**Discovered**: 2026-06-29 during WASM platform gap closure session
**GitHub Issue**: [#189](https://github.com/stapler/stelekit/issues/189)
**Impact**: Dragging an image file from the desktop onto a page in the web app does
nothing. The drop target composable exists but never fires. Users who expect drag-and-drop
image insertion (which works on JVM/desktop) are silently blocked on web with no feedback.

## Problem Description

Compose for WASM renders the entire UI into a single `<canvas>` element. The browser's
HTML5 drag-and-drop API fires `dragenter`, `dragover`, and `drop` events on DOM nodes, but
the single `<canvas>` host element is the only node in the page body. Compose's own
`dragAndDropTarget` modifier cannot intercept file drops from outside the browser window
because that requires native OS integration — something only the browser's HTML5 API
provides.

The current `PageDropTarget.wasmJs.kt` implements the `actual` composable as a
pass-through that renders its content unchanged and never calls the drop callback.

## Reproduction Steps

1. Open the web app (`bazel build //kmp:web_app`).
2. Navigate to any page.
3. Drag an image file from the desktop OS into the browser window over the page content.
4. Expected: The image is inserted into the current block as a local asset.
5. Actual: Nothing happens. No visual drop indicator, no insertion, no error.

## Root Cause

Compose WASM has no built-in mechanism to bridge HTML5 drag events from the `<canvas>`
host element to per-composable hit-tested regions. The fix requires two coordinated pieces:

1. **JS side**: A `dragover` + `drop` event listener registered on `document.body` (or
   the canvas element) that reads `event.dataTransfer.files` and sends the file bytes to
   Kotlin via a shared channel (e.g., a `kotlinx.coroutines.Channel` exposed through a
   `CompositionLocal`).

2. **Kotlin side**: `PageDropTarget.wasmJs.kt` reads from that channel and forwards
   `FileInfo` payloads to the existing drop callback, mirroring the JVM contract.

The JS listener must call `event.preventDefault()` on `dragover` to allow the `drop`
event to fire; otherwise the browser opens the file itself.

## Files Affected (2 files)

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt`
  — WASM actual; currently a no-op pass-through composable
- A new JS interop helper (e.g., `DropTargetBridge.js` or inline `@JsExport` Kotlin)
  to register the canvas-level event listener and funnel files into Kotlin

## Fix Approach

```kotlin
// Sketch — exact JS interop API depends on Kotlin/WASM stdlib version

// 1. JS glue (inline or separate .js file included via @JsModule)
//    document.addEventListener('dragover', e => e.preventDefault())
//    document.addEventListener('drop', e => {
//        e.preventDefault()
//        for (const file of e.dataTransfer.files) {
//            file.arrayBuffer().then(buf => kotlinDropCallback(file.name, new Uint8Array(buf)))
//        }
//    })

// 2. Kotlin actual
actual fun PageDropTarget(
    onDrop: (FileInfo) -> Unit,
    content: @Composable () -> Unit
) {
    val channel = LocalFileDropChannel.current  // CompositionLocal<Channel<FileInfo>>
    LaunchedEffect(onDrop) {
        for (fileInfo in channel) { onDrop(fileInfo) }
    }
    content()
}
```

A `CompositionLocal` holding the `Channel<FileInfo>` must be provided at the root of the
WASM composition (in `Main.kt`) and the JS listener must be wired up once at startup.

## Verification

1. Build and serve the WASM app.
2. Drag an image file onto the page.
3. Confirm a visual drop indicator appears during the drag.
4. Confirm the image is inserted into the current block after dropping.
5. Confirm that dropping non-image files (e.g., `.txt`) is either handled or rejected
   gracefully with a user-visible message.

## Related Tasks

- `docs/tasks/drag-and-drop-reorder.md` — block reorder drag-and-drop (separate concern,
  internal to Compose; this bug is about OS-level file drops only)
