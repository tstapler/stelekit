// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystemLibraryStatsProvider
import dev.stapler.stelekit.platform.LibraryStatsProvider
import dev.stapler.stelekit.platform.NoOpLibraryStatsProvider
import dev.stapler.stelekit.ui.graphContentFileSystem
import dev.stapler.stelekit.ui.graphContentLibraryStatsProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LibraryStatsProviderTest {
    @Test
    fun webGraphLoadsStatisticsWithoutNativePaths() = runTest {
        val root = "/stelekit/default"
        val files = object : FakeFileSystem() {
            override fun readFile(path: String): String? = error("Must use suspend file reads")
            override suspend fun readFileSuspend(path: String): String? = super.readFile(path)
        }
        files.writeFile("$root/pages/Alpha.md", "- [[Beta]]\n- another block")
        files.writeFile("$root/pages/Beta.md", "- content #topic")
        files.writeFile("$root/pages/ignored.txt", "- not markdown")
        files.writeFile("$root/journals/2026_10_15.md", "- [[Alpha]]")
        val report = FileSystemLibraryStatsProvider(files).collect(EffectiveNotesPath(root))!!
        assertEquals(root, report.graphPath)
        assertEquals(2, report.pageCount)
        assertEquals(1, report.journalCount)
        assertEquals(4, report.totalBlocks)
        assertEquals(2, report.totalOutgoingLinks)
        assertEquals(1, report.totalHashtags)
        assertEquals("2026-10-15", report.firstJournalDate)
        assertEquals(1f, report.journalFillRate)
    }

    @Test
    fun missingGraphReturnsNull() = runTest {
        assertNull(FileSystemLibraryStatsProvider(FakeFileSystem()).collect(EffectiveNotesPath("/stelekit/missing")))
    }

    @Test
    fun emptyGraphReturnsAnEmptyReport() = runTest {
        val files = FakeFileSystem()
        files.createDirectory("/stelekit/empty")
        val report = FileSystemLibraryStatsProvider(files).collect(EffectiveNotesPath("/stelekit/empty"))!!
        assertEquals(0, report.pageCount)
        assertEquals(0, report.journalCount)
        assertEquals(0, report.totalBlocks)
        assertTrue(report.benchmarkTargets.blocksPerPageMax >= report.benchmarkTargets.blocksPerPageMin)
    }

    @Test
    fun consecutiveJournalsHaveFullCoverage() = runTest {
        val files = FakeFileSystem()
        files.writeFile("/stelekit/default/journals/2026_10_14.md", "- yesterday")
        files.writeFile("/stelekit/default/journals/2026_10_15.md", "- today")
        val report = FileSystemLibraryStatsProvider(files).collect(EffectiveNotesPath("/stelekit/default"))!!
        assertEquals(1f, report.journalFillRate)
    }

    @Test
    fun productionSelectionUsesDemoFilesystemForDemoGraph() = runTest {
        val effective = graphContentFileSystem(isDemo = true, FakeFileSystem())
        val provider = graphContentLibraryStatsProvider(NoOpLibraryStatsProvider, effective)
        val report = provider.collect(EffectiveNotesPath("/demo"))!!
        assertTrue(report.pageCount > 0)
        assertTrue(report.totalBlocks > 0)
    }

    @Test
    fun productionSelectionUsesBrowserFilesystemForDefaultGraph() = runTest {
        val files = FakeFileSystem()
        files.writeFile("/stelekit/default/pages/Browser.md", "- cached content")
        val effective = graphContentFileSystem(isDemo = false, files)
        assertSame(files, effective)
        val provider = graphContentLibraryStatsProvider(NoOpLibraryStatsProvider, effective)
        assertEquals(1, provider.collect(EffectiveNotesPath("/stelekit/default"))!!.pageCount)
    }

    @Test
    fun productionSelectionPreservesCustomProviderForEveryGraph() = runTest {
        val paths = mutableListOf<String>()
        val custom = object : LibraryStatsProvider {
            override suspend fun collect(notesPath: EffectiveNotesPath): GraphStatsReport? {
                paths += notesPath.value
                return null
            }
        }
        for ((isDemo, path) in listOf(true to "/demo", false to "/stelekit/default")) {
            val provider = graphContentLibraryStatsProvider(custom, graphContentFileSystem(isDemo, FakeFileSystem()))
            assertSame(custom, provider)
            provider.collect(EffectiveNotesPath(path))
        }
        assertEquals(listOf("/demo", "/stelekit/default"), paths)
    }

    @Test
    fun cachedReadsYieldBetweenFilesAndAllowCancellation() = runTest {
        for (journal in listOf(false, true)) {
            var reads = 0
            var heartbeatReads = 0
            var completed = false
            lateinit var scanJob: Job
            val names = List(1000) { "$it.md" }
            scanJob = launch {
                MarkdownGraphStatsCollector().collect(
                    "/cached", if (journal) emptyList() else names, if (journal) names else emptyList(),
                ) {
                    reads++
                    if (reads == 1) launch {
                        heartbeatReads = reads
                        scanJob.cancel()
                    }
                    "- cached [[Target]]"
                }
                completed = true
            }
            scanJob.join()
            assertTrue(heartbeatReads in 1..32, "Scheduled work waited for $heartbeatReads cached reads")
            assertTrue(scanJob.isCancelled)
            assertTrue(!completed)
        }
    }

    @Test
    fun cachedLargeFileYieldsBeforeNextReadAndAllowsCancellation() = runTest {
        // Non-block lines must also participate in the checkpoint budget.
        for (line in listOf("- [[Target]]", "plain text")) {
            val content = ("$line\n").repeat(4096)
            var reads = 0
            var heartbeatReads = 0
            var completed = false
            lateinit var scanJob: Job
            scanJob = launch {
                MarkdownGraphStatsCollector().collect("/cached", listOf("large.md", "next.md"), emptyList()) {
                    reads++
                    if (reads == 1) launch {
                        heartbeatReads = reads
                        scanJob.cancel()
                    }
                    content
                }
                completed = true
            }
            scanJob.join()
            assertEquals(1, heartbeatReads, "Large-file CPU scan must yield before reading the next file")
            assertEquals(1, reads)
            assertTrue(scanJob.isCancelled)
            assertTrue(!completed)
        }
    }
}
