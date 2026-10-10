# Git Setup UX — Implementation Pitfalls & Risk Register

Platform tags: **(A)** = Android only, **(iOS)** = iOS only, **(JVM)** = Desktop JVM only, **(X)** = cross-platform / protocol-level

---

## 1. GitHub Device Flow OAuth **(X)**

### 1.1 Polling error codes — complete reference

All error responses arrive as `application/x-www-form-urlencoded` bodies on `POST https://github.com/login/oauth/access_token`. Parse the `error` field.

| Error string | HTTP status | Meaning | Retryable? | Required action |
|---|---|---|---|---|
| `authorization_pending` | 200 | User has not yet entered the code | Yes | Wait full `interval`, then retry |
| `slow_down` | 200 | Polled too fast | Yes | Add **5 s** to current interval (cumulative), then retry |
| `expired_token` | 200 | `expires_in` window (900 s) elapsed | No — restart | Request new device code via `POST /login/device/code` |
| `access_denied` | 200 | User clicked Cancel | No | Show "auth cancelled" UI, offer retry from beginning |
| `incorrect_device_code` | 200 | `device_code` value is wrong/corrupt | No | Logic error — re-request device code |
| `unsupported_grant_type` | 400 | Wrong `grant_type` value | No | Must use `urn:ietf:params:oauth:grant-type:device_code` |
| `incorrect_client_credentials` | 401 | Bad `client_id` | No | Configuration error |
| `device_flow_disabled` | 400 | App settings have device flow turned off | No | Configuration error — enable in GitHub App settings |

**`slow_down` is cumulative.** Each `slow_down` response adds 5 s to whatever the current interval already is. If you receive three `slow_down` responses starting from a 5 s interval, the interval becomes 5 → 10 → 15 → 20 s. Track the live interval in a mutable variable and never reset it back to the initial value within a single auth session.

### 1.2 Rate limits

- **50 verification code submissions per hour per OAuth App** (the browser-side entry, not your polling). No client-side mitigation possible — surface a clear error if you ever receive this.
- **Polling rate limit** is enforced per `client_id`. Exceeding the `interval` triggers `slow_down`. There is no published hard ceiling beyond "one request per interval."
- **10 tokens per user per scope set**: If your app creates more than 10 tokens for the same user with the same scopes, GitHub automatically revokes the oldest ones. Mitigation: store and reuse the issued token; never re-authenticate if a valid token already exists.

### 1.3 Network errors during polling

Distinguish protocol errors from transport errors:

- **Transport error** (connection refused, timeout, DNS failure): do **not** count against the interval. Log and retry after the normal interval. Implement an exponential-backoff cap (e.g., max 60 s) to avoid hammering unreachable endpoints.
- **Protocol error** (`slow_down`, `authorization_pending`): adjust interval as specified and continue.
- **Terminal protocol error** (`expired_token`, `access_denied`): stop polling unconditionally.

A common bug is treating a 5xx response as `authorization_pending` and continuing to poll. 5xx from GitHub means a server-side problem — back off aggressively (30–60 s) and surface a warning to the user after several consecutive failures.

### 1.4 Token expiry after issuance — classic OAuth App vs GitHub App

This is a critical distinction:

| Token type | Prefix | Expires? | Refresh token? |
|---|---|---|---|
| Classic OAuth App token | `gho_` | No (revoked after 1 year of inactivity) | No |
| GitHub App user-to-server token (expiration **enabled**) | `ghu_` | Yes — **8 hours** | Yes — refresh token expires in 6 months |
| GitHub App user-to-server token (expiration **disabled**) | `ghu_` | No | No |
| Fine-grained PAT | `github_pat_` | Yes — user-set, up to 1 year | No |

If SteleKit uses a **classic OAuth App** (the typical choice for a third-party app without a server component), issued tokens do **not** expire. No refresh flow is needed; just store the token securely and reuse it.

If SteleKit ever migrates to a **GitHub App** with token expiration enabled, the app must implement the refresh token flow (`POST /login/oauth/access_token` with `grant_type=refresh_token`) before the 8-hour window closes, and must handle refresh token expiry (6 months) by re-triggering device flow.

### 1.5 SSO / SAML-enforced organizations

**Symptom**: Token is issued successfully, but API calls to a private repo under an SSO-enforced org return 403 or omit org resources silently.

**Cause**: Classic OAuth tokens must be explicitly authorized for each SAML SSO organization by the user after the token is issued. This is a browser-based action on `github.com/settings/tokens` — it cannot be done programmatically. The token works for personal repos but not the org's repos.

**Mitigation**:
- After token issuance, make a test call to `GET /user` and `GET /user/orgs`. If the target org does not appear in the org list, surface a message: "If your repository belongs to an organization with SSO enabled, you may need to authorize this app at github.com/settings/connections/applications/{client_id}."
- Fine-grained PATs handle SSO at creation time and do not have this post-issuance gap.

### 1.6 Public `client_id` without a client secret

Device flow is designed for public clients. GitHub explicitly states `client_secret` is **not used** and **not needed** in device flow. The `client_id` is safe to embed in the distributed binary. There are no rate limits specific to the `client_id` beyond the per-app polling limits described in 1.2.

### 1.7 `expires_in` timing edge case

The 900 s clock starts when the device code is **issued** by GitHub, not when the user starts entering it. If your app shows a waiting screen before the user acts, the effective window is shorter. Show a countdown timer derived from `(issued_at + expires_in) - now` rather than a fixed "15 minutes" label. Reset the entire flow (re-request device code) on `expired_token`.

---

## 2. Android SAF URI Persistence **(A)**

### 2.1 `ACTION_OPEN_DOCUMENT` vs `ACTION_OPEN_DOCUMENT_TREE`

| Intent | Selects | Persistence mechanism | Typical use |
|---|---|---|---|
| `ACTION_OPEN_DOCUMENT` | A single file | `takePersistableUriPermission()` | Opening one config file, one SSH key |
| `ACTION_OPEN_DOCUMENT_TREE` | An entire directory tree | `takePersistableUriPermission()` | Selecting a git repository root |

Both require calling `ContentResolver.takePersistableUriPermission(uri, flags)` before the app exits; without this call the grant is **session-only** and dies on reboot.

Flags for read+write:
```kotlin
val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
contentResolver.takePersistableUriPermission(uri, flags)
```

For a git repository directory, always request both flags — write is needed for commits.
For an SSH key, consider read-only (`FLAG_GRANT_READ_URI_PERMISSION` only) and copy the content to app-private storage (see 2.4).

### 2.2 URI invalidation conditions

Even a correctly persisted URI becomes invalid when:
1. The user **moves** the directory/file to a different location.
2. The user **deletes** the file or directory.
3. The underlying **storage volume is ejected** (SD card removal).
4. On some OEMs, after a **factory reset or user-data wipe**.

Detection: wrap every `contentResolver.openInputStream(uri)` / `DocumentFile.fromTreeUri(ctx, uri)` in a try-catch for `SecurityException` and `FileNotFoundException`. On failure, clear the stored URI and prompt the user to re-select.

```kotlin
fun isUriStillValid(ctx: Context, uri: Uri): Boolean {
    return try {
        ctx.contentResolver.openInputStream(uri)?.close()
        true
    } catch (e: Exception) {
        false
    }
}
```

Perform this check at **app startup** before attempting any git operations.

### 2.3 Persisted permission grant limit

- Android ≤ 10 (API 29): **128 grants** per app.
- Android 11+ (API 30): **512 grants** per app.

For a git client that manages multiple repositories, this limit is reachable. A user managing 130+ repos on Android 10 will hit the wall. When `takePersistableUriPermission()` fails at the limit, it throws `SecurityException` silently in some Android versions (no exception, the call is a no-op) or the subsequent access attempt fails.

**Mitigation**: before taking a new grant, call `contentResolver.getPersistedUriPermissions()` to count existing grants. If approaching the limit, prompt the user to remove unused repos or warn proactively. Use `contentResolver.releasePersistableUriPermission(uri, flags)` when a graph is removed.

### 2.4 SSH key files via SAF — copy, don't persist the URI

If the user selects an SSH private key via `ACTION_OPEN_DOCUMENT`:

- **Do not** store the `content://` URI as the long-term key reference. The file may be on external storage or a cloud provider; persistent URI access is fragile and re-opening the file each time adds latency.
- **Do** read the key bytes immediately after selection and copy them to app-private storage: `context.filesDir` or `context.getDir("ssh_keys", Context.MODE_PRIVATE)`. This removes the SAF dependency for the key entirely.
- Private keys must be stored with mode `0600` equivalent — on Android this means app-private storage only, never shared storage or a SAF-backed location accessible by other apps.
- After copying, close and release the URI — do not call `takePersistableUriPermission` for key files.

### 2.5 Restricted paths (Android 11+, API 30+)

`ACTION_OPEN_DOCUMENT_TREE` cannot grant access to:
- The root of internal storage (`/`)
- Root directories of SD card volumes
- The `Download/` directory
- `Android/data/` and `Android/obb/` subdirectories

A git repository stored at one of these paths cannot be opened. Surface a user-facing message if the selected URI resolves to a restricted path: parse `DocumentsContract.getTreeDocumentId(uri)` and check for known restricted prefixes.

### 2.6 Human-readable display path from SAF URI

SAF URIs (`content://com.android.externalstorage.documents/tree/primary%3AMyRepo`) are not human-readable. Approaches:

```kotlin
fun getDisplayPath(ctx: Context, treeUri: Uri): String? {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    // External storage: "primary:path/to/dir" or "XXXX-XXXX:path"
    return if (docId.contains(':')) {
        val parts = docId.split(':', limit = 2)
        val volume = if (parts[0] == "primary") "Internal Storage" else "SD Card (${parts[0]})"
        "$volume/${parts[1]}"
    } else {
        docId
    }
}
```

This covers `ExternalStorageProvider` URIs. Cloud provider URIs (Google Drive, Dropbox) will have provider-specific document IDs that do not map to file paths — display the `DISPLAY_NAME` column from a `ContentResolver.query()` on the URI instead.

### 2.7 `DocumentFile.canWrite()` is unreliable

Do not use `DocumentFile.canWrite()` to gate write operations. Query the `Document.COLUMN_FLAGS` column directly and check `Document.FLAG_SUPPORTS_WRITE`:

```kotlin
val cursor = contentResolver.query(
    uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null
)
val canWrite = cursor?.use {
    if (it.moveToFirst()) (it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
    else false
} ?: false
```

---

## 3. iOS Security-Scoped Bookmarks **(iOS)**

### 3.1 Directory picker setup (iOS 13+)

Use `UIDocumentPickerViewController` with `UTType.folder`:

```swift
// Swift — UIKit
let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
picker.delegate = self

// SwiftUI (iOS 14+)
// .fileImporter(isPresented:allowedContentTypes:allowsMultipleSelection:onCompletion:)
// UTType.folder is supported
```

**KMP/Compose-iOS interop note**: There is no native Compose Multiplatform equivalent for the document picker. The only path is calling UIKit/SwiftUI from the `iosMain` source set via `UIViewController` interop or `expect/actual` declarations wrapping `UIDocumentPickerViewController`. The `UIDocumentPickerDelegate.documentPicker(_:didPickDocumentsAt:)` callback (not the deprecated single-URL variant `documentPicker(_:didPickDocumentAt:)`) must be used.

**Limitation on iOS vs Android**: The Files app is the only picker surface. Users can pick from:
- On-device storage (the app's iCloud Drive folder, local device files)
- iCloud Drive
- Third-party file provider extensions (Dropbox, Google Drive if installed)

There is no way to access arbitrary filesystem paths (e.g., `~/.ssh/`) — everything must go through the document picker. For SSH keys, the user must have copied the key file into the Files app first.

### 3.2 Security-scoped bookmarks — required pattern for persistence

A URL returned from `UIDocumentPickerViewController` is only valid for the current app session. To persist access across launches:

```swift
// Step 1: Immediately after receiving the URL in delegate callback
func storeBookmark(for url: URL) throws -> Data {
    guard url.startAccessingSecurityScopedResource() else {
        throw BookmarkError.accessDenied
    }
    defer { url.stopAccessingSecurityScopedResource() }

    return try url.bookmarkData(
        options: .minimalBookmark,
        includingResourceValuesForKeys: nil,
        relativeTo: nil
    )
    // Persist `bookmarkData` to UserDefaults or app-private file
}

// Step 2: On subsequent launches, resolve the bookmark
func resolveBookmark(_ data: Data) throws -> URL {
    var isStale = false
    let url = try URL(
        resolvingBookmarkData: data,
        bookmarkDataIsStale: &isStale
    )
    if isStale {
        // Directory was moved/renamed — recreate bookmark from resolved URL
        let newData = try storeBookmark(for: url)
        // Persist newData, replacing the old bookmark
    }
    return url
}

// Step 3: Every time you access files, wrap in start/stop
func withSecurityScope(_ url: URL, block: () throws -> Void) rethrows {
    guard url.startAccessingSecurityScopedResource() else { return }
    defer { url.stopAccessingSecurityScopedResource() }
    try block()
}
```

### 3.3 `startAccessingSecurityScopedResource` — critical pitfalls

**Pitfall 1: Unbalanced calls**
Every `startAccessingSecurityScopedResource()` that returns `true` **must** have a matching `stopAccessingSecurityScopedResource()` call. The system maintains a reference count. Unbalanced start calls lead to resource leaks; the sandbox kernel extension holds open file descriptors until the process exits. Use `defer` unconditionally.

**Pitfall 2: Calling `stop` too early**
If `stop` is called while file operations are still in progress (e.g., on a background thread reading file contents), subsequent reads will fail with `EPERM`. Ensure the security scope encompasses the entire I/O operation, not just the URL resolution step.

**Pitfall 3: Scope is not re-entrant across threads**
The security scope is process-wide but the reference count is per-call. If two coroutines both call `start` on the same URL, both must also call `stop`. Do not assume one `start` covers multiple concurrent accesses from different threads.

**Pitfall 4: `startAccessingSecurityScopedResource()` returns `false`**
This occurs when the URL is already within the app's container (no sandbox restriction applies) — in that case `false` is safe to ignore. But it also occurs if the security-scoped bookmark has become invalid (directory deleted, volume unmounted). Always check the return value and handle `false` gracefully outside the app-container case.

**Pitfall 5: Stale bookmarks**
When `resolvingBookmarkData:bookmarkDataIsStale:` sets `isStale = true`, the resolved URL is still valid for the current session, but you must regenerate and persist the bookmark data immediately. Failure to do so means the next launch will fail to resolve the bookmark entirely.

### 3.4 Entitlements required

For **sandboxed macOS** targets (not iOS — iOS apps are always sandboxed but the entitlement model differs):
- Add `com.apple.security.files.user-selected.read-write` to the entitlements file.
- This entitlement is required for sandboxed apps to read/write user-selected files. Without it, `NSOpenPanel`/`UIDocumentPickerViewController`-returned URLs cannot be accessed.

For **iOS** targets:
- No explicit entitlement declaration is needed for `UIDocumentPickerViewController` file access.
- iCloud Drive access requires `com.apple.developer.icloud-services` and `com.apple.developer.ubiquity-container-identifiers` in the entitlements, plus `NSUbiquitousContainers` in `Info.plist`.

---

## 4. JVM Headless Environment Detection **(JVM)**

### 4.1 Correct headless check

Always guard `JFileChooser` construction (not just `showOpenDialog`) with:

```kotlin
import java.awt.GraphicsEnvironment

fun isHeadless(): Boolean = GraphicsEnvironment.isHeadless()
```

`GraphicsEnvironment.isHeadless()` checks the system property `java.awt.headless` and whether a `GraphicsDevice` is available. It must be called before any AWT/Swing class construction — some AWT classes (including `JFileChooser`) throw `HeadlessException` on instantiation, not just on `show`.

System property override (for CI overrides):
```
-Djava.awt.headless=true
```

### 4.2 Headless fallback

In headless environments (CI, SSH sessions without X11 forwarding, servers), display a text prompt instead:

```kotlin
fun chooseDirectoryPath(): String? {
    return if (GraphicsEnvironment.isHeadless()) {
        print("Enter path to git repository: ")
        System.out.flush()
        readLine()?.takeIf { it.isNotBlank() }
    } else {
        showJFileChooser()
    }
}
```

Do not attempt to catch `HeadlessException` as the fallback trigger — check `isHeadless()` eagerly. `HeadlessException` can also be thrown from the AWT event pump initialization, which may occur on a different thread.

### 4.3 EDT requirement for `JFileChooser`

`JFileChooser` is a Swing component and must be created and shown on the **Event Dispatch Thread (EDT)**. Off-EDT instantiation can cause race conditions and display corruption.

Use `invokeAndWait` (not `invokeLater`) when the calling code needs to block for the result:

```kotlin
import javax.swing.SwingUtilities
import javax.swing.JFileChooser
import java.io.File

fun showDirectoryChooser(): File? {
    var selected: File? = null
    if (SwingUtilities.isEventDispatchThread()) {
        // Already on EDT — call directly
        selected = buildAndShowChooser()
    } else {
        SwingUtilities.invokeAndWait {
            selected = buildAndShowChooser()
        }
    }
    return selected
}

private fun buildAndShowChooser(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Select Git Repository"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}
```

**`invokeLater` pitfall**: Using `invokeLater` and then trying to read `chooser.selectedFile` outside the lambda will return `null` or stale data because the dialog has not yet been shown. `invokeAndWait` is required for synchronous result retrieval.

**Known JDK deadlock (JDK-6744953, JDK-6684954, JDK-6789084)**: Calling `rescanCurrentDirectory()` on `JFileChooser` from the EDT while another operation is pending can deadlock. Do not call `rescanCurrentDirectory()` programmatically; let the dialog manage its own filesystem scanning.

### 4.4 macOS-specific `JFileChooser` issues

**Only the first instance appears (some macOS JDK versions)**: On certain macOS + JDK combinations, only the first `JFileChooser` dialog in the process lifetime displays. Subsequent instances silently fail to show. This is a known bug in the AWT macOS peer implementation. Workaround: use `java.awt.FileDialog` (which delegates to native `NSOpenPanel`) instead of `JFileChooser` on macOS.

```kotlin
import java.awt.FileDialog
import java.awt.Frame

fun showNativeDirectoryChooser(): String? {
    // System property enables directory mode on macOS
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    val dialog = FileDialog(null as Frame?, "Select Git Repository", FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.directory?.let { dir ->
        dialog.file?.let { file -> "$dir$file" }
    }.also {
        System.clearProperty("apple.awt.fileDialogForDirectories")
    }
}
```

`apple.awt.fileDialogForDirectories=true` enables directory-only mode in `java.awt.FileDialog` on macOS. This uses the native `NSOpenPanel` under the hood, which respects TCC properly.

**TCC (Transparency, Consent, and Control) / Gatekeeper restrictions on macOS Catalina+**:
- A `.app` bundle using a **script-based `CFBundleExecutable`** (e.g., a shell wrapper launching a JAR) does not receive TCC prompts. The system cannot attribute file access to the bundle, so access to Desktop, Documents, and Downloads is silently denied — no permission dialog appears.
- The fix is to use a native binary launcher (e.g., a compiled C stub or a proper Java launcher binary) as `CFBundleExecutable`, not a shell script.
- For **unsandboxed** `.app` bundles with a proper native executable, Gatekeeper prompts for Desktop/Documents/Downloads on first access. No special entitlement is required for unsandboxed apps — just the native binary launcher.
- For **sandboxed** `.app` bundles (required for Mac App Store), add the entitlement `com.apple.security.files.user-selected.read-write` and use `NSOpenPanel` (via `java.awt.FileDialog` or JNA) so the system associates the file access with a user-initiated action.
- Full Disk Access (`System Settings → Privacy & Security → Full Disk Access`) bypasses TCC restrictions as a last-resort workaround for advanced users, but should not be the instructed path.

**`java.awt.FileDialog` directory limitation**: On macOS, `FileDialog` in `LOAD` mode only selects files, not directories, unless `apple.awt.fileDialogForDirectories=true` is set. This property is macOS-specific and has no effect on other platforms — set it conditionally:

```kotlin
val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
if (isMacOS) System.setProperty("apple.awt.fileDialogForDirectories", "true")
```

---

## Summary Risk Matrix

| # | Risk | Platform | Severity | Retryable |
|---|---|---|---|---|
| 1.1a | `slow_down` — interval not incremented cumulatively | X | High | Yes |
| 1.1b | `expired_token` — not restarting device code flow | X | High | Restart |
| 1.1c | `access_denied` — treated as retryable | X | Medium | No |
| 1.2 | 10-token-per-scope revocation not detected | X | Medium | N/A |
| 1.3 | 5xx treated as `authorization_pending` | X | Medium | Back off |
| 1.4 | GitHub App 8-hour token expiry without refresh flow | X | High | Yes (refresh) |
| 1.5 | SSO org access silently missing post-issuance | X | Medium | Manual auth |
| 1.7 | Timer shown as fixed "15 min" vs live countdown | X | Low | N/A |
| 2.2 | Stale SAF URI crashes on app startup | A | High | Re-prompt |
| 2.3 | Permission grant limit hit silently | A | High | Release old grants |
| 2.4 | SSH private key stored as persistent SAF URI | A | High | Copy to app-private |
| 2.5 | Restricted path (`Download/`, root) selected | A | Medium | Show error |
| 2.6 | Raw `content://` URI shown in UI | A | Low | Parse display name |
| 2.7 | `DocumentFile.canWrite()` returns wrong result | A | Medium | Query COLUMN_FLAGS |
| 3.3a | Unbalanced `start`/`stop` security scope calls | iOS | High | Fix code |
| 3.3b | `stop` called before background I/O completes | iOS | High | Fix code |
| 3.3c | Stale bookmark not regenerated on `isStale=true` | iOS | Medium | Regenerate |
| 3.3d | `startAccessingSecurityScopedResource` returns `false` silently | iOS | High | Handle gracefully |
| 3.1 | SSH key inaccessible (not in Files app) | iOS | Medium | UX guidance |
| 4.1 | `JFileChooser` constructed before headless check | JVM | High | Fix code |
| 4.3a | `invokeLater` used instead of `invokeAndWait` → null result | JVM | High | Fix code |
| 4.3b | `rescanCurrentDirectory()` deadlock (JDK-6744953) | JVM | Medium | Don't call it |
| 4.4a | Only first `JFileChooser` appears on macOS | JVM | High | Use `FileDialog` on macOS |
| 4.4b | TCC denial silent due to script-based launcher | JVM/macOS | High | Use native binary launcher |
