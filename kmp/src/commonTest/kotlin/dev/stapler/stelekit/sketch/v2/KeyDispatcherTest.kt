// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.ZOrderMove
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.ui.sketch.v2.CommandLabels
import dev.stapler.stelekit.ui.sketch.v2.EscOrder
import dev.stapler.stelekit.ui.sketch.v2.EscRow
import dev.stapler.stelekit.ui.sketch.v2.KeyAction
import dev.stapler.stelekit.ui.sketch.v2.KeyActionPlanner
import dev.stapler.stelekit.ui.sketch.v2.KeyContext
import dev.stapler.stelekit.ui.sketch.v2.KeyDispatcher
import dev.stapler.stelekit.ui.sketch.v2.KeyOutcome
import dev.stapler.stelekit.ui.sketch.v2.KeyPress
import dev.stapler.stelekit.ui.sketch.v2.KeyboardRegions
import dev.stapler.stelekit.ui.sketch.v2.KeymapEnv
import dev.stapler.stelekit.ui.sketch.v2.PhysicalKey
import dev.stapler.stelekit.ui.sketch.v2.ShortcutsSheetModel
import dev.stapler.stelekit.ui.sketch.v2.SingleKeyHintProducer
import dev.stapler.stelekit.ui.sketch.v2.SketchKeyboard
import dev.stapler.stelekit.ui.sketch.v2.SketchToolId
import dev.stapler.stelekit.ui.sketch.v2.TabStop
import dev.stapler.stelekit.ui.sketch.v2.UndoAnnouncer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.6.1 (Tasks 3.6.1a, 3.6.1b): the key dispatcher, regions, the Esc order, undo announcements and the shortcuts sheet. */
class KeyDispatcherTest {
    private val linux = KeymapEnv(isMac = false, isWeb = false)
    private val mac = KeymapEnv(isMac = true, isWeb = false)
    private val web = KeymapEnv(isMac = false, isWeb = true)

    private fun ctx(env: KeymapEnv = linux, singleKey: Boolean = true, text: Boolean = false, selection: Boolean = false, hand: Boolean = false) =
        KeyContext(env, singleKey, canvasFocused = true, textInputOrDialogFocused = text, hasSelection = selection, handToolActive = hand)

    private fun key(c: String, ctrl: Boolean = false, meta: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        KeyPress(c, ctrl = ctrl, meta = meta, shift = shift, alt = alt)

    private fun handled(press: KeyPress, ctx: KeyContext) = assertIs<KeyOutcome.Handled>(KeyDispatcher.dispatch(press, ctx)).action

    @Test
    fun ctrlZ_should_Undo_And_CmdZOnMacNotCtrlZ() {
        assertEquals(KeyAction.Undo, handled(key("z", ctrl = true), ctx()))
        assertEquals(KeyAction.Undo, handled(key("z", meta = true), ctx(mac)))
        assertEquals(KeyOutcome.Ignored, KeyDispatcher.dispatch(key("z", ctrl = true), ctx(mac)))
        assertEquals(KeyAction.Redo, handled(key("z", ctrl = true, shift = true), ctx()))
    }

    @Test
    fun singleKeyOff_should_ChangeNothing_And_ReportBlocked_But_ChordsStillWork() {
        val off = ctx(singleKey = false)
        assertEquals(KeyOutcome.SingleKeyBlocked("rectangle"), KeyDispatcher.dispatch(key("r"), off))
        assertEquals(KeyOutcome.SingleKeyBlocked("select"), KeyDispatcher.dispatch(key("1"), off))
        assertEquals(KeyAction.Group, handled(key("g", ctrl = true), off))
        assertEquals(KeyAction.Delete, handled(key("Delete"), off))
        assertEquals(KeyAction.SelectTool(SketchToolId.Rectangle), handled(key("r"), ctx()))
    }

    @Test
    fun letters_should_NeverSwitchTools_When_ATextFieldOrDialogHasFocus() {
        for (singleKey in listOf(true, false)) {
            assertEquals(KeyOutcome.Ignored, KeyDispatcher.dispatch(key("r"), ctx(singleKey = singleKey, text = true)))
            assertEquals(KeyOutcome.Ignored, KeyDispatcher.dispatch(key("z", ctrl = true), ctx(singleKey = singleKey, text = true)))
        }
    }

    @Test
    fun allFourteenTools_should_BeReachable() {
        val byLetter = mapOf("v" to SketchToolId.Select, "h" to SketchToolId.Hand, "r" to SketchToolId.Rectangle, "d" to SketchToolId.Diamond, "o" to SketchToolId.Ellipse, "a" to SketchToolId.Arrow, "l" to SketchToolId.Line, "p" to SketchToolId.Freedraw, "t" to SketchToolId.Text, "9" to SketchToolId.Image, "f" to SketchToolId.Frame, "e" to SketchToolId.Eraser, "x" to SketchToolId.Point, "c" to SketchToolId.Arc)
        assertEquals(SketchToolId.entries.toSet(), byLetter.values.toSet())
        for ((k, tool) in byLetter) assertEquals(KeyAction.SelectTool(tool), handled(key(k), ctx()), k)
        assertEquals(KeyAction.ToolLock, handled(key("q"), ctx()))
    }

    @Test
    fun arrows_should_NudgeASelection_And_PanOtherwise() {
        assertEquals(KeyAction.Nudge(-1, 0, far = false), handled(key("ArrowLeft"), ctx(selection = true)))
        assertEquals(KeyAction.Nudge(0, 1, far = true), handled(key("ArrowDown", shift = true), ctx(selection = true)))
        assertEquals(KeyAction.Pan(0, -1, far = false), handled(key("ArrowUp"), ctx(selection = false)))
        assertEquals(KeyAction.Pan(1, 0, far = true), handled(key("ArrowRight", shift = true), ctx(selection = false)))
        assertEquals(KeyAction.Pan(1, 0, far = false), handled(key("ArrowRight"), ctx(selection = true, hand = true)))
        assertEquals(KeyAction.Pan(1, 0, far = false), handled(key("ArrowRight", ctrl = true), ctx(selection = true)))
        assertEquals(KeyAction.PanPage(-1), handled(key("PageUp"), ctx()))
        assertEquals(KeyAction.FitToContent, handled(key("Home"), ctx()))
    }

    @Test
    fun brackets_should_ArrangeAndUseThePhysicalKeyOnLayoutsThatNeedAModifier() {
        assertEquals(KeyAction.Arrange(ZOrderMove.Backward), handled(key("[", ctrl = true), ctx()))
        assertEquals(KeyAction.Arrange(ZOrderMove.Forward), handled(KeyPress("ü", PhysicalKey.BracketRight, ctrl = true), ctx()))
        assertEquals(KeyAction.Arrange(ZOrderMove.ToBack), handled(key("[", ctrl = true, shift = true), ctx()))
    }

    @Test
    fun backspaceAndQuestionMarkAndRegionKeys() {
        assertEquals(KeyAction.Delete, handled(key("Backspace"), ctx()))
        assertEquals(KeyAction.OpenShortcuts, handled(key("?", shift = true), ctx()))
        assertEquals(KeyOutcome.SingleKeyBlocked("shortcutsSheet"), KeyDispatcher.dispatch(key("?", shift = true), ctx(singleKey = false)))
        assertEquals(KeyAction.NextRegion, handled(key("F6"), ctx()))
        assertEquals(KeyAction.PreviousRegion, handled(key("F6", shift = true), ctx()))
        assertEquals(KeyAction.Escape, handled(key("Escape"), ctx()))
        assertEquals(KeyAction.ToggleSnap, handled(key("s", alt = true), ctx()))
        assertEquals(KeyAction.ToggleGrid, handled(key("'", ctrl = true), ctx()))
    }

    @Test
    fun constrainMenu_should_OpenOnKOrCtrlShiftKAndCtrlKOnWeb() {
        assertEquals(KeyAction.OpenConstrainMenu, handled(key("k"), ctx()))
        assertEquals(KeyAction.OpenConstrainMenu, handled(key("k", ctrl = true, shift = true), ctx(singleKey = false)))
        assertEquals(KeyAction.OpenConstrainMenu, handled(key("k", ctrl = true), ctx(web, singleKey = false)))
        assertEquals(KeyOutcome.Ignored, KeyDispatcher.dispatch(key("k", ctrl = true), ctx(linux, singleKey = false)))
    }

    private class Strings : BannerStringSource {
        private val table = ChromeTableParser.parseFixture()
        private fun row(id: BannerRowId) = table.banners.single { it.id == id.name }
        override fun summary(row: BannerRowId) = row(row).summary
        override fun shortTitle(row: BannerRowId) = row(row).shortTitle
        override fun primaryLabel(row: BannerRowId) = row(row).primaryLabel.orEmpty()
        override fun moreItems(row: BannerRowId) = row(row).moreItems
    }

    @Test
    fun firstBlockedSingleKeyOnWeb_should_EmitTheSingleKeyChipOnce_And_NeverTouchABannerSlot() {
        val port = InMemoryDiagnosticsPort(Strings())
        val keyboard = SketchKeyboard(SingleKeyHintProducer(port, isWeb = true))
        assertTrue(port.diagnostics.value.entries.isEmpty())
        keyboard.onKey(key("r"), ctx(web, singleKey = false))
        val chip = port.diagnostics.value.entries.single()
        assertEquals(BannerRowId.SingleKeyChip, chip.row)
        assertEquals("Single keys are off.", chip.summary)
        assertEquals("Single-key tool shortcuts are off.", chip.details)
        port.resolve(BannerRowId.SingleKeyChip)
        keyboard.onKey(key("t"), ctx(web, singleKey = false))
        assertTrue(port.diagnostics.value.entries.isEmpty(), "raised once per session")
    }

    @Test
    fun desktopBlockedKey_should_EmitNoChip() {
        val port = InMemoryDiagnosticsPort(Strings())
        SketchKeyboard(SingleKeyHintProducer(port, isWeb = false)).onKey(key("r"), ctx(linux, singleKey = false))
        assertTrue(port.diagnostics.value.entries.isEmpty())
    }

    @Test
    fun undo_should_Announce_WhatItUndid_And_EmptyHistorySaysSo() {
        val doc = run(SketchDocumentV2(), AddElement(rect("r", 0, 0)))
        val move = MoveElements(listOf(ElementId("r")), 5.0, 0.0)
        var announcer = UndoAnnouncer.Empty
        assertEquals("Nothing to undo", announcer.undone().first)
        announcer = announcer.recorded(CommandLabels.of(move, doc.scene))
        val (said, after) = announcer.undone()
        assertEquals("Undid: move rectangle", said)
        assertEquals("Nothing to undo", after.undone().first)
        assertEquals("Redid: move rectangle", after.redone().first)
        assertEquals("Nothing to redo", after.redone().second.redone().first)
        assertEquals("Nothing to redo", after.recorded("x").redone().first, "a new step drops the redo labels")
    }

    @Test
    fun f6_should_WalkTheRegionsOfTheOneTabOrder_SkippingAbsentOnes() {
        val present = setOf(TabStop.TopBar, TabStop.ToolStrip, TabStop.Canvas, TabStop.ViewCluster, TabStop.StatusLine)
        assertEquals(TabStop.ToolStrip, KeyboardRegions.f6Target(TabStop.TopBar, present, backward = false), "notice region is skipped while empty")
        assertEquals(TabStop.ViewCluster, KeyboardRegions.f6Target(TabStop.Canvas, present, backward = false), "no selection bar")
        assertEquals(TabStop.TopBar, KeyboardRegions.f6Target(TabStop.StatusLine, present, backward = false), "wraps")
        assertEquals(TabStop.StatusLine, KeyboardRegions.f6Target(TabStop.TopBar, present, backward = true))
        val withNotice = present + TabStop.NoticeRegion
        assertEquals(TabStop.NoticeRegion, KeyboardRegions.f6Target(TabStop.TopBar, withNotice, backward = false), "banner and toast actions are reachable")
        assertEquals(TabStop.ViewCluster, KeyboardRegions.f6Target(TabStop.CrosshairPad, present, backward = false))
        assertEquals(TabStop.Canvas, KeyboardRegions.f6Target(TabStop.ActionChipRow, present, backward = true))
    }

    @Test
    fun popover_should_SendF6PastItsAnchor_And_ShiftF6ToTheAnchor() {
        val present = setOf(TabStop.TopBar, TabStop.ToolStrip, TabStop.Canvas, TabStop.ViewCluster)
        assertEquals(TabStop.Canvas, KeyboardRegions.fromPopover(TabStop.ToolStrip, present, backward = false))
        assertEquals(TabStop.ToolStrip, KeyboardRegions.fromPopover(TabStop.ToolStrip, present, backward = true))
    }

    @Test
    fun skipLink_should_GoToTheNextStopAfterTheCanvas() {
        assertEquals(TabStop.ActionChipRow, KeyboardRegions.skipCanvasTarget(setOf(TabStop.ActionChipRow, TabStop.SelectionBar, TabStop.ViewCluster)))
        assertEquals(TabStop.SelectionBar, KeyboardRegions.skipCanvasTarget(setOf(TabStop.SelectionBar, TabStop.ViewCluster)))
        assertEquals(TabStop.ViewCluster, KeyboardRegions.skipCanvasTarget(setOf(TabStop.ViewCluster, TabStop.StatusLine)))
    }

    @Test
    fun escOrder_should_HaveTwelveRowsAndTakeOneRowPerPress() {
        assertEquals(12, EscRow.entries.size)
        assertEquals((1..12).toList(), EscRow.entries.map { it.rank })
        assertEquals(EscRow.entries.toList(), EscOrder.drain(EscRow.entries.toSet()))
        assertEquals(EscRow.MidDraw, EscOrder.resolve(setOf(EscRow.Selection, EscRow.MidDraw, EscRow.NewestToast)))
        assertNull(EscOrder.resolve(emptySet()))
        // A parked picker drops row 5; the other eleven keep their order.
        assertEquals(EscRow.entries.filter { it != EscRow.ArmedPicker }, EscOrder.drain(EscRow.entries.toSet() - EscRow.ArmedPicker))
    }

    @Test
    fun planner_should_SkipLockedElements_And_ReportThem() {
        val doc = run(
            SketchDocumentV2(),
            AddLayer(LayerId("walls"), "Walls"),
            AddElements(listOf(rect("a", 0, 0), rect("b", 20, 0), rect("w", 40, 0, layer = LayerId("walls")))),
            LockElements(listOf(ElementId("b")), true),
            SetLayerLocked(LayerId("walls"), true),
        )
        val plan = KeyActionPlanner.plan(KeyAction.Nudge(1, 0, far = true), doc.scene, listOf(ElementId("a"), ElementId("b"), ElementId("w")), W6Kit.SeqIds())
        assertEquals(MoveElements(listOf(ElementId("a")), 10.0, 0.0), plan.command)
        assertEquals(listOf(ElementId("b"), ElementId("w")), plan.skippedLocked)
        assertNull(KeyActionPlanner.plan(KeyAction.Delete, doc.scene, listOf(ElementId("b")), W6Kit.SeqIds()).command)
    }

    @Test
    fun shortcutsSheet_should_ListAll14Tools_FromTheSameTableTheDispatcherReads() {
        val model = ShortcutsSheetModel.of(linux, singleKeyEnabled = true)
        assertEquals(SketchToolId.entries.map { it.id }.toSet(), model.toolRows.map { it.id }.toSet())
        assertEquals(listOf("Tools", "Edit", "Arrange", "View", "Constraints"), model.sections.map { it.title })
        assertEquals(ShortcutsSheetModel.REGION_NOTE, model.sections.first { it.title == "View" }.notes.single())
        assertTrue(model.footnote!!.contains("not verified against the live Excalidraw dialog or your screen reader"))
        val undoLinux = model.sections.flatMap { it.rows }.first { it.id == "undo" }.keys
        assertEquals(listOf("Ctrl+Z"), undoLinux)
        assertEquals(listOf("Cmd+Z"), ShortcutsSheetModel.of(mac, true).sections.flatMap { it.rows }.first { it.id == "undo" }.keys)
        assertEquals(listOf("Ctrl+Shift+Z", "Ctrl+Y"), model.sections.flatMap { it.rows }.first { it.id == "redo" }.keys)
        assertEquals(false, model.sections.flatMap { it.rows }.first { it.id == "constrain" }.sameAsExcalidraw)
    }
}
