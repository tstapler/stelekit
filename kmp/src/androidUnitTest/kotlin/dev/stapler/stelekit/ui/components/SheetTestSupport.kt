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
import androidx.compose.ui.graphics.toPixelMap
import dev.stapler.stelekit.ui.components.sheet.OutlineEdges
import dev.stapler.stelekit.ui.components.sheet.ParsedSheet
import dev.stapler.stelekit.ui.components.sheet.drawSelectionOutline
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
    private val rule: ComposeContentTestRule,
    content: String,
    wrapPort: (dev.stapler.stelekit.ui.components.sheet.TableManagerPort) -> dev.stapler.stelekit.ui.components.sheet.TableManagerPort = { it },
) {
    val h = SheetHarness(rule, content, wrapPort)
    var state: SheetState? = null
    val sink = RecordingSink()

    /** Key events nobody inside the body consumed (they bubble out to this spy and on to the host). */
    var unconsumed = mutableListOf<androidx.compose.ui.input.key.Key>()

    /** The clipboard the real commands use when [show] gets `realCommands = true`. */
    val clipboard = FakeTableClipboard()

    /** [realCommands] installs the real clipboard, structure and sort commands in place of the recording sink. */
    fun show(
        parse: (String) -> ParsedSheet = ::parseSheet,
        readOnly: Boolean = false,
        realCommands: Boolean = false,
        sizeClass: dev.stapler.stelekit.ui.WindowSizeClass? = null,
        preferences: TablePreferences? = null,
        nowMs: (() -> Long)? = null,
        wrap: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable () -> Unit) -> Unit = { it() },
    ) {
        h.show(
            readOnly = readOnly,
            bindFakeHooks = false,
            body = { s ->
                Box(
                    Modifier.onKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key !in MODIFIERS) unconsumed += it.key
                        false
                    },
                ) {
                    val body = @androidx.compose.runtime.Composable {
                        SheetBodyContent(
                            s,
                            sink = if (realCommands) dev.stapler.stelekit.ui.components.sheet.NoOpSheetCommandSink else sink,
                            parse = parse,
                            onState = { state = it },
                            clipboardPort = clipboard,
                            nowMs = nowMs,
                        )
                    }
                    val sized = @androidx.compose.runtime.Composable {
                        if (sizeClass == null) body() else androidx.compose.runtime.CompositionLocalProvider(dev.stapler.stelekit.ui.LocalWindowSizeClass provides sizeClass) { body() }
                    }
                    val prefd = @androidx.compose.runtime.Composable {
                        if (preferences == null) sized() else androidx.compose.runtime.CompositionLocalProvider(LocalTablePreferences provides preferences) { sized() }
                    }
                    wrap(prefd)
                }
            },
        )
    }

    /** Mounts the body in the activity window, without the host's `Dialog`, so pixels can be captured. */
    fun showBare(
        wrap: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable () -> Unit) -> Unit = { it() },
    ) {
        h.showBare { session ->
            wrap {
                Box {
                    SheetBodyContent(
                        session,
                        sink = dev.stapler.stelekit.ui.components.sheet.NoOpSheetCommandSink,
                        onState = { state = it },
                        clipboardPort = clipboard,
                    )
                }
            }
        }
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

/** A clipboard in memory; [failCopy] and [failPaste] make the port throw, [text] is what the system clipboard holds. */
internal class FakeTableClipboard : dev.stapler.stelekit.table.TableClipboard {
    var text: String? = null
    var html: String? = null
    var failCopy = false
    var failPaste = false
    var copies = 0

    /** While set, [paste] waits for it: a slow permission prompt or a busy system clipboard. */
    var pasteGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun copy(plainTsv: String, html: String?) {
        if (failCopy) error("clipboard refused")
        copies++
        text = plainTsv
        this.html = html
    }

    override suspend fun paste(): String? {
        pasteGate?.await()
        if (failPaste) error("clipboard unreadable")
        return text
    }
}

/** The production outline drawn into a bitmap: the pixel width of its left edge and the color of that edge, on [surface]. */
internal fun renderedOutline(color: androidx.compose.ui.graphics.Color, widthDp: Float, surface: androidx.compose.ui.graphics.Color, density: Float = 3f): Pair<Int, androidx.compose.ui.graphics.Color> {
    val side = 120
    val bitmap = androidx.compose.ui.graphics.ImageBitmap(side, side)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        androidx.compose.ui.unit.Density(density),
        androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(bitmap),
        androidx.compose.ui.geometry.Size(side.toFloat(), side.toFloat()),
    ) {
        drawRect(surface)
        drawSelectionOutline(OutlineEdges(top = true, bottom = true, left = true, right = true), color, widthDp)
    }
    val map = bitmap.toPixelMap()
    val edge = map[0, side / 2]
    var x = 0
    while (x < side && map[x, side / 2] == edge) x++
    return x to edge
}
