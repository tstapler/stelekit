// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Every strip icon draws, and no two tools share one (the old Select and Arrow glyphs were both diagonal arrows). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class StripIconsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyIcon_should_Draw() {
        rule.setContent {
            Column {
                StripIcon.entries.chunked(ICONS_PER_ROW).forEach { row ->
                    androidx.compose.foundation.layout.Row { row.forEach { StripIconView(it, Color.Black, Modifier.testTag("icon.${it.name}")) } }
                }
            }
        }
        StripIcon.entries.forEach { rule.onNodeWithTag("icon.${it.name}").assertIsDisplayed() }
    }

    private companion object {
        const val ICONS_PER_ROW = 8
    }

    @Test
    fun everyTool_should_HaveItsOwnIcon() {
        val tools = ToolId.entries.filter { it != ToolId.Select }
        assertEquals(StripIcon.Select, StripIcon.of(ToolId.Select))
        tools.map { StripIcon.of(it) }.filter { it == StripIcon.Select }.also { assertEquals(emptyList(), it, "a tool fell back to the Select icon") }
        assertNotEquals(StripIcon.of(ToolId.Select), StripIcon.of(ToolId.Arrow))
        assertEquals(tools.size, tools.map { StripIcon.of(it) }.toSet().size, "two tools share an icon")
    }
}
