// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGuideStrings as S

/** What picking a search result does. */
sealed interface SearchAction {
    data class UseTool(val tool: ToolId) : SearchAction

    /** An editor action the host runs, named by [GuideActions]. */
    data class Run(val action: String) : SearchAction

    data class OpenTopic(val topicId: String) : SearchAction
}

enum class SearchKind { Tool, Action, Help }

/** One searchable thing: its [title] is what the user sees and types, [keywords] are extra words that find it. */
data class SearchEntry(val id: String, val title: String, val kind: SearchKind, val action: SearchAction, val keywords: List<String> = emptyList())

/** The names of the editor actions search can run; the host maps each to what it does. */
object GuideActions {
    const val UNDO = "undo"
    const val REDO = "redo"
    const val DELETE = "delete"
    const val CONSTRAIN = "constrain"
    const val SHORTCUTS = "shortcuts"
    const val GO_TO_ELEMENT = "go-to-element"
    const val FIT = "fit"
    const val GRID = "grid"
    const val SNAP = "snap"
    const val EXPORT = "export"
    const val IMPORT = "import"
}

object GuideSearch {
    private const val EXACT = 100
    private const val PREFIX = 80
    private const val WORD_PREFIX = 60
    private const val CONTAINS = 40
    private const val KEYWORD = 20

    /** Tools, actions and help topics together; [tools] are the tools this sheet offers, [actions] the ones the host can run. */
    fun entries(tools: Collection<ToolId>, actions: Collection<String>, topics: List<HelpTopic> = GuideContent.helpTopics): List<SearchEntry> {
        val toolEntries = ToolId.entries.filter { it in tools }.map { id ->
            val (name, what) = S.tool(id)
            SearchEntry("tool.${id.name.lowercase()}", name, SearchKind.Tool, SearchAction.UseTool(id), listOf(what))
        }
        val actionEntries = ACTIONS.filter { it.first in actions }.map { (name, title, words) -> SearchEntry("action.$name", title, SearchKind.Action, SearchAction.Run(name), words) }
        val helpEntries = topics.filterNot { it.id.startsWith("tool.") }.map { SearchEntry("help.${it.id}", it.title, SearchKind.Help, SearchAction.OpenTopic(it.id), it.keywords) }
        return toolEntries + actionEntries + helpEntries
    }

    /**
     * The entries that match every word of [query], best first: an exact title, then a title prefix, a word prefix, a title
     * substring, and last a keyword. Ties keep the order of [entries]. A blank query matches nothing; the caller shows the topic list.
     */
    fun rank(query: String, entries: List<SearchEntry>): List<SearchEntry> {
        val words = query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.withIndex()
            .mapNotNull { (i, e) -> score(words, e)?.let { Triple(it, i, e) } }
            .sortedWith(compareByDescending<Triple<Int, Int, SearchEntry>> { it.first }.thenBy { it.second })
            .map { it.third }
    }

    private fun score(words: List<String>, entry: SearchEntry): Int? {
        val title = entry.title.lowercase()
        val parts = words.map { wordScore(it, title, entry.keywords) ?: return null }
        return parts.sum() / parts.size + if (title == words.joinToString(" ")) EXACT else 0
    }

    private fun wordScore(word: String, title: String, keywords: List<String>): Int? = when {
        title == word -> EXACT
        title.startsWith(word) -> PREFIX
        title.split(' ').any { it.startsWith(word) } -> WORD_PREFIX
        title.contains(word) -> CONTAINS
        keywords.any { it.lowercase().contains(word) } -> KEYWORD
        else -> null
    }

    private val ACTIONS = listOf(
        Triple(GuideActions.UNDO, S.Search.UNDO, listOf("back", "revert")),
        Triple(GuideActions.REDO, S.Search.REDO, listOf("forward")),
        Triple(GuideActions.DELETE, S.Search.DELETE, listOf("remove", "erase")),
        Triple(GuideActions.CONSTRAIN, S.Search.CONSTRAIN, listOf("rule", "parallel", "horizontal", "vertical", "dimension")),
        Triple(GuideActions.SHORTCUTS, S.Search.SHORTCUTS, listOf("keys", "keyboard", "hotkeys")),
        Triple(GuideActions.GO_TO_ELEMENT, S.Search.GO_TO, listOf("find", "locate", "jump")),
        Triple(GuideActions.FIT, S.Search.FIT, listOf("zoom", "view", "center")),
        Triple(GuideActions.GRID, S.Search.GRID, listOf("snap", "guides")),
        Triple(GuideActions.SNAP, S.Search.SNAP, listOf("grid", "magnet")),
        Triple(GuideActions.EXPORT, S.Search.EXPORT, listOf("save", "svg", "png", "excalidraw")),
        Triple(GuideActions.IMPORT, S.Search.IMPORT, listOf("open", "excalidraw")),
    )
}
