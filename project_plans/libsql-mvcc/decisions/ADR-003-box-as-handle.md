# ADR-003: Box-as-handle pattern for Rust-to-JVM object lifetime

**Status**: Accepted
**Date**: 2026-06-17

## Context

The JNI bridge must expose long-lived Rust objects — `DbHandle` (database connection pool),
`ConnHandle` (individual connection), `StmtHandle` (prepared statement), `CursorHandle` (collected
result rows) — to JVM callers. The JVM side treats them as opaque `Long` values and passes them back
on every subsequent call. Several strategies exist for implementing this handle model:

- **Global `HashMap<jlong, Box<T>>`**: A static registry maps integer IDs to heap-allocated objects.
  Requires a `Mutex` or `RwLock` for thread safety; a global lock creates contention; the map grows
  unboundedly if callers forget to close handles.
- **`Arc<Mutex<T>>`**: Objects are reference-counted and shared. Requires `T: Send + Sync`; adds
  atomics and lock overhead on every access; the `Arc` itself must still be stored somewhere (back to
  the HashMap problem) or leaked.
- **`Box<T>` → raw pointer → `jlong`**: The object is heap-allocated, ownership is transferred to the
  JVM side via a raw pointer cast to `i64`/`jlong`. The JVM stores the integer and passes it back;
  each JNI call casts it back to `*mut T` and re-boxes temporarily to call methods.
- **Thread-local storage**: Not viable; JNI calls arrive on arbitrary Java threads.

## Decision

Use the **`Box<T>` → `Box::into_raw()` → `jlong` cast** pattern for all handle types:

```rust
// Create: transfer ownership to JVM
let handle = Box::new(DbHandle { ... });
let ptr: *mut DbHandle = Box::into_raw(handle);
return ptr as jlong;

// Use: borrow without taking ownership
let handle = unsafe { &mut *(ptr as *mut DbHandle) };
handle.do_something();

// Close: reclaim ownership and drop
let handle = unsafe { Box::from_raw(ptr as *mut DbHandle) };
drop(handle);
```

This pattern is applied to `DbHandle`, `ConnHandle`, `StmtHandle`, and `CursorHandle`.

## Rationale

- **No global state**: Eliminates the `HashMap` + `Mutex` entirely. Each handle is self-contained.
- **No `Send`/`Sync` constraints beyond what libsql already provides**: The raw-pointer approach does
  not require wrapping types in `Arc`. The JVM is responsible for not passing the same handle to
  concurrent JNI calls (the driver's Kotlin pool layer enforces this).
- **Zero synchronization overhead on the hot path**: Re-boxing a raw pointer is a single pointer cast;
  there is no atomic increment, no lock acquisition, and no HashMap lookup.
- **Idiomatic for C-compatible ABI**: This is the standard pattern used by SQLite's own C API
  (`sqlite3*`, `sqlite3_stmt*`) and by virtually every C-ABI Rust library. JNI consumers expect
  opaque integer handles.
- **Deterministic lifetime**: The object lives exactly as long as the JVM holds the `jlong`. There is
  no garbage-collection interference; the Rust allocator controls the memory.

## Consequences

- **The JVM caller owns the lifetime**: Every `openDb` / `openConn` / `prepareStmt` / `openCursor`
  JNI call that returns a `jlong` handle must be paired with the corresponding `closeDb` /
  `closeConn` / `finalizeStmt` / `closeCursor` call. Kotlin `use`-style wrappers or `AutoCloseable`
  implementations in the driver layer are the canonical enforcement mechanism.
- **No double-free protection**: Calling `close*` twice on the same handle is undefined behavior
  (use-after-free). The driver's Kotlin code must null the stored `jlong` or use a flag after closing.
  There is no Rust-side guard — adding one would require reintroducing global state.
- **No use-after-free detection in release builds**: If the JVM discards a handle without closing it
  (e.g., due to an unhandled exception before the `finally` block), the memory leaks silently. Debug
  builds can add a `Drop` impl that panics if the raw-pointer version of the handle is never reclaimed,
  but this cannot be enforced at compile time.
- **`unsafe` is unavoidable and localized**: All pointer casts must be wrapped in `unsafe` blocks in
  `lib.rs`. The unsafety is confined to the JNI boundary layer; internal Rust types remain safe.
- **Handles are not `Send` across threads from the JVM side**: Passing a `ConnHandle` `jlong` from one
  Java thread to another without synchronization is undefined behavior. The Kotlin connection-pool layer
  must ensure each handle is used from a single thread at a time (or that the underlying type is
  internally synchronized).
