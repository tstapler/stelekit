// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.service.DroppedFileBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Matches App.kt's "GraphContent" tag convention for drag-and-drop-related logging (this file
// has no access to App.kt's composable-local `graphContentLogger`, which is scoped inside
// `StelekitApp`, not a top-level singleton).
private val logger = Logger("GraphContent")

// A CoroutineExceptionHandler here is defense-in-depth, not a crash guard: WASM/JS's
// Dispatchers.Default is single-threaded, so an uncaught throw here would only be logged
// to the console by the platform default handler anyway, never kill the page.
private val dropScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        logger.error("Unhandled error while processing a dropped file", e)
    }
)

// internal (not private): PageDropTargetListenerLifecycleTest (wasmJsTest, same module) drives
// these directly to verify listener-swap behavior without a full Compose composition.
internal var activeDropHandler: ((List<Any>) -> Unit)? = null
internal var listenerInstalled = false

// Per-file try/catch: an unreadable file (e.g. a mid-drag permission error) must not abort
// the rest of a multi-file drop batch.
private suspend fun readDroppedImageOrNull(file: JsAny): DroppedFileBytes? {
    val name = jsFileName(file)
    if (!name.isImageFileName()) return null
    return try {
        DroppedFileBytes(name, readFileBytes(file))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("Failed to read dropped file bytes for \"$name\": ${e.message}")
        null
    }
}

internal fun ensureListenerInstalled() {
    if (listenerInstalled) return
    listenerInstalled = true
    installBodyDropListener { fileArray ->
        dropScope.launch {
            val handlerAtDropTime = activeDropHandler ?: return@launch
            val count = jsFileArrayLength(fileArray)
            val dropped = (0 until count)
                .mapNotNull { i -> readDroppedImageOrNull(jsFileArrayGet(fileArray, i)) }
            // Re-check identity: the page may have navigated away (swapping activeDropHandler
            // via SideEffect/onDispose) while the suspending per-file reads above were in
            // flight — deliver only to the handler still current when reads finished, not a
            // stale one captured before navigation.
            if (dropped.isNotEmpty() && activeDropHandler === handlerAtDropTime) {
                handlerAtDropTime(dropped)
            }
        }
    }
}

actual fun Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier = composed {
    SideEffect { activeDropHandler = onFilesDropped }
    DisposableEffect(Unit) {
        ensureListenerInstalled()
        onDispose { if (activeDropHandler === onFilesDropped) activeDropHandler = null }
    }
    this
}
