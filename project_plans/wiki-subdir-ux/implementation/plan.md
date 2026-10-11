# Implementation Plan: Wiki Subdirectory UX

## Overview

**Phase**: This is the implementation phase for the wiki-subdir-ux project, addressing the core problem where SteleKit incorrectly detects or fails to detect the notes subdirectory after Git clone, resulting in empty graphs. The implementation will be a refactoring-first effort extending existing primitives rather than building new capabilities.

**Team**: Tyler Stapler (primary developer) — all technical decisions are made unilaterally; no external collaboration tools (Jira/Trello) are used; planning follows the AIC (Atomic-InVEST-Context) framework; communication via Claude Code terminal only.

**Duration**: Estimated 4-6 weeks (sequential development with CI feedback loops)

**Dependencies**: All existing codebase dependencies; no new external libraries required. Code uses Kotlin Multiplatform with Bazel canonical build.

## Architecture

### Existing Capabilities Utilized

1. **Detection Logic (Extraction from diagnostics)**: The nested-candidate scan from `GraphDiagnostics.kt:96-102` currently runs only for diagnostics export; needs extraction into a reusable function callable from both diagnostics and warm-reconcile paths.

2. **Banner UI Pattern**: Stacked banner system (`GraphContentMainArea.kt:95-174`) with `GitDetectionBanner` (conditional display based on `gitConfig == null`). Needs extension to a new mismatch banner that fires even when git config exists but on-disk content differs.

3. **Move/Merge Dialog Pattern**: `StorageMoveChoiceDialog.kt:45-111` provides two-way decision (Relocate/Link) with even-handed UI and safety copy semantics. Needs extension to three-way choice (Move/Merge/Leave-as-is) with dry-run manifest.

4. **File Operations Primitives**: `FileSystem.kt:5-212` provides per-file `renameFile()` (single-file, destructive) and recursive `listFilesRecursiveWithModTimes()`. Directory-level recursive move and copy primitives are missing.

5. **Atomic Git Commit**: `GitSyncService.commitLocalChanges()` (line 450-475) stages subdirectory changes and commits atomically. Wrappers (`WasmGitWriteService.commit()`) provide platform-specific equivalents.

6. **Database Write Serialization**: `DatabaseWriteActor` serializes all DB writes through a single coroutine, eliminating SQLite contention.

7. **Git Sync Coordination**: `GitSyncBusyCounter` (lines 68, 93-98) provides synchronization primitive for sequencing reconciliation commits against in-flight auto-sync, used in §Rabbit Holes race condition mitigation.

### Key Architectural Decisions

1. **Refactor-First**: Extract detection logic, extend banner system, expand dialog pattern, and assemble per-file operations into directory-level moves. All new behavior must be opt-in, not a breaking change.

2. **SAF Limitations**: Android's `DocumentsContract` has no atomic "move" primitive. Move operations must use copy-then-delete fallback pattern (mirroring existing `safRenameFile()`), with `FLAG_SUPPORTS_MOVE` capability detection.

3. **Candidate Caching**: The nested-candidate scan must be cached to avoid expensive re-scans, especially on SAF. Heuristic gating (only scan when page count is below threshold) bounds expensive scans. Cached candidate scan is stored in `GraphInfo.detectedWikiSubdir` (already persists via `updateGraphInfoDetection()` in `GraphManager.kt:1042-1043`) and re-scanned during warm reconcile.

4. **Crash Safety**: Marker files (`.stelekit/move-in-progress.json`) with transaction logs and resumption support. On next launch, `GraphLoader.warmReconcile` checks for the marker and surfaces a "Resume move" or "Cancel and leave as-is" prompt. The soft-delete trash holds pre-move originals so a crash during move does not orphan files.

5. **Soft-Delete**: Metadata-marked-for-deletion approach for SAF (no physical staging — copy-to-trash costs an extra full content-provider round-trip on Android), and file-based trash at `.stelekit/trash/<timestamp>/` on local platforms (JVM Desktop). The trash holds originals from the start of a Move operation (not post-hoc), so if a partial failure occurs mid-move, successful copies are preserved and failed files remain in the original location (research §4.2: "leave successful copies in place, don't roll back").

6. **DatabaseWriteActor Integration**: All `GraphInfo` field updates (e.g. `contentMismatchBannerDismissed`) route through `DatabaseWriteActor` via `updateGraphField()` (`GraphManager.kt:1035`), which serializes writes and flushes before any filesystem move begins — preventing race between registry persistence and reconciliation commits.

7. **GitSyncBusyCounter for Quiescence**: The counter (lines 68, 93-98, `GitSyncService.kt:65-68`) provides `begin()`/`end()`/`awaitIdle()` — a suspend function that waits until count reaches 0. The relocate/quiesce sequence calls `awaitIdle()` *before* staging subdirectory changes in the git index, ensuring no `GitSyncService.sync()` call is mid-flight during a move. This is the same synchronization primitive used for conflict detection (`DatabaseWriteActor.hasPendingWrites` at `DatabaseWriteActor.kt:252`), confirming the pattern is already trusted in the codebase.

8. **No DirectSqlWrite for migration-time writers**: `MigrationRunner` and `UuidMigration` carry `@OptIn(DirectSqlWrite::class)` at class level as the one approved exception (per repo CLAUDE.md). No new migration-time writer classes should be created — route all writes through `DatabaseWriteActor`.

### Integration Points

1. **GraphLoader.WarmReconcile**: Triggers candidate detection and mismatch signal emission.

2. **StelekitViewModel**: Manages candidate signal StateFlow and banner visibility.

3. **GraphContentMainArea**: Banner rendering and dialog coordination.

4. **GitSyncService**: Coordinates reconciliation commits with `GitSyncBusyCounter`.

5. **DatabaseWriteActor**: Serializes registry updates and content writes.

### Technical Constraints

- **Platform Coverage**: JVM/Desktop, Android (git + SAF), and Web/Wasm.
- **Performance**: SAF listing cost (50-500ms each) must be bounded; shallow depth (not recursive) for candidate scans.
- **Safety**: No destructive operations without dry-run preview and user confirmation.
- **Backward Compatibility**: All changes are additive; existing git-clone behavior unchanged.

## Tasks

### Workstream 1: Detection and Signal Extraction

**Task 1.1**: Extract nested-candidate scan into reusable function
- **Effort**: 2 days
- **Description**: Extract `GraphDiagnosticsCollector.appendDiskCandidate()` logic into shared utility `DirectoryScanner.scanForWikiCandidates(configuredRoot: String): List<DirectoryScanResult>` callable from both diagnostics and warm-reconcile paths.

**Task 1.2**: Create mismatch state model
- **Effort**: 1 day  
- **Description**: Add `effectivePath: String`, `contentMismatchDetected: Boolean`, and `contentMismatchBannerDismissed: Boolean` fields to `GraphInfo` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/GraphInfo.kt:18`). Extend `updateGraphInfoDetection()` (`GraphManager.kt:1042-1043`) to compare configured vs. actual paths, populating these fields. The `contentMismatchBannerDismissed` field mirrors the existing `gitDetectionDismissed: Boolean` pattern (`GraphInfo.kt:26`) — it persists per-graph whether the user has dismissed the mismatch banner, preventing immediate re-firing without a config change. All updates route through `DatabaseWriteActor` via `updateGraphField()` (`GraphManager.kt:1035`) which serializes writes and flushes before any filesystem move begins.

**Task 1.3**: Implement candidate caching and heuristic gating
- **Effort**: 2 days
- **Description**: Create `CandidateCache` that stores last scan results with TTL/heuristic (only scan when page count < threshold). Integrate into `GraphLoader.warmReconcile`.

### Workstream 2: UI Extension and Dialog Enhancement

**Task 2.1**: Extend banner system to mismatch detection
- **Effort**: 2 days
- **Description**: Add `showContentMismatchBanner` condition in `GraphContentMainArea.computeBannerVisibility()` (`GraphContentMainArea.kt:95-174`). When git config exists but `contentMismatchDetected == true`, show mismatch banner alongside existing git detection banner.
  - **Banner text** uses human-readable labels via `describeForHumans()` pattern inherited from `StorageMoveChoiceDialog` — e.g. "No notes found at the repository root — found notes in `logseq/` instead." Avoids exposing raw filesystem paths to the user.
  - **Accessibility**: Apply `Modifier.semantics { role = Role.Alert }` (Compose `SemanticsPropertyReceiver.role`) so screen readers announce the banner proactively on state change — the existing `GitDetectionBanner.kt` does not use alert roles; the mismatch banner must.
  - **Dismissal persistence**: The banner reads `GraphInfo.contentMismatchBannerDismissed` (Task 1.2) — when the user dismisses the banner via the × button, persist `contentMismatchBannerDismissed = true`. The banner re-appears on next warm reconcile only if the mismatch persists AND the user previously did NOT choose "Leave as-is" with "don't show again."
  - **Re-access path**: Add an inline "Validate now" action (brainstorm A.7) in graph settings so a dismissed banner is never the only path back to the fix flow.
  - **Dialog dismissal semantics**: Dismissing the move/merge dialog via back-button, outside-tap, or Escape key is logged as an explicit "left in place" decision (brainstorm D.27) — NOT a silent no-op. The banner persists (does not permanently suppress) on dialog dismissal; only an explicit "Leave as-is" + "don't show again" suppresses until the next config change.

**Task 2.2**: Create three-way move/merge dialog
- **Effort**: 3 days
- **Description**: Create `WikiSubdirFixDialog.kt` as sibling to `StorageMoveChoiceDialog.kt` (`StorageMoveChoiceDialog.kt:45-111`) with Move/Merge/Leave-as-is options and dry-run manifest.
  - **Choice ethics**: "Leave as-is" is the safe default (no files change — lowest risk). Do NOT replicate `StorageMoveChoiceDialog`'s purely even-handed `OutlinedButton` styling — the three-way choice has a clear risk hierarchy. Instead: Move uses a caution-colored `OutlinedButton`, Merge uses a warning-colored `OutlinedButton`, Leave-as-is is visually de-emphasized (text button or subtle styling) but clearly labeled as the safe/recommended default. Document this rationale in code comments.
  - **Accessibility**: Each option uses distinct text + icon combinations (not color alone) — e.g. a warning triangle icon on Leave-as-is, a caution icon on Merge, a destructive icon on Move. Full Tab/Enter/Escape keyboard navigation with visible focus rings. Dialog title properly labeled via `AlertDialog`'s `title=` parameter.
  - **WCAG AA contrast**: Banner text and dialog content meet 4.5:1 (normal text) / 3:1 (large text) contrast ratios.
  - **Partial-failure recovery UI**: When the move operation completes with some failures (copy succeeded for some files, failed for others), show a results state within the same dialog:
    - List succeeded files with checkmarks and failed files with error reasons (per-file granularity)
    - Three buttons: "Retry failed" (re-attempts only failed files), "Skip failed" (commits successful moves, leaves failed files at old location), "Abort" (rolls back successful moves to soft-delete trash — requires trash to be populated during the move, not after)
    - Per research §4.2: successful copies must NOT be rolled back — the soft-delete trash holds originals from the start of the move, not post-hoc.

**Task 2.3**: Extend existing conflict resolution
- **Effort**: 1 day
- **Description**: Ensure `DiskConflictDialog` integrates with the new move/merge flow for same-filename collisions. Reuse existing `DiskConflictBlockMatcher` for per-file conflict resolution. Add accessibility check to Phase C verification list.

**Task 2.4**: Assign discoverability features to tracked tasks
- **Effort**: 2 days
- **Description**: Assign brainstorm ideas A.5, A.6, A.7, A.8 to explicit tracked tasks:
  - **A.5 (auto-fill on clone)**: Run the candidate scan immediately after a fresh clone completes and pre-populate the "Notes subfolder" field with the best guess. Added to Phase A deliverables.
  - **A.6 (first-run coachmark)**: Show a coachmark on the "Notes subfolder" field the first time a fresh clone finishes, calling out that most git-backed repos nest content in a subfolder. Dismissable but does NOT suppress auto-fill or the mismatch banner.
  - **A.7 (inline "Validate now")**: Folded into Task 2.1 as a sub-feature of the banner — an inline action in graph settings that runs the on-disk existence check synchronously with pass/fail feedback.
  - **A.8 (content-path breadcrumb)**: Add `"<repo name> / <wikiSubdir>"` breadcrumb to the content area's top bar as a passive, always-visible reminder of where content is being read from. Added to Phase A deliverables.

### Workstream 3: File Operations Assembly

**Task 3.1**: Add directory-level move primitive
- **Effort**: 4 days
- **Description**: Add `FileSystem.moveDirectory(from: String, to: String): Boolean` with platform implementations:
  - **JVM**: `renameTo` with cross-volume fallback
  - **Android SAF**: `DocumentsContract.moveDocument` + `genericCopyThenDelete` fallback
  - **Wasm**: `HostDirectorySync.renameHostDirectory`

**Task 3.2**: Add copy primitive for Merge strategy
- **Effort**: 3 days
- **Description**: Add `FileSystem.copyFile(from: String, to: String): Boolean` with platform implementations:
  - **JVM**: `Files.copy`
  - **Android SAF**: `DocumentsContract.copyDocument` or manual stream-copy
  - **Wasm**: `HostDirectorySync.copyHostFile`

**Task 3.3**: Assemble directory-level move from per-file operations
- **Effort**: 2 days
- **Description**: Implement `FileSystem.moveDirectoryRecursive(from: String, to: String): Boolean` using `listFilesRecursiveWithModTimes` + `moveFile` for each file.

### Workstream 4: Crash Safety and Soft-Delete

**Task 4.1**: Create marker file system
- **Effort**: 3 days
- **Description**: Implement `.stelekit/move-in-progress.json` marker with transaction log using `kotlinx-serialization-json`. Add resume/cancel flow in `GraphManager.warmReconcile`.

**Task 4.2**: Implement soft-delete trash
- **Effort**: 2 days
- **Description**: Create trash system (`.stelekit/trash/<timestamp>/`) with retention policy. On SAF, use metadata-marked-for-deletion; on local platforms, use physical trash.

### Workstream 5: Git Sync Coordination

**Task 5.1**: Integrate GitSyncBusyCounter into reconciliation
- **Effort**: 2 days
- **Description**: Await `GitSyncBusyCounter` quiescence before staging subdirectory changes in reconciliation flow. Use existing pattern from §Rabbit Holes.

**Task 5.2**: Ensure atomic commit of file changes
- **Effort**: 1 day
- **Description**: Wire reconciliation through `GitSyncService.commitLocalChanges()` for atomic commit with appropriate messaging.

### Workstream 6: Integration and Testing

**Task 6.1**: Integrate detection signal into view model
- **Effort**: 2 days
- **Description**: Connect `DirectoryScanResult` to `StelekitViewModel` StateFlow and manage banner visibility.

**Task 6.2**: Create comprehensive regression tests
- **Effort**: 3 days
- **Description**: Follow `WikiSubdirUriGuardTest.kt` pattern for regression coverage in `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/git/`. Tests must mirror existing test structure.

**Task 6.3**: Integration test for move/merge flow
- **Effort**: 3 days
- **Description**: End-to-end test simulating clone → misconfiguration → detection → move fix → verification.

### Total Estimated Effort

| Workstream | Tasks | Days |
|------------|-------|------|
| Detection and Signal Extraction | 1.1, 1.2, 1.3 | 5 |
| UI Extension and Dialog Enhancement | 2.1, 2.2, 2.3, 2.4 | 8 |
| File Operations Assembly | 3.1, 3.2, 3.3 | 9 |
| Crash Safety and Soft-Delete | 4.1, 4.2 | 5 |
| Git Sync Coordination | 5.1, 5.2 | 3 |
| Integration and Testing | 6.1, 6.2, 6.3 | 9 |
| **TOTAL** | | **39** |

## Open Questions

### Q.1: How to handle SAF provider variability for move operations?

**Current State**: `DocumentsContract.moveDocument` has no guarantee across OEMs/providers. Some support atomic move, others require copy+delete.

**Design Options**:
1. **Capability detection**: Check `FLAG_SUPPORTS_MOVE` and fallback to copy+delete
2. **Universal fallback**: Use copy+delete for all SAF moves (simpler, but more IPC)
3. **Hybrid**: Try moveDocument first, fallback to copy+delete on failure

**Recommendation**: Use capability detection with `FLAG_SUPPORTS_MOVE` and fallback to `genericCopyThenDelete` stream-copy pattern (mirrors existing `safRenameFile()`).

### Q.2: What is the optimal threshold for warm-reconcile candidate scanning?

**Considerations**:
- SAF listing cost: 50-500ms each
- Local filesystem scan: cheap
- Graph size: some users have thousands of pages, others have <10

**Design Options**:
1. **Fixed threshold**: <20 pages always scan, else skip
2. **Dynamic threshold**: based on platform (cheap on local, expensive on SAF)
3. **Percentage threshold**: <5% of estimated max pages

**Recommendation**: Dynamic platform-based threshold — <10 pages on SAF, <50 pages on local platforms, with maximum cap of 1000 total scanned files.

### Q.3: How to implement soft-delete retention policy?

**Options**:
1. **Time-based**: Keep for 7 days, auto-cleanup weekly
2. **Event-based**: Keep until next successful git sync
3. **Size-based**: Keep until trash exceeds 1GB

**Recommendation**: Time-based with configurable retention (default 7 days). SAF path uses metadata-marked-for-deletion instead of physical staging (due to copy cost). Local paths use actual trash.

### Q.4: What's the optimal UX for confirming "Leave as-is" decisions?

**Options**:
1. Simple confirmation dialog ("This will keep the graph empty. Continue?")
2. Details disclosure (“The notes are in logseq/ subfolder, graph will be empty. Continue?")
3. Automatic timeout after 5 seconds

**Recommendation**: Details disclosure with explicit logging of the "left in place" decision (brainstorm D.27). Must be logged as explicit user choice, not silent no-op.

### Q.5: How to coordinate with existing git sync auto-sync?

**Current**: `GitSyncService` runs periodic syncs via `periodicSyncJob`.

**Design**: Use `GitSyncBusyCounter` to await quiescence before staging subdirectory changes. The counter is already documented as intended for "quiesce strategy" use.

## Phased Delivery

### Phase A: Foundation (Week 1-2)

**Deliverables**:
- [ ] Extracted candidate scan utility (Task 1.1)
- [ ] Mismatch state model with `effectivePath`, `contentMismatchDetected`, `contentMismatchBannerDismissed` on `GraphInfo` (Task 1.2)
- [ ] Candidate caching and heuristic gating (Task 1.3)
- [ ] Enhanced banner system with accessibility (alert role) and dismissal persistence (Task 2.1)
- [ ] Inline "Validate now" action in graph settings (Task 2.1 / Task 2.4, A.7)
- [ ] Content-path breadcrumb in content area top bar (Task 2.4, A.8)
- [ ] Basic three-way move/merge dialog skeleton with safe-default styling and iconography (Task 2.2)
- [ ] Auto-fill-on-clone placeholder wiring — candidate scan after clone pre-fills subcategory field (Task 2.4, A.5)
- [ ] First-run coachmark stub for "Notes subfolder" field (Task 2.4, A.6)
- [ ] File move primitives skeleton (Task 3.1 skeleton)

**CI Verification**:
- All existing tests pass
- New candidate scan extraction unit tests
- Banner display logic unit tests (including dismissal persistence)
- Dialog accessibility semantics test (alert role, keyboard nav)
- Auto-fill-on-clone scan unit test
- Dialog UI compilation

**Criteria**: Core detection and UI framework ready for development.

### Phase B: Core Move/Merge Implementation (Week 3-4)

**Deliverables**:
- [ ] Complete file move/copy primitives (Tasks 3.1, 3.2)
- [ ] Directory-level move assembly (Task 3.3)
- [ ] Partial-failure recovery UI in dialog (results state with Retry/Skip/Abort) (Task 2.2)
- [ ] Crash safety marker system (Task 4.1)
- [ ] Soft-delete trash infrastructure (Task 4.2)
- [ ] Git sync coordination integration (Task 5.1)

**CI Verification**:
- All existing tests continue to pass
- New integration tests for move operations
- Partial-failure recovery test (copy succeeds for some, fails for others, successful copies preserved)
- Crash recovery tests
- SAF compatibility tests

**Criteria**: Core move/merge functionality implemented and tested.

### Phase C: Completion and Validation (Week 5-6)

**Deliverables**:
- [ ] Complete dialog implementation (Task 2.2)
- [ ] Git sync atomic commit integration (Task 5.2)
- [ ] Comprehensive test suite (Task 6.1-6.3)
- [ ] End-to-end workflow verification
- [ ] Documentation updates
- [ ] Accessibility audit complete (manual + automated checks)
- [ ] Auto-fill-on-clone + coachmark finalized (Task 2.4)

**CI Verification**:
- All tests pass including new regression suite
- Performance benchmarks meet targets
- Manual verification of fix scenarios
- Accessibility checklist: alert role on banner, keyboard nav on dialog, color+text distinction, WCAG AA contrast
- Safari/Wayland display check (see CLAUDE.md `scripts/jvm-display-check.sh`)

**Criteria**: Full feature implementation complete and validated.

## Validation Gate

### Pre-Release Checklist

1. **Code Quality**:
   - [ ] All existing tests pass
   - [ ] New tests have ≥90% coverage (Jacoco/ktest)
   - [ ] Code follows SOLID principles and clean architecture
   - [ ] No dead code or unused imports

2. **Functional Testing**:
   - [ ] Clone scenario: Empty graph → detection banner → fix → populated graph
   - [ ] SAF scenario: SAF-mounted graph → detection works (git-detection disabled)
   - [ ] Move operation: Dry-run preview → user confirms → content relocates correctly
   - [ ] Merge operation: Same filename conflict → conflict resolution dialog
   - [ ] Leave as-is: Explicit "left in place" decision logged (not silent no-op)
   - [ ] Dialog dismissal: Back-button/outside-tap/Escape logged as "left in place"
   - [ ] Partial-failure recovery: Move with some copy failures → results UI shows per-file success/failure → Retry/Skip/Abort → successful copies preserved (no rollback)
   - [ ] Undo capability: Soft-delete trash allows restoring a bad move (Task 4.2)
   - [ ] Crash recovery: Mid-move interruption → marker file detected → resume option
   - [ ] Git sync coordination: Auto-sync during move → proper quiescence via GitSyncBusyCounter
   - [ ] Auto-fill on clone: Fresh clone with nested content → subfolder field pre-populated
   - [ ] Coachmark: First clone → coachmark shown; dismissable, doesn't suppress auto-fill or banner
   - [ ] Validate now: Inline action in graph settings → synchronous path check with pass/fail feedback
   - [ ] Banner dismissal: Dismiss × → `contentMismatchBannerDismissed` persisted; re-appears on next warm reconcile if mismatch persists (without explicit "Leave as-is")

3. **Performance Testing**:
   - [ ] SAF listing bounded by heuristic (<1000 files total scanned)
   - [ ] Directory move within acceptable time (<5 minutes for 1000 files)
   - [ ] Memory usage stable under load

4. **Platform Coverage**:
   - [ ] JVM/Desktop: All functionality verified
   - [ ] Android: Git sync + SAF move operations tested on emulator
   - [ ] Web/Wasm: Client-side move operations tested
   - [ ] iOS: Out of scope (per requirements §Scope)

5. **Accessibility** (research §4):
   - [ ] Mismatch banner uses alert role (`role = Role.Alert`) for screen reader announcement
   - [ ] Dialog fully navigable via Tab/Enter/Escape with visible focus rings
   - [ ] Three-way choice uses distinct text + icon combinations (not color alone)
   - [ ] Banner text and dialog content meet WCAG AA contrast (4.5:1 normal, 3:1 large)
   - [ ] Manual screen-reader verification on Desktop + Android

6. **Security and Safety**:
   - [ ] No plaintext credentials exposed
   - [ ] All file operations validated before execution
   - [ ] Soft-delete prevents data loss
   - [ ] Git credentials remain secure

### Decision Gates

**Gate 1**: After Phase A completion — All core detection and UI framework components implemented and tested.

**Gate 2**: After Phase B completion — Core move/merge functionality functional and integrated with git sync.

**Gate 3**: After Phase C completion — Complete feature implementation with all tests passing.

### Acceptance Criteria

The implementation is ready for production when:

1. **Problem Solved**: Git clone resulting in empty graphs now detects misconfiguration and offers to fix automatically
2. **User Experience**: Clear banner, guided fix flow with three-way choice (safe default identified as "Leave as-is"), transparent dry-run preview, and persistent repo/subfolder display outside the wizard
3. **Data Safety**: No data loss during any operation; soft-delete and crash recovery prevent orphaned content; partial failures preserve successful copies without rollback
4. **Accessibility**: Banner announces as alert role; dialog is keyboard-navigable; three-way choice uses text+icon (not color alone); WCAG AA contrast met
5. **Discoverability**: Auto-fill-on-clone pre-populates the subfolder field; first-run coachmark surfaces on fresh clone; inline "Validate now" works from graph settings; content-path breadcrumb always visible
6. **Performance**: Reasonable performance on all platforms (bounded scans, efficient moves)
7. **Reliability**: Comprehensive test coverage with no regressions
8. **Auditability**: "Left in place" decisions (dialog dismissal, explicit Leave, partial-failure Skip) are logged, not silent

## Notes and References

### Code Locations

- **GraphDiagnostics**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/diagnostics/GraphDiagnostics.kt`
- **GraphContentMainArea**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphContentMainArea.kt`
- **StorageMoveChoiceDialog**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveChoiceDialog.kt`
- **GitSyncService**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`
- **DatabaseWriteActor**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatabaseWriteActor.kt`
- **FileSystem**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`

### Test Locations

- **Regression Tests**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/git/WikiSubdirUriGuardTest.kt`

### Build System

- **Canonical Build**: Bazel (`bazel test //kmp:jvm_tests`, `bazel build //kmp:android_app`)
- **Gradle Fallback**: For iOS screenshots and benchmarks only

### References

- **CLAUDE.md**: Phase 3 calibration and tool usage guidelines
- **requirements.md**: Requirements documents for the wiki-subdir-ux project
- **brainstorm.md**: Technical brainstorming for the feature
- **features.md**: Feature landscape analysis
- **architecture.md**: Architecture design decisions
- **pitfalls.md**: Critical risk areas and implementation challenges
- **ux.md**: User experience research and design recommendations
- **build-vs-buy.md**: Build vs. buy decision rationale

### Bibliography

1. **Kotlin Multiplatform Development Guide**
2. **SQLDelight Documentation**
3. **Android SAF Documentation**
4. **Git Integration Best Practices**
5. **Compose Multiplatform UI Guidelines**

---

*This implementation plan follows the AIC (Atomic-InVEST-Context) framework with clear acceptance criteria, risk mitigation strategies, and phased delivery approach. All decisions are documented with rationale and references for maintainability.*
