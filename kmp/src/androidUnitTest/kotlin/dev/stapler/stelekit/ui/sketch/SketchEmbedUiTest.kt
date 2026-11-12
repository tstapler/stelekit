// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.components.ImageBlock
import dev.stapler.stelekit.ui.components.MobileBlockToolbar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SketchEmbedUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tappingASketchEmbedReopensTheEditorInsteadOfTheLightbox() {
        var opened = 0
        rule.setContent {
            MaterialTheme {
                ImageBlock(
                    url = "../assets/stelekit-sketch/proj-0a1b2c/preview.svg", altText = "sketch",
                    onStartEditing = {}, onOpenSketch = { opened++ },
                )
            }
        }
        rule.onNodeWithContentDescription("sketch").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theNewSketchToolbarButtonFiresItsCallback() {
        var started = 0
        rule.setContent {
            MaterialTheme {
                MobileBlockToolbar(
                    editingBlockId = "b1", onIndent = {}, onOutdent = {}, onMoveUp = {}, onMoveDown = {},
                    onNewSketch = { started++ },
                )
            }
        }
        rule.onNodeWithContentDescription("New sketch").performClick()
        assertEquals(1, started)
    }
}
