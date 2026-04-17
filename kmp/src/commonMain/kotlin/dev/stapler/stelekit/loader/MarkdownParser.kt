package dev.stapler.stelekit.loader

import dev.stapler.stelekit.model.ParsedPage
import dev.stapler.stelekit.model.ParsedBlock

/**
 * Interface for parsing Logseq-flavored Markdown content.
 */
interface MarkdownParser {
    /**
     * Parses a full markdown file content into a ParsedPage structure.
     * 
     * @param content The raw string content of the file.
     * @return A ParsedPage containing the title (if found/inferred), properties, and block hierarchy.
     */
    fun parse(content: String): ParsedPage
    
    /**
     * Parses a single block string into a ParsedBlock.
     * Useful for partial updates or streaming.
     * 
     * @param rawBlock The raw block string (including bullet point).
     * @return The structured ParsedBlock.
     */
    fun parseBlock(rawBlock: String): ParsedBlock
}
