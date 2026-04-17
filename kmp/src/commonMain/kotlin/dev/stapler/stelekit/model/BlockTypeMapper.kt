package dev.stapler.stelekit.model

fun BlockType.toDiscriminatorString(): String = when (this) {
    is BlockType.Bullet -> BlockTypes.BULLET
    is BlockType.Paragraph -> BlockTypes.PARAGRAPH
    is BlockType.Heading -> BlockTypes.HEADING
    is BlockType.CodeFence -> BlockTypes.CODE_FENCE
    is BlockType.Blockquote -> BlockTypes.BLOCKQUOTE
    is BlockType.OrderedListItem -> BlockTypes.ORDERED_LIST_ITEM
    is BlockType.ThematicBreak -> BlockTypes.THEMATIC_BREAK
    is BlockType.Table -> BlockTypes.TABLE
    is BlockType.RawHtml -> BlockTypes.RAW_HTML
}
