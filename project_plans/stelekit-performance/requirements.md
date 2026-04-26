# Requirements: SteleKit Performance

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-24

## Problem Statement

SteleKit (a Kotlin Multiplatform outliner/note-taking app targeting Desktop JVM, Android, and iOS)
has unmeasured and potentially poor performance on its core user journeys. The suspected problem
areas span three layers:

1. **Read concurrency**: The database read dispatcher may be over-restricted, preventing scale-out
   of reads even though the underlying pool (8 connections on JVM) supports concurrency.
2. **Caching**: Blocks and pages are not cached aggressively enough, causing repeated round-trips
   to SQLite for data that hasn't changed.
3. **Mobile I/O**: The SQLite file system path used on Android/iOS may be inherently slower than
   the JVM desktop path; the current code uses the same `PlatformDispatcher.DB` abstraction but
   doesn't tune or optimize for platform-specific characteristics.

**Who has this problem**: The primary user is the project owner (Tyler Stapler) using SteleKit as
a daily personal wiki. Performance regressions are invisible until they become painful in daily use,
and there is currently no systematic way to catch them before shipping.

## Success Criteria

- Core user journeys (journal view load, page navigation, block edit → save) have measured
  baselines and explicit performance budgets.
- Performance regressions on those journeys are caught automatically (CI or benchmark gate)
  before they reach production.
- The codebase makes it easy to add new features without unknowingly degrading performance for
  existing journeys.
- Shipped and validated in production (daily use on desktop + Android).

## Scope

### Must Have (MoSCoW)
- Measure the current performance of core user journeys (end-to-end latency, not microbenchmarks)
- Identify whether reads are bottlenecked by dispatcher restrictions vs. actual I/O
- Evaluate and improve caching at the repository layer (blocks by page, pages by UUID)
- Identify if mobile SQLite I/O needs a different driver or dispatcher strategy
- Establish a repeatable benchmark/regression gate so future features can be added with confidence

### Should Have
- A simple performance dashboard or CI artifact showing trends over commits
- Automated alerting when a benchmark regresses beyond a threshold

### Out of Scope
- Cross-platform network sync or cloud storage
- UI rendering performance (Compose recomposition profiling)
- iOS-specific native profiling (instrument but don't optimize iOS-first)
- Any changes that break KMP compatibility

## Constraints

- **Tech stack**: Must remain Kotlin Multiplatform (KMP); shared logic in `commonMain`; platform
  specifics in `jvmMain` / `androidMain` / `iosMain`
- **Timeline**: No hard deadline — iterating toward a quality bar
- **Team**: Solo project
- **Dependencies**: SQLDelight 2.3.2 for DB; `DatabaseWriteActor` for write serialization;
  `PlatformDispatcher.DB` abstraction for read/write threads

## Context

### Existing Work

- **BlockHound configured** (this branch): Detects blocking calls on `Dispatchers.Default` threads
  during tests; `BlockHoundTestBase` installs it via `@BeforeClass`; blocking `Thread.sleep` calls
  replaced with `delay()`
- **OTel instrumentation added** (`a9770de16`): Comprehensive observability — SLO monitoring,
  performance export; spans and metrics are already flowing through the app
- **`PlatformDispatcher.DB`**: Already abstracts the correct dispatcher per platform
  (`Dispatchers.IO` on JVM, `Dispatchers.Default` on iOS/WASM)
- **`DatabaseWriteActor`**: Serializes all writes; already prevents write contention

### Stakeholders

- Tyler Stapler (owner, sole developer, primary user)

## Research Dimensions Needed

- [ ] Stack — evaluate caching libraries and patterns (in-memory LRU, Caffeine on JVM, etc.)
- [ ] Features — survey comparable apps (Logseq, Obsidian, Bear) for known perf strategies
- [ ] Architecture — design patterns for read concurrency, cache invalidation, mobile SQLite tuning
- [ ] Pitfalls — known failure modes: cache staleness, write-after-read races, SQLite WAL on mobile
