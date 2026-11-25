// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import dev.stapler.stelekit.model.BlockUuid

/** Opens the spreadsheet view for a table block; null (default) hides every Maximize entry. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell, like LocalAnnotateImageAction; threading it through BlockList/BlockRenderer would touch every page screen
val LocalOpenTableSheetAction = staticCompositionLocalOf<((BlockUuid) -> Unit)?> { null }

/**
 * Commits table text for a block through the state manager (block, new content, the caller's local version).
 * Null (default) makes the table fall back to `onContentChange`.
 */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell; the table sits below BlockItem, which has no manager handle
val LocalTableCommitAction = staticCompositionLocalOf<((BlockUuid, String, Long) -> Unit)?> { null }

/** The block currently owned by the sheet, or null; an inline table of that block is inert while it is set. */
@Suppress("CompositionLocalAllowlist") // set by the sheet host (a later task); read by tables far below it
val LocalTableSheetOwner = staticCompositionLocalOf<BlockUuid?> { null }

/**
 * The page's undo manager as the inline strip sees it. The flows let the strip dim Undo and Redo and notice an undo
 * entry it did not make itself (UX-85); the actions run the manager's own undo and redo.
 */
internal class TableUndoHost(
    val undo: () -> Unit,
    val redo: () -> Unit,
    val canUndo: kotlinx.coroutines.flow.StateFlow<Boolean>,
    val canRedo: kotlinx.coroutines.flow.StateFlow<Boolean>,
    val recordCount: kotlinx.coroutines.flow.StateFlow<Long>,
)

/** Null (default) leaves the strip's Undo and Redo dimmed; the shell provides the state manager's. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell; the table sits below BlockItem, which has no manager handle
internal val LocalTableUndoHost = staticCompositionLocalOf<TableUndoHost?> { null }

/** The table's copy port; null builds the default Compose-clipboard adapter at the table. */
@Suppress("CompositionLocalAllowlist") // test seam and future platform override; one value per window
internal val LocalTableClipboard = staticCompositionLocalOf<dev.stapler.stelekit.table.TableClipboard?> { null }

/** True when [content] parses as a table with a header and delimiter row (also matches Bullet-typed, Logseq-indented tables). */
fun isGridTableContent(content: String): Boolean =
    content.contains('|') && dev.stapler.stelekit.table.TableParser.parse(content) is dev.stapler.stelekit.table.TableParseResult.Parsed

/** Flag-gated: false without evaluating the parse when both table flags are off (ADR-010). */
internal fun TableDisplayPrefs.bulletMayBeTable(block: dev.stapler.stelekit.model.Block): Boolean =
    (inlineEditEnabled || sheetEnabled) &&
        block.blockType is dev.stapler.stelekit.model.BlockType.Bullet &&
        isGridTableContent(block.content)
