// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

data class RawBlock(val content: String, val level: Int)

object HtmlBlockConverter {
    /**
     * Converts an HTML string to a structured list of raw blocks.
     * Headings become top-level blocks (level 0) with markdown heading syntax (## Heading).
     * Paragraphs become level-0 blocks.
     * List items become blocks: top-level ul/ol items are level 1, nested are level 2.
     * <br> within a block becomes \n within that block's content.
     * Returns null if input is not HTML (no < characters detected) — caller falls back to plain-text split.
     */
    fun convert(html: String): List<RawBlock>? {
        if (!html.trimStart().startsWith("<")) return null

        val doc = Ksoup.parse(html)
        val blocks = mutableListOf<RawBlock>()

        for (element in doc.body().children()) {
            processElement(element, blocks)
        }

        return blocks.filter { it.content.isNotBlank() }
    }

    private fun processElement(element: Element, blocks: MutableList<RawBlock>) {
        when (element.tagName().lowercase()) {
            "h1" -> blocks.add(RawBlock("# ${element.text()}", level = 0))
            "h2" -> blocks.add(RawBlock("## ${element.text()}", level = 0))
            "h3" -> blocks.add(RawBlock("### ${element.text()}", level = 0))
            "h4" -> blocks.add(RawBlock("#### ${element.text()}", level = 0))
            "h5" -> blocks.add(RawBlock("##### ${element.text()}", level = 0))
            "h6" -> blocks.add(RawBlock("###### ${element.text()}", level = 0))
            "p" -> {
                val text = element.text()
                if (text.isNotBlank()) {
                    blocks.add(RawBlock(text, level = 0))
                }
            }
            "ul", "ol" -> {
                val allListItems = element.select("li")
                for (li in allListItems) {
                    // Count parent ul/ol elements to determine nesting depth
                    val depth = li.parents().select("ul,ol").size - 1
                    // Get only the direct text of this li, not nested li text
                    val liText = getDirectText(li)
                    if (liText.isNotBlank()) {
                        val blockLevel = if (depth <= 0) 1 else 2
                        blocks.add(RawBlock(liText, level = blockLevel))
                    }
                }
            }
            "blockquote" -> {
                val text = element.text()
                if (text.isNotBlank()) {
                    blocks.add(RawBlock("> $text", level = 0))
                }
            }
            "pre", "code" -> {
                val text = element.text()
                if (text.isNotBlank()) {
                    blocks.add(RawBlock("```\n$text\n```", level = 0))
                }
            }
            else -> {
                val text = element.text()
                if (text.isNotBlank()) {
                    blocks.add(RawBlock(text, level = 0))
                }
            }
        }
    }

    /**
     * Returns the text content of an li element, excluding the text of any nested li elements.
     */
    private fun getDirectText(li: Element): String {
        // Clone the element and remove nested ul/ol to get only direct text
        val clone = li.clone()
        clone.select("ul,ol").remove()
        return clone.text().trim()
    }
}
