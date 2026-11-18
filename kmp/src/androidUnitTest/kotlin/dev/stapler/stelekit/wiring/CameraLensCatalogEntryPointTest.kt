// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.sensor.CameraLensInfo
import dev.stapler.stelekit.platform.sensor.LensFacing
import dev.stapler.stelekit.platform.sensor.LensInfoSource
import dev.stapler.stelekit.platform.sensor.PhysicalBindTester
import dev.stapler.stelekit.platform.sensor.defaultCameraLensCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The real Android root factory `defaultCameraLensCatalog` with a fake camera source (Robolectric has no cameras):
 * the lens list the dialog would show comes out of the real [dev.stapler.stelekit.platform.sensor.ProbingLensCatalog]
 * and classifier, including the rule that an untested physical id is never listed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CameraLensCatalogEntryPointTest {
    private val logical = CameraLensInfo(
        cameraId = "0", facing = LensFacing.BACK, focalLengthMm = 5.6, sensorWidthMm = 6.4, sensorHeightMm = 4.8,
        physicalIds = listOf("2", "3"), physicalFocalsMm = mapOf("2" to 2.2, "3" to 9.0),
        minZoomRatio = 0.6, maxZoomRatio = 8.0,
    )

    @Test
    fun wiredCatalog_should_ListTheFakeBackendLenses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = defaultCameraLensCatalog(
            context,
            source = LensInfoSource { listOf(logical) },
            bindTester = PhysicalBindTester { _, physical -> physical == "2" },
        )
        val lenses = runBlocking { catalog.lenses() }

        ObservableEffect.assert("camera-lens-list") {
            assertEquals(listOf("0/2", "0"), lenses.map { it.id })
            assertEquals(listOf("0.4x", "1x"), lenses.map { it.label })
        }
    }
}
