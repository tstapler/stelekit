package dev.stapler.stelekit.outliner

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Validation

/**
 * Port of the ClojureScript outliner pipeline.
 * Responsible for processing blocks, extracting references, and validating content.
 */
class OutlinerPipeline {

    companion object {
        private val PAGE_REF_REGEX = Regex("\\[\\[([^\\]]+)\\]\\]")
        private val BLOCK_REF_REGEX = Regex("\\(\\(([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\)\\)")
        private val TAG_REGEX = Regex("(?:^|\\s)#([^\\s#]+)")
        private val PROPERTY_REGEX = Regex("^([a-zA-Z0-9_-]+)::\\s*(.*)$")
    }

    /**
     * Processes a block through the transformation pipeline.
     */
    fun processBlock(block: Block): Block {
        val validatedContent = Validation.validateContent(block.content)
        val properties = parseProperties(validatedContent)
        
        // Strip properties from content for the processed version
        val contentWithoutProperties = stripProperties(validatedContent)
        
        return block.copy(
            content = contentWithoutProperties,
            properties = block.properties + properties
        )
    }

    /**
     * Parses properties from block content.
     * Properties are lines starting with 'key:: value' at the beginning of the block.
     */
    fun parseProperties(content: String): Map<String, String> {
        val properties = mutableMapOf<String, String>()
        val lines = content.lines()
        
        for (line in lines) {
            val trimmedLine = line.trim()
            val match = PROPERTY_REGEX.matchEntire(trimmedLine)
            if (match != null) {
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2].trim()
                properties[key] = value
            } else if (trimmedLine.isEmpty()) {
                continue
            } else {
                break
            }
        }
        return properties
    }

    /**
     * Strips properties from the beginning of the content.
     */
    fun stripProperties(content: String): String {
        val lines = content.lines()
        var startIndex = 0
        
        for (i in lines.indices) {
            val trimmedLine = lines[i].trim()
            if (PROPERTY_REGEX.matches(trimmedLine)) {
                startIndex = i + 1
            } else if (trimmedLine.isEmpty()) {
                startIndex = i + 1
            } else {
                startIndex = i
                break
            }
        }
        
        return lines.drop(startIndex).joinToString("\n").trim()
    }

    /**
     * Extracts all references (pages, blocks, tags) from a block's content.
     */
    fun extractReferences(block: Block): BlockReferences {
        val content = block.content
        val properties = block.properties
        
        val pageRefs = mutableSetOf<String>()
        val blockRefs = mutableSetOf<String>()
        val tags = mutableSetOf<String>()

        fun extractFromText(text: String, isTagsProperty: Boolean = false) {
            PAGE_REF_REGEX.findAll(text).forEach { 
                val name = it.groupValues[1].trim()
                pageRefs.add(name)
                if (isTagsProperty) tags.add(name)
            }
            BLOCK_REF_REGEX.findAll(text).forEach { blockRefs.add(it.groupValues[1]) }
            TAG_REGEX.findAll(text).forEach { tags.add(it.groupValues[1]) }
        }

        extractFromText(content)
        
        // Also extract from properties
        properties.forEach { (key, value) ->
            val isTags = key == "tags"
            val isAlias = key == "alias"
            
            if (isTags || isAlias) {
                extractFromText(value, isTagsProperty = isTags)
                // Also handle comma separated plain tags/aliases
                value.split(",").forEach {
                    val trimmed = it.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("[[") && !trimmed.startsWith("#")) {
                        if (isTags) tags.add(trimmed)
                        else pageRefs.add(trimmed)
                    }
                }
            } else {
                extractFromText(value)
            }
        }

        return BlockReferences(
            pages = pageRefs,
            blocks = blockRefs,
            tags = tags
        )
    }
}

data class BlockReferences(
    val pages: Set<String>,
    val blocks: Set<String>,
    val tags: Set<String>
)
