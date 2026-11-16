// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.input.pointer.PointerEvent

/**
 * Android palm classification from the raw `MotionEvent` (Task 3.1.2h): Compose reports a palm as `PointerType.Unknown`
 * and still delivers the press, so the adapter asks here. A contact is a palm when its tool type is
 * `TOOL_TYPE_PALM` or the framework flagged the cancel as a palm rejection (`FLAG_CANCELED`). Nothing else counts: the
 * app has no size threshold of its own (UX-102). Behaviour on real hardware is UNVERIFIED (Task 0.1.4d).
 */
object AndroidPalmClassifier : PalmClassifier {
    /** `MotionEvent.TOOL_TYPE_PALM`, API 34; the literal keeps the check valid on older compile-time APIs. */
    const val TOOL_TYPE_PALM = 5

    /** `MotionEvent.FLAG_CANCELED`, API 33: set when the framework cancels a pointer because it turned out to be a palm. */
    const val FLAG_CANCELED = 0x20

    override fun isPalm(nativeEvent: Any?, pointerIndex: Int): Boolean {
        val e = (nativeEvent as? PointerEvent)?.motionEvent ?: nativeEvent as? MotionEvent ?: return false
        if (pointerIndex < 0 || pointerIndex >= e.pointerCount) return false
        if (e.getToolType(pointerIndex) == TOOL_TYPE_PALM) return true
        val cancelling = e.actionMasked == MotionEvent.ACTION_CANCEL || e.actionMasked == MotionEvent.ACTION_POINTER_UP
        return cancelling && (e.flags and FLAG_CANCELED) != 0
    }
}

/** Android system cancel from the raw `MotionEvent` (`ACTION_CANCEL`; Task 3.1.2g). A palm-flagged cancel is the palm classifier's, not this one's. */
object AndroidPointerCancelDetector : PointerCancelDetector {
    override fun isCancelled(nativeEvent: Any?, pointerIndex: Int): Boolean {
        val e = (nativeEvent as? PointerEvent)?.motionEvent ?: nativeEvent as? MotionEvent ?: return false
        return e.actionMasked == MotionEvent.ACTION_CANCEL && (e.flags and AndroidPalmClassifier.FLAG_CANCELED) == 0
    }
}

/**
 * Claims the protected span of an edge from the system back gesture with `View.setSystemGestureExclusionRects`
 * (API 29). The platform keeps only what a layout pass last registered, so the rectangles are re-applied on every
 * global layout. Each rectangle is clamped to [maxSpanPx] tall, centred, because the system grants a bounded height
 * per edge (200 dp expected, measured by Task 0.1.4d); asking for more is silently trimmed by the platform anyway.
 */
class AndroidEdgeGestureExclusion(
    private val view: View,
    private val maxSpanPx: Int,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : EdgeGestureExclusion {
    private var requested: List<EdgeExclusionRect> = emptyList()
    private var listening = false
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { apply() }

    override val canRequest: Boolean get() = sdkInt >= Build.VERSION_CODES.Q

    override fun request(rects: List<EdgeExclusionRect>) {
        requested = rects
        if (!canRequest) return
        if (!listening) {
            view.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
            listening = true
        }
        apply()
    }

    override fun release() {
        requested = emptyList()
        if (listening) {
            view.viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
            listening = false
        }
        apply()
    }

    private fun apply() {
        if (!canRequest) return
        view.systemGestureExclusionRects = requested.map { clamped(it) }
    }

    private fun clamped(r: EdgeExclusionRect): Rect {
        val trim = (r.height - maxSpanPx).coerceAtLeast(0) / 2
        return Rect(r.left, r.top + trim, r.right, r.bottom - trim)
    }
}
