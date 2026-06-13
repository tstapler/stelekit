# Pitfalls Research: Share / Export Feature

## Pitfall 1: Android OAuth Deep-Link Callback — The Biggest Risk

### Current State
`AndroidGoogleAuthManager.authenticate()` opens the browser with the consent URL and then **immediately returns `Left(202)`** with the message "OAuth flow initiated in browser." There is no mechanism to receive the callback. The `AndroidManifest.xml` (in `kmp/src/androidMain/`) has **no `<intent-filter>` for `com.stelekit.app:/oauth2redirect`**.

### What Must Happen
1. The app module's `AndroidManifest.xml` (not the kmp module's) must declare an `<intent-filter>` on `MainActivity` for the custom scheme.
2. `authenticate()` must suspend until the browser callback delivers the auth code. This requires a process-wide singleton: either a `CompletableDeferred<String?>` stored in a companion object or a `Channel<String>` — both are accessible from the Activity's `onNewIntent()` callback.
3. `MainActivity.onNewIntent()` (in the app module) must extract the `code` from the `Uri` and deliver it to that singleton.
4. `authenticate()` then calls the token exchange endpoint with `grant_type=authorization_code`.

### Current Stub in JvmGoogleAuthManager
`JvmGoogleAuthManager.exchangeCodeForTokens()` currently reuses `refreshGoogleToken()` (which sends `grant_type=refresh_token`) instead of a proper `grant_type=authorization_code` POST. This is a known TODO in the file. The correct token exchange endpoint is `https://oauth2.googleapis.com/token` with:
```
grant_type=authorization_code
code=<auth_code>
redirect_uri=http://localhost:8765/oauth2callback
client_id=<client_id>
client_secret=<client_secret>
```

### Risk
If the deep-link is not registered in the manifest, Google's OAuth screen will redirect to the custom URI but Android will show "No app found to handle this URI." The browser will not return the user to the app. This is a silent failure that's hard to debug in testing.

## Pitfall 2: rememberCoroutineScope Leakage in ShareDialog

### CLAUDE.md Rule
> Never pass a `rememberCoroutineScope()` result to a class that outlives the composable.

`ShareDialog` will be a composable. The share operation (especially Google Docs upload) is async. The temptation is:

```kotlin
// WRONG — leaks scope into ViewModel or service
val scope = rememberCoroutineScope()
val shareService = remember { ShareService(scope) }  // forbidden
```

### Correct Pattern
Button click handlers inside `ShareDialog` can use `rememberCoroutineScope()` for the click handler lambda itself (the lambda does not outlive the button — it's a one-shot). Long-running network work (Google Docs upload) must go through `StelekitViewModel`, which owns `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`. The ViewModel's scope is not tied to composition.

```kotlin
// Correct — scope used only for one-shot button click
val scope = rememberCoroutineScope()
Button(onClick = {
    scope.launch {
        val content = exportService.exportToString(...)
        shareProvider.shareText(content)  // fast local op — scope is fine here
    }
})

// For Google Docs upload — route through ViewModel
Button(onClick = { viewModel.shareToGoogleDocs(page, blocks, format) })
```

## Pitfall 3: Journal Date Range — N+1 DB Queries

`PageRepository.getJournalPageByDate()` returns a `Flow<Either<DomainError, Page?>>`. For a date range of 30 days, calling `getJournalPageByDate()` 30 times in a loop and collecting each flow would create 30 DB queries.

### Better Approach
Use `getJournalPages(limit, offset)` (which returns all journal pages ordered by date) and filter in-memory by the date range. For very large ranges, `getAllPages()` filtered by namespace `"journals"` is an option. The journal page `name` field contains the date string — filtering after a single `getAllPages()` call avoids N+1.

Alternatively, add a new repository method `getJournalPagesByDateRange(from: LocalDate, to: LocalDate)` that issues a single SQL query. This requires a new `SteleDatabase.sq` query and migration-runner entry (see CLAUDE.md mandatory migration rule).

## Pitfall 4: Block Ref UUID Resolution for Cross-Page Export

When exporting "Page + linked pages", `ExportService.resolveBlockRefs()` resolves `((uuid))` block references by fetching blocks from `BlockRepository`. If a `((uuid))` block ref points to a block in a *different page* that has not been loaded into the DB yet (because that page's file was never opened), the ref will be missing from the map and the exporter will fall back to `"[block ref]"`.

### Impact
For the "Page + linked pages" scope, linked pages are loaded via `PageRepository.getPageByName()`, but their blocks may not be in the DB if those pages were never visited. `ExportService.resolveBlockRefs()` calls `blockRepository.getBlockByUuid()` which only queries the DB — it doesn't trigger file loading.

### Mitigation
Before exporting linked pages, call `graphLoader.loadPage(linkedPage)` for each linked page that has `isLoaded = false`. This is the same path used by `StelekitViewModel.navigateTo()`. The export scope selection UI should show a loading indicator while linked pages are being loaded.

## Pitfall 5: Android Activity Context for Intent.ACTION_SEND

`Intent.ACTION_SEND` must be started from a valid Android `Context`. `SteleKitContext.context` (used in `AndroidGoogleAuthManager`) holds the `Application` context. Starting an Activity-style intent from `Application` context requires `FLAG_ACTIVITY_NEW_TASK`:

```kotlin
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, content)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // required for Application context
}
context.startActivity(Intent.createChooser(intent, "Share via")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))  // also needed on the chooser intent
```

On Android 12+ (API 32+), the `Intent.createChooser()` bottom sheet is shown automatically by the OS. On Android 11 and below, the chooser may appear as a full-screen activity. Both work correctly with `FLAG_ACTIVITY_NEW_TASK`.

## Pitfall 6: `DriveApiClient.uploadFile()` Sets Wrong Content-Type for Google Docs Conversion

Looking at the current `uploadFile()` implementation (line 97–102 in `DriveApiClient.kt`), the multipart body uses `Content-Type: $mimeType` for the content part. When `mimeType = "application/vnd.google-apps.document"`, the content part's Content-Type must be set to `text/html` (the source format), **not** `application/vnd.google-apps.document`. The metadata's `mimeType` field tells Drive what to *convert to*, while the content part's Content-Type tells Drive what format the *uploaded bytes* are in.

Current code:
```kotlin
append("Content-Type: $mimeType\r\n\r\n")   // WRONG for Google Docs — sets target type as source type
```

Required fix for Google Docs upload:
```kotlin
// The metadata `mimeType` is the target format (Google Docs)
// The content part must declare the source format (HTML)
val contentType = if (mimeType == "application/vnd.google-apps.document") "text/html" else mimeType
append("Content-Type: $contentType\r\n\r\n")
```

This bug would cause Drive to reject the upload or create a blank document. Must be fixed before Google Docs export will work.

## Pitfall 7: AWT FileDialog Threading on Desktop JVM

`FileDialog` on JVM must run on the AWT Event Dispatch Thread. Compose Desktop's `Dispatchers.Main` maps to the EDT, so any `suspend fun saveToFile(...)` in the JVM `ShareProvider` must `withContext(Dispatchers.Main) { ... }` around the `FileDialog` call:

```kotlin
// jvmMain
actual suspend fun saveToFile(content: String, suggestedName: String, extension: String): Boolean =
    withContext(Dispatchers.Main) {           // EDT required for FileDialog
        val dialog = FileDialog(null as Frame?, "Save File", FileDialog.SAVE)
        dialog.file = "$suggestedName.$extension"
        dialog.isVisible = true              // blocks until user dismisses
        val dir = dialog.directory ?: return@withContext false
        val file = dialog.file ?: return@withContext false
        withContext(Dispatchers.IO) {         // actual file write on IO dispatcher
            File(dir, file).writeText(content)
        }
        true
    }
```

If `FileDialog` is shown off the EDT, it may freeze or not render at all on macOS.

## Pitfall 8: Manifest Scope — kmp vs. app Module

The `kmp/src/androidMain/AndroidManifest.xml` is the library manifest. The OAuth deep-link `<intent-filter>` must go in the **app module's** `AndroidManifest.xml` (i.e., `app/src/main/AndroidManifest.xml`), because the intent-filter targets `MainActivity` which lives in the app module. Adding it to the kmp manifest would have no effect. This is a common mistake in KMP projects where the kmp module has its own manifest.

## Summary

- **Top risk**: Android OAuth deep-link callback is completely unwired — `authenticate()` returns immediately without waiting for the token. This requires both a manifest `<intent-filter>` in the app module and a coroutine suspension bridge in `AndroidGoogleAuthManager`.
- **Hidden bug**: `DriveApiClient.uploadFile()` sends `Content-Type: application/vnd.google-apps.document` in the content part, which is wrong — must be `text/html` when uploading HTML for Google Docs conversion.
- **Scope discipline**: Never pass `rememberCoroutineScope()` to `ShareDialog` internals; all network work goes through `StelekitViewModel`'s own scope.
- **Journal range N+1**: Use a single `getAllPages()` filter instead of per-day `getJournalPageByDate()` calls for date range export.
