# Implementation Plan: stelekit-share-note

**Feature**: Unified Share / Export — native OS share sheet, Google Docs export, and a multi-scope format picker replacing the current simple clipboard dropdown.
**Date**: 2026-06-07
**Status**: Ready for implementation
**ADRs**: Inline below (no separate ADR files; decisions are self-contained)

---

## Dependency Visualization

```
Phase 1: Foundation
  1.1a ShareProvider interface (commonMain)
  1.1b ShareProvider Android actual
  1.1c ShareProvider JVM actual
  1.1d ShareProvider iOS actual
  1.2a AppState + ShareScope additions
  1.2b ViewModel showShareDialog / hideShareDialog / shareToGoogleDocs
       ↓
Phase 2: OAuth Fixes (parallelisable with Phase 1)
  2.1a Fix AndroidGoogleAuthManager — SharedFlow bridge
  2.1b Add OAuth <intent-filter> to AndroidManifest
  2.1c MainActivity.onNewIntent → deliver code
  2.2a Fix JvmGoogleAuthManager.exchangeCodeForTokens
  2.3a Fix DriveApiClient content-type bug (Pitfall 6)
       ↓
Phase 3: Share Dialog UI
  3.1a ShareContent composable (scope + format + destination selectors)
  3.1b ShareDialog wrapper (ModalBottomSheet vs AlertDialog by isMobile)
  3.2a Wire ShareDialog into App.kt
  3.2b Replace TopBar export items with single "Share…"
  3.2c Sidebar page context menu → showShareDialog()
  3.2d Journal header → share icon → showShareDialog()
       ↓
Phase 4: Export Scopes
  4.1a Current-page scope (default, already works via ExportService)
  4.2a Page + linked pages scope — cycle-safe link resolver
  4.3a Selected blocks scope — reuse subtreeBlocks()
  4.4a Journal date range scope — getAllPages() + in-memory filter + date picker UI
       ↓
Phase 5: Save-to-File + Google Docs destinations
  5.1a Android SAF save-to-file via ACTION_CREATE_DOCUMENT
  5.1b Desktop AWT FileDialog save-to-file (EDT-safe)
  5.1c iOS UIDocumentPickerViewController save-to-file
  5.2a Google Docs upload via DriveApiClient + open browser
  5.2b Google account UX — inline "Connect Google" CTA in ShareDialog
       ↓
Phase 6: Tests + CI Hardening
  6.1a ShareProvider unit tests (JVM actual)
  6.2a ExportService scope tests (page+links, journal range, selected blocks)
  6.3a ShareDialog screenshot test (desktop dialog)
  6.4a DriveApiClient content-type fix regression test
  6.5a AndroidGoogleAuthManager token callback integration test
```

---

## ADR Decisions (Inline)

**ADR-1: ShareProvider as non-composable interface with @Composable expect factory**
`ShareProvider` is a plain `interface` (not a Composable). The `expect fun rememberShareProvider(): ShareProvider` factory is `@Composable` only to access `LocalContext` on Android. This mirrors `ClipboardProvider` / `PlatformClipboardProvider` exactly. Rationale: share operations are suspend functions, not UI-bound; keeping the interface non-composable lets it be tested without Compose infra.

**ADR-2: ShareDialog rendered at App.kt level, not ScreenRouter**
Like `SettingsDialog` and `SearchDialog`, `ShareDialog` is rendered in `App.kt` when `appState.shareDialogVisible == true`. This avoids prop-drilling through `ScreenRouter`. Entry points call `viewModel.showShareDialog()` only.

**ADR-3: Google Docs network work goes through StelekitViewModel**
`viewModel.shareToGoogleDocs(page, blocks)` uses the ViewModel's own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. `rememberCoroutineScope()` is used only for fast local operations (clipboard write, share intent dispatch) inside button click handlers in `ShareDialog`.

**ADR-4: Journal range export uses getAllPages() + in-memory filter**
Rather than N+1 per-day `getJournalPageByDate()` calls, fetch all pages via `PageRepository.getAllPages()` and filter by date range in-memory. For typical journal sizes (< 1000 pages) this is faster and simpler than a new SQL query. If DB grows large, a new indexed SQL query can replace this without API changes.

**ADR-5: Android OAuth — SharedFlow as coroutine bridge**
`AndroidGoogleAuthManager` suspends on a `MutableSharedFlow<String>` (capacity=1, `LATEST` overflow). `MainActivity.onNewIntent()` emits the auth code to this flow. `authenticate()` collects the first emission with a 5-minute timeout. This avoids a process-wide `CompletableDeferred` that cannot be reset on retry.

**ADR-6: DriveApiClient content-type fix**
When `mimeType == "application/vnd.google-apps.document"`, the content part must declare `Content-Type: text/html` (source format), not the target mimeType. The metadata `mimeType` field tells Drive the conversion target. This is a bug fix, not a new feature.

---

## Phase 1: Foundation — ShareProvider Interface + AppState

### Epic 1.1: ShareProvider Platform Abstraction
**Goal**: Establish the `ShareProvider` expect/actual structure for all three platforms so later phases can program against a stable interface.

#### Story 1.1.1: Define ShareProvider interface and expect factory
**As a** developer, **I want** a `ShareProvider` interface in commonMain, **so that** platform-specific share mechanics are hidden behind a stable contract.
**Acceptance Criteria**:
- `ShareProvider` interface with `shareText`, `shareHtml`, `saveToFile` compiles on all targets
- `rememberShareProvider()` `@Composable expect` function declared in commonMain
- No platform code in commonMain

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ShareProvider.kt` (create)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.kt` (create)

##### Task 1.1.1a: Write ShareProvider interface (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ShareProvider.kt`
- Define interface:
  ```kotlin
  interface ShareProvider {
      suspend fun shareText(content: String, mimeType: String = "text/plain")
      suspend fun shareHtml(html: String, plainFallback: String)
      suspend fun saveToFile(content: String, suggestedName: String, extension: String): Boolean
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ShareProvider.kt`

##### Task 1.1.1b: Write @Composable expect factory (~2 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.kt`
- Declare: `@Composable expect fun rememberShareProvider(): ShareProvider`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.kt`

#### Story 1.1.2: Android actual ShareProvider
**As a** developer, **I want** the Android ShareProvider to use `Intent.ACTION_SEND` with `FLAG_ACTIVITY_NEW_TASK`, **so that** notes can be shared via the OS share sheet.
**Acceptance Criteria**:
- `shareText` fires `Intent.ACTION_SEND` with `EXTRA_TEXT` + `FLAG_ACTIVITY_NEW_TASK`
- `shareHtml` fires `Intent.ACTION_SEND` with both `EXTRA_TEXT` (fallback) and `EXTRA_HTML_TEXT`
- `saveToFile` uses `Intent.ACTION_CREATE_DOCUMENT` via SAF; writes via `ContentResolver`

**Files**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.android.kt` (create)

##### Task 1.1.2a: Implement Android shareText + shareHtml (~4 min)
- `actual fun rememberShareProvider()` returns `AndroidShareProvider`
- `AndroidShareProvider.shareText`: `Intent.ACTION_SEND`, `type = mimeType`, `EXTRA_TEXT`, `FLAG_ACTIVITY_NEW_TASK` on both intent and chooser
- `AndroidShareProvider.shareHtml`: same but also set `EXTRA_HTML_TEXT`
- Use `SteleKitContext.context` (same as `AndroidGoogleAuthManager`)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.android.kt`

##### Task 1.1.2b: Implement Android saveToFile via SAF (~5 min)
- `saveToFile` uses `Intent.ACTION_CREATE_DOCUMENT`; requires returning a result from the Activity
- Pattern: mirror `pendingSaveFile: CompletableDeferred<String?>` already in `MainActivity`
- `saveFileLauncher` already exists in `MainActivity` for JSON — reuse or extend to support variable MIME types
- `AndroidShareProvider.saveToFile()` suspends on a new `CompletableDeferred<Uri?>` injected via a singleton channel pattern (see ADR-5 pattern)
- Writes content via `context.contentResolver.openOutputStream(uri)`
- Returns `true` if user confirmed, `false` if cancelled
- Files:
  - `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.android.kt`
  - `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt` (add SAF result handler)

#### Story 1.1.3: JVM actual ShareProvider
**As a** developer, **I want** the desktop ShareProvider to show an AWT `FileDialog` on the EDT for save-to-file, **so that** Desktop users can export notes to the filesystem.
**Acceptance Criteria**:
- `saveToFile` opens a native OS file-save dialog (AWT `FileDialog`)
- All dialog calls happen on the AWT EDT (`withContext(Dispatchers.Main)`)
- `shareText` / `shareHtml` are no-ops on desktop (Share via app tile is hidden on desktop)

**Files**:
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.jvm.kt` (create)

##### Task 1.1.3a: Implement JVM ShareProvider (~4 min)
- `actual fun rememberShareProvider()` returns `JvmShareProvider()`
- `shareText` / `shareHtml`: no-op (desktop has no share sheet; tile hidden in UI)
- `saveToFile`: `withContext(Dispatchers.Main) { FileDialog(...).apply { isVisible = true }; write with withContext(PlatformDispatcher.IO) { } }`
- Handle user cancel (null dir/file → return `false`)
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.jvm.kt`

#### Story 1.1.4: iOS actual ShareProvider
**As a** developer, **I want** the iOS ShareProvider to use `UIActivityViewController` for sharing and `UIDocumentPickerViewController` for save-to-file, **so that** iOS users get the native system share and save experience.
**Acceptance Criteria**:
- `shareText` presents `UIActivityViewController` from `rootViewController`
- `saveToFile` presents `UIDocumentPickerViewController` in export mode

**Files**:
- `kmp/src/iosMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.ios.kt` (create)

##### Task 1.1.4a: Implement iOS ShareProvider (~5 min)
- `actual fun rememberShareProvider()` returns `IosShareProvider()`
- `shareText`: `UIActivityViewController(activityItems = listOf(content as NSString), applicationActivities = null)`; present from `UIApplication.sharedApplication.keyWindow?.rootViewController`
- `shareHtml`: share plain fallback (HTML as NSAttributedString is complex; use plainFallback for now)
- `saveToFile`: write content to a temp file, present `UIDocumentPickerViewController` in export mode
- Files: `kmp/src/iosMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.ios.kt`

#### Story 1.1.5: wasmJs actual ShareProvider (stub)
**As a** developer, **I want** a no-op wasmJs actual, **so that** the WASM/JS target compiles without errors.

**Files**:
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.js.kt` (create)

##### Task 1.1.5a: Write wasmJs no-op actual (~2 min)
- `actual fun rememberShareProvider()` returns a no-op object implementing all methods
- `saveToFile` returns `false`
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.js.kt`

---

### Epic 1.2: AppState + ViewModel Extensions
**Goal**: Add share dialog state to `AppState` and the ViewModel methods to drive it, enabling all entry points to call `viewModel.showShareDialog()` without prop-drilling.

#### Story 1.2.1: Extend AppState with share dialog fields
**As a** developer, **I want** `AppState` to hold `shareDialogVisible`, `shareFormat`, `shareScope`, and `isExportingToDrive`, **so that** the share dialog state is centralized and persists across invocations within a session.
**Acceptance Criteria**:
- `AppState` compiles with new fields
- Default values: `shareDialogVisible = false`, `shareFormat = "markdown"`, `shareScope = ShareScope.CurrentPage`, `isExportingToDrive = false`
- `ShareScope` sealed class / enum defined in same package

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ShareScope.kt` (create)

##### Task 1.2.1a: Define ShareScope enum and extend AppState (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ShareScope.kt`:
  ```kotlin
  enum class ShareScope { CurrentPage, PageAndLinks, SelectedBlocks, JournalRange }
  ```
- Add to `AppState` data class:
  ```kotlin
  val shareDialogVisible: Boolean = false,
  val shareFormat: String = "markdown",
  val shareScope: ShareScope = ShareScope.CurrentPage,
  val isExportingToDrive: Boolean = false,
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ShareScope.kt`

#### Story 1.2.2: Add ViewModel share methods
**As a** developer, **I want** `StelekitViewModel` to expose `showShareDialog()`, `hideShareDialog()`, `setShareFormat()`, `setShareScope()`, and `shareToGoogleDocs()`, **so that** composable entry points have a thin, stable API.
**Acceptance Criteria**:
- `showShareDialog()` sets `appState.shareDialogVisible = true`
- `hideShareDialog()` sets `shareDialogVisible = false`
- `shareToGoogleDocs(page, blocks)` launches in `scope`, sets `isExportingToDrive`, emits Snackbar event on error
- All methods use `_appState.update { }` pattern (consistent with existing ViewModel)

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 1.2.2a: Add share state management methods (~4 min)
- Add `fun showShareDialog()`, `fun hideShareDialog()`, `fun setShareFormat(format: String)`, `fun setShareScope(scope: ShareScope)` — each calls `_appState.update { it.copy(...) }`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 1.2.2b: Add shareToGoogleDocs suspend ViewModel method (~5 min)
- `fun shareToGoogleDocs(page: Page, blocks: List<Block>, driveClient: DriveApiClient, exportService: ExportService)`:
  - `_appState.update { it.copy(isExportingToDrive = true) }`
  - `scope.launch { ... }`
  - `exportService.exportToString(page, blocks, "html")` → fold on Left: emit snackbar error
  - `driveClient.uploadFile(page.name, "application/vnd.google-apps.document", htmlBytes, null)` → fold
  - On success: `openInBrowser("https://docs.google.com/document/d/$fileId/edit")`
  - `_appState.update { it.copy(isExportingToDrive = false) }`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

---

## Phase 2: OAuth + Drive Bug Fixes

### Epic 2.1: Fix Android OAuth Deep-Link Callback
**Goal**: Wire `AndroidGoogleAuthManager.authenticate()` to actually suspend until the OAuth code is returned via deep-link, and register the `<intent-filter>` in the app manifest.

#### Story 2.1.1: Add OAuth intent-filter to AndroidManifest
**As a** user, **I want** the Android app to handle `com.stelekit.app:/oauth2redirect` deep links, **so that** Google's OAuth redirect returns me to SteleKit automatically.
**Acceptance Criteria**:
- `androidApp/src/main/AndroidManifest.xml` has `<intent-filter>` on `MainActivity` for scheme `com.stelekit.app`, path `/oauth2redirect`
- `android:launchMode="singleTask"` (already set) handles `onNewIntent`

**Files**:
- `androidApp/src/main/AndroidManifest.xml`

##### Task 2.1.1a: Add intent-filter to app AndroidManifest (~2 min)
- Add inside `<activity android:name="dev.stapler.stelekit.MainActivity">`:
  ```xml
  <intent-filter android:label="OAuth Callback">
      <action android:name="android.intent.action.VIEW" />
      <category android:name="android.intent.category.DEFAULT" />
      <category android:name="android.intent.category.BROWSABLE" />
      <data android:scheme="com.stelekit.app" android:path="/oauth2redirect" />
  </intent-filter>
  ```
- Files: `androidApp/src/main/AndroidManifest.xml`

#### Story 2.1.2: Implement SharedFlow suspend bridge in AndroidGoogleAuthManager
**As a** user, **I want** `authenticate()` to wait for the OAuth code delivered by deep-link instead of returning immediately, **so that** Google sign-in actually completes on Android.
**Acceptance Criteria**:
- `AndroidGoogleAuthManager` has a `companion object` `MutableSharedFlow<String>` (capacity=1, onBufferOverflow=`DROP_OLDEST`)
- `authenticate()` suspends on `.first()` with a 5-minute `withTimeout`
- On timeout, returns `Left(DomainError.NetworkError.HttpError(408, "OAuth timed out"))`
- Token exchange is called after receiving the code

**Files**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/google/AndroidGoogleAuthManager.kt`

##### Task 2.1.2a: Add SharedFlow bridge and suspend in authenticate() (~5 min)
- Add to companion object: `val oauthCodeFlow = MutableSharedFlow<String>(replay=0, extraBufferCapacity=1, onBufferOverflow=BufferOverflow.DROP_OLDEST)`
- In `authenticate()`: after `startActivity(intent)`, `withTimeout(5 * 60 * 1000L) { oauthCodeFlow.first() }` → call `exchangeCodeForTokens(code)`
- Add stub `private suspend fun exchangeCodeForTokens(code: String): Either<DomainError, String>` (real impl in Story 2.1.3)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/google/AndroidGoogleAuthManager.kt`

#### Story 2.1.3: Wire MainActivity.onNewIntent to deliver code
**As a** developer, **I want** `MainActivity.onNewIntent()` to extract the OAuth code from the deep-link URI and emit it to `AndroidGoogleAuthManager.oauthCodeFlow`, **so that** the suspended `authenticate()` call resumes.
**Acceptance Criteria**:
- `MainActivity.onNewIntent()` handles `intent.data?.scheme == "com.stelekit.app"` and extracts `code` query param
- Emits code to `AndroidGoogleAuthManager.oauthCodeFlow.tryEmit(code)`

**Files**:
- `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`

##### Task 2.1.3a: Add onNewIntent handler in MainActivity (~3 min)
- Override `onNewIntent(intent: Intent)` (call `super.onNewIntent(intent)`)
- Check `intent.data?.scheme == "com.stelekit.app" && intent.data?.path == "/oauth2redirect"`
- Extract `code = intent.data?.getQueryParameter("code")`
- `AndroidGoogleAuthManager.oauthCodeFlow.tryEmit(code ?: return)`
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`

#### Story 2.1.4: Implement proper token exchange for Android
**As a** developer, **I want** `AndroidGoogleAuthManager` to exchange the auth code for tokens via `grant_type=authorization_code` POST, **so that** the access/refresh token pair is stored correctly.
**Acceptance Criteria**:
- `exchangeCodeForTokens()` POSTs to `https://oauth2.googleapis.com/token` with `grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`
- Stores tokens in `tokenStore`
- Returns `Right(email)` on success, `Left(DomainError)` on failure

**Files**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/google/AndroidGoogleAuthManager.kt`

##### Task 2.1.4a: Implement Android token exchange POST (~5 min)
- Android doesn't have an `httpClient` injected yet — need to inject `io.ktor.client.HttpClient` (same pattern as `JvmGoogleAuthManager`)
- Reuse `refreshGoogleToken` function from `JvmGoogleAuthManager.kt` if it's in `commonMain` or extract it
- If `refreshGoogleToken` is JVM-only, extract a shared `suspend fun exchangeAuthCode(httpClient, code, clientId, redirectUri, tokenStore)` into `commonMain` or `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/GoogleTokenExchange.kt`
- Files:
  - `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/google/AndroidGoogleAuthManager.kt`
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/GoogleTokenExchange.kt` (create if needed)

---

### Epic 2.2: Fix JVM Token Exchange Stub
**Goal**: Replace the `exchangeCodeForTokens` stub in `JvmGoogleAuthManager` that incorrectly reuses `refreshGoogleToken` with a proper `grant_type=authorization_code` POST.

#### Story 2.2.1: Fix exchangeCodeForTokens in JvmGoogleAuthManager
**As a** developer, **I want** the JVM token exchange to send `grant_type=authorization_code`, **so that** Desktop OAuth sign-in returns valid tokens.
**Acceptance Criteria**:
- `exchangeCodeForTokens` sends a POST to `https://oauth2.googleapis.com/token` with `grant_type=authorization_code`
- Parses response for `access_token`, `refresh_token`, `expires_in`
- Stores tokens and email in `tokenStore`

**Files**:
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/google/JvmGoogleAuthManager.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/GoogleTokenExchange.kt` (create, shared with Android)

##### Task 2.2.1a: Extract and implement proper token exchange (~5 min)
- Create `GoogleTokenExchange.kt` in `commonMain` with `suspend fun exchangeAuthorizationCode(httpClient, code, clientId, clientSecret, redirectUri, tokenStore): Either<DomainError, String>`
- POST body: `grant_type=authorization_code&code=$code&redirect_uri=$redirectUri&client_id=$clientId&client_secret=$clientSecret`
- Parse JSON response; store `access_token`, `refresh_token`, `expires_in`, decode email from JWT claims or `/userinfo` endpoint
- Replace stub in `JvmGoogleAuthManager.exchangeCodeForTokens()` with call to `exchangeAuthorizationCode()`
- Files:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/GoogleTokenExchange.kt`
  - `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/google/JvmGoogleAuthManager.kt`

---

### Epic 2.2b: Add openInBrowser expect/actual
**Goal**: Provide a `commonMain` `expect fun openInBrowser(url: String)` with `androidMain` and `jvmMain` actuals so `shareToGoogleDocs()` can open the created doc URL cross-platform. `openInBrowser` does not currently exist in the codebase.

#### Story 2.2b.1: Add openInBrowser expect/actual
**Acceptance Criteria**:
- `commonMain` declares `expect fun openInBrowser(url: String)`
- `androidMain` actual uses `Intent.ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK`
- `jvmMain` actual uses `Desktop.getDesktop().browse(URI(url))`
- `iosMain` actual uses `UIApplication.sharedApplication.openURL`
- `wasmJsMain` actual is a no-op (or `window.open`)

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.kt` (create)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.android.kt` (create)
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.jvm.kt` (create)
- `kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.ios.kt` (create)
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.js.kt` (create)

##### Task 2.2b.1a: Implement openInBrowser expect + all actuals (~4 min)
- `commonMain`: `expect fun openInBrowser(url: String)`
- `androidMain`: `Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` via `SteleKitContext.context`
- `jvmMain`: `if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))`
- `iosMain`: `UIApplication.sharedApplication.openURL(NSURL.URLWithString(url)!!)`
- `wasmJsMain`: no-op or `js("window.open(url)")`
- Files: all 5 files listed above

---

### Epic 2.3: Fix DriveApiClient Content-Type Bug
**Goal**: Fix Pitfall 6 — the multipart content part must declare `text/html` as Content-Type when uploading HTML for Google Docs conversion, not the target mimeType.

#### Story 2.3.1: Fix uploadFile() content-type for Google Docs
**As a** developer, **I want** `DriveApiClient.uploadFile()` to use `text/html` in the content part when `mimeType == "application/vnd.google-apps.document"`, **so that** Drive correctly converts the uploaded HTML into a Google Doc.
**Acceptance Criteria**:
- When `mimeType == "application/vnd.google-apps.document"`, content-part header is `Content-Type: text/html`
- For all other mimeTypes, content-part header is `Content-Type: $mimeType` (unchanged)
- Existing tests pass; new regression test added (Phase 6)

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/DriveApiClient.kt`

##### Task 2.3.1a: Fix content-type in multipart builder (~2 min)
- Line ~101 in `DriveApiClient.kt`:
  ```kotlin
  // Before:
  append("Content-Type: $mimeType\r\n\r\n")
  // After:
  val contentMimeType = if (mimeType == "application/vnd.google-apps.document") "text/html" else mimeType
  append("Content-Type: $contentMimeType\r\n\r\n")
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/DriveApiClient.kt`

---

## Phase 3: Share Dialog UI

### Epic 3.1: Build ShareDialog Composable
**Goal**: A single composable that renders as `ModalBottomSheet` on mobile and `AlertDialog` on desktop, containing scope selector, format selector, and destination tiles.

#### Story 3.1.1: Build ShareContent composable (inner content)
**As a** user, **I want** to see scope, format, and destination options in the share UI, **so that** I can configure and trigger any export with a few taps.
**Acceptance Criteria**:
- Scope radio: Current Page | Page + Links | Selected Blocks | Journal Range
- Format segmented picker: Markdown | Plain Text | HTML | JSON
- Destination tiles: Clipboard (always), Share via app (mobile only), Save to file (always), Google Docs (always; shows "Connect Google" if not authenticated)
- Journal date range picker shown when scope = JournalRange
- Google account email shown below Google Docs tile when authenticated
- All selections update `AppState` via ViewModel callbacks

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt` (create)

##### Task 3.1.1a: Scaffold ShareContent with scope + format selectors (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`
- `@Composable fun ShareContent(appState, onScopeChange, onFormatChange, ...)` with scope radio group and format segmented control
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

##### Task 3.1.1b: Add destination tiles to ShareContent (~5 min)
- Add 2×2 tile grid: Clipboard, Share via app (hidden if `!isMobile`), Save to file, Google Docs
- Each tile: icon + label + `onClick` callback
- Google Docs tile: show `isExportingToDrive` spinner; show "Connect Google" CTA if `!isAuthenticated`
- Show connected email below Google Docs tile via `LaunchedEffect { googleAuthManager?.getConnectedEmail() }`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

##### Task 3.1.1c: Add journal date range picker to ShareContent (~4 min)
- When `shareScope == ShareScope.JournalRange`: show two `DatePicker`-like fields for `fromDate` and `toDate`
- Use `kotlinx-datetime.LocalDate`; local state `var fromDate by remember { mutableStateOf<LocalDate?>(null) }`
- Pass range to export action callbacks
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

#### Story 3.1.2: Wrap ShareContent in adaptive ShareDialog
**As a** user, **I want** the share UI to appear as a bottom sheet on mobile and a dialog on desktop, **so that** the experience matches platform conventions.
**Acceptance Criteria**:
- `ShareDialog` uses `ModalBottomSheet` when `isMobile`, `AlertDialog` when desktop
- Dismissal calls `onDismiss` → `viewModel.hideShareDialog()`

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

##### Task 3.1.2a: Wrap with ModalBottomSheet / AlertDialog (~3 min)
- `@Composable fun ShareDialog(isVisible, onDismiss, ...)`: early return if `!isVisible`
- `val isMobile = LocalWindowSizeClass.current.isMobile`
- Mobile: `ModalBottomSheet(onDismissRequest = onDismiss) { ShareContent(...) }`
- Desktop: `AlertDialog(onDismissRequest = onDismiss, ...) { ShareContent(...) }`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

---

### Epic 3.2: Wire ShareDialog into App.kt and Entry Points

#### Story 3.2.1: Render ShareDialog from App.kt
**As a** developer, **I want** `App.kt` to conditionally render `ShareDialog` when `appState.shareDialogVisible`, **so that** any entry point can trigger it without prop-drilling.
**Acceptance Criteria**:
- `ShareDialog` rendered in `App.kt`'s `StelekitApp` composable (same level as `SettingsDialog`)
- `shareProvider = rememberShareProvider()` called once in `StelekitApp`
- `googleAuthManager` and `driveApiClient` passed in from platform entry point (already wired for image annotation)

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 3.2.1a: Add ShareDialog to App.kt (~3 min)
- Add `if (appState.shareDialogVisible) { ShareDialog(page = appState.currentPage, ...) }` in `StelekitApp`
- `val shareProvider = rememberShareProvider()` (stored in `remember`)
- Wire `onDismiss = { viewModel.hideShareDialog() }`
- Wire destination callbacks to `viewModel.shareToGoogleDocs(...)`, `shareProvider.saveToFile(...)`, etc.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

#### Story 3.2.2: Replace TopBar export items with single "Share…"
**As a** user, **I want** the TopBar to show a single "Share…" menu item instead of 4 separate export options, **so that** the UI is cleaner and all export modes are accessible in one place.
**Acceptance Criteria**:
- `TopBar` parameter `onExportPage: ((formatId: String) -> Unit)?` replaced by `onShareClick: (() -> Unit)?`
- Overflow menu on mobile shows "Share…" calling `onShareClick()`
- Desktop File menu shows "Share…" calling `onShareClick()`
- All callers of `TopBar` updated

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/TopBarTest.kt`

##### Task 3.2.2a: Refactor TopBar signature and menu items (~4 min)
- Replace `onExportPage: ((formatId: String) -> Unit)?` with `onShareClick: (() -> Unit)?`
- Remove 4 export `DropdownMenuItem` entries; add single "Share…" item that calls `onShareClick?.invoke()`
- Update `TopBarTest` to use new parameter
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/TopBarTest.kt`

##### Task 3.2.2b: Update App.kt TopBar invocation (~2 min)
- Pass `onShareClick = { viewModel.showShareDialog() }` where `onExportPage` was previously passed
- Remove `isExporting` parameter if it's no longer needed by TopBar (it's now `appState.isExportingToDrive`)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

#### Story 3.2.3: Add "Share…" to sidebar page context menu
**As a** user, **I want** a "Share…" action in the sidebar page list's context/kebab menu, **so that** I can share a page without opening it.
**Acceptance Criteria**:
- Page context menu includes "Share…" item
- Clicking it calls `viewModel.showShareDialog()` (current page must already be set)

**Files**:
- Find sidebar page context menu composable (likely `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PageList.kt` or similar)

##### Task 3.2.3a: Add Share action to sidebar page context menu (~3 min)
- Locate sidebar page kebab / context menu composable
- Add `DropdownMenuItem("Share…") { onShareClick() }` before dismiss
- Pass `onShareClick: () -> Unit` down from `StelekitApp`
- Files: sidebar page list composable (exact path TBD on inspection)

#### Story 3.2.4: Add share icon to journal header
**As a** user, **I want** a share icon button in the journal page header, **so that** I can share today's journal entry quickly.
**Acceptance Criteria**:
- Journal header has an `IconButton` with a share icon
- Tapping it calls `viewModel.showShareDialog()`

**Files**:
- Find journal header composable (likely `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsScreen.kt` or `JournalHeader.kt`)

##### Task 3.2.4a: Add share icon to journal header (~3 min)
- Locate journal header composable
- Add `IconButton(onClick = onShareClick) { Icon(Icons.Default.Share, ...) }` in the header action row
- Files: journal header composable (exact path TBD on inspection)

---

## Phase 4: Export Scopes

### Epic 4.1: Current Page Scope
**Goal**: Current page is the default scope. `ExportService.exportToString()` already handles this; the ViewModel wires it correctly.

#### Story 4.1.1: Wire current-page scope export
**Acceptance Criteria**:
- When `shareScope == CurrentPage`, export uses `appState.currentPage` and blocks from `BlockStateManager`
- All destinations work with current-page scope

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (pass blocks to ShareDialog)

##### Task 4.1.1a: Pass current page blocks to ShareDialog (~2 min)
- In `App.kt`, pass `blocks = blockStateManager.blocksForCurrentPage()` to `ShareDialog`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

---

### Epic 4.2: Page + Linked Pages Scope
**Goal**: Export the current page plus all pages linked via `[[PageName]]` tokens (one level, cycle-safe).

#### Story 4.2.1: Implement linked-pages export resolver
**As a** user, **I want** to export the current page and all pages it links to, **so that** I can share a complete note cluster as a single document.
**Acceptance Criteria**:
- Resolves `[[PageName]]` tokens from block content via `InlineParser` + `PageLinkNode` (or equivalent)
- Loads linked pages from DB via `PageRepository.getPageByName()` + `BlockRepository`
- Calls `graphLoader.loadPage()` for pages with `isLoaded == false` before export
- Uses a `visited: Set<String>` to prevent cycles
- Empty linked pages are silently skipped
- Returns combined content in a single export string

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 4.2.1a: Add exportPageWithLinks() to ExportService (~5 min)
- `suspend fun exportPageWithLinks(page: Page, blocks: List<Block>, formatId: String, pageRepo: PageRepository, blockRepo: BlockReadRepository, graphLoader: GraphLoaderPort): Either<DomainError, String>`
- Collect `[[PageName]]` tokens using `InlineParser` looking for `WikiLinkNode` (confirmed name in `InlineNodes.kt`)
- For each linked page name not in `visited`: `pageRepo.getPageByName(name).first()`, load if needed, fetch blocks
- Combine all pages' exports via the chosen exporter
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt`

##### Task 4.2.1b: Add ViewModel method for page+links export (~3 min)
- `fun sharePageWithLinks(destination: ShareDestination, format: String)` in ViewModel
- Calls `exportService.exportPageWithLinks(...)` → dispatch to destination
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

---

### Epic 4.3: Selected Blocks Scope
**Goal**: Reuse the existing `ExportService.subtreeBlocks()` with `BlockStateManager.selectedBlockUuids`.

#### Story 4.3.1: Wire selected-blocks scope
**As a** user, **I want** to export only my selected blocks, **so that** I can share a subset of a page.
**Acceptance Criteria**:
- When `shareScope == SelectedBlocks`, `ExportService.subtreeBlocks(allBlocks, selectedUuids)` is used
- If no blocks are selected, show an inline error in `ShareDialog` ("No blocks selected")

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

##### Task 4.3.1a: Pass selectedBlockUuids to ShareDialog and guard empty selection (~3 min)
- In `App.kt`, pass `selectedBlockUuids = blockStateManager.selectedBlockUuids`
- In `ShareDialog`, when scope = `SelectedBlocks` and uuids empty: show warning text, disable destination tiles
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

---

### Epic 4.4: Journal Date Range Scope
**Goal**: Export all daily journal pages in a user-specified date range as a single document using `getAllPages()` + in-memory filter (ADR-4).

#### Story 4.4.1: Implement journal date range export
**As a** user, **I want** to export a date range of journal entries, **so that** I can share a week or month of notes as a single file.
**Acceptance Criteria**:
- Date range picker in `ShareDialog` (two `LocalDate` fields)
- Export uses `pageRepo.getAllPages()` filtered by date; empty days skipped
- Combined output is a single string with each day's content separated by a heading
- Returns `Left(DomainError.ExportError.SerializationFailed("No journal pages in range"))` if range is empty

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 4.4.1a: Add exportJournalRange() to ExportService (~5 min)
- `suspend fun exportJournalRange(from: LocalDate, to: LocalDate, formatId: String, pageRepo: PageRepository, blockRepo: BlockReadRepository): Either<DomainError, String>`
- `pageRepo.getAllPages().first()` → filter pages in journals namespace with date in `[from, to]`
- For each matching page: load blocks, export, join with `"## $date\n\n"` separator
- Return `Left` if zero pages in range
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt`

##### Task 4.4.1b: Wire journal range in ViewModel and ShareDialog (~3 min)
- Pass `fromDate` / `toDate` from `ShareDialog` local state to ViewModel callback
- ViewModel method `shareJournalRange(from, to, destination, format)` dispatches to correct destination
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

---

## Phase 5: Save-to-File and Google Docs Destinations

### Epic 5.1: Save-to-File Wire-Up
**Goal**: Destinations dispatch `exportToString()` output to `ShareProvider.saveToFile()` for all scopes.

#### Story 5.1.1: Wire save-to-file destination in ShareDialog
**As a** user, **I want** to save an exported note to a file on my device, **so that** I have a local copy.
**Acceptance Criteria**:
- "Save to file" tile triggers `shareProvider.saveToFile(content, pageName, extension)`
- Extension derived from format: `"md"`, `"txt"`, `"html"`, `"json"`
- On Android, uses SAF `ACTION_CREATE_DOCUMENT`; on Desktop, uses AWT `FileDialog`
- Returns `false` (user cancelled) → no error shown; `true` → show success snackbar

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

##### Task 5.1.1a: Wire save-to-file onClick in ShareContent (~3 min)
- Map formatId to extension: `"markdown" → "md"`, `"plain-text" → "txt"`, `"html" → "html"`, `"json" → "json"`
- `scope.launch { val content = exportService.exportToString(...); if (shareProvider.saveToFile(content, page.name, ext)) viewModel.showSnackbar("Saved") }`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

---

### Epic 5.2: Google Docs Upload + Account UX

#### Story 5.2.1: Wire Google Docs tile in ShareDialog
**As a** user, **I want** to tap "Google Docs" and have my note uploaded and opened in the browser, **so that** I can collaborate on it in Google Workspace.
**Acceptance Criteria**:
- "Google Docs" tile onClick calls `viewModel.shareToGoogleDocs(page, blocks, driveApiClient, exportService)`
- `isExportingToDrive` shows spinner on tile
- On success: browser opens `https://docs.google.com/document/d/$fileId/edit`
- On network error: Snackbar with retry action
- On auth failure: show reconnect prompt inline

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

##### Task 5.2.1a: Wire Google Docs tile onClick and loading state (~4 min)
- Google Docs tile: `isLoading = appState.isExportingToDrive`; `onClick = { viewModel.shareToGoogleDocs(...) }`
- `viewModel.shareToGoogleDocs()` (from Story 1.2.2b) already handles error emission and browser open
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`

#### Story 5.2.2: Google account connection UX
**As a** user, **I want** to see an inline "Connect Google Account" prompt when I'm not authenticated, **so that** I can initiate sign-in without leaving the Share dialog.
**Acceptance Criteria**:
- Google Docs tile shows "Connect Google" text + button when `!isAuthenticated`
- Tapping "Connect Google" calls `viewModel.scope.launch { googleAuthManager.authenticate() }` (routes through ViewModel to avoid `rememberCoroutineScope` leakage)
- After auth success, tile resets to show account email
- Connected account email shown below tile when authenticated

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 5.2.2a: Implement auth CTA in Google Docs tile (~4 min)
- `var isAuthenticated by remember { mutableStateOf(false) }`
- `LaunchedEffect(Unit) { isAuthenticated = googleAuthManager?.isAuthenticated() == true }`
- When `!isAuthenticated`: show "Connect Google" `TextButton`; onClick → `viewModel.connectGoogle(googleAuthManager)`
- `viewModel.connectGoogle()`: `scope.launch { googleAuthManager.authenticate().onRight { isAuthenticated refresh } }`
- When `isAuthenticated`: show email via `LaunchedEffect { email = googleAuthManager.getConnectedEmail() }`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

---

## Phase 6: Tests + CI Hardening

### Epic 6.1: ShareProvider Unit Tests (JVM)

#### Story 6.1.1: JVM ShareProvider save-to-file test
**Acceptance Criteria**:
- Test that `JvmShareProvider.saveToFile()` writes expected content to a temp file when user "accepts" the dialog (mock `FileDialog`)
- Test cancel path returns `false`

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/JvmShareProviderTest.kt` (create)

##### Task 6.1.1a: Write JvmShareProvider tests (~4 min)
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/JvmShareProviderTest.kt`

---

### Epic 6.2: ExportService Scope Tests

#### Story 6.2.1: Page + linked pages export test
**Acceptance Criteria**:
- In-memory repositories; page A links to page B; assert both appear in export output
- Cycle (A→B→A) test: assert B appears once, no infinite loop

**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/export/ExportServiceLinkedPagesTest.kt` (create)

##### Task 6.2.1a: Write linked-pages export tests (~5 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/export/ExportServiceLinkedPagesTest.kt`

#### Story 6.2.2: Journal date range export test
**Acceptance Criteria**:
- In-memory repositories with 3 journal pages; range includes 2; assert output includes those 2
- Empty range returns `Left`

**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/export/ExportServiceJournalRangeTest.kt` (create)

##### Task 6.2.2a: Write journal range export tests (~4 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/export/ExportServiceJournalRangeTest.kt`

---

### Epic 6.3: ShareDialog Screenshot Test

#### Story 6.3.1: Desktop ShareDialog screenshot test
**Acceptance Criteria**:
- Roborazzi screenshot test of `ShareDialog` in desktop (AlertDialog) mode
- Covers: scope selector, format picker, destination tiles, Google Docs "Connect" state

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/ShareDialogScreenshotTest.kt` (create)

##### Task 6.3.1a: Write ShareDialog screenshot test (~4 min)
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/ShareDialogScreenshotTest.kt`

---

### Epic 6.4: DriveApiClient Content-Type Regression Test

#### Story 6.4.1: Test that Google Docs upload uses text/html content type
**Acceptance Criteria**:
- Mock HTTP client asserts `Content-Type: text/html` in the multipart body when `mimeType = "application/vnd.google-apps.document"`
- Existing upload test for other mimeTypes unchanged

**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/platform/google/DriveApiClientTest.kt` (create or extend existing)

##### Task 6.4.1a: Write DriveApiClient content-type regression test (~4 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/platform/google/DriveApiClientTest.kt`

---

### Epic 6.5: Android OAuth Callback Integration Test

#### Story 6.5.1: Test AndroidGoogleAuthManager SharedFlow bridge
**Acceptance Criteria**:
- Unit test: `authenticate()` suspends; `oauthCodeFlow.emit(code)` resumes it; `exchangeCodeForTokens()` is called with that code
- Timeout test: no code delivered → returns `Left(408)`

**Files**:
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/google/AndroidGoogleAuthManagerTest.kt` (create)

##### Task 6.5.1a: Write AndroidGoogleAuthManager unit tests (~5 min)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/google/AndroidGoogleAuthManagerTest.kt`

---

## Implementation Sequence Summary

| Order | Task | Parallelisable with |
|---|---|---|
| 1 | Phase 1: ShareProvider interface + AppState | independent |
| 2 | Phase 2: OAuth + Drive bug fixes | can start in parallel with Phase 1 |
| 3 | Phase 3: ShareDialog UI | requires Phase 1 complete |
| 4 | Phase 4: Export scopes | requires Phase 3 scaffolded |
| 5 | Phase 5: Destinations wire-up | requires Phase 3 + Phase 2 |
| 6 | Phase 6: Tests | requires Phase 1–5 complete |

Minimum path to testable feature: 1.1a → 1.1b → 1.2.1a → 1.2.2a → 2.3.1a → 3.1.1a → 3.1.2a → 3.2.1a → 3.2.2a → 4.1.1a → 5.2.1a

---

## Risk Register

| Risk | Mitigation |
|---|---|
| Android OAuth: no client_secret available in kmp module | `exchangeCodeForTokens` accepts `clientSecret` as constructor param; app module injects from `BuildConfig` |
| `WikiLinkNode` for page link traversal | Confirmed: `InlineNodes.kt` defines `WikiLinkNode(target: String, alias: String?)` — use `node is WikiLinkNode` in the linked-pages resolver |
| iOS `UIDocumentPickerViewController` requires main thread | Dispatch to main via `MainScope().launch` inside `IosShareProvider` |
| `wasmJs` target disabled by default (`enableJs=true` in gradle.properties) | Confirm wasmJs actual is only compiled when enabled; no-op stub is safe |
| Android `saveFileLauncher` is currently typed to JSON MIME | Extend or add a second launcher for variable MIME; reuse `pendingSaveFile` pattern |
