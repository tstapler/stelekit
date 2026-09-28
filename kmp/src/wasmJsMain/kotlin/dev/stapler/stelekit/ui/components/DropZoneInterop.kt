// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.platform.jsArrayBufferToByteArray
import kotlinx.coroutines.await

/**
 * Installs `dragenter`/`dragover`/`drop` listeners on `document.body` (bubbling covers the
 * Compose canvas, which cannot be intercepted directly — see PageDropTargetModifier.kt's doc
 * comment). [onFiles] receives the dropped `File[]` as a JS array; `preventDefault()` is always
 * called so the browser never navigates away with the file.
 *
 * Intentionally never removed: `document.body` outlives every composable for this SPA's whole
 * page lifetime, so there's no `onDispose` counterpart — `ensureListenerInstalled`'s
 * `listenerInstalled` guard exists to make repeated calls a no-op, not to support teardown.
 */
internal fun installBodyDropListener(onFiles: (JsAny) -> Unit): Unit = js("""
    (function() {
        document.body.addEventListener('dragenter', function(e) { e.preventDefault(); });
        document.body.addEventListener('dragover', function(e) { e.preventDefault(); });
        document.body.addEventListener('drop', function(e) {
            e.preventDefault();
            onFiles(Array.from(e.dataTransfer.files));
        });
    })()
""")

internal fun jsFileArrayLength(arr: JsAny): Int = js("arr.length | 0")
internal fun jsFileArrayGet(arr: JsAny, index: Int): JsAny = js("arr[index]")
internal fun jsFileName(file: JsAny): String = js("file.name")

// internal (not private): reused by WasmMediaAttachmentService.pickAndAttach — both entry points
// need the raw promise, not just awaited bytes, since pickAndAttach still writes a JsAny
// ArrayBuffer through persistAttachment rather than a ByteArray.
internal fun fileArrayBufferPromise(file: JsAny): kotlin.js.Promise<JsAny> = js("file.arrayBuffer()")

internal suspend fun readFileBytes(file: JsAny): ByteArray {
    val buffer: JsAny = fileArrayBufferPromise(file).await()
    return jsArrayBufferToByteArray(buffer)
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp")

internal fun String.isImageFileName(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
