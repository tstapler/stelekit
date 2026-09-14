# Architecture Review: graph-mcp-server
**Date**: 2026-09-14
**Verdict**: CONCERNS

## Prior Blocker — Resolved (verified directly)

The previous review's blocker (Story 4.1.3/Task 4.1.3b wrongly assumed `GraphManager`/
`StelekitViewModel`/`LlmSuggestionInbox` are locally constructed in `Main.kt`) is
**resolved**. Verified directly against real source, not re-read from the plan's own claims:

- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt:142-150` calls
  `StelekitApp(fileSystem, graphPath, urlFetcher, spanRecorder, cryptoEngine,
  attachmentService, gitRepository)` — no `onGraphManagerReady`/`onViewModelReady`
  passed, confirming the plan's claim that today's call site passes neither.
- `App.kt:176-177` declares `onGraphManagerReady: ((GraphManager) -> Unit)? = null`;
  `App.kt:267` is exactly `LaunchedEffect(graphManager) { onGraphManagerReady?.invoke(graphManager) }`
  — both citations are byte-for-byte accurate.
- `StelekitViewModel.kt:139` is exactly `private val llmSuggestionInbox: ... LlmSuggestionInbox = ...`
  (no accessor); `StelekitViewModel.kt:414-415` is the public `val llmSuggestions: StateFlow<...>`
  projection; `StelekitViewModel.kt:437-439` is the public gated `fun proposeLlmSuggestion(suggestion)`.
  All three citations check out exactly.
- The `onViewModelReady`/`McpSuggestionSink` design (Tasks 4.1.3b, 1.1.2b) is therefore
  sound: it mirrors a real, working precedent (`onGraphManagerReady`) and routes through
  the ViewModel's actual public gated API rather than a nonexistent inbox accessor.

One citation-precision nitpick survives (see Nitpicks) but does not affect the design's
correctness.

## Blockers

None — see Resolved section below.

## Blockers — Resolved (verified against updated plan.md)

- [x] **Story 4.1.2 / Task 4.1.2a/b — launcher command now resolves the java binary from the running app's bundled runtime instead of a bare `java`.** `plan.md`'s Task 4.1.2a/b (and Story 4.1.2's AC) now specify `System.getProperty("java.home") + File.separator + "bin" + File.separator + (if (isWindows) "java.exe" else "java")`, with a Given/When/Then AC and Task 4.1.2c test both asserting the rendered command contains `<java.home>/bin/java` and never the bare, unqualified substring `"java -jar"`.

## Concerns

- [ ] **Story 1.2.1 / Task 1.2.1d — hand-rolling one raw JSON-RPC frame contradicts the plan's own "adopt the SDK, don't hand-roll" pattern decision.** The Pattern Decisions table justifies adopting `kotlin-sdk-server` specifically because "hand-rolling reinvents a moving target for zero cost savings." Task 1.2.1d has `McpSocketServer`'s accept loop write a raw `{"jsonrpc":"2.0","id":null,"error":{"code":-32000,...}}` frame directly to the rejected `SocketChannel`, bypassing `Server.connect(...)`/the SDK's own framing entirely — the task text itself hedges with "confirm exact framing from research/stack.md during implementation," i.e. it isn't confirmed the hand-written bytes match the SDK's actual wire format, and any future SDK version bump could silently desync this one hand-rolled path from everything else the SDK owns. **Remediation**: check whether `kotlin-sdk-server` exposes any transport/session-level rejection primitive (e.g. connecting a short-lived `Server`/`Transport` just long enough to emit a standard error response and close) before falling back to hand-written bytes; if the SDK genuinely has no such affordance, document that finding explicitly as a scoped, deliberate exception to the "no hand-rolling" decision rather than leaving it as an unconfirmed implementation detail.

- [ ] **Story 1.1.3's `addTimedTool` only catches `TimeoutCancellationException`, not the `Throwable`-catching pitfalls.md item 6 asks for.** `research/pitfalls.md` §5.6 item 6 explicitly requires "the MCP server's stdio read/dispatch loop... catch `Throwable` at its outermost boundary and convert to a structured MCP error response, not let it propagate and silently kill the server" — citing the same real hang/crash issues (`anthropics/claude-code` #44006/#63379) that motivated `addTimedTool` in the first place. Task 1.1.3a's implementation is `try { withTimeout(timeoutMs) { handler(request) } } catch (e: TimeoutCancellationException) { ... }` — a handler bug (NPE, an unmapped `RuntimeException` from a repository edge case, a JSON-arg-parsing failure) is not caught here and nothing in Phase 1/2 confirms the SDK's own dispatch loop catches it either. Task 1.1.3b's test only exercises the timeout path, not an unexpected-throw path. **Remediation**: broaden `addTimedTool`'s catch to `Throwable` (mapping to `McpToolError.Internal`/a generic error result), and separately confirm (or add) `Throwable`-catching at `McpSocketServer`'s session/accept-loop level per pitfalls.md's dispatch-loop framing — these are two different failure points and neither is currently covered.

- [ ] **Story 3.2.1's pileup-cap check-then-act is a real, verified race, still unaddressed.** Read `LlmSuggestionInbox.propose()` directly (`kmp/src/commonMain/.../llm/LlmSuggestionInbox.kt`): `fun propose(suggestion) { _pending.update { it + (suggestion.id to suggestion) } }` — a plain atomic map append with no cap awareness. Task 3.2.1b's enforcement ("count pending, then call `propose()`") is two separate `StateFlow` reads/writes with nothing serializing them; an MCP client pipelining multiple `propose_*` calls without awaiting each response (legal under JSON-RPC) can have N concurrent handler invocations all observe a count under `MCP_PENDING_CAP` before any of them commits, exceeding the cap by up to N-1. This was already flagged in `adversarial-review.md` and the repair pass did not touch Task 3.2.1b. **Remediation**: guard the count-check-then-propose sequence with a `Mutex` in the MCP adapter layer (still without modifying `LlmSuggestionInbox` itself, consistent with ADR-002's isolation goal), or add a compare-and-set-style helper on top of the existing `StateFlow.update` that atomically rejects over-cap proposals in one `update` call.

- [ ] **The `"mcp:"` provenance-prefix convention is stringly-typed and duplicated across the jvmMain/commonMain boundary with no shared constant.** `McpPendingCap.mcpPendingCount()` (Task 3.2.1a, jvmMain-only) and `StelekitViewModel`'s `pendingMcpSuggestionCount` derivation (Task 3.2.2a, commonMain) each independently write `it.sourceProviderId.startsWith("mcp:")` — necessarily duplicated rather than shared, since `mcp/` is scoped JVM-only and `StelekitViewModel` is commonMain, so the two can't reference one Kotlin symbol across that source-set boundary. Nothing enforces the two string literals stay in sync; a future rename of the prefix (e.g. to disambiguate multiple MCP client types) silently desyncs the cap-enforcement count from the sidebar badge count with no compiler signal. **Remediation**: hoist the literal into a `const val MCP_SOURCE_PREFIX = "mcp:"` in a commonMain location both sides can reference (e.g. next to `PendingLlmSuggestion` in `llm/`), even though the *logic* using it stays split across the two source sets.

- [ ] **`PageResource`'s shared resolver lives inside a single-tool-handler file, giving `ReadPageTool.kt` a second reason to change.** Task 2.3.1a extracts `resolvePageByUuid` into `ReadPageTool.kt` specifically so `PageResource.kt` (a different feature — MCP Resources, Epic 2.3) can call the identical resolution logic `read_page` uses. That's the right instinct (no duplicated resolver), but the chosen home couples an independent feature's correctness to a file whose name and primary responsibility is "the `read_page` tool." A future change scoped to `read_page` only (e.g. adding retry/telemetry specific to that tool call) now has to reason about `PageResource`'s dependency on the same file. **Remediation**: extract the shared uuid/name/date resolution logic into its own file (e.g. `mcp/PageResolver.kt`) that both `ReadPageTool` and `PageResource` depend on symmetrically, rather than one tool file doubling as a shared library for another feature.

## Nitpicks

- The Domain Glossary's `onViewModelReady` citation ("invoked... immediately after `viewModel` is constructed (`App.kt:711`)") is imprecise: line 711 is inside the `StelekitViewModelDependencies(...)` constructor-argument list, mid-construction — not after it. The `val viewModel = remember(...) { ... }` block doesn't close until ~line 742, and the codebase's own working precedent for "run something once the ViewModel instance exists" is the `LaunchedEffect(viewModel) { onMemoryPressure?.invoke { ... } }` at `App.kt:751-753`. An implementer following the citation literally would try to insert a `LaunchedEffect` mid-expression, which doesn't compile — harmless once hit, but worth correcting the citation to point at the `remember` block's close (~line 742) or the existing `onMemoryPressure` `LaunchedEffect` as the insertion model.
- Task 2.2.1b's `read_page` schema (three independent nullable `uuid?`/`name?`/`date?` fields plus a hand-written "exactly one supplied" check) is a small primitive-obsession smell — a `sealed class PageLookupKey { Uuid(...); Name(...); JournalDate(...) }` parsed once at the tool boundary would make "exactly one" a structural guarantee instead of a runtime check. Low priority: it's contained to one tool and the runtime check is already specified and tested.
- Task 1.1.3's enforcement that all six tools use `addTimedTool` (never raw `addTool`) is currently a manual `Grep`-at-review-time check, not a CI gate. Since `kibitzer` is available in this environment, a `naming_rules`/`content_rules` entry in `.claude/inspect.json` scoping the `mcp/tools/` package to forbid direct `Server.addTool(` calls would make this mechanically enforced rather than relying on every future PR reviewer remembering to grep for it. Optional — the existing convention-by-grep approach is consistent with how the rest of this plan documents its guardrails.

---

**Summary of what changed since the last review**: the repair correctly diagnosed and fixed the Story 4.1.3 wiring blocker (verified against real source, not just re-read from the plan). It also closed the four adversarial-review blockers (unpackaged launcher, missing Resources epic, undefined second-connection behavior, missing per-tool timeout) with new surface area (Epic 2.3, Tasks 1.2.1d/e, 1.2.2e/f, Story 1.1.3) — but that new surface area introduces its own gaps (the launcher's `java -jar` command not actually running on a packaged install being the most severe), and several of the adversarial review's pre-existing Concerns (pileup-cap atomicity, Throwable-catching at the dispatch loop) remain unaddressed by this pass.
