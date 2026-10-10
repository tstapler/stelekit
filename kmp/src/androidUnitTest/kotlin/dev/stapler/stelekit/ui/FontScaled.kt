package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * [density] (default 1f; pass the qualifier's real density when a test sets one) with [scale] as font scale. Avoids `LocalDensity.current`, whose
 * inline getter Bazel's Kotlin backend fails to inline in this source set ("Couldn't inline method call").
 */
@Composable
internal fun FontScaled(scale: Float, density: Float = 1f, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = scale), content = content)
}
