// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.sensor

import androidx.camera.core.CameraState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraCaptureCleanupTest {
    @Test
    fun isFatalCameraError_should_IgnoreRecoverableCodes() {
        listOf(
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE, CameraState.ERROR_OTHER_RECOVERABLE_ERROR,
        ).forEach { assertFalse(isFatalCameraError(it), "code $it") }
        listOf(
            CameraState.ERROR_STREAM_CONFIG, CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_CAMERA_FATAL_ERROR,
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
        ).forEach { assertTrue(isFatalCameraError(it), "code $it") }
    }

    @Test
    fun sweepStaleCaptures_should_DeleteOnlyFilesOlderThanTheLimit() {
        val dir = kotlin.io.path.createTempDirectory("captures").toFile()
        val old = File(dir, "old.jpg").also { it.writeText("x"); it.setLastModified(1_000L) }
        val fresh = File(dir, "fresh.jpg").also { it.writeText("x"); it.setLastModified(9_000_000L) }
        assertEquals(1, sweepStaleCaptures(dir, nowMs = 10_000_000L, maxAgeMs = 3_600_000L))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        dir.deleteRecursively()
    }
}
