package dev.stapler.stelekit.repository

internal val WIKILINK_REGEX = Regex("""\[\[([^\]]{1,500})\]\]""")

internal fun extractWikilinks(content: String): Set<String> =
    WIKILINK_REGEX.findAll(content).map { it.groupValues[1].trim() }.toHashSet()
