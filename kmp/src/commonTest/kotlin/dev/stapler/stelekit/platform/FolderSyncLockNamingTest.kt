package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.GitWriteLockNaming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [FolderSyncLockNaming.pollLockNameFor] and [FolderSyncLockNaming.writeLockNameFor]
 * — the pure lock-name derivation backing cross-tab Web Locks coordination for folder sync (Phase 6).
 * See Story 1.2.1 in project_plans/web-local-folder-livesync/implementation/plan.md and the
 * corresponding rows in validation.md.
 *
 * Mirrors the structure of `git/GitWriteLockNamingTest.kt`.
 */
class FolderSyncLockNamingTest {

    @Test
    fun pollLockNameFor_should_ReturnIdenticalStringOnRepeatedCalls_When_GivenSameGraphId() {
        val first = FolderSyncLockNaming.pollLockNameFor("a1b2c3d4")
        val second = FolderSyncLockNaming.pollLockNameFor("a1b2c3d4")

        assertEquals("stele-folder-sync-poll-a1b2c3d4", first)
        assertEquals(first, second)
    }

    @Test
    fun writeLockNameFor_should_ReturnDistinctNames_When_GivenDifferentRepoRelativePaths() {
        val graphId = "a1b2c3d4"

        val foo = FolderSyncLockNaming.writeLockNameFor(graphId, "pages/Foo.md")
        val bar = FolderSyncLockNaming.writeLockNameFor(graphId, "pages/Bar.md")

        assertNotEquals(foo, bar)

        // Deterministic: same inputs always produce the same name.
        assertEquals(foo, FolderSyncLockNaming.writeLockNameFor(graphId, "pages/Foo.md"))

        // No reserved/unsafe characters — only ASCII letters, digits, '.', and '-'.
        assertTrue(foo.matches(Regex("^[A-Za-z0-9.-]+$")), "name '$foo' contains unsafe characters")
        assertTrue(bar.matches(Regex("^[A-Za-z0-9.-]+$")), "name '$bar' contains unsafe characters")
    }

    // ── Bug fix (code-review repair loop): literal '-' must not collide with a collapsed '/' ────

    @Test
    fun writeLockNameFor_should_ReturnDifferentNames_When_OneInputHasLiteralDashAndOtherHasPathSeparator() {
        val graphId = "a1b2c3d4"

        // "a/b" has one path separator; "a-b" has one literal dash. Before the fix, both collapsed
        // to the identical sanitized "a-b" — a real lock-name collision between two different files.
        val withSeparator = FolderSyncLockNaming.writeLockNameFor(graphId, "a/b")
        val withLiteralDash = FolderSyncLockNaming.writeLockNameFor(graphId, "a-b")

        assertNotEquals(
            withSeparator,
            withLiteralDash,
            "a path separator ('a/b') and a literal dash ('a-b') must never sanitize to the same lock name",
        )

        // Still deterministic and still restricted to the documented safe character set.
        assertEquals(withLiteralDash, FolderSyncLockNaming.writeLockNameFor(graphId, "a-b"))
        assertTrue(
            withLiteralDash.matches(Regex("^[A-Za-z0-9.-]+$")),
            "name '$withLiteralDash' contains unsafe characters",
        )
    }

    // ── Cross-feature isolation guard ────────────────────────────────────────

    @Test
    fun pollLockNameFor_and_writeLockNameFor_should_NeverSharePrefixWithGitWriteLockNaming_When_ComparedForAnyGraphId() {
        val graphIds = listOf("a1b2c3d4", "graph-2", "0000")

        for (graphId in graphIds) {
            val poll = FolderSyncLockNaming.pollLockNameFor(graphId)
            val write = FolderSyncLockNaming.writeLockNameFor(graphId, "x")
            val gitWrite = GitWriteLockNaming.lockNameFor("https://github.com/a/b")

            assertTrue(poll.startsWith("stele-folder-sync-poll-"))
            assertTrue(write.startsWith("stele-folder-sync-write-"))
            assertTrue(gitWrite.startsWith("stele-write-"))

            // None of the three share a prefix with each other.
            assertTrue(!poll.startsWith("stele-write-") && !poll.startsWith("stele-folder-sync-write-"))
            assertTrue(!write.startsWith("stele-write-") && !write.startsWith("stele-folder-sync-poll-"))
            assertTrue(
                !gitWrite.startsWith("stele-folder-sync-poll-") &&
                    !gitWrite.startsWith("stele-folder-sync-write-"),
            )

            assertNotEquals(poll, gitWrite)
            assertNotEquals(write, gitWrite)
        }
    }
}
