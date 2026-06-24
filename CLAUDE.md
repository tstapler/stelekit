# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SteleKit is a Kotlin Multiplatform (KMP) migration of Logseq — a Markdown-based outliner/note-taking app. It targets Desktop (JVM), Android, iOS, and Web from a single shared codebase in the `kmp/` module.

## Bazel Build Commands

**Bazel is the canonical build system.** Use Bazel for all JVM/Desktop, Android, and Web
work. Gradle is kept only for iOS (no Bazel KMP support yet), screenshot tests
(Roborazzi), and benchmarks until those are migrated (Epic 7).

| Gradle (legacy) | Bazel (canonical) |
|--------|-------|
| `./gradlew run` | `bazel run //kmp:desktop_app` |
| `./gradlew jvmTest` | `bazel test //kmp:jvm_tests` |
| `./gradlew allTests` | `bazel test //...` |
| `./gradlew ciCheck` | `bazel test //... --config=ci` |
| `./gradlew installAndroid` | `bazel mobile-install //kmp:android_app --config=android` |
| `./gradlew packageDistributionForCurrentOS` | _(Gradle only — see Future Epic D)_ |
| `./gradlew testDebugUnitTest` | `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` |
| `./gradlew wasmJsBrowserDistribution` | `bazel build //kmp:web_app` |

```bash
# Launch desktop app
bazel run //kmp:desktop_app

# Run all JVM tests (excluding screenshot tests which remain Gradle-only)
bazel test //kmp:jvm_tests

# Run only business-logic tests (no UI, fastest)
bazel test //kmp:business_tests

# Build Android APK (requires ANDROID_HOME to be set)
bazel build //kmp:android_app --config=android

# Build web (WASM/JS) bundle — output: bazel-bin/kmp/web_dist.tar.gz
# Note: delegates to Gradle internally until rules_kotlin#567 lands
bazel build //kmp:web_app

# Run all Bazel tests
bazel test //...

# Re-generate SQLDelight sources (when .sq files change)
./gradlew :kmp:generateCommonMainSteleDatabase
rsync -a kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/ kmp/src/generated/sqldelight/
./gradlew :kmp:generateCommonMainTelemetryDatabase
rsync -a kmp/build/generated/sqldelight/code/TelemetryDatabase/commonMain/ kmp/src/generated/sqldelight-telemetry/
```

## Gradle Build & Run Commands

**Always use `./gradlew`, never the system `gradle` command.** The wrapper pins Gradle 9.5.0; the system install is 9.3.1 and cannot share daemons with wrapper builds — using it silently doubles the daemon count and memory footprint.

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

# Run all CI checks locally (detekt + jvmTest + Android unit tests + assembleDebug)
# Also compiles androidTest/ and WASM test sources to catch platform-specific type errors without a device.
./gradlew ciCheck
# UI/screenshot tests require a display. Use the appropriate wrapper for your environment:
#   Wayland (native display available):   ./gradlew ciCheck                  # display is already set
#   X11 (DISPLAY set):                    ./gradlew ciCheck                  # display is already set
#   Headless Linux / SSH (no display):    xvfb-run --auto-servernum ./gradlew ciCheck
# Automatic detection (try Wayland/X11 first, fall back to xvfb-run):
# [ -n "$WAYLAND_DISPLAY" ] || [ -n "$DISPLAY" ] && ./gradlew ciCheck || xvfb-run --auto-servernum ./gradlew ciCheck
# Run instrumented tests on a connected device/emulator (adb must see the device):
./gradlew ciCheck -PciInstrumentedTests
# README sync is not covered by ciCheck — run separately:
# bash scripts/generate-readme.sh && git diff --exit-code README.md

# Lint all GitHub Actions workflow files (mirrors the workflow-lint CI job)
# Install once: curl -sSfL https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz | tar -xz -C ~/.local/bin actionlint
actionlint -color

# Security audit all workflow files (uses uvx — no persistent install required)
uvx 'zizmor==1.25.2' .

# Run benchmark locally — mirrors CI, generates flamegraph PNGs (requires async-profiler + librsvg)
./scripts/benchmark-local.sh /path/to/your/graph   # real graph (recommended — most representative)
./scripts/benchmark-local.sh                        # synthetic XLARGE only (7978 pages, matches real graph scale)
# BENCH_CONFIG=SMALL ./scripts/benchmark-local.sh  # quick smoke (200 pages, same as CI)

# Or run the Gradle task directly (flamegraph PNGs require flamegraph.pl + rsvg-convert separately)
./gradlew :kmp:jvmTestProfile -PgraphPath=/path/to/your/graph
# Outputs to kmp/build/reports/:
#   graph-load.jfr              — raw JFR recording (alloc events)
#   graph-load-wall.jfr         — async-profiler wall-clock recording (all thread states)
#   graph-load-alloc.collapsed  — allocation stacks (collapsed, flamegraph-ready)
#   graph-load-cpu.collapsed    — wall-clock stacks filtered to DefaultDispatcher-worker-*
#                                 (Kotlin coroutine pool, Gradle/Kryo noise excluded)
#                                 Falls back to JFR CPU samples if async-profiler not found.
#   flamegraph.html             — interactive allocation flamegraph
#
# Wall-clock mode (macOS): brew install async-profiler
# Wall-clock mode (Linux): place async-profiler-4.4-linux-x64/ in repo root, or set AP_LIB=
# CI uploads flamegraph-alloc.png and flamegraph-cpu.png as individual artifacts
# viewable directly in the browser (no download required).
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

### Error handling — Arrow `Either`

All repository and service methods use [Arrow](https://arrow-kt.io)'s `Either<DomainError, T>` for error-returning operations. **Do not use `Result<T>`, nullable returns, or thrown exceptions for domain errors at repository boundaries.**

```kotlin
// Return success
return Unit.right()
return page.right()

// Return failure
return DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()

// Consume at call site
result.onLeft { e -> logger.error("failed: ${e.message}") }
result.getOrNull()           // null on failure
result.fold({ err -> … }, { value -> … })
```

Rules:
- **Repository interfaces** return `Either<DomainError, T>` for `suspend fun` and `Flow<Either<DomainError, T>>` for reactive queries.
- **Never** let SQLite exceptions propagate raw — wrap in `DomainError.DatabaseError.WriteFailed`.
- **`getOrNull()`** is fine for internal callers that treat failure as absence. Use `.fold` or `.onLeft` when the error needs to be surfaced.
- Arrow is already on the classpath via `commonMain`; import `arrow.core.Either`, `arrow.core.left`, `arrow.core.right`.

### Database

SQLDelight 2.3.2 generates type-safe Kotlin from `.sq` files in `kmp/src/commonMain/sqldelight/`. Schema in `SteleDatabase.sq`.

#### Adding a new table — mandatory migration rule

**Every `CREATE TABLE IF NOT EXISTS <name>` added to `SteleDatabase.sq` must also appear in `MigrationRunner.all` (`db/MigrationRunner.kt`).**

Why: `DriverFactory.createDriver()` calls `SteleDatabase.Schema.create(driver)` first, but on any existing database that call fails immediately (its first `CREATE TABLE pages` has no `IF NOT EXISTS`), the exception is swallowed, and all subsequent DDL is silently skipped. `MigrationRunner.applyAll()` is the only mechanism that creates new tables on existing user databases.

`MigrationRunnerSchemaSyncTest` (businessTest) enforces this automatically — it reads `SteleDatabase.sq`, extracts all `IF NOT EXISTS` table names, and asserts each appears in `MigrationRunner.all`. It will fail at CI time if you forget.

#### Write enforcement — `@DirectSqlWrite`

**Never call mutating methods (`insert*`, `update*`, `delete*`, `upsert*`, `transaction`) directly on `SteleDatabaseQueries`.** All writes are gated behind `@DirectSqlWrite` on `RestrictedDatabaseQueries` (`db/RestrictedDatabaseQueries.kt`).

Rules:
- Route writes through `DatabaseWriteActor` (preferred) — use `actor.execute { ... }` or the typed methods (`saveBlock`, `savePage`, etc.).
- If a helper class needs to write inside an actor lambda, inject `RestrictedDatabaseQueries` and annotate the private write function `@OptIn(DirectSqlWrite::class)`.
- Migration-time writers (`MigrationRunner`, `UuidMigration`) run before the actor exists and may carry `@OptIn(DirectSqlWrite::class)` at class level — this is the only approved class-level opt-in outside the actor.
- When you add a new query to `SteleDatabase.sq` that performs INSERT/UPDATE/DELETE/UPSERT, add a corresponding forwarding stub to `RestrictedDatabaseQueries` annotated `@DirectSqlWrite`.

### Coroutine dispatcher and database connection pool

The database layer uses `PlatformDispatcher.DB` for all SQL work and `DatabaseWriteActor` to serialize writes. Follow these rules when adding or modifying repository code.

#### Dispatcher matrix

| Context | Dispatcher | Mechanism |
|---|---|---|
| Read `Flow` (SQLDelight generated) | `PlatformDispatcher.DB` | `mapToList(DB)` / `mapToOneOrNull(DB)` |
| Read `flow { }` block with raw SQL | `PlatformDispatcher.DB` | `.flowOn(DB)` at end of chain |
| Write `suspend fun` | `PlatformDispatcher.DB` | `withContext(DB) { ... }` |
| `DatabaseWriteActor` internal scope | `PlatformDispatcher.Default` | Owns its own `CoroutineScope` |
| Non-database IO (files, network) | `PlatformDispatcher.IO` | Direct or `withContext(IO)` |

#### Read pattern — use in every `SqlDelight*Repository`

**Prefer `asDbFlowList` / `asDbFlowOrNull` (defined in `repository/DbFlowExtensions.kt`).** These combine `asFlow() + mapToList/mapToOneOrNull + map + catchDbError()` into one call, so the closed-DB guard is structurally impossible to forget:

```kotlin
// ✓ Preferred — list query, guard built in
override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
    queries.selectAllPagesPaginated(limit.toLong(), offset.toLong())
        .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

// ✓ Preferred — single-or-null query, guard built in
override fun getPageByUuid(uuid: PageUuid): Flow<Either<DomainError, Page?>> =
    queries.selectPageByUuid(uuid.value)
        .asDbFlowOrNull(PlatformDispatcher.DB) { it.toModel() }

// ✓ Manual chain required when mid-chain operators (e.g. conflate) are needed
override fun getAllPages(): Flow<Either<DomainError, List<Page>>> =
    queries.selectAllPages()
        .asFlow()
        .conflate()                                 // ← prevents O(N²) scans on bulk import
        .mapToList(PlatformDispatcher.DB)
        .map { list -> list.map { it.toModel() }.right() }
        .catchDbError()                             // ← must always terminate manual chains

// ✓ Custom flow with raw SQL calls (property parsing, hierarchy traversal, etc.)
override fun getBlockHierarchy(rootUuid: BlockUuid): Flow<Either<DomainError, List<BlockWithDepth>>> = flow {
    try {
        // Synchronous SQL here — flowOn switches the whole upstream to DB dispatcher
        emit(queries.selectBlockByUuid(rootUuid.value).executeAsOneOrNull()
            ?.let { buildHierarchy(it) }.right())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { emit(DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()) }
}.flowOn(PlatformDispatcher.DB)                     // ← always at the end of the chain
```

#### Write pattern — use in every `SqlDelight*Repository`

```kotlin
override suspend fun savePage(page: Page): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
    try {
        queries.transaction { queries.insertPage(...); queries.updatePage(...) }
        Unit.right()
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
    }
}
```

All write calls reach the repository through `DatabaseWriteActor`, which serializes them to a single coroutine. The `withContext(PlatformDispatcher.DB)` inside the repository ensures the write always executes on a pooled DB connection thread, regardless of which thread the actor is currently running on.

#### Platform mapping for `PlatformDispatcher.DB`

| Platform | Value | Rationale |
|---|---|---|
| JVM | `Dispatchers.IO` | `PooledJdbcSqliteDriver` pre-creates 8 connections at startup — unlimited read concurrency; pool bounds total connection count |
| Android | `Dispatchers.IO` | Android SQLite driver manages its own native connection pool |
| iOS | `Dispatchers.Default` | Native driver, GCD handles threading |
| WASM/JS | `Dispatchers.Default` | Single-threaded runtime |

#### Never do

```kotlin
// ✗ Hardcode Dispatchers.IO — wrong on iOS/WASM and bypasses pool abstraction
.mapToList(Dispatchers.IO)

// ✗ Use PlatformDispatcher.DB for non-database IO
withContext(PlatformDispatcher.DB) { File("...").readText() }

// ✗ Use PlatformDispatcher.IO for database reads — bypasses pool abstraction on JVM
.mapToList(PlatformDispatcher.IO)

// ✗ Write SQL without withContext(DB) — inconsistent thread safety across platforms
override suspend fun addReference(...): Result<Unit> {
    queries.insertBlockReference(...)  // missing withContext
}

// ✗ Pass rememberCoroutineScope() into any class that touches the DB
val scope = rememberCoroutineScope()
val actor = remember { DatabaseWriteActor(repo, scope) }  // scope cancelled on recomposition
```

### Coroutine scope ownership — `rememberCoroutineScope` must not escape composition

**Never pass a `rememberCoroutineScope()` result to a class that outlives the composable.** Compose cancels `rememberCoroutineScope` scopes when the composable leaves the composition; any object still holding that scope will throw `ForgottenCoroutineScopeException` on its next `launch`.

Rules:
- Any class instantiated inside `remember { }` must own its `CoroutineScope` internally: `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- `rememberCoroutineScope()` is for transient UI work only — event handlers, one-shot animations, button callbacks — never for objects stored in `remember`.
- Long-lived classes expose results as `StateFlow`/`Flow`; composables collect them with `collectAsState()`. They do not accept a caller-supplied scope.
- To audit: grep for `remember.*scope` or `scope.*remember` and confirm no `rememberCoroutineScope()` value is flowing into a constructor stored by `remember { }`.

Violation pattern (forbidden):
```kotlin
val scope = rememberCoroutineScope()
val manager = remember { SomeManager(scope) }  // scope will be cancelled on recomposition
```

Correct pattern:
```kotlin
// SomeManager.kt
class SomeManager(...) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
// Composable
val manager = remember { SomeManager() }
```

### Uncaught coroutine Throwables kill the process on Android — guard long-lived scopes

An uncaught `Throwable` (notably `OutOfMemoryError`) escaping any coroutine reaches the platform default uncaught-exception handler. **On Android that handler kills the process ("app keeps stopping"); on desktop JVM it only prints** — so this class of crash never reproduces on desktop. Under heap pressure the OOM is thrown in whichever coroutine allocates next, not necessarily the one doing the heavy work, so per-call-site `catch(Throwable)` is not sufficient.

Rules:
- Every long-lived `CoroutineScope` that hosts user-path collectors or fire-and-forget launches must attach a `CoroutineExceptionHandler` (see `StelekitViewModel.scope`, `GraphLoader.parallelScope`). Surface errors as `fatalError` UI state where possible.
- Standing `collect { }` bodies and `stateIn` upstream chains on such scopes are the unguarded vectors — a repository flow's `catchDbError()` does not protect them.
- Regression tests: `StelekitViewModelCrashReproductionTest`, `PageNameIndexResilienceTest`, `LargeGraphWarmStartCrashTest` (8 030-page warm start with a recording default uncaught-exception handler).

### Graph-scale reads must be paginated, projected, or chunked — never O(graph)

Every DB write invalidates SQLDelight queries on the written table, so a standing collector of an unbounded query re-materializes its **entire result set per write burst**. During graph import/reconcile on an 8 000+ page graph this causes GC thrash (UI hang) and `OutOfMemoryError` (crash) on Android. **`PageRepository` therefore has no `getAllPages()` / unbounded `getUnloadedPages()` at all — the absence is compile-time enforced.** Do not add unbounded reads back to any repository interface.

Patterns, by consumer type:
- **Standing UI observers** (sidebar, etc.): bounded queries only — `getFavoritePages()` (`WHERE is_favorite = 1`), `getPages(limit, offset)`, `getPageByUuid` point lookups.
- **Standing whole-graph observers** (e.g. `PageNameIndex`): use a **projection** (`getPageNameEntries()` — name + is_journal only), plus `conflate()` + `distinctUntilChanged()` + debounce as backpressure, plus `Throwable` guards.
- **Bulk reconcile** (`GraphLoader.loadDirectory`): per-chunk `IN`-clause lookups — `getPagesByNames(chunk)` / `getJournalPagesByDates(chunk)` — never a full-table preload. `IN` lists chunked ≤500 (`SQLITE_MAX_VARIABLE_NUMBER` = 999 on Android API < 30).
- **Background indexing** (`GraphLoader.indexRemainingPages`): drain loop over `getUnloadedPages(limit, offset)` (`INDEX_BATCH_SIZE` = 100); offset advances past permanently-failing rows via an attempted-UUID set so the loop is guaranteed to terminate; `countUnloadedPages()` provides the O(1) progress denominator.
- **Whole-graph one-shots** (export, migration tooling, benchmarks, tests): `getAllPagesSnapshot()` — a suspend interface method that pages through `getPages(limit, offset)` in bounded batches (never a single unbounded query, never a reactive flow).
- Do not pin full-table snapshots in fields (the former `cachedAllPages` pattern is forbidden).

Regression tests: `LargeGraphWarmStartCrashTest` (asserts ≤100-row batches across a full 8 030-page warm start), `GraphLoaderIndexBatchingTest` (bounded drain + termination with permanently-failing pages), `QueryPlanAuditTest` (audits query plans for the bounded query set).

### Android Application.onCreate — catch Throwable, not Exception

`Application.onCreate()` must use `catch (e: Throwable)`, not `catch (e: Exception)`. Native library loading failures (`UnsatisfiedLinkError`, `NoClassDefFoundError`) are `Error` subclasses, not `Exception`. Catching only `Exception` lets them propagate uncaught and crash the app at startup before the UI is shown. See `SteleKitApplication.kt`.

### Repository Flow resilience — use `asDbFlowList` / `asDbFlowOrNull`, never raw `asFlow()`

When `GraphManager.shutdown()` or `switchGraph()` closes the database, any Compose `LaunchedEffect` still collecting a repository `Flow` will hit `IllegalStateException` on the closed driver and crash the main thread.

**The guard:** `catchDbError()` converts this to `Either.Left(DomainError.ReadFailed)` so the UI degrades gracefully instead of crashing.

**The enforcement:** `catchDbError()` is defined once in `repository/DbFlowExtensions.kt` (not copy-pasted per file). The preferred way to apply it is through the typed helpers that build it in automatically:

```kotlin
// ✓ Guard is structural — you cannot get asDbFlowList without catchDbError
queries.selectAllPages().asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

// ✓ For manual chains that need mid-chain operators — guard must be explicit
queries.selectAllPages().asFlow().conflate().mapToList(DB).map { ... }.catchDbError()

// ✗ Raw asFlow() chain without catchDbError — will crash on DB close
queries.selectAllPages().asFlow().mapToList(DB).map { ... }
```

`flow { try/catch }` blocks that call `executeAsList()` / `executeAsOneOrNull()` directly already handle the exception inline — they do not need `catchDbError()`.

Regression tests: `UpgradeResilienceTest` (TC-UPGRADE-001 exercises every `Flow`-backed read across all repositories against a closed DB — any future method missing the guard will fail here), `RepositoryFlowResilienceTest`, `GraphManagerDatabaseLifecycleTest`.

### GitHub Actions — `workflow_call` propagates caller's `event_name`

When a workflow is called via `workflow_call`, `github.event_name` inside the called workflow reflects the **caller's** triggering event (e.g. `push`), not `workflow_call`. A job `if:` condition checking `github.event_name == 'workflow_call'` will always be false when called from a push-triggered parent. Remove the job-level `if:` entirely and rely on the workflow-level `on:` triggers instead.

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
| `kmp/src/commonMain/.../repository/DbFlowExtensions.kt` | `catchDbError`, `asDbFlowList`, `asDbFlowOrNull` — shared closed-DB guard helpers |
| `kmp/src/jvmMain/.../desktop/Main.kt` | Desktop entry point |
| `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq` | SQLDelight schema |
