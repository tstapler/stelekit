//! JNI bridge exposing libsql's local embedded database to the JVM / Android.
//!
//! # Handle model
//! Every object (Database, Connection, Statement, Cursor) is allocated via `Box::into_raw`
//! and returned to Kotlin as an opaque `jlong` handle.  Kotlin is responsible for:
//!   - never using a handle after freeing it
//!   - never sharing a handle across concurrent callers (pool in JvmLibsqlDriver enforces this)
//!   - calling the matching close/finalize function exactly once
//!
//! # MVCC / BEGIN CONCURRENT
//! libsql extends WAL with `BEGIN CONCURRENT`, which allows multiple write transactions to
//! proceed optimistically in parallel.  Transactions only conflict if they touch the same
//! underlying B-tree pages.  On conflict libsql returns SQLITE_BUSY_SNAPSHOT; the caller
//! (JvmLibsqlDriver) retries.  For SteleKit's data model (each page's blocks in disjoint
//! row ranges) actual conflicts are rare.
//!
//! # Async / Tokio
//! libsql's Rust API is fully async.  A single global multi-threaded Tokio runtime is shared
//! across all JNI calls; each call blocks the calling Java thread with `get_runtime().block_on()`.
//! Java threads are never Tokio tasks, so nested-runtime panics cannot occur.
#![allow(non_snake_case, clippy::missing_safety_doc)]

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use libsql::{Builder, Connection, Database, Value};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, OnceLock};
use tokio::runtime::Runtime;

// ---------------------------------------------------------------------------
// Global Tokio runtime
// ---------------------------------------------------------------------------

static RT: OnceLock<Arc<Runtime>> = OnceLock::new();

fn get_runtime() -> &'static Arc<Runtime> {
    RT.get_or_init(|| {
        Arc::new(
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(8)
                .enable_all()
                .build()
                .expect("failed to build Tokio runtime"),
        )
    })
}

// ---------------------------------------------------------------------------
// Panic safety helper
// ---------------------------------------------------------------------------

use std::panic::{self, AssertUnwindSafe};

fn with_env_catch<F, T>(env: &jni::JNIEnv, f: F, sentinel: T) -> T
where
    F: FnOnce() -> T + panic::UnwindSafe,
{
    match panic::catch_unwind(f) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "Rust panic in JNI call");
            sentinel
        }
    }
}

// ---------------------------------------------------------------------------
// Handle types — stored as Box<T>, leaked as *mut T cast to jlong
// ---------------------------------------------------------------------------

struct DbHandle {
    db: Database,
    mvcc_enabled: bool,
}

struct ConnHandle {
    conn: Connection,
    last_error: Option<String>,
    poisoned: AtomicBool,
}

/// Statement stores the SQL string and accumulated 1-based positional bindings.
/// Preparation happens lazily at execute/query time to avoid Rust lifetime
/// conflicts between Connection and Statement.
struct StmtHandle {
    sql: String,
    /// (1-based index, value) — matches SQLDelight's SqlPreparedStatement convention.
    bindings: Vec<(usize, Value)>,
}

/// Cursor holds all rows collected eagerly so the async Rows iterator lifetime
/// does not escape the JNI call boundary.
struct CursorHandle {
    rows: Vec<Vec<Value>>,
    /// Index of the NEXT row to yield (0 = before first row). After cursorNext()
    /// returns JNI_TRUE, the current row is rows[pos - 1].
    pos: usize,
    column_count: usize,
}

// ---------------------------------------------------------------------------
// Handle allocation helpers
// ---------------------------------------------------------------------------

fn alloc<T>(val: T) -> jlong {
    Box::into_raw(Box::new(val)) as jlong
}

/// # Safety
/// `handle` must have been produced by `alloc::<T>()` and must not have been freed.
/// No other reference to the same allocation may exist concurrently.
#[inline]
unsafe fn deref_mut<T>(handle: jlong) -> &'static mut T {
    &mut *(handle as *mut T)
}

/// # Safety
/// `handle` must have been produced by `alloc::<T>()` and must be freed exactly once.
#[inline]
unsafe fn free<T>(handle: jlong) {
    drop(Box::from_raw(handle as *mut T));
}

// ---------------------------------------------------------------------------
// Param builder — converts sparse 1-based bindings to a positional Vec
// ---------------------------------------------------------------------------

fn build_params(bindings: &[(usize, Value)]) -> Vec<Value> {
    if bindings.is_empty() {
        return Vec::new();
    }
    let max_idx = bindings.iter().map(|(i, _)| *i).max().unwrap_or(0);
    let mut params = vec![Value::Null; max_idx];
    for (idx, val) in bindings {
        if *idx >= 1 && *idx <= max_idx {
            params[*idx - 1] = val.clone();
        }
    }
    params
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_openDatabase<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        let path_str: String = match env.get_string(&path) {
            Ok(s) => s.into(),
            Err(e) => {
                let _ = env.throw_new("java/lang/RuntimeException", e.to_string());
                return -1i64 as jlong;
            }
        };
        let db = match get_runtime().block_on(Builder::new_local(&path_str).build()) {
            Ok(d) => d,
            Err(e) => {
                let _ = env.throw_new("java/lang/RuntimeException", e.to_string());
                return -1i64 as jlong;
            }
        };
        let mvcc_enabled = get_runtime().block_on(async {
            let setup_conn = db.connect().map_err(|e| e.to_string())?;
            setup_conn.execute("PRAGMA journal_mode='mvcc'", ()).await.map_err(|e| e.to_string())?;
            let mut rows = setup_conn.query("PRAGMA journal_mode", ()).await.map_err(|e| e.to_string())?;
            if let Some(row) = rows.next().await.map_err(|e| e.to_string())? {
                let mode: String = row.get(0).map_err(|e| e.to_string())?;
                Ok::<bool, String>(mode == "mvcc")
            } else {
                Ok(false)
            }
        }).unwrap_or_else(|e: String| {
            eprintln!("[libsql-jni] MVCC PRAGMA failed: {e} — falling back to WAL");
            false
        });

        if !mvcc_enabled {
            // Try WAL fallback
            get_runtime().block_on(async {
                if let Ok(fc) = db.connect() {
                    let _ = fc.execute("PRAGMA journal_mode=wal", ()).await;
                }
            });
        }

        alloc(DbHandle { db, mvcc_enabled })
    }), -1i64 as jlong)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_closeDatabase<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if handle > 0 {
            unsafe { free::<DbHandle>(handle) };
        }
    }), ())
}

// ---------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_openConnection<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    db_handle: jlong,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        // SAFETY: db_handle valid; Database::connect does not mutate the Database.
        let db = unsafe { deref_mut::<DbHandle>(db_handle) };
        match db.db.connect() {
            Ok(conn) => alloc(ConnHandle { conn, last_error: None, poisoned: AtomicBool::new(false) }),
            Err(e) => {
                let _ = env.throw_new("java/lang/RuntimeException", e.to_string());
                -1i64 as jlong
            }
        }
    }), -1i64 as jlong)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_closeConnection<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if handle > 0 {
            unsafe { free::<ConnHandle>(handle) };
        }
    }), ())
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_connectionChanges<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        unsafe { deref_mut::<ConnHandle>(handle) }.conn.changes() as jlong
    }), 0i64)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_connectionLastInsertRowId<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        unsafe { deref_mut::<ConnHandle>(handle) }.conn.last_insert_rowid()
    }), 0i64)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_connectionLastError<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    with_env_catch(&env, AssertUnwindSafe(|| {
        match unsafe { deref_mut::<ConnHandle>(handle) }.last_error.as_deref() {
            Some(msg) => env.new_string(msg).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    }), std::ptr::null_mut())
}

// ---------------------------------------------------------------------------
// Raw execute — no parameters (PRAGMA, BEGIN CONCURRENT, COMMIT, ROLLBACK, DDL)
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_executeRaw<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    conn_handle: jlong,
    sql: JString<'local>,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        let sql_str: String = match env.get_string(&sql) {
            Ok(s) => s.into(),
            Err(_) => return -1i64 as jlong,
        };
        // Use a raw pointer so the borrow checker doesn't block the error-store access below.
        let conn_ptr = conn_handle as *mut ConnHandle;
        let result = get_runtime().block_on(async {
            // SAFETY: conn_ptr valid and exclusively owned for this JNI call.
            let conn = unsafe { &(*conn_ptr).conn };
            conn.execute(&sql_str, ()).await
        });
        match result {
            Ok(n) => n as jlong,
            Err(e) => {
                // SAFETY: block_on completed; no other reference to conn_ptr exists.
                unsafe { (*conn_ptr).last_error = Some(e.to_string()) };
                -1i64 as jlong
            }
        }
    }), -1i64 as jlong)
}

// ---------------------------------------------------------------------------
// Statement
// ---------------------------------------------------------------------------

/// Allocates a statement handle.  No round-trip to libsql here; preparation is
/// deferred to executeStatement / queryStatement to avoid Rust lifetime conflicts
/// between Connection and Statement.
#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_prepareStatement<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    _conn_handle: jlong,
    sql: JString<'local>,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        let sql_str: String = match env.get_string(&sql) {
            Ok(s) => s.into(),
            Err(_) => return -1i64 as jlong,
        };
        alloc(StmtHandle { sql: sql_str, bindings: Vec::new() })
    }), -1i64 as jlong)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_finalizeStatement<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if handle > 0 {
            unsafe { free::<StmtHandle>(handle) };
        }
    }), ())
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_bindNull<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        unsafe { deref_mut::<StmtHandle>(handle) }.bindings.push((idx as usize, Value::Null));
    }), ())
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_bindLong<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
    value: jlong,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        unsafe { deref_mut::<StmtHandle>(handle) }
            .bindings
            .push((idx as usize, Value::Integer(value)));
    }), ())
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_bindDouble<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
    value: jdouble,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        unsafe { deref_mut::<StmtHandle>(handle) }
            .bindings
            .push((idx as usize, Value::Real(value)));
    }), ())
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_bindString<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
    value: JString<'local>,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if let Ok(s) = env.get_string(&value) {
            unsafe { deref_mut::<StmtHandle>(handle) }
                .bindings
                .push((idx as usize, Value::Text(s.into())));
        }
    }), ())
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_bindBytes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
    value: JByteArray<'local>,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if let Ok(bytes) = env.convert_byte_array(&value) {
            unsafe { deref_mut::<StmtHandle>(handle) }
                .bindings
                .push((idx as usize, Value::Blob(bytes)));
        }
    }), ())
}

/// Executes a DML/DDL statement.  Returns rows-changed (≥ 0) or -1 on failure.
#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_executeStatement<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    conn_handle: jlong,
    stmt_handle: jlong,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        let (sql, params) = {
            let s = unsafe { deref_mut::<StmtHandle>(stmt_handle) };
            (s.sql.clone(), build_params(&s.bindings))
        };
        let conn_ptr = conn_handle as *mut ConnHandle;
        let result = get_runtime().block_on(async {
            let conn = unsafe { &(*conn_ptr).conn };
            conn.execute(&sql, libsql::params_from_iter(params)).await
        });
        match result {
            Ok(n) => n as jlong,
            Err(e) => {
                unsafe { (*conn_ptr).last_error = Some(e.to_string()) };
                -1i64 as jlong
            }
        }
    }), -1i64 as jlong)
}

/// Runs a SELECT, eagerly collects all rows, and returns a cursor handle.  Returns -1 on failure.
#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_queryStatement<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    conn_handle: jlong,
    stmt_handle: jlong,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        // Clone SQL and params before the async block to avoid borrow-checker conflicts
        // between the &mut StmtHandle reference and the block_on call boundary.
        let (sql, params) = {
            let s = unsafe { deref_mut::<StmtHandle>(stmt_handle) };
            (s.sql.clone(), build_params(&s.bindings))
        };
        let conn_ptr = conn_handle as *mut ConnHandle;
        let result = get_runtime().block_on(async {
            // SAFETY: conn_ptr valid; no concurrent mutation (enforced by Kotlin connection pool).
            let conn = unsafe { &(*conn_ptr).conn };
            let mut rows = conn.query(&sql, libsql::params_from_iter(params)).await?;
            let col_count = rows.column_count() as usize;
            let mut collected: Vec<Vec<Value>> = Vec::new();
            while let Some(row) = rows.next().await? {
                let mut row_vals = Vec::with_capacity(col_count);
                for i in 0..col_count {
                    row_vals.push(row.get_value(i as i32).unwrap_or(Value::Null));
                }
                collected.push(row_vals);
            }
            Ok::<_, libsql::Error>((collected, col_count))
        });
        match result {
            Ok((rows, column_count)) => alloc(CursorHandle { rows, pos: 0, column_count }),
            Err(e) => {
                // SAFETY: block_on completed; no aliasing with the async block above.
                unsafe { (*conn_ptr).last_error = Some(e.to_string()) };
                -1i64 as jlong
            }
        }
    }), -1i64 as jlong)
}

// ---------------------------------------------------------------------------
// Cursor
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_cursorNext<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    with_env_catch(&env, AssertUnwindSafe(|| {
        let cur = unsafe { deref_mut::<CursorHandle>(handle) };
        if cur.pos < cur.rows.len() {
            cur.pos += 1;
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }), JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_cursorColumnCount<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    with_env_catch(&env, AssertUnwindSafe(|| {
        unsafe { deref_mut::<CursorHandle>(handle) }.column_count as jint
    }), 0i32)
}

/// Returns a pointer to the current row's value at `idx`, or None if out of range.
/// # Safety: handle must be valid and pos must be ≥ 1.
fn current_value(handle: jlong, idx: jint) -> Option<Value> {
    let cur = unsafe { deref_mut::<CursorHandle>(handle) };
    if cur.pos == 0 || cur.pos > cur.rows.len() {
        return None;
    }
    cur.rows.get(cur.pos - 1)?.get(idx as usize).cloned()
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_cursorIsNull<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
) -> jboolean {
    with_env_catch(&env, AssertUnwindSafe(|| {
        match current_value(handle, idx) {
            Some(Value::Null) | None => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }), JNI_TRUE)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_cursorGetLong<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
) -> jlong {
    with_env_catch(&env, AssertUnwindSafe(|| {
        match current_value(handle, idx) {
            Some(Value::Integer(n)) => n,
            Some(Value::Real(f)) => f as jlong,
            _ => 0,
        }
    }), 0i64)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_cursorGetDouble<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
) -> jdouble {
    with_env_catch(&env, AssertUnwindSafe(|| {
        match current_value(handle, idx) {
            Some(Value::Real(f)) => f,
            Some(Value::Integer(n)) => n as jdouble,
            _ => 0.0,
        }
    }), 0.0f64)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_cursorGetString<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
) -> jstring {
    with_env_catch(&env, AssertUnwindSafe(|| {
        match current_value(handle, idx) {
            Some(Value::Text(s)) => env
                .new_string(&s)
                .map(|js| js.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            _ => std::ptr::null_mut(),
        }
    }), std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_cursorGetBytes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    idx: jint,
) -> jbyteArray {
    with_env_catch(&env, AssertUnwindSafe(|| {
        match current_value(handle, idx) {
            Some(Value::Blob(b)) => env
                .byte_array_from_slice(&b)
                .map(|a| a.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            _ => std::ptr::null_mut(),
        }
    }), std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_closeCursor<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if handle > 0 {
            unsafe { free::<CursorHandle>(handle) };
        }
    }), ())
}

// ---------------------------------------------------------------------------
// Extended connection introspection — MVCC / poison / errcode
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_connectionExtendedErrcode(
    env: JNIEnv,
    _class: JClass,
    conn_handle: jlong,
) -> jni::sys::jint {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if conn_handle == 0 { return 0i32; }
        let ch = unsafe { &*(conn_handle as *const ConnHandle) };
        // libsql doesn't expose sqlite3_extended_errcode directly through public API.
        // Use last_error string as fallback and check for SQLITE_BUSY_SNAPSHOT.
        // FIXME: replace with sqlite3_extended_errcode raw FFI when libsql exposes it.
        if let Some(ref err) = ch.last_error {
            if err.contains("517") || err.contains("SQLITE_BUSY_SNAPSHOT") || err.contains("snapshot") {
                return 517i32; // SQLITE_BUSY_SNAPSHOT
            }
        }
        0i32
    }), 0i32)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_isConnectionPoisoned(
    env: JNIEnv,
    _class: JClass,
    conn_handle: jlong,
) -> jni::sys::jboolean {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if conn_handle == 0 { return JNI_FALSE; }
        let ch = unsafe { &*(conn_handle as *const ConnHandle) };
        if ch.poisoned.load(std::sync::atomic::Ordering::SeqCst) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }), JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_isDatabaseMvccEnabled(
    env: JNIEnv,
    _class: JClass,
    db_handle: jlong,
) -> jni::sys::jboolean {
    with_env_catch(&env, AssertUnwindSafe(|| {
        if db_handle == 0 { return JNI_FALSE; }
        let dh = unsafe { &*(db_handle as *const DbHandle) };
        if dh.mvcc_enabled { JNI_TRUE } else { JNI_FALSE }
    }), JNI_FALSE)
}

// ---------------------------------------------------------------------------
// Tests — empirical MVCC probe
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn probe_mvcc_local_mode() {
        let db = libsql::Builder::new_local(":memory:").build().await.unwrap();
        let conn = db.connect().unwrap();
        conn.execute("PRAGMA journal_mode='mvcc'", ()).await.unwrap();
        let mut rows = conn.query("PRAGMA journal_mode", ()).await.unwrap();
        let row = rows.next().await.unwrap().unwrap();
        let mode: String = row.get(0).unwrap();
        assert_eq!(mode, "mvcc", "MVCC mode not supported in this libsql build — fallback to WAL required");
        conn.execute("BEGIN CONCURRENT", ()).await.unwrap();
        conn.execute("ROLLBACK", ()).await.unwrap();
    }

    #[tokio::test]
    async fn probe_begin_concurrent_is_raw_sql() {
        // Documents that BEGIN CONCURRENT has no typed API variant — must be raw SQL
        let db = libsql::Builder::new_local(":memory:").build().await.unwrap();
        let conn = db.connect().unwrap();
        let result = conn.execute("BEGIN CONCURRENT", ()).await;
        // Just verify it's a recognized command (no "syntax error")
        match result {
            Ok(_) => { conn.execute("ROLLBACK", ()).await.ok(); }
            Err(e) => {
                let msg = e.to_string();
                assert!(!msg.contains("syntax error"), "BEGIN CONCURRENT is not a recognized SQL keyword: {msg}");
            }
        }
    }
}
