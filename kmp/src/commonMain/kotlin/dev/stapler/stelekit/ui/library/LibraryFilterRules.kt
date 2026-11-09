package dev.stapler.stelekit.ui.library

/** Entry-point filter carried by `Screen.Library` (palette "Show annotated photos", Gallery alias). */
enum class LibraryFilter { Annotated }

/** Type chips (LD-11). `FILE` and any unrecognised stored media type are [OTHER]. */
enum class LibraryMediaClass {
    IMAGES, PDFS, AUDIO, VIDEO, DOCS, OTHER;

    companion object {
        fun of(mediaType: String): LibraryMediaClass = when (mediaType) {
            "IMAGE" -> IMAGES
            "PDF" -> PDFS
            "AUDIO" -> AUDIO
            "VIDEO" -> VIDEO
            "DOCUMENT" -> DOCS
            else -> OTHER
        }
    }
}

enum class LibrarySort { RECENTLY_ADDED, RECENTLY_ANNOTATED, MOST_MEASUREMENTS, NAME }

/** Chips that can be dimmed with a reason. */
sealed interface LibraryChip {
    data class Type(val mediaClass: LibraryMediaClass) : LibraryChip
    data object Annotated : LibraryChip
    data object Orphaned : LibraryChip
    data class Tag(val tag: String) : LibraryChip
}

/** User-visible filter state. Always passed through [LibraryFilterRules.normalize] before reaching a query. */
data class LibraryFilterState(
    val types: Set<LibraryMediaClass> = emptySet(),
    val annotated: Boolean = false,
    val orphaned: Boolean = false,
    val tags: Set<String> = emptySet(),
    val search: String = "",
) {
    val activeChipCount: Int get() = types.size + (if (annotated) 1 else 0) + (if (orphaned) 1 else 0) + tags.size
    val isDefault: Boolean get() = this == LibraryFilterState()
}

enum class LibraryDisabledReason { ANNOTATE_NEEDS_PHOTOS, TYPE_NOT_PHOTO }

/** Pure filter rules (LD-1, LD-3, LD-8, LD-16): shared by the ViewModel, UI and the SQL parity test. */
object LibraryFilterRules {
    const val MIN_SEARCH_CHARS = 2
    const val MAX_TAGS = 5
    const val MAX_SEARCH_TOKENS = 5

    private fun hasNonImageType(state: LibraryFilterState) = state.types.any { it != LibraryMediaClass.IMAGES }
    private fun needsPhotos(state: LibraryFilterState) = state.annotated || state.tags.isNotEmpty()

    /** Why [chip] cannot be toggled on in [state], or null when it can. Chips already on are never disabled. */
    fun disabledReason(chip: LibraryChip, state: LibraryFilterState): LibraryDisabledReason? = when (chip) {
        is LibraryChip.Type ->
            if (chip.mediaClass != LibraryMediaClass.IMAGES && chip.mediaClass !in state.types && needsPhotos(state)) {
                LibraryDisabledReason.ANNOTATE_NEEDS_PHOTOS
            } else null
        LibraryChip.Annotated ->
            if (!state.annotated && hasNonImageType(state)) LibraryDisabledReason.TYPE_NOT_PHOTO else null
        is LibraryChip.Tag ->
            if (chip.tag !in state.tags && hasNonImageType(state)) LibraryDisabledReason.TYPE_NOT_PHOTO else null
        LibraryChip.Orphaned -> null // Annotated + Orphaned is allowed (LD-16)
    }

    /** Applies a tap; a disabled chip leaves the state unchanged. */
    fun toggle(state: LibraryFilterState, chip: LibraryChip): LibraryFilterState {
        if (disabledReason(chip, state) != null) return state
        return when (chip) {
            is LibraryChip.Type -> state.copy(types = state.types.toggled(chip.mediaClass))
            LibraryChip.Annotated -> state.copy(annotated = !state.annotated)
            LibraryChip.Orphaned -> state.copy(orphaned = !state.orphaned)
            is LibraryChip.Tag -> {
                val next = state.tags.toggled(chip.tag)
                if (next.size > MAX_TAGS) state else state.copy(tags = next)
            }
        }
    }

    /**
     * Drops conflicts from restored or deep-linked state: when Annotated or a tag is on, non-image
     * types are removed (photos-only conditions win). Idempotent. Search text is trimmed.
     */
    fun normalize(state: LibraryFilterState): LibraryFilterState {
        var s = state.copy(search = state.search.trim(), tags = state.tags.take(MAX_TAGS).toSet())
        if (needsPhotos(s)) s = s.copy(types = s.types.filterTo(mutableSetOf()) { it == LibraryMediaClass.IMAGES })
        return s
    }

    /** Entry-point rules (LD-8): the Annotated filter clears everything else; a search sets search only. */
    fun fromEntryPoint(filter: LibraryFilter?, search: String?): LibraryFilterState = normalize(
        when {
            filter == LibraryFilter.Annotated -> LibraryFilterState(annotated = true)
            search != null -> LibraryFilterState(search = search)
            else -> LibraryFilterState()
        },
    )

    private fun <T> Set<T>.toggled(v: T): Set<T> = if (v in this) this - v else this + v
}

/** Search definition of LD-3 / LD-7 / ADR-008 Default 4: word-prefix match on every token, ASCII case-insensitive. */
object LibrarySearch {
    private val separators = charArrayOf('_', '-', '.', ',', '/', '"', '[', ']')

    /** Tokens of [text], or empty when the search is inactive (fewer than 2 characters after trimming). */
    fun tokens(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.length < LibraryFilterRules.MIN_SEARCH_CHARS) return emptyList()
        return trimmed.split(Regex("""[\s_\-.,/"\[\]]+""")).filter { it.isNotEmpty() }.take(LibraryFilterRules.MAX_SEARCH_TOKENS)
    }

    /** SQL `LIKE ... ESCAPE '\'` patterns, one per token, padded with nulls to [LibraryFilterRules.MAX_SEARCH_TOKENS]. */
    fun patterns(text: String): List<String?> {
        val p = tokens(text).map { "% ${escapeLike(it)}%" }
        return p + List(LibraryFilterRules.MAX_SEARCH_TOKENS - p.size) { null }
    }

    /**
     * Exact-tag patterns for the tags column, padded with nulls to [LibraryFilterRules.MAX_TAGS]. The column holds
     * raw `["a","b"]` text written without JSON escaping, so a tag matches as stored.
     */
    fun tagPatterns(tags: Collection<String>): List<String?> {
        val p = tags.take(LibraryFilterRules.MAX_TAGS).map { "%\"${escapeLike(it)}\"%" }
        return p + List(LibraryFilterRules.MAX_TAGS - p.size) { null }
    }

    fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Same normalisation as the SQL `hay()`: leading space, separators mapped to spaces, ASCII lower-case. */
    fun hay(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(" ")
        for (c in s) sb.append(if (c in separators) ' ' else asciiLower(c))
        return sb.toString()
    }

    /** True when every token starts a word in at least one of [fields]. */
    fun matches(text: String, fields: List<String?>): Boolean {
        val toks = tokens(text)
        if (toks.isEmpty()) return true
        val hays = fields.map { hay(it) }
        return toks.all { t -> val needle = " " + asciiLower(t); hays.any { it.contains(needle) } }
    }

    fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c
    fun asciiLower(s: String): String = buildString(s.length) { s.forEach { append(asciiLower(it)) } }
}
