# ADR-001: MCP Server Runs Embedded in the Desktop Process, Bridged by a Local-Socket Launcher Shim, No Token Auth

**Date**: 2026-09-14
**Status**: Accepted
**Deciders**: Tyler Stapler
**Context**: graph-mcp-server Phase 3 (planning) — resolves requirements.md Open Questions 1, 3, and 5

---

## Context

`requirements.md` leaves three related open questions: (1) whether an MCP server is
compatible with `project_plans/llm-service/requirements.md`'s "no mandatory new
backend/server" constraint, (3) whether the MCP server runs as its own OS process or
inside the running SteleKit desktop app, and (5) whether the stdio↔running-app bridge
needs a token even though it's loopback/local-process-only.

`research/architecture.md` and `research/pitfalls.md` establish two facts that
determine the answer to all three at once:

- `LlmSuggestionInbox` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionInbox.kt`)
  is a plain in-memory `MutableStateFlow`, scoped to the JVM heap of whichever process
  constructs it (ADR-012, `project_plans/llm-service/decisions/ADR-012-in-memory-suggestion-inbox.md`).
  There is no IPC into it and no persisted table. A separate MCP process cannot call
  `propose()` on the instance the running desktop app's `StelekitViewModel` is
  observing — it would need to invent a new cross-process channel just to reach the
  inbox, which is itself the kind of new backend surface `llm-service/requirements.md`
  is trying to avoid.
- The human must review every write in the SteleKit desktop UI
  (`requirements.md`'s own "Users / Consumers" section). There is no scenario in scope
  where a standalone MCP process usefully operates while the desktop app is closed,
  since nothing it proposes could ever be accepted. This removes the only reason
  (availability when the app is closed) to want a second process, from v1 scope
  entirely.

Separately, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/DriverFactory.jvm.kt`
configures the app's SQLite connection pool with `journal_mode=WAL` and
`busy_timeout=10000`, but `DatabaseWriteActor`'s write-serialization guarantee is
in-process only — it has no cross-process equivalent. A second process opening its own
`GraphManager`/connection pool against the same `.db` file would be safe for reads but
unsafe for writes as a system property (confirmed via a real-world precedent:
`imap-mcp-pro` issue #290, where Claude Desktop itself spawned two instances of one
stdio MCP server against a single SQLite file).

MCP hosts (Claude Desktop/Code) spawn a **fresh subprocess per session** and own its
stdin/stdout exclusively — the standard desktop MCP transport convention. SteleKit's
desktop app is a long-running GUI process whose stdio is already used for its own
console/logging, so it cannot itself be the process an MCP host spawns.

## Decision

Ship two artifacts:

1. **The real MCP server** (tool handlers, resource reads, `PendingLlmSuggestion`
   construction) runs inside the existing desktop app's JVM process, gated behind a
   Settings toggle (`mcpServerEnabled`, off by default). When enabled, it opens a
   `kotlin-sdk` `Server` bound to a **local Unix domain socket** (`java.net.UnixDomainSocketAddress`
   / `java.nio.channels.SocketChannel.open(StandardProtocolFamily.UNIX)`, JEP 380,
   available on the JDK 21 toolchain this repo already targets — no third-party
   sockets library needed) at a per-user path (e.g.
   `~/.stelekit/mcp/<graphId>.sock` on Linux/macOS). The socket file is created with
   owner-only permissions (`0600` via `PosixFilePermissions`, or the OS default for a
   file created in a `0700` directory) — the same trust boundary stdio's
   process-parentage model already relies on: only processes running as the same OS
   user can connect.
2. **A tiny standalone launcher binary** (`dev.stapler.stelekit.mcp.McpLauncherMain`,
   no KMP/Compose dependencies, no `GraphManager`) is what the MCP host's config
   actually spawns via stdio. It does nothing but connect to the desktop app's local
   socket and proxy bytes bidirectionally between its own stdin/stdout and that
   socket — the MCP JSON-RPC framing passes through unmodified. If the desktop app
   isn't running (or the target graph isn't open), the shim fails to connect and exits
   with a clear error the MCP host surfaces as a tool-list failure.

**No token/credential scheme for v1.** The socket's filesystem permissions already
provide the same "only my own processes can reach this" guarantee stdio's
process-parentage model provides — adding a bearer token on top would authenticate a
principal (the current OS user) the socket ACL already authenticates, for no
additional protection against the actual threat model (a different OS user, or a
remote attacker, neither of which can reach a Unix-domain-socket file they don't have
filesystem access to).

The MCP process **never opens a second `GraphManager`/`DriverFactory`/DB connection**.
All repository calls run through the same `GraphManager` instance the desktop UI uses
(`GraphManager.getActiveRepositorySet()`), sidestepping the cross-process SQLite
write-safety question in production entirely rather than relying on it.

This also resolves Open Question 1: the core app has zero runtime dependency on the
MCP server when the toggle is off (no socket opened, no `kotlin-sdk` classes touched
at runtime beyond being on the classpath) — satisfying `llm-service/requirements.md`'s
"no mandatory new backend/server" intent for the app's default behavior, while still
delivering the optional integration this item asks for. This is a scoping
interpretation, not an edit to `llm-service/requirements.md` itself — **the user
should confirm this reading is acceptable before Phase 4 (Settings toggle) ships**,
since a documented cross-project constraint is being narrowed rather than a code
change being made.

## Consequences

**Positive**:
- Zero new cross-process SQLite concurrency surface — one process, one connection
  pool, one `DatabaseWriteActor`, exactly as today.
- `LlmSuggestionInbox`/`LlmSuggestionWriter`/the review UI need no modification —
  the MCP tool handlers are just another in-process caller.
- No token/secret to generate, store, rotate, or leak — one fewer thing to get wrong
  in a security-relevant path.
- The launcher binary is small enough to audit in full and has no dependency on the
  KMP/Compose/SQLDelight stack, so it can't introduce a build/version-skew problem of
  its own.

**Negative / accepted v1 limitations**:
- MCP tool calls only work while the SteleKit desktop app is running and has the
  target graph open. A `search`/`read` call issued while the app is closed fails fast
  with a clear "SteleKit is not running" error (the launcher's connect failure),
  rather than the MCP host being able to launch SteleKit on demand — there is no
  mechanism for that in v1, and MCP's stdio transport only launches the launcher's own
  process, not arbitrary other applications.
- Any local process running as the same OS user can connect to the socket once the
  feature is enabled — this is deliberately equivalent to, not stronger than, the
  trust model stdio itself provides (whatever process a user's MCP host config can
  spawn already has this same level of access to their files and this app). If a
  future requirement needs to distinguish "my MCP host" from "some other local
  process as the same user," that requires a token scheme — explicitly deferred, not
  designed here.
- Windows named-pipe behavior is not separately verified in this ADR; JEP 380's
  `StandardProtocolFamily.UNIX` is documented to work on Windows 10 1803+ (which
  ships native `AF_UNIX` support), so the same code path is expected to work
  unmodified, but this has not been build-tested on Windows as of this ADR.

## Alternatives Considered

### Fully standalone MCP process with its own `GraphManager`/DB connection

Rejected. Gains nothing (the desktop app must be open for the human review step
regardless of where the read-side tool logic runs) while introducing real risk
(cross-process SQLite write safety, the file-watcher write-suppression mechanism
racing a second writer, `LlmSuggestionInbox` being unreachable without inventing a new
IPC channel — see `research/pitfalls.md` §1–2).

### Standalone process with a persisted suggestion queue (undoing ADR-012)

Rejected for v1. Would require reversing `project_plans/llm-service/decisions/ADR-012-in-memory-suggestion-inbox.md`'s
tradeoff — a new SQLDelight table, a `MigrationRunner.all` entry, and
`@DirectSqlWrite`-gated access — solely to let an independent process see pending
suggestions. No concrete need for the MCP server to work while the desktop app is
closed is in scope for v1; flagged as a v2 option if that need materializes.

### Bearer-token-over-loopback-TCP (Obsidian Local REST API / Trilium ETAPI precedent)

Rejected for v1. `research/features.md`'s comparable-products survey found
bearer-token + localhost-bind as the converged pattern for **HTTP** local APIs — but
those products have no OS-level equivalent to stdio's process-parentage trust model to
lean on, because their transport is a TCP socket any local (or, if misconfigured,
remote) process can attempt to reach. A Unix domain socket restricted to the current
OS user already closes that gap without a token, and this project's v1 transport is
stdio/local-socket, not HTTP — the precedent doesn't transfer cleanly. Revisit if a
future v2 adds a network-reachable transport (explicitly out of scope per
`requirements.md`).
