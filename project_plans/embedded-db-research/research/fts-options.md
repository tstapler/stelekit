# Rust FTS Options for KMP (JVM / Android / iOS / WASM)

**Context**: SteleKit needs to replace SQLite FTS5 (BM25 + `highlight()`) with a Rust FTS component that compiles to all four KMP targets. ~100 000 markdown blocks.

---

## Summary Comparison Table

| Option | WASM | Android arm64 | iOS arm64 | BM25 | Highlight/Snippet | Est. binary size (Android arm64 .so) | LOC to integrate | Status |
|---|---|---|---|---|---|---|---|---|
| **Tantivy** | ✗ (blocked; RFC open since 2019) | ✓ (via flutter_tantivy / FFI) | ✓ (via flutter_tantivy / FFI) | ✓ (same as Lucene ≥6) | ✗ native; snippet extraction manual | ~4–8 MB stripped | ~500–1 000 (JNI + Kotlin glue) | Active (v0.26.1, May 2026, 15.4k ⭐, MIT) |
| **Stork** | ✓ (primary target) | ✗ | ✗ | ✗ (TF·IDF-based, not BM25) | ✓ (built-in) | N/A (WASM only) | N/A | **Abandoned** (v1.6.0, Jan 2023, no further commits) |
| **milli** (Meilisearch core) | ✗ | Unknown (no known ports) | Unknown (no known ports) | ✓ (ranking_rules system) | ✓ (crop/highlight API) | ~10–20 MB (LMDB dependency) | Very high — deeply coupled to Meilisearch's indexer pipeline | **Archived** (repo archived Apr 2023; code merged into meilisearch main) |
| **probly-search** | ✓ (explicit claim + demo) | ✓ (pure Rust, no native deps) | ✓ (pure Rust, no native deps) | ✓ (BM25 + zero-to-one modes) | ✗ (no built-in) | <1 MB | ~200–400 | Low activity (v2.0.1 Jul 2024, 73 ⭐) |
| **bm25 crate** (Michael-JB) | ✓ (WASM demo live) | ✓ (pure Rust, no native deps) | ✓ (pure Rust, no native deps) | ✓ | ✗ (scoring/search only, no snippet extraction) | <500 KB | ~100–300 | Active (v2.3.2 Sep 2025, 65 ⭐, MIT) |
| **Custom minimal BM25** | ✓ (pure Rust) | ✓ | ✓ | ✓ | ✓ (you implement) | <200 KB | ~600–1 200 (index + BM25 + highlight) | You own it |

---

## Tantivy

**GitHub**: https://github.com/quickwit-oss/tantivy  
**License**: MIT  
**Stars**: 15.4k (May 2026)  
**Latest release**: v0.26.1 (May 10, 2026) — actively maintained by Quickwit/Datadog  
**crates.io**: https://crates.io/crates/tantivy

### WASM support

No. The WASM RFC (#541) was opened in April 2019 and remains open. The core maintainers explicitly noted that WASM would affect feature development, programming style, and CI infrastructure in fundamental ways. The blockers are:

- Tantivy uses Rayon for multi-threaded indexing; WASM (wasm32-unknown-unknown) has no threads API.
- `mmap`-based directory (`MmapDirectory`) is not available in the browser.
- Tantivy uses `crossbeam` and platform-specific lock-free structures.

A single-threaded WASM build would require disabling most of what makes Tantivy fast. As of 2026, no official WASM target exists. The issue tracker shows no merged WASM PR.

### Android / iOS cross-compilation

**Yes** — confirmed by two real-world Flutter projects:

- **flutter_tantivy** (aristojeff/flutter_tantivy): Uses `flutter_rust_bridge` for FFI between Dart and Rust. Builds for Android arm64/x86_64 and iOS arm64 via Cargo cross-compilation (`aarch64-linux-android`, `aarch64-apple-ios`).
- **full_search** (yiv/full_search): Another Flutter plugin wrapping Tantivy via FFI, supporting Android + iOS + desktop.
- **seshat** (matrix-org/seshat): The Matrix message indexer uses Tantivy and ships on desktop (JVM) with Node.js bindings; latest release May 2026. Does not target mobile, but the Rust library itself compiles to mobile targets.

No published JNI (Java/Kotlin-native) wrapper exists specifically. For KMP, you would write JNI wrappers (JVM/Android) and Kotlin/Native cinterop (iOS).

### BM25

Yes. Tantivy implements the same BM25F variant as Lucene ≥ 6.0 (`Bm25StatisticsProvider`). Scored via `TopDocs::with_limit().and_order_by_score()`.

### Highlight / Snippet extraction

Not built-in as a `highlight()` function equivalent to SQLite FTS5. Tantivy provides `SnippetGenerator` (in `tantivy::snippet`) that can extract context windows around matched terms, but does **not** wrap them in `<em>` tags by default — the caller formats the highlighting. This requires reading back stored document content.

SQLite FTS5's `highlight(table, col, '<em>', '</em>')` is directly equivalent to writing a snippet loop with `SnippetGenerator`. The LOC overhead is ~30–50 lines.

### Incremental updates

Yes. `IndexWriter` supports `add_document`, `delete_term`, and `commit`. However: data in Tantivy is immutable per segment — editing a document requires delete + re-add. The `LogMergePolicy` handles segment merging in the background.

### Binary size (Android arm64)

No published benchmark specific to Android. General Rust on Android: a stripped `.so` for a non-trivial library runs 3–8 MB. Tantivy depends on many crates (sstable, columnar, bitpacker, tantivy-stacker) so estimate **5–8 MB stripped** for `aarch64-linux-android`. Enabling LTO + `opt-level = "z"` can reduce this ~30%.

### Memory usage (100k documents)

Tantivy uses mmap by default on disk-backed indexes — RAM overhead is mostly OS page cache + in-memory structures for the current segment being written. A 100k-document text corpus would produce an on-disk index of roughly 20–100 MB (depends on average doc size and stored fields). Active memory during search is low (a few MB), since Tantivy mmaps segment files.

For 100k short markdown blocks (~50–200 words each), expect ~10–30 MB index on disk, <5 MB active memory during queries.

### Known mobile deployments

- flutter_tantivy: confirmed Android + iOS deployments (Flutter apps)
- seshat: desktop/Node.js only
- Element.io: listed as a Tantivy user (desktop/server, not confirmed mobile)

### Summary

Tantivy is the highest-quality option with the largest ecosystem, but has a hard WASM blocker. For the three native targets (JVM, Android, iOS) it is viable via Rust FFI.

---

## Stork

**GitHub**: https://github.com/jameslittle230/stork  
**License**: Apache-2.0  
**Stars**: 2.8k  
**Status**: **Abandoned** — maintainer posted a wind-down notice; last release v1.6.0 (January 12, 2023), no commits since

### Primary target

Stork was built specifically for static-site WASM search: index built offline → uploaded to CDN → loaded by `stork.js` WASM runtime in browser. It has a `stork-wasm` crate and ships `stork.wasm`.

### BM25

No. Stork uses TF·IDF ranking, not BM25. The maintainer never implemented BM25.

### Highlight / Snippet

Yes — highlight is a core feature (shows excerpt + matched term positions in the result UI).

### 100k document scale

Stork was designed for static documentation sites (hundreds to low thousands of documents). It prebuilds a binary `.st` index file at index time. Scaling to 100k documents is **untested** and likely produces very large index files (Stork stores all document text in the index). This is architecturally unsuitable for an outliner with live updates.

### Mobile / Android

No. Stork is WASM-only; no Android/iOS target.

### Verdict

Abandoned, wrong ranking model, not updatable at runtime, no mobile target. **Do not use.**

---

## Meilisearch `milli` (archived) / meilisearch search core (active)

**Archived repo**: https://github.com/meilisearch/milli (archived Apr 4, 2023)  
**Current location**: https://github.com/meilisearch/meilisearch (workspace `milli/`)  
**License**: MIT  
**Stars (meilisearch main)**: 50k+

### Standalone embedded use

The standalone `milli` repo is **archived**. The code was merged into the main `meilisearch` repository as a workspace crate. The `milli-core` crate on crates.io is a third-party fork ("created to provide only the core index and search functionality").

Using `milli` standalone today means either:
1. Depending on the full `meilisearch` monorepo (brings in HTTP server crate, actix-web, etc.)
2. Using the unmaintained `milli-core` fork

Neither is suitable for embedding in a mobile app.

### WASM / Android / iOS

No published WASM target. Milli uses **LMDB** (Lightning Memory-Mapped Database) via the `heed` crate — LMDB is a C library that requires native compilation. WASM is unsupported. Android/iOS compilation would require cross-compiling LMDB and `heed`, which is doable but has no known published example.

Binary size would be large (~10–20 MB unstripped) due to LMDB + the full ranking pipeline.

### Features

Milli/Meilisearch supports BM25-like ranking (configurable `ranking_rules`), typo tolerance, highlighting/cropping, and faceting. However, these features are tightly coupled to its own document format, LMDB schema, and indexer pipeline — they are not available as composable Rust functions.

### Verdict

Not viable as an embedded library for mobile/WASM. Use only if running Meilisearch as a sidecar server, which is incompatible with SteleKit's offline-first architecture.

---

## probly-search

**GitHub**: https://github.com/quantleaf/probly-search  
**License**: MIT  
**Stars**: 73  
**Latest release**: v2.0.1 (July 3, 2024)  
**crates.io**: https://crates.io/crates/probly-search

### Overview

A trie-based dynamic inverted index ported from the JavaScript NDX library. Pure Rust, no native dependencies.

### WASM support

**Yes** — explicitly documented. Has a live WASM demo (recipe search, 50k documents) at https://quantleaf.github.io/probly-search-demo/.

### Android / iOS

Yes. Pure Rust with no unsafe or platform-specific code visible. Should cross-compile to `aarch64-linux-android` and `aarch64-apple-ios` without changes.

### BM25

Yes. Implements Okapi BM25 (same as Lucene ≥ 6). Also offers "zero-to-one" normalized scoring and a pluggable `ScoreCalculator` trait.

### Highlight / Snippet

**No** built-in highlight function. Returns `QueryResult { key, score }` only. Highlight extraction would need to be built on top (get matching terms from the query, scan document content for positions).

### Incremental updates

Yes — `add_document`, `remove_document` (with lazy deletion; `vacuum()` to compact).

### 100k document scale

The demo is 50k documents. The library is in-memory (trie structure). At 100k blocks (~100 words each), the trie + postings would consume roughly 50–200 MB RAM depending on vocabulary size. This may be tight on low-end Android devices (512 MB RAM). No published benchmark at 100k scale.

### Concerns

- Low adoption (73 stars, 6 forks)
- Last release July 2024 — no activity since
- No snippet API
- In-memory only (no persistence — must rebuild index on every app launch)

### Verdict

Technically compatible with all four targets, but risky: small community, no persistence layer, no highlight, uncertain 100k performance.

---

## `bm25` crate (Michael-JB/bm25)

**GitHub**: https://github.com/Michael-JB/bm25  
**License**: MIT  
**Stars**: 65  
**Latest release**: v2.3.2 (Sep 7, 2025)  
**crates.io**: https://crates.io/crates/bm25  
**WASM demo**: https://michael-jb.github.io/bm25-demo (live)

### Overview

A sparse BM25 embedder + scorer + in-memory search engine. Three abstraction levels: raw embedding, scored document retrieval, or full search engine. Multilingual tokenizer with stemming, stop-word removal, and unicode normalization.

### WASM support

**Yes** — the crate has a live WebAssembly demo. Uses only safe Rust + standard-library features; compatible with `wasm32-unknown-unknown`.

### Android / iOS

Yes. Pure Rust, no native deps. Cross-compiles cleanly.

### BM25

Yes. Standard Okapi BM25 with configurable `k1`, `b`, `avgdl` parameters.

### Highlight / Snippet

**No**. The crate returns `SearchResult { document, score }` and `ScoredDocument { id, score }`. There is no term-offset tracking for highlight extraction. You would need to re-implement position indexing on top.

### Incremental updates

Partial. The `SearchEngine` supports `upsert` and `remove`. Caveat: mutating the corpus changes the true `avgdl`, which the crate documents as causing score drift. For SteleKit's continuous editing use case (frequent single-block updates), this drift is acceptable for 100k blocks where `avgdl` changes slowly.

### Parallelism feature

Optional `parallelism` feature uses Rayon for faster batch fitting. **Disable this for WASM** (Rayon uses threads). The crate is designed to work without it.

### 100k document scale

The crate description says "sub-millisecond performance on corpora under 100K documents" (per bm25_turbo; the Michael-JB crate is separate but similar in design). At 100k short blocks, in-memory footprint would be ~30–100 MB depending on vocabulary. Reasonable for desktop/server; borderline for low-end Android.

No persistence — index is in-memory and must be rebuilt on launch or serialized manually.

### Verdict

Small but well-designed, WASM-native, pure Rust. The missing feature is **highlight/snippet extraction**, which would need to be written (see Custom BM25 section).

---

## Other Crates Surveyed

### `bm25_turbo` / BM25-Turbo

**GitHub**: https://github.com/alessandrobenigni/BM25-Turbo-Rust-Python-WASM-CLI  
Precomputes BM25 scores into a compressed sparse column matrix (CSR format). Queries become sparse vector dot products — no scoring math at query time. Claims 2,300× faster than BM25S; 28k QPS on 8.8M docs.

- Has WASM bindings (npm package)
- Has Python bindings
- Designed for large corpora with static documents (batch indexing, not incremental updates)
- Memory-mapped persistence (mmap)
- Not suitable for real-time block editing (requires full rebuild or streaming re-index)

### `bm25x` (LightOn)

**GitHub**: https://github.com/lightonai/bm25x  
Streaming-friendly BM25 with mmap persistence. Lazy scoring (computes BM25 at query time). Supports incremental add/delete/update cheaply. No highlight. No WASM demo found. Likely compilable to mobile.

### `elasticlunr-rs`

A port of the JavaScript elasticlunr.js library. Uses TF-IDF (not BM25). Abandoned; last updated ~2019. Not recommended.

### `tinysearch`

WASM-first, generates a static WASM blob from a static corpus. No dynamic updates. Not suitable.

---

## Build-Your-Own Minimal BM25 Inverted Index

### Approach

A minimal BM25 inverted index in Rust that supports:
1. Tokenize text → `unicode-segmentation` crate (UAX #29 word boundaries)
2. Stem tokens → `rust-stemmers` crate (Snowball algorithms, 17 languages)
3. Build inverted index: `HashMap<String, Vec<(DocId, tf)>>`
4. Store `doc_length`, `total_docs`, `avgdl` for BM25 scoring
5. Query: intersect postings lists, compute BM25, rank top-k
6. Highlight: scan original text for query terms, extract byte offsets, annotate with `<em>…</em>`

### Realistic LOC estimate

| Component | LOC |
|---|---|
| Tokenizer wrapper (unicode-segmentation + rust-stemmers) | ~80 |
| Inverted index struct + add/remove/update | ~150 |
| BM25 scoring (standard Okapi formula) | ~60 |
| Top-k heap retrieval | ~40 |
| Highlight extraction (byte-offset scan + annotation) | ~120 |
| Serialization (serde + bincode for persistence) | ~60 |
| **Total** | **~510 LOC** |

With tests and error handling, expect **700–1 000 LOC**.

### Key crates

- `unicode-segmentation` (crates.io): UAX #29 word segmentation. Pure Rust, WASM-compatible, used by Tantivy itself.
- `rust-stemmers` (crates.io): Snowball stemmers for 17 languages. Pure Rust.
- `serde` + `bincode` or `postcard`: For index serialization/persistence.
- No native deps → compiles to all four targets.

### Viability for 100k documents

At 100k blocks × avg 100 tokens, with a vocabulary of ~100k unique stems:
- Inverted index: ~100k terms × avg 10 postings × 8 bytes = ~8 MB (minimal)
- Doc store (for highlight): depends on whether you store raw text. For 100k × 150 chars avg = ~15 MB
- Total in-memory: **~25–50 MB** — fine for JVM/desktop, acceptable for Android (mid-range device, 4+ GB RAM)

Query latency: intersecting two postings lists of ~5 000 docs each takes <1 ms. Highlight scan over one document <0.1 ms. Interactive search is viable.

### Missing features vs. SQLite FTS5

- No phrase queries (`"exact phrase"`)
- No proximity ranking
- No persistent WAL (you serialize the whole index on save)
- Stemmer doesn't handle every language FTS5 can

For SteleKit (English + CJK outliner notes), this is acceptable. Phrase support can be added with position posting lists (+~100 LOC).

---

## Recommendation

**If building a custom Rust storage layer, use Tantivy for FTS on JVM/Android/iOS, and build a minimal custom BM25 index for WASM.**

### Rationale

Tantivy is the only production-grade, actively maintained Rust FTS library that (a) implements correct BM25, (b) supports incremental updates, (c) has confirmed Android/iOS cross-compilation via flutter_tantivy, and (d) has a large enough ecosystem (15.4k stars, commercial backing from Quickwit/Datadog) to bet on for a core search feature. The 5–8 MB binary size overhead is acceptable for Android APKs.

The WASM blocker is real and will not be resolved quickly. For WASM, the pragmatic path is a custom minimal BM25 index (~700 LOC) using `unicode-segmentation` + `rust-stemmers`. This is the same approach Stork took (a thin Rust search layer compiled to WASM), but with BM25 instead of TF-IDF and with update support. The custom layer is small enough to build in 1–2 days and own permanently.

**Rejected alternatives:**
- Stork: abandoned, no BM25, no updates, WASM-only
- milli: not embeddable, requires LMDB, no WASM/mobile
- probly-search / bm25 crate: all-targets-compatible but no highlight, no persistence, low adoption — not worth depending on vs. writing 500 LOC yourself

**If the team decides not to build a custom storage layer and keep SQLite**, SQLite FTS5 already satisfies all four targets (SQLite compiles to WASM via sql.js/wa-sqlite, and to Android/iOS natively). This remains the lowest-risk option.

---

*Researched June 2026. Sources: GitHub repositories, crates.io, and web searches for tantivy-go, flutter_tantivy, seshat, probly-search, bm25, milli.*
