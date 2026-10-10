package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page

/** Whether a copy also pulls in pages linked from the selection (ADR-003). */
sealed interface LinkClosurePolicy {
    data object Off : LinkClosurePolicy

    /** Pages linked directly from the selection, never transitive; at most [cap] are added. */
    data class Depth1(val cap: Int = LinkClosure.HARD_CAP) : LinkClosurePolicy

    companion object {
        val DEFAULT: LinkClosurePolicy = Off
    }
}

/**
 * [added] are the existing linked pages to copy too (name order, <= cap). [notIncluded] is the
 * "N more not included" count. [totalResolved] is the closure size before truncation.
 */
data class ClosureResult(
    val added: List<Page>,
    val notIncluded: Int,
    val totalResolved: Int,
) {
    val requiresConfirmation: Boolean get() = totalResolved > LinkClosure.CONFIRM_THRESHOLD
    val truncated: Boolean get() = notIncluded > 0

    companion object {
        val EMPTY = ClosureResult(emptyList(), 0, 0)
    }
}

object LinkClosure {
    const val CONFIRM_THRESHOLD = 200
    const val HARD_CAP = 1000
    const val LOOKUP_CHUNK = 500

    private val WIKI_LINK = Regex("""\[\[([^\[\]]+)]]""")

    /** Page names linked from [content] via `[[Name]]` / `#[[Name]]`. */
    fun linkedNames(content: String): List<String> =
        if (!content.contains("[[")) emptyList()
        else WIKI_LINK.findAll(content).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

    /** Pure: distinct linked names of [pages] not already selected (case-insensitive), in first-seen order. */
    fun candidateNames(pages: Collection<MergePage>): List<String> {
        val visited = HashSet<String>()
        pages.forEach { visited += it.name.lowercase() }
        val out = ArrayList<String>()
        fun walk(blocks: List<MergeBlock>) {
            for (b in blocks) {
                linkedNames(b.content).forEach { if (visited.add(it.lowercase())) out += it }
                b.properties.values.forEach { v -> linkedNames(v).forEach { if (visited.add(it.lowercase())) out += it } }
                walk(b.children)
            }
        }
        pages.forEach { walk(it.blocks) }
        return out
    }

    /** Pure: applies the confirm threshold and cap to resolved pages; output is name-sorted. */
    fun limit(resolved: Collection<Page>, policy: LinkClosurePolicy): ClosureResult {
        if (policy !is LinkClosurePolicy.Depth1) return ClosureResult.EMPTY
        val cap = policy.cap.coerceIn(0, HARD_CAP)
        val sorted = resolved.sortedBy { it.name.lowercase() }
        return ClosureResult(sorted.take(cap), maxOf(0, sorted.size - cap), sorted.size)
    }

    /**
     * IO entry point. [lookup] is `PageRepository::getPagesByNames`; it is called with <= [LOOKUP_CHUNK]
     * names at a time. Linked names with no existing page are skipped (nothing to copy).
     */
    suspend fun expand(
        selected: Collection<MergePage>,
        policy: LinkClosurePolicy,
        lookup: suspend (List<String>) -> Either<DomainError, List<Page>>,
    ): Either<DomainError, ClosureResult> {
        if (policy !is LinkClosurePolicy.Depth1) return ClosureResult.EMPTY.right()
        val selectedNames = selected.mapTo(HashSet()) { it.name.lowercase() }
        val resolved = LinkedHashMap<String, Page>()
        for (chunk in candidateNames(selected).chunked(LOOKUP_CHUNK)) {
            val pages = lookup(chunk).fold({ return it.left() }, { it })
            pages.forEach { p ->
                val key = p.name.lowercase()
                if (key !in selectedNames && key !in resolved) resolved[key] = p
            }
        }
        return limit(resolved.values, policy).right()
    }
}
