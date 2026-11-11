package dev.stapler.stelekit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX-28: muted text (`onSurfaceVariant`) must reach WCAG AA 4.5:1 on every surface role it can sit on.
 * Schemes mirror the private ones in `Theme.kt` (same factory, same arguments), as in QrTransferContrastTest.
 */
class MutedTextContrastTest {

    private val light = lightColorScheme(
        primary = DeepPatina,
        background = ParchmentBackground,
        onBackground = DarkStoneText,
        surface = ParchmentBackground,
        onSurface = DarkStoneText,
        surfaceVariant = LimestoneSurface,
        onSurfaceVariant = AgedStone,
    )

    private val dark = darkColorScheme(
        primary = PatinaAccent,
        background = StoneBackground,
        onBackground = SandText,
        surface = StoneBackground,
        onSurface = SandText,
        surfaceVariant = GraniteSurface,
        onSurfaceVariant = WornStone,
    )

    // Stone theme uses the same background/surface/surfaceVariant/onSurfaceVariant as dark.
    private val stone = darkColorScheme(
        primary = PatinaAccent,
        background = StoneBackground,
        onBackground = SandText,
        surface = StoneBackground,
        onSurface = SandText,
        surfaceVariant = GraniteSurface,
        onSurfaceVariant = WornStone,
    )

    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun backgroundsOf(s: ColorScheme): Map<String, Color> = mapOf(
        "background" to s.background,
        "surface" to s.surface,
        "surfaceVariant" to s.surfaceVariant,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
        "surfaceBright" to s.surfaceBright,
        "surfaceDim" to s.surfaceDim,
    )

    @Test
    fun onSurfaceVariant_should_MeetWcagAa_When_PlacedOnEverySurfaceRoleInEveryTheme() {
        for ((theme, scheme) in listOf("light" to light, "dark" to dark, "stone" to stone)) {
            for ((role, bg) in backgroundsOf(scheme)) {
                val ratio = contrast(scheme.onSurfaceVariant, bg)
                assertTrue(ratio >= 4.5, "[$theme] onSurfaceVariant on $role was $ratio, expected >= 4.5:1")
            }
        }
    }

    @Test
    fun mutedPaletteColors_should_MeetWcagAa_When_UsedAsTextOnThemeBackgrounds() {
        val pairs = listOf(
            "AgedStone on ParchmentBackground" to (AgedStone to ParchmentBackground),
            "AgedStone on LimestoneSurface" to (AgedStone to LimestoneSurface),
            "WornStone on StoneBackground" to (WornStone to StoneBackground),
            "WornStone on GraniteSurface" to (WornStone to GraniteSurface),
        )
        for ((label, p) in pairs) {
            val ratio = contrast(p.first, p.second)
            assertTrue(ratio >= 4.5, "$label was $ratio, expected >= 4.5:1")
        }
    }
}
