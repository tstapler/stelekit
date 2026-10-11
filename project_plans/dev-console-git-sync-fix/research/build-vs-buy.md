# Build vs Buy: dev console and git fix

Date: 2026-10-10. Brief: `../requirements.md`.
Confidence labels: VERIFIED = read in repo; INFERRED = from general knowledge of the libraries, not re-checked online this session (licenses, sizes, and maturity should be confirmed before adoption).

## Classpath today (VERIFIED, `kmp/build.gradle.kts`)
There is no `gradle/libs.versions.toml`; dependencies are inline.
- commonMain (lines 82-135): Arrow 2.2.1.1, coroutines 1.10.2, kotlinx-datetime/serialization, SQLDelight runtime 2.3.2, Compose MP (runtime/foundation/material3/icons), Coil 3, Ktor client 3.1.3, Ksoup, Okio 3.17.0, JetBrains `markdown`.
- JGit 7.3.0 on jvmMain (+ ssh.apache) and androidMain (+ ssh.jsch). Not in commonMain, not on wasmJs.
- jvmMain DB: `app.cash.sqldelight:sqlite-driver:2.3.2` (xerial sqlite-jdbc). androidMain DB: `com.github.requery:sqlite-android:3.49.0`. wasmJs DB: `@sqlite.org/sqlite-wasm`.
- No terminal, scripting, or SQL-parser library is present. Nothing in `kmp/src` references `setAuthorizer`, `query_only`, or PRAGMA read-only (grep returned no usable hits).

## (a) Console UI / REPL

| Option | Pros | Cons | License / maturity / KMP | Verdict |
|---|---|---|---|---|
| Custom Compose `LazyColumn` scrollback + `BasicTextField` input | Pure commonMain, so it works on Android, Desktop, and wasm. Matches the repo's "custom Compose renderers over third-party libs" preference (memory: render-all-markdown). Total control over cancel, truncation markers, and share/export. Needs no pty. | Must handle large-output perf (cap lines, windowed list), IME and focus quirks, and monospace selection and copy. Roughly 400-600 lines. | Own code, no new dependency | **Recommended** |
| Termux `terminal-view` / `terminal-emulator` | Real VT100 emulator. | Android-only (View-based, not Compose). GPLv3 for the app, with library modules under Apache-2.0 or GPL depending on module (INFERRED; check). Needs a pty or session. Out of scope per requirements ("not a terminal emulator"). | Android only, no KMP | Not recommended |
| JediTerm | Mature JVM terminal widget. | Swing/JetBrains only; needs a pty (pty4j). Desktop only. LGPL/Apache dual (INFERRED). Heavy. | JVM desktop only | Not recommended |
| Compose-terminal libs (e.g. `compose-terminal`, community) | Ready-made look. | Small, young, unmaintained-risk; emulation features we do not need; wasm support unverified. | Varies | Not recommended |
| Mordant / Clikt (JetBrains-adjacent, Apache-2.0) for output styling | Nice ANSI output on Desktop CLI. | Targets a real terminal; irrelevant inside Compose. | KMP incl. wasm | Not recommended for UI |

LLM-vs-library risk: UI is low-risk custom code; correctness is testable with Compose Robolectric tests (`androidUnitTest`, per CLAUDE.md). A library adds no meaningful correctness gain here.

## (b) Command interpreter / parsing

| Option | Pros | Cons | License / maturity / KMP / size | Verdict |
|---|---|---|---|---|
| Own tokenizer plus a `Command` registry in commonMain (quotes, escapes, `;`, maybe `\|` between built-ins) | Works on every target. Registry makes "one-file addition" trivial. No arbitrary-code surface. Cancellation via coroutines. Secret redaction centralised. | Must write and test the quoting rules. Roughly 150 lines. Risk of subtle quoting bugs if hand-written by an LLM, mitigated by property tests (kotest-property is already on the commonTest classpath per CLAUDE.md). | Own code | **Recommended** |
| Kotlin scripting / Kotlin REPL | Full language. | JVM-only; the compiler embeds ~50 MB; not usable on Android (no kotlinc at runtime) or wasm. Large unguarded surface. | Apache-2.0, JVM only | Not recommended |
| JShell | Built into JDK. | Not on Android; Desktop only. | GPL+CE (JDK) | Not recommended |
| Rhino / QuickJS (`quickjs-kt`, Zipline) | Scripting for power users; QuickJS is small (~1 MB native per ABI, INFERRED). | Needs a bridge layer to expose DB/git safely; native libs per ABI; wasm not covered by Rhino, and QuickJS-kt wasm support is unverified. Over-delivers versus the stated "probe" use. | Rhino MPL-2.0; quickjs-kt MIT/Apache (INFERRED) | Viable later, not now |
| Bundle toybox/busybox on Android | Real shell utilities. | Per-ABI binaries must be executed from app-private storage; Android 10+ blocks exec from writable app dirs (W^X, targetSdk 29+, INFERRED), so it only works from `nativeLibraryDir`. GPL (busybox) / 0BSD (toybox). Cannot see the SAF tree anyway (requirements Rabbit Holes). | Android-only | Not recommended |
| `Runtime.exec` / `ProcessBuilder` for an opt-in `sh` command | Zero bundle cost; works on Desktop; on Android runs `/system/bin/sh` with toybox (present on API 23+, INFERRED) as the app uid. Answers open question 6: expose as a thin `sh` command, platform-gated, behind second confirm. | Not available on wasm (and iOS). Sandboxed: cannot see SAF or other apps' data. Timeouts and cancellation need `Process.destroyForcibly`. | JDK / Android framework | **Viable** as the `sh` command only |

Recommendation: in-process interpreter for everything first-class (`sql`, `git`, `fs`, `settings`, `logs`, `graph`, `diag`), plus `sh` via `ProcessBuilder` in an `expect/actual` that returns "unsupported" on wasm/iOS.

## (c) SQL guard

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Read-only connection / `PRAGMA query_only=ON` for the `sql` read path | Enforced by SQLite itself, not by string matching; handles CTEs, `WITH ... INSERT`, triggers. Cheap. SQLDelight JVM driver is a pooled JDBC driver (PooledJdbcSqliteDriver per CLAUDE.md), so the pragma is per-connection and must be set and reset on a dedicated connection, or a second read-only connection opened with `SQLITE_OPEN_READONLY`. | Android's `requery/sqlite-android` pool and the wasm driver need separate handling. Changing the pragma on a shared pool connection is a footgun (leaks to other users of that connection). | **Recommended** for read mode, with a dedicated connection where the driver allows |
| Statement-class check (`sqlite3_stmt_readonly`; Android `SQLiteStatement.isReadOnly()`) | Authoritative per statement: SQLite itself says whether a prepared statement writes. Good to route `sql` (read) vs `sql!` (write, needs confirm and actor). | Availability through each driver is not verified: xerial JDBC and the wasm build may not expose it (INFERRED). It misses PRAGMAs that change state. | **Viable** as a second layer where exposed |
| SQLite authorizer callback (`sqlite3_set_authorizer`) | Finest control (allow/deny per table/column/op). | Not exposed by xerial sqlite-jdbc or the Android framework API (INFERRED, not verified); would need JNI. Not worth it for a single-user tool. | Not recommended |
| SQL parser library (JSqlParser, SQLDelight's sqlite grammar, ANTLR sqlite grammar) | Gives an AST. | JSqlParser is JVM-only (Apache-2.0/LGPL dual) and does not track SQLite dialect (FTS5, `PRAGMA`, `INSERT OR REPLACE`) exactly; any grammar gap becomes a bypass. SQLDelight's grammar is compile-time tooling, not a runtime dependency. A keyword regex is the failure mode LLM-written guards usually have. | Not recommended |

Write path (VERIFIED from CLAUDE.md): writes must go through `DatabaseWriteActor` (`actor.execute { }`) with a `RestrictedDatabaseQueries`-style raw-exec stub annotated `@DirectSqlWrite`, preceded by a DB backup and confirm. Do not rely on any guard to make raw writes "safe"; the guard only chooses which path a statement takes.

Correctness risk: engine-enforced options (read-only connection, `stmt_readonly`) have near-zero LLM-authored logic. Parser or regex options put all correctness in generated code.

## (d) Git: stay on JGit vs libgit2

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Stay on JGit 7.3.0 (EDL/BSD-3) | Already wired on Desktop and Android (VERIFIED), with SAF shadow support and credentials. The bug under investigation is a logic issue in `doFetch` (unresolved ref treated as "no changes"), not a library limitation. Console `git` subcommands map onto the existing `GitRepository` interface (`status`, `log`, `describeRefs`, `fetch`, `merge`...) plus raw JGit calls. Out of scope per requirements to replace it. | Pure-Java, slower on large repos; no wasm (the console's git is off on Web). | **Recommended** |
| libgit2 (via git24j, libgit2-android, or cinterop) | Faster, closer to canonical git behaviour (e.g. `ls-remote --symref`). | Native build per ABI; Android prebuilts are third-party; cinterop for iOS/Desktop; SSH needs libssh2 plus OpenSSL; large rewrite of auth/SAF shadow code; explicitly out of scope. GPLv2 with linking exception. | Not recommended |
| Shell out to system `git` | Exact canonical semantics. | Not present on Android; contradicts the "no shell assumption" constraint. | Not recommended |

Note: `ls-remote --symref HEAD` (default-branch detection) is available in JGit as `Git.lsRemoteRepository().setRemote(url)` / `LsRemoteCommand` with `callAsMap()` and the HEAD symref (INFERRED API detail; verify with `javap` before coding).

## (e) Existing debug inspectors

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Android Studio Database Inspector / `adb run-as` | Zero code. | Requires adb and a debuggable build; this is the stated gap. | Not recommended as a replacement; fine as a supplement when a cable is available |
| Flipper (Meta, MIT) | Rich plugins. | Deprecated/archived by Meta (INFERRED; confirm); needs a desktop client and network socket (excluded: no network-exposed console). Android-only. | Not recommended |
| Stetho (Facebook, BSD, archived) | Chrome DevTools bridge for DB and network. | Archived; listens on a local socket reachable via adb forward; Android-only; needs Chrome on a host machine. | Not recommended |
| Chucker (Apache-2.0) | In-app network inspector UI. | HTTP only (OkHttp interceptor); the app uses Ktor, and it is irrelevant to SQL/git/fs. Android-only. | Not recommended |
| Android's built-in `adb bugreport` / `dumpsys` | Free. | Needs adb. | Not applicable |

## Summary decision
- UI: build (custom Compose). Interpreter: build (tokenizer plus registry; `sh` via `ProcessBuilder`). SQL guard: engine-enforced (read-only connection / `stmt_readonly`) and writes via `DatabaseWriteActor`. Git: stay on JGit and fix `doFetch`. Inspectors: none fit the no-adb, no-socket, cross-platform constraints.
- Net new dependencies: none. Size cost: effectively zero.
- Open items to verify before planning: whether `requery/sqlite-android` and the wasm driver expose `stmt_readonly`/read-only open flags; JGit `LsRemoteCommand` symref API; Android exec behaviour on the owner's API level.
