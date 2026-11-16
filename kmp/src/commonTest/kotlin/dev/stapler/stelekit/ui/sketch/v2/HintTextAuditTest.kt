// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 3.6.8 (UX-124): every tool has a keyboard hint and a touch hint, and a touch hint names no key. */
class HintTextAuditTest {
    private val keys = Regex("""\b(Shift|Alt|Ctrl|Cmd|Esc|Enter|Tab|double-click)\b""")

    @Test
    fun everyTool_should_HaveAKeyboardAndATouchHint() {
        for (tool in ToolId.entries) {
            val hint = SketchStrings.Hints.of(tool)
            assertTrue(hint.keyboard.isNotBlank() && hint.touch.isNotBlank(), "$tool is missing a hint")
        }
    }

    @Test
    fun aTouchHint_should_NameNoKeyAndNoDoubleClick() {
        val offenders = ToolId.entries.filter { keys.containsMatchIn(SketchStrings.Hints.of(it).touch) }
        assertEquals(emptyList(), offenders, "touch hints that name a key")
    }

    @Test
    fun aKeyboardHint_should_StillMentionItsModifiers_WhereTheToolHasThem() {
        assertTrue("Shift" in SketchStrings.Hints.of(ToolId.Rectangle).keyboard)
        assertTrue("Ctrl" in SketchStrings.Hints.of(ToolId.Arrow).keyboard)
        assertTrue("Enter" in SketchStrings.Hints.of(ToolId.Line).keyboard)
    }

    @Test
    fun theModifierChips_should_StandInForTheKeys_OnTouch() {
        val rectangle = SketchStrings.Hints.of(ToolId.Rectangle).touch
        assertTrue("chips" in rectangle)
        assertTrue("Don't bind" in SketchStrings.Hints.of(ToolId.Arrow).touch)
        assertTrue("Done" in SketchStrings.Hints.of(ToolId.Line).touch)
        assertTrue("15 degrees" in SketchStrings.Hints.of(ToolId.Line).touch)
    }

    @Test
    fun theDetector_should_CatchAPlantedKeyName() {
        assertTrue(keys.containsMatchIn("Hold Shift to square it"))
        assertFalse(keys.containsMatchIn("Drag to draw."))
    }
}
