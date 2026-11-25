// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.logging.Logger
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.stapler.stelekit.table.EntryProfile
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.TableClipboard
import dev.stapler.stelekit.ui.PlatformBackHandler
import dev.stapler.stelekit.ui.components.LocalTableClipboard
import dev.stapler.stelekit.ui.components.LocalTableUndoHost
import dev.stapler.stelekit.ui.rememberClipboardProvider
import kotlinx.coroutines.launch
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.ColumnAlign
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableStripPolicy
import dev.stapler.stelekit.ui.components.BREAKABLE_CELL
import dev.stapler.stelekit.ui.components.LocalTablePreferences
import dev.stapler.stelekit.ui.components.LocalTableSheetOwner
import dev.stapler.stelekit.ui.components.TableColumnAlignment
import dev.stapler.stelekit.ui.components.TableData
import dev.stapler.stelekit.ui.components.TableDensity
import dev.stapler.stelekit.ui.components.TableDisplayPrefs
import dev.stapler.stelekit.ui.components.TableGrid
import dev.stapler.stelekit.ui.components.TableMessages
import dev.stapler.stelekit.ui.components.UNBREAKABLE_CELL
import dev.stapler.stelekit.ui.components.WikiLinkText
import dev.stapler.stelekit.ui.components.captureMaxWidth
import dev.stapler.stelekit.ui.components.effectiveAlignments
import kotlinx.coroutines.CancellationException

/** Cells composed inline before the rest waits for the sheet; distinct from the 10 000 paste cap and 20 000 formula cap. */
internal const val INLINE_CELL_WINDOW = 5_000

private val sheetLogger = Logger("TableSheet")

/** Body rows plus header that fit [windowCells] (at least the header), never more than [rowCount]. */
internal fun inlineRowWindow(rowCount: Int, columnCount: Int, windowCells: Int): Int =
    (windowCells / columnCount.coerceAtLeast(1)).coerceAtLeast(1).coerceAtMost(rowCount)

/** Flag-on table: tap edits a cell in place. The flag-off path stays in `TableBlock`. */
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun InlineTableBlock(
    content: String,
    onStartEditing: () -> Unit,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPressSelect: (() -> Unit)? = null,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    onLinkClick: (String) -> Unit = {},
    prefs: TableDisplayPrefs = LocalTablePreferences.current.prefs,
    blockUuid: BlockUuid? = null,
    onOpenSheet: (() -> Unit)? = null,
    inBlockSelectionMode: Boolean = false,
    cellWindow: Int = INLINE_CELL_WINDOW,
    accessibilityServiceActive: Boolean = false,
    landscapeOverride: Boolean? = null,
) {
    // One holder per state: a state replaced for another block keeps committing to its own block on dispose.
    val commitHolder = remember(blockUuid) { mutableStateOf(onCommit) }.also { it.value = onCommit }
    val state = remember(blockUuid, cellWindow) { InlineTableState(content, cellWindow = cellWindow) { commitHolder.value(it) } }
    val activation = LocalTableActivation.current ?: remember { TableActivation() }
    val isActive = state.selection != null
    LaunchedEffect(isActive, activation) { if (isActive) activation.activate(state) }
    DisposableEffect(state, activation) {
        onDispose {
            // Leaving composition (navigation, collapse) must not lose a typed draft.
            state.commitDraft()
            activation.release(state)
        }
    }
    val frozen = blockUuid != null && LocalTableSheetOwner.current == blockUuid
    val selectionMode = isInSelectionMode || inBlockSelectionMode
    val undoHost = LocalTableUndoHost.current
    state.undoHost = undoHost

    LaunchedEffect(content) { state.onContent(content) }
    LaunchedEffect(frozen) { if (frozen) state.freeze() else if (state.isFrozen) state.reload(content) }
    LaunchedEffect(selectionMode) { if (selectionMode) state.commitAndClear() }
    val recordCount = undoHost?.recordCount?.collectAsState()?.value
    undoHost?.canUndo?.collectAsState()
    undoHost?.canRedo?.collectAsState()
    LaunchedEffect(recordCount) { if (recordCount != null) state.onUndoRecordCount(recordCount) }
    // UNVERIFIED on device: an inline draft committed on ON_STOP (config change, app backgrounded); no Robolectric lifecycle harness here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.commitDraft() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val failure = state.failure
    LaunchedEffect(blockUuid, failure) {
        if (failure != null) sheetLogger.warn("table not editable as a grid: $failure")
    }
    if (failure != null) {
        Column(modifier) {
            Text(TableMessages.cannotEditAsGrid(failure), color = MaterialTheme.colorScheme.onSurface)
            ActionButton(TableMessages.EDIT_AS_MARKDOWN, onStartEditing)
        }
        return
    }
    val doc = state.doc ?: return

    val uriHandler = LocalUriHandler.current
    val onUrlClick: (String) -> Unit = { url ->
        try {
            if (isSafeCellUrl(url)) uriHandler.openUri(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignore a URL the platform cannot open, like BlockViewer.
        }
    }
    val calcMemo = remember { CalcMemo() }
    val calc = remember(doc) { calcMemo.of(doc) }
    val shownRows = inlineRowWindow(doc.rowCount, doc.columnCount, cellWindow)
    val alignments = remember(doc, prefs.numericRightAlign, shownRows) { doc.displayData(shownRows).effectiveAlignments(prefs.numericRightAlign) }
    val hits = remember { CellHitRegistry() }
    val availableWidth = remember { mutableIntStateOf(0) }
    val colors = TableColors(
        border = MaterialTheme.colorScheme.outlineVariant,
        divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        header = MaterialTheme.colorScheme.surfaceVariant,
        zebra = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    )
    val outline = outlineColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface)

    val sheetOpen = onOpenSheet.takeIf { prefs.sheetEnabled }
    val recoveredStore = LocalRecoveredDraftStore.current
    val clipboard = LocalTableClipboard.current ?: defaultTableClipboard()
    val copyScope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val hints = LocalTablePreferences.current.hints
    SideEffect {
        state.hooks.openSheet = sheetOpen
        state.hooks.editAsMarkdown = onStartEditing
        state.hooks.recoverDraft = recoveredStore?.let { store -> blockUuid?.let { id -> { d -> store.add(RecoveredDraft(id, d)) } } }
        state.hooks.bindCellDisplay(calcMemo)
        state.hooks.copyToClipboard = { text, done ->
            copyScope.launch {
                try {
                    clipboard.copy(text, null)
                    done(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sheetLogger.warn("table copy failed: ${e.message}")
                    done(false)
                }
            }
        }
    }

    val navFocus = remember { FocusRequester() }
    val anchor = state.selection?.anchor
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val currentIme by rememberUpdatedState(imeBottom)
    val compact = landscapeOverride ?: isCompactLandscape()
    var tipActive by remember { mutableStateOf(false) }
    val tipDue = state.stripVisible && state.entryProfile == EntryProfile.Touch && !hints.isSeen(TableHint.H1)
    LaunchedEffect(tipDue) {
        if (tipDue) {
            tipActive = true
            hints.markSeen(TableHint.H1)
        }
    }
    SideEffect { state.hooks.onStripAction = { tipActive = false } }
    PlatformBackHandler(enabled = state.backEnabled) {
        state.onBack(LadderKey.Back, imeVisible = currentIme > 0) { keyboard?.hide() }
    }

    Column(modifier) {
        if (state.conflictHeld) {
            if (state.conflictBannerOpen) {
                TableConflictBanner(
                    draftAddress = state.draft?.address?.toString(),
                    onKeepMine = state::keepMine,
                    onTakeTheirs = state::takeTheirs,
                    onEditAsMarkdown = state::editAsMarkdown,
                )
            } else {
                ResolveChip(onClick = state::reopenBanner)
            }
        }
        doc.prefix.filter { it.isNotBlank() }.forEach { PlainLine(it, onStartEditing) }
        Box(
            modifier = Modifier
                .captureMaxWidth(availableWidth)
                .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val chord = navigateChord(event.key, event.isCtrlPressed || event.isMetaPressed, event.isShiftPressed, event.isAltPressed)
                    chord != null && state.handleNavigateKey(chord)
                }
                .tableGestureOwner(
                    hits = hits,
                    mode = when {
                        selectionMode -> GestureMode.BlockSelection
                        frozen -> GestureMode.ReadOnly
                        else -> GestureMode.Interactive
                    },
                    editingCell = { state.editingAddress },
                    onEditCell = { cell, type ->
                        state.startEdit(cell, TableStripPolicy.profileAtEntry(isTouchLikePointer(type), accessibilityServiceActive))
                    },
                    onLink = { link ->
                        when (link) {
                            is CellLink.Page -> onLinkClick(link.name)
                            is CellLink.Url -> onUrlClick(link.url)
                        }
                    },
                    onLongPressSelect = onLongPressSelect,
                    onToggleSelect = onToggleSelect,
                    onRangeDrag = state::dragSelect,
                    onShiftClick = { cell -> (state.selection != null).also { if (it) state.extendSelectionTo(cell) } },
                )
                .semantics { collectionInfo = CollectionInfo(rowCount = doc.rowCount, columnCount = doc.columnCount) },
        ) {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                TableGrid(columnCount = doc.columnCount, availableWidth = { availableWidth.intValue }, overflow = prefs.overflow) {
                    for (row in 1..shownRows) {
                        for (col in 1..doc.columnCount) {
                            val address = CellAddress(row, col)
                            InlineCell(
                                state = state,
                                hits = hits,
                                address = address,
                                display = cellDisplay(doc, calc, address),
                                alignment = alignments[col - 1],
                                colors = colors,
                                lastColumn = col == doc.columnCount,
                                lastRow = row == doc.rowCount,
                                density = prefs.density,
                                linkColor = linkColor,
                                outline = outline,
                                navFocus = navFocus.takeIf { anchor == address && state.mode == GridMode.Navigate && !frozen },
                                sheetEnabled = sheetOpen != null,
                            )
                        }
                    }
                }
            }
        }
        if (shownRows < doc.rowCount) {
            RowWindowNote(
                shown = shownRows,
                total = doc.rowCount,
                onMaximize = sheetOpen?.let { { state.maximize() } },
                onEditAsMarkdown = { state.editAsMarkdown() },
            )
        } else if (sheetOpen != null && !state.stripVisible && !frozen) {
            ActionButton(TableMessages.MAXIMIZE) { state.maximize() }
        }
        doc.suffix.filter { it.isNotBlank() }.forEach { PlainLine(it, onStartEditing) }
    }

    val notice = state.notices.visible
    if (state.stripVisible || notice != null) {
        val hasWhy = anchor != null && state.hooks.errorDetail?.invoke(doc, anchor) != null
        WindowBottomOverlay(imeBottom = { currentIme }) {
            TableOverlayStack(
                notice = notice,
                moreCount = state.notices.moreCount,
                hint = if (tipActive && state.stripVisible) ({ TableHintChip(TableHint.H1.text) { tipActive = false } }) else null,
                compact = compact,
                onNoticeAction = state.notices::activate,
                onNoticeDismiss = state.notices::dismiss,
                picker = if (state.openMenu != null) ({ TableStripPicker(state, sheetOpen != null, hasWhy) }) else null,
                strip = if (state.stripVisible) ({ TableActionStrip(state, sheetOpen != null, hasWhy, compact) }) else null,
            )
        }
    }
}

/** Landscape on a short window: hints go, notices shrink to a line and the strip collapses (ux.md rule 13). */
@Composable
private fun isCompactLandscape(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    val heightDp = with(LocalDensity.current) { size.height.toDp() }
    return size.width > size.height && heightDp < COMPACT_HEIGHT
}

private val COMPACT_HEIGHT = 480.dp

@Composable
private fun defaultTableClipboard(): TableClipboard {
    val manager = LocalClipboardManager.current
    val provider = rememberClipboardProvider(manager)
    return remember(manager, provider) { ComposeTableClipboard(manager, provider) }
}

private class TableColors(val border: Color, val divider: Color, val header: Color, val zebra: Color)

@Composable
private fun PlainLine(text: String, onClick: () -> Unit) {
    WikiLinkText(
        text = text,
        textColor = MaterialTheme.colorScheme.onBackground,
        linkColor = MaterialTheme.colorScheme.primary,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
private fun RowWindowNote(shown: Int, total: Int, onMaximize: (() -> Unit)?, onEditAsMarkdown: () -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(TableMessages.showingFirstRows(shown, total), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            if (onMaximize != null) ActionButton(TableMessages.MAXIMIZE, onMaximize)
            ActionButton(TableMessages.EDIT_AS_MARKDOWN, onEditAsMarkdown)
        }
    }
}

/** Display values for alignment and numeric-column detection over the first [shownRows] rows; cell text is the unescaped doc text. */
internal fun TableDoc.displayData(shownRows: Int = rowCount): TableData {
    val cols = columnCount
    fun rowTexts(row: Int) = List(cols) { c -> cell(CellAddress(row, c + 1))?.value ?: "" }
    val explicit = List(cols) { c ->
        when (delimiter.aligns.getOrNull(c)) {
            ColumnAlign.LEFT -> TableColumnAlignment.LEFT
            ColumnAlign.CENTER -> TableColumnAlignment.CENTER
            ColumnAlign.RIGHT -> TableColumnAlignment.RIGHT
            else -> null
        }
    }
    return TableData(
        headers = rowTexts(1),
        alignments = explicit.map { it ?: TableColumnAlignment.LEFT },
        rows = (2..shownRows.coerceAtMost(rowCount)).map(::rowTexts),
        explicitAlignments = explicit,
    )
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun InlineCell(
    state: InlineTableState,
    hits: CellHitRegistry,
    address: CellAddress,
    display: CellDisplay,
    alignment: TableColumnAlignment,
    colors: TableColors,
    lastColumn: Boolean,
    lastRow: Boolean,
    density: TableDensity,
    linkColor: Color,
    outline: Color,
    navFocus: FocusRequester?,
    sheetEnabled: Boolean,
) {
    val isHeader = address.row == 1
    val editing = state.editingAddress == address
    val selected = state.selection?.contains(address) == true
    val edges = if (editing) OutlineEdges.None else outlineEdges(state.selection, address)
    val textAlign = when (alignment) {
        TableColumnAlignment.LEFT -> TextAlign.Start
        TableColumnAlignment.CENTER -> TextAlign.Center
        TableColumnAlignment.RIGHT -> TextAlign.End
    }
    val (horizontal, vertical) = when (density) {
        TableDensity.COMPACT -> 6.dp to 0.dp
        TableDensity.COMFORTABLE -> 10.dp to 4.dp
    }
    val background = when {
        isHeader -> colors.header
        address.row % 2 == 0 -> colors.zebra
        else -> Color.Transparent
    }
    val rightRule = if (!lastColumn) colors.divider else null
    val bottomRule = if (!lastRow) (if (isHeader) colors.border else colors.divider) else null
    DisposableEffect(address) { onDispose { hits.remove(address) } }
    if (navFocus != null) {
        LaunchedEffect(state.focusToken) {
            try {
                navFocus.requestFocus()
            } catch (_: IllegalStateException) {
                // The cell left composition before focus could land; the next selection re-requests it.
            }
        }
    }
    Box(
        modifier = Modifier
            .drawBehind {
                drawRect(background)
                if (rightRule != null) {
                    val x = if (layoutDirection == LayoutDirection.Rtl) 0.5f else size.width - 0.5f
                    drawLine(rightRule, Offset(x, 0f), Offset(x, size.height), 1f)
                }
                if (bottomRule != null) {
                    drawLine(bottomRule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                }
            }
            .selectionOutline(edges, outline)
            .onGloballyPositioned { hits.setCell(address, it) }
            .then(if (navFocus != null) Modifier.focusRequester(navFocus).focusable() else Modifier)
            .padding(horizontal = horizontal, vertical = vertical)
            .layoutId(if (!editing && display.text.none { it.isWhitespace() }) UNBREAKABLE_CELL else BREAKABLE_CELL)
            .semantics(mergeDescendants = !editing) {
                collectionItemInfo = CollectionItemInfo(rowIndex = address.row - 1, rowSpan = 1, columnIndex = address.col - 1, columnSpan = 1)
                markSelected(selected)
                when (display) {
                    is CellDisplay.Formula -> stateDescription = display.formula
                    is CellDisplay.Failed -> {
                        stateDescription = display.formula
                        error(display.detail)
                    }
                    is CellDisplay.InvalidFormula -> error(display.detail)
                    is CellDisplay.Plain -> Unit
                }
                val announced = state.announcement
                if (editing && announced != null) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announced
                }
                // A semantic click (TalkBack, Switch Access) has no pointer: enter as touch so the strip is reachable.
                if (!editing) onClick(label = TableMessages.EDIT_CELL) { state.startEdit(address, EntryProfile.Touch); true }
                customActions = cellCustomActions(state, address, sheetEnabled, editing)
            },
    ) {
        val textColor = if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        if (editing) {
            CellEditor(
                state = state,
                textAlign = textAlign,
                textColor = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        } else {
            CellContent(display, address, hits, textColor, linkColor, textAlign, if (isHeader) FontWeight.Bold else null)
        }
    }
}

/** TalkBack and Switch Access path to everything the strip does (ux.md 3.6b); each acts on this cell. */
private fun cellCustomActions(state: InlineTableState, address: CellAddress, sheetEnabled: Boolean, editing: Boolean): List<CustomAccessibilityAction> {
    fun onCell(label: String, run: () -> Unit) = CustomAccessibilityAction(label) {
        run()
        true
    }
    fun select() = state.dragSelect(address, address)
    return buildList {
        if (editing) {
            add(onCell(TableMessages.NEXT_COLUMN) { state.perform(StripAction.NextColumn) })
            add(onCell(TableMessages.LINE_BREAK) { state.perform(StripAction.LineBreak) })
            return@buildList
        }
        add(onCell(TableMessages.SELECT_ROW) { select(); state.perform(StripAction.SelectRow) })
        add(onCell(TableMessages.SELECT_COLUMN) { select(); state.perform(StripAction.SelectColumn) })
        add(onCell(TableMessages.SELECT_TABLE) { select(); state.perform(StripAction.SelectTable) })
        ExtendChoice.entries.forEach { choice ->
            add(onCell(TableMessages.extendLabel(choice.label.lowercase())) { select(); state.performExtend(choice) })
        }
        add(onCell(TableMessages.COPY) { select(); state.copySelection() })
        add(onCell(TableMessages.UNDO) { state.perform(StripAction.Undo) })
        add(onCell(TableMessages.REDO) { state.perform(StripAction.Redo) })
        if (sheetEnabled) add(onCell(TableMessages.MAXIMIZE) { state.maximize() })
        add(onCell(TableMessages.EDIT_AS_MARKDOWN) { state.editAsMarkdown() })
    }
}

@Composable
private fun CellContent(
    display: CellDisplay,
    address: CellAddress,
    hits: CellHitRegistry,
    textColor: Color,
    linkColor: Color,
    textAlign: TextAlign,
    fontWeight: FontWeight?,
) {
    val marker = when (display) {
        is CellDisplay.Formula -> "fx"
        is CellDisplay.InvalidFormula -> "!"
        else -> null
    }
    val color = if (display is CellDisplay.Failed) MaterialTheme.colorScheme.error else textColor
    Box {
        // An error token like `#DIV/0!` is not user text: parsed as markup it reads as a #tag link and steals the tap.
        if (display is CellDisplay.Failed) {
            Text(
                text = display.text,
                color = color,
                textAlign = textAlign,
                fontWeight = fontWeight,
                modifier = Modifier.fillMaxWidth().padding(end = if (marker != null) 16.dp else 0.dp),
            )
        } else WikiLinkText(
            text = display.text,
            textColor = color,
            linkColor = linkColor,
            textAlign = textAlign,
            fontWeight = fontWeight,
            onHitInfo = { text, layout, coords -> hits.setText(address, text, layout, coords) },
            modifier = Modifier.fillMaxWidth().padding(end = if (marker != null) 16.dp else 0.dp),
        )
        if (marker != null) {
            Text(
                text = marker,
                fontSize = 9.sp,
                color = if (display is CellDisplay.InvalidFormula) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}
