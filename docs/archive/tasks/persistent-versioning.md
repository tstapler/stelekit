# Feature Plan: Persistent Versioning (SQLite-Only)

## Epic Overview
**User Value**: This feature ensures that the Logseq-KMP editor remains perfectly synchronized even during high-frequency typing and background file reloads. It prevents the "Editor Replay" bug where local typing can be overwritten by stale database updates, while keeping Markdown files clean and versionless.

**Success Metrics**:
- Zero occurrences of "Editor Replay" (BUG-007) during daily driver usage.
- Markdown files remain 100% compatible with official Logseq (no `version::` properties).
- App restarts preserve the synchronization state for existing blocks.

**Scope**:
- **Included**: SQLite schema management for versions, Repository mapping, ViewModel version increments, GraphLoader version preservation.
- **Excluded**: Storing versions in Markdown files, Cross-device version sync (out of scope for MVP).

---

## Architecture Decisions

### ADR 008: SQLite-Only Versioning
- **Context**: To fix BUG-007, we need a way to distinguish "Stale" vs "Fresh" updates from the repository.
- **Decision**: Store a monotonically increasing `version` number in the SQLite `pages` and `blocks` tables.
- **Rationale**: 
    - Keeping versions in SQLite keeps the "Daily Driver" UX fast and safe.
    - Excluding versions from Markdown ensures the app remains a "good citizen" in the Logseq ecosystem, not polluting user files with internal sync metadata.
- **Consequences**:
    - If the database is deleted, versions reset to 0 (acceptable).
    - `GraphLoader` must be careful not to overwrite a high DB version with 0 when re-scanning files.

---

## Story Breakdown

### Story 1: Version-Aware Data Layer [1 week]
**User Value**: Establishes the "source of truth" for versions in the database.
- **Acceptance Criteria**:
    - SQLite schema supports `version` column.
    - All Block/Page save operations increment the version in the DB.
    - Repository mapping preserves versions during retrieval.

### Story 2: Version-Agnostic File Persistence [2 days]
**User Value**: Ensures user files remain clean.
- **Acceptance Criteria**:
    - `GraphWriter` does NOT include the version field when writing to `.md`.
    - `GraphLoader` preserves existing DB versions if file content matches.

---

## Atomic Tasks

### Task 1.1: Schema & Model Hardening [Micro - 1h]
- **Objective**: Ensure `version` column exists and models have default values.
- **Files**: `SteleDatabase.sq`, `Models.kt`.
- **Implementation**:
    - Verify `version INTEGER NOT NULL DEFAULT 0` in `pages` and `blocks`.
    - Ensure `Block` and `Page` data classes have `val version: Long = 0`.
- **Validation**:
    - Build project to verify SQLDelight code generation.

### Task 1.2: Repository Mapping Update [Small - 2h]
- **Objective**: Map the `version` field in both directions (Domain <-> DB).
- **Files**: `SqlDelightBlockRepository.kt`, `SqlDelightPageRepository.kt`.
- **Implementation**:
    - Update `toDomainBlock` / `toDbBlock` to include version.
    - Ensure `insertBlock` query passes the version through.
- **Validation**:
    - Unit test verifying a block saved with version 5 is retrieved with version 5.

### Task 1.3: Version Preservation in GraphLoader [Medium - 3h]
- **Objective**: Prevent `GraphLoader` from resetting versions to 0 on file reload.
- **Files**: `GraphLoader.kt`.
- **Implementation**:
    - When parsing a page, fetch the existing record from DB.
    - If content is identical to DB, keep DB version.
    - If content changed externally, reset version to 0 (or increment DB version to signify "External Truth").
- **Validation**:
    - Manual Test: Type in app (version > 0), restart app, verify version is still > 0 in DB.

---

## Dependency Visualization
```
[Task 1.1: Schema] -> [Task 1.2: Repo Mapping] -> [Task 1.3: Loader Preservation]
                                            \-> [UI Sync (Implemented)]
```

## Context Preparation Guide
### Task 1.3: Loader Preservation
- **Files to load**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt` - Main logic.
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt` - Repository interfaces.
- **Concepts**: 
    - Understand the `ParseMode.FULL` vs `ParseMode.METADATA_ONLY` flow in `GraphLoader`.
    - Identify where the `Page` and `Block` constructors are called after parsing.

---

## Success Criteria
- [ ] Build passes on JVM and Android.
- [ ] Typing in the editor does not trigger "flicker" or data loss.
- [ ] Markdown files on disk contain NO `version::` or `id::` properties unless they were already there.
- [ ] Database survives app restarts with versions intact.
