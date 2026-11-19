// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.sketch.v2

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.platform.AndroidEdgeGestureExclusion
import dev.stapler.stelekit.platform.AndroidPalmClassifier
import dev.stapler.stelekit.platform.AndroidPointerCancelDetector
import dev.stapler.stelekit.platform.EdgeExclusionRect
import dev.stapler.stelekit.platform.SketchPlatformInput
import dev.stapler.stelekit.ui.buildProjectBridgeDeps
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import dev.stapler.stelekit.wiring.FakePlatformPorts
import dev.stapler.stelekit.wiring.ObservableEffect
import dev.stapler.stelekit.wiring.WiredAppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 3.1.2h: the palm, system-cancel and edge-exclusion seams the real `GraphContentViewModelSetup` provider hands the v2
 * canvas on Android answer from raw `MotionEvent`s and a real `View`. Synthesised events under Robolectric are NOT a device
 * run: whether a device reports `TOOL_TYPE_PALM`, grants the span or delivers the drag is Task 0.1.4d.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SketchPlatformInputAndroidEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File = createTempDirectory("sketch_input_android_").toFile()
    private var fixture: WiredAppFixture? = null
    private val view by lazy { View(context) }

    @After
    fun tearDown() {
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    /** The seams as the project bridge hands them to the v2 editor, built through the real provider. */
    private fun bridgedInput(): SketchPlatformInput {
        val ports = FakePlatformPorts(fileSystem = FakeFileSystem())
        val wired = runBlocking {
            WiredAppFixture.android(context, dataDir, ports = ports, sketchInput = SketchPlatformInput(
                    palm = AndroidPalmClassifier,
                    cancel = AndroidPointerCancelDetector,
                    edgeExclusion = AndroidEdgeGestureExclusion(view, SPAN),
                ),
            )
        }
        fixture = wired
        var input: SketchPlatformInput? = null
        composeRule.setContent {
            val vault = rememberGraphContentVaultSetup(wired.graphManager, ports.fileSystem, null)
            val io = rememberGraphContentGraphIoStack(
                wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                dev.stapler.stelekit.logging.Logger("SketchPlatformInputAndroidEntryPointTest"),
            )
            input = buildProjectBridgeDeps(wired.deps, vault.effectiveFileSystem, io).sketchInput
        }
        composeRule.waitUntil(WAIT_MS) { input != null }
        return assertNotNull(input)
    }

    private fun motion(action: Int, toolType: Int): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f })
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    @Test
    fun bridgedPalmClassifier_should_RejectAPalmContact_AndKeepAFinger() {
        val input = bridgedInput()
        ObservableEffect.assert("palm-contact-rejected-android") {
            assertTrue(input.palm.isPalm(motion(MotionEvent.ACTION_DOWN, AndroidPalmClassifier.TOOL_TYPE_PALM), 0))
            assertFalse(input.palm.isPalm(motion(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER), 0))
        }
    }

    @Test
    fun bridgedCancelDetector_should_SeeASystemCancel_ButNotARelease() {
        val input = bridgedInput()
        ObservableEffect.assert("pointer-cancel-detected-android") {
            assertTrue(input.cancel.isCancelled(motion(MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_FINGER), 0))
            assertFalse(input.cancel.isCancelled(motion(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER), 0))
        }
    }

    @Test
    fun bridgedEdgeExclusion_should_ClaimOnlyTheRequestedSpanOnTheView() {
        val input = bridgedInput()
        val exclusion = assertNotNull(input.edgeExclusion)
        ObservableEffect.assert("edge-exclusion-claimed-android") {
            exclusion.request(listOf(EdgeExclusionRect(0, 0, 44, SPAN)))
            assertEquals(1, view.systemGestureExclusionRects.size)
            exclusion.release()
            assertTrue(view.systemGestureExclusionRects.isEmpty())
        }
    }

    private companion object {
        const val SPAN = 400
        const val WAIT_MS = 60_000L
    }
}
