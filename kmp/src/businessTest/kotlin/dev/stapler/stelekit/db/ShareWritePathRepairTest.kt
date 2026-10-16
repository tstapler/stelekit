// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.db.ShareWritePathRepair.PagePath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareWritePathRepairTest {
    private val root = "/graphs/g"
    private val notes = "$root/logseq"
    private val fs = FakeRelocationFileSystem()
    private val repair = ShareWritePathRepair(fs)

    private fun put(path: String, text: String) = fs.writeFile(path, text)
    private fun run(vararg pages: PagePath) = repair.repair(root, notes, pages.toList())

    @Test
    fun repair_should_MoveStrayJournalAndRepointPage() {
        put("$root/journals/2026_10_16.md", "- shared\n")

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertEquals(mapOf("p1" to "$notes/journals/2026_10_16.md"), outcome.repoints)
        assertEquals("- shared\n", fs.readFile("$notes/journals/2026_10_16.md"))
        assertFalse(fs.fileExists("$root/journals/2026_10_16.md"))
        assertEquals(1, outcome.moved)
        assertTrue(outcome.clean)
    }

    @Test
    fun repair_should_MoveOnlyAssetsTheMovedPageReferences() {
        put("$root/journals/2026_10_16.md", "- look\n  ![image](../assets/images/a.jpg)\n")
        fs.writeFileBytes("$root/assets/images/a.jpg", byteArrayOf(1, 2, 3))
        fs.writeFileBytes("$root/assets/images/unrelated.jpg", byteArrayOf(9))

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertContentEquals(byteArrayOf(1, 2, 3), fs.readFileBytes("$notes/assets/images/a.jpg"))
        assertFalse(fs.fileExists("$root/assets/images/a.jpg"))
        assertTrue(fs.fileExists("$root/assets/images/unrelated.jpg"), "a file no moved page references must not be touched")
        assertEquals(1, outcome.assetsMoved)
    }

    @Test
    fun repair_should_RepointOnly_When_FileIsAlreadyUnderNotesPath() {
        put("$notes/pages/Foo.md", "- foo\n")

        val outcome = run(PagePath("p1", "$root/pages/Foo.md"))

        assertEquals(mapOf("p1" to "$notes/pages/Foo.md"), outcome.repoints)
        assertEquals(0, outcome.moved)
        assertEquals("- foo\n", fs.readFile("$notes/pages/Foo.md"))
    }

    @Test
    fun repair_should_DropStrayCopy_When_BothCopiesAreIdentical() {
        put("$root/pages/Foo.md", "- same\n")
        put("$notes/pages/Foo.md", "- same\n")

        val outcome = run(PagePath("p1", "$root/pages/Foo.md"))

        assertEquals(mapOf("p1" to "$notes/pages/Foo.md"), outcome.repoints)
        assertFalse(fs.fileExists("$root/pages/Foo.md"))
        assertTrue(outcome.clean)
    }

    @Test
    fun repair_should_KeepBothAndReportConflict_When_CopiesDiffer() {
        put("$root/journals/2026_10_16.md", "- phone\n")
        put("$notes/journals/2026_10_16.md", "- desktop\n")

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertTrue(outcome.repoints.isEmpty(), "a conflicting page must keep its old path")
        assertEquals(listOf("$root/journals/2026_10_16.md"), outcome.conflicts)
        assertFalse(outcome.clean, "a conflict must be retried, not marked done")
        assertEquals("- phone\n", fs.readFile("$root/journals/2026_10_16.md"))
        assertEquals("- desktop\n", fs.readFile("$notes/journals/2026_10_16.md"))
    }

    @Test
    fun repair_should_LeaveUnrelatedAndAlreadyCorrectPathsAlone() {
        put("$root/README.md", "readme")
        put("$notes/journals/2026_10_15.md", "- ok\n")
        put("$root/journals/not-in-db.md", "- stray but unknown to SteleKit\n")

        val outcome = run(
            PagePath("a", "$root/README.md"),
            PagePath("b", "$notes/journals/2026_10_15.md"),
            PagePath("c", "$root/pages/missing-everywhere.md"),
        )

        assertTrue(outcome.repoints.isEmpty())
        assertTrue(fs.fileExists("$root/README.md"))
        assertTrue(fs.fileExists("$root/journals/not-in-db.md"), "repair is DB-driven and must not sweep the repo root")
    }

    @Test
    fun repair_should_RefuseTraversalOutOfTheStrayFolder() {
        put("$root/journals/../secret.md", "x")

        val outcome = run(PagePath("p1", "$root/journals/../secret.md"))

        assertTrue(outcome.repoints.isEmpty())
        assertNull(fs.readFile("$notes/journals/../secret.md"))
    }

    @Test
    fun repair_should_BeNoOp_When_GraphHasNoWikiSubdir() {
        put("$root/journals/2026_10_16.md", "- x\n")

        val outcome = ShareWritePathRepair(fs).repair(root, root, listOf(PagePath("p1", "$root/journals/2026_10_16.md")))

        assertTrue(outcome.repoints.isEmpty())
        assertTrue(fs.fileExists("$root/journals/2026_10_16.md"))
    }

    @Test
    fun repair_should_BeIdempotent() {
        put("$root/journals/2026_10_16.md", "- shared\n")
        val first = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        val second = run(PagePath("p1", first.repoints.getValue("p1")))

        assertTrue(second.repoints.isEmpty())
        assertEquals("- shared\n", fs.readFile("$notes/journals/2026_10_16.md"))
    }
}
