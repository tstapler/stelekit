// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetMerge
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The editor's side of saving: the saver's state for the top bar, the Retry, Keep mine and Use theirs buttons of the save banners,
 * and the flush a leaving editor asks for. With no saver (a read-only sheet) nothing is written and the state stays `Saved`.
 */
class EditorSaves(
    private val saver: SheetSaver?,
    private val scope: CoroutineScope,
    private val notifier: EditorNotifier,
    private val onReloaded: (SketchDocumentV2) -> Unit,
    /** Takes another device's changes that the saver merged into the file; it must end by calling [acknowledgeMerge]. */
    private val onMerged: (SheetMerge) -> Unit,
    /** The document the editor holds now, which "Keep mine" writes even when no edit is waiting. */
    private val current: () -> SketchDocumentV2,
) {
    val state: StateFlow<SaveState> = saver?.state ?: MutableStateFlow(SaveState.Saved)

    init {
        saver?.let { s -> scope.launch { s.merges.filterNotNull().collect { onMerged(it) } } }
    }

    /** The editor holds [merge] now and has submitted its scene: the saver stops rebasing saves onto it. */
    fun acknowledgeMerge(merge: SheetMerge) {
        saver?.let { s -> scope.launch { s.acknowledgeMerge(merge) } }
    }

    /** Hands the latest document to the saver. */
    fun submit(doc: SketchDocumentV2) {
        saver?.submit(doc)
    }

    /** Saves what the editor holds now (leaving the sheet, a hidden page) and returns where that left the file. */
    suspend fun flush(): SaveState = saver?.flush() ?: SaveState.Saved

    /** The primary button of a banner row (Retry, Keep mine); other rows only go away. */
    fun bannerPrimary(row: BannerRowId) {
        when (row) {
            BannerRowId.SaveFailed -> saver?.retry().also { notifier.refresh() }
            BannerRowId.SaveConflict -> saver?.let { scope.launch { it.keepMine(current()) } }.also { notifier.refresh() }
            else -> notifier.resolve(row)
        }
    }

    /** A More item of a banner row: "Use theirs" adopts the file on disk. */
    fun bannerMore(row: BannerRowId, label: String) {
        if (row == BannerRowId.SaveConflict && label == SketchEditorStrings.Banners.USE_THEIRS) {
            saver?.let { scope.launch { it.reload().onRight(onReloaded) } }
            notifier.refresh()
        } else {
            notifier.resolve(row)
        }
    }

    fun close() {
        saver?.close()
    }
}
