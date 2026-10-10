// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 3.1.4 Task 3.1.4c — regression coverage proving [AtomicFileRelocationStep.relocate]
 * reproduces `GraphManager.moveGraphFilesAndCredentials`'s pre-extraction DB/WAL/SHM
 * rename-with-rollback-on-partial-failure algorithm exactly (REQ-6).
 */
class AtomicFileRelocationStepTest {

    /** Mirrors the DB/WAL/SHM triple `moveGraphFilesAndCredentials` moves. */
    private fun dbWalShmMoves(): List<FileMove> = listOf(
        FileMove("old.db", "new.db"),
        FileMove("old.db-wal", "new.db-wal", optional = true),
        FileMove("old.db-shm", "new.db-shm", optional = true),
    )

    /** In-memory [FileSystem] that tracks existing paths and can be told to fail specific renames. */
    private class RenameFakeFileSystem(initiallyExisting: Set<String> = emptySet()) : FileSystem {
        val existingPaths = initiallyExisting.toMutableSet()
        val failRenameFrom = mutableSetOf<String>()
        val renameCalls = mutableListOf<Pair<String, String>>()

        override fun getDefaultGraphPath(): String = "/graph"
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = true
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = path in existingPaths
        override fun directoryExists(path: String): Boolean = false
        override fun createDirectory(path: String): Boolean = true
        override fun deleteFile(path: String): Boolean {
            existingPaths.remove(path)
            return true
        }
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null

        override fun renameFile(from: String, to: String): Boolean {
            renameCalls.add(from to to)
            if (from in failRenameFrom || from !in existingPaths) return false
            existingPaths.remove(from)
            existingPaths.add(to)
            return true
        }
    }

    @Test
    fun `relocate should RollBackFirstFileRename When SecondFileRenameFails`() {
        val fs = RenameFakeFileSystem(initiallyExisting = setOf("old.db", "old.db-wal", "old.db-shm"))
        fs.failRenameFrom += "old.db-wal"

        val result = AtomicFileRelocationStep.relocate(fs, dbWalShmMoves())

        val error = assertIs<arrow.core.Either.Left<DomainError.StorageError>>(result).value
        assertEquals(DomainError.StorageError.RelocationFailed("old.db-wal"), error)

        // First file's rename is rolled back — matches moveGraphFilesAndCredentials's existing
        // behavior exactly, not just "ends up in a consistent state."
        assertTrue(fs.fileExists("old.db"))
        assertFalse(fs.fileExists("new.db"))

        // SHM is never attempted once WAL fails — the original code short-circuits via
        // `walMoved && renameSidecarIfPresent(...)`, so a rollback-worthy failure on WAL must not
        // also touch SHM.
        assertTrue(fs.renameCalls.none { it.first == "old.db-shm" })
    }

    @Test
    fun `relocate should RollBackPriorRenames When ThirdFileRenameFails`() {
        val fs = RenameFakeFileSystem(initiallyExisting = setOf("old.db", "old.db-wal", "old.db-shm"))
        fs.failRenameFrom += "old.db-shm"

        val result = AtomicFileRelocationStep.relocate(fs, dbWalShmMoves())

        assertIs<arrow.core.Either.Left<DomainError.StorageError>>(result)
        assertTrue(fs.fileExists("old.db"))
        assertTrue(fs.fileExists("old.db-wal"))
        assertFalse(fs.fileExists("new.db"))
        assertFalse(fs.fileExists("new.db-wal"))
    }

    @Test
    fun `relocate should ReturnSuccess When AllFilesRenameSuccessfully`() {
        val fs = RenameFakeFileSystem(initiallyExisting = setOf("old.db", "old.db-wal", "old.db-shm"))

        val result = AtomicFileRelocationStep.relocate(fs, dbWalShmMoves())

        assertIs<arrow.core.Either.Right<Unit>>(result)
        assertTrue(fs.fileExists("new.db"))
        assertTrue(fs.fileExists("new.db-wal"))
        assertTrue(fs.fileExists("new.db-shm"))
    }

    @Test
    fun `relocate should SkipOptionalMoveAndSucceed When SidecarAbsent`() {
        // No WAL/SHM sidecars exist — mirrors the common case where no WAL checkpoint is pending.
        val fs = RenameFakeFileSystem(initiallyExisting = setOf("old.db"))

        val result = AtomicFileRelocationStep.relocate(fs, dbWalShmMoves())

        assertIs<arrow.core.Either.Right<Unit>>(result)
        assertTrue(fs.fileExists("new.db"))
        // Never attempted a rename for an absent optional sidecar.
        assertTrue(fs.renameCalls.none { it.first == "old.db-wal" || it.first == "old.db-shm" })
    }

    @Test
    fun `relocate should ReturnFailureWithoutRollback When FirstFileRenameFails`() {
        val fs = RenameFakeFileSystem(initiallyExisting = setOf("old.db"))
        fs.failRenameFrom += "old.db"

        val result = AtomicFileRelocationStep.relocate(
            fs,
            listOf(FileMove("old.db", "new.db")),
        )

        assertIs<arrow.core.Either.Left<DomainError.StorageError>>(result)
        assertTrue(fs.fileExists("old.db"))
        // No rollback attempted since nothing succeeded yet — only the one failed call was made.
        assertEquals(1, fs.renameCalls.size)
    }
}
