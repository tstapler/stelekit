// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.misplaced

import dev.stapler.stelekit.db.ProposedResolution
import dev.stapler.stelekit.db.StrayResult
import dev.stapler.stelekit.db.misplaced.MisplacedFile
import dev.stapler.stelekit.db.misplaced.MisplacedFilesService
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One misplaced file with what moving it would do; [proposal] is null until it has been worked out. */
data class MisplacedItem(val file: MisplacedFile, val proposal: ProposedResolution?) {
    val movable: Boolean get() = proposal != null && proposal != ProposedResolution.Missing && proposal !is ProposedResolution.CannotMerge

    /** True when automatic merging is refused but the user can still append the stray blocks to the notes page. */
    val appendable: Boolean get() = (proposal as? ProposedResolution.CannotMerge)?.canAppend == true
}

data class MisplacedFilesUiState(
    val items: List<MisplacedItem> = emptyList(),
    val dialogVisible: Boolean = false,
    val busy: Boolean = false,
    /** What the last move did, shown until dismissed. */
    val summary: String? = null,
)

/**
 * Keeps track of the journals and pages found outside the notes folder and carries out what the user chooses.
 * Owns its own scope and state (it must outlive any one composable, so it never takes a composition scope).
 * Nothing moves until [moveAll] or [move] is called.
 */
class MisplacedFilesController(
    private val service: MisplacedFilesService,
    private val repoRoot: () -> RepoRoot?,
    private val notesPath: () -> EffectiveNotesPath?,
    /** Called after files moved, so the graph reloads and shows the merged pages. */
    private val onFilesChanged: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(MisplacedFilesUiState())
    val state: StateFlow<MisplacedFilesUiState> = _state.asStateFlow()

    fun close() = scope.cancel()

    /** Looks for misplaced files again; call when the graph opens and after anything that could create one. */
    fun refresh() {
        scope.launch {
            val (root, notes) = locations() ?: return@launch _state.update { it.copy(items = emptyList()) }
            val files = service.scan(root, notes)
            _state.update { current ->
                val known = current.items.associate { it.file.relativePath to it.proposal }
                current.copy(items = files.map { MisplacedItem(it, known[it.relativePath]) })
            }
        }
    }

    fun openDialog() {
        _state.update { it.copy(dialogVisible = true) }
        scope.launch {
            val (root, notes) = locations() ?: return@launch
            // Previews read both copies, so they are filled in one at a time rather than blocking the dialog.
            _state.value.items.filter { it.proposal == null }.forEach { item ->
                val proposal = service.preview(root, notes, item.file)
                _state.update { current ->
                    current.copy(items = current.items.map { if (it.file == item.file) it.copy(proposal = proposal) else it })
                }
            }
        }
    }

    fun closeDialog() = _state.update { it.copy(dialogVisible = false) }

    fun dismissSummary() = _state.update { it.copy(summary = null) }

    fun moveAll() = move(_state.value.items.filter { it.movable }.map { it.file })

    /** Appends [file]'s blocks to the end of the notes copy when automatic merging refused it; both originals are backed up. */
    fun mergeAnyway(file: MisplacedFile) = move(listOf(file), appendOnRefusal = true)

    fun move(files: List<MisplacedFile>, appendOnRefusal: Boolean = false) {
        if (files.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, summary = null) }
        scope.launch {
            try {
                val (root, notes) = locations() ?: return@launch
                val results = service.moveAll(root, notes, files, appendOnRefusal)
                _state.update { it.copy(summary = summarize(results.map { (_, result) -> result })) }
                if (results.any { (_, result) -> result is StrayResult.Done }) onFilesChanged()
            } finally {
                _state.update { it.copy(busy = false) }
                refresh()
            }
        }
    }

    fun ignoreAll() = ignore(_state.value.items.map { it.file })

    fun ignore(files: List<MisplacedFile>) {
        if (files.isEmpty()) return
        scope.launch {
            service.ignore(files)
            refresh()
        }
    }

    private fun locations(): Pair<RepoRoot, EffectiveNotesPath>? {
        val root = repoRoot() ?: return null
        val notes = notesPath() ?: return null
        return root to notes
    }

    private fun summarize(results: List<StrayResult>): String {
        val done = results.filterIsInstance<StrayResult.Done>()
        val merged = done.count { it.how == StrayResult.How.MERGED }
        val moved = done.count { it.how == StrayResult.How.MOVED }
        val duplicates = done.count { it.how == StrayResult.How.DEDUPED }
        val blocks = done.sumOf { it.blocksAdded }
        val left = results.size - done.size
        val parts = buildList {
            if (merged > 0) add("Merged $merged ${plural(merged, "page")} ($blocks ${plural(blocks, "block")} added)")
            if (moved > 0) add("Moved $moved ${plural(moved, "page")}")
            if (duplicates > 0) add("Removed $duplicates ${plural(duplicates, "duplicate")}")
            if (left > 0) add("$left could not be moved and ${if (left == 1) "was" else "were"} left where they were")
        }
        val backups = if (merged > 0) " Originals are backed up in .stelekit/share-path-repair-backup." else ""
        return parts.joinToString(". ").ifEmpty { "Nothing was moved." } + "." + backups
    }

    private fun plural(count: Int, word: String) = if (count == 1) word else "${word}s"
}
