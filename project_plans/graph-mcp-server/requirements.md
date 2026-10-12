# Requirements: graph-mcp-server

**Date**: 2026-09-14
**Type**: new feature (backlog item `06a011e1-0661-47c2-99e6-b6d9587eaf7a`)
**Source**: backlog item "feat: MCP server for LLM-driven search/management of the SteleKit graph"

## Problem Statement

SteleKit has no way for an external LLM client (Claude Desktop, Claude Code, other
MCP hosts) to search, read, or propose edits to a user's graph. Everything
LLM-related today (`project_plans/llm-service/`) is *internal* — SteleKit calling
out to a provider for tag suggestion/voice formatting/synthesis — never the reverse
direction of an external assistant operating on the graph as a tool-using client.

The user's actual workflow already involves an LLM (Claude) doing research,
writing, and knowledge work outside SteleKit. An MCP server turns "copy-paste
findings into my wiki" into "the assistant searches/updates the wiki directly" —
the same class of benefit issue #232 (local REST/automation API) gives
scripts/other apps, for MCP hosts specifically.

## Users / Consumers

- **External MCP clients**: Claude Desktop, Claude Code, and other local MCP hosts,
  connecting as stdio subprocesses (the standard desktop MCP transport).
- **Human user**: reviews and approves/rejects any proposed write before it lands,
  via the existing SteleKit desktop UI.

## Constraints (from item description + repo conventions)

- **Depends on #232** (local REST/automation API) per the item's "References"
  section, *or* may embed the same read/write logic directly if #232 isn't ready
  first (item explicitly allows this fallback).
- **Local-only, v1 Desktop/JVM only** — no remote/network-exposed MCP access,
  matching #232's scope.
- **Approval-gated writes** — every write MCP could trigger must go through the
  same "propose → user reviews → user accepts/rejects → then write" pattern
  `project_plans/llm-service/requirements.md` establishes for LLM-driven graph
  edits. No auto-apply, no exceptions.
- **Repository/DB conventions** (`CLAUDE.md`): all repository calls return
  `Either<DomainError, T>`; all writes route through `DatabaseWriteActor` /
  `GraphWriter`, never `RestrictedDatabaseQueries` directly; graph-scale reads must
  be bounded (no unbounded `getAllPages()`-style calls).

## Known Blocker / Scope Conflict (found during research, not in original item)

`project_plans/llm-service/requirements.md` (the doc this item's approval-gated
pattern explicitly cites) states as a hard constraint: **"No mandatory new
backend/server: this stays client-side within the existing KMP shared codebase...
No new backend service to build or operate."** An MCP server *is* a new backend
process (a stdio subprocess the SteleKit desktop app would need to launch/manage,
or a standalone process sharing the SQLite DB). This item's premise is in direct
tension with that already-documented constraint from the project it explicitly
leans on for its write-approval design. This conflict needs a resolution (revise
the llm-service constraint, or scope the MCP server as an optional side-process the
main app doesn't depend on) before implementation — flagged as an open question
below and as the top planning risk.

**Correction (post-research, verified directly via `git log`/`grep`):** an earlier
draft of this document claimed Epic 7 (the approval-gated inbox) was unimplemented.
That was wrong. `PendingLlmSuggestion`, `LlmSuggestionInbox`, and
`LlmSuggestionWriter` shipped in commit `72e7837d08` ("Epic 7 — approval-gated edit
workflow", merged via `9fd6f89d79`) and are live in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/`, wired into
`StelekitViewModel`/`AppState`, with a working review screen
(`ui/screens/llm/LlmSuggestionReviewScreen.kt`) and staleness re-validation on
accept (`LlmSuggestionWriter.kt`). **This item should reuse Epic 7's inbox** — the
MCP propose-write tool constructs a `PendingLlmSuggestion` and hands it to a
`McpSuggestionSink` (plan.md's adapter interface, backed by
`StelekitViewModel.proposeLlmSuggestion(...)`, since `LlmSuggestionInbox` itself is
a private field with no external accessor — not a direct `LlmSuggestionInbox.propose()`
call), adding an MCP-sourced provenance field rather than building a second approval
queue. See
`project_plans/graph-mcp-server/research/architecture.md` and `pitfalls.md` for
detail. This resolves Open Question 4 below: **reuse, do not rebuild.**

One architecturally-binding consequence of Epic 7's design: `LlmSuggestionInbox` is
**in-memory-only, scoped to the running desktop app process** (deliberate, per its
own ADR) with no IPC into it from outside that process. This means a fully
separate MCP server process would have no way to deliver proposals into the
existing approval UI — which resolves Open Question 3 in favor of embedding the
MCP server's tool-handler logic inside the running desktop app's own JVM process
(see architecture.md), not a standalone process with its own DB connection.

Issue #232 itself is **open, unimplemented, no `project_plans/` entry exists for
it.** It also references #224 (live-query executor, also open/unimplemented) as a
future extension point for its query endpoint, not a v1 dependency.

## Success Metrics

- An MCP host (Claude Desktop/Code) can list, via `tools/list`, at minimum: search
  pages/blocks, read a page, list backlinks, propose a page/block create-or-update.
- A search or read call returns real graph data end-to-end against a local SteleKit
  graph, exercised by an automated test (not just manual smoke test).
- A propose-write call never mutates the DB/files directly — it always lands as a
  pending suggestion the user must accept in the SteleKit UI first, verified by a
  test that asserts no `DatabaseWriteActor`/`GraphWriter` write occurs before
  accept.
- Server runs local-only (stdio or loopback-bound), requires no code change to
  `kmp/src/commonMain` write-gating rules (`@DirectSqlWrite`,
  `DatabaseWriteActor`) — it is a new client of those, not a bypass.

**Outcome metric (post-ship, not a build gate)**: `mcpServerEnabled` is flipped on
by at least one real user session (i.e. a session other than the implementer's own
testing) within 90 days of release. **Triage note (triad-review PM gap)**: this
item is scoped as an off-by-default, power-user-only feature — a
single-user desktop app with no rollout mechanism and a manual per-host setup step
(pre-mortem.md #3). Effort (10 epics/22 stories/~70 tasks, one new pre-1.0
dependency) is high relative to expected reach. This is a deliberate scope
tradeoff, not an oversight: ship it because it unlocks a capability class (external
LLM tool-use over the graph) nothing else in the roadmap provides, not because
near-term usage is expected to be high. If the 90-day outcome metric isn't met,
that's a signal to invest in discoverability (e.g. an install wizard) rather than
a signal the feature was wrong to build.

## In Scope (v1, proposed — subject to research/plan refinement)

- MCP server process (JVM/Desktop), stdio transport (standard for Claude
  Desktop/Code — no HTTP/network layer needed for v1, narrower than #232's HTTP
  API).
- Tools: search pages/blocks, read a page (with blocks), list backlinks, propose
  create/update page or block (queues a pending suggestion, does not write).
- Resources: individual pages addressable **by UUID** for direct reads
  (`note://<uuid>`, v1). Name-based lookup stays a `read_page` *tool* call (search
  first, then read) rather than a second resource-URI scheme — see plan.md Epic 2.3.
- Reuses `PageRepository`/`BlockSearchRepository`/`BlockReadRepository`/
  `ReferenceRepository` (commonMain, JVM actuals already exist) directly if #232
  isn't ready — no bespoke second data-access layer.
- Approval surface: either reuse/co-build the llm-service Epic 7 inbox, or a
  minimal MCP-specific pending-suggestion list reviewed in a new/existing SteleKit
  screen — decision deferred to planning phase.

## Out of Scope (v1)

- Remote/network-exposed MCP access.
- Auto-applying LLM-proposed edits without the existing approval step.
- Building #232's full HTTP/REST surface (only the read/write repository logic it
  would have exposed is reused, per the item's explicit fallback clause).
- Mobile/iOS/Web MCP server (matches #232's JVM/Desktop-first scope).
- Full ETAPI parity, webhooks/event subscriptions.

## Open Questions

1. **Resolved by research (architecture.md)**: scope the MCP server as optional
   and off-by-default (a setting the user enables), embedded in the existing
   desktop app process rather than a mandatory standalone service — the core app
   has zero runtime dependency on it when disabled. This narrows, but does not
   fully eliminate, the tension with llm-service's "no new backend/server"
   constraint; still needs the user's explicit sign-off before implementation
   since it's a documented constraint being reinterpreted, not a code change.
2. **Resolved by research (architecture.md, build-vs-buy.md)**: skip #232
   entirely for v1. It's open/unimplemented with no `project_plans/` entry; call
   the existing `Either<DomainError,T>` repositories directly through
   `GraphManager.getActiveRepositorySet()`.
3. **Resolved by research (architecture.md, pitfalls.md)**: embed the MCP
   tool-handler logic inside the running desktop app's JVM process. `LlmSuggestionInbox`
   is in-memory-only and process-local with no IPC in, so a separate process
   couldn't deliver proposals into the existing approval UI anyway. A thin
   stdio-subprocess launcher shim bridges MCP hosts' subprocess convention to the
   running app (exact IPC mechanism — local socket vs. other — is a plan.md
   decision, not yet made).
4. **Resolved by research (architecture.md, pitfalls.md, ux.md, verified directly
   via `git log`/`grep`)**: reuse Epic 7's `LlmSuggestionInbox` directly, add an
   MCP-sourced provenance field. Do not build a second approval queue.
5. **Resolved by planning (ADR-001, "No token/credential scheme for v1")**: no
   token. The socket's filesystem permissions (`0700`, owning-user-only, see
   plan.md Story 5.1.1) already provide the same "only this OS user" guarantee a
   token would add on top of — a bearer token would authenticate a process that
   already passed a stronger check. Explicitly deferred, not rejected: a
   multi-user-on-one-machine threat model would need a real token scheme (noted in
   ADR-001 as future work, not v1).
6. **Resolved by planning (ADR-002, plan.md Story 3.2.1/3.2.2)**: yes — a
   per-MCP-source pending cap (20) plus a sidebar badge were added.
   **Not fully resolved**: pre-mortem.md #4 (P2, accepted risk) notes the existing
   "Apply all" bulk-accept is reused unmodified for MCP-sourced suggestions, so the
   cap/badge alone don't prevent a user from bulk-accepting without reading — ADR-002
   accepts this as a scoped v1 limitation rather than excluding `mcp:`-sourced items
   from bulk accept; worth reconsidering post-ship if it proves a real problem.
