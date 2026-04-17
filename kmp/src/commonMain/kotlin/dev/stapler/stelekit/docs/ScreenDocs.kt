package dev.stapler.stelekit.docs

class JournalsDocs : HowToDoc {
    override val howTo = HowToContent(
        title = "Daily Notes",
        description = "SteleKit's journal feature creates automatic daily pages for your notes. " +
            "Think of it as a captain's log — a running record of thoughts, tasks, and events. " +
            "Each day's entry is a separate Markdown file in the journals/ directory.",
        steps = listOf(
            "Tap or click the Journals icon in the sidebar to open the Journals view",
            "The view shows entries in reverse-chronological order — newest at the top",
            "Today's journal entry appears at the top; start typing to add your first block",
            "Scroll down to see older entries",
            "Tap a date header to open that date as a full page",
            "Create a wiki link like [[2026-04-12]] from any page to jump directly to that entry"
        ),
        tips = listOf(
            "Journal entries are stored as YYYY_MM_DD.md files in the journals/ folder",
            "Use the daily note as an inbox: capture ideas quickly, then move them to dedicated pages later",
            "Link from your daily note to reference pages using [[Page Name]] to create a trail of context"
        )
    )
}

class FlashcardsDocs : HowToDoc {
    override val howTo = HowToContent(
        title = "Flashcards",
        description = "SteleKit's flashcard feature turns any block into a spaced-repetition review card. " +
            "Spaced repetition schedules reviews at increasing intervals — cards you know well appear less often, " +
            "while cards you struggle with appear more frequently.",
        steps = listOf(
            "Add the block property `card:: true` as an indented child of any block to mark it as a flashcard",
            "Tap the Flashcards icon in the sidebar to open the review queue",
            "Cards due for review are shown one at a time — read the front and recall your answer",
            "Reveal the back of the card to check your answer",
            "Rate how well you remembered: 'Again' returns the card soon, 'Easy' defers it longer",
            "The scheduler adjusts the next review date based on your rating"
        ),
        tips = listOf(
            "Keep flashcard blocks short — one fact per card works best for recall",
            "Use block nesting: put the question as the parent block and the answer as a child with card:: true",
            "Review your queue daily for best results",
            "See Properties for more about the key:: value block property syntax"
        )
    )
}

class AllPagesDocs : ReferenceDoc {
    override val reference = ReferenceContent(
        title = "All Pages",
        description = "The All Pages view lists every non-journal page in your graph, sorted alphabetically by title.",
        sections = listOf(
            ReferenceSection(
                heading = "What is shown",
                body = "All pages in the pages/ directory of the graph, sorted alphabetically by title. " +
                    "Journal entries (stored in journals/) are not listed here — use the Journals view for those. " +
                    "Newly created pages appear in the list immediately after they are saved."
            ),
            ReferenceSection(
                heading = "Navigating to a page",
                body = "Tap or click any page name in the list to open it. " +
                    "The page opens in the main content area; the sidebar stays visible on desktop."
            ),
            ReferenceSection(
                heading = "Tips",
                body = "Use All Pages to audit your graph — look for pages that have no links (orphans). " +
                    "An alphabetical list is useful for quickly jumping to a known page name without searching. " +
                    "Combine with Search for finding pages when you only remember a word from the content."
            )
        )
    )
}

class PageViewDocs : MinimalFeatureDoc {
    override val howTo = HowToContent(
        title = "Block Editing",
        description = "Blocks are the primary unit of content in SteleKit. Every line of text is a block. " +
            "Blocks can be nested to any depth, creating an outline hierarchy. " +
            "A page is simply a named list of blocks.",
        steps = listOf(
            "Click anywhere on the page to focus the editor",
            "Type your content and press Enter to create a new block below",
            "Press Tab to indent a block — it becomes a child of the block above",
            "Press Shift+Tab to unindent — the block moves up one level in the hierarchy",
            "Click the bullet triangle to collapse children under a parent block",
            "Press Backspace on an empty block to delete it and move focus up"
        ),
        tips = listOf(
            "Use deep nesting to capture sub-tasks, sub-ideas, or supporting evidence",
            "Keep top-level blocks as main ideas; nest the details beneath them",
            "A flat structure with many blocks works just as well — choose what feels natural",
            "See Block Editor Reference for the full list of keyboard shortcuts and block states"
        )
    )

    override val reference = ReferenceContent(
        title = "Block Editor Reference",
        description = "Quick reference for block editing interactions, keyboard shortcuts, and block states.",
        sections = listOf(
            ReferenceSection(
                heading = "Keyboard Shortcuts — Block creation and deletion",
                body = "Enter — create a new block below the current block. " +
                    "Backspace on an empty block — delete the block, move cursor to block above. " +
                    "Shift+Enter — insert a line break within the current block (soft newline)."
            ),
            ReferenceSection(
                heading = "Keyboard Shortcuts — Indentation",
                body = "Tab — indent the current block (makes it a child of the block above). " +
                    "Shift+Tab — unindent the current block (promotes it to the parent's level)."
            ),
            ReferenceSection(
                heading = "Keyboard Shortcuts — Navigation",
                body = "Arrow Up / Arrow Down — move cursor between blocks. " +
                    "Ctrl+Home — jump to the first block on the page. " +
                    "Ctrl+End — jump to the last block on the page."
            ),
            ReferenceSection(
                heading = "Block States",
                body = "Focused — the block the cursor is in; shows an edit cursor. " +
                    "Editing — actively receiving keyboard input. " +
                    "Read-only — blocks rendered outside the editor (e.g. in the sidebar references panel). " +
                    "Collapsed — a parent block whose children are hidden; click the triangle bullet to expand."
            ),
            ReferenceSection(
                heading = "Inline Formatting Shortcuts",
                body = "**text** — bold. *text* — italic. `text` — inline code. ~~text~~ — strikethrough. " +
                    "See Markdown Formatting for the complete inline markup reference."
            )
        )
    )
}

class SearchDocs : HowToDoc {
    override val howTo = HowToContent(
        title = "Search",
        description = "SteleKit's search dialog lets you find any page or block across your entire graph instantly. " +
            "Results update with each keystroke, scanning both page titles and block content.",
        steps = listOf(
            "Press Cmd+K on macOS or Ctrl+K on Windows and Linux to open the search dialog",
            "The dialog opens with a text field and a list of recent pages",
            "Start typing — results update with each keystroke",
            "Results show the page title and, for block matches, a snippet of the matching block",
            "Click any result to navigate directly to that page or block"
        ),
        tips = listOf(
            "Search for a page name to jump to it without using the sidebar",
            "Use search to discover pages you forgot you created",
            "Searching for a tag like #reference surfaces all pages with that tag in their content",
            "Page title matches rank above block-content matches",
            "See All Pages for browsing pages alphabetically without a search query"
        )
    )
}
