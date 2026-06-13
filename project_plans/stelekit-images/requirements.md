# Requirements: Image & File Attachment Support

## Problem Statement

SteleKit users cannot currently attach images or files to pages and have them rendered inline. The `ImageNode` AST node exists and the `ImageBlock` composable is written, but the wiring is incomplete:
- `SteleKitAssetFetcher` is stubbed (TODO) — relative `../assets/` paths don't resolve
- `ImageBlock` is not dispatched from `BlockItem` — image-only blocks render as text
- No attachment flow exists (no picker, no camera, no copy-to-assets)
- No slash command `/image` or toolbar button for attachment

## Goals

1. **Display** — Existing `![alt](path)` markdown links render as inline images in view mode, for both http/https URLs and relative `../assets/` paths.
2. **Attach** — Users can add images/files to a page from:
   - Camera capture (Android, iOS)
   - Gallery / file picker (all platforms)
   - Drag-and-drop (Desktop JVM, Web)
   - Paste from clipboard (all platforms where OS supports it)
3. **Storage** — Attached files are copied into an `assets/` subfolder inside the graph root (Logseq-compatible path convention: `../assets/<filename>` relative to the page file).
4. **UX entry points** — Attachment is triggered via:
   - A toolbar button (paperclip / image icon) in the block editing toolbar
   - A `/image` slash command

## Additional Feature: Web Archive Link Generation

Users want to archive URLs to the Wayback Machine (web.archive.org) and store archive links on pages.

**User story**: Right-clicking a URL in a block (or using a `/archive` slash command) submits the URL to `https://web.archive.org/save/<url>` and inserts the returned `https://web.archive.org/web/<timestamp>/<url>` permalink into the block as a second link. Later, the archived copy is accessible even if the original goes offline.

**Note**: "siteshival" in the original request is interpreted as the Wayback Machine / Internet Archive (web.archive.org). Clarify if a different archiving service (e.g., archive.ph) was intended.

### Archive Feature Goals

5. **Archive URL** — From a block containing a URL, submit it to the Wayback Machine save API and append the archive permalink to the block content.
6. **Display archived links** — Render archived Wayback Machine URLs as normal links (existing behavior — no special rendering needed).

### Archive Feature Entry Points

- Right-click context menu on a URL → "Archive this link"
- `/archive` slash command (types the URL, command submits it)

### Archive Success Criteria

8. Right-clicking a bare URL or markdown link in a block shows an "Archive link" option.
9. Selecting it calls `https://web.archive.org/save/<url>` and on success appends `[archived](https://web.archive.org/web/<timestamp>/<url>)` after the original link in the block content.
10. On failure (network error, rate limit), a snackbar/toast informs the user.
11. The operation is non-blocking — the block remains usable while the archive request is in flight.

### Archive Constraints

- Uses the public Wayback Machine Availability API (no API key required for saving).
- The HTTP request uses the existing Ktor client already on the classpath.
- Archive is triggered manually — no automatic archiving on link paste.

## Non-Goals

- Audio/video playback (may be added later; just link for now)
- PDF inline rendering (link only)
- Cloud/remote storage for attachments (always local graph folder)
- Base64 embedding
- Per-file storage choice (always copy)
- Automatic archiving of every link on save

## Platform Matrix

| Feature | Desktop JVM | Android | iOS | Web |
|---------|-------------|---------|-----|-----|
| Render `![](path)` URLs | ✅ (Coil network) | ✅ | ✅ | ✅ |
| Render `![](../assets/*)` relative paths | Implement JVM fetcher | Implement Android fetcher | Implement iOS fetcher | Implement WASM fetcher |
| Gallery / file picker | AWT JFileChooser or compose-multiplatform | ActivityResultContracts | UIImagePickerController | `<input type="file">` |
| Camera capture | N/A (desktop) | CameraX / MediaStore | UIImagePickerController camera | N/A |
| Drag-and-drop | Compose DragAndDrop or AWT DropTarget | N/A | N/A | HTML drag events |
| Clipboard paste | AWT Clipboard | ClipboardManager | UIPasteboard | Clipboard API |

## Existing Assets to Build On

- `ImageNode` — AST node in `parsing/ast/InlineNodes.kt`
- `ImageBlock` composable — `ui/components/ImageBlock.kt` (complete, not wired)
- `SteleKitAssetFetcher` stub — `ui/components/SteleKitAssetFetcher.kt` (TODO)
- `LocalGraphRootPath` — composition local providing graph root path
- `BlockItem.kt` line ~388 — dispatch on `BlockTypes.PARAGRAPH` / `else` branch; image blocks need to be detected and dispatched to `ImageBlock`
- Coil 3 already on classpath (`coil-compose:3.2.0`, `coil-network-ktor3:3.2.0`)

## Success Criteria

1. Opening a page with `![](https://example.com/image.png)` renders the image inline, not as link text.
2. Opening a page with `![](../assets/photo.jpg)` where `photo.jpg` exists renders the image inline on Desktop and Android.
3. Tapping the image enters edit mode for that block.
4. On Android: the `/image` slash command and toolbar button open a bottom sheet with camera / gallery options.
5. On Desktop: the `/image` slash command and toolbar button open a file picker; dropping a file into the editor also works.
6. After attaching, the file is copied to `<graph_root>/assets/<filename>` and the block content is updated to `![<filename>](../assets/<filename>)`.
7. Duplicate filenames are handled by appending a suffix (e.g., `photo-1.jpg`).

## Key Constraints

- Follow the project's `Either<DomainError, T>` error pattern for all service methods.
- Platform-specific code lives in `androidMain`, `jvmMain`, `iosMain` — not `commonMain`.
- Common interface/expect-actual for the file picker is defined in `commonMain`.
- File copy uses `PlatformDispatcher.IO` (not DB dispatcher).
- The `assets/` folder is created if it doesn't exist.
- Logseq compatibility: asset path in markdown is always relative: `../assets/<filename>`.
