package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.luminance

/** Outcome of one Mermaid diagram render attempt. */
sealed interface MermaidRenderResult {
    data class Rendered(val svg: String) : MermaidRenderResult
    data class Failed(val reason: String) : MermaidRenderResult
    data object UnsupportedPlatform : MermaidRenderResult
}

/** Cheap fingerprint of the active color scheme, used to invalidate cached renders on theme change. */
data class ThemeFingerprint(val isLight: Boolean, val colorHash: Int)

/**
 * Derives a [ThemeFingerprint] from the current [MaterialTheme.colorScheme]. Luminance of
 * [androidx.compose.material3.ColorScheme.surface] (rather than `isSystemInDarkTheme()`) drives
 * [ThemeFingerprint.isLight] so it also tracks this app's non-system-tied `STONE` theme mode
 * (`Theme.kt`), which is always dark regardless of the OS setting. `colorHash` combines the three
 * colors most visible in a rendered diagram — background, text, and accent — so any theme
 * (including a future custom one) that changes what a diagram actually looks like invalidates
 * the cache, without over-invalidating on unrelated color-scheme fields.
 */
@Composable
@ReadOnlyComposable
fun currentThemeFingerprint(): ThemeFingerprint {
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() >= 0.5f
    val colorHash = 31 * (31 * colorScheme.surface.hashCode() + colorScheme.onSurface.hashCode()) +
        colorScheme.primary.hashCode()
    return ThemeFingerprint(isLight, colorHash)
}
