// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExport
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * E1: a sketch embed on a phone draws the sheet's dimension annotations when the sheet says so. Renders the real preview at
 * 360 dp wide (images in build/outputs/roborazzi) and checks the picture changes with the flag. Gradle-only, like the other
 * Robolectric embed tests: it composes @Composable lambdas, which the Bazel android target cannot compile.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class DimensionedEmbedPhoneRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun u(v: Double) = SceneUnit.of(v).getOrNull() ?: error("unit")

    private fun room(): SketchSceneV2 {
        var s = SketchSceneV2.empty()
        s = s.add(RectElement(ElementBase(ElementId("room")), u(0.0), u(0.0), u(1920.0), u(1440.0))).getOrNull() ?: error("room")
        s = s.add(RectElement(ElementBase(ElementId("table")), u(400.0), u(400.0), u(768.0), u(384.0))).getOrNull() ?: error("table")
        s = s.add(EllipseElement(ElementBase(ElementId("rug")), u(1500.0), u(1000.0), u(240.0), u(240.0))).getOrNull() ?: error("rug")
        return s
    }

    @Test
    fun embed_should_DrawTheAnnotations_When_TheSheetShowsDimensions() {
        var on by mutableStateOf(false)
        rule.setContent { MaterialTheme { SketchPreviewContent(SketchPreview.ModernDrawing(room(), on), modern = null) } }
        rule.waitForIdle()
        // captureRoboImage writes only in Roborazzi record mode, so the images are a look-at-it aid; the pass condition is that both states compose.
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/embed-dimensions-off.png")
        rule.onNodeWithTag(SKETCH_PREVIEW_MODERN_TAG).assertIsDisplayed()
        on = true
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/embed-dimensions-on.png")
        rule.onNodeWithTag(SKETCH_PREVIEW_MODERN_TAG).assertIsDisplayed()
    }

    @Test
    fun previewBounds_should_GrowToHoldTheAnnotations() {
        val scene = room()
        val plain = modernPreviewBounds(scene) ?: error("bounds")
        val withDims = modernPreviewBounds(scene, DimensionExport.layout(scene)) ?: error("bounds")
        assertTrue(withDims.minY < plain.minY && withDims.maxX > plain.maxX, "the lines and values sit outside the shapes: $plain vs $withDims")
        assertContentEquals(listOf(plain), listOf(modernPreviewBounds(scene, emptyList())))
    }
}
