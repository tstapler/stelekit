# Research Plan: SteleKit Performance

**Requirements input**: `../requirements.md`
**Date**: 2026-04-24

## Subtopics

### 1. Stack
**Focus**: KMP-compatible caching libraries and SQLite driver/pool options  
**Search strategy**: Survey Caffeine (JVM), AndroidX LRU cache, custom in-memory maps in KMP context; SQLite WAL mode; PooledJdbcSqliteDriver options  
**Search cap**: 4 searches  
**Trade-off axes**: KMP compatibility, eviction policy, thread safety, memory overhead, integration complexity

### 2. Features
**Focus**: How comparable note-taking apps (Logseq, Obsidian, Bear) approach block/page caching and mobile DB performance  
**Search strategy**: Look for public architecture posts, GitHub issues, or changelogs discussing perf; Logseq is open-source so code is inspectable  
**Search cap**: 5 searches  
**Trade-off axes**: Caching strategy, DB choice, mobile perf approach, offline correctness

### 3. Architecture
**Focus**: Read concurrency patterns with SQLDelight, cache invalidation alongside `DatabaseWriteActor`, mobile SQLite WAL tuning  
**Search strategy**: SQLDelight flow/dispatcher patterns; Kotlin coroutine channel vs actor for read fan-out; cache invalidation with StateFlow  
**Search cap**: 4 searches  
**Trade-off axes**: Consistency vs. throughput, invalidation granularity, code complexity, correctness under concurrent writes

### 4. Pitfalls
**Focus**: Known failure modes for in-process SQLite caching, WAL on Android/iOS, benchmark flakiness, StateFlow vs. DB emission races  
**Search strategy**: SQLite WAL Android issues, SQLDelight known bugs, KMP coroutine test flakiness patterns  
**Search cap**: 4 searches  
**Trade-off axes**: Risk severity, detectability, mitigation availability

## Output Files
- `research/findings-stack.md`
- `research/findings-features.md`
- `research/findings-architecture.md`
- `research/findings-pitfalls.md`
- `research/synthesis.md` (parent agent, after all findings complete)
