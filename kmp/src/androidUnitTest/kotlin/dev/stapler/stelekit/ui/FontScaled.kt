package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Density 1f (Robolectric's default) with [scale] as font scale. Avoids `LocalDensity.current`, whose
 * inline getter Bazel's Kotlin backend fails to inline in this source set ("Couldn't inline method call").
 */
@Composable
internal fun FontScaled(scale: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = scale), content = content)
}
