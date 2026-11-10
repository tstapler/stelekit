package dev.stapler.stelekit.projectbridge

/** Table header spellings the extractor normalises before the registry sees them. */
internal object HeaderAliases {
    /** Headers that name the row key column (`Zone`, `Measurement`); otherwise column 0 is the key. */
    private val ROW_KEY_HEADERS = setOf("zone", "measurement", "item", "name", "dimension", "part", "component")

    private val UNIT_SUFFIX = Regex("""\s*\((?:in|inch|inches)\)\s*$""", RegexOption.IGNORE_CASE)

    /** Removes bold markers and surrounding whitespace. */
    fun clean(raw: String): String = raw.replace("**", "").trim()

    /** `Width (in)` -> `Width`; used as the second registry lookup when the literal header has no alias. */
    fun withoutUnit(header: String): String = UNIT_SUFFIX.replace(clean(header), "")

    fun keyColumn(headers: List<String>): Int =
        headers.indexOfFirst { withoutUnit(it).lowercase() in ROW_KEY_HEADERS }.let { if (it < 0) 0 else it }
}
