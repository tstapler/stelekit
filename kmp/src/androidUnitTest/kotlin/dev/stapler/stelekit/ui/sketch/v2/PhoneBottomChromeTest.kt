// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** On a phone the technical sheet's chip row and the view cluster share the bottom of the canvas; they must never draw over each other. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneBottomChromeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun mount(fontScale: Float = 1f): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet)
        session.controller.setSheetKind(SheetKind.Technical)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {})
            }
        }
        rule.waitForIdle()
        return session
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun assertApart(name: String) {
        val chips = bounds(ShellTags.CHIP_ROW)
        val cluster = bounds(ShellTags.VIEW_CLUSTER)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        assertTrue(!chips.overlaps(cluster), "chip row $chips draws over the view cluster $cluster")
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(chips.left >= root.left && chips.right <= root.right, "chip row $chips runs off the screen $root")
        assertTrue(cluster.left >= root.left && cluster.right <= root.right, "view cluster $cluster runs off the screen $root")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
    fun chips_should_NotOverlapTheViewCluster_OnA360dpPhone() {
        mount()
        assertApart("bottom-chrome-360")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w432dp-h900dp-xxhdpi")
    fun chips_should_NotOverlapTheViewCluster_OnA432dpPhone() {
        mount()
        assertApart("bottom-chrome-432")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
    fun chips_should_NotOverlapTheViewCluster_AtLargeFont() {
        mount(fontScale = 2f)
        assertApart("bottom-chrome-360-font2")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
    fun chips_should_SitBesideTheViewCluster_When_TheyFit() {
        mount()
        assertApart("bottom-chrome-1280")
        val chips = bounds(ShellTags.CHIP_ROW)
        val cluster = bounds(ShellTags.VIEW_CLUSTER)
        assertTrue(chips.bottom > cluster.top && chips.top < cluster.bottom, "on a wide canvas the chips share the cluster's row: $chips vs $cluster")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
    fun chips_should_StayClearOfAnOpenPanPad() {
        val session = mount()
        session.controller.ui.panPadOpen = true
        rule.waitForIdle()
        val chips = bounds(ShellTags.CHIP_ROW)
        val pad = bounds(PanTags.PAD)
        assertTrue(!chips.overlaps(pad), "chip row $chips draws over the open pan pad $pad")
        assertApart("bottom-chrome-360-panpad")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w432dp-h900dp-xxhdpi")
    fun chips_should_NotOverlapTheViewCluster_AtLargeFontOnA432dpPhone() {
        mount(fontScale = 1.5f)
        assertApart("bottom-chrome-432-font15")
    }
}
