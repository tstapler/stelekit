// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.6.1 (Task 3.6.1d; UX-105, UX-108, UX-111; WCAG 2.1.1, 2.1.4): the tool scheme, keyboard panning, Constrain mnemonics and the web hint chip. */
class KeyboardPanningTest {
    private val linux = KeymapEnv(isMac = false, isWeb = false)
    private val web = KeymapEnv(isMac = false, isWeb = true)
    private val canvas = CanvasSize(widthPx = CANVAS_W, heightPx = CANVAS_H, pxPerDp = 2.0)

    private fun ctx(env: KeymapEnv = linux, selection: Boolean = false, hand: Boolean = false, singleKey: Boolean = true) =
        KeyContext(env, singleKey, canvasFocused = true, textInputOrDialogFocused = false, hasSelection = selection, handToolActive = hand)

    private fun action(press: KeyPress, ctx: KeyContext) = assertIs<KeyOutcome.Handled>(KeyDispatcher.dispatch(press, ctx)).action

    @Test
    fun arrow_should_Pan40dp_AndShiftArrow200dp_WithNothingSelected() {
        val near = KeyboardPanning.apply(action(KeyPress("ArrowRight"), ctx()), SketchViewport.of(), canvas) { SketchViewport.of() }
        assertEquals(-KeyboardPanning.STEP_DP * canvas.pxPerDp, near!!.panX, 1e-9, "the view moves right, so the content moves left")
        assertEquals(0.0, near.panY, 1e-9)
        val far = KeyboardPanning.apply(action(KeyPress("ArrowUp", shift = true), ctx()), SketchViewport.of(), canvas) { SketchViewport.of() }
        assertEquals(KeyboardPanning.FAR_STEP_DP * canvas.pxPerDp, far!!.panY, 1e-9)
    }

    @Test
    fun arrow_should_Pan_WithTheHandTool_And_CtrlArrowAlways_ButNudgeASelection() {
        assertIs<KeyAction.Pan>(action(KeyPress("ArrowLeft"), ctx(hand = true, selection = true)))
        assertIs<KeyAction.Pan>(action(KeyPress("ArrowLeft", ctrl = true), ctx(selection = true)))
        assertIs<KeyAction.Nudge>(action(KeyPress("ArrowLeft"), ctx(selection = true)))
        assertIs<KeyAction.Nudge>(action(KeyPress("ArrowLeft", shift = true), ctx(selection = true)))
        val mac = KeymapEnv(isMac = true, isWeb = false)
        assertIs<KeyAction.Pan>(action(KeyPress("ArrowLeft", meta = true), ctx(mac, selection = true)), "Cmd pans on macOS")
    }

    @Test
    fun pageKeys_should_PanOneScreenHeight_AndHomeFitsToContent() {
        val fitted = SketchViewport.of(zoom = 2.0, panX = 5.0, panY = 6.0)
        val down = KeyboardPanning.apply(action(KeyPress("PageDown"), ctx()), SketchViewport.of(), canvas) { fitted }
        assertEquals(-CANVAS_H, down!!.panY, 1e-9)
        val up = KeyboardPanning.apply(action(KeyPress("PageUp"), ctx()), SketchViewport.of(), canvas) { fitted }
        assertEquals(CANVAS_H, up!!.panY, 1e-9)
        assertEquals(fitted, KeyboardPanning.apply(action(KeyPress("Home"), ctx()), SketchViewport.of(), canvas) { fitted })
        assertNull(KeyboardPanning.apply(KeyAction.Undo, SketchViewport.of(), canvas) { fitted }, "not a panning action")
    }

    @Test
    fun announcement_should_BeTheSettledZoomAndCentre_OncePerBurst() {
        val moved = SketchViewport.of(panX = CANVAS_W / 2.0 - CENTRE_X, panY = CANVAS_H / 2.0 - CENTRE_Y)
        assertEquals("Zoom 100%, centre ${CENTRE_X.toInt()}, ${CENTRE_Y.toInt()}", KeyboardPanning.settledAnnouncement(moved, canvas))

        val queue = SketchNoticeQueue()
        repeat(BURST) { queue.announceStatus(KeyboardPanning.settledAnnouncement(moved.panBy(it.toDouble(), 0.0), canvas), KeyboardPanning.STREAM_KEY) }
        val first = queue.nextAnnouncement(0L)
        assertNotNull(first)
        assertEquals(false, first.assertive)
        assertNull(queue.nextAnnouncement(NoticeTiming.POLITE_INTERVAL_MS), "the burst said one thing: the last state")
    }

    @Test
    fun constrainMnemonics_should_PickWhileTheMenuIsOpen_And_NeverTheCanvasShortcuts() {
        val letters = ConstrainMenuItem.entries.map { it.mnemonic }
        assertEquals(letters.size, letters.toSet().size, "no two entries share a mnemonic")
        assertEquals("CHVLPETSFDARX", letters.joinToString(""))
        val all = ConstrainMenuItem.entries.toSet()
        assertEquals(ConstrainMenuItem.Coincident, ConstrainMnemonics.pick("c", all))
        assertEquals(ConstrainMenuItem.Parallel, ConstrainMnemonics.pick("L", all))
        assertEquals(ConstrainMenuItem.RadiusOrDiameter, ConstrainMnemonics.pick("r", all))
        assertEquals(ConstrainMenuItem.ReferenceToggle, ConstrainMnemonics.pick("x", all))
        assertNull(ConstrainMnemonics.pick("z", all))
        assertNull(ConstrainMnemonics.pick("c", all - ConstrainMenuItem.Coincident), "a disabled item is not picked")
        assertEquals(ConstrainMenuItem.Horizontal, ConstrainMnemonics.firstFocus(all - ConstrainMenuItem.Coincident), "focus lands on the first enabled item")
        assertNull(ConstrainMnemonics.firstFocus(emptySet()))
        // On the canvas the same letters stay tool shortcuts: the menu's own handler is the only reader of the mnemonics.
        assertEquals(KeyAction.SelectTool(SketchToolId.Arc), action(KeyPress("c"), ctx()))
        assertEquals(KeyAction.SelectTool(SketchToolId.Hand), action(KeyPress("h"), ctx()))
    }

    @Test
    fun constrainKey_should_OpenTheMenu_OnKOrCtrlShiftKAndCtrlKOnWeb() {
        assertEquals(KeyAction.OpenConstrainMenu, action(KeyPress("k"), ctx()))
        assertEquals(KeyAction.OpenConstrainMenu, action(KeyPress("k", ctrl = true, shift = true), ctx(singleKey = false)))
        assertEquals(KeyAction.OpenConstrainMenu, action(KeyPress("k", ctrl = true), ctx(web, singleKey = false)))
    }

    @Test
    fun shortcutsSheet_should_ListPanKeysConstrainMnemonicsAndTheCollisionNote() {
        val model = ShortcutsSheetModel.of(linux, singleKeyEnabled = true)
        val rows = model.sections.flatMap { it.rows }.associateBy { it.id }
        for (id in listOf("pan", "panFar", "panAlways", "panPage", "panHome")) assertNotNull(rows[id], "the sheet lists $id")
        for (item in ConstrainMenuItem.entries) {
            assertEquals(listOf(item.mnemonic.toString()), rows.getValue("${ShortcutsSheetModel.MNEMONIC_ID_PREFIX}${item.name}").keys)
        }
        assertTrue(ShortcutsSheetModel.COLLISION_NOTE in model.sections.flatMap { it.notes })
        for (letter in listOf("H", "F", "E", "T", "L", "D")) assertTrue(letter in ShortcutsSheetModel.COLLISION_NOTE, "names $letter")
    }

    @Test
    fun singleKeyOffOnWeb_should_RaiseTheChipOnce_And_TurnOnStoresTheSettingAndSettlesIt() {
        val raw = InMemorySettings()
        val port = InMemoryDiagnosticsPort(ChromeBannerStrings(ChromeTableParser.parseFixture()))
        val producer = SingleKeyHintProducer(port, isWeb = true)
        val keyboard = SketchKeyboard(producer)
        val off = SingleKeyShortcuts.read(raw, SingleKeyShortcuts.defaultFor(SingleKeyTarget.Web, hardwareKeyboard = true, touchExploration = false))
        assertEquals(false, off, "web defaults to Off")
        val blocked = keyboard.onKey(KeyPress("r"), ctx(web, singleKey = off))
        assertIs<KeyOutcome.SingleKeyBlocked>(blocked)
        assertEquals(BannerRowId.SingleKeyChip, port.diagnostics.value.entries.single().row)

        producer.turnOn(raw)
        assertTrue(SingleKeyShortcuts.read(raw, default = false))
        assertTrue(port.diagnostics.value.entries.isEmpty())
        assertEquals(KeyAction.SelectTool(SketchToolId.Rectangle), action(KeyPress("r"), ctx(web, singleKey = true)))
    }

    private companion object {
        const val CANVAS_W = 800.0
        const val CANVAS_H = 600.0
        const val CENTRE_X = 120.0
        const val CENTRE_Y = 80.0
        const val BURST = 50
    }
}
