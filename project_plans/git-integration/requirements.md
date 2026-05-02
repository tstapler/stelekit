# Git Integration — Requirements

## Problem Statement

SteleKit users maintain a personal wiki in a git repository shared across multiple machines (desktop + mobile). Currently, syncing requires leaving the app to run git commands in a terminal (e.g., Termux on Android). When two machines both edit journal entries, merge conflicts must be resolved outside the app. The goal is to bring git operations — fetch, merge, commit, push — directly into SteleKit's UI so users never need to leave the app to stay in sync.

## Users & Context

- Primary user: single person, multi-device (desktop + Android + iOS)
- Wiki is stored as Markdown files in a **subdirectory** of a git repository (not the repo root)
- On Android, the repo is currently cloned via Termux; the wiki subdirectory is opened separately
- Other machines push journal entries independently; conflicts arise when two machines write the same journal file

---

## Functional Requirements

### FR-1: Repository Attachment

1. The user can point SteleKit at a **folder that is already a git clone** — no in-app clone needed initially
2. The user can **clone a repo from a URL** within the app (HTTPS or SSH remote URL)
3. After a repo is attached or cloned, the user selects the **subdirectory** within that repo to use as the wiki root
4. The repo URL, branch, subdirectory path, and auth method are persisted per-graph

### FR-2: Two-Way Sync

1. SteleKit can **pull** (fetch + merge) from the configured remote
2. SteleKit can **push** committed local changes to the remote
3. On **app launch**, SteleKit checks for remote changes before the user can start editing (fetch-only, non-blocking but presented clearly in the UI)
4. **Background polling** checks the remote at a user-configurable interval (default: 5 minutes) while the app is open
5. A **manual Sync button** is always available to trigger an immediate fetch+merge+push cycle

### FR-3: Safe Sync — Never Clobber Active Edits

1. When new remote commits are detected, SteleKit shows a **notification/badge** ("N new commits available") but does **not** auto-apply them
2. The user must explicitly trigger a sync to apply remote changes
3. If the user is actively editing a block when a sync is triggered, the sync **waits** until the current editing session ends (block loses focus or user saves)
4. The commit-per-session model: changes are staged and committed as a batch at the end of a session or when the user manually triggers sync, not on every individual save

### FR-4: Conflict Resolution UI

1. When a merge produces conflicts, SteleKit surfaces a **conflict resolution screen**
2. Conflicted blocks are presented as a **side-by-side diff**: left = local version, right = remote version
3. For each conflict, the user can:
   - Accept local (keep mine)
   - Accept remote (take theirs)
   - Edit a merged result manually
4. After resolving all conflicts, the user confirms and SteleKit completes the merge commit
5. Resolution state is persisted — if the user closes the conflict screen mid-way, their choices so far are remembered

### FR-5: Authentication

1. Support **SSH key** authentication (uses the key already configured on the device)
2. Support **HTTPS + personal access token** (token stored in the system keychain / secure storage)
3. Auth method is configured per-repo at setup time
4. On Android, SSH key path must be configurable (default: `~/.ssh/id_rsa` or Termux equivalent)

### FR-6: Status Visibility

1. The app shows git sync status prominently: last sync time, pending local commits, pending remote commits
2. Sync errors (network failure, auth failure, conflict) surface as actionable notifications, not silent failures
3. The git log for the current repo is viewable in-app (last N commits, optional)

---

## Non-Functional Requirements

- **Platform scope**: Android, Desktop (JVM), iOS — all three platforms for initial release
- **Safety**: A sync operation must never silently overwrite the user's unsaved or in-progress edits
- **Performance**: Fetch operations run off the main thread; UI remains responsive during network I/O
- **Offline**: If no network is available, sync is gracefully skipped and the user is notified; the app functions fully offline

---

## Out of Scope (v1)

- Branch management (creating/switching branches from within the app)
- Rebase workflow (merge-only for conflict resolution)
- Git blame / line history viewer
- Support for multiple remotes per repo
- Submodule support
- Automatic resolution of non-overlapping same-file changes (three-way merge is used; SteleKit does not write its own merge strategy)

---

## Success Criteria

1. User can open a Termux-cloned repo in SteleKit (Android) and sync without leaving the app
2. When a second machine pushes a journal entry, SteleKit detects it within the configured poll interval and notifies the user
3. If the user was editing when a push arrived, their draft is not lost and no merge happens until they explicitly trigger it
4. Conflicting journal entries surface a side-by-side diff; user resolves and the repo ends in a valid merged state
5. The workflow from "remote has new commits" → "merged and synced" requires zero terminal commands

---

## Open Questions / Risks

- **JGit vs libgit2 vs system git**: KMP git library choice is a critical early decision. JGit is JVM-only; libgit2 (via Kotlin/Native or JNI) could cover all platforms; shelling out to system `git` is simplest but unavailable on iOS.
- **SSH agent forwarding on Android/iOS**: Mobile SSH key access is non-trivial; may need in-app key file selection.
- **Subdirectory GraphLoader alignment**: `GraphLoader` currently opens a directory as the graph root; attaching a git repo at a parent path while the wiki lives in a child directory needs care to avoid watching unrelated files.
- **Three-way merge semantics at block level**: Git operates on lines; SteleKit models blocks. The conflict resolution UI needs to map git conflict hunks back to block boundaries.
