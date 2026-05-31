# Git Sync — UX Improvements

UX review findings from triad review, covering multi-graph integration, user flows, and credential management.

## Critical (data integrity / security)

- [ ] **Fix merge-cancel data corruption** — `ConflictResolutionScreen` "Cancel" leaves git in `MERGING` state; next sync fails silently. Fix: call `gitRepository.abortMerge(config)` on cancel, or replace Cancel with a warning dialog. (`GraphDialogLayer.kt`, `ConflictResolutionScreen.kt`, `GitSyncService.kt`)

- [ ] **Mask HTTPS token field** — Step 3 of `GitSetupScreen` uses a plain `OutlinedTextField` for the personal access token. Add `visualTransformation = PasswordVisualTransformation()`. (`GitSetupScreen.kt` step 3)

## High (core UX gaps)

- [ ] **Differentiate error subtypes in SyncStatusBadge** — All `SyncState.Error` subtypes show truncated raw error text. Add `fun DomainError.GitError.toSyncErrorMessage(): String` in `DomainError.kt` mapping each subtype to plain language. For `AuthFailed` specifically: show "Authentication failed — tap to update credentials" and route tap to Step 3 of the git wizard. (`SyncStatusBadge.kt`, `DomainError.kt`, `StelekitViewModel.kt`)

- [ ] **Pre-fill repoRoot from active graph path in wizard** — Step 2 re-asks for the repo path even though `GraphInfo.path` is already known. Pass `graphPath: String = ""` to `GitSetupScreen` and use it as default for `repoRoot`. Thread `graphManager.getGraphInfo(activeGraphId)?.path` from `GraphDialogLayer`. (`GitSetupScreen.kt`, `GraphDialogLayer.kt`)

- [ ] **Disable manual sync button when git is not configured** — Tapping "Sync now" when no `GitConfig` exists silently does nothing. Check if git is configured; if not, disable the `IconButton` and set `contentDescription = "Git sync not configured"`. (`SyncStatusBadge.kt`, `Sidebar.kt`)

## Medium

- [ ] **Show git-backed indicator per graph in dropdown** — `GraphItem` in the graph switcher dropdown shows no distinction between git-backed and local-only graphs. Add a small `Icons.Default.Sync` icon (tinted `primary`) to git-configured graphs. Requires `GraphInfo` to carry `hasGitSync: Boolean` or check `gitConfigRepository` at render time. (`Sidebar.kt`, `GraphInfo.kt` or equivalent)

- [ ] **Delete credentials on graph removal** — `GraphManager.removeGraph()` does not call `CredentialStore.delete("git_https_token_$id")`. Add credential cleanup on removal. (`GraphManager.kt`, `CredentialStore`)

- [ ] **Add SSH key passphrase field** — `GitAuth.SshKey.passphraseProvider` is hardcoded to `{ null }` in the wizard save path. Add an optional passphrase `OutlinedTextField` (masked) in Step 3. (`GitSetupScreen.kt`)

- [ ] **Auto-switch to newly cloned graph** — After `cloneAndAdd` completes, the user must manually select the new graph from the dropdown. Call `graphManager.switchGraph(newGraphId)` after successful clone in the wizard's save callback. (`GitSetupScreen.kt`, `GraphDialogLayer.kt` or `App.kt`)

- [ ] **Add explanatory subtitle to ConflictResolutionScreen** — The screen title says "Resolve Merge Conflicts" with no context for why the user is here. Add subtitle: "A background sync pulled remote changes that conflict with your local edits. Choose which version to keep for each file." (`ConflictResolutionScreen.kt`)

## Low

- [ ] **Correct security copy on JVM desktop** — "Token is stored securely on device" overstates JVM protection (PBKDF2 from username+OS, not hardware keystore). Change copy to: "Token is encrypted on disk using device-specific keys. For stronger protection, use SSH key auth." (`GitSetupScreen.kt` Step 3)

- [ ] **Unify "Add Graph" and "Clone remote" entry points** — Two disconnected entry points (graph switcher → directory picker vs. sidebar nav → git wizard) violate established conventions (GitHub Desktop, Logseq). Replace "Add Graph..." dropdown item with an "Add Graph" dialog offering: "Open local folder" vs. "Clone from remote URL." The clone path calls `GraphManager.cloneAndAdd` directly. (`Sidebar.kt`, `App.kt`, `GraphDialogLayer.kt`)
