// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The chips to show for this device's rear cameras, best first; empty means show no lens or zoom chips. */
interface CameraLensCatalog {
    suspend fun lenses(): List<CameraLens>
}

class NoOpCameraLensCatalog : CameraLensCatalog {
    override suspend fun lenses(): List<CameraLens> = emptyList()
}

fun interface LensInfoSource {
    suspend fun readInfos(): List<CameraLensInfo>
}

fun interface PhysicalBindTester {
    /** True only when binding with `setPhysicalCameraId(physicalId)` on [cameraId] actually succeeded. */
    suspend fun canBind(cameraId: String, physicalId: String): Boolean
}

/**
 * Reads camera info, bind-tests each physical id behind a logical camera, then classifies. A physical lens the bind
 * test cannot confirm is never listed, so an OEM that hides lenses falls back to zoom stops (ADR-004 decision 2).
 */
class ProbingLensCatalog(
    private val source: LensInfoSource,
    private val bindTester: PhysicalBindTester,
    private val log: (String) -> Unit = { Logger("CameraLensCatalog").info(it) },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val classify: (List<CameraLensInfo>) -> List<CameraLens> = LensClassifier::classify,
    private val bindTimeoutMs: Long = 3_000L,
) : CameraLensCatalog {
    // Own scope: a caller that times out leaves the probe running so the next open reuses its result.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> })
    private val lock = Mutex()
    private var probe: Deferred<List<CameraLens>>? = null
    private var degraded = false
    private var degradedProbes = 0

    override suspend fun lenses(): List<CameraLens> {
        val running = lock.withLock { probe ?: scope.async { probeOnce() }.also { probe = it } }
        val result = running.await()
        // Empty or a probe with failed bind tests is likelier transient (cold camera, no permission) than final:
        // re-probe on later opens, but stop after MAX_DEGRADED_PROBES so an OEM that hides lenses is not probed forever.
        lock.withLock {
            val retry = result.isEmpty() || (degraded && degradedProbes < MAX_DEGRADED_PROBES)
            if (probe === running && retry) probe = null
        }
        return result
    }

    private suspend fun probeOnce(): List<CameraLens> {
        var failedBind = false
        val infos = guarded("read camera info", emptyList()) { source.readInfos() }
        val result = guarded("classify", emptyList()) {
            classify(infos.map { withBindResults(it) { failedBind = true } })
        }
        lock.withLock {
            degraded = failedBind
            if (failedBind) degradedProbes++
        }
        return result
    }

    private suspend fun withBindResults(info: CameraLensInfo, onFailure: () -> Unit): CameraLensInfo {
        if (info.facing != LensFacing.BACK || info.physicalIds.isEmpty()) return info
        val ok = info.physicalIds.filter { id ->
            val bound = guarded("bind ${info.cameraId}/$id", false) {
                withTimeoutOrNull(bindTimeoutMs) { bindTester.canBind(info.cameraId, id) } ?: false
            }
            if (!bound) onFailure()
            bound
        }
        return info.copy(boundPhysicalIds = ok.toSet())
    }

    private suspend fun <T> guarded(what: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log("camera catalog $what failed: ${e::class.simpleName}")
        fallback
    }

    companion object {
        const val MAX_DEGRADED_PROBES = 3
    }
}
