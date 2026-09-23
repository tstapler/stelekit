package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Regression test for ux.md criterion 21 (Layer 4 acceptance criteria): the disabled-state
 * icon/label tint in [AnnotationToolbar] ([DISABLED_TINT_COLOR]) must maintain a WCAG contrast
 * ratio of at least 4.5:1 against the toolbar background ([TOOLBAR_BACKGROUND_COLOR]).
 *
 * The original color, Color(0xFF555555), measured ~2.33:1 against Color(0xEE1A1A1A) — a
 * verified accessibility failure caught during /sdd:6-verify. This test computes the ratio
 * directly from the production color constants using the standard WCAG relative-luminance
 * formula so any future regression (someone reverting to a low-contrast gray) fails the build
 * instead of silently shipping.
 */
class DisabledTintContrastTest {

    /** sRGB channel (0-1) -> linear-light channel, per the WCAG 2.x definition. */
    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance of an opaque color, per WCAG: L = 0.2126*R + 0.7152*G + 0.0722*B. */
    private fun relativeLuminance(color: Color): Double {
        val r = linearize(color.red)
        val g = linearize(color.green)
        val b = linearize(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio between two colors: (L_lighter + 0.05) / (L_darker + 0.05). */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun disabledTint_should_meetMinimumContrast_When_measuredAgainstToolbarBackground() {
        // Alpha caveat: TOOLBAR_BACKGROUND_COLOR carries alpha 0xEE (~93%). This test treats
        // both colors at full opacity against each other, which is the standard approximation
        // when the compositing target behind the toolbar is unknown/variable; the true
        // composited background is even darker (closer to the app's near-black scrim), which
        // would only increase — never decrease — the measured contrast ratio here.
        val ratio = contrastRatio(DISABLED_TINT_COLOR, TOOLBAR_BACKGROUND_COLOR)

        assertTrue(
            "Disabled tint contrast ratio was $ratio, expected >= 4.5:1 (WCAG AA for text/icons)",
            ratio >= 4.5,
        )
    }

    @Test
    fun previousDisabledTint_should_haveFailedMinimumContrast_regressionBaseline() {
        // Documents the originally-flagged violation so the fix's necessity stays legible in
        // the test suite: Color(0xFF555555) against Color(0xEE1A1A1A) measured ~2.33:1.
        val previousTint = Color(0xFF555555)
        val ratio = contrastRatio(previousTint, TOOLBAR_BACKGROUND_COLOR)

        assertTrue(
            "Expected the previously-flagged tint to remain under 4.5:1 (ratio was $ratio)",
            ratio < 4.5,
        )
    }
}
