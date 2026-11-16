// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchTestSupport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(DirectRepositoryWrite::class)
/** Task 1.4.3i: the `NewSketchProject` predicate's verdicts and its read bounds, over a counting file system. */
class NewSketchProjectPredicateTest {
    private val slug = "proj-0a1b2c"
    private val project = ProjectUuid("p1")

    private val v2Bytes = "{\"schema\":2,\"sheetUuid\":\"x\",\"elements\":[]}\n".encodeToByteArray()
    private val v1Bytes = "{\n  \"schema\": 1,\n  \"sheet\": \"x\"\n}\n".encodeToByteArray()

    private class Rig(val fs: CountingSidecarFs, val repos: SketchRepositories) {
        val predicate get() = NewSketchProjectPredicate(newStore(fs), repos.sheets)
    }

    private fun sheetRow(i: Int, mirror: Long) = SketchTestSupport.sheet("s$i", position = i.toLong()).copy(schemaVersion = mirror)

    /** [files]: per sheet the (model file name, bytes on disk or null for absent) and the database mirror schema. */
    private fun rig(files: List<Triple<String, ByteArray?, Long>>): Rig = runBlocking {
        val fs = CountingSidecarFs()
        val repos = SketchTestSupport.backends().first { it.name == "sqldelight" }.repos
        val base = SketchTestSupport.bundle(sheets = 0)
        val rows = files.mapIndexed { i, (_, _, mirror) -> sheetRow(i, mirror) }
        repos.projects.replaceProjectContents(project, base.copy(sheets = rows))
        val refs = files.mapIndexed { i, (name, _, _) -> "{\"uuid\":\"s$i\",\"file\":\"sheets/$name\"}" }.joinToString(",")
        fs.seed(sidecarPath(slug, "project-model.json"), "{\"schema\":1,\"sheets\":[$refs]}".encodeToByteArray())
        files.forEach { (name, bytes, _) -> bytes?.let { fs.seed(sidecarPath(slug, "sheets/$name"), it) } }
        Rig(fs, repos)
    }

    private fun qualifies(rig: Rig) = runBlocking { rig.predicate.qualifies(project, slug) }

    @Test
    fun allV2Sheets_should_Qualify_WithOneModelOpenAndNoListing() {
        val rig = rig(List(3) { Triple("s$it.v2.json", v2Bytes, 2L) })
        assertTrue(qualifies(rig))
        assertEquals(1, rig.fs.fullReads.count { it.endsWith("project-model.json") })
        assertEquals(3, rig.fs.prefixReads.size)
        assertTrue(rig.fs.prefixReads.all { it.second == SheetCodec.HEADER_PROBE_BYTES })
        assertEquals(0, rig.fs.listCalls)
        assertTrue(rig.fs.fullReads.none { it.contains("/sheets/") })
    }

    @Test
    fun aProjectWithNoSheets_should_NotQualify_AndReadNoSheetFile() {
        val rig = rig(emptyList())
        assertFalse(qualifies(rig))
        assertTrue(rig.fs.prefixReads.isEmpty())
    }

    @Test
    fun aV1OnlyProject_should_StopAfterTheFirstProbe() {
        val rig = rig(List(4) { Triple("s$it.json", v1Bytes, 1L) })
        assertFalse(qualifies(rig))
        assertEquals(1, rig.fs.prefixReads.size)
    }

    @Test
    fun aMixedProject_should_StopAtTheFirstNonV2Sheet() {
        val rig = rig(
            listOf(
                Triple("s0.v2.json", v2Bytes, 2L), Triple("s1.v2.json", v2Bytes, 2L),
                Triple("s2.json", v1Bytes, 1L), Triple("s3.v2.json", v2Bytes, 2L),
            ),
        )
        assertFalse(qualifies(rig))
        assertEquals(3, rig.fs.prefixReads.size)
    }

    @Test
    fun aMirrorThatSaysV2_should_LoseToAV1File() {
        assertFalse(qualifies(rig(listOf(Triple("s0.json", v1Bytes, 2L)))))
    }

    @Test
    fun aMirrorThatSaysV1_should_LoseToAV2File() {
        assertTrue(qualifies(rig(listOf(Triple("s0.v2.json", v2Bytes, 1L)))))
    }

    @Test
    fun aSheetRefNamingAnAbsentV2File_should_NotQualify_EvenWhenAV1FileExists() {
        val rig = rig(listOf(Triple("s0.v2.json", null, 2L)))
        rig.fs.seed(sidecarPath(slug, "sheets/s0.json"), v1Bytes)
        assertFalse(qualifies(rig))
    }

    @Test
    fun whenBothFilesExist_should_TheOneTheSheetRefNamesDecide() {
        val named = rig(listOf(Triple("s0.v2.json", v2Bytes, 2L)))
        named.fs.seed(sidecarPath(slug, "sheets/s0.json"), v1Bytes)
        assertTrue(qualifies(named))
        val other = rig(listOf(Triple("s0.json", v1Bytes, 1L)))
        other.fs.seed(sidecarPath(slug, "sheets/s0.v2.json"), v2Bytes)
        assertFalse(qualifies(other))
    }

    @Test
    fun anUnclassifiableFile_should_NotQualify() {
        assertFalse(qualifies(rig(listOf(Triple("s0.v2.json", "garbage".encodeToByteArray(), 2L)))))
    }

    @Test
    fun moreThanOnePageOfSheets_should_NeverQualify_AndReadNoSheetFile() {
        val total = dev.stapler.stelekit.repository.SketchSheetRepository.DEFAULT_SHEET_LIMIT + 1
        val rig = rig(List(total) { Triple("s$it.v2.json", v2Bytes, 2L) })
        assertFalse(qualifies(rig))
        assertTrue(rig.fs.prefixReads.isEmpty())
    }

    @Test
    fun anUnavailableStore_should_NeverQualify_AndReadNothing() {
        val rig = rig(listOf(Triple("s0.v2.json", v2Bytes, 2L)))
        rig.fs.tempRenameSupported = false
        assertFalse(qualifies(rig))
        assertTrue(rig.fs.prefixReads.isEmpty())
    }

    @Test
    fun theLegacyProbe_should_SeeAV2FileThatTheMirrorCallsV1() = runBlocking<Unit> {
        val rig = rig(listOf(Triple("s0.v2.json", v2Bytes, 1L)))
        assertTrue(SheetFileSchemaProbe(newStore(rig.fs)).isV2(slug, SheetUuid("s0")))
        val v1 = rig(listOf(Triple("s0.json", v1Bytes, 2L)))
        assertFalse(SheetFileSchemaProbe(newStore(v1.fs)).isV2(slug, SheetUuid("s0")))
    }
}
