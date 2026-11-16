// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** U-E2-13: the first-three-times strip hints show, persist their count in Settings, and then stop. */
class StatusStripHintTest {
    private class MapSettings : Settings {
        private val map = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key]?.toBooleanStrictOrNull() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    @Test
    fun firstThreeTimesHint_should_ShowThenStop_AndPersistCountInSettings() {
        val settings = MapSettings()
        repeat(DRAG_HINT_MAX_SHOWS) { shown ->
            assertEquals(shown, annotatorDragHintCount(settings))
            assertTrue(shouldShowDragHint(settings), "show #${shown + 1}")
            recordDragHint(settings)
        }
        assertFalse(shouldShowDragHint(settings))
        assertEquals(DRAG_HINT_MAX_SHOWS.toString(), settings.getString(DRAG_HINT_COUNT_KEY, "missing"))
        recordDragHint(settings)
        assertFalse(shouldShowDragHint(settings))
    }

    @Test
    fun hintCount_should_SurviveANewReaderOverTheSameSettings() {
        val settings = MapSettings()
        recordDragHint(settings)
        recordDragHint(settings)
        assertEquals(2, annotatorDragHintCount(settings))
        assertTrue(shouldShowDragHint(settings))
        settings.putString(DRAG_HINT_COUNT_KEY, "not a number")
        assertEquals(0, annotatorDragHintCount(settings))
    }

    @Test
    fun voiceHint_should_ShowOnTheFirstThreeEditorOpensOnly() {
        val settings = MapSettings()
        val shown = (1..5).map { shouldShowVoiceHint(recordEditorOpen(settings)) }
        assertEquals(listOf(true, true, true, false, false), shown)
        assertEquals(5, annotatorVoiceHintCount(settings))
    }

    @Test
    fun hints_should_BeSilent_When_NoSettingsAreAvailable() {
        assertTrue(shouldShowDragHint(null))
        recordDragHint(null)
        assertEquals(0, annotatorDragHintCount(null))
        assertEquals(1, recordEditorOpen(null))
    }
}
