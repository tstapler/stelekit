# Adversarial Review: graph-mcp-server
**Date**: 2026-09-14
**Verdict**: CONCERNS

## Prior Blockers — Resolution Status (verified against real sources, not re-read from the plan's own claims)

1. **`mcpClientName` undefined/uncompilable in `McpAdapter`** — **RESOLVED**. `McpAdapter`'s
   constructor (Task 1.1.2c) is now
   `McpAdapter(graphManager: GraphManager, suggestionSink: McpSuggestionSink, mcpClientName: () -> String)`.
   Every call site is consistent: Task 3.1.1b/3.1.2a pass `McpAdapter`'s own
   `mcpClientName` param through to `registerProposeBlockEditTool`/`registerProposeNewPageTool`;
   Task 4.1.3a constructs `McpAdapter(graphManager, ViewModelSuggestionSink(viewModel),
   mcpClientName = mcpClientIdentity::resolve)`, wiring the same `McpClientIdentity`
   instance `McpSocketServer` (Task 1.2.1b) updates via `session.onInitialized { ... }`.
   No dangling reference remains anywhere in the plan.
2. **SDK-capability uncertainty (does `kotlin-sdk-server` expose per-call client
   identity?)** — **RESOLVED, and independently verified against the real SDK source
   at the pinned commit, not just trusted from the plan's citation.** Fetched
   `kotlin-sdk-server` tag `0.15.0` (commit `76b8e7d63aac39fe41e03684e3b91a6c3724a8da`,
   confirmed real via `gh api repos/modelcontextprotocol/kotlin-sdk/commits/<sha>` —
   message: "release: 0.15.0 (#918)") directly from GitHub:
   - `ClientConnection.kt`: `sessionId` is doc-commented verbatim "A unique identifier
     identifying the current session. Has no inherent meaning." — no `clientVersion`/
     `clientInfo` member anywhere on the interface. Exact match to the plan's citation.
   - `Server.kt:310/342`: `addTool(..., handler: suspend ClientConnection.(CallToolRequest) -> CallToolResult)`
     — confirms the tool-handler receiver type really is the identity-less `ClientConnection`.
   - `ServerSession.kt:72,78,124,352` — line-for-line exact match to the plan's
     citation: `_clientVersion: AtomicRef<Implementation?>` (72), `public val
     clientVersion: Implementation? get() = _clientVersion.value` (78), `public fun
     onInitialized(block: () -> Unit)` (124), `_clientVersion.value =
     request.params.clientInfo` (352, inside the `initialize` handler).
   - `Server.kt:216`: `public suspend fun createSession(transport: Transport):
     ServerSession` — exact match, confirms this is the only place a `ServerSession`
     (and thus `clientVersion`) is returned to a caller.
   - `tools.kt:231-237`: `CallToolRequestParams(name, arguments, task, meta)` — no
     call-id field, confirming the plan's second fix (`request.callId` → fresh
     `UuidGenerator.generateV7()`) was also a real, necessary correction, not invented.
   Every load-bearing citation checked out exactly. The resulting design
   (`McpClientIdentity` captured once per connection in `McpSocketServer`'s accept
   loop, read by tool handlers via the threaded `mcpClientName: () -> String`) is
   sound engineering given what the SDK actually exposes.
3. **Architecture-review blocker — bare `java` command fails for packaged installs**
   — **RESOLVED**. Task 4.1.2a now resolves the interpreter via
   `System.getProperty("java.home") + "/bin/java"` (the JVM-guaranteed property
   pointing at the running process's own jlink runtime image), never a bare `java`.
   Consistently threaded through Task 4.1.2a/4.1.2b's AC and test.

All three previously-cited blockers are concretely fixed, internally consistent
across every call site touched, and — for the SDK-capability claim, the one this
review could independently check against a primary source — verified correct against
real code, not just plausible-sounding prose.

## Blockers

None found in this pass.

## Concerns

- [ ] **New in this pass: no handling for a stale Unix-domain-socket file left behind
  by an unclean shutdown (crash, `kill -9`, power loss) — the next `start()` would
  fail to bind.** `McpSocketServer.stop()` (Story 1.2.1) removes the socket file, but
  only runs on clean shutdown; nothing in Task 1.2.1a/b or Task 5.1.1a unlinks a
  leftover socket file before `ServerSocketChannel.bind(UnixDomainSocketAddress...)`.
  Binding to an existing UDS path fails (`BindException`/`AddressAlreadyInUseException`)
  regardless of whether the original process is still alive. Since `mcpServerEnabled`
  persists across restarts (Story 4.1.1) and the app is a single-user desktop app
  with no supervisor process, an unclean exit followed by relaunch would leave the
  feature permanently unable to start until the user manually finds and deletes a
  dotfile under `~/.stelekit/mcp/`. The plan's own Observability Plan does define a
  failure path here (bind failure logs ERROR + dismissible banner), so this degrades
  to a confusing but non-silent failure rather than a hang — it is not a blocker, but
  it is a real, unaddressed gap for a "crash resilience" desktop app. **Recommend**:
  in `McpSocketServer.start()`, attempt to connect to an existing socket file first
  (a live connect success means a real server is already listening — refuse to start
  a second one); on connect failure (stale file, nothing listening), `Files.deleteIfExists(...)`
  it before binding.
- [ ] **Graph-switch-mid-session is still unhandled, and — combined with the
  still-unaddressed "read tools don't echo which graph they answered against"
  Concern from the prior review — the two compound into a genuine silent-wrong-data
  risk, not just a missing-signal one.** Unchanged from both prior reviews: Task
  4.1.3a's `McpServerController` observes only `mcpServerEnabled`, checking
  `graphManager.getActiveGraphId()` once as a start-guard, never as a second reactive
  trigger. If a user switches the active graph in the UI while an MCP client is
  connected, the socket file's name still advertises the graph active at connect
  time, but every read tool (`search_graph`/`read_page`/`list_backlinks`) resolves
  `graphManager.getActiveRepositorySet()` **dynamically at call time** — so the
  client silently starts receiving a different graph's content under the same
  connection, with no field in any read-tool response identifying which graph
  answered. (The write side is safer: Story 3.1.1's `graphId` on the persisted
  suggestion is also read dynamically at propose time, so a queued suggestion is at
  least correctly attributed to the graph that was active *when proposed* — it just
  means the human reviewing it may be surprised which graph it targets.) This was
  flagged as a Concern twice already and is unchanged by this repair pass; not
  escalating to Blocker since no write ever happens without human review and the
  socket accepts only one connection at a time (bounding the blast radius to one
  session), but it remains worth fixing or explicitly deferring with a stated
  rationale (as ADR-002 models for other cut items) rather than left as a live
  contradiction between the Domain Glossary's prose ("reacts to the
  `mcpServerEnabled`/active-graph combination") and the AC that doesn't implement
  that combination. **Recommend**: either wire `combine(mcpServerEnabled,
  activeGraphId)` with a defined restart behavior, or add the active graph's
  id/name to every read-tool's JSON envelope so a client can at least detect the
  switch, or explicitly document both as an accepted v1 gap.
- [ ] **`"mcp:"` provenance-prefix string is still duplicated with no shared
  constant, unchanged from the architecture review's Concern.** Task 3.2.1a's
  `McpSuggestionSink.mcpPendingCount()` (jvmMain) and Task 3.2.2a's
  `StelekitViewModel` derivation (commonMain) each still independently write the
  literal `it.sourceProviderId.startsWith("mcp:")` — the repair pass did not hoist
  this into a shared `const val` as the architecture review recommended. A future
  rename of the prefix silently desyncs the cap-enforcement count from the sidebar
  badge count with no compiler signal. **Recommend**: `const val MCP_SOURCE_PREFIX =
  "mcp:"` in a commonMain location both source sets can reference (e.g. next to
  `PendingLlmSuggestion`).
- [ ] **`PageResource`'s shared uuid resolver still lives inside `ReadPageTool.kt`,
  unchanged from the architecture review's Concern.** Task 2.3.1a still extracts
  `resolvePageByUuid` into `ReadPageTool.kt` for `PageResource.kt` (a separate
  feature, Epic 2.3) to call — the repair pass didn't move it to a neutral shared
  file. Low-impact (correctness is fine, only future maintainability is affected).
  **Recommend**: extract to `mcp/PageResolver.kt` if touched again, not urgent enough
  to block this plan.
- [ ] **Windows Unix-domain-socket support remains unverified on a real shipping
  platform.** Unchanged from both prior reviews: ADR-001 still states this "has not
  been build-tested on Windows," and SteleKit ships a real `.msi`
  (`TargetFormat.Msi`, `kmp/build.gradle.kts:1014`). No task in any phase verifies
  this. Acceptable to carry as a documented Unresolved Question for a plan of this
  scope (JDK 16+'s `java.nio.channels` UDS support is cross-platform per the JDK
  spec, so the risk is "unverified," not "known broken"), but it should be tested
  before the feature is promoted out of off-by-default on Windows specifically.
- [ ] **Mid-edit clobber race is still neither resolved nor explicitly deferred.**
  Unchanged from both prior reviews: `research/pitfalls.md` §4.1's question about
  warning on accepting a suggestion targeting a block the user is actively editing
  is absent from all six phases, with no disposition entry explaining the omission.
  Acceptable to defer for v1 given the human-review gate is the primary mitigation
  already in place (ADR-002), but should get an explicit Unresolved Questions entry
  rather than silent omission.

## Minors

- Throwable-catching now correctly covers every individual tool-handler body
  (`addTimedTool`, Story 1.1.3, verified broadened to `catch (e: Throwable)` after
  the more specific `TimeoutCancellationException`/`CancellationException` catches,
  in the correct order). It is **not** separately wrapped around `McpSocketServer`'s
  accept/session loop itself (Task 1.2.1b) or `McpLauncherMain`'s byte-copy loops —
  an exception at that layer (not inside a tool call) relies on
  `McpServerController`'s outer `CoroutineExceptionHandler` (Task 4.1.3a) to avoid
  crashing the app, which is probably sufficient but is never explicitly stated as
  the mechanism covering this layer. Worth a one-line note in Task 1.2.1b tying the
  two together explicitly.
- No single test exercises `McpClientIdentity` capture through the *real* accept-loop
  wiring (`McpSocketServer` → `session.onInitialized { ... }` → identity update) —
  Task 1.1.2f tests `McpClientIdentity` directly via a manual `ChannelTransport`
  handshake, and Task 1.2.1c/e explicitly punt identity assertions to 1.1.2f. The two
  halves are each tested, but never together through the same object graph
  `McpServerController` actually builds. Low priority given both halves are simple
  and independently verified.
- Requirements' JVM/Desktop-only MCP feature still adds `mcpServerEnabled`/
  `pendingMcpSuggestionCount` fields to `AppState` (commonMain, shared by
  Android/iOS/Web, which can never use MCP in v1) — unchanged from both prior
  passes. Harmless.
- `propose_new_page`'s `blocks` array still has no stated size cap — unchanged from
  both prior reviews.

---

**Assessment**: This is the second repair pass, and both blockers carried into it
(the uncompilable `mcpClientName` reference and the unverified `java` launcher
command) are now fixed with internally consistent signatures across every call site
touched, and — for the one claim independently checkable against a primary source
(the `kotlin-sdk-server` SDK-capability question) — verified correct line-for-line
against the real pinned commit, not merely plausible. No new blocker-severity issue
surfaced in a fresh five-lens pass; the one genuinely new finding (stale-socket-file
recovery) degrades to a logged, banner-surfaced failure rather than a hang or silent
corruption, so it lands as a Concern. The remaining Concerns are unchanged carry-overs
from one or both prior reviews (graph-switch-mid-session, the `"mcp:"` prefix
duplication, `PageResource` file placement, Windows UDS, mid-edit clobber) that this
repair pass did not touch — none of them compile-block or represent unresolved
design ambiguity, and all are either already flagged as Unresolved Questions or are
reasonable to defer/document for a v1, off-by-default, single-user desktop feature.

**Ready to proceed to Phase 4 (validate).**
