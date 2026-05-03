package dev.stapler.stelekit.editor.commands

import dev.stapler.stelekit.editor.commands.*
import kotlinx.coroutines.CancellationException

/**
 * Collection of essential editor commands
 */
object EssentialCommands {
    
    /**
     * Text formatting commands
     */
    object TextFormatting {
        val bold = EditorCommand(
            id = "text.bold",
            type = CommandType.TEXT,
            label = "Bold", 
            description = "Format selected text as bold",
            shortcut = "Ctrl+B",
            icon = "bold",
            config = CommandConfig(
                requiresSelection = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    val selectedText = context.currentText.substring(context.selectionStart, context.selectionEnd)
                    val formattedText = "**$selectedText**"
                    CommandResult.Success(
                        message = "Text formatted as bold",
                        data = mapOf<String, Any>(
                            "formattedText" to formattedText,
                            "selectionStart" to context.selectionStart,
                            "selectionEnd" to context.selectionEnd
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to format text as bold: ${e.message}", e)
                }
            }
        )
        
        val italic = EditorCommand(
            id = "text.italic",
            type = CommandType.TEXT,
            label = "Italic",
            description = "Format selected text as italic",
            shortcut = "Ctrl+I",
            icon = "italic",
            config = CommandConfig(
                requiresSelection = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    val selectedText = context.currentText.substring(context.selectionStart, context.selectionEnd)
                    val formattedText = "*$selectedText*"
                    CommandResult.Success(
                        message = "Text formatted as italic",
                        data = mapOf<String, Any>(
                            "formattedText" to formattedText,
                            "selectionStart" to context.selectionStart,
                            "selectionEnd" to context.selectionEnd
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to format text as italic: ${e.message}", e)
                }
            }
        )
        
        val code = EditorCommand(
            id = "text.code",
            type = CommandType.TEXT,
            label = "Code",
            description = "Format selected text as inline code",
            shortcut = "Ctrl+`",
            icon = "code",
            config = CommandConfig(
                requiresSelection = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    val selectedText = context.currentText.substring(context.selectionStart, context.selectionEnd)
                    val formattedText = "`$selectedText`"
                    CommandResult.Success(
                        message = "Text formatted as code",
                        data = mapOf<String, Any>(
                            "formattedText" to formattedText,
                            "selectionStart" to context.selectionStart,
                            "selectionEnd" to context.selectionEnd
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to format text as code: ${e.message}", e)
                }
            }
        )
        
        val strikethrough = EditorCommand(
            id = "text.strikethrough",
            type = CommandType.TEXT,
            label = "Strikethrough",
            description = "Format selected text as strikethrough",
            shortcut = "Ctrl+Shift+S",
            icon = "strikethrough",
            config = CommandConfig(
                requiresSelection = true,
                priority = CommandPriority.NORMAL
            ),
            execute = { context ->
                try {
                    val selectedText = context.currentText.substring(context.selectionStart, context.selectionEnd)
                    val formattedText = "~~$selectedText~~"
                    CommandResult.Success(
                        message = "Text formatted as strikethrough",
                        data = mapOf<String, Any>(
                            "formattedText" to formattedText,
                            "selectionStart" to context.selectionStart,
                            "selectionEnd" to context.selectionEnd
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to format text as strikethrough: ${e.message}", e)
                }
            }
        )
        
        val highlight = EditorCommand(
            id = "text.highlight",
            type = CommandType.TEXT,
            label = "Highlight",
            description = "Highlight selected text",
            shortcut = "Ctrl+Shift+H",
            icon = "highlight",
            config = CommandConfig(
                requiresSelection = true,
                priority = CommandPriority.NORMAL
            ),
            execute = { context ->
                try {
                    val selectedText = context.currentText.substring(context.selectionStart, context.selectionEnd)
                    val formattedText = "==$selectedText=="
                    CommandResult.Success(
                        message = "Text highlighted",
                        data = mapOf<String, Any>(
                            "formattedText" to formattedText,
                            "selectionStart" to context.selectionStart,
                            "selectionEnd" to context.selectionEnd
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to highlight text: ${e.message}", e)
                }
            }
        )
        
        val link = EditorCommand(
            id = "text.link",
            type = CommandType.TEXT,
            label = "Link",
            description = "Create a link from selected text",
            shortcut = "Ctrl+K",
            icon = "link",
            config = CommandConfig(
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    val selectedText = if (context.selectionStart < context.selectionEnd) {
                        context.currentText.substring(context.selectionStart, context.selectionEnd)
                    } else {
                        ""
                    }
                    
                    val linkText = if (selectedText.isNotEmpty()) selectedText else "link text"
                    val linkUrl = "https://example.com"
                    val formattedText = "[$linkText]($linkUrl)"
                    
                    CommandResult.Success(
                        message = "Link created",
                        data = mapOf<String, Any>(
                            "formattedText" to formattedText,
                            "selectionStart" to context.selectionStart,
                            "selectionEnd" to context.selectionEnd,
                            "requiresUrlInput" to true
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to create link: ${e.message}", e)
                }
            }
        )
        
        val heading = EditorCommand(
            id = "text.heading",
            type = CommandType.TEXT,
            label = "Heading",
            description = "Toggle heading level",
            shortcut = "Ctrl+Shift+1",
            icon = "heading",
            config = CommandConfig(
                priority = CommandPriority.NORMAL
            ),
            execute = { context ->
                try {
                    val lineStart = context.currentText.lastIndexOf('\n', context.selectionStart) + 1
                    val lineEnd = context.currentText.indexOf('\n', context.selectionEnd)
                    val actualLineEnd = if (lineEnd == -1) context.currentText.length else lineEnd
                    
                    val line = context.currentText.substring(lineStart, actualLineEnd).trim()
                    val headingLevel = when {
                        line.startsWith("######") -> 5
                        line.startsWith("#####") -> 4
                        line.startsWith("####") -> 3
                        line.startsWith("###") -> 2
                        line.startsWith("##") -> 1
                        else -> 0
                    }
                    
                    val newLevel = (headingLevel + 1) % 7 // 0-6 levels
                    val prefix = "#".repeat(newLevel)
                    val newLine = if (newLevel > 0) "$prefix $line" else line.substringAfter("#").trim()
                    
                    CommandResult.Success(
                        message = "Heading level set to $newLevel",
                        data = mapOf<String, Any>(
                            "lineStart" to lineStart,
                            "lineEnd" to actualLineEnd,
                            "newLine" to newLine
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to set heading: ${e.message}", e)
                }
            }
        )
    }
    
    /**
     * Block manipulation commands
     */
    object BlockCommands {
        val newBlock = EditorCommand(
            id = "block.new",
            type = CommandType.BLOCK,
            label = "New Block",
            description = "Create a new block",
            shortcut = "Enter",
            icon = "plus",
            config = CommandConfig(
                requiresBlock = true,
                priority = CommandPriority.HIGHEST
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "New block created",
                        data = mapOf<String, Any>(
                            "blockId" to (context.currentBlockId ?: ""),
                            "position" to "after"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to create new block: ${e.message}", e)
                }
            }
        )
        
        val delete = EditorCommand(
            id = "block.delete",
            type = CommandType.BLOCK,
            label = "Delete Block",
            description = "Delete current block",
            shortcut = "Ctrl+Shift+Backspace",
            icon = "trash",
            config = CommandConfig(
                requiresBlock = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Block deleted",
                        data = mapOf<String, Any>(
                            "blockId" to (context.currentBlockId ?: "")
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to delete block: ${e.message}", e)
                }
            }
        )
        
        val moveUp = EditorCommand(
            id = "block.move-up",
            type = CommandType.BLOCK,
            label = "Move Block Up",
            description = "Move current block up",
            shortcut = "Ctrl+Shift+Up",
            icon = "arrow-up",
            config = CommandConfig(
                requiresBlock = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Block moved up",
                        data = mapOf<String, Any>(
                            "blockId" to (context.currentBlockId ?: ""),
                            "direction" to "up"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to move block up: ${e.message}", e)
                }
            }
        )
        
        val moveDown = EditorCommand(
            id = "block.move-down",
            type = CommandType.BLOCK,
            label = "Move Block Down",
            description = "Move current block down",
            shortcut = "Ctrl+Shift+Down",
            icon = "arrow-down",
            config = CommandConfig(
                requiresBlock = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Block moved down",
                        data = mapOf<String, Any>(
                            "blockId" to (context.currentBlockId ?: ""),
                            "direction" to "down"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to move block down: ${e.message}", e)
                }
            }
        )
        
        val indent = EditorCommand(
            id = "block.indent",
            type = CommandType.BLOCK,
            label = "Indent Block",
            description = "Indent current block",
            shortcut = "Tab",
            icon = "indent",
            config = CommandConfig(
                requiresBlock = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Block indented",
                        data = mapOf<String, Any>(
                            "blockId" to (context.currentBlockId ?: ""),
                            "level" to "increment"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to indent block: ${e.message}", e)
                }
            }
        )
        
        val outdent = EditorCommand(
            id = "block.outdent",
            type = CommandType.BLOCK,
            label = "Outdent Block",
            description = "Outdent current block",
            shortcut = "Shift+Tab",
            icon = "outdent",
            config = CommandConfig(
                requiresBlock = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Block outdented",
                        data = mapOf<String, Any>(
                            "blockId" to (context.currentBlockId ?: ""),
                            "level" to "decrement"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to outdent block: ${e.message}", e)
                }
            }
        )
        
        val toggleTodo = EditorCommand(
            id = "block.toggle-todo",
            type = CommandType.BLOCK,
            label = "Toggle Todo",
            description = "Toggle todo status",
            shortcut = "Ctrl+Enter",
            icon = "check",
            config = CommandConfig(
                requiresBlock = true,
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    val content = context.currentBlockContent ?: ""
                    val newContent = when {
                        content.startsWith("TODO ") -> content.replaceFirst("TODO ", "DONE ")
                        content.startsWith("DOING ") -> content.replaceFirst("DOING ", "DONE ")
                        content.startsWith("DONE ") -> content.replaceFirst("DONE ", "TODO ")
                        else -> "TODO $content"
                    }
                    
                    CommandResult.Success(
                        message = "Todo status toggled",
                        data = mapOf<String, Any>(
                            "blockId" to (context.currentBlockId ?: ""),
                            "oldContent" to content,
                            "newContent" to newContent
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to toggle todo: ${e.message}", e)
                }
            }
        )
    }
    
    /**
     * Navigation commands
     */
    object NavigationCommands {
        val search = EditorCommand(
            id = "navigation.search",
            type = CommandType.NAVIGATION,
            label = "Search",
            description = "Open search dialog",
            shortcut = "Ctrl+Shift+F",
            icon = "search",
            config = CommandConfig(
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Search opened",
                        data = mapOf<String, Any>(
                            "action" to "openSearch",
                            "query" to ""
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to open search: ${e.message}", e)
                }
            }
        )
        
        val gotoPage = EditorCommand(
            id = "navigation.goto-page",
            type = CommandType.NAVIGATION,
            label = "Go to Page",
            description = "Open page selector",
            shortcut = "Ctrl+P",
            icon = "file",
            config = CommandConfig(
                priority = CommandPriority.HIGH
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Go to page opened",
                        data = mapOf<String, Any>(
                            "action" to "openPageSelector"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to open page selector: ${e.message}", e)
                }
            }
        )
        
        val back = EditorCommand(
            id = "navigation.back",
            type = CommandType.NAVIGATION,
            label = "Back",
            description = "Navigate back",
            shortcut = "Alt+Left",
            icon = "arrow-left",
            config = CommandConfig(
                priority = CommandPriority.NORMAL
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Navigated back",
                        data = mapOf<String, Any>(
                            "action" to "navigateBack"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to navigate back: ${e.message}", e)
                }
            }
        )
        
        val forward = EditorCommand(
            id = "navigation.forward",
            type = CommandType.NAVIGATION,
            label = "Forward",
            description = "Navigate forward",
            shortcut = "Alt+Right",
            icon = "arrow-right",
            config = CommandConfig(
                priority = CommandPriority.NORMAL
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Navigated forward",
                        data = mapOf<String, Any>(
                            "action" to "navigateForward"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to navigate forward: ${e.message}", e)
                }
            }
        )
    }
    
    /**
     * System commands
     */
    object SystemCommands {
        val save = EditorCommand(
            id = "system.save",
            type = CommandType.SYSTEM,
            label = "Save",
            description = "Save current document",
            shortcut = "Ctrl+S",
            icon = "save",
            config = CommandConfig(
                priority = CommandPriority.HIGHEST,
                async = true
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Document saved",
                        data = mapOf<String, Any>(
                            "action" to "save",
                            "timestamp" to kotlin.time.Clock.System.now()
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to save: ${e.message}", e)
                }
            }
        )
        
        val undo = EditorCommand(
            id = "system.undo",
            type = CommandType.SYSTEM,
            label = "Undo",
            description = "Undo last action",
            shortcut = "Ctrl+Z",
            icon = "undo",
            config = CommandConfig(
                priority = CommandPriority.HIGHEST
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Undo performed",
                        data = mapOf<String, Any>(
                            "action" to "undo"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to undo: ${e.message}", e)
                }
            }
        )
        
        val redo = EditorCommand(
            id = "system.redo",
            type = CommandType.SYSTEM,
            label = "Redo",
            description = "Redo last undone action",
            shortcut = "Ctrl+Y",
            icon = "redo",
            config = CommandConfig(
                priority = CommandPriority.HIGHEST
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Redo performed",
                        data = mapOf<String, Any>(
                            "action" to "redo"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to redo: ${e.message}", e)
                }
            }
        )
        
        val settings = EditorCommand(
            id = "system.settings",
            type = CommandType.SYSTEM,
            label = "Settings",
            description = "Open settings",
            shortcut = "Ctrl+,",
            icon = "settings",
            config = CommandConfig(
                priority = CommandPriority.NORMAL
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Settings opened",
                        data = mapOf<String, Any>(
                            "action" to "openSettings"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to open settings: ${e.message}", e)
                }
            }
        )
        
        val help = EditorCommand(
            id = "system.help",
            type = CommandType.SYSTEM,
            label = "Help",
            description = "Open help documentation",
            shortcut = "F1",
            icon = "help",
            config = CommandConfig(
                priority = CommandPriority.NORMAL
            ),
            execute = { context ->
                try {
                    CommandResult.Success(
                        message = "Help opened",
                        data = mapOf<String, Any>(
                            "action" to "openHelp"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.Error("Failed to open help: ${e.message}", e)
                }
            }
        )
    }
    
    /**
     * Get all essential commands
     */
    fun getAll(): List<EditorCommand> {
        return listOf(
            // Text formatting
            TextFormatting.bold,
            TextFormatting.italic,
            TextFormatting.code,
            TextFormatting.strikethrough,
            TextFormatting.highlight,
            TextFormatting.link,
            TextFormatting.heading,
            
            // Block commands
            BlockCommands.newBlock,
            BlockCommands.delete,
            BlockCommands.moveUp,
            BlockCommands.moveDown,
            BlockCommands.indent,
            BlockCommands.outdent,
            BlockCommands.toggleTodo,
            
            // Navigation
            NavigationCommands.search,
            NavigationCommands.gotoPage,
            NavigationCommands.back,
            NavigationCommands.forward,
            
            // System
            SystemCommands.save,
            SystemCommands.undo,
            SystemCommands.redo,
            SystemCommands.settings,
            SystemCommands.help
        )
    }
}
