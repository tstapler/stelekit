# Migration Plan: Git Sync / Version Control

## 1. Discovery & Requirements
Logseq supports versioning graphs using Git. This allows for backup, history, and collaboration.

### Existing Artifacts
- `src/main/frontend/components/repo.cljs`: UI for Git status/commit.
- `src/main/frontend/handler/repo.cljs`: Git command handling.

### Functional Requirements
- **Auto-Commit**: Automatically commit changes after a timer.
- **Manual Commit**: User triggers commit with message.
- **Sync**: Push/Pull from remote (GitHub/GitLab).
- **History**: View file history and diffs.

### Non-Functional Requirements
- **Performance**: Git operations should not freeze the UI.
- **Security**: Secure storage of SSH keys and Personal Access Tokens.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **GitService**: Interface for Git operations.
    - **Desktop/Android**: Use **JGit** (Pure Java implementation).
    - **iOS**: Use **Objective-Git** (libgit2 bindings) or a KMP wrapper around `libgit2`.
- **CredentialStore**: Secure storage (Keychain/Keystore) for auth tokens.

### UI Layer (Compose Multiplatform)
- **Component**: `GitStatusPanel`.
- **Component**: `HistoryView` (List of commits).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Logic: Merge Conflicts [SEVERITY: High]
- **Description**: Auto-syncing often leads to conflicts if the user edits on two devices.
- **Mitigation**:
    - Strategy 1: "Union Merge" for text files (risky).
    - Strategy 2: "Ours/Theirs" auto-resolution (data loss risk).
    - Strategy 3: Detect conflict, stop sync, ask user. (Safest).

### 🐛 Performance: Large Repo Bloat [SEVERITY: Medium]
- **Description**: `.git` folder can grow huge. JGit can be slow on large repos.
- **Mitigation**: Shallow clones? (Hard for history). Periodic `git gc`. Run operations in background thread.

## 4. Implementation Roadmap

### Phase 1: Core Operations
- [ ] Integrate JGit (JVM) and libgit2 (Native).
- [ ] Implement `init`, `add`, `commit`, `status`.

### Phase 2: Remote Sync
- [ ] Implement `push`, `pull`.
- [ ] Implement Credential Management.

### Phase 3: Conflict Handling
- [ ] Implement conflict detection logic.
- [ ] Create UI for resolving conflicts.

## 5. Migration Checklist
- [ ] **Logic**: Git commit/push/pull works on Desktop.
- [ ] **Logic**: Git commit/push/pull works on Mobile.
- [ ] **UI**: Git status shows correct info.
- [ ] **Security**: Credentials stored securely.

