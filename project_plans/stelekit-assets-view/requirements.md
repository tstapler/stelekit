# SteleKit Assets Viewer — Requirements

## Context

The `AssetBrowserScreen` scaffold, `AssetBrowserViewModel`, `AssetRepository`, and sidebar
navigation already exist. This project completes the feature to a shippable, polished state.

## Existing infrastructure (do not break)

| Component | Location |
|---|---|
| `AssetBrowserScreen` | `ui/assets/AssetBrowserScreen.kt` |
| `AssetBrowserViewModel` | `ui/assets/AssetBrowserViewModel.kt` |
| `AssetBrowserUiState` / `AssetFilter` / `ViewMode` | `ui/assets/AssetBrowserUiState.kt` |
| `AssetItemCard` | `ui/assets/AssetItemCard.kt` |
| `AssetActionMenu` | `ui/assets/AssetActionMenu.kt` |
| `AssetRepository` | `repository/AssetRepository.kt` |
| `SqlDelightAssetRepository` | `repository/SqlDelightAssetRepository.kt` |
| `Screen.AssetBrowser` + ScreenRouter wiring | `ui/AppState.kt`, `ui/ScreenRouter.kt` |
| Sidebar "Assets" nav item | `ui/components/Sidebar.kt:160` |
| Coil image loading (`rememberSteleKitImageLoader`) | `ui/components/SteleKitAssetFetcher.kt` |

---

## REQ-1 — Image thumbnails in grid and list view

`AssetItemCard` currently renders a type icon for all asset types. For IMAGE assets,
replace the icon with an actual thumbnail using `AsyncImage` and the existing
`rememberSteleKitImageLoader()` (Coil — already installed and wired for file:// paths).

- Grid: `AsyncImage` fills the card, `ContentScale.Crop`; icon fallback when path is missing
- List: small leading thumbnail (48×48 dp), icon fallback
- Non-image types (PDF, Audio, Video, Document, File) keep the existing icon treatment

## REQ-2 — Asset detail screen (in-app viewer)

Tapping any asset in the browser opens a detail screen:

- **Images**: full-screen `AsyncImage` with pinch-to-zoom, title bar showing filename
- **PDF / Audio / Video / Document / File**: metadata card (filename, size, media type, import
  date, tags, OCR text if available, page references) + "Open in…" button
- Navigation: back arrow returns to browser
- "Go to page" chips for each `pageUuid` that references the asset — tapping navigates to
  that page via `onNavigateToPage`

The detail screen is a new `Screen.AssetDetail(assetUuid: AssetUuid)` in AppState and a
new `AssetDetailScreen` composable with its own `AssetDetailViewModel`.

## REQ-3 — Wired action menu

`AssetActionMenu` and `AssetAction` already exist. Wire them:

- **Open in system app**: platform service call — Desktop `java.awt.Desktop.open(File)`,
  Android `Intent.ACTION_VIEW` with FileProvider URI. Add `PlatformFileOpener` expect/actual.
- **Copy markdown link**: write `![](<relativePath>)` for images, `[<filename>](<relativePath>)`
  for other types to the system clipboard. Use existing `PlatformClipboardProvider`.
- **Delete**: `assetRepository.deleteAsset(uuid)` + delete the file from disk. Show confirm
  dialog before executing. Remove from list immediately (optimistic update).
- Long-press on a grid or list item shows the action menu anchored to that item.

## REQ-4 — Sort order

Add a sort dropdown to `AssetBrowserScreen` (same pattern as `GalleryScreen`).

Sort options:
- **Date added** (default, newest first)
- **Name** (A→Z)
- **Size** (largest first)

Sort state lives in `AssetBrowserUiState`. SQLDelight queries must accept an `ORDER BY`
clause; add `sortedAssets(mediaType, query, sortOrder, limit, offset)` variant to
`SteleDatabase.sq` and expose via `AssetRepository`.

## REQ-5 — Pagination / infinite scroll

The current hard cap of 50 items must be replaced with infinite scroll:

- Load first page (50 items) on screen entry
- Append next page when user scrolls to within 5 items of the end (`LazyGrid` / `LazyColumn`
  `itemIndex >= items.size - 5`)
- `AssetBrowserUiState` gains `isLoadingMore: Boolean` and `hasMore: Boolean`
- `totalCount` from `assetRepository.countAssets()` drives `hasMore` calculation

## REQ-6 — Tag-based grouping

Assets can be grouped by tag (user-defined strings already stored in `AssetEntry.tags`):

- `AssetFilter` gains a `TAG(name: String)` entry (data class variant) for tag filters
- The existing "All / Images / PDFs / …" chip row gains additional chips for each distinct
  tag found across all assets in the graph (up to 10 shown; "+ N more" expands)
- Selecting a tag chip filters to assets that have that tag in their `tags` list
- Tag chips are populated from a new `AssetRepository.getDistinctTags()` query

## REQ-7 — Orphaned assets filter

`AssetFilter.ORPHANED` is already in the enum but falls through to `getAssets()` in the
ViewModel. Wire it: `AssetRepository.getOrphanedAssets(limit, offset)` returns assets where
`pageUuids` is empty (no markdown references). Add the SQLDelight query.

## REQ-8 — Empty state

When `uiState.assets` is empty and `isLoading` is false, show an empty state:
- Icon + headline: "No assets yet"
- Sub-text for filter context: "No images found" / "No PDFs found" / etc.
- For the ALL filter: "Attach a file to any page to see it here"

---

## Non-goals (this PR)

- Re-running ML pipeline from the browser (separate Epic)
- Drag-to-reorder or move to custom folder (REQ-1.2 from asset-management plan — future)
- Platform-specific in-app PDF/audio/video players (REQ-2, detail screen shows metadata only)
- Web/iOS platform file opener (Desktop + Android only for this PR)

---

## Constraints

- KMP: `PlatformFileOpener` must be `expect`/`actual` — Desktop uses `java.awt.Desktop`,
  Android uses `Intent.ACTION_VIEW` with `FileProvider`
- Arrow `Either` at all repository boundaries
- SQLDelight migration required for any new `ORDER BY` query changes (queries only, no DDL,
  so no migration entry needed — but verify no schema changes)
- No `rememberCoroutineScope()` passed to `AssetDetailViewModel`
- Bounded reads only — all repository calls paginated

---

## Success criteria

1. Grid shows image thumbnails (not just icons) for image assets
2. Tapping an asset opens the detail screen; back returns to browser
3. Long-press opens action menu; Delete removes from list + disk; Copy Link puts markdown
   in clipboard; Open in system app launches the OS handler
4. Sort dropdown changes asset order without full reload
5. Scrolling to end of list loads the next page of 50 items
6. Selecting a tag chip filters to tagged assets
7. ORPHANED filter shows only unreferenced assets
8. Empty state shown when no assets match the current filter
