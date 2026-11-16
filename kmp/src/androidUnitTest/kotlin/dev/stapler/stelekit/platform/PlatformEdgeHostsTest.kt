// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 3.1.2h (UX-102, UX-121): the Android palm classifier and system-gesture exclusion host, driven with
 * synthesised `MotionEvent`s and a real `View` under Robolectric. This is NOT a device run: whether a real device
 * reports `TOOL_TYPE_PALM`, grants the span or delivers the drag is Task 0.1.4d, so nothing here marks a target Supported.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformEdgeHostsTest {
    private fun motion(action: Int, toolType: Int, flags: Int = 0): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f })
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
    }

    @Test
    fun palmToolType_should_BeClassifiedAsPalm_And_FingerStylusAndMouseShouldNot() {
        assertTrue(AndroidPalmClassifier.isPalm(motion(MotionEvent.ACTION_DOWN, AndroidPalmClassifier.TOOL_TYPE_PALM), 0))
        for (tool in listOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_MOUSE, MotionEvent.TOOL_TYPE_ERASER)) {
            assertFalse(AndroidPalmClassifier.isPalm(motion(MotionEvent.ACTION_DOWN, tool), 0), "tool type $tool")
        }
    }

    @Test
    fun framework_should_FlagAPalmCancel_Only_WhenTheCancelCarriesFlagCanceled() {
        val flagged = motion(MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_FINGER, AndroidPalmClassifier.FLAG_CANCELED)
        assertTrue(AndroidPalmClassifier.isPalm(flagged, 0))
        assertFalse(AndroidPalmClassifier.isPalm(motion(MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_FINGER), 0))
        assertFalse(AndroidPalmClassifier.isPalm(motion(MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_FINGER, AndroidPalmClassifier.FLAG_CANCELED), 0))
    }

    @Test
    fun classifier_should_BeSafe_ForNonMotionEventsAndBadIndexes() {
        assertFalse(AndroidPalmClassifier.isPalm(null, 0))
        assertFalse(AndroidPalmClassifier.isPalm("not an event", 0))
        assertFalse(AndroidPalmClassifier.isPalm(motion(MotionEvent.ACTION_DOWN, AndroidPalmClassifier.TOOL_TYPE_PALM), 3))
        assertFalse(AndroidPalmClassifier.isPalm(motion(MotionEvent.ACTION_DOWN, AndroidPalmClassifier.TOOL_TYPE_PALM), -1))
        assertFalse(PalmClassifier.None.isPalm(motion(MotionEvent.ACTION_DOWN, AndroidPalmClassifier.TOOL_TYPE_PALM), 0))
    }

    @Test
    fun cancelDetector_should_SeeASystemCancel_ButNotARelease_ANullEvent_OrAPalmFlaggedCancel() {
        assertTrue(AndroidPointerCancelDetector.isCancelled(motion(MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_FINGER), 0))
        assertFalse(AndroidPointerCancelDetector.isCancelled(motion(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER), 0))
        assertFalse(AndroidPointerCancelDetector.isCancelled(motion(MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_FINGER, AndroidPalmClassifier.FLAG_CANCELED), 0), "that is the palm classifier's")
        assertFalse(AndroidPointerCancelDetector.isCancelled(null, 0))
        assertFalse(PointerCancelDetector.None.isCancelled(motion(MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_FINGER), 0))
    }

    @Test
    fun exclusion_should_RegisterOnlyTheRequestedSpan_ClampedToTheGrantedHeight_AndClearOnRelease() {
        val view = View(ApplicationProvider.getApplicationContext())
        val host = AndroidEdgeGestureExclusion(view, maxSpanPx = SPAN)
        assertTrue(host.canRequest)
        host.request(listOf(EdgeExclusionRect(0, 100, 44, 100 + TALL), EdgeExclusionRect(700, 0, 744, 150)))
        val rects = view.systemGestureExclusionRects
        assertEquals(2, rects.size)
        assertEquals(SPAN, rects[0].height())
        assertEquals(100 + (TALL - SPAN) / 2, rects[0].top)
        assertEquals(150, rects[1].height())
        host.release()
        assertTrue(view.systemGestureExclusionRects.isEmpty())
    }

    @Test
    fun exclusion_should_BeReapplied_OnEveryLayoutPass() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = View(activity)
        activity.setContentView(view)
        val host = AndroidEdgeGestureExclusion(view, maxSpanPx = SPAN)
        host.request(listOf(EdgeExclusionRect(0, 0, 44, SPAN)))
        view.systemGestureExclusionRects = emptyList()
        assertTrue(view.systemGestureExclusionRects.isEmpty())
        view.requestLayout()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, view.systemGestureExclusionRects.size, "the host re-registers after a layout pass")
        host.release()
    }

    @Test
    fun hostBelowApi29_should_RefuseToRequest_SoTheStripStaysOff() {
        val view = View(ApplicationProvider.getApplicationContext())
        val host = AndroidEdgeGestureExclusion(view, maxSpanPx = SPAN, sdkInt = PRE_Q)
        assertFalse(host.canRequest)
        host.request(listOf(EdgeExclusionRect(0, 0, 44, SPAN)))
        assertTrue(view.systemGestureExclusionRects.isEmpty())
    }

    @Test
    fun invertedRect_should_BeRejectedAtConstruction() {
        val failed = runCatching { EdgeExclusionRect(10, 10, 5, 20) }
        assertTrue(failed.isFailure)
    }

    private companion object {
        const val SPAN = 200
        const val TALL = 600
        const val PRE_Q = 28
    }
}
