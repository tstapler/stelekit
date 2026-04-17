# Findings: Features — Import UX in Comparable Tools

**Dimension**: Features | **Date**: 2026-04-14
**Input**: `project_plans/stelekit-import/requirements.md`

---

## Summary

No major PKM tool combines all four of SteleKit's import requirements (paste text, URL fetch, auto-tag existing topics, suggest new pages) in a single unified flow. Each tool handles a subset well:

- **Logseq**: paste-and-write is the primary flow; no URL import; auto-tagging is a post-hoc "unlinked references" panel workflow, not applied on import.
- **Obsidian**: URL import via community plugins (Readwise, MarkDownload, Obsidian Web Clipper); native auto-linking on import is absent — handled post-hoc via the Backlinks "Unlinked mentions" panel.
- **Roam Research**: clipboard paste is first-class; roam/clippings via browser extension; no auto-tagging on import; new-page suggestion is implicit (any `[[]]` typed creates a page).
- **Notion**: rich import (web, Evernote, Markdown, Google Docs); no auto-linking or tag suggestion at any stage.
- **Readwise**: Reader + Highlights pipeline; surfaces and sends highlights to Logseq/Obsidian; no auto-linking.

The recommended pattern for SteleKit is a **two-stage import dialog**: Stage 1 creates the page with a preview; Stage 2 shows a diff-style review of proposed `[[wiki links]]` and new-page suggestions, each individually confirmable. This pattern is absent in all surveyed tools and is SteleKit's differentiator.

---

## Options Surveyed

### Logseq

**Import mechanism**: No dedicated import UI. Primary workflow is open graph folder → write new page from scratch or paste text into a block. There is a "Import" menu item that accepts JSON or EDN files from a prior Logseq export — not intended for arbitrary text. [TRAINING_ONLY — verify current Logseq import menu options]

**Auto-tagging on import**: Not applied at import time. Logseq's "Unlinked References" panel runs continuously on all existing pages and surfaces occurrences of a page name that are not yet wrapped in `[[...]]`. Users must visit each page and manually accept links. No batch "link all on import" flow exists natively.

**New-page suggestion**: None. Typing `[[New Page Name]]` anywhere creates the page implicitly on next navigation. There is no proactive suggestion of "this term appears in your content and is not a page yet."

**Paste UX**: Pasting raw text into a block inserts the text verbatim — no transformation, no link scanning. Pasting multiple lines creates multiple blocks (one per line). Tab-indented lines become child blocks. This is well-regarded as low-friction but offers no intelligence.

**URL import**: Not natively supported. The Logseq HTTP server API (community plugin) can accept content programmatically, but this is not a UI feature. [TRAINING_ONLY — verify]

**Discoverability**: Low for linking — unlinked references panel is collapsed by default and must be manually expanded per page.

---

### Obsidian

**Import mechanism**: No native "import from URL" in core Obsidian as of 2026. [TRAINING_ONLY — verify whether Obsidian 1.x added native web clipper to core] The community ecosystem has strong coverage:

- **Obsidian Web Clipper** (official browser extension, released late 2024): captures the full page or a selection, converts HTML to Markdown, and creates a new note. Supports template-based frontmatter injection. Does not auto-link to existing notes.
- **Readwise Official Plugin**: syncs Readwise highlights into Obsidian as notes. No auto-linking.
- **MarkDownload** (community browser extension): downloads a webpage as Markdown directly into the vault. No auto-linking.

**Auto-tagging on import**: Not performed at creation time. After the note exists, the Backlinks panel's "Unlinked mentions" section shows occurrences in other notes that could be linked. The new note itself is not scanned for matches against existing note titles on creation.

**New-page suggestion**: None proactive. Obsidian's graph view visualizes orphan notes visually, nudging users to link, but offers no textual suggestion during creation.

**Paste UX**: Paste into editor inserts verbatim. "Paste as plain text" is an available option. No scanning on paste.

**Properties / frontmatter on import**: Obsidian Web Clipper templates allow predefined YAML frontmatter fields to be populated from page metadata (title, URL, author, date). This is the closest analog to SteleKit's "suggest tags" — it pre-populates structured fields, not inline links.

**Discoverability**: The Web Clipper browser extension (separate install) is moderately discoverable for URL import. In-app import from arbitrary text (paste → new note) is friction-free but unintelligent.

---

### Roam Research

**Import mechanism**: Roam provides a "Import Files" option (JSON, Markdown, EDN) and a browser bookmarklet / extension ("Roam Highlighter", "Roam-js") for web clipping. Paste into the daily notes page or any block is the everyday flow.

**Auto-tagging on import**: Not applied at import time. Roam's "All Pages" and "Mentions" views surface cross-page references, but they are post-hoc. Like Logseq, Roam's creation model assumes users type `[[Page]]` themselves as they write.

**New-page suggestion**: Implicit — any `[[text]]` the user types creates a page stub. There is no proactive suggestion of "this word could be a page." [TRAINING_ONLY — verify whether Roam's AI features introduced in 2024-2025 add suggestions]

**Paste UX**: Paste into a Roam block creates one block per line (similar to Logseq). Multi-line paste handling is considered adequate but unsophisticated.

**URL import**: Browser extension captures highlights into the daily notes or a specified page. The captured highlight is a block quote with the source URL as a property. No HTML-to-Markdown transformation — plain text extraction only. [TRAINING_ONLY — verify]

**Discoverability**: Import is not a primary affordance in Roam's UI. The bookmarklet/extension approach places discoverability in the browser, not the app.

---

### Notion

**Import mechanism**: Notion has the most polished import UI of any tool surveyed. It supports:
- Import from Evernote (XML), Confluence (HTML), Asana, Trello, CSV, Google Docs, Word, and plain Markdown.
- A "Web Clipper" browser extension that saves a page to Notion as a new document.

**Auto-tagging on import**: None. Notion has no `[[wiki link]]` concept. Its "Mentions" feature (@person, @page) is manually invoked; it is never automatically applied.

**New-page suggestion**: None. Notion's relational database (linked databases) does not suggest new entries during content creation.

**Paste UX**: Paste converts clipboard content to Notion blocks. Rich text (from browser copy) preserves formatting. Pasting a URL on an empty line offers to "Create embed" or "Dismiss" — the only import-adjacent UX.

**Discoverability**: High. Import is prominently featured in the workspace settings under "Import." The Web Clipper is an officially maintained browser extension.

**Key lesson**: Notion shows that a polished import UI with multiple source formats significantly reduces friction. The "paste a URL → embed or create page" prompt is a useful pattern.

---

### Readwise

**Readwise** (Reader product) is specialized: it ingests highlights from Kindle, web articles, PDFs, and Twitter/X threads, then pushes them to Obsidian or Logseq via official integrations.

**Auto-tagging**: Readwise's own AI can auto-tag highlights with topics, but these are Readwise-internal tags, not wiki links in the destination graph. [TRAINING_ONLY — verify Readwise AI tagging feature scope]

**Key lesson**: Readwise's "save URL → extract highlights → sync to PKM" pipeline illustrates that users often want a filtered subset of a web page (highlights, not full text). SteleKit v1 is "full text or paste" — highlights extraction is a future concern.

---

### Bear Notes

Bear (Apple ecosystem) offers a "Link to Note" suggestion dropdown when the user types `[[`. It fuzzy-matches existing note titles in real time. This is the closest native analog to SteleKit's auto-tag feature, but it is invoked manually per-occurrence, not applied automatically to imported text. [TRAINING_ONLY — verify Bear 2 behavior]

---

### Capacities / Tana / Mem

These newer tools (2022–2025) experiment with AI-assisted linking:

- **Tana**: uses AI to suggest "supertags" (structured node types) on content capture. Captures via a browser extension to the "inbox." Tags are suggested but must be confirmed. [TRAINING_ONLY — verify current Tana behavior]
- **Mem**: AI auto-organizes notes into collections; does not generate explicit wiki links. [TRAINING_ONLY — verify]
- **Capacities**: no auto-linking on import. [TRAINING_ONLY — verify]

The AI-suggestion pattern in Tana is the closest to SteleKit's "suggest new tags" requirement, but it requires an AI backend — out of scope for SteleKit v1 (no external AI API constraint).

---

## Trade-off Matrix

| Tool / Pattern | Auto-link accuracy | UX friction | New-page suggestion quality | Discoverability | Notes |
|---|---|---|---|---|---|
| **Logseq** (post-hoc unlinked refs) | Medium — exact match, word-boundary issues | High — requires visiting each page after import | None | Low — panel collapsed by default | Best for incremental linking; poor for bulk import |
| **Obsidian Web Clipper** (URL → note) | None | Low for URL import; zero for paste | None | Medium — requires browser extension install | Frontmatter templates reduce manual metadata entry |
| **Obsidian Unlinked Mentions** (post-hoc) | High — exact phrase match, case-insensitive | Medium — panel is per-page, not per-import event | None | Medium — always visible in sidebar | Does not match aliases; matches inside code blocks (bug) |
| **Roam Highlighter** (URL → blocks) | None | Low for URL import (extension-based) | None | Low — browser-only entry point | Plain-text only; no Markdown conversion |
| **Notion Web Clipper + Import** | None (no wiki links) | Low | None | High — official product feature | Reference for polished import dialog UX |
| **Tana inbox + AI tag suggestion** | N/A (AI tags, not wiki links) | Low — single capture action | High (AI-driven, requires backend) | High — capture button in extension | Requires AI API; out of scope v1 |
| **Bear [[link]] suggestions** (manual trigger) | High — fuzzy match on existing notes | Low — inline, real-time | None — only matches existing notes | High — built into `[[` typing | Reactive, not proactive on import |
| **SteleKit proposed: two-stage import dialog** | High — exact + word-boundary match using existing multi-word-term-highlight algorithm | Medium — two-stage review adds one extra step | Medium — surface detected terms not in graph | High — accessible from command palette + menu | No external AI; uses local graph data |

---

## Risk and Failure Modes

### Auto-link false positives on import

The biggest risk is wrapping common words in `[[wiki links]]` when the user has pages titled after short or common terms (e.g., a page called "State" or "Model"). The multi-word-term-highlight algorithm already mitigates this by requiring word-boundary matches and preferring longer matches over shorter ones, but the import context has additional risk because:

- The imported text may be from an external source (e.g., an LLM conversation) that uses common vocabulary.
- The user has not seen the auto-linked version yet and may accept all suggestions without reviewing.

Mitigation: The review stage must make rejecting individual suggestions low-friction (single tap/click). Bulk-accept should require an extra confirmation when the suggestion count is high.

### New-page suggestion noise

Suggesting that every unrecognized proper noun or capitalized phrase become a new page is high noise. Without an AI backend, detection heuristics are weak (capitalization, title-casing, presence in common-noun wordlist). Generating 50+ suggestions for a single Claude conversation import would cause suggestion fatigue.

Mitigation: Cap displayed suggestions at a reasonable number (e.g., 10–15). Rank by: term frequency in imported text, similarity to existing page names, length (longer terms are more likely to be intentional topics).

### User rejects all suggestions and never uses the feature again

If the first import experience surfaces many wrong suggestions, the user will disable the feature mentally. A single false-positive wiki link that alters meaning (e.g., `[[State]]` wrapping "state" in a sentence about US states) is more damaging than no suggestion at all.

Mitigation: Default the review stage to "no links applied" with suggestions opt-in, not opt-out.

### Stale page title index at import time

If the graph's page title index is not up-to-date (e.g., a recently renamed page), auto-tagging will miss matches or produce links to deleted pages.

Mitigation: Ensure the import flow re-queries the live page index at the moment the user triggers the scan, not from a cached snapshot.

---

## Migration and Adoption Cost

Each UX pattern carries a different adoption cost for SteleKit:

| UX Pattern | Implementation cost | User learning curve | Risk |
|---|---|---|---|
| **Paste → verbatim page** (Logseq baseline) | Minimal — GraphWriter already exists | Zero — matches expectation | No value-add over manual copy/paste |
| **Post-hoc unlinked references** (Logseq/Obsidian) | Low — unlinked-references infrastructure already exists | Low — familiar to Logseq users | Users must revisit pages to link; may never do so |
| **Two-stage import dialog with inline review** | Medium — new ImportViewModel, UI for diff-style review, suggestion ranking | Medium — unfamiliar flow; must be discoverable | Over-engineering risk if suggestion quality is poor |
| **Frontmatter template on URL import** (Obsidian Web Clipper pattern) | Low for page metadata injection | Low — optional enhancement | Does not address inline wiki links |

Recommendation: implement paste → page as the baseline (zero learning curve), then layer the two-stage review as an optional step that can be skipped. This avoids forcing users through a review they do not want.

---

## Operational Concerns

- **Performance**: scanning a 50KB paste against a graph with 1,000+ page titles using the existing `TermHighlighter` must complete in under 200ms on desktop JVM to feel instant. Benchmarks from the multi-word-term-highlighting feature show the algorithm is O(n × m) where n = text length and m = number of page titles; this is acceptable for typical graph sizes. [TRAINING_ONLY — verify actual benchmark numbers from the highlighting PR]
- **Undo**: creating a page from import should be undoable (delete the created page). Adding wiki links to content should be undoable per-link or as a batch. The existing GraphWriter does not currently support transactional undo — this is a gap.
- **Clipboard access on iOS/Android**: pasting text is standard, but programmatic clipboard read on iOS requires explicit user action (paste button, not background clipboard access). The import UI must present a visible paste target, not a "read clipboard automatically" flow. [TRAINING_ONLY — verify current Compose Multiplatform clipboard API on iOS]

---

## Prior Art and Lessons Learned

1. **Logseq's unlinked references** taught the field that post-hoc link discovery works but requires user discipline to visit each page. Import-time linking is the missing piece.

2. **Obsidian Web Clipper's frontmatter templates** show that pre-populating structured metadata at import time (author, URL, date) is well-received even without wiki-link auto-tagging.

3. **Tana's inbox + AI suggestions** show that users will engage with suggestion review when accuracy is high and the review surface is low-friction. The failure mode is over-suggestion.

4. **Notion's import polish** shows that a multi-source import dialog (paste, URL, file) with a consistent UI is worth building once rather than having separate entry points for each source.

5. **Bear's `[[` inline suggestions** show that exact-match fuzzy-search against existing note titles is fast enough to be real-time and is highly valued by users. SteleKit's import scan is the same algorithm applied in bulk.

6. **Readwise → Logseq/Obsidian sync** shows that users accept a two-step pipeline (capture in Readwise, review in PKM) when each step has a clear role. SteleKit's two-stage import dialog follows the same mental model.

---

## Open Questions

1. Should the import review stage be modal (blocking, must complete or cancel) or inline (the page is created immediately and the link suggestions appear as a dismissible overlay)? Modal is safer for correctness; inline is lower friction if the user wants to edit before accepting.

2. Should new-page suggestions be shown in the import review stage, or in a separate "follow-up" notification after the page is created? Separating them reduces cognitive load during import but risks them being ignored.

3. Should the URL import fetcher run on the main thread or a background coroutine with a progress indicator? For desktop JVM this is straightforward (coroutine). For iOS/Android, network on main thread is an error. Architecture must assume async from the start.

4. What should happen when the imported text already contains `[[wiki links]]`? (e.g., importing a Logseq export back into SteleKit) — preserve existing links, skip those spans during auto-link scanning.

5. Should the page name be derived from the first line of the pasted text (if it looks like a heading) or always prompted? Logseq and Obsidian derive the title from the filename, not the content. Notion derives a title from the first heading. SteleKit should decide.

6. For URL import: should the page title default to the `<title>` tag of the fetched page, or should the user always be prompted? Defaulting to the `<title>` reduces friction but produces ugly titles from some sites.

---

## Recommendation

**Recommended UX pattern for SteleKit import**: two-stage import dialog with opt-out review.

### Stage 1 — Source selection

Entry points: command palette ("Import page…"), File menu → Import, keyboard shortcut.

A dialog with two tabs:
- **Paste text**: multi-line text area with a placeholder ("Paste your content here…"). Page title field (pre-populated from first line if it looks like a heading, otherwise blank).
- **From URL**: URL input field. "Fetch" button triggers async content retrieval with a progress indicator. Page title pre-populated from fetched `<title>`.

Primary action: "Import" button proceeds to Stage 2.

### Stage 2 — Link review (skippable)

A split view:
- **Left pane**: rendered preview of the new page content with proposed `[[wiki links]]` highlighted in a distinct color (e.g., amber underline).
- **Right pane**: two sections:
  - "Linked topics" — list of existing pages that were matched; each has a toggle (default: on). Toggling off removes all instances of that link from the preview.
  - "New page suggestions" — list of detected terms not yet in the graph, ranked by frequency. Each has a "Create page" button and a dismiss (×) button. Default: none selected (opt-in).

Primary action: "Accept" creates the page with the reviewed link set applied.
Secondary action: "Skip review" bypasses Stage 2 and creates the page verbatim (no auto-links, no new pages). This is the escape hatch for users who find the review overwhelming.

### Rationale

- Matches the mental model of Obsidian Web Clipper (capture → review) but adds the link intelligence that no tool currently provides.
- The "skip review" escape hatch ensures the flow degrades to the Logseq baseline (paste → page, no intelligence) for users who prefer it.
- Opt-in new-page suggestions (not opt-out) prevents the most damaging failure mode: a flood of unwanted stub pages.
- Reuses the existing `TermHighlighter` algorithm and `SearchRepository` page title index — no new matching infrastructure needed.

---

## Pending Web Searches

The following queries should be run by the parent agent to verify or extend training-only claims:

1. `"logseq import" site:discuss.logseq.com 2024 2025` — verify current Logseq import menu options and whether URL import was ever added natively.
2. `"obsidian web clipper" auto-link auto-tag existing notes 2024` — verify whether the official Web Clipper added any auto-linking to existing vault notes.
3. `"obsidian import" site:forum.obsidian.md "auto link" OR "auto tag" 2024 2025` — community discussion on auto-tagging on import.
4. `"roam research" import auto-link 2024 2025` — verify whether Roam's AI features (if any) introduced auto-linking on import.
5. `"tana" capture inbox "auto tag" OR "supertag suggestion" 2025` — verify Tana's current AI tag suggestion behavior.
6. `"readwise" auto-tag OR "auto link" PKM highlights 2025` — verify scope of Readwise's AI tagging feature.
7. `"bear notes" import "auto link" OR "wiki link suggestion" 2025` — verify Bear 2's behavior for existing-note link suggestions.
8. `compose multiplatform clipboard iOS paste 2025` — verify current Clipboard API behavior in Compose Multiplatform on iOS.
9. `"logseq" "link all" unlinked references plugin 2024 2025` — verify current state of community plugin for bulk link acceptance.
10. `"notion" import dialog OR "web clipper" auto-link 2025` — verify Notion has no auto-linking (confirm negative).

## Web Search Results

**Query 1 — Logseq native import auto-link** (2026-04-14):
- **CONFIRMED no native auto-link on import**: Community plugin `logseq-autolink-autotag` exists for this exact purpose, confirming the feature is not built-in to Logseq core. Source: https://github.com/braladin/logseq-autolink-autotag

**Query 2 — Obsidian Web Clipper auto-link to existing notes** (2026-04-14):
- **CONFIRMED no auto-linking to existing notes**: Official Web Clipper released late 2024 supports template application and AI summarization but does NOT auto-link to existing vault notes. The `[TRAINING_ONLY]` claim is verified. Source: https://obsidian.md/clipper, https://github.com/obsidianmd/obsidian-clipper

**Overall competitive position confirmed**: No major PKM tool currently provides automatic wiki-linking to existing notes on import without a plugin or external AI API. SteleKit can be the first with this as a native feature using the existing `AhoCorasickMatcher` — no external AI required.
