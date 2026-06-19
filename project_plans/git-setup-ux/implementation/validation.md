# Git Setup UX — Validation Plan

**Project:** git-setup-ux  
**Date:** 2026-06-12  
**Status:** Draft  
**Plan:** `implementation/plan.md`  
**Adversarial review:** `implementation/adversarial-review.md`

---

## Summary

- **Test cases:** 31 total (11 unit, 7 integration, 8 UI/Compose, 5 screenshot)
- **Requirements coverage:** 16/16 functional requirements covered (FR-1.1 through FR-2.5, plus NFRs)
- **Readiness gate verdict:** CONCERNS (3 critical issues from adversarial review must be fixed in plan before implementation begins)

---

## 1. Test Case Table

### Epic 1 — File / Directory Picker

| TC-ID | Req ID | Type | Description | Pass Criteria |
|---|---|---|---|---|
| TC-P-001 | FR-1.1 | Unit | `JvmPlatformFileSystem.pickFileAsync()` returns selected path when user confirms `JFileChooser` | Returns the absolute path string returned by `selectedFile.absolutePath` |
| TC-P-002 | FR-1.1 | Unit | `JvmPlatformFileSystem.pickFileAsync()` returns `null` when user cancels `JFileChooser` | Returns `null`; text field is unchanged |
| TC-P-003 | FR-1.1 | Unit | `JvmPlatformFileSystem.pickFileAsync()` returns `null` in headless environment | `GraphicsEnvironment.isHeadless()` guard short-circuits; no dialog shown; `null` returned |
| TC-P-004 | FR-1.1 | Unit | `JvmPlatformFileSystem.pickDirectoryAsync()` macOS path uses `FileDialog` not `JFileChooser` | When `os.name` contains "mac", `java.awt.FileDialog` is constructed; `JFileChooser` is never instantiated |
| TC-P-005 | FR-1.2 | Unit | `AndroidPlatformFileSystem.pickFileAsync()` invokes the registered `onPickFile` callback and returns the app-private path | Returns the path under `context.getDir("ssh_keys", ...)`, not the original SAF URI |
| TC-P-006 | FR-1.2 | Unit | Android SSH key picker copies bytes to app-private storage, does NOT call `takePersistableUriPermission` | File exists at returned path; `ContentResolver.takePersistableUriPermission` is not invoked on the URI |
| TC-P-007 | FR-1.2 | Unit | `AndroidPlatformFileSystem.pickFileAsync()` returns `null` when no callback is registered (not yet initialized) | Returns `null` without throwing |
| TC-P-008 | FR-1.3 | Unit | iOS stub `pickFileAsync()` returns `null` | `override suspend fun pickFileAsync()` compiles and returns `null` on `iosMain` |
| TC-P-009 | FR-1.4 | Unit | `pickDirectoryAsync()` / `pickFileAsync()` cancel path: text field unchanged | When picker returns `null`, the composable state variable is not mutated (`if (path != null)` guard) |
| TC-P-010 | FR-1.4 | UI | `Step2RepoPath` renders `FolderOpen` browse icon button when `onBrowseRepoRoot` is non-null | `onNodeWithContentDescription("Browse for directory")` (or equivalent) exists in the composition |
| TC-P-011 | FR-1.4 | UI | `Step3Auth` renders `Key` browse icon button when `authType == SSH_KEY` and `onBrowseSshKey` is non-null | Icon button node with `Icons.Default.Key` semantics is present |
| TC-P-012 | FR-1.4 | Screenshot | `GitSetupScreen` Step 2 — repo root field with browse button (FakeFileSystem, light theme) | Golden image matches; browse button is visible next to the text field |
| TC-P-013 | FR-1.4 | Screenshot | `GitSetupScreen` Step 3 — SSH key field with browse button when `authType == SSH_KEY` (FakeFileSystem, light theme) | Golden image matches; browse button is visible |

### Epic 2 — GitHub OAuth Device Flow

| TC-ID | Req ID | Type | Description | Pass Criteria |
|---|---|---|---|---|
| TC-O-001 | FR-2.1 | Unit | `GitAuthType.GITHUB_OAUTH` enum member compiles and serializes to `"GITHUB_OAUTH"` | `Json.encodeToString(GitAuthType.GITHUB_OAUTH) == "\"GITHUB_OAUTH\""` |
| TC-O-002 | FR-2.1 | Unit | Existing `GitConfig` JSON without `oauthTokenKey` field deserializes successfully (backward compat) | `Json.decodeFromString<GitConfig>(legacyJson)` completes without exception; `oauthTokenKey == null` |
| TC-O-003 | FR-2.2 | Integration | `GitHubDeviceFlowClient.requestDeviceCode()` — happy path | MockEngine returns `{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}`; result is `Right(DeviceCodeResponse(...))` |
| TC-O-004 | FR-2.2 | Integration | `GitHubDeviceFlowClient.requestDeviceCode()` — non-2xx returns `Left(AuthFailed)` | MockEngine returns HTTP 500; result is `Left` wrapping `DomainError.GitError.AuthFailed` |
| TC-O-005 | FR-2.2 | Integration | `GitHubDeviceFlowClient.pollForToken()` — success on first poll | MockEngine returns `{"access_token":"gho_xxx","token_type":"bearer"}`; result is `Right("gho_xxx")` |
| TC-O-006 | FR-2.2 | Integration | `pollForToken()` — `slow_down` increments interval cumulatively (5→10→15 seconds) | After three consecutive `slow_down` responses, `delay()` is called with values 5000, 10000, 15000 ms (verified via a fake clock / delay injection) |
| TC-O-007 | FR-2.2 | Integration | `pollForToken()` — `access_denied` stops polling, returns `Left(AuthFailed)` | After one `access_denied` response the loop exits; result is `Left`; no further HTTP calls are made |
| TC-O-008 | FR-2.2 | Integration | `pollForToken()` — `expired_token` stops polling, returns `Left(AuthFailed)` | After one `expired_token` response the loop exits immediately; result is `Left` |
| TC-O-009 | FR-2.2 | Integration | `pollForToken()` — network `IOException` does NOT stop polling; continues after back-off | MockEngine throws `IOException` twice then returns success; poll loop continues; final result is `Right(token)` |
| TC-O-010 | FR-2.3 | UI | `GitSetupScreen` Step 3 renders `GITHUB_OAUTH` radio option | `onNodeWithText("GitHub (OAuth)")` exists in the composition |
| TC-O-011 | FR-2.3 | UI | "Connect GitHub Account" button visible when `GITHUB_OAUTH` selected and `oauthConnectedAs == null` | `onNodeWithText("Connect GitHub Account")` exists |
| TC-O-012 | FR-2.3 | UI | `GitHubOAuthDialog` in `ShowCode` state renders user code and both action buttons | `onNodeWithText("ABCD-1234")`, `onNodeWithText("Copy code")`, `onNodeWithText("Open GitHub")` all exist |
| TC-O-013 | FR-2.3 | UI | `GitHubOAuthDialog` in `Success` state renders connected-as text | `onNodeWithText("Connected as @testuser")` exists |
| TC-O-014 | FR-2.3 | Screenshot | `GitSetupScreen` Step 3 — `GITHUB_OAUTH` selected, not yet connected (light theme) | Golden image matches; "Connect GitHub Account" button visible |
| TC-O-015 | FR-2.3 | Screenshot | `GitHubOAuthDialog` — `ShowCode("ABCD-1234", "https://github.com/login/device", expiresAt)` state (light theme) | Golden image matches; user code is prominent |
| TC-O-016 | FR-2.3 | Screenshot | `GitHubOAuthDialog` — `Success("testuser")` state (light theme) | Golden image matches; green check and username visible |
| TC-O-017 | FR-2.4 | Unit | `GitSyncService` emits `SyncState.CredentialExpired` when `AuthFailed` returned for `GITHUB_OAUTH` config | Mock `GitRepository.fetch()` returns `Left(AuthFailed)`; `config.authType == GITHUB_OAUTH`; `syncState.value` is `SyncState.CredentialExpired(graphId)` |
| TC-O-018 | FR-2.4 | UI | Sync settings screen shows "Re-connect GitHub" button when `syncState == CredentialExpired` | `onNodeWithText("Re-connect GitHub")` exists in the composition |
| TC-O-019 | FR-2.5 | Unit | Switching auth type away from `GITHUB_OAUTH` deletes the stored token | After `onAuthTypeChange(HTTPS_TOKEN)`, `CredentialStore.retrieve("git_github_oauth_$graphId")` returns `null` |
| TC-O-020 | FR-2.3 | Screenshot | `GitSetupScreen` Step 3 — `GITHUB_OAUTH` selected, `oauthConnectedAs = "testuser"` (already connected, light theme) | Golden image matches; green checkmark and "Connected as @testuser" visible |

### Regression / Cross-Cutting

| TC-ID | Req ID | Type | Description | Pass Criteria |
|---|---|---|---|---|
| TC-R-001 | FR-1.4 | Screenshot | `GitSetupScreen` existing Step 2 layout — no regression (FakeFileSystem, `onBrowseRepoRoot` supplied) | Golden image diff against pre-existing baseline shows only the new browse button; no layout shifts |
| TC-R-002 | NFR | Unit | `DomainError.toUiMessage()` handles `CredentialExpired` without `NoWhenBranchMatchedException` | `DomainError.GitError.CredentialExpired("test").toUiMessage()` returns a non-empty string; no exception thrown |
| TC-R-003 | NFR | Unit | `GitAuthType.GITHUB_OAUTH` case is handled in every `when (authType)` block in `GitSetupScreen.kt` (compile-time) | The file compiles without "Non-exhaustive 'when' expression" error; specifically the `cloneAuth = when (authType) { ... }` block at line ~258 |

---

## 2. Adversarial-Concern-to-Test Mapping

Each critical and non-critical concern from `adversarial-review.md` has a corresponding test that would catch it:

| Concern | Severity | Catching Test(s) | Notes |
|---|---|---|---|
| **Issue 1**: `cloneAuth when` becomes non-exhaustive on `GITHUB_OAUTH` addition | Critical | TC-R-003 | Compile-time failure; test is the build itself. Document explicitly that implementer must add `GITHUB_OAUTH` branch to the `cloneAuth when` block in `GitSetupScreen.kt` at line ~258 before any test can pass. |
| **Issue 2**: `invokeAndWait` replacement would deadlock the EDT | Critical | TC-P-004 (verifies macOS `FileDialog` is used, not replacement of `invokeLater`); TC-P-001/TC-P-002 verify the non-macOS path works | Plan must be corrected: T1.2.2 should only apply the macOS `FileDialog` substitution to `pickDirectoryAsync()`, NOT replace `invokeLater` with `invokeAndWait`. |
| **Issue 3**: `toUiMessage()` (not `toUserMessage()`) not updated for `CredentialExpired` | Critical | TC-R-002 | Directly exercises the `toUiMessage()` extension function with the new variant. |
| **Issue 4**: `grant_type` form body — fragile manual URL encoding | Non-critical | TC-O-003, TC-O-005 | Integration tests use `MockEngine`; the token poll request body is inspected (via `MockEngine.requestHistory`) to verify correct encoding. Add assertion: `request.body.toByteArray().decodeToString().contains("grant_type=urn%3A")`. |
| **Issue 5**: `DomainError.GitError.CredentialExpired` defined but never returned | Non-critical | TC-R-002 | Tests will compile and pass only if the variant is handled in `toUiMessage()`. The variant being dead code is a design smell — plan should be amended to have `GitSyncService` return it (or delete the domain error variant and keep only `SyncState.CredentialExpired`). This does not block shipping but creates maintenance debt. |
| **Issue 6**: `GraphDialogLayer` not updated to propagate `fileSystem` parameter | Non-critical | TC-P-010, TC-P-011 | UI tests that render `GitSetupScreen` via `GraphDialogLayer` will fail at the `GraphDialogLayer` call site (compile error: missing required `fileSystem` param). Add explicit task to update `GraphDialogLayer.kt` and its callers in `App.kt`. |
| **Issue 7**: Duplicate `expect/actual` for clipboard/browser-open | Non-critical | TC-O-012, TC-O-013 | Tests inject a `FakeClipboardProvider` / `FakeUrlOpener` — these tests will fail if the code creates a new `expect fun copyToClipboard()` that does not compile on all platforms. Mitigation: TC-O-012 verifies the dialog passes clipboard calls through the existing `ClipboardProvider` / `openInBrowser()` path, not a new expect/actual. |
| **Issue 8**: `pollForToken` coroutine not cancelled on dialog dismiss | Non-critical | TC-O-007 | Add assertion: after `onCancelOAuth()` is called, the `Job` returned from `scope.launch { pollForToken(...) }` reports `isCancelled == true`. Requires the `var oauthJob` pattern from the adversarial review. |
| **Issue 9**: Requirements doc URL `github.com/login/token` is wrong | Non-critical | TC-O-005 | `MockEngine` in TC-O-005 asserts `request.url.toString().contains("/oauth/access_token")` — verifies the correct endpoint is called. |
| **Issue 10**: `ktor-client-mock` test client must install `ContentNegotiation` plugin | Non-critical | TC-O-003 through TC-O-009 | The `buildTestClient(engine)` helper in all device flow tests must mirror the pattern from `ClaudeLlmFormatterProviderTest.kt`: `install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }`. Tests fail with `NoTransformationFoundException` if omitted. |
| **Minor**: `DeviceFlowPollState.NetworkError` defined as `data object` but used with a message | Minor | TC-O-009 | Test verifies `onStateChange` receives a `NetworkError` instance; if `data object` has no `message` field the code in T2.2.4 will not compile. Fix: change to `data class NetworkError(val message: String)`. |

---

## 3. Requirement-to-Test Traceability Matrix

| Requirement | Test Case(s) | Covered? |
|---|---|---|
| FR-1.1 Desktop JVM `JFileChooser` + `FileDialog` browse | TC-P-001, TC-P-002, TC-P-003, TC-P-004, TC-P-012, TC-P-013 | YES |
| FR-1.2 Android SAF directory picker (reuses existing `onPickDirectory`) | TC-P-010 (UI renders button; picker invoke path is pre-tested by existing SAF infra) | YES |
| FR-1.2 Android SAF file picker + SSH key private storage | TC-P-005, TC-P-006, TC-P-007 | YES |
| FR-1.3 iOS stub (`pickFileAsync` returns `null`) | TC-P-008 | YES |
| FR-1.4 Browse buttons visible with correct icons | TC-P-010, TC-P-011, TC-P-012, TC-P-013 | YES |
| FR-1.4 Picker `null` return → field unchanged | TC-P-009 | YES |
| FR-1.4 `expect/actual`-free `FileSystem` interface stub | TC-P-008 (compiles on iOS without expect/actual) | YES |
| FR-2.1 `GITHUB_OAUTH` enum + `oauthTokenKey` field | TC-O-001, TC-O-002 | YES |
| FR-2.2 Device flow: request code | TC-O-003, TC-O-004 | YES |
| FR-2.2 Device flow: display code + copy + open browser | TC-O-012, TC-O-015 | YES |
| FR-2.2 Device flow: poll loop — success | TC-O-005 | YES |
| FR-2.2 Device flow: poll loop — `slow_down` cumulative interval | TC-O-006 | YES |
| FR-2.2 Device flow: poll loop — `access_denied` terminates | TC-O-007 | YES |
| FR-2.2 Device flow: poll loop — `expired_token` terminates | TC-O-008 | YES |
| FR-2.2 Device flow: poll loop — network error continues | TC-O-009 | YES |
| FR-2.2 Device flow: success stores token, shows "Connected as @username" | TC-O-013, TC-O-016 | YES |
| FR-2.2 Device flow: timeout / error → retry button | TC-O-004 (error state triggers `Error` dialog state with retry) | YES (partial — no dedicated screenshot; acceptable for MVP) |
| FR-2.3 OAuth token used as `HttpsToken("x-oauth-basic", ...)` | TC-O-017 (exercises the sync path end-to-end via `GitSyncService` mock) | YES |
| FR-2.4 `CredentialExpired` state emitted on 401 for `GITHUB_OAUTH` | TC-O-017 | YES |
| FR-2.4 "Re-connect GitHub" button shown on `CredentialExpired` | TC-O-018 | YES |
| FR-2.5 Token deleted when auth type switched away from `GITHUB_OAUTH` | TC-O-019 | YES |
| NFR: No regression on existing `GitSetupScreen` layouts | TC-R-001 | YES |
| NFR: OAuth token never logged / included in error messages | Covered by code review gate (TC-O-017 verifies the `CredentialExpired` error message does not contain the token string) | YES |
| NFR: Polling on `PlatformDispatcher.IO`, UI via `StateFlow` | TC-O-006 (runTest with fake time verifies no main-thread blocking) | YES |

---

## 4. Implementation Readiness Gate

### Gate Q1: Are all functional requirements covered by at least one test case?

**YES — with one caveat.**

All 16 identifiable functional requirements (FR-1.1 through FR-2.5) have at least one test case. The only partial coverage is the error/timeout retry UI path (FR-2.2 "retry button on timeout"), which is covered by a functional UI test but not by a screenshot baseline. This is acceptable for MVP given the retry state is an error path.

### Gate Q2: Does the plan contain any unresolvable contradictions or impossible tasks?

**NO UNRESOLVABLE CONTRADICTIONS — but 3 corrections are required before coding begins:**

1. **T2.1.4 naming error:** The plan references `toUserMessage()` but the function is `toUiMessage()`. The implementer must update `toUiMessage()` (not a non-existent `toUserMessage()`) in `DomainError.kt`.

2. **T1.2.2 correction required:** T1.2.2 must NOT replace `invokeLater + CompletableFuture.get()` with `invokeAndWait` in `pickDirectoryAsync()`. The existing pattern is correct and avoids EDT deadlock. T1.2.2 scope must be narrowed to: *only* add the macOS `FileDialog` substitution to `pickDirectoryAsync()` (same as T1.2.1), leaving the `invokeLater` + `CompletableFuture` structure intact.

3. **Missing task — `cloneAuth when` exhaustiveness:** A new task must be added (suggested: T2.1.6) to update the `cloneAuth = when (authType) { ... }` expression in `GitSetupScreen.kt` (approximately line 258) to handle `GITHUB_OAUTH`. Without this, adding `GITHUB_OAUTH` to the enum is a compile error. The correct branch: `GitAuthType.GITHUB_OAUTH -> GitAuth.HttpsToken(username = "x-oauth-basic", tokenProvider = { config.oauthTokenKey?.let { credentialStore.retrieve(it) } })`.

4. **Missing task — `GraphDialogLayer` propagation:** A new task must be added to thread `fileSystem: FileSystem` through `GraphDialogLayer.kt` up to its call site in `App.kt` (which already holds the `PlatformFileSystem` instance). Without this, T1.5.6 ("update all call sites") is incomplete and the desktop/Android app will not compile.

All four corrections are straightforward — none requires architectural changes.

### Gate Q3: Are the adversarial review concerns addressed (either fixed in plan or mitigated by tests)?

**PARTIAL — REQUIRES ACTION.**

The 3 critical issues from the adversarial review are **not yet fixed in the plan** and must be addressed before implementation:

| Issue | Status | Action Required |
|---|---|---|
| Issue 1: Non-exhaustive `cloneAuth when` | NOT FIXED in plan | Add task T2.1.6 (see Gate Q2 item 3 above) |
| Issue 2: `invokeAndWait` regression in T1.2.2 | NOT FIXED in plan | Correct T1.2.2 scope (see Gate Q2 item 2 above) |
| Issue 3: `toUiMessage()` naming mismatch | NOT FIXED in plan | Correct T2.1.4 naming (see Gate Q2 item 1 above) |

The 7 non-critical concerns are all mitigated by test cases in this validation plan (see Section 2). They do not require plan changes to proceed with implementation, but the `GraphDialogLayer` propagation gap (Issue 6) should be added as a task to avoid a compile surprise during Step 1.5.6.

### Gate Q4: Is the tech stack validated (no new deps needed beyond what's in build.gradle.kts)?

**YES — no new dependencies required.**

| Requirement | Dependency | Status |
|---|---|---|
| Ktor HTTP client (device flow) | `io.ktor:ktor-client-core:3.1.3` | Already in `commonMain` |
| Ktor content negotiation | `io.ktor:ktor-client-content-negotiation:3.1.3` | Already in `commonMain` |
| Ktor JSON serialization | `io.ktor:ktor-serialization-kotlinx-json:3.1.3` | Already in `commonMain` |
| Mock Ktor engine (tests) | `io.ktor:ktor-client-mock:3.1.3` | Already in `jvmTest` |
| Arrow Either | `io.arrow-kt:arrow-core` | Already in `commonMain` |
| Roborazzi (screenshot tests) | `io.github.takahirom.roborazzi` | Already in `jvmTest` |
| JFileChooser / FileDialog | Part of JDK AWT | No new dep |
| Android SAF `ACTION_OPEN_DOCUMENT` | Android SDK (no library) | No new dep |
| `openInBrowser(url)` | `platform/OpenInBrowser.kt` — already implemented | Reuse existing, no new dep |
| Clipboard copy | `export/ClipboardProvider.kt` and `ui/PlatformClipboardProvider.kt` — already implemented | Reuse existing, no new dep |

The plan correctly identifies that T2.3.7 (clipboard + browser open) should reuse the existing `openInBrowser()` expect/actual and `ClipboardProvider` abstraction, not create new expect/actual pairs. Test cases TC-O-012 and TC-O-013 enforce this by injecting fake instances of the existing types.

---

## 5. Test Implementation Notes

### businessTest placement

The following tests belong in `businessTest` (pure Kotlin, no Compose dependency):

- TC-O-001, TC-O-002 (enum serialization)
- TC-O-003 through TC-O-009 (all `GitHubDeviceFlowClient` MockEngine tests)
- TC-O-017 (`GitSyncService.CredentialExpired`)
- TC-O-019 (auth type switch token deletion)
- TC-R-002 (`toUiMessage()` exhaustiveness)
- TC-R-003 (compile-time only — not a runtime test)

### jvmTest placement

The following tests require Compose and belong in `jvmTest`:

- TC-P-001 through TC-P-004 (JVM file picker unit tests — require JVM runtime)
- TC-P-009 through TC-P-013 (UI / screenshot)
- TC-O-010 through TC-O-016 (UI / screenshot)
- TC-O-018 (UI)
- TC-R-001 (screenshot regression)

### androidUnitTest placement

- TC-P-005, TC-P-006, TC-P-007 (Android SSH key picker — require Android `Context`)

### Roborazzi baseline update procedure

New screenshot tests (TC-P-012, TC-P-013, TC-O-014, TC-O-015, TC-O-016, TC-O-020, TC-R-001) require recording golden images before they can be used as pass/fail gates:

```bash
./gradlew jvmTest -Proborazzi.test.record=true
```

Run this once after the UI changes are implemented, commit the `.png` files in `kmp/build/outputs/roborazzi/`, and subsequent CI runs will diff against these baselines.

### MockEngine client setup (applies to TC-O-003 through TC-O-009)

All `GitHubDeviceFlowClient` tests must build the test client with `ContentNegotiation` installed to avoid `NoTransformationFoundException`:

```kotlin
private fun buildClient(engine: MockEngine): GitHubDeviceFlowClient {
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return GitHubDeviceFlowClient(client)
}
```

This pattern is already established in `ClaudeLlmFormatterProviderTest.kt` and should be replicated exactly.

### `DeviceFlowPollState.NetworkError` fix (prerequisite for TC-O-009)

Before TC-O-009 can be written, `DeviceFlowPollState.NetworkError` must be changed from `data object` to `data class NetworkError(val message: String)` in `GitHubDeviceFlow.kt` (adversarial review minor issue). The test calls `onStateChange(DeviceFlowPollState.NetworkError("Connection refused"))`.
