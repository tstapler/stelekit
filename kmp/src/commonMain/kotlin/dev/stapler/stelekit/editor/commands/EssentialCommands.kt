package dev.stapler.stelekit.editor.commands

import dev.stapler.stelekit.editor.commands.*
import kotlinx.coroutines.CancellationException

/**
 * Collection of essential editor commands.
 *
 * Phase H reachability audit (rich-editing-experience, ADR-001/Epic H.2): `StelekitViewModel`'s
 * `updateCommands()` bridges every command this object's [getAll] returns (that survives
 * [CommandTypes.CommandConfig.isAvailable]'s filter — see `CommandTypes.kt`) into a real,
 * clickable `Command` row in the command palette. Its `action` lambda calls
 * `executeCommand(cmd.id)`, which discards the returned `CommandResult` — so any command here
 * that both (a) passes the availability filter and (b) has no special-cased real mutation route
 * is a "wired-looking but silently non-functional" trap the moment a user clicks it (features.md
 * §2 — the exact anti-pattern this project exists partly to eliminate). Confirmed only two
 * commands avoid this trap: `media.image` (special-cased in `updateCommands()` to call the real
 * `attachImageCallback`) and `block.toggle-todo` (special-cased in
 * `StelekitViewModel.executeCommand()` to call the real `requestTodoToggle()`, but also hidden
 * from the palette below since Phase F already ships a working, canonically-named
 * `format.toggle-todo` entry — see that command's own comment for the full reasoning). Every
 * other command below was audited against `CommandConfig.isAvailable(context)` using the actual
 * `CommandContext` `updateCommands()` constructs (no selection ever set: `selectionStart = 0,
 * selectionEnd = 0`; `currentBlockId` set to the *page* uuid, so `requiresBlock` does not
 * meaningfully gate anything while any page is open); each is marked `hidden = true` unless it
 * is one of the two exceptions above.
 */
object EssentialCommands {

    /**
     * Text formatting commands
     */
    object TextFormatting {
        // Phase H audit: excluded from the palette by design — requiresSelection = true and the
        // palette's CommandContext never carries a real selection (selectionStart/selectionEnd
        // are always 0), so CommandConfig.isAvailable() structurally excludes this from
        // getAvailableCommands() today. This was ADR-001's original (correct, for this one)
        // assumption. See StelekitViewModel.updateCommands()'s real, working "format.bold"
        // palette entry (Phase F.2) for the working equivalent.
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
        
        // Phase H audit: excluded from the palette by design — same requiresSelection = true
        // structural exclusion as `bold` above. See real "format.italic" palette entry (Phase F.2).
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
        
        // Phase H audit: excluded from the palette by design — same requiresSelection = true
        // structural exclusion as `bold` above. See real "format.code" palette entry (Phase F.2).
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
        
        // Phase H audit: excluded from the palette by design — same requiresSelection = true
        // structural exclusion as `bold` above. See real "format.strikethrough" palette entry (Phase F.2).
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
        
        // Phase H audit: excluded from the palette by design — same requiresSelection = true
        // structural exclusion as `bold` above. See real "format.highlight" palette entry (Phase F.2).
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
        
        // Phase H audit FINDING (corrects ADR-001's assumption): unlike bold/italic/code/
        // strikethrough/highlight above, this command's CommandConfig never set
        // requiresSelection = true — so it was NOT structurally excluded from
        // getAvailableCommands() and was live+silently-broken in the real command palette today
        // (clicking "Link" computed a CommandResult that StelekitViewModel.executeCommand()
        // discarded). Hidden explicitly as part of Phase H's fix. See the real, working
        // "format.link" palette entry (Phase F.2, StelekitViewModel.updateCommands()) instead.
        val link = EditorCommand(
            id = "text.link",
            type = CommandType.TEXT,
            label = "Link",
            description = "Create a link from selected text",
            shortcut = "Ctrl+K",
            icon = "link",
            config = CommandConfig(
                hidden = true,
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
        
        // Phase H audit FINDING (corrects ADR-001's assumption): same issue as `link` above —
        // no requiresSelection = true, so this was NOT structurally excluded and was
        // live+silently-broken in the real command palette. Hidden explicitly. See the real,
        // working "format.heading" palette entry (Phase F.2) instead.
        val heading = EditorCommand(
            id = "text.heading",
            type = CommandType.TEXT,
            label = "Heading",
            description = "Toggle heading level",
            shortcut = "Ctrl+Shift+1",
            icon = "heading",
            config = CommandConfig(
                hidden = true,
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
        // Phase H audit FINDING (beyond ADR-001's named list — these block.* commands were not
        // called out by name in the ADR at all): requiresBlock = true does NOT exclude this from
        // the palette, because updateCommands()'s CommandContext always sets currentBlockId to
        // the *current page's* uuid (a pre-existing "// This would be updated by actual editor"
        // placeholder, StelekitViewModel.kt), which is non-null whenever any page is open. So
        // this was live+silently-broken in the real command palette (result computed and
        // discarded). There is no working palette-driven equivalent for "new block" today — real
        // block creation is driven by Enter-key handling directly in BlockEditor.kt, not through
        // this framework. Hidden explicitly as part of Phase H's fix; kept as a registry entry
        // only (unreachable via the palette).
        val newBlock = EditorCommand(
            id = "block.new",
            type = CommandType.BLOCK,
            label = "New Block",
            description = "Create a new block",
            shortcut = "Enter",
            icon = "plus",
            config = CommandConfig(
                hidden = true,
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
        
        // Phase H audit FINDING: same requiresBlock non-exclusion issue as `newBlock` above —
        // live+silently-broken today. Real block deletion is driven by BlockGutter.kt/keyboard
        // handling directly, not through this framework. Hidden explicitly.
        val delete = EditorCommand(
            id = "block.delete",
            type = CommandType.BLOCK,
            label = "Delete Block",
            description = "Delete current block",
            shortcut = "Ctrl+Shift+Backspace",
            icon = "trash",
            config = CommandConfig(
                hidden = true,
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
        
        // Phase H audit FINDING: same requiresBlock non-exclusion issue as `newBlock` above —
        // live+silently-broken today. Real block reordering is driven by BlockGutter.kt's
        // Move-Up/Move-Down buttons directly, not through this framework. Hidden explicitly.
        val moveUp = EditorCommand(
            id = "block.move-up",
            type = CommandType.BLOCK,
            label = "Move Block Up",
            description = "Move current block up",
            shortcut = "Ctrl+Shift+Up",
            icon = "arrow-up",
            config = CommandConfig(
                hidden = true,
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
        
        // Phase H audit FINDING: same requiresBlock non-exclusion issue as `newBlock` above —
        // live+silently-broken today. Hidden explicitly; see `moveUp`'s comment.
        val moveDown = EditorCommand(
            id = "block.move-down",
            type = CommandType.BLOCK,
            label = "Move Block Down",
            description = "Move current block down",
            shortcut = "Ctrl+Shift+Down",
            icon = "arrow-down",
            config = CommandConfig(
                hidden = true,
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
        
        // Phase H audit FINDING: same requiresBlock non-exclusion issue as `newBlock` above —
        // live+silently-broken today. Real indent/outdent is driven by Tab/Shift+Tab handling
        // directly in BlockEditor.kt, not through this framework. Hidden explicitly.
        val indent = EditorCommand(
            id = "block.indent",
            type = CommandType.BLOCK,
            label = "Indent Block",
            description = "Indent current block",
            shortcut = "Tab",
            icon = "indent",
            config = CommandConfig(
                hidden = true,
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
        
        // Phase H audit FINDING: same requiresBlock non-exclusion issue as `newBlock` above —
        // live+silently-broken today. Hidden explicitly; see `indent`'s comment.
        val outdent = EditorCommand(
            id = "block.outdent",
            type = CommandType.BLOCK,
            label = "Outdent Block",
            description = "Outdent current block",
            shortcut = "Shift+Tab",
            icon = "outdent",
            config = CommandConfig(
                hidden = true,
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
        
        // Phase H resolution (Epic H.2, ADR-001): this was THE worst offender ADR-001 set out to
        // fix — its `execute` used to compute a `newContent` string and return it inside
        // `CommandResult.data`, which `StelekitViewModel.executeCommand()` simply discarded, so
        // selecting "Toggle Todo" from the palette silently did nothing (features.md §2).
        //
        // Resolution chosen (see StelekitViewModel.executeCommand()'s dedicated comment for the
        // full reasoning): `StelekitViewModel.executeCommand()` now special-cases the
        // "block.toggle-todo" id BEFORE it ever reaches this registry entry, calling the real
        // `blockStateManager.requestTodoToggle()` -> `applyTodoToggle` mutation (Phase C.1) —
        // so if anything ever invokes `commandManager.executeCommand("block.toggle-todo", ...)`
        // directly (bypassing the ViewModel), it is still correct-but-inert here rather than
        // silently wrong. This command is ALSO hidden from the palette (`hidden = true`) because
        // Phase F.2 already ships a working, canonically-named "format.toggle-todo" entry
        // (`StelekitViewModel.updateCommands()`) — shipping both would put two identical
        // "Toggle Todo" rows (same Ctrl+Enter shortcut) in the palette simultaneously, itself a
        // confusing UX smell. `format.toggle-todo` is the one surviving, user-visible entry.
        //
        // The `execute` lambda below is simplified to metadata-only per Task H.2.1b — the real
        // mutation lives in `BlockEditor.applyTodoToggle`/`BlockStateManager.requestTodoToggle`,
        // not here.
        val toggleTodo = EditorCommand(
            id = "block.toggle-todo",
            type = CommandType.BLOCK,
            label = "Toggle Todo",
            description = "Toggle todo status",
            shortcut = "Ctrl+Enter",
            icon = "check",
            config = CommandConfig(
                hidden = true,
                requiresBlock = true,
                priority = CommandPriority.HIGH
            ),
            execute = {
                // Unreachable via the (hidden) palette. StelekitViewModel.executeCommand()
                // intercepts this id and calls the real requestTodoToggle() mutation before ever
                // delegating to CommandManager/CommandSystem, so this lambda only runs if some
                // future caller invokes commandManager.executeCommand("block.toggle-todo", ...)
                // directly. Kept as CommandResult.Nothing (not Success with fabricated data) so
                // such a caller cannot mistake this for a real mutation.
                CommandResult.Nothing
            }
        )
    }
    
    /**
     * Navigation commands.
     *
     * Phase H audit FINDING (beyond ADR-001's named list): none of these four commands set
     * requiresSelection/requiresBlock/requiresPage, so `CommandConfig.isAvailable()` never
     * excluded them — all four were live+silently-broken in the real command palette (each
     * `execute` computes a `CommandResult.Success` describing an action like "openSearch" that
     * `StelekitViewModel.executeCommand()` discards; nothing ever actually opens search/page
     * selector or navigates back/forward). There is no working palette-driven equivalent for any
     * of these today. All four hidden explicitly as part of Phase H's fix.
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
                hidden = true,
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
                hidden = true,
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
                hidden = true,
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
                hidden = true,
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
     * Media commands.
     *
     * Phase H audit: `media.image` is the ONE command in this object confirmed to route to a
     * real mutation when clicked from the palette — `StelekitViewModel.updateCommands()`
     * special-cases `cmd.id == "media.image"` and calls the real, platform-specific
     * `attachImageCallback` instead of the generic (discarding) `executeCommand(cmd.id)` path.
     * Left visible (not hidden) and unchanged.
     */
    object MediaCommands {
        val image = EditorCommand(
            id = "media.image",
            type = CommandType.MEDIA,
            label = "Image",
            description = "Attach an image from gallery or file system",
            icon = "image",
            config = CommandConfig(
                priority = CommandPriority.HIGH
            ),
            execute = { _ ->
                // Note (superseded by Phase H audit): the comment below pre-dates this project —
                // there is no slash-command handler in the codebase anymore (deleted, Epic H.1;
                // zero call sites). This execute lambda is effectively unreachable in practice
                // because StelekitViewModel.updateCommands() intercepts "media.image" before
                // calling executeCommand(), routing to the real attachImageCallback instead. It
                // is kept as a documented fallback for the (currently theoretical) case where
                // attachImageCallback is null.
                CommandResult.Success(
                    message = "Image attachment requested",
                    data = mapOf<String, Any>("action" to "attachImage")
                )
            }
        )
    }

    /**
     * System commands.
     *
     * Phase H audit FINDING (beyond ADR-001's named list): none of these five commands set
     * requiresSelection/requiresBlock/requiresPage either — all five were live+silently-broken
     * in the real command palette (save/undo/redo/settings/help all compute a discarded
     * `CommandResult.Success`; none actually saves, undoes, redoes, or opens anything). SteleKit
     * has no explicit "Save" action (writes are debounced automatically), and Undo/Redo has its
     * own separate, real mechanism (`BlockStateManager`'s undo/redo stack, not this framework).
     * All five hidden explicitly as part of Phase H's fix.
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
                hidden = true,
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
                hidden = true,
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
                hidden = true,
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
                hidden = true,
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
                hidden = true,
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
            
            // Media
            MediaCommands.image,

            // System
            SystemCommands.save,
            SystemCommands.undo,
            SystemCommands.redo,
            SystemCommands.settings,
            SystemCommands.help
        )
    }
}
