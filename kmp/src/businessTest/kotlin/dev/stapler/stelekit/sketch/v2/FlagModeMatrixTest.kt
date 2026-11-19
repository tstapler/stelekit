// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.projectbridge.ModeDisabled
import dev.stapler.stelekit.projectbridge.NoGraph
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2ModeFlag
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.ui.sketch.ClassicReason
import dev.stapler.stelekit.ui.sketch.NewSheetKind
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `Settings` that throws when the v2 mode key is read as a Boolean (the key is a string, read with `getString` only). */
private class StringOnlyModeSettings : Settings {
    val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        check(!key.startsWith(ProjectBridgeSettings.V2_MODE_KEY)) { "v2 mode must be read with getString" }
        return store[key]?.toBoolean() ?: defaultValue
    }
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

/** Modes x capabilities x project kinds (Story 1.4.3, Tasks 1.4.3a, 1.4.3d, 1.4.3j). */
class FlagModeMatrixTest {
    private val storages = mapOf(
        "desktop" to V2Storage.Available,
        "android" to V2Storage.Available,
        "wasm-available" to V2Storage.Available,
        "wasm-unavailable" to V2Storage.Browser(StorageReason.NoOpfs),
        "ios" to V2Storage.PlatformUnsupported,
        "no-store" to V2Storage.NoStore,
    )

    private fun caps(ready: Boolean = true, offered: Boolean = true, storage: V2Storage = V2Storage.Available) =
        V2Capabilities(ready, offered, storage)

    @Test
    fun effective_should_CapEveryModeAtOff_When_StorageIsUnavailable() {
        for ((name, storage) in storages) {
            for (mode in V2Mode.entries) {
                val expected = if (storage == V2Storage.Available) mode else V2Mode.Off
                assertEquals(expected, caps(storage = storage).effective(mode), "$name $mode")
            }
        }
    }

    @Test
    fun effective_should_CapEveryModeAtOff_When_ConversionIsNotReady() {
        for (mode in V2Mode.entries) assertEquals(V2Mode.Off, caps(ready = false).effective(mode))
    }

    @Test
    fun effective_should_TreatOnAsNewProjectsOnly_When_ConversionIsNotOffered() {
        assertEquals(V2Mode.NewProjectsOnly, caps(offered = false).effective(V2Mode.On))
        assertEquals(V2Mode.NewProjectsOnly, caps(offered = false).effective(V2Mode.NewProjectsOnly))
        assertEquals(V2Mode.Off, caps(offered = false).effective(V2Mode.Off))
    }

    @Test
    fun disabledReason_should_ReportBuildCapped_ForOnlyTheCappedOptions() {
        assertNull(caps().disabledReason(V2Mode.On))
        assertEquals(ModeDisabled.BuildCapped, caps(offered = false).disabledReason(V2Mode.On))
        assertNull(caps(offered = false).disabledReason(V2Mode.NewProjectsOnly))
        assertEquals(ModeDisabled.BuildCapped, caps(ready = false).disabledReason(V2Mode.NewProjectsOnly))
        assertNull(caps(ready = false).disabledReason(V2Mode.Off))
        assertEquals("Not available in this build yet", SketchReasonText.disabled(ModeDisabled.BuildCapped))
    }

    @Test
    fun disabledReason_should_PutThePlatformBeforeTheBuildCap() {
        val c = caps(ready = false, storage = V2Storage.PlatformUnsupported)
        assertEquals(ModeDisabled.Storage(V2Storage.PlatformUnsupported), c.disabledReason(V2Mode.On))
        assertEquals("The new editor can't save sketches on iOS yet.", SketchReasonText.disabled(c.disabledReason(V2Mode.On)!!))
        assertEquals(ModeDisabled.Checking, caps(storage = V2Storage.Checking).disabledReason(V2Mode.NewProjectsOnly))
        assertNull(caps(storage = V2Storage.Checking).disabledReason(V2Mode.Off))
        assertEquals(
            "The new editor isn't available in the demo graph or while no graph is open.",
            SketchReasonText.v2StorageUnavailable(V2Storage.NoStore),
        )
    }

    @Test
    fun webReasons_should_HaveOneTextPerReason_AndTheIosFamilyWording() {
        val texts = StorageReason.entries.map { SketchReasonText.webStorage(it, iosWebKit = false) }
        assertTrue(texts.all { it.isNotBlank() })
        assertTrue(SketchReasonText.webStorage(StorageReason.NoOpfs, iosWebKit = true).startsWith("On iPhone and iPad"))
    }

    @Test
    fun newSheetKind_should_BeV2OnlyWhereTheModeAndProjectAllowIt() {
        for (mode in V2Mode.entries) {
            for (isNew in listOf(true, false)) {
                val kind = SketchEditorRouter.newSheetKind(RouteInput(mode, caps(), SheetSchema.V1), isNew)
                val v2 = (mode == V2Mode.NewProjectsOnly && isNew) || mode == V2Mode.On
                assertEquals(v2, kind == NewSheetKind.V2, "$mode isNew=$isNew")
            }
        }
    }

    @Test
    fun newSheetKind_should_NameTheReasonOfEachClassicCell() {
        fun reason(input: RouteInput, isNew: Boolean) = (SketchEditorRouter.newSheetKind(input, isNew) as NewSheetKind.Classic).reason
        assertEquals(ClassicReason.ModeOff, reason(RouteInput(V2Mode.Off, caps(), SheetSchema.V1), true))
        assertEquals(ClassicReason.BuildCapped, reason(RouteInput(V2Mode.On, caps(ready = false), SheetSchema.V1), true))
        assertEquals(ClassicReason.ProjectNotNew, reason(RouteInput(V2Mode.NewProjectsOnly, caps(), SheetSchema.V1), false))
        assertEquals(
            ClassicReason.Storage(V2Storage.NoStore),
            reason(RouteInput(V2Mode.On, caps(storage = V2Storage.NoStore), SheetSchema.V1), true),
        )
        val refusal = dev.stapler.stelekit.sketch.v2.runtime.convert.HasTechnicalData
        assertEquals(ClassicReason.TechnicalData, reason(RouteInput(V2Mode.On, caps(), SheetSchema.V1, refusal = refusal), true))
    }

    @Test
    fun v2Mode_should_ReadAsOff_When_MissingBlankOrUnknown_AndNeverReadAsBoolean() {
        val store = StringOnlyModeSettings()
        val settings = ProjectBridgeSettings(store)
        assertEquals(V2Mode.Off, settings.v2Mode("g"))
        store.putString("${ProjectBridgeSettings.V2_MODE_KEY}.g", "")
        assertEquals(V2Mode.Off, settings.v2Mode("g"))
        store.putString("${ProjectBridgeSettings.V2_MODE_KEY}.g", "Everything")
        assertEquals(V2Mode.Off, settings.v2Mode("g"))
        settings.setV2Mode("g", V2Mode.NewProjectsOnly)
        assertEquals(V2Mode.NewProjectsOnly, settings.v2Mode("g"))
        assertFailsWith<IllegalStateException> { store.getBoolean("${ProjectBridgeSettings.V2_MODE_KEY}.g", false) }
    }

    @Test
    fun modeFlag_should_RebindOnGraphSwitch_AndNeverShowTheOtherGraphsMode() = runBlocking<Unit> {
        val settings = ProjectBridgeSettings(StringOnlyModeSettings())
        val ready = MutableStateFlow<String?>("A")
        val flag = V2ModeFlag(settings, ready) { ready.value }
        assertTrue(flag.set(V2Mode.On).isRight())
        assertEquals(V2Mode.On, flag.mode.first())
        ready.value = "B"
        assertEquals(V2Mode.Off, flag.mode.first())
        assertEquals(V2Mode.Off, flag.current())
        flag.set(V2Mode.NewProjectsOnly)
        assertEquals(V2Mode.NewProjectsOnly, settings.v2Mode("B"))
        assertEquals(V2Mode.On, settings.v2Mode("A"))
        ready.value = "A"
        assertEquals(V2Mode.On, flag.mode.first())
    }

    @Test
    fun modeFlag_should_WriteNothing_When_NoGraphIsReady() {
        val store = StringOnlyModeSettings()
        val flag = V2ModeFlag(ProjectBridgeSettings(store), MutableStateFlow(null)) { null }
        assertEquals(Either.Left(NoGraph), flag.set(V2Mode.On))
        assertTrue(store.store.isEmpty())
        assertEquals(V2Mode.Off, flag.current())
    }
}
