# Git Setup UX — Feature Research

Research date: 2026-06-12

---

## 1. Logseq Vault/Graph Directory Selection UX

### Desktop (Electron / File System Access API)

On desktop, Logseq uses the browser-based **File System Access API** (via Electron). The selected folder path is stored in `~/.logseq/graphs/` as a `.transit` file keyed by the absolute path. The graph name shown in the sidebar is derived from the directory name — not the full path. There is no truncation problem on desktop because folder names are short.

### Android — Current Implementation

Logseq Android uses a custom **Capacitor plugin called `FolderPicker`** to handle local file system directory selection. The plugin is part of Logseq's fork of Capacitor that extends it with several native Android plugins (also including `LiquidTabsPlugin` for bottom nav and `NativeBottomSheetPlugin` for modal sheets).

**Critical finding:** Logseq Android does **not** use SAF (Storage Access Framework). It requests the broad **`MANAGE_ALL_FILES` permission** (i.e., `android.permission.MANAGE_EXTERNAL_STORAGE`), which grants read/write/delete access to the entire filesystem. This is the legacy approach predating Android 10's scoped storage enforcement.

**UX flow (Android):**

1. On first launch, Logseq presents a screen asking if the user wants to create a new graph or open an existing directory.
2. The user taps **"Open Existing Directory"** to browse to a folder.
3. The `FolderPicker` plugin fires an Android `Intent` to let the user select a directory.
4. After selection, the graph appears in the sidebar identified by its folder name.

**Known issues:**

- Users on Android 10 reported: `"Error: Cannot support this directory type:"` — the directory picker rejected certain paths silently.
- The issue was marked "DONE" in Logseq's tracker but without a published fix description.
- Because the app uses `MANAGE_ALL_FILES` rather than SAF, **network drives, encrypted folders (e.g. EDS Lite), and app-private Document Provider directories are inaccessible**.
- Users trying to point Logseq at a folder exposed by a Document Provider (e.g. Nextcloud, Cryptomator) cannot do so.

**Path display:** Logseq shows the graph by its folder name in the sidebar. The underlying full path is not displayed to the user during normal use. Community users trying to locate the path dig into `~/.logseq/graphs` or use `logseq://graph/<name>` URIs.

**SAF Feature Request status:** An active forum thread (open through 2024) requests implementing full SAF support — specifically the Document Provider pattern so Logseq could act as a provider and expose its graphs to other apps, and consume Document Providers for network drives. This remains unimplemented in the legacy ClojureScript codebase.

### Android — What SAF Would Mean

The correct Android approach (which Logseq does not yet use) is:

- Fire `Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)` — this invokes the system directory picker via SAF.
- Receive a `content://` URI (e.g. `content://com.android.externalstorage.documents/tree/primary:MyGraph`) from the `ActivityResult`.
- Persist it with `contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)`.
- Store the URI string as the graph identifier, not a filesystem path.

The trade-off: `content://` URIs are opaque and ugly if shown directly to users. Good UX converts them to a display name using `DocumentFile.fromTreeUri(context, uri)?.name`.

---

## 2. Obsidian Vault Path Selection UX

### Desktop

On desktop (Electron), Obsidian presents a **vault switcher screen** on first launch with three options:

1. **Create new vault** — prompts for a vault name and lets the user choose a storage location via the native OS file dialog.
2. **Open folder as vault** — opens a native OS directory picker; any folder can be a vault.
3. **Open vault from Obsidian Sync** — for paying subscribers.

After selection, Obsidian shows the vault name (derived from the folder name) in the title bar. The full path is available in Settings → About.

### Android

**UX flow (Android):**

1. Launch Obsidian → vault selection screen appears.
2. Three buttons: **"Create new vault"**, **"Open folder as vault"**, **"Sign in with Obsidian Sync"**.
3. Tapping **"Open folder as vault"** → Android system file picker opens.
4. User browses directories; tapping a folder shows **"Use this folder"** button at the bottom.
5. Tapping "Use this folder" → Android prompts: **"Allow Obsidian to access files in [FolderName]?"** — this is the SAF permission grant dialog.
6. Obsidian receives a persistent `content://` URI and stores it.

**SAF implementation:** Obsidian Android **does use SAF** via `ACTION_OPEN_DOCUMENT_TREE`. This is confirmed by the system permission dialog users see and by the fact that vault locations include inaccessible areas like Samsung's Secure Folder — which SAF intentionally blocks, causing a known bug where the folder picker is blank or crashes inside Secure Folder.

**Known bugs:**

- On Android 16 (Samsung S25): after selecting "Open folder as vault" or "Create new vault", users see a blank "Recent" files screen with no folders visible.
- Attempting to navigate the file picker causes the entire interface to crash within ~5 seconds.
- The underlying cause is suspected to be missing system file browser components, ungranted permissions, or outdated WebView — but no definitive fix is documented.
- Secure Folder vaults are impossible: SAF does not expose Secure Folder contents to normal apps by design.
- Feature request for arbitrary folder access (e.g. a custom iCloud folder) remains open since 2021.

**Path display:** After vault selection, Obsidian shows only the **vault name** (folder name) in the vault switcher and title bar. The underlying `content://` URI is never shown to the user. The vault path is accessible in Settings → About → "Vault path".

### iOS

**Current restrictions:** Obsidian iOS can only store vaults in one of two locations:

1. **App container**: the "Obsidian" folder inside the app's sandboxed iCloud Drive container.
2. **On My iPhone/iPad**: local device storage.

Users cannot pick an arbitrary iCloud Drive folder outside the Obsidian container, a Dropbox folder, or a network drive. This is enforced by iOS sandboxing.

**Technical mechanism:** On iOS, Obsidian uses **security-scoped bookmarks** (via `UIDocumentPickerViewController`) to get persistent access to file URLs outside the sandbox. The security-scoped bookmark is stored so the app can re-open the same directory across launches without re-requesting permission. However, Obsidian has constrained this to its own container folder rather than exposing arbitrary picker access.

**iOS feature request (open since 2021):** Users want `UIDocumentPickerViewController` with `asCopy: false` mode to select any folder in iCloud Drive as a vault. Apps like Textastic already do this successfully. The Obsidian team has not announced a timeline for implementing this.

**iOS onboarding smoothness:** Despite the restriction, iOS onboarding is considered smooth for the common case (iCloud vault) because:

- The default is iCloud sync, which works automatically.
- No folder picker is needed for the standard flow.
- Friction only appears when users want non-iCloud storage.

---

## 3. GitHub CLI OAuth Device Flow UX

### Command Sequence

Running `gh auth login` presents an interactive prompt sequence (arrow-key navigation):

```
? What account do you want to log into?
> GitHub.com
  GitHub Enterprise Server

? What is your preferred protocol for Git operations on this host?
> HTTPS
  SSH

? Authenticate Git with your GitHub credentials? (Y/n)

? How would you like to authenticate GitHub CLI?
> Login with a web browser
  Paste an authentication token
```

### One-Time Code Display

After selecting "Login with a web browser":

```
! First copy your one-time code: 7CDF-8959
- Press Enter to open https://github.com/login/device in your browser...
```

The code format is **`XXXX-XXXX`** (4 alphanumeric chars, hyphen, 4 alphanumeric chars) — matching the GitHub API's `user_code` field. The `!` prefix (yellow/orange in terminals that support ANSI color) visually sets it apart from the interactive prompt lines.

### Browser Launch Behavior

- The CLI **automatically opens the browser** when the user presses Enter.
- If the browser cannot be opened (headless environment, SSH session), it falls back to: `"Please manually open the following URL in your browser:"` followed by `https://github.com/login/device`.
- The CLI does **not** pre-fill the code in the URL — the user must type the 8-character code manually on the GitHub website.

### Polling Feedback

While waiting for the user to authorize in the browser, the CLI shows a spinner or waiting message. The implementation (in the `cli/oauth` Go library) polls `POST https://github.com/login/oauth/access_token` at the interval returned by the device code endpoint (default: every 5 seconds).

### Error Handling

The GitHub device flow returns specific error codes during polling; the `gh` CLI handles them as follows:

| Error code | GitHub API meaning | CLI behavior |
|---|---|---|
| `authorization_pending` | User hasn't authorized yet | Continue polling silently |
| `slow_down` | Polling too fast | Add 5 seconds to interval, log: `"Received slow_down response, increasing interval"` |
| `expired_token` | 15 min window elapsed | Display: `"The device code has expired. Please start the process again."` and exit |
| `access_denied` | User clicked "Cancel" | Display: `"User has denied the request. The authorization process has been canceled."` and exit |
| `incorrect_device_code` | Invalid code | Display error and exit |

The polling interval starts at 5 seconds (as returned by the API) and increases by 5 seconds per `slow_down` response. The maximum polling window is 900 seconds (15 minutes), yielding a maximum of ~150 polling attempts before the code expires.

### Success State

```
✓ Authentication complete.
! Authentication credentials saved in plain text
✓ Logged in as <username>
```

The `✓` prefix (green) and `!` prefix (yellow) provide visual differentiation between success and informational messages.

### Implementation Architecture

The GitHub CLI uses a separate Go library, [`cli/oauth`](https://github.com/cli/oauth), for the OAuth flow. It attempts Device Flow first and falls back to a localhost HTTP receiver (`http://127.0.0.1:<port>/`) as a web-app flow fallback. The separation of the oauth library from the main `cli/cli` repo means the UX logic is independently testable.

---

## 4. `expect`/`actual` Pattern in KMP for Platform-Specific UI Callbacks

### The Core Problem

Platform pickers (directory, file, camera) require lifecycle-bound registration on Android:

- `registerForActivityResult()` / `rememberLauncherForActivityResult()` must be called **during composition**, not inside a click handler.
- On JVM, a Swing `JFileChooser` is shown synchronously and returns immediately.
- On iOS, a `UIDocumentPickerViewController` is presented modally and calls a delegate callback asynchronously.

This asymmetry means the common interface must be **callback-based** (not suspend-returning), since Android requires the launcher to be pre-registered in the composable tree.

### Recommended Pattern: Composable Factory Function + Callback

The established KMP pattern (used by FileKit and community implementations) is:

```kotlin
// commonMain
@Composable
expect fun rememberDirectoryPickerLauncher(
    title: String = "Pick a directory",
    initialDirectory: String? = null,
    onResult: (PlatformDirectory?) -> Unit
): DirectoryPickerLauncher

expect class DirectoryPickerLauncher {
    fun launch()
}
```

The `@Composable` factory function (`rememberXxx`) is the key insight: it runs in the composition phase, allowing Android's `rememberLauncherForActivityResult` to register properly. The returned `DirectoryPickerLauncher` is then called imperatively (e.g. from a `Button`'s `onClick`).

### Android Actual Implementation

```kotlin
// androidMain
@Composable
actual fun rememberDirectoryPickerLauncher(
    title: String,
    initialDirectory: String?,
    onResult: (PlatformDirectory?) -> Unit
): DirectoryPickerLauncher {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val dir = uri?.let { PlatformDirectory(it) }
        onResult(dir)
    }
    return remember { DirectoryPickerLauncher { launcher.launch(null) } }
}

actual class DirectoryPickerLauncher(private val onLaunch: () -> Unit) {
    actual fun launch() = onLaunch()
}
```

Key points:
- `ActivityResultContracts.OpenDocumentTree()` triggers `ACTION_OPEN_DOCUMENT_TREE` — the SAF directory picker.
- `rememberLauncherForActivityResult` **must be called during composition**, hence the `@Composable` factory.
- The `launcher.launch(null)` call in `DirectoryPickerLauncher.launch()` defers the actual Intent launch to click time.
- The resulting URI is a `content://` SAF URI requiring `takePersistableUriPermission` for persistence across process restarts.

**Android initialization requirement (FileKit pattern):** For non-Compose (coroutine-based) usage, `FileKit.init(this)` must be called in `MainActivity.onCreate()` before the composable tree starts. This registers the `ActivityResultRegistry` reference. The Compose variant eliminates this manual step by using `LocalContext` and `rememberLauncherForActivityResult` directly.

### JVM (Desktop) Actual Implementation

```kotlin
// jvmMain
@Composable
actual fun rememberDirectoryPickerLauncher(
    title: String,
    initialDirectory: String?,
    onResult: (PlatformDirectory?) -> Unit
): DirectoryPickerLauncher {
    return remember {
        DirectoryPickerLauncher(title, initialDirectory, onResult)
    }
}

actual class DirectoryPickerLauncher(
    private val title: String,
    private val initialDirectory: String?,
    private val onResult: (PlatformDirectory?) -> Unit
) {
    actual fun launch() {
        // JFileChooser is synchronous — safe to call from any thread,
        // but must dispatch result back to UI thread
        val chooser = JFileChooser(initialDirectory).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        val result = chooser.showOpenDialog(null)
        val dir = if (result == JFileChooser.APPROVE_OPTION) {
            PlatformDirectory(chooser.selectedFile)
        } else null
        onResult(dir)
    }
}
```

On Linux, FileKit uses **XDG Desktop Portal** (via DBus/JNA) instead of `JFileChooser`, which produces a native GTK/KDE dialog. On Windows/macOS it uses JNA to call native Win32 or AppKit dialogs.

### iOS Actual Implementation

```kotlin
// iosMain
@Composable
actual fun rememberDirectoryPickerLauncher(
    title: String,
    initialDirectory: String?,
    onResult: (PlatformDirectory?) -> Unit
): DirectoryPickerLauncher {
    return remember { DirectoryPickerLauncher(onResult) }
}

actual class DirectoryPickerLauncher(
    private val onResult: (PlatformDirectory?) -> Unit
) {
    actual fun launch() {
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeFolder)
        )
        picker.allowsMultipleSelection = false
        picker.delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>
            ) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                onResult(url?.let { PlatformDirectory(it) })
            }
            override fun documentPickerWasCancelled(
                controller: UIDocumentPickerViewController
            ) {
                onResult(null)
            }
        }
        // Present from root view controller
        UIApplication.sharedApplication.keyWindow?.rootViewController
            ?.presentViewController(picker, animated = true, completion = null)
    }
}
```

The iOS result is an `NSURL` with a **security-scoped bookmark** — the app must call `url.startAccessingSecurityScopedResource()` before reading and `stopAccessingSecurityScopedResource()` after. For persistence across launches, bookmark data is archived with `url.bookmarkDataWithOptions`.

### Alternative: Suspend-Based API (Non-Composable)

For non-Compose callers or when a suspend API is preferred, the pattern shifts to a `CompletableDeferred`:

```kotlin
// commonMain
expect suspend fun pickDirectory(title: String): PlatformDirectory?
```

On Android, this requires the `ActivityResultRegistry` to already be initialized (via `FileKit.init(activity)` or equivalent), then wraps the callback in `suspendCancellableCoroutine`:

```kotlin
// androidMain
actual suspend fun pickDirectory(title: String): PlatformDirectory? =
    suspendCancellableCoroutine { cont ->
        activityResultRegistry.register(
            key = "dir_picker_${System.currentTimeMillis()}",
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            cont.resume(uri?.let { PlatformDirectory(it) })
        }.launch(null)
    }
```

**Caveat:** Registering an `ActivityResultLauncher` outside the composable lifecycle (e.g. after `onStart`) produces a runtime warning and may fail on Android. The recommended production approach is the `@Composable` factory pattern above.

### FileKit as the Canonical Reference Implementation

**[FileKit](https://github.com/vinceglb/FileKit)** (by Vincent Guillebaud) is the most widely referenced KMP library for file/directory picking. Its API is the de facto standard:

- `FileKit.openDirectoryPicker()` — suspend function for non-Compose callers
- `rememberDirectoryPickerLauncher { directory -> }` — Compose composable with callback
- `PlatformDirectory.path: String?` — common property; platform-specific access via `uri` (Android), `nsUrl` (iOS), `file` (JVM)
- `FileKit.isDirectoryPickerSupported()` — runtime check (returns false for WASM/JS)
- `FileKit.init(activity)` — required in `MainActivity.onCreate()` for core (non-Compose) API on Android

Platform implementations:
- **Android**: `ActivityResultContracts.OpenDocumentTree` → SAF `content://` URI
- **iOS/macOS**: `UIDocumentPickerViewController` / `NSOpenPanel`
- **JVM (Windows/macOS)**: JNA to native Win32/AppKit dialogs
- **JVM (Linux)**: XDG Desktop Portal via DBus

### Design Tension: Activity Context Requirement

The fundamental Android constraint is that `registerForActivityResult` must be called at composition time (before the composable is first drawn), not lazily. Three patterns exist to resolve this:

| Pattern | Mechanism | Trade-off |
|---|---|---|
| `@Composable` factory (`rememberXxx`) | Registers launcher during composition via `rememberLauncherForActivityResult` | Cannot be called outside Compose; requires `@Composable` propagation up the call stack |
| `FileKit.init(activity)` + suspend API | Pre-registers registry reference in `onCreate`; wraps callback in coroutine | Requires manual init step; potential lifecycle mismatch if activity is recreated |
| ViewModel + `ActivityResultRegistry` injection | Inject the registry into the ViewModel at `onCreate` time; ViewModel owns the launcher | Testable; survives config changes; more boilerplate |

The `@Composable` factory pattern is the cleanest for pure Compose apps. The ViewModel injection pattern is preferred when the picker needs to be triggered from business logic outside the composable tree.
