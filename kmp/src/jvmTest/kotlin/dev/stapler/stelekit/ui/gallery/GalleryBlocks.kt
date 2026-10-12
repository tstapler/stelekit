package dev.stapler.stelekit.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.tags.LlmSuggestionStatus
import dev.stapler.stelekit.tags.TagSuggestion
import dev.stapler.stelekit.ui.components.BlockDragGhost
import dev.stapler.stelekit.ui.components.BlockGutter
import dev.stapler.stelekit.ui.components.BlockItem
import dev.stapler.stelekit.ui.components.BlockList
import dev.stapler.stelekit.ui.components.CodeFenceBlock
import dev.stapler.stelekit.ui.components.MermaidBlock
import dev.stapler.stelekit.ui.components.MermaidRenderResult
import dev.stapler.stelekit.ui.components.MobileBlockToolbar
import dev.stapler.stelekit.ui.components.QueryBlock
import dev.stapler.stelekit.ui.components.SuggestionItem
import dev.stapler.stelekit.ui.components.SuggestionNavigatorPanel
import dev.stapler.stelekit.ui.components.WikiLinkText
import dev.stapler.stelekit.ui.components.tags.TagChipRow
import kotlin.time.Instant

/** Stories for the blocks category -- see [ComponentGallery]. */

private val FIXED_TIME = Instant.fromEpochSeconds(1_700_000_000)
private val GALLERY_PAGE = PageUuid("00000000-0000-0000-0000-0000000000aa")

private fun blockUuid(index: Int) = BlockUuid("00000000-0000-0000-0000-" + index.toString(16).padStart(12, '0'))

private fun galleryBlock(
    index: Int,
    content: String,
    level: Int = 0,
    type: BlockType = BlockType.Bullet,
    properties: Map<String, String> = emptyMap(),
    parent: Int? = null,
) = Block(
    uuid = blockUuid(index),
    pageUuid = GALLERY_PAGE,
    parentUuid = parent?.let { blockUuid(it) },
    content = content,
    level = level,
    position = "a$index",
    createdAt = FIXED_TIME,
    updatedAt = FIXED_TIME,
    properties = properties,
    blockType = type,
)

@Composable
private fun GalleryBlockItem(
    block: Block,
    hasChildren: Boolean = false,
    isCollapsed: Boolean = false,
    isSelected: Boolean = false,
    isInSelectionMode: Boolean = false,
) {
    BlockItem(
        block = block,
        isEditing = false,
        hasChildren = hasChildren,
        isCollapsed = isCollapsed,
        isSelected = isSelected,
        isInSelectionMode = isInSelectionMode,
        onStartEditing = {},
        onStopEditing = {},
        onContentChange = { _, _ -> },
        onLinkClick = {},
        onNewBlock = {},
        onSplitBlock = { _, _ -> },
    )
}

private const val LONG_BLOCK =
    "A long block that keeps going so the wrapping behaviour is visible: [[Kotlin Multiplatform]] shares " +
        "code across Desktop, Android, iOS and Web, while #outliner blocks stay plain markdown on disk, " +
        "with **bold**, *italic*, `inline code` and a link to https://example.com/some/long/path/segment."

private val sampleSuggestions = listOf(
    SuggestionItem("b1", "Kotlin Multiplatform", 4, 24),
    SuggestionItem("b2", "Roborazzi", 0, 9),
    SuggestionItem("b3", "Compose", 10, 17),
)

internal val blockStories: List<GalleryStory> = listOf(
    GalleryStory("blocks-block-item-states") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryBlockItem(galleryBlock(1, "A plain bullet block with a [[wiki link]] and #tag"))
            GalleryBlockItem(galleryBlock(2, "TODO A task that is still open"))
            GalleryBlockItem(galleryBlock(3, "DONE A task that is finished"))
            GalleryBlockItem(galleryBlock(4, LONG_BLOCK))
            GalleryBlockItem(galleryBlock(5, "A selected block", level = 0), isSelected = true, isInSelectionMode = true)
            GalleryBlockItem(galleryBlock(6, "An unselected block in selection mode"), isInSelectionMode = true)
        }
    },
    GalleryStory("blocks-block-item-children") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryBlockItem(galleryBlock(10, "Parent with children, expanded"), hasChildren = true, isCollapsed = false)
            GalleryBlockItem(galleryBlock(11, "Parent with children, collapsed"), hasChildren = true, isCollapsed = true)
            Box(Modifier.height(IntrinsicSize.Min)) {
                GalleryBlockItem(galleryBlock(12, "Nested child at level 2", level = 2))
            }
        }
    },
    GalleryStory("blocks-block-item-typed") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryBlockItem(galleryBlock(20, "## A heading block", type = BlockType.Heading(2)))
            Box(Modifier.height(IntrinsicSize.Min)) {
                GalleryBlockItem(galleryBlock(21, "> A blockquote block", type = BlockType.Blockquote))
            }
            Box(Modifier.height(IntrinsicSize.Min)) {
                GalleryBlockItem(
                    galleryBlock(22, "```kotlin\nval answer = 42\n```", type = BlockType.CodeFence("kotlin")),
                )
            }
            GalleryBlockItem(galleryBlock(23, "3. A numbered step", type = BlockType.OrderedListItem(3)))
            GalleryBlockItem(galleryBlock(24, "---", type = BlockType.ThematicBreak))
        }
    },
    GalleryStory("blocks-block-list") {
        // Nested rows use fillMaxHeight guides; a scroll container gives them the unbounded height
        // they get inside the real page LazyColumn.
        Box(Modifier.verticalScroll(rememberScrollState())) {
        BlockList(
            blocks = listOf(
                galleryBlock(30, "Project notes", level = 0),
                galleryBlock(31, "First child with a [[link]]", level = 1, parent = 30),
                galleryBlock(32, "Grandchild", level = 2, parent = 31),
                galleryBlock(33, "Second child", level = 1, parent = 30),
                galleryBlock(34, "TODO Top-level task", level = 0),
            ),
            editingBlockUuid = null,
            selectedBlockUuids = setOf(blockUuid(33).value),
            isInSelectionMode = true,
            onStartEditing = {},
            onStopEditing = {},
            onContentChange = { _, _, _ -> },
            onLinkClick = {},
            onNewBlock = {},
            onSplitBlock = { _, _ -> },
        )
        }
    },
    GalleryStory("blocks-wiki-link-text") {
        val text = MaterialTheme.colorScheme.onSurface
        val link = MaterialTheme.colorScheme.primary
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WikiLinkText("Plain text with a [[wiki link]] and an #inline-tag", text, link)
            WikiLinkText("**Bold**, *italic*, ~~strike~~, `code` and [a markdown link](https://example.com)", text, link)
            WikiLinkText("Alias link [[Kotlin Multiplatform]] and a bare URL https://example.com/path", text, link)
            WikiLinkText(LONG_BLOCK, text, link)
            WikiLinkText(
                "Cross-section links: [[Available Page]] and [[Other Section Page]]",
                text, link,
                localPageNames = setOf("Available Page"),
                hasSectionFilter = true,
            )
        }
    },
    GalleryStory("blocks-query-block") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryBlock(
                rawQuery = "(and [[project]] (task TODO))",
                queryExecutor = null,
                pageRepository = null,
                onStartEditing = {},
                onLinkClick = {},
            )
            QueryBlock(
                rawQuery = "(sort-by created-at)",
                queryExecutor = null,
                pageRepository = null,
                onStartEditing = {},
                onLinkClick = {},
            )
        }
    },
    GalleryStory("blocks-mermaid-fallbacks") {
        val fallback: @Composable (String, Modifier) -> Unit = { content, modifier ->
            CodeFenceBlock(content = content, language = "mermaid", onStartEditing = {}, modifier = modifier)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MermaidBlock(
                content = "```mermaid\ngraph TD\n  A --> B\n```",
                onStartEditing = {},
                renderer = { MermaidRenderResult.Failed("Parse error on line 2") },
                fallback = fallback,
            )
            MermaidBlock(
                content = "```mermaid\ngraph TD\n  A --> B\n```",
                onStartEditing = {},
                renderer = { MermaidRenderResult.UnsupportedPlatform },
                fallback = fallback,
            )
            MermaidBlock(content = "```mermaid\n```", onStartEditing = {}, fallback = fallback)
        }
    },
    GalleryStory("blocks-drag-ghost") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BlockDragGhost(draggedCount = 1)
            BlockDragGhost(draggedCount = 5)
            BlockDragGhost(draggedCount = 3, isBlocked = true)
        }
    },
    GalleryStory("blocks-gutter") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Triple(0, false, false),
                Triple(1, true, false),
                Triple(2, true, true),
            ).forEach { (level, hasChildren, collapsed) ->
                Row {
                    BlockGutter(
                        level = level,
                        isDebugMode = false,
                        hasChildren = hasChildren,
                        isCollapsed = collapsed,
                        onToggleCollapse = {},
                        onMoveUp = {},
                        onMoveDown = {},
                    )
                    Text("Level $level, children=$hasChildren, collapsed=$collapsed")
                }
            }
            Row {
                BlockGutter(
                    level = 0, isDebugMode = true, hasChildren = false, isCollapsed = false,
                    onToggleCollapse = {}, onMoveUp = {}, onMoveDown = {},
                    isSelected = true, isInSelectionMode = true,
                )
                Text("Selected, debug mode")
            }
        }
    },
    GalleryStory("blocks-tag-chip-row") {
        val suggestions = listOf(
            TagSuggestion("Kotlin", 1.0f, TagSuggestion.Source.LOCAL),
            TagSuggestion("Compose Multiplatform", 0.8f, TagSuggestion.Source.LLM),
            TagSuggestion("Screenshot Testing", 0.6f, TagSuggestion.Source.LLM),
            TagSuggestion("Already Applied", 1.0f, TagSuggestion.Source.LOCAL, autoApplied = true),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TagChipRow(suggestions, LlmSuggestionStatus.Resolved, onAccept = {}, onDismiss = {})
            TagChipRow(suggestions.take(1), LlmSuggestionStatus.Stalled(retryable = true), onAccept = {}, onDismiss = {})
            TagChipRow(suggestions.take(1), LlmSuggestionStatus.Failed("Model unavailable", retryable = false), onAccept = {}, onDismiss = {})
        }
    },
    GalleryStory("blocks-suggestion-navigator") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionNavigatorPanel(
                suggestions = sampleSuggestions,
                currentIndex = 0,
                onLink = {}, onSkip = {}, onPrevious = {}, onNext = {}, onClose = {},
            )
            SuggestionNavigatorPanel(
                suggestions = sampleSuggestions,
                currentIndex = 2,
                onLink = {}, onSkip = {}, onPrevious = {}, onNext = {}, onClose = {},
            )
        }
    },
    GalleryStory("blocks-mobile-toolbar") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MobileBlockToolbar(
                editingBlockId = "b1",
                onIndent = {}, onOutdent = {}, onMoveUp = {}, onMoveDown = {},
                onLinkPicker = {}, onSuggestTags = {}, onAttachImage = {},
            )
            MobileBlockToolbar(
                editingBlockId = "b1",
                onIndent = {}, onOutdent = {}, onMoveUp = {}, onMoveDown = {},
                hasDiskConflictPending = true,
            )
            MobileBlockToolbar(
                editingBlockId = null,
                onIndent = {}, onOutdent = {}, onMoveUp = {}, onMoveDown = {},
                isInSelectionMode = true, selectedCount = 3, clipboardEmpty = false,
            )
        }
    },
)
