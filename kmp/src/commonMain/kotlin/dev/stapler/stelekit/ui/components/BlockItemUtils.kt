// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.ImageNode
import dev.stapler.stelekit.parsing.ast.TextNode

/**
 * Returns the image URL and alt text if [content] parses to a single [ImageNode] with no
 * meaningful sibling content. Logseq size-hint annotations ({:height 300, :width 400}) that
 * follow the image are tolerated — the parser emits them as individual token-level TextNodes
 * ({, :, height, …, }) which are reassembled and matched against the {:…} pattern.
 */
internal fun extractSingleImageNode(content: String): Pair<String, String>? {
    val nodes = InlineParser(content.trim()).parse()
    val meaningful = nodes.filterNot { it is TextNode && it.content.isBlank() }
    if (meaningful.isEmpty() || meaningful[0] !is ImageNode) return null
    val img = meaningful[0] as ImageNode
    if (meaningful.size == 1) return img.url to img.alt
    // Tolerate a trailing Logseq size-hint block — all remaining nodes must be TextNodes and
    // their concatenation (re-joined without the whitespace-only nodes already filtered above)
    // must match {:...}.
    val trailing = meaningful.drop(1)
    if (trailing.all { it is TextNode }) {
        val joined = trailing.joinToString("") { (it as TextNode).content }.trim()
        if (joined.startsWith("{:") && joined.endsWith("}")) return img.url to img.alt
    }
    return null
}
