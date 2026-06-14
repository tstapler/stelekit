# Asset Management — Feature Research

**Date**: 2026-06-13  
**Branch**: stelekit-assets  
**Phase**: Research (Phase 2 of MDD)

---

## 1. Obsidian Vault Attachments

### Attachment Folder Organization

Obsidian supports multiple attachment-storage modes, configurable in Settings → Files & Links → Default location for new attachments:

| Mode | Behavior |
|---|---|
| Vault folder | Single flat `<vault>/attachments/` |
| Same folder as note | Attachment sits beside the note file |
| In subfolder under current folder | Creates a per-note subfolder (e.g. `MyNote/assets/`) |
| Specified path | Any user-defined path |

There is **no native typed-subfolder routing** (images→images/, pdfs→pdfs/) in core Obsidian. Typing is entirely user-driven through naming conventions or third-party plugins.

### Broken Link Handling When Files Are Moved

This is the primary pain point in Obsidian's ecosystem:

- **Core behavior**: Obsidian does auto-update wikilinks (`[[filename]]`) when you move a file within the vault via the app, but only for the links _pointing to_ the moved file. Links _inside_ the moved file to other files (relative paths) are not always updated correctly in relative-path mode.
- **Relative-path mode bug**: In relative path mode, if a note containing relative links is moved, those relative links break because Obsidian only updates links _to_ the moved file, not links _from_ it.
- **External moves (Finder/Files app)**: If a file is moved outside Obsidian while the app is open or closed, links are not updated at all. The community workaround is to always move files via Obsidian's own file explorer.
- **Closed-app moves**: There is an open feature request (no native support as of 2026) for auto-updating links for files moved while Obsidian is closed.

### Community Plugins Filling the Gap

Two widely-used plugins address what core Obsidian leaves broken:

**Consistent Attachments and Links** (dy-sh):
- Moves attachments together with their parent note
- Checks whether an attachment is referenced by multiple notes before moving; if shared, it copies instead of moves
- Keeps attachment links consistent after note moves

**Update Relative Links**:
- Fixes the core bug where links inside a moved file are not updated
- Triggers automatically after any file move

### Asset Browser UX (Obsidian)

Obsidian has no native media/asset browser. The community fills this with:
- **Find Orphaned Images**: Scans vault, identifies images with zero backlinks, allows batch deletion
- **Find Unlinked Files**: Broader orphan detection covering all file types
- **Bases** (official, 2025): Allows creating database views of vault files — users are using it to build ad-hoc attachment dashboards with thumbnail previews

The vault graph view does not include attachments. Attachments are second-class citizens in Obsidian's knowledge model.

### Design Recommendation for SteleKit

Obsidian's core weakness — broken links on external moves and no typed routing — is exactly where SteleKit can lead. The `BacklinkRenamer` pattern already in the codebase (DB + disk rewrite in a single actor transaction) is architecturally stronger than Obsidian's approach. Extend it to handle asset moves.

---

## 2. Logseq Asset Handling

### Canonical Link Format

When a user drags or pastes a file into Logseq, it is copied to `<graph>/assets/` and a block is inserted with:

```markdown
[DisplayName.pdf](../assets/DisplayName_1658000515517_0.pdf)
```

Key points:
- The path is always relative: `../assets/<filename>` (two dots up from the current page file, then into assets)
- A timestamp suffix (`_<epoch>_0`) is appended to prevent collisions
- Images use `![alt](../assets/image.jpg)` markdown image syntax
- The relative path is calculated from the page file location (`pages/` or `journals/`)

SteleKit's current `ImageImportService` already produces exactly this format (`"../assets/images/$filename"`) which is correct for Logseq compatibility.

### Subfolder Support

Logseq does **not** natively support typed subfolders within `assets/`. The flat `assets/` directory is the only supported intake path. A user can manually create `assets/images/` and adjust the link to `../assets/images/filename.jpg`, but:
- Logseq provides no UI to create or manage subfolders
- There is a long-standing community feature request for date-based or type-based subfolders (open since ~2022, not implemented as of 2026)
- The path `../subfolder/` (without "assets" in it) is sometimes cited as an alternate convention, but it is non-standard

### Moved/Renamed Assets

Logseq has no built-in link-update mechanism for assets. If a user moves a file in the file system:
- The block markdown link becomes stale immediately
- The image/file fails to render in the UI
- There is no conflict detection or recovery path

This is a known weakness — Logseq treats asset links as opaque strings.

### Design Recommendation for SteleKit

SteleKit's typed-subfolder routing (`assets/images/`, `assets/pdfs/`, etc.) is a genuine improvement over Logseq's flat model. However, to maintain Logseq compatibility for users who sync their graph with the desktop Logseq app, the relative path format `../assets/<subdir>/<filename>` must be produced correctly relative to the page's location. The existing `"../assets/images/$filename"` pattern already handles this for images; extend it to `../assets/pdfs/`, `../assets/audio/`, etc.

---

## 3. Notion Gallery View — UX Patterns

### How the Gallery Works

Notion's gallery view is a database view that renders each row as a card. Key UX decisions:

- **Card preview source**: Users choose whether the card thumbnail pulls from "Page cover", "Page content" (first image found), or a specific `Files & media` property. This "what is the thumbnail?" choice is the most critical UX decision for a media browser.
- **Card sizes**: Small / Medium / Large toggle — adjusts grid density.
- **Aspect ratio control**: Square, portrait, landscape.

### Filtering

- Per-property filter chips ("Filter") accessible via the three-dot menu or a dedicated toolbar button
- Filters are stackable and ANDed by default
- Common filter axes for media: Tags, Status, Date, a custom `Type` property

### Sorting

- Multi-level sort: primary → secondary sort keys
- Common sort options for media: Date Added (newest first), Name A→Z, File Size

### Grouping

- "Group by" clusters cards by any property value (e.g. by `Type` shows Images / PDFs / Audio as columns)
- This is the Notion equivalent of "type filter tabs"

### What Notion Does Not Do

- No inline file metadata extraction (dimensions, duration, EXIF)
- No orphan detection
- No "find all images on this page" view — the gallery is always a database, not a page-level view
- Uploading a file and viewing it in a gallery require two separate steps (create DB row, then attach file)

### Design Recommendation for SteleKit

The Notion pattern that maps best to SteleKit's asset browser is:
1. **Type tabs** (not grouping) — horizontal chip row: All / Images / PDFs / Audio / Video / Documents. Chips are faster to tap than a dropdown group-by.
2. **Card size toggle** in a toolbar (small = more density, large = thumbnail-forward)
3. **"Card preview"** for non-image assets: use a file-type icon on a colored background (PDF = red, Audio = purple, etc.)
4. **Stacked filter + sort sheet** accessible from a single toolbar button (matches iOS pattern of "filter" bottom sheet)

---

## 4. Asset Browser UX Patterns (Mobile + Desktop)

### Grid vs. List Toggle

The dominant pattern across iOS Photos, Google Photos, Android Gallery, and Finder:
- **Grid** (default): 3-column on mobile, 4–5 column on desktop. Thumbnail-forward. Best for images and video.
- **List**: One row per asset, smaller thumbnail, filename, size, date visible. Best for documents and audio.
- Toggle is a toolbar icon (grid icon / list icon), not a menu item.
- Persist the last-used mode per-user in preferences.

### Type Filtering

Three proven patterns:

| Pattern | Examples | Best For |
|---|---|---|
| **Horizontal chip bar** | Spotify, Google Maps, iOS Notes attachments | ≤7 categories, always visible |
| **Segmented control** | Apple Notes attachment browser | ≤5 categories, always visible |
| **Bottom sheet / dropdown** | Android Files app, Google Drive | Many categories or facets |

Apple Notes attachments browser uses a **segmented control** with exactly the right categories for SteleKit: Photos & Videos / Scans / Websites / Audio / Documents. This is the closest prior art.

### Search

- Persistent search bar at top (not behind a tap)
- Search tokens: filename, tag, page name
- Real-time filtering as user types (debounced 300ms)
- Clear button to reset

### Bulk Selection

- Long-press or "Select" button enters multi-select mode
- Checkboxes appear on each item
- Floating action bar at bottom: Move to / Delete / Share / Add Tag
- "Select All" in toolbar when in selection mode

### Drag-and-Drop (Desktop)

- Assets draggable to: another tag/group in sidebar, a note editor (inserts markdown link), Finder
- Accept drops from: Finder, mail attachments, browser (download URL)

### Sorting

Standard options for an asset browser:
1. Date added (newest first) — default
2. Date added (oldest first)
3. Name A→Z
4. Name Z→A
5. File size (largest first)
6. Type

### Design Recommendation for SteleKit

For mobile: horizontal chip bar (type filter) + grid/list toggle in a compact toolbar row below the search bar. Long-press for multi-select. Toolbar collapses to icon-only on small screens.

For desktop: sidebar with type tree (All / Images / PDFs / Audio / Video / Documents / Custom Groups) + main content pane grid/list. Keyboard shortcut `⌘G`/`⌘L` for grid/list toggle.

---

## 5. Local Asset Search Architecture

### The Problem

500+ local files need to be searchable by:
- Filename substring
- Tag (user-applied)
- Auto-label (ML output)
- Page name (which notes reference this asset)
- Media type

### Option A: SQLite FTS5

FTS5 is SQLite's full-text search extension. It builds an inverted index (LSM-tree internally) stored in virtual tables alongside the main data.

**Pros**:
- Already on the classpath (SQLDelight uses SQLite)
- Sub-millisecond queries on 10,000+ rows
- Token-aware: prefix search, phrase search, relevance ranking (BM25)
- Survives DB restarts (persisted on disk)
- Handles multi-valued fields via space-separated token lists

**Cons**:
- FTS5 tokenizer is word-based — filename stems like `meeting-2024-01-15` need a custom tokenizer or pre-processing
- FTS5 tables are separate from the main table; must be kept in sync on every insert/update/delete

**At 500 files**: FTS5 is significantly overengineered. SQLite `LIKE '%query%'` with a B-tree index on `lower(filename)` is fast enough for 500 rows. FTS5 becomes the right choice at ~5,000+ rows.

### Option B: In-Memory Trie / Inverted Index

Build in Kotlin at graph-load time from the asset index table.

**Pros**:
- Zero query latency after build (pure memory)
- Custom tokenization (split on `-`, `_`, `.`, whitespace)
- No schema changes

**Cons**:
- Lost on process kill; must be rebuilt on every cold start
- Memory cost grows with graph size (500 assets × ~200 bytes metadata ≈ 100KB — negligible)
- Multi-valued fields (tags list, pageUuids list) require custom inverted index

### Option C: Hybrid (Recommended)

- **Primary store**: SQLDelight `asset_index` table with standard B-tree indexes on `lower(filename)`, `media_type`, `imported_at_ms`
- **Tag/label search**: SQLite `json_each()` or a normalized join table `asset_tags(asset_uuid, tag)` with an index on `tag`
- **Page-name search**: Join table `asset_page_refs(asset_uuid, page_uuid)` — already mirrors the `pageUuids` field
- **Full-text on filename**: SQLite `LIKE '%query%'` with `lower()` is sufficient for 500–5,000 files; add FTS5 virtual table only if profiling shows it as a bottleneck

**Why not FTS5 now**: The asset corpus is small. Over-indexing creates two tables to keep in sync for no measurable user benefit at 500 files. Design the schema so FTS5 can be added as a migration later.

### Design Recommendation for SteleKit

```
asset_index table:
  uuid TEXT PK
  file_path TEXT NOT NULL
  media_type TEXT NOT NULL  -- "image", "pdf", "audio", "video", "document", "other"
  filename_lower TEXT NOT NULL  -- lower(basename) for LIKE queries
  size_bytes INTEGER
  imported_at_ms INTEGER
  is_orphan INTEGER DEFAULT 0  -- 1 = file deleted from disk

asset_tags table:
  asset_uuid TEXT REFERENCES asset_index(uuid)
  tag TEXT NOT NULL
  INDEX(tag)

asset_page_refs table:
  asset_uuid TEXT REFERENCES asset_index(uuid)
  page_uuid TEXT NOT NULL
  INDEX(page_uuid)
```

Search query for `"meeting notes"` across filename + tags + page names:
```sql
SELECT DISTINCT ai.* FROM asset_index ai
LEFT JOIN asset_tags at ON at.asset_uuid = ai.uuid
LEFT JOIN asset_page_refs apr ON apr.asset_uuid = ai.uuid
LEFT JOIN pages p ON p.uuid = apr.page_uuid
WHERE ai.filename_lower LIKE '%meeting%notes%'
   OR at.tag LIKE '%meeting%'
   OR lower(p.name) LIKE '%meeting%'
ORDER BY ai.imported_at_ms DESC
LIMIT 100
```

---

## 6. Edge Cases

### 6.1 External File Move (TOCTOU)

**Scenario**: User moves `assets/images/photo.jpg` to `assets/images/archive/photo.jpg` in Finder while SteleKit is open.

**Detection**: The existing `GraphFileWatcher` (5-second polling + platform-native fast-path) watches `pages/` and `journals/` but **not** `assets/`. Extend it to watch the `assets/` subtree.

**Response strategy**:
1. On deletion event for a known asset path: mark `is_orphan = 1` in `asset_index`
2. On creation event for a new path in `assets/`: check if content hash matches a known orphan asset — if yes, update `file_path` and clear `is_orphan` (treat as a move, not delete+new)
3. Surface orphan assets to user with a "Locate file" action in the Asset Browser

**For the markdown links**: The existing `BacklinkRenamer` handles this for pages. For assets, implement an `AssetLinkRewriter` that finds all blocks containing `../assets/<old-path>` and rewrites them to `../assets/<new-path>`.

**Multi-page reference**: The rewriter must query `asset_page_refs` to get all pages that reference the asset, then rewrite all affected blocks — not just the most recently opened page. This is the same pattern as `BacklinkRenamer.execute()`.

### 6.2 Two Graphs Sharing the Same Assets Directory

**Scenario**: User has two graphs both pointing at the same `assets/` directory (e.g. personal and work graph both reading from a shared folder).

**Risk**: Both graphs maintain separate SQLite databases and separate `asset_index` tables. Writes from Graph A could be detected as external changes by Graph B's watcher.

**Mitigation**: This is a user configuration error, but SteleKit should not crash. The asset watcher should treat any change in the assets directory as an external change event (same as the existing `GraphFileWatcher` model) and reload the affected entries rather than asserting ownership.

**Sidecar conflict**: Both graphs would write to `.stelekit/images/<uuid>.measure.json` for the same files. Since UUIDs are globally unique, there is no filename collision risk. The risk is two databases each having partial views of the sidecar set.

**Recommendation**: Document this as unsupported, but handle gracefully (no assertion failures, no data corruption).

### 6.3 Asset Referenced from Multiple Pages

**Scenario**: `report.pdf` is embedded in both `Meeting Notes.md` and `Project Summary.md`. User moves `report.pdf` to `pdfs/` subfolder.

**Correct behavior**: Both pages' markdown links must be updated atomically. Using the `asset_page_refs` join table, the rewriter can enumerate all `page_uuid` values for the asset and dispatch parallel rewrite jobs (up to 4 concurrent, matching the `BacklinkRenamer` semaphore pattern).

**Incorrect behavior to avoid**: Updating only the page that was open in the editor, leaving the other pages with broken links.

### 6.4 Extension/MIME Type Mismatch

**Scenario**: A file named `photo.jpg` is actually a PNG (or WebP, or HEIC).

**Detection approach**: Read the first 12 bytes (magic bytes / file signature) at import time:
- PNG: `\x89PNG\r\n\x1a\n`
- JPEG: `\xFF\xD8\xFF`
- WebP: `RIFF....WEBP`
- PDF: `%PDF`
- MP3: `ID3` or `\xFF\xFB`

**Strategy**:
1. At intake, detect actual MIME type from magic bytes
2. Use the detected type for routing to the correct subfolder (images/ vs documents/)
3. Store `detected_mime_type` separately from `declared_extension` in `asset_index`
4. Do not rename the file — preserve the original filename to avoid breaking any external references the user may have
5. Show a warning badge in the asset browser: "Extension says .jpg but content is PNG"

**Platform support**: Android has `MimeTypeMap.getSingleton()` and `ContentResolver.getType()`. JVM has Apache Tika or a custom magic-byte reader. iOS has `UTType` from UniformTypeIdentifiers. Implement a `MimeTypeDetector` interface in `commonMain` with platform impls.

### 6.5 Very Large Assets (1GB Video)

**Indexing**: Store only metadata — size, duration, thumbnail path — not content. Never load 1GB into memory.

**Thumbnail generation**:
- Android: `MediaMetadataRetriever.getFrameAtTime()` — extract one frame at seek position 0
- JVM Desktop: FFmpeg (if installed) or `javax.imageio` for the first frame; fall back to file-type icon
- iOS: `AVAssetImageGenerator`
- Thumbnail is stored as a small JPEG in `.stelekit/thumbnails/<asset_uuid>.jpg` (analogous to the existing sidecar pattern)

**Index entry**: Include `thumbnail_path`, `duration_ms`, `width_px`, `height_px` in `asset_index`. These are nullable — absent for non-video/non-image types.

**Asset browser rendering**: Never load the full file; always render from the pre-generated thumbnail. For video, show a play-button overlay on the thumbnail.

**Search**: Large files should not be excluded from search. Size is just another indexed field; the browser shows `1.2 GB` in the list view.

### 6.6 Asset Deleted from Disk Externally

**Scenario**: User deletes `assets/images/photo.jpg` in Finder.

**Detection**: The extended assets watcher (see 6.1) fires a deletion event. Mark `is_orphan = 1`.

**UI surface**:
- Orphan badge in the asset browser (red dot or strikethrough)
- A dedicated "Orphaned Assets" filter in the type chip bar
- Per-orphan actions: "Remove references" (delete all `![]()` blocks pointing to it), "Locate file" (open file picker to re-point)
- Option to "Clean up all orphans" in a batch action

**Index cleanup**: Do not automatically delete orphan rows. Preserve them so the user can take deliberate action. Rows are deleted from `asset_index` only when the user explicitly removes them or when "Clean up orphans" is run.

---

## 7. Users' Unstated Needs

Beyond the explicit requirements, prior art and user forum research surfaces these latent desires:

### 7.1 "Find All Media in My Meeting Notes"

Users want to navigate _from_ a page _to_ its assets (already possible via `asset_page_refs`) and _from_ an asset _to_ all pages that reference it (reverse lookup). The asset detail view should show "Referenced in: Meeting Notes, Project Summary" as clickable page links.

### 7.2 Thumbnail Previews Everywhere

In Obsidian discussions and Logseq forums, the single most-requested asset feature is thumbnail previews in the asset list — not filenames. The asset browser must render image thumbnails (Coil-based, already integrated via `SteleKitAssetFetcher`) and generate thumbnails for video and PDF first-pages.

### 7.3 Usage Statistics

Users want to know: "How much disk space do my assets consume?" and "Which assets are largest?" A summary panel showing total asset count, total size (MB), and a type breakdown chart addresses this without requiring a separate analytics tab.

### 7.4 Orphaned Asset Detection as a First-Class Feature

Obsidian users rely entirely on community plugins to find orphaned assets. This is a high-leverage built-in feature: after every graph sync or page delete, run a reconciliation pass and surface orphans proactively. The `ImageSidecarIndexer.rebuildFromSidecars()` pattern is the right model — extend it to all asset types.

### 7.5 "I Want to Attach a File to Multiple Pages"

Users embed the same PDF or image in multiple notes. The current model (copy on each attach) means the file is duplicated. Power users want a single canonical file referenced from many pages. The `asset_page_refs` join table enables this: import once, reference everywhere. The UI entry point is "Attach existing asset" in the block editor.

### 7.6 Quick Re-attach After Sync Conflict

When users sync their graph between devices, the assets directory may arrive in a different state (file added on iOS, not yet synced to desktop). The asset browser should show sync-pending assets with a clock badge and allow manual "retry sync" from the detail view.

### 7.7 Search by Content, Not Just Filename

Advanced users expect "find all images that contain text 'invoice'" — i.e., OCR-indexed search. This maps to Phase 2's ML Kit TextRecognition pipeline. The Phase 1 schema should include an `ocr_text` column in `asset_index` (nullable, populated later by Phase 2) so Phase 2 can extend the search query without a schema migration.

---

## 8. Summary of Concrete Design Recommendations

| Area | Recommendation |
|---|---|
| **Typed routing** | `assets/images/`, `assets/pdfs/`, `assets/audio/`, `assets/video/`, `assets/documents/`, `assets/files/` — detected at intake by MIME magic bytes, not extension |
| **Markdown link format** | `../assets/<subdir>/<filename>` — relative to page file, preserving Logseq compatibility |
| **Multi-page link update** | Query `asset_page_refs` for all referencing pages; rewrite all in parallel with 4-coroutine semaphore (mirror `BacklinkRenamer` pattern) |
| **Asset browser type filter** | Horizontal chip bar: All / Images / PDFs / Audio / Video / Documents / Orphaned |
| **Grid/list toggle** | Toolbar icon pair; grid default; persist preference |
| **Search** | Persistent top bar; query across `filename_lower`, `asset_tags.tag`, `pages.name` via JOIN |
| **Search index** | SQLite B-tree + LIKE for Phase 1; add FTS5 only when profiling shows need (>5,000 assets) |
| **Orphan detection** | Extend `GraphFileWatcher` to assets subtree; mark `is_orphan=1` on deletion; surface in browser |
| **External move detection** | Content-hash match on new asset creation event to detect rename-vs-new; offer "Update links" action |
| **Large video** | Index metadata only; generate thumbnail via platform API; render thumbnail in browser |
| **MIME mismatch** | Magic-byte detection at intake; store `detected_mime_type`; warn in UI; do not rename file |
| **Shared asset** | Prevent duplication via "Attach existing asset" UI; `asset_page_refs` as the reference model |
| **Phase 2 schema hook** | Include nullable `ocr_text TEXT`, `auto_labels TEXT` (JSON array) in `asset_index` from day 1 |

---

## Sources

- [Consistent Attachments and Links Plugin](https://github.com/dy-sh/obsidian-consistent-attachments-and-links)
- [Obsidian Broken Links Bug Report](https://forum.obsidian.md/t/broken-links-in-relative-path-mode-on-move-rename/4386)
- [Obsidian Auto-update links for files moved while closed](https://forum.obsidian.md/t/auto-update-links-for-files-moved-while-obsidian-is-closed/91670)
- [Find Orphaned Images Plugin](https://github.com/josmarcristello/Obsidian-Find-Orphaned-Images)
- [Obsidian Attachments Forum — Newbie Q&A](https://forum.obsidian.md/t/newbie-questions-attachments-how-to-deal-with-them/80461)
- [Logseq assets relative path discussion](https://discuss.logseq.com/t/the-relative-path-for-files-in-assets-folder/4582)
- [Logseq subfolder feature request](https://discuss.logseq.com/t/organize-assets-in-subfolders-based-on-date/1478)
- [Logseq understanding attachments](https://discuss.logseq.com/t/understanding-the-proper-way-to-handle-attachements-assets/8910)
- [Notion Gallery View Guide](https://super.so/blog/notion-gallery-view-a-comprehensive-guide)
- [Notion Views, Filters, Sorts](https://www.notion.com/help/views-filters-and-sorts)
- [Apple Notes Attachment Browser (macOS Sequoia)](https://appleinsider.com/inside/macos-sequoia/tips/how-to-use-the-new-attachments-browser-in-notes-for-macos)
- [Apple Notes — View Attachments](https://support.apple.com/guide/notes/view-attachments-apd9953dabf9/mac)
- [Smashing Magazine — Mobile UI Patterns for Search, Sort, Filter](https://www.smashingmagazine.com/2012/04/ui-patterns-for-mobile-apps-search-sort-filter/)
- [SQLite FTS5 Extension](https://sqlite.org/fts5.html)
- [FTS5 in Practice](https://thelinuxcode.com/sqlite-full-text-search-fts5-in-practice-fast-search-ranking-and-real-world-patterns/)
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Magic Bytes / MIME type detection](https://pye.hashnode.dev/how-to-validate-javascript-file-types-with-magic-bytes-and-mime-type)
- [Video Thumbnail Generation with FFmpeg](https://sebi.io/posts/2024-12-21-faster-thumbnail-generation-with-ffmpeg-seeking/)
