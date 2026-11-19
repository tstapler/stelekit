// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.clipboard.ClipboardPayload
import dev.stapler.stelekit.sketch.v2.clipboard.PasteRoute
import dev.stapler.stelekit.sketch.v2.clipboard.SceneClipboard
import dev.stapler.stelekit.sketch.v2.library.ElementLibrary
import dev.stapler.stelekit.sketch.v2.library.LibraryItem
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings

/** The system clipboard as text; the host replaces it from composition, where the platform clipboard is reachable. */
class ClipboardPort(val read: () -> String?, val write: (String) -> Unit)

/**
 * Copy, cut and paste of the selection (Story 3.5.3): our own payload round-trips every element field, Excalidraw JSON goes to
 * the importer through [pasteExcalidraw], anything else changes nothing. Every edit is one undoable step on [controller].
 */
class EditorClipboard(
    private val controller: SketchEditorController,
    private val limits: SceneLimits,
    private val pasteExcalidraw: (String) -> Unit,
) {
    /** Replaced by the host from composition; the default reads and writes nothing. */
    var port: ClipboardPort = ClipboardPort({ null }, {})

    private var pasteIndex = 0

    fun copy() {
        if (writeSelection()) controller.notifier.toast(SketchHostStrings.Clipboard.COPIED_KEY, SketchHostStrings.Clipboard.COPIED)
    }

    fun cut() {
        if (!writeSelection()) return
        controller.keys.run(KeyAction.Delete)
        controller.notifier.toast(SketchHostStrings.Clipboard.CUT_KEY, SketchHostStrings.Clipboard.CUT)
    }

    fun paste() {
        val text = port.read().orEmpty()
        when (ClipboardPayload.route(text)) {
            PasteRoute.SceneInternal -> ClipboardPayload.fromText(text, limits)?.let { payload ->
                controller.submit(SceneClipboard.paste(payload, pasteIndex++, controller.ids))
            } ?: nothingToPaste()
            PasteRoute.ExcalidrawJson -> pasteExcalidraw(text)
            PasteRoute.Unknown -> nothingToPaste()
        }
    }

    /** Puts the selection on the clipboard; false (with a toast) when nothing is selected. */
    private fun writeSelection(): Boolean {
        val ids = controller.ui.selection.map(::ElementId)
        if (ids.isEmpty()) {
            controller.notifier.toast(SketchHostStrings.Clipboard.NOTHING_TO_COPY_KEY, SketchHostStrings.Clipboard.NOTHING_TO_COPY)
            return false
        }
        val text = SceneClipboard.copy(controller.doc.scene, ids).toText(limits).getOrNull() ?: return false
        port.write(text)
        pasteIndex = 0
        return true
    }

    private fun nothingToPaste() = controller.notifier.toast(SketchHostStrings.Clipboard.NOTHING_TO_PASTE_KEY, SketchHostStrings.Clipboard.NOTHING_TO_PASTE)
}

/**
 * The Library tab's state (Story 3.5.6): saved selections that insert as fresh copies. Bounded by the library caps; a refused save
 * shows the exact message and changes nothing. Every change is written to the graph's library storage.
 */
class LibraryHost(
    private val controller: SketchEditorController,
    private val services: HostServices,
) {
    private var library: ElementLibrary by mutableStateOf(ElementLibrary.load(services.library, services.limits))

    val items: List<LibraryItem> get() = library.items

    fun saveSelection() {
        val ids = controller.ui.selection.map(::ElementId)
        val elements = SceneClipboard.copy(controller.doc.scene, ids).elements
        val id = controller.ids.next().value
        library.add(id, SketchHostStrings.Library.defaultName(library.items.size + 1), elements, controller.notifier.now()).fold(
            { error -> controller.notifier.toast(SketchHostStrings.Library.REFUSED_KEY, error.message) },
            { next ->
                library = next
                next.save(services.library)
                controller.notifier.toast(SketchHostStrings.Library.SAVED_KEY, SketchHostStrings.Library.SAVED)
            },
        )
    }

    fun insert(itemId: String) {
        val command = library.insert(itemId, controller.ids, INSERT_OFFSET_UNITS, INSERT_OFFSET_UNITS) ?: return
        controller.submit(command)
        controller.notifier.toast(SketchHostStrings.Library.INSERTED_KEY, SketchHostStrings.Library.INSERTED)
    }

    fun delete(itemId: String) {
        library = library.delete(itemId).also { it.save(services.library) }
    }

    private companion object {
        const val INSERT_OFFSET_UNITS = 24.0
    }
}
