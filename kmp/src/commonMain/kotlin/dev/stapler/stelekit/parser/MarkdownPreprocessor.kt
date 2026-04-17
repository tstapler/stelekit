package dev.stapler.stelekit.parser

/**
 * Preprocesses Markdown content to ensure compatibility with strict CommonMark parsers.
 * 
 * Main responsibility: Normalizing indentation.
 * Logseq (and many users) use 2-space indentation for lists, but strict CommonMark 
 * often requires 4 spaces (or 2 spaces relative to the bullet) for nested lists.
 * To ensure consistent AST generation, we normalize known list patterns to a safe indentation.
 */
object MarkdownPreprocessor {

    fun normalize(content: String): String {
        val lines = content.lines()
        val normalizedLines = mutableListOf<String>()
        
        var lastLevel = 0
        
        for (line in lines) {
            if (line.isBlank()) {
                normalizedLines.add(line)
                continue
            }

            val trimmedStart = line.trimStart()
            
            // If it's a list item
            if (isListItem(trimmedStart)) {
                val level = calculateLevel(line)
                lastLevel = level
                
                // CommonMark standard is often 4 spaces for a sub-block.
                val newIndentation = "    ".repeat(level)
                normalizedLines.add(newIndentation + trimmedStart)
            } else {
                // For non-list items (continuation text, properties, etc.)
                // Indent them to match the current block level so they stay inside the block (or at least inside the list)
                // We add a slight extra indent (2 spaces) to make them part of the previous item content in CommonMark
                // or just align with the bullet.
                // In CommonMark, continuation lines must be indented past the bullet.
                // Bullet is at `level * 4`. Content starts at `level * 4 + 2` (approx).
                // Let's indent them to `level * 4 + 4` to be safe?
                
                val newIndentation = "    ".repeat(lastLevel) + "    " 
                normalizedLines.add(newIndentation + trimmedStart)
            }
        }
        
        return normalizedLines.joinToString("\n")
    }
    
    private fun isListItem(trimmedLine: String): Boolean {
        // Matches "- ", "* ", "+ ", "1. "
        return trimmedLine.startsWith("- ") || 
               trimmedLine.startsWith("* ") || 
               trimmedLine.startsWith("+ ") || 
               (trimmedLine.isNotEmpty() && trimmedLine[0].isDigit() && trimmedLine.contains(". "))
    }
    
    private fun calculateLevel(line: String): Int {
        var spaces = 0
        var tabs = 0
        
        for (char in line) {
            when (char) {
                ' ' -> spaces++
                '\t' -> tabs++
                else -> break
            }
        }
        
        // Logic: 1 tab = 1 level
        // 2 spaces = 1 level
        // But we need to handle mixed? Usually it's one or the other.
        // Let's assume tabs take precedence if present, or add to spaces.
        // A tab is usually 2 or 4 spaces visually.
        
        // FIX: Round UP for odd spaces or treat 1 space as level 0 unless it's strictly > 0?
        // Actually, if spaces=1, spaces/2 = 0.
        // If the user indented 1 space, they probably meant level 1 if previous was level 0.
        // But 2 spaces is standard.
        // Let's change the rounding or threshold.
        // If spaces % 2 != 0, it's ambiguous.
        
        // Attempt to support 1-space indentation:
        // If we strictly follow 2-space rule:
        // 0->0, 1->0, 2->1, 3->1, 4->2
        
        // If we want 1 space to count as a level?
        // return tabs + spaces  <-- This would make 2 spaces = level 2 (8 spaces normalized). TOO MUCH.
        
        // What if we check if spaces is odd?
        // If spaces is 1, maybe treat as 1?
        // But 3 spaces? Is that level 1.5?
        
        // Let's try: (spaces + 1) / 2
        // 0 -> 0
        // 1 -> 1
        // 2 -> 1
        // 3 -> 2
        // 4 -> 2
        // 5 -> 3
        
        return tabs + ((spaces + 1) / 2)
    }
}
