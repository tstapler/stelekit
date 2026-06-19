# Storage Engine Research

_Research date: 2026-06-19. Engines evaluated for SteleKit KMP use: JVM/Desktop, Android (`aarch64-linux-android`), iOS (`aarch64-apple-ios`), WASM (`wasm32-unknown-unknown` / `wasm32-wasi`)._

---

## Summary Matrix

| Engine | Android arm64 | iOS arm64 | WASM | Memory model | Binary (.so arm64) | ACID | Stars / Status |
|---|---|---|---|---|---|---|---|
| **redb** | Likely works (no C deps, no CI) | Likely works (no CI) | CI-verified `cargo check`; WASI tests run | Heap pread/pwrite; no mmap | ~300–500 KB | Full ACID, CoW B-tree | 4,600 / active |
| **LMDB** | **Ready** — Maven artifacts published | Compilable; no packaging | **BLOCKED** — mmap + POSIX locks | mmap CoW B-tree (VA = DB size) | ~60–120 KB | Full ACID, no WAL | ~3,000 / stable |
| **heed** (LMDB Rust) | Theoretically yes; untested | Theoretically yes; untested | **BLOCKED** — inherits LMDB | Inherits LMDB mmap | ~200–400 KB | Inherits LMDB | 896 / active |
| **sled** | BLOCKED — mandatory C `zstd` dep | BLOCKED — same | **BLOCKED** — rayon/parking_lot/fs2/zstd | Heap + log-structured | ~1.5–2.5 MB | Pre-1.0 rewrite; not safe | 9,000 / stalled |
| **fjall** | Theoretically yes; untested | Theoretically yes; untested | **BLOCKED** — std::fs + threads | Heap LSM; bounded block cache | ~2.2 MB (self-reported) | WAL + serializable txn | 2,100 / active |
| **persy** | Theoretically yes; untested | Theoretically yes; untested | BLOCKED (fs2) | Heap/page; no mmap | ~1–2 MB (est.) | WAL-style 2PC | N/A GitLab / active |
| **SQLite minimal** | **Gold standard** | **Gold standard** (system fw) | **Yes** — official sqlite.wasm (~897 KB; ~600 KB min) | Page cache; optional mmap | ~350–500 KB (stripped, no FTS5/RTree) | Full ACID, WAL | Billions of deploys |
| **nebari** | Likely works; no CI | Likely works; no CI | BLOCKED — flume/parking_lot/num_cpus | Heap LRU; append-only file | ~400–700 KB | Full ACID; append-only | 283 / **inactive since Oct 2023** |
| **native_db** | **CI-confirmed** (redb backend) | **CI-confirmed** (redb backend) | BLOCKED — redb backend uses mmap | Inherits redb | ~1–2 MB | Inherits redb ACID | 703 / unstable API |
| **surrealKV** | Untested | Untested | **Explicitly unsupported** | Heap LSM (Tokio async) | ~3–5 MB | Full ACID MVCC | 532 / active |
| **agatedb** | None | None | None | Heap LSM | N/A | Partial | 891 / **abandoned** |

---

## H2 Engine Sections

### redb

**Language + License:** Pure Rust (zero C deps, no `unsafe` in core). MIT OR Apache-2.0. MSRV: Rust 1.89.

**Android (`aarch64-linux-android`):** No Android CI, but the pure-Rust + zero-external-C-deps nature means `cargo build --target aarch64-linux-android` with NDK toolchain has no structural blockers. CI runs on `ubuntu-24.04-arm` (native ARM64 Linux), confirming the ARM64 codepath is tested. No published `.so` artifacts; you compile a `cdylib` JNI wrapper yourself. A CPython binding (`crates/redb-python` via PyO3/maturin) in the monorepo demonstrates the pattern.

**iOS (`aarch64-apple-ios`):** No iOS CI. Same reasoning as Android: no C deps, no `build.rs` hazards, only `std::fs::File` and `std::io`. Expected to work; not verified.

**WASM:** Active, CI-tested. CI explicitly runs:
- `cargo check -p redb --target wasm32-unknown-unknown` (all-features and no-default-features)
- WASI test execution via wasmtime 24.0.0 (`just test_wasi`)

No `mmap` anywhere in the codebase (FileBackend uses `pread`/`pwrite`; InMemoryBackend uses `Vec<u8>`). `Mutex`/`RwLock` in `std::sync` work in WASM's single-threaded mode. Release notes for 3.1.0 explicitly fix "compilation error on wasm32-unknown."

**Memory model:** Heap-based. FileBackend = direct `read`/`write` + `fsync` via `std::fs::File`. No `mmap` syscall. Active B+tree pages brought into heap on demand. Configurable page cache (default 16 MB). For 100 MB dataset: 5–30 MB heap depending on read/write patterns.

**ACID / crash safety:** Full ACID. Two commit strategies: 1PC+C (single `fsync` + XXH3-128 checksum Merkle tree, default) or 2PC (two-fsync). MVCC copy-on-write B+trees. Single writer, multiple concurrent readers. Crash recovery via quick-repair or full B+tree walk. Savepoints with rollback. File format is stable since v1.0.

**Binary size (Android arm64 .so):** Estimated ~300–500 KB stripped. No C deps, no compression, no regex. Minimal mandatory dependency graph. Comparable to SQLite binary surface with less feature area.

**Maturity:** 4,600 GitHub stars, 64 releases, v4.1.0 (April 19, 2026), actively maintained. Self-described as "stable and maintained." 1,574 commits. Production users include Tantivy ecosystem tooling; Python wrapper (PyO3/maturin) in monorepo. No known major mobile production deployment.

**JNI bridgeability:** No existing Java/Kotlin binding. Write a thin `cdylib` wrapping `Database`, `WriteTransaction`, `ReadTransaction` in `Arc<Mutex<...>>` + JNI functions via the `jni` Rust crate. API surface is small (~10 core types). Effort: ~1–2 weeks. Pattern mirrors the existing PyO3 wrapper.

---

### LMDB

**Language + License:** C (96.9%). OpenLDAP Public License v2.8 — permissive BSD-style, app-store compatible.

**Android (`aarch64-linux-android`):** **Production-ready with Maven artifacts.** `org.lmdbjava:lmdbjava` (Java JNI wrapper, 876 stars, Apache 2.0, v0.9.3 Maven Central) publishes `lmdbjava-native-linux-android-arm64-v8a` (v0.9.29-1), `armeabi-v7a`, `x86`, and `x86_64` slices. The compile flag `-DMDB_USE_ROBUST=0` is required (Android lacks POSIX robust mutexes). Zero-copy `BufferCursor` mode is unavailable on Android (`DirectByteBuffer` restriction) — fallback copy mode works fine.

**iOS (`aarch64-apple-ios`):** Compilable via Xcode's clang with no unusual libc dependencies. The Swift Package (RapidLMDB / tannerdsilva) has been **archived**. No official CocoaPod, XCFramework, or KMP cinterop binding. Would require a manual `build.rs` + `.def` cinterop file or a prebuilt static `.a`.

**WASM:** **Permanently blocked.** LMDB's entire read path depends on `mmap` (CoW B+tree pages live in the mapped region). WASM has no `mmap` equivalent — WASI offers only partial read-only emulation; `wasm32-unknown-unknown` has none. POSIX locks are unavailable. The heed maintainers closed issue #162 ("WASM support") as "not planned": _"highly unlikely that LMDB will compile for web for many years to come."_ This is an architectural impossibility, not a 6-month gap.

**Memory model:** Pure mmap-based CoW B+tree. Entire DB file mapped up to declared `MDB_MAPSIZE` (default 10 MB; must be set to ≥ DB size manually). VA space is reserved on `mdb_env_open`; RSS covers only accessed pages (OS demand-paging). On 64-bit Android (aarch64), large VA reservations are safe. Long-lived read transactions pin pages and prevent file compaction — known footgun. For 100 MB data: VA = 100 MB, RSS = accessed subset.

**ACID / crash safety:** Full ACID. No WAL — uses CoW B+tree with double-buffered meta-pages (pages 0 and 1). Data pages written + `fsync`'d before meta-page update; prior version remains valid across any crash. MVCC: readers never block writers; writers serialize. Default: fully durable (`fsync` per commit). `MDB_NOSYNC` option trades durability for speed (risky).

**Binary size (Android arm64 .so):** ~60–120 KB stripped. The LMDB C core is cited as ~32 KB object code (Mozilla documentation). One of the smallest KV stores available. The Arch Linux ARM aarch64 package totals 730 KiB (with headers and static lib).

**Maturity:** GitHub mirror (LMDB/lmdb): ~3,000 stars, 641 forks, 1,375 commits. Latest release: 0.9.33 (March 21, 2024) — slow, steady cadence. Production users: OpenLDAP (primary), Mozilla Firefox (via `rkv` since Firefox 63), RavenDB, 143+ npm packages. Extremely mature; the stable API has not changed significantly in years.

**JNI bridgeability:** Best of all candidates. `lmdbjava` (876 stars, Apache 2.0, Maven Central, v0.9.3, actively maintained) provides a complete Java JNI wrapper with prebuilt Android arm64 artifacts. `implementation("org.lmdbjava:lmdbjava:0.9.3")` plus the matching native slice is a drop-in. LMDB's API surface (~15 core functions) is small enough that a custom JNI layer is also straightforward.

---

### heed

**Language + License:** Pure Rust over LMDB C via `lmdb-sys` submodule. MIT. Maintained by Meilisearch.

**Android (`aarch64-linux-android`):** Theoretically yes — `cargo-ndk` + `-DMDB_USE_ROBUST=0` via `lmdb-sys` build script. No Android CI, no published Maven artifact, no community success reports found.

**iOS (`aarch64-apple-ios`):** Theoretically yes. No official support, no XCFramework.

**WASM:** Blocked — inherits every LMDB constraint. Issue #162 closed "not planned."

**Memory model:** Identical to raw LMDB. Typed Rust wrappers (`Database<K, V>`) add zero heap overhead beyond Serde deserialization; data pages live in the mmap region.

**ACID / crash safety:** Full ACID inherited from LMDB. `RwTxn::commit()` maps directly to `mdb_txn_commit`.

**Binary size:** Estimated ~200–400 KB stripped (LMDB C core ~60–120 KB + Rust std overhead after LTO + `opt-level=z`).

**Maturity:** 896 stars, 78 forks, v0.22.1 (April 7, 2026), actively maintained. crates.io: 3,809,471 total downloads. Meilisearch uses it in production on Linux servers. No known mobile production deployment.

**JNI bridgeability:** No existing JNI/Java/Kotlin binding. Would need a `cdylib` wrapping heed via the `jni` crate. Alternatively: use lmdbjava on JVM/Android and heed only in a Rust-native layer on other targets — splitting the bridge concern cleanly.

---

### sled

**Language + License:** Pure Rust (main branch pulls C via mandatory `zstd` dep). MIT OR Apache-2.0.

**Android (`aarch64-linux-android`):** **BLOCKED.** The `main` branch has `zstd` as a mandatory direct dependency (C code), blocking NDK cross-compilation unless made optional. The only stable release (v0.34.7, September 12, 2021) is 4+ years old and unsupported.

**iOS (`aarch64-apple-ios`):** **BLOCKED** — same `zstd` C dependency.

**WASM:** **Not supported.** `main` branch depends on `rayon` (threads), `parking_lot` (OS thread primitives), `fs2` (file locking), `zstd` (C) — all fundamentally incompatible with `wasm32-unknown-unknown`. No WASM CI. No port planned.

**Memory model:** Log-structured heap with page-based I/O. Object cache (LRU + EBR epoch-based reclamation). No mmap. Background flusher thread. Working set: 15–50 MB heap for 100 MB data (varies by cache config).

**ACID / crash safety:** The `main` branch rewrite has known, documented bugs (listed as "1.0 blockers" at the top of `src/lib.rs`). ACID correctness is not guaranteed. The stable 0.34.7 had serializable transactions but is abandoned.

**Binary size (Android arm64 .so):** ~1.5–2.5 MB (with zstd); not practically buildable for Android currently.

**Maturity:** 9,000 GitHub stars (historical). Last stable release: v0.34.7 (Sep 2021). `main` branch commits are sporadic (5–6 month gaps). The rewrite ("project bloodstone") has been ongoing for years with no 1.0 release date. Stars reflect historical popularity; the project is effectively stalled. **Not recommended for new projects.**

**JNI bridgeability:** High risk. Unstable API (rewrite), C dependency complicating NDK builds, no mature crate to bind against.

---

### fjall

**Language + License:** Pure safe Rust, zero `unsafe` in core, zero C FFI. MIT OR Apache-2.0.

**Android (`aarch64-linux-android`):** Theoretically yes — uses only `std::fs`, `std::io`, `std::thread`, `std::sync`. `cargo ndk --target aarch64-linux-android build --release` should work. Caveat: GitHub issue #225 notes `File::lock()` is "unsupported on some platforms" — `flock`/`fcntl` lock quirks on older Android API levels need validation. No CI targets, no published AAR, no community reports.

**iOS (`aarch64-apple-ios`):** Theoretically yes. Same `File::lock()` caveat applies.

**WASM:** **Blocked.** `std::fs` stubs always-error on `wasm32-unknown-unknown`. `std::thread` incompatible with WASM's single-threaded model. WASM threads (Atomics proposal) are experimental and not universally supported. Not a project goal.

**Memory model:** Heap-based LSM tree. Writes go to in-memory MemTable → flush to immutable SSTable files → compact in background. Block cache is user-configurable per level. Memory footprint is bounded by configured block cache size, not dataset size — meaningful advantage on memory-constrained Android. No mmap, no VA reservation.

**ACID / crash safety:** WAL-based durability (standard LSM pattern: writes to WAL first, then MemTable; WAL replayed on crash). Serializable snapshot isolation (optimistic concurrency control) as of fjall 2.x/3.x. Checksums on block reads (128-bit xxh3). No mmap means no silent corruption from stray pointer writes.

**Binary size (Android arm64 .so):** ~2.2 MB (self-reported in fjall 3.0 release post). Notably larger than LMDB (~60–120 KB) or redb (~300–500 KB). LSM compaction adds code size.

**Maturity:** ~2,100 GitHub stars, v3.1.5 (June 8, 2026), actively developed, 92 releases, 1,082,837 crates.io downloads, ~90,912/month. 122 dependent crates. Relatively young (2–3 years). No publicly named production users found; ecosystem users are mostly other Rust libraries.

**JNI bridgeability:** No existing binding. Write a `cdylib` wrapping fjall via the `jni` crate (~20–30 JNI functions for put/get/delete/scan/txn). Moderate effort. Pure Rust simplifies NDK build (no C ABI wrestling).

---

### persy

**Language + License:** Rust. MPL-2.0. GitLab-hosted (gitlab.com/persy/persy).

**Android (`aarch64-linux-android`):** Not confirmed. Pure Rust with no FFI deps; uses `fs2` (file locking) and `rand`. Theoretically cross-compilable via `cargo-ndk` but no documented support, no CI evidence, no community reports.

**iOS (`aarch64-apple-ios`):** No documented iOS support or CI targets.

**WASM:** Blocked — `fs2` uses OS-level file locking primitives unavailable in WASM. `std::fs` also absent in `wasm32-unknown-unknown`.

**Memory model:** Custom segment/page layout on disk. Copy-on-write transactions. No mmap. Does not pin file-mapped memory.

**ACID / crash safety:** Full ACID with WAL-style two-phase commit (prepare + commit).

**Binary size:** Estimated ~1–2 MB stripped Android arm64 `.so` (source is 705 KB; total dependency disk footprint ~1–2.3 MB). No public benchmark found.

**Maturity:** 81 releases, v1.7.1 (Sep 26, 2025). ~62,685 crates.io downloads/month, 550k all-time. Ranks #23 in lib.rs database implementations. Single maintainer (tglman). 35 dependent crates (13 direct). No major production users documented. MPL-2.0 license requires source disclosure of modifications to persy itself (copyleft on the library) — may affect proprietary app distribution strategy.

**JNI bridgeability:** Pure Rust, straightforward JNI wrapping. No existing published binding.

---

### SQLite compiled minimal

**Language + License:** C. Public domain (SQLite is explicitly placed in the public domain).

**Android (`aarch64-linux-android`):** Gold standard. System SQLite available on every Android device. Custom builds via NDK are well-established. `rusqlite` bundles and cross-compiles SQLite via `cc` crate. requery/sqlite-android publishes pre-built arm64-v8a `.so` (~1.7 MB), armeabi-v7a (~1.2 MB).

**iOS (`aarch64-apple-ios`):** Gold standard. SQLite ships as system framework (`libsqlite3.tbd`) on iOS. Custom builds via xcconfig + Clang also work.

**WASM:** **Yes — official sqlite.wasm exists** (sqlite.org/wasm, github.com/sqlite/sqlite-wasm). Build sizes:
- Default official build: **897 KB** (.wasm) + 469 KB JS glue
- `wa-sqlite` stripped build: **566 KB** .wasm + 229 KB JS
- With heavy `SQLITE_OMIT_*` flags: ~822 KB .wasm achievable; theoretical floor ~600 KB
- `-Oz` vs `-O2`: ~10% size reduction with minor perf impact
- Maximum omission (per official docs): below 300 KB — but breaks ORM/FTS compatibility

Key `SQLITE_OMIT_*` flags for size:
- `SQLITE_OMIT_FTS5` — removes full-text search (largest single reduction)
- `SQLITE_OMIT_RTREE` — removes R*tree spatial indexing
- `SQLITE_OMIT_JSON` — removes JSON1 extension
- `SQLITE_OMIT_WINDOWFUNC` — removes window functions
- `SQLITE_OMIT_UTF16` — removes UTF-16 support (safe if UTF-8 only)
- `SQLITE_OMIT_LOAD_EXTENSION` — removes dynamic extension loading
- `SQLITE_OMIT_TRIGGER` — removes triggers (breaks some ORMs)
- `SQLITE_OMIT_VIEW` — removes views

For SteleKit specifically, `SQLITE_OMIT_FTS5` would break the FTS index and require a separate FTS layer replacement.

**Memory model:** Page cache (default 2000 pages × 4 KB = ~8 MB; tunable via `PRAGMA cache_size`). WAL mode for MVCC reads. Optional mmap via `PRAGMA mmap_size` (disabled by default). Full control over memory footprint.

**ACID / crash safety:** Fully ACID. WAL mode provides crash-safe concurrent reads. 100%+ branch test coverage. Industry gold standard for 20+ years.

**Binary size (Android arm64 .so, custom build):** Default: ~1.7 MB (requery/sqlite-android). With typical embedded profile (no FTS5, no R*tree, no triggers, no JSON): estimated **~350–500 KB** stripped. Maximum omissions: below 300 KB (per official docs, though this sacrifices significant functionality).

**Maturity:** Billions of deployments, 20+ years, formal verification via SQL Logic Test. Zero concerns.

**JNI bridgeability:** `rusqlite` (64M+ all-time crates.io downloads) provides a complete, safe Rust wrapper. Standard JNI via the `jni` crate on top of rusqlite is the most battle-tested path in the ecosystem. SQLDelight can also generate typed Kotlin directly against SQLite — no JNI layer needed if staying on the JVM path.

---

### nebari

**Language + License:** Pure Rust (`unsafe-forbid`). MIT OR Apache-2.0. MSRV: Rust 1.56.1.

**Android (`aarch64-linux-android`):** Likely works (no C deps, no mmap). `backtrace` dependency may have symbol-resolution quirks on Android (non-critical). No CI evidence.

**iOS (`aarch64-apple-ios`):** Expected to work. `num_cpus` has iOS support. Not CI-verified.

**WASM:** **Not supported.** `flume` (channels), `parking_lot`, and `num_cpus` all require OS thread primitives unavailable in `wasm32-unknown-unknown`. No WASM issues or PRs found.

**Memory model:** No mmap. LRU cache for hot B+tree nodes. Append-only file format. **Important**: deleted/updated data accumulates until explicit user-triggered compaction — on-disk size grows unboundedly with heavy write workloads.

**ACID / crash safety:** Full ACID via append-only format. Durability guaranteed by `fsync` before reporting commit success. Crash recovery: scan backwards from end of file to last valid transaction; partial writes truncated.

**Binary size (Android arm64 .so):** Estimated ~400–700 KB.

**Maturity:** 283 GitHub stars, 8 forks. Latest release: v0.5.5 (Feb 27, 2023). **Last commit: October 11, 2023. No commits in 2024, 2025, or 2026.** 24 open issues. The BonsaiDb project (sole user) also in low-activity state. **Effectively unmaintained. Do not adopt.**

**JNI bridgeability:** Low technical complexity but high strategic risk given maintenance status.

---

### native_db

**Language + License:** Rust (redb backend). MIT.

**Android (`aarch64-linux-android`):** **Confirmed CI** — explicit `build_test_android.yml` CI job.

**iOS (`aarch64-apple-ios`):** **Confirmed CI** — explicit `build_test_ios.yml` CI job.

**WASM:** Blocked — redb backend uses mmap on some platforms; `native_db` adds no WASM support.

**Memory model:** Inherits redb's mmap-based CoW B+trees. Concerns for Android 15+ devices with 16 KB page size (different from redb's default 4 KB page assumptions).

**ACID / crash safety:** Inherits redb's full ACID guarantees.

**Binary size:** ~1–2 MB (adds proc-macro + Serde serialization overhead on top of redb).

**Maturity:** 703 stars, v0.8.2 (Jul 8, 2025), 28 releases. Unstable API. Small community. Used in Oku browser and one scheduler project. ORM-style models defined via Rust macros (`#[native_db]`, `#[native_model]`) — complicates Kotlin-side modeling.

**JNI bridgeability:** Pure Rust, wrappable. No existing JNI binding. The macro-driven model system would add friction for a Kotlin bridge.

---

### surrealKV

**Language + License:** Rust. Apache-2.0.

**Android / iOS:** Untested. No CI.

**WASM:** **Explicitly unsupported** (stated in README: "Requires file system access not available in WASM environments. WAL and VLog operations are not compatible").

**Memory model:** Heap-based LSM tree (Tokio async). VLog (Wisckey) for large values. Configurable memtable (default 100 MB). Snappy compression.

**ACID / crash safety:** Full ACID with MVCC snapshot isolation.

**Binary size:** ~3–5 MB (Tokio + Snappy deps).

**Maturity:** 532 stars, v0.21.2 (May 12, 2026), backed by SurrealDB company. Tokio async runtime adds complexity to JNI bridging (requires careful runtime management across JNI boundary).

**JNI bridgeability:** Complex due to async Tokio runtime at the boundary.

---

### agatedb

Not viable. Explicitly "early heavy development / experimental." No releases published. Rust port of BadgerDB for TiKV. No CI beyond Linux. Effectively abandoned. **Do not evaluate further.**

---

## WASM Platform: The Fundamental Problem

**Every Rust embedded database evaluated is blocked from `wasm32-unknown-unknown`** due to one or more of:
- `std::fs` stubs (always-error in WASM)
- `mmap` syscall (not available in WASM; only WASI offers partial read-only emulation)
- Background threads (`std::thread`) incompatible with WASM's single-threaded default
- POSIX file locks unavailable

The only production-ready WASM storage solution found is **sqlite.wasm** (official, backed by the SQLite team). redb is the closest Rust alternative — it passes `cargo check` for `wasm32-unknown-unknown` and runs WASI tests under wasmtime — but requires a custom persistence backend (OPFS adapter) because its file-based storage uses `std::fs`. An in-memory `InMemoryBackend` works today for WASM with no persistence.

**Practical implication for SteleKit:** The WASM target needs sqlite.wasm regardless of what engine is chosen for JVM/Android/iOS. There is no single Rust engine that covers all four targets today.

---

## Top-3 Candidates

### 1. redb — Best overall cross-platform Rust engine

**Why:** The only Rust embedded KV store with active WASM CI (both `cargo check` for `wasm32-unknown-unknown` and actual WASI test execution). Zero C dependencies guarantees clean `cargo build` to any Rust target. Full ACID, stable file format since v1.0, actively maintained (v4.1.0, April 2026). Smallest estimated binary (~300–500 KB). No mmap means no WASM architectural blocker and no mmap footprint concerns on Android. The JNI bridge (a `cdylib` wrapping `Database` in `Arc<Mutex<...>>`) follows the same pattern as the existing PyO3 Python binding in the monorepo.

**Gap:** No Android/iOS CI proof. No Java JNI binding published. Requires a custom JNI wrapper crate (~1–2 weeks). WASM persistence requires an OPFS adapter on top of `InMemoryBackend`.

**Use it if:** The team accepts writing the JNI bridge and OPFS adapter, wants ACID Rust storage with WASM viability, and wants to avoid C dependencies entirely.

---

### 2. LMDB (via lmdbjava on JVM/Android, cinterop on iOS) — Best for JVM/Android today

**Why:** The only engine with published, maintained Maven artifacts for Android arm64 (`lmdbjava-native-linux-android-arm64-v8a`). Smallest binary of any engine (~60–120 KB). Production-proven in Mozilla Firefox, OpenLDAP, RavenDB. Extremely mature (0.9.33, March 2024). The lmdbjava Java wrapper (876 stars, Apache 2.0) is a drop-in on JVM/Android — no custom JNI work required. LMDB's copy-on-write B+tree with demand-paged mmap gives near-zero memory overhead for read-heavy workloads.

**Gap:** WASM is an architectural impossibility — LMDB cannot be ported to WASM. The WASM target must use a different backend (sqlite.wasm). iOS requires manual cinterop setup (no published XCFramework). The `MDB_MAPSIZE` must be pre-declared, adding operational complexity. Long-lived read transactions block file compaction.

**Use it if:** The JVM/Android targets are the priority and the team accepts a two-backend strategy (LMDB on native + sqlite.wasm on web). The zero-JNI-work story on Android is a strong argument given the team size (one developer).

---

### 3. SQLite (minimal compile) — Lowest risk, highest coverage

**Why:** The only engine that is production-ready on all four targets simultaneously — Android (NDK + rusqlite), iOS (system framework), WASM (official sqlite.wasm), JVM (rusqlite / SQLDelight). The existing SteleKit codebase is built around SQLDelight + SQLite, so this is the "stay the course" option with a binary-size optimization pass. With `SQLITE_OMIT_FTS5 + SQLITE_OMIT_RTREE + SQLITE_OMIT_JSON + SQLITE_OMIT_WINDOWFUNC + SQLITE_OMIT_UTF16 + SQLITE_OMIT_LOAD_EXTENSION`, the Android `.so` drops from ~1.7 MB to an estimated ~350–500 KB. The WASM build drops from 897 KB to ~600 KB minimum.

**Gap:** Omitting FTS5 requires replacing the FTS index layer (see `fts-options.md`). The "minimal compile" benefit is moderate — SQLite is already small relative to the DuckDB baseline that was rejected. There is no memory footprint improvement beyond page cache tuning (already available in the current libsql setup).

**Use it if:** The team wants zero platform risk, minimal migration effort from the current libsql setup, and is willing to replace only the FTS layer (see Tantivy research in `fts-options.md`) rather than the entire storage engine.
