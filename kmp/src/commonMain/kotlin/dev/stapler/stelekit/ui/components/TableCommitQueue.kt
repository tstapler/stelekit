// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.model.BlockUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs table commits one at a time in submission order. `BlockStateManager.updateBlockContent` launches each write
 * on its own coroutine, so two quick commits could otherwise land out of order and persist the older text.
 * [write] must return only after the write finished (join the manager's job). Close with [close]; queued commits still run.
 */
internal class TableCommitQueue(
    scope: CoroutineScope,
    private val write: suspend (BlockUuid, String, Long) -> Unit,
) {
    private class Commit(val uuid: BlockUuid, val text: String, val version: Long)

    private val queue = Channel<Commit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (commit in queue) write(commit.uuid, commit.text, commit.version)
        }
    }

    fun submit(uuid: BlockUuid, text: String, version: Long) {
        queue.trySend(Commit(uuid, text, version))
    }

    fun close() {
        queue.close()
    }
}
