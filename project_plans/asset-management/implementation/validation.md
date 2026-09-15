# Validation Plan: asset-management

**Feature**: Asset organization, browser, ML tagging, and cloud enrichment for SteleKit graphs
**Date**: 2026-06-13
**Status**: Ready for implementation
**Source artifacts**: requirements.md, plan.md (Pass 3), adversarial-review.md (Pass 3)

---

## Summary

| Metric | Value |
|---|---|
| Total test cases | 62 |
| Unit tests | 38 |
| Integration tests | 16 |
| UI/screenshot tests | 8 |
| Requirements covered | 10 / 10 (100%) |
| Critical path risks with explicit TCs | 6 / 6 |
| Readiness gate | **CONCERNS** |

---

## Test Cases

### Group A — Schema + Persistence (TC-ASSET-001 to TC-ASSET-012)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-001 | `asset_index` table exists in `MigrationRunner.all` | Integration | REQ-1.3, REQ-1.6 |
| TC-ASSET-002 | `pending_asset_moves` table exists in `MigrationRunner.all` | Integration | REQ-1.2 |
| TC-ASSET-003 | Both new tables detected and asserted by `MigrationRunnerSchemaSyncTest` (existing test auto-coverage) | Integration | REQ-1.3, REQ-1.2 |
| TC-ASSET-004 | `SqlDelightAssetRepository` CRUD round-trip: save + getByUuid returns equal entry | Unit | REQ-1.3 |
| TC-ASSET-005 | `getAssets(limit=10, offset=0)` returns first page; `getAssets(limit=10, offset=10)` returns next page | Unit | REQ-1.3 |
| TC-ASSET-006 | `getAssetsByMediaType(PDF)` returns only PDF entries | Unit | REQ-1.3 |
| TC-ASSET-007 | `updateTags` persists new tag list; subsequent read reflects update | Unit | REQ-1.3, REQ-1.5 |
| TC-ASSET-008 | `markMlProcessed` sets `mlProcessed = true`; asset excluded from `getUnprocessedAssets` | Unit | REQ-2.5 |
| TC-ASSET-009 | `deleteAsset` removes entry; subsequent `getByUuid` returns null | Unit | REQ-1.4 |
| TC-ASSET-010 | `InMemoryAssetRepository` satisfies same CRUD contract as SQL-backed repo (smoke test) | Unit | REQ-1.3 |
| TC-ASSET-011 | `getUnprocessedAssets` excludes entries with `mlFailed = true` | Unit | REQ-2.5 |
| TC-ASSET-012 | No unbounded `getAllAssets()` method exists on `AssetRepository` interface (compile-time enforcement + naming audit) | Unit | Constraints |

---

### Group B — MIME Detection + Routing (TC-ASSET-013 to TC-ASSET-020)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-013 | JPEG magic bytes (FF D8) detected as `image/*` → routes to `assets/images/` | Unit | REQ-1.1 |
| TC-ASSET-014 | PNG magic bytes (89 50) detected as `image/*` → routes to `assets/images/` | Unit | REQ-1.1 |
| TC-ASSET-015 | PDF magic bytes (`%PDF`) detected as `application/pdf` → routes to `assets/pdfs/` | Unit | REQ-1.1 |
| TC-ASSET-016 | MP4 ftyp magic bytes detected as `video/*` → routes to `assets/video/` | Unit | REQ-1.1 |
| TC-ASSET-017 | Extension fallback: `.mp3` with no recognized magic bytes → routes to `assets/audio/` | Unit | REQ-1.1 |
| TC-ASSET-018 | 0-byte file (`ByteArray()`) → extension fallback, no exception | Unit | REQ-1.1 |
| TC-ASSET-019 | 4-byte file (`byteArrayOf(0,0,0,0)`) → extension fallback, no exception | Unit | REQ-1.1 |
| TC-ASSET-020 | 7-byte file (shorter than any recognized magic pattern) → extension fallback, no exception | Unit | REQ-1.1 |

---

### Group C — Asset Move + WAL Saga (TC-ASSET-021 to TC-ASSET-030)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-021 | Happy path: `moveAsset()` renames file, rewrites markdown refs in all referencing pages, updates DB path, deletes WAL entry | Integration | REQ-1.2 |
| TC-ASSET-022 | Compensation: file rename fails → WAL entry still exists; no DB update; no ref rewrite | Unit | REQ-1.2 |
| TC-ASSET-023 | Compensation: ref rewrite fails after rename → file rename is rolled back; DB unchanged; WAL entry still exists | Unit | REQ-1.2 |
| TC-ASSET-024 | **Arrow Saga WAL replay — incomplete move (old exists, new absent)**: `replayPendingMoves()` completes rename + ref rewrite + DB update + WAL delete | Integration | REQ-1.2 |
| TC-ASSET-025 | **WAL replay — already complete (old absent, new exists)**: replay skips rename and ref rewrite; performs DB update if needed; deletes WAL row | Integration | REQ-1.2 |
| TC-ASSET-026 | **WAL replay — ambiguous (both files exist)**: replay skips row; logs warning; WAL row NOT deleted | Unit | REQ-1.2 |
| TC-ASSET-027 | **WAL replay — both absent**: replay deletes WAL row; logs warning; no other action | Unit | REQ-1.2 |
| TC-ASSET-028 | Concurrent moves on same UUID are serialized (second call blocks until first completes) | Unit | REQ-1.2 |
| TC-ASSET-029 | `rewriteAssetReference` calls `onPreWrite` before writing; no spurious `DiskConflict` event emitted for the rewritten page | Integration | REQ-1.2 |
| TC-ASSET-030 | `AttachmentResult.relativePath` uses `../assets/<subfolder>/...` form after a move (Logseq-compatibility) | Unit | REQ-1.1, REQ-1.2, Constraints |

---

### Group D — Asset Index + Backfill (TC-ASSET-031 to TC-ASSET-037)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-031 | `registerAsset()` creates an `AssetEntry` with correct UUID, mediaType, sizeBytes, importedAtMs | Unit | REQ-1.3, REQ-1.6 |
| TC-ASSET-032 | `backfillGraph()` with 200 fake files processes in batches ≤ 50 per pass | Unit | REQ-1.6 |
| TC-ASSET-033 | Backfill skips files already in index (same content_hash); no duplicate entries created | Unit | REQ-1.6 |
| TC-ASSET-034 | Permanently unreadable file (simulate read error) does not block forward progress; all other files indexed | Unit | REQ-1.6 |
| TC-ASSET-035 | Backfill terminates when all files are indexed (drain loop exits; no infinite loop) | Unit | REQ-1.6 |
| TC-ASSET-036 | `IN`-clause chunk size ≤ 500 for Android API < 30 compatibility (batch size audit) | Unit | Constraints |
| TC-ASSET-037 | Graph open triggers backfill as non-blocking background job (does not delay page load) | Integration | REQ-1.6 |

---

### Group E — Asset Browser UI (TC-ASSET-038 to TC-ASSET-046)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-038 | `AssetBrowserScreen` renders in grid mode for image filter | UI | REQ-1.4 |
| TC-ASSET-039 | `AssetBrowserScreen` renders in list mode when toggled | UI | REQ-1.4 |
| TC-ASSET-040 | Filter chip "PDFs" shows only PDF assets; chip "All" restores full list | UI | REQ-1.4 |
| TC-ASSET-041 | Search by filename returns matching asset; search by tag returns asset with that tag | UI | REQ-1.4 |
| TC-ASSET-042 | Per-asset action menu renders: Open, Copy Link, Rename, Move, Delete, Edit Tags | UI | REQ-1.4 |
| TC-ASSET-043 | `AssetBrowserViewModel` scope cancelled when screen leaves composition (`onForgotten` / `onAbandoned`) | Unit | REQ-1.4, Constraints |
| TC-ASSET-044 | `AssetBrowserViewModel` not constructed with `rememberCoroutineScope()` (code audit / architectural test) | Unit | Constraints |
| TC-ASSET-045 | Search debounce: rapid input (5 keystrokes within 200ms) issues only one query call | Unit | REQ-1.4 |
| TC-ASSET-046 | `remember(graphId)` key used in `App.kt` — ViewModel recreated on graph switch | Unit | REQ-1.4 |

---

### Group F — Custom Groups (TC-ASSET-047 to TC-ASSET-049)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-047 | Creating a group stores it as a tag in the asset index; group appears as filter chip | Unit | REQ-1.5 |
| TC-ASSET-048 | Asset can belong to multiple groups simultaneously | Unit | REQ-1.5 |
| TC-ASSET-049 | Custom groups section visible in asset browser sidebar/filter bar | UI | REQ-1.5 |

---

### Group G — ML Plugin Architecture (TC-ASSET-050 to TC-ASSET-056)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-050 | `PluginRegistry`: registering image + PDF plugins — only image plugin called for IMAGE asset; only PDF plugin for PDF asset | Unit | REQ-2.4 |
| TC-ASSET-051 | `AssetPipelineService`: stub plugins return fixed labels → `autoLabels` updated in repository; `mlProcessed = true` | Unit | REQ-2.1, REQ-2.4 |
| TC-ASSET-052 | `OnDeviceLabelingPlugin`: `canProcess` returns false for non-IMAGE asset types | Unit | REQ-2.1, REQ-2.4 |
| TC-ASSET-053 | `OcrPlugin`: `canProcess` returns false for PDF and non-image types | Unit | REQ-2.1, REQ-2.4 |
| TC-ASSET-054 | `PdfTextPlugin`: `canProcess` returns true for PDF only | Unit | REQ-2.2, REQ-2.4 |
| TC-ASSET-055 | **ML plugin isolation**: no `import com.google.mlkit`, `import ai.onnxruntime`, or `import com.tom_roush` in any `commonMain` source file (grep-based build-time check) | Unit | REQ-2.4, Constraints |
| TC-ASSET-056 | Plugin failure sets `mlFailed = true` via `markMlFailed()` and records `mlAttemptedAt` | Unit | REQ-2.5 |

---

### Group H — Cloud Enrichment (TC-ASSET-057 to TC-ASSET-062)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-057 | **Explicit opt-in gate**: `CloudVisionPlugin.canProcess()` returns false when `CloudEnrichmentConfig.enabled = false`, even if session cap not reached | Unit | REQ-2.3 |
| TC-ASSET-058 | **Explicit opt-in gate**: `CloudClaudePlugin.canProcess()` returns false when config disabled | Unit | REQ-2.3 |
| TC-ASSET-059 | Session cap enforced: after 20 successful cloud calls, `canProcess()` returns false for both cloud plugins | Unit | REQ-2.3 |
| TC-ASSET-060 | Idempotency: asset with `mlTagsSource = "CLOUD_VISION"` skips `CloudVisionPlugin`; not called again on next graph open | Unit | REQ-2.3 |
| TC-ASSET-061 | Idempotency: asset with `mlTagsSource = "CLAUDE"` skips `CloudClaudePlugin` | Unit | REQ-2.3 |
| TC-ASSET-062 | Cloud result is additive: on-device labels preserved; cloud labels merged into `autoLabels`; `cloudDescription` stored | Unit | REQ-2.3 |

---

### Group I — Backfill Throttle + Drain Loop (TC-ASSET-063 to TC-ASSET-066)

| ID | Name | Type | Covers |
|---|---|---|---|
| TC-ASSET-063 | **Backfill drain termination**: 100 unprocessed assets, each succeeds → drain loop terminates; total batches = 10 × 10 | Unit | REQ-2.5 |
| TC-ASSET-064 | **Backfill drain termination with permanent failures**: 10 assets all fail ML processing → `mlFailed = 1` set; drain loop terminates; not retried on next `scheduleBackfill()` call | Unit | REQ-2.5 |
| TC-ASSET-065 | `attemptedUuids` in-session guard: same UUID not submitted twice even if `ml_failed` DB update is delayed | Unit | REQ-2.5 |
| TC-ASSET-066 | `yield()` called between batches: no single batch holds the coroutine dispatcher for > 1 batch duration (cooperative scheduling) | Unit | REQ-2.5 |

---

## Critical Path Test Cases

The following table maps the six critical risks identified in the task brief to their test coverage.

| Critical Risk | Primary TCs | Mitigation in plan.md |
|---|---|---|
| **Arrow Saga: crash between rename and ref-update — WAL replay recovery** | TC-ASSET-024, TC-ASSET-025, TC-ASSET-026, TC-ASSET-027 | Task 1.3.3b: 4-case idempotent replay with write-actor serialization. Saga WAL-insert-before-rename ordering specified. |
| **MigrationRunnerSchemaSyncTest: both new tables in MigrationRunner.all** | TC-ASSET-001, TC-ASSET-002, TC-ASSET-003 | Tasks 1.1.1c, 1.1.2b: explicit MigrationRunner entries. Task 1.5.4a: verify auto-detection by existing test. |
| **Bounded reads: no unbounded asset queries** | TC-ASSET-005, TC-ASSET-012, TC-ASSET-036 | Tasks 1.1.1b, 1.3.1a: `selectAssets` requires mandatory LIMIT/OFFSET; `getAllAssets()` absent from interface. `selectUnprocessedAssets` also paginated. |
| **ML plugin isolation: ONNX/ML Kit not reachable from commonMain** | TC-ASSET-055 | Tasks 2.2.2a–2.2.4a: all platform ML impls placed in `androidMain`/`jvmMain`/`iosMain`. Task 2.3.1c: `PluginRegistry` injected from platform entry points; `RepositoryFactory` (commonMain) takes pre-built registry as constructor parameter. |
| **Cloud enrichment: never called without explicit user opt-in** | TC-ASSET-057, TC-ASSET-058, TC-ASSET-059, TC-ASSET-060, TC-ASSET-061 | Tasks 2.4.1a, 2.4.2a, 2.4.2b: `enabled` flag in `CloudEnrichmentConfig`; `canProcess()` checks enabled + session cap + `mlTagsSource` idempotency guard before any network call. |
| **Backfill drain: terminates even if assets permanently fail ML** | TC-ASSET-063, TC-ASSET-064, TC-ASSET-065 | Task 2.1.2a: `ml_failed = 1` written after failure; excluded by `selectUnprocessedAssets` filter; `attemptedUuids` in-session guard mirrors `GraphLoader.indexRemainingPages` pattern. |

---

## Requirements Coverage Matrix

| Requirement | Description (abbreviated) | Test Cases |
|---|---|---|
| REQ-1.1 | Typed subfolder routing on attach | TC-ASSET-013, TC-ASSET-014, TC-ASSET-015, TC-ASSET-016, TC-ASSET-017, TC-ASSET-018, TC-ASSET-019, TC-ASSET-020, TC-ASSET-030 |
| REQ-1.2 | User-controlled regrouping / asset move | TC-ASSET-021, TC-ASSET-022, TC-ASSET-023, TC-ASSET-024, TC-ASSET-025, TC-ASSET-026, TC-ASSET-027, TC-ASSET-028, TC-ASSET-029, TC-ASSET-030 |
| REQ-1.3 | Asset metadata index | TC-ASSET-001, TC-ASSET-003, TC-ASSET-004, TC-ASSET-005, TC-ASSET-006, TC-ASSET-007, TC-ASSET-008, TC-ASSET-009, TC-ASSET-010, TC-ASSET-011, TC-ASSET-031 |
| REQ-1.4 | Asset Browser Screen | TC-ASSET-038, TC-ASSET-039, TC-ASSET-040, TC-ASSET-041, TC-ASSET-042, TC-ASSET-043, TC-ASSET-044, TC-ASSET-045, TC-ASSET-046 |
| REQ-1.5 | Custom groups / virtual folders | TC-ASSET-047, TC-ASSET-048, TC-ASSET-049 |
| REQ-1.6 | Backfill on graph load | TC-ASSET-032, TC-ASSET-033, TC-ASSET-034, TC-ASSET-035, TC-ASSET-036, TC-ASSET-037 |
| REQ-2.1 | On-device image labeling | TC-ASSET-050, TC-ASSET-051, TC-ASSET-052, TC-ASSET-053 |
| REQ-2.2 | On-device PDF text extraction | TC-ASSET-054 |
| REQ-2.3 | Cloud-optional enrichment (opt-in per graph) | TC-ASSET-057, TC-ASSET-058, TC-ASSET-059, TC-ASSET-060, TC-ASSET-061, TC-ASSET-062 |
| REQ-2.4 | Processing pipeline / plugin hooks | TC-ASSET-050, TC-ASSET-051, TC-ASSET-052, TC-ASSET-053, TC-ASSET-054, TC-ASSET-055, TC-ASSET-056 |
| REQ-2.5 | Processing triggers and throttling | TC-ASSET-008, TC-ASSET-011, TC-ASSET-056, TC-ASSET-063, TC-ASSET-064, TC-ASSET-065, TC-ASSET-066 |

Coverage: **10 / 10 requirements** have at least one test case.

---

## Constraints Coverage

| Constraint | Enforced by |
|---|---|
| Logseq-compatible `../assets/...` relative paths | TC-ASSET-030 |
| Arrow `Either` at repository boundaries | TC-ASSET-004 through TC-ASSET-011 (all verify `Either` return types) |
| SQLDelight migrations in `MigrationRunner.all` | TC-ASSET-001, TC-ASSET-002, TC-ASSET-003 |
| No `rememberCoroutineScope()` passed to long-lived classes | TC-ASSET-044 |
| Bounded reads only — no unbounded queries | TC-ASSET-005, TC-ASSET-012, TC-ASSET-036 |
| ML platform classes not in commonMain | TC-ASSET-055 |

---

## Test Implementation Notes

### TC-ASSET-003: `MigrationRunnerSchemaSyncTest` auto-detection
The existing `MigrationRunnerSchemaSyncTest` reads `SteleDatabase.sq`, extracts all `CREATE TABLE IF NOT EXISTS <name>` table names, and asserts each appears in `MigrationRunner.all`. No code change is required for the new tables — the test auto-detects them. Verify with:
```
./gradlew jvmTest --tests "*MigrationRunnerSchemaSyncTest"
```

### TC-ASSET-012 and TC-ASSET-055: Static enforcement tests
These are enforced at two levels:
1. **Compile-time (preferred)**: The `AssetRepository` interface has no `getAllAssets()` method — any attempt to call an unbounded query won't compile.
2. **Naming audit (test)**: A test can scan all SQL query names in the generated `SteleDatabase.sq` and assert none are named `getAllAssets` or `selectAllAssets`.

For TC-ASSET-055, implement as a `jvmTest` that invokes `grep`-equivalent over `commonMain` source files:
```kotlin
@Test
fun `no platform ML imports in commonMain`() {
    val commonMainDir = File("src/commonMain")
    val forbidden = listOf("com.google.mlkit", "ai.onnxruntime", "com.tom_roush", "org.tensorflow")
    val violations = commonMainDir.walkTopDown()
        .filter { it.extension == "kt" }
        .flatMap { f -> forbidden.mapNotNull { lib -> if (f.readText().contains(lib)) "${f.path}: $lib" else null } }
        .toList()
    assertTrue(violations.isEmpty(), "Platform ML imports found in commonMain:\n${violations.joinToString("\n")}")
}
```

### TC-ASSET-021: `rewriteAssetReference` integration test
Requires a fake filesystem (okio `FakeFileSystem`), two markdown pages with `![image](../assets/images/foo.jpg)` references, and asserting both are rewritten to `![image](../assets/pdfs/foo.jpg)` after the move.

### TC-ASSET-037: Non-blocking backfill test
Assert that the `loadDirectory()` coroutine completes before backfill processing finishes (i.e., backfill is launched as a separate job, not awaited).

### TC-ASSET-066: Cooperative scheduling test
Use `TestCoroutineScheduler` (kotlinx-coroutines-test) to verify the drain loop calls `yield()` between each batch, preventing monopolization of the coroutine dispatcher.

---

## Open Items from Adversarial Review

The following concerns from the adversarial review (Pass 3) are not fully covered by test cases. They require a plan decision before implementation:

### CONCERN-1: WAL case 3 (both files exist) — silent stuck row
**Status**: Partially mitigated (TC-ASSET-026 covers the behavior; it does not verify a user-facing signal).
**Decision required**: Choose one:
- (a) Emit a `DomainError` from `replayPendingMoves()` surfaced as a UI warning banner — add a UI test asserting the banner appears.
- (b) Document why case 3 is structurally impossible under the current move protocol, and collapse it into case 2.
**Impact on validation**: If (a), add TC-ASSET-026b: `replayPendingMoves()` emits a non-fatal warning to UI state when an ambiguous WAL row is found.

### CONCERN-2: Nullable `writeActor` in `AssetBrowserViewModel`
**Status**: Not covered by current test cases; all write tests assume a non-null actor.
**Decision required**: Choose one:
- (a) Make `writeActor` non-null; guard at `App.kt` call site — add TC: ViewModel only constructed when write actor is available.
- (b) Keep nullable with explicit "read-only degraded mode" — add TC: all write-action buttons disabled when actor is null; no silent no-ops.
**Impact on validation**: Without a decision, write paths are untestable under the null case.

### MINOR-1: `AssetEntry` missing `mlTagsSource` and `mlFailed` fields
**Impact on validation**: TC-ASSET-060, TC-ASSET-061, TC-ASSET-011 depend on these fields being present in the domain model. Implementation must add both fields to `AssetEntry` (Task 1.1.3a) before these tests can be written.

### MINOR-4: `remember(graphId)` key in App.kt
**Impact on validation**: TC-ASSET-046 verifies this. Implementor must follow the `remember(graphId) { ... }` pattern in Task 1.4.4a.

### MINOR-5: `CloudClaudePlugin` idempotency guard not in task body
**Impact on validation**: TC-ASSET-061 covers the behavior. Implementor must cross-reference Task 2.4.2 acceptance criteria against the task body; the check for `asset.mlTagsSource == "CLAUDE"` must precede the API call.

---

## Implementation Readiness Gate

### Criterion A — All requirements have test coverage
**Result**: PASS
All 10 requirements (REQ-1.1 through REQ-2.5) have at least one test case. Coverage matrix above is complete.

### Criterion B — Critical path risks have explicit mitigations in plan.md
**Result**: PASS (with one noted gap)
All six critical path risks have corresponding test cases and plan.md mitigations:
- Arrow Saga WAL replay: 4-case decision tree in Task 1.3.3b
- MigrationRunnerSchemaSyncTest: Tasks 1.1.1c, 1.1.2b, 1.5.4a
- Bounded reads: Tasks 1.1.1b, 1.3.1a, naming contract on interface
- ML plugin isolation: Task 2.3.1c (platform entry point wiring)
- Cloud opt-in: Tasks 2.4.1a, 2.4.2a, 2.4.2b (`enabled` flag + idempotency)
- Backfill drain termination: Task 2.1.2a (`ml_failed` + `attemptedUuids`)

**Gap**: CONCERN-1 (WAL case 3 user-facing signal) is a plan-level decision that is documented but unresolved. It does not block implementation but should be resolved before shipping.

### Criterion C — No adversarial blockers remain
**Result**: PASS
Adversarial review Pass 3 reports 0 blockers. All 6 Pass-1 blockers and 1 Pass-2 blocker are confirmed resolved.

### Criterion D — Technology choices are validated in research
**Result**: PASS
plan.md Technology Validation Notes table confirms:
- ONNX Runtime: OK (file-path loading, two artifacts)
- ML Kit: OK (behind service interface, never in commonMain)
- PDFBox-Android: ACCEPTABLE (API 26 desugaring required — noted as minor in adversarial review)
- iText 7 / MuPDF: BLOCKED (AGPL-3.0)
- TFLite JVM: BLOCKED (use ONNX Runtime instead)
- Arrow Saga: OK (with WAL table)
- Okio FileSystem: OK

**Minor open item**: PDFBox-Android API 26 `coreLibraryDesugaring` build change not assigned to any task (adversarial minor). Add to Task 2.2.2b before implementation.

---

## Overall Readiness Verdict: CONCERNS

Implementation can proceed. All 10 requirements have test coverage. All 6 critical path risks are mitigated. No adversarial blockers remain. Technology choices are validated.

Two plan-level concerns must be resolved before the implementation is considered shippable:
1. **CONCERN-1**: WAL case 3 stuck-row user signal — decide between UI warning or structural impossibility argument.
2. **CONCERN-2**: Nullable `writeActor` in `AssetBrowserViewModel` — decide between non-null guard or explicit read-only degraded mode.

These concerns do not block starting implementation (Phase 1 Epics 1.1–1.4 are unaffected) but must be resolved before the asset move flow and browser write actions are testable end-to-end.
