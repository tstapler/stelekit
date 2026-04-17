# Task: UUID-Native Block Storage Implementation

## Objective
Migrate the entire application (Schema, Models, Repositories, GraphLoader, ViewModels) from numeric IDs to UUID-native storage to enable cross-device merge, content deduplication, and replication support.

## Context
- Primary File: `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq`
- Supporting Files: 
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`

## Implementation Status (March 2026)
- [x] **Phase 1: Schema migration** - Added UUID PKs and FTS5 compatibility.
- [x] **Phase 2: Model & repository layer** - Removed `id: Long` in favor of UUID-only identity.
- [x] **Phase 3: GraphLoader UUID-native loading** - Populates `left_uuid` and uses content hashing.
- [x] **Phase 4: Test refactor** - Updated all 130+ tests to use UUIDs.

## Validation
- [x] All existing JVM tests pass with UUID-based identities.
- [ ] Regression testing for content deduplication during file re-scans.
- [ ] Verification of sibling chain integrity (`left_uuid`) during block moves.

## Completion Criteria
1. No numeric IDs used as primary keys in business logic.
2. UUIDs are generated during block creation and preserved across sessions.
3. `GraphLoader` correctly maps file structure to UUID-indexed database.
