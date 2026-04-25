# Research Plan: Android Save & Parse Performance

**Project**: android-save-parse-performance  
**Date**: 2026-04-23

## Subtopics & Search Strategy

### 1. Stack — Measurement Infrastructure
**Question**: What is the right tool to measure save/parse latency on a real Android device and gate it in CI?  
**Search cap**: 5 searches  
**Axes**: Setup complexity, CI integration, on-device accuracy, emulator support, KMP compatibility  
**Key queries**:
1. `Android Macrobenchmark library vs instrumentation test latency measurement`
2. `androidx.benchmark.macro AndroidX Benchmark KMP Kotlin Multiplatform`
3. `Android Macrobenchmark CI emulator cold start measurement`

---

### 2. Features — SQLite Tuning on Android
**Question**: What SQLite PRAGMAs or driver configuration options meaningfully reduce write latency on Android?  
**Search cap**: 5 searches  
**Axes**: Write throughput, durability tradeoff, crash safety, compatibility with `AndroidSqliteDriver`/`RequerySQLiteOpenHelperFactory`  
**Key queries**:
1. `SQLite WAL mode synchronous=NORMAL vs OFF Android write performance`
2. `RequerySQLiteOpenHelperFactory AndroidSqliteDriver performance tuning`
3. `SQLite cache_size temp_store page_size Android write latency`

---

### 3. Architecture — Bottleneck Identification
**Question**: Is the several-second save/parse latency caused by `DatabaseWriteActor` queue contention, file I/O (read-before-write safety check), markdown parsing, or Aho-Corasick reconstruction?  
**Search cap**: 4 searches  
**Axes**: Fix complexity, correctness risk, platform specificity, measurability with existing OTel spans  
**Key queries**:
1. `Kotlin coroutines Channel actor serialization throughput bottleneck`
2. `SQLDelight Android write throughput DatabaseWriteActor contention`
3. `Android file I/O latency internal storage SAF okio`

---

### 4. Pitfalls — Android Storage & Driver Edge Cases
**Question**: What are known failure modes when measuring or optimizing SQLite and file I/O performance on Android?  
**Search cap**: 4 searches  
**Axes**: Frequency in production, severity, detectability, workaround availability  
**Key queries**:
1. `Android SAF Storage Access Framework performance latency file write`
2. `AndroidSqliteDriver RequerySQLiteOpenHelperFactory known issues`
3. `Android emulator SQLite performance vs real device Macrobenchmark`
4. `SQLite WAL Android crash corruption recovery`

---

## Scope Boundaries
- iOS and JVM performance excluded
- Compose rendering/frame timing excluded (JankStats already wired)
- Network/sync excluded
- Synthesis will produce a single `synthesis.md` as input to `/plan:adr`
