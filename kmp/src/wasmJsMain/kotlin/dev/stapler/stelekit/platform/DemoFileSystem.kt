// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * An in-memory FileSystem implementation for the browser demo.
 * Provides pre-seeded content so the wasmJs demo loads with meaningful pages
 * without requiring a real filesystem or SQLite driver.
 */
class DemoFileSystem : FileSystem {
    private val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    private val journalFileName =
        "${today.year}_${today.monthNumber.toString().padStart(2, '0')}_${today.dayOfMonth.toString().padStart(2, '0')}.md"

    private val overrides = mutableMapOf<String, String>()

    private val demoFiles = mapOf(
        "journals/$journalFileName" to """
            - date:: ${today.year}-${today.monthNumber.toString().padStart(2, '0')}-${today.dayOfMonth.toString().padStart(2, '0')}
            - tags:: #journal #welcome
            - Welcome to SteleKit! You are reading the demo graph — a live, editable example of what a SteleKit graph looks like.
              - Everything you see is stored as plain Markdown files, compatible with Logseq.
              - Edit any block right now. Your changes stay in the demo — nothing is permanent here.
            - **Where to start**
              - Head to [[Start Here]] for a full index of feature pages.
              - Or dive straight into one of the highlights below.
            - **Five things to try in the next five minutes**
              - Type `[[` in any block to create a wiki link — a suggestion dropdown appears instantly.
              - Press `Tab` to indent the current block and make it a child of the one above.
              - Press `Cmd+K` (macOS) or `Ctrl+K` (Windows/Linux) to open full-text search.
              - Add `card:: true` as a child of any block, then open the Flashcards screen to review it.
              - Type `TODO ` at the start of a block to mark it as a task — cycle through `TODO → DOING → DONE`.
            - **What makes SteleKit different**
              - ==Every block is linkable.== You can link to a page, but you can also link to a single block inside any page.
              - Local-first — your notes live on disk as `.md` files. No account required, no lock-in.
              - The same graph files work in Logseq unchanged — you own your data.
            - Explore the demo, break things, then open your own graph folder when you are ready to start for real.
        """.trimIndent(),
        "pages/Start Here.md" to """
            - type:: index
            - tags:: #welcome #getting-started
            - Welcome to SteleKit — your Logseq-compatible outliner for Desktop, Android, iOS, and Web.
              - This graph is both a working demo and a built-in help reference.
              - Every feature has a dedicated page. Read it, edit it, link from it.
            - This graph is portable. The same `.md` files open in Logseq unchanged.
            - **Feature Pages** — explore SteleKit's capabilities:
              - [[Block Editing]] — create, indent, and reorder blocks; the primary unit of content
              - [[Block Editor Reference]] — keyboard shortcuts and block states at a glance
              - [[Page Linking]] — connect pages with `[[double bracket]]` wiki links
              - [[Backlinks]] — the References Panel and bidirectional linking model
              - [[Properties]] — add structured `key:: value` metadata to pages and blocks
              - [[Tags & Highlight]] — `#tag-name` inline tags and `==highlighted text==`
              - [[Daily Notes]] — the Journals view and navigating between dates
              - [[Search]] — full-text search with Cmd+K / Ctrl+K
              - [[Flashcards]] — mark blocks for spaced-repetition review
              - [[Markdown Formatting]] — bold, italic, highlight, blockquotes, code, headings, and more
              - [[Tables]] — GFM pipe-table syntax
              - [[Tasks]] — TODO/DOING/DONE markers and scheduled/deadline timestamps
              - [[Keyboard Shortcuts]] — full shortcut reference organized by category
            - **Getting Started**
              - Open [[Block Editing]] to learn the fundamentals.
              - Then visit [[Page Linking]] to see how the graph grows as you connect ideas.
              - Check [[Daily Notes]] to start a daily journaling habit.
              - Try [[Tags & Highlight]] to quickly categorize and emphasize content.
            - This page is your home base. Bookmark it or link to it from your own pages.
        """.trimIndent(),
        "pages/Block Editing.md" to """
            - tags:: #reference #editing
            - The block is the fundamental unit of content in SteleKit.
            - **Creating blocks**
              - Press **Enter** to create a new block below the current one.
              - A block can contain any inline Markdown: bold, italic, links, code, and more.
            - **Nesting blocks**
              - Press **Tab** to indent a block (make it a child of the one above).
              - Press **Shift-Tab** to outdent a block (move it up one level).
              - Blocks nest to any depth — the outline is the structure.
            - **Reordering blocks**
              - Press **Alt+Up** / **Alt+Down** to move a block up or down within its parent.
              - Drag the bullet handle to move a block anywhere in the tree.
            - **Selecting multiple blocks**
              - Hold **Shift** and click a block to add it to the selection.
              - Use the selection toolbar to delete, indent, or outdent multiple blocks at once.
            - See [[Block Editor Reference]] for the full keyboard shortcut reference.
        """.trimIndent(),
        "pages/Block Editor Reference.md" to """
            - tags:: #reference #shortcuts
            - Quick reference for block editing keyboard shortcuts.
            - **Navigation**
              - `Enter` — new block below
              - `Shift+Enter` — line break within block
              - `Tab` — indent (make child)
              - `Shift-Tab` — outdent (move up one level)
              - `Alt+Up / Alt+Down` — move block up / down
            - **Editing**
              - `Backspace` on empty block — delete block and move cursor up
              - `Cmd/Ctrl+Z` — undo
              - `Cmd/Ctrl+Shift+Z` — redo
            - **Formatting (inline)**
              - `Cmd/Ctrl+B` — bold
              - `Cmd/Ctrl+I` — italic
            - **Search and navigation**
              - `Cmd/Ctrl+K` — open full-text search
              - `[[` — insert a wiki link (autocomplete opens)
            - See [[Markdown Formatting]] for inline syntax reference.
        """.trimIndent(),
        "pages/Page Linking.md" to """
            - tags:: #reference #linking
            - Wiki links connect pages together. They are the backbone of the graph.
            - **Creating a wiki link**
              - Type `[[` in any block to open the autocomplete.
              - Start typing a page name — matching pages appear in a dropdown.
              - Press `Enter` to insert the link, or select from the list.
              - If the page does not exist yet, it is created when you navigate to it.
            - **Following a link**
              - Click a `[[Link]]` to navigate to the linked page.
              - Use the back button or browser history to return.
            - **Aliases**
              - You can write `[[Target Page|display text]]` to show custom text for a link.
              - The link still points to "Target Page" but displays as "display text".
            - **Tips**
              - Links are case-insensitive — `[[my page]]` and `[[My Page]]` link to the same page.
              - Pages are created on demand — just type `[[New Idea]]` and navigate to it.
            - See [[Backlinks]] to understand how linked pages reference each other.
        """.trimIndent(),
        "pages/Backlinks.md" to """
            - tags:: #reference #linking
            - Every `[[link]]` you create is tracked bidirectionally.
            - **How backlinks work**
              - When page A links to page B, page B automatically shows page A in its References panel.
              - No manual bookkeeping — the index updates in real time as you type.
            - **Finding backlinks**
              - Open any page and scroll to the References section at the bottom.
              - Each reference shows which page links here and the block that contains the link.
            - **Unlinked references**
              - SteleKit also finds mentions of a page name that are not yet linked.
              - These appear as "Unlinked References" below the linked references.
              - Click the mention to convert it to a `[[link]]` automatically.
            - **Why this matters**
              - Backlinks let you build a graph of ideas without managing the structure manually.
              - Start with simple notes. Let connections emerge as you write.
            - See [[Page Linking]] to learn how to create links.
        """.trimIndent(),
        "pages/Properties.md" to """
            - tags:: #reference #properties
            - Properties are structured `key:: value` pairs on pages and blocks.
            - **Page properties**
              - Add a block at the top of a page in the form `key:: value` to set a page property.
              - Example:
                - tags:: #project #active
                - status:: in-progress
                - due:: 2026-07-01
            - **Block properties**
              - Add a child block to any block in the form `key:: value` to set a property on that block.
              - Example:
                - Prepare release notes
                  - scheduled:: 2026-04-14
                  - deadline:: 2026-04-15
            - **Common property keys**
              - `tags::` — categorize with `#tag-name` values
              - `type::` — mark a page as index, note, reference, etc.
              - `alias::` — add alternative names for the page
              - `card::` — set to `true` to enqueue the block for flashcard review
            - **Logseq compatibility**
              - Properties use the same `key:: value` syntax as Logseq — graphs are cross-compatible.
            - See [[Tags & Highlight]] for inline tag syntax and [[Flashcards]] for card review.
        """.trimIndent(),
        "pages/Tags & Highlight.md" to """
            - tags:: #reference #tags #highlight
            - SteleKit supports inline `#tags` for categorizing content and `==highlight==` for visual emphasis.
            - **Tags**
              - Type `#` followed by a word anywhere in a block to tag it: `#idea`, `#reference`, `#todo`
              - Multi-word tags use hyphens: `#project-alpha`, `#follow-up`
              - Tags are searchable — use `Cmd+K` / `Ctrl+K` and type the tag name to find all tagged blocks.
              - Tags appear in the block as styled inline text so they stand out visually.
              - Example blocks using tags:
                - Read this paper before the meeting #important #reading
                - Idea: use tags as a lightweight status system #idea
                - This reference lives here permanently #reference #evergreen
            - **Highlight**
              - Wrap text in `==double equals==` to highlight it: `==key insight==` → ==key insight==
              - Use highlight to mark the most important sentence in a block.
              - Combine with bold for maximum emphasis: `**==critical==**` → **==critical==**
              - Example:
                - The most important rule: ==every block is linkable, searchable, and reviewable==.
                - Highlights are rendered inline — ==they do not disrupt the outline flow==.
            - **Combining tags and highlight**
              - Tags categorize a block; highlight emphasizes a phrase within the block text.
              - They complement each other: tag the block's topic, highlight the key takeaway.
              - Example:
                - ==Spaced repetition== dramatically improves long-term recall — even brief daily review compounds over time. #learning #flashcards
            - **Tips**
              - Keep tag names short and consistent — use the same tag across pages to group related content.
              - Use search (Cmd+K / Ctrl+K) and type `#tag-name` to surface all blocks with that tag.
              - Prefer highlight over bold when you want to mark something as important without implying strong text emphasis.
            - See [[Search]] to find tagged blocks, [[Markdown Formatting]] for all inline syntax, and [[Flashcards]] to turn highlighted blocks into spaced-repetition cards.
        """.trimIndent(),
        "pages/Daily Notes.md" to """
            - tags:: #reference #journals
            - The Journals view is a daily log — a new page is created automatically for each day.
            - **Navigating journals**
              - Use the left/right arrows in the Journals view to move between dates.
              - Each day's journal is stored as a separate `.md` file named `YYYY_MM_DD.md`.
            - **Using journals**
              - Journal pages are perfect for daily notes, meeting notes, and fleeting ideas.
              - Link out to permanent pages with `[[wiki links]]` to capture connections as you go.
              - Add `TODO` blocks to track tasks for the day.
            - **Journal files**
              - Journal files live in the `journals/` folder of your graph.
              - They are Logseq-compatible — the same file format works in both apps.
            - See [[Page Linking]] to learn how to connect journal entries to permanent pages.
        """.trimIndent(),
        "pages/Search.md" to """
            - tags:: #reference #search
            - Full-text search lets you find any block or page instantly.
            - **Opening search**
              - Press `Cmd+K` (macOS) or `Ctrl+K` (Windows/Linux) to open the search dialog.
              - Type any word or phrase — results appear in real time.
            - **What is indexed**
              - Every block's content is indexed, including nested blocks.
              - Page names, tags, and properties are all searchable.
            - **Navigating results**
              - Click a result to jump to that page or block.
              - Use arrow keys to move between results, then `Enter` to navigate.
            - **Search tips**
              - Type `#tag-name` to find all blocks with a specific tag.
              - Type a page name to quickly navigate to it.
              - The index updates in real time as you type and edit.
            - See [[Tags & Highlight]] for how to use tags to organize searchable content.
        """.trimIndent(),
        "pages/Flashcards.md" to """
            - tags:: #reference #flashcards
            - Any block can become a flashcard for spaced-repetition review.
            - **Marking a block for review**
              - Add `card:: true` as a child property block to any block.
              - Example:
                - The mitochondria is the powerhouse of the cell.
                  - card:: true
            - **Reviewing cards**
              - Open the Flashcards screen from the sidebar.
              - Cards are shown one at a time. Rate how well you remembered each one.
              - The review schedule adjusts based on your ratings.
            - **Tips**
              - Use flashcards for facts, definitions, and anything you want to memorize long-term.
              - Keep card content short — one fact per card works best.
              - Pair with [[Tags & Highlight]] to highlight the key phrase you want to memorize.
        """.trimIndent(),
        "pages/Markdown Formatting.md" to """
            - tags:: #reference #formatting
            - SteleKit renders standard inline Markdown within every block.
            - **Text emphasis**
              - Bold: `**bold text**` → **bold text**
              - Italic: `*italic text*` → *italic text*
              - Bold and italic combined: `***bold italic***` → ***bold italic***
              - Strikethrough: `~~struck through~~` → ~~struck through~~
              - Highlight: `==important text==` → ==important text==
            - **Inline code**
              - Wrap text in backticks: `` `code here` `` → `code here`
              - Use inline code for file paths, variable names, and commands.
            - **Headings (in outliner mode)**
              - Add `# ` at the start of a block to render it as a heading.
              - `# Heading 1`, `## Heading 2`, `### Heading 3` are all supported.
              - Note: in an outliner, headings are rarely needed — use nesting for hierarchy instead.
            - **Blockquotes**
              - Prefix a block with `> ` to render it as a blockquote.
              - Example: `> This is a quoted passage.` → renders with a left-border indent.
              - Use for quotes, callouts, or content lifted from another source.
            - **Fenced code blocks**
              - Use triple backticks with an optional language identifier.
            - **Links**
              - External links: `[link text](https://example.com)`
              - Wiki links: `[[Page Linking]]` is an example — see [[Page Linking]] for full details
            - **Tags**
              - Add `#tag-name` inline in any block to tag it.
              - Tags are searchable and appear in the [[Tags & Highlight]] reference page.
            - **Support status**
              - Fully supported: bold, italic, strikethrough, inline code, highlight, fenced code blocks, blockquotes, wiki links, external links, tags, tables
              - Partially supported: headings (rendered but outliner hierarchy is preferred)
            - See [[Block Editor Reference]] for editing shortcuts, [[Tables]] for pipe-table syntax, and [[Tags & Highlight]] for tag and highlight details.
        """.trimIndent(),
        "pages/Tables.md" to """
            - tags:: #reference #tables
            - SteleKit renders GFM pipe tables natively inside blocks.
            - **Syntax**
              - A table has a header row, a separator row, and data rows.
              - Each column is separated by a pipe character `|`.
              - The separator row uses hyphens `---` in each column cell.
              - Column alignment: `:---` = left, `:---:` = center, `---:` = right.
            - **Example — feature support table**

            | Feature         | Status    | Notes                             |
            |:----------------|:---------:|----------------------------------:|
            | Bold / italic   | Supported | Use `**` and `*` markers          |
            | Inline code     | Supported | Use backticks                     |
            | Highlight       | Supported | Use `==text==`                    |
            | Wiki links      | Supported | Use `[[double bracket]]` syntax   |
            | Tables          | Supported | GFM pipe-table syntax (this page) |
            | Tags            | Supported | Use `#tag-name` inline            |
            | Task markers    | Supported | TODO, DOING, DONE prefix syntax   |

            - **Tips**
              - Tables render inside any block — paste the markdown directly.
              - Use alignment markers to control column layout: `:---` left, `:---:` center, `---:` right.
              - For simple comparisons, nested blocks can also work well as an alternative.
            - See [[Markdown Formatting]] for all supported inline markup, and [[Properties]] for `key:: value` metadata syntax.
        """.trimIndent(),
        "pages/Tasks.md" to """
            - tags:: #reference #tasks
            - SteleKit supports Logseq-style task markers as a prefix on any block.
            - **Logseq-style task markers**
              - Add a marker keyword at the start of a block to set its state.
              - Supported markers: `TODO`, `DOING`, `DONE`, `NOW`, `LATER`, `WAITING`, `WAIT`, `STARTED`, `CANCELLED`
              - Examples:
                - TODO Write the introduction section
                - DOING Review pull request comments
                - DONE Set up the project repository
                - LATER Follow up with the design team
                - WAITING Awaiting feedback from the design team
                - CANCELLED Migrate old notes from Evernote
            - **Cycling task state**
              - Use the block menu or keyboard shortcut to cycle a block through: `TODO → DOING → DONE → TODO`
              - Markers are stored as plain text in the Markdown file — Logseq-compatible.
            - **SCHEDULED and DEADLINE timestamps**
              - Add a timestamp property to a block to schedule it or record a hard deadline.
              - Use `key:: value` child block syntax (see [[Properties]]).
              - Example:
                - Prepare release notes
                  - scheduled:: 2026-04-14
                  - deadline:: 2026-04-15
            - **Tips**
              - Task markers are plain text — you can type them directly or toggle them from the block menu.
              - Pair tasks with [[Properties]] to add context: `priority:: high`, `project:: SteleKit`.
              - Logseq-compatible: these task markers open correctly in Logseq with no conversion needed.
            - See [[Properties]] for `key:: value` syntax, and [[Markdown Formatting]] for other inline markup.
        """.trimIndent(),
        "pages/Keyboard Shortcuts.md" to """
            - tags:: #reference #shortcuts
            - Full shortcut reference organized by category.
            - **Block editing**
              - `Enter` — new block below
              - `Shift+Enter` — soft line break within block
              - `Tab` — indent (make child of block above)
              - `Shift-Tab` — outdent (move up one nesting level)
              - `Alt+Up / Alt+Down` — move block up / down within parent
              - `Backspace` on empty block — delete and move cursor up
            - **Text formatting**
              - `Cmd/Ctrl+B` — bold
              - `Cmd/Ctrl+I` — italic
            - **Undo / redo**
              - `Cmd/Ctrl+Z` — undo
              - `Cmd/Ctrl+Shift+Z` — redo
            - **Navigation**
              - `Cmd/Ctrl+K` — open full-text search
              - `Cmd/Ctrl+/` — open command palette
              - `[[` — open wiki link autocomplete
            - **Sidebar**
              - `Cmd/Ctrl+\` — toggle sidebar
            - See [[Block Editor Reference]] for a focused editing shortcut reference.
        """.trimIndent(),
        "pages/Getting Started.md" to """
            - SteleKit is a local-first outliner — see [[Start Here]] for the full feature index.
            - **Key shortcuts**
              - **Enter** — new block below
              - **Tab / Shift-Tab** — indent / outdent
              - **Alt+Up / Alt+Down** — move block up / down
              - **Cmd/Ctrl+K** — open search
            - **Links**
              - Type `[[page name]]` to link to any page
              - Backlinks are tracked automatically — see [[Backlinks]]
        """.trimIndent(),
    )

    override fun getDefaultGraphPath(): String = "/demo"
    override fun expandTilde(path: String): String = path

    override fun readFile(path: String): String? {
        return overrides[path] ?: demoFiles[path.removePrefix("/demo/")]
    }

    // Demo mode: writes are persisted in-memory for the session duration.
    // Content does not survive a page reload, but reads within the same session
    // reflect edits made via writeFile.
    override fun writeFile(path: String, content: String): Boolean {
        overrides[path] = content
        return true
    }

    override fun listFiles(path: String): List<String> {
        // Return file NAMES only (not full paths) — callers reconstruct "$path/$name" themselves.
        val prefix = path.removePrefix("/demo/").let { if (it.isEmpty()) "" else "$it/" }
        return (demoFiles.keys + overrides.keys.map { it.removePrefix("/demo/") })
            .filter {
                if (prefix.isEmpty()) {
                    // root: return only direct children (no nested paths)
                    !it.contains('/')
                } else {
                    it.startsWith(prefix)
                }
            }
            .map { it.removePrefix(prefix) }
            .distinct()
    }

    override fun listDirectories(path: String): List<String> {
        // Return directory NAMES only — callers construct full paths themselves.
        return if (path == "/demo" || path == "/demo/") {
            listOf("journals", "pages")
        } else emptyList()
    }

    override fun fileExists(path: String): Boolean {
        return overrides.containsKey(path) || demoFiles.containsKey(path.removePrefix("/demo/"))
    }

    // Only return true for directories that actually exist in the demo filesystem.
    // The previous implementation (`path.startsWith("/demo")`) caused detectGitRoot()
    // to find a false `/demo/.git` directory and show the git detection banner incorrectly.
    private val knownDirectories = setOf("/demo", "/demo/journals", "/demo/pages")
    override fun directoryExists(path: String): Boolean = path.trimEnd('/') in knownDirectories

    override fun createDirectory(path: String): Boolean = true
    override fun deleteFile(path: String): Boolean = true
    override fun pickDirectory(): String? = null
    override suspend fun pickDirectoryAsync(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
}
