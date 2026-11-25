// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.graphics.Color
import dev.stapler.stelekit.ui.theme.stelekitStaticSchemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UX-51: the colors the sheet draws with meet WCAG contrast in every static theme, light and dark. */
class SheetContrastTest {
    private val schemes = stelekitStaticSchemes()

    private fun each(check: (name: String, s: androidx.compose.material3.ColorScheme) -> Unit) = schemes.forEach { (n, s) -> check(n, s) }

    private fun assertRatio(min: Double, a: Color, b: Color, what: String) {
        val r = contrastRatio(a, b)
        assertTrue(r >= min, "$what: $r < $min")
    }

    @Test
    fun contrastRatio_should_MatchTheWcagDefinition() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.001)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
        assertEquals(contrastRatio(Color.Black, Color.Red), contrastRatio(Color.Red, Color.Black), 1e-9)
    }

    @Test
    fun selectionAndFocusOutline_should_Reach3To1_OnEveryCellBackgroundInEveryTheme() {
        each { name, s ->
            val all = cellBackgrounds(s)
            for ((bgName, bg) in listOf("surface", "zebra", "formula tint", "header").zip(all)) {
                val ring = sheetOutlineColor(s.primary, all)
                assertRatio(OUTLINE_MIN_CONTRAST, ring, bg, "$name outline on $bgName")
            }
        }
    }

    @Test
    fun errorText_should_Reach4_5To1_OnCellBackgrounds_InEveryTheme() {
        each { name, s ->
            val all = cellBackgrounds(s)
            for ((bgName, bg) in listOf("surface", "zebra", "formula tint").zip(all)) {
                assertRatio(4.5, sheetErrorColor(s.error, all), bg, "$name error text on $bgName")
            }
        }
    }

    @Test
    fun bodyAndHeaderText_should_Reach4_5To1_InEveryTheme() {
        each { name, s ->
            assertRatio(4.5, s.onSurface, s.surface, "$name body text")
            assertRatio(4.5, s.onSurfaceVariant, s.surfaceVariant, "$name header text")
        }
    }

    @Test
    fun highContrastInk_should_BeTheMaxContrastOfBlackAndWhite_OnEverySurface() {
        each { name, s ->
            val ink = highContrastInk(s.surface)
            assertTrue(ink == Color.Black || ink == Color.White, name)
            assertRatio(7.0, ink, s.surface, "$name high-contrast ink")
        }
    }

    @Test
    fun highContrastOutline_should_BeWiderThanTheNormalOneAndNeverBelow2dp() {
        assertTrue(OutlineWidths.of(TableVisualPrefs()) >= 2f)
        assertTrue(OutlineWidths.of(TableVisualPrefs(highContrast = true)) > OutlineWidths.of(TableVisualPrefs()))
    }

    @Test
    fun outlineColor_should_FallBackToBlackOrWhite_When_ThePreferredColorIsTooCloseToTheBackground() {
        val ring = outlineColor(Color(0xFFEEEEEE), Color.White)
        assertTrue(ring == Color.Black || ring == Color.White)
        assertRatio(OUTLINE_MIN_CONTRAST, ring, Color.White, "fallback on white")
    }
}
