// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** BlockItem's dispatch with the table flags off equals today's; Bullet-typed table content opts in only with a flag on (ADR-010). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableBlockFlagOffEquivalenceTest {
    @get:Rule
    val rule = createComposeRule()

    private fun block(type: BlockType, content: String = TABLE) = Block(
        uuid = BlockUuid("00000000-0000-0000-0000-000000000001"),
        pageUuid = PageUuid("00000000-0000-0000-0000-000000000002"),
        content = content,
        position = "a0",
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
        blockType = type,
    )

    private var started = 0
    private val tablePrefs = TablePreferences(null)
    private var shown by mutableStateOf<Block?>(null)

    private fun mountOnce() {
        rule.setContent {
            MaterialTheme {
                Surface {
                    val current = shown ?: return@Surface
                    CompositionLocalProvider(LocalTablePreferences provides tablePrefs) {
                        Box(Modifier.width(360.dp)) {
                            BlockItem(
                                block = current,
                                isEditing = false,
                                onStartEditing = { started++ },
                                onStopEditing = {},
                                onContentChange = { _, _ -> },
                                onLinkClick = {},
                                onNewBlock = {},
                                onSplitBlock = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Unmerged semantics dump of [block] rendered under [prefs] (one composition per test, content swapped in place). */
    private fun show(block: Block, prefs: TableDisplayPrefs): String {
        rule.runOnIdle {
            tablePrefs.update { prefs }
            shown = block
        }
        rule.waitForIdle()
        return rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 40)
    }

    private fun tapBlock() {
        rule.onRoot().performClick()
        rule.waitForIdle()
    }

    @Test
    fun tableBlock_should_OpenRawEditorOnTap_When_FlagsOff() {
        mountOnce()
        assertTrue(show(block(BlockType.Table), TableDisplayPrefs()).contains("Qty"))
        tapBlock()
        assertEquals(1, started)
    }

    @Test
    fun bulletWithTableContent_should_StayBullet_When_FlagsOff() {
        mountOnce()
        val tableTree = show(block(BlockType.Table), TableDisplayPrefs())
        val bulletTree = show(block(BlockType.Bullet), TableDisplayPrefs())
        assertNotEquals(tableTree, bulletTree, "flags off: a Bullet must not take the table path")
        assertFalse(bulletTree.contains("CollectionInfo"), "flags off: a Bullet with table content is not a grid")
        assertTrue(bulletTree.contains("Qty"), "flags off: the bullet shows the content as text")
        assertEquals(bulletTree, show(block(BlockType.Bullet), TableDisplayPrefs()), "flags off: stable bullet rendering")
        started = 0
        tapBlock()
        assertEquals(1, started)
    }

    @Test
    fun bulletWithTableContent_should_RenderGrid_When_InlineFlagOn() {
        mountOnce()
        val on = TableDisplayPrefs(inlineEditEnabled = true)
        val tableTree = show(block(BlockType.Table), on)
        assertEquals(tableTree, show(block(BlockType.Bullet), on), "Bullet with table content renders as the Table branch does")
    }

    @Test
    fun bulletWithPlainContent_should_StayBullet_When_FlagOn() {
        mountOnce()
        val on = TableDisplayPrefs(sheetEnabled = true)
        val plainBullet = show(block(BlockType.Bullet, "Qty and Load"), on)
        assertTrue(plainBullet.contains("Qty and Load"))
        assertEquals(plainBullet, show(block(BlockType.Bullet, "Qty and Load"), TableDisplayPrefs()), "a plain bullet is not routed to TableBlock")
        assertFalse(isGridTableContent("Qty and Load | not a table"))
        assertTrue(isGridTableContent(TABLE))
    }

    @Test
    fun bulletWithFencedOrProseWrappedTable_should_StayBullet_When_FlagOn() {
        mountOnce()
        val on = TableDisplayPrefs(inlineEditEnabled = true)
        val fenced = show(block(BlockType.Bullet, "```\n$TABLE\n```"), on)
        assertFalse(fenced.contains("CollectionInfo"), "a table inside a code fence is not hijacked")
        val prose = show(block(BlockType.Bullet, "Intro\n$TABLE"), on)
        assertFalse(prose.contains("CollectionInfo"), "prose around the table keeps the bullet rendering")
    }

    @Test
    fun tableBlock_should_HideTheMaximizeButton_InSelectionMode_WhenInlineIsOff() {
        rule.setContent {
            MaterialTheme {
                Surface {
                    TableBlock(
                        content = TABLE,
                        onStartEditing = {},
                        prefs = TableDisplayPrefs(inlineEditEnabled = false, sheetEnabled = true),
                        onOpenSheet = {},
                        isInSelectionMode = true,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText(TableMessages.MAXIMIZE).assertCountEquals(0)
    }

    @Test
    fun bulletTable_should_WriteBackThroughTheCommitActionKeepingItsIndentation() {
        val commits = mutableListOf<Triple<BlockUuid, String, Long>>()
        val prefs = TablePreferences(null).also { it.update { TableDisplayPrefs(inlineEditEnabled = true) } }
        val indented = "  | Item | Qty |\n  |---|---|\n  | Apple | 3 |"
        rule.setContent {
            MaterialTheme {
                Surface {
                    CompositionLocalProvider(
                        LocalTablePreferences provides prefs,
                        LocalTableCommitAction provides { uuid, text, version -> commits += Triple(uuid, text, version) },
                    ) {
                        Box(Modifier.width(360.dp)) {
                            BlockItem(
                                block = block(BlockType.Bullet, indented),
                                isEditing = false,
                                onStartEditing = {},
                                onStopEditing = {},
                                onContentChange = { _, _ -> },
                                onLinkClick = {},
                                onNewBlock = {},
                                onSplitBlock = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("3").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("45")
        rule.onNodeWithTag("strip:Done").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val written = commits.single().second
        assertEquals("  | Item | Qty |\n  |---|---|\n  | Apple | 45 |", written)
        assertEquals(BlockUuid("00000000-0000-0000-0000-000000000001"), commits.single().first)
    }

    private companion object {
        val TABLE = """
            | Load | Qty |
            |---|---|
            | Pixel track | 140 ft |
        """.trimIndent()
    }
}
