// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * A test or host override of the reduce-motion setting (UX-47): null reads the platform. Provide it with
 * [ProvideReducedMotion]; nothing else should read the platform signal.
 */
@Suppress("CompositionLocalAllowlist") // a test and host override of one setting, read only through rememberReducedMotion
val LocalReducedMotion = staticCompositionLocalOf<Boolean?> { null }

@Composable
fun ProvideReducedMotion(reduced: Boolean?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides reduced) { content() }
}

/** True when every transition must be replaced by an instant change: the override when one is provided, else the platform. */
@Composable
fun rememberReducedMotion(): Boolean = LocalReducedMotion.current ?: platformReducedMotion()

/**
 * The ONE gateway to animation in the v2 UI (Task 3.6.6b, UX-47). With reduced motion every transition is one frame with no
 * interpolation. `MotionPolicyGrepTest` fails on `animate*AsState`, `AnimatedVisibility`, `Animatable`, an infinite transition
 * or a Material progress indicator anywhere else in `ui/sketch/v2`, so a new transition has to come through here.
 */
object MotionPolicy {
    const val DEFAULT_MS = 200
    private const val PULSE_MS = 1_200
    private const val STATIC_HALF = 0.5f

    /** The duration of a transition that would last [base] ms: none when [reduced]. */
    fun durationMs(base: Int, reduced: Boolean): Int = if (reduced) 0 else base

    /** [base] normally, an instant [snap] when [reduced]. */
    fun <T> spec(base: AnimationSpec<T>, reduced: Boolean): AnimationSpec<T> = if (reduced) snap() else base

    /** A value that follows [target]: it eases there normally and jumps there when motion is reduced. */
    @Composable
    fun animateFloat(target: Float, label: String, baseMs: Int = DEFAULT_MS): State<Float> {
        val reduced = rememberReducedMotion()
        return animateFloatAsState(target, spec(tween(baseMs), reduced), label = label)
    }

    /** [content] appears and disappears with a fade; with reduced motion it is simply present or absent in one frame. */
    @Composable
    fun Visibility(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        val reduced = rememberReducedMotion()
        AnimatedVisibility(
            visible,
            modifier,
            enter = if (reduced) EnterTransition.None else fadeIn(),
            exit = if (reduced) ExitTransition.None else fadeOut(),
        ) { content() }
    }

    /**
     * A repeating 0..1 value for a shimmer or a busy glyph: it runs while motion is allowed and is a constant 0 when reduced,
     * so a skeleton is a static block and nothing keeps the frame clock busy.
     */
    @Composable
    fun pulse(label: String, periodMs: Int = PULSE_MS): State<Float> {
        val still = remember { mutableFloatStateOf(0f) }
        if (rememberReducedMotion()) return still
        val transition = rememberInfiniteTransition(label)
        return transition.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label)
    }

    /**
     * A progress bar. [progress] null is indeterminate: it animates normally and is a static half-filled bar when motion is
     * reduced (the operation name beside it carries the meaning).
     */
    @Composable
    fun Bar(progress: (() -> Float)?, modifier: Modifier = Modifier) {
        when {
            progress != null -> LinearProgressIndicator(progress, modifier)
            rememberReducedMotion() -> LinearProgressIndicator({ STATIC_HALF }, modifier)
            else -> LinearProgressIndicator(modifier)
        }
    }

    /** A spinner for a place with no room for a bar; a static ring when motion is reduced. */
    @Composable
    fun Spinner(modifier: Modifier = Modifier) {
        if (rememberReducedMotion()) CircularProgressIndicator({ STATIC_HALF }, modifier) else CircularProgressIndicator(modifier)
    }
}
