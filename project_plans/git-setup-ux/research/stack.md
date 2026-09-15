# Stack Research: Git Setup UX

## 1. KMP/Compose Multiplatform File Picker APIs

### 1.1 FileKit (vinceglb/FileKit)

**Repo**: https://github.com/vinceglb/FileKit  
**Latest version**: 0.14.1 (as of May 2026)  
**Status**: Actively maintained (1 500+ stars, 1 179+ commits)

#### Platform Support

Android, iOS, macOS, JVM (Windows/macOS/Linux), JS, WASM — all targets.

#### Module Structure

| Artifact | Purpose |
|---|---|
| `io.github.vinceglb:filekit-core:0.14.1` | `PlatformFile` abstraction, file read/write/utils |
| `io.github.vinceglb:filekit-dialogs:0.14.1` | File/directory pickers without Compose dependency |
| `io.github.vinceglb:filekit-dialogs-compose:0.14.1` | Same + Composable wrappers |
| `io.github.vinceglb:filekit-coil:0.14.1` | Coil 3 integration for image loading |

#### Directory Picker API

The directory picker is a first-class feature:

```kotlin
// Suspend call — returns PlatformFile? (null if user cancels)
val directory: PlatformFile? = FileKit.openDirectoryPicker()

// With initial directory and dialog settings
val directory = FileKit.openDirectoryPicker(
    directory = PlatformFile("/initial/path"),
    dialogSettings = FileKitDialogSettings.createDefault(),
)
```

`PlatformFile` wraps a platform path. On JVM you get the `java.io.File` underneath.

**JS/WASM caveat**: Uses `webkitdirectory` browser input; directory is reconstructed as a virtual tree from file relative paths. Empty directories are not available.

#### Compose Integration

`filekit-dialogs-compose` provides Composable wrappers that can be triggered via state:

```kotlin
// Composable-style (state-driven)
val coroutineScope = rememberCoroutineScope()
Button(onClick = {
    coroutineScope.launch {
        val dir = FileKit.openDirectoryPicker()
        // handle dir
    }
}) { Text("Pick directory") }
```

The suspend function `FileKit.openDirectoryPicker()` can be called from any coroutine; no special Composable wrapper is required for the directory picker.

#### Platform-Specific Setup

**Android** (only needed for `filekit-dialogs`; not needed for `filekit-dialogs-compose`):

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileKit.init(this)  // registers ActivityResultRegistry
    }
}
```

**JVM** (required — needed to locate app data directories):

```kotlin
fun main() {
    FileKit.init(appId = "SteleKit")
    application { Window(...) { ... } }
}
```

**Linux**: Add to `build.gradle.kts`:

```kotlin
compose.desktop {
    application {
        nativeDistributions {
            linux { modules("jdk.security.auth") }
        }
    }
}
```

#### JVM Dialog Settings (Title, Parent Window)

```kotlin
val settings = FileKitDialogSettings(
    title = "Choose graph folder",
    parentWindow = window,          // ComposeWindow reference
    macOS = FileKitMacOSSettings(
        canCreateDirectories = true
    )
)
val dir = FileKit.openDirectoryPicker(dialogSettings = settings)
```

In a KMP project, expose `FileKitDialogSettings` via `expect/actual`:

```kotlin
// commonMain
expect fun createDialogSettings(): FileKitDialogSettings

// jvmMain
actual fun createDialogSettings(): FileKitDialogSettings =
    FileKitDialogSettings(title = "Choose graph folder", parentWindow = window)

// androidMain, jsMain
actual fun createDialogSettings(): FileKitDialogSettings =
    FileKitDialogSettings.createDefault()
```

#### Dependencies (JVM)

Uses JNA for native dialogs on Windows/macOS/Linux (via nativefiledialog and IFileDialogImp). On Linux, uses XDG Desktop Portal / DBus. ProGuard rules needed if minifying:

```
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.freedesktop.dbus.** { *; }
-keep class io.github.vinceglb.filekit.dialogs.platform.xdg.** { *; }
```

**Verdict**: FileKit is the clear recommendation. It is Compose-first, actively maintained, supports all SteleKit targets, and `openDirectoryPicker()` is a simple suspend call.

---

### 1.2 mpfilepicker (Wavesonics/compose-multiplatform-file-picker)

**Repo**: https://github.com/Wavesonics/compose-multiplatform-file-picker  
**Status**: ARCHIVED (June 2024). The README itself recommends migrating to FileKit.  
**Latest release**: 3.1.0 (December 2023)

#### API Surface (historical reference only)

```kotlin
// State-driven Composable widgets
var showDirPicker by remember { mutableStateOf(false) }
DirectoryPicker(showDirPicker) { path ->
    showDirPicker = false
    // path: String?
}
```

#### Platform Support

JVM (Windows/macOS/Linux via TinyFileDialogs), Android, iOS, macOS (native), JS.

**Verdict**: Do not use. Archived, no longer maintained. Use FileKit instead.

---

### 1.3 Compose Multiplatform Built-In File Picker

As of mid-2025, JetBrains Compose Multiplatform does **not** include a multiplatform file/directory picker API in the official `compose-multiplatform` library. The desktop target (`compose.desktop`) does expose `java.awt.FileDialog` and `javax.swing.JFileChooser` through the AWT/Swing bridge (since Compose Desktop runs on the JVM), but these are:

- JVM-only — not shared to Android, iOS, or JS source sets
- Not Compose idioms — they block the calling thread and must be dispatched to the AWT EDT

There is no `expect/actual` multiplatform abstraction provided by JetBrains for file picking; libraries like FileKit exist to fill this gap.

---

### 1.4 JVM `JFileChooser` DIRECTORIES_ONLY Pattern

For cases where FileKit cannot be used (e.g., inside `jvmMain` only) or as a fallback, the standard pattern is:

```kotlin
import java.awt.Window
import java.util.concurrent.CompletableFuture
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun pickDirectory(
    initialPath: String? = null,
    parentWindow: Window? = null
): java.io.File? = withContext(Dispatchers.IO) {
    val future = CompletableFuture<java.io.File?>()
    SwingUtilities.invokeLater {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose graph folder"
            isAcceptAllFileFilterUsed = false
            initialPath?.let { currentDirectory = java.io.File(it) }
        }
        val result = chooser.showOpenDialog(parentWindow)
        future.complete(
            if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        )
    }
    future.get()  // blocks Dispatchers.IO thread, not main thread
}
```

**Notes**:
- `SwingUtilities.invokeLater` queues the dialog on the AWT Event Dispatch Thread (EDT), which is required — all Swing/AWT operations must run on the EDT.
- `CompletableFuture.get()` blocks a `Dispatchers.IO` thread (not the coroutine's continuation), so this is safe to call from a coroutine via `withContext(Dispatchers.IO)`.
- Alternatively, use `suspendCoroutine` + `SwingUtilities.invokeLater` to avoid blocking a thread entirely, but for a short-lived dialog operation `CompletableFuture` is simpler.
- On macOS, to get the native system look (including dark mode), set before calling:
  ```kotlin
  System.setProperty("apple.awt.application.appearance", "system")
  ```
  This must be set before the AWT toolkit initialises (i.e., in `main()` before any window creation).

**FileKit does this internally** — prefer FileKit over raw `JFileChooser`.

---

## 2. GitHub Device Flow HTTP API

Source: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow

### 2.1 Overview

The OAuth 2.0 Device Authorization Grant ([RFC 8628](https://tools.ietf.org/html/rfc8628)) allows public clients (no `client_secret` needed for polling) to authenticate without a browser on the device running the app. The user authorises on a separate browser (phone, laptop).

Device flow must be **enabled** in the OAuth app's settings on GitHub before it can be used.

### 2.2 Step 1 — Request device and user codes

```
POST https://github.com/login/device/code
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id=<CLIENT_ID>&scope=repo
```

**Request parameters**:

| Parameter | Required | Description |
|---|---|---|
| `client_id` | Yes | OAuth app's client ID |
| `scope` | No | Space-delimited scope list |

**Response** (`Accept: application/json`):

```json
{
  "device_code": "3584d83530557fdd1f46af8289938c8ef79f9dc5",
  "user_code": "WDJB-MJHT",
  "verification_uri": "https://github.com/login/device",
  "expires_in": 900,
  "interval": 5
}
```

| Field | Type | Description |
|---|---|---|
| `device_code` | string | 40-char opaque code; used when polling for the token |
| `user_code` | string | 8-char code with hyphen (e.g. `WDJB-MJHT`); shown to the user |
| `verification_uri` | string | Always `https://github.com/login/device` |
| `expires_in` | integer | Seconds until `device_code` and `user_code` expire (default 900 = 15 min) |
| `interval` | integer | Minimum seconds between polling requests (default 5) |

### 2.3 Step 2 — Show user the code

Display `user_code` in your UI and direct the user to open `verification_uri` (`https://github.com/login/device`) in a browser. They type the code there and click Authorize.

### 2.4 Step 3 — Poll for the access token

```
POST https://github.com/login/oauth/access_token
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id=<CLIENT_ID>&device_code=<DEVICE_CODE>&grant_type=urn:ietf:params:oauth:grant-type:device_code
```

**Request parameters**:

| Parameter | Required | Description |
|---|---|---|
| `client_id` | Yes | OAuth app's client ID (`client_secret` is NOT needed) |
| `device_code` | Yes | The `device_code` from Step 1 |
| `grant_type` | Yes | Must be `urn:ietf:params:oauth:grant-type:device_code` |

**Success response**:

```json
{
  "access_token": "gho_16C7e42F292c6912E7710c838347Ae178B4a",
  "token_type": "bearer",
  "scope": "repo"
}
```

**Error responses** (still HTTP 200, `error` field in JSON body):

| Error | Description | Action |
|---|---|---|
| `authorization_pending` | User hasn't approved yet | Wait `interval` seconds, poll again |
| `slow_down` | Polling too fast | Add 5 s to current interval, then poll again; response also contains updated `interval` |
| `expired_token` | `device_code` expired (>15 min) | Restart from Step 1 |
| `access_denied` | User clicked Cancel | Abort; cannot reuse the code |
| `unsupported_grant_type` | Wrong `grant_type` string | Fix the request |
| `incorrect_client_credentials` | Wrong `client_id` | Fix the `client_id`; no `client_secret` is required or accepted |
| `incorrect_device_code` | Invalid `device_code` | Fix the code or restart from Step 1 |
| `device_flow_disabled` | App has device flow disabled | Enable in app settings |

### 2.5 Polling Logic (Kotlin pseudocode)

```kotlin
suspend fun pollForToken(
    clientId: String,
    deviceCode: String,
    expiresIn: Int,
    initialInterval: Int
): String? {
    var interval = initialInterval
    val deadline = System.currentTimeMillis() + expiresIn * 1000L
    while (System.currentTimeMillis() < deadline) {
        delay(interval * 1000L)
        val response = postTokenRequest(clientId, deviceCode)
        when (response.error) {
            null -> return response.accessToken      // success
            "authorization_pending" -> continue      // keep waiting
            "slow_down" -> interval += 5             // back off; response.interval is updated too
            "expired_token" -> return null           // must restart
            "access_denied" -> return null           // user cancelled
            else -> throw GitHubAuthException(response.error)
        }
    }
    return null  // timed out locally
}
```

### 2.6 Scopes for Repository Access

| Scope | Access Granted |
|---|---|
| `repo` | Full access to public **and private** repositories (code, commit statuses, webhooks, deployments). Also grants org project/team access. |
| `public_repo` | Read/write to public repos only |
| `read:user` | Read user profile data (username, avatar, etc.) |
| `user:email` | Read user's email addresses |

**For cloning/pushing private repos**: `repo` scope is required.

**Minimum recommended scope for a Git remote tool**: `repo` (covers both public and private).

Multiple scopes are space-delimited: `scope=repo+read:user` or `scope=repo%20read%3Auser`.

### 2.7 GitHub CLI Client ID

The GitHub CLI (`gh`) uses client ID **`178c6fc778ccc68e1d6a`** (confirmed in source: `cli/cli/internal/authflow/flow.go`). The CLI requests minimum scopes `["repo", "read:org", "gist"]`.

The `client_secret` (`34ddeff2b558a23d38fba8a6de74f086ede1cc0b`) is embedded in the open-source CLI binary — GitHub documents that for public clients using device flow, the secret is not used for security; it is merely a legacy field. Do not embed it in SteleKit; use only `client_id` for device flow polling.

### 2.8 Token Expiry

GitHub OAuth tokens obtained via device flow do **not** expire by default (they are long-lived until revoked). The `expires_in` field in the Step 1 response refers only to the expiry of the device/user code (15 minutes), not the resulting access token. The access token itself has no expiration unless token expiration is enabled on the OAuth app's settings page.

### 2.9 Rate Limits

- **Code submission**: 50 submissions per hour per app (on the browser side)
- **Polling**: Poll at no faster than `interval` seconds; violations trigger `slow_down` which adds 5 s permanently to the interval for that session

### 2.10 Sources

- GitHub Docs — Authorizing OAuth apps (Device Flow): https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow
- GitHub Docs — Scopes for OAuth apps: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps
- RFC 8628 — OAuth 2.0 Device Authorization Grant: https://tools.ietf.org/html/rfc8628
- GitHub CLI source (client_id confirmation): https://github.com/cli/cli/blob/trunk/internal/authflow/flow.go
