# Research: Build vs. Buy — graph-mcp-server

**Date**: 2026-09-14
**Question**: should the MCP server (protocol layer + SteleKit tool handlers) be
built from scratch, sourced from an existing OSS library, or adapted from an
existing reference implementation?

## 1. Existing OSS library/framework — official Kotlin MCP SDK

**Repo**: [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
(Maven: `io.modelcontextprotocol:kotlin-sdk`, plus `-client`/`-server` split
artifacts). Formerly JetBrains-only (`JetBrains/mcp-kotlin-sdk`), now the
official SDK under the `modelcontextprotocol` GitHub org, "maintained in
collaboration with JetBrains."

Verified via `gh api` and the repo's `README.md` on 2026-09-14:

- **License**: Apache 2.0 (README badge; `gh api repos/.../license` returns
  `NOASSERTION` because GitHub's SPDX matcher didn't auto-detect it, but the
  badge and `LICENSE` file both state Apache 2.0 — compatible with SteleKit).
- **Maturity/activity**: 1,452 stars, 245 forks, 86 open issues, last push
  2026-09-14 (same day as this research), commit cadence of multiple PRs/day
  (dependency bumps + feature PRs). Latest release `0.15.0` (2026-07-28,
  "adds wire-level support for SEP-1686 Tasks and SEP-1036 URL Mode
  Elicitation" — i.e. still tracking newly-ratified spec extensions, not
  stagnant). 27 published versions total. Not a v0.x toy — actively tracking
  the live MCP spec.
- **Platform fit**: genuinely Kotlin Multiplatform — JVM, Native, JS, Wasm
  from one codebase, built on Ktor (Kotlin 2.2+, JVM 11+). SteleKit's
  `kmp/build.gradle.kts` already depends on Ktor 3.1.3 client artifacts (for
  `coil-network-ktor3`), so adding the SDK's Ktor-based transport doesn't
  introduce a new HTTP stack — version alignment (SDK's Ktor version vs.
  3.1.3) needs a compile check in planning, but there's no framework clash.
- **Transports**: stdio, Streamable HTTP, SSE, WebSocket, plus an in-process
  `ChannelTransport` for testing. Stdio is exactly what this project's v1
  scope needs (matches Claude Desktop/Code's subprocess model per
  `requirements.md`).
- **What the SDK gives you**: JSON-RPC 2.0 framing, MCP lifecycle
  (initialize/capabilities negotiation), `tools/list` + `tools/call`
  dispatch, `resources/list` + `resources/read`, prompts, pagination,
  logging, and a coroutine-friendly `addTool(name, description, ...)` /
  `Server(serverInfo, options)` API.
- **What SteleKit still has to hand-write**: 100% of the domain logic — every
  tool handler body (search pages/blocks, read a page, list backlinks,
  propose create/update), the mapping from MCP's JSON tool-call args to
  `PageRepository`/`BlockSearchRepository`/`BlockReadRepository`/
  `ReferenceRepository` calls, `Either<DomainError, T>` → MCP
  `CallToolResult`/`isError` translation, the approval-gate plumbing
  (pending-suggestion queue, never a direct `DatabaseWriteActor`/
  `GraphWriter` call from a tool handler), resource URI scheme for
  page-by-name/UUID addressing, and the process/DB-connection-sharing
  decision (open question #3 in requirements.md) — none of that is in scope
  for any generic MCP SDK, Kotlin or otherwise.

**Verdict: Recommended.** Adopt `io.modelcontextprotocol:kotlin-sdk-server`
(or the umbrella artifact) for the protocol layer. It is actively maintained,
Apache-2.0, already spec-current as of this week, and Ktor-based like the
rest of the KMP module. There is no reason to hand-roll JSON-RPC framing —
see §3.

### Reference/adjacent OSS PKM MCP servers (not directly reusable)

Checked as prior-art for tool surface/API shape, not as code to fork —
none share SteleKit's Kotlin/SQLDelight stack:

| Project | Stack | License | Stars | Last push | Notes |
|---|---|---|---|---|---|
| [MarkusPfundstein/mcp-obsidian](https://github.com/MarkusPfundstein/mcp-obsidian) | Python | MIT | 4,412 | 2026-08-31 | Talks to Obsidian's Local REST API plugin (HTTP), not files directly — closest analogue to SteleKit's "reuse #232's repository layer" fallback design, since it treats the host app as the source of truth rather than reading disk itself. Worth skimming its tool list (`list_files_in_vault`, `search`, `patch_content`, `append_content`) as a naming/shape reference for `tools/list`. |
| [StevenStavrakis/obsidian-mcp](https://github.com/StevenStavrakis/obsidian-mcp) | TS/Node | — | — | — | Reads vault files directly, no running-app dependency — the architectural alternative (independent process owns its own read path) that requirements.md's open question #3 is also weighing for SteleKit. |
| [jimsynz/logseq-mcp-server](https://github.com/jimsynz/logseq-mcp-server) | Rust | MIT | 3 | 2026-02-08 | Talks to Logseq's own local HTTP API. Low adoption (3 stars), but same "talk to the running app's API" pattern as MarkusPfundstein's Obsidian server. |
| [eugeneyvt/logseq-mcp-server](https://github.com/eugeneyvt/logseq-mcp-server) | TypeScript | MIT | 7 | 2025-08-27 (>1yr stale) | Low adoption, stale. Reference only for tool naming, not for architecture. |

None of these are candidates to fork or vendor — different language, no
SQLDelight/`Either`/`DatabaseWriteActor` conventions to preserve, and none
solve the approval-gated-write requirement this project has (most either
read-only or write directly). Their value is purely as a sanity check on
what tool names/shapes other MCP-PKM integrations have converged on
(`search`, `get_file_contents`/read-page, `patch_content`/propose-update,
list/backlinks) — useful input for planning's tool-surface design, not a
build artifact.

## 2. SaaS/managed API angle

None applies. `requirements.md` explicitly scopes this **local-only, v1
Desktop/JVM**, matching issue #232's scope, with stdio or loopback-bound
transport and "no remote/network-exposed MCP access" listed under Out of
Scope. MCP itself is a local-process protocol for this use case — there is
no hosted "MCP-as-a-service" that would apply to a single user's local
Markdown graph; the entire value proposition (Claude Desktop/Code as a
stdio subprocess parent) is inherently local. No further evaluation needed
here — this axis is not applicable, not merely "not recommended."

## 3. LLM-generated/hand-rolled JSON-RPC framing vs. the official SDK

**Not justified.** Hand-rolling JSON-RPC 2.0 framing plus MCP's
lifecycle/capability-negotiation layer over stdio carries pure downside here:

- **Protocol conformance risk is asymmetric.** Claude Desktop/Code's actual
  client behavior (exact `initialize` handshake sequence, capability
  negotiation edge cases, error-code expectations, pagination cursor format)
  is not published as an exhaustively testable spec from SteleKit's side —
  it's whatever the real client does. The official SDK is maintained by the
  same collaboration (Anthropic/JetBrains-adjacent under the
  `modelcontextprotocol` org) that also ships the reference clients, so it
  tracks real client behavior and spec revisions (see the 0.15.0 SEP-1686/
  SEP-1036 changelog entry) as they land — a hand-rolled implementation would
  have to reverse-engineer each of those independently and would drift.
- **No cost saved by hand-rolling.** The SDK is a thin, idiomatic
  Kotlin/coroutines layer (`Server(serverInfo, options)`, `addTool { }`) —
  there's no meaningful integration tax being avoided by not using it, and
  every line of hand-rolled framing is a line SteleKit would have to
  maintain against future spec revisions instead of getting them via a
  version bump.
- **License and dependency profile are clean** (Apache 2.0, Ktor-based,
  already-present Ktor client in the KMP module) — no legal or dependency-
  bloat reason to avoid it.

The only place "LLM-generated implementation" is appropriate in this project
is the SteleKit-specific tool handler bodies and approval-gate wiring — code
with no existing library to source, where SteleKit's own domain conventions
(`Either`, `DatabaseWriteActor`, bounded reads) are the spec to follow, not a
third-party protocol.

**Verdict: Recommended (adopt SDK) / Not recommended (hand-roll).**
Unambiguous — this is not a close call.

## 4. Fork-or-adapt a reference skeleton

The SDK repo ships its own sample:
[`samples/kotlin-mcp-server`](https://github.com/modelcontextprotocol/kotlin-sdk/tree/main/samples/kotlin-mcp-server),
which registers a prompt, a tool, and a resource and supports stdio (default)
and two Ktor-based SSE modes. This is the right starting skeleton — same
language, same SDK, same org, actively maintained alongside the SDK itself
(so it won't drift out of sync with the API the way a third-party sample
could).

- **Pros**: zero translation cost (Kotlin-to-Kotlin), demonstrates the
  `addTool`/`addResource`/stdio-transport wiring SteleKit needs, kept in sync
  with SDK releases by the same maintainers.
- **Cons**: it's a generic "hello world" sample, not a PKM-shaped server —
  contributes nothing to the domain-specific parts (repository wiring,
  approval gate, resource URI scheme for pages).

**Verdict: Recommended** as the starting skeleton for the protocol
bootstrapping (server construction, transport selection, tool/resource
registration plumbing) — not as a source of domain logic, which still has to
be designed fresh against SteleKit's repository layer in the planning phase.

## Summary table

| Option | Verdict |
|---|---|
| Official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) for protocol layer | **Recommended** |
| Obsidian/Logseq MCP servers as reference for tool-surface shape (not code reuse) | **Viable** (research input only) |
| SaaS/hosted MCP angle | **Not applicable** — local-only by explicit scope |
| Hand-rolled JSON-RPC/MCP framing | **Not recommended** |
| SDK's own `samples/kotlin-mcp-server` as a starting skeleton | **Recommended** |
| SteleKit-specific tool handlers / approval-gate logic | Must be hand-written regardless — no existing library covers this |
