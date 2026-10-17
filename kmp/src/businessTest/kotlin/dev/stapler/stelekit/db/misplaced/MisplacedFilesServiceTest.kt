// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.misplaced

import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.ProposedResolution
import dev.stapler.stelekit.db.StrayResult
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MisplacedFilesServiceTest {
    private val root = RepoRoot("/graphs/g")
    private val notes = EffectiveNotesPath("/graphs/g/logseq")
    private val fs = FakeRelocationFileSystem()
    private val store = object : MisplacedIgnoreStore {
        var saved: Set<String> = emptySet()
        override fun load() = saved
        override fun save(entries: Set<String>) { saved = entries }
    }
    private val repointed = mutableListOf<Map<String, String>>()
    private fun service(ignore: MisplacedIgnoreStore = store) = MisplacedFilesService(fs, ignore, { repointed += it })

    private fun scan(service: MisplacedFilesService = service()) = runBlocking { service.scan(root, notes) }

    @Test
    fun scan_should_ListJournalsAndPagesOutsideTheNotesFolder_WithWhetherNotesAlreadyHasThem() {
        fs.writeFile("/graphs/g/journals/2026_10_16.md", "- phone\n")
        fs.writeFile("/graphs/g/pages/Garage Door.md", "- spring\n")
        fs.writeFile("/graphs/g/pages/readme.txt", "not markdown")
        fs.writeFile("/graphs/g/logseq/journals/2026_10_16.md", "- desktop\n")

        val found = scan()

        assertEquals(listOf("journals/2026_10_16.md", "pages/Garage Door.md"), found.map { it.relativePath })
        assertEquals(listOf(MisplacedFile.Kind.JOURNAL, MisplacedFile.Kind.PAGE), found.map { it.kind })
        assertEquals(listOf(true, false), found.map { it.hasCopyInNotes })
        assertEquals("Garage Door", found.last().name)
    }

    @Test
    fun scan_should_ReturnNothing_When_TheGraphHasNoWikiSubdir() {
        fs.writeFile("/graphs/g/journals/2026_10_16.md", "- x\n")

        assertTrue(runBlocking { service().scan(root, EffectiveNotesPath("/graphs/g")) }.isEmpty())
    }

    @Test
    fun ignore_should_HideTheFileUntilItChanges_AndSurviveANewServiceInstance() {
        fs.writeFile("/graphs/g/journals/2026_10_16.md", "- phone\n")
        runBlocking { service().ignore(scan()) }

        assertTrue(scan(service()).isEmpty(), "an ignored file stays hidden, even for a fresh service reading the same store")

        fs.writeFile("/graphs/g/journals/2026_10_16.md", "- phone\n- and more written later\n")
        assertEquals(1, scan().size, "a changed file asks again")
    }

    @Test
    fun preview_should_SayWhatMovingWouldDo(): Unit = runBlocking {
        fs.writeFile("/graphs/g/journals/only-here.md", "- a\n")
        fs.writeFile("/graphs/g/journals/same.md", "- same\n")
        fs.writeFile("/graphs/g/logseq/journals/same.md", "- same\n")
        fs.writeFile("/graphs/g/journals/both.md", "- phone\n")
        fs.writeFile("/graphs/g/logseq/journals/both.md", "- desktop\n")
        fs.writeFile("/graphs/g/journals/clash.md", "title:: a\n- x\n")
        fs.writeFile("/graphs/g/logseq/journals/clash.md", "- y\n")
        val byName = scan().associateBy { it.name }
        val svc = service()

        assertEquals(ProposedResolution.MoveAsNew, svc.preview(root, notes, byName.getValue("only-here")))
        assertEquals(ProposedResolution.IdenticalCopy, svc.preview(root, notes, byName.getValue("same")))
        assertEquals(ProposedResolution.MergeIntoExisting(1), svc.preview(root, notes, byName.getValue("both")))
        assertIs<ProposedResolution.CannotMerge>(svc.preview(root, notes, byName.getValue("clash")))
    }

    @Test
    fun moveAll_should_MoveAndMerge_RepointOnlyWhatMoved_AndNotStopOnAFailure(): Unit = runBlocking {
        fs.writeFile("/graphs/g/journals/new.md", "- brand new\n")
        fs.writeFile("/graphs/g/journals/both.md", "- phone\n")
        fs.writeFile("/graphs/g/logseq/journals/both.md", "- desktop\n")
        fs.writeFile("/graphs/g/journals/clash.md", "title:: a\n- x\n")
        fs.writeFile("/graphs/g/logseq/journals/clash.md", "- y\n")

        val results = service().moveAll(root, notes, scan()).associate { (file, result) -> file.name to result }

        assertEquals(StrayResult.How.MOVED, assertIs<StrayResult.Done>(results.getValue("new")).how)
        val merged = assertIs<StrayResult.Done>(results.getValue("both"))
        assertEquals(StrayResult.How.MERGED, merged.how)
        assertEquals(1, merged.blocksAdded)
        assertIs<StrayResult.Refused>(results.getValue("clash"))
        assertTrue(fs.readFile("/graphs/g/logseq/journals/both.md")!!.let { "- desktop" in it && "- phone" in it })
        assertFalse(fs.fileExists("/graphs/g/journals/both.md"))
        assertTrue(fs.fileExists("/graphs/g/journals/clash.md"), "a refused merge leaves the stray copy where it was")
        assertEquals(
            mapOf(
                "/graphs/g/journals/new.md" to "/graphs/g/logseq/journals/new.md",
                "/graphs/g/journals/both.md" to "/graphs/g/logseq/journals/both.md",
            ),
            repointed.single(),
        )
    }

    @Test
    fun moveAll_should_MoveTheAssetsAPageLinksTo(): Unit = runBlocking {
        fs.writeFile("/graphs/g/journals/photo.md", "- ![x](../assets/a.png)\n")
        fs.writeFileBytes("/graphs/g/assets/a.png", byteArrayOf(1, 2))

        val result = service().moveAll(root, notes, scan()).single().second

        assertEquals(1, assertIs<StrayResult.Done>(result).assetsMoved)
        assertTrue(fs.fileExists("/graphs/g/logseq/assets/a.png"))
    }
}
