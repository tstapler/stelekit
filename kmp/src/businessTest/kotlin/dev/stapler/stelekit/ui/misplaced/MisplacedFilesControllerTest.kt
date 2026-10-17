// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.misplaced

import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.ProposedResolution
import dev.stapler.stelekit.db.misplaced.MisplacedFilesService
import dev.stapler.stelekit.db.misplaced.MisplacedIgnoreStore
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MisplacedFilesControllerTest {
    private val fs = FakeRelocationFileSystem()
    private val store = object : MisplacedIgnoreStore {
        var saved: Set<String> = emptySet()
        override fun load() = saved
        override fun save(entries: Set<String>) { saved = entries }
    }
    private var reloads = 0

    private fun controller() = MisplacedFilesController(
        service = MisplacedFilesService(fs, store),
        repoRoot = { RepoRoot("/graphs/g") },
        notesPath = { EffectiveNotesPath("/graphs/g/logseq") },
        onFilesChanged = { reloads++ },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun await(what: String, condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }.also { assertTrue(condition(), what) }

    @Test
    fun refresh_should_TrackWhatNeedsAttention_AndStayEmptyWhenThereIsNothing() {
        val c = controller()
        c.refresh()
        Thread.sleep(100)
        assertTrue(c.state.value.items.isEmpty())

        fs.writeFile("/graphs/g/journals/2026_10_16.md", "- phone\n")
        c.refresh()
        await("the stray journal is tracked") { c.state.value.items.size == 1 }
        assertEquals(false, c.state.value.dialogVisible, "tracking alone never opens anything")
        assertTrue(fs.fileExists("/graphs/g/journals/2026_10_16.md"), "and never moves anything")
        c.close()
    }

    @Test
    fun openDialog_should_WorkOutWhatEachMoveWouldDo() {
        fs.writeFile("/graphs/g/journals/both.md", "- phone\n")
        fs.writeFile("/graphs/g/logseq/journals/both.md", "- desktop\n")
        val c = controller()
        c.refresh()
        await("tracked") { c.state.value.items.isNotEmpty() }

        c.openDialog()

        await("previews filled in") { c.state.value.items.all { it.proposal != null } }
        assertTrue(c.state.value.dialogVisible)
        assertEquals(ProposedResolution.MergeIntoExisting(1), c.state.value.items.single().proposal)
        c.close()
    }

    @Test
    fun moveAll_should_MergeReportWhatHappenedReloadAndClearTheList() {
        fs.writeFile("/graphs/g/journals/both.md", "- phone\n")
        fs.writeFile("/graphs/g/logseq/journals/both.md", "- desktop\n")
        fs.writeFile("/graphs/g/journals/new.md", "- brand new\n")
        val c = controller()
        c.refresh()
        await("tracked") { c.state.value.items.size == 2 }
        c.openDialog()
        await("previews") { c.state.value.items.all { it.proposal != null } }

        c.moveAll()

        await("done") { c.state.value.summary != null && !c.state.value.busy && c.state.value.items.isEmpty() }
        val summary = assertNotNull(c.state.value.summary)
        assertTrue("Merged 1 page (1 block added)" in summary && "Moved 1 page" in summary, summary)
        assertTrue("backed up" in summary, "a merge must say where the originals went: $summary")
        assertEquals(1, reloads)
        c.close()
    }

    @Test
    fun moveAll_should_DoNothing_When_NothingCanBeMovedSafely() {
        fs.writeFile("/graphs/g/journals/clash.md", "title:: a\n- x\n")
        fs.writeFile("/graphs/g/logseq/journals/clash.md", "- y\n")
        val c = controller()
        c.refresh()
        await("tracked") { c.state.value.items.isNotEmpty() }
        c.openDialog()
        await("previews") { c.state.value.items.all { it.proposal != null } }
        assertFalse(c.state.value.items.single().movable)

        c.moveAll()
        Thread.sleep(100)

        assertEquals(0, reloads)
        assertTrue(fs.fileExists("/graphs/g/journals/clash.md"))
        c.close()
    }

    @Test
    fun ignoreAll_should_StopTrackingThem_AndKeepFilesWhereTheyAre() {
        fs.writeFile("/graphs/g/journals/2026_10_16.md", "- phone\n")
        val c = controller()
        c.refresh()
        await("tracked") { c.state.value.items.isNotEmpty() }

        c.ignoreAll()

        await("no longer tracked") { c.state.value.items.isEmpty() }
        assertTrue(fs.fileExists("/graphs/g/journals/2026_10_16.md"))
        c.close()
    }
}
