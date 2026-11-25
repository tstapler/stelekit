package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TablePreferencesTest {
    private class MapSettings : Settings {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    @Test
    fun defaultsWrapNumericRightAlignAndComfortable() {
        val prefs = MapSettings().tableDisplayPrefs()
        assertEquals(TableOverflow.WRAP, prefs.overflow)
        assertTrue(prefs.numericRightAlign)
        assertEquals(TableDensity.COMFORTABLE, prefs.density)
    }

    @Test
    fun updatePersistsAndNextSessionReadsItBack() {
        val raw = MapSettings()
        TablePreferences(raw).update { it.copy(overflow = TableOverflow.SCROLL, numericRightAlign = false, density = TableDensity.COMPACT) }
        val next = TablePreferences(raw).prefs
        assertEquals(TableDisplayPrefs(TableOverflow.SCROLL, numericRightAlign = false, density = TableDensity.COMPACT), next)
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        val raw = MapSettings().apply {
            putString(TABLE_OVERFLOW_KEY, "bogus")
            putString(TABLE_DENSITY_KEY, "")
        }
        assertEquals(TableDisplayPrefs(), raw.tableDisplayPrefs())
    }

    @Test
    fun nullSettingsGiveDefaultsAndStillHoldInMemoryChanges() {
        val p = TablePreferences(null)
        p.update { it.copy(density = TableDensity.COMPACT) }
        assertEquals(TableDensity.COMPACT, p.prefs.density)
    }

    @Test
    fun editFlagsDefaultOffAndPersist() {
        val raw = MapSettings()
        val defaults = raw.tableDisplayPrefs()
        assertEquals(false, defaults.inlineEditEnabled)
        assertEquals(false, defaults.sheetEnabled)
        TablePreferences(raw).update { it.copy(inlineEditEnabled = true) }
        val next = raw.tableDisplayPrefs()
        assertTrue(next.inlineEditEnabled)
        assertEquals(false, next.sheetEnabled)
        TablePreferences(raw).update { it.copy(sheetEnabled = true) }
        assertTrue(raw.tableDisplayPrefs().sheetEnabled)
    }
}
