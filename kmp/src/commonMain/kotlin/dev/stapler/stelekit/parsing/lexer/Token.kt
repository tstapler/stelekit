package dev.stapler.stelekit.parsing.lexer

enum class TokenType {
    // Structural
    EOF,
    NEWLINE,        // \n
    INDENT,         // Spaces/Tabs at start of line
    BULLET,         // - or * or + (at start of line after indent)
    
    // Inline Formatting Markers
    STAR,           // *
    UNDERSCORE,     // _
    TILDE,          // ~
    BACKTICK,       // `
    
    // Links & References
    L_BRACKET,      // [
    R_BRACKET,      // ]
    L_PAREN,        // (
    R_PAREN,        // )
    BLOCK_REF_OPEN, // ((
    BLOCK_REF_CLOSE, // ))
    HASH,           // #
    
    // Properties
    COLON,          // :

    // Extended Operators & Punctuation
    EQ,             // = (runs)
    EXCLAMATION,    // !
    L_ANGLE,        // <
    R_ANGLE,        // >
    L_BRACE,        // {
    R_BRACE,        // }
    MACRO_OPEN,     // {{
    MACRO_CLOSE,    // }}
    CARET,          // ^
    BACKSLASH,      // \
    PIPE,           // |

    // Text Content
    TEXT,           // Normal text
    WS              // Whitespace (that isn't a newline or indent)
}

/**
 * Represents a lexical token.
 * Holds indices into the source text to avoid string allocation (Zero-Copy).
 */
data class Token(
    val type: TokenType,
    val start: Int,
    val end: Int
) {
    fun text(source: CharSequence): CharSequence {
        // Bounds check to prevent crashes and provide better error messages
        val safeStart = start.coerceIn(0, source.length)
        val safeEnd = end.coerceIn(safeStart, source.length)
        return source.subSequence(safeStart, safeEnd)
    }
}
