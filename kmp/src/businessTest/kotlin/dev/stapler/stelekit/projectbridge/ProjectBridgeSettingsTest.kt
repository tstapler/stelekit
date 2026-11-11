// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectBridgeSettingsTest {
    private val store = BridgeMapSettings()
    private val settings = ProjectBridgeSettings(store)

    @Test
    fun everythingIsOff_When_NothingWasEverStored() {
        assertFalse(settings.homeSketchEnabled)
        assertFalse(settings.digestEnabled("g1"))
        assertFalse(settings.analyseAfterSync("g1"))
        assertFalse(settings.isDigestActive("g1"))
    }

    @Test
    fun digestIsInactive_When_OnlyTheFlagIsOn() {
        settings.homeSketchEnabled = true

        assertFalse(settings.isDigestActive("g1"))
    }

    @Test
    fun digestIsInactive_When_OnlyThePerGraphOptInIsOn() {
        settings.setDigestEnabled("g1", true)

        assertFalse(settings.isDigestActive("g1"))
    }

    @Test
    fun digestIsActive_When_FlagAndOptInAreBothOn() {
        settings.homeSketchEnabled = true
        settings.setDigestEnabled("g1", true)

        assertTrue(settings.isDigestActive("g1"))
        assertFalse(settings.isDigestActive("g2"), "the opt-in is per graph")
    }

    @Test
    fun turningTheFlagOff_StopsEveryGraph() {
        settings.homeSketchEnabled = true
        settings.setDigestEnabled("g1", true)

        settings.homeSketchEnabled = false

        assertFalse(settings.isDigestActive("g1"))
    }

    @Test
    fun digestTitle_DefaultsToDisplayNameDigest_UntilOverridden() {
        assertEquals("Our House Digest", settings.digestPageTitle("g1", "Our House"))

        settings.setDigestPageTitle("g1", "House notes")

        assertEquals("House notes", settings.digestPageTitle("g1", "Our House"))
        assertEquals("Our House Digest", settings.digestPageTitle("g2", "Our House"))
    }

    @Test
    fun blankStoredTitle_FallsBackToTheDefault() {
        settings.setDigestPageTitle("g1", "  ")

        assertEquals("Shed Digest", settings.digestPageTitle("g1", "Shed"))
    }

    @Test
    fun analyseAfterSync_RoundTripsPerGraph() {
        settings.setAnalyseAfterSync("g1", true)

        assertTrue(settings.analyseAfterSync("g1"))
        assertFalse(settings.analyseAfterSync("g2"))
    }

    @Test
    fun flagIsStoredUnderTheDocumentedKey() {
        settings.homeSketchEnabled = true

        assertTrue(store.getBoolean(ProjectBridgeSettings.HOME_SKETCH_KEY, false))
    }
}
