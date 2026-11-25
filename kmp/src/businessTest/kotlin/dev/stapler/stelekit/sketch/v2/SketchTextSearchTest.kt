// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchSearchRow
import dev.stapler.stelekit.repository.SketchSearchTextRepository
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.runtime.persist.SketchTextSearch
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.search.SketchTextExtractor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E8: sketch text reaches global search through the real sheet saver and the real SQLDelight repository. */
class SketchTextSearchTest {
    private fun label(id: String, text: String, name: String? = null) = TextElement(
        ElementBase(ElementId(id), zKey = ZKey("b$id"), name = name),
        SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(40), SceneUnit.ofInt(20), text = text,
    )

    private fun docWith(base: SketchDocumentV2, vararg els: TextElement) =
        els.fold(base) { d, e -> d.copy(scene = d.scene.add(e).getOrNull()!!) }

    @Test
    fun extractor_should_IndexTextNamesAndLengthsBothWays_When_ASheetHasThem() {
        val rect = SaverRig.rect("r1", 0)
        val doc = docWith(SketchDocumentV2(sheetUuid = "s"), label("t1", "Kitchen\nIsland", name = "North wall"))
            .let { it.copy(scene = it.scene.add(rect).getOrNull()!!) }
            .copy(constraints = listOf(Constraint(ConstraintId("d1"), ConstraintKind.Distance, listOf(ConstraintTarget(ElementId("r1"))), value = 576.0)))
        val text = SketchTextExtractor.extract(doc).body
        assertTrue("kitchen island" in text, text)
        assertTrue("north wall" in text, text)
        assertTrue("36\"" in text, "a 36 inch dimension is found by its inch form: $text")
        assertTrue("3' 0\"" in text, "and by its feet-inches form: $text")
    }

    @Test
    fun extractor_should_StayInsideItsBudget_When_ASheetIsVeryWordy() {
        var doc = SketchDocumentV2(sheetUuid = "s")
        repeat(500) { i -> doc = docWith(doc, label("t$i", "word$i ".repeat(10))) }
        val text = SketchTextExtractor.extract(doc)
        assertTrue(text.body.length <= SketchTextExtractor.BODY_BUDGET)
        assertTrue("word0" in text.body)
    }

    @Test
    fun savedSheet_should_BeFoundByTextAndByDimension_When_TheSaverWrites() = runBlocking<Unit> {
        val rig = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        val doc = docWith(rig.loadedDoc(), label("kt", "Kitchen pantry")).copy(
            constraints = listOf(Constraint(ConstraintId("d1"), ConstraintKind.Distance, listOf(ConstraintTarget(ElementId("kt"))), value = 576.0)),
        )
        rig.saver.submit(doc)
        assertEquals(SaveState.Saved, rig.saver.flush())
        val search = SketchTextSearch(rig.device.repos, { rig.device.store }, { it() }, { 1L })
        val byText = search.search("pantry")
        assertEquals(listOf(SketchTestSupport.P.value), byText.map { it.projectUuid })
        assertEquals("kitchen pantry", byText.single().snippet)
        assertEquals(1, search.search("36\"").size)
        assertEquals(1, search.search("PANTRY kitchen").size, "every word must match, in any order and case")
        assertTrue(search.search("pantry bathroom").isEmpty())
        rig.saver.close()
    }

    @Test
    fun backfill_should_IndexSheetsTheSaverNeverSaw_AndSkipUnchangedOnes() = runBlocking<Unit> {
        val device = MixedProjectFixtures.device(convertA = true)
        val search = SketchTextSearch(device.repos, { device.store }, { it() }, { 1L })
        assertEquals(1, search.backfill())
        assertEquals(0, search.backfill(), "an unchanged sheet is not rewritten")
    }

    @Test
    fun search_should_BeBounded_When_ManyRowsMatch() = runBlocking<Unit> {
        val repos = SketchTestSupport.backends().first { it.name == "sqldelight" }.repos
        repos.projects.saveProject(SketchTestSupport.project())
        repeat(SketchSearchTextRepository.CANDIDATE_PAGE * 3) { i ->
            repos.searchText.save(SketchSearchRow("p1", "s$i", "kitchen $i", "h$i", i.toLong()))
        }
        val hits = repos.searchText.search("kitchen", 5).getOrNull()!!
        assertEquals(5, hits.size)
        assertEquals("s${SketchSearchTextRepository.CANDIDATE_PAGE * 3 - 1}", hits.first().sheetUuid, "newest first")
        assertTrue(repos.searchText.search("100%", 5).getOrNull()!!.isEmpty(), "LIKE wildcards in the query are literal")
        repos.searchText.deleteProject("p1")
        assertTrue(repos.searchText.search("kitchen", 5).getOrNull()!!.isEmpty())
    }
}
