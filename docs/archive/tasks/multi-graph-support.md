# Task: Multi-Graph Support Implementation

## Objective
Allow users to manage multiple knowledge graphs with per-graph SQLite databases, enabling separation of concerns and easier data management.

## Context
- Primary File: `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphManager.kt`
- Supporting Files:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/db/DriverFactory.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/JournalsViewModel.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/model/GraphRegistry.kt`

## Implementation Status (March 2026)
- [x] **Phase 1: Foundation** - Implemented hashing, `GraphInfo` model, path canonicalization, and `databaseUrlForGraph`.
- [x] **Phase 2: Repository lifecycle** - Created `GraphManager` to handle driver open/close and provide `StateFlow<RepositorySet>`.
- [x] **Phase 3: ViewModel + UI integration** - Integrated graph switcher and key-scoped ViewModels.
- [x] **Phase 4: Migration + polish** - Single-DB migration, graph removal, and status bar updates.

## Validation
- [x] Multiple graph databases are created and persisted in the correct platform-specific locations.
- [x] Switching graphs correctly updates the active repository and refreshes the UI.
- [ ] Edge case: Handling graph deletion while it's the active graph.
- [ ] Edge case: Handling concurrent access to the same graph from different instances.

## Completion Criteria
1. `GraphManager` successfully manages a registry of available graphs.
2. Each graph has its own independent SQLite database file.
3. The UI allows seamless switching between graphs without application restart.
