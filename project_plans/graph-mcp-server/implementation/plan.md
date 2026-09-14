# Implementation Plan: graph-mcp-server

**Feature**: An optional, off-by-default MCP server embedded in the SteleKit desktop
app's JVM process, exposing read-only search/read/backlink tools and approval-gated
propose-write tools to external MCP clients (Claude Desktop/Code) over a local socket,
bridged to MCP hosts' stdio-subprocess convention by a thin launcher binary.
**Date**: 2026-09-14
**Status**: Ready for implementation
**ADRs**:
- [ADR-001: MCP Server Runs Embedded in the Desktop Process, Bridged by a Local-Socket Launcher Shim, No Token Auth](../decisions/ADR-001-embedded-process-local-socket-no-token.md)
- [ADR-002: MCP Proposals Reuse `LlmSuggestionInbox` Unmodified; Pileup Cap Lives in the MCP Adapter, Not the Inbox](../decisions/ADR-002-mcp-suggestion-provenance-and-pileup-cap.md)

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `McpAdapter` | Top-level façade in the new `mcp/` package that wires `GraphManager` + `McpSuggestionSink` into a `kotlin-sdk` `Server` instance and registers all tools/resources. Constructor is `McpAdapter(graphManager: GraphManager, suggestionSink: McpSuggestionSink, mcpClientName: () -> String)` — takes `McpSuggestionSink`, never a raw `LlmSuggestionInbox`, and a resolved client-identity accessor (see `McpClientIdentity` below and the adversarial-review Blocker fix in Story 1.1.2/3.1.1). Internally builds one `McpPendingCapGuard` (Story 3.2.1) from the `suggestionSink` and passes it to both propose-tool registrations — see Story 1.1.2/4.1.3 and the architecture-review fix below. | New, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt` |
| `McpSuggestionSink` | Narrow interface (`pending: StateFlow<Map<String, PendingLlmSuggestion>>`, `propose(suggestion)`) that `McpAdapter`/propose-tools depend on instead of the raw `LlmSuggestionInbox`. `ViewModelSuggestionSink` is the production implementation, wrapping `StelekitViewModel.llmSuggestions`/`proposeLlmSuggestion(...)` — the same gated call convention the page-scan matcher already uses. Exists because `LlmSuggestionInbox` is a `private` field on `StelekitViewModel` with no accessor; this is the seam that lets tool handlers reach the inbox only through the ViewModel's already-public, already-gated API. | New, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSuggestionSink.kt`, Story 1.1.2 |
| `McpServerController` | JVM lifecycle owner: observes `mcpServerEnabled` from the running `StelekitViewModel`'s `uiState` and starts/stops the local socket listener accordingly. Constructed in `desktop/Main.kt` once **both** a `GraphManager` and a `StelekitViewModel` instance have been captured via `StelekitApp`'s `onGraphManagerReady`/`onViewModelReady` callbacks — those instances are built deep inside the `commonMain` `StelekitApp` composable (`App.kt:711`), never locally in `Main.kt`. See Story 4.1.3. | New, JVM-only, constructed once in `desktop/Main.kt` |
| `onViewModelReady` | New optional `((StelekitViewModel) -> Unit)?` parameter on `StelekitApp` (`App.kt`), invoked via `LaunchedEffect(viewModel) { onViewModelReady?.invoke(viewModel) }` immediately after `viewModel` is constructed (`App.kt:711`) — mirrors the existing `onGraphManagerReady` callback (`App.kt:176-177`, `App.kt:267`) exactly, giving `Main.kt` the same kind of escape hatch for the ViewModel that it already has for `GraphManager`. | New, `commonMain`, Task 4.1.3b |
| `McpSocketServer` | Wraps a `kotlin-sdk` `Server` + a bound Unix-domain-socket `ServerSocketChannel`, accepting one launcher-shim connection at a time and running the MCP session loop over it. A second simultaneous connection attempt is accepted at the socket level but immediately sent a JSON-RPC "server busy" error frame and closed — never queued, never left to hang. Calls `Server.createSession(transport): ServerSession` per accepted connection (not the lower-level `Server.connect`), and registers `session.onInitialized { mcpClientIdentity.onSessionInitialized(session) }` so `McpClientIdentity` captures the connecting client's negotiated name — see Story 1.1.2/1.2.1. | New |
| `McpClientIdentity` | Small mutable holder (`kmp/src/jvmMain/.../mcp/McpClientIdentity.kt`) resolving the connected MCP client's display name for provenance strings. `fun resolve(): String` returns the last-captured name (default `"unknown-mcp-client"` before any handshake completes); `internal fun onSessionInitialized(session: ServerSession)` sets it from `session.clientVersion?.name`. Exists because `kotlin-sdk-server:0.15.0`'s tool-handler receiver (`ClientConnection`, the receiver type of `Server.addTool`'s handler lambda) exposes only a `sessionId: String` documented as "has no inherent meaning" — **not** `clientVersion`/`clientInfo` — so client identity is unreachable from inside a tool handler itself. The only place the negotiated `Implementation` (client name/version, populated from the `initialize` request's `clientInfo`) is reachable is on the `ServerSession` object `Server.createSession(transport)` returns to its caller — i.e. `McpSocketServer`'s accept loop, not the tool handler. Verified directly against `kotlin-sdk-server` tag `0.15.0` (commit `76b8e7d63aac39fe41e03684e3b91a6c3724a8da`): `ClientConnection.kt` (interface members), `ServerSession.kt:72,78,124,352` (`clientVersion`/`onInitialized`/handshake assignment), `Server.kt:216` (`createSession` returns `ServerSession`). Because `McpSocketServer` accepts only one connection at a time (Story 1.2.1), session-level capture is equivalent in accuracy to a literal per-call lookup — the client never changes mid-session. | New, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpClientIdentity.kt`, Task 1.1.2e |
| `McpPendingCapGuard` | `class McpPendingCapGuard(private val sink: McpSuggestionSink)` — wraps `McpPendingCap`'s count-then-propose sequence in a single `Mutex.withLock { ... }` so pipelined/concurrent `propose_*` calls can't race past `MCP_PENDING_CAP`. `suspend fun proposeIfUnderCap(build: () -> PendingLlmSuggestion): Either<McpToolError.InboxFull, Unit>`. One instance is constructed per `McpAdapter` (shared by both propose-tool registrations, since the cap is source-scoped across both tools, not per-tool). See Story 3.2.1. | New, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpPendingCap.kt` |
| `McpLauncherMain` | `main()` of the tiny standalone binary an MCP host spawns via stdio; proxies bytes between its own stdin/stdout and `McpSocketServer`'s socket. No KMP/Compose/`GraphManager` dependency; ships as a separate fat jar (`stelekit-mcp-launcher.jar`) bundled into the packaged app via Compose Desktop's `appResourcesRootDir`, not the full app classpath. | New, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpLauncherMain.kt` |
| `McpToolHandlers` | Object grouping the `addTimedTool(...)` registration + handler-lambda bodies for every MCP tool this feature exposes. | New |
| `addTimedTool` | `Server` extension (`McpToolTimeout.kt`) wrapping every tool handler body in `withTimeout(timeoutMs)`, mapping a `TimeoutCancellationException` to `McpToolError.Timeout` and any other `Throwable` (after re-throwing plain `CancellationException` for normal client-disconnect cancellation) to `McpToolError.UnexpectedFailure` — per `research/pitfalls.md` §5.6 item 6 and the architecture-review Concern this fixes. All six tool registrations use this instead of the SDK's raw `addTool`. | New, Story 1.1.3 |
| `search_graph` | MCP tool name. Wraps `SearchRepository.searchWithFilters(SearchRequest)`. | Tool schema in Story 2.1.1 |
| `list_graphs` | MCP tool name. Wraps `GraphManager`'s `GraphRegistry.graphs`. | Tool schema in Story 2.1.2 |
| `read_page` | MCP tool name. Wraps `PageRepository.getPageByName`/`getPageByUuid` + `BlockReadRepository.getBlocksForPage`, block-paginated. | Tool schema in Story 2.2.1 |
| `list_backlinks` | MCP tool name. Wraps `BlockSearchRepository.getLinkedReferences(pageName, limit, offset)`. | Tool schema in Story 2.2.2 |
| `note://<uuid>` | MCP Resource URI scheme (`resources/read`) addressing an individual page by UUID, reusing `read_page`'s resolution logic. `resources/list` always returns empty (never enumerates the graph); `resources/templates/list` publishes the `note://{uuid}` template. | Tool schema in Story 2.3.1 |
| `propose_block_edit` | MCP tool name. Constructs a `PendingLlmSuggestion.BlockEdit` and calls `McpSuggestionSink.propose(...)`. Never writes directly. | Tool schema in Story 3.1.1 |
| `propose_new_page` | MCP tool name. Constructs a `PendingLlmSuggestion.NewPage` and calls `McpSuggestionSink.propose(...)`. Never writes directly. | Tool schema in Story 3.1.2 |
| `McpToolError` | New sealed class enumerating MCP-specific failure reasons that are not `DomainError`s (`GraphNotOpen`, `AmbiguousPageName`, `InboxFull`, `Timeout`) — translated to `CallToolResult(isError = true, ...)`. | Type-driven design, Story 1.1.2 |
| `mcpProvenance` | String convention `"mcp:<mcp-client-name>:<proposal-id>"` stored in `PendingLlmSuggestion.sourceProviderId` for MCP-sourced proposals, where `<mcp-client-name>` comes from `McpClientIdentity.resolve()` and `<proposal-id>` is a fresh `UuidGenerator.generateV7()` generated at propose time — **not** a literal MCP JSON-RPC call id, since `CallToolRequest` (verified against `kotlin-sdk-server:0.15.0`) exposes no such field (only `name`, `arguments`, `task`, `meta`). See Story 1.1.2/3.1.1 and ADR-002. | ADR-002 |
| `MCP_PENDING_CAP` | Constant (`20`) — max concurrently pending MCP-sourced suggestions before `propose_*` tools refuse with `McpToolError.InboxFull`. | ADR-002, Story 3.2.1 |
| `mcpServerEnabled` | `AppState`/`PlatformSettings` boolean flag (key `"mcp.server.enabled"`), off by default — the feature flag gating the entire server. | Story 4.1.1 |
| `pendingMcpSuggestionCount` | Derived `AppState` field: count of pending suggestions whose `sourceProviderId` starts with `"mcp:"`, surfaced as a sidebar badge. | Story 3.2.2 |
| `McpSocketPath` | Helper computing the per-user, per-graph Unix-domain-socket path (`~/.stelekit/mcp/<graphId>.sock`) with `0700`-permission parent directory. | Story 1.2.1 |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| MCP server process topology | Embedded in desktop JVM process, local-socket transport + stdio proxy shim | Architectural (this plan, ADR-001) | (a) Fully standalone process with its own `GraphManager`/DB connection; (b) standalone process with a persisted suggestion queue | (a) gains nothing since the human must have the desktop app open to review anyway, while adding real cross-process SQLite write-safety risk; (b) undoes ADR-012's in-memory tradeoff for no v1 benefit |
| Protocol layer | Adopt official `io.modelcontextprotocol:kotlin-sdk-server:0.15.0` | Build-vs-buy research §1, §3 | Hand-rolled JSON-RPC/MCP framing | Protocol conformance risk is asymmetric — the SDK tracks real Claude Desktop/Code client behavior and spec revisions; hand-rolling reinvents a moving target for zero cost savings |
| Data access layer | Call `GraphManager.getActiveRepositorySet()` repositories directly, in-process | Architecture research (Open Question 2) | Build on issue #232 (local REST API) first, MCP as its HTTP client | #232 is open/unimplemented with no `project_plans/` entry — building it first means a second HTTP/token-auth layer for zero v1 benefit, and the item's own text allows this fallback |
| Approval-gate / write path | Reuse `LlmSuggestionInbox`/`LlmSuggestionWriter` directly (Observer + re-validate-before-execute, PoEAA Unit-of-Work-adjacent) | Architecture research (Open Question 4), ADR-002 | (a) New MCP-specific pending-suggestion queue; (b) in-band `confirm`/`dryRun` token round-trip (NotePlan/Apple-Notes style) instead of human UI review | (a) creates a second competing approval system against shipped code; (b) confirmation still lives entirely inside the untrusted client's control, per pitfalls research's Openlane "Guardian" precedent — a human physically reviewing in the SteleKit UI is the stronger mitigation already chosen |
| MCP↔desktop IPC transport & auth | Unix-domain socket, filesystem-permission-restricted, no token | ADR-001 | (a) Bearer-token-over-loopback-TCP (Obsidian/Trilium precedent); (b) no IPC — bespoke shared-memory/named-pipe framing | (a) doesn't transfer cleanly — those products have no OS-ACL equivalent to lean on, this project does; (b) reinvents byte-proxying a plain socket already solves |
| Suggestion pileup / approval fatigue | Per-MCP-source cap (`MCP_PENDING_CAP = 20`) enforced in the adapter layer before `propose()` | ADR-002, ux.md §4c | (a) Global cap inside `LlmSuggestionInbox` itself; (b) do nothing for v1 | (a) would let unrelated MCP activity and page-scan-matcher activity compete for one budget, and requires modifying shipped Epic 7 code; (b) leaves a documented approval-fatigue risk unaddressed by omission rather than decision |
| Tool-handler → repository call translation | Adapter pattern (GoF): one small handler class per tool, each translating MCP JSON args → repository `Either` call → `CallToolResult` | GoF | Inline lambda bodies directly in `Server` construction with no intermediate class | Inline lambdas are untestable without spinning up a full `Server`/transport harness; small adapter classes are unit-testable directly against fake repositories |
| Launcher shim | Proxy pattern (GoF): a minimal process that forwards bytes unmodified between two endpoints | GoF | MCP host execs the desktop app's own stdio directly | The desktop app's stdio is already used for its own console/logging and is a long-running GUI process, not a fresh-per-session subprocess MCP hosts expect |
| MCP-specific error vocabulary | New sum type `McpToolError` (type-driven design) | Type-driven design | Reuse `DomainError` directly as the MCP-facing error vocabulary | Not every MCP failure maps to a `DomainError` (e.g. "graph not open," "inbox full," "ambiguous page name" are MCP-adapter-level concerns, not domain-repository concerns) — forcing them into `DomainError` would pollute a vocabulary owned by `commonMain` repositories |
| MCP tool registration | `object`-based grouping of `addTimedTool` registrations, not a class hierarchy per tool | PoEAA (Transaction-Script-level complexity match) | A `Strategy`/`Command` object per tool with a shared interface | Each tool handler is a single, non-polymorphic translation step (args → repository call → result) with no need for runtime substitution — a full GoF `Strategy` hierarchy would be over-engineering for four straightforward, independently-testable functions |
| ViewModel/GraphManager access from `Main.kt` | New `onViewModelReady` callback on `StelekitApp`, mirroring the existing `onGraphManagerReady` (Observer/hook pattern already established in this codebase) | architecture-review Blocker fix (verified `App.kt:176-177`, `:267`, `:711`) | (a) Construct `GraphManager`/`StelekitViewModel`/`LlmSuggestionInbox` directly in `Main.kt` as the plan originally assumed; (b) give `McpAdapter` a raw `LlmSuggestionInbox` reference | (a) is impossible as scoped — those instances are built deep inside the `commonMain` `StelekitApp` composable, not `Main.kt`, confirmed by direct inspection; (b) is unreachable too, since `LlmSuggestionInbox` is a `private` field on `StelekitViewModel` with no accessor, and even if it were reachable it would bypass the ViewModel's existing gated mutation convention every other suggestion producer uses |
| MCP proposal delivery into the inbox | `McpSuggestionSink` interface + `ViewModelSuggestionSink` adapter wrapping `StelekitViewModel.proposeLlmSuggestion(...)`/`llmSuggestions` | architecture-review Blocker fix (Tell-Don't-Ask concern) | Tool handlers call `LlmSuggestionInbox.propose(...)` directly | Every existing suggestion producer (the page-scan matcher included) goes through `StelekitViewModel.proposeLlmSuggestion(suggestion)`, never the raw inbox — MCP tool handlers reaching around the ViewModel would be the only exception in the codebase, and a narrow interface keeps `McpAdapter`'s unit tests from needing to construct a full `StelekitViewModel` |
| Second simultaneous MCP connection | `McpSocketServer` accepts the second connection, immediately writes a JSON-RPC `-32000` "server busy" error frame, and closes it | adversarial-review Blocker fix, `research/pitfalls.md` §3.4 | (a) Queue the second connection until the first disconnects; (b) rely on OS-level socket backlog to refuse it silently | (a) reproduces exactly the "hang with no cancel path" failure mode pitfalls.md cites (anthropics/claude-code #44006, #63379) — a second MCP host (e.g. Claude Code opened alongside an already-connected Claude Desktop) would wait indefinitely with no error; (b) is not portably deterministic across the JDK's Unix-domain-socket backlog behavior on macOS/Linux/Windows, so an explicit in-app rejection is the only mechanism guaranteed to produce an immediate, clear error on every platform |
| Per-tool-call bound | Single `addTimedTool` `Server` extension wrapping every handler in `withTimeout(...)`, called from all six tool-registration functions | adversarial-review Blocker fix, `research/pitfalls.md` §3.4 | `withTimeout(...)` duplicated inside each of the six `registerXTool` functions | A single choke point makes the timeout impossible to forget on a future seventh tool, and keeps `McpToolError.Timeout` (already declared in the Domain Glossary but never wired) actually reachable from every handler rather than none |
| MCP page resource exposure | Thin `PageResource` wrapper reusing `read_page`'s existing uuid/name resolution function, `resources/list` always empty + `resources/templates/list` publishes `note://{uuid}` | adversarial-review Blocker fix, `research/stack.md` "Artifacts and modeling", CLAUDE.md's bounded-read rule | A resource-specific second data-access/resolution path | `requirements.md`'s in-scope Resources item (UUID-addressable pages, v1 — name lookup stays a `read_page` tool call, not a second resource-URI scheme) is satisfied without a second copy of the ambiguous-name/journal-date resolution logic Story 2.2.1 already built, and an always-empty `resources/list` keeps this from becoming the unbounded `getAllPages()`-style read CLAUDE.md forbids |
| MCP launcher binary distribution | Separate fat jar (`stelekit-mcp-launcher.jar`) built from the launcher's own minimal classpath, bundled into the packaged app via Compose Desktop's `appResourcesRootDir` mechanism | adversarial-review Blocker fix | Ship only the Gradle `JavaExec`/Bazel `kt_jvm_binary` dev-mode targets, as originally scoped | Neither a Gradle task nor a Bazel target is reachable from an installed `.msi`/`.dmg`/`.deb` — the launcher must exist as a real file inside the shipped install for `SettingsDialog`'s displayed launcher command to ever be runnable by an end user |
| MCP client-identity source for `sourceProviderId` provenance | Capture `ServerSession.clientVersion?.name` once per connection, at `McpSocketServer`'s accept loop via `session.onInitialized { ... }`, held in a small `McpClientIdentity` and read by tool handlers through a `mcpClientName: () -> String` accessor threaded through `McpAdapter`'s constructor | adversarial-review Blocker fix (this repair pass) — verified directly against `kotlin-sdk-server` tag `0.15.0` (commit `76b8e7d63aac39fe41e03684e3b91a6c3724a8da`) | (a) Read client identity per-call from inside the tool handler itself (the plan's original, unimplementable assumption); (b) a hardcoded per-controller-instance constant, ignoring the SDK entirely | (a) is impossible: `ClientConnection`, the tool handler's receiver type, exposes only a meaningless `sessionId`, never `clientVersion`/`clientInfo` — confirmed by reading `ClientConnection.kt`'s full public interface; (b) would work (and was the adversarial review's offered fallback) but throws away real, available data — `ServerSession.clientVersion` **is** reachable, just one layer up from the tool handler, at the point `Server.createSession(transport)` returns the session to `McpSocketServer`; since only one connection is ever active at a time (Story 1.2.1), session-scoped capture loses no accuracy relative to a true per-call lookup |
| MCP-proposal per-call identifier (`sourceProviderId`'s second segment) | Fresh `UuidGenerator.generateV7()` generated at propose time | This repair pass — verified `CallToolRequest` (tag `0.15.0`) exposes no call-id field | A literal MCP JSON-RPC request/call id read off `CallToolRequest` | `CallToolRequest` only exposes `name`, `arguments`, `task`, and `meta` (verified by reading `tools.kt`'s class body) — there is no call-id field to read, so the plan's original `request.callId` reference was as unimplementable as the `mcpClientName` gap it shipped next to |
| Pileup-cap check-and-propose atomicity | `McpPendingCapGuard` — one `Mutex`-guarded wrapper per `McpAdapter` instance, shared by both propose tools, replacing the separate count-then-propose calls | architecture-review Concern fix (this repair pass) | (a) Leave the check and the `propose()` call as two separate, unsynchronized steps (original plan); (b) add cap-awareness inside `LlmSuggestionInbox` itself | (a) is a real, verified race: pipelined/concurrent `propose_*` calls can all observe a stale under-cap count before any commits, exceeding `MCP_PENDING_CAP` by up to N-1; (b) was already rejected by ADR-002 for the same reason a global cap was rejected — it would touch shipped Epic 7 code for a policy that only matters to one new producer, and the socket layer's own second-connection fix (Task 1.2.1d) already establishes `Mutex`-in-the-adapter-layer as this plan's house style for exactly this kind of race |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| MCP integration surface vs. `commonMain` write-gating machinery | Not a violation — a new feature touching load-bearing existing subsystems (`GraphManager`'s single-active-`RepositorySet` model, Epic 7's in-memory inbox) that carry their own documented constraints | **Isolate via seam** — new JVM-only `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/` adapter package | Confirmed by `research/architecture.md`: every integration point (`RepositorySet`, `LlmSuggestionInbox`) is an existing, stable, already-tested seam the new package depends on as a client. No changes to `commonMain` repository interfaces, `DatabaseWriteActor`, `GraphWriter`, or Epic 7's domain model are needed — this satisfies `requirements.md`'s "requires no code change to `kmp/src/commonMain` write-gating rules" success metric by construction. |
| `GraphManager` single-`activeGraphId` model vs. a UI-less MCP client's "which graph" ambiguity | Not a bug — `GraphManager` was designed for a UI that always has an obvious active graph; an MCP client has no such affordance | **Extend as-is for v1** — add a `list_graphs` tool for visibility; all other tools operate against `GraphManager.getActiveGraphId()` | Adding session-scoped graph selection to the MCP protocol layer is a real new feature (a per-connection state machine), not a fix to an existing defect, and `requirements.md` does not require it for v1. Flagged as a named v2 candidate rather than silently assumed away — see Unresolved Questions. |

---

## Observability Plan

- **Logs**: every MCP tool call logs `tool=<name> elapsedMs=<n> result=<ok|error>` at
  INFO via the existing `dev.stapler.stelekit.logging.LogManager`/`FileLogSink`
  (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` already wires this
  sink at startup). Tool call **arguments and results are not logged at INFO**
  (they may contain private graph content) — only at DEBUG, gated the same way other
  verbose paths in this codebase are. Socket bind failures and launcher-shim connect
  failures log at ERROR.
- **Metrics**: wrap each tool handler body in an OpenTelemetry span
  `mcp.tool.<name>` via the existing `OtelProvider`/`OtelSpanRecorder`
  (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/performance/OtelProvider.kt`, already
  initialized in `Main.kt`) — no new metrics backend needed, this is a single-user
  local app. `pendingMcpSuggestionCount` is surfaced directly in the Settings MCP
  Server panel and the sidebar badge (Story 3.2.2), not via a separate metrics path.
- **Alerts**: none — single-user local desktop app, no on-call. Socket-bind failure
  surfaces as a dismissible top-of-app banner (same pattern as
  `HostReconnectBanner.kt`), not a blocking dialog, since MCP connectivity is never
  required for the app's core read/write loop.

## Risk Control

- **Feature flag**: `mcpServerEnabled` (Settings → Developer or a new "MCP Server"
  category), off by default. This is the entire risk-control mechanism for this
  feature — with it off, zero MCP code paths execute at runtime (Story 4.1.1–4.1.3).
- **Rollback procedure**: toggle off. `McpServerController` stops the socket listener
  immediately; no persisted state exists beyond the boolean setting itself and the
  in-memory `LlmSuggestionInbox` (already session-scoped per ADR-012), so there is
  nothing to reverse or migrate back.
- **Staged rollout**: not applicable in the SaaS-cohort sense — this is a single-user
  personal desktop app with no server-side rollout mechanism. "Staged rollout" here
  means: ship behind the off-by-default toggle in a normal release; the user enables
  it manually when ready. Stated explicitly rather than inventing a cohort/percentage
  rollout plan that has no mechanism to execute against.

## Unresolved Questions

- [ ] **`kotlin-stdlib` 2.4.0 (SDK's transitive dependency) vs. the repo's pinned
  Kotlin Gradle plugin 2.3.21 — does the classpath resolve and compile cleanly?**
  — blocks Task 1.1.1a (adding the SDK dependency) — owner: implementer, resolved by
  running `./gradlew :kmp:dependencies --configuration jvmRuntimeClasspath` per
  `research/stack.md` §1 and watching for Kotlin metadata-version warnings/errors
  before proceeding past Phase 1.
- [ ] **Ktor server 3.5.1 (transitive via `kotlin-sdk-server`) vs. the repo's existing
  Ktor client 3.1.3 on the same `jvmMain` classpath — do both resolve without a
  duplicate/conflicting artifact version?** — adversarial-review Concern: the repo
  already pins `io.ktor:ktor-client-*` at 3.1.3 for Coil's network layer
  (`kmp/build.gradle.kts:120-122,168`), and a runtime `NoSuchMethodError`/
  `ClassNotFoundException` from mismatched Ktor artifacts is a more likely failure
  mode than the `kotlin-stdlib` metadata-version question above, which this plan's
  Unresolved Questions list previously tracked in isolation. Blocks Task 1.1.1a (same
  task as the `kotlin-stdlib` question, same command) — owner: implementer, resolved
  by running `./gradlew :kmp:dependencies --configuration jvmRuntimeClasspath` and
  checking the output for duplicate `io.ktor:ktor-*` entries or a forced/conflicting
  version resolution, not just Kotlin-stdlib metadata warnings.
- [x] **Does `kotlin-sdk-server` 0.15.0 expose per-call MCP client identity to a tool
  handler?** — **Resolved** (this repair pass, adversarial-review Blocker): No. Verified
  directly against `kotlin-sdk-server` tag `0.15.0`
  (commit `76b8e7d63aac39fe41e03684e3b91a6c3724a8da`): the tool-handler receiver type
  `ClientConnection` (`ClientConnection.kt`) exposes only `sessionId: String`
  (doc-commented "has no inherent meaning") and client-directed RPC methods — never
  `clientVersion`/`clientInfo`. The negotiated client `Implementation` (name/version,
  set from the `initialize` request's `clientInfo` — `ServerSession.kt:352`) lives on
  `ServerSession.clientVersion` (`ServerSession.kt:78`), which is reachable only where
  the session object itself is visible: the caller of `Server.createSession(transport):
  ServerSession` (`Server.kt:216`), i.e. `McpSocketServer`'s accept loop, not the tool
  handler. See the new `McpClientIdentity` (Task 1.1.2e) and the Pattern Decisions
  table entry for the resulting design: session-scoped capture via
  `session.onInitialized { ... }`, threaded into `McpAdapter` as
  `mcpClientName: () -> String`. Also resolved in the same pass: `CallToolRequest`
  exposes no call-id field (`name`/`arguments`/`task`/`meta` only), so
  `sourceProviderId`'s second segment is a fresh `UuidGenerator.generateV7()`, not a
  literal MCP call id — see Story 3.1.1.
- [x] **Does SteleKit's DB schema enforce page-name uniqueness, or only the
  domain-model's non-blank/length validation?** — **Resolved** (architecture-review):
  `SteleDatabase.sq:19` has `UNIQUE(name, section_id)` — uniqueness is per-section,
  not global. Task 2.2.1c's disambiguation branch is unconditional as a result; see
  Task 2.2.1a.
- [ ] **Does `project_plans/llm-service/requirements.md`'s "no mandatory new
  backend/server" constraint need its own text updated, or is ADR-001's "optional,
  off-by-default, zero runtime dependency when disabled" reading sufficient as-is?**
  — blocks nothing in this plan's task execution (ADR-001 already adopts the
  narrowing reading), but should be confirmed by Tyler before Phase 4 (the Settings
  toggle that makes the feature reachable) ships to `main` — owner: Tyler Stapler
  (product decision, not resolvable by an implementer).
- [ ] **Does the installed Compose Desktop app version in this repo resolve
  `appResourcesRootDir` bundled files at the exact `compose.application.resources.dir`
  system-property path this plan assumes, on all three packaged platforms
  (`.msi`/`.dmg`/`.deb`)?** — blocks Task 1.2.2f (bundling the launcher jar) and Task
  4.1.2a (the Settings panel's launcher-path resolution) — owner: implementer,
  resolved by building a local `packageDistributionForCurrentOS` and inspecting the
  installed app's resource directory for `stelekit-mcp-launcher.jar` plus printing
  `System.getProperty("compose.application.resources.dir")` from a debug log line
  before finalizing Task 4.1.2a's path logic.

## Dependency Visualization

```
Phase 1: Foundation
  1.1.1 Add kotlin-sdk-server dep (Gradle+Bazel) ──┐
  1.1.2 mcp/ package skeleton + McpToolError  ──────┤
        + McpSuggestionSink (not raw inbox)         │
  1.1.3 addTimedTool wrapper (depends on 1.1.2) ─────┼──> 1.2.1 Local-socket transport
                                                     │      (+ 1.2.1d/e: reject 2nd
                                                     │        simultaneous connection)
                                                     │        │
                                                     │        └──> 1.2.2 Launcher shim binary
                                                     │             + 1.2.2e/f: fat jar,
                                                     │               bundled into
                                                     │               nativeDistributions
Phase 2: Read-side tools (depends on 1.1.2, 1.1.3)   │
  2.1.1 search_graph  ─┐                             │
  2.1.2 list_graphs    ├── depend on McpAdapter ──────┘
  2.2.1 read_page      │   (1.1.2) + repository seam
  2.2.2 list_backlinks │   (already stable), registered
  2.3.1 note:// page   │   via addTimedTool (1.1.3)
        resource ───────┘   (reuses 2.2.1's resolver)

Phase 3: Approval-gated write tools (depends on 1.1.2's McpSuggestionSink,
         1.1.3's addTimedTool; independent of Phase 2)
  3.1.1 propose_block_edit ─┐
  3.1.2 propose_new_page    ├──> 3.2.1 Per-MCP-source pending cap (ADR-002,
                            │      via McpSuggestionSink.pending)
                            └──> 3.2.2 Sidebar pending-suggestion badge

Phase 4: Settings toggle & lifecycle (depends on 1.2.1, 1.2.2; independent of Phase 2/3
         tool bodies, but the toggle is meaningless until at least one tool exists)
  4.1.1 mcpServerEnabled setting ──> 4.1.2 SettingsDialog UI (launcher path resolved
                                       from packaged install, Task 1.2.2f)
                                       │
                                       └──> 4.1.3 McpServerController lifecycle:
                                              4.1.3a controller (GraphManager +
                                                StelekitViewModel, not raw inbox)
                                              4.1.3b onViewModelReady callback on
                                                StelekitApp (App.kt, mirrors
                                                onGraphManagerReady)
                                              4.1.3c construct in Main.kt once both
                                                onGraphManagerReady/onViewModelReady
                                                have fired
                                              4.1.3d tests

Phase 5: Security hardening (depends on 1.2.1)
  5.1.1 Socket path + permissions (formalizes what 1.2.1 already builds)

Phase 6: Verification (depends on all tool/resource stories: 2.1.1, 2.1.2, 2.2.1,
         2.2.2, 2.3.1, 3.1.1, 3.1.2)
  6.1.1 End-to-end tools/list + search/read businessTest
  6.1.2 Propose-write-never-mutates-before-accept businessTest
```

---

## Phase 1: Foundation — SDK Dependency, Adapter Skeleton, Local Transport

### Epic 1.1: Dependency & Adapter Skeleton

**Goal**: Get `kotlin-sdk-server` on the `jvmMain` classpath (Gradle + Bazel) and stand
up an empty, testable `Server` instance with no tools yet, proving the dependency
resolves and the wiring compiles before any domain logic is written.

#### Story 1.1.1: Add the Kotlin MCP SDK dependency
**As a** SteleKit contributor, **I want** `kotlin-sdk-server` available on the JVM
classpath, **so that** subsequent stories can construct a `Server` and register tools
without hand-rolling MCP protocol framing.

**Acceptance Criteria**:
- `./gradlew :kmp:dependencies --configuration jvmRuntimeClasspath` resolves without
  Kotlin metadata-version errors after the dependency is added.
  - *Given* `kmp/build.gradle.kts`'s `jvmMain` source set with
    `io.modelcontextprotocol:kotlin-sdk-server:0.15.0` added, *When* running
    `./gradlew :kmp:dependencies --configuration jvmRuntimeClasspath`, *Then* the
    command exits 0 and the output contains no `"Kotlin metadata definitely
    incompatible"` or `"class file has wrong version"` warnings for
    `kotlin-stdlib`.
- `bazel build //kmp:desktop_app` still succeeds after the equivalent Bazel maven
  coordinates are added.
  - *Given* `io.modelcontextprotocol:kotlin-sdk-server-jvm:0.15.0`,
    `io.ktor:ktor-server-core-jvm:3.5.1`, and `io.ktor:ktor-server-sse-jvm:3.5.1`
    added to `MODULE.bazel`'s maven `artifacts` list and to
    `kmp/src/jvmMain/kotlin/BUILD.bazel`'s `jvm_main_lib` `deps`, *When* running
    `bazel build //kmp:desktop_app`, *Then* the build succeeds with no dependency
    resolution conflict.

**Files**: `kmp/build.gradle.kts`, `MODULE.bazel`, `kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 1.1.1a: Add the Gradle dependency (~3 min)
- In `kmp/build.gradle.kts`, inside the existing `val jvmMain by getting { dependencies { ... } }`
  block (the one already containing `implementation("app.cash.sqldelight:sqlite-driver:2.3.2")`
  around line 154), add `implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")`
  with a one-line comment noting it transitively pulls Ktor server-core/SSE 3.5.1 and
  `kotlinx-io-core` 0.9.1 (new to this repo — see `research/stack.md` §1).
- Run `./gradlew :kmp:dependencies --configuration jvmRuntimeClasspath 2>&1 | grep -i "kotlin-stdlib\|incompatible"` and confirm no incompatibility warning (resolves the first Unresolved Question above).
- Files: `kmp/build.gradle.kts`

##### Task 1.1.1b: Add the Bazel maven coordinates (~4 min)
- In `MODULE.bazel`, add `"io.modelcontextprotocol:kotlin-sdk-server-jvm:0.15.0"`,
  `"io.ktor:ktor-server-core-jvm:3.5.1"`, `"io.ktor:ktor-server-sse-jvm:3.5.1"` to the
  maven `artifacts` list (near the existing `io.ktor:ktor-client-*` entries around
  line 230).
- In `kmp/src/jvmMain/kotlin/BUILD.bazel`, add the corresponding
  `@maven//:io_modelcontextprotocol_kotlin_sdk_server_jvm`,
  `@maven//:io_ktor_ktor_server_core_jvm`, `@maven//:io_ktor_ktor_server_sse_jvm`
  targets to `jvm_main_lib`'s `deps` list.
- Run `bazel build //kmp:desktop_app` and confirm success.
- Files: `MODULE.bazel`, `kmp/src/jvmMain/kotlin/BUILD.bazel`

#### Story 1.1.2: `mcp/` adapter package skeleton with `McpToolError`
**As a** SteleKit contributor, **I want** an empty, wired `McpAdapter` class and an
`McpToolError` sum type, **so that** every later tool-handler story has a stable place
to register into and a shared error vocabulary to return.

**Acceptance Criteria**:
- `McpAdapter` constructs a `kotlin-sdk` `Server` with no tools registered, connectable
  via the SDK's in-memory `ChannelTransport` from a test with no subprocess/socket
  involved.
  - *Given* `McpAdapter(graphManager, fakeSuggestionSink, mcpClientName = { "test-client" })`
    constructed in a `businessTest` (a fake `McpSuggestionSink`, not `LlmSuggestionInbox`
    — see below), *When* the test connects the SDK's `Server` via `ChannelTransport`
    and sends an MCP `initialize` request, *Then* the server responds with a valid
    `InitializeResult` and an empty `tools` capability list.
- `McpToolError` is a sealed class with at least `GraphNotOpen`, `AmbiguousPageName`,
  `InboxFull`, `Timeout`, and `Internal(cause: DomainError)` variants, each convertible
  to a `CallToolResult(isError = true, content = listOf(TextContent(...)))`.
  - *Given* `McpToolError.GraphNotOpen`, *When* `.toCallToolResult()` is called,
    *Then* it returns `CallToolResult(isError = true, content = [TextContent("No
    graph is currently open in SteleKit.")])`.
- `McpAdapter` depends on `McpSuggestionSink`, never a raw `LlmSuggestionInbox` — the
  inbox is a `private` field on `StelekitViewModel` with no accessor (verified
  `StelekitViewModel.kt:139`), so `McpAdapter` cannot and must not assume it can
  construct or receive one directly.
  - *Given* a fake `McpSuggestionSink` (in-memory `MutableStateFlow` + a recording
    `propose()`), *When* `McpAdapter(graphManager, fakeSink, mcpClientName = { "test-client" })`
    is constructed, *Then* it compiles and builds a `Server` with no reference to
    `LlmSuggestionInbox` anywhere in `McpAdapter.kt`.
- `McpAdapter`'s `mcpClientName: () -> String` constructor parameter is a resolved
  accessor, not a dangling reference — every call site that reads MCP client identity
  (Story 3.1.1/3.1.2's propose tools) reaches it through this same parameter, and
  `McpAdapter.buildServer()` passes it to both propose-tool registration functions
  (adversarial-review Blocker fix — see the new `McpClientIdentity`, Task 1.1.2e, and
  the Pattern Decisions table for why this can't be resolved from inside a tool
  handler itself).
  - *Given* `McpAdapter(graphManager, fakeSink, mcpClientName = { "claude-desktop" })`,
    *When* `buildServer()` is called and a `propose_block_edit` tool call is made
    through the resulting `Server`, *Then* the resulting `PendingLlmSuggestion`'s
    `sourceProviderId` starts with `"mcp:claude-desktop:"`.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpToolError.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSuggestionSink.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpClientIdentity.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpAdapterTest.kt`

##### Task 1.1.2a: `McpToolError` sealed class (~4 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpToolError.kt`: `sealed class McpToolError` with
  `GraphNotOpen`, `AmbiguousPageName(val name: String, val matchCount: Int)`,
  `InboxFull(val cap: Int)`, `Timeout(val toolName: String, val timeoutMs: Long)`,
  `Internal(val cause: DomainError)`, and
  `UnexpectedFailure(val toolName: String, val cause: Throwable)` variants, each with a
  `toCallToolResult(): CallToolResult` extension mapping to a human/LLM-readable
  message (`Timeout`'s message includes `toolName`/`timeoutMs` so the calling LLM
  knows which call to retry or shrink; `UnexpectedFailure`'s message includes
  `toolName` and `cause::class.simpleName` only — never the raw exception message or
  stack trace, consistent with the Observability Plan's "arguments/results aren't
  logged at INFO" discipline for potentially-sensitive content — and tells the LLM
  this is a SteleKit bug, not a retryable condition). `UnexpectedFailure` is Story
  1.1.3's target for `addTimedTool`'s broadened `Throwable` catch (architecture-review
  Concern fix, this repair pass).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpToolError.kt`

##### Task 1.1.2b: `McpSuggestionSink` interface + `ViewModelSuggestionSink` (~4 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSuggestionSink.kt`:
  `interface McpSuggestionSink { val pending: StateFlow<Map<String, PendingLlmSuggestion>>; fun propose(suggestion: PendingLlmSuggestion) }`
  and `class ViewModelSuggestionSink(private val viewModel: StelekitViewModel) : McpSuggestionSink`
  delegating `pending` to `viewModel.llmSuggestions` and `propose(...)` to
  `viewModel.proposeLlmSuggestion(...)` (`StelekitViewModel.kt:414-415`/`437-439`) —
  this is the only production path that ever touches suggestion state, and it goes
  through the same gated call every other suggestion producer uses.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSuggestionSink.kt`

##### Task 1.1.2c: `McpAdapter` skeleton (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`: `class McpAdapter(private val graphManager: GraphManager, private val suggestionSink: McpSuggestionSink, private val mcpClientName: () -> String)`
  with a `fun buildServer(): Server` that constructs `Server(Implementation(name = "stelekit", version = BuildInfo.appVersion), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false), resources = ServerCapabilities.Resources(subscribe = false, listChanged = false))))`
  and returns it with no tools/resources registered yet (later stories add
  `registerXTool(server, ...)`/`registerPageResource(server, ...)` calls here — Story
  3.1.1/3.1.2 pass `mcpClientName` through to the propose-tool registrations, and
  `buildServer()` constructs one `McpPendingCapGuard(suggestionSink)` — Story 3.2.1 —
  and passes it to both). `mcpClientName` is populated in production by a
  `McpClientIdentity` instance owned by the caller (`McpServerController`, Task
  4.1.3a) that `McpSocketServer` updates per connection — `McpAdapter` itself only
  ever reads it, never constructs or updates it (adversarial-review Blocker fix; see
  the Pattern Decisions table for why this can't be resolved from inside a tool
  handler).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`

##### Task 1.1.2d: `ChannelTransport` smoke test (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpAdapterTest.kt`: construct
  `McpAdapter` with a fake `GraphManager`, a fake `McpSuggestionSink` (in-memory
  `MutableStateFlow<Map<String, PendingLlmSuggestion>>` + a recording `propose()` —
  no `StelekitViewModel` construction needed for this unit-level test), and a fake
  `mcpClientName = { "test-client" }` lambda, connect its `Server` via
  `ChannelTransport`, send `initialize`, assert a successful `InitializeResult` with
  empty `tools`.
- Run `./gradlew jvmTest --tests "dev.stapler.stelekit.mcp.McpAdapterTest"` and confirm green.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpAdapterTest.kt`

##### Task 1.1.2e: `McpClientIdentity` — session-scoped client-name capture (~4 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpClientIdentity.kt`:
  `class McpClientIdentity { private val name: AtomicRef<String> = atomic("unknown-mcp-client"); fun resolve(): String = name.value; internal fun onSessionInitialized(session: ServerSession) { name.value = session.clientVersion?.name ?: "unknown-mcp-client" } }`
  (or an equivalent `AtomicReference`/JVM-native primitive — the SDK already depends
  on `kotlinx-atomicfu`, per `ServerSession`'s own use of `atomic(...)`, so reusing it
  keeps this consistent with the SDK's own concurrency primitive rather than adding a
  second one). This exists because `ServerSession.clientVersion` (the client's
  negotiated `Implementation`, i.e. name/version, from the `initialize` handshake) is
  reachable only where the `ServerSession` object itself is visible — the caller of
  `Server.createSession(transport)` — never from inside a tool handler, whose receiver
  type `ClientConnection` exposes only a meaningless `sessionId` (verified against
  `kotlin-sdk-server` tag `0.15.0`, commit `76b8e7d63aac39fe41e03684e3b91a6c3724a8da`
  — see the resolved Unresolved Question above and the Pattern Decisions table).
  `McpSocketServer` (Task 1.2.1b) calls `onSessionInitialized` from
  `session.onInitialized { ... }` once per accepted connection; `McpAdapter` reads it
  via `mcpClientName = mcpClientIdentity::resolve`, passed in at construction (Task
  4.1.3a wires both to the same `McpClientIdentity` instance).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpClientIdentity.kt`

##### Task 1.1.2f: `McpClientIdentity` test (~3 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpClientIdentityTest.kt`
  asserting: `resolve()` returns `"unknown-mcp-client"` before `onSessionInitialized`
  is ever called; after calling `onSessionInitialized(session)` with a fake/real
  `ServerSession` whose `clientVersion` is `Implementation(name = "claude-desktop",
  version = "1.0")` (set via a real `initialize` handshake over `ChannelTransport` in
  the test, mirroring Task 1.1.2d's setup), `resolve()` returns `"claude-desktop"`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpClientIdentityTest.kt`

#### Story 1.1.3: `addTimedTool` — bounded per-tool-call timeout wrapper
**As a** SteleKit desktop app, **I want** every MCP tool call bounded by a hard
timeout, **so that** one stalled repository call (large page, disk contention, a
closed-DB race not caught by `catchDbError()`) can never wedge the whole MCP session
for a connected client (per `research/pitfalls.md` §3.4, which requires exactly this,
and the `McpToolError.Timeout` variant Task 1.1.2a already declares).

**Acceptance Criteria**:
- `Server.addTimedTool(name, description, inputSchema, annotations, timeoutMs = 15_000L, handler)`
  wraps `handler` in `withTimeout(timeoutMs) { handler(request) }`; a
  `TimeoutCancellationException` is caught and mapped to
  `McpToolError.Timeout(name, timeoutMs).toCallToolResult()`, never propagated as an
  uncaught exception up through the SDK's session loop.
  - *Given* a tool registered via `addTimedTool("slow_tool", ..., timeoutMs = 100L) { delay(5_000); ... }`,
    *When* the tool is called, *Then* the call returns within roughly 100ms as
    `CallToolResult(isError = true, ...)` containing the `Timeout` message, not after
    a 5-second wait.
- Any other `Throwable` thrown from a handler body (an unmapped `RuntimeException`, an
  `Error` such as `OutOfMemoryError`, an NPE from a repository edge case) is caught and
  mapped to `McpToolError.UnexpectedFailure(name, cause).toCallToolResult()`, never
  propagated uncaught into the SDK's session loop — per `research/pitfalls.md` §5.6
  item 6 and the architecture-review Concern this fixes (Task 1.1.3a's original
  `catch (e: TimeoutCancellationException)`-only implementation left every other
  exception type unhandled). Plain `CancellationException` (not `TimeoutCancellationException`)
  is re-thrown, not treated as a tool error, so normal coroutine cancellation (e.g. the
  client disconnecting mid-call) isn't reported as a bug.
  - *Given* a tool registered via `addTimedTool("buggy_tool", ...) { throw IllegalStateException("boom") }`,
    *When* the tool is called, *Then* the call returns `CallToolResult(isError = true, ...)`
    containing the `UnexpectedFailure` message (naming `buggy_tool` and
    `IllegalStateException`, not the raw message text `"boom"`), and no exception
    propagates out of the `Server`/`ChannelTransport` call.
- All six tool-registration functions (`SearchGraphTool`, `ListGraphsTool`,
  `ReadPageTool`, `ListBacklinksTool`, `ProposeBlockEditTool`, `ProposeNewPageTool`)
  call `server.addTimedTool(...)`, never the SDK's raw `server.addTool(...)`.
  - *Given* the `mcp/tools/` package, *When* `Grep`-ed for `server.addTool(`, *Then*
    the only match is inside `addTimedTool`'s own definition in `McpToolTimeout.kt`.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpToolTimeout.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpToolTimeoutTest.kt`

##### Task 1.1.3a: `addTimedTool` extension (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpToolTimeout.kt`:
  `const val DEFAULT_TOOL_TIMEOUT_MS = 15_000L` and
  `fun Server.addTimedTool(name: String, description: String, inputSchema: Tool.Input, annotations: ToolAnnotations? = null, timeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS, handler: suspend (CallToolRequest) -> CallToolResult)`
  calling the SDK's
  `addTool(name, description, inputSchema, annotations) { request ->`
  `  try { withTimeout(timeoutMs) { handler(request) } }`
  `  catch (e: TimeoutCancellationException) { McpToolError.Timeout(name, timeoutMs).toCallToolResult() }`
  `  catch (e: CancellationException) { throw e }`
  `  catch (e: Throwable) { logger.error(e) { "Unhandled exception in MCP tool '$name'" }; McpToolError.UnexpectedFailure(name, e).toCallToolResult() }`
  `}` — the `catch (e: CancellationException) { throw e }` branch must come **after**
  the more specific `TimeoutCancellationException` catch and **before** the broad
  `Throwable` catch (ordering matters: `TimeoutCancellationException` is itself a
  `CancellationException` subtype), so normal cancellation (client disconnect) isn't
  misreported as `UnexpectedFailure` while every other `Throwable` — including
  `Error` subtypes like `OutOfMemoryError`, per `CLAUDE.md`'s own uncaught-Throwable
  rule — still cannot escape uncaught (architecture-review Concern fix, this repair
  pass; `research/pitfalls.md` §5.6 item 6).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpToolTimeout.kt`

##### Task 1.1.3b: Tests (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpToolTimeoutTest.kt`:
  build a bare `Server` via `McpAdapter`-style construction, register one slow handler
  through `addTimedTool(timeoutMs = 100L) { delay(5_000) }`, connect via
  `ChannelTransport`, call it, assert the response arrives well under 5s and contains
  the `Timeout` error content. Add a second test registering a handler that throws
  `IllegalStateException("boom")` directly (no `delay`), asserting the call returns
  `CallToolResult(isError = true, ...)` containing the `UnexpectedFailure` message
  (tool name + exception class name) instead of the test itself catching an
  unhandled exception from the `Server`/`ChannelTransport` call.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpToolTimeoutTest.kt`

### Epic 1.2: Local Socket Transport & Launcher Shim

**Goal**: Bridge the embedded `Server` (bound to a Unix-domain socket inside the
desktop app process) to MCP hosts' stdio-subprocess convention via a standalone
launcher binary — per ADR-001.

#### Story 1.2.1: `McpSocketServer` — bind the embedded `Server` to a local socket
**As a** SteleKit desktop app, **I want** the embedded MCP `Server` reachable over a
local, permission-restricted socket, **so that** an external launcher process can
connect to it without the desktop app itself being spawned by the MCP host.

**Acceptance Criteria**:
- `McpSocketServer.start()` binds a `UnixDomainSocketAddress` at
  `McpSocketPath.forGraph(graphId)` (`~/.stelekit/mcp/<graphId>.sock`), creating the
  parent directory with `0700` permissions if absent.
  - *Given* no `~/.stelekit/mcp/` directory exists, *When*
    `McpSocketServer(graphId = GraphId("g1")).start()` is called, *Then*
    `~/.stelekit/mcp/g1.sock` exists as a socket file and
    `Files.getPosixFilePermissions(Paths.get(System.getProperty("user.home"), ".stelekit", "mcp"))`
    returns exactly owner read/write/execute.
- `McpSocketServer.stop()` closes the listening channel and removes the socket file,
  idempotently.
  - *Given* a started `McpSocketServer`, *When* `.stop()` is called twice in a row,
    *Then* neither call throws and `~/.stelekit/mcp/g1.sock` no longer exists after
    the first call.
- A second simultaneous connection attempt, while one launcher-shim session is
  already active, is rejected immediately with a clear error — never queued, never
  left to hang (per `research/pitfalls.md` §3.4's hung-tool-call/no-cancel-path
  failure mode, which this must not reproduce for the *second* client the way
  anthropics/claude-code #44006/#63379 did for a stalled single client). A realistic
  trigger: the user has both Claude Desktop and Claude Code open against the same
  graph.
  - *Given* one `SocketChannel` already connected and mid-session, *When* a second
    `SocketChannel` connects to the same socket path, *Then* it receives a single
    JSON-RPC `{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"Another MCP client session is already connected to this graph"}}`
    frame and the channel is closed by the server within 2 seconds — the first
    session's in-flight session loop is completely unaffected.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketServer.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketPath.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpSocketServerTest.kt`

##### Task 1.2.1a: `McpSocketPath` helper (~3 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketPath.kt`: `object McpSocketPath { fun forGraph(graphId: GraphId): Path }`
  computing `~/.stelekit/mcp/<graphId.value>.sock`, creating `~/.stelekit/mcp/` with
  `PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))`
  if it doesn't exist.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketPath.kt`

##### Task 1.2.1b: `McpSocketServer` bind/accept loop (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketServer.kt`: `class McpSocketServer(private val graphId: GraphId, private val server: Server, private val mcpClientIdentity: McpClientIdentity)`
  with `suspend fun start()` opening a `ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(McpSocketPath.forGraph(graphId)))`,
  accepting one connection at a time in a loop on `Dispatchers.IO`, wrapping the
  accepted `SocketChannel`'s input/output in a transport, and calling
  `val session = server.createSession(transport)` (the SDK's session-creating,
  transport-connecting call — **not** the lower-level `session.connect(transport)`
  directly, since `createSession` is what returns the `ServerSession` this task needs)
  followed immediately by `session.onInitialized { mcpClientIdentity.onSessionInitialized(session) }`
  so `McpClientIdentity` (Task 1.1.2e) captures the connecting client's negotiated
  name as soon as the `initialize` handshake completes — this is the only point in the
  whole codebase where a `ServerSession` object (and therefore `clientVersion`) is
  reachable (adversarial-review Blocker fix, this repair pass). `fun stop()` closes
  the channel + deletes the socket file.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketServer.kt`

##### Task 1.2.1c: Bind/stop test (~4 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpSocketServerTest.kt`
  asserting the first two Acceptance Criteria above using a temp `user.home` (JUnit
  `@TempDir` + `System.setProperty` restored in a `finally`), constructing
  `McpSocketServer(graphId, server, McpClientIdentity())` (a fresh, unused identity
  instance — this test doesn't assert identity capture, Task 1.1.2f does).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpSocketServerTest.kt`

##### Task 1.2.1d: Reject a second simultaneous connection (~4 min)
- In `McpSocketServer`'s accept loop (Task 1.2.1b), track whether a session is
  currently active (e.g. a `Mutex`/`AtomicBoolean` guarding the accepted
  `SocketChannel`). When `accept()` returns a second channel while a session is
  already active, do **not** hand it to `Server.connect(...)` — instead write the
  JSON-RPC `-32000` "server busy" frame (raw bytes, the SDK's line/length framing
  convention — confirm exact framing from `research/stack.md` during implementation)
  directly to the new channel and close it, then immediately loop back to `accept()`
  for the *next* connection. The primary accept loop must never block on the rejected
  client.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketServer.kt`

##### Task 1.2.1e: Second-connection-rejected test (~4 min)
- Extend `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpSocketServerTest.kt`
  asserting the third Acceptance Criterion above: with one connection active, open a
  second `SocketChannel`, assert it receives the error frame and is closed within 2
  seconds, and assert the first session's `ChannelTransport` is still able to
  complete an unrelated `tools/list` call afterward (proves no cross-session
  interference).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpSocketServerTest.kt`

#### Story 1.2.2: `McpLauncherMain` — standalone stdio↔socket proxy binary
**As an** MCP host (Claude Desktop/Code), **I want** a small subprocess I can spawn via
stdio that transparently connects me to the running SteleKit app's MCP server, **so
that** I can use SteleKit's tools without SteleKit itself needing to be my subprocess.

**Acceptance Criteria**:
- `McpLauncherMain.main()` connects to the socket at `McpSocketPath.forGraph(graphId)`
  (graph id passed via `--graph-id` arg or `STELEKIT_MCP_GRAPH_ID` env var) and proxies
  bytes bidirectionally between `System.in`/`System.out` and the socket until either
  side closes.
  - *Given* a running `McpSocketServer` bound to `~/.stelekit/mcp/g1.sock`, *When*
    `McpLauncherMain.main(arrayOf("--graph-id", "g1"))` runs with a byte sequence
    written to its stdin, *Then* the same bytes arrive on the socket's read side
    (and vice versa for socket→stdout), verified by a subprocess-level test.
- If the socket doesn't exist (desktop app not running / MCP disabled), the launcher
  exits with a non-zero code and a one-line stderr message within 2 seconds — never
  hangs.
  - *Given* no `~/.stelekit/mcp/g1.sock` exists, *When* `McpLauncherMain.main(arrayOf("--graph-id", "g1"))`
    runs, *Then* it exits with code `1` and stderr contains `"SteleKit is not running or MCP is not enabled for graph g1"`
    within 2 seconds.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpLauncherMain.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpLauncherMainTest.kt`,
`kmp/build.gradle.kts`, `kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 1.2.2a: Launcher `main()` (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpLauncherMain.kt`
  modeled on `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncMain.kt`'s
  shape (root `Job()` + shutdown hook + `runBlocking`) but with **no**
  `GraphManager`/`PlatformFileSystem`/`PlatformSettings` construction — it only
  parses `--graph-id`, attempts `SocketChannel.open(StandardProtocolFamily.UNIX)` +
  `connect(McpSocketPath.forGraph(...))`, and on success starts two coroutines
  copying bytes each direction (`System.in`↔channel, channel↔`System.out`) until EOF.
- On connect failure, print the "SteleKit is not running..." message to stderr and
  `exitProcess(1)`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpLauncherMain.kt`

##### Task 1.2.2b: Gradle `runMcpLauncher` JavaExec task (~3 min)
- In `kmp/build.gradle.kts`, near the existing `runSync` task (around line 1056),
  register `tasks.register<JavaExec>("runMcpLauncher") { mainClass.set("dev.stapler.stelekit.mcp.McpLauncherMainKt"); javaLauncher.set(jdk21Launcher); ... }`,
  copying `runSync`'s classpath/launcher wiring exactly.
- Files: `kmp/build.gradle.kts`

##### Task 1.2.2c: Bazel `kt_jvm_binary` target for the launcher (~5 min)
- In `kmp/src/jvmMain/kotlin/BUILD.bazel`, add a new `kt_jvm_binary(name = "mcp_launcher", main_class = "dev.stapler.stelekit.mcp.McpLauncherMainKt", srcs = ["mcp/McpLauncherMain.kt"], deps = [...])`
  target — intentionally **not** depending on `jvm_main_lib` (avoids pulling in
  Compose/SQLDelight/the full app for a binary that must stay minimal per ADR-001).
- Run `bazel build //kmp/src/jvmMain/kotlin:mcp_launcher` and confirm it produces a
  runnable jar with no Compose/SQLDelight classes inside (`unzip -l` spot check).
- Files: `kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 1.2.2d: Connect-failure and byte-proxy tests (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpLauncherMainTest.kt`
  covering both Acceptance Criteria: (1) spin up a real `McpSocketServer` in-test,
  run the launcher against it, assert byte-for-byte proxying; (2) run the launcher
  with no server listening, assert exit code 1 and stderr content within 2s.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpLauncherMainTest.kt`

##### Task 1.2.2e: Self-contained launcher fat jar (~4 min)
- In `kmp/build.gradle.kts`, register a `Jar` task `mcpLauncherJar` that packages
  `McpLauncherMain`'s compiled output plus only the runtime dependencies it actually
  needs (`java.nio`/`kotlin-stdlib` — `McpLauncherMain` only opens a
  `SocketChannel` and copies bytes, per Task 1.2.2a's "no
  GraphManager/PlatformFileSystem/PlatformSettings" constraint, so it should need
  **no** `kotlin-sdk-server`/Ktor/SQLDelight dependency at all; confirm this during
  implementation and merge in `zipTree(...)` only whatever the compiler actually
  requires) into `build/mcp-launcher/stelekit-mcp-launcher.jar`, with
  `Main-Class: dev.stapler.stelekit.mcp.McpLauncherMainKt` in its manifest. This
  directly fixes the adversarial-review Minor noting that Task 1.2.2b's
  `runMcpLauncher` `JavaExec` task copies `runSync`'s full
  Compose/SQLDelight/app classpath "exactly" — that task remains fine for local dev
  (`./gradlew runMcpLauncher`), but this new `mcpLauncherJar` task is what ships,
  and it must stay minimal per ADR-001.
- Files: `kmp/build.gradle.kts`

##### Task 1.2.2f: Bundle the launcher jar into the packaged app (~4 min)
- In `kmp/build.gradle.kts`'s `compose.desktop.application.nativeDistributions`
  block (`kmp/build.gradle.kts:1013-1037`), set
  `appResourcesRootDir.set(layout.buildDirectory.dir("mcp-launcher"))` — Compose
  Desktop's documented mechanism for bundling arbitrary files into the installed
  app's resource directory, exposed at runtime via the
  `compose.application.resources.dir` system property (see the Unresolved Question
  added for this task — verify the exact resolved path on this repo's Compose Gradle
  plugin version before finalizing Task 4.1.2a's path-resolution logic). Make
  `createDistributable`/`packageDistributionForCurrentOS` depend on the new
  `mcpLauncherJar` task (`tasks.named("createDistributable") { dependsOn("mcpLauncherJar") }`)
  so the jar exists before packaging runs. Verify by running
  `./gradlew packageDistributionForCurrentOS` locally and confirming
  `stelekit-mcp-launcher.jar` exists inside the produced install's resource
  directory.
- Files: `kmp/build.gradle.kts`

---

## Phase 2: Read-Side Tools

### Epic 2.1: Search & Graph-Navigation Tools

**Goal**: Expose `search_graph` and `list_graphs`, the two tools that resolve the
"which graph, and what's in it" questions `research/features.md` §3 flags.

#### Story 2.1.1: `search_graph` tool
**As an** MCP client, **I want** to search pages and blocks by text/filters, **so
that** I can find relevant existing notes before proposing an edit or answering a
question about what's already written.

**Acceptance Criteria**:
- `search_graph(query, scope?, limit?, offset?)` wraps
  `SearchRepository.searchWithFilters(SearchRequest(query = query, scope = SearchScope.valueOf(scope ?: "ALL"), limit = min(limit ?: 20, 50), offset = offset ?: 0))`
  and returns a compact JSON projection (`uuid`, `name`/`snippet`, `score`) per hit,
  plus `hasMore`/`totalCount` from `SearchResult`.
  - *Given* a graph containing a page named `"Zettelkasten Method"` with body text
    "atomic notes", *When* `search_graph(query = "atomic notes")` is called, *Then*
    the tool result's `ranked` array contains a `PageHit` for `"Zettelkasten Method"`
    and the top-level result includes `"hasMore": false`.
- A `limit` above 50 is clamped to 50 server-side, never passed through to the
  repository unclamped (per `CLAUDE.md`'s bounded-read rule and pitfalls §3.3).
  - *Given* an MCP client calls `search_graph(query = "x", limit = 500)`, *When* the
    handler executes, *Then* the repository call receives `limit = 50`, not `500`.
- No graph open returns `McpToolError.GraphNotOpen`, not an exception/hang.
  - *Given* `GraphManager.getActiveRepositorySet()` returns `null`, *When*
    `search_graph(query = "x")` is called, *Then* the tool returns
    `CallToolResult(isError = true, ...)` with the `GraphNotOpen` message, and the
    call returns within the test's timeout (no hang).

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/SearchGraphTool.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/SearchGraphToolTest.kt`

##### Task 2.1.1a: `SearchGraphTool` handler (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/SearchGraphTool.kt`:
  `fun registerSearchGraphTool(server: Server, graphManager: GraphManager)` calling
  `server.addTimedTool("search_graph", description = "Search pages and blocks by text. Read-only.", inputSchema = ...) { request -> ... }`
  (Story 1.1.3's timeout-wrapped registration — never the SDK's raw `addTool`).
  Handler resolves `graphManager.getActiveRepositorySet()`, returns
  `McpToolError.GraphNotOpen.toCallToolResult()` if null; otherwise calls
  `searchRepository.searchWithFilters(...).first()`, clamps `limit`, maps
  `Either.Left` to `McpToolError.Internal(it).toCallToolResult()`, maps
  `Either.Right` to a JSON `CallToolResult`.
- Set `annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false)`
  per pitfalls §3.1's annotation-accuracy guidance.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/SearchGraphTool.kt`

##### Task 2.1.1b: Wire into `McpAdapter` (~2 min)
- In `McpAdapter.buildServer()`, call `registerSearchGraphTool(server, graphManager)`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`

##### Task 2.1.1c: Tests for the three Acceptance Criteria (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/SearchGraphToolTest.kt`
  using an in-memory fake `RepositorySet`/`SearchRepository` (existing test fakes —
  `Glob` for `Fake*Repository` under `kmp/src/commonMain`/`businessTest` first) plus
  `ChannelTransport`, asserting: hit returned, limit clamped to 50, `GraphNotOpen`
  returned when `getActiveRepositorySet()` is null.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/SearchGraphToolTest.kt`

#### Story 2.1.2: `list_graphs` tool
**As an** MCP client, **I want** to see which graphs are registered and which is
active, **so that** I know what "the graph" refers to before calling any other tool
(resolves `research/features.md` §3's multi-graph ambiguity for a UI-less client).

**Acceptance Criteria**:
- `list_graphs()` returns every `GraphInfo` in `GraphManager`'s `GraphRegistry.graphs`
  with an `isActive` flag matching `activeGraphId`.
  - *Given* `GraphManager` has two registered graphs `g1` ("Personal") and `g2`
    ("Work"), with `g1` active, *When* `list_graphs()` is called, *Then* the result
    contains two entries and `g1`'s entry has `"isActive": true`, `g2`'s has
    `"isActive": false`.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ListGraphsTool.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ListGraphsToolTest.kt`

##### Task 2.1.2a: `ListGraphsTool` handler + registration (~4 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ListGraphsTool.kt`:
  `fun registerListGraphsTool(server: Server, graphManager: GraphManager)`, registered
  via `server.addTimedTool(...)` (Story 1.1.3), no input schema fields required,
  reads `graphManager.graphRegistry.value` (or equivalent public accessor — confirm
  exact field name in `GraphManager.kt` during implementation) synchronously (no DB
  access, this is in-memory registry state). `annotations = ToolAnnotations(readOnlyHint = true)`.
- Wire into `McpAdapter.buildServer()`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ListGraphsTool.kt`,
  `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`

##### Task 2.1.2b: Test (~3 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ListGraphsToolTest.kt`
  asserting the Acceptance Criterion above against a `GraphManager` with two
  registered test graphs.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ListGraphsToolTest.kt`

### Epic 2.2: Page & Backlink Read Tools

#### Story 2.2.1: `read_page` tool
**As an** MCP client, **I want** to read a page's full block content by name or UUID,
**so that** I can ground a research/writing task in what's already written on that
page.

**Acceptance Criteria**:
- Exact `uuid` lookup uses `PageRepository.getPageByUuid`; `name` lookup uses
  `PageRepository.getPageByName` **only when exactly one match exists** — otherwise
  returns `McpToolError.AmbiguousPageName` listing candidate names, never guessing
  (per `research/features.md` §3's "don't assume exact-match-returns-one" finding).
  - *Given* two pages named `"Project Ideas"` and `"project ideas"` both exist (case
    variants), *When* `read_page(name = "Project Ideas")` is called and
    `getPageByName` is confirmed case-sensitive-exact, *Then* if both are returned by
    a disambiguation pre-check, the tool returns `McpToolError.AmbiguousPageName("Project Ideas", 2)`
    rather than picking one silently. Also applies across sections: `pages.name` is
    only unique per `section_id` (`SteleDatabase.sq:19`), so two identically-named
    pages in different sections must trigger the same `AmbiguousPageName` result.
- Blocks are paginated: `read_page(uuid = "...", blockLimit = 50, blockOffset = 0)`
  returns at most 50 blocks plus a `hasMoreBlocks` boolean, never the full tree for a
  pathologically large page (per pitfalls §3.3 / `research/features.md` §4's
  block-level-pagination gap).
  - *Given* a page with 300 blocks, *When* `read_page(uuid = "p1", blockLimit = 50)`
    is called, *Then* the result contains exactly 50 blocks and
    `"hasMoreBlocks": true`.
- Journal pages are looked up via `getJournalPageByDate`, never string-matched by
  name, when a `date` parameter is supplied instead of `name`/`uuid` (per
  `research/features.md` §3's journal-name-format trap).
  - *Given* today's journal page exists on disk as `2026_09_14` but was created
    in-app and would render as `2026-09-14`, *When* `read_page(date = "2026-09-14")`
    is called, *Then* the tool calls `PageRepository.getJournalPageByDate(LocalDate(2026, 9, 14))`
    and returns the page regardless of its on-disk name format.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageTool.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageToolTest.kt`

##### Task 2.2.1a: Verify page-name-uniqueness assumption (~3 min)
- Confirmed directly: `SteleDatabase.sq:19` has `UNIQUE(name, section_id)` (`name` is
  `COLLATE NOCASE`, line 6) — uniqueness is scoped **per section, not global**. Two
  pages with the same name in different sections are legal (sections are a live
  feature per ADR-014). Record this exact finding (not a plain yes/no) in a one-line
  code comment at the top of `ReadPageTool.kt`, and treat Task 2.2.1c's
  disambiguation branch as **unconditional** — a bare `getPageByName` call is never
  safe on its own, since a global name match can span multiple sections.
- Files: none (research step, informs Task 2.2.1c)

##### Task 2.2.1b: `read_page` handler skeleton (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageTool.kt`:
  `fun registerReadPageTool(server: Server, graphManager: GraphManager)`, registered
  via `server.addTimedTool(...)` (Story 1.1.3), accepting
  `uuid?`, `name?`, `date?`, `blockLimit = 50`, `blockOffset = 0` as input schema
  fields (exactly one of `uuid`/`name`/`date` required — validate and return a
  `CallToolResult(isError = true, ...)` if zero or multiple are supplied). Extract the
  uuid/name resolution branch into an `internal` function reusable by Story 2.3.1's
  `PageResource` (both need identical disambiguation logic — do not duplicate it).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageTool.kt`

##### Task 2.2.1c: Name-disambiguation + journal-date branches (~5 min)
- Implement the `uuid` branch (`getPageByUuid`), the `date` branch
  (`getJournalPageByDate`), and the `name` branch: since name uniqueness is only
  per-section (Task 2.2.1a), always precede `getPageByName` with a bounded
  `searchPagesByTitle(name, limit = 5)` check and return
  `McpToolError.AmbiguousPageName` when more than one case-insensitive match exists
  across any section — never skip this check based on a global-uniqueness
  assumption.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageTool.kt`

##### Task 2.2.1d: Block pagination (~4 min)
- After resolving the `Page`, call `BlockReadRepository.getBlocksForPage(pageUuid).first()`,
  slice to `[blockOffset, blockOffset + blockLimit)`, and set
  `hasMoreBlocks = blocks.size > blockOffset + blockLimit`.
- Wire into `McpAdapter.buildServer()`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageTool.kt`,
  `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`

##### Task 2.2.1e: Tests for all three Acceptance Criteria (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageToolTest.kt`
  covering ambiguous-name, block-pagination, and journal-date-format-agnostic cases
  against fake repositories.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageToolTest.kt`

#### Story 2.2.2: `list_backlinks` tool
**As an** MCP client, **I want** to see which blocks link to a given page, **so that**
I can understand a page's context before proposing related edits.

**Acceptance Criteria**:
- `list_backlinks(pageName, limit?, offset?)` wraps
  `BlockSearchRepository.getLinkedReferences(pageName, limit ?: 50, offset ?: 0)` —
  **semantic `[[wiki-link]]` references, not child blocks** (per
  `research/features.md` §3's outline-vs-reference-link disambiguation finding); the
  tool description text says so explicitly.
  - *Given* a page `"Zettelkasten Method"` is `[[wiki-linked]]` from a block on page
    `"Reading Notes"`, *When* `list_backlinks(pageName = "Zettelkasten Method")` is
    called, *Then* the result includes that block from `"Reading Notes"`, and the
    tool's registered `description` string contains the phrase "wiki-link
    references" (not "child blocks" or "outline structure").

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ListBacklinksTool.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ListBacklinksToolTest.kt`

##### Task 2.2.2a: `ListBacklinksTool` handler + registration (~4 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ListBacklinksTool.kt`:
  `fun registerListBacklinksTool(server: Server, graphManager: GraphManager)`,
  registered via `server.addTimedTool(...)` (Story 1.1.3), calling
  `blockSearchRepository.getLinkedReferences(pageName, limit, offset).first()`,
  clamping `limit` to ≤50, `annotations = ToolAnnotations(readOnlyHint = true)`.
- Wire into `McpAdapter.buildServer()`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ListBacklinksTool.kt`,
  `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`

##### Task 2.2.2b: Test (~3 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ListBacklinksToolTest.kt`
  asserting the Acceptance Criterion above.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ListBacklinksToolTest.kt`

### Epic 2.3: Page Resources (`requirements.md`'s "Resources" in-scope item)

**Goal**: Close the gap the adversarial review flagged — `requirements.md`'s In Scope
(v1) list names "Resources: individual pages addressable by name/UUID for direct
reads" as a distinct deliverable from the tool list, and `research/stack.md`'s
"Artifacts and modeling" section maps it onto the MCP `resources/read` capability
(`server.addResource`), not a tool call. This epic adds that capability as a thin
wrapper over Story 2.2.1's existing resolution logic — not a second data-access path.

#### Story 2.3.1: `note://<uuid>` MCP Resource for direct page reads
**As an** MCP client that prefers resource-addressing over tool calls for a known
page, **I want** to fetch a page's content via `resources/read` on a `note://<uuid>`
URI, **so that** I can treat a SteleKit page like any other MCP resource (e.g. to
attach it to a prompt) without needing to know it's implemented as a "tool."

**Acceptance Criteria**:
- `resources/read` on `note://<uuid>` resolves via the same uuid-resolution function
  `read_page` uses (Task 2.2.1b's extracted resolver) and returns a
  `ReadResourceResult` whose `contents` is one `TextResourceContents(uri, mimeType = "text/markdown", text = ...)`
  rendering the page's blocks — reusing `read_page`'s block-pagination defaults
  (first 50 blocks), never a second, divergent read path.
  - *Given* a page `"Zettelkasten Method"` with uuid `p1`, *When*
    `resources/read` is called with uri `note://p1`, *Then* the returned text content
    matches what `read_page(uuid = "p1")` would return for the same page.
- `resources/list` always returns an empty `resources` array and performs **zero**
  repository calls — pages are addressed directly via `note://<uuid>`, never
  enumerated wholesale, per CLAUDE.md's graph-scale-reads rule (no
  `getAllPages()`-style unbounded read). A `resources/templates/list` call instead
  publishes one template, `note://{uuid}`, so clients know the addressing scheme
  without the server ever walking the full page table.
  - *Given* a graph with 8 000 pages, *When* `resources/list` is called, *Then* the
    response is `resources: []` and a recording fake `PageRepository` shows zero
    calls; *When* `resources/templates/list` is called, *Then* it returns exactly one
    template with `uriTemplate == "note://{uuid}"`.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/resources/PageResource.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageTool.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/resources/PageResourceTest.kt`

##### Task 2.3.1a: Share `ReadPageTool`'s uuid resolver (~3 min)
- In `ReadPageTool.kt`, confirm/finish the `internal suspend fun resolvePageByUuid(graphManager: GraphManager, uuid: String): Either<McpToolError, Page>`
  extraction started in Task 2.2.1b, so `PageResource` can call the exact same
  function `read_page`'s `uuid` branch uses — no duplicated lookup logic.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ReadPageTool.kt`

##### Task 2.3.1b: `PageResource` handler + `resources` capability (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/resources/PageResource.kt`:
  `fun registerPageResource(server: Server, graphManager: GraphManager)` calling
  `server.addResourceTemplate(uriTemplate = "note://{uuid}", name = "SteleKit page", description = "A single SteleKit page by UUID. Use read_page to search by name.", mimeType = "text/markdown") { request -> ... }`
  parsing the `uuid` from the request URI, delegating to Task 2.3.1a's resolver, and
  returning `ReadResourceResult`. Register with an explicit empty `resources/list`
  handler (or confirm the SDK returns empty by default when only templates are
  registered — verify against `research/stack.md` during implementation). In
  `McpAdapter.buildServer()` (already declares the `resources` capability per Task
  1.1.2c), call `registerPageResource(server, graphManager)`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/resources/PageResource.kt`,
  `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`

##### Task 2.3.1c: Tests for both Acceptance Criteria (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/resources/PageResourceTest.kt`
  asserting: `note://<uuid>` content matches `read_page`'s output for the same page;
  `resources/list` returns empty with a recording fake `PageRepository` showing zero
  calls; `resources/templates/list` returns the one `note://{uuid}` template.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/resources/PageResourceTest.kt`

---

## Phase 3: Approval-Gated Write Tools (Epic 7 Reuse)

### Epic 3.1: Propose-Write Tools

**Goal**: Let an MCP client queue a block edit or new page as a `PendingLlmSuggestion`
via the existing, unmodified `LlmSuggestionInbox` — never a direct write (ADR-002).

#### Story 3.1.1: `propose_block_edit` tool
**As an** MCP client, **I want** to propose a change to an existing block's content,
**so that** the human can review and accept it in the SteleKit UI without me ever
writing to the graph directly.

**Acceptance Criteria**:
- The tool reads the block's **current committed content** via
  `BlockReadRepository.getBlockByUuid(blockUuid).first()` at propose time (never trusts
  a client-supplied "current content" string) and uses it as
  `PendingLlmSuggestion.BlockEdit.currentContentSnapshot`.
  - *Given* a block `b1` with live content `"Original text"`, *When*
    `propose_block_edit(pageUuid = "p1", blockUuid = "b1", newContent = "Edited text")`
    is called, *Then* the constructed `PendingLlmSuggestion.BlockEdit` has
    `currentContentSnapshot == "Original text"` (read fresh from the repository, not
    echoed from any client-supplied value).
- The tool never calls `DatabaseWriteActor`, `GraphWriter`, or any
  `@DirectRepositoryWrite`-annotated method, and never touches a raw
  `LlmSuggestionInbox` reference — only `McpPendingCapGuard.proposeIfUnderCap(...)`
  (Story 3.2.1's `Mutex`-guarded wrapper, which internally calls
  `McpSuggestionSink.propose(...)`), which in production is backed by
  `ViewModelSuggestionSink` delegating to `StelekitViewModel.proposeLlmSuggestion(...)`
  (the same gated call every other suggestion producer uses). There is no separate,
  unguarded `suggestionSink.propose(...)` call anywhere in this tool's handler.
  - *Given* a test double for `DatabaseWriteActor`/`GraphWriterPort` that records
    every call, *When* `propose_block_edit(...)` is called and completes
    successfully, *Then* the recorder has zero recorded calls, and
    `suggestionSink.pending.value` contains exactly one new entry.
- `sourceProviderId` follows the `"mcp:<client-name>:<proposal-id>"` convention from
  ADR-002 (`<client-name>` from `mcpClientName()`, `<proposal-id>` a fresh
  `UuidGenerator.generateV7()` generated at propose time — **not** a literal MCP
  call id, since `CallToolRequest` exposes no such field, verified against
  `kotlin-sdk-server:0.15.0`; see the resolved Unresolved Question above), and
  `graphId` is populated from `graphManager.getActiveGraphId()`.
  - *Given* the active graph is `"g1"` and `mcpClientName()` resolves to
    `"claude-desktop"` (captured from the connected session per Task 1.1.2e/1.2.1b),
    *When* `propose_block_edit(...)` is called, *Then* the stored suggestion has
    `sourceProviderId` matching `"mcp:claude-desktop:<uuid>"` (a well-formed UUID as
    the third segment) and `graphId == "g1"`.
- No graph open returns `McpToolError.GraphNotOpen`.
  - *Given* `graphManager.getActiveGraphId()` returns `null`, *When*
    `propose_block_edit(...)` is called, *Then* the tool returns
    `McpToolError.GraphNotOpen.toCallToolResult()` and `suggestionSink.pending`
    stays unchanged.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeBlockEditTool.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ProposeBlockEditToolTest.kt`

##### Task 3.1.1a: `ProposeBlockEditTool` handler (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeBlockEditTool.kt`:
  `fun registerProposeBlockEditTool(server: Server, graphManager: GraphManager, pendingCapGuard: McpPendingCapGuard, mcpClientName: () -> String)`
  (takes the `McpPendingCapGuard` — Story 3.2.1 — instead of a raw `McpSuggestionSink`,
  so the cap check and the propose call can never be split into two separate,
  unguarded steps), registered via `server.addTimedTool(...)` (Story 1.1.3). Handler
  resolves the active graph/repository set (returning `McpToolError.GraphNotOpen` via
  an early guard, before any `!!`, if it's `null`), reads the live block via
  `blockRepository.getBlockByUuid(BlockUuid(blockUuid)).first()`, then calls
  `pendingCapGuard.proposeIfUnderCap { PendingLlmSuggestion.BlockEdit(id = UuidGenerator.generateV7(), graphId = activeGraphId.value, sourceProviderId = "mcp:${mcpClientName()}:${UuidGenerator.generateV7()}", proposedAtEpochMs = Clock.System.now().toEpochMilliseconds(), rationale = args.rationale, pageUuid = args.pageUuid, blockUuid = args.blockUuid, currentContentSnapshot = liveBlock.content, proposedContent = args.newContent) }`,
  mapping a `Left(McpToolError.InboxFull)` result to
  `McpToolError.InboxFull(MCP_PENDING_CAP).toCallToolResult()`. `mcpClientName` is the
  session-captured accessor from `McpClientIdentity` (Task 1.1.2e) — **not** a literal
  per-call MCP client id (unavailable, see the resolved Unresolved Question above), and
  the suggestion's `sourceProviderId` third segment is a freshly generated
  `UuidGenerator.generateV7()`, not `request.callId` (`CallToolRequest` has no such
  field). Never touches a raw `LlmSuggestionInbox`, and never an unguarded
  `suggestionSink.propose(...)` call outside `pendingCapGuard`.
- Set `annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false)`
  per pitfalls §3.1 (it never mutates directly, so not `destructiveHint`, but not
  `readOnlyHint` either) and a tool description stating explicitly: "Queues a
  suggested edit for human review in SteleKit. Does not modify the graph." (pitfalls
  §3.2's naming/description guidance).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeBlockEditTool.kt`

##### Task 3.1.1b: Wire into `McpAdapter` (~2 min)
- Add `registerProposeBlockEditTool(server, graphManager, pendingCapGuard, mcpClientName)`
  call in `McpAdapter.buildServer()`, where `pendingCapGuard = McpPendingCapGuard(suggestionSink)`
  is constructed once in `buildServer()` (shared with Task 3.1.2b's
  `registerProposeNewPageTool` call so both propose tools enforce the cap against the
  same `Mutex`) from the `suggestionSink: McpSuggestionSink` constructor param
  `McpAdapter` already holds, and `mcpClientName` is the constructor param added in
  Task 1.1.2c — no new external dependency, both are built/held by `McpAdapter` itself.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`

##### Task 3.1.1c: Tests for all four Acceptance Criteria (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ProposeBlockEditToolTest.kt`
  with a fake `BlockRepository` returning a known live block, a test
  `McpSuggestionSink` implementation backed by a real `LlmSuggestionInbox` wrapped in a
  real `McpPendingCapGuard` (so the assertions exercise real inbox + cap-guard
  semantics without constructing a full `StelekitViewModel`), a
  `mcpClientName = { "claude-desktop" }` fake, and a recording fake
  `DatabaseWriteActor`/`GraphWriterPort` to assert zero write calls. Assert the
  `sourceProviderId` regex match (`"mcp:claude-desktop:<uuid>"`) from the third AC.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ProposeBlockEditToolTest.kt`

#### Story 3.1.2: `propose_new_page` tool
**As an** MCP client, **I want** to propose creating a new page with a block tree,
**so that** I can capture research/synthesis into the graph without writing directly.

**Acceptance Criteria**:
- Constructs `PendingLlmSuggestion.NewPage(proposedTitle, proposedBlocks = args.blocks.map { ProposedBlock(it.content, it.depth, it.order) }, ...)`
  and calls `McpPendingCapGuard.proposeIfUnderCap { ... }` (same shared-`Mutex` guard
  as Story 3.1.1, same `mcpClientName`/fresh-UUID `sourceProviderId` convention — see
  Story 3.1.1's resolved AC) — same zero-direct-write, no-raw-inbox, no-unguarded-propose
  guarantee as Story 3.1.1.
  - *Given* an MCP call `propose_new_page(title = "Research: X", blocks = [{content: "Summary", depth: 0, order: 0}, {content: "Detail", depth: 1, order: 1}], rationale = "captured from conversation")`,
    *When* the handler completes, *Then* `suggestionSink.pending.value` contains
    one `PendingLlmSuggestion.NewPage` with `proposedTitle == "Research: X"` and two
    `proposedBlocks`, and the recording write-call fake from Story 3.1.1's harness
    still shows zero calls.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeNewPageTool.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ProposeNewPageToolTest.kt`

##### Task 3.1.2a: `ProposeNewPageTool` handler + registration (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeNewPageTool.kt`:
  `fun registerProposeNewPageTool(server: Server, graphManager: GraphManager, pendingCapGuard: McpPendingCapGuard, mcpClientName: () -> String)`,
  mirroring Task 3.1.1a's shape (`server.addTimedTool(...)` registration, same
  `McpPendingCapGuard.proposeIfUnderCap { ... }` call — never a raw `McpSuggestionSink.propose(...)`,
  never a raw `LlmSuggestionInbox` — same `mcpClientName`/fresh-`UuidGenerator.generateV7()`
  `sourceProviderId` convention, same `McpToolError.GraphNotOpen` guard applied
  *before* any `!!`, same tool-annotation/description discipline). Wire into
  `McpAdapter.buildServer()`, passing the same `pendingCapGuard` instance
  Task 3.1.1b constructs (shared `Mutex` across both propose tools).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeNewPageTool.kt`,
  `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt`

##### Task 3.1.2b: Test (~4 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ProposeNewPageToolTest.kt`
  asserting the Acceptance Criterion above.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/tools/ProposeNewPageToolTest.kt`

### Epic 3.2: Approval-Fatigue Guardrail (ADR-002)

#### Story 3.2.1: Per-MCP-source pending cap
**As a** SteleKit user, **I want** an MCP client to be refused once it has 20 pending
suggestions of its own awaiting my review, **so that** one noisy MCP conversation
can't silently bury my review queue or crowd out the page-scan matcher's suggestions.

**Acceptance Criteria**:
- Both `propose_block_edit` and `propose_new_page` route through a single, shared
  `McpPendingCapGuard.proposeIfUnderCap { ... }` call — never a bare count-then-propose
  pair of separate statements — so the count of existing
  `sourceProviderId.startsWith("mcp:")` entries and the `propose()` call itself happen
  inside one `Mutex.withLock { ... }` critical section; at `MCP_PENDING_CAP` (20) it
  returns `Left(McpToolError.InboxFull(20))` instead of proposing.
  - *Given* `suggestionSink.pending` already holds 20 suggestions with
    `sourceProviderId` starting `"mcp:"` and 5 with `"aho-corasick-matcher"`, *When*
    `propose_block_edit(...)` is called again, *Then* it returns
    `McpToolError.InboxFull(20).toCallToolResult()` and `suggestionSink.pending.value.size`
    stays at 25 (unchanged) — the matcher's 5 entries are unaffected and would not
    have blocked a 21st MCP proposal either way (proving the cap is per-source, not
    global).
- The check-then-propose sequence is race-free under concurrent/pipelined calls (the
  architecture-review Concern this fixes: JSON-RPC legally allows a client to send
  multiple `tools/call` requests without awaiting each response, so N concurrent
  handler invocations could otherwise all observe a stale under-cap count before any
  commits).
  - *Given* the pending count is at `MCP_PENDING_CAP - 1` (19), *When* two
    `propose_block_edit(...)` calls are launched concurrently (e.g. both started via
    `async { }` before either completes, against the same `McpPendingCapGuard`
    instance), *Then* exactly one succeeds (bringing the count to 20) and the other
    returns `McpToolError.InboxFull(20).toCallToolResult()` — the count never reaches
    21, which a non-atomic check-then-act implementation could allow.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpPendingCap.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeBlockEditTool.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeNewPageTool.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpPendingCapTest.kt`

##### Task 3.2.1a: `McpPendingCap`/`McpPendingCapGuard` (~4 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpPendingCap.kt`:
  `const val MCP_PENDING_CAP = 20`,
  `private fun McpSuggestionSink.mcpPendingCount(): Int = pending.value.values.count { it.sourceProviderId.startsWith("mcp:") }`
  (kept `private` — Task 3.2.1b's original plan exposed this as a standalone
  extension callers could read separately from the propose call, which is exactly the
  non-atomic check-then-act pattern this task exists to remove), and
  `class McpPendingCapGuard(private val sink: McpSuggestionSink) { private val mutex = Mutex(); suspend fun proposeIfUnderCap(build: () -> PendingLlmSuggestion): Either<McpToolError.InboxFull, Unit> = mutex.withLock { if (sink.mcpPendingCount() >= MCP_PENDING_CAP) McpToolError.InboxFull(MCP_PENDING_CAP).left() else { sink.propose(build()); Unit.right() } } }`.
  One `McpPendingCapGuard` instance is constructed per `McpAdapter` (Task 3.1.1b) and
  shared by both propose tools, so the same `Mutex` serializes both — a per-tool
  `Mutex` would not close the race between `propose_block_edit` and `propose_new_page`
  racing each other.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpPendingCap.kt`

##### Task 3.2.1b: Enforce in both propose tools (~2 min)
- `ProposeBlockEditTool.kt` and `ProposeNewPageTool.kt` (Tasks 3.1.1a/3.1.2a) already
  call `pendingCapGuard.proposeIfUnderCap { ... }` instead of a raw
  `suggestionSink.propose(...)` — this task is the cross-check that no call site in
  either file constructs a `PendingLlmSuggestion` and calls `.propose(...)`/`.mcpPendingCount()`
  directly, outside the guard (`Grep` both files for `suggestionSink.propose(` and
  `mcpPendingCount(` — the only legitimate use of the latter is inside
  `McpPendingCapGuard` itself).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeBlockEditTool.kt`,
  `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/tools/ProposeNewPageTool.kt`

##### Task 3.2.1c: Tests (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpPendingCapTest.kt`
  asserting the first Acceptance Criterion (pre-seed 20 `mcp:`-sourced + 5
  matcher-sourced entries, assert 21st MCP call refused, size stays 25) directly
  against `McpPendingCapGuard`. Add a second test for the concurrency Acceptance
  Criterion: pre-seed to 19, launch two concurrent `proposeIfUnderCap { ... }` calls
  against one shared `McpPendingCapGuard` instance (e.g. both started via `async { }`
  before either completes), assert exactly one `Left(InboxFull)` and one
  `Right(Unit)`, and assert the final pending count is exactly 20, never 21.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpPendingCapTest.kt`

#### Story 3.2.2: Sidebar pending-MCP-suggestion badge
**As a** SteleKit user, **I want** a visible count when MCP-sourced suggestions are
pending and I've dismissed the review screen, **so that** I don't forget they're
waiting (closes the `research/ux.md` §2 "MCP client thinks its work vanished" gap).

**Acceptance Criteria**:
- `AppState.pendingMcpSuggestionCount: Int` is derived from
  `LlmSuggestionInbox.pending` filtered to `sourceProviderId.startsWith("mcp:")`, and
  the sidebar renders a badge with that count when `> 0`.
  - *Given* `LlmSuggestionInbox` holds 3 `mcp:`-sourced and 2 matcher-sourced pending
    suggestions, *When* the sidebar recomposes, *Then* it shows a badge reading `"3"`
    (not `"5"`), and the badge is absent when the count is `0`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (exact
sidebar file — confirm via `Glob` for `*Sidebar*.kt` before editing),
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/StelekitViewModelLlmSuggestionTest.kt`

##### Task 3.2.2a: `AppState` field + `StelekitViewModel` derivation (~4 min)
- Add `pendingMcpSuggestionCount: Int = 0` to `AppState` (near the existing
  `llmSuggestionReviewVisible: Boolean = false` at `AppState.kt:168`). In
  `StelekitViewModel`'s existing `llmSuggestionInbox.pending.collect { pending -> ... }`
  block (around line 426), also update
  `_uiState.update { it.copy(pendingMcpSuggestionCount = pending.values.count { s -> s.sourceProviderId.startsWith("mcp:") }) }`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`,
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 3.2.2b: Sidebar badge composable (~4 min)
- Locate the sidebar composable (`Glob` `**/Sidebar*.kt` under
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/`) and add a small badge
  `Text`/`Badge` next to the existing settings/notifications affordance, visible only
  when `uiState.pendingMcpSuggestionCount > 0`, following `HostReconnectBanner.kt`'s
  `.semantics { contentDescription = ... }` pattern for accessibility (per
  `research/ux.md` §3's gap note).
- Files: sidebar composable file (confirmed via Glob)

##### Task 3.2.2c: Test (~3 min)
- Extend `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/StelekitViewModelLlmSuggestionTest.kt`
  (or add a new test in the same package) asserting `pendingMcpSuggestionCount`
  reflects only `mcp:`-prefixed entries per the Acceptance Criterion.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/StelekitViewModelLlmSuggestionTest.kt`

##### Task 3.2.2d: Wire badge click to open the review screen — triad-review UX blocker fix
- `design/ux.md` Surface C's interaction flow (step 2) specifies tapping the badge
  opens `LlmSuggestionReviewScreen` — Task 3.2.2b only rendered the badge's count,
  with no click handler, which defeats the badge's stated purpose (closing the "MCP
  client thinks its work vanished" gap only works if tapping it actually surfaces the
  suggestions). Add an `onClick = { viewModel.openLlmSuggestionReviewScreen() }` (or
  the existing equivalent call already used to show `LlmSuggestionReviewScreen` from
  elsewhere in the sidebar/UI — `Grep` for how the review screen is currently opened,
  e.g. `llmSuggestionReviewVisible = true`, and reuse that exact call, don't invent a
  second entry point) to the badge composable from Task 3.2.2b.
  - *Given* the sidebar badge showing `"3"`, *When* the user taps it, *Then*
    `uiState.value.llmSuggestionReviewVisible == true` (the same state
    `LlmSuggestionReviewScreen`'s existing visibility already keys off).
- Files: sidebar composable file (same as Task 3.2.2b)

#### Story 3.2.3: MCP provenance chip in `LlmSuggestionReviewScreen`
**As a** SteleKit user reviewing pending suggestions, **I want** to see which ones
came from an external MCP client vs. SteleKit's own tag-suggestion matcher, **so
that** I apply appropriately more scrutiny to externally-sourced proposals (closes
`design/ux.md` Surface B, UX Acceptance Criteria #4 and #10 — triad-review UX
blocker fix; the underlying `sourceProviderId` data convention was already planned
in Story 1.1.2/ADR-002, but nothing rendered it).

**Acceptance Criteria**:
- Each item in `LlmSuggestionReviewScreen`'s list renders a small chip/label when
  `suggestion.sourceProviderId.startsWith("mcp:")`, reading `"MCP: <client-name>"`
  (parsed from the `"mcp:<client-name>:<proposal-id>"` convention — split on `:`,
  take the second segment), with a `.semantics { contentDescription = "Proposed by
  MCP client <client-name>" }` for screen readers; items without an `"mcp:"` prefix
  render no chip (unchanged from today).
  - *Given* two pending suggestions, one with `sourceProviderId =
    "mcp:claude-desktop:<uuid>"` and one with `sourceProviderId =
    "aho-corasick-matcher"`, *When* `LlmSuggestionReviewScreen` renders both, *Then*
    only the first shows a chip reading `"MCP: claude-desktop"`, and the second shows
    no chip.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreen.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreenMcpChipTest.kt`

##### Task 3.2.3a: Chip composable + parsing helper (~4 min)
- In `LlmSuggestionReviewScreen.kt`, add a small private
  `parseMcpClientName(sourceProviderId: String): String?` (returns the second
  `:`-delimited segment when the string starts with `"mcp:"`, else `null`) and a
  `McpProvenanceChip(clientName: String)` composable (small `AssistChip`/`Text` in a
  rounded `Surface`, following whatever chip pattern already exists elsewhere in this
  screen or codebase — `Grep` for `AssistChip`/`SuggestionChip` first before adding a
  new visual primitive), rendered per-item when `parseMcpClientName(...) != null`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreen.kt`

##### Task 3.2.3b: Test (~3 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreenMcpChipTest.kt`
  asserting the chip renders only for `"mcp:"`-prefixed entries and shows the correct
  parsed client name, per the Acceptance Criterion.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreenMcpChipTest.kt`

---

## Phase 4: Settings Toggle & Lifecycle Wiring

### Epic 4.1: Off-By-Default Enablement

**Goal**: Make the entire feature reachable only through an explicit, off-by-default
Settings toggle — the mechanism that resolves Open Question 1 (ADR-001) and is this
plan's Risk Control feature flag.

#### Story 4.1.1: `mcpServerEnabled` setting
**As a** SteleKit user, **I want** the MCP server off unless I explicitly turn it on,
**so that** the app has zero new runtime behavior by default.

**Acceptance Criteria**:
- `AppState.mcpServerEnabled: Boolean` defaults to `false`, backed by
  `platformSettings.getBoolean("mcp.server.enabled", false)`, mirroring
  `isLibsqlDriverEnabled`'s exact wiring shape.
  - *Given* a fresh install with no prior `PlatformSettings` value for
    `"mcp.server.enabled"`, *When* `StelekitViewModel` initializes `_uiState`, *Then*
    `uiState.value.mcpServerEnabled == false`.
- `StelekitViewModel.setMcpServerEnabled(true)` persists via
  `platformSettings.putBoolean("mcp.server.enabled", true)` and updates
  `_uiState.value.mcpServerEnabled`.
  - *Given* `uiState.value.mcpServerEnabled == false`, *When*
    `viewModel.setMcpServerEnabled(true)` is called, *Then*
    `platformSettings.getBoolean("mcp.server.enabled", false) == true` and
    `uiState.value.mcpServerEnabled == true`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 4.1.1a: `AppState` field (~2 min)
- Add `mcpServerEnabled: Boolean = false` to `AppState` next to
  `isLibsqlDriverEnabled: Boolean = false` (`AppState.kt:130`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`

##### Task 4.1.1b: `StelekitViewModel` init read + setter (~4 min)
- In the `_uiState` initializer near `isLibsqlDriverEnabled = platformSettings.getBoolean("db.libsql.enabled", false)`
  (`StelekitViewModel.kt:535`), add
  `mcpServerEnabled = platformSettings.getBoolean("mcp.server.enabled", false)`.
- Add `fun setMcpServerEnabled(value: Boolean)` next to `setLibsqlDriverEnabled`
  (`StelekitViewModel.kt:2101`), mirroring its body exactly with the new key/field.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

#### Story 4.1.2: `SettingsDialog` "MCP Server" panel
**As a** SteleKit user, **I want** a Settings panel to enable MCP and see how to
connect Claude Desktop/Code to it, **so that** I don't need to read source code to use
the feature.

**Acceptance Criteria**:
- A new `SettingsCategory.MCP_SERVER` entry is always visible (like `GENERAL`), shows
  a toggle bound to `isMcpServerEnabled`/`onMcpServerToggle`, and — when enabled —
  displays the exact launcher command
  (`<resolved-java-binary-path> -jar <resolved-launcher-jar-path> --graph-id <activeGraphId>`)
  for the user to paste into their MCP host's config. **`<resolved-java-binary-path>`
  is never a bare `"java"`** (architecture-review Blocker fix, this repair pass):
  Compose Desktop's jpackage-based `.msi`/`.dmg`/`.deb` distributions
  (`kmp/build.gradle.kts:1013-1038`'s `nativeDistributions` block) bundle a private
  jlink runtime image and do not add it to the system `PATH` — that is the entire
  point of jpackage bundling — so a bare `java -jar ...` command fails with
  "java: command not found" for any installed-app user with no separately-installed
  JDK on `PATH`, which is the majority case. Resolve it instead from the *running
  app's own* bundled runtime:
  `System.getProperty("java.home") + File.separator + "bin" + File.separator + (if (isWindows) "java.exe" else "java")`
  — `java.home` is a JVM-guaranteed system property pointing at the JRE/JDK the
  current process is actually running under, so this is always the correct
  interpreter for a jar built for this same JVM, whether running packaged or via
  `./gradlew run`. (`ProcessHandle.current().info().command()` is an acceptable
  fallback if `java.home` is ever unexpectedly absent, but `java.home` is the primary
  path — no such fallback is needed in normal operation.) `<resolved-launcher-jar-path>`
  is computed as: `System.getProperty("compose.application.resources.dir")?.let { "$it/stelekit-mcp-launcher.jar" }`
  when running as a packaged install (Task 1.2.2f bundles the jar there), falling
  back to a dev-mode hint (`"./gradlew :kmp:runMcpLauncher --args='--graph-id <id>'` — run from a source checkout)
  when that system property is absent, i.e. running via `./gradlew run`.
  - *Given* `SettingsDialog` rendered with `isMcpServerEnabled = true`,
    `compose.application.resources.dir` set to a temp path, and `java.home` set to
    `/opt/stelekit/runtime` (simulating a packaged install's bundled jlink image),
    *When* the MCP Server category is selected, *Then* the panel shows the toggle in
    the "on" state and a text block containing `/opt/stelekit/runtime/bin/java -jar`,
    the literal substring `"--graph-id"`, and the resolved `stelekit-mcp-launcher.jar`
    path — the command never contains the bare, unqualified string `"java -jar"` with
    no path prefix in front of it.
  - *Given* the same rendering with `compose.application.resources.dir` unset
    (dev-mode `./gradlew run`), *When* the MCP Server category is selected, *Then*
    the panel shows the `./gradlew :kmp:runMcpLauncher` fallback text instead of a
    nonexistent jar path (this dev-mode fallback is unaffected by the java-path fix —
    Gradle's `JavaExec` task already resolves its own launcher JDK internally).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphDialogLayer.kt`

##### Task 4.1.2a: `SettingsCategory.MCP_SERVER` + `McpServerSettings` composable (~5 min)
- In `SettingsDialog.kt`, add `MCP_SERVER("MCP Server", Icons.Default.Hub)` (or a
  similarly available icon) to the `SettingsCategory` enum, add
  `isMcpServerEnabled: Boolean = false`, `onMcpServerToggle: ((Boolean) -> Unit)? = null`,
  and `mcpLauncherCommand: String` (pre-computed by the caller per the AC's
  packaged-vs-dev-mode resolution logic, **including the `java.home`-resolved binary
  path — never a bare `java`** — keep both `System.getProperty("java.home")` and
  `System.getProperty("compose.application.resources.dir")` reads out of `commonMain`,
  since both are JVM-only concepts; resolve them in `Main.kt`/a JVM-`actual` and pass
  the assembled command string in) params to `SettingsDialog(...)`, add
  a `SettingsCategory.MCP_SERVER` branch to `visibleCategories`'s `when` (always
  visible — no null-gate, matching `GENERAL`), and add a private
  `@Composable fun McpServerSettings(...)` following `DeveloperSettings`'s
  `SettingsSection`/`SettingsToggleRow` shape, with a status line rendering
  `mcpLauncherCommand`, plus the two additional elements `ux.md` Surface A's
  wireframe specifies that the Observability Plan's prose already assumed exist but
  no prior task built (triad-review UX gap fix):
  - A **three-state status text**, computed from `uiState.mcpServerEnabled` and
    `uiState.mcpServerRunningForGraphName: String?` (new `AppState` field, set by
    `McpServerController` alongside its existing start/stop logic): `"Not running"`
    (toggle off), `"Waiting for a graph to open"` (toggle on, no active graph —
    mirrors Story 4.1.3's existing start-guard), or `"Running for \"<graph name>\""`
    (toggle on, graph open, socket bound).
  - A **`"Pending suggestions from MCP: N"` line + `"Review suggestions"` button**,
    reading `uiState.pendingMcpSuggestionCount` (Task 3.2.2a, already computed —
    this is a second rendering of the same state, not a new count), with the button
    calling the same `viewModel.openLlmSuggestionReviewScreen()` call Task 3.2.2d
    wires the sidebar badge to — a second entry point into the same screen and
    state, not a second suggestion-tracking mechanism.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`,
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (new `mcpServerRunningForGraphName` field),
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 4.1.2b: Wire `GraphDialogLayer` call site (~3 min)
- In `GraphDialogLayer.kt`, add `isMcpServerEnabled = uiState.mcpServerEnabled, onMcpServerToggle = { viewModel.setMcpServerEnabled(it) }, mcpLauncherCommand = mcpLauncherCommand`
  next to the existing `onLibsqlDriverToggle` wiring (`GraphDialogLayer.kt:185`),
  where `mcpLauncherCommand: String` is a new parameter threaded down from
  `StelekitApp`/`Main.kt` — JVM resolves both
  `System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"`
  (the launcher's own bundled runtime, never a bare `java` — architecture-review
  Blocker fix) and `compose.application.resources.dir` per Task 4.1.2a's AC and
  assembles the full `<javaPath> -jar <jarPath> --graph-id <id>` string; other
  platforms pass a static "not available" string, since MCP is JVM/Desktop-only per
  `requirements.md`'s Out of Scope.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphDialogLayer.kt`

##### Task 4.1.2c: Test (~3 min)
- Add a `jvmTest` UI test (mirroring an existing `SettingsDialog` test if one exists —
  `Glob` for `SettingsDialog*Test.kt` first) asserting the toggle renders, the
  launcher-command text is present when enabled, and — with `java.home` set to a test
  value — the rendered command contains `<that value>/bin/java`, never the bare
  substring `"java -jar"` unprefixed by a path.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialogMcpServerTest.kt`

##### Task 4.1.2d: Copy-to-clipboard + host-config hint (~4 min) — pre-mortem P1 #3 fix
- pre-mortem.md flags the realistic activation funnel (discover toggle → enable →
  find MCP host's config file → hand-copy the command → restart host → repeat per
  graph) as a likely reason the feature ships but goes undiscovered/unused. Reduce
  friction at the one step SteleKit can control: add a "Copy" `IconButton`
  (`Icons.Default.ContentCopy`, `ClipboardManager.setText(...)`, following the
  existing copy-affordance pattern already used for the git remote URL field in
  `GitSetupScreen.kt` — `Glob` for `ClipboardManager` first to confirm the exact
  call site to mirror) next to `mcpLauncherCommand`'s status line from Task 4.1.2a,
  plus a one-line static hint text: `"Paste into your MCP host's config (e.g.
  Claude Desktop: claude_desktop_config.json) and restart it."` No dynamic
  per-OS path resolution — this is static guidance text, not a file-picker.
  - *Given* `McpServerSettings` rendered with `isMcpServerEnabled = true`, *When*
    the user taps the Copy button next to the launcher command, *Then*
    `ClipboardManager.setText` is called with exactly the currently-displayed
    `mcpLauncherCommand` string, and a transient "Copied" `Text` or `Snackbar`
    confirms the action (mirrors the existing copy-confirmation pattern — reuse
    it, don't invent a new one).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`
  (same composable as Task 4.1.2a), `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialogMcpServerTest.kt` (extend Task 4.1.2c's test)

#### Story 4.1.3: `McpServerController` — JVM lifecycle wiring
**As a** SteleKit desktop app, **I want** the socket listener to start/stop exactly
when the setting changes, **so that** enabling/disabling the toggle takes effect
immediately without an app restart.

**Architecture-review Blocker fix — read before implementing**: the plan originally
assumed `GraphManager`, `StelekitViewModel`, and `LlmSuggestionInbox` are locally
constructible in `desktop/Main.kt`. Verified directly, they are not:
- `Main.kt` only calls the `commonMain` composable `StelekitApp(...)` (`App.kt:162`);
  `GraphManager` and `StelekitViewModel` are constructed *inside* `StelekitApp`, deep
  in its body (`StelekitViewModelDependencies(...)` at `App.kt:711`), never exposed to
  the caller as return values.
- `StelekitApp` already has exactly one precedent for this exact problem:
  `onGraphManagerReady: ((GraphManager) -> Unit)?` (`App.kt:176-177`), invoked via
  `LaunchedEffect(graphManager) { onGraphManagerReady?.invoke(graphManager) }`
  (`App.kt:267`) — but `Main.kt`'s current `StelekitApp(...)` call site doesn't even
  pass it.
- `LlmSuggestionInbox` is a `private` field on `StelekitViewModel`
  (`StelekitViewModel.kt:139`) with no accessor. Only a read-only projection
  (`llmSuggestions: StateFlow<Map<String, PendingLlmSuggestion>>`,
  `StelekitViewModel.kt:414-415`) and a gated mutator
  (`proposeLlmSuggestion(suggestion)`, `StelekitViewModel.kt:437-439`) are public.

**Resolution** (Tasks 4.1.3b/c below): add a new `onViewModelReady` callback to
`StelekitApp` mirroring `onGraphManagerReady` exactly, so `Main.kt` can capture both
instances the same way. `McpServerController`/`McpAdapter` then depend on
`StelekitViewModel` (via `McpSuggestionSink`, Task 1.1.2b) for the suggestion path —
never a raw `LlmSuggestionInbox` — which also fixes the Tell-Don't-Ask concern the
architecture review raised, since it's the same call convention the page-scan matcher
already uses.

**Acceptance Criteria**:
- `McpServerController` observes `viewModel.uiState.map { it.mcpServerEnabled }.distinctUntilChanged()`
  and calls `McpSocketServer.start()`/`.stop()` accordingly, guarded by
  `graphManager.getActiveGraphId()` being non-null (no graph open ⇒ never starts,
  even if the toggle is on).
  - *Given* `mcpServerEnabled` flips `false → true` while a graph is active, *When*
    `McpServerController` observes the change, *Then* `McpSocketServer.start()` is
    called exactly once and the socket file described in Story 1.2.1 exists
    afterward.
  - *Given* `mcpServerEnabled == true` but `graphManager.getActiveGraphId() == null`
    (no graph open), *When* `McpServerController` starts observing, *Then*
    `McpSocketServer.start()` is never called.
- `McpServerController` is constructed in `Main.kt` only once **both**
  `onGraphManagerReady` and the new `onViewModelReady` callback have fired — never
  from instances locally constructed in `Main.kt`, since none exist there.
  - *Given* `StelekitApp(...)` rendered with `onGraphManagerReady`/`onViewModelReady`
    callbacks wired to local `remember { mutableStateOf<T?>(null) }` holders, *When*
    the composable finishes its first successful composition, *Then* both holders are
    non-null and exactly one `McpServerController` has been constructed from them (not
    zero, not more than one across recompositions).

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpServerController.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`,
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpServerControllerTest.kt`

##### Task 4.1.3a: `McpServerController` (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpServerController.kt`:
  `class McpServerController(private val viewModel: StelekitViewModel, private val graphManager: GraphManager, private val scope: CoroutineScope)`
  — takes the concrete `StelekitViewModel`, not a raw `LlmSuggestionInbox`; internally
  constructs `ViewModelSuggestionSink(viewModel)` (Task 1.1.2b) to pass into
  `McpAdapter`. Attach a `CoroutineExceptionHandler` on `scope` (per `CLAUDE.md`'s
  "Uncaught coroutine Throwables" rule), launching a collector of the
  `mcpServerEnabled`/active-graph combination, starting/stopping an `McpSocketServer`
  built as follows per toggle-on: construct one `val mcpClientIdentity = McpClientIdentity()`
  (Task 1.1.2e), build `val adapter = McpAdapter(graphManager, ViewModelSuggestionSink(viewModel), mcpClientName = mcpClientIdentity::resolve)`,
  call `adapter.buildServer()`, then construct
  `McpSocketServer(graphId, server, mcpClientIdentity)` (Task 1.2.1b) — the same
  `McpClientIdentity` instance passed to both, so `McpSocketServer`'s
  `session.onInitialized { ... }` write and `McpAdapter`'s `mcpClientName()` read
  target the same holder (adversarial-review Blocker fix, this repair pass). On
  successful bind, set `viewModel.setMcpServerRunningForGraphName(graphManager.getActiveGraphName())`
  (new `AppState.mcpServerRunningForGraphName: String?` field, Task 4.1.2a); on
  `stop()` (toggle-off or graph-switch), clear it back to `null` — this is what
  makes Task 4.1.2a's `"Running for \"<graph name>\""` status text state reachable
  (triad-review UX gap fix: the field existed but nothing set it in the prior draft
  of this task).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpServerController.kt`

##### Task 4.1.3b: Add `onViewModelReady` callback to `StelekitApp` (~4 min)
- In `App.kt`, add a new parameter mirroring `onGraphManagerReady` exactly:
  `onViewModelReady: ((StelekitViewModel) -> Unit)? = null` (placed next to
  `onGraphManagerReady` at `App.kt:176-177`, same doc-comment style — "Called once the
  StelekitViewModel instance is ready. Used by the host process for JVM-only
  lifecycle owners (e.g. the MCP server controller)."). Immediately after `viewModel`
  is constructed (`App.kt:711`), add
  `LaunchedEffect(viewModel) { onViewModelReady?.invoke(viewModel) }`, mirroring the
  existing `LaunchedEffect(graphManager) { onGraphManagerReady?.invoke(graphManager) }`
  at `App.kt:267`. This is a purely additive, optional, default-`null` parameter —
  zero behavior change for Android/iOS/Web callers that don't pass it, consistent
  with how `onGraphManagerReady` already works on every non-JVM platform today.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 4.1.3c: Construct in `Main.kt` (~4 min)
- In `desktop/Main.kt`'s `application { ... }` block, add
  `var graphManagerRef by remember { mutableStateOf<GraphManager?>(null) }` and
  `var viewModelRef by remember { mutableStateOf<StelekitViewModel?>(null) }`, pass
  `onGraphManagerReady = { graphManagerRef = it }` and
  `onViewModelReady = { viewModelRef = it }` to the existing `StelekitApp(...)` call
  (which currently passes neither). Add
  `var mcpServerController by remember { mutableStateOf<McpServerController?>(null) }`
  and a
  `LaunchedEffect(graphManagerRef, viewModelRef) { val gm = graphManagerRef; val vm = viewModelRef; if (gm != null && vm != null && mcpServerController == null) { mcpServerController = McpServerController(vm, gm, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also { it.start() } } }`
  (per `CLAUDE.md`'s "coroutine scope ownership" rule — `McpServerController` owns
  its scope internally via the constructor arg here, `remember`/`LaunchedEffect` only
  hold/guard the reference, no `rememberCoroutineScope()` passed in; the
  `mcpServerController == null` guard prevents double-construction across
  recompositions once both refs are set).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`

##### Task 4.1.3d: Tests for both Acceptance Criteria (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpServerControllerTest.kt`
  asserting start-on-toggle-with-graph-open and never-start-without-graph-open,
  constructing `McpServerController` against a real (lightweight, fake-repository-backed)
  `StelekitViewModel` — reuse the existing ViewModel test-construction fixture (`Glob`
  for `*ViewModelTest*`/`StelekitViewModelLlmSuggestionTest.kt`'s setup before writing
  a new one from scratch).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpServerControllerTest.kt`

##### Task 4.1.3e: Bind-failure banner — triad-review UX blocker fix
- `design/ux.md` Surface D specifies a dismissible top-of-app banner (mirroring
  `HostReconnectBanner.kt`) when `McpSocketServer.start()` fails (e.g. the socket
  path is already held by a stale/second process). The Observability Plan's prose
  ("surfaces as a dismissible top-of-app banner") was never backed by a story/task —
  this task closes that gap. Add `AppState.mcpBindFailureMessage: String? = null`
  (next to `pendingMcpSuggestionCount`, Task 3.2.2a); in `McpServerController`
  (Task 4.1.3a), wrap the `McpSocketServer.start()` call in a `try/catch` that calls
  `viewModel.setMcpBindFailureMessage(e.message ?: "MCP server failed to start")` on
  failure and clears it (`null`) on the next successful `start()`. Add a top-of-app
  `McpBindFailureBanner` composable (new file, following `HostReconnectBanner.kt`'s
  `Surface`/`.semantics { contentDescription = ... }`/dismiss-`IconButton` shape
  exactly — `Glob`/`Read` `HostReconnectBanner.kt` first and mirror its structure,
  don't invent a new banner pattern), rendered in `App.kt` when
  `uiState.mcpBindFailureMessage != null`, with **two** actions — `Dismiss` (clears
  the message via a new `viewModel.dismissMcpBindFailureBanner()`, UI-state-only) and
  **`Retry`** (calls a new `viewModel.retryMcpServerStart()`, which re-invokes
  `McpServerController`'s bind attempt — `ux.md` Surface D's wireframe and interaction
  flow both specify a Retry button, not Dismiss-only; a Dismiss-only banner leaves the
  user's only recovery path as "cycle the Settings toggle off/on," which is strictly
  worse than a direct retry and was flagged as an incomplete fix in triad review).
  `Retry` failing again re-populates `mcpBindFailureMessage` with the same message
  (idempotent, no infinite auto-retry loop — per `ux.md`'s edge-case table) rather
  than silently swallowing the second failure.
  - *Given* `McpSocketServer.start()` throws (e.g. `BindException` from an
    already-bound socket path), *When* `McpServerController` catches it, *Then*
    `uiState.value.mcpBindFailureMessage` is non-null and the banner renders with
    that message and both `Retry`/`Dismiss` buttons; *When* the user taps `Retry` and
    the second `start()` attempt also throws, *Then* the banner remains visible with
    the (possibly updated) failure message, not silently dismissed; *When* the user
    taps `Dismiss`, *Then* `uiState.value.mcpBindFailureMessage == null` and the
    banner is gone.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`,
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`,
  `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpServerController.kt`,
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/McpBindFailureBanner.kt` (new),
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`,
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpServerControllerTest.kt` (extend)

##### Task 4.1.3f: Keyboard operability + contrast verification for all new controls — triad-review UX gap fix
- `design/ux.md` UX Acceptance Criteria #9 (keyboard-navigable: every new
  interactive control reachable/operable via Tab + Enter/Space, no mouse-only
  affordance) and #11 (contrast ≥4.5:1, light and dark mode) apply to every new
  control this plan adds (sidebar badge, `McpBindFailureBanner`'s Retry/Dismiss,
  the MCP provenance chip, Settings panel's toggle/Copy button/Review-suggestions
  button) but had no dedicated verification task. Add one: extend
  `QrTransferAccessibilityUxTest.kt`'s pattern (`hasClickAction()`/
  `hasRequestFocusAction()` semantics assertions — `Glob`/`Read` it first for the
  exact helper functions to reuse, don't reinvent them) to assert each new
  clickable control exposes both actions, and extend `QrTransferContrastTest.kt`'s
  programmatic contrast-ratio calculation (reused, not a new screenshot-based
  check) against the `McpBindFailureBanner`'s text/background and the provenance
  chip's text/background `ColorScheme` token pairs, for both light and dark
  `ColorScheme`.
  - *Given* the sidebar badge, `McpBindFailureBanner`'s two buttons, the provenance
    chip is not itself clickable (informational only — no test needed for it under
    AC#9), and the Settings panel's Copy/Review-suggestions buttons, *When* the
    keyboard-operability test suite runs, *Then* every listed *clickable* control
    passes both `hasClickAction()` and `hasRequestFocusAction()`; *When* the
    contrast test suite runs against both light and dark `ColorScheme`, *Then* the
    banner and chip's computed contrast ratios are both `>= 4.5`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/McpControlsAccessibilityUxTest.kt` (new, modeled on `QrTransferAccessibilityUxTest.kt`), `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/McpControlsContrastTest.kt` (new, modeled on `QrTransferContrastTest.kt`)

---

## Phase 5: Security Hardening

### Epic 5.1: Local-Socket Access Control

#### Story 5.1.1: Formalize and test socket permission restriction
**As a** SteleKit user, **I want** the MCP socket unreachable by other OS users on a
shared machine, **so that** enabling MCP doesn't widen my graph's exposure beyond
"processes I run" (ADR-001's stated trust model).

**Acceptance Criteria**:
- `McpSocketPath.forGraph(graphId)`'s parent directory is verified `0700` and the
  socket file itself inherits no broader permission, on every `McpSocketServer.start()`
  call, not just first creation (covers the case where a prior run left looser
  permissions).
  - *Given* `~/.stelekit/mcp/` already exists with `0755` permissions (simulating a
    pre-hardening install or manual tampering), *When* `McpSocketServer.start()` is
    called, *Then* the directory's permissions are corrected to `0700` before the
    socket is bound, and a test asserts this via
    `Files.getPosixFilePermissions(...)` before and after.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketPath.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpSocketPathPermissionTest.kt`

##### Task 5.1.1a: Idempotent permission correction (~3 min)
- In `McpSocketPath.forGraph(...)` (Task 1.2.1a), change directory creation to always
  call `Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))`
  after `Files.createDirectories(...)`, whether or not the directory already existed
  (idempotent — safe to call every `start()`).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpSocketPath.kt`

##### Task 5.1.1b: Test (~3 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpSocketPathPermissionTest.kt`
  pre-creating the directory with `0755`, calling `forGraph(...)`, asserting `0700`
  afterward.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpSocketPathPermissionTest.kt`

---

## Phase 6: Verification — End-to-End Success Metrics

### Epic 6.1: Automated Proof of `requirements.md`'s Success Metrics

**Goal**: Directly exercise the two success metrics `requirements.md` calls out as
needing automated (not manual) proof — real end-to-end tool calls, and a hard
assertion that no write occurs before accept.

#### Story 6.1.1: End-to-end `tools/list` + `search_graph`/`read_page` against a real graph
**As a** SteleKit maintainer, **I want** an automated test proving an MCP client can
list tools and get real graph data back, **so that** "it works end-to-end" is a test
result, not a manual smoke-test claim.

**Acceptance Criteria**:
- A `businessTest` builds a real `GraphManager` against a temp on-disk graph (fixture
  pattern already used by existing `GraphLoader`/`GraphManager` tests — `Glob` for an
  existing precedent before writing this from scratch), constructs `McpAdapter`,
  connects via `ChannelTransport`, calls `tools/list`, then `tools/call` for
  `search_graph` and `read_page`, and asserts real content comes back.
  - *Given* a temp graph containing one page `"Test Page"` with block content
    `"hello world"`, *When* the test sends a `tools/call` request for `search_graph(query = "hello")`
    over the `ChannelTransport`-connected session, *Then* the response contains
    `"Test Page"` in its ranked results, proving the full path (transport → tool
    handler → repository → real SQLite-backed graph → back through the transport)
    works without mocks at the repository layer.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/McpEndToEndReadTest.kt`

##### Task 6.1.1a: Test fixture + `tools/list` assertion (~5 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/McpEndToEndReadTest.kt`:
  set up a real `GraphManager`/temp graph fixture, construct `McpAdapter`, connect via
  `ChannelTransport`, assert `tools/list` returns `search_graph`, `list_graphs`,
  `read_page`, `list_backlinks`, `propose_block_edit`, `propose_new_page`.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/McpEndToEndReadTest.kt`

##### Task 6.1.1b: `search_graph`/`read_page` real-data assertions (~5 min)
- In the same file, add the `tools/call` assertions from the Acceptance Criterion
  above for both `search_graph` and `read_page`.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/McpEndToEndReadTest.kt`

#### Story 6.1.2: Propose-write never mutates before accept
**As a** SteleKit maintainer, **I want** an automated test asserting a `propose_*`
tool call never triggers `DatabaseWriteActor`/`GraphWriter`, **so that** the
approval-gate guarantee `requirements.md`'s Success Metrics section requires is
enforced by CI, not just design intent.

**Acceptance Criteria**:
- A `businessTest` calls `propose_block_edit` against a real graph with a
  write-call-recording wrapper around `DatabaseWriteActor`/`GraphWriterPort`, asserts
  zero recorded calls after the tool call completes, then separately drives
  `LlmSuggestionWriter.materializeAndWrite(...)` (simulating the human clicking
  Accept) and asserts the write **does** occur then — proving the gate is real, not
  just "nothing happened because nothing was implemented."
  - *Given* a real graph with block `b1` content `"before"`, *When*
    `propose_block_edit(pageUuid = "p1", blockUuid = "b1", newContent = "after")` is
    called via MCP and completes, *Then* the on-disk/DB content of `b1` is still
    `"before"` (unchanged); *When* the test then calls
    `LlmSuggestionWriter.materializeAndWrite(the proposed suggestion, graphPath)`
    directly, *Then* `b1`'s content becomes `"after"`.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/McpProposeWriteGateTest.kt`

##### Task 6.1.2a: Propose-then-assert-unchanged (~5 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/McpProposeWriteGateTest.kt`:
  real graph fixture, call `propose_block_edit` via `ChannelTransport`, re-read the
  block via `BlockRepository.getBlockByUuid(...).first()` and assert content is
  unchanged from before the call.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/McpProposeWriteGateTest.kt`

##### Task 6.1.2b: Accept-path assertion (~4 min)
- In the same file, after the propose assertion, fetch the queued suggestion from
  `LlmSuggestionInbox.pending.value`, call
  `LlmSuggestionWriter.materializeAndWrite(suggestion, graphPath)` directly, and
  assert the block's content **does** change — proving the test would fail if the
  propose path accidentally wrote directly (a test that only checks "nothing
  changed" without this second half can't distinguish "correctly gated" from
  "silently broken").
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/McpProposeWriteGateTest.kt`

#### Story 6.1.3: Real-subprocess smoke test over actual stdio + Unix socket — pre-mortem P1 #2 fix
**As a** SteleKit maintainer, **I want** one test that chains every real seam (subprocess
→ stdio → launcher byte-proxy → Unix domain socket → embedded `Server` → MCP
`initialize` handshake), **so that** CI can't go green while the feature is broken
against every seam it depends on — Story 6.1.1/6.1.2 and every other test in this plan
use `ChannelTransport` (in-memory) or exercise the launcher/socket in isolation
(Task 1.2.2d); none of them proves the seams compose. pre-mortem.md #2 flags this
gap as the top failure mode: CI fully green, first real Claude Desktop/Code connection
still fails or hangs.

**Acceptance Criteria**:
- A `jvmTest` starts a real `McpSocketServer` bound to a temp-dir socket path against a
  real temp-graph `GraphManager`, then launches the actual built `McpLauncherMain`
  `main()` function **in a separate JVM subprocess** (`ProcessBuilder`, not an in-process
  function call) with its stdin/stdout connected as pipes, writes a real MCP
  `initialize` JSON-RPC request to the subprocess's stdin, reads the `initialize`
  response from its stdout, then sends one real `tools/call` (`search_graph`) the same
  way and asserts a real result comes back — proving the byte-proxy loop, socket
  framing, and SDK handshake all compose correctly end to end, not just in isolation.
  - *Given* `McpSocketServer` running against a temp graph containing page
    `"Smoke Test Page"`, and `McpLauncherMain` launched as a real child process
    pointed at that server's socket path via `--graph-id`, *When* the test writes a
    raw `initialize` JSON-RPC request followed by a `tools/call` request for
    `search_graph(query = "Smoke")` to the subprocess's stdin (newline-delimited,
    matching the SDK's real stdio framing) and reads both responses from its stdout,
    *Then* the `initialize` response's `protocolVersion` matches the SDK's declared
    version and the `tools/call` response contains `"Smoke Test Page"` — proving a
    real external process talking real stdio bytes gets real graph data back, with
    zero `ChannelTransport` anywhere in this test's call path.
- This test is explicitly `@Tag("mcp-subprocess")` or an equivalent existing
  slow-test marker (`Glob`/`Grep` for an existing slow/integration-tag convention in
  this repo first — e.g. how screenshot/Roborazzi tests are excluded from the fast
  suite — and follow it) so it doesn't add subprocess-spawn latency to the default
  fast `bazel test //kmp:business_tests` loop, but does run in `ciCheck`/full CI.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpRealSubprocessSmokeTest.kt`

##### Task 6.1.3a: Subprocess harness + `initialize` handshake assertion (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpRealSubprocessSmokeTest.kt`:
  real `McpSocketServer` + temp graph fixture (reuse Task 6.1.1a's fixture helper),
  `ProcessBuilder` launching the built launcher jar (or, if the packaged jar isn't
  available at test time, the `runMcpLauncher` Gradle-run equivalent's raw `java`
  invocation against `McpLauncherMain`'s compiled classes — confirm which is feasible
  from a `jvmTest` context before committing to one), write a raw `initialize` frame
  to its stdin, assert a well-formed `initialize` response on stdout.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpRealSubprocessSmokeTest.kt`

##### Task 6.1.3b: `tools/call` over the subprocess + slow-test tagging (~4 min)
- In the same file, send the `search_graph` `tools/call` frame, assert the real result,
  clean up the subprocess (`destroy()`/await exit) in a `finally`, and apply the
  slow-test tag/exclusion identified in the AC.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/McpRealSubprocessSmokeTest.kt`
