package dev.stapler.stelekit.editor.format

import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.Flow
import kotlin.Result

/**
 * Interface for text formatting and processing in editor.
 * Handles rich text formatting, markdown processing, and text transformations.
 */
interface IFormatProcessor {
    
    /**
     * Process raw text into formatted display text.
     * 
     * @param text Raw input text
     * @param formatType Target format (markdown, rich text, etc.)
     * @return Result containing formatted text or error
     */
    suspend fun processText(text: String, formatType: FormatType): Result<FormattedText>
    
    /**
     * Parse formatted text back to raw text.
     * 
     * @param formattedText Formatted text
     * @param sourceFormat Source format type
     * @return Result containing raw text or error
     */
    suspend fun parseFormattedText(formattedText: FormattedText, sourceFormat: FormatType): Result<String>
    
    /**
     * Apply text formatting at specified range.
     * 
     * @param text Original text
     * @param range Range to apply formatting
     * @param formatting Formatting to apply
     * @return Result containing formatted text or error
     */
    suspend fun applyFormatting(text: String, range: TextRange, formatting: TextFormatting): Result<String>
    
    /**
     * Remove formatting from text.
     * 
     * @param text Formatted text
     * @param range Range to remove formatting (null for all)
     * @return Result containing plain text or error
     */
    suspend fun removeFormatting(text: String, range: TextRange? = null): Result<String>
    
    /**
     * Detect text formatting in a string.
     * 
     * @param text Text to analyze
     * @return Result containing detected formatting ranges
     */
    suspend fun detectFormatting(text: String): Result<List<FormattedRange>>
    
    /**
     * Convert between different text formats.
     * 
     * @param text Text to convert
     * @param fromFormat Source format
     * @param toFormat Target format
     * @return Result containing converted text or error
     */
    suspend fun convertFormat(text: String, fromFormat: FormatType, toFormat: FormatType): Result<String>
    
    /**
     * Validate text format syntax.
     * 
     * @param text Text to validate
     * @param formatType Format to validate against
     * @return Result containing validation result
     */
    suspend fun validateFormat(text: String, formatType: FormatType): Result<FormatValidation>
}

/**
 * Text format types supported by the processor.
 */
enum class FormatType {
    PLAIN_TEXT,
    MARKDOWN,
    RICH_TEXT,
    HTML,
    ORG_MODE,
    // Text formatting types
    BOLD,
    ITALIC,
    CODE,
    LINK,
    STRIKETHROUGH,
    HEADING,
    LIST,
    QUOTE
}

/**
 * Container for formatted text with metadata.
 */
data class FormattedText(
    val text: String,
    val formatType: FormatType,
    val formatting: List<FormattedRange> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Represents a range of text with specific formatting.
 */
data class FormattedRange(
    val range: TextRange,
    val formatting: TextFormatting,
    val attributes: Map<String, Any> = emptyMap()
)

/**
 * Text formatting information.
 */
data class TextFormatting(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val color: String? = null,
    val backgroundColor: String? = null,
    val fontSize: Int? = null,
    val fontFamily: String? = null,
    val link: String? = null,
    val heading: Int? = null // 1-6 for heading levels
)

/**
 * Text range representation.
 */
data class TextRange(
    val start: Int,
    val end: Int
) {
    val length: Int get() = end - start + 1
    
    companion object {
        fun all(length: Int): TextRange = TextRange(0, length - 1)
    }
}

/**
 * Format validation result.
 */
data class FormatValidation(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)
