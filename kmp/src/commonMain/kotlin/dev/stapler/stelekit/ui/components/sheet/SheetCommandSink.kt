// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridSelection

/**
 * What the grid needs from the host session; [TableSheetSession] implements it and tests fake it, so `SheetState`
 * never touches the block manager.
 */
internal interface SheetHostPort {
    val readOnly: Boolean

    /** Writes the whole serialized table; false when nothing was issued (read-only, or a held conflict). */
    fun commit(text: String, label: String, draft: DraftText? = null): Boolean
    fun undo()
    fun redo()

    /** Commit any draft, flush and close: the same path as Back, (x) and Ctrl+Shift+Enter. */
    fun closeSheet()
    fun editAsMarkdown()
}

/**
 * Where the grid sends commands it does not own: clipboard, structural operations, sort and menus belong to
 * later PR-C workers. The default handles nothing, so the keys reach the sink and the tests assert dispatch.
 */
internal interface SheetCommandSink {
    /** True when the sink acted on [command]; the grid consumes the key either way. */
    fun onCommand(command: GridCommand, selection: GridSelection): Boolean = false

    /** The entries of the menu for [target]; null (the default) shows no menu. */
    fun menuFor(target: SheetMenuTarget): SheetMenuModel? = null
}

internal object NoOpSheetCommandSink : SheetCommandSink
