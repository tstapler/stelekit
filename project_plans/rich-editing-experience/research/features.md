# Research: Features & UX Benchmarking — rich-editing-experience

Agent 2 (Features). Research question: what does best-in-class block/outliner editing look like, and where does SteleKit's implementation fall short?

## 1. Comparable products — UX patterns and step counts

### Logseq (upstream — the product this repo is migrating)
SteleKit has no vendored copy of upstream Logseq's editor source to diff against directly (only planning docs: `docs/logseq-compose-plan.md`, `docs/develop-logseq*.md`), so the comparison below is against upstream's documented/public behavior.

- **Tag/link insertion**: type `[[` → autocomplete opens inline (1 keystroke + select). Type `#` → same for tags. Also `((` for block references. **SteleKit has `[[` and `#` autocomplete already at parity** (`BlockEditor.kt` `WIKI_LINK_AUTOCOMPLETE_REGEX` / `HASHTAG_AUTOCOMPLETE_REGEX`), but no `((` block-reference trigger found.
- **Text formatting**: `Ctrl/Mod+B/I` etc., *and* a `/` slash-command menu listing all formatting/insert actions inline, discoverable without memorizing shortcuts. **SteleKit has the hardware shortcuts (Ctrl+B/I/S/H/E/K) but no `/` trigger at all** — confirmed by grep: `BlockEditor.kt`'s `onValueChange` only detects `[[` and `#`, never `/`. This is the single biggest discoverability gap: Logseq's canonical "type / to see everything you can do" affordance does not exist in SteleKit's editor.
- **Block reordering**: `Alt+Shift+Up/Down` moves a block among siblings (1 keystroke). **SteleKit implements `Alt+Up/Down` for the same effect** (`BlockEditor.kt` lines 440-463) — functional parity, slightly different modifier combo.
- **TODO toggling**: `Mod+Enter` (Ctrl+Enter) rotates TODO → DOING → DONE → TODO directly on the focused block, 1 keystroke, no menu. **SteleKit does NOT implement this.** `Key.Enter` handling in `BlockEditor.kt`'s "Standard block keyboard shortcuts" section (line 407) never checks `isCtrlPressed`, so Ctrl+Enter falls through to plain-Enter behavior (splits/creates a block) instead of toggling TODO state. See finding in §2 below — a `toggleTodo` command exists in code but is entirely disconnected from this keybinding.
- **Code blocks/tables**: type `/code` or `` ``` `` then language, `/table` inserts a markdown table skeleton. SteleKit has no equivalent trigger inside the block editor (no `/` menu, no `` ``` `` auto-expand — see §3).

### Notion
- **Tag/link insertion**: `@` mentions pages/people (1 keystroke + select); `[[` also works as an alias in newer versions.
- **Text formatt­ing**: `/` slash-command menu is the primary discovery surface for *every* block type and formatting action — regarded as one of the smoothest insert-affordance patterns in any writing tool. Inline `**`/`*`/`` ` `` markdown shortcuts also auto-convert as you type.
- **Block reordering**: `Escape` selects the current block, arrow keys move block selection, drag handle (6-dot icon on hover) for mouse-driven reordering — 1 keystroke to select + drag, or pure keyboard block-select + move.
- **TODO toggling**: `/todo` or typing `[]` + space auto-converts to a checkbox; click toggles it directly — 1 click, no modal.
- Source: [Notion Help Center — Keyboard shortcuts](https://www.notion.com/help/keyboard-shortcuts), [Using slash commands](https://www.notion.com/help/guides/using-slash-commands)

### Obsidian
- **Tag/link insertion**: `[[` triggers link autocomplete (parity with SteleKit); `#` triggers tag autocomplete.
- **Text formatting**: native markdown live-preview (type `**x**`, see it render inline immediately) plus `Ctrl+B/I` shortcuts; slash-command menu is plugin-provided (`Slash Inserter`, `SlashComplete`), not core — i.e. Obsidian's *core* editor is closer to SteleKit's current shortcut-only model, and the community consensus is that lacking a built-in slash menu is a known gap vs. Notion.
- **Block reordering**: no native drag-and-drop reordering in core; requires the community "Outliner" plugin.
- **TODO toggling**: `- [ ]` / `- [x]` checkbox syntax, click to toggle, `Ctrl+Enter`-style commands assignable via hotkeys.
- Source: [Notion vs Obsidian 2026 comparison](https://www.vantagelabsai.com/blog/notion-vs-obsidian-2025), [Slash Inserter plugin](https://community.obsidian.md/plugins/slash-commands)

### Roam Research
- **Tag/link insertion**: `#tag` and `[[page]]` autocomplete (the pattern SteleKit and Logseq both descend from).
- **Block reordering**: drag handle (bullet itself is draggable) plus keyboard block-move; block referencing (`((uid))`) is a first-class citizen distinct from page links — a triage-worthy feature (`((` in SteleKit — see gap above).
- **TODO toggling**: `{{TODO}}` template macro or typed `[[TODO]]`, click checkbox to toggle.

### Craft / Bear (lighter-weight benchmarks)
- Both lean on `/` or `+`-triggered insert menus for block types (image, table, code) and native drag handles for reordering; formatting toolbars appear contextually on text selection (a floating/bubble toolbar) rather than requiring a persistent toolbar row — worth considering for Desktop/Web where SteleKit currently has no equivalent to a selection-triggered floating formatting bar (only the always-visible/expandable `MobileBlockToolbar` on mobile, and hardware shortcuts on desktop with no visual affordance at all).

### Cross-product pattern summary
| Journey | Logseq upstream | Notion | Obsidian (core) | SteleKit today |
|---|---|---|---|---|
| Insert tag/link | `[[`/`#` autocomplete | `@`/`[[` | `[[`/`#` | `[[`/`#` autocomplete (parity) |
| Format text | shortcut + `/` menu | shortcut + `/` menu + inline markdown | shortcut + live preview | shortcut only, no menu, no visible affordance on desktop |
| Reorder block | `Alt+Shift+↑/↓` | select+arrow or drag handle | plugin-only | `Alt+↑/↓` (parity) |
| Code block/table | `/code`, `/table`, `` ``` `` | `/code`, `/table` | `` ``` ``, plugin slash menu | no trigger found — must hand-type fence syntax |
| Toggle TODO | `Ctrl+Enter` rotates state | `/todo` or `[]` auto-convert | `- [ ]` syntax + click | **no keyboard trigger works** (Ctrl+Enter falls through to plain Enter); must hand-type `TODO ` prefix |

## 2. Codebase gaps found (TODO/FIXME grep + structural trace)

Direct grep for `TODO|FIXME|XXX|HACK` in `BlockEditor.kt`, `MobileBlockToolbar.kt`, `EditorToolbar.kt`, `AnnotationToolbar.kt` returned **zero matches** — there are no inline comment markers flagging known gaps. The real gaps only surface by tracing the command-execution path, and they are more serious than a comment would suggest:

- **A parallel, disconnected command system exists and is dead code with respect to actually editing text.** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/EssentialCommands.kt` defines a full set of `EditorCommand`s — `text.bold`, `text.italic`, `text.code`, `text.strikethrough`, `text.highlight`, `text.link`, `text.heading`, `block.toggle-todo`, `media.image`, etc. — each with a `shortcut` label (e.g. `toggleTodo.shortcut = "Ctrl+Enter"`) and an `execute` lambda that computes a `CommandResult.Success` containing the *would-be* new content (e.g. `newContent` for the TODO toggle) as a `data` map entry.
  - `StelekitViewModel.executeCommand()` (line 1973) calls `commandManager.executeCommand(...)` and **discards the result** — nothing reads `result.data["newContent"]`/`formattedText` and writes it back via `GraphWriter`/`saveBlock`. Confirmed by grepping the whole `ui/` tree for consumption of these command IDs: the only place `cmd.id` is inspected is `updateCommands()` (line 2017-2032), and only to special-case `media.image` (routes to `attachImageCallback`); every other command including `block.toggle-todo` and all `text.*` commands is executed purely for its side-effect-free `CommandResult`, which then vanishes.
  - The **Command Palette itself likely never surfaces these anyway**: `updateCommands()` builds its `CommandContext` with only `currentPageId`/`currentBlockId` (no `selectionStart`/`selectionEnd`/`currentBlockContent`). `CommandTypes.kt` line 109 filters out any command with `config.requiresSelection = true` unless `context.selectionStart < context.selectionEnd`. Every `TextFormatting` command (`bold`, `italic`, `code`, `strikethrough`, `highlight`, `link`, `heading`) sets `requiresSelection = true`, so with the palette's static, selection-less context, **none of them ever appear in `getAvailableCommands()`'s filtered output** — this matches the requirements.md baseline's observation ("no formatting-specific entries") even though the commands are fully implemented in isolation.
  - `block.toggle-todo` uses `config = CommandConfig(requiresBlock = true)` (no selection requirement) so it likely *does* pass the filter and show up in the palette with label "Toggle Todo" / shortcut "Ctrl+Enter" (also present in `I18n.kt`, both locales) — but selecting it from the palette computes `newContent` and throws it away, so **the palette entry is present but non-functional**. This is worth flagging explicitly for the implementation plan: it's not a missing feature, it's a wired-looking feature that silently no-ops.
  - `SlashCommandHandler.kt` similarly maps `"bold" -> "text.bold"`, `"link" -> "text.link"`, `"image" -> "media.image"` etc. for a `/`-style slash-command parser, but as established in §1 there is no `/` keystroke trigger anywhere in `BlockEditor.kt`'s `onValueChange`/autocomplete-regex logic that would ever invoke `isSlashCommand`/`executeSlashCommand`. This is fully-built, entirely unreachable infrastructure from the editing surface.
- **Real, working formatting logic lives entirely separately**, in `BlockEditor.kt`'s private `applyFormatAction` (line ~491) driven by the `FormatAction` enum (`BOLD`, `ITALIC`, `STRIKETHROUGH`, `CODE`, `HIGHLIGHT`, plus line-prefix actions `QUOTE`/`NUMBERED_LIST`/`HEADING`) and dispatched only from hardware `Ctrl+B/I/S/H/E` and the `MobileBlockToolbar` buttons. **Recommendation for the implementation phase: retire/repoint the `EssentialCommands`/`CommandManager`/`SlashCommandHandler` stack to call into `applyFormatAction` and real mutation paths (`onContentChange`/actor-backed save), or delete it — do not leave two parallel, divergent definitions of "what bold means."**
- **No markdown-triggered auto-formatting on typing** was found anywhere in `BlockEditor.kt` (no `startsWith("- ")`, `startsWith("1. ")`, `` startsWith("```") ``, or any post-keystroke auto-convert logic) — confirmed by targeted grep. Each outline bullet is already a separate block (so `- ` prefix triggers are moot for lists), but code-fence (` ``` `) auto-expansion into a code-block region and `1. `→numbered-list auto-conversion, which several benchmarked products offer, are absent.
- **No `((` block-reference autocomplete trigger** — only `[[` (page link) and `#` (tag) regexes exist (`WIKI_LINK_AUTOCOMPLETE_REGEX`, `HASHTAG_AUTOCOMPLETE_REGEX`). Block references are a Logseq/Roam signature feature; confirm with Agent covering data model/parser whether block refs are supported at all in SteleKit's model before scoping this as an editor gap vs. a deeper feature gap.

## 3. Unstated needs vs. current implementation

Beyond explicit formatting, note-taking users commonly expect:
- **Quick block-type conversion** (turn a bullet into a heading/quote/code block without retyping) — partially present: `HEADING`/`QUOTE`/`NUMBERED_LIST` are `FormatAction` line-prefix operations reachable from `MobileBlockToolbar`'s overflow row, but there is no equivalent hardware-keyboard shortcut or `/`-menu path on desktop.
- **Drag handles for reordering** — not found in `BlockEditor.kt`/`BlockItem.kt`/`BlockList.kt` search; reordering is keyboard-only (`Alt+Up/Down`) or via the mobile toolbar's explicit Move Up/Down buttons. No pointer-drag affordance for desktop/web mouse users, which is a strong convention in Notion/Roam/Craft.
- **Swipe gestures on mobile** — not found; `MobileBlockToolbar` is a persistent multi-row button toolbar rather than gesture-driven (e.g. swipe-to-indent, swipe-to-delete), which is heavier on taps than gesture-first competitors.
- **Long-press context menus** — block-level multi-select ("selection mode") exists (`BlockItem.kt`, `BlockRenderer.kt`, `BlockList.kt`, `MobileBlockToolbar.kt` all reference selection-mode state), giving copy/cut/delete/clear actions, but confirm during UX flow analysis whether entry into that mode is a long-press (common pattern) or a separate explicit action.
- **Undo affordance visibility** — `MobileBlockToolbar` has explicit Undo/Redo buttons (bottom row), good parity; desktop/hardware-keyboard path was not confirmed to have a visible undo affordance (likely relies on system-standard Ctrl+Z, unconfirmed in this pass — flag for Agent 1/keyboard-shortcut research).
- **Multi-cursor/multi-block editing** — no evidence found; out of scope per requirements (collaborative editing is explicitly out of scope, and multi-cursor is not called out as an explicit journey either).
- **Markdown-shortcut auto-formatting while typing** (e.g. `` ``` `` → code block, `1. ` → numbered list) — confirmed absent, see §2. This is a real unstated-need gap: users coming from Notion/Obsidian/Roam will expect typing to trigger structure, not just explicit toolbar/shortcut actions.

## 4. Edge cases / failure modes for toolbar and shortcut design

- **Empty selection**: `applyFormatAction` (BlockEditor.kt) already handles this — wraps at cursor with markers and places cursor between them (e.g. `**|**`) when `selection.collapsed`, rather than erroring or no-op'ing. Confirm this same fallback exists for `MobileBlockToolbar`'s formatting buttons and the (currently non-functional) `EssentialCommands.TextFormatting` set, which explicitly declares `requiresSelection = true` and would need a defined empty-selection behavior if ever wired up.
- **Multi-block selection**: formatting/shortcut actions are defined at the single-textfield (`TextFieldValue`) level; there is a separate block-level "selection mode" (copy/cut/delete/clear only) but no evidence any formatting action (bold/link/etc.) is selection-mode-aware across multiple blocks at once — worth an explicit design decision (disable formatting shortcuts entirely during multi-block selection mode, or apply per-block).
- **IME composition state (Android/iOS)**: no explicit IME composition-range guard found in `BlockEditor.kt`'s `onValueChange`/autocomplete trigger logic (the only "composition" hits in the codebase are Jetpack Compose's own recomposition, unrelated to IME text composition). This matters because the `[[`/`#` autocomplete regexes run on every `onValueChange`, including intermediate IME composition updates for CJK/Japanese/Korean input — triggering autocomplete mid-composition (before the user commits a character) is a known failure mode in text editors and should be explicitly tested/guarded (e.g. checking `newValue.composition` is null before firing autocomplete).
- **RTL text**: no custom `LayoutDirection`/RTL-specific logic found anywhere in the editing surfaces (only unrelated hits in `AutocompleteMenu.kt` did not pertain to RTL). SteleKit likely inherits default Compose `BasicTextField` bidi behavior, which is usually correct for plain text but autocomplete-popup positioning (`textLayoutResult?.getCursorRect`) and the `[[`/`#` trigger regexes (which scan `textBeforeCursor` assuming LTR visual order) may misbehave with RTL content mixed with LTR wiki-links/tags — flag as a test case, not confirmed broken.
- **Very long blocks**: no `maxLines`, chunking, or virtualization guard found in `BlockEditor.kt` for a single block's text field. Given the repo's hard architectural rule against O(graph) reads (see CLAUDE.md "Graph-scale reads" section), an analogous concern exists at the single-block level: a pathologically long block (e.g. a pasted large table or log dump) re-running the `[[`/`#` regex scan and `getCursorRect` layout call on every keystroke could become a perceptible input-latency problem; not scoped as broken, but should get a benchmark data point during validation.
- **Ctrl+Enter overload**: confirmed concretely in §1/§2 — `Ctrl+Enter` is already claimed for "create new page from autocomplete query" (line 258, only active while `autocompleteState != null`) and is *also* the documented shortcut for `EssentialCommands.toggleTodo`. If TODO-toggle is wired to real hardware-keyboard dispatch during implementation, this collision must be resolved (e.g. autocomplete-active Ctrl+Enter wins when a query is open; plain Ctrl+Enter toggles TODO when not autocompleting) rather than silently picked by code ordering.

## Sources
- [Notion Help Center — Keyboard shortcuts](https://www.notion.com/help/keyboard-shortcuts)
- [Notion — Using slash commands](https://www.notion.com/help/guides/using-slash-commands)
- [Notion vs Obsidian in 2026: The Definitive Comparison](https://www.vantagelabsai.com/blog/notion-vs-obsidian-2025)
- [Slash Inserter — Obsidian Plugin](https://community.obsidian.md/plugins/slash-commands)
- [SlashComplete – Obsidian Plugin](https://www.obsidianstats.com/plugins/slash-complete)
- [Logseq keyboard shortcuts — DefKey](https://defkey.com/logseq-shortcuts)
- [Logseq Documentation — Shortcuts](https://chrislasar.github.io/logseq-doc/docs/reference/shortcuts/)
- [Logseq Markdown: Cheat Sheet with Syntax Examples — Face Dragons](https://facedragons.com/foss/logseq-markdown-cheat-sheet/)
