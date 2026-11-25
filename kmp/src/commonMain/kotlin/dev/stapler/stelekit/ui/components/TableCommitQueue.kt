// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.BlockUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs table commits for one block at a time in submission order (other blocks run independently, so one hung write
 * blocks only its own table). `BlockStateManager.updateBlockContent` launches each write on its own coroutine, so two
 * quick commits could otherwise land out of order and persist the older text. [write] must return only after the write
 * finished (join the manager's job). A throwing write is logged and does not stop later ones. Call [submit] and [close]
 * from one thread (the UI). After [close], queued commits still run and later submits are dropped with a warning.
 */
internal class TableCommitQueue(
    private val scope: CoroutineScope,
    private val write: suspend (BlockUuid, String, Long) -> Unit,
) {
    private class Commit(val uuid: BlockUuid, val text: String, val version: Long)

    private val log = Logger("TableCommitQueue")
    private val queues = HashMap<BlockUuid, Channel<Commit>>()
    private var closed = false

    fun submit(uuid: BlockUuid, text: String, version: Long) {
        if (closed) return log.warn("table commit dropped after close: ${uuid.value}")
        val queue = queues.getOrPut(uuid) { newQueue() }
        if (queue.trySend(Commit(uuid, text, version)).isFailure) log.warn("table commit dropped: ${uuid.value}")
    }

    private fun newQueue(): Channel<Commit> {
        val channel = Channel<Commit>(Channel.UNLIMITED)
        scope.launch {
            for (commit in channel) {
                try {
                    write(commit.uuid, commit.text, commit.version)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("table commit write failed for ${commit.uuid.value}: ${e.message}")
                }
            }
        }
        return channel
    }

    fun close() {
        closed = true
        queues.values.forEach { it.close() }
    }
}
