// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.ImageNode
import dev.stapler.stelekit.parsing.ast.TextNode

/**
 * Returns the image URL and alt text if [content] parses to exactly one [ImageNode]
 * with no sibling non-whitespace inline nodes. Returns null for all other content.
 */
internal fun extractSingleImageNode(content: String): Pair<String, String>? {
    val nodes = InlineParser(content.trim()).parse()
    val meaningful = nodes.filterNot { it is TextNode && it.content.isBlank() }
    return if (meaningful.size == 1 && meaningful[0] is ImageNode) {
        val img = meaningful[0] as ImageNode
        img.url to img.alt
    } else null
}
