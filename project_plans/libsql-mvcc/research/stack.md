# Stack Research: libsql Rust crate + rules_rust + JNI

## 1. libsql Crate — Current Version and Features

**Current version**: `0.9.30` (released 2026-03-19, per crates.io / docs.rs).

The requirements document references "0.6.x" but the crate has moved significantly further. Use `0.9.x` (latest stable) — not `0.6`.

### Feature flags

The crate has three named feature flags. By default **all three are enabled**.

| Feature | Description |
|---|---|
| `core` | Includes the C code backing local embedded database usage and embedded replicas. **This is the flag to use for local-only mode.** |
| `replication` | Enables `core` plus HTTP code for syncing a remote database locally (embedded replica). |
| `remote` | HTTP-only; queries against a remote Turso/sqld server, no local C library. |

For a local-embedded JNI bridge, use:

```toml
libsql = { version = "0.9", default-features = false, features = ["core"] }
```

There is **no** `local` or `bundled` feature name. The `core` feature is the correct one for embedded mode.

### `params_from_iter`

The libsql crate has its own `params` module with utilities for heterogeneous parameter sets (named and positional params). However, **`libsql::params_from_iter` does not exist as a free function** the way `rusqlite::params_from_iter` does. The libsql API uses the `params!` macro and the `IntoParams` trait for parameter binding. There is a known compatibility gap with rusqlite's parameter API (tracked in tursodatabase/libsql issue #278). For a JNI bridge, parameters will likely be passed as `Vec<libsql::Value>`, which implements `IntoParams` via `Vec<Value>`.

---

## 2. `Connection` — Send, Sync, and Method Signatures

### Send / Sync

`libsql::Connection` **does implement `Send`** (and `Sync`) for the local embedded backend. The implementation is backed by SQLite's `SERIALIZED` threadsafe mode, which allows moving connections between threads. The docs explicitly note:

> "We implement Send/Sync for all the types to allow them to move between threads safely. libsql tries to use the SERIALIZED threadsafe mode for sqlite3 by default."

Caveat: if another library in the same process has initialized SQLite with a different mode, this can cause undefined behavior. For a single-process JNI bridge this is generally safe.

### Query method signatures

All query/execute methods take `&self` (shared reference), not `&mut self`. The key signatures are approximately:

```rust
impl Connection {
    pub async fn query(
        &self,
        sql: &str,
        params: impl IntoParams,
    ) -> Result<Rows>;

    pub async fn execute(
        &self,
        sql: &str,
        params: impl IntoParams,
    ) -> Result<u64>;

    pub async fn execute_batch(&self, sql: &str) -> Result<()>;

    pub async fn transaction(&self) -> Result<Transaction>;
}
```

The `&self` signature means `Connection` can be shared across tasks without `Arc<Mutex<_>>` wrapping, though the internal SQLite serialization still serializes concurrent calls.

### Tokio async requirement

All `Connection` methods are `async` and **require a Tokio runtime**. For a JNI bridge (Java threads are not Tokio tasks), the correct pattern is:

```rust
let rt = tokio::runtime::Builder::new_multi_thread()
    .worker_threads(2)
    .enable_all()
    .build()?;
// Per JNI call:
let result = rt.block_on(async { conn.query(sql, params).await });
```

This is confirmed as the standard pattern for bridging sync callers into async libsql.

---

## 3. rules_rust — Correct Version for Bazel 9.1.1

**Recommended version**: `0.68.0` through `0.70.0` — all are available on the Bazel Central Registry and support Bazel 9. Version `0.70.0` is the most recent (released ~May 2026). Use `0.68.0` if you want a slightly older but well-tested release; use `0.70.0` for the latest.

The Bazel Central Registry lists: 0.57.0, 0.57.1, 0.60.0, 0.65.0, 0.68.0, 0.68.1, 0.69.0, 0.70.0.

rules_rust bzlmod support is officially described as "still a work in progress" — most features work, but bugs are more likely than with WORKSPACE. Plan for some troubleshooting.

### Minimal working MODULE.bazel

```python
module(name = "stelekit", version = "0.0.0")

bazel_dep(name = "rules_rust", version = "0.70.0")

# Register a Rust toolchain
rust = use_extension("@rules_rust//rust:extensions.bzl", "rust")
rust.toolchain(
    edition = "2021",
    versions = ["1.85.0"],
)
register_toolchains("@rust_toolchains//:all")

# Ingest Cargo.toml / Cargo.lock
crate = use_extension("@rules_rust//crate_universe:extensions.bzl", "crate")
crate.from_cargo(
    name = "crates",
    cargo_lockfile = "//:Cargo.lock",
    manifests = ["//:Cargo.toml"],
)
use_repo(crate, "crates")
```

Key notes:
- Bzlmod requires **explicit** `register_toolchains` — it is not done automatically.
- `crate.from_cargo` (not `crates_repository`) is the bzlmod-native extension method.
- A generated `Cargo.lock` is required for `crate.from_cargo` to be deterministic.
- `use_repo(crate, "crates")` makes `@crates//...` targets available.

---

## 4. jni Crate — Version Recommendation

The `jni = "0.21"` pinned in the requirements is **outdated**. Current status:

| Version | Release date | Notes |
|---|---|---|
| 0.21.1 | 2023-03-15 | Outdated |
| 0.22.0 | 2026-02-17 | Breaking changes vs 0.21 |
| 0.22.4 | 2026-03-16 | Current latest |

**Recommendation**: Use `jni = "0.22"` (0.22.4 as of research date). A migration guide from 0.21 → 0.22 exists in the jni-rs repository. The breaking changes are relatively minor (primarily around error types and some method renames).

### Alternatives considered

- **jni-simple**: A minimal, low-magic alternative to jni-rs. Less ergonomic but zero overhead. Not recommended for this project — jni-rs's ergonomics are worth it for a complex bridge.
- **JNR-FFI / Project Panama**: Java-side solutions (no Rust side needed), not applicable here since the requirement is a Rust cdylib.
- **j4rs**: Higher-level Java↔Rust bridge, not suitable for Android + JVM dual-target.

**Use `jni = "0.22"`** for both JVM and Android targets.

---

## 5. Rust Toolchain MSRV for libsql

The libsql crate's Cargo.toml does not declare a public `rust-version` field that surfaced in documentation searches. However, the toolchain version `1.85.0` appears in rules_rust examples specifically targeting libsql-era projects, and the libsql CI infrastructure references Rust 1.85 in GitHub Actions runs from 2025.

**Practical recommendation**: Use **Rust 1.85.0** as the pinned toolchain version. This is:
- Stable Rust from February 2025 (Rust 2024 edition stabilization release)
- Required for the Rust 2024 edition features that tokio and libsql's dependency tree now use
- The version seen in contemporary rules_rust MODULE.bazel examples

A version older than 1.75 will definitely fail (tokio requires ≥1.70; libsql's async machinery likely requires ≥1.75). 1.80+ is safe; 1.85+ is the safest choice given the crate's active development pace.

---

## 6. libsql `core` Feature — Confirmed

As detailed in section 1: **the feature name is `core`**, not `local` or `bundled`.

- `core` = embedded SQLite engine C code, local file-based databases, BEGIN CONCURRENT support
- `local` — does not exist in libsql
- `bundled` — does not exist in libsql (this term is from rusqlite/libsqlite3-sys)

The `libsql-sys` crate (the raw FFI bindings layer underneath) does have its own feature flags including bundled SQLite, but users of the higher-level `libsql` crate only need to specify `features = ["core"]`.

---

## Summary of Key Decisions

| Question | Answer |
|---|---|
| libsql crate version | `0.9.x` (0.9.30 current); NOT 0.6.x |
| Feature for local embedded | `features = ["core"]`, `default-features = false` |
| `params_from_iter` in libsql? | Does not exist; use `params!` macro or `Vec<libsql::Value>` |
| `Connection::query` signature | `async fn query(&self, sql: &str, params: impl IntoParams) -> Result<Rows>` |
| `Connection` implements `Send`? | Yes (via SQLite SERIALIZED mode) |
| rules_rust version | `0.70.0` (or `0.68.0` for conservatism) |
| Rust toolchain version | `1.85.0` |
| jni crate version | `0.22.4` (NOT 0.21; migration guide available) |
| `core` feature exists? | Yes — correct name for local embedded mode |

---

## Sources

- [libsql on crates.io](https://crates.io/crates/libsql)
- [libsql 0.9.30 docs.rs](https://docs.rs/crate/libsql/latest)
- [Connection in libsql - docs.rs](https://docs.rs/libsql/latest/libsql/struct.Connection.html)
- [Turso SDK Rust Reference](https://docs.turso.tech/sdk/rust/reference)
- [tursodatabase/libsql GitHub](https://github.com/tursodatabase/libsql)
- [libsql issue #278 — params incompatibility](https://github.com/tursodatabase/libsql/issues/278)
- [rules_rust on Bazel Central Registry](https://registry.bazel.build/modules/rules_rust)
- [rules_rust crate_universe bzlmod docs](https://bazelbuild.github.io/rules_rust/crate_universe_bzlmod.html)
- [rules_rust GitHub releases](https://github.com/bazelbuild/rules_rust/releases)
- [jni crate on crates.io](https://crates.io/crates/jni)
- [jni 0.22.4 docs.rs](https://docs.rs/crate/jni/latest)
- [jni-rs GitHub](https://github.com/jni-rs/jni-rs)
- [Tokio bridging sync/async](https://tokio.rs/tokio/topics/bridging)
