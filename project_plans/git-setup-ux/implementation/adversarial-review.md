# Adversarial Review: git-setup-ux Implementation Plan

**Reviewer:** Adversarial review pass  
**Date:** 2026-06-12  
**Plan:** `/project_plans/git-setup-ux/implementation/plan.md`

---

## Verdict: CONCERNS

No single defect would outright prevent the feature from shipping, but several issues — a non-exhaustive `when` break in `GitSetupScreen.kt`, wrong URL-encoding assumption, and the `invokeLater` vs `invokeAndWait` correction applied incorrectly — are likely to produce runtime failures or subtle bugs that would surface immediately in testing or production.

---

## Critical Issues (would block shipping)

### 1. `cloneAuth` `when` expression becomes non-exhaustive when `GITHUB_OAUTH` is added

**Location:** `GitSetupScreen.kt` line 258–268 — the `cloneAuth = when (authType)` block.

The current code is:
```kotlin
val cloneAuth = when (authType) {
    GitAuthType.HTTPS_TOKEN -> ...
    GitAuthType.SSH_KEY -> ...
    GitAuthType.NONE -> GitAuth.None
}
```

This is an exhaustive `when` over a 3-member enum. When T2.1.1 adds `GITHUB_OAUTH` to `GitAuthType`, this `when` gains a fourth variant that is not handled. Kotlin requires `when` expressions used as values to be exhaustive — this will be a **compile error**, not a runtime warning.

The plan's T2.6.1 and T2.6.2 address the `onTestConnection` and `onSave` handlers for the existing-clone path, but neither task mentions fixing the `cloneAuth` `when` block used in the clone path. This must be addressed explicitly. The correct fix is to add a `GITHUB_OAUTH` branch that maps to `GitAuth.HttpsToken` using the already-stored token (same as T2.5.1), or to fall back to `GitAuth.None` with a comment.

**Severity:** Compile-time breakage. The feature cannot ship until this is handled.

---

### 2. `invokeLater` correction in T1.2.2 introduces a race condition

**Location:** T1.2.2 — the plan corrects `pickDirectoryAsync()` on JVM to use `invokeAndWait` instead of `invokeLater`.

The **actual JVM implementation** at `jvmMain/.../platform/PlatformFileSystem.kt` (lines 54–68) uses `SwingUtilities.invokeLater` paired with a `CompletableFuture.get()` — not `invokeAndWait`. The plan's T1.2.1 correctly mirrors this pattern for `pickFileAsync()`. But T1.2.2 says to replace the existing `pickDirectoryAsync()` with `invokeAndWait`, specifically calling out "pitfall 4.3a."

The `invokeLater + CompletableFuture.get()` pattern used in the existing code is **already correct and avoids the nested-event-loop issue** — `invokeLater` schedules the dialog on the EDT without blocking the EDT itself, and `future.get()` blocks the IO thread (not the EDT). Replacing `invokeLater` with `invokeAndWait` is wrong: if `pickDirectoryAsync()` is ever called from the EDT (e.g., from `pickDirectory()` which is already on the EDT), `invokeAndWait` will deadlock. The existing pattern is not the bug. The plan should drop the `invokeLater` → `invokeAndWait` replacement and focus only on the macOS `FileDialog` substitution.

**Severity:** Would introduce an EDT deadlock regression on macOS when `pickDirectoryAsync` is called from certain contexts. This is a behavioral regression in an existing working feature.

---

### 3. `toUiMessage()` in `DomainError.kt` will fail to compile after `CredentialExpired` is added to `DomainError.GitError`

**Location:** `DomainError.kt` lines 114–158 — the `toUiMessage()` extension function is a `when` expression over `DomainError`. The `toSyncErrorMessage()` on line 160 is also a `when` over `DomainError.GitError`.

`DomainError.GitError` is a `sealed interface`. Both `toUiMessage()` and `toSyncErrorMessage()` use exhaustive `when` expressions over it. Adding `CredentialExpired` to `DomainError.GitError` without updating both `when` expressions will cause a **compile error** (or, if these are `when` statements rather than expressions, a silent missing branch).

Inspecting the actual code: `toUiMessage()` (line 140–151) and `toSyncErrorMessage()` (line 160–173) each enumerate all current `GitError` variants. Both are `when` expressions (they return `String`), so they are exhaustive. Adding `CredentialExpired` breaks both. Plan task T2.1.4 mentions updating `toUserMessage()` and `toSyncErrorMessage()`, but there is no `toUserMessage()` function — the actual function is `toUiMessage()`. This naming mismatch means the implementer will search for a non-existent function and may miss the actual one.

**Severity:** Compile error unless `toUiMessage()` (not `toUserMessage()`) is also updated.

---

## Non-Critical Concerns (worth addressing before implementation)

### 4. Wrong URL encoding claim for `grant_type` form body

**Location:** Section "Step 2: Poll for token" and T2.2.4.

The plan states: "the `grant_type` value must be URL-encoded in a form body: `urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code` (colons and slashes encoded)."

This is correct for a raw string body — but the plan's own Ktor implementation uses `setBody("client_id=...&grant_type=urn%3A...")` with `contentType(ContentType.Application.FormUrlEncoded)`. When you pass a raw pre-encoded string to `setBody()`, the body is sent verbatim and Ktor does not re-encode it, so this works. However, the concern is that this is fragile: if a future developer switches to Ktor's `Parameters.build { append("grant_type", "urn:ietf:params:oauth:grant-type:device_code") }` approach (which auto-encodes), the value would be double-encoded.

More practically: the `device_code` value from GitHub is an opaque base64url string that does not require encoding. But the plan does not mention URL-encoding `deviceCode` before interpolating it into the form body string. If GitHub ever returns a device_code with `+`, `=`, or `&` characters (base64url does not, but base64 does), the raw interpolation would corrupt the body. Using Ktor's `Parameters.build` approach would be safer and should be the preferred recommendation.

The plan's current approach works for GitHub's actual device_code format but is unnecessarily fragile. Recommend using `Parameters.build { append(...) }` with `setBody(formData)` instead of manual string concatenation.

### 5. `CredentialExpired` added to both `SyncState` and `DomainError.GitError` — duplication creates maintenance burden but is not blocked

**Location:** T2.1.3 and T2.1.4.

`SyncState.CredentialExpired` and `DomainError.GitError.CredentialExpired` serve different purposes (UI state vs. domain error), so the duplication is arguably warranted. However, the plan does not explain where `DomainError.GitError.CredentialExpired` is ever *returned* — `GitSyncService` T2.5.2 only emits `SyncState.CredentialExpired` and returns the original `AuthFailed` error, never `CredentialExpired` the domain error. The `DomainError.GitError.CredentialExpired` variant is defined but never produced in the plan's code. This either means:
1. It should be removed (saving implementers from updating `toUiMessage()` and `toSyncErrorMessage()` for a variant that is never emitted), or
2. T2.5.2 should be amended to return `DomainError.GitError.CredentialExpired` rather than the original `AuthFailed`.

As-written, `DomainError.GitError.CredentialExpired` is dead code at the point the plan ends. Recommend either removing it or amending T2.5.2 to use it.

### 6. `GitSetupScreen` new required parameters break the sole call site non-trivially

**Location:** T1.5.1, T2.3.6 and `GraphDialogLayer.kt` line 153.

The plan adds `fileSystem: FileSystem` and `deviceFlowClient: GitHubDeviceFlowClient?` as new parameters to `GitSetupScreen`. The current signature has all new parameters with defaults except the ones that already lack them. Checking the actual call site in `GraphDialogLayer.kt` (line 153–166):

```kotlin
GitSetupScreen(
    graphId = activeGraphId ?: "",
    gitRepository = gitRepository,
    gitConfigRepository = gitConfigRepository,
    gitSyncService = gitSyncService,
    onDismiss = { viewModel.dismissGitSetup() },
    onSave = { viewModel.dismissGitSetup() },
    onCloneAndAdd = onCloneAndAdd,
    graphPath = graphPath,
    onCloneComplete = onCloneComplete,
    initialStep = appState.gitSetupInitialStep,
    initialUseExistingClone = !appState.gitSetupOpenForClone,
    existingConfig = null,
)
```

`fileSystem` cannot have a sensible default (`null` is wrong — the Browse button must work), so it will be a required parameter. `GraphDialogLayer` does not currently receive a `FileSystem` parameter, meaning the plan must also propagate `fileSystem` up through `GraphDialogLayer` to `App.kt`. The plan does not mention updating `GraphDialogLayer`'s own signature, which is a gap. T1.5.6 only says "update all call sites of `GitSetupScreen`" without identifying that `GraphDialogLayer` itself needs a new parameter wired from wherever `PlatformFileSystem` lives.

The plan should explicitly add updating `GraphDialogLayer` and its callers to the task list.

### 7. `openUrl` / `copyToClipboard` already exist — plan proposes a redundant `expect/actual`

**Location:** T2.3.7.

The plan proposes creating `expect fun copyToClipboard(text: String)` and `expect fun openUrl(url: String)` as new expect/actual pairs.

Verification against the actual codebase:
- `expect fun openInBrowser(url: String)` already exists in `platform/OpenInBrowser.kt` with `actual` implementations for Android (`Intent.ACTION_VIEW`), JVM (`OpenInBrowser.jvm.kt`), and presumably iOS.
- `rememberClipboardProvider()` already exists in `ui/PlatformClipboardProvider.kt` as a `@Composable expect fun` with JVM and Android actuals.

The plan must not create new `expect/actual` pairs for these — it should reuse `openInBrowser(url)` directly and pass a `ClipboardManager` / `ClipboardProvider` to the dialog composable (the latter is already the pattern in `App.kt` for copy operations). Creating duplicate expect/actual pairs for the same capability would fragment platform implementations and add maintenance overhead.

### 8. `GitHubDeviceFlowClient` scope: `pollForToken` coroutine runs in the caller's `scope.launch` — cancellation on dialog dismiss needs explicit handling

**Location:** T2.3.5 — `onStartOAuthFlow` wires the poll into `scope.launch { }` where `scope = rememberCoroutineScope()`.

If the user dismisses the dialog (triggers `onCancelOAuth`), the plan sets `showOAuthDialog = false` but the `scope.launch` coroutine continues polling until the device code expires (up to 15 minutes) because `rememberCoroutineScope` is only cancelled when the composable leaves composition — the dialog dismissal does not cancel the scope. The plan should use a `Job` handle:

```kotlin
var oauthJob by remember { mutableStateOf<Job?>(null) }
onStartOAuthFlow = {
    oauthJob?.cancel()
    oauthJob = scope.launch { ... }
}
onCancelOAuth = {
    oauthJob?.cancel()
    oauthJob = null
    showOAuthDialog = false
}
```

Without this, dismissing the dialog leaves a background coroutine polling GitHub for up to 15 minutes, making network calls and attempting to update Compose state after the relevant state variables may have changed.

### 9. Token endpoint URL — plan uses correct URL; `https://github.com/login/token` in requirements doc is a discrepancy

**Location:** T2.2.2 companion constants and "Step 2: Poll for token" section.

The plan uses `https://github.com/login/oauth/access_token` for the token polling endpoint. This is the **correct** GitHub OAuth Device Flow endpoint per GitHub's public documentation. If the requirements doc says `https://github.com/login/token`, that URL does not exist on GitHub's API — the requirements doc is wrong. The plan is correct here.

Similarly, `https://github.com/login/device/code` for the device code request is correct.

### 10. `ktor-client-mock` is in `jvmTest` but `GitHubDeviceFlowClient` uses `commonMain` Ktor — tests may require `ContentNegotiation` plugin in the test client

**Location:** T2.2.7 and the Ktor configuration in `build.gradle.kts` lines 86–88.

`ktor-client-content-negotiation` and `ktor-serialization-kotlinx-json` are in `commonMain` dependencies, not installed by default on every `HttpClient` instance. The plan's `requestDeviceCode()` and `pollForToken()` use `.body<DeviceCodeResponse>()` and `.body<TokenPollResponse>()` — these require the `ContentNegotiation` plugin with `json()` installed on the client, *and* they require GitHub's response to have `Content-Type: application/json` (which it does when `Accept: application/json` is sent).

The test mock client in T2.2.7 must install `ContentNegotiation { json() }` or the `.body<T>()` deserialization will throw `NoTransformationFoundException`. The plan does not mention this requirement for the test client setup. The `GitHubDeviceFlowClient`'s constructor should either accept a pre-configured client or install the plugin itself.

---

## Verified Correct

- **T1.5.1 scope usage:** Correct — `scope.launch { fileSystem.pickDirectoryAsync() }` keeps the `rememberCoroutineScope` inside the composable and never passes it to `fileSystem`. No scope-leak violation.
- **T1.2.1 `pickFileAsync()` implementation:** The `withContext(Dispatchers.IO) + invokeLater + CompletableFuture.get()` pattern mirrors the existing `pickDirectoryAsync()` exactly. This is the correct approach.
- **T1.3.1/T1.3.2 Android `initFilePicker` pattern:** Correctly mirrors the existing `initSaveFilePicker` / `onPickSaveFile` pattern already present in Android `PlatformFileSystem`. The pattern is proven working.
- **`pickFileAsync` default stub in `FileSystem` interface:** The `suspend fun pickFileAsync(): String? = null` default is backward-compatible for all platforms that don't override it. No `expect/actual` is required since `FileSystem` is an `interface`, not an `expect class`.
- **`GitConfig` serialization backward compatibility (T2.4.1):** Adding `oauthTokenKey: String? = null` with a default is safe for `kotlinx.serialization` — missing keys are filled with defaults on deserialization.
- **`GitAuth.HttpsToken("x-oauth-basic", ...)` pattern (T2.5.1):** This is GitHub's documented username for OAuth token HTTPS auth. Correct.
- **Ktor dependencies:** `ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json` are all already in `commonMain` (lines 86–88). `ktor-client-mock:3.1.3` is in `jvmTest` (line 147). No new build.gradle changes are required.
- **Compose UI tests in `jvmTest` (T2.8.2):** The existing test infrastructure uses `createComposeRule()` from `compose.desktop.uiTestJUnit4` (confirmed by `VoiceCaptureButtonScreenshotTest.kt`). This works without an `Activity`. The T2.8.2 test approach is valid.
- **`slow_down` cumulative interval handling (T2.2.4):** `intervalMs += 5000L` on each `slow_down` response is the correct RFC 8628 behavior.
- **`DeviceFlowPollState.NetworkError` as `data object` (T2.2.5):** Defined as `data object` but `onStateChange(DeviceFlowPollState.NetworkError(msg))` in T2.2.4 passes a message. This is a minor inconsistency — the `data object` has no `msg` field. Either define it as `data class NetworkError(val message: String)` or drop the message parameter. Low severity but should be consistent.
