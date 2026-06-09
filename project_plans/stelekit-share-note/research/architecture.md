# Architecture Research: Unified Share UI

## ShareProvider Interface Design

Following the exact `ClipboardProvider` / `PlatformClipboardProvider` pattern:

### commonMain — Interface

```kotlin
// commonMain/export/ShareProvider.kt
interface ShareProvider {
    /** Share text content via the OS native share mechanism. */
    suspend fun shareText(content: String, mimeType: String = "text/plain")

    /** Share HTML content, with a plain-text fallback for apps that don't understand HTML. */
    suspend fun shareHtml(html: String, plainFallback: String)

    /** Save content to a user-chosen file. Returns false if the user cancelled. */
    suspend fun saveToFile(content: String, suggestedName: String, extension: String): Boolean
}
```

### commonMain — Composable Factory (expect/actual)

```kotlin
// commonMain/ui/PlatformShareProvider.kt
@Composable
expect fun rememberShareProvider(): ShareProvider
```

This mirrors `rememberClipboardProvider(clipboard: ClipboardManager)`. The share provider needs the Activity context on Android, so `LocalContext.current` is used inside the `actual` implementation.

### Platform Actuals

| File | Implementation |
|---|---|
| `androidMain/ui/PlatformShareProvider.android.kt` | `Intent.ACTION_SEND` + `Intent.ACTION_CREATE_DOCUMENT` via SAF |
| `jvmMain/ui/PlatformShareProvider.jvm.kt` | AWT `FileDialog` for save; no-op for shareText (Desktop has no share sheet) |
| `iosMain/ui/PlatformShareProvider.ios.kt` | `UIActivityViewController` for shareText; `UIDocumentPickerViewController` for saveToFile |

Desktop does not have an OS share sheet; the "Share via app" destination tile is hidden when `LocalWindowSizeClass.current.isMobile == false` (same `isMobile` used throughout TopBar, CommandPalette, SearchDialog).

## Share Bottom Sheet / Dialog Architecture

### Existing Precedent: CommandPalette and SearchDialog

Both `CommandPalette.kt` and `SearchDialog.kt` use the same adaptive pattern:

```kotlin
val isMobile = LocalWindowSizeClass.current.isMobile
// On mobile: full-screen or bottom-anchored overlay
// On desktop: floating Dialog
```

`ShareBottomSheet` (the name used in the requirements) should follow the same pattern. It is a single composable that either renders as a `ModalBottomSheet` (mobile) or an `AlertDialog`/`BasicAlertDialog` (desktop) based on `isMobile`.

Material3's `ModalBottomSheet` is already available via `androidx.compose.material3` which is on the classpath. No new dependency needed.

### Composable Structure

```kotlin
// commonMain/ui/components/ShareDialog.kt
@Composable
fun ShareDialog(
    page: Page?,
    blocks: List<Block>,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    shareProvider: ShareProvider,
    googleAuthManager: GoogleAuthManager,
    driveApiClient: DriveApiClient?,
    exportService: ExportService,
    journalDateRange: ClosedRange<LocalDate>? = null,
) {
    if (!isVisible) return
    val isMobile = LocalWindowSizeClass.current.isMobile
    if (isMobile) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            ShareContent(/* ... */)
        }
    } else {
        AlertDialog(onDismissRequest = onDismiss, /* ... */) {
            ShareContent(/* ... */)
        }
    }
}
```

### State Management — AppState vs. ViewModel

`AppState` already has `isExporting: Boolean`. The Share UI state (visible/hidden, selected format, selected scope, selected destination) should be added to `AppState`:

```kotlin
data class AppState(
    // ... existing fields ...
    val shareDialogVisible: Boolean = false,
    val shareFormat: String = "markdown",         // persisted across invocations per FR-7
    val shareScope: ShareScope = ShareScope.CurrentPage,
)

enum class ShareScope { CurrentPage, PageAndLinks, SelectedBlocks, JournalRange }
```

This avoids prop-drilling: any entry point calls `viewModel.showShareDialog()` which sets `shareDialogVisible = true`. The `StelekitApp` composable (in `App.kt`) renders the `ShareDialog` when `appState.shareDialogVisible == true`, just as it renders `SettingsDialog` when `appState.settingsVisible == true`.

### Entry Point Integration — No Prop Drilling

Looking at how `SettingsDialog` is rendered: `App.kt` reads `appState.settingsVisible` and conditionally renders `SettingsDialog(onDismiss = { viewModel.hideSettings() })`. The same pattern for Share:

```kotlin
// In App.kt (StelekitApp composable)
if (appState.shareDialogVisible) {
    ShareDialog(
        page = appState.currentPage,
        blocks = blockStateManager.blocksForCurrentPage(),
        isVisible = true,
        onDismiss = { viewModel.hideShareDialog() },
        shareProvider = rememberShareProvider(),
        googleAuthManager = googleAuthManager,
        driveApiClient = driveApiClient,
        exportService = exportService,
    )
}
```

Entry points only call `viewModel.showShareDialog()`:
- **TopBar overflow (mobile)**: replace 4 export items with single "Share…" item → `viewModel.showShareDialog()`
- **TopBar File menu (desktop)**: replace 4 export items with "Share…" → `viewModel.showShareDialog()`
- **Sidebar page context menu**: add "Share…" action → `viewModel.showShareDialog()`
- **Journal page header**: add share icon → `viewModel.showShareDialog()`

`TopBar` already receives `onExportPage: ((formatId: String) -> Unit)?` — this parameter becomes `onShareClick: (() -> Unit)?`. The 4 export items in the File menu and overflow menu collapse to 1.

### Google Auth State in Share UI

`GoogleAuthManager` is platform-specific. It must be injected into the Share UI as an interface reference. `StelekitApp` already constructs `googleAuthManager` (it's passed down from the platform entry point). The ViewModel can hold a reference:

```kotlin
// StelekitViewModel
var googleAuthManager: GoogleAuthManager? = null  // injected after construction (like clipboard)
```

Or more cleanly, `ShareDialog` receives it directly as a parameter (avoiding growing the ViewModel further). The auth state check (`isAuthenticated()`) runs in a `LaunchedEffect` inside `ShareDialog`.

### Share Operation — Coroutine Scope

The share operation is triggered by a button click inside `ShareDialog`. The scope for the launch must **not** be `rememberCoroutineScope()` passed into a class — but since the work is done inline in the composable's `LaunchedEffect` or in the ViewModel, this is fine:

```kotlin
// Inside ShareDialog composable — correct
val scope = rememberCoroutineScope()   // used only for button click handlers, not stored in a class
Button(onClick = {
    scope.launch {
        val content = exportService.exportToString(page, blocks, format)
        // dispatch to destination
    }
})
```

For Google Docs upload (network operation), prefer routing through `StelekitViewModel.shareToGoogleDocs(page, blocks, format)` which uses the ViewModel's own `CoroutineScope(SupervisorJob())` — same pattern as `exportPage()`.

## Data Flow Summary

```
Entry Point (TopBar / Sidebar / Journal Header)
    → viewModel.showShareDialog()
    → AppState.shareDialogVisible = true
    → App.kt renders ShareDialog

ShareDialog
    ├── Scope selector → AppState.shareScope (via viewModel)
    ├── Format selector → AppState.shareFormat (via viewModel)
    └── Destination tile click:
        ├── Clipboard → exportService.exportToClipboard(...)
        ├── Share via app → exportService.exportToString(...) → shareProvider.shareText/shareHtml(...)
        ├── Save to file → exportService.exportToString(...) → shareProvider.saveToFile(...)
        └── Google Docs → viewModel.shareToGoogleDocs(page, blocks) → driveApiClient.uploadFile(...) → openBrowser(docUrl)
```

## Key Architectural Decisions

1. **ShareProvider is a non-composable `interface`** (vs. `ClipboardProvider`). The `expect fun rememberShareProvider()` factory is `@Composable` only to access `LocalContext` on Android — the returned `ShareProvider` itself is a plain object.

2. **`AppState` holds share dialog visibility and persisted format/scope** — follows the existing pattern for `settingsVisible`, `searchDialogVisible`, `commandPaletteVisible`.

3. **`ShareDialog` is rendered at the `App.kt` level** (not inside `ScreenRouter`) — this allows it to be triggered from any screen without threading it through ScreenRouter's parameter list.

4. **Google Docs network work goes through `StelekitViewModel`** — keeps the composable thin, allows progress/error state via `AppState.isExporting`, and avoids `rememberCoroutineScope` leakage.

## Summary

- `ShareProvider` follows the 3-file expect/actual structure of `PlatformClipboardProvider`; the `commonMain` interface has `shareText`, `shareHtml`, `saveToFile`.
- `ShareDialog` is a single composable rendered from `App.kt` (not ScreenRouter), using `isMobile` to switch between `ModalBottomSheet` and `AlertDialog` — same pattern as `CommandPalette` and `SearchDialog`.
- Entry points call `viewModel.showShareDialog()` only — no prop-drilling of format/destination choices through ScreenRouter.
- Network-bound share operations (Google Docs) go through `StelekitViewModel.shareToGoogleDocs()` using the ViewModel's own scope — not `rememberCoroutineScope`.
