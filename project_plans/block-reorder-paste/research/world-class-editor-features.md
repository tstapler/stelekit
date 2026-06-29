# World-Class Block Editor Features — Research Report

**Purpose**: Comprehensive feature matrix across leading block-based outliners to inform SteleKit's roadmap. Each table entry rates Priority (P0/P1/P2/Stretch) from SteleKit's perspective as a KMP outliner (Desktop JVM + Android + iOS).

**Apps surveyed**: Logseq · Roam Research · Notion · Obsidian · Craft · Bear · WorkFlowy · Tana · Mem · Linear (shortcuts only)

---

## Executive Summary

1. **Copy/paste blocks with subtree fidelity is the single most urgent gap** — every competitive outliner supports it and users assume it; SteleKit's absence is felt immediately. Implementing paste-as-block (preserving hierarchy) with proper `leftUuid`/`parentUuid` rewiring is the P0 unlock.

2. **Block references / transclusion is the defining differentiator** of the outliner category — Roam invented it, Logseq refined it, Tana extends it. Without `((block-ref))` support, SteleKit cannot claim parity with its own inspiration (Logseq).

3. **Keyboard shortcuts are muscle memory** — deviating from the Logseq/Roam convention (Alt+Up/Down move, Tab indent, Shift+Enter new line within block) will alienate migrating users. Ship the canonical shortcut set before beta.

4. **Mobile editing is bottlenecked by the floating toolbar** — Craft sets the gold standard with a full formatting + indent/outdent toolbar above the keyboard. A well-designed toolbar row is worth more than any gesture shortcut on mobile.

5. **Slash commands are the discoverability gateway** — Notion proved that `/` as an entry point into block types removes the need to memorize markdown syntax and dramatically lowers the floor for new users. This should be P1 even before full block-type support exists.

---

## 1. Text Editing Within a Block

| Feature | Best-in-class app | Priority | Notes for SteleKit |
|---|---|---|---|
| Standard word jump (Ctrl+Left/Right, Option+Left/Right on Mac) | Universal | P0 | Delegate to platform `BasicTextField` — works for free on all targets |
| Home / End within block (go to line start/end) | Universal | P0 | Handled by `BasicTextField`; verify behavior matches platform convention (iOS Home = Cmd+Left) |
| Ctrl+Home / Ctrl+End to first/last block on page | Logseq, WorkFlowy | P1 | Requires intercepting the key event before `BasicTextField` sees it |
| Inline bold (Ctrl+B) | Universal | P0 | Wrap selection with `**…**`; render as bold in markdown render pass |
| Inline italic (Ctrl+I) | Universal | P0 | Wrap with `_…_` |
| Inline underline (Ctrl+U) | Notion, Craft | P2 | Not standard Markdown; requires custom span syntax or HTML; defer until own render is mature |
| Inline code (Ctrl+E / Ctrl+`) | Notion, Logseq | P1 | Wrap with backticks; render monospace |
| Strikethrough (Ctrl+Shift+S) | Obsidian, Notion | P2 | `~~…~~` |
| Inline link (Ctrl+K) | Universal | P1 | Opens URL dialog; inserts `[text](url)` |
| Markdown shorthand auto-complete: `**` → bold | Obsidian, Bear, Logseq | P1 | Fire on closing delimiter typed; convert in-place to formatted span |
| Markdown shorthand: `[[` → wikilink picker | Logseq (best UX), Roam, Obsidian | P0 | SteleKit already has this partially; polish the dropdown picker |
| Markdown shorthand: `#tag` → tag autocomplete | Logseq, Bear, Tana, WorkFlowy | P1 | Auto-suggest from existing tags; insert linked tag |
| Markdown shorthand: `((` → block-ref picker | Roam (best), Logseq, Tana | P1 | Requires block-ref feature first; fuzzy search block content |
| Markdown shorthand: `::` → property | Logseq, Tana | P2 | Key:: value syntax; show property panel |
| Markdown heading shortcuts: `# ` → H1, `## ` → H2 | Notion, Logseq, Obsidian | P1 | Trigger on Space after leading hashes; convert block to heading type |
| `- ` or `* ` → bullet (already bullet; no-op or re-indent) | All | P1 | Graceful handling — already in a bullet context |
| `- [ ] ` → checkbox / task | All | P1 | Convert block to task type on trigger |
| `` ``` `` → code block | Notion, Logseq, Obsidian | P1 | Language specifier; syntax highlight |
| Slash commands (`/`) | Notion (best), Logseq, Craft, Tana | P1 | Searchable menu of block types, dates, templates; Notion's UX is the gold standard |
| Multi-cursor editing | None (not an outliner feature) | Stretch | No outliner implements true multi-cursor; not expected by users |
| Drag to select text within block | Universal (OS-native) | P0 | Automatic via `BasicTextField` |
| Double-click to select word | Universal (OS-native) | P0 | Automatic |
| Triple-click to select full block content | Notion, Craft | P1 | Override on desktop; useful for "grab entire block text" |

---

## 2. Block Manipulation

| Feature | Best-in-class app | Priority | Notes for SteleKit |
|---|---|---|---|
| Drag-and-drop reorder (single block) | All (Notion best visual polish) | P0 | SteleKit has this; polish drop indicator and snap zones |
| Drag-and-drop reorder (block + subtree) | All outliners | P0 | Drag should carry all children; re-parent by computing new `parentUuid`/`leftUuid` |
| Keyboard move up/down (Alt+Up / Alt+Down) | Logseq, Roam, WorkFlowy, Obsidian | P0 | SteleKit has this; verify edge cases at top/bottom of list |
| Move block to another page | Logseq (via cut/paste), Notion ("Move to…"), Tana | P1 | Command-palette action: "Move to page…"; re-parent across page boundary |
| Select block (click bullet / handle) | Logseq, WorkFlowy, Notion | P0 | Click on bullet dot selects block without entering edit mode |
| Shift+Click range selection | Logseq, Notion, WorkFlowy | P0 | Select contiguous range of blocks |
| Ctrl+Click multi-select (non-contiguous) | Logseq | P1 | Add/remove individual blocks to selection set |
| Ctrl+A select all blocks on page | Logseq, Notion, WorkFlowy | P1 | Selects all top-level blocks (and children) |
| Shift+Up/Down extend block selection | Logseq, WorkFlowy | P0 | Extends from anchor to cursor block |
| Copy block(s) with full subtree | All outliners | P0 | **Critical gap.** Copy serializes block + all descendants preserving indent levels |
| Cut block(s) with full subtree | All outliners | P0 | Remove from source; clipboard holds structured data |
| Paste blocks (structured, preserving hierarchy) | All outliners | P0 | **Critical gap.** Re-creates parent/child relationships; assigns new UUIDs; links `leftUuid` chain |
| Paste as plain text (Ctrl+Shift+V) | Roam, Notion | P1 | Strips block structure; pastes as flat text into current block |
| Paste from external clipboard (plain text) | All | P0 | Auto-detect non-block content; insert as new sibling block(s), splitting on newlines |
| Paste from external clipboard (rich text) | Notion (best conversion), Craft | P1 | Convert HTML → markdown blocks on paste |
| Duplicate block (Ctrl+D) | Logseq, Notion, Roam | P1 | Deep copy of block + children; insert immediately below original |
| Clone / block reference (live mirror) | Roam (best), Logseq, Tana | P1 | `((uuid))` — a reference renders the target block's content; editing either updates both |
| Block embed (one-way transclude) | Logseq, Tana | P2 | Rendered copy of another block; read-only at embed site |
| Synced blocks (two-way live embed) | Notion ("Synced block") | Stretch | Notion's implementation is powerful but complex; defer |
| Merge blocks (Backspace at start of block) | All outliners | P0 | Merges current block content into end of previous sibling; children of current become children of previous |
| Split block (Enter in middle of text) | All outliners | P0 | Splits text at cursor; content before cursor stays; content after becomes new sibling below |
| Delete block (no children) | All | P0 | Standard |
| Delete block + promote children (orphan prevention) | WorkFlowy (best), Logseq | P1 | When deleting a parent with children, children become siblings of the deleted parent |
| Delete block + delete children | All | P0 | Default bulk delete behavior |
| Zoom into block (make it the page root) | WorkFlowy (core feature), Roam, Logseq, Tana | P1 | Sets a block as the viewport root; shows breadcrumb back to full page |
| Turn block into page | Notion, Logseq | P1 | Convert a block's content to a new page title; replace block with `[[PageName]]` link |
| Turn block into different type (heading, task, etc.) | Notion ("Turn into…"), Logseq | P1 | Block-type switching via context menu or slash command |
| Context menu (right-click / long-press on handle) | All | P1 | Copy · Cut · Paste · Duplicate · Move to · Delete · Block ref · Properties |

---

## 3. Hierarchy Operations

| Feature | Best-in-class app | Priority | Notes for SteleKit |
|---|---|---|---|
| Indent block (Tab) | Universal | P0 | SteleKit has this |
| Outdent block (Shift+Tab) | Universal | P0 | SteleKit has this |
| Indent preserves children | All outliners | P0 | When indenting a parent, all children follow |
| Outdent promotes children with parent | All outliners | P0 | Children stay under parent; parent moves up a level |
| Bulk indent (Tab on multi-selection) | Logseq, WorkFlowy, Notion | P0 | All selected blocks indent together preserving relative hierarchy |
| Bulk outdent (Shift+Tab on multi-selection) | Logseq, WorkFlowy, Notion | P0 | Same in reverse |
| Collapse block (hide children) | All outliners | P0 | SteleKit has this |
| Expand block (show children) | All outliners | P0 | SteleKit has this |
| Collapse all siblings at this level | Logseq, WorkFlowy, Roam | P1 | Keyboard shortcut or context menu: collapse every peer at same level |
| Expand all (recursive) | Logseq, WorkFlowy, Roam, Tana | P1 | Recursively expand entire subtree |
| Collapse all at page level | Logseq, WorkFlowy | P1 | Shows only top-level blocks; useful for overview |
| Toggle collapse (Alt+Shift+C in Logseq) | Logseq, WorkFlowy | P1 | Single shortcut to flip collapse state |
| Focus / zoom mode (block as root) | WorkFlowy (founding feature), Roam, Tana | P1 | Isolate a subtree; breadcrumb shows path back |
| Breadcrumb navigation when zoomed | WorkFlowy, Tana, Notion | P1 | "Page > Block > Sub-block" trail; click to navigate up |
| Fold / unfold heading sections | Obsidian, Logseq | P1 | Fold all blocks under a heading block |
| Level indicator / indentation guides | Notion, Craft, Tana | P2 | Visual indent guides at each nesting level |

---

## 4. Selection and Multi-Block Operations

| Feature | Best-in-class app | Priority | Notes for SteleKit |
|---|---|---|---|
| Enter selection mode from keyboard (Esc) | Logseq, WorkFlowy | P0 | Esc exits text edit → selects current block |
| Arrow up/down in selection mode | Logseq, WorkFlowy | P1 | Navigate between blocks without entering edit |
| Shift+Up/Down extend selection | Logseq, WorkFlowy | P0 | Extend anchor-to-cursor selection |
| Shift+Click range select | Logseq, Notion | P0 | Select from last-selected to clicked block |
| Ctrl+Click add/remove from selection | Logseq | P1 | Non-contiguous multi-select |
| Ctrl+A select all | Logseq, WorkFlowy, Notion | P1 | Select all blocks on visible page |
| Copy selected blocks (Ctrl+C) | All | P0 | Copy as structured block clipboard |
| Cut selected blocks (Ctrl+X) | All | P0 | Remove + clipboard |
| Paste into selection location (Ctrl+V) | All | P0 | Insert clipboard blocks after selection anchor |
| Delete selected blocks (Backspace / Delete) | All | P0 | SteleKit has this |
| Bulk indent selected blocks (Tab) | All outliners | P0 | |
| Bulk outdent selected blocks (Shift+Tab) | All outliners | P0 | |
| Bulk move selected blocks to another page | Logseq, Tana, Notion | P1 | Command: "Move to page…" via command palette |
| Bulk add tag to selected blocks | Tana (best), Logseq | P2 | Insert `#tag` at end of each selected block |
| Bulk assign property to selected blocks | Tana (best), Notion databases | P2 | Set a key::value property on each |
| Bulk collapse selected blocks | Logseq | P2 | Collapse all selected parents |
| Bulk duplicate selected blocks | Notion, Logseq | P2 | Deep copy inserted below selection |
| Bulk change block type (heading, task, etc.) | Notion ("Turn into…"), Tana | P2 | Via slash command or context menu on multi-select |
| Select all children of a block | WorkFlowy, Tana | P2 | Select entire subtree under a parent |

---

## 5. Navigation

| Feature | Best-in-class app | Priority | Notes for SteleKit |
|---|---|---|---|
| Up/Down arrow navigate between blocks | All outliners | P0 | SteleKit has this |
| Left/Right arrow within text (natural) | Universal | P0 | |
| Left arrow at block start → go to end of previous block | Logseq, WorkFlowy | P0 | SteleKit should ensure this works across block boundaries |
| Right arrow at block end → go to start of next block | Logseq, WorkFlowy | P0 | |
| Ctrl+Home to first block on page | Logseq, WorkFlowy | P1 | Jump to very top |
| Ctrl+End to last block on page | Logseq, WorkFlowy | P1 | Jump to very bottom |
| Click wikilink to navigate to page | All | P0 | Ctrl+Click in some apps to open in sidebar |
| Open link in sidebar / split pane | Logseq, Roam, Obsidian | P1 | Ctrl+Click or Shift+Click opens page without losing current view |
| Back navigation (Alt+Left / Cmd+[ ) | Logseq, Obsidian, Roam, WorkFlowy | P1 | Navigate to previously visited page |
| Forward navigation (Alt+Right / Cmd+] ) | Logseq, Obsidian, Roam | P1 | Forward through history |
| Global page search (Ctrl+K / Ctrl+P) | All | P0 | SteleKit has Ctrl+K; ensure fuzzy matching |
| Quick-jump to page by name | All | P0 | Part of global search |
| Breadcrumb when viewing zoomed block | WorkFlowy, Tana, Logseq | P1 | "Graph > Page > Block" clickable trail |
| Linked references panel (backlinks) | Logseq (best UX), Roam, Obsidian, Tana | P1 | Bottom-of-page panel showing all blocks that reference this page |
| Unlinked references panel | Logseq, Obsidian | P2 | Mentions of page name not wrapped in `[[ ]]` |
| Graph / network view | Logseq, Obsidian, Roam | P2 | Visual map of page links; nice showcase but rarely used daily |
| Table of contents (headings) | Notion, Obsidian, Craft | P2 | Auto-generated ToC sidebar for long pages |
| Jump to heading within page | Notion, Obsidian | P2 | Part of ToC; also via search |
| Recently visited pages | All | P1 | Show in global search or sidebar |
| Daily journal quick-open | Logseq (core feature), Roam | P1 | Keyboard shortcut to jump to today's journal page |

---

## 6. Search and Replace Within Page

| Feature | Best-in-class app | Priority | Notes for SteleKit |
|---|---|---|---|
| Find in page (Ctrl+F) | All desktop apps | P1 | Highlight matching text in blocks; cycle through matches |
| Find in page — match highlighting | Logseq, Obsidian, Notion | P1 | All matches highlighted simultaneously |
| Find next / previous (Enter / Shift+Enter in find bar) | All | P1 | |
| Find and replace in page (Ctrl+H) | Obsidian (best), Logseq | P1 | Replace one or all occurrences in current page |
| Case-sensitive / whole-word options | Obsidian | P2 | Toggle in find bar |
| Regex search | Obsidian (via plugin) | Stretch | Power feature; low priority for initial implementation |
| Global text search across all pages | All | P1 | SteleKit has page-name search; extend to full-text content search |
| Search results show block context | Logseq (best), Obsidian, Roam | P1 | Show excerpt of matching block, not just page name |
| Filter search by tag, date, property | Tana (best), Logseq queries, Notion databases | P2 | Advanced filtering |
| Saved search / query blocks | Logseq ({{query}}), Roam, Tana (supertag views) | P2 | Embed a live search result as a block |

---

## 7. Undo / Redo

| Feature | Best-in-class app | Priority | Notes for SteleKit |
|---|---|---|---|
| Text-level undo within block (Ctrl+Z) | All | P0 | OS-native text undo via `BasicTextField` |
| Structural undo: un-indent | Logseq, WorkFlowy, Roam | P0 | Undo must reverse structural operations, not just text |
| Structural undo: un-move (up/down) | Logseq, WorkFlowy | P0 | Move up then Ctrl+Z should restore original position |
| Structural undo: un-delete block | Logseq, WorkFlowy, Notion | P0 | Restore deleted block + children |
| Structural undo: un-create block | All outliners | P0 | Ctrl+Z after Enter removes the new block |
| Structural undo: un-merge blocks | Logseq, WorkFlowy | P0 | Ctrl+Z after Backspace-merge splits them back |
| Structural undo: un-split block | Logseq, WorkFlowy | P0 | Ctrl+Z after Enter-split joins them back |
| Redo (Ctrl+Y or Ctrl+Shift+Z) | All | P0 | |
| Undo depth ≥ 50 steps | Logseq, WorkFlowy (unlimited) | P1 | Notion is weakest here (~20 steps) |
| Cross-block undo (single Ctrl+Z reverses structural op even if cursor has moved) | Logseq, WorkFlowy | P0 | Critical: undo stack must be global, not per-block |
| Undo across paste operations | All | P0 | Ctrl+Z after paste removes all pasted blocks |
| Undo visual indicator (what will be undone) | None (no outliner does this well) | Stretch | |

---

## 8. Slash Commands and Block Types

| Feature | Best-in-class app | Priority | Notes for SteleKit |
|---|---|---|---|
| Slash command menu (`/`) | Notion (best UX — searchable, icon-rich, keyboard-navigable) | P1 | Trigger on `/` at any point in block; show filterable list of insertable types |
| `/` menu: Heading 1 | Notion, Logseq, Obsidian | P1 | Change block to H1 display type |
| `/` menu: Heading 2 | Same | P1 | |
| `/` menu: Heading 3 | Same | P2 | |
| `/` menu: Bullet list | Notion | P2 | All blocks are already bullets in an outliner |
| `/` menu: Numbered list (ordered) | Notion, Logseq, Obsidian | P1 | Display numeric index instead of bullet |
| `/` menu: Checkbox / TODO | Notion, Logseq | P0 | Task toggle; marks block with `- [ ]` |
| `/` menu: Code block | Notion (syntax highlight + language picker), Logseq, Obsidian | P1 | Multi-line code block; fenced with language specifier |
| `/` menu: Quote / blockquote | Notion, Craft, Obsidian | P2 | Visual call-out style for quoted text |
| `/` menu: Callout (colored box with icon) | Notion (best — emoji + color), Obsidian (`> [!NOTE]`) | P1 | Callout blocks dramatically improve visual hierarchy in docs |
| `/` menu: Divider / horizontal rule | Notion, Logseq (`---`) | P2 | Visual separator block |
| `/` menu: Table | Notion (database-grade), Craft (visual), Obsidian (markdown tables) | P1 | Markdown table is baseline; visual editor is P2 |
| `/` menu: Toggle / collapsible block | Notion, Logseq | P1 | Already have collapse — surface via slash menu |
| `/` menu: Image | Notion, Craft, Bear, Obsidian | P1 | Upload or paste URL; render inline |
| `/` menu: File attachment | Notion, Craft, Obsidian | P2 | Upload any file; link to it |
| `/` menu: YouTube / web embed | Notion (best — auto-detect URL type), Logseq, Craft | P2 | iFrame embed for YouTube, Twitter, Figma, etc. |
| `/` menu: Math / LaTeX (block) | Notion, Logseq, Obsidian | P1 | `$$ formula $$` rendered as math |
| Inline math (between `$...$`) | Logseq, Obsidian, Notion | P1 | KaTeX or MathJax render |
| `/` menu: Date mention | Logseq (calendar picker, creates journal link), Notion, Tana | P1 | Pick a date; insert as `[[2025-06-29]]` link to journal page |
| `/` menu: Page reference | Logseq, Roam | P0 | Alias for `[[` |
| `/` menu: Block reference | Logseq, Roam, Tana | P1 | Alias for `((` |
| `/` menu: Template insertion | Logseq, Notion, Tana | P1 | Insert a named block template |
| `/` menu: Page embed (transclude full page) | Logseq, Roam, Notion | P2 | Render another page's content inline |
| `/` menu: Current time insert | Logseq | P2 | Insert `HH:mm` timestamp |
| Heading shorthand: `# ` at start triggers H1 | Logseq, Obsidian | P1 | Markdown-native trigger: type `## ` at start → convert to heading block |
| `- [ ] ` triggers task block | All markdown outliners | P1 | |
| `` ``` `` at start triggers code block | Logseq, Obsidian | P1 | Optionally followed by language name |
| `> ` at start triggers quote block | Logseq, Obsidian, Notion | P2 | |

---

## 9. Keyboard Shortcuts — Canonical Cross-App Reference

### 9a. Universal / Platform-Standard (must match)

| Action | Shortcut (Desktop) | Present in | SteleKit Priority |
|---|---|---|---|
| New block below (Enter) | Enter | All outliners | P0 |
| New line within block (Shift+Enter) | Shift+Enter | Logseq, Roam, Notion, WorkFlowy | P0 |
| Indent (Tab) | Tab | All | P0 |
| Outdent (Shift+Tab) | Shift+Tab | All | P0 |
| Move block up | Alt+Up | Logseq, Roam, WorkFlowy, Obsidian, Tana | P0 |
| Move block down | Alt+Down | Logseq, Roam, WorkFlowy, Obsidian, Tana | P0 |
| Bold | Ctrl+B / Cmd+B | All | P0 |
| Italic | Ctrl+I / Cmd+I | All | P0 |
| Inline code | Ctrl+E / Cmd+E | Notion, Logseq | P1 |
| Underline | Ctrl+U / Cmd+U | Notion, Craft (not standard Markdown) | P2 |
| Strikethrough | Ctrl+Shift+S | Notion, Obsidian | P2 |
| Insert link | Ctrl+K / Cmd+K | All | P1 |
| Undo | Ctrl+Z / Cmd+Z | All | P0 |
| Redo | Ctrl+Y or Ctrl+Shift+Z | All | P0 |
| Copy | Ctrl+C / Cmd+C | All | P0 |
| Cut | Ctrl+X / Cmd+X | All | P0 |
| Paste | Ctrl+V / Cmd+V | All | P0 |
| Paste plain text | Ctrl+Shift+V / Cmd+Shift+V | Roam, Notion | P1 |
| Select all (blocks on page) | Ctrl+A / Cmd+A | Logseq, Notion | P1 |
| Save / sync (explicit) | Ctrl+S | Some (auto-save is standard now) | P2 (auto-save is P0) |
| Global search | Ctrl+K / Cmd+K | Logseq, Notion; Ctrl+P in Obsidian | P0 |
| Command palette | Ctrl+Shift+P / Cmd+Shift+P | Logseq, Obsidian | P1 |
| Zoom into block | (app-specific — see below) | WorkFlowy, Logseq | P1 |
| Collapse / expand block | Alt+Shift+C (Logseq) | Logseq, WorkFlowy | P1 |
| Duplicate block | Ctrl+D / Cmd+D | Logseq, Notion, Roam | P1 |
| Open linked page | Click (Ctrl+Click for sidebar) | All | P0 |
| Back | Alt+Left / Cmd+[ | Logseq, Obsidian, Roam | P1 |
| Forward | Alt+Right / Cmd+] | Logseq, Obsidian | P1 |
| Jump to today's journal | Alt+D (Logseq) | Logseq, Roam | P1 |
| Exit edit mode / select block | Escape | Logseq | P0 |

### 9b. Logseq Full Shortcut Reference (primary inspiration for SteleKit)

| Action | Shortcut |
|---|---|
| New block | Enter |
| New line in block | Shift+Enter |
| Indent | Tab |
| Outdent | Shift+Tab |
| Move block up | Alt+Up |
| Move block down | Alt+Down |
| Select block (exit edit) | Escape |
| Select block above/below | Up / Down (in block-select mode) |
| Extend selection up | Shift+Up |
| Extend selection down | Shift+Down |
| Delete selected blocks | Backspace |
| Collapse / expand | Alt+Shift+C |
| Cycle TODO state | Ctrl+Enter |
| Copy block reference | Ctrl+Shift+C |
| Bold | Ctrl+B |
| Italic | Ctrl+I |
| Underline | Ctrl+U |
| Strikethrough | Ctrl+Shift+S |
| Inline code | Ctrl+` |
| Link | Ctrl+K |
| Undo | Ctrl+Z |
| Redo | Ctrl+Y |
| Global search | Ctrl+K (also Ctrl+U for UI search) |
| Command palette | Ctrl+Shift+P |
| Back | Alt+Left |
| Forward | Alt+Right |
| Jump to today's journal | Alt+D |
| Jump to all pages list | Ctrl+Shift+J |
| Zoom in on block | (click bullet) |
| Open in right sidebar | Shift+Click block bullet |

### 9c. Notion Full Shortcut Reference

| Action | Shortcut |
|---|---|
| New block below | Enter |
| Soft newline | Shift+Enter |
| Indent (in list) | Tab or Ctrl+] |
| Outdent (in list) | Shift+Tab or Ctrl+[ |
| Bold | Ctrl+B |
| Italic | Ctrl+I |
| Underline | Ctrl+U |
| Strikethrough | Ctrl+Shift+S |
| Inline code | Ctrl+E |
| Link | Ctrl+K |
| Mark as comment | Ctrl+Shift+M |
| Duplicate block | Ctrl+D |
| Slash commands | / |
| Move block up | Alt+Up |
| Move block down | Alt+Down |
| Undo | Ctrl+Z |
| Redo | Ctrl+Shift+Z |
| Search / navigate | Ctrl+P or Ctrl+K |
| Toggle sidebar | Ctrl+\ |
| Select current block | Click drag handle |
| Page back | Ctrl+Shift+[ |
| Page forward | Ctrl+Shift+] |

### 9d. Roam Research Shortcut Reference

| Action | Shortcut |
|---|---|
| New block | Enter |
| New line in block | Shift+Enter |
| Indent | Tab |
| Outdent | Shift+Tab |
| Move block up | Ctrl+Shift+Up (also Alt+Up) |
| Move block down | Ctrl+Shift+Down |
| Bold | Ctrl+B |
| Italic | Ctrl+I |
| Underline | Ctrl+U |
| Inline code | ` (backtick wrapping) |
| Block reference `((` | Type `(( ` |
| Page reference `[[` | Type `[[ ` |
| Duplicate block | Ctrl+D |
| Daily notes | Alt+D |
| Zoom into block | Alt+Click bullet |
| Open in sidebar | Shift+Click |
| Undo | Ctrl+Z |
| Search | Ctrl+U |
| Expand all | Ctrl+Shift+Down (at page level) |

### 9e. WorkFlowy Shortcut Reference

| Action | Shortcut |
|---|---|
| New item | Enter |
| New line | Shift+Enter |
| Indent | Tab |
| Outdent | Shift+Tab |
| Move up | Alt+Shift+Up |
| Move down | Alt+Shift+Down |
| Collapse / expand | Alt+Left / Alt+Right |
| Zoom in | Alt+O or Ctrl+O |
| Complete item | Ctrl+Enter |
| Search | Ctrl+F |
| Select range | Shift+Click |
| Navigate up (out of zoom) | Ctrl+Up |
| Bold | Ctrl+B |
| Italic | Ctrl+I |
| Underline | Ctrl+U |

### 9f. Linear — Shortcut Inspiration (non-outliner, but canonical UX)

Linear is renowned for keyboard-first UX. Key patterns worth adopting:

| Pattern | Implementation |
|---|---|
| Single-key shortcuts (no modifier) in non-edit mode | `j`/`k` for navigation, `x` to select, `c` to create, `e` to edit |
| Fuzzy-search command palette (Ctrl+K) | Instant fuzzy matching, section headers, arrow-key navigation |
| Keyboard shortcut hints shown in tooltips and menus | Every action label shows its shortcut to the right |
| Progressive disclosure: hints only after a pause | Don't show hints immediately; reveal after user pauses on item |
| Shortcut legend / cheat sheet (Ctrl+Shift+?) | Full shortcut reference accessible at any time |

---

## 10. Mobile-Specific Features

| Feature | Best-in-class app | Priority | Notes for SteleKit (Android + iOS) |
|---|---|---|---|
| Floating toolbar above keyboard | Craft (best — full row: B, I, U, `code`, link, indent, outdent, block type, checklist) | P0 | Single most impactful mobile feature; SteleKit must implement a `ContentToolbar` composable for KMP |
| Toolbar: Bold button | Craft, Bear, Notion, Obsidian | P0 | Wraps selection or toggles at cursor |
| Toolbar: Italic button | Same | P0 | |
| Toolbar: Inline code button | Craft, Obsidian | P1 | |
| Toolbar: Link button | Craft, Bear, Notion | P1 | Opens URL entry dialog |
| Toolbar: Indent button | Craft (best), Notion | P0 | Critical for outliner on mobile (no Tab key) |
| Toolbar: Outdent button | Same | P0 | |
| Toolbar: Checkbox / task toggle | Craft, Bear, Notion | P1 | |
| Toolbar: Block type picker (H1, H2, etc.) | Craft, Notion | P1 | |
| Toolbar: `[[` page link trigger | Logseq Mobile | P0 | Mobile users need this; no `[` key on default keyboard |
| Toolbar scrollable (overflow) | Craft | P1 | More actions accessible by scrolling toolbar row |
| Touch drag-drop reorder | Notion (best on mobile), Craft, Logseq Mobile | P1 | Long-press bullet/handle → drag handle appears → drag block |
| Long-press to select block | All mobile apps | P1 | Long-press on block bullet/margin enters selection mode |
| Selection mode: tap to add/remove blocks | Notion, Logseq Mobile | P1 | After entering selection mode, tap blocks to multi-select |
| Swipe right on block to indent | Bear, Craft | P1 | Swipe gesture for indent on mobile |
| Swipe left on block to outdent | Bear, Craft | P1 | |
| Swipe to reveal action buttons (delete, etc.) | Bear (best), Notion | P2 | iOS-style swipe actions |
| Pinch to zoom text size | Bear, Craft | P2 | Accessibility; system font size should be preferred |
| Pull down to search (iOS) | Bear, Notion | P1 | iOS-standard pull-down to expose search |
| Hardware keyboard support on iPad/tablet | Craft (excellent), Obsidian, Notion | P0 | All keyboard shortcuts must work with external keyboard on iPad/Android tablet |
| Hardware keyboard shortcut discoverability (iPad: hold Cmd) | Craft, Notion | P1 | iOS/iPadOS hold-Cmd shows keyboard shortcut overlay |
| Haptic feedback on block actions | Craft, Notion | P2 | Light haptic on drag, on paste, on delete |
| Autocorrect / autocapitalize awareness | Bear (best), Craft | P1 | Disable autocorrect inside `[[...]]` and `code` spans |
| Markdown preview toggle (source ↔ rendered) | Bear (source mode), Obsidian (reading view) | P1 | Especially useful on mobile where inline rendering is harder |

---

## Appendix A: Feature Coverage by App

Quick-reference matrix showing which apps implement each major capability category.

| Capability | Logseq | Roam | Notion | Obsidian | Craft | Bear | WorkFlowy | Tana | Mem |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Block drag-drop reorder | ✓ | ✓ | ✓ | ✓* | ✓ | — | ✓ | ✓ | — |
| Alt+Up/Down move | ✓ | ✓ | ✓ | ✓* | — | — | ✓ | ✓ | — |
| Copy/paste blocks (subtree) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| Block references | ✓ | ✓ | ~synced | ✓** | — | — | — | ✓ | — |
| Zoom into block | ✓ | ✓ | — | — | — | — | ✓ | ✓ | — |
| Slash commands | ✓ | — | ✓ | ✓* | ✓ | — | — | ✓ | — |
| Callout blocks | — | — | ✓ | ✓ | ✓ | — | — | — | — |
| Table blocks | MD | MD | ✓ | ✓* | ✓ | MD | — | ✓ | — |
| Math/LaTeX | ✓ | — | ✓ | ✓ | — | — | — | — | — |
| Date mention (journal link) | ✓ | ✓ | ✓ | ✓ | — | — | — | ✓ | ✓ |
| Find + Replace (page) | ✓ | — | — | ✓ | — | ✓ | — | — | — |
| Full-text search (global) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Back/Forward history | ✓ | ✓ | ✓ | ✓ | — | — | ✓ | ✓ | — |
| Backlinks panel | ✓ | ✓ | — | ✓ | ✓ | — | — | ✓ | ✓ |
| Mobile toolbar | basic | — | ✓ | ✓* | ✓✓ | ✓ | ✓ | ✓ | ✓ |
| Touch drag-drop | ✓ | — | ✓ | ✓ | ✓ | — | ✓ | ✓ | — |
| Templates | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | ✓ | — |
| Undo structural ops | ✓ | ✓ | limited | ✓ | ✓ | ✓ | ✓ | ✓ | — |

`*` = via plugin  `**` = `^anchor` syntax  `MD` = Markdown tables only  `~` = limited/different paradigm

---

## Appendix B: Priority Summary by Feature Domain

| Domain | P0 items | P1 items | P2 items | Stretch |
|---|---|---|---|---|
| Text editing | Word jump, Home/End, Ctrl+B/I, `[[` picker | Inline code, Ctrl+K, Markdown auto-complete, heading shortcuts, `/` slash menu | Underline, strikethrough, triple-click select | Multi-cursor |
| Block manipulation | Drag+subtree, Alt+Up/Down, click-to-select, Shift+Click range, **copy/paste blocks**, merge, split, delete | Move to page, Ctrl+D duplicate, block references, Turn into page, Shift+Up/Down select | Clone/embed, delete+promote children | Synced (live mirror) blocks |
| Hierarchy | Tab/Shift+Tab, bulk indent/outdent, collapse/expand | Collapse all, expand all, zoom/focus mode, breadcrumb | Level guides | |
| Multi-block selection | Esc to select, Shift+Up/Down, range select, bulk indent/outdent, delete | Ctrl+Click non-contiguous, Ctrl+A, bulk move to page | Bulk tag, bulk type-change | |
| Navigation | Arrow-key cross-block, wikilink click, global search | Open in sidebar, Back/Forward, breadcrumb, backlinks panel, recent pages, journal jump | Graph view, ToC, unlinked refs | |
| Search/Replace | (none P0) | Find in page, Find+Replace, full-text global search | Case options, saved queries | Regex |
| Undo/Redo | Text undo, structural undo (all ops), cross-block undo, paste undo | Undo depth ≥50 | | Visual undo preview |
| Block types (slash) | Checkbox/task, code block | Headings, callout, table (MD), math, date mention, template | Image, file, embed, numbered list | Complex tables, embeds |
| Keyboard shortcuts | Canonical shortcut set (all P0 ops above) | Full Logseq parity, command palette, shortcut cheat sheet | Linear-style single-key mode | |
| Mobile | Formatting toolbar, indent/outdent in toolbar, `[[` in toolbar, hardware keyboard | Touch drag-drop, long-press select, swipe indent/outdent, autocorrect suppression | Swipe actions, haptic, pinch zoom | |
