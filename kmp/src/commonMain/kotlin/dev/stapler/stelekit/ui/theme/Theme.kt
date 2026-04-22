// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Stelekit extended colors that don't fit into standard Material 3 ColorScheme
 */
@Immutable
data class StelekitExtendedColors(
    val bullet: Color,
    val indentGuide: Color,
    val sidebarBackground: Color,
    val blockRefBackground: Color
)

val LocalStelekitColors = staticCompositionLocalOf {
    StelekitExtendedColors(
        bullet = Color.Unspecified,
        indentGuide = Color.Unspecified,
        sidebarBackground = Color.Unspecified,
        blockRefBackground = Color.Unspecified
    )
}

private val LightColorScheme = lightColorScheme(
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
    onSurfaceVariant = AgedStone
)

private val LightExtendedColors = StelekitExtendedColors(
    bullet = PaleStone,
    indentGuide = PaleStone.copy(alpha = INDENT_GUIDE_ALPHA),
    sidebarBackground = LimestoneSurface,
    blockRefBackground = DeepPatina.copy(alpha = BLOCK_REF_ALPHA)
)

private val DarkColorScheme = darkColorScheme(
    primary = PatinaAccent,
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F),
    background = StoneBackground,
    onBackground = SandText,
    surface = StoneBackground,
    onSurface = SandText,
    surfaceVariant = GraniteSurface,
    onSurfaceVariant = WornStone
)

private val DarkExtendedColors = StelekitExtendedColors(
    bullet = WornStone.copy(alpha = 0.6f),
    indentGuide = WornStone.copy(alpha = INDENT_GUIDE_ALPHA),
    sidebarBackground = GraniteSurface,
    blockRefBackground = PatinaAccent.copy(alpha = BLOCK_REF_ALPHA)
)

// Stone theme: same warm stone palette as dark, richer contrast
private val StoneColorScheme = darkColorScheme(
    primary = PatinaAccent,
    onPrimary = StoneBackground,
    primaryContainer = StoneMid,
    onPrimaryContainer = CreamText,
    secondary = OchreHighlight,
    onSecondary = StoneBackground,
    background = StoneBackground,
    onBackground = SandText,
    surface = StoneBackground,
    onSurface = SandText,
    surfaceVariant = GraniteSurface,
    onSurfaceVariant = WornStone
)

private val StoneExtendedColors = StelekitExtendedColors(
    bullet = WornStone.copy(alpha = 0.8f),
    indentGuide = WornStone.copy(alpha = 0.2f),
    sidebarBackground = GraniteSurface,
    blockRefBackground = PatinaAccent.copy(alpha = 0.1f)
)

enum class StelekitThemeMode {
    LIGHT, DARK, SYSTEM, STONE, DYNAMIC
}

private var isDarkThemeSystem: Boolean = false

fun setSystemDarkTheme(dark: Boolean) {
    isDarkThemeSystem = dark
}

@Composable
fun StelekitTheme(
    themeMode: StelekitThemeMode = StelekitThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        StelekitThemeMode.LIGHT -> false
        StelekitThemeMode.DARK -> true
        StelekitThemeMode.STONE -> true
        StelekitThemeMode.SYSTEM -> isDarkThemeSystem
        StelekitThemeMode.DYNAMIC -> isDarkThemeSystem
    }

    val colorScheme = when (themeMode) {
        StelekitThemeMode.LIGHT -> LightColorScheme
        StelekitThemeMode.DARK -> DarkColorScheme
        StelekitThemeMode.STONE -> StoneColorScheme
        StelekitThemeMode.SYSTEM -> if (isDarkThemeSystem) DarkColorScheme else LightColorScheme
        // DYNAMIC: use platform-provided dynamic colors (Android 12+); fall back to static scheme
        StelekitThemeMode.DYNAMIC -> getDynamicColorScheme(darkTheme)
            ?: if (isDarkThemeSystem) DarkColorScheme else LightColorScheme
    }

    // TODO: For DYNAMIC mode, derive extended colors from Material 3 roles (tonal palette)
    //  rather than static stone/parchment palettes. Tracked as a follow-up improvement.
    val extendedColors = when (themeMode) {
        StelekitThemeMode.LIGHT -> LightExtendedColors
        StelekitThemeMode.DARK -> DarkExtendedColors
        StelekitThemeMode.STONE -> StoneExtendedColors
        StelekitThemeMode.SYSTEM -> if (isDarkThemeSystem) DarkExtendedColors else LightExtendedColors
        StelekitThemeMode.DYNAMIC -> if (isDarkThemeSystem) DarkExtendedColors else LightExtendedColors
    }

    val baseTypography = Typography()
    val stelekitTypography = Typography(
        bodyLarge = baseTypography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = baseTypography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        titleLarge = baseTypography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = baseTypography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    )

    CompositionLocalProvider(LocalStelekitColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = stelekitTypography,
            content = content
        )
    }
}

/**
 * Accessor for Stelekit extended colors within a composition
 */
object StelekitTheme {
    val colors: StelekitExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStelekitColors.current
}
