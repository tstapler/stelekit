# Features Research: Google Docs Export + Share Feature Landscape

## Google Docs API: HTML → Google Doc Conversion

The Drive API v3 supports automatic format conversion during upload. Uploading HTML content with `mimeType = "application/vnd.google-apps.document"` causes Drive to convert the HTML into a native Google Docs document. This is exactly what `DriveApiClient.uploadFile()` already supports — the caller simply passes the docs mimeType:

```kotlin
driveApiClient.uploadFile(
    fileName = "My Note.gdoc",
    mimeType = "application/vnd.google-apps.document",   // triggers auto-conversion
    bytes = htmlContent.encodeToByteArray(),             // HTML source
    parentFolderId = null,
)
```

Drive returns a file ID. The Google Doc URL is deterministic: `https://docs.google.com/document/d/{fileId}/edit`.

### Required OAuth Scope

`https://www.googleapis.com/auth/drive.file` — already declared in both `AndroidGoogleAuthManager.SCOPES` and `JvmGoogleAuthManager.SCOPES`. No new scope needed.

### HTML Fidelity

Drive's HTML→Docs conversion handles:
- Headings (`<h1>`–`<h6>`) → Docs heading styles
- Bold/italic/underline inline styles
- Ordered and unordered lists
- Hyperlinks (`<a href>`)
- Tables
- Paragraph spacing

It does NOT faithfully handle:
- Custom CSS classes (ignored)
- Syntax-highlighted code blocks (converted to plain text spans)
- Nested lists deeper than ~3 levels (may flatten)
- Logseq-specific block reference tokens (`((uuid))`) — these will appear as raw text; they are resolved by `ExportService.resolveBlockRefs()` before export, so they'll appear as the referenced block content rather than UUIDs

The existing `HtmlExporter` should produce clean enough HTML for Drive conversion. No changes to `HtmlExporter` are required.

### Opening the Created Doc

After `uploadFile()` returns a file ID, open the browser:

```kotlin
// commonMain
expect fun openInBrowser(url: String)

// androidMain
actual fun openInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    SteleKitContext.context.startActivity(intent)
}

// jvmMain
actual fun openInBrowser(url: String) {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
}
```

Both `JvmGoogleAuthManager` already uses `Desktop.getDesktop().browse()` and `AndroidGoogleAuthManager` already uses `Intent.ACTION_VIEW` — this is a proven pattern in the codebase.

## What Is Already Wired vs. Stubbed

| Component | Status |
|---|---|
| `DriveApiClient.uploadFile()` | Fully implemented — multipart upload, error handling, Either return |
| `DriveApiClient.listFiles()` | Fully implemented |
| `DriveApiClient.createFolder()` | Fully implemented |
| `DriveApiClient.downloadFile()` | Fully implemented |
| `GoogleAuthClient.getValidToken()` | Fully implemented — mutex-guarded refresh |
| `GoogleTokenStore` interface | Fully defined |
| `GoogleTokenRefresher` | Implemented (called by `GoogleAuthClient`) |
| `JvmGoogleAuthManager.authenticate()` | Implemented — local HTTP server, browser open, code extraction |
| `JvmGoogleAuthManager.exchangeCodeForTokens()` | **Stub** — calls `refreshGoogleToken` with auth code instead of proper `grant_type=authorization_code` POST |
| `AndroidGoogleAuthManager.authenticate()` | **Stub** — opens browser, returns `202 Left` immediately; deep-link callback not connected |
| `DriveExportService` | Implemented but specialized for image+JSON, not notes |

## Feature Edge Cases and Unstated Needs

### Scope: Page + Linked Pages
- Must resolve `[[PageName]]` tokens from `Block.content` fields using `InlineParser`
- `PageRepository.getPageByName()` is the query path; must load blocks for each linked page via `BlockRepository`
- Circular links (Page A links to Page B which links back to Page A) need cycle detection — a visited `Set<String>` of page names is sufficient for one-level traversal
- Empty linked pages (page name exists but no blocks loaded) should be silently skipped or represented as an empty section

### Scope: Journal Date Range
- `PageRepository.getJournalPageByDate()` exists and is the correct query
- Date range picker UI: `kotlinx-datetime.LocalDate` pairs; the existing `JournalsView` uses `LocalDate` already
- Large date ranges (e.g., 365 days) may involve many DB queries; should batch or use `getAllPages()` filtered by date range
- Empty days in the range should be omitted from the export output

### Scope: Selected Blocks
- `ExportService.subtreeBlocks()` already exists and handles disjoint block selection
- `BlockStateManager.selectedBlockUuids` already tracks selection
- `exportSelectedBlocks()` in `StelekitViewModel` already calls `subtreeBlocks()` — this path can be reused directly

### Destination: Save to File
- Desktop: `FileDialog` (AWT)
- Android: `Intent.ACTION_CREATE_DOCUMENT` with the Storage Access Framework (SAF) — distinct from the share intent; returns a URI that must be written to via `ContentResolver.openOutputStream()`
- iOS: `UIDocumentPickerViewController` in export mode

### Google Account UX
- The Share UI must check `GoogleAuthManager.isAuthenticated()` at display time (suspend → `collectAsState` or a `LaunchedEffect`)
- If not authenticated, tapping Google Docs shows an inline "Connect Google" CTA rather than launching export immediately
- After `authenticate()` completes, the Share UI should auto-retry the export or just refresh the auth status to enable the tile

### Error Scenarios
- Drive upload fails mid-export: `Either.Left(DomainError.NetworkError.HttpError)` — surface via ViewModel event → Snackbar
- File too large (>5 MB): `DriveApiClient` docs note resumable upload is future work; for now show a size limit error
- Auth token expired during upload: `GoogleAuthClient.getValidToken()` handles refresh transparently before returning the token; if refresh fails it returns `Left(401)` which maps to a reconnect prompt

## Comparison with Existing Export Flow

The current flow: `TopBar.onExportPage(formatId)` → `StelekitViewModel.exportPage(formatId)` → `ExportService.exportToClipboard()` → `ClipboardProvider.writeText/writeHtml()`.

The new flow replaces `exportToClipboard()` with `exportToString()` (already implemented) plus a destination dispatcher:
- Clipboard → `ClipboardProvider.writeText/writeHtml()` (unchanged)
- Share via app → `ShareProvider.share(content, mimeType)`
- Save to file → `ShareProvider.saveToFile(content, suggestedName, extension)`
- Google Docs → `DriveApiClient.uploadFile(name, "application/vnd.google-apps.document", htmlBytes, null)` + open browser

## Summary

- Google Docs export requires only completing two stubs: Android deep-link token callback and the JVM `exchangeCodeForTokens` proper `grant_type=authorization_code` POST.
- `DriveApiClient.uploadFile()` with `mimeType = "application/vnd.google-apps.document"` is the entire Drive integration — no new API methods needed.
- Journal range export needs a date iterator + `getJournalPageByDate()` per day; cycle detection for linked-page scope.
- The doc URL pattern `https://docs.google.com/document/d/{fileId}/edit` is constructed client-side after the upload returns the file ID.
