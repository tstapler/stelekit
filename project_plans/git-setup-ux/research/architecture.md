# Architecture Research: git-setup-ux

## 1. Android File Picker — `PlatformFileSystem` (Android)

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

### `onPickDirectory` callback

Defined as a private field, wired via the `init()` method called from `MainActivity`:

```kotlin
private var onPickDirectory: (suspend () -> String?)? = null

fun init(context: Context, onPickDirectory: (suspend () -> String?)? = null) {
    this.context = context
    this.onPickDirectory = onPickDirectory
    // also restores persisted SAF URI from SharedPreferences
}
```

`pickDirectoryAsync()` invokes the callback and then refreshes internal SAF state from SharedPreferences after a successful pick:

```kotlin
actual override suspend fun pickDirectoryAsync(): String? {
    val result = onPickDirectory?.invoke() ?: return null
    // ... refreshes treeUri / treeRootDocId from SharedPreferences after pick
    return result
}

actual override fun pickDirectory(): String? = null // Handled via pickDirectoryAsync on Android
```

### `onPickFile` for single files — does NOT exist

There is no `onPickFile` callback for picking a single file. The only file-picking callbacks are:
- `onPickDirectory: (suspend () -> String?)?` — picks a SAF tree URI (directory)
- `onPickSaveFile: (suspend (suggestedName: String, mimeType: String) -> String?)?` — picks a save destination via `ACTION_CREATE_DOCUMENT`

The `onPickSaveFile` callback is registered separately via:

```kotlin
fun initSaveFilePicker(onPickSaveFile: suspend (suggestedName: String, mimeType: String) -> String?) {
    this.onPickSaveFile = onPickSaveFile
}

override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? {
    return onPickSaveFile?.invoke(suggestedName, mimeType)
}
```

**A new `onPickFile` callback would need to be added** to support picking a single file (e.g. an SSH private key). The pattern would mirror `onPickDirectory`: a private field, a public `initFilePicker(callback)` method called from MainActivity, and a `suspend fun pickFileAsync(): String?` method on the interface.

### SAF URI to displayable path conversion

`displayNameForPath()` converts a `saf://...` path to a human-readable folder name using `DocumentFile.fromTreeUri()`:

```kotlin
override fun displayNameForPath(path: String): String {
    if (path.startsWith("content://")) return displayNameForContentUri(path)
    if (!path.startsWith("saf://")) return super.displayNameForPath(path)
    return try {
        val ctx = context ?: return super.displayNameForPath(path)
        val resolvedTreeUri = treeUri ?: return super.displayNameForPath(path)
        DocumentFile.fromTreeUri(ctx, resolvedTreeUri)?.name
            ?: super.displayNameForPath(path)
    } catch (_: Exception) { super.displayNameForPath(path) }
}
```

The `getLibraryDisplayName()` method also returns the folder display name even when the permission is revoked (reads from SharedPreferences):

```kotlin
override fun getLibraryDisplayName(): String? {
    val ctx = context ?: return null
    val uri = treeUri
    if (uri == null) {
        val str = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAF_TREE_URI, null) ?: return null
        return try { DocumentFile.fromTreeUri(ctx, Uri.parse(str))?.name } catch (_: Exception) { null }
    }
    return try { DocumentFile.fromTreeUri(ctx, uri)?.name } catch (_: SecurityException) { null }
}
```

The internal `toSafRoot(treeUri)` method converts a `Uri` to a `saf://` path by percent-encoding. The stored `treeUri` field is always the original Android `Uri` from the picker — it is never reconstructed from the encoded `saf://` string to avoid decoding ambiguity.

### Full class signature (Android)

```kotlin
actual class PlatformFileSystem actual constructor() : FileSystem {
    private var context: Context? = null
    private var onPickDirectory: (suspend () -> String?)? = null
    private var onPickSaveFile: (suspend (suggestedName: String, mimeType: String) -> String?)? = null

    fun init(context: Context, onPickDirectory: (suspend () -> String?)? = null)
    fun initSaveFilePicker(onPickSaveFile: suspend (suggestedName: String, mimeType: String) -> String?)
    fun getStoredTreeUri(): Uri?
    fun isSafPermissionValid(context: Context, uri: Uri): Boolean  // companion object
    fun toSafRoot(treeUri: Uri): String  // companion object

    actual override fun pickDirectory(): String?  // always null on Android
    actual override suspend fun pickDirectoryAsync(): String?  // invokes onPickDirectory
    override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String?
    // ... all FileSystem interface methods
}
```

---

## 2. Desktop File Picker — `DesktopFilePicker.kt` and `PlatformFileSystem.kt` (JVM)

### `DesktopFilePicker` object

**File:** `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/sensor/DesktopFilePicker.kt`

This is an **image-only** file picker. It uses `JFileChooser.FILES_ONLY` mode filtered to JPEG/PNG:

```kotlin
object DesktopFilePicker {
    suspend fun pickImageFile(): Either<DomainError.SensorError, PlatformImageFile>
}
```

Invocation pattern:
- Calls `UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())` for native styling
- Runs dialog on AWT EDT via `SwingUtilities.invokeLater`
- Blocks the IO thread using `CountDownLatch(1)` + `done.await()`
- Wraps entire call in `withContext(Dispatchers.IO)`
- Returns `Either.Left(CaptureFailed)` on cancel, `Either.Left(HardwareUnavailable)` in headless environments
- `fileSelectionMode = JFileChooser.FILES_ONLY`, `isMultiSelectionEnabled = false`

This picker is **not suitable for reuse as a generic file picker** — it only handles image files and returns `PlatformImageFile`.

### Directory picking in `PlatformFileSystem` (JVM)

**File:** `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

```kotlin
actual override fun pickDirectory(): String? {
    // Synchronous — only safe on AWT EDT
    val future = CompletableFuture<String?>()
    SwingUtilities.invokeLater {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        val result = chooser.showOpenDialog(null)
        future.complete(if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null)
    }
    return future.get()?.also { registerGraphRoot(it) }
}

actual override suspend fun pickDirectoryAsync(): String? {
    return withContext(Dispatchers.IO) {
        val future = CompletableFuture<String?>()
        SwingUtilities.invokeLater {
            val chooser = JFileChooser()
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            val result = chooser.showOpenDialog(null)
            future.complete(if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null)
        }
        future.get()?.also { registerGraphRoot(it) }
    }
}
```

**Key points:**
- `pickDirectory()` uses `CompletableFuture.get()` (blocks calling thread) — comment warns this is only safe on the AWT EDT, not inside a Compose coroutine dispatcher
- `pickDirectoryAsync()` wraps in `withContext(Dispatchers.IO)` to avoid corrupting Compose coroutine continuation state (JFileChooser creates a nested AWT event loop)
- Both use `JFileChooser.DIRECTORIES_ONLY`
- Both call `registerGraphRoot(it)` on success (whitelists path in `JvmFileSystemBase`)
- Save file dialog (`pickSaveFileAsync`) uses the default mode (files), pre-populates with `suggestedName` in the Downloads path

**There is no `pickFile()` / `pickFileAsync()` for picking a single file (e.g. SSH key) on desktop.** Adding one would follow the same pattern as `pickDirectoryAsync()` but with `fileSelectionMode = JFileChooser.FILES_ONLY` and an optional `FileFilter`.

### `pickSaveFileAsync` (JVM)

```kotlin
override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? {
    return withContext(Dispatchers.IO) {
        val future = CompletableFuture<String?>()
        SwingUtilities.invokeLater {
            val chooser = JFileChooser()
            chooser.selectedFile = java.io.File(getDownloadsPath(), suggestedName)
            val result = chooser.showSaveDialog(null)
            future.complete(if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null)
        }
        future.get()
    }
}
```

---

## 3. `GitAuthType` enum

**File:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitConfig.kt`

The enum is defined inline in `GitConfig.kt` (no separate file):

```kotlin
enum class GitAuthType { NONE, SSH_KEY, HTTPS_TOKEN }
```

No associated properties or methods — plain enum, 3 values.

`GitAuthType` is a field on `GitConfig`:

```kotlin
@Serializable
data class GitConfig(
    val graphId: String,
    val repoRoot: String,
    val wikiSubdir: String,
    val remoteName: String = "origin",
    val remoteBranch: String = "main",
    val authType: GitAuthType,
    val sshKeyPath: String? = null,
    val sshKeyPassphraseKey: String? = null,   // key into CredentialStore
    val httpsTokenKey: String? = null,          // key into CredentialStore
    val pollIntervalMinutes: Int = 5,
    val autoCommit: Boolean = true,
    val commitMessageTemplate: String = "SteleKit: {date}",
)

val GitConfig.wikiRoot: String get() = if (wikiSubdir.isEmpty()) repoRoot else "$repoRoot/$wikiSubdir"
```

---

## 4. `CredentialStore` usage in `GitSetupScreen`

**File:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`

### CredentialStore instantiation and key patterns

```kotlin
val credentialStore = remember { CredentialStore() }
```

**Credential key patterns used:**

| Credential | Key pattern | When stored |
|---|---|---|
| HTTPS token (test) | `"git_https_token_$graphId"` | On "Test Connection" click when `authType == HTTPS_TOKEN && httpsToken.isNotBlank()` |
| SSH passphrase (test) | `"git_ssh_passphrase_$graphId"` | On "Test Connection" click when `authType == SSH_KEY && sshPassphrase.isNotBlank()` |
| HTTPS token (save, existing graph) | `"git_https_token_$graphId"` | On save when `authType == HTTPS_TOKEN && httpsToken.isNotBlank()` |
| SSH passphrase (save, existing graph) | `"git_ssh_passphrase_$graphId"` | On save when `authType == SSH_KEY && sshPassphrase.isNotBlank()` |
| HTTPS token (save, new clone) | `"git_https_token_$newGraphId"` | After clone, using `newGraphId` returned by `onCloneAndAdd` |
| SSH passphrase (save, new clone) | `"git_ssh_passphrase_$newGraphId"` | After clone, using `newGraphId` |

Retrieval on initial load (pre-fill from existing config):
```kotlin
var httpsToken by remember {
    mutableStateOf(
        existingConfig?.httpsTokenKey?.let { key -> credentialStore.retrieve(key) } ?: ""
    )
}
```

SSH passphrase is **not** pre-filled from the credential store — it is always empty on open and must be re-entered.

### Auth type handling in Step 3

- `GitAuthType.NONE` — no extra fields shown
- `GitAuthType.SSH_KEY` — shows `sshKeyPath` text field (plain text, tilde-expandable) and `sshPassphrase` password field with visibility toggle
- `GitAuthType.HTTPS_TOKEN` — shows `httpsToken` password field with visibility toggle, plus note about encryption

**The SSH key path is currently a plain text field only** — there is no browse/picker button for selecting the `.ssh/id_ed25519` file path. This is a gap the git-setup-ux feature is intended to address.

### 5-step wizard structure

| Step | Name | What it does |
|---|---|---|
| 1 | Repository mode | Radio: "Use existing clone" vs "Clone a remote repository" |
| 2 | Repository path | Text fields for `repoRoot`, optional `cloneUrl` (if cloning), optional `wikiSubdir` |
| 3 | Authentication | Radio for `authType`; conditional fields for SSH key path + passphrase or HTTPS token |
| 4 | Sync settings | `remoteBranch` text field; `pollIntervalMinutes` radio (Off / 5m / 15m / 30m / 1h) |
| 5 | Test & save | "Test connection" button; "Save configuration" button; clone progress display |

### Local state variables

```kotlin
var step: Int                     // 1–5, controls which step composable is shown
var useExistingClone: Boolean     // Step 1
var cloneUrl: String              // Step 2 (clone mode only)
var repoRoot: String              // Step 2
var wikiSubdir: String            // Step 2
var authType: GitAuthType         // Step 3
var sshKeyPath: String            // Step 3, SSH_KEY only — plain string, no picker
var sshPassphrase: String         // Step 3, SSH_KEY only — password field, not persisted in CredentialStore pre-fill
var httpsToken: String            // Step 3, HTTPS_TOKEN only — pre-filled from CredentialStore
var remoteBranch: String          // Step 4
var pollIntervalMinutes: Int      // Step 4
var testInProgress: Boolean       // Step 5
var testResult: String?           // Step 5
var testSuccess: Boolean          // Step 5
var saving: Boolean               // Step 5
var saveError: String?            // Step 5
var cloneInProgress: Boolean      // Step 5 (clone mode)
var cloneProgress: String         // Step 5 (clone mode)
var cloneError: String?           // Step 5 (clone mode)
```

---

## 5. `expect`/`actual` for `PlatformFileSystem`

### `expect` declaration

**File:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

```kotlin
expect class PlatformFileSystem() : FileSystem {
    override fun getDefaultGraphPath(): String
    override fun expandTilde(path: String): String
    override fun readFile(path: String): String?
    override fun writeFile(path: String, content: String): Boolean
    override fun listFiles(path: String): List<String>
    override fun listDirectories(path: String): List<String>
    override fun fileExists(path: String): Boolean
    override fun directoryExists(path: String): Boolean
    override fun createDirectory(path: String): Boolean
    override fun deleteFile(path: String): Boolean
    override fun pickDirectory(): String?
    override suspend fun pickDirectoryAsync(): String?
    override fun getLastModifiedTime(path: String): Long?
}
```

`FileSystem` interface (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`) is the full platform abstraction. It includes default implementations for most optional methods. Key methods relevant to file picking:

```kotlin
fun pickDirectory(): String?
suspend fun pickDirectoryAsync(): String? = pickDirectory()
suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String = "application/json"): String? = null
```

Note that `pickSaveFileAsync` and `pickFileAsync` (single file) are **not** in the `expect` class — they are defined only on the `FileSystem` interface with default implementations. This means adding a single-file picker only requires:
1. Adding a default-null `suspend fun pickFileAsync(): String? = null` to `FileSystem` interface
2. Overriding it in the Android and JVM `actual` implementations
3. Wiring the Android callback from `MainActivity`

The `expect` class does NOT need to be modified because `pickFileAsync` would be an interface extension, not an `expect`/`actual` method.

### Platform `actual` implementations

| Platform | File | Directory picker | File picker (single) |
|---|---|---|---|
| Android | `androidMain/.../PlatformFileSystem.kt` | Via `onPickDirectory` callback wired from MainActivity | Not implemented; needs `onPickFile` callback + MainActivity wiring |
| JVM/Desktop | `jvmMain/.../PlatformFileSystem.kt` | `JFileChooser(DIRECTORIES_ONLY)` via `pickDirectoryAsync` | Not implemented; needs `JFileChooser(FILES_ONLY)` in `pickFileAsync` |
| iOS | `iosMain/.../PlatformFileSystem.kt` | `pickDirectory()` returns null (stub) | Not implemented (null stub) |
| WASM/JS | `wasmJsMain/.../PlatformFileSystem.kt` | (not checked — likely stub) | Not implemented |

---

## Summary: What's Already Wired vs. What Needs to Be Added

### Already wired
- Directory picking on Android (SAF via `onPickDirectory` callback from MainActivity)
- Directory picking on Desktop (JFileChooser `DIRECTORIES_ONLY`)
- Save-file picking on Android (`ACTION_CREATE_DOCUMENT` via `onPickSaveFile`)
- Save-file picking on Desktop (JFileChooser save dialog)
- SSH key path entry in `GitSetupScreen` (plain text field, Step 3)
- Credential storage/retrieval for HTTPS tokens and SSH passphrases

### Needs to be added (for git-setup-ux feature)
1. **`FileSystem.pickFileAsync()`** — new interface method with `null` default
2. **Android implementation** — `onPickFile: (suspend () -> String?)?` field + `initFilePicker()` method + `pickFileAsync()` override + MainActivity wiring with `ACTION_OPEN_DOCUMENT` launcher
3. **Desktop implementation** — `pickFileAsync()` override using `JFileChooser(FILES_ONLY)` pattern matching `pickDirectoryAsync()`
4. **`GitSetupScreen` Step 3** — "Browse" button next to `sshKeyPath` text field that calls `fileSystem.pickFileAsync()` (requires injecting `FileSystem` into the screen, or passing a lambda callback like `onCloneAndAdd`)
