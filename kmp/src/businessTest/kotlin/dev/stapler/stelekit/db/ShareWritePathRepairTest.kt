// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.db.ShareWritePathRepair.PagePath
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.model.EffectiveNotesPath
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
    private fun run(vararg pages: PagePath) = repair.repair(RepoRoot(root), EffectiveNotesPath(notes), pages.toList())

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
        put("$root/journals/2026_10_16.md", "title:: phone\n- x\n")
        put("$notes/journals/2026_10_16.md", "title:: desktop\n- y\n")

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertTrue(outcome.repoints.isEmpty(), "a conflicting page must keep its old path")
        assertEquals(listOf("$root/journals/2026_10_16.md"), outcome.conflicts)
        assertFalse(outcome.clean, "a conflict must be retried, not marked done")
        assertEquals("title:: phone\n- x\n", fs.readFile("$root/journals/2026_10_16.md"))
        assertEquals("title:: desktop\n- y\n", fs.readFile("$notes/journals/2026_10_16.md"))
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

        val outcome = ShareWritePathRepair(fs).repair(RepoRoot(root), EffectiveNotesPath(root), listOf(PagePath("p1", "$root/journals/2026_10_16.md")))

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

    @Test
    fun repair_should_RetryConflictingAsset_WithoutPageRows_AfterPagesAreRepointed() {
        put("$root/journals/2026_10_16.md", "- look\n  ![image](../assets/a.jpg)\n")
        fs.writeFileBytes("$root/assets/a.jpg", byteArrayOf(1))
        fs.writeFileBytes("$notes/assets/a.jpg", byteArrayOf(2))

        val first = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertEquals(setOf("a.jpg"), first.pendingAssets)
        assertFalse(first.clean)
        assertTrue(first.conflicts.isEmpty() && first.failures.isEmpty(), "an asset problem must not force a page rescan")
        assertEquals(ShareWritePathMigration.STATE_DONE + "\na.jpg", first.nextState())

        fs.deleteFile("$notes/assets/a.jpg")
        val second = repair.repair(RepoRoot(root), EffectiveNotesPath(notes), emptyList(), first.pendingAssets)

        assertContentEquals(byteArrayOf(1), fs.readFileBytes("$notes/assets/a.jpg"))
        assertEquals(1, second.assetsMoved)
        assertEquals(ShareWritePathMigration.STATE_DONE, second.nextState())
    }

    @Test
    fun repair_should_NotRepoint_When_StrayFileExistsButIsUnreadable() {
        put("$root/journals/2026_10_16.md", "- newer edits\n")
        val blocked = "$root/journals/2026_10_16.md"
        val unreadable = object : FakeRelocationFileSystem() {
            override fun readFileBytes(path: String): ByteArray? = if (path == blocked) null else super.readFileBytes(path)
        }
        unreadable.writeFile(blocked, "- newer edits\n")

        val outcome = ShareWritePathRepair(unreadable).repair(RepoRoot(root), EffectiveNotesPath(notes), listOf(PagePath("p1", blocked)))

        assertTrue(outcome.repoints.isEmpty(), "an unreadable file is not an absent one")
        assertEquals(listOf(blocked), outcome.failures)
        assertEquals(ShareWritePathMigration.STATE_OPEN, outcome.nextState())
        assertTrue(unreadable.fileExists(blocked))
    }

    @Test
    fun repair_should_KeepSourceAndLeaveNoPartialCopy_When_WriteFails() {
        val src = "$root/journals/2026_10_16.md"
        val failing = object : FakeRelocationFileSystem() {
            override fun writeFileBytes(path: String, data: ByteArray): Boolean = false
        }
        failing.writeFile(src, "- x\n")

        val outcome = ShareWritePathRepair(failing).repair(RepoRoot(root), EffectiveNotesPath(notes), listOf(PagePath("p1", src)))

        assertEquals(listOf(src), outcome.failures)
        assertTrue(failing.fileExists(src))
        assertFalse(failing.fileExists("$notes/journals/2026_10_16.md"))
    }

    @Test
    fun repair_should_SkipFilesTooLargeToCopySafely() {
        val src = "$root/journals/2026_10_16.md"
        val huge = object : FakeRelocationFileSystem() {
            override fun getFileSize(path: String): Long? = 64L * 1024 * 1024
        }
        huge.writeFile(src, "- big\n")

        val outcome = ShareWritePathRepair(huge).repair(RepoRoot(root), EffectiveNotesPath(notes), listOf(PagePath("p1", src)))

        assertTrue(outcome.repoints.isEmpty())
        assertEquals(listOf(src), outcome.failures)
        assertTrue(huge.fileExists(src))
    }

    @Test
    fun repair_should_IgnoreAssetRefsThatEscapeTheAssetsFolder() {
        put("$root/secret.txt", "outside the assets folder")
        put("$root/journals/2026_10_16.md", "- a ![x](../assets/../secret.txt)\n- b ![y](../assets/..\\secret.txt)\n- c ![z](../assets/C:/secret.txt)\n")

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertEquals(0, outcome.assetsMoved)
        assertEquals("outside the assets folder", fs.readFile("$root/secret.txt"))
        assertNull(fs.readFile("$notes/secret.txt"))
    }

    @Test
    fun repair_should_WarnButNotBlock_When_PageIsEncrypted() {
        put("$root/journals/2026_10_16.md.stek", "ciphertext")

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md.stek"))

        assertEquals(mapOf("p1" to "$notes/journals/2026_10_16.md.stek"), outcome.repoints)
        assertEquals(1, outcome.warnings.size)
        assertEquals(ShareWritePathMigration.STATE_DONE, outcome.nextState())
    }

    @Test
    fun repair_should_RepointRowsPointingAtANonexistentRootPath_When_RealFileIsUnderNotes() {
        put("$notes/pages/Foo.md", "- real\n")

        val outcome = run(PagePath("p1", "$root/pages/Foo.md"))

        assertEquals(mapOf("p1" to "$notes/pages/Foo.md"), outcome.repoints)
        assertEquals("- real\n", fs.readFile("$notes/pages/Foo.md"))
    }

    @Test
    fun repair_should_KeepPendingAssets_When_AnotherPageConflictsInTheSameRun() {
        put("$root/journals/a.md", "- ![x](../assets/x.jpg)\n")
        fs.writeFileBytes("$root/assets/x.jpg", byteArrayOf(1))
        fs.writeFileBytes("$notes/assets/x.jpg", byteArrayOf(2))
        put("$root/journals/b.md", "title:: phone\n- x\n")
        put("$notes/journals/b.md", "title:: desktop\n- y\n")

        val outcome = run(PagePath("a", "$root/journals/a.md"), PagePath("b", "$root/journals/b.md"))

        assertEquals(ShareWritePathMigration.STATE_OPEN + "\nx.jpg", outcome.nextState())
        assertEquals(mapOf("a" to "$notes/journals/a.md"), outcome.repoints)
    }

    private val backupBase = "$root/.stelekit/share-path-repair-backup/journals/2026_10_16.md"

    @Test
    fun repair_should_MergeBothCopiesAndBackThemUp_When_PagesDifferButCombine() {
        put("$root/journals/2026_10_16.md", "- phone note\n  id:: 00000000-0000-4000-8000-0000000000a1\n")
        put("$notes/journals/2026_10_16.md", "- desktop note\n")

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertEquals(1, outcome.merged)
        assertEquals(mapOf("p1" to "$notes/journals/2026_10_16.md"), outcome.repoints)
        val merged = fs.readFile("$notes/journals/2026_10_16.md")!!
        assertTrue(merged.contains("- desktop note") && merged.contains("- phone note"), merged)
        assertFalse(fs.fileExists("$root/journals/2026_10_16.md"), "the stray copy is folded in, not left behind")
        assertEquals("- desktop note\n", fs.readFile("$backupBase.notes"), "the notes original must be recoverable")
        assertTrue(fs.readFile("$backupBase.stray")!!.contains("- phone note"), "the stray original must be recoverable")
        assertTrue(outcome.clean)
    }

    @Test
    fun repair_should_RestoreNotesCopyAndKeepStray_When_MergedWriteFails() {
        val notesPath = "$notes/journals/2026_10_16.md"
        val failing = object : FakeRelocationFileSystem() {
            override fun writeFile(path: String, content: String): Boolean =
                if (path == notesPath && content.contains("- phone note")) false else super.writeFile(path, content)
        }
        failing.writeFile("$root/journals/2026_10_16.md", "- phone note\n")
        failing.writeFile(notesPath, "- desktop note\n")

        val outcome = ShareWritePathRepair(failing).repair(RepoRoot(root), EffectiveNotesPath(notes), listOf(PagePath("p1", "$root/journals/2026_10_16.md")))

        assertTrue(outcome.repoints.isEmpty())
        assertEquals(listOf("$root/journals/2026_10_16.md"), outcome.failures)
        assertEquals("- desktop note\n", failing.readFile(notesPath), "a failed merge must leave the notes copy as it was")
        assertEquals("- phone note\n", failing.readFile("$root/journals/2026_10_16.md"), "and keep the stray copy")
    }

    @Test
    fun repair_should_MergeSharedAndDisjointBlocksWithoutDuplicates() {
        put("$root/journals/2026_10_16.md", "- shared\n- phone only\n")
        put("$notes/journals/2026_10_16.md", "- shared\n- desktop only\n")

        run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertEquals(
            listOf("- shared", "- desktop only", "- phone only"),
            fs.readFile("$notes/journals/2026_10_16.md")!!.lines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun repair_should_KeepEncryptedPagesAsConflicts() {
        put("$root/journals/2026_10_16.md.stek", "cipher-a")
        put("$notes/journals/2026_10_16.md.stek", "cipher-b")

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md.stek"))

        assertTrue(outcome.repoints.isEmpty())
        assertEquals(listOf("$root/journals/2026_10_16.md.stek"), outcome.conflicts)
    }

    @Test
    fun repair_should_ExplainWhyAMergeWasRefused() {
        put("$root/journals/2026_10_16.md", "title:: phone\n- x\n")
        put("$notes/journals/2026_10_16.md", "- y\n")

        val outcome = run(PagePath("p1", "$root/journals/2026_10_16.md"))

        assertEquals(listOf("$root/journals/2026_10_16.md"), outcome.conflicts)
        assertTrue(outcome.warnings.any { it.startsWith("not merged") }, outcome.warnings.toString())
    }
}
