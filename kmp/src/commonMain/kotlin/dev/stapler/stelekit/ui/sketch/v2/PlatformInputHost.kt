// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import dev.stapler.stelekit.platform.EdgeGestureExclusion
import dev.stapler.stelekit.platform.PalmClassifier
import dev.stapler.stelekit.platform.PointerCancelDetector
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSide
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings

/**
 * Routes the web build's DOM pointer samples (window CSS pixels) to the canvas: a contact that starts outside the canvas is
 * dropped, one that starts inside keeps flowing until it ends (a drag may leave the canvas), and every position becomes
 * canvas-local pixels. [scale] is the device pixel ratio, which is Compose's density on the web. UNVERIFIED on a device (Task 0.1.4d).
 */
class RawPointerRouter(private val bounds: () -> ScreenRect, private val scale: () -> Double) {
    private val tracked = HashSet<Int>()

    fun route(raw: RawPointer): RawPointer? {
        val canvas = bounds()
        val x = raw.position.x * scale()
        val y = raw.position.y * scale()
        val inside = ScreenPx(x, y) in canvas
        val keep = when (raw.phase) {
            RawPhase.Down -> inside.also { if (it) tracked += raw.id }
            RawPhase.Hover -> inside
            RawPhase.Move -> raw.id in tracked
            RawPhase.Up, RawPhase.Cancel -> tracked.remove(raw.id)
        }
        return if (keep) raw.copy(position = ScreenPx(x - canvas.left, y - canvas.top)) else null
    }
}

/**
 * The platform input of one open sheet (Task 3.1.2h): the palm and system-cancel seams the pointer modifier consults, the
 * raw pointer source of the web build, and the edge-pan strips with their system gesture exclusion and cancel backstop.
 * The strip is Off until Task 0.1.4d marks a target Supported or the user chooses a side, so with the recorded results
 * nothing here reaches the system gestures.
 */
class PlatformInputHost(
    private val platform: HostPlatform,
    private val adapter: SketchPointerAdapter,
    private val onActions: (List<PointerAction>) -> Unit,
    sequencer: OnboardingSequencer?,
    /** The Finger drawing controller, chained before the edge-pan backstop; null where the setting is not wired. */
    finger: PointerGestureListener? = null,
) {
    private val backstop = platform.edgePan?.let { settings ->
        sequencer?.let { EdgePanBackstop(settings, it, SketchEditorStrings.Banners) }
    }
    private var bounds = ScreenRect(0.0, 0.0, 0.0, 0.0)
    private var density = 1.0

    init {
        val existing = finger ?: adapter.listener
        adapter.listener = object : PointerGestureListener {
            override fun onStylusStrokeEnd() = existing?.onStylusStrokeEnd() ?: Unit

            override fun onOneFingerDrag() = existing?.onOneFingerDrag() ?: Unit

            override fun onStripDragCancelled() {
                existing?.onStripDragCancelled()
                this@PlatformInputHost.onStripDragCancelled()
            }
        }
    }

    val palm: PalmClassifier get() = platform.input.palm
    val cancel: PointerCancelDetector get() = platform.input.cancel
    val exclusion: EdgeGestureExclusion? get() = platform.input.edgeExclusion

    /** True when the platform supplies the samples, so the Compose pointer modifier must not listen too. */
    val usesRawPointers: Boolean get() = platform.input.rawPointers != null

    var strips: List<EdgeStrip> by mutableStateOf(emptyList())
        private set

    var widthDp: Double by mutableDoubleStateOf(0.0)
        private set

    /** True while a strip is drawn at the end side, so the view cluster keeps clear of it. */
    val stripAtEnd: Boolean get() = strips.any { it.edge == StripEdge.Right }

    /** The canvas moved or resized: [origin] is its top-left in the window and [size] its extent, both in pixels. */
    fun onBounds(origin: ScreenPx, size: ScreenPx, pxPerDp: Double) {
        bounds = ScreenRect(origin.x, origin.y, origin.x + size.x, origin.y + size.y)
        density = pxPerDp
        widthDp = size.x / pxPerDp
        relayout(size.y / pxPerDp)
    }

    private fun relayout(heightDp: Double) {
        val settings = platform.edgePan
        val side = if (settings == null || backstop?.hiddenForSession == true) {
            EdgePanSide.Off
        } else {
            EdgePanPolicy.effectiveSide(settings, platform.edgeTarget, widthDp.toInt(), exclusion?.canRequest == true)
        }
        strips = EdgePanGeometry.strips(side, EdgePanGeometry.Host(widthDp, heightDp))
        adapter.edgeStrips = EdgePanGeometry.hitRects(strips, density)
    }

    /** The system cancelled a one-finger strip drag: the second one hides the strip for the session (Task 3.1.2g). */
    internal fun onStripDragCancelled() {
        val stop = backstop ?: return
        stop.onStripDragCancelled()
        if (stop.hiddenForSession) {
            strips = emptyList()
            adapter.edgeStrips = emptyList()
        }
    }

    /** Starts the platform's raw pointer source, if it has one, and returns what stops it. */
    fun installRawPointers(): () -> Unit {
        val source = platform.input.rawPointers ?: return {}
        val router = RawPointerRouter({ bounds }, { density })
        return source.install { raw -> router.route(raw)?.let { onActions(adapter.onPointer(it)) } }
    }
}

/** The platform input's overlays: the edge-pan strips and the raw pointer source, both absent where the platform wired nothing. */
@Composable
internal fun BoxScope.PlatformInputOverlays(input: PlatformInputHost) {
    val density = LocalDensity.current.density.toDouble()
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            val at = it.positionInWindow()
            input.onBounds(ScreenPx(at.x.toDouble(), at.y.toDouble()), ScreenPx(it.size.width.toDouble(), it.size.height.toDouble()), density)
        },
    )
    EdgePanStrip(input.strips, input.widthDp, input.exclusion)
    DisposableEffect(input) {
        val stop = input.installRawPointers()
        onDispose(stop)
    }
}
