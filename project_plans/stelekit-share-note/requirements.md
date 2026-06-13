# Requirements: Robust Note Share / Export

## Problem Statement

SteleKit has a clipboard-based export menu (Markdown, Plain Text, HTML, JSON) but no integration with device OS share APIs or cloud platforms. Users need to move notes out of the app into external systems — Google Docs for collaboration, other apps via the native share sheet, or files saved to disk — without copy-paste friction. This feature makes SteleKit notes first-class citizens in external workflows.

## Current State

- `ExportService` orchestrates 4 exporters (markdown, plain-text, html, json), already supports `exportToString()` for non-clipboard destinations
- `DriveApiClient` is a full REST client (upload, list, createFolder, download) backed by `GoogleAuthClient` + `GoogleTokenRefresher`
- `GoogleAuthManager` / `AndroidGoogleAuthManager` exist but OAuth token exchange is not yet wired (browser is opened, token storage pending)
- `DriveExportService` exists but is specialized for annotated image + sidecar JSON, not notes
- TopBar has a simple dropdown copying to clipboard; no native share integration

## Stakeholders

- **Primary user**: Tyler Stapler — brainstorms and takes notes in SteleKit, needs to export to Google Docs and share via platform APIs
- **Secondary**: Any SteleKit user on Android, iOS, or Desktop

## Functional Requirements

### FR-1: Native OS Share Sheet

- A "Share" action triggers the platform-native share mechanism:
  - **Android**: `Intent.ACTION_SEND` with `text/plain` and `text/html` extras
  - **iOS**: `UIActivityViewController` with NSItemProvider for text and HTML
  - **Desktop JVM**: System file-save dialog (no OS-level share sheet; save to file is the equivalent)
- Content format is chosen by the user before the share sheet is invoked (see FR-4)
- `ShareProvider` platform interface lives in `commonMain`; platform implementations in `androidMain`, `iosMain`, `jvmMain`

### FR-2: Google Docs Export

- Export a note as a native Google Doc (editable, not a Drive file upload)
- Implementation: upload HTML content to Drive with `mimeType = "application/vnd.google-apps.document"` — Drive auto-converts to Google Docs format
- Requires existing `DriveApiClient.uploadFile()` with the docs mimeType
- Requires `https://www.googleapis.com/auth/drive.file` scope (already requested in `AndroidGoogleAuthManager.SCOPES`)
- Wire the existing OAuth token exchange (complete `AndroidGoogleAuthManager.authenticate()`)
- On success, open the created Google Doc URL in the system browser
- Show the connected Google account in the Share UI (or prompt to sign in if not authenticated)

### FR-3: Export Scope Selection

Users can choose what to export:

| Scope | Description |
|---|---|
| **Current page** | The page currently open in the editor |
| **Page + linked pages** | Current page plus all pages it `[[links]]` to (one level of link traversal) |
| **Selected blocks** | User selects specific blocks from the current page editor |
| **Journal date range** | All daily journal pages in a user-specified date range |

- "Current page" is the default and requires no additional UI
- "Page + linked pages" resolves `[[PageName]]` tokens from block content via `PageRepository`
- "Selected blocks" reuses `ExportService.subtreeBlocks()` with block UUIDs from a selection model
- "Journal date range" uses a date range picker and `PageRepository` to fetch journal pages

### FR-4: Format + Destination Picker (Share Bottom Sheet / Dialog)

A unified Share UI replaces the current simple dropdown:

- **Entry points** (all invoke the same composable):
  - TopBar overflow menu (existing, replace current export items with a single "Share…" item)
  - TopBar desktop File menu (replace 4 export items with "Share…")
  - Page context menu / kebab in the sidebar page list
  - Journal page header action button
  
- **Share UI shows**:
  1. **Scope** — radio selector: Current Page | Page + Links | Selected Blocks | Journal Range
  2. **Format** — segmented picker: Markdown | Plain Text | HTML | JSON
  3. **Destination** — tile grid:
     - Clipboard (always available)
     - Share via app (native share sheet; hidden on Desktop, shown on Android/iOS)
     - Save to file (always available; opens file-save dialog)
     - Google Docs (requires Google sign-in; shows "Connect Google" if not authenticated)
  
- On mobile (Android/iOS): implemented as a `ModalBottomSheet`
- On Desktop: implemented as a `Dialog`
- Format default: Markdown
- Scope default: Current page

### FR-5: Google Account Connection UX

- If user taps "Google Docs" when not authenticated:
  - Show inline "Connect Google Account" prompt within the Share UI
  - Tapping it initiates `GoogleAuthManager.authenticate()`
  - After successful auth, return to Share UI with Google Docs tile active
- Show connected account email below the Google Docs tile when authenticated
- "Disconnect" available via existing `GoogleAccountSettings` in app settings

### FR-6: Export Error Handling

- Network errors during Google Docs upload: show `Snackbar` with retry action
- Auth expired: auto-refresh via `GoogleTokenRefresher`; if refresh fails, show reconnect prompt
- All export operations return `Either<DomainError, Unit>` — surface errors via ViewModel event state

### FR-7: UX Entry Point Consistency

- The same `ShareBottomSheet` / `ShareDialog` composable is invoked from all entry points
- No duplicate export logic; always routes through `ExportService.exportToString()`
- Format and scope choices are preserved across invocations within the same session (via `AppState` or ViewModel state)

## Non-Functional Requirements

- **No new library dependencies** for share sheet (Android `Intent` and iOS `UIActivityViewController` are SDK APIs)
- **Google Docs conversion** reuses existing `DriveApiClient.uploadFile()` — no new HTTP client
- **Platform interface pattern**: `ShareProvider` follows the same pattern as `ClipboardProvider`
- **Coroutine/Arrow conventions**: all async operations return `Either<DomainError, T>` and use `PlatformDispatcher` correctly per CLAUDE.md rules
- Export time for a 1000-block page must be < 2s on mid-range Android hardware

## Out of Scope

- Export to Notion, Obsidian, or other platforms (future)
- Bidirectional sync (import from Google Docs back into SteleKit)
- Google Drive folder picker for organizing exported docs
- Resumable upload for pages > 5 MB (already noted as future work in `DriveApiClient`)
- Block-level sharing (block permalink / URL)

## Success Criteria

1. User can tap "Share" on any page → native share sheet opens with the note content on Android
2. User can export a note to a new Google Doc; the doc opens in the browser after creation
3. User can export a journal date range as a single Markdown file to their device
4. The Share UI appears consistently from the toolbar, page context menu, and journal header
5. All existing export tests pass; new share paths have unit and integration test coverage
6. `ciCheck` passes with no regressions
