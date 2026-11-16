// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun lens(id: String, kind: LensKind = LensKind.WIDE, zoom: Double = 1.0, camera: String = id) = CameraLens(
    id = id, cameraId = camera, physicalId = null, facing = LensFacing.BACK, focalLengthMm = 5.0, focal35mmEq = null,
    zoomRatio = zoom, labelRatio = zoom, isLogicalMulti = false, kind = kind, optical = true,
    label = "${zoom}x", secondaryLabel = null, hint = null,
)

private class FakeCameraBackend : CameraBackend {
    val binds = mutableListOf<String>()
    val zooms = mutableListOf<Double>()
    var unbinds = 0
    var bindDelayMs = 300L
    val failures = mutableMapOf<String, Throwable>()

    override suspend fun bind(lens: CameraLens) {
        binds += lens.id
        delay(bindDelayMs)
        failures[lens.id]?.let { throw it }
    }

    override suspend fun setZoom(ratio: Double) {
        zooms += ratio
    }

    override fun unbind() {
        unbinds++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CameraSessionTest {
    private fun TestScope.session(backend: FakeCameraBackend, log: MutableList<String> = mutableListOf()) =
        CameraSession(backend, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }) { log += it }

    @Test
    fun fiveTapsIn100ms_should_RunOnlyLastBindAfterInFlight_AndDisableShutterDuringRebind() = runTest {
        val backend = FakeCameraBackend()
        val session = session(backend)
        session.requestLens(lens("A"))
        advanceUntilIdle()
        assertTrue(session.state.value.shutterEnabled)

        val token = session.beginCapture()
        assertNotNull(token)
        listOf("B", "C", "B", "C", "D").forEach { session.requestLens(lens(it)); advanceTimeBy(20) }
        assertFalse(session.state.value.shutterEnabled)
        assertNull(session.beginCapture())
        advanceUntilIdle()

        assertEquals(listOf("A", "B", "D"), backend.binds)
        assertEquals("D", session.state.value.selected?.id)
        assertTrue(session.state.value.shutterEnabled)
        assertFalse(session.acceptCapture(token), "a capture started before the switch completed is dropped")
        assertTrue(session.acceptCapture(session.beginCapture()!!))
        assertTrue(testScheduler.currentTime <= 1_000, "state machine adds no wait beyond the fake's own bind time")
    }

    @Test
    fun failedBind_should_KeepPreviousLensAndDisableChipWithReason_AndLogResultFail() = runTest {
        val backend = FakeCameraBackend()
        val log = mutableListOf<String>()
        val session = session(backend, log)
        val failures = listOf(
            "3" to IllegalArgumentException("no such lens"),
            "4" to SecurityException("denied"),
            "5" to IllegalStateException("camera in use"),
        )
        failures.forEach { backend.failures[it.first] = it.second }
        session.requestLens(lens("1"))
        advanceUntilIdle()
        for ((id, ex) in failures) {
            session.requestLens(lens(id))
            advanceUntilIdle()
            assertEquals("1", session.state.value.selected?.id)
            assertNotNull(session.state.value.disabled[id])
            assertTrue(log.any { it.startsWith("camera bind lens=$id result=fail:${ex::class.simpleName}") }, log.toString())
        }
        assertTrue(log.any { it.startsWith("camera bind lens=1 result=ok ms=") })
        assertFalse(session.state.value.allFailed)
    }

    @Test
    fun failedFirstBind_should_ReportAllFailed_WhenNothingWasEverBound() = runTest {
        val backend = FakeCameraBackend()
        backend.failures["1"] = IllegalStateException("in use")
        val session = session(backend)
        session.requestLens(lens("1"))
        advanceUntilIdle()
        assertNull(session.state.value.selected)
        assertTrue(session.state.value.allFailed)
    }

    @Test
    fun zoomStopOnTheBoundCamera_should_NotRebind() = runTest {
        val backend = FakeCameraBackend()
        val session = session(backend)
        session.requestLens(lens("0@1", LensKind.ZOOM_STOP, 1.0, camera = "0"))
        advanceUntilIdle()
        session.requestLens(lens("0@2", LensKind.ZOOM_STOP, 2.0, camera = "0"))
        advanceUntilIdle()
        assertEquals(listOf("0@1"), backend.binds)
        assertEquals(listOf(2.0), backend.zooms)
        assertEquals("0@2", session.state.value.selected?.id)
    }

    @Test
    fun close_should_UnbindOwnUseCasesOnce_AndNeverMarkACancelledBindAsFailed() = runTest {
        val backend = FakeCameraBackend()
        val session = session(backend)
        session.requestLens(lens("A"))
        advanceUntilIdle()
        session.requestLens(lens("B"))
        advanceTimeBy(10)
        session.close()
        advanceUntilIdle()
        assertEquals(1, backend.unbinds)
        assertTrue(session.state.value.disabled.isEmpty())
    }

    @Test
    fun bindThrowingCancellation_should_NotBeSwallowedAsAFailedLens() = runTest {
        val backend = FakeCameraBackend()
        backend.failures["X"] = CancellationException("cancelled")
        val session = session(backend)
        session.requestLens(lens("X"))
        advanceUntilIdle()
        assertTrue(session.state.value.disabled.isEmpty())
    }
}
