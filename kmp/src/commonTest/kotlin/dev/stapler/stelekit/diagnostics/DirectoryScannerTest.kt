// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.diagnostics

import dev.stapler.stelekit.platform.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DirectoryScannerTest {

    private class MockFileSystem(
        private val directories: Map<String, List<String>> = emptyMap(),
        private val existingDirectories: Set<String> = emptySet(),
    ) : FileSystem {
        override fun getDefaultGraphPath(): String = "/tmp"
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = true
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = directories[path] ?: emptyList()
        override fun fileExists(path: String): Boolean = false
        override fun directoryExists(path: String): Boolean = existingDirectories.contains(path)
        override fun createDirectory(path: String): Boolean = true
        override fun deleteFile(path: String): Boolean = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    @Test
    fun `scanForWikiCandidates finds nested logseq folder with pages and journals`() {
        val fs = MockFileSystem(
            directories = mapOf("/repo" to listOf("logseq", "src", "docs")),
            existingDirectories = setOf(
                "/repo/logseq/pages",
                "/repo/logseq/journals",
            )
        )

        val candidates = scanForWikiCandidates("/repo", fs)

        assertEquals(1, candidates.size)
        val candidate = candidates.first()
        assertEquals("logseq", candidate.path)
        assertEquals("logseq", candidate.name)
        assertTrue(candidate.pages)
        assertTrue(candidate.journals)
        assertTrue(candidate.hasPages)
        assertTrue(candidate.hasJournals)
    }

    @Test
    fun `scanForWikiCandidates filters out dot internal directories`() {
        val fs = MockFileSystem(
            directories = mapOf("/repo" to listOf(".git", ".stelekit", ".obsidian", "notes")),
            existingDirectories = setOf(
                "/repo/.git/pages",
                "/repo/.stelekit/pages",
                "/repo/notes/pages",
            )
        )

        val candidates = scanForWikiCandidates("/repo", fs)

        assertEquals(1, candidates.size)
        assertEquals("notes", candidates.first().path)
    }

    @Test
    fun `scanForWikiCandidates returns empty list when no candidates exist`() {
        val fs = MockFileSystem(
            directories = mapOf("/repo" to listOf("src", "build")),
            existingDirectories = emptySet()
        )

        val candidates = scanForWikiCandidates("/repo", fs)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `CandidateCache shouldScan respects page count thresholds and TTL`() {
        val cache = CandidateCache(
            ttl = 10.seconds,
            pageThreshold = 20,
            localThreshold = 100,
        )

        // Below threshold -> scan allowed
        assertTrue(cache.shouldScan(currentPageCount = 5, isSafPlatform = true, currentTime = 1000L))

        // Above threshold for SAF -> scan blocked
        assertFalse(cache.shouldScan(currentPageCount = 25, isSafPlatform = true, currentTime = 1000L))

        // Cache a result at t=1000L
        cache.cacheResult(listOf(DirectoryScanResult("logseq", pages = true, journals = true, name = "logseq")), currentTime = 1000L)

        // Before TTL expiration (t=5000L) -> should not scan
        assertFalse(cache.shouldScan(currentPageCount = 5, isSafPlatform = true, currentTime = 5000L))

        // Get cached result
        val cached = cache.getCached(currentTime = 5000L)
        assertEquals(1, cached?.size)

        // After TTL expiration (t=20000L) -> getCached returns null
        assertNull(cache.getCached(currentTime = 20000L))
        // And shouldScan is true again if page count is low
        assertTrue(cache.shouldScan(currentPageCount = 5, isSafPlatform = true, currentTime = 20000L))
    }
}
