// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbOnlyRowsGuardTest {
    private val files = FakeRestoreFiles()
    private val pages = listOf(DbOnlyPage("2026_10_07", "- seven\n"), DbOnlyPage("2026/10/09", "- nine\n"), DbOnlyPage("2026_10_07", "- dup\n"))
    private fun guard(fileSystem: FakeRestoreFiles = files) =
        DbOnlyRowsGuard({ pages }, fileSystem, "/data/console-recovery", nowMs = { 1_793_000_000_000L }, zone = TimeZone.UTC)

    @Test
    fun export_should_WriteEachPageUnderATimestampedDirectoryAndReadItBack() {
        val dir = guard().export(pages).getOrNull()
        assertEquals("/data/console-recovery/20261026-073320", dir)
        assertEquals("- seven\n", files.store["$dir/2026_10_07.md"])
        assertEquals("- nine\n", files.store["$dir/2026_10_09.md"], "path separators in a page name never create directories")
        assertEquals("- dup\n", files.store["$dir/2026_10_07_2.md"], "same-named pages must not overwrite each other")
    }

    @Test
    fun export_should_WriteNothingAndReturnTheRootDirectory_When_NoPagesAreLocalOnly() {
        assertEquals("/data/console-recovery", guard().export(emptyList()).getOrNull())
        assertTrue(files.store.isEmpty())
    }

    @Test
    fun export_should_FailNamingThePage_When_AFileCannotBeWritten() {
        files.failWrite = { it.endsWith("2026_10_09.md") }
        val error = guard().export(pages).leftOrNull().orEmpty()
        assertTrue("2026/10/09" in error && "Nothing was changed" in error, error)
    }

    @Test
    fun export_should_Fail_When_TheReadBackDiffers() {
        val lossy = object : dev.stapler.stelekit.db.BackupFiles by files {
            override fun readText(path: String) = "truncated"
        }
        val error = DbOnlyRowsGuard({ pages }, lossy, "/data/console-recovery").export(pages).leftOrNull().orEmpty()
        assertTrue("Couldn't save" in error, error)
    }

    @Test
    fun scan_should_ReturnWhatTheSourceFinds() = runBlocking<Unit> {
        assertEquals(pages, guard().scan())
    }

    @Test
    fun renderOutline_should_NestChildrenOrderSiblingsAndIndentContinuationLines() {
        val blocks = listOf(
            StoredBlock("c", "a", "child", "a0"),
            StoredBlock("b", null, "second root", "a1"),
            StoredBlock("a", null, "first root\nsecond line", "a0"),
            StoredBlock("orphan", "missing-parent", "orphan becomes a root", "a2"),
        )
        assertEquals(
            "- first root\n  second line\n\t- child\n- second root\n- orphan becomes a root\n",
            JournalDbOnlyRowsSource.renderOutline(blocks),
        )
    }
}
