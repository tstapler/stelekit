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
    is BlockType.ImageAnnotation -> BlockTypes.IMAGE_ANNOTATION
    is BlockType.Unknown -> this.raw
}

/**
 * Converts a raw DB string discriminator to a [BlockType].
 * Unknown discriminators are preserved as [BlockType.Unknown] instead of falling back to a default,
 * so no data is silently discarded.
 */
fun blockTypeFromString(s: String): BlockType = when (s) {
    BlockTypes.BULLET -> BlockType.Bullet
    BlockTypes.PARAGRAPH -> BlockType.Paragraph
    BlockTypes.HEADING -> BlockType.Heading(level = 1)
    BlockTypes.CODE_FENCE -> BlockType.CodeFence(language = "")
    BlockTypes.BLOCKQUOTE -> BlockType.Blockquote
    BlockTypes.ORDERED_LIST_ITEM -> BlockType.OrderedListItem(number = 1)
    BlockTypes.THEMATIC_BREAK -> BlockType.ThematicBreak
    BlockTypes.TABLE -> BlockType.Table
    BlockTypes.RAW_HTML -> BlockType.RawHtml
    BlockTypes.IMAGE_ANNOTATION -> BlockType.ImageAnnotation
    else -> BlockType.Unknown(s)
}
