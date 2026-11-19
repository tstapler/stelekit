package dev.stapler.stelekit.sketch.v2

/**
 * Parses the chrome table (the generated [ChromeTableFixture.TABLE] constant) into typed rows. Tests that
 * quote a layout figure or a banner/toast string read it from here and never type the value themselves.
 * A malformed row throws [IllegalArgumentException] naming the section and line.
 */
data class ChromeBanner(
    val rank: Int,
    val id: String,
    val tier: String,
    val summary: String,
    val shortTitle: String?,
    val primaryLabel: String?,
    val moreItems: List<String>,
    val primaryAccessibleName: String?,
)

data class ChromeToast(
    val id: String,
    val summary: String,
    val shortTitle: String,
    val primaryLabel: String?,
    val moreItems: List<String>,
    val primaryAccessibleName: String?,
)

data class ChromeSized(val name: String, val sizeDp: Int)

data class ChromeTool(val id: String, val group: String?)

data class ChromeWindow(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Double,
    val guaranteed: Boolean,
)

class ChromeTable internal constructor(
    val params: Map<String, Int>,
    private val sections: Map<String, List<List<String>>>,
) {
    /** Raw cells of a section, for sections without a typed view. */
    fun raw(section: String): List<List<String>> =
        sections[section] ?: throw IllegalArgumentException("chrome table has no [$section] section")

    fun param(name: String): Int =
        params[name] ?: throw IllegalArgumentException("chrome table has no param $name")

    val pinned: List<ChromeSized> get() = raw("pinned").map { ChromeSized(it[0], it[1].toInt()) }
    val strip: List<ChromeSized> get() = raw("strip").map { ChromeSized(it[0], it[1].toInt()) }
    val tools: List<ChromeTool> get() = raw("tools").map { ChromeTool(it[0], it[1].orNull()) }
    val windows: List<ChromeWindow> get() = raw("windows").map {
        ChromeWindow(it[0], it[1].toInt(), it[2].toInt(), it[3].toDouble(), it[4] == "yes")
    }
    val banners: List<ChromeBanner> get() = raw("banner").map {
        ChromeBanner(it[0].toInt(), it[1], it[2], it[3], it[4].orNull(), it[5].orNull(), items(it[6]), it[7].orNull())
    }
    val toasts: List<ChromeToast> get() = raw("toast").map {
        ChromeToast(it[0], it[1], it[2], it[3].orNull(), items(it[4]), it[5].orNull())
    }

    private fun String.orNull(): String? = if (this == "-") null else this
    private fun items(cell: String): List<String> = cell.split(';').map { it.trim() }.filter { it.isNotEmpty() && it != "-" }
}

object ChromeTableParser {
    private val columnCounts = mapOf(
        "pinned" to 2, "strip" to 2, "tools" to 2, "groups" to 2, "topbar" to 5, "rows" to 5,
        "permanent" to 2, "windows" to 5, "banner" to 8, "toast" to 6,
    )

    fun parseFixture(): ChromeTable = parse(ChromeTableFixture.TABLE)

    fun parse(text: String): ChromeTable {
        val params = linkedMapOf<String, Int>()
        val sections = linkedMapOf<String, MutableList<List<String>>>()
        var current: String? = null
        text.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val where = "line ${index + 1}"
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("[") && line.endsWith("]") -> {
                    val name = line.substring(1, line.length - 1)
                    current = name
                    if (name != "params") sections.getOrPut(name) { mutableListOf() }
                }
                else -> {
                    val section = current ?: throw IllegalArgumentException("$where: row before any [section]: $line")
                    if (section == "params") addParam(params, line, where) else addRow(sections.getValue(section), section, line, where)
                }
            }
        }
        return ChromeTable(params, sections)
    }

    private fun addParam(params: MutableMap<String, Int>, line: String, where: String) {
        val eq = line.indexOf('=')
        require(eq > 0) { "$where: [params] row needs 'name = value': $line" }
        val name = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim().toIntOrNull()
            ?: throw IllegalArgumentException("$where: [params] $name is not an integer: $line")
        require(params.put(name, value) == null) { "$where: duplicate param $name" }
    }

    private fun addRow(rows: MutableList<List<String>>, section: String, line: String, where: String) {
        val cells = line.split('|').map { it.trim() }
        val expected = columnCounts[section]
        require(expected == null || cells.size == expected) {
            "$where: [$section] row has ${cells.size} columns, expected $expected: $line"
        }
        rows.add(cells)
    }

    /** FNV-1a 64 over the UTF-8 bytes, 16 lowercase hex digits; the generator computes the same value. */
    fun contentHash(text: String): String {
        var h = -3750763034362895579L // 0xCBF29CE484222325
        for (b in text.encodeToByteArray()) {
            h = (h xor (b.toLong() and 0xFF)) * 0x100000001B3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }
}
