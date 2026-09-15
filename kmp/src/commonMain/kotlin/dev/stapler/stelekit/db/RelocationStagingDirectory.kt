// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Staging-directory + marker-file convention for a graph relocation's copy step (Epic 3.1,
 * `research/pitfalls.md` §5 "Orphaned partial-copy directories", `design/ux.md` Surface 13).
 *
 * The copy step writes into `<destinationParent>/.stele-relocate-staging-<graphId>/` — a distinct
 * path, never the final destination — with a `.marker` file recording `graphId` and
 * `startedAtEpochMs`. This makes an interrupted relocate detectable and safely cleanable on next
 * launch instead of leaving an ambiguous partial state at the real destination.
 *
 * [sweep] mirrors `GitShadowWorktree.sweepOrphans()`'s precedent exactly: a staging directory with
 * no marker file is never deleted (ambiguous absence is never treated as evidence of staleness),
 * and only a marker aged past the grace period is swept. This routine never touches the relocation
 * source or final destination — only the distinct staging path.
 */
object RelocationStagingDirectory {
    private const val STAGING_PREFIX = ".stele-relocate-staging-"
    private const val MARKER_FILE_NAME = ".marker"

    /** Default grace period for [sweep] — 7 days (`design/ux.md` Surface 13). */
    const val DEFAULT_GRACE_PERIOD_MILLIS = 7L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Marker(val graphId: String, val startedAtEpochMs: Long)

    /** `<destinationParent>/.stele-relocate-staging-<graphId>` — never the final destination path. */
    fun stagingPath(destinationParent: String, graphId: String): String =
        "${destinationParent.trimEnd('/')}/$STAGING_PREFIX$graphId"

    private fun markerPath(stagingPath: String): String = "${stagingPath.trimEnd('/')}/$MARKER_FILE_NAME"

    /**
     * Creates [stagingPath] (if absent) and writes/overwrites its marker recording [graphId] and
     * [startedAtEpochMs]. Called once, at the start of the copy step (Story 3.1.4/3.1.5).
     */
    fun writeMarker(
        fileSystem: FileSystem,
        stagingPath: String,
        graphId: String,
        startedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        fileSystem.createDirectory(stagingPath)
        return fileSystem.writeFile(markerPath(stagingPath), json.encodeToString(Marker(graphId, startedAtEpochMs)))
    }

    /** Returns the parsed marker for [stagingPath], or null if absent, unreadable, or corrupt. */
    fun readMarker(fileSystem: FileSystem, stagingPath: String): Marker? {
        val content = fileSystem.readFile(markerPath(stagingPath)) ?: return null
        return try {
            json.decodeFromString<Marker>(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Startup sweep (Story 3.1.2 / `design/ux.md` Surface 13). Scans the immediate subdirectories
     * of [destinationParent] for staging directories (`.stele-relocate-staging-*`) and deletes any
     * whose marker's [Marker.startedAtEpochMs] is older than [gracePeriodMillis]. A staging
     * directory with **no** marker file is left untouched — ambiguous absence is never treated as
     * staleness, matching `GitShadowWorktree.sweepOrphans()`'s precedent.
     *
     * Performs blocking file I/O (mirrors `sweepOrphans`) — callers must invoke this off the main
     * thread, e.g. from `Dispatchers.IO`.
     */
    fun sweep(
        fileSystem: FileSystem,
        destinationParent: String,
        nowEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
        gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS,
    ) {
        val stagingDirNames = fileSystem.listDirectories(destinationParent).filter { it.startsWith(STAGING_PREFIX) }
        for (name in stagingDirNames) {
            val path = "${destinationParent.trimEnd('/')}/$name"
            val marker = readMarker(fileSystem, path) ?: continue // ambiguous absence — never sweep
            val age = nowEpochMs - marker.startedAtEpochMs
            if (age <= gracePeriodMillis) continue
            fileSystem.deleteFile(path)
        }
    }
}
