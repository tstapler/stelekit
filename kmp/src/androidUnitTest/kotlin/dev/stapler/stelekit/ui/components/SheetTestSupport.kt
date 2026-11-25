// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.ui.components.sheet.ParsedSheet
import dev.stapler.stelekit.ui.components.sheet.SheetBodyContent
import dev.stapler.stelekit.ui.components.sheet.SheetCommandSink
import dev.stapler.stelekit.ui.components.sheet.SheetState
import dev.stapler.stelekit.ui.components.sheet.parseSheet

/** A pipe table of [rows] body rows and [cols] columns; the cell at row r (2-based) and column c holds `r-c`. */
internal fun gridContent(rows: Int, cols: Int): String = buildString {
    append((1..cols).joinToString(" | ", "| ", " |") { "H$it" })
    append("\n").append((1..cols).joinToString("|", "|", "|") { "---" })
    for (r in 2..rows + 1) append("\n").append((1..cols).joinToString(" | ", "| ", " |") { "$r-$it" })
}

/** Mounts the real sheet body inside the real host; [state] is the grid's `SheetState` once composed. */
internal class SheetFixture(
    rule: ComposeContentTestRule,
    content: String,
    wrapPort: (dev.stapler.stelekit.ui.components.sheet.TableManagerPort) -> dev.stapler.stelekit.ui.components.sheet.TableManagerPort = { it },
) {
    val h = SheetHarness(rule, content, wrapPort)
    var state: SheetState? = null
    val sink = RecordingSink()

    /** Key events nobody inside the body consumed (they bubble out to this spy and on to the host). */
    var unconsumed = mutableListOf<androidx.compose.ui.input.key.Key>()

    fun show(parse: (String) -> ParsedSheet = ::parseSheet, readOnly: Boolean = false) {
        h.show(
            readOnly = readOnly,
            bindFakeHooks = false,
            body = { s ->
                Box(
                    Modifier.onKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key !in MODIFIERS) unconsumed += it.key
                        false
                    },
                ) { SheetBodyContent(s, sink = sink, parse = parse, onState = { state = it }) }
            },
        )
    }

    val s: SheetState get() = state!!

    fun close() = h.close()
}

internal class RecordingSink : SheetCommandSink {
    val seen = mutableListOf<dev.stapler.stelekit.table.GridCommand>()
    override fun onCommand(command: dev.stapler.stelekit.table.GridCommand, selection: dev.stapler.stelekit.table.GridSelection): Boolean =
        true.also { seen += command }
}

internal fun ComposeContentTestRule.textOf(tag: String): String? =
    runCatching {
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    }.getOrNull()

internal fun ComposeContentTestRule.editableTextOf(tag: String): String? =
    runCatching {
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
    }.getOrNull()

internal fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}

internal fun ComposeContentTestRule.exists(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

internal val B2 = CellAddress(2, 2)

private val MODIFIERS = setOf(
    androidx.compose.ui.input.key.Key.CtrlLeft, androidx.compose.ui.input.key.Key.CtrlRight,
    androidx.compose.ui.input.key.Key.ShiftLeft, androidx.compose.ui.input.key.Key.ShiftRight,
    androidx.compose.ui.input.key.Key.AltLeft, androidx.compose.ui.input.key.Key.AltRight,
    androidx.compose.ui.input.key.Key.MetaLeft, androidx.compose.ui.input.key.Key.MetaRight,
)
