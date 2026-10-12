# ADR-002: MCP Proposals Reuse `LlmSuggestionInbox` Unmodified; Pileup Cap Lives in the MCP Adapter, Not the Inbox

**Date**: 2026-09-14
**Status**: Accepted
**Deciders**: Tyler Stapler
**Context**: graph-mcp-server Phase 3 (planning) — resolves requirements.md Open Questions 4 and 6

---

## Context

`requirements.md` Open Question 4 asks whether to reuse Epic 7's
`LlmSuggestionInbox`/`LlmSuggestionWriter` or build a second, MCP-specific approval
queue. `research/architecture.md` and `research/pitfalls.md` confirm Epic 7 is fully
shipped (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/{PendingLlmSuggestion,LlmSuggestionInbox,LlmSuggestionWriter}.kt`,
commit `72e7837d08`), tested, and wired into `StelekitViewModel`/`LlmSuggestionReviewScreen`.

Open Question 6 asks a follow-on question `research/ux.md` §4c raises directly:
`LlmSuggestionInbox` is a flat `Map<String, PendingLlmSuggestion>` with **no cap, no
TTL, no eviction policy** (confirmed by reading the full file — 35 lines, no size
guard). Today this is bounded in practice because the only producer is a
user-triggered, manually-run page-scan feature. An MCP client is a qualitatively
different producer: an external LLM conversation could call a `propose_*` tool many
times in one turn with no natural rate limit. `research/ux.md` §1 cites the
"cry-wolf effect" / approval-fatigue literature: once a review queue's
no-longer-read rate passes roughly half, users stop reading individual items and
start batch-accepting — a large MCP-driven pileup risks training exactly that
behavior on a UI that also serves the existing page-scan matcher's suggestions.

## Decision

**Reuse `LlmSuggestionInbox`/`LlmSuggestionWriter`/`LlmSuggestionReviewScreen`
directly, unmodified.** The MCP `propose_block_edit`/`propose_new_page` tool handlers
construct a `PendingLlmSuggestion.BlockEdit`/`.NewPage` and call
`McpSuggestionSink.propose(suggestion)` — backed in production by `ViewModelSuggestionSink`,
which wraps `StelekitViewModel.proposeLlmSuggestion(suggestion)`, never a raw
`LlmSuggestionInbox` reference (plan.md's architecture-review repair: `LlmSuggestionInbox` is
a private, accessor-less field on `StelekitViewModel`, so tool handlers cannot reach it
directly — see plan.md Pattern Decisions row "MCP proposal delivery into the inbox"). This is
functionally the same outcome as the existing page-scan matcher
does. `sourceProviderId` is set to `"mcp:<mcp-client-name>:<proposal-id>"` (e.g.
`"mcp:claude-desktop:0198f2b1-...-a1b2c3"`) so the review screen and any future
analytics can distinguish MCP-sourced proposals from the matcher's
`"aho-corasick-matcher"` or a future on-device/remote-LLM synthesis feature's provider
id. No new `PendingLlmSuggestion` variant, no new SQLDelight table, no change to
`LlmSuggestionWriter`'s staleness re-check.

**Provenance string sourcing (revised during implementation planning — plan repair,
2026-09-14):** `<mcp-client-name>` is **not** read per tool call. Verified directly
against `kotlin-sdk-server` tag `0.15.0` (commit
`76b8e7d63aac39fe41e03684e3b91a6c3724a8da`): a tool handler's receiver type,
`ClientConnection`, exposes only a `sessionId` documented as having "no inherent
meaning" — never the client's negotiated `Implementation` (name/version). That value
lives on `ServerSession.clientVersion`, reachable only where the `ServerSession`
object itself is visible, i.e. wherever `Server.createSession(transport)` is called —
the socket accept loop (`McpSocketServer`), not the tool handler. `<mcp-client-name>`
is therefore captured once per connection, immediately after the `initialize`
handshake completes (`session.onInitialized { ... }`), by a small `McpClientIdentity`
holder shared between `McpSocketServer` (writer) and `McpAdapter`'s tool handlers
(reader via a `mcpClientName: () -> String` accessor). Since the server accepts only
one connection at a time (ADR-001), session-scoped capture carries the same accuracy
as a true per-call lookup would have. Similarly, `<proposal-id>` is a freshly
generated `UuidGenerator.generateV7()` at propose time, not a literal MCP call id —
`CallToolRequest` (same SDK version) exposes no such field. Neither change affects
this ADR's Decision or Consequences below; both segments still uniquely and
legibly distinguish MCP-sourced proposals from every other producer.

**The pileup cap is enforced in the new MCP adapter layer, before `propose()` is
ever called — not inside `LlmSuggestionInbox` itself.** Concretely: a
`McpPendingCapGuard` (constructed once per `McpAdapter`, shared by both propose
tools) wraps the count-and-propose sequence in a single `Mutex`-guarded operation:
it counts `LlmSuggestionInbox.pending.value.values.count { it.sourceProviderId.startsWith("mcp:") }`
against a constant `MCP_PENDING_CAP = 20`, and only calls `propose()` while still
holding the lock if the count is under the cap. (Revised during plan repair,
2026-09-14: the original design left the count and the `propose()` call as two
separate, unsynchronized steps, which a pipelined/concurrent MCP client could race
past the cap by up to N-1 — the `Mutex` closes that gap without touching
`LlmSuggestionInbox`.) If the cap is already reached, the tool handler returns a
structured MCP tool error (`CallToolResult(isError = true, ...)`) with a message
telling the calling LLM the review queue is full and to ask the human to review
pending suggestions first — never a silent drop, and never a queue mutation that
isn't visible to the human. This is a **per-source cap**, not a global cap: the
existing page-scan matcher's suggestions are counted separately and cannot be crowded
out by a noisy MCP conversation, and vice versa.

## Consequences

**Positive**:
- Zero changes to shipped, tested Epic 7 code (`LlmSuggestionInbox`,
  `LlmSuggestionWriter`, `PendingLlmSuggestion`, `LlmSuggestionReviewScreen`) — the cap
  is a property of the new adapter's call discipline, not a new inbox capability, so
  there is nothing to regression-test in the existing suite.
  matches `research/architecture.md`'s "Isolate via seam" disposition: the seam
  absorbs new policy, the wrapped subsystem stays untouched.
- The refusal is legible to the calling LLM (a structured tool error, not a hang or a
  silent no-op), which can relay "please review your pending suggestions" back to the
  human in the same conversation — turning a pileup into a signal instead of a
  silent failure mode.
- A future per-source cap change (raising/lowering `MCP_PENDING_CAP`, or extending the
  same counting pattern to a future non-MCP provider) is a one-constant change in the
  adapter, not a schema or API change.

**Negative / accepted v1 limitations**:
- The cap is a simple count, not a smarter policy (e.g. TTL-based eviction of stale
  MCP proposals, or grouping near-duplicate proposals into one review item). If real
  usage shows 20 is the wrong number, or that count-based capping isn't the right
  mitigation for approval fatigue building up *before* the cap is hit (§1's numbness
  concern applies to proposal #10 as much as proposal #21), that is a follow-up
  design pass, not solved here.
- No sidebar badge exists today reminding the user suggestions are pending once they
  dismiss the review screen (`research/ux.md` §2 gap). This plan adds a minimal count
  badge (Story 3.2.2) as a cheap, directly-scoped fix for the "MCP client thinks its
  work vanished" risk, but does not build OS-level notifications (net-new platform
  surface, out of scope per `research/ux.md` §2's recommendation).

## Alternatives Considered

### Build a second, MCP-specific pending-suggestion queue

Rejected. Would create exactly the "two competing approval systems" risk
`requirements.md` Open Question 4 warns against — except competing with *shipped*
code, not a hypothetical future one. No benefit identified: `PendingLlmSuggestion`'s
existing variants (`BlockEdit`, `NewPage`) already cover the MCP tool surface this
project needs.

### Global cap on `LlmSuggestionInbox` (any source), enforced inside the inbox

Rejected. A single-user page-scan run and a single MCP conversation would compete for
the same budget, so a legitimate matcher-driven batch could be silently blocked by
unrelated MCP activity or vice versa. Also would require modifying shipped Epic 7
code (`LlmSuggestionInbox.propose()` would need to become fallible / return a result),
changing its public API and every existing call site's error handling for a policy
that only matters for one new producer.

### Do nothing for v1 (revisit if real usage shows a problem)

Considered — consistent with `LlmSuggestionInbox`'s own "session-only, worst case is
re-run the proposal" philosophy (ADR-012). Rejected as the *default* specifically
because `research/ux.md` §1's approval-fatigue evidence argues pileup actively
degrades review quality rather than being merely inert, and `requirements.md` asks
this open question to be resolved with an explicit decision, not left to accumulate
silently by omission.
