// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException

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
) : CameraLensCatalog {
    override suspend fun lenses(): List<CameraLens> {
        val infos = guarded("read camera info", emptyList()) { source.readInfos() }
        return LensClassifier.classify(infos.map { withBindResults(it) })
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
