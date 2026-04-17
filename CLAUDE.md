# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SteleKit is a Kotlin Multiplatform (KMP) migration of Logseq — a Markdown-based outliner/note-taking app. It targets Desktop (JVM), Android, iOS, and Web from a single shared codebase in the `kmp/` module.

## Build & Run Commands

```bash
# Run desktop app
./gradlew run

# Run all tests
./gradlew allTests

# Run JVM (desktop/shared) tests only
./gradlew jvmTest

# Run a single test class
./gradlew jvmTest --tests "dev.stapler.stelekit.SomeTest"

# Run Android tests
./gradlew testDebugUnitTest

# Build and install on connected Android device
./gradlew installAndroid

# Package desktop distributable
./gradlew packageDistributionForCurrentOS
```

## Module Structure

All shared code lives in `kmp/src/`:

| Source Set | Purpose |
|------------|---------|
| `commonMain` | Platform-agnostic UI, domain, repository, DB, parser |
| `jvmMain` | Desktop entry point, file watching, JVM logging |
| `androidMain` | Android entry point, driver factory |
| `iosMain` | iOS driver |
| `jsMain` | Web (enabled via `gradle.properties` `enableJs=true`) |
| `jvmTest` / `commonTest` / `businessTest` | Tests |

## Architecture

SteleKit follows a layered architecture inside `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`:

```
UI (Compose)       → ui/ (App.kt, screens/, components/)
ViewModel          → ui/StelekitViewModel.kt, ui/LogseqViewModel.kt
Repository         → repository/ (Page, Block, Search, Journal)
Database/Files     → db/ (GraphManager, GraphLoader, GraphWriter)
Domain Models      → model/
Parser             → parser/ + outliner/
Platform abstracts → platform/
```

### Key Data Flow

1. **Startup**: `StelekitApp` → `GraphManager.addGraph(path)` creates per-graph `RepositorySet` (PageRepository, BlockRepository, SearchRepository)
2. **Page load**: `StelekitViewModel.navigateTo()` → `GraphLoader` reads markdown → `OutlinerPipeline` builds block tree → saved to repositories
3. **Editing**: `BlockEditor` → `BlockStateManager` (local state) → debounced 500ms → `GraphWriter.saveBlock()` writes to disk
4. **External changes**: `GraphLoader.externalFileChanges` (SharedFlow) detects disk writes → emits `DiskConflict` → user resolves in UI

### Multi-Graph Support

`GraphManager` maintains multiple isolated graphs simultaneously. Each graph has its own `RepositorySet` and `CoroutineScope`. Repository backends: `IN_MEMORY` (tests), `SQLDELIGHT` (production).

### State Management

- `StelekitViewModel`: central `StateFlow`-based state (navigation, open page, search)
- `AppState`: global UI flags (sidebar, search dialog, command palette)
- `BlockStateManager`: isolated block editing state per block

### Database

SQLDelight 2.3.2 generates type-safe Kotlin from `.sq` files in `kmp/src/commonMain/sqldelight/`. Schema in `SteleDatabase.sq`.

## Testing Infrastructure

See `kmp/TESTING_README.md` for the full testing guide. Test source sets:
- `commonTest` — shared utilities
- `businessTest` — business logic without UI
- `jvmTest` — JVM UI + integration tests (uses Roborazzi for screenshot tests)
- `androidUnitTest` — Android local unit tests

## Key Files

| File | Role |
|------|------|
| `kmp/build.gradle.kts` | All dependencies, targets, SQLDelight config |
| `kmp/src/commonMain/.../ui/App.kt` | Root Compose composable, screen routing |
| `kmp/src/commonMain/.../ui/AppState.kt` | Global app state model |
| `kmp/src/commonMain/.../db/GraphManager.kt` | Multi-graph lifecycle |
| `kmp/src/commonMain/.../db/GraphLoader.kt` | File import and markdown parsing |
| `kmp/src/commonMain/.../db/GraphWriter.kt` | File export and conflict detection |
| `kmp/src/commonMain/.../model/Models.kt` | Page, Block, Property data classes with built-in validation |
| `kmp/src/commonMain/.../repository/RepositoryFactory.kt` | Backend abstraction |
| `kmp/src/jvmMain/.../desktop/Main.kt` | Desktop entry point |
| `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq` | SQLDelight schema |
