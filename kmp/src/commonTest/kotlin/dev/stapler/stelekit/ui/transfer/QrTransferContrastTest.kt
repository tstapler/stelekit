package dev.stapler.stelekit.ui.transfer

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.stapler.stelekit.ui.theme.AgedStone
import dev.stapler.stelekit.ui.theme.DarkStoneText
import dev.stapler.stelekit.ui.theme.DeepPatina
import dev.stapler.stelekit.ui.theme.GraniteSurface
import dev.stapler.stelekit.ui.theme.LimestoneSurface
import dev.stapler.stelekit.ui.theme.ParchmentBackground
import dev.stapler.stelekit.ui.theme.PatinaAccent
import dev.stapler.stelekit.ui.theme.SandText
import dev.stapler.stelekit.ui.theme.StoneBackground
import dev.stapler.stelekit.ui.theme.WornStone
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX Acceptance Test criterion 15 (validation.md) — AUTOMATED HALF ONLY: computes the WCAG
 * relative-luminance contrast ratio for the actual `ColorScheme` foreground/background token pairs
 * [QrEncodeScreen]'s pre-flight summary line, "No internet connection used" line, and
 * [QrDecodeScreen]'s fragment-count / stalled-hint / terminal-error text resolve to, in both light
 * and dark theme.
 *
 * OUT OF SCOPE (explicitly, per validation.md): the manual Roborazzi-screenshot spot-check against
 * a real contrast-checker tool is NOT attempted here.
 *
 * Token sourcing: none of the `Text(...)` calls this criterion covers pass an explicit `color =`
 * argument (`QrEncodeScreen`'s pre-flight summary / "No internet" line, `QrDecodeScreen`'s
 * "Receiving… (N fragments)" line, and both screens' terminal `Failed` messages) — Compose resolves
 * their color from the ambient `LocalContentColor`, which the app's root `Surface`
 * (`MaterialTheme(colorScheme = ...)` in `ui/theme/Theme.kt`) sets to `contentColorFor(background)`
 * == `onBackground`. The one exception is `QrDecodeScreen`'s stalled/wrong-code/low-light hint line
 * (`HintCopy`), which passes `color = MaterialTheme.colorScheme.error` explicitly.
 *
 * [LightColorScheme]/[DarkColorScheme] below are reconstructed by calling the exact same
 * `androidx.compose.material3.lightColorScheme`/`darkColorScheme` factory functions with the exact
 * same arguments as the private `LightColorScheme`/`DarkColorScheme` vals in `ui/theme/Theme.kt`
 * (verified by reading that file) — not invented tokens. `error`/`onError` are left at the
 * Material3 baseline default in both, matching Theme.kt (which doesn't override them either).
 */
class QrTransferContrastTest {

    /** Mirrors `ui/theme/Theme.kt`'s private `LightColorScheme`. */
    private val lightScheme = lightColorScheme(
        primary = DeepPatina,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6EAE8),
        onPrimaryContainer = Color(0xFF00201E),
        secondary = Color(0xFF535F70),
        onSecondary = Color.White,
        background = ParchmentBackground,
        onBackground = DarkStoneText,
        surface = ParchmentBackground,
        onSurface = DarkStoneText,
        surfaceVariant = LimestoneSurface,
        onSurfaceVariant = AgedStone,
    )

    /** Mirrors `ui/theme/Theme.kt`'s private `DarkColorScheme`. */
    private val darkScheme = darkColorScheme(
        primary = PatinaAccent,
        onPrimary = Color(0xFF003737),
        primaryContainer = Color(0xFF004F4F),
        background = StoneBackground,
        onBackground = SandText,
        surface = StoneBackground,
        onSurface = SandText,
        surfaceVariant = GraniteSurface,
        onSurfaceVariant = WornStone,
    )

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
    fun statusLineColors_should_MeetWcagAaContrastRatio_When_ComputedForLightAndDarkThemeTokenPairs() {
        // (label, foreground token, background token) for each persistent status line criterion 15
        // names: pre-flight summary, "No internet connection used", fragment count, terminal error
        // messages (all default LocalContentColor == onBackground-on-background), plus the
        // explicitly-colored stalled/wrong-code/low-light hint (error-on-background).
        val pairsByTheme = listOf(
            "light" to lightScheme,
            "dark" to darkScheme,
        )

        for ((themeName, scheme) in pairsByTheme) {
            val checks = listOf(
                "pre-flight summary / \"No internet\" / fragment count / terminal error (onBackground on background)" to
                    (scheme.onBackground to scheme.background),
                "stalled/wrong-code/low-light hint (error on background)" to
                    (scheme.error to scheme.background),
            )
            for ((label, pair) in checks) {
                val (fg, bg) = pair
                val ratio = contrastRatio(fg, bg)
                assertTrue(
                    ratio >= 4.5,
                    "[$themeName] $label contrast ratio was $ratio, expected >= 4.5:1 (WCAG AA)",
                )
            }
        }
    }
}
