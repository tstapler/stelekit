package dev.stapler.stelekit.performance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Platform-neutral interface for recording navigation and interaction events as spans.
 * Inject a platform-specific implementation (e.g. [OtelSpanRecorder] on JVM/Android)
 * via [LocalSpanRecorder].
 *
 * Mirrors the Datadog Compose integration's NavigationViewTrackingEffect API.
 */
interface SpanRecorder {
    fun recordScreenView(screenName: String)
    fun recordInteraction(componentName: String, action: String = "tap")
}

/** No-op fallback used when no recorder is wired (e.g. iOS, tests). */
object NoOpSpanRecorder : SpanRecorder {
    override fun recordScreenView(screenName: String) {}
    override fun recordInteraction(componentName: String, action: String) {}
}

/**
 * CompositionLocal providing the active [SpanRecorder].
 * Provide an [OtelSpanRecorder] (JVM/Android) at the root composable.
 */
val LocalSpanRecorder = staticCompositionLocalOf<SpanRecorder> { NoOpSpanRecorder }

/**
 * Records a screen-view span when the composable enters composition and marks it ended
 * on disposal. Analogous to Datadog's `NavigationViewTrackingEffect`.
 *
 * ```kotlin
 * NavigationTracingEffect(screenName = "PageView/${page.name}")
 * ```
 */
@Composable
fun NavigationTracingEffect(screenName: String) {
    val recorder = LocalSpanRecorder.current
    DisposableEffect(screenName) {
        recorder.recordScreenView(screenName)
        onDispose {}
    }
}
