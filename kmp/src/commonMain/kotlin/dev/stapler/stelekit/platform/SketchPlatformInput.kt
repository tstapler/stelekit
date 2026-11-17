// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.ui.sketch.v2.RawPointer

/**
 * Platform seams of the v2 sketch canvas input (Task 3.1.2h). Each platform supplies its own host; the pointer adapter and
 * the edge-pan strip (Task 3.1.2g) only see these interfaces. Device behaviour is UNVERIFIED until Task 0.1.4d: no host
 * here is marked Supported by this code, so `EdgePanPolicy` keeps its safe default of Off.
 */

/** A rectangle of the canvas host, in the host view's pixels, where the system edge swipe must not take a drag. */
data class EdgeExclusionRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right >= left && bottom >= top) { "exclusion rect must not be inverted" }
    }

    val height: Int get() = bottom - top
}

/**
 * Asks the system to leave a drag that starts inside the given rectangles to the app (Android system gesture
 * exclusion, iOS edge deferral, web `overscroll-behavior`). Only the protected span of the edge-pan strip is
 * requested, never the whole edge (UX-121). [request] replaces what was requested before; [release] clears it.
 */
interface EdgeGestureExclusion {
    /** False where the platform cannot be asked (Android below API 29): the strip then stays Off. */
    val canRequest: Boolean

    fun request(rects: List<EdgeExclusionRect>)

    fun release()
}

/**
 * Says whether one contact of a pointer event ended because the system cancelled it (Android `ACTION_CANCEL`). Compose
 * reports a system cancel as an ordinary release, so only the host's native event can tell; the edge-pan backstop of Task
 * 3.1.2g counts these. Arguments as for [PalmClassifier.isPalm]. Device behaviour is UNVERIFIED (Task 0.1.4d).
 */
fun interface PointerCancelDetector {
    fun isCancelled(nativeEvent: Any?, pointerIndex: Int): Boolean

    companion object {
        /** No platform detection: a cancel looks like a release and the backstop never fires. */
        val None = PointerCancelDetector { _, _ -> false }
    }
}

/**
 * Says whether one contact of a pointer event is a palm. [nativeEvent] is the event as the host received it (the
 * Compose `PointerEvent`, whose Android `MotionEvent` the classifier unwraps; common Compose has no native-event
 * accessor), [pointerIndex] the contact within it. The app implements no palm detection of its own (no size
 * threshold); it uses what the platform reports and otherwise does not reject (UX-102).
 */
fun interface PalmClassifier {
    fun isPalm(nativeEvent: Any?, pointerIndex: Int): Boolean

    companion object {
        /** No platform classification: every contact is a deliberate touch. */
        val None = PalmClassifier { _, _ -> false }
    }
}

/**
 * Feeds the host the pointer samples of a platform that has a richer source than Compose (the web build's DOM pointer events,
 * which carry pen type, pressure, eraser and barrel button). [install] starts forwarding and returns the disposer. A host
 * that has a source takes its samples from it and does not also listen through Compose, so no contact is seen twice.
 */
fun interface RawPointerSource {
    fun install(onPointer: (RawPointer) -> Unit): () -> Unit
}

/**
 * What one platform root adds to the v2 canvas input (Task 3.1.2h): the palm classifier, the system-cancel detector, the
 * edge-gesture exclusion and, on the web, a raw pointer source. Every field defaults to "nothing": a root that wires none
 * keeps the Compose-only input and the edge-pan strip Off.
 */
data class SketchPlatformInput(
    val palm: PalmClassifier = PalmClassifier.None,
    val cancel: PointerCancelDetector = PointerCancelDetector.None,
    val edgeExclusion: EdgeGestureExclusion? = null,
    val rawPointers: RawPointerSource? = null,
) {
    companion object {
        val None = SketchPlatformInput()
    }
}
