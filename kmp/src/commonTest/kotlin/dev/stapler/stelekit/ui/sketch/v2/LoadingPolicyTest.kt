// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchLoadingStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 3.6.8c (UX-94) and Task 3.6.6b (UX-47): the pure rules of loading states and of the motion policy. */
class LoadingPolicyTest {
    @Test
    fun indicator_should_ShowOnlyAfter200ms() {
        assertFalse(LoadingPolicy.shouldShow(0))
        assertFalse(LoadingPolicy.shouldShow(LoadingPolicy.SHOW_AFTER_MS))
        assertTrue(LoadingPolicy.shouldShow(LoadingPolicy.SHOW_AFTER_MS + 1))
        assertEquals(200L, LoadingPolicy.SHOW_AFTER_MS)
    }

    @Test
    fun announcements_should_BeTheStartAndTheEndOnly_AndTheEndOnlyAfterAShownStart() {
        assertEquals("Exporting...", LoadingPolicy.startText(SketchLoadingStrings.EXPORT))
        assertEquals("Exporting finished", LoadingPolicy.endText(SketchLoadingStrings.EXPORT, startWasShown = true))
        assertEquals("", LoadingPolicy.endText(SketchLoadingStrings.EXPORT, startWasShown = false), "a fast operation ends silently")
    }

    @Test
    fun label_should_NameTheOperation_AndAddThePercentOnlyWhenProgressIsKnown() {
        assertEquals("Importing", LoadingPolicy.label(SketchLoadingStrings.IMPORT, null))
        assertEquals("Importing 40%", LoadingPolicy.label(SketchLoadingStrings.IMPORT, 0.4f))
        assertEquals("Importing 100%", LoadingPolicy.label(SketchLoadingStrings.IMPORT, 7f))
    }

    @Test
    fun motion_should_BeInstant_When_Reduced() {
        assertEquals(0, MotionPolicy.durationMs(300, reduced = true))
        assertEquals(300, MotionPolicy.durationMs(300, reduced = false))
        assertTrue(MotionPolicy.spec(tween<Float>(300), reduced = true) is SnapSpec<*>)
        assertEquals(snap<Float>(), MotionPolicy.spec(snap<Float>(), reduced = true))
        assertFalse(MotionPolicy.spec(tween<Float>(300), reduced = false) is SnapSpec<*>)
    }
}
