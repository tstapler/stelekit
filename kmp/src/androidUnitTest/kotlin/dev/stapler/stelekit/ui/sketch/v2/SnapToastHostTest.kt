// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Toggling Snap twice from the real view cluster leaves one live toast, not "Snap on" and "Snap off" stacked (seen on a phone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SnapToastHostTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun togglingSnapTwice_should_LeaveOneToast_ThatSaysOff() {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val session = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()

        rule.onNodeWithTag("sketch.snap").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sketch.snap").performClick()
        rule.waitForIdle()

        val notifier = session.controller.notifier
        val shown = notifier.queue.visible(notifier.now()).filter { it.notice.key == SketchHostStrings.View.SNAP_KEY }
        assertEquals(listOf(SketchHostStrings.View.SNAP_OFF), shown.map { it.notice.text })
    }
}
