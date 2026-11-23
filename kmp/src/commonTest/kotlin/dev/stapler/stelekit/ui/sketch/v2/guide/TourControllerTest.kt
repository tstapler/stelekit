// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.sketch.v2.TabStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TourControllerTest {
    private class MapSettings : Settings {
        val map = HashMap<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private val a = Tour("a", "A", listOf(TourStep("1", TabStop.ToolStrip, "t1", "b1"), TourStep("2", null, "t2", "b2")), setOf("feature.a"))
    private val b = Tour("b", "B", listOf(TourStep("1", null, "t", "b")), setOf("feature.a", "feature.b"))
    private val settings = MapSettings()

    /** A fresh controller over the same stored settings, like a new session on the same device. */
    private fun controller() = TourController(TourRegistry(listOf(a, b)), TourProgress(settings))

    @Test
    fun firstUse_should_StartTheMatchingTour_ThenTheNextUnseenOne_ThenNothing() {
        val c = controller()
        assertTrue(c.onFeatureFirstUse("feature.a"))
        assertEquals("a", c.active?.id)
        c.finish()
        assertTrue(c.onFeatureFirstUse("feature.a"))
        assertEquals("b", c.active?.id)
        c.finish()
        assertFalse(c.onFeatureFirstUse("feature.a"))
        assertNull(c.active)
    }

    @Test
    fun firstUse_should_StartNothing_When_TheTourWasSeenInAnEarlierSession() {
        controller().apply { onFeatureFirstUse("feature.b"); finish() }
        assertFalse(controller().onFeatureFirstUse("feature.b"))
        assertFalse(controller().onFeatureFirstUse("unknown"))
    }

    @Test
    fun firstUse_should_NotStart_While_AnotherTourIsShowing() {
        val c = controller()
        c.onFeatureFirstUse("feature.a")
        assertFalse(c.onFeatureFirstUse("feature.b"))
        assertEquals("a", c.active?.id)
    }

    @Test
    fun quittingHalfWay_should_NotBringTheTourBack() {
        val c = controller()
        c.onFeatureFirstUse("feature.a"); c.next(); c.finish()
        assertTrue(TourProgress(settings).isSeen("a"))
        assertEquals("b", controller().apply { onFeatureFirstUse("feature.a") }.active?.id)
    }

    @Test
    fun skipAll_should_StopAutomaticTours_ButReplayStillWorks_AndTheSwitchTurnsThemBackOn() {
        val c = controller()
        c.onFeatureFirstUse("feature.a")
        c.skipAll()
        assertNull(c.active)
        assertFalse(c.autoTours)
        assertFalse(controller().onFeatureFirstUse("feature.b"))
        assertTrue(controller().replay("a"))
        c.setAutoTours(true)
        assertTrue(c.autoTours)
        assertTrue(c.onFeatureFirstUse("feature.b"))
    }

    @Test
    fun replay_should_StartASeenTourFromTheTop_AndRefuseAnUnknownId() {
        val c = controller()
        c.onFeatureFirstUse("feature.a"); c.next()
        c.finish()
        assertTrue(c.replay("a"))
        assertEquals(0, c.stepIndex)
        c.finish()
        assertFalse(c.replay("nope"))
    }

    @Test
    fun steps_should_AdvanceBackAndEndAfterTheLast() {
        val c = controller()
        c.back()
        assertNull(c.step)
        c.replay("a")
        assertEquals("1", c.step?.id)
        c.back()
        assertEquals(0, c.stepIndex)
        c.next()
        assertTrue(c.isLast)
        c.next()
        assertNull(c.active)
        assertNull(c.step)
        assertFalse(c.isLast)
        assertEquals(listOf("a", "b"), c.tours.map { it.id })
    }

    @Test
    fun registry_should_RefuseDuplicateIdsAndEmptyTours() {
        assertFailsWith<IllegalArgumentException> { TourRegistry(listOf(a, a)) }
        assertFailsWith<IllegalArgumentException> { Tour("x", "X", emptyList()) }
    }
}
