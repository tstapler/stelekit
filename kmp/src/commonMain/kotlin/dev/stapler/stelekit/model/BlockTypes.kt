package dev.stapler.stelekit.model

/** String discriminators for [Block.blockType]. Shared by [BlockTypeMapper] and the UI dispatch in BlockItem. */
object BlockTypes {
    const val BULLET = "bullet"
    const val PARAGRAPH = "paragraph"
    const val HEADING = "heading"
    const val CODE_FENCE = "code_fence"
    const val BLOCKQUOTE = "blockquote"
    const val ORDERED_LIST_ITEM = "ordered_list_item"
    const val THEMATIC_BREAK = "thematic_break"
    const val TABLE = "table"
    const val RAW_HTML = "raw_html"
}
