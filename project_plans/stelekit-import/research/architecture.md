# Findings: Architecture — Import Integration with SteleKit

## Summary

SteleKit already owns all the primitives needed for text-import with auto-wiki-linking:

- **`AhoCorasickMatcher`** (`domain/AhoCorasickMatcher.kt`) — O(n) multi-pattern matcher with word-boundary enforcement and overlap resolution; already used in production for unlinked-reference highlighting.
- **`PageNameIndex`** (`domain/PageNameIndex.kt`) — reactive `StateFlow<AhoCorasickMatcher?>` that rebuilds the trie whenever `PageRepository` emits a new page list; already wired into `StelekitViewModel.suggestionMatcher`.
- **`GraphWriter.savePage(page, blocks, graphPath)`** (`db/GraphWriter.kt`) — immediate write path; handles file-path resolution, safety-checks, sidecar creation and `filePath` back-propagation.
- **`StelekitViewModel.createPage(pageName)`** (private) and `navigateToPageByName(pageName)` — create-or-navigate idiom already in production.
- **Command palette** (`ui/components/CommandPalette.kt`) — fuzzy-searchable list of `Command(id, label, shortcut, action)` objects fed from `AppState.commands`; new commands are registered by appending to the list in `StelekitViewModel.updateCommands()`.
- **`Screen` sealed class** (`ui/AppState.kt`) — already has `GlobalUnlinkedReferences` as a first-class screen; the import screen follows the same pattern.

The import feature therefore needs: one new service class (`ImportService`), one new ViewModel (`ImportViewModel`), one new `Screen` variant (`ImportPage`), one new Composable (`ImportScreen`), and a command-palette entry.

---

## Options Surveyed

### Option A — Thin domain service + dedicated screen (recommended)

Create a `domain/ImportService.kt` that accepts raw text + `AhoCorasickMatcher` and returns `ImportResult(linkedText, newPageSuggestions)`. Wire a new `ImportViewModel` to it and expose a new `Screen.Import` that hosts a text area, a preview panel, and a "new pages to create" checklist. Entry point: command palette command `"import.paste-text"` + (optional) a sidebar button.

**New pages to suggest** are all `MatchSpan.canonicalName` values whose page does not already exist in `PageRepository`. After the user confirms, the screen calls `StelekitViewModel.navigateToPageByName()` for each accepted new page, then `GraphWriter.savePage()` with the linked-text page.

### Option B — Import as a ViewModel method on `StelekitViewModel`

Add `fun importTextAsPage(rawText: String, pageName: String)` directly to the existing ViewModel. Fewer files, but `StelekitViewModel` is already 1 100+ lines; adding import logic there further degrades separation of concerns and makes the method hard to test in isolation.

### Option C — Reuse `GlobalUnlinkedReferencesViewModel` pattern

Structurally the import flow is an inverted version of the unlinked-references flow: instead of scanning *existing* blocks for page-name matches and proposing links, import scans *incoming* text before it is persisted. The `GlobalUnlinkedReferencesViewModel` pattern (ViewModel owns state, accepts `AhoCorasickMatcher?`, writes via `DatabaseWriteActor`) is a direct template. Duplicating the entire screen would be wrong, but the ViewModel pattern and state shape are worth mirroring exactly.

### Option D — Import via a slash command inside the editor

Trigger import inside an existing block editor using the `SlashCommandHandler`. This would allow "import from clipboard" inline. Problem: it assumes the user is already inside a page, while import should create a *new* page. Also clipboard access is platform-specific and already proxied through `ClipboardProvider`; a cross-platform paste UI is simpler as a dedicated screen dialog.

---

## Trade-off Matrix

| Option | Code reuse | Separation of concerns | Testability | UI discoverability | Notes |
|---|---|---|---|---|---|
| A — Thin domain service + screen | High — reuses `AhoCorasickMatcher`, `PageNameIndex`, `GraphWriter`, `navigateToPageByName` | Good — logic in `ImportService`, state in `ImportViewModel` | High — `ImportService` is pure function; ViewModel uses fake repos | High — command palette + optional sidebar | Recommended |
| B — ViewModel method | Medium — same primitives but inline | Poor — bloats `StelekitViewModel` | Medium — harder to unit-test in isolation | Medium — command palette only, no dedicated UI | Pragmatic shortcut, not recommended |
| C — Extend `GlobalUnlinkedReferencesViewModel` | Medium — shares structural pattern | Moderate — re-use stretches a class beyond its purpose | Medium | Low — buried in existing screen | Confuses two distinct UX flows |
| D — Slash command | Low for new-page creation | Poor | Low | Low | Wrong UX model |

---

## Risk and Failure Modes

1. **Matcher not yet ready** — `PageNameIndex.matcher` starts as `null` until the first page list arrives. `ImportViewModel` must gate the scan behind a non-null check and surface a "loading page index…" state to the user instead of silently producing no suggestions.

2. **Large paste text** — `AhoCorasickMatcher.findAll` is O(text length + matches). For a pasted article of tens of thousands of characters this is fast, but it runs on whatever thread calls it. `ImportService` should dispatch to `Dispatchers.Default` (same pattern as `PageNameIndex`).

3. **Page-name collision** — `navigateToPageByName` already handles the case where a page exists; it falls back to `loadPageByName` then `createPage`. Import must not call `createPage` for pages that already exist. The correct check is: a suggested new-page name is one that appears in the match results but is *absent* from `pageRepository.getAllPages()`. The `PageNameIndex` already filters these out of the matcher (only existing pages are in the trie), so any match from the matcher is *by definition* an existing page. New-page suggestions must come from a *second*, separate NLP step (e.g. entity extraction or user-supplied keywords) or from user-typed additions in the UI — not from the Aho-Corasick pass.

    > **Clarification needed**: The original requirement says "suggest creating new pages for detected topics not yet in the graph." This requires a topic-extraction mechanism that is *outside* `AhoCorasickMatcher`, which only matches known page names. Possible approaches: (a) regex NLP for noun phrases, (b) user manually adds candidate names in the UI before scanning, (c) a future LLM call. Option (b) is the safest first version.

4. **`GraphWriter` safety check** — `savePageInternal` aborts if more than 50% of blocks are deleted versus the old file. For a brand-new page (no file on disk), `fileSystem.fileExists(filePath)` returns false and the check is skipped — safe.

5. **`Validation.validateContent` length limit** — `Block.content` is capped at 10 000 000 chars, `Page.name` at 255. Long-form pasted text distributed across many blocks is fine; a single block holding the entire article might hit the limit on extreme inputs.

6. **Concurrent writes** — `GraphWriter` uses `saveMutex`. An import triggering `savePage` while the user is actively editing will block briefly; acceptable given the 500 ms debounce window on normal edits.

7. **Platform clipboard access** — Desktop has `ClipboardProvider` via `PlatformClipboardProvider`. Android SAF paths and iOS may have additional permission requirements. The import screen should accept text via a `TextField` (always safe) and optionally offer a "Paste from clipboard" button that delegates to `ClipboardProvider`.

---

## Migration and Adoption Cost

- **No schema changes needed.** Import creates pages and blocks through the existing `PageRepository`/`BlockRepository` + `GraphWriter` path.
- **No new platform abstractions needed.** Pasted text is plain `String`; URL-fetching (if added later) would need a platform `HttpClient` but that is out of scope for v1.
- **Existing test infrastructure applies.** `InMemoryRepositories` + a constructed `AhoCorasickMatcher` is sufficient for unit tests of `ImportService`. The `GlobalUnlinkedReferencesViewModelTest` is the closest existing test to model against.
- **Estimated new files**: `ImportService.kt` (domain), `ImportViewModel.kt` (ui/screens), `ImportScreen.kt` (ui/screens), plus additions to `AppState.kt` (one new `Screen` variant) and `StelekitViewModel.updateCommands()` (one new `Command` entry).

---

## Operational Concerns

- **No background re-indexing required.** GraphLoader's file-watcher will detect the newly written file and update the DB automatically after `GraphWriter` writes it; `onFileWritten` suppresses the spurious external-change event.
- **Sidecar write.** `GraphWriter.savePageInternal` writes a sidecar when `SidecarManager` is non-null. This is automatic — import gets sidecar support for free.
- **Undo.** `DatabaseWriteActor` + `UndoManager` (via `OperationLogger`) record block saves. An imported page's blocks are therefore undo-able in the same session if the undo stack is available.

---

## Prior Art and Lessons Learned

- `GlobalUnlinkedReferencesViewModel` (committed in the `multi-word-term-highlighting` feature) is the strongest reference design. Key lessons it encodes:
    - **Stale-guard (ADR-002)**: compare `capturedContent` against live block content before writing. The same guard is necessary in import: if the user edits the preview text in the `ImportScreen` after the scan runs, re-scan before saving.
    - **Prefer `writeActor` over direct repository writes** when available; fall back to direct writes in test/in-memory mode.
    - **`AhoCorasickMatcher` is null-safe and nullable by design** — consumers check for null rather than blocking on initialization.
- `ExportService` (export feature) demonstrates the pattern of a focused domain service (`ImportService` mirrors it) that is constructed once in `GraphContent` and passed to the ViewModel.
- `updateCommands()` pattern in `StelekitViewModel` — export commands were added in the same function. Import command follows identically.

---

## Open Questions

1. **Topic extraction for "new page" suggestions** — The Aho-Corasick pass only matches *existing* pages. How will the feature identify *new* topic candidates? Options: user-driven (text box to add candidate names), regex noun-phrase extraction, or a future LLM step. Decision needed before implementation.

2. **URL fetch** — Is fetching from a URL in scope for v1? If so, a `platform/` interface (`UrlFetcher`) is needed with JVM/Android/iOS implementations. This can be deferred.

3. **Destination page name** — When saving, what is the default page title? Options: user-specified in the import dialog, auto-derived from the first heading in the text, or a timestamp. UX decision needed.

4. **Outliner format vs. flat paragraphs** — Pasted text is prose (paragraphs, newlines). Should `ImportService` split on `\n\n` to create top-level blocks, on `\n` for bullet-per-line, or preserve as a single block? Logseq convention is one bullet per "thought." Decision affects `Block` construction.

5. **Duplicate detection** — If the user imports the same text twice (creating two pages with the same name), `createPage` will fail the `Validation.validateName` uniqueness check at the `PageRepository` level. Should the import screen warn if the proposed page name already exists?

6. **`Screen.Import` vs modal dialog** — Should import be a full screen (like `GlobalUnlinkedReferences`) or a `Dialog` overlay? A full screen provides more space for the preview; a dialog feels more transient. Recommendation: full screen for feature parity with unlinked-references.

---

## Recommendation

Implement **Option A** — a thin `ImportService` in `domain/` + a dedicated `ImportViewModel` + `ImportScreen`.

**Minimal v1 scope:**
1. `ImportService` takes `(rawText: String, matcher: AhoCorasickMatcher?) -> ImportResult`. It runs on `Dispatchers.Default`, calls `matcher.findAll(rawText)`, and rewrites the text by inserting `[[canonicalName]]` around each `MatchSpan`. Returns `linkedText` and no new-page suggestions in v1 (defer topic extraction to v2).
2. `ImportViewModel` holds a `MutableStateFlow<ImportState>` with fields: `rawText`, `linkedText`, `pageName`, `isProcessing`, `errorMessage`. Calls `ImportService` on text change (debounced 300 ms, same as `DebounceManager` usage in `StelekitViewModel`). On confirm, calls `StelekitViewModel.createPage` (or an extracted `createPage` helper) then `GraphWriter.savePage`.
3. `ImportScreen` — `TextField` for raw text, `Text` (annotated) preview using `parseMarkdownWithStyling` with `suggestionMatcher`, page-name input, Confirm/Cancel buttons.
4. `Screen.Import` added to the sealed class; `navigateTo(Screen.Import)` called from a new command-palette entry `"import.paste-text"` / label `"Import text as new page"`.

This keeps `StelekitViewModel` clean, reuses every existing primitive, and is fully testable with `InMemoryRepositories`.

---

## Pending Web Searches

- Search: "Kotlin Multiplatform clipboard paste multiplatform" — to verify whether `ClipboardManager` in Compose Multiplatform exposes `getClipboardText()` consistently on Android, iOS and Desktop in 2025 releases, or whether a custom `expect/actual` is needed.
- Search: "Compose Multiplatform TextField large text performance" — to confirm there are no known performance regressions when binding a `TextField` to a multi-kilobyte `String` state on Android/iOS.
- Search: "Logseq import from clipboard" — to check if Logseq's original feature set informs any UX conventions (e.g. block splitting rules, property injection) that SteleKit should match for user familiarity.
