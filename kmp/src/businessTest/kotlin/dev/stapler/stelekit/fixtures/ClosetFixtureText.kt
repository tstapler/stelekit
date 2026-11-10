package dev.stapler.stelekit.fixtures

/** Test-side access to the sanitised closet sources; a reader of fixture text, not a production extractor. */
object ClosetFixtureText {
    data class TableCell(val rowKey: String, val header: String, val value: String)

    fun load(name: String): String =
        checkNotNull(ClosetFixtureText::class.java.classLoader.getResourceAsStream("home-sketch/closet/$name")) {
            "fixture $name missing from the test classpath"
        }.bufferedReader().use { it.readText() }

    /** Every non-key cell of every markdown table, keyed by its row's first cell and its column header. */
    fun tableCells(markdown: String): List<TableCell> {
        val cells = ArrayList<TableCell>()
        var header: List<String>? = null
        var previous: List<String>? = null
        for (line in markdown.lines().map { it.trim().removePrefix("- ") }) {
            if (!line.startsWith("|")) {
                header = null
                previous = null
                continue
            }
            val row = line.trim('|').split('|').map { it.trim().replace("**", "") }
            when {
                row.all { it.matches(Regex("-+")) } -> header = previous
                header != null -> row.drop(1).forEachIndexed { i, value -> cells += TableCell(row[0], header[i + 1], value) }
                else -> previous = row
            }
        }
        return cells
    }

    /** Text of every `<text>` element in an svg. */
    fun svgLabels(svg: String): List<String> =
        Regex("<text[^>]*>([^<]*)</text>").findAll(svg).map { it.groupValues[1] }.toList()
}
