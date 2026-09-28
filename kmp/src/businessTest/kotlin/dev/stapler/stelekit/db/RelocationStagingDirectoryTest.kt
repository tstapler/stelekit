// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 3.1.2 (Task 3.1.2c). Mirrors `GitShadowWorktreeSweepStorageGateTest`'s precedent for the
 * "ambiguous absence is never treated as staleness" rule, applied to `RelocationStagingDirectory`'s
 * startup sweep instead of `GitShadowWorktree.sweepOrphans()`.
 */
class RelocationStagingDirectoryTest {

    private lateinit var tempDir: java.io.File
    private lateinit var fileSystem: PlatformFileSystem

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("relocation_staging_test_").toFile()
        fileSystem = PlatformFileSystem.withRoot(tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun startupSweep_should_DeleteStagingDirectory_When_MarkerIsOlderThanGracePeriod() {
        val stagingPath = RelocationStagingDirectory.stagingPath(tempDir.absolutePath, "g1")
        val startedAt = 1_000_000_000L
        RelocationStagingDirectory.writeMarker(fileSystem, stagingPath, graphId = "g1", startedAtEpochMs = startedAt)
        val eightDaysLater = startedAt + 8L * 24 * 60 * 60 * 1000

        RelocationStagingDirectory.sweep(fileSystem, tempDir.absolutePath, nowEpochMs = eightDaysLater)

        assertFalse(fileSystem.directoryExists(stagingPath))
    }

    @Test
    fun startupSweep_should_LeaveDirectoryUntouched_When_NoMarkerFilePresent() {
        val stagingPath = RelocationStagingDirectory.stagingPath(tempDir.absolutePath, "g2")
        fileSystem.createDirectory(stagingPath)
        fileSystem.writeFile("$stagingPath/partially-copied-page.md", "content") // no marker written
        val farFuture = 1_000_000_000L + 365L * 24 * 60 * 60 * 1000

        RelocationStagingDirectory.sweep(fileSystem, tempDir.absolutePath, nowEpochMs = farFuture)

        assertTrue(fileSystem.directoryExists(stagingPath))
    }

    @Test
    fun startupSweep_should_LeaveDirectoryUntouched_When_MarkerIsWithinGracePeriod() {
        val stagingPath = RelocationStagingDirectory.stagingPath(tempDir.absolutePath, "g3")
        val startedAt = 1_000_000_000L
        RelocationStagingDirectory.writeMarker(fileSystem, stagingPath, graphId = "g3", startedAtEpochMs = startedAt)
        val oneDayLater = startedAt + 24 * 60 * 60 * 1000

        RelocationStagingDirectory.sweep(fileSystem, tempDir.absolutePath, nowEpochMs = oneDayLater)

        assertTrue(fileSystem.directoryExists(stagingPath))
    }

    @Test
    fun stagingPath_should_NeverEqualDestination_When_Constructed() {
        val destination = "${tempDir.absolutePath}/g4"
        val stagingPath = RelocationStagingDirectory.stagingPath(tempDir.absolutePath, "g4")

        assertTrue(stagingPath != destination)
        assertTrue(stagingPath.substringAfterLast('/').startsWith(".stele-relocate-staging-"))
    }

    @Test
    fun readMarker_should_RoundTripGraphIdAndStartedAt_When_WriteMarkerSucceeded() {
        val stagingPath = RelocationStagingDirectory.stagingPath(tempDir.absolutePath, "g5")
        RelocationStagingDirectory.writeMarker(fileSystem, stagingPath, graphId = "g5", startedAtEpochMs = 42L)

        val marker = RelocationStagingDirectory.readMarker(fileSystem, stagingPath)

        assertEquals("g5", marker?.graphId)
        assertEquals(42L, marker?.startedAtEpochMs)
    }

    @Test
    fun readMarker_should_ReturnNull_When_NoMarkerFileExists() {
        val stagingPath = RelocationStagingDirectory.stagingPath(tempDir.absolutePath, "g6")
        fileSystem.createDirectory(stagingPath)

        assertNull(RelocationStagingDirectory.readMarker(fileSystem, stagingPath))
    }
}
