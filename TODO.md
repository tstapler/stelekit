# TODO.md - Logseq Kotlin Compose Desktop Re-implementation

## Current Status
- **Migration State**: Feature Implementation Phase - Core features complete!
- **Technology Stack**: Kotlin 2.0.21, Compose Multiplatform 1.7.1, **SQLDelight 2.1.0 (Persistent)**
- **Recent Activity**: Implemented FTS5 search with BM25 ranking, page-term highlighting with click-to-link UX, and Gradle daemon cleanup.
- **Last Updated**: April 12, 2026
- **Current Focus**: Knowledge Graph Maintenance (KGM Story 1 — Page Rename with Backlink Update)

## Build Status

| Target | Status | Notes |
|--------|--------|-------|
| JVM/Desktop | ✅ PASSING | Stable, SQLDelight persistent, Skiko resolved transitively (0.8.18) |
| Android | ✅ PASSING | Stable, SQLDelight persistent |
| JS | ✅ PASSING | Fixed via BUG-003, SQLDelight web-worker-driver + SQL.js |
| iOS | ⏸️ Disabled | Ivy repository issues |

## Project Structure (Multiplatform)

```
kmp/
├── build.gradle.kts           # Kotlin Multiplatform configuration
└── src/
    ├── commonMain/
    │   └── kotlin/com/logseq/kmp/
    │       ├── platform/          # expect classes
    │       ├── model/             # Page, Block, Property
    │       ├── repository/        # SQLDelight implementations
    │       ├── db/                # GraphLoader, DriverFactory, GraphManager
    │       ├── editor/            # Editor core logic
    │       └── ui/                # Compose UI components
    ├── jvmMain/                   # JVM/Desktop implementations
    └── androidMain/               # Android implementations
```

---

## Branding Workstream (April 2026)

Five parallel tracks to bring the project identity in line with the SteleKit brand. All are pre-code planned.

| Task | Plan | Priority |
|---|---|---|
| Color token redesign | [docs/tasks/branding-color-tokens.md](docs/tasks/branding-color-tokens.md) | P1 |
| Source-available license | [docs/tasks/branding-license.md](docs/tasks/branding-license.md) | P0 |
| README rewrite | [docs/tasks/branding-readme.md](docs/tasks/branding-readme.md) | P1 |
| App identity strings | [docs/tasks/branding-app-name.md](docs/tasks/branding-app-name.md) | P1 |
| Logo & asset infrastructure | [docs/tasks/branding-logo-assets.md](docs/tasks/branding-logo-assets.md) | P2 |

ADRs: [project_plans/stelekit/decisions/](project_plans/stelekit/decisions/)

- [x] **[BRAND-001] Source-Available License** — Add `LICENSE` file (PolyForm Noncommercial 1.0.0) + headers to key source files ([plan](docs/tasks/branding-license.md))
- [x] **[BRAND-002] Color Token Redesign** — Replace `Logseq*`/`Gruvbox*` names with semantic stone tokens; implement new default palettes ([plan](docs/tasks/branding-color-tokens.md))
- [x] **[BRAND-003] App Identity Strings** — Replace user-visible "Logseq" with "SteleKit" in window title, onboarding, I18n ([plan](docs/tasks/branding-app-name.md))
- [x] **[BRAND-004] README Rewrite** — Create root README.md; update kmp/README.md ([plan](docs/tasks/branding-readme.md))
- [x] **[BRAND-005] Logo Asset Infrastructure** — Define asset directory structure and design brief; wire icon into build system ([plan](docs/tasks/branding-logo-assets.md))

---

## Active Remediation (Post-Review March 2026)

### P0: STABILITY & COMPATIBILITY
- [x] **[JVM-001] Fix Skiko Runtime Crash** - Resolved transitively via Compose 1.7.1 on Kotlin 2.0.21.
- [x] **[BUG-003] Fix JS/Android Target Issues** - Re-enabled targets with proper driver initialization and memory settings.

### P0: ARCHITECTURE & MAINTAINABILITY
- [x] **[UI-001] Decompose BlockRenderer** - Split 600+ line God Object into BlockGutter, BlockEditor, BlockViewer, BlockItem, BlockList components.
- [x] **[ED-001] Implement Undo/Redo Command Pattern** - Undo/redo stack in JournalsViewModel: lightweight content edits + full page snapshots for structural ops. Ctrl+Z/Ctrl+Shift+Z/Ctrl+Y wired in App.kt.
- [x] **[ED-002] Decouple UI from Editor Core** - Moved `AutocompleteMenu` from `editor.components` to `ui.components`; removed cross-package Compose dependency.
- [x] **[TEST-001] Fix Flaky Test Sync** - Replace `delay(50)` with `UnconfinedTestDispatcher(testScheduler)` + `backgroundScope` for deterministic, non-hanging tests.
- [x] **[DB-001] UUID-Native Block Storage** - Moved from numeric IDs to UUID-native storage across the entire application. Enables cross-device merge.
- [x] **[MG-001] Multi-Graph Support** - Allow users to manage multiple knowledge graphs with per-graph SQLite databases.

### P0: LOGSEQ PARITY (Launch-Critical)
- [ ] **[HASH-001] Hashtag Links** — `#tag` and `#[[multi word tag]]` must parse as page references, render as clickable links, participate in backlink rename, and trigger autocomplete. Every Logseq graph uses hashtags; without this, all tag backlinks are broken. ([plan](docs/tasks/hashtag-links.md))

### P1: FEATURE COMPLETION
- [x] **[OPS-001] Implement Subtree Operations** - `promoteSubtree`, `demoteSubtree`, and `duplicateSubtree` are implemented.
- [x] **[FTS-001] Native Search Optimization** - `searchWithFilters` and `searchBlocksByContent` now use FTS5.
- [x] **[PL-001] Progressive Data Loading** - Implement pagination for all pages and linked references. ([plan](docs/tasks/progressive-loading.md))
- [ ] **[SR-001] Advanced Search & Query** - Implement Datalog engine via Datascript. ([plan](docs/tasks/search-system.md))
- [x] **[SEARCH-001] Search Improvements** - FTS5 page search, BM25 ranking, snippet rendering, phrase search, scope filters, recent searches. ([plan](docs/tasks/search-improvements.md))
- [x] **[ARCH-001] Extract JournalService** - Remove journal-domain methods from `PageRepository`; centralize creation logic in a `JournalService`. ([plan](docs/tasks/journal-service-extraction.md))
- [x] **[PTH-001] Page Term Highlighting** - Highlight unlinked page-name occurrences in block view mode; click to convert to `[[wikilink]]`. Aho-Corasick matching, reactive `PageNameIndex`, `LinkSuggestionPopup`. ([plan](docs/tasks/page-term-highlighting.md))
- [ ] **[GV-001] Graph View (Knowledge Graph Visualization)** — Force-directed global graph, local graph sidebar panel, namespace colour clustering, filters (journals/orphans/hops), Barnes–Hut for large graphs. ([plan](docs/tasks/graph-view.md))
- [ ] **[KGM-001] Knowledge Graph Maintenance** - Three-story epic: (1) atomic page rename with backlink rewrite across all files, (2) unlinked mentions navigator panel reusing PTH-001 infrastructure, (3) duplicate page detector with edit-distance matching and safe non-destructive merge. Depends on PTH-001 Story 1. ([plan](docs/tasks/knowledge-graph-maintenance.md))

---

## Completed Work

### ✅ Build & Persistence (March 2026)
- [x] **SQLDelight Migration**: Replaced In-Memory/DataScript with persistent SQLite backend.
- [x] **Atomic Hierarchy Sync**: `left_id` sibling chain correctly maintained in DB.
- [x] **Reactive DB Flows**: UI updates automatically via SQLDelight observers.
- [x] **Markdown Engine**: Extracted regex parsing into standalone `MarkdownEngine`.

### ✅ Core Infrastructure
- [x] PlatformFileSystem with all file operations
- [x] GraphLoader with progressive loading
- [x] Block and Page repositories (SQLDelight)
- [x] Performance monitoring infrastructure
- [x] UUID-Native storage
- [x] Multi-graph support (GraphManager)

### ✅ UI Framework
- [x] Main application window with menu bar
- [x] Theme toggle (Light/Dark/System)
- [x] Left sidebar with navigation
- [x] Right sidebar (expandable)
- [x] **Re-index Graph** button in Advanced Settings

### ✅ Content Display & Editing
- [x] JournalsView with reactive multi-journal display
- [x] BlockRenderer with wiki link, block ref, and tag support
- [x] **Atomic Merge/Split**: Backspace and Enter handle hierarchy correctly in DB.
- [x] Page Alias support indexed in SQL.

### ✅ Search & Discovery (April 2026)
- [x] **[SEARCH-001] FTS5 Search**: BM25 ranking, snippets, phrase search, scope filters, recent searches.
- [x] **[PTH-001] Page Term Highlighting**: Unlinked page-name highlight in view mode; click-to-link via `SuggestionContextMenu`. Aho-Corasick matching, reactive `PageNameIndex`.

---

## Known Issues

| ID | Severity | Description | Status |
|----|----------|-------------|--------|
| iOS-001 | Medium | iOS target disabled due to Ivy repository issues | Open |
| PL-001 | High | "All Pages" loads all pages into memory; linked refs unpaginated | Planned ([plan](docs/tasks/progressive-loading.md)) |
| SR-001 | High | Datalog engine (Datascript) not implemented | Planned ([plan](docs/tasks/search-system.md)) |
| SEARCH-001 | High | highlight() returns empty strings (wrong column index); no BM25 ranking; no snippet UI; no phrase search | ✅ Fixed (commit 9feaff181) |
| ARCH-001 | Medium | Journal-domain methods on `PageRepository` violate SRP; `generateTodayJournal` in ViewModel; duplicate journal risk | ✅ Fixed (commit 16766bf6f) |
| DEP-001 | Low | `ClickableText` deprecated — should use `Text` with `LinkAnnotation` (`BlockViewer.kt:67`) | ✅ Fixed |
| DEP-002 | Low | `Icons.Filled.KeyboardArrowRight` deprecated — use `Icons.AutoMirrored.Filled.KeyboardArrowRight` (`ReferencesPanel.kt:149`) | ✅ Fixed |
| WARN-001 | Low | `expect`/`actual` beta warnings across 8 files — suppress with `-Xexpect-actual-classes` in build.gradle.kts | ✅ Fixed |
| WARN-002 | Low | `SqlDelightBlockRepository.kt:469,472,509,512` — redundant `.toLong()` conversion calls | Open |
| PERF-001 | Medium | `loadDirectory` takes ~6.5s for 5464 pages — investigate parallelism or lazy chunk sizing | Open |
| PERF-002 | Low | `processChunk` occasionally hits 288ms+ — may need batch-size tuning | Open |
| KGM-001 | High | No backlink update on page rename — `[[OldName]]` links become broken after rename | Planned ([plan](docs/tasks/knowledge-graph-maintenance.md)) |
| KGM-002 | Medium | No duplicate page detection or merge workflow | Planned ([plan](docs/tasks/knowledge-graph-maintenance.md)) |

---

## Build & Test Commands

```bash
# Run the desktop application
./gradlew :kmp:runApp

# Compile JVM code
./gradlew :kmp:compileKotlinJvm

# Run tests
./gradlew :kmp:jvmTest
```
