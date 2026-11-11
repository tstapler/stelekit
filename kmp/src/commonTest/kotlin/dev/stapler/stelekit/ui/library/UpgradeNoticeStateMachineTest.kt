package dev.stapler.stelekit.ui.library

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpgradeNoticeStateMachineTest {
    private class MapSettings : Settings {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    @Test
    fun freshInstall_showsTipOnly_untilDismissed_neverBadge() {
        val store = LibraryOnboardingStore(MapSettings())
        var s = store.load(graphRegistered = false)
        assertTrue(s.showTip)
        assertFalse(s.showNotice)
        assertFalse(s.showBadge)
        s = s.dismissTip()
        assertFalse(s.showTip)
    }

    @Test
    fun existingInstall_showsNoticeAndBadge_notTip() {
        val s = LibraryOnboardingStore(MapSettings()).load(graphRegistered = true)
        assertTrue(s.showNotice)
        assertTrue(s.showBadge)
        assertFalse(s.showTip)
    }

    @Test
    fun openingLibraryClearsBadge_butNoticePersistsUntilDismissed() {
        var s = LibraryOnboardingStore(MapSettings()).load(graphRegistered = true)
        s = s.onLibraryOpened()
        assertFalse(s.showBadge)
        assertTrue(s.showNotice)
        s = s.dismissNotice()
        assertFalse(s.showNotice)
    }

    @Test
    fun modeIsDecidedOnce_andDismissalsPersistAcrossLoads() {
        val store = LibraryOnboardingStore(MapSettings())
        val first = store.load(graphRegistered = true).dismissNotice().onLibraryOpened()
        store.save(first)
        // A later load with a different graph count must not flip the install's mode.
        val again = store.load(graphRegistered = false)
        assertEquals(LibraryOnboardingMode.UPGRADE, again.mode)
        assertEquals(first, again)
    }

    @Test
    fun aMissingStore_neverBlocks() {
        val s = LibraryOnboardingStore(null).load(graphRegistered = true)
        assertTrue(s.showNotice)
        LibraryOnboardingStore(null).save(s)
    }
}
