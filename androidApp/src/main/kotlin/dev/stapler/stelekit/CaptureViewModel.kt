// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val _captureText = MutableStateFlow("")
    val captureText: StateFlow<String> = _captureText.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    sealed class SaveState {
        data object Idle : SaveState()
        data object Saving : SaveState()
        data object Saved : SaveState()
        data class Error(val throwable: Throwable?) : SaveState()
    }

    fun updateText(text: String) {
        _captureText.value = text
    }

    /** Sets the initial text only if the field is still empty (idempotent for singleTop re-launch). */
    fun initializeText(text: String) {
        if (_captureText.value.isEmpty() && text.isNotEmpty()) {
            _captureText.value = text
        }
    }

    fun save() {
        val text = _captureText.value.trim()
        if (text.isEmpty()) return

        val steleApp = getApplication<SteleKitApplication>()
        val graphManager = steleApp.graphManager ?: run {
            _saveState.value = SaveState.Error(IllegalStateException("No graph configured"))
            return
        }

        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            val result = performSave(graphManager, steleApp.fileSystem, text)
            _saveState.value = if (result.isSuccess) SaveState.Saved
                                else SaveState.Error(result.exceptionOrNull())
        }
    }

    private suspend fun performSave(
        graphManager: GraphManager,
        fileSystem: PlatformFileSystem,
        text: String,
    ): Result<Unit> = runCatching {
        val repoSet = graphManager.getActiveRepositorySet()
            ?: error("No active graph — open SteleKit to set up your graph")

        val page = repoSet.journalService.ensureTodayJournal()
        val graphPath = graphManager.getActiveGraphInfo()?.path
            ?: error("No active graph path")

        val existingBlocks = repoSet.blockRepository
            .getBlocksForPage(page.uuid)
            .first()
            .getOrThrow()

        val now = Clock.System.now()
        val newBlock = Block(
            uuid = UuidGenerator.generateV7(),
            pageUuid = page.uuid,
            content = text,
            position = (existingBlocks.maxOfOrNull { it.position } ?: -1) + 1,
            createdAt = now,
            updatedAt = now,
        )

        // Bug 1 mitigation: catch ClosedSendChannelException from a graph-switch race
        val writeActor = repoSet.writeActor
        if (writeActor != null) {
            try {
                writeActor.saveBlock(newBlock).getOrThrow()
            } catch (e: ClosedSendChannelException) {
                throw IllegalStateException("Graph switched during save — please retry", e)
            }
        } else {
            @OptIn(DirectRepositoryWrite::class)
            repoSet.blockRepository.saveBlock(newBlock).getOrThrow()
        }

        // Bug 8 mitigation: flush the Markdown file after every actor write.
        // Pass writeActor so GraphWriter can persist filePath for newly created journal pages.
        val writer = GraphWriter(fileSystem, writeActor = repoSet.writeActor)
        writer.startAutoSave(viewModelScope)
        writer.savePage(page, existingBlocks + newBlock, graphPath)
    }
}
