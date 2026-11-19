package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.sketch.v2.GateItem
import dev.stapler.stelekit.ui.sketch.v2.GateStatus
import dev.stapler.stelekit.ui.sketch.v2.KeyPress
import dev.stapler.stelekit.ui.sketch.v2.KeyboardLayoutInfo
import dev.stapler.stelekit.ui.sketch.v2.KeymapEnv
import dev.stapler.stelekit.ui.sketch.v2.KeymapGate
import dev.stapler.stelekit.ui.sketch.v2.KeymapGroup
import dev.stapler.stelekit.ui.sketch.v2.KeymapSpec
import dev.stapler.stelekit.ui.sketch.v2.Modifier
import dev.stapler.stelekit.ui.sketch.v2.PhysicalKey
import dev.stapler.stelekit.ui.sketch.v2.SingleKeyShortcuts
import dev.stapler.stelekit.ui.sketch.v2.SingleKeyTarget
import dev.stapler.stelekit.ui.sketch.v2.SketchToolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 3.6.1c: the keymap table and its layout, macOS and single-key decisions (UX-85, UX-100, UX-108, UX-111).
 * Tool ids are cross-checked against the generated chrome-table fixture, not retyped here.
 */
class KeymapSpecTest {
    private val linux = KeymapEnv(isMac = false, isWeb = false)
    private val mac = KeymapEnv(isMac = true, isWeb = false)
    private val web = KeymapEnv(isMac = false, isWeb = true)

    private fun ids(press: KeyPress, env: KeymapEnv = linux) = KeymapSpec.entriesFor(press, env).map { it.id }

    @Test
    fun keymap_should_BindAll14ToolsUniquely_And_ShortcutsSheetListAll14() {
        val chromeTools = ChromeTableParser.parseFixture().tools.map { it.id }
        assertEquals(chromeTools.toSet(), SketchToolId.entries.map { it.id }.toSet())
        assertEquals(chromeTools.toSet(), KeymapSpec.tools.map { it.id }.toSet())
        KeymapSpec.tools.forEach { assertTrue(it.bindings.isNotEmpty(), "${it.id} has no binding") }

        val seen = mutableMapOf<String, String>()
        KeymapSpec.tools.forEach { tool ->
            tool.bindings.forEach { b ->
                val clash = seen.put(b.key, tool.id)
                assertNull(clash, "${b.key} is bound to ${tool.id} and $clash")
            }
        }
        assertEquals(chromeTools.size, KeymapSpec.entries.count { it.group == KeymapGroup.Tools && it.id in chromeTools })
    }

    @Test
    fun keymap_should_UseExcalidrawDigitsForTheFirstNineToolsAndEraserZero() {
        val digits = KeymapSpec.tools.flatMap { t -> t.bindings.filter { it.key.single().isDigit() }.map { it.key to t.id } }.toMap()
        assertEquals(
            mapOf(
                "1" to "select", "2" to "rectangle", "3" to "diamond", "4" to "ellipse", "5" to "arrow",
                "6" to "line", "7" to "freedraw", "8" to "text", "9" to "image", "0" to "eraser",
            ),
            digits,
        )
        val withoutDigit = KeymapSpec.tools.filter { t -> t.bindings.none { it.key.single().isDigit() } }
        withoutDigit.forEach { t -> assertTrue(t.bindings.any { it.key.single().isLetter() }, "${t.id} has no letter") }
    }

    @Test
    fun ux85_singleKeys_should_FireOnlyWithCanvasFocusAndSettingOn() {
        assertTrue(SingleKeyShortcuts.isActive(enabled = true, canvasFocused = true, textInputOrDialogFocused = false))
        assertFalse(SingleKeyShortcuts.isActive(enabled = false, canvasFocused = true, textInputOrDialogFocused = false))
        assertFalse(SingleKeyShortcuts.isActive(enabled = true, canvasFocused = false, textInputOrDialogFocused = false))
        assertFalse(SingleKeyShortcuts.isActive(enabled = true, canvasFocused = true, textInputOrDialogFocused = true))
    }

    @Test
    fun singleKeyBindings_should_BeUnmodifiedPrintableAndEveryOtherPrintableBindingCarryAModifier() {
        val all = KeymapSpec.entries.flatMap { it.bindings }
        all.filter { it.singleKey }.forEach {
            assertTrue(it.modifiers.isEmpty() && it.key.length == 1, "${it.key} is not an unmodified character")
        }
        all.filter { it.key.length == 1 && it.modifiers.isEmpty() && it.key != "?" }.forEach {
            assertTrue(it.singleKey, "${it.key} is a bare character binding outside the setting")
        }
    }

    @Test
    fun keymap_should_MatchProducedCharacterOnAzertyAndUseCmdOnMac() {
        // AZERTY: the key that produces "r" is not at the QWERTY R position; the produced character wins.
        assertEquals(listOf("rectangle"), ids(KeyPress(produced = "r", physical = null)))
        // Dvorak: the QWERTY R position produces "p", which is Freedraw, never Rectangle.
        assertEquals(listOf("freedraw"), ids(KeyPress(produced = "p")))

        assertTrue("undo" in ids(KeyPress(produced = "z", ctrl = true)))
        assertFalse("undo" in ids(KeyPress(produced = "z", ctrl = true), mac), "Ctrl+Z must not fire on macOS")
        assertTrue("undo" in ids(KeyPress(produced = "z", meta = true), mac))
        assertFalse("undo" in ids(KeyPress(produced = "z", meta = true)), "Cmd is not a chord modifier off macOS")
        assertTrue("redo" in ids(KeyPress(produced = "z", meta = true, shift = true), mac))
        assertTrue("redo" in ids(KeyPress(produced = "y", ctrl = true)))
        assertFalse("redo" in ids(KeyPress(produced = "y", meta = true), mac))
    }

    @Test
    fun brackets_should_FallBackToThePhysicalKeyAndTheSheetShowTheKeyTheLayoutNeeds() {
        // German-like layout: "[" needs AltGr, so the physical key produces another character.
        val press = KeyPress(produced = "ü", physical = PhysicalKey.BracketLeft, ctrl = true)
        assertEquals(listOf("sendBackward"), ids(press))
        assertEquals(listOf("sendBackward"), ids(KeyPress(produced = "[", ctrl = true)))
        assertEquals(listOf("bringForward"), ids(KeyPress(produced = "]", physical = PhysicalKey.BracketRight, ctrl = true)))

        val german = object : KeyboardLayoutInfo {
            override fun canTypeUnmodified(character: String) = character != "[" && character != "]"
            override fun physicalKeyLabel(key: PhysicalKey) = if (key == PhysicalKey.BracketLeft) "Ü" else "+"
        }
        val sendBackward = KeymapSpec.entries.first { it.id == "sendBackward" }.bindings.single()
        assertEquals("Ctrl+Ü", KeymapSpec.displayLabel(sendBackward, linux, german))
        assertEquals("Cmd+[", KeymapSpec.displayLabel(sendBackward, mac, null))
    }

    @Test
    fun macLabels_should_ShowCmdForEveryPrimaryChord() {
        KeymapSpec.entries.flatMap { it.bindings }.filter { Modifier.Primary in it.modifiers }.forEach {
            val label = KeymapSpec.displayLabel(it, mac)
            assertTrue(label.startsWith("Cmd"), label)
            assertFalse("Ctrl" in label, label)
        }
    }

    @Test
    fun ux108_singleKey_should_DefaultOffOnWebAndUnderTouchExploration() {
        assertTrue(SingleKeyShortcuts.defaultFor(SingleKeyTarget.Desktop, hardwareKeyboard = true, touchExploration = false))
        assertFalse(SingleKeyShortcuts.defaultFor(SingleKeyTarget.Web, hardwareKeyboard = true, touchExploration = false))
        assertTrue(SingleKeyShortcuts.defaultFor(SingleKeyTarget.Android, hardwareKeyboard = true, touchExploration = false))
        assertFalse(SingleKeyShortcuts.defaultFor(SingleKeyTarget.Android, hardwareKeyboard = true, touchExploration = true))
        assertFalse(SingleKeyShortcuts.defaultFor(SingleKeyTarget.Android, hardwareKeyboard = false, touchExploration = false))
        assertFalse(SingleKeyShortcuts.defaultFor(SingleKeyTarget.Ios, hardwareKeyboard = true, touchExploration = true))
    }

    @Test
    fun singleKeySetting_should_PreferTheStoredChoiceOverTheDefault() {
        val settings = MapSettings()
        assertFalse(SingleKeyShortcuts.read(settings, default = false))
        assertTrue(SingleKeyShortcuts.read(settings, default = true))
        SingleKeyShortcuts.write(settings, enabled = false)
        assertFalse(SingleKeyShortcuts.read(settings, default = true), "an explicit false must beat a true default")
        SingleKeyShortcuts.write(settings, enabled = true)
        assertTrue(SingleKeyShortcuts.read(settings, default = false))
    }

    @Test
    fun ux111_constrainKey_should_BeKOrCtrlShiftKAndCtrlKOnWeb() {
        assertEquals(listOf("constrain"), ids(KeyPress(produced = "k")))
        assertEquals(listOf("constrain"), ids(KeyPress(produced = "k", ctrl = true, shift = true)))
        assertTrue("constrain" !in ids(KeyPress(produced = "k", ctrl = true)), "Ctrl+K is the web chord only")
        assertEquals(listOf("constrain"), ids(KeyPress(produced = "k", ctrl = true), web))
        assertTrue("constrain" !in ids(KeyPress(produced = "k", ctrl = true, shift = true), web), "Firefox opens its console")
        assertEquals(listOf("constrain"), ids(KeyPress(produced = "k", meta = true, shift = true), mac))
    }

    @Test
    fun deviation_should_RecordKAsConstrainWhereExcalidrawBindsLaser() {
        val k = KeymapSpec.excalidrawDeviations.single { it.key == "K" }
        assertTrue("laser" in k.excalidraw)
        assertTrue("Constrain" in k.here)
        assertFalse(KeymapSpec.claimsExcalidrawParity("k"))
        assertTrue(KeymapSpec.claimsExcalidrawParity("r"))
    }

    @Test
    fun keymap_should_FreezeOnlyAfterU8U11U12RecordedOrDefaulted_And_CarryFootnoteWhenDefaulted() {
        val gate = KeymapSpec.gate
        assertEquals(setOf("U8", "U11", "U12"), gate.items.map { it.id }.toSet())
        assertTrue(gate.frozen)
        assertEquals("not verified against the live Excalidraw dialog or your screen reader", gate.footnote)

        val allRecorded = KeymapGate(gate.items.map { GateItem(it.id, it.what, GateStatus.Recorded) })
        assertTrue(allRecorded.frozen)
        assertNull(allRecorded.footnote)
    }

    private class MapSettings : Settings {
        private val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }
}
