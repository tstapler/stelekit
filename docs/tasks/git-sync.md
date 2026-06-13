# Git Sync — Desktop v1 (JVM + Android)

## Problem
Technical knowledge workers who use SteleKit for personal knowledge management have no way to version or sync their notes across machines without relying on a proprietary cloud service. They already use git for code and want the same workflow for their notes. Without this, their data is siloed to one device with no history.

## Target User
Power users: developers and technical writers who are comfortable with git, SSH keys, and personal access tokens. Non-technical users are out of scope for v1.

## Success Metrics
- 30-day retention of graphs with git sync enabled ≥ local-only graphs (proxy: sync is not causing data loss)
- Zero reports of credential leaks or unintended commits of .db files
- Sync cycle completes in < 10s on a graph with ≤ 500 pages and a standard GitHub remote

## Scope — v1 Desktop (JVM + Android)
**In scope:**
- Add graph from existing local clone (existing directory picker flow)
- Clone a new graph from a remote URL during git setup wizard
- HTTPS personal access token authentication
- SSH key path authentication
- Periodic background sync (commit → fetch → merge → push)
- Per-file merge conflict resolution (accept-local or accept-remote per file)
- Sync status indicator in sidebar (idle, syncing, error, conflict, merge-available)

**Out of scope (explicitly deferred):**
- iOS support (libgit2 integration required — tracked separately)
- Web/WASM support
- Per-hunk conflict resolution (accept-local/remote per file is sufficient for v1)
- Git history / log screen
- Credential deletion UI (credentials can be cleared by re-running setup)
- SSH key generation within the app

## Risky Assumptions
1. **Users will trust the app with SSH keys/PATs.** Mitigation: clear in-app disclosure ("stored encrypted on device, never transmitted").
2. **Auto-merge will not cause data loss.** Mitigation: conflict detection blocks sync until user resolves; no silent auto-resolution.
3. **JGit performance is acceptable on large graphs.** Mitigation: benchmark target < 10s; shallow clone for first setup.

## Architecture (implemented)
- `GitRepository` interface → `JvmGitRepository` (JGit 7.x) + `AndroidGitRepository` (JGit 5.13)
- `CredentialStore` → JVM: AES-256-GCM + per-machine PBKDF2 salt; Android: Android Keystore
- `GitSyncService` — full sync cycle + conflict resolution
- `GitSetupScreen` — 5-step wizard for configuration
- `SyncStatusBadge` — sidebar chip for all sync states
- `ConflictResolutionScreen` — per-file accept-local/remote UI
- `GraphManager.cloneAndAdd()` — clone + register new graph in one step
