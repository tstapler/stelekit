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
) : CameraLensCatalog {
    // Own scope: a caller that times out leaves the probe running so the next open reuses its result.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> })
    private val lock = Mutex()
    private var probe: Deferred<List<CameraLens>>? = null

    override suspend fun lenses(): List<CameraLens> {
        val running = lock.withLock { probe ?: scope.async { probeOnce() }.also { probe = it } }
        val result = running.await()
        // An empty answer is likelier a transient failure than a camera-less device; let the next open retry.
        if (result.isEmpty()) lock.withLock { if (probe === running) probe = null }
        return result
    }

    private suspend fun probeOnce(): List<CameraLens> {
        val infos = guarded("read camera info", emptyList()) { source.readInfos() }
        return guarded("classify", emptyList()) { classify(infos.map { withBindResults(it) }) }
    }

    private suspend fun withBindResults(info: CameraLensInfo): CameraLensInfo {
        if (info.facing != LensFacing.BACK || info.physicalIds.isEmpty()) return info
        val ok = info.physicalIds.filter { guarded("bind ${info.cameraId}/$it", false) { bindTester.canBind(info.cameraId, it) } }
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
}
