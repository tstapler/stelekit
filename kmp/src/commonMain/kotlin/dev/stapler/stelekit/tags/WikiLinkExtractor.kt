package dev.stapler.stelekit.tags

/** Extracts page names from Logseq wiki-link syntax `[[PageName]]` in block content. */
object WikiLinkExtractor {
    private val WIKI_LINK_REGEX = Regex("""\[\[(.+?)]]""")

    fun extractPageNames(content: String): Set<String> =
        WIKI_LINK_REGEX.findAll(content).map { it.groupValues[1] }.toSet()
}
