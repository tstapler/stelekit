// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.ui.components.sheet.LocalPasteEventSource
import dev.stapler.stelekit.ui.components.sheet.PasteEventSource
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 7.1b on the common side: a DOM paste event reaches the sheet through [PasteEventSource], is applied once even
 * when Ctrl+V also reads the clipboard port, and is left to the text field while a cell is being edited.
 * The browser half (does Ctrl+V on the canvas fire `paste`) is spike 0.3a/b and stays UNVERIFIED.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetPasteEventTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var fixture: SheetFixture? = null
    private val source = FakePasteSource()

    private class FakePasteSource : PasteEventSource {
        var handler: ((String) -> Boolean)? = null
        var registers = 0
        var releases = 0
        override fun register(onPaste: (String) -> Boolean): () -> Unit {
            registers++
            handler = onPaste
            return {
                releases++
                handler = null
            }
        }
    }

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(): SheetFixture =
        SheetFixture(rule, gridContent(rows = 4, cols = 4)).also {
            fixture = it
            it.show(realCommands = true, wrap = { inner -> CompositionLocalProvider(LocalPasteEventSource provides source) { inner() } })
        }

    private fun deliver(text: String): Boolean {
        var taken = false
        rule.runOnUiThread { taken = source.handler!!(text) }
        rule.waitForIdle()
        return taken
    }

    private fun select(f: SheetFixture, a: CellAddress) {
        rule.runOnUiThread { f.s.selectRange(GridSelection(a, a)) }
        rule.waitForIdle()
    }

    @Test
    fun eventPaste_should_ApplyTheTextOnce_AndSayItTookIt() {
        val f = open()
        select(f, CellAddress(2, 1))
        val before = f.s.announcementSeq
        assertTrue(deliver("1\t2\n3\t4"))
        assertEquals(listOf("1", "2", "3", "4"), listOf(f.s.rawText(CellAddress(2, 1)), f.s.rawText(CellAddress(2, 2)), f.s.rawText(CellAddress(3, 1)), f.s.rawText(CellAddress(3, 2))))
        assertEquals(before + 1, f.s.announcementSeq, "one paste, one announcement")
    }

    @Test
    fun sheetOpening_should_RegisterOnceAndClosingShouldRelease() {
        open()
        assertEquals(1, source.registers)
        assertNotNull(source.handler)
        fixture!!.h.open = false
        rule.waitForIdle()
        assertEquals(1, source.releases)
        assertNull(source.handler)
    }

    @Test
    fun eventPaste_should_BeLeftToTheTextField_While_ACellIsBeingEdited() {
        val f = open()
        rule.runOnUiThread { f.s.startEdit(CellAddress(2, 1)) }
        rule.waitForIdle()
        assertFalse(deliver("9\t9"), "the browser's paste belongs to the edit field")
        assertEquals("2-1", f.s.rawText(CellAddress(2, 1)))
    }

    @Test
    fun eventPaste_should_BeLeftToThePasteBox_While_ItIsOpen() {
        var now = 0L
        val f = open { now }
        f.clipboard.failPaste = true
        select(f, CellAddress(2, 1))
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        assertTrue(rule.exists("pasteHereDialog"), "the Paste here box is open")
        now = 10_000 // the keystroke is long past: this paste is the user's, into the box
        assertFalse(deliver("9\t9"))
        assertEquals("2-1", f.s.rawText(CellAddress(2, 1)))
    }

    @Test
    fun ctrlV_should_ApplyOnce_When_BothThePortAndTheDomEventDeliverTheSameText() {
        val f = open()
        f.clipboard.text = "7\t8"
        select(f, CellAddress(2, 1))
        val before = f.s.pasteApplications
        assertTrue(deliver("7\t8"))
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("7", "8"), listOf(f.s.rawText(CellAddress(2, 1)), f.s.rawText(CellAddress(2, 2))))
        assertEquals(before + 1, f.s.pasteApplications, "the second route was recognised as the same paste")
    }

    @Test
    fun ctrlVTwice_should_StillApplyTwice_When_OnlyThePortDelivers() {
        val f = open()
        f.clipboard.text = "7\t8"
        select(f, CellAddress(2, 1))
        val before = f.s.pasteApplications
        listOf(CellAddress(2, 1), CellAddress(4, 1)).forEach { at ->
            select(f, at)
            rule.onNodeWithTag("sheetGridFocus").performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.V)
                keyUp(Key.CtrlLeft)
            }
            rule.waitForIdle()
        }
        assertEquals(before + 2, f.s.pasteApplications, "two deliberate pastes of the same text to two places are two pastes")
    }

    @Test
    fun eventArrivingFirst_should_StopThePortReadFailureFromOpeningTheFallbackBox() {
        val f = open()
        f.clipboard.failPaste = true
        select(f, CellAddress(2, 1))
        assertTrue(deliver("5\t6"))
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        assertFalse(rule.exists("pasteHereDialog"), "no fallback box: the event already delivered")
    }

    private fun open(nowMs: () -> Long): SheetFixture =
        SheetFixture(rule, gridContent(rows = 4, cols = 4)).also {
            fixture = it
            it.show(realCommands = true, nowMs = nowMs, wrap = { inner -> CompositionLocalProvider(LocalPasteEventSource provides source) { inner() } })
        }

    private fun ctrlV() {
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    @Test
    fun aSlowPortRead_should_NotApplyASecondTime_When_TheEventAlreadyDeliveredThatPaste() {
        var now = 0L
        val f = open { now }
        f.clipboard.text = "7\t8"
        f.clipboard.pasteGate = kotlinx.coroutines.CompletableDeferred()
        select(f, CellAddress(2, 1))
        val before = f.s.pasteApplications
        ctrlV() // asked at t=0, the port is still thinking
        now = 100
        assertTrue(deliver("7\t8"))
        now = 5_000 // far past the window by the time the port answers
        f.clipboard.pasteGate!!.complete(Unit)
        rule.waitForIdle()
        assertEquals(before + 1, f.s.pasteApplications, "the port's late answer is the same keystroke's paste")
    }

    @Test
    fun theEvent_should_StillBeTaken_When_ThePortFailedFirstAndOpenedTheBox() {
        var now = 0L
        val f = open { now }
        f.clipboard.failPaste = true
        select(f, CellAddress(2, 1))
        ctrlV()
        assertTrue(rule.exists("pasteHereDialog"), "the failed read opened the box")
        now = 50
        assertTrue(deliver("5\t6"), "the DOM event of the same Ctrl+V is not lost to the box")
        assertEquals("5", f.s.rawText(CellAddress(2, 1)))
        assertFalse(rule.exists("pasteHereDialog"))
    }

    @Test
    fun anOpenBox_should_KeepOwningLaterPastes_OnceTheKeystrokeIsLongPast() {
        var now = 0L
        val f = open { now }
        f.clipboard.failPaste = true
        select(f, CellAddress(2, 1))
        ctrlV()
        now = 10_000
        assertFalse(deliver("5\t6"))
        assertEquals("2-1", f.s.rawText(CellAddress(2, 1)))
    }

    @Test
    fun eventPaste_should_BeLeftToTheShowAsTextPanel_While_ItIsOpen() {
        val f = open()
        f.clipboard.failCopy = true
        select(f, CellAddress(2, 1))
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.C)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("noticeAction").performClick()
        rule.waitForIdle()
        assertTrue(rule.exists("showAsTextDialog"))
        assertFalse(deliver("9\t9"))
        assertEquals("2-1", f.s.rawText(CellAddress(2, 1)))
    }
}
