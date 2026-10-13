package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class LfsModeResolverTest {
    private class MapSettings : Settings {
        val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val jvm = LfsPlatformCapabilities(transferSupported = true, androidLfsEnabled = false)
    private val web = LfsPlatformCapabilities.GUARD_ONLY_PLATFORM

    @Test
    fun effective_should_ReturnGuardOnly_When_PlatformCannotTransfer() {
        assertEquals(LfsMode.GUARD_ONLY, LfsModeResolver.effective(LfsMode.AUTO, web))
    }

    @Test
    fun effective_should_ReturnGuardOnly_When_AndroidFlagIsOff() {
        assertEquals(LfsMode.GUARD_ONLY, LfsModeResolver.effective(LfsMode.AUTO, jvm, isAndroid = true))
        assertEquals(LfsMode.AUTO, LfsModeResolver.effective(LfsMode.AUTO, jvm.copy(androidLfsEnabled = true), isAndroid = true))
    }

    @Test
    fun effective_should_ReturnAuto_When_JvmSupportsTransfer() {
        assertEquals(LfsMode.AUTO, LfsModeResolver.effective(LfsMode.AUTO, jvm))
    }

    @Test
    fun effective_should_NeverUpgradeAStoredMode() {
        for (capabilities in listOf(jvm, web)) {
            assertEquals(LfsMode.GUARD_ONLY, LfsModeResolver.effective(LfsMode.GUARD_ONLY, capabilities))
            assertEquals(LfsMode.OFF, LfsModeResolver.effective(LfsMode.OFF, capabilities))
        }
    }

    @Test
    fun modeSource_should_DefaultToGuardOnly_When_NothingIsStored() {
        assertEquals(LfsMode.GUARD_ONLY, LfsModeSource(MapSettings()).get("g1"))
        assertEquals(LfsMode.GUARD_ONLY, LfsModeSource(null).get("g1"))
    }

    @Test
    fun modeSource_should_RoundTripPerGraph_When_ModesAreStored() {
        val settings = MapSettings()
        val source = LfsModeSource(settings)

        source.set("g1", LfsMode.AUTO)
        source.set("g2", LfsMode.OFF)

        assertEquals(LfsMode.AUTO, source.get("g1"))
        assertEquals(LfsMode.OFF, source.get("g2"))
        assertEquals("AUTO", settings.store["git_lfs_mode_g1"])
    }

    @Test
    fun modeSource_should_FallBackToDefault_When_StoredValueIsUnknown() {
        val settings = MapSettings().apply { store["git_lfs_mode_g1"] = "TURBO" }

        assertEquals(LfsMode.GUARD_ONLY, LfsModeSource(settings).get("g1"))
    }
}
