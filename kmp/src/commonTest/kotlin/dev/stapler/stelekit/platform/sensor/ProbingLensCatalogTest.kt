// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbingLensCatalogTest {
    private val logical = CameraLensInfo(
        cameraId = "0", facing = LensFacing.BACK, focalLengthMm = 5.6, sensorWidthMm = 6.4, sensorHeightMm = 4.8,
        physicalIds = listOf("2", "3"), physicalFocalsMm = mapOf("2" to 2.2, "3" to 9.0),
        minZoomRatio = 0.6, maxZoomRatio = 8.0,
    )

    private fun catalog(infos: List<CameraLensInfo>, bindable: Set<String>, log: MutableList<String> = mutableListOf()) =
        ProbingLensCatalog({ infos }, { _, physical -> physical in bindable }, { log += it })

    @Test
    fun physicalIdLens_should_AppearOnlyIfBindTested_ElseZoomStops() = runTest {
        val none = catalog(listOf(logical), emptySet()).lenses()
        assertTrue(none.all { it.kind == LensKind.ZOOM_STOP })
        val both = catalog(listOf(logical), setOf("2", "3")).lenses()
        assertEquals(listOf(LensKind.ULTRA_WIDE, LensKind.WIDE, LensKind.TELE), both.map { it.kind })
        val onlyOne = catalog(listOf(logical), setOf("2")).lenses()
        assertEquals(listOf(LensKind.ULTRA_WIDE, LensKind.WIDE), onlyOne.map { it.kind })
    }

    @Test
    fun lenses_should_ReturnEmptyAndLog_When_TheSourceThrowsOrABindTestThrows() = runTest {
        val log = mutableListOf<String>()
        val broken = ProbingLensCatalog({ error("camera service died") }, { _, _ -> true }, { log += it })
        assertTrue(broken.lenses().isEmpty())
        assertTrue(log.any { it.contains("IllegalStateException") })

        val throwingTester = ProbingLensCatalog({ listOf(logical) }, { _, _ -> throw SecurityException("denied") }, { log += it })
        assertTrue(throwingTester.lenses().all { it.kind == LensKind.ZOOM_STOP })
    }

    @Test
    fun lenses_should_ProbeOnce_AndReuseTheResult_EvenWhenAFirstCallerTimedOut() = runTest {
        var probes = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cached = ProbingLensCatalog(
            { probes++; listOf(logical) },
            { _, physical -> gate.await(); physical in setOf("2", "3") },
            { },
        )
        assertEquals(null, withTimeoutOrNull(50) { cached.lenses() })
        gate.complete(Unit)
        val first = cached.lenses()
        val second = cached.lenses()
        assertEquals(1, probes)
        assertEquals(first, second)
        assertEquals(3, first.size)
    }

    @Test
    fun lenses_should_ReturnEmpty_When_TheClassifierThrows() = runTest {
        val bad = logical.copy(minZoomRatio = Double.NaN, maxZoomRatio = Double.NaN, focalLengthMm = null)
        val throwingClassifier = ProbingLensCatalog({ listOf(bad) }, { _, _ -> true }, { }, classify = { error("boom") })
        assertTrue(throwingClassifier.lenses().isEmpty())
    }
}
