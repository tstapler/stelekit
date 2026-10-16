package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.*

/**
 * Removes well-known ad-tracking query parameters from URLs. Pure string logic, no platform APIs.
 *
 * Applied to block content before it reaches a [PageExporter], so every export format
 * (including JSON, which emits raw block content) is covered by one code path.
 */
object TrackingParamStripper {
    val TRACKING_PARAMS: Set<String> = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "fbclid", "gclid", "dclid", "wbraid", "gbraid",
        "msclkid", "twclid", "mc_eid", "mc_cid", "igshid", "yclid",
    )

    /** Strips tracking parameters from [url]; everything else is kept verbatim. Never throws. */
    fun strip(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx == -1) return url
        val hashIdx = url.indexOf('#')
        // A '#' before the '?' means the '?' belongs to the fragment, not a query string.
        if (hashIdx != -1 && hashIdx < qIdx) return url
        val base = url.substring(0, qIdx)
        val fragment = if (hashIdx == -1) "" else url.substring(hashIdx)
        val query = url.substring(qIdx + 1, if (hashIdx == -1) url.length else hashIdx)
        val pairs = query.split('&')
        val retained = pairs.filterNot { it.substringBefore('=').lowercase() in TRACKING_PARAMS }
        if (retained.size == pairs.size) return url
        val kept = retained.filter { it.isNotEmpty() }
        return if (kept.isEmpty()) "$base$fragment" else "$base?${kept.joinToString("&")}$fragment"
    }

    /**
     * Strips tracking parameters from every [UrlLinkNode], [MdLinkNode] and [ImageNode] URL found
     * in [content]'s inline AST, rewriting the URL in place. Text outside those URLs is untouched.
     */
    fun stripInContent(content: String): String {
        if (content.indexOf('?') == -1) return content
        val urls = LinkedHashSet<String>()
        collectUrls(InlineParser(content).parse(), urls)
        var result = content
        for (url in urls) {
            val stripped = strip(url)
            if (stripped != url) result = result.replace(url, stripped)
        }
        return result
    }

    /** Returns [blocks] with tracking parameters stripped from each block's content. */
    fun stripInBlocks(blocks: List<Block>): List<Block> =
        blocks.map { block ->
            val stripped = stripInContent(block.content)
            if (stripped == block.content) block else block.copy(content = stripped)
        }

    private fun collectUrls(nodes: List<InlineNode>, out: MutableSet<String>) {
        for (node in nodes) {
            when (node) {
                is UrlLinkNode -> { out.add(node.url); collectUrls(node.text, out) }
                is MdLinkNode -> out.add(node.url)
                is ImageNode -> out.add(node.url)
                is BoldNode -> collectUrls(node.children, out)
                is ItalicNode -> collectUrls(node.children, out)
                is StrikeNode -> collectUrls(node.children, out)
                is HighlightNode -> collectUrls(node.children, out)
                is SubscriptNode -> collectUrls(node.children, out)
                is SuperscriptNode -> collectUrls(node.children, out)
                else -> Unit
            }
        }
    }
}
