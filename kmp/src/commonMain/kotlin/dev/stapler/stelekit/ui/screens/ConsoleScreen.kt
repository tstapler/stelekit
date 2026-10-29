// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import dev.stapler.stelekit.ui.components.typedItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.stapler.stelekit.console.ConfirmAnswer
import dev.stapler.stelekit.console.ConfirmRequest
import dev.stapler.stelekit.console.ConfirmTier
import dev.stapler.stelekit.console.requiredText
import dev.stapler.stelekit.console.ConsoleSession
import dev.stapler.stelekit.console.ResultTable
import dev.stapler.stelekit.console.ScrollbackKind
import dev.stapler.stelekit.console.toCsv
import dev.stapler.stelekit.export.ShareProvider
import dev.stapler.stelekit.ui.rememberShareProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The developer console: scrollback, a command line, shortcut chips and a few punctuation keys the
 * phone keyboard buries. All state that must survive a configuration change lives in [session]
 * (scrollback, history) or `rememberSaveable` (the input text).
 */
@Composable
fun ConsoleScreen(session: ConsoleSession, modifier: Modifier = Modifier) {
    val lines by session.scrollback.lines.collectAsState()
    val running by session.isRunning.collectAsState()
    val history by session.history.collectAsState()
    val pendingConfirm by session.pendingConfirm.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shareProvider = rememberShareProvider()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    fun submit() {
        val line = input.trim()
        if (line.isEmpty() || running) return
        session.submit(line)
        input = ""
        historyCursor = -1
    }

    fun recall(step: Int) {
        if (history.isEmpty()) return
        val next = (if (historyCursor == -1) history.size else historyCursor) + step
        historyCursor = next.coerceIn(0, history.lastIndex)
        input = history[historyCursor]
    }

    pendingConfirm?.let { request ->
        WriteConfirmDialog(request, onAnswer = session::answerConfirm)
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Console", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Row {
                IconButton(onClick = {
                    scope.launch {
                        shareProvider.saveToFile(session.exportText(), session.exportFileBaseName(Clock.System.now()), "txt")
                    }
                }, enabled = lines.isNotEmpty()) { Icon(Icons.Default.Share, contentDescription = "Export console output") }
                IconButton(onClick = { session.clearScrollback() }, enabled = lines.isNotEmpty()) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear console output")
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            if (lines.isEmpty()) {
                Text(
                    "Type 'help' to list commands.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("console-scrollback")) {
                    typedItems(lines, key = { it.id.toString() }) { line ->
                        val table = line.table
                        if (table != null) {
                            ConsoleResultGrid(
                                table = table,
                                onExportCsv = { exportCsv(session, shareProvider, scope, it) },
                                onExplain = { input = ResultTable.explainCommandLine(it) },
                            )
                        } else Text(
                            text = if (line.kind == ScrollbackKind.INPUT) "> ${line.text}" else line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = when (line.kind) {
                                ScrollbackKind.ERROR -> MaterialTheme.colorScheme.error
                                ScrollbackKind.INPUT -> MaterialTheme.colorScheme.primary
                                ScrollbackKind.OUTPUT -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            typedItems(suggestedCommands(session), key = { it.label }) { chip ->
                AssistChip(onClick = { input = chip.input }, label = { Text(chip.label) }, enabled = !running, modifier = Modifier.defaultMinSize(minWidth = MIN_TOUCH_TARGET, minHeight = MIN_TOUCH_TARGET))
            }
        }
        // History arrows sit outside the scrolling key row so they stay reachable at 360dp.
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(modifier = Modifier.weight(1f)) {
                typedItems(PUNCTUATION_KEYS, key = { it }) { key ->
                    TextButton(onClick = { input += key }, modifier = Modifier.defaultMinSize(minWidth = MIN_TOUCH_TARGET, minHeight = MIN_TOUCH_TARGET)) {
                        Text(key, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            IconButton(onClick = { recall(-1) }, modifier = Modifier.size(MIN_TOUCH_TARGET)) { Icon(Icons.Default.ArrowUpward, contentDescription = "Previous command") }
            IconButton(onClick = { recall(1) }, modifier = Modifier.size(MIN_TOUCH_TARGET)) { Icon(Icons.Default.ArrowDownward, contentDescription = "Next command") }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Console command line" }.testTag("console-input"),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("command") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            if (running) {
                OutlinedButton(onClick = { session.cancel() }) { Text("Cancel") }
            } else {
                Button(onClick = { submit() }, enabled = input.isNotBlank()) { Text("Run") }
            }
        }
    }
}

private fun exportCsv(session: ConsoleSession, share: ShareProvider, scope: CoroutineScope, table: ResultTable) {
    scope.launch { share.saveToFile(table.toCsv(), session.exportFileBaseName(Clock.System.now(), prefix = "sql"), "csv") }
}

/** The `sql!` confirm as a dialog window. Its content is [WriteConfirmPanel], which tests drive directly. */
@Composable
internal fun WriteConfirmDialog(request: ConfirmRequest, onAnswer: (ConfirmAnswer) -> Unit) {
    Dialog(onDismissRequest = { onAnswer(ConfirmAnswer.Cancel) }) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            WriteConfirmPanel(request, onAnswer, modifier = Modifier.padding(24.dp))
        }
    }
}

/**
 * The statement verbatim, the plain-language details, and Cancel as the focused default. A
 * [ConfirmTier.TypedCount] confirm keeps the primary button disabled until the exact count is typed.
 */
@Composable
internal fun WriteConfirmPanel(request: ConfirmRequest, onAnswer: (ConfirmAnswer) -> Unit, modifier: Modifier = Modifier) {
    var typed by remember(request) { mutableStateOf("") }
    val cancelFocus = remember { FocusRequester() }
    val tier = request.tier
    val required = tier.requiredText
    val ready = required == null || typed.trim() == required
    LaunchedEffect(request) { runCatching { cancelFocus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.testTag("console-write-confirm")) {
        Text(request.title, style = MaterialTheme.typography.headlineSmall)
        Text(request.statement, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.testTag("console-write-statement"))
        request.details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (required != null) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                label = { Text("Type $required to confirm") },
                keyboardOptions = if (tier is ConfirmTier.TypedPhrase) {
                    KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
                } else {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                },
                modifier = Modifier.testTag("console-write-typed-count"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onAnswer(ConfirmAnswer.Cancel) },
                modifier = Modifier.focusRequester(cancelFocus).testTag("console-write-cancel-button"),
            ) { Text("Cancel") }
            Button(
                onClick = { onAnswer(ConfirmAnswer.Proceed(typed.takeIf { required != null })) },
                enabled = ready,
                modifier = Modifier.testTag("console-write-confirm-button"),
            ) { Text(request.primaryLabel) }
        }
    }
}

/** First-open chips; a chip appears only if its verb is registered, so unbuilt verbs never show. */
private fun suggestedCommands(session: ConsoleSession): List<Chip> {
    val verbs = session.commands.map { it.name }.toSet()
    return CHIPS.filter { it.input.substringBefore(' ') in verbs }
}

/** [label] is what the chip says; [input] is what tapping it puts on the command line. */
private class Chip(val label: String, val input: String = label)

private val CHIPS = listOf(
    Chip("tables"),
    Chip("schema <table>", "schema "),
    Chip("sql: newest journals", "sql \"select name from pages where is_journal = 1 order by updated_at desc limit 3\""),
    Chip("diag"),
    Chip("help"),
    Chip("logs"),
)
private val MIN_TOUCH_TARGET = 48.dp
private val PUNCTUATION_KEYS = listOf("\"", "'", "-", "_", "/", "|", "*", ";")
