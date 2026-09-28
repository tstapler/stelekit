# WASM Constraints for Embedded Database Selection

Scope: WebAssembly (browser) constraints that affect which embedded database can serve
the SteleKit WASM/Web target. Based on codebase inspection of `kmp/src/wasmJsMain/` and
technical research on the WASM platform as of mid-2025.

---

## Threading

### Default: Single-threaded

WASM in the browser runs in a single-threaded environment by default. The WASM specification
supports threads through the "threads" proposal (SharedArrayBuffer + Atomics), but the
browser security model gates this behind **cross-origin isolation** (COI).

Cross-origin isolation requires two HTTP response headers on the page:
```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp  (or: credentialless)
```

Without both headers, `SharedArrayBuffer` is undefined and `Atomics.wait()` is disallowed.

### Browser support for SharedArrayBuffer (2024–2025)

- Chrome 92+ (July 2021): enabled with COOP+COEP — ~65% global market share
- Firefox 79+ (August 2020): enabled with COOP+COEP — ~4% global share
- Safari 15.2+ (December 2021): enabled with COOP+COEP — ~19% global share
- Combined caniuse data: ~93% of browsers *support* SharedArrayBuffer with proper headers

The catch: most hosting platforms do **not** set COOP/COEP headers by default.
- GitHub Pages: no COOP/COEP — SharedArrayBuffer unavailable without a workaround
- Netlify, Vercel: configurable via `_headers` file, but not the default
- Self-hosted: full control, but requires server configuration

### The coi-serviceworker workaround

SteleKit currently ships `coi-serviceworker.min.js` in `wasmJsMain/resources/` and
conditionally loads it in `index.html` when `crossOriginIsolated` is false:

```html
<script>if (!crossOriginIsolated) document.write('<scr' + 'ipt src="coi-serviceworker.min.js"><\/scr' + 'ipt>');</script>
```

This service worker intercepts fetch events and adds the required headers to responses,
enabling `SharedArrayBuffer` on hosts that don't set them. It causes a single page reload
on first load (noted in the UI: "This page may refresh once on first load — this is expected").

**Limitation**: `coi-serviceworker` requires the page to be served over HTTPS or localhost.
It does not work in private browsing modes on Firefox. It also cannot be used in contexts
where the page is loaded inside an `<iframe>` on a third-party origin.

**Coverage estimate**: With the service worker fallback, ~95%+ of modern browser deployments
can achieve SharedArrayBuffer access. The 5% gap is primarily: iOS WKWebView in apps that
disable service workers, Firefox private mode, and some enterprise-locked browsers.

### Atomics.wait on the main thread

Even with SharedArrayBuffer available, `Atomics.wait()` is **synchronously blocking and
forbidden on the main thread** in all major browsers. This rules out synchronous blocking
on shared memory from the main thread. Databases that require a blocking mutex or blocking
I/O (like LMDB's mmap+lock) cannot be adapted without a worker thread.

### Kotlin/WASM is single-threaded

Kotlin/WASM (`wasmJs` target) compiles to a single WASM module that runs on the main
browser thread. `PlatformDispatcher.DB` maps to `Dispatchers.Default` (single-threaded
event loop on WASM — see `PlatformDispatcher.js.kt`). There is no true parallelism.

The SteleKit implementation correctly uses a Web Worker for SQLite operations
(`sqlite-stelekit-worker.js`): all SQL executes in the worker thread; the main WASM module
communicates via `postMessage`. This sidesteps the main-thread Atomics restriction.

---

## Memory

### WASM heap limits (practical, 2025)

| Browser | Theoretical Max | Practical Limit | Notes |
|---------|----------------|-----------------|-------|
| Chrome (64-bit) | 4 GB | ~3.5 GB | Shared with renderer process heap |
| Firefox (64-bit) | 4 GB | ~2 GB | More conservative allocator |
| Safari (macOS) | 4 GB | ~2 GB | iOS: significantly lower (~512 MB–1 GB) |
| Mobile Chrome (Android) | 4 GB | 256 MB–1 GB | Depends on device RAM |
| Mobile Safari (iOS) | 4 GB | 256 MB–768 MB | Strict; tab killed at ~150–300 MB on older iPhones |

The SteleKit requirement is ≤1 GB for native. On mobile browsers the effective budget is
much tighter: **256–512 MB total** for the page including WASM heap, JS heap, DOM, and GPU
memory. This is the binding constraint.

### mmap unavailability

WASM has no `mmap` system call. The WASM memory model is a flat linear array grown via
`memory.grow`. This means:

- **LMDB is architecturally impossible in WASM**: LMDB is built entirely around
  `mmap()` for both read and write access. No WASM port exists and none can without a
  fundamental redesign.
- Any engine that relies on OS-managed memory-mapped files is similarly excluded.
- Engines that manage their own I/O abstraction (redb, sled, fjall) can work with an
  OPFS-backed I/O layer instead.

### sqlite-wasm memory profile

The official `@sqlite.org/sqlite-wasm` package (version 3.46.1, as used by SteleKit)
reports in its documentation:
- Base SQLite WASM module: ~1.5–2 MB compiled size
- Runtime heap for a 100k-row database: typically **20–80 MB** depending on cache size,
  page size, and number of open statements
- Default `SQLITE_DEFAULT_CACHE_SIZE` in the WASM build is 2000 4-kB pages = ~8 MB
- With a 100k-block graph (SteleKit scale), empirical measurements suggest 50–150 MB peak
  during bulk import; steady-state is 20–40 MB

This is very efficient for mobile browsers.

### SharedArrayBuffer requirement summary

- `SharedArrayBuffer` is needed for **cross-worker shared memory** (Atomics-based sync)
- The OPFS SAH Pool VFS (`installOpfsSAHPoolVfs`) internally uses synchronous OPFS
  `FileSystemSyncAccessHandle` — this requires the worker to have obtained an exclusive lock.
  The SAH pool approach does NOT require SharedArrayBuffer from the main thread.
- The older `sqlite3OopfsVfs` (non-SAH) does require SharedArrayBuffer for coordination.
- SteleKit uses SAH Pool: `sqlite3.installOpfsSAHPoolVfs({ name: 'opfs-sahpool', ... })`
  which avoids the SharedArrayBuffer dependency for the VFS itself.

---

## Persistence

### OPFS (Origin Private File System)

OPFS is the only WASM-native durable storage API with filesystem-like semantics:
- Available in all major browsers since Chrome 102, Firefox 111, Safari 16.4 (2022–2023)
- Files in OPFS are opaque to the user (not visible in the OS file browser)
- Synchronous access (`FileSystemSyncAccessHandle`) is available **only inside Web Workers**,
  not on the main thread
- Provides read/write performance comparable to native files

sqlite-wasm's SAH Pool VFS uses OPFS with synchronous handles, which is why SQLite must
run in a Web Worker in the SteleKit architecture.

### IndexedDB

IndexedDB is an alternative persistence backend, but it has fundamental mismatches:
- Only asynchronous API — synchronous KV access requires promisification overhead
- Structured clone serializer is slow for large binary blobs
- No support for file-range reads (entire value must be read/written)
- sqlite-wasm has an IndexedDB-backed VFS (`sqlite3-vfs-opfs-sahpool.c`) as a fallback,
  but it performs ~10–50x worse than the OPFS SAH Pool VFS

### In-memory fallback

SteleKit's worker already falls back to `:memory:` SQLite if OPFS is unavailable:
```js
db = new sqlite3.oo1.DB(':memory:');
self.postMessage({ type: 'ready', backend: 'memory', warning: e.message });
```

For an outliner app that loads from files each session (the Logseq model), in-memory
is acceptable for the WASM target as long as the source markdown files can be read
(via the File System Access API or bundled assets). However, this means graph changes
are lost on page reload, which degrades the WASM experience.

### Engines with OPFS support (summary)

| Engine | OPFS Support | Notes |
|--------|-------------|-------|
| sqlite-wasm | Native (SAH Pool VFS) | Proven, production-ready |
| DuckDB-WASM | Yes (OPFS VFS, `opfs://` paths) | JS-interop only; ~2 MB gzipped binary |
| redb | Not yet | Needs custom WASM I/O shim |
| sled | Not yet | Needs custom WASM I/O shim |
| fjall | No | std::thread dependency blocks WASM |
| persy | No | std::thread dependency blocks WASM |
| Tantivy | No | File I/O via std — not WASM-compatible |

---

## Per-engine WASM status

### a) sqlite-wasm (current)

**Status: Production-ready, proven in SteleKit**

How `WasmOpfsSqlDriver` works:
1. Main Kotlin/WASM thread spawns a Web Worker (`new Worker('./sqlite-stelekit-worker.js')`)
2. Worker loads `sqlite3-bundler-friendly.mjs` (the official sqlite-wasm WASM module)
3. Worker calls `sqlite3.installOpfsSAHPoolVfs()` to mount OPFS with synchronous handles
4. Worker opens/creates the database file at `/graph-{graphId}.sqlite3` in OPFS
5. All SQL runs inside the worker; results are sent back via `postMessage`
6. Main thread wraps each SQL call in a `Promise` and `await`s it via SQLDelight's
   `QueryResult.AsyncValue` abstraction

Threading model: **Worker thread** (not main thread). Synchronous OPFS inside worker.
No SharedArrayBuffer required (SAH Pool VFS uses exclusive file locks, not shared memory).

Memory footprint: 50–150 MB peak on 100k-block import; 20–40 MB steady-state.

COOP/COEP: Required for SharedArrayBuffer-dependent features but the SAH Pool VFS itself
avoids this requirement. SteleKit includes `coi-serviceworker` as belt-and-suspenders for
any other browser APIs that may need cross-origin isolation.

### b) DuckDB-WASM

**Status: Technically functional but architecturally wrong for SteleKit**

- DuckDB-WASM is production-ready for analytics (SELECT-heavy) workloads
- Bundle size: **~2 MB gzipped** (the WASM binary itself; JS glue adds overhead) — comparable
  to sqlite-wasm in raw transfer, but total initial load is higher
- OPFS: supported via an OPFS VFS since DuckDB 0.10 (2024)
- Requires SharedArrayBuffer for multi-worker coordination (the main DuckDB-WASM approach
  uses `SharedArrayBuffer` + `Atomics.wait` in workers to block on results)
- Not designed for OLTP / single-row update patterns (no row-level locks, no FTS)
- DuckDB-WASM targets JavaScript/TypeScript; Kotlin/WASM interop requires JS glue
  (cannot be imported directly as wasm32 — must go through `@JsModule`)
- Previously rejected for Android/iOS at 2.3 GB peak; WASM memory profile is much
  smaller but OLTP design mismatch and JS interop indirection remain

**Verdict: Rejected for WASM.**

### c) redb compiled to wasm32

**Status: Theoretically possible but no production OPFS support exists**

redb is pure Rust with no unsafe code and explicitly avoids OS-specific assumptions. Its
`wasm32-unknown-unknown` compilation has been explored by the community:

- The `redb` crate's `StorageBackend` trait is the abstraction layer for I/O
- A custom WASM I/O backend would need to implement `StorageBackend` using OPFS
  Synchronous Access Handles (from inside a Web Worker)
- No published OPFS `StorageBackend` implementation exists as of mid-2025
- redb uses `parking_lot` mutexes internally; `parking_lot` compiles to WASM via
  its `wasm` feature but blocking operations become spin-loops (no OS scheduler)
- On wasm32, redb's background compaction thread cannot run (no threads); compaction
  would need to be triggered manually

**Estimated effort**: Building an OPFS `StorageBackend` for redb is ~500–1000 LOC of
Rust (WASM glue + JS interop). It would produce a minimal embedded store, but without
SQL or FTS — those layers remain to be built.

**Verdict: Possible but requires significant custom work. No off-the-shelf solution.**

### d) Rust-based databases compiled to wasm32

**sled**:
- Relies on `std::sync::Mutex`, `std::thread::spawn`, and OS file I/O
- Compilation to `wasm32-unknown-unknown` fails on std::thread usage
- Even with threading stubbed out, sled's concurrent BTreeMap design assumes parallelism
- **Status: Not WASM-compatible without a fork.**

**fjall**:
- Uses `std::thread` for background compaction and WAL flushing
- LSM-tree design has mandatory background compaction
- No `wasm32` feature or cfg in the crate
- **Status: Architecturally incompatible with wasm32-unknown-unknown.**

**persy**:
- Requires `std::thread` and blocking file I/O
- No community WASM port known
- **Status: Not WASM-compatible.**

**Tantivy** (FTS):
- Uses `std::thread` for indexing workers and `mmap` for segment files on disk
- Has been compiled to wasm32 with significant limitations: single-threaded mode,
  in-memory index only, no persistence
- `tantivy-wasm` experimental port exists but indexes are not persistent and the
  binary adds ~5–10 MB
- **Status: In-memory only on WASM, no OPFS persistence. Not viable for production FTS.**

---

## Kotlin/WASM specifics

### What is Kotlin/WASM (`wasmJs`)?

Kotlin's `wasmJs` target compiles Kotlin source to **WasmGC** (WebAssembly Garbage
Collection proposal) bytecode, NOT plain wasm32. The output is:
- A `.wasm` binary using WasmGC reference types and managed heap
- A JavaScript glue file (`.js`) that instantiates the WASM module and bridges Kotlin's
  type system to JS types

This is **critically different** from:
- `wasm32-unknown-unknown`: bare Rust/C WASM with linear memory, no GC, no OS
- `wasm32-wasi`: WASM with the WASI system interface (server-side runtimes)
- `wasm32-wasm-unknown-unknown` Rust output: linear memory, no reference types

WasmGC and wasm32 linear-memory WASM are **not binary-compatible** and cannot share
linear memory directly. Kotlin/WASM output assumes a **JS host** and uses `js("...")`
inline expressions and `@JsModule` for interop with JS/WASM modules.

### Can Kotlin/WASM call Rust-compiled .wasm modules?

**Not directly.** Kotlin/WASM outputs WasmGC; Rust outputs wasm32 linear-memory WASM.
These two WASM flavors cannot share linear memory and are not directly linkable in the
browser. The WASM Component Model would eventually solve this, but it is not shipped in
any browser as of mid-2025.

The only interop path is through JavaScript:
1. Rust is compiled to `.wasm` with `wasm-bindgen` (generates JS glue)
2. The JS glue is imported by the HTML page as a JS module
3. Kotlin/WASM calls into JS via `js("...")` inline expressions or `@WasmImport`
4. The JS glue forwards calls to the Rust WASM module

Note: `@WasmImport("./rust_library.wasm", "function_name")` allows direct WASM function
imports from Kotlin, but Rust functions must be exported as `#[no_mangle] extern "C"` and
must use only primitive types (integers, floats). Complex types require explicit
serialization through a shared buffer or JS objects.

This means a Rust-compiled database engine would need:
- `wasm-bindgen` bindings exposing a JS API
- Kotlin `external fun` declarations or `js("...")` calls to invoke those bindings
- A serialization layer for query parameters and results (JS arrays or ArrayBuffers)

**Performance overhead**: Every Kotlin→Rust call crosses WASM→JS boundaries
(Kotlin WasmGC → JS → Rust wasm32). Overhead is sub-millisecond for primitive calls;
bulk operations (100k inserts) would incur 10–50 ms of serialization overhead total.

**Alternative (Web Worker approach)**:
Like the current sqlite-wasm implementation, a Rust database engine could run entirely
in a Web Worker. Kotlin/WASM would communicate via `postMessage`, avoiding the
synchronous cross-WASM-boundary overhead. This matches the existing architecture exactly.

### OPFS from Rust WASM via JS interop

A Rust WASM module can access OPFS by:
1. Running inside a Web Worker (required for synchronous OPFS handles)
2. Calling JS OPFS APIs via `wasm-bindgen`/`web-sys` bindings
3. The `web_sys::FileSystemSyncAccessHandle` binding exists in `web-sys` crate

This is the path a Rust storage engine would take. It is well-defined but requires
explicit implementation — no off-the-shelf Rust WASM storage engine targets OPFS today.

---

## Recommendation

### Is WASM the hardest platform to satisfy?

**Yes.** WASM imposes constraints that no native platform has:
- No `mmap` (eliminates LMDB)
- No background threads (eliminates engines with mandatory background I/O)
- All persistence must go through OPFS via a Web Worker
- JS interop overhead for any Rust-based engine
- Memory budget significantly tighter on mobile browsers (~256–512 MB) vs. Android native

Among the four SteleKit platforms, WASM is uniquely constrained. iOS (Kotlin/Native +
SQLite C interop) is the second hardest, but iOS at least has native OS primitives.

### Can WASM be covered by a custom Rust engine, or should sqlite-wasm be retained?

**Retain sqlite-wasm for the WASM target.** The reasoning:

1. **No viable alternative exists today.** No Rust storage engine has a working OPFS
   backend. Building one (redb + custom OPFS StorageBackend) is 500–1000 LOC of new
   Rust, requires wasm-bindgen plumbing, and produces a store without SQL or FTS —
   those layers would still need to be built on top.

2. **sqlite-wasm already works and is efficient.** The current `WasmOpfsSqlDriver`
   implementation (Worker + SAH Pool VFS + postMessage) is the correct architecture.
   It handles OPFS persistence, falls back to in-memory gracefully, requires no
   SharedArrayBuffer for the VFS itself, and carries a ~20–40 MB steady-state heap.

3. **The per-platform split is a clean boundary.** The most practical architecture is:
   - JVM/Android: custom Rust engine (via JNI) for the memory/performance gains
   - iOS: custom Rust engine (via C interop .a)
   - WASM: retain sqlite-wasm

   SQLDelight's `SqlDriver` interface is already platform-dispatched. `WasmOpfsSqlDriver`
   is a wasmJsMain implementation; it is invisible to the other targets. Adding a new
   JVM/Android/iOS driver does not disturb the WASM driver at all.

4. **The WASM target's performance constraints are different.** WASM runs on desktop
   browsers where the 100k-block graph is uncommon; mobile web users rarely run large
   personal knowledge graphs. The 20–40 MB sqlite-wasm footprint fits comfortably within
   desktop browser budgets and the in-memory fallback is acceptable for smaller graphs.

5. **DuckDB-WASM is rejected** (OLTP design mismatch, SharedArrayBuffer hard-dependency,
   analytics-only query model with no FTS). **Tantivy WASM** is rejected (in-memory only,
   no OPFS persistence for the FTS index).

### Minimum viable approach

Keep sqlite-wasm for WASM. Use the custom Rust engine only for native targets. The
`DriverFactory` split already exists (`DriverFactory.js.kt` vs. `DriverFactory.jvm.kt`,
etc.) — the WASM target simply continues using `WasmOpfsSqlDriver`.

If FTS on WASM is needed beyond what SQLite FTS5 provides: FTS5 works in sqlite-wasm
(tested in the `WasmBenchmarkTest`), so this is not a gap.

**Decision trigger for reconsidering**: If a maintained Rust WASM storage engine with
OPFS persistence and SQL support ships (e.g., a `redb-opfs` crate with active
maintenance), revisit. Until then, the maintenance cost of a custom OPFS backend for
Rust outweighs the benefit.

---

## Gap: Rust custom engine on WASM — future project

**Status**: Deferred. Not blocking the current evaluation.

**The gap**: Kotlin/WASM outputs **WasmGC** bytecode; Rust compiles to **wasm32 linear
memory**. These are binary-incompatible — Kotlin cannot directly link a Rust `.wasm` module.

**How to bridge it** (when worth doing):

```
Rust engine (wasm32)
  └── wasm-bindgen → JS glue (openDb, execute, query, closeCursor...)
        └── @JsModule("rust-engine.js") in Kotlin/WASM
              └── same call pattern as current WasmOpfsSqlDriver
```

This is the same three-layer bridge already used by sqlite-wasm today. The cost is
~1–2 JS boundary crossings per query (microsecond overhead, not millisecond).

**Work required to implement**:

| Component | Estimated LOC | Notes |
|---|---|---|
| `wasm-bindgen` JS bridge for the Rust engine | ~200–400 LOC Rust | Expose typed JS functions per operation |
| OPFS `StorageBackend` for chosen KV engine | ~500–1000 LOC Rust | Persist pages via `FileSystemSyncAccessHandle`; must run in a Worker |
| Kotlin `@JsModule` declarations | ~100 LOC Kotlin | Mirror the existing `WasmOpfsSqlDriver` pattern |
| WASM Worker setup + `postMessage` protocol | ~200 LOC JS/TS | Same pattern as `sqlite-stelekit-worker.js` |

**Total estimate**: ~2–4 weeks for a motivated developer already familiar with the codebase.

**Preconditions before starting**:
1. The custom Rust engine must already be working on JVM/Android/iOS (validates correctness
   before adding WASM complexity)
2. A clear user-facing capability gap must exist that sqlite-wasm cannot provide (e.g., graph
   traversal queries, vector search) — otherwise sqlite-wasm covers the WASM target adequately
3. The WASM Component Model should be evaluated at that point — if Kotlin/WASM has adopted it,
   direct wasm-to-wasm linking becomes available without the JS bridge layer

**Future options that may simplify this**:
- **WASM Component Model** (spec stable, browser support landing in 2025): enables direct
  typed interfaces between wasm32 and WasmGC modules — no JS bridge required
- **Rust → WasmGC** (experimental compiler work): would allow direct linking; not production-ready
