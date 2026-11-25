// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.CoroutineContext

/**
 * The shell's one handle on the sheet: which block is open and how to open one. The shell provides
 * [openAction] as `LocalOpenTableSheetAction` (null while the flag is off, so no Maximize button exists) and
 * [owner] as `LocalTableSheetOwner` (the block's inline table is inert while it is set).
 */
internal class TableSheetController(
    /** The manager's in-memory content (it can be ahead of the stored row while a write is queued); null when unknown. */
    private val liveContent: (BlockUuid) -> String? = { null },
    // Owns its scope: this object sits in `remember`, so a composition scope handed in would be cancelled under it.
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + PlatformDispatcher.Default +
            CoroutineExceptionHandler { _, t -> Logger("TableSheet").error("opening the sheet failed: ${t.message}", t) },
    ),
    private val loadBlock: suspend (BlockUuid) -> Block?,
) {
    // Taken synchronously by the caller's thread so two quick taps cannot both pass the `open` check.
    private val opening = Mutex()

    class Open(val uuid: BlockUuid, val content: String, val version: Long, val pendingApply: RecoveredDraft? = null)

    var open: Open? by mutableStateOf(null)
        private set

    val owner: BlockUuid? get() = open?.uuid

    /**
     * Dropped with the controller on a graph switch on purpose: a draft is keyed by block uuid and cell, and
     * applying it into another graph's table would write text into a table it never belonged to.
     */
    val recovered = RecoveredDraftStore()

    fun openSheet(uuid: BlockUuid, pendingApply: RecoveredDraft? = null) {
        if (open != null || !opening.tryLock()) return
        scope.launch {
            try {
                val block = loadBlock(uuid) ?: return@launch
                if (open == null) open = Open(uuid, liveContent(uuid) ?: block.content, block.version, pendingApply)
            } finally {
                opening.unlock()
            }
        }
    }

    fun closed() {
        open = null
    }

    /** Stops any open in flight; the controller is leaving composition. */
    fun dispose() {
        scope.cancel()
    }
}

/** The window's recovered drafts; provided by the shell so an inline conflict can hand its draft over (null: none kept). */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell, like LocalTableActivation; tables sit far below it
internal val LocalRecoveredDraftStore = androidx.compose.runtime.staticCompositionLocalOf<RecoveredDraftStore?> { null }

/** True when [draft]'s cell exists in [content]; Apply is disabled with a reason otherwise. */
internal fun recoveredCellExists(content: String?, draft: DraftText): Boolean =
    content != null && (TableParser.parse(content) as? TableParseResult.Parsed)?.doc?.cell(draft.address) != null

/**
 * Renders the open sheet, or the recovered-draft slot when no sheet is open. Composed once by the shell, inside the
 * providers (window size class), so flags off means this is never reached with an open block.
 */
@Suppress("LongParameterList") // the shell's collaborators, each distinct
@Composable
internal fun TableSheetShell(
    controller: TableSheetController,
    sheetEnabled: Boolean,
    pageName: String?,
    graphKey: String?,
    port: TableManagerPort,
    feedFor: (BlockUuid) -> Flow<Block?>,
    context: CoroutineContext,
    flushScope: CoroutineScope,
    flush: suspend () -> Unit,
    showMessage: (String) -> Unit,
    editAsMarkdown: (BlockUuid) -> Unit,
    onChangeLoss: (String) -> Unit = showMessage,
    isChangingConfiguration: () -> Boolean = { false },
    readOnly: Boolean = false,
    body: SheetBody = { PlaceholderSheetBody(it) },
) {
    val clipboard = LocalClipboardManager.current
    val open = controller.open
    var live: TableSheetSession? by remember { mutableStateOf(null) }
    val actions = remember(controller, port) {
        RecoveredDraftActions(
            onCopy = { clipboard.setText(AnnotatedString(it.draft.text)) },
            onApply = { item ->
                val session = live
                if (session != null && !session.closed && session.blockUuid == item.blockUuid) session.applyRecovered(item)
                else controller.openSheet(item.blockUuid, pendingApply = item)
            },
            canApply = { item ->
                val session = live
                if (session != null && !session.closed && session.blockUuid == item.blockUuid) session.canApply(item.draft)
                else recoveredCellExists(port.contentOf(item.blockUuid), item.draft)
            },
            onDiscard = { controller.recovered.discard(it) },
        )
    }
    if (open != null) {
        TableSheetHost(
            blockUuid = open.uuid,
            initialContent = open.content,
            title = pageName,
            feed = remember(open.uuid, graphKey) { feedFor(open.uuid) },
            port = port,
            context = context,
            flushScope = flushScope,
            flush = flush,
            recovered = controller.recovered,
            sheetEnabled = sheetEnabled,
            graphKey = graphKey,
            startVersion = open.version,
            readOnly = readOnly,
            isChangingConfiguration = isChangingConfiguration,
            recoveredActions = actions,
            onChangeLoss = onChangeLoss,
            body = body,
            onSessionReady = { session ->
                live = session
                open.pendingApply?.let { session.applyRecovered(it) }
            },
            onClose = { reason ->
                live = null
                controller.closed()
                reason.message?.let(showMessage)
                if (reason == SheetCloseReason.EditAsMarkdown) editAsMarkdown(open.uuid)
            },
        )
    } else {
        controller.recovered.pinned?.let { item ->
            Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false)) {
                RecoveredSlot(item, actions)
            }
        }
    }
}
