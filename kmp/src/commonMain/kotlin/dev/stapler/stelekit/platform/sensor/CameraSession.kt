// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Binds and rebinds the camera use cases the session owns. An implementation unbinds only its own use cases (never
 * `unbindAll()`), so a preview binder or frame source bound elsewhere stays up (ADR-004 decision 1), and a failing
 * [bind] must leave the previous lens running and then throw.
 */
interface CameraBackend {
    suspend fun bind(lens: CameraLens)

    suspend fun setZoom(ratio: Double)

    /** Unbinds the backend's own use cases. */
    fun unbind()
}

data class CameraSessionState(
    val selected: CameraLens? = null,
    val binding: Boolean = false,
    /** Lens id to the reason it cannot be selected, shown on the disabled chip. */
    val disabled: Map<String, String> = emptyMap(),
    /** True when a bind failed and no lens has ever been bound. */
    val allFailed: Boolean = false,
    internal val generation: Int = 0,
    internal val requestSeq: Long = 0,
) {
    val shutterEnabled: Boolean get() = !binding && selected != null
}

/** Handed out by [CameraSession.beginCapture]; a bind that completes in between makes it stale. */
class CaptureToken internal constructor(internal val generation: Int)

/**
 * The one owner of lens binding (ADR-004). Requests are serialised and last-wins: while a bind is in flight only the
 * latest newer request is kept. It owns its scope, so no composition scope can leak into it.
 */
class CameraSession(
    private val backend: CameraBackend,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = { 0L },
    private val log: (String) -> Unit = { Logger("CameraSession").info(it) },
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t -> log("camera session crashed: ${t::class.simpleName}") },
    )
    private val requests = Channel<LensRequest>(Channel.CONFLATED)
    private val _state = MutableStateFlow(CameraSessionState())
    val state: StateFlow<CameraSessionState> = _state.asStateFlow()

    init {
        scope.launch { for (request in requests) process(request) }
    }

    /**
     * Selects [lens]; the shutter is disabled immediately and until the latest request has been handled.
     * [forceRebind] rebinds even a zoom-only change, for when something outside the session released the camera.
     */
    fun requestLens(lens: CameraLens, forceRebind: Boolean = false) {
        val seq = _state.updateAndGetSeq()
        requests.trySend(LensRequest(seq, lens, forceRebind))
    }

    /** A token while the shutter is enabled, else null. */
    fun beginCapture(): CaptureToken? = _state.value.takeIf { it.shutterEnabled }?.let { CaptureToken(it.generation) }

    /** False for a capture that started before the latest bind completed; its result must be dropped. */
    fun acceptCapture(token: CaptureToken): Boolean = _state.value.let { !it.binding && it.generation == token.generation }

    fun close() {
        scope.cancel()
        backend.unbind()
    }

    private fun MutableStateFlow<CameraSessionState>.updateAndGetSeq(): Long {
        var seq = 0L
        update { s -> seq = s.requestSeq + 1; s.copy(binding = true, requestSeq = seq) }
        return seq
    }

    private class LensRequest(val seq: Long, val lens: CameraLens, val force: Boolean)

    private suspend fun process(request: LensRequest) {
        val seq = request.seq
        val target = request.lens
        val previous = _state.value.selected
        val started = clockMs()
        try {
            if (previous != null && !request.force && previous.isZoomOnlyChange(target)) {
                backend.setZoom(target.zoomRatio)
            } else {
                backend.bind(target)
                if (target.kind == LensKind.ZOOM_STOP && target.zoomRatio != 1.0) {
                    // The lens is already bound; a zoom failure must not leave `selected` naming the previous lens.
                    try {
                        backend.setZoom(target.zoomRatio)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log("camera zoom lens=${target.id} result=fail:${e::class.simpleName}")
                    }
                }
            }
            log("camera bind lens=${target.id} result=ok ms=${clockMs() - started}")
            _state.update { s ->
                s.copy(selected = target, generation = s.generation + 1, allFailed = false, binding = s.requestSeq != seq)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("camera bind lens=${target.id} result=fail:${e::class.simpleName}")
            _state.update { s ->
                s.copy(
                    disabled = s.disabled + (target.id to UNAVAILABLE_REASON),
                    allFailed = s.selected == null,
                    binding = s.requestSeq != seq,
                )
            }
        }
    }

    private fun CameraLens.isZoomOnlyChange(next: CameraLens): Boolean =
        kind == LensKind.ZOOM_STOP && next.kind == LensKind.ZOOM_STOP &&
            cameraId == next.cameraId && physicalId == next.physicalId

    private companion object {
        const val UNAVAILABLE_REASON = "Not available right now"
    }
}
