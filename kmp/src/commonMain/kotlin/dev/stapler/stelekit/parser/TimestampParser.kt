package dev.stapler.stelekit.parser

/**
 * Parsed timestamp data.
 */
data class TimestampResult(
    val content: String,
    val scheduled: String?,
    val deadline: String?
)

/**
 * Parser for Logseq timestamp formats (SCHEDULED, DEADLINE).
 */
object TimestampParser {
    // Regex to match SCHEDULED: <date> or DEADLINE: <date>
    // Matches:
    // 1. "SCHEDULED:" or "DEADLINE:"
    // 2. Whitespace
    // 3. "<"
    // 4. Content inside <> (lazy)
    // 5. ">"
    private val timestampRegex = Regex("""\b(SCHEDULED|DEADLINE):\s*<([^>]+)>""")

    fun parse(content: String): TimestampResult {
        var currentContent = content
        var scheduled: String? = null
        var deadline: String? = null

        // Find all matches
        val matches = timestampRegex.findAll(content).toList()
        
        // Process matches
        for (match in matches) {
            val type = match.groupValues[1]
            val date = match.groupValues[2]
            
            when (type) {
                "SCHEDULED" -> scheduled = date
                "DEADLINE" -> deadline = date
            }
        }
        
        // Remove matches from content
        if (matches.isNotEmpty()) {
            currentContent = timestampRegex.replace(content, "").trim()
        }

        return TimestampResult(
            content = currentContent,
            scheduled = scheduled,
            deadline = deadline
        )
    }
}
