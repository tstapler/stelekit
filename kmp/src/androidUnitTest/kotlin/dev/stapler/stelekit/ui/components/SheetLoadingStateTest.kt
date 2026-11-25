// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.ui.components.sheet.SheetLoadingState
import dev.stapler.stelekit.ui.components.sheet.TableManagerPort
import dev.stapler.stelekit.ui.components.sheet.parseSheet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 5.9: the open indicator for big tables, and the 300 ms rule for "Saving..." and "Applying...". */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetLoadingStateTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var fixture: SheetFixture? = null
    private val gate = CompletableDeferred<Unit>()

    @After
    fun tearDown() {
        gate.complete(Unit)
        fixture?.close()
    }

    /** Holds every manager write until [gate] completes: a commit "in flight". */
    private class GatedPort(private val inner: TableManagerPort, private val gate: CompletableDeferred<Unit>) : TableManagerPort by inner {
        private val scope = CoroutineScope(Dispatchers.Unconfined)
        override fun write(uuid: BlockUuid, text: String, version: Long): Job = scope.launch {
            gate.await()
            inner.write(uuid, text, version).join()
        }
    }

    @Test
    fun thresholds_should_MatchTheUxNumbers() {
        assertEquals(1_000, SheetLoadingState.SHEET_OPEN_INDICATOR_CELLS)
        assertFalse(SheetLoadingState.showOpeningIndicator(cells = 1_000, elapsedMs = 100))
        assertTrue(SheetLoadingState.showOpeningIndicator(cells = 1_001, elapsedMs = 0))
        assertTrue(SheetLoadingState.showOpeningIndicator(cells = 5, elapsedMs = 101))
        assertFalse(SheetLoadingState.showSaving(299))
        assertTrue(SheetLoadingState.showSaving(300))
        assertEquals(TableMessages.SAVING, SheetLoadingState.statusText(saving = true, applying = true))
        assertEquals(TableMessages.APPLYING, SheetLoadingState.statusText(saving = false, applying = true))
        assertNull(SheetLoadingState.statusText(saving = false, applying = false))
    }

    @Test
    fun estimateCells_should_TrackTheRealCellCount() {
        val content = gridContent(rows = 100, cols = 12)
        val real = 101 * 12
        val estimate = SheetLoadingState.estimateCells(content)
        assertTrue(estimate in real..(real + 12 * 2), "estimate $estimate vs $real")
        assertFalse(SheetLoadingState.parsesOffThread(gridContent(5, 3)))
        assertTrue(SheetLoadingState.parsesOffThread(content))
    }

    @Test
    fun openingTable_should_ShowWithTopBarAndEnabledClose_UntilTheParseFinishes_When_Over1000Cells() {
        val latch = CountDownLatch(1)
        val f = SheetFixture(rule, gridContent(rows = 100, cols = 12)).also { fixture = it }
        f.show(parse = { c -> latch.await(); parseSheet(c) })
        rule.onNodeWithTag("openingTable").assertExists()
        rule.onNode(hasText(TableMessages.OPENING_TABLE)).assertExists()
        rule.onNodeWithTag("sheetTopBar").assertExists()
        rule.onNodeWithTag("sheetClose").assertExists()
        rule.onNodeWithTag("sheetClose").assertIsEnabled()
        assertFalse(rule.exists("sheetGrid"))
        latch.countDown()
        rule.waitUntil(10_000) { rule.exists("sheetGrid") }
        assertFalse(rule.exists("openingTable"))
    }

    @Test
    fun smallTable_should_ComposeNoIndicator() {
        val f = SheetFixture(rule, gridContent(rows = 5, cols = 3)).also { fixture = it }
        f.show()
        assertFalse(rule.exists("openingTable"))
        assertTrue(rule.exists("sheetGrid"))
    }

    @Test
    fun saving_should_ShowOnlyAfter300ms_KeepEditingEnabled_AndClearOnCompletion() {
        val f = SheetFixture(rule, gridContent(rows = 4, cols = 3), wrapPort = { GatedPort(it, gate) }).also { fixture = it }
        f.show()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { f.s.applyEdit(TableEdit.SetCell(CellAddress(2, 2), CellText("7")), "edit B2") }
        rule.mainClock.advanceTimeBy(250)
        assertFalse(rule.exists("busyLine"), "no indicator before 300 ms")
        rule.mainClock.advanceTimeBy(100)
        assertEquals(TableMessages.SAVING, rule.textOf("busyLine"))
        rule.runOnUiThread { f.s.startEdit(CellAddress(3, 1)) }
        rule.mainClock.advanceTimeBy(16)
        assertTrue(rule.exists("barField"), "editing stays enabled while saving")
        gate.complete(Unit)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) { !rule.exists("busyLine") }
    }

    @Test
    fun applying_should_ShowAfter300ms_ForLargePasteOrSort() {
        val f = SheetFixture(rule, gridContent(rows = 4, cols = 3)).also { fixture = it }
        f.show()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { f.s.applying = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(200)
        assertFalse(rule.exists("busyLine"))
        rule.mainClock.advanceTimeBy(150)
        assertEquals(TableMessages.APPLYING, rule.textOf("busyLine"))
        rule.runOnUiThread { f.s.applying = false; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(64)
        assertFalse(rule.exists("busyLine"))
    }
}
