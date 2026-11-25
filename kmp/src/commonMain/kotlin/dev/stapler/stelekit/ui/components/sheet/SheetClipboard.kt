// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellRange
import dev.stapler.stelekit.table.EditSummary
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.NoticeType
import dev.stapler.stelekit.table.RefusalReason
import dev.stapler.stelekit.table.SelectionStats
import dev.stapler.stelekit.table.TabularParse
import dev.stapler.stelekit.table.TabularText
import dev.stapler.stelekit.table.TableClipboard
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.ui.components.TableMessages
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Where DOM paste events reach the common sheet (ADR-008): the Wasm entry point provides one, everything else leaves
 * [LocalPasteEventSource] null and the sheet uses the clipboard port and the "Paste here" box. Each registered
 * listener gets the plain text of every paste event until the returned handle is called. UNVERIFIED in a real
 * browser: whether Ctrl+V on the canvas fires a `paste` event at all is spike 0.3a/b; [SheetClipboard] therefore
 * accepts both routes and applies a paste that arrives by both exactly once.
 */
interface PasteEventSource {
    /** [onPaste] returns true when the sheet took the text; the source then cancels the browser's own paste. */
    fun register(onPaste: (String) -> Boolean): () -> Unit
}

@Suppress("CompositionLocalAllowlist") // provided once by the wasm entry point; null everywhere else
val LocalPasteEventSource = staticCompositionLocalOf<PasteEventSource?> { null }

/**
 * The last in-app copy: its TSV of values and the raw cells (formula text kept). A paste whose text equals [tsv]
 * restores the raw cells, so a copy and paste inside SteleKit keeps formulas while other apps only ever see values.
 */
internal class TableClipboardSlot {
    private var tsv: String? = null
    private var raw: List<List<String>> = emptyList()

    fun remember(tsv: String, raw: List<List<String>>) {
        this.tsv = tsv
        this.raw = raw
    }

    /** The raw cells when [pasted] is exactly the slot's TSV (line endings compared as written by [TabularText]). */
    fun rawFor(pasted: String): List<List<String>>? = raw.takeIf { tsv != null && normalize(pasted) == normalize(tsv.orEmpty()) }

    private fun normalize(s: String) = s.replace("\r\n", "\n").replace('\r', '\n')
}

/** `<table>` of [grid] for the clipboard's rich-text flavour; cell text is HTML-escaped. */
internal fun tableHtml(grid: List<List<String>>): String = buildString {
    append("<table>")
    for (row in grid) {
        append("<tr>")
        for (cell in row) append("<td>").append(escapeHtml(cell)).append("</td>")
        append("</tr>")
    }
    append("</table>")
}

private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "<br>")

/** Both paste routes of one Ctrl+V land within this many milliseconds of each other. */
internal const val DUPLICATE_PASTE_WINDOW_MS = 750L

private fun monotonicNowMs(): () -> Long {
    val start = kotlin.time.TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

/** Pastes above this many cells parse off the main dispatcher (ADR-008). */
internal const val PASTE_OFF_THREAD_CELLS = 2_000

/** Cheap upper bound on the cells in [text] before parsing it: delimiters plus line breaks. */
internal fun estimatePasteCells(text: String): Int {
    var n = 1
    for (c in text) if (c == '\t' || c == ',' || c == '\n') n++
    return n
}

/**
 * The sheet's clipboard work: copy, cut, copy as formulas and paste (Story 5.4b). It owns its coroutine scope (never a
 * composition scope), built on the composition's own [context] so commits stay on the one context the host expects,
 * and hands a large parse to [parseContext]. UNVERIFIED on device: system clipboard timing and Wasm paste events.
 */
@Suppress("TooManyFunctions") // copy, cut, paste and the two fallback dialogs share one state holder
internal class SheetClipboard(
    private val state: SheetState,
    private val session: TableSheetSession,
    private val clipboard: TableClipboard,
    context: CoroutineContext,
    private val parseContext: CoroutineContext = PlatformDispatcher.Default,
    val slot: TableClipboardSlot = TableClipboardSlot(),
    private val nowMs: () -> Long = monotonicNowMs(),
) {
    private val log = Logger("SheetClipboard")
    private val scope = CoroutineScope(
        SupervisorJob() + context + CoroutineExceptionHandler { _, e -> log.error("clipboard action failed", e) },
    )

    /** The TSV a failed clipboard write leaves for manual copy ("Show as text"); null when no dialog is open. */
    var shownText: String? by mutableStateOf(null)
        private set

    /** True while the "Paste here" fallback box is open. */
    var pasteBoxOpen: Boolean by mutableStateOf(false)
        private set

    fun dispose() = scope.cancel()

    fun showAsText(text: String) {
        shownText = text
    }

    fun closeShownText() {
        shownText = null
    }

    fun openPasteBox() {
        pasteBoxOpen = true
    }

    fun closePasteBox() {
        pasteBoxOpen = false
    }

    // ---- copy and cut ------------------------------------------------------------------------------------------

    fun copy() = copyOrCut(ClipboardAction.Copy)

    /** Copy, then clear the selection as one undo step, only once the clipboard took the text. */
    fun cut() {
        if (state.readOnly) return state.announce(TableMessages.READ_ONLY)
        copyOrCut(ClipboardAction.Cut)
    }

    private enum class ClipboardAction { Copy, Cut }

    private fun copyOrCut(action: ClipboardAction) {
        val sel = state.selection
        val raw = grid(sel, state::rawText)
        val tsv = tsvOf(grid(sel, state::valueText))
        scope.launch {
            val ok = write(tsv, html = tableHtml(grid(sel, state::valueText)))
            if (!ok) return@launch
            slot.remember(tsv, raw)
            val range = sel.range
            if (action == ClipboardAction.Cut) state.attemptEdit(TableEdit.ClearRange(range), "cut ${rangeLabel(range)}")
            state.announce(TableMessages.copied(range.rows, range.cols))
            if (action == ClipboardAction.Copy) session.notices.info(TableMessages.copied(range.rows, range.cols))
        }
    }

    /** "Copy as formulas": the raw cell text as TSV; it never enters the slot, so a paste back is literal. */
    fun copyAsFormulas() {
        val sel = state.selection
        val tsv = tsvOf(grid(sel, state::rawText))
        scope.launch {
            if (write(tsv, html = null)) session.notices.info(TableMessages.copied(sel.range.rows, sel.range.cols))
        }
    }

    private suspend fun write(tsv: String, html: String?): Boolean = try {
        clipboard.copy(tsv, html)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log.warn("clipboard write failed: ${e.message}")
        session.notices.withAction(NoticeType.Action, TableMessages.COPY_FAILED_MANUAL, TableMessages.SHOW_AS_TEXT) { showAsText(tsv) }
        false
    }

    private fun grid(sel: GridSelection, textAt: (CellAddress) -> String): List<List<String>> {
        val r = sel.range
        return (r.topLeft.row..r.bottomRight.row).map { row -> (r.topLeft.col..r.bottomRight.col).map { col -> textAt(CellAddress(row, col)) } }
    }

    private fun tsvOf(grid: List<List<String>>): String {
        val single = grid.singleOrNull()?.singleOrNull()
        val plain = single != null && single.none { it == '\t' || it == '\n' || it == '\r' || it == '"' }
        return if (single != null && plain) single else TabularText.format(grid)
    }

    // ---- paste -------------------------------------------------------------------------------------------------

    /** Ctrl+V and the strip's Paste: reads the clipboard port; unreadable opens the fallback box, empty says so. */
    fun paste() {
        if (state.readOnly) return state.announce(TableMessages.READ_ONLY)
        // The window starts when the keystroke asked, not when a slow port answers: a DOM event in between is the same paste.
        val askedAt = nowMs()
        scope.launch {
            val text = try {
                clipboard.paste()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("clipboard read failed: ${e.message}")
                if (!eventPasteIsRecent(askedAt)) {
                    pasteBoxOpen = true
                    boxOpenedAtMs = nowMs()
                }
                return@launch
            }
            if (text.isNullOrEmpty()) {
                if (!eventPasteIsRecent(askedAt)) {
                    session.notices.withAction(NoticeType.Action, TableMessages.NOTHING_TO_PASTE, TableMessages.PASTE_HERE) { pasteBoxOpen = true }
                }
            } else {
                deliver(text, PasteOrigin.Port, askedAt)
            }
        }
    }

    /** Text from the "Paste here" box: one paste is one edit, one undo entry. */
    fun pasteText(text: String) {
        deliver(text, PasteOrigin.Box)
    }

    /**
     * Text from a DOM paste event. Taken only while the grid itself has the keys: in `Edit` or with the "Paste here"
     * box open the browser's paste belongs to the text field, so it is left alone (false).
     */
    fun pasteFromEvent(text: String): Boolean {
        if (state.mode != GridMode.Navigate || shownText != null) return false
        // The box a failed port read opened a moment ago belongs to this same keystroke; any other open box owns the paste.
        if (pasteBoxOpen && nowMs() - boxOpenedAtMs > DUPLICATE_PASTE_WINDOW_MS) return false
        deliver(text, PasteOrigin.Event)
        return true
    }

    private var boxOpenedAtMs = Long.MIN_VALUE / 2

    private enum class PasteOrigin { Port, Event, Box }

    private class LastPaste(val text: String, val origin: PasteOrigin, val atMs: Long)

    private var lastPaste: LastPaste? = null

    private fun eventPasteIsRecent(since: Long): Boolean =
        lastPaste?.let { it.origin == PasteOrigin.Event && it.atMs >= since - DUPLICATE_PASTE_WINDOW_MS } == true

    /** A paste that reaches us by both the port (Ctrl+V keydown) and the DOM event within the window applies once. */
    private fun deliver(text: String, origin: PasteOrigin, since: Long = nowMs()) {
        val now = nowMs()
        if (isSecondRouteOfSamePaste(text, origin, since)) {
            lastPaste = null
            return
        }
        lastPaste = LastPaste(normalize(text), origin, now)
        applyPaste(text)
    }

    /** [since] is when this route's request began (now, for a DOM event): the earlier route must fall inside the window before it. */
    private fun isSecondRouteOfSamePaste(text: String, origin: PasteOrigin, since: Long): Boolean {
        val last = lastPaste ?: return false
        val bothRoutes = origin != PasteOrigin.Box && last.origin != PasteOrigin.Box && last.origin != origin
        return bothRoutes && last.text == normalize(text) && since - last.atMs <= DUPLICATE_PASTE_WINDOW_MS
    }

    private fun normalize(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')

    private fun applyPaste(text: String) {
        pasteBoxOpen = false
        state.pasteApplications++
        if (state.readOnly) return state.announce(TableMessages.READ_ONLY)
        scope.launch {
            val big = estimatePasteCells(text) > PASTE_OFF_THREAD_CELLS
            if (big) state.applying = true
            try {
                val parsed = parse(text, big)
                apply(text, parsed)
            } finally {
                if (big) state.applying = false
            }
        }
    }

    private suspend fun parse(text: String, offThread: Boolean): TabularParse? = try {
        val slotted = slot.rawFor(text)
        when {
            slotted != null -> TabularParse.Grid(slotted)
            offThread -> withContext(parseContext) { TabularText.parse(text) }
            else -> TabularText.parse(text)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log.warn("clipboard parse failed: ${e.message}")
        null
    }

    private fun apply(text: String, parsed: TabularParse?) {
        when (parsed) {
            null -> session.notices.info(TableMessages.PASTE_UNREADABLE)
            TabularParse.TooLarge -> session.notices.info(TableMessages.PASTE_TOO_LARGE)
            is TabularParse.Grid -> applyGrid(parsed.rows, transformsReported = slot.rawFor(text) == null)
        }
    }

    private fun applyGrid(rows: List<List<String>>, transformsReported: Boolean) {
        if (rows.isEmpty() || rows.all { it.isEmpty() }) return session.notices.info(TableMessages.NOTHING_TO_PASTE)
        val sel = state.selection
        val range = sel.range
        val multi = range.rows > 1 || range.cols > 1
        val edit = TableEdit.PasteRange(range.topLeft, rows, if (multi) range else null)
        when (val attempt = state.attemptEdit(edit, "paste")) {
            is EditAttempt.Applied -> {
                val s = attempt.outcome.summary as? EditSummary.Pasted ?: return
                val at = range.topLeft
                state.selectRange(GridSelection(at, CellAddress(at.row + s.rows - 1, at.col + s.cols - 1)))
                val message = TableMessages.pasted(s.rows, s.cols, s.addedRows, s.addedCols)
                state.announce(message)
                val second = if (s.transformedCells > 0 && transformsReported) {
                    val flat = rows.flatten()
                    TableMessages.pasteTransformed(s.transformedCells, flat.any { '|' in it }, flat.any { '\n' in it || '\r' in it })
                } else null
                session.noticeWithUndo(if (second == null) message else "$message\n$second")
            }
            is EditAttempt.Refused -> if (attempt.reason == RefusalReason.PasteTooLarge) session.notices.info(TableMessages.PASTE_TOO_LARGE)
            EditAttempt.NoChange, EditAttempt.NotCommitted -> Unit
        }
    }

    private fun rangeLabel(r: CellRange) = if (r.rows == 1 && r.cols == 1) r.topLeft.toString() else "${r.topLeft}:${r.bottomRight}"

    /** TSV of the selection by values; the pure counterpart the tests read. */
    internal fun tsvOfSelection(): String = SelectionStats.toTsv(state.selection, state::valueText)
}

/**
 * The two clipboard fallbacks: "Show as text" after a failed write (the TSV selected for manual copy) and "Paste here"
 * when the platform gives no clipboard read (UX-28). They are panels inside the sheet's own window, not a second dialog
 * window (a text field in a nested window never goes idle under the Robolectric test clock, and a second window is
 * unwelcome on the web). Both register on the Back ladder, so Back or Esc cancels them.
 */
@Composable
internal fun SheetClipboardDialogs(state: SheetState, clipboard: SheetClipboard, source: PasteEventSource?) {
    DisposableEffect(clipboard, source) {
        val release = source?.register { clipboard.pasteFromEvent(it) }
        onDispose { release?.invoke() }
    }
    clipboard.shownText?.let { text -> ShowAsTextPanel(state, text, clipboard::closeShownText) }
    if (clipboard.pasteBoxOpen) PasteHerePanel(state, clipboard)
}

@Composable
private fun ClipboardPanel(tag: String, title: String, onDismiss: () -> Unit, actions: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // The scrim is a sibling: a clickable parent would merge the panel's own semantics into one node.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onDismiss).testTag("${tag}Scrim"))
        Surface(
            modifier = Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth().testTag(tag),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        }
    }
}

@Composable
private fun ShowAsTextPanel(state: SheetState, text: String, onDismiss: () -> Unit) {
    DisposableEffect(state) { onDispose(state.registerMenu(onDismiss)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ClipboardPanel(
        tag = "showAsTextDialog",
        title = TableMessages.SHOW_AS_TEXT,
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = TextFieldValue(text, TextRange(0, text.length)),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).focusRequester(focus).testTag("showAsText"),
            )
        },
        actions = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(TableMessages.DONE) } },
    )
}

@Composable
private fun PasteHerePanel(state: SheetState, clipboard: SheetClipboard) {
    DisposableEffect(state) { onDispose(state.registerMenu(clipboard::closePasteBox)) }
    var field by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ClipboardPanel(
        tag = "pasteHereDialog",
        title = TableMessages.PASTE_HERE,
        onDismiss = clipboard::closePasteBox,
        content = {
            Text(TableMessages.PASTE_HERE_HINT, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp).focusRequester(focus).testTag("pasteHereField"),
            )
        },
        actions = {
            TextButton(onClick = clipboard::closePasteBox, modifier = Modifier.heightIn(min = 48.dp).testTag("pasteHereCancel")) { Text(TableMessages.CANCEL) }
            TextButton(onClick = { clipboard.pasteText(field) }, enabled = field.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("pasteHereInsert")) {
                Text(TableMessages.INSERT)
            }
        },
    )
}
