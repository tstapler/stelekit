package dev.stapler.stelekit.editor.format

/**
 * Text formatting information
 */
data class TextFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val codeBlock: Boolean = false,
    val quote: Boolean = false,
    val heading: Int? = null, // 1-6 for h1-h6
    val listType: ListType? = null,
    val listItem: Boolean = false,
    val link: String? = null,
    val reference: String? = null,
    val highlight: Boolean = false,
    val textColor: String? = null,
    val backgroundColor: String? = null
) {
    /**
     * Apply this format to a text range
     */
    fun applyToText(text: String): String {
        var result = text
        
        if (bold) result = "**$result**"
        if (italic) result = "*$result*"
        if (code) result = "`$result`"
        if (codeBlock) result = "```\n$result\n```"
        if (quote) result = "> $result"
        if (strikethrough) result = "~~$result~~"
        if (underline) result = "<u>$result</u>"
        if (highlight) result = "==$result=="
        
        heading?.let { level ->
            val prefix = "#".repeat(level)
            result = "$prefix $result"
        }
        
        listType?.let { type ->
            val prefix = when (type) {
                ListType.BULLET -> "- "
                ListType.NUMBERED -> "1. "
                ListType.TODO -> "- [ ] "
                ListType.CHECKED -> "- [x] "
            }
            result = prefix + result
        }
        
        link?.let { url ->
            result = "[$result]($url)"
        }
        
        return result
    }
    
    /**
     * Check if format has any active styling
     */
    fun isEmpty(): Boolean {
        return !bold && !italic && !underline && !strikethrough && 
               !code && !codeBlock && !quote && heading == null &&
               listType == null && !listItem && link == null && 
               reference == null && !highlight && 
               textColor == null && backgroundColor == null
    }
    
    /**
     * Merge with another format (for overlapped selections)
     */
    fun merge(other: TextFormat): TextFormat {
        return TextFormat(
            bold = bold || other.bold,
            italic = italic || other.italic,
            underline = underline || other.underline,
            strikethrough = strikethrough || other.strikethrough,
            code = code || other.code,
            codeBlock = codeBlock || other.codeBlock,
            quote = quote || other.quote,
            heading = heading ?: other.heading,
            listType = listType ?: other.listType,
            listItem = listItem || other.listItem,
            link = link ?: other.link,
            reference = reference ?: other.reference,
            highlight = highlight || other.highlight,
            textColor = textColor ?: other.textColor,
            backgroundColor = backgroundColor ?: other.backgroundColor
        )
    }
}

/**
 * List types
 */
enum class ListType {
    BULLET, NUMBERED, TODO, CHECKED
}

/**
 * Block reference
 */
data class BlockReference(
    val uuid: String,
    val alias: String? = null,
    val startChar: Int,
    val endChar: Int
)

/**
 * Document metadata
 */
data class DocumentMetadata(
    val title: String? = null,
    val tags: List<String> = emptyList(),
    val properties: Map<String, String> = emptyMap()
)

/**
 * Parsed block with formatting
 */
data class ParsedBlock(
    val content: String,
    val format: TextFormat = TextFormat(),
    val children: List<ParsedBlock> = emptyList(),
    val level: Int = 0,
    val properties: Map<String, String> = emptyMap(),
    val references: List<BlockReference> = emptyList()
)

/**
 * Parsed document structure
 */
data class ParsedDocument(
    val blocks: List<ParsedBlock>,
    val metadata: DocumentMetadata = DocumentMetadata()
)

/**
 * Display document for rendering
 */
data class DisplayDocument(
    val blocks: List<DisplayBlock>,
    val metadata: DocumentMetadata
)

/**
 * Display block with styling
 */
data class DisplayBlock(
    val content: String,
    val style: BlockStyle,
    val children: List<DisplayBlock> = emptyList(),
    val level: Int = 0
)

/**
 * Block styling information
 */
data class BlockStyle(
    val textFormat: TextFormat,
    val marginLeft: Int = 0,
    val marginTop: Int = 0,
    val marginBottom: Int = 0,
    val backgroundColor: String? = null
)

/**
 * Markdown validation result
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<ValidationError> = emptyList(),
    val warnings: List<ValidationWarning> = emptyList()
)

/**
 * Validation error
 */
data class ValidationError(
    val message: String,
    val position: Int,
    val severity: ErrorSeverity
)

/**
 * Validation warning
 */
data class ValidationWarning(
    val message: String,
    val position: Int
)

/**
 * Error severity levels
 */
enum class ErrorSeverity {
    ERROR, WARNING, INFO
}