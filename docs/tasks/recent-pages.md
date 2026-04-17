# Recent Pages — Implementation Plan

> **Status**: Ready for implementation  
> **Priority**: High — navigation staple; its absence makes the app feel incomplete immediately  
> **Logseq parity target**: "Recent" sidebar section showing last 10 visited pages, persisting across sessions, per-graph

---

## Epic Overview

### User Value

Users routinely work across 3–5 active pages in a session. Without a "Recent" list they must remember page names and re-search for them constantly. Logseq users in particular depend on Recents as their primary navigation shortcut — it is muscle memory. Its absence is the most immediately noticeable gap when migrating from Logseq.

### Success Metrics

| Metric | Target |
|--------|--------|
| "Recent" section visible in left sidebar | Shown whenever ≥ 1 page has been visited |
| Correct ordering | Most recently visited always at top |
| Session persistence | List survives app restart |
| Per-graph isolation | Switching graphs shows that graph's recents |
| Display cap | Max 10 items shown (store 20 for resilience) |
| Journal pages included | Yes — same as Logseq |

### Scope

**In scope**:
- Visit-based recency (not modification-based)
- Persistence across sessions via `PlatformSettings`
- Per-graph isolation using graph-path-namespaced keys
- Display cap of 10 in sidebar
- Clicking any entry navigates to that page
- Deleted-page graceful handling (omit from display, keep in store for buffer)

**Out of scope**:
- Visit count / frequency weighting
- "Pinned recents" or user reordering
- Recents shown in Command Palette (separate feature)
- Recents synced across devices

### Constraints

- No new SQLDelight table or schema migration required (see ADR-002)
- Must integrate with existing `navigateTo()` hook — no new navigation interceptors
- KMP: all changes in `commonMain` (no platform-specific code)

---

## Architecture Decisions

| ADR | File | Decision |
|-----|------|----------|
| ADR-002 | `project_plans/stelekit-parity/decisions/ADR-002-recent-pages-persistence-strategy.md` | Use `PlatformSettings` with per-graph key `"recent_pages_<graphPath>"` instead of a new SQLDelight table |
| ADR-003 | `project_plans/stelekit-parity/decisions/ADR-003-recent-pages-list-cap.md` | Store 20 UUIDs; display 10 in sidebar |

---

## Current State Analysis

The feature is **partially implemented** with the following gaps:

| Component | Status | Gap |
|-----------|--------|-----|
| `AppState.recentPages: List<Page>` | Done | — |
| `StelekitViewModel.addToRecent()` | Done | Key is graph-agnostic (`"recent_pages"`) |
| `StelekitViewModel.recentPageUuids` init | Done | Reads from wrong key on graph switch |
| `LeftSidebar` "Recent" section | Done | Already renders `recentPages`, capped to 20 not 10 |
| `observeSpecialPages()` UUID → Page resolution | Done | Works but stale UUIDs from other graphs resolve silently to null |
| Per-graph key namespacing | **Missing** | — |
| Display cap enforcement (10) | **Missing** | Currently passes all 20 to sidebar |
| Recents cleared/restored on graph switch | **Missing** | — |

---

## Story Breakdown

### Story 1: Fix Per-Graph Isolation and Display Cap [~3h]

**User value**: Recents correctly reflect the current graph after a graph switch; no ghost entries from other graphs.

**Acceptance criteria**:
1. Visiting page A in Graph 1, switching to Graph 2, then back to Graph 1 shows Graph 1's recents, not Graph 2's.
2. After app restart, recents for the current graph are restored from settings.
3. Sidebar "Recent" section shows at most 10 entries.
4. Visiting the same page twice only shows it once (moved to top, not duplicated).

#### Task 1.1 — Fix per-graph key in `addToRecent()` and init [2h]

**Objective**: Namespace the `PlatformSettings` key by graph path so recents are per-graph.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt`

**Total context**: ~1,400 lines (StelekitViewModel) — read only the relevant sections (lines 99–242 for recents logic, lines 612–648 for `navigateTo`)

**Prerequisites**:
- Understand `PlatformSettings` API (simple key/value, platform-specific backing store)
- Understand `_uiState.value.currentGraphPath` is always set before any page navigation

**Implementation approach**:

1. Add a private computed helper `recentPagesKey` that returns the namespaced settings key:
   ```kotlin
   private val recentPagesKey: String
       get() = "recent_pages_${_uiState.value.currentGraphPath}"
   ```

2. Update the `recentPageUuids` field initialization in the class body. Currently it reads eagerly at construction time using the hardcoded `"recent_pages"` key. Change the field to a simple `MutableList<String>` initialized to empty, and load from settings lazily inside `observeSpecialPages()` (which runs after `currentGraphPath` is set):
   ```kotlin
   private var recentPageUuids: MutableList<String> = mutableListOf()
   ```

3. In `observeSpecialPages()`, before starting the `getAllPages()` collection, load the correct list:
   ```kotlin
   recentPageUuids = platformSettings.getString(recentPagesKey, "")
       .split(",")
       .filter { it.isNotEmpty() }
       .toMutableList()
   ```

4. In `addToRecent()`, replace the hardcoded `"recent_pages"` key with `recentPagesKey`:
   ```kotlin
   platformSettings.putString(recentPagesKey, recentPageUuids.joinToString(","))
   ```

5. In `setGraphPath()`, reload recents when the graph changes. After updating `_uiState` and before calling `loadGraph()`, reload the list:
   ```kotlin
   recentPageUuids = platformSettings.getString(recentPagesKey, "")
       .split(",").filter { it.isNotEmpty() }.toMutableList()
   ```

**Validation strategy**:
- Unit test: construct two `StelekitViewModel` instances with different `currentGraphPath` values using a fake `PlatformSettings`; visit pages in each, verify the settings keys are distinct.
- Manual: open Graph 1, visit pages A and B, switch to Graph 2, visit page C, switch back to Graph 1 — sidebar should show A and B only.

**INVEST check**:
- Independent: no dependency on Task 1.2
- Negotiable: key format (`"recent_pages_<path>"`) can be adjusted without affecting external interfaces
- Valuable: eliminates cross-graph pollution
- Estimable: 2h — well-understood change in a known file
- Small: 3 files, < 30 lines changed
- Testable: fake `PlatformSettings` enables isolated unit testing

---

#### Task 1.2 — Enforce 10-item display cap [1h]

**Objective**: Pass at most 10 items to `LeftSidebar` so the "Recent" section matches Logseq.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (lines 159–182, `observeSpecialPages`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (AppState definition)

**Total context**: ~100 lines

**Prerequisites**: Task 1.1 understanding of `recentPageUuids` resolution

**Implementation approach**:

1. In `observeSpecialPages()`, when mapping `recentPageUuids` → `List<Page>`, take the first 10 resolved pages:
   ```kotlin
   val recent = recentPageUuids
       .mapNotNull { uuid -> allPages.find { it.uuid == uuid } }
       .take(10)
   ```

2. In `addToRecent()`, the same `take(10)` should apply to the `_uiState.update` block:
   ```kotlin
   val recent = recentPageUuids
       .mapNotNull { uuid -> cachedAllPages.find { it.uuid == uuid } }
       .take(10)
   ```

3. The storage cap of 20 in `addToRecent()` remains unchanged (buffer for deleted-page resilience).

**Validation strategy**:
- Unit test: visit 15 distinct pages; assert `uiState.value.recentPages.size == 10`.
- Unit test: visit the same page twice; assert it appears once and is at index 0.

**INVEST check**:
- Independent: does not depend on Task 1.1 (but should be done after for consistent key)
- Negotiable: cap value (10) documented in ADR-003
- Valuable: matches Logseq parity
- Estimable: 1h — 2-line change
- Small: 1 primary file, 2 lines
- Testable: pure list transformation, easily unit-tested

---

### Story 2: Tests and Polish [~2h]

**User value**: Confidence the recents feature behaves correctly after graph switches, app restarts, and page deletions.

**Acceptance criteria**:
1. Unit tests pass for per-graph isolation.
2. Unit tests pass for display cap.
3. Unit tests pass for stale UUID filtering (deleted pages omitted).
4. Sidebar renders recents with correct semantics (accessibility label, selected state).

#### Task 2.1 — Unit tests for `StelekitViewModel` recents logic [2h]

**Objective**: Cover the three recents correctness invariants with `businessTest` or `jvmTest`.

**Context boundary**:
- Primary: new test file `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/RecentPagesTest.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt` (for fake impl)
- Supporting: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/HistogramWriterTest.kt` (test structure reference)

**Total context**: ~300 lines

**Prerequisites**: Task 1.1 and 1.2 complete

**Implementation approach**:

1. Create a `FakePlatformSettings` in the test file that stores key-value pairs in a `MutableMap<String, String>`.

2. Write test: **per-graph isolation**
   - Set `currentGraphPath = "/graphs/work"`, visit pages P1 and P2
   - Change `currentGraphPath = "/graphs/personal"`, visit page P3
   - Change back to `/graphs/work`
   - Assert `recentPages` contains P1 and P2 but not P3

3. Write test: **display cap**
   - Visit 15 unique pages
   - Assert `uiState.value.recentPages.size == 10`

4. Write test: **deduplication and ordering**
   - Visit pages in order: A, B, C, A
   - Assert recents order is `[A, C, B]` (A moved to top on second visit)

5. Write test: **deleted-page resilience**
   - Store 15 UUIDs in settings where 5 are not in `cachedAllPages`
   - Assert `uiState.value.recentPages` contains only the 10 valid pages

**Validation strategy**:
- `./gradlew jvmTest --tests "dev.stapler.stelekit.ui.RecentPagesTest"`
- All 4 tests green

**INVEST check**:
- Independent: tests validate completed implementation
- Negotiable: test cases adjustable to match actual behaviour edge cases
- Valuable: prevents regression
- Estimable: 2h — well-bounded fake setup + 4 test cases
- Small: 1 new file, ~120 lines
- Testable: by definition (it is the tests)

---

## Known Issues

### BUG-001: Stale UUIDs After Graph Clear (SEVERITY: Low)

**Description**: When `triggerReindex()` is called, `pageRepository.clear()` + `blockRepository.clear()` wipe all pages. The `cachedAllPages` list becomes empty. Until `getAllPages()` emits new results, `recentPageUuids.mapNotNull` resolves all UUIDs to null and `recentPages` shows as empty in the sidebar — even if the user did not switch graphs.

**Mitigation**:
- This is transient (lasts only until the first `getAllPages()` emission after re-index).
- The sidebar already shows a `CircularProgressIndicator` while `isLoading = true`, so the empty recents list is hidden behind the loading indicator.

**Files likely affected**:
- `StelekitViewModel.kt` — `triggerReindex()` and `observeSpecialPages()`

**Prevention strategy**:
- Task 2.1 tests verify that recents are restored after `getAllPages()` emits the full list.
- No code change needed; document as known transient state.

**Related tasks**: Task 2.1

---

### BUG-002: `recentPagesKey` Race at ViewModel Construction (SEVERITY: Low)

**Description**: `recentPageUuids` is currently initialized in the class body (line 100–104) before `_uiState` is constructed. After Task 1.1's change, the key uses `_uiState.value.currentGraphPath`. If any code between the `_uiState = MutableStateFlow(...)` line and `observeSpecialPages()` calls `addToRecent()`, it would use a wrong key.

**Mitigation**:
- `addToRecent()` is only called from `navigateTo()`, which is never called during `init` (the graph loads asynchronously).
- The lazy init of `recentPageUuids` in `observeSpecialPages()` sets the list before any user navigation can occur.

**Files likely affected**:
- `StelekitViewModel.kt` — class body initialization order

**Prevention strategy**:
- Add a `check(_uiState.value.currentGraphPath.isNotEmpty())` assertion inside `recentPagesKey` getter with a comment explaining the invariant. This turns a silent wrong-key access into a visible crash during testing.

**Related tasks**: Task 1.1

---

## Dependency Visualization

```
Task 1.1 (fix per-graph key)
    │
    ▼
Task 1.2 (enforce display cap 10)   ← independent, can run in parallel with 1.1
    │
    ▼
Task 2.1 (unit tests)               ← requires 1.1 and 1.2 complete
```

Tasks 1.1 and 1.2 can be executed in either order or in parallel. Task 2.1 validates both.

---

## Integration Checkpoints

**After Task 1.1**: Manual smoke test — visit pages in Graph 1, switch to Graph 2, switch back. Recents correctly isolated.

**After Task 1.2**: Verify sidebar shows ≤ 10 items after visiting 15+ pages.

**After Task 2.1 (complete feature)**: Run `./gradlew jvmTest`. All `RecentPagesTest` cases pass. Manual walkthrough of acceptance criteria complete.

---

## Context Preparation Guide

### Task 1.1 — Fix per-graph key

**Files to load**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` — lines 99–242 (recents init, `observeSpecialPages`, `addToRecent`) and lines 263–270 (`setGraphPath`)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt` — understand the `getString`/`putString` API

**Concepts to understand**:
- `_uiState` is a `MutableStateFlow<AppState>`; `_uiState.value.currentGraphPath` is safe to read from any thread.
- `recentPageUuids` is a plain `MutableList` — not a `StateFlow`, so no reactive wiring needed.
- `observeSpecialPages()` is called from `init`, after `_uiState` is initialized.

### Task 1.2 — Display cap

**Files to load**:
- `StelekitViewModel.kt` — lines 159–182 (`observeSpecialPages`) and lines 222–242 (`addToRecent`)

**Concepts to understand**:
- `recentPageUuids.mapNotNull { ... }` produces a list ordered by recency.
- `.take(10)` on a `List` is safe on an empty list (returns empty).

### Task 2.1 — Unit tests

**Files to load**:
- `StelekitViewModel.kt` — full recents section (lines 99–242)
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/HistogramWriterTest.kt` — reference for test scaffolding pattern in this project

**Concepts to understand**:
- `StelekitViewModel` constructor requires `PlatformSettings` — use a `FakePlatformSettings` with a `MutableMap` backing.
- `StelekitViewModel` requires a `CoroutineScope` — use `TestScope` from `kotlinx-coroutines-test`.
- Repositories can be faked with `IN_MEMORY` backend (see `RepositoryFactory`).

---

## Success Criteria

- [ ] All 4 unit tests in `RecentPagesTest` pass
- [ ] `./gradlew jvmTest` exits clean
- [ ] Manual smoke test: Graph 1 recents survive graph switch and app restart
- [ ] Sidebar shows ≤ 10 items after 15+ page visits
- [ ] No regression in existing sidebar Favorites or navigation tests
- [ ] Code review: per-graph key naming matches ADR-002 (`"recent_pages_<graphPath>"`)
