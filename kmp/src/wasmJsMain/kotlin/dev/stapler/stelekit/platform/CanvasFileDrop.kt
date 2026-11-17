// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.components.jsFileName
import dev.stapler.stelekit.ui.components.readFileBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The web [CanvasFileDropHost] (Story 5.1.5b, UX-135): `dragover`, `drop` and `paste` listeners on the canvas element only. The
 * page-level drop target is a no-op on wasm (BUG-004), and the body-level image drop must not see a `.excalidraw` drop, so
 * the handler stops propagation. A file over `SceneLimits.maxImportBytes` is handed over unread, so the importer refuses it
 * for size before any byte is copied. UNVERIFIED in a real browser (the compile check is the only automated one).
 */
class CanvasFileDrop(private val host: JsAny, private val limits: SceneLimits = SceneLimits.Default) : CanvasFileDropHost {
    private val logger = Logger("CanvasFileDrop")

    override fun install(onFile: (DroppedCanvasFile) -> Unit, onPasteText: (String) -> Unit): () -> Unit {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> logger.warn("Could not read a dropped file: ${e.message}") },
        )
        val handle = addCanvasListeners(
            host,
            { file ->
                scope.launch {
                    try {
                        onFile(read(file))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn("Could not read a dropped file: ${e.message}")
                    }
                }
            },
            { text -> onPasteText(text) },
        )
        return {
            removeCanvasListeners(host, handle)
            scope.cancel()
        }
    }

    private suspend fun read(file: JsAny): DroppedCanvasFile {
        val name = jsFileName(file)
        val size = fileSize(file)
        if (size > limits.maxImportBytes) return DroppedCanvasFile(name, unread(size))
        return DroppedCanvasFile(name, ImportSource.ofBytes(readFileBytes(file)))
    }

    private fun unread(size: Long): ImportSource = object : ImportSource {
        override val size: Long = size
        override fun read(offset: Long, length: Int): ByteArray = ByteArray(0)
    }
}

private fun addCanvasListeners(host: JsAny, onFile: (JsAny) -> Unit, onPaste: (String) -> Unit): JsAny = js(
    """
    (function() {
        const over = function(e) { e.preventDefault(); e.stopPropagation(); };
        const drop = function(e) {
            e.preventDefault(); e.stopPropagation();
            const files = e.dataTransfer && e.dataTransfer.files;
            if (files && files.length > 0) onFile(files[0]);
        };
        const paste = function(e) {
            const text = e.clipboardData && e.clipboardData.getData('text/plain');
            if (text) onPaste(text);
        };
        host.addEventListener('dragenter', over);
        host.addEventListener('dragover', over);
        host.addEventListener('drop', drop);
        host.addEventListener('paste', paste);
        return { over: over, drop: drop, paste: paste };
    })()
    """,
)

private fun removeCanvasListeners(host: JsAny, handle: JsAny): Unit = js(
    """
    (function() {
        host.removeEventListener('dragenter', handle.over);
        host.removeEventListener('dragover', handle.over);
        host.removeEventListener('drop', handle.drop);
        host.removeEventListener('paste', handle.paste);
    })()
    """,
)
