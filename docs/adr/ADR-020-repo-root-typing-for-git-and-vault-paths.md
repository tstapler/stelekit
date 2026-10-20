# ADR-020: Type git and vault paths as `RepoRoot`

**Status**: Accepted
**Date**: 2026-10-19
**Backlog**: 7a793e45 (GitHub issue #451)

## Context
The notes-folder typing work (#439–#446) made writers target `GraphInfo.effectiveNotesPath` (repo root + wiki subdir). Git paths and the vault are different: `.git` lives at the repo root, and `GitConfig`, `GitRepository.init/clone`, `LfsRulesLoader` and `FileSystemLfsContentProbe` all take a raw `String` for it. `VaultCredentialStore` and `VaultManager` currently receive the notes path (`GraphContentVaultSetup`), so `.stele-credentials` and `.stele-vault` sit inside the wiki subfolder. Typing these as `EffectiveNotesPath` would be wrong for git and entrench the vault placement.

## Decision
Owner decision, 2026-10-19:
- Git paths (`GitConfig.repoRoot`, `GitRepository.init/clone`, `LfsRulesLoader`, `FileSystemLfsContentProbe`) are typed `RepoRoot`.
- Vault files (`.stele-credentials`, `.stele-vault`, and the hidden-reserve sentinel) live at the repo root, and `VaultCredentialStore` / `VaultManager` take `RepoRoot` (from `GraphInfo.repoRootObj`).

## Consequences
- Flat-layout graphs (no wiki subdir): repo root equals notes path, so nothing moves.
- **Migration note (wiki-subfolder users):** vault files move from `<root>/<wikiSubdir>/.stele-*` to `<root>/.stele-*`. On first open after upgrade, if the repo-root file is absent and the notes-folder file exists, move it (copy, verify, then delete) before the vault or credential store reads. Without that step these users would see no vault and have to re-authenticate. Never overwrite an existing repo-root file.
- `RepoRoot` needs `@Serializable` (bare-string form) before `GitConfig` can use it. `git_config.repo_root` stays TEXT, so no DB migration.
- Android WorkManager input data packs `RepoRoot.value` and re-wraps on read.

## Implementation
Tracked as its own tranche, following `plan.md` phases 1–5 (git types first, then vault + migration, then tests: subdir-layout vault path, WorkManager round trip, `GitConfig` serialization).
