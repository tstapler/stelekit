# Git Setup UX — Implementation Plan

**Project:** git-setup-ux  
**Date:** 2026-06-12  
**Status:** Draft

---

## Technology Decisions

### File/Directory Picker: Manual JFileChooser + SAF Pattern (No FileKit)

**Decision: Do NOT add FileKit. Extend the existing `FileSystem`/`PlatformFileSystem` pattern.**

Rationale:
1. FileKit is not in `kmp/build.gradle.kts`. Adding a new third-party library is prohibited by the codebase constraints.
2. The existing codebase already has a working `pickDirectoryAsync()` on both Android (SAF `onPickDirectory` callback) and JVM (`JFileChooser` via `CompletableFuture`). Adding `pickFileAsync()` is a 30-line extension of an established pattern.
3. On macOS, use `java.awt.FileDialog` with `apple.awt.fileDialogForDirectories=true` instead of `JFileChooser` to avoid the "only first dialog appears" bug (pitfall 4.4a). The `isHeadless()` guard (pitfall 4.1) already exists in `DesktopFilePicker`.
4. Android requires the `@Composable` `rememberLauncherForActivityResult` pattern or a pre-registered callback — both of which are already wired via `PlatformFileSystem.init()` in `MainActivity`. Adding `initFilePicker()` mirrors the existing `initSaveFilePicker()`.
5. For iOS, a stub returning `null` is acceptable per requirements (iOS is explicitly scoped).

**macOS-specific JFileChooser mitigation:**

In `pickFileAsync()` and `pickDirectoryAsync()` on JVM, detect macOS and use `java.awt.FileDialog` (which calls native `NSOpenPanel`) instead of `JFileChooser`:

```kotlin
val isMacOS = System.getProperty("os.name", "").lowercase().contains("mac")
```

For directory mode on macOS: `System.setProperty("apple.awt.fileDialogForDirectories", "true")` before showing `FileDialog`, then clear it after. For file mode: use `FileDialog` in `LOAD` mode without the property.

### GitHub Device Flow: Ktor Client (Already in commonMain)

Ktor is already a dependency (`io.ktor:ktor-client-core:3.1.3`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`). GitHub Device Flow HTTP calls use Ktor's `HttpClient` in `commonMain`. No new dependencies needed.

The device flow client (`GitHubDeviceFlowClient`) lives in `commonMain` and uses the shared Ktor `HttpClient`. Platform-specific Ktor engines (OkHttp for JVM/Android, Darwin for iOS) are already registered.

---

## Architecture Overview

### New Files (to create)

| File | Layer | Purpose |
|---|---|---|
| `commonMain/.../git/GitHubDeviceFlowClient.kt` | Service | Ktor HTTP calls for device flow (Step 1 + polling) |
| `commonMain/.../git/model/GitHubDeviceFlow.kt` | Model | Data classes for device flow API responses |
| `commonMain/.../ui/screens/git/GitHubOAuthDialog.kt` | UI | Composable dialog showing user code, copy/open buttons, status |
| `jvmMain/.../platform/PlatformFilePickerHelper.kt` | Platform | macOS `FileDialog` vs `JFileChooser` selector + headless guard |

### Modified Files

| File | Change |
|---|---|
| `commonMain/.../git/model/GitConfig.kt` | Add `GITHUB_OAUTH` to `GitAuthType` enum; add `oauthTokenKey: String?` field to `GitConfig` |
| `commonMain/.../platform/FileSystem.kt` | Add `suspend fun pickFileAsync(): String? = null` default method |
| `commonMain/.../git/model/SyncState.kt` | Add `CredentialExpired` state |
| `commonMain/.../error/DomainError.kt` | Add `CredentialExpired` variant to `GitError` (for 401 re-auth trigger) |
| `commonMain/.../ui/screens/git/GitSetupScreen.kt` | Add `FileSystem` param; browse buttons in Step 2 and Step 3; GITHUB_OAUTH radio + dialog in Step 3; `GITHUB_OAUTH` branch in save/test handlers |
| `androidMain/.../platform/PlatformFileSystem.kt` | Add `onPickFile` field, `initFilePicker()` method, `pickFileAsync()` override; SSH key copy-to-app-private-storage logic |
| `androidMain/.../MainActivity.kt` (or equivalent) | Register `ACTION_OPEN_DOCUMENT` launcher; wire `PlatformFileSystem.initFilePicker()` |
| `jvmMain/.../platform/PlatformFileSystem.kt` | Add `pickFileAsync()` override using `FileDialog`/`JFileChooser` `FILES_ONLY` |
| `iosMain/.../platform/PlatformFileSystem.kt` | Add stub `override suspend fun pickFileAsync(): String? = null` |
| `wasmJsMain/.../platform/PlatformFileSystem.kt` | Add stub `override suspend fun pickFileAsync(): String? = null` |
| `commonMain/.../git/GitSyncService.kt` | Map `GITHUB_OAUTH` auth type to `GitAuth.HttpsToken`; detect 401 and emit `CredentialExpired` |
| `kmp/build.gradle.kts` | No changes (no new deps) |

---

## Epic Breakdown

### Epic 1: File/Directory Picker Integration

**Goal:** Add Browse buttons to Step 2 (repo root) and Step 3 (SSH key path) in `GitSetupScreen`, backed by platform pickers.

#### Story 1.1: `FileSystem` interface — add `pickFileAsync()`

**Tasks:**

- **T1.1.1** — Open `FileSystem.kt` (`commonMain/.../platform/FileSystem.kt`). Add:
  ```kotlin
  suspend fun pickFileAsync(): String? = null
  ```
  after `pickSaveFileAsync`. This is a default-null method so all non-implementing platforms get a safe stub.

#### Story 1.2: JVM `pickFileAsync()` implementation

**File:** `jvmMain/.../platform/PlatformFileSystem.kt`

**Tasks:**

- **T1.2.1** — Add `pickFileAsync()` override. Use macOS branch: `FileDialog(null, "Select file", FileDialog.LOAD)` when `isMacOS`, standard `JFileChooser(FILES_ONLY)` otherwise. Mirror the existing `pickDirectoryAsync()` pattern: wrap in `withContext(Dispatchers.IO)`, dispatch dialog on EDT via `SwingUtilities.invokeAndWait` (not `invokeLater` — pitfall 4.3a). Guard with `GraphicsEnvironment.isHeadless()` returning `null`.
  
  ```kotlin
  override suspend fun pickFileAsync(): String? {
      if (java.awt.GraphicsEnvironment.isHeadless()) return null
      return withContext(Dispatchers.IO) {
          var result: String? = null
          val isMacOS = System.getProperty("os.name", "").lowercase().contains("mac")
          SwingUtilities.invokeAndWait {
              result = if (isMacOS) {
                  val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Select SSH Key File", java.awt.FileDialog.LOAD)
                  dialog.isVisible = true
                  val dir = dialog.directory
                  val file = dialog.file
                  if (dir != null && file != null) "$dir$file" else null
              } else {
                  val chooser = javax.swing.JFileChooser().apply {
                      fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
                      dialogTitle = "Select SSH Key File"
                      isMultiSelectionEnabled = false
                  }
                  if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION)
                      chooser.selectedFile.absolutePath
                  else null
              }
          }
          result
      }
  }
  ```

- **T1.2.2** — Apply the same macOS `FileDialog` treatment to the existing `pickDirectoryAsync()` method on JVM (bug fix alongside the feature — pitfall 4.4a). Use `System.setProperty("apple.awt.fileDialogForDirectories", "true")` before showing and clear it after.

#### Story 1.3: Android `pickFileAsync()` implementation

**Files:** `androidMain/.../platform/PlatformFileSystem.kt`, `androidMain/.../MainActivity.kt`

**Tasks:**

- **T1.3.1** — In `PlatformFileSystem.kt (android)`, add:
  ```kotlin
  private var onPickFile: (suspend () -> String?)? = null
  
  fun initFilePicker(onPickFile: suspend () -> String?) {
      this.onPickFile = onPickFile
  }
  
  override suspend fun pickFileAsync(): String? = onPickFile?.invoke()
  ```

- **T1.3.2** — In `MainActivity` (or the file that calls `PlatformFileSystem.init()`), register an `ActivityResultLauncher` for `ActivityResultContracts.OpenDocument(arrayOf("*/*"))` using `rememberLauncherForActivityResult` during composition (or `registerForActivityResult` in `onCreate`). On result URI:
  1. Read the file bytes immediately via `contentResolver.openInputStream(uri)`.
  2. Copy bytes to `context.getDir("ssh_keys", Context.MODE_PRIVATE)` — **do NOT call `takePersistableUriPermission`** (pitfall 2.4 — SSH keys must be copied to app-private storage, not held as persistent URI references).
  3. Return the app-private file path (e.g., `context.getDir("ssh_keys", ...).absolutePath + "/" + fileName`).
  4. Wire via `platformFileSystem.initFilePicker { suspendCoroutine { cont -> launcher.launch(...); /* resume in callback */ } }`.

  **Implementation note:** Because `registerForActivityResult` must be called at composition time, use `rememberLauncherForActivityResult` in the `MainActivity`'s setContent and store it in a `CompletableDeferred<Uri?>` or `Channel<Uri?>` that the `PlatformFileSystem.onPickFile` callback reads from. Pattern already used for `onPickDirectory`.

- **T1.3.3** — Write a unit test `AndroidSshKeyPickerTest` verifying that after `pickFileAsync()`:
  - The file is written to `context.getDir("ssh_keys", ...)` not to shared storage.
  - The returned path points to the app-private copy.
  - `takePersistableUriPermission` is NOT called.

#### Story 1.4: iOS/WASM stubs

**Tasks:**

- **T1.4.1** — In `iosMain/.../platform/PlatformFileSystem.kt`, add `override suspend fun pickFileAsync(): String? = null`. (iOS SSH key UX guidance note: add a comment linking to the requirements doc about Files app prerequisite.)
- **T1.4.2** — In `wasmJsMain/.../platform/PlatformFileSystem.kt`, add `override suspend fun pickFileAsync(): String? = null`.

#### Story 1.5: Browse buttons in `GitSetupScreen`

**File:** `commonMain/.../ui/screens/git/GitSetupScreen.kt`

**Tasks:**

- **T1.5.1** — Add `fileSystem: FileSystem` parameter to `GitSetupScreen`. This is the only injection needed — the screen already uses `rememberCoroutineScope()` for launch, so calling `fileSystem.pickDirectoryAsync()` / `fileSystem.pickFileAsync()` inside `scope.launch { }` is correct and does not violate the "no rememberCoroutineScope passed to a class" rule (the scope stays in the composable, not in `fileSystem`).

- **T1.5.2** — In `Step2RepoPath`, add `onBrowseRepoRoot: (() -> Unit)?` parameter. When non-null, render a trailing icon button (`Icons.Default.FolderOpen`) inside the `repoRoot` `OutlinedTextField` or as a sibling icon button. Clicking it calls `onBrowseRepoRoot()`.

- **T1.5.3** — In the parent `GitSetupScreen`, in the `Step2RepoPath(...)` call site, wire:
  ```kotlin
  onBrowseRepoRoot = {
      scope.launch {
          val path = fileSystem.pickDirectoryAsync()
          if (path != null) repoRoot = path
      }
  }
  ```

- **T1.5.4** — In `Step3Auth`, add `onBrowseSshKey: (() -> Unit)?` parameter. When `authType == SSH_KEY` and `onBrowseSshKey != null`, render a trailing `Icons.Default.Key` icon button in the `sshKeyPath` `OutlinedTextField`.

- **T1.5.5** — Wire `onBrowseSshKey` in `GitSetupScreen`:
  ```kotlin
  onBrowseSshKey = {
      scope.launch {
          val path = fileSystem.pickFileAsync()
          if (path != null) sshKeyPath = path
      }
  }
  ```

- **T1.5.6** — Update all call sites of `GitSetupScreen` (and screenshot tests) to pass the `fileSystem` parameter. For tests, pass a `FakeFileSystem` or a `NoopFileSystem` instance that returns `null` from both picker methods (preserving test isolation — no real dialog is shown).

- **T1.5.7** — Screenshot test: verify that `Step2RepoPath` renders the browse button. Use a `FakeFileSystem` returning `null`. Capture with Roborazzi.

---

### Epic 2: GitHub OAuth Device Flow

**Goal:** Add `GITHUB_OAUTH` auth type with full device flow UI and token lifecycle.

#### Story 2.1: Model changes

**Files:** `commonMain/.../git/model/GitConfig.kt`, `commonMain/.../git/model/SyncState.kt`, `commonMain/.../error/DomainError.kt`

**Tasks:**

- **T2.1.1** — Add `GITHUB_OAUTH` to `GitAuthType`:
  ```kotlin
  enum class GitAuthType { NONE, SSH_KEY, HTTPS_TOKEN, GITHUB_OAUTH }
  ```

- **T2.1.2** — Add `oauthTokenKey: String? = null` to `GitConfig`. This is the `CredentialStore` key for the OAuth access token (pattern: `"git_github_oauth_$graphId"`). The field must have a default of `null` so existing serialized configs deserialize without error.

- **T2.1.3** — Add `CredentialExpired` to `SyncState`:
  ```kotlin
  data class CredentialExpired(val graphId: String) : SyncState()
  ```

- **T2.1.4** — Add `CredentialExpired` variant to `DomainError.GitError`:
  ```kotlin
  data class CredentialExpired(override val message: String) : GitError
  ```
  Update the `toUserMessage()` and `toSyncErrorMessage()` extension functions in `DomainError.kt` to handle the new case.

- **T2.1.5** — Update `buildConfig()` in `GitSetupScreen.kt` to pass `oauthTokenKey`:
  ```kotlin
  private fun buildConfig(..., oauthTokenKey: String? = null): GitConfig = GitConfig(
      ...,
      oauthTokenKey = oauthTokenKey,
  )
  ```

#### Story 2.2: `GitHubDeviceFlowClient` — HTTP service

**File:** `commonMain/.../git/GitHubDeviceFlowClient.kt`

**Tasks:**

- **T2.2.1** — Create data classes in `commonMain/.../git/model/GitHubDeviceFlow.kt`:
  ```kotlin
  @Serializable
  data class DeviceCodeResponse(
      @SerialName("device_code") val deviceCode: String,
      @SerialName("user_code") val userCode: String,
      @SerialName("verification_uri") val verificationUri: String,
      @SerialName("expires_in") val expiresIn: Int,
      val interval: Int,
  )
  
  @Serializable
  data class TokenPollResponse(
      @SerialName("access_token") val accessToken: String? = null,
      @SerialName("token_type") val tokenType: String? = null,
      val scope: String? = null,
      val error: String? = null,
      @SerialName("error_description") val errorDescription: String? = null,
      val interval: Int? = null,  // returned on slow_down
  )
  ```

- **T2.2.2** — Create `GitHubDeviceFlowClient`:
  ```kotlin
  class GitHubDeviceFlowClient(
      private val httpClient: HttpClient,
      private val clientId: String = GITHUB_CLIENT_ID,
  ) {
      companion object {
          const val GITHUB_CLIENT_ID = "178c6fc778ccc68e1d6a"  // GitHub CLI's public client ID
          private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
          private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
          private const val USER_API_URL = "https://api.github.com/user"
          const val SCOPE = "repo"
      }
      
      suspend fun requestDeviceCode(): Either<DomainError.GitError, DeviceCodeResponse>
      
      suspend fun pollForToken(
          deviceCode: String,
          expiresIn: Int,
          initialInterval: Int,
          onStateChange: (DeviceFlowPollState) -> Unit,
      ): Either<DomainError.GitError, String>  // returns access token string
      
      suspend fun fetchUsername(token: String): String?  // GET /user, returns login field
  }
  ```

- **T2.2.3** — Implement `requestDeviceCode()`:
  ```kotlin
  // POST https://github.com/login/device/code
  // Content-Type: application/x-www-form-urlencoded
  // Body: client_id=<ID>&scope=repo
  // Accept: application/json
  val response = httpClient.post(DEVICE_CODE_URL) {
      header(HttpHeaders.Accept, ContentType.Application.Json.toString())
      contentType(ContentType.Application.FormUrlEncoded)
      setBody("client_id=$clientId&scope=$SCOPE")
  }
  ```
  Return `Either.Left(DomainError.GitError.AuthFailed(...))` on non-2xx or exception.

- **T2.2.4** — Implement `pollForToken()` with the complete state machine:
  - Mutable `var intervalMs = initialInterval * 1000L`
  - Deadline: `val deadline = Clock.System.now().toEpochMilliseconds() + expiresIn * 1000L`
  - Loop while `now < deadline`:
    1. `delay(intervalMs)`
    2. POST to `TOKEN_URL` with `client_id`, `device_code`, `grant_type=urn:ietf:params:oauth:grant-type:device_code`
    3. Parse `TokenPollResponse`
    4. Handle all error codes:
       - `null` (success): return `accessToken.right()`
       - `"authorization_pending"`: `onStateChange(DeviceFlowPollState.Pending)`, `continue`
       - `"slow_down"`: `intervalMs += 5000L` (cumulative — pitfall 1.1a), `onStateChange(Pending)`, `continue`
       - `"expired_token"`: return `DomainError.GitError.AuthFailed("Device code expired").left()`
       - `"access_denied"`: return `DomainError.GitError.AuthFailed("Authorization denied by user").left()`
       - Transport errors (IOException, network exception): `onStateChange(DeviceFlowPollState.NetworkError(msg))`, back off with `min(intervalMs * 2, 60_000L)`, continue (do NOT treat as `access_denied` — pitfall 1.3)
       - 5xx responses: back off 30s, `onStateChange(ServerError)`, continue (pitfall 1.3)
       - Other error strings: `DomainError.GitError.AuthFailed(error).left()`
  - After loop ends (deadline reached): return `DomainError.GitError.AuthFailed("Timed out").left()`

- **T2.2.5** — Define `DeviceFlowPollState` sealed class used by the UI state machine:
  ```kotlin
  sealed class DeviceFlowPollState {
      data object Pending : DeviceFlowPollState()
      data object NetworkError : DeviceFlowPollState()
      data object ServerError : DeviceFlowPollState()
  }
  ```

- **T2.2.6** — Implement `fetchUsername(token)`: `GET https://api.github.com/user` with `Authorization: Bearer $token`; parse `{ "login": "..." }`. Return `null` on any failure — username display is cosmetic.

- **T2.2.7** — Write unit tests for `GitHubDeviceFlowClient` using `ktor-client-mock`:
  - `pollForToken_returnsToken_onSuccess` — mock returns `{"access_token": "gho_xxx"}`
  - `pollForToken_incrementsInterval_onSlowDown` — verify interval grows cumulatively (5→10→15)
  - `pollForToken_stopsPolling_onAccessDenied`
  - `pollForToken_stopsPolling_onExpiredToken`
  - `pollForToken_retriesOnNetworkError_doesNotStopPolling`
  - Use `ktor-client-mock:3.1.3` (already in `jvmTest` dependencies).

#### Story 2.3: GitHub OAuth UI in `GitSetupScreen` (Step 3)

**File:** `commonMain/.../ui/screens/git/GitSetupScreen.kt`  
**New file:** `commonMain/.../ui/screens/git/GitHubOAuthDialog.kt`

**Tasks:**

- **T2.3.1** — Add `GITHUB_OAUTH` radio option in `Step3Auth`:
  ```kotlin
  Row(verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = authType == GitAuthType.GITHUB_OAUTH, onClick = { onAuthTypeChange(GitAuthType.GITHUB_OAUTH) })
      Spacer(modifier = Modifier.width(8.dp))
      Text("GitHub (OAuth)")
  }
  ```

- **T2.3.2** — Add `GITHUB_OAUTH` conditional block in `Step3Auth`. When `authType == GITHUB_OAUTH`:
  - If `oauthConnectedAs != null` (already authenticated): show green checkmark + "Connected as @{username}" text + "Re-connect" button.
  - If `oauthConnectedAs == null`: show "Connect GitHub Account" button.
  - Show `GitHubOAuthDialog` when `showOAuthDialog == true`.

  New parameters added to `Step3Auth`:
  ```kotlin
  oauthConnectedAs: String?,           // null = not connected, "username" = connected
  onStartOAuthFlow: () -> Unit,        // launches the dialog
  showOAuthDialog: Boolean,
  oauthDialogState: OAuthDialogState?,
  onCopyCode: (String) -> Unit,        // copies user code to clipboard
  onOpenBrowser: (String) -> Unit,     // opens verification URL
  onCancelOAuth: () -> Unit,
  ```

- **T2.3.3** — Create `OAuthDialogState` sealed class:
  ```kotlin
  sealed class OAuthDialogState {
      data object Loading : OAuthDialogState()
      data class ShowCode(
          val userCode: String,
          val verificationUri: String,
          val expiresAt: Long,  // epoch ms — used for countdown
      ) : OAuthDialogState()
      data object Polling : OAuthDialogState()
      data class Success(val username: String) : OAuthDialogState()
      data class Error(val message: String) : OAuthDialogState()
  }
  ```

- **T2.3.4** — Create `GitHubOAuthDialog.kt` composable:
  - A `Dialog` (or `AlertDialog`) that renders based on `state: OAuthDialogState`:
    - `Loading`: `CircularProgressIndicator` + "Requesting code from GitHub…"
    - `ShowCode(userCode, verificationUri, expiresAt)`:
      - Prominent `Text(userCode)` in `MaterialTheme.typography.headlineMedium`
      - Countdown text derived from `(expiresAt - now) / 1000` seconds (use `LaunchedEffect` with `delay(1000)` loop — not a fixed "15 min" label — pitfall 1.7)
      - "Copy code" `OutlinedButton` → `onCopyCode(userCode)`
      - "Open GitHub" `Button` → `onOpenBrowser(verificationUri)`
      - `LinearProgressIndicator` (indeterminate) while waiting
      - "Cancel" `TextButton`
    - `Polling`: same as `ShowCode` but without the open/copy buttons (code has been entered, waiting for approval)
    - `Success(username)`: green check icon + "Connected as @{username}" + "Done" button
    - `Error(message)`: error text + "Try Again" button + "Cancel" button

- **T2.3.5** — Wire the device flow coroutine in `GitSetupScreen`:
  Add local state:
  ```kotlin
  var showOAuthDialog by remember { mutableStateOf(false) }
  var oauthDialogState by remember { mutableStateOf<OAuthDialogState?>(null) }
  var oauthConnectedAs by remember {
      mutableStateOf(
          existingConfig?.oauthTokenKey?.let { credentialStore.retrieve(it)
              ?.let { /* fetch username synchronously? no — init to null, lazy load */ null }
          }
      )
  }
  ```

  `onStartOAuthFlow` callback (runs in `scope.launch { }`):
  1. `showOAuthDialog = true`
  2. `oauthDialogState = OAuthDialogState.Loading`
  3. Call `deviceFlowClient.requestDeviceCode()`:
     - On `Left`: `oauthDialogState = OAuthDialogState.Error(err.message)`, return
     - On `Right(response)`: `oauthDialogState = OAuthDialogState.ShowCode(response.userCode, response.verificationUri, System.currentTimeMillis() + response.expiresIn * 1000L)`
  4. Call `deviceFlowClient.pollForToken(response.deviceCode, response.expiresIn, response.interval) { pollState -> if (pollState == Pending && userHasOpenedBrowser) oauthDialogState = OAuthDialogState.Polling }`:
     - On `Left`: `oauthDialogState = OAuthDialogState.Error(err.message)`
     - On `Right(token)`:
       - `val key = "git_github_oauth_$graphId"`
       - `credentialStore.store(key, token)` (stores OAuth token securely)
       - `val username = deviceFlowClient.fetchUsername(token) ?: "GitHub User"`
       - `oauthConnectedAs = username`
       - `oauthDialogState = OAuthDialogState.Success(username)`

  **Note:** `deviceFlowClient` is instantiated via `remember { GitHubDeviceFlowClient(httpClient) }`. The `httpClient` must be injected as a parameter to `GitSetupScreen` or created internally — inject it as a parameter for testability.

- **T2.3.6** — Add `GitHubDeviceFlowClient` and `HttpClient` parameters to `GitSetupScreen`:
  ```kotlin
  deviceFlowClient: GitHubDeviceFlowClient = remember { GitHubDeviceFlowClient(HttpClient(/* engine */) { ... }) }
  ```
  To avoid platform-specific engine references in `commonMain`, create a `expect fun createDefaultHttpClient(): HttpClient` in commonMain with `actual` implementations per platform using the existing engine artifacts.

  **Simpler alternative (preferred):** Inject `GitHubDeviceFlowClient?` as a nullable parameter defaulting to `null`. When null, the "Connect GitHub Account" button is disabled (useful for platforms that don't support HTTP or for tests). For the actual app instantiation, pass a pre-built client from the call site.

- **T2.3.7** — Clipboard + browser opening: these are platform-specific. Add to `FileSystem` (or create a separate `PlatformUtils` expect/actual):
  ```kotlin
  // commonMain interface or expect fun
  expect fun copyToClipboard(text: String)
  expect fun openUrl(url: String)
  ```
  Platform implementations:
  - JVM: `java.awt.Toolkit.getDefaultToolkit().systemClipboard` + `java.awt.Desktop.getDesktop().browse(URI(url))`
  - Android: `ClipboardManager` + `Intent(Intent.ACTION_VIEW, Uri.parse(url))`
  - iOS: `UIPasteboard.general.string = text` + `UIApplication.shared.open(url)`

  Check if these already exist in the codebase before creating; look for `ClipboardManager` or `openUrl` usage.

#### Story 2.4: `GitConfig` serialization backward compatibility

**Tasks:**

- **T2.4.1** — Verify `@Serializable` on `GitConfig` handles new nullable fields with defaults. Since `oauthTokenKey: String? = null` has a default, `kotlinx.serialization` will deserialize existing JSON without the field successfully. No migration needed for `GitConfig` itself.

- **T2.4.2** — Verify `GitAuthType.GITHUB_OAUTH` serialization. The enum is `@Serializable` via the enclosing `@Serializable data class`. Adding a new enum member is backward-compatible for Kotlin serialization when reading; writing new configs with `GITHUB_OAUTH` will serialize to `"GITHUB_OAUTH"` which is not backward-compatible with the previous field-missing case. This is acceptable — old clients cannot parse new GITHUB_OAUTH configs, but new clients can parse all old configs.

#### Story 2.5: `GitSyncService` — map `GITHUB_OAUTH` → `HttpsToken`

**File:** `commonMain/.../git/GitSyncService.kt`  

The `GitSyncService` does not directly call `GitAuth` construction — it reads `GitConfig` and passes it to `gitRepository.fetch(config)` and `gitRepository.push(config)`. The `GitRepository` implementations build `GitAuth` from `GitConfig`.

**Tasks:**

- **T2.5.1** — In each platform's `GitRepository` implementation (JvmGitRepository, AndroidGitRepository), find where `GitAuth` is constructed from `GitConfig`. Add `GITHUB_OAUTH` case:
  ```kotlin
  GitAuthType.GITHUB_OAUTH -> {
      val token = config.oauthTokenKey?.let { credentialAccess.retrieve(it) }
      GitAuth.HttpsToken(
          username = "x-oauth-basic",
          tokenProvider = { token }
      )
  }
  ```
  This follows the GitHub-documented pattern for OAuth-over-HTTPS.

  Find the files:
  ```
  kmp/src/jvmMain/.../git/JvmGitRepository.kt
  kmp/src/androidMain/.../git/AndroidGitRepository.kt
  ```

- **T2.5.2** — Add `CredentialExpired` detection in `GitSyncService.sync()` and `fetchOnly()`. When `gitRepository.fetch(config)` returns `Either.Left(DomainError.GitError.AuthFailed(...))` and `config.authType == GitAuthType.GITHUB_OAUTH`, emit `SyncState.CredentialExpired(graphId)` and return `DomainError.GitError.CredentialExpired(...)`:
  ```kotlin
  is Either.Left -> {
      val err = r.value
      if (err is DomainError.GitError.AuthFailed && config.authType == GitAuthType.GITHUB_OAUTH) {
          _syncState.value = SyncState.CredentialExpired(graphId)
      } else {
          _syncState.value = SyncState.Error(err)
      }
      return@withContext err.left()
  }
  ```

#### Story 2.6: Save/test handlers — `GITHUB_OAUTH` branch

**File:** `commonMain/.../ui/screens/git/GitSetupScreen.kt`

**Tasks:**

- **T2.6.1** — In `onTestConnection` handler (Step 5), add `GITHUB_OAUTH` case:
  ```kotlin
  val testOauthTokenKey = if (authType == GitAuthType.GITHUB_OAUTH && oauthConnectedAs != null) {
      "git_github_oauth_$graphId"  // token already stored in CredentialStore by OAuth flow
  } else null
  ```
  Pass `testOauthTokenKey` to `buildConfig(...)`.

- **T2.6.2** — In `onSave` handler (Step 5), add `GITHUB_OAUTH` case for the existing-clone path:
  ```kotlin
  val oauthTokenKey = if (authType == GitAuthType.GITHUB_OAUTH) {
      existingConfig?.oauthTokenKey ?: "git_github_oauth_$graphId"
  } else null
  ```
  Pass to `buildConfig(...)`.

  For the clone path: same pattern, using `newGraphId`:
  ```kotlin
  val oauthTokenKey = if (authType == GitAuthType.GITHUB_OAUTH) "git_github_oauth_$newGraphId" else null
  ```

- **T2.6.3** — In auth-type-switching logic: when the user switches away from `GITHUB_OAUTH` to another auth type, delete the stored token:
  ```kotlin
  onAuthTypeChange = { newType ->
      if (authType == GitAuthType.GITHUB_OAUTH && newType != GitAuthType.GITHUB_OAUTH) {
          credentialStore.delete("git_github_oauth_$graphId")
          oauthConnectedAs = null
      }
      authType = newType
  }
  ```

#### Story 2.7: `CredentialExpired` UI (sync settings screen)

**Note:** This is a minimal implementation — the full re-auth screen is out of scope for this plan. The requirement is that `SyncState.CredentialExpired` is emitted and surfaced.

**Tasks:**

- **T2.7.1** — Find the screen that displays `syncState` (likely `StelekitViewModel` or a dedicated git settings screen). Add a `when` branch for `SyncState.CredentialExpired`: render a "Re-connect GitHub" button that navigates back to `GitSetupScreen` with `initialStep = 3` (auth step).

- **T2.7.2** — Update `toSyncErrorMessage()` in `DomainError.kt` to handle `CredentialExpired`: "GitHub authentication expired — tap to re-connect".

#### Story 2.8: Tests

**Tasks:**

- **T2.8.1** — `GitHubDeviceFlowClientTest` (jvmTest) — see T2.2.7 above.

- **T2.8.2** — `GitSetupScreenOAuthTest` (jvmTest) — Compose UI test verifying:
  - `GITHUB_OAUTH` radio renders in Step 3.
  - "Connect GitHub Account" button is present when `GITHUB_OAUTH` is selected.
  - Dialog shows with user code when `oauthDialogState = ShowCode(...)`.
  - Roborazzi screenshot for the dialog state.

- **T2.8.3** — `GitSyncServiceCredentialExpiredTest` (jvmTest / businessTest) — verify that when `gitRepository.fetch()` returns `AuthFailed` and `config.authType == GITHUB_OAUTH`, `syncState.value` becomes `SyncState.CredentialExpired`.

---

## File-by-File Change Summary

### Create new

| Path | Purpose |
|---|---|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitHubDeviceFlow.kt` | `DeviceCodeResponse`, `TokenPollResponse`, `DeviceFlowPollState` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClient.kt` | HTTP service for device flow |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitHubOAuthDialog.kt` | Composable dialog for device flow UX |

### Modify existing

| Path | Change summary |
|---|---|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitConfig.kt` | Add `GITHUB_OAUTH` enum; add `oauthTokenKey` field |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt` | Add `CredentialExpired` state |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt` | Add `CredentialExpired` to `GitError`; update `toUserMessage()`, `toSyncErrorMessage()` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt` | Add `suspend fun pickFileAsync(): String? = null` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt` | Add `FileSystem` and `GitHubDeviceFlowClient?` params; browse buttons; OAuth radio + dialog wiring; GITHUB_OAUTH branches in save/test/type-switch handlers |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` | Add `onPickFile` field, `initFilePicker()`, `pickFileAsync()`, SSH key copy-to-private logic |
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` | Add `pickFileAsync()`; fix `pickDirectoryAsync()` with macOS `FileDialog` path |
| `kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` | Add `override suspend fun pickFileAsync(): String? = null` |
| `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` | Add `override suspend fun pickFileAsync(): String? = null` |
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/git/JvmGitRepository.kt` | Add `GITHUB_OAUTH` → `GitAuth.HttpsToken("x-oauth-basic", token)` mapping |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt` | Add `GITHUB_OAUTH` → `GitAuth.HttpsToken("x-oauth-basic", token)` mapping |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt` | Add `CredentialExpired` detection on `AuthFailed` for GITHUB_OAUTH |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/MainActivity.kt` (actual path TBD) | Register `ACTION_OPEN_DOCUMENT` launcher; wire `initFilePicker()` |

---

## GitHub Device Flow — Exact HTTP Calls

### Step 1: Request device code

```
POST https://github.com/login/device/code
Accept: application/json
Content-Type: application/x-www-form-urlencoded

client_id=178c6fc778ccc68e1d6a&scope=repo
```

Ktor implementation:
```kotlin
val response: DeviceCodeResponse = httpClient.post("https://github.com/login/device/code") {
    header(HttpHeaders.Accept, "application/json")
    contentType(ContentType.Application.FormUrlEncoded)
    setBody("client_id=$clientId&scope=$SCOPE")
}.body()
```

### Step 2: Poll for token

```
POST https://github.com/login/oauth/access_token
Accept: application/json
Content-Type: application/x-www-form-urlencoded

client_id=178c6fc778ccc68e1d6a&device_code=<DEVICE_CODE>&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code
```

Ktor implementation:
```kotlin
val response: TokenPollResponse = httpClient.post("https://github.com/login/oauth/access_token") {
    header(HttpHeaders.Accept, "application/json")
    contentType(ContentType.Application.FormUrlEncoded)
    setBody(
        "client_id=$clientId" +
        "&device_code=$deviceCode" +
        "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"
    )
}.body()
```

**Critical:** the `grant_type` value must be URL-encoded in a form body: `urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code` (colons and slashes encoded).

### Step 3: Fetch username (cosmetic)

```
GET https://api.github.com/user
Authorization: Bearer <access_token>
```

Ktor implementation:
```kotlin
@Serializable data class GitHubUser(val login: String)

val user: GitHubUser = httpClient.get("https://api.github.com/user") {
    header(HttpHeaders.Authorization, "Bearer $token")
}.body()
```

---

## `GitAuthType.GITHUB_OAUTH` Integration Map

```
GitAuthType.GITHUB_OAUTH
  ↓ stored in GitConfig.authType
  ↓ GitConfig.oauthTokenKey = "git_github_oauth_$graphId"
  ↓ CredentialStore.retrieve(oauthTokenKey) → access token string

JvmGitRepository.buildAuth(config: GitConfig):
  GITHUB_OAUTH → GitAuth.HttpsToken(
      username = "x-oauth-basic",
      tokenProvider = { credentialAccess.retrieve(config.oauthTokenKey!!) }
  )
  
GitSyncService.sync()/fetchOnly():
  fetch returns AuthFailed + GITHUB_OAUTH config
  → _syncState.value = SyncState.CredentialExpired(graphId)
```

---

## Risk Mitigations Applied

| Pitfall | Mitigation in Plan |
|---|---|
| JVM: macOS JFileChooser only shows once | Use `java.awt.FileDialog` on macOS (T1.2.1, T1.2.2) |
| JVM: `invokeLater` → null result | Use `invokeAndWait` in `pickFileAsync()` and `pickDirectoryAsync()` (T1.2.1) |
| JVM: headless environment crash | `GraphicsEnvironment.isHeadless()` guard returns `null` (T1.2.1) |
| Android: SSH key as persistent SAF URI | Copy to `context.getDir("ssh_keys", ...)`, release URI (T1.3.2) |
| Device flow: `slow_down` not cumulative | `intervalMs += 5000L` on each `slow_down` (T2.2.4) |
| Device flow: 5xx treated as `pending` | Separate 5xx handling with aggressive back-off (T2.2.4) |
| Device flow: transport error stops polling | Catch network exceptions separately, continue polling (T2.2.4) |
| Device flow: timer shows fixed "15 min" | `LaunchedEffect` countdown from `expiresAt` epoch ms (T2.3.4) |
| `rememberCoroutineScope` passed to class | Scope stays in composable; `deviceFlowClient` is stateless (T2.3.5) |
| `GitHubDeviceFlowClient` scope leak | Client is stateless (no internal scope); coroutine runs in composable scope via `scope.launch { }` (T2.3.5) |
| New `GitConfig` fields break serialization | All new fields have default values (`null`) (T2.4.1) |

---

## Success Criteria Traceability

| Requirement | Stories |
|---|---|
| FR-1.1 Desktop Browse button | S1.2, S1.5 |
| FR-1.2 Android SAF Browse button (directory) | S1.3, S1.5 (reuses existing `onPickDirectory`) |
| FR-1.2 Android SAF Browse button (file/SSH key) | S1.3 |
| FR-1.3 iOS Browse stub | S1.4 |
| FR-1.4 Picker returns null on cancel → no change | T1.5.3, T1.5.5 (`if (path != null)`) |
| FR-2.1 `GITHUB_OAUTH` enum | S2.1 |
| FR-2.2 Device flow UI | S2.2, S2.3 |
| FR-2.3 Token used as `HttpsToken("x-oauth-basic", ...)` | S2.5 |
| FR-2.4 `CredentialExpired` state + re-auth | S2.5, S2.7 |
| FR-2.5 Token deletion on auth-type switch | T2.6.3 |
| Existing screenshot tests still pass | T1.5.6, T1.5.7 (FakeFileSystem in tests) |
