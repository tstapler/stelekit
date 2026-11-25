// jvmTest-reason: real SQLDelight driver (in-memory SQLite) feed; no compose.ui.test import, runs headless
package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.SyncDecision
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.TableSerializer
import dev.stapler.stelekit.table.TableSheetRealFeedHarness
import dev.stapler.stelekit.table.formula.TableEngine
import dev.stapler.stelekit.ui.components.sheet.BlockStateManagerTablePort
import dev.stapler.stelekit.ui.components.sheet.DraftText
import dev.stapler.stelekit.ui.components.sheet.SheetBodyHooks
import dev.stapler.stelekit.ui.components.sheet.SheetCloseReason
import dev.stapler.stelekit.ui.components.sheet.TableSheetSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 5.1c: the sheet's classification over the real feed (SQLDelight, `version = version + 1`), not a script:
 * a real manager, a host-style collector of the real `getBlockByUuid`, and the real committer. Decisions are
 * recorded in arrival order and the DB version is never an input. Timing is real, so only order-independent
 * properties are asserted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableSheetRealFeedTest {
    private val s0 = "| A | B |\n|---|---|\n| 1 | 2 |"
    private fun at(n: Int): String {
        val doc = (TableParser.parse(s0) as TableParseResult.Parsed).doc
        val out = TableEngine.editor().apply(doc, TableEdit.SetCell(CellAddress(2, 1), CellText("v$n"))) as TableEditOutcome.Applied
        return TableSerializer.serialize(out.doc)
    }
    private val s1 = at(1)
    private val s2 = at(2)

    private val harness = TableSheetRealFeedHarness(s0)
    private val context = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + context)
    private val decisions = mutableListOf<Pair<SyncDecision, String>>()
    private val displays = mutableListOf<String>()
    private val closes = mutableListOf<SheetCloseReason>()
    private lateinit var session: TableSheetSession
    private var draftText: DraftText? = null

    private val hooks = object : SheetBodyHooks {
        override val hasDirtyDraft get() = draftText != null
        override val selectionAnchor: CellAddress? get() = CellAddress(2, 1)
        override fun dirtyDraft() = draftText
        override fun commitPendingDraft() = Unit
        override fun discardDraft() = Unit
        override fun rebuild(content: String, keep: CellAddress?) { displays += content }
        override fun restoreDraft(address: CellAddress, text: String) = Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        harness.close()
    }

    private fun start() = runBlocking {
        harness.start(s0)
        val port = BlockStateManagerTablePort(harness.manager) { id -> harness.blockRepo.getBlockByUuid(id).first().getOrNull()?.content }
        session = TableSheetSession(
            blockUuid = harness.blockUuid, initialContent = s0, port = port, context = context,
            flushScope = scope, flush = {}, onCloseRequest = { closes += it },
        )
        session.bind(hooks)
        session.decisionObserver = { d, c -> synchronized(decisions) { decisions += d to c } }
        scope.launch {
            harness.blockRepo.getBlockByUuid(harness.blockUuid).collect { session.onBlock(it.getOrNull()) }
        }
        harness.settle()
    }

    private fun settle() = runBlocking { harness.settle() }

    private fun onSession(block: () -> Unit) = runBlocking { scope.launch { block() }.join() }

    private fun dbContent() = runBlocking { harness.blockRepo.getBlockByUuid(harness.blockUuid).first().getOrNull()!!.content }

    private fun decisionsFor(vararg contents: String) = synchronized(decisions) { decisions.filter { it.second in contents } }

    private fun assertNoOwnEchoRebuilds() {
        val bad = synchronized(decisions) {
            decisions.filter { (d, _) -> d is SyncDecision.Rebuild || d is SyncDecision.Adopt || d == SyncDecision.ConflictKeepDraft }
        }
        assertTrue(bad.isEmpty(), "own commits must classify as Echo or Stale, got $bad")
    }

    @Test
    fun twoQuickCommits_should_NeverShowS1AfterS2_AndNeverRebuild() {
        start()
        onSession { session.commit(s1, "edit A2"); session.commit(s2, "edit A2") }
        settle()
        assertNoOwnEchoRebuilds()
        assertEquals(s2, session.content)
        assertEquals(s2, dbContent())
        assertEquals(s2, harness.managerContent())
        assertTrue(displays.isEmpty(), "the grid is never told to rebuild: $displays")
        assertFalse(session.conflictHeld)
        assertTrue(closes.isEmpty())
    }

    @Test
    fun twoQuickCommits_should_RaiseNoConflictBanner_When_ADraftIsPending() {
        start()
        draftText = DraftText(CellAddress(3, 2), "typing")
        onSession { session.commit(s1, "edit A2"); session.commit(s2, "edit A2") }
        settle()
        assertFalse(session.conflictHeld, "own echoes are never a conflict")
        assertNoOwnEchoRebuilds()
        assertEquals(s2, dbContent())
        assertEquals(DraftText(CellAddress(3, 2), "typing"), draftText)
    }

    @Test
    fun undoThenRedo_should_EachAdoptWithoutABanner() {
        start()
        onSession { session.commit(s1, "edit A2") }
        settle()
        onSession { session.undo() }
        settle()
        assertFalse(session.conflictHeld)
        assertEquals(s0, dbContent())
        assertEquals(listOf<SyncDecision>(SyncDecision.Adopt(s0)), decisionsFor(s0).map { it.first }.filter { it is SyncDecision.Adopt })
        onSession { session.redo() }
        settle()
        assertFalse(session.conflictHeld)
        assertEquals(s1, dbContent())
        assertTrue(decisionsFor(s1).any { it.first == SyncDecision.Adopt(s1) }, "redo adopts S1: ${decisionsFor(s1)}")
        assertTrue(synchronized(decisions) { decisions.none { it.first is SyncDecision.Rebuild || it.first == SyncDecision.ConflictKeepDraft } })
        assertEquals(s1, session.content)
        assertTrue(closes.isEmpty())
    }

    @Test
    fun undoThenImmediateRecommitOfS1_should_AdoptS1AndEndAtS1() {
        start()
        onSession { session.commit(s1, "edit A2") }
        settle()
        onSession { session.undo(); session.commit(s1, "edit A2") }
        settle()
        // The undo's S0 emission is either seen before the re-commit registers (then S1 is an Echo of the displayed
        // state) or after it (then S0 is adopted and S1 must be adopted back, never an Echo of a stale display).
        // Both orders occur on a real feed; what must hold is that nothing is a Rebuild and the end state is S1.
        val s1Decisions = decisionsFor(s1)
        assertTrue(s1Decisions.isNotEmpty(), "S1 was emitted at least once, so all{} below cannot pass on nothing")
        assertTrue(s1Decisions.all { it.first == SyncDecision.Echo || it.first == SyncDecision.Adopt(s1) }, "decisions for S1: $s1Decisions")
        assertEquals(s1, displays.lastOrNull() ?: session.content, "the grid's last rebuild is S1, never the undo's S0")
        // The feed may conflate the undo's S0 away under load (only the start-up emission is then left), so Adopt is
        // required only when S0 was emitted again after it.
        val adoptedS0 = decisionsFor(s0).size <= 1 || decisionsFor(s0).any { it.first == SyncDecision.Adopt(s0) }
        assertTrue(adoptedS0, "the undo's emission is adopted: ${decisionsFor(s0)}")
        assertTrue(synchronized(decisions) { decisions.none { it.first is SyncDecision.Rebuild || it.first == SyncDecision.ConflictKeepDraft } })
        assertEquals(s1, dbContent())
        assertEquals(s1, session.content)
        assertEquals(s1, harness.managerContent())
        assertFalse(session.conflictHeld)
    }
}
