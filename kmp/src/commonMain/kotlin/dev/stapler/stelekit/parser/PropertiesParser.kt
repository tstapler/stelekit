package dev.stapler.stelekit.parser

/**
 * Parsed properties result.
 */
data class PropertiesResult(
    val content: String,
    val properties: Map<String, String>
)

/**
 * Parser for Logseq property formats (Drawers and Inline).
 */
object PropertiesParser {
    // Regex for inline properties: key:: value
    // Matches start of line, optional whitespace, key, ::, optional whitespace, value
    private val propertyRegex = Regex("""^\s*([\w\-_]+)::\s*(.*)$""")
    
    // Markers for property drawer
    private const val DRAWER_START = ":PROPERTIES:"
    private const val DRAWER_END = ":END:"

    fun parse(content: String): PropertiesResult {
        val lines = content.lines()
        val properties = mutableMapOf<String, String>()
        val finalContentLines = mutableListOf<String>()
        
        var inDrawer = false
        var drawerProcessed = false

        for (line in lines) {
            val trimmedLine = line.trim()
            
            // Check for drawer start
            if (!drawerProcessed && trimmedLine == DRAWER_START) {
                inDrawer = true
                continue
            }
            
            // Check for drawer end
            if (inDrawer && trimmedLine == DRAWER_END) {
                inDrawer = false
                drawerProcessed = true
                continue
            }
            
            if (inDrawer) {
                // Inside drawer, parse property
                parsePropertyLine(line, properties)
            } else {
                // Outside drawer, check for inline property or content
                // Note: Logseq typically treats the first block's properties as page properties
                // and they can be anywhere, but usually at the start.
                // However, inline properties (key:: value) remove the line from content.
                if (isPropertyLine(line)) {
                    parsePropertyLine(line, properties)
                } else {
                    finalContentLines.add(line)
                }
            }
        }

        return PropertiesResult(
            content = finalContentLines.joinToString("\n").trim(),
            properties = properties
        )
    }

    private fun isPropertyLine(line: String): Boolean {
        return propertyRegex.matches(line)
    }

    private fun parsePropertyLine(line: String, properties: MutableMap<String, String>) {
        val match = propertyRegex.matchEntire(line)
        if (match != null) {
            val (key, value) = match.destructured
            properties[key] = value
        }
    }
}
