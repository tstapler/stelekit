# Git Setup UX — Requirements

## Problem Statement

The Git Setup wizard (`GitSetupScreen`) currently uses plain `OutlinedTextField` inputs for file paths (repo root, SSH key path, wiki subdirectory). Users must type full absolute paths by hand, which is error-prone and slow — especially on mobile. Additionally, GitHub authentication only supports raw Personal Access Token entry; there is no OAuth flow, so users must manually navigate to GitHub, generate a PAT, copy it, and paste it — a multi-step process that breaks on every token rotation.

These two friction points are the primary barriers to completing git sync setup on first install.

---

## Priority & Roadmap

**Priority**: P0 — onboarding blocker. Git sync setup is the primary differentiator vs. Logseq's built-in sync; every user who bounces during setup is lost. No dependency on unreleased platform features. Implement before v1.0 public beta.

**RICE estimate**: Reach 100% of git-sync users · Impact 3 (removes a hard blocker) · Confidence 90% · Effort 1 sprint → RICE ≈ 270 (top of backlog).

---

## Context

- Existing code: `GitSetupScreen.kt` (5-step wizard), `CredentialStore` (expect/actual, platform secure storage), `PlatformFileSystem.android.kt` (SAF + `onPickDirectory` callback already wired), `DesktopFilePicker.kt` (JFileChooser for images), `IosGitRepository.kt` (iOS platform target exists)
- Auth types: `GitAuthType.NONE`, `GitAuthType.SSH_KEY`, `GitAuthType.HTTPS_TOKEN` — a new `GITHUB_OAUTH` type will be added
- The `CredentialStore` already persists tokens securely per-platform (Keystore on Android, Keychain on iOS, encrypted file on JVM)

### Target Users
- **Primary**: Multi-device Logseq user migrating to SteleKit on desktop + Android, who knows what git is but does not run git commands daily.
- **Secondary**: Mobile-first Android user who has never opened a terminal; relies on Termux or SSH clients for git today.

---

## Functional Requirements

### FR-1: Native File/Directory Picker — All Platforms

**Job to be done**: *When setting up git sync, I want to navigate to my repo folder using a familiar file browser, so I don't have to remember or type an absolute path.*

**FR-1.1 Desktop (JVM)**
- Step 2 "Local repository root path" field gets a "Browse…" button that opens a `JFileChooser` in `DIRECTORIES_ONLY` mode
- Step 3 SSH key path field gets a "Browse…" button that opens a `JFileChooser` in `FILES_ONLY` mode
- Both pickers use the system look-and-feel and run on the AWT EDT (same pattern as `DesktopFilePicker.pickImageFile`)
- Pre-populate the chooser's current directory from the existing text field value if it parses as a valid directory

**FR-1.2 Android**
- Step 2 repo root field gets a "Browse…" button that launches `ACTION_OPEN_DOCUMENT_TREE` via the existing `onPickDirectory` callback already plumbed into `PlatformFileSystem.android.kt`
- Step 3 SSH key path field gets a "Browse…" button that uses `ACTION_OPEN_DOCUMENT` (single file picker) for key files
- Returned SAF URI is converted to a human-readable path (or `saf://...` path) using the existing SAF path helpers in `PlatformFileSystem.android.kt`

**FR-1.3 iOS**
- Step 2 repo root field gets a "Browse…" button that invokes `UIDocumentPickerViewController` in directory-selection mode
- Step 3 SSH key path field gets a "Browse…" button for file selection
- The selected URL is passed back as a bookmark-persisted path (or security-scoped URL string)

**FR-1.4 Common**
- Browse buttons appear as trailing icon buttons (`Icons.Default.FolderOpen` / `Icons.Default.Key`) inside or adjacent to each text field
- Picking a path populates the text field; the text field remains editable afterward
- On platforms where the picker returns `null` (user cancelled), the text field is unchanged
- A `expect fun interface DirectoryPicker` / `FilePicker` abstraction in `commonMain` is wired per-platform; `GitSetupScreen` receives it as a parameter

---

### FR-2: GitHub OAuth Device Flow

**Job to be done**: *When connecting my GitHub repository, I want to authenticate without leaving the app to generate a token, so I can complete setup in under two minutes.*

**FR-2.1 New auth type**
- A new `GitAuthType.GITHUB_OAUTH` enum value is added to `git/model/GitAuthType.kt`
- When `authType == GitAuthType.GITHUB_OAUTH`, the stored credential is an OAuth access token (not a PAT), keyed as `git_github_oauth_<graphId>`

**FR-2.2 Device flow UI in Step 3**
- When the user selects "GitHub (OAuth)" in the auth radio group, a "Connect GitHub Account" button appears
- Tapping it starts the device flow:
  1. POST `https://github.com/login/device/code` with `client_id` and `scope=repo`
  2. Display the 8-character user code prominently (e.g. `ABCD-1234`) and the verification URL (`github.com/login/device`)
  3. A "Copy code" button copies the code to the clipboard
  4. An "Open GitHub" button opens the verification URL in the system browser
  5. The app polls `https://github.com/login/token` every `interval` seconds as specified by GitHub's response
  6. On success, the access token is stored in `CredentialStore` under `git_github_oauth_<graphId>`
  7. The UI shows a green checkmark and "Connected as @username" (fetched via `https://api.github.com/user`)
  8. On error or timeout (10-minute expiry), a retry button appears

**FR-2.3 Token use**
- When `authType == GITHUB_OAUTH`, `GitAuth` uses `HttpsToken` with `username = "x-oauth-basic"` and the stored OAuth token as the password (standard GitHub OAuth-over-HTTPS pattern)
- The GitHub OAuth client ID is stored as a build config constant (`GITHUB_CLIENT_ID`); the default is the GitHub CLI's open-source client ID (`178c6fc778ccc68e1d6a`) or a registered app ID via `local.properties`

**FR-2.4 Token refresh / re-auth**
- If `GITHUB_OAUTH` auth fails with a 401 during sync, `SyncState` emits a new `CredentialExpired` state
- The sync settings screen shows a "Re-connect GitHub" button in this state
- Re-clicking "Connect GitHub Account" in Step 3 (edit mode) replaces the stored token

**FR-2.5 Revocation**
- Deleting a git config (or switching away from OAuth auth type) calls `CredentialStore.delete(key)` to remove the token

### FR-2.6: GitHub SAML SSO Organizations

1. GitHub organizations with SAML SSO enforcement return HTTP 403 (not 401) when an OAuth token has not been authorized for SSO. `GitSyncService` must distinguish this case from a generic permission error.
2. Detection: HTTP 403 response with an `X-GitHub-SSO` response header present.
3. The app shows a distinct error: "Your GitHub organization requires SAML SSO authorization. Visit github.com/settings/tokens to authorize this token for your organization." with a button to open that URL.
4. This error does NOT trigger `CredentialExpired` state — the token is valid but needs SSO authorization, which cannot be done in-app.

---

## Non-Functional Requirements

- **Platform scope**: Android, JVM desktop, iOS for file pickers; all platforms (including wasmJs stub) for `GITHUB_OAUTH` auth type
- **Security**: OAuth token stored in the same `CredentialStore` backend as HTTPS tokens (Keystore/Keychain/encrypted-file); never logged or included in error messages
- **Network**: Device flow polling runs on `PlatformDispatcher.IO`; UI updates via `StateFlow` — no blocking the main thread
- **Offline**: If polling the GitHub token endpoint fails with a network error, the error is surfaced to the user; the device code dialog stays open until expiry or manual cancel
- **No GitHub App secret**: Device flow for public clients requires only a `client_id`, no `client_secret`. The client ID is embedded in the build; this is the documented GitHub-approved pattern for CLI/native apps.

---

## Assumptions

- The device has a system browser (or can open URLs) to complete the GitHub OAuth device flow. On locked-down Android kiosk profiles without a browser, the OAuth flow will fail with a clear error and fall back to prompting the user to use HTTPS token auth instead.
- The user has internet access during initial git setup. Offline use after setup is fully supported (see NFR: Offline).

## Out of Scope

- GitLab / Bitbucket OAuth (HTTPS token covers them; OAuth is GitHub-specific for now)
- Refresh tokens (GitHub OAuth access tokens do not expire unless revoked; device flow issues long-lived tokens)
- In-app SSH key generation
- Web (wasmJs) file picker (wasmJs has no git sync; stubs only)

---

## Success Criteria

1. On Desktop: clicking "Browse…" next to the repo root field opens a native folder picker; the chosen path fills the text field
2. On Android: clicking "Browse…" triggers the SAF folder picker; selected URI populates the field
3. On iOS: clicking "Browse…" opens `UIDocumentPickerViewController`; selected path populates the field
4. "GitHub (OAuth)" auth type is selectable in Step 3; device flow completes end-to-end; subsequent fetch with the stored token succeeds against a private GitHub repo
5. If the token is later revoked, the app surfaces a `CredentialExpired` state and offers re-auth without requiring the user to visit GitHub settings manually
6. All existing `GitSetupScreen` jvmTest screenshot tests still pass (no layout regressions)

## Outcome Metrics

| Metric | Baseline | Target | How to Measure |
|---|---|---|---|
| Git sync setup completion rate | TBD (pre-release) | ≥ 60% of users who open GitSetupScreen reach Step 5 and save a valid config | Telemetry event: `git_setup_saved` / `git_setup_opened` |
| GitHub OAuth adoption | 0% (not yet available) | ≥ 40% of new git configs use GITHUB_OAUTH within 30 days | Telemetry: `auth_type` field on `git_config_saved` event |
