# Stack Research Findings: SQLite Event Sourcing, CRDT Libraries, and KMP Compatibility

**Date**: 2026-04-13
**Sources**: Training knowledge (cutoff August 2025) + web search (April 2026)
**Confidence scale**: HIGH = well-established; MEDIUM = widely discussed; LOW = reconstruction

---

## 1. SQLite Event Sourcing / Append-Only Log Patterns

**Confidence: HIGH**

### 1.1 Typical `operations` Table Schema

```sql
CREATE TABLE operations (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    graph_id    TEXT    NOT NULL,
    page_id     TEXT    NOT NULL,
    block_id    TEXT    NOT NULL,
    op_type     TEXT    NOT NULL CHECK (op_type IN ('INSERT','UPDATE','DELETE','MOVE')),
    seq         INTEGER NOT NULL,   -- Lamport timestamp
    parent_id   TEXT,
    left_id     TEXT,               -- RGA-style left-sibling pointer
    content     TEXT,               -- NULL for DELETE and MOVE ops
    properties  TEXT,               -- JSON blob
    created_at  INTEGER NOT NULL,   -- Unix milliseconds
    session_id  TEXT    NOT NULL,   -- device/session attribution
    is_undo     INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX ops_page_seq ON operations(page_id, seq);
CREATE INDEX ops_block    ON operations(block_id, seq);
```

Key decisions:
- `AUTOINCREMENT` prevents ID reuse — critical for an event log.
- `seq` is a Lamport clock, not wall-clock time. Enables ordering without clock skew.
- `session_id` attributes ops to a device/session for merge and conflict detection.
- `left_id` is an RGA-style pointer to the left sibling at insertion time — stable under concurrent inserts.
- Properties as JSON blob via SQLite JSON functions (`json_extract`, `->`), available since SQLite 3.38+ (Android API 32+, all modern JVMs).

### 1.2 Snapshots vs. Deltas

Hybrid approach: `blocks` table (current state, updated in place) + `operations` (append-only history).

| Approach | Verdict |
|---|---|
| Delta only (pure log) | Slow reconstruction; acceptable only for pure undo tracking |
| **Snapshot + delta (hybrid)** | **Recommended** |
| Snapshot only | No history, no undo |

### 1.3 WAL Mode

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA wal_autocheckpoint = 1000;
```

WAL is strongly recommended: readers never block writers, append writes are sequential in the WAL file. The existing `DriverFactory.jvm.kt` PRAGMA-via-Properties fix applies here. With `DatabaseWriteActor` serializing all writes, an explicit checkpoint on app exit is feasible.

### 1.4 Compaction Strategy

For SteleKit (single user, git sync):
- Keep all ops from the last N sessions (default 50).
- After confirmed bidirectional git sync, compact ops older than the sync watermark into a single snapshot row per block.
- Session-based compaction: on clean app exit, collapse all ops for a block within that session into one op with the final content.

### 1.5 Performance

SQLite with WAL sustains ~50,000 simple INSERTs/second. At a typical editing rate (~10 keystrokes/second), the operation log is never the bottleneck.

---

## 2. CRDT Libraries for Kotlin Multiplatform

**Confidence: HIGH** (web-verified, April 2026)

### 2.1 Survey

| Library | Language | KMP Compatible? | Notes |
|---|---|---|---|
| **concordant/c-crdtlib** | Kotlin | **Yes** | RGA support for ordered sequences; compiles to JVM + JS. Active. |
| **CharlieTap/synk** | Kotlin KMP | **Yes** | State-based CRDT with persistent key-value storage; offline-first design. |
| **Yjs** | JavaScript/TypeScript | No | JS-only; no Kotlin bindings |
| **Automerge** | Rust core + JS/Wasm | No | Could wrap via JNI but no maintained bindings |
| **Diamond Types** | Rust | No | Same JNI challenge; no KMP bindings |

**Key find (web search)**: Two production-quality KMP CRDT libraries exist — `concordant/c-crdtlib` (includes RGA for ordered sequences, compiles to JVM and JS) and `CharlieTap/synk` (state-based CRDT with persistence, built explicitly for local-first offline-first KMP apps). The training research incorrectly concluded no KMP CRDT libraries exist.

### 2.2 concordant/c-crdtlib

- GitHub: `concordant/c-crdtlib`
- Supports RGA (Replicated Growable Array) for ordered sequences
- KMP: targets JVM bytecode and JavaScript
- Also published as NPM package (`@concordant/c-crdtlib`)
- Based on the Concordant platform API

### 2.3 CharlieTap/synk

- GitHub: `CharlieTap/synk`
- State-based CRDT library for offline-first KMP applications
- Uses a special timestamp type tracking events in distributed systems
- Maintains persistent key-value storage locally on each client
- Designed explicitly for the local-first use case

### 2.4 Assessment for SteleKit

**Synk** is the more directly applicable library for SteleKit's use case (local-first, offline-first KMP app with persistent storage). **concordant/c-crdtlib** is more relevant if a sequence CRDT (RGA) is needed for block ordering.

Before using either: evaluate maturity, last commit date, test coverage, and whether they integrate with SQLDelight or require their own storage layer.

A manual RGA implementation (~150-250 lines of Kotlin) remains viable if neither library fits cleanly. The `left_id` column already exists in SteleKit's block schema.

---

## 3. cr-sqlite

**Confidence: MEDIUM** (web search confirmed current status)

### 3.1 What It Is

cr-sqlite (`vlcn-io/cr-sqlite`) is a run-time loadable SQLite extension that adds CRDT semantics to standard tables. Mark a table as a conflict-free replicated relation with `SELECT crsql_as_crr('table_name')`, then exchange changesets via `crsql_changes()` and `crsql_merge_changeset()`.

### 3.2 KMP / Android Compatibility

The project is still a native SQLite extension loaded via `sqlite3_load_extension()`. There is no dedicated KMP wrapper. The web search confirmed it remains an extension-based approach with no first-class Android or JVM packaging.

**Android**: Extension loading is disabled in the system SQLite. Requires embedding a custom `libsqlite3.so` via JNI — significant APK size and maintenance burden incompatible with SQLDelight's managed driver.

**JVM**: Possible with `sqlite-jdbc` if extension loading is enabled and platform-specific binaries bundled. Operationally complex.

**Verdict: Skip for SteleKit.** The manual operation log approach is more portable and maintainable.

---

## 4. SQLDelight 2.x Patterns for Append-Only Tables

**Confidence: HIGH**

```sql
-- Operations.sq

insertOperation:
INSERT INTO operations (graph_id, page_id, block_id, op_type, seq,
                        parent_id, left_id, content, properties,
                        created_at, session_id)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

opsForPageSinceSeq:
SELECT * FROM operations
WHERE page_id = :pageId AND seq > :afterSeq
ORDER BY seq ASC;

opsForBlock:
SELECT * FROM operations
WHERE block_id = :blockId
ORDER BY seq ASC;

lastSeqForPage:
SELECT MAX(seq) FROM operations WHERE page_id = :pageId;

compactBefore:
DELETE FROM operations WHERE page_id = :pageId AND seq < :beforeSeq;
```

### Known Limitations in SQLDelight 2.x

1. **`asFlow()` re-emits full lists**: Avoid attaching reactive queries to the full `operations` table — this would re-fetch thousands of rows on every insert. Use tight `WHERE` clauses or don't use reactive queries for the operations table at all.
2. **`RETURNING` support**: SQLDelight 2.3.x has partial support for `RETURNING` on INSERT. Test with `executeAsOne()` before relying on it.
3. **`AUTOINCREMENT` overhead**: Negligible at editing rates.
4. **Schema migrations**: Adding `operations` table requires a new `.sqm` migration file. SQLDelight's migration system handles this cleanly.
5. **Multiple `.sq` files**: SQLDelight generates a single class from all `.sq` files in the same package directory. Split: `SteleDatabase.sq` for existing tables, `Operations.sq` for the event log.

---

## 5. Relevant Libraries

| Library | Relevance | Verdict |
|---|---|---|
| **concordant/c-crdtlib** | RGA sequence CRDT for KMP | **Evaluate first** |
| **CharlieTap/synk** | State-based CRDT, offline-first KMP | **Evaluate first** |
| **kotlin.uuid.Uuid** (stdlib 2.0+) | `Uuid.random()` in `commonMain` | **Use for stable block IDs** |
| **kotlinx.serialization** | JSON for sidecar files | Likely already in dependency tree |
| **Arrow** | `Either` types for merge result modeling | Only if already a dependency |

---

## 6. Summary and Recommendations

| Decision | Recommendation | Confidence |
|---|---|---|
| Operation log schema | Append-only `operations` table with AUTOINCREMENT, `seq`, `session_id`, `left_id` | HIGH |
| SQLite mode | WAL + `synchronous=NORMAL` via Properties on connection open | HIGH |
| State model | Hybrid: `blocks` (current state) + `operations` (append-only history) | HIGH |
| Compaction | Session-based on clean exit; watermark-based after confirmed git sync | MEDIUM |
| CRDT library | Evaluate `synk` and `concordant/c-crdtlib`; fall back to ~200-line manual RGA | HIGH |
| cr-sqlite | Not viable for SteleKit | HIGH |
| Stable block UUIDs | `Uuid.random()` on first import; persist to `.stelekit/` sidecar | HIGH |

---

## Web Search Sources

- [concordant/c-crdtlib — GitHub](https://github.com/concordant/c-crdtlib)
- [CharlieTap/synk — GitHub](https://github.com/CharlieTap/synk)
- [Synking all the things with CRDTs: Local first development — DEV Community](https://dev.to/charlietap/synking-all-the-things-with-crdts-local-first-development-3241)
- [Offline should be the norm: building local-first apps with CRDTs & KMP — Speaker Deck](https://speakerdeck.com/renaudmathieu/offline-should-be-the-norm-building-local-first-apps-with-crdts-and-kotlin-multiplatform)
- [vlcn-io/cr-sqlite — GitHub](https://github.com/vlcn-io/cr-sqlite)
- [Logseq DB Unofficial FAQ](https://discuss.logseq.com/t/logseq-db-unofficial-faq/32508)
- [Logseq DB sync discussion](https://discuss.logseq.com/t/syncing-logseq-db/31285)
