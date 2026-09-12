// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Drains [PendingCaptureFile]s left on disk by [PendingCaptureWriter] — the headless CLI
 * capture path and any capture taken before a graph finished loading — by replaying each one
 * through the same [CaptureWriter.writeCaptureDirect] chain the live hotkey popup uses.
 *
 * Runs on its own 5s poll loop (matching [dev.stapler.stelekit.db.GraphFileWatcher]'s
 * convention) plus once on [start] so cold-start replay isn't gated on the first tick.
 */
class PendingCapturePoller(
    private val fileSystem: PlatformFileSystem,
    private val directory: String = PendingCapturesDirectory.path(),
    private val pollIntervalMs: Long = 5_000L,
) {

    @Volatile
    private var graphManager: GraphManager? = null

    private val logger = Logger("PendingCapturePoller")

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            logger.error("Poller failure", e)
        },
    )

    fun attachGraphManager(gm: GraphManager) {
        graphManager = gm
    }

    /** Starts the poll loop: an immediate [scanOnce] followed by one every [pollIntervalMs]. */
    fun start() {
        scope.launch {
            while (isActive) {
                scanOnce()
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Replays every pending capture file in [directory], oldest first (filenames are UUIDv7
     * capture IDs, so lexical order is chronological). Each file's decode+write is isolated in
     * its own try/catch so one malformed or failing file never blocks the rest of the batch.
     */
    suspend fun scanOnce() {
        val gm = graphManager ?: return

        val dir = Path.of(directory)
        if (!Files.isDirectory(dir)) return

        val files = Files.list(dir).use { stream ->
            stream.filter { it.name.endsWith(".json") }.sorted().toList()
        }

        for (file in files) {
            replayOne(gm, file)
        }
    }

    private suspend fun replayOne(gm: GraphManager, file: Path) {
        try {
            val pending = Json.decodeFromString<PendingCaptureFile>(Files.readString(file))

            when (val result = CaptureWriter.writeCaptureDirect(gm, fileSystem, pending.text, pending.captureId)) {
                is CaptureResult.Saved -> Files.deleteIfExists(file)
                else -> logger.warn("Replay failed for ${file.name}, will retry next scan: $result")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            logger.warn("Malformed pending capture file ${file.name}, renaming to .failed", e)
            renameToFailed(file)
        } catch (e: Exception) {
            logger.warn("Failed to replay pending capture ${file.name}, will retry next scan", e)
        }
    }

    private fun renameToFailed(file: Path) {
        try {
            Files.move(file, file.resolveSibling("${file.name}.failed"))
        } catch (e: Exception) {
            logger.warn("Failed to rename malformed capture file ${file.name} to .failed", e)
        }
    }
}
