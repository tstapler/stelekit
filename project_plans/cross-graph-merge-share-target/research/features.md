# Feature landscape: cross-graph merge and share target

Confidence: VERIFIED = opened in this repo; INDUSTRY = from prior knowledge, no live source fetched this session (UNVERIFIED, re-check before citing).

## 1. Existing in-repo building blocks (VERIFIED)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/GraphMergeService.kt`: snapshot of every page as serialized markdown (`LogseqPageSerializer`), then `merge` skips pages whose lowercase name exists, imports the rest via `QrImportService` + `GraphLoader`. Page-level only, in-memory, whole graph; `snapshot` loads all page markdown at once (conflicts with the 8k-page NFR).
- `GraphManager.activeRepositorySet` (`db/GraphManager.kt:99`) is a single `StateFlow<RepositorySet?>`; `switchGraph` (line 804) closes the old DB. This is the root of the cross-graph write constraint.
- `capture/CaptureWriter.writeCapture(repoSet, fileSystem, graphPath, text, captureId)`: headless, takes a `RepositorySet` parameter (not the active one), appends to today's journal, derives a deterministic block UUID from `captureId` for idempotent replay. Good seam for a graph-chosen share target if a target `RepositorySet` can be opened.
- `jvmMain/capture/CaptureController.kt` and `CapturePopupState.kt` (desktop quick capture); Android `CaptureActivity` / `CaptureViewModel` live in `androidApp` (not read here; the ACTION_SEND grep over `.xml` failed on a zsh glob, so the manifest filters are unconfirmed beyond the requirements doc).
- `db/DiskConflictBlockMatcher.kt`: ordinal-path matching of blocks (explicitly "an ordinal-position heuristic, not an identity match"). Reusable idea for position-based matching, not for identity.
- `db/ContentHasher` use: `blocks.content_hash` column (SHA-256 of normalised content, "used for deduplication", `SteleDatabase.sq:35`). Content-equality matching already has a normalisation definition to reuse.
- `db/RelocationStagingDirectory.kt`, `GraphRelocationCoordinator`, `BulkCopyVerifier`: staging dir and copy-verification patterns for large graph moves; candidates for asset copy and for a staged merge.
- `ui/screens/git/JournalMergeReviewScreen.kt`: existing merge-review UI for git journal conflicts; reuse look-and-feel for conflict review (requirements say it is out of scope functionally).
- Schema facts (`SteleDatabase.sq`): `blocks.uuid TEXT NOT NULL UNIQUE`, `pages.uuid PRIMARY KEY`, `parent_uuid`/`left_uuid` FKs, `position` ordering (fractional indexing via `FractionalIndexing`). `Page.namespace` exists in `model/Models.kt:107`. Aliases: no hit in `Models.kt` (alias likely stored as a page property; unconfirmed).

## 2. Industry landscape (INDUSTRY, unverified this session)
- Logseq: no native graph merge. Common workflows are copy files between graph folders and re-index; "Export graph" / "Import" produce duplicates or `___` namespace filename clashes. Block `id::` properties are what preserve block refs across copies; copying a file with `id::` into a graph that already has that id causes duplicate-UUID warnings. Takeaway: users expect file-copy semantics to "just work" and hit UUID collision.
- Obsidian: vault merge is file-level (copy folders; conflict = same path). Obsidian Sync/Git use whole-file 3-way merge; Obsidian "Merge" plugin-style tools offer per-file keep/replace/both. No block identity, so block-level merge is not a convention; users accept "keep both with suffix".
- Git/outliner merge: line-based 3-way merge on an outline file yields conflict markers inside nested bullets; tools like Logseq git-sync and Org-mode merge drivers need a structure-aware merge. Closest model for us: a set-union CRDT-like merge keyed by block id (as in Roam/Tana/Notion exports), additive only. Two-way merge (no common ancestor) cannot detect deletions, which matches the additive-only requirement.
- Android share targets: standard pattern is an overlay/bottom-sheet `ACTION_SEND` activity with a destination chip (Keep, Todoist, Obsidian "Share to vault" via Advanced URI/plugin), remembering the last destination; Android Direct Share (`ShortcutInfoCompat` sharing shortcuts) can expose each graph as a first-class target in the system chooser. This is a cheap route to "pick graph in one step" without any in-app picker.
- iOS share extensions run in a separate process with an App Group container; writing to a graph requires the graph to live in the shared container or a queued inbox. Web Share Target needs a PWA manifest `share_target`; wasm app would have to be installed as a PWA. Both are low-cost only as "inbox" mechanisms.

## 3. Edge cases and failure modes the design must handle
Block identity
- Same block, different UUID (independently created, or imported through `QrImportService` which may regenerate UUIDs): match by normalised-content hash among siblings under a matching parent path, not globally, to avoid merging unrelated blocks with common text ("TODO", "-", empty).
- Same UUID, different content: true conflict. Never overwrite (`INSERT OR REPLACE` semantics would clobber target content). Keep both: give the source block a fresh UUID and place as sibling, tagged/flagged for review.
- UUID collision across pages: source block UUID already exists in target on a different page (copy-pasted blocks). `blocks.uuid` is UNIQUE per DB, so insertion fails or replaces; must remap UUID and rewrite `((uuid))` block refs and embeds in all imported content.
- Empty blocks, whitespace-only differences, trailing `id::` / `collapsed::` properties changing hash: normalise by excluding those properties.
- Idempotency: remapped UUIDs must be deterministic (e.g. derived from source UUID + source graph id), else repeating the merge duplicates. Mirrors `CaptureWriter`'s `captureId`-derived UUID approach.
Ordering and tree
- Place merged blocks relative to matched anchors (after the previous matched sibling) using fractional `position`; unmatched-parent children need the parent created first. Cycles are impossible if parents are resolved by match then insert top-down.
- Reordered-in-source blocks that already match: leave target order alone (additive-only).
- Collapsed state and children-of-conflict blocks: children follow their (possibly remapped) parent.
Properties
- Page and block property union; same key, different value is a conflict: keep target value, surface source value. Multi-valued props (`tags::`, `alias::`) should union as sets. Property key case and `-`/`_` normalisation.
Pages, journals, aliases, namespaces
- Page-name match is case-insensitive (already done); also match via aliases both directions (target page alias equals source page name) to avoid creating a duplicate page for an aliased name.
- Journals: match by date, not name string; journal title format can differ between graphs (`Oct 7th, 2026` vs `2026-10-07`), and filename format (`2026_10_07.md`) is configured per graph. Journal blocks usually unrelated across graphs, so union is correct but ordering by time matters.
- Namespaces: `a/b/c` pages need parent pages `a`, `a/b` to exist or hierarchy views break; file names use `___` or `%2F` per graph config, so filename of the created file must follow the target graph's config, not the source's.
- Case-only name differences and special characters in filenames across OSes.
Links and assets
- Linked-page closure: bound depth (default 0 or 1, never transitive), cap and show counts in the dry run.
- Assets: relative `../assets/x.png` references; filename collisions with different bytes (hash, rename with suffix and rewrite refs); large files on Android; dedupe by content hash; missing source asset (broken link) should warn, not fail the page.
- Page links to pages that do not exist in the target: create stub pages or leave dangling (Logseq tolerates dangling).
Consistency
- DB vs disk: writing through DB only leaves no markdown file; writing a file only needs the watcher to index it. File watcher can echo merge writes back as external changes and raise `DiskConflict`; suppress with the same `MoveInProgressFlag`-style guard or by writing via `GraphWriter`.
- Partial failure mid-merge: per-page transactionality and a resumable/idempotent re-run; report failed pages.
- Source graph changing between snapshot and merge (stale snapshot); snapshot lost on app kill or process death on Android (in-memory only).
- Open-graph constraint: reading source while target is open is impossible today; options listed in requirements. Reading the source from disk markdown (parse with the existing parser, no DB) avoids it for read, and `CaptureWriter`-style writes need only a `RepositorySet` for the target.
Share target
- Target graph not loaded or locked, on removable/SAF storage, or deleted: show visible failure, keep shared content in a retry queue (never silent loss).
- Share of large text/HTML/images: image needs asset copy into target graph assets dir; multiple items; share arriving while graph is mid-load.
- Idempotent replays via `captureId` already exist.
- Per-share override must not silently change the default; "remember last used" vs "default" ambiguity needs a clear label.

## 4. Unstated user needs
- Undo or a pre-merge backup of the target ("I merged the wrong graph"); at minimum a visible "merged N blocks" report with a list of touched pages, and an optional tag/property marking merged blocks so they can be found and removed.
- Recovery use case (split graph, accidental new graph after losing folder): users want "bring everything over", so a select-all path must be fast and not require per-page confirmation.
- Trust: seeing exactly what will change (dry run with expandable per-page diff) before commit; "unchanged" count builds confidence in idempotence.
- Selection ergonomics: select from the page view ("copy this page to graph X") as well as bulk picker; remember the last destination.
- Sharing to the right graph by context: work links to the work graph; default per source app is a plausible later ask.
- Cross-graph block refs and embeds should survive; users will notice broken `((uuid))` refs.
- Works offline, no data leaves the device; fast on a phone; survives backgrounding (WorkManager-style job on Android for large merges).
- Journals: capture lands in the journal of the shared-at date in the destination graph's format.

## 5. Implications for design and planning
1. Prefer a pure, commonMain merge function over parsed block trees (source tree, target tree) returning an operations list; this is the property-test target (idempotent, no loss, no target deletion, commutative on content set).
2. Separate "read source" (parse markdown from disk, bounded per page) from "write target" (target RepositorySet via actor); this may avoid refactoring `GraphManager` for copy. Share target needs a target `RepositorySet` or a file-level inbox; evaluate both.
3. Make UUID remap deterministic and rewrite block refs; treat UNIQUE `blocks.uuid` as the key hazard.
4. Cheap platform wins: Android Direct Share shortcuts per graph; iOS and Web via an inbox directory only.

## 6. Gaps
- Android manifest/`CaptureActivity` filters and `CaptureViewModel` not opened (androidApp not read).
- How `QrImportService`/`GraphLoader` assigns UUIDs on import (preserve vs regenerate) not verified; it determines the collision rate.
- Alias storage and journal filename/format config not verified.
- Industry claims in section 2 are unverified; no web sources were fetched.
