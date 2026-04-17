# Research: Features — Unlinked References UX Survey

## Summary

All major knowledge-graph PKM tools (Logseq, Obsidian, Roam Research) expose an "unlinked references" panel at the bottom of each page that surfaces blocks/paragraphs from other pages containing the current page's exact title string. Multi-word page names are matched as full phrases with word-boundary awareness, though each tool has documented edge cases. The accept-link UX is universally per-occurrence (hover to reveal a "Link" button that wraps the text in `[[...]]`), with bulk "link all" remaining an unimplemented native feature in every tool as of 2026 — addressed only by community plugins. Notion has no unlinked-mentions feature at all.

---

## Logseq

### Inline Highlighting

Logseq does **not** highlight unlinked page-name occurrences inline while editing a block. There is a long-standing feature request (Dec 2021) to visually distinguish matching text within the unlinked references panel itself — users report it is hard to spot the page name in a dense block of text without a highlight. A separate feature request asks for in-editor highlighting of all words matching an existing page title or alias, but this has not shipped natively.

### Multi-Word Page Names

Logseq uses `[[double bracket]]` syntax for multi-word page names. The unlinked references panel performs an exact string match for the page name (or any declared alias). However, matching has known edge cases:

- **Substring false positives**: a page named `art` will surface blocks containing `article` or `artist` because word-boundary enforcement is inconsistent (filed as "char matching too sensitive").
- **Namespace pages**: pages in namespaces (e.g. `project/subpage`) do not reliably appear in unlinked references for either the full name or the leaf component — an open limitation.
- **Alias matching**: unlinked references do not match declared page aliases; only the canonical page name is searched. This is an open inconsistency — aliases work when typing `[[` but are ignored for unlinked reference discovery.
- **Case sensitivity**: the search is case-insensitive for the page title itself.
- **CJK**: CJK character environments have a known bug where unlinked reference content is sometimes truncated or absent.

### Unlinked References Panel

Located at the bottom of every page, below "Linked References". It is collapsed by default and must be manually expanded. Each result shows the full block (or bullet) tree context that contains the matching string. The panel does **not** highlight which part of the block text matched.

### Accept/Reject UX

Logseq's native UI has no per-occurrence "Link" button in the unlinked references panel. Users must manually navigate to the source block and type `[[...]]` around the text themselves. A community plugin (`logseq-plugin-link-unlink`) adds:

- Keyword highlighting within the unlinked reference panel entries.
- A "Link" button per entry to wrap the match in brackets.
- A "Link All" button that simulates clicking all individual Link buttons; it may require multiple clicks to process all visible entries due to rendering batching.

There is no native reject/dismiss/snooze for individual unlinked reference entries.

### Global Workflow

There is no global "unlinked references" view across all pages. Discovery is per-page only. Users must visit each page individually to review its unlinked references.

---

## Obsidian

### Inline Highlighting

Obsidian does **not** highlight unlinked page-name occurrences inline in the editor while writing. Unlinked mentions appear only in the Backlinks side panel, not as inline decorations in the note body.

### Multi-Word Page Names

The "Unlinked mentions" section of the Backlinks panel matches any note whose body contains the **exact title string** of the currently-open note. Key properties:

- **Exact phrase match**: the full page title must appear verbatim; partial word matches are not surfaced.
- **Case insensitive**: matching is case-insensitive. A page titled "Machine Learning" will match "machine learning" in body text.
- **Multi-word works well**: multi-word titles are matched as a complete phrase, not individual words, so "Machine Learning" does not produce false positives from notes that mention only "machine" or "learning".
- **Aliases**: as of current versions, unlinked mentions match on page title only, not aliases — consistent with Logseq's limitation.
- **Exclusions**: text inside code blocks is matched (and shown as unlinked), which is widely considered a bug.

### Unlinked References Panel

Located in the Backlinks core plugin panel, under "Unlinked mentions". The panel is collapsed by default. Each entry shows the matching note title, a snippet of the surrounding paragraph, and the matched text is highlighted within the snippet in the panel view (bold/underline depending on theme). Clicking an entry navigates to the source file.

### Accept/Reject UX

Hovering over an unlinked mention entry in the Backlinks panel reveals a **"Link" button** (per occurrence). Clicking it automatically wraps the matched text in `[[...]]` in the source markdown file — no manual editing required. This is the most polished native accept UX of any tool surveyed.

There is no native reject/ignore mechanism; unlinked mentions reappear every session until linked.

**Bulk "Link All"**: This is an open feature request since at least 2020 and remains unimplemented natively as of 2026. A community plugin (`obsidian-link-all-unlinked-mentions`) provides this as a single-click action.

### Global Workflow

Obsidian has no global unlinked mentions view. The Backlinks panel is scoped to the currently open note. A separate "Outgoing links" panel shows unlinked mentions going outward from the current note. No cross-vault bulk review is available natively.

---

## Roam Research

### Inline Highlighting

Roam Research does **not** highlight unlinked page-name occurrences inline while writing.

### Multi-Word Page Names

Roam uses `[[double brackets]]` for multi-word page names. The unlinked references section performs an exact string search for the page title across all blocks in the graph. Multi-word names are matched as full phrases.

### Unlinked References Panel

Each Roam page has a "Linked References" and "Unlinked References" section at the bottom. The unlinked references section searches across the entire graph for any block containing the page title as plain text (not wrapped in brackets). A practical example: if you wrote a person's name in a note before creating their page, the unlinked references section retroactively surfaces that mention once the page exists.

Roam's native unlinked references panel is more minimal than Logseq's or Obsidian's — entries show the block and its page of origin, but there is no per-entry highlight of the matched substring within the panel.

### Accept/Reject UX

Roam's native UI provides a quick-link option directly from the unlinked references entry — users can click to link right there without navigating to the source block. This is per-occurrence, not bulk. A community extension (`RoamJS Unlink Finder`) extends this workflow further.

There is no native "link all" or reject/dismiss mechanism.

A GitHub feature request ("Reverse Unlinked Reference linking") proposes showing as-you-type matches to current page names while writing new content — i.e., proactive inline suggestion mode — but this has not shipped.

### Global Workflow

No global unlinked references view exists. Discovery is per-page only.

---

## Notion

### Inline Highlighting

Notion has no unlinked-mention feature. There is no mechanism to detect plain-text occurrences of page names that are not explicitly `@`-mentioned or linked.

### Backlinks

Notion's backlinks feature tracks explicit `@`-mentions and internal links only. Every `@`-mention automatically becomes a backlink — there is no concept of an "unlinked" mention because Notion's data model treats mentions as structured references, not free text.

### Accept/Reject UX

Not applicable — Notion's model is "link at write time or not at all."

### Global Workflow

Not applicable.

---

## Common Patterns & Best Practices

### What all tools agree on

1. **Per-page panel, bottom of page**: All tools with this feature place the unlinked references panel below the linked references panel at the page's bottom. This is the established convention.

2. **Exact phrase matching for multi-word names**: All tools match the complete page title as a phrase, not individual words. This is correct behavior — matching individual words would produce too much noise.

3. **Case-insensitive matching**: All tools treat the match as case-insensitive. Users expect "machine learning" and "Machine Learning" to be treated the same.

4. **Per-occurrence accept, no bulk accept**: Every native implementation (Logseq, Obsidian, Roam) requires per-occurrence acceptance. Bulk "link all" is universally delegated to plugins, suggesting it is useful but risky (indiscriminate bulk-linking destroys nuance).

5. **No inline as-you-type suggestions**: None of the surveyed tools highlight unlinked page-name occurrences inline in the editor in real time. This is an unsolved UX frontier.

6. **No global unlinked references view**: All tools scope unlinked reference discovery to the currently-viewed page. Cross-graph bulk review is absent everywhere.

### Unsolved pain points across all tools

- Alias matching: unlinked references do not respect declared aliases in any tool.
- Word boundary handling: false positives from substrings remain a known bug in Logseq; Obsidian handles this better with exact phrase matching.
- No inline dismissal/ignore: once an unlinked mention exists, there is no way to mark it "intentionally unlinked" to suppress it from future panel appearances.
- No confidence scoring: all matches are treated equally regardless of context; there is no ranking or relevance signal.

---

## Recommended UX for SteleKit

### Copy from Obsidian

- **Per-entry "Link" button in the panel** that directly rewrites the source block file on click — this is the most ergonomic native accept UX surveyed.
- **Highlight the matched substring within each panel entry** so users can instantly see which text would be linked, without reading the full block.
- **Exact phrase matching** (not per-word) for multi-word page names to avoid the false-positive problem Logseq has.
- **Case-insensitive matching** as the default.

### Copy from Logseq (plugin pattern) — but make it native

- **"Link All" button** at the panel header level, scoped to the current page's unlinked references. Include a confirmation step to prevent accidental mass-linking.
- **Keyword highlighting within the panel** (the plugin behavior that Logseq's native UI lacks).

### Avoid

- Logseq's **substring matching bug**: enforce word-boundary matching so `art` does not surface `article`.
- The **no-highlight pattern** in panel entries: plain-text entries with no visual indication of which word matched are hard to use.
- Logseq's **collapsed-by-default with no count indicator**: if there are unlinked references, show a count badge on the collapsed section header so users know whether to expand it.

### SteleKit-specific additions

1. **Word-boundary enforcement**: use `\b` or Unicode-aware word-boundary matching when searching block text. This is a correctness improvement over Logseq's known bug.
2. **Alias matching**: when a page has declared aliases, include those alias strings in the unlinked reference search — no other tool does this natively, and it would be a genuine differentiator.
3. **Intentional-unlink dismissal**: allow a user to right-click an unlinked reference entry and choose "Don't suggest this occurrence" to suppress it from future panel appearances. Store dismissals in a sidecar or page property.
4. **Inline editor hints (future)**: as a stretch goal, dim or underline occurrences of known page names in block text to surface potential links while writing — none of the surveyed tools do this inline. A subtle underline (not bold, not disruptive) on hover would be an additive UX improvement.
5. **No global bulk-link view for v1**: keep the scope per-page for the initial release, consistent with all tools surveyed. A global view can be a v2 feature once per-page UX is validated.
