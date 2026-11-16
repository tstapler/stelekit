// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

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
