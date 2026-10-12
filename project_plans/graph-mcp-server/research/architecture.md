# Research: Architecture — graph-mcp-server

Scope: process model, approval-gate integration, and data-access layer for an MCP server
exposing SteleKit's graph to external LLM clients (Claude Desktop/Code). Resolves
`project_plans/graph-mcp-server/requirements.md`'s Open Questions 1–4. Builds directly on
`project_plans/llm-service/research/architecture.md` (cited by section, not re-derived) and
`CLAUDE.md`'s write-gating/dispatcher/bounded-read rules.

## Headline finding: Epic 7 is not a design — it's shipped code

`project_plans/llm-service/research/architecture.md` (written before Epic 7 was implemented)
and `project_plans/graph-mcp-server/requirements.md`'s "Known Blocker" section (line 58-63)
both state Epic 7 (`PendingLlmSuggestion`/`LlmSuggestionInbox`/`LlmSuggestionWriter`) is
"fully specced but not yet implemented" with "zero matches... in `kmp/src/commonMain` today."

**This is stale.** Epic 7 is fully implemented, wired into the UI, and tested:

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/PendingLlmSuggestion.kt` — sealed
  interface with `BlockEdit`, `TagChange`, `NewPage`, `UnlinkedReference` variants, each
  carrying a mandatory `graphId` and a `currentContentSnapshot` for staleness checking.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionInbox.kt` — in-memory
  `MutableStateFlow<Map<String, PendingLlmSuggestion>>` (line 20), `propose()`/`remove()`
  (lines 24-30), `pendingForGraph(graphId)` (line 33-34).
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionWriter.kt` —
  `materializeAndWrite()` (line 44) re-validates staleness via point lookups
  (`BlockRepository.getBlockByUuid`, `PageRepository.getPageByUuid`, lines 87-106), returns
  `DomainError.ConflictError.ConcurrentWrite` on a changed block (line 94-98) or
  `DomainError.DatabaseError.NotFound` on a deleted target, and writes exclusively via
  `GraphWriterPort.savePage(page, blocks, graphPath)` (line 118, line 148) — never
  `DatabaseWriteActor` directly, matching CLAUDE.md's write convention.
- Wired into the UI: `ui/StelekitViewModel.kt` — `llmSuggestionInbox` field (line 139-140),
  `_uiState.update { it.copy(llmSuggestionReviewVisible = true) }` when the inbox becomes
  non-empty (line 431), `rejectLlmSuggestion(id)` (line 452), `acceptLlmSuggestion(id)`
  (line 462). `ui/AppState.kt:168` — `llmSuggestionReviewVisible: Boolean = false`.
- Tests: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/{LlmSuggestionInboxTest,
  LlmSuggestionWriterTest, PendingLlmSuggestionTest, StelekitViewModelLlmSuggestionTest}.kt`,
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/LlmSuggestionCaptionStatesUiTest.kt`.
- Landed in commit `72e7837d08 feat(llm): Epic 7 — approval-gated edit workflow`, matching
  all 5 stories in `project_plans/llm-service/implementation/plan.md:1168-1388` (7.1 domain
  model, 7.2 inbox, 7.3 ViewModel wiring, 7.4 write path, 7.5 review UI).

This changes the shape of the whole project: it is no longer "co-implement an approval gate,"
it is "add a new proposer that calls an inbox that already exists and is already reviewed by
a human in the running app." That also substantially defuses Open Question 1 (the "no new
backend/server" tension) — see below.

---

## Open Question 4 (requirements.md): reuse Epic 7 or build a narrower MCP-only queue?

**Resolved: reuse Epic 7 directly. Do not build a second queue.**

Given the above, building a parallel MCP-specific pending-write queue would create exactly
the "two competing approval systems" risk requirements.md's Open Question 4 warns against —
except now it would be competing with *shipped* code, not a future one. The MCP propose-write
tool handler should:

1. Construct a `PendingLlmSuggestion.BlockEdit` (edit) or `.NewPage` (create), populating
   `graphId` from the currently active graph (`GraphManager.getActiveGraphId()` /
   `db/GraphManager.kt:702`) and `sourceProviderId` with an MCP-specific value (e.g.
   `"mcp:<tool-call-id-or-client-name>"` — distinguishes MCP-sourced suggestions from
   on-device/remote-LLM-sourced ones and from the Aho-Corasick matcher's
   `"aho-corasick-matcher"` per `PendingLlmSuggestion.kt:66-69`).
2. Call `LlmSuggestionInbox.propose(suggestion)` (`llm/LlmSuggestionInbox.kt:24`) — exactly
   what any other Epic-7 proposer does.
3. Return immediately from the MCP tool call. The proposal appears in the existing review UI
   (`llmSuggestionReviewVisible`), the human accepts/rejects through the existing
   `StelekitViewModel.acceptLlmSuggestion`/`rejectLlmSuggestion` methods, and acceptance goes
   through the existing `LlmSuggestionWriter` → `GraphWriter.savePage` path. **Zero new write
   surface, zero new UI, zero new domain model.**

The only genuinely new piece is the MCP *tool-handler* layer that translates an MCP
`tools/call` request into a `PendingLlmSuggestion` — thin, mechanical, and exactly the
"Isolate via seam" adapter shape (see disposition below). The `PendingLlmSuggestion.currentContentSnapshot` staleness re-check already covers the MCP-specific race the requirements
worried about implicitly: an MCP client proposing an edit against a page that changes before
the human clicks Accept.

One real gap for MCP specifically: `LlmSuggestionWriter`'s staleness check re-validates
*content*, not *authorization* — nothing currently stops a proposal from targeting a page the
MCP client never actually read. That's an acceptable v1 risk (the human still reviews the diff
before accept), not a blocker.

---

## Open Question 1 (requirements.md): the "no new backend/server" conflict

**Narrowed, not fully resolved — but substantially defused by the Epic 7 finding above.**

`project_plans/llm-service/requirements.md`'s constraint is about *SteleKit's own* feature
surface not requiring a new server to operate — i.e., a user who never touches MCP still gets
a fully working app with zero new processes. That constraint is about default behavior, not
about "no MCP server may ever exist." Recommendation: keep the constraint as-is for
llm-service (unchanged — voice/tag/synthesis features still need zero new processes), and
scope the MCP server explicitly as **optional, off by default, and started only from a
Settings toggle** — the core app has zero runtime dependency on it, satisfying both documents'
intent simultaneously. This is a scoping/communication resolution, not a constraint rewrite:
no edit to `llm-service/requirements.md` is needed.

The process-model section below (Open Question 3) shows *how* that toggle avoids introducing
a true second backend service in the "always-running daemon" sense.

---

## Open Question 3 (requirements.md): process model — embedded vs. separate process

**Resolved: embedded — a local IPC transport inside the running desktop app process, bridged
to MCP hosts' stdio-subprocess convention by a thin launcher shim. Not a fully separate process
with its own `GraphManager`/DB connection.**

### Why a separate process is the wrong default, independent of SQLite safety

Even setting aside SQLite concurrency, two facts make a separate always-on process the wrong
choice for v1:

1. **The approval inbox is in-memory and lives in the running desktop app's process**
   (`LlmSuggestionInbox` above — a plain `MutableStateFlow`, no persistence, no IPC surface).
   A separate MCP process cannot call `propose()` on that instance directly; it would need to
   invent a **new** cross-process channel just to reach the inbox — the exact kind of new
   backend surface requirements.md is trying to avoid.
2. **The human must review every write in the SteleKit desktop UI** (requirements.md's own
   "Users / Consumers" section, line 25-26). The desktop app therefore has to be open for the
   approval step regardless of where the read-side tool logic runs. There is no scenario in
   scope where a standalone MCP process usefully operates while the desktop app is closed,
   since nothing it proposes can ever be accepted. This removes the main reason to want a
   second process (availability when the app is closed) from v1 scope entirely.

Given both, the read side gains nothing from being out-of-process, and the write side actively
requires being in-process. Embedding is the strictly better default.

### The stdio mismatch, and its resolution

MCP hosts' stdio convention (Claude Desktop/Code) spawns a **fresh subprocess per session**
and owns its stdin/stdout pipes exclusively. SteleKit's desktop app is a long-running GUI
process whose stdio is already used for its own console/logging — it cannot itself be the
process an MCP host spawns and pipes into.

Resolution: ship two artifacts, not one.

- **The real MCP server logic** (tool handlers, resource reads, `PendingLlmSuggestion`
  construction) runs **inside the existing desktop app JVM process**, gated behind a Settings
  toggle (parallel to `openGitSetup()`/`dismissGitSetup()` wiring precedent in
  `ui/StelekitViewModel.kt` cited by `llm-service/research/architecture.md` §4). When enabled,
  it opens a local transport endpoint — a Unix domain socket on macOS/Linux, a named pipe on
  Windows (no existing precedent for this in the codebase today; `grep` for
  `ServerSocket`/`UnixDomainSocket` across `kmp/src/jvmMain` and `kmp/src/commonMain` found
  only an OAuth-loopback `ServerSocket` in `platform/google/JvmGoogleAuthManager.kt`, unrelated
  — this is new infrastructure, not a reused pattern).
- **A tiny separate launcher binary** (a few dozen lines — no KMP/Compose dependencies) is what
  the MCP host's config actually spawns via stdio, per the standard convention. It does nothing
  but connect to the desktop app's local socket and pipe bytes bidirectionally between its own
  stdin/stdout and that socket (a byte-for-byte proxy — the MCP JSON-RPC framing passes through
  unmodified). If the desktop app isn't running, the shim fails to connect and exits with a
  clear error surfaced to the MCP host as a tool-list failure.

This is a known pattern for "MCP access to an already-running GUI app" (comparable to how some
IDEs/desktop tools expose a running instance to a spawned CLI shim via a local socket) — it is
**not** SteleKit-specific invention, just applied here for the first time in this codebase.

Consequence: **the MCP process never opens a second `GraphManager`/`DriverFactory`/DB
connection at all.** All repository calls run through the same `GraphManager` instance the
desktop UI uses — `GraphManager.getActiveRepositorySet()` (`db/GraphManager.kt:713`) — which
sidesteps the SQLite concurrent-access question in production entirely rather than relying on
it, even though the analysis below shows it would likely be safe for the read-only case.

### SQLite concurrent-access analysis (for completeness, and to size the risk if this
recommendation is revisited later — e.g. a future v2 standalone read replica)

`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/DriverFactory.jvm.kt:47-68` configures every
pooled connection with `journal_mode=WAL`, `transaction_mode=IMMEDIATE`, and
`busy_timeout=10000`. WAL-mode locking in SQLite is OS-file-level (POSIX advisory locks /
Windows locking), not JVM-in-process — so it **does** extend across separate OS processes, not
just separate connections in one pool. A second JVM process opening its own
`PooledJdbcSqliteDriver` against the same `.db` file would:

- **Reads**: safe. WAL mode's entire purpose is letting readers proceed without blocking a
  writer and vice versa; this holds across processes exactly as it does across connections in
  one process's pool.
- **Writes**: unsafe as a *system property*, even though individual writes wouldn't corrupt the
  file. `transaction_mode=IMMEDIATE` + `busy_timeout=10000` (comment at
  `DriverFactory.jvm.kt:40-45`) only solves the single-process `SQLITE_BUSY_SNAPSHOT` race
  described there; it does not replace `DatabaseWriteActor`'s serialization guarantee, which
  is an in-process, application-level invariant with no cross-process equivalent. More
  importantly, `GraphWriter.savePage`'s file-write + DB-write saga (`llm-service/research/
  architecture.md`'s citation of `db/GraphWriter.kt`) and the file-watcher write-suppression
  mechanism it coordinates with are **in-memory, single-process state** — a second process
  performing the same saga concurrently has no way to participate in that suppression, and
  could race the file-watcher into treating its own write as an external change.

Net: a second process would be safe for reads only, and only if it *never* attempts a write —
an easy invariant to state but one with no structural enforcement across a process boundary
(unlike `@DirectSqlWrite`/`DatabaseWriteActor`, which are enforced within one process). That
residual risk, combined with gaining nothing (per the two points above), is why embedding is
recommended over "separate process, reads only" even though the latter is technically less
hazardous than it might first appear.

### Relation to Open Question 5 (auth)

The shim-over-local-socket design gives a natural, low-effort answer to Open Question 5: bind
the socket with filesystem permissions restricted to the current user (standard Unix socket /
named-pipe ACL behavior), which is comparable to stdio's own process-parentage trust model.
This needs no token scheme for v1 — flagged here as a narrowing, not a full resolution, since
the exact platform-specific permission defaults are a planning-phase detail.

---

## Open Question 2 (requirements.md): build on #232 or embed repository calls directly?

**Resolved: embed repository calls directly, skip #232 entirely for v1.** Confirmed via
`gh issue view 232`: issue #232 (local REST/automation API) is **open, unimplemented, zero
`project_plans/` entry**. Its own "Suggested scope" section explicitly frames a CLI as "a thin
client of this API" and treats the query endpoint as building on #224 (also open,
unimplemented) — i.e., #232 itself has no implementation to build on, only a scope proposal.
Building even a "minimal slice" of #232 first would mean building a second HTTP/token-auth
layer (JVM/Desktop, localhost-bound, token-authenticated per #232's own scope) *in addition to*
the MCP transport work above, for no v1 benefit — the item's own text (requirements.md line
30-32) already allows this fallback. This is independent of the process-model choice: even
under the embedded-shim design, tool handlers call repositories in-process, never HTTP.

---

## Data access layer — tool → repository mapping

All four repositories requirements.md names (`PageRepository`, `BlockSearchRepository`,
`BlockReadRepository`, `ReferenceRepository`) are commonMain interfaces already returning
`Either<DomainError, T>` per `CLAUDE.md`; `BlockReadRepository`/`BlockSearchRepository` are
two of the four role interfaces composed into `BlockRepository`
(`repository/BlockRepository.kt:13`). All four are already exposed in one place via
`RepositorySet` (`repository/RepositoryFactory.kt:47-54`,fields `blockRepository`,
`pageRepository`, `referenceRepository`, `searchRepository`), reachable through
`GraphManager.getActiveRepositorySet(): RepositorySet?` (`db/GraphManager.kt:713`). This is
the single seam the MCP adapter layer should depend on — a null result (no graph open) maps to
a clean MCP tool error, not a crash.

| MCP tool | Repository method | Citation |
|---|---|---|
| Search pages/blocks | `SearchRepository.searchWithFilters(SearchRequest)` — unified, filterable by `SearchScope`/`DataType`, ranked (`RankedSearchHit`), paginated (`limit`/`offset`). Narrower single-purpose alternatives: `searchPagesByTitle(query, limit)`, `searchBlocksByContent(query, limit, offset)`. | `repository/SearchRepository.kt:15` (unified), `:12-13` (narrow) |
| Read a page (with blocks) | `PageRepository.getPageByName(name)` or `getPageByUuid(uuid)`, then `BlockReadRepository.getBlocksForPage(pageUuid)` | `repository/PageRepository.kt:29-30`, `repository/BlockReadRepository.kt:52` |
| List backlinks | `BlockSearchRepository.getLinkedReferences(pageName, limit, offset)` — page-name-scoped, bounded/paginated. **Already the precedent for exactly this**: `llm/LlmSynthesisContextBuilder.kt:62-63` uses this same call to build backlink context for LLM synthesis. `ReferenceRepository.getIncomingReferences(blockUuid)` is the finer-grained, single-block variant — use only if a future tool needs block-level (not page-level) backlinks. | `repository/BlockSearchRepository.kt:22` (page-level, primary), `repository/ReferenceRepository.kt:11` (block-level, secondary) |
| Propose create/update page or block | `LlmSuggestionInbox.propose(PendingLlmSuggestion.BlockEdit \| .NewPage)` — see Open Question 4 above. Never calls a repository write method or `DatabaseWriteActor` directly. | `llm/LlmSuggestionInbox.kt:24`, `llm/PendingLlmSuggestion.kt:29-40` (BlockEdit), `:55-63` (NewPage) |

None of these are `@DirectSqlWrite`/`@DirectRepositoryWrite`-annotated calls — all four tools
are read-only against the DB, matching CLAUDE.md's write-gating rules with zero exceptions
needed. The propose tool's only "write" is `LlmSuggestionInbox.propose()`, a synchronous
in-memory map mutation with no DB or filesystem interaction (`llm/LlmSuggestionInbox.kt:16-18`
doc comment: "no async work inside").

---

## Disposition: Isolate via seam

Confirmed, not just assumed. The MCP layer should be a **new adapter package** (e.g.
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/mcp/`, JVM-only per requirements.md's Desktop-only
v1 scope) containing:

- Tool-handler classes that depend on `GraphManager` (for `getActiveRepositorySet()`) and
  `LlmSuggestionInbox` — both already constructor-injectable dependencies per the existing
  `StelekitViewModel`/`App.kt` wiring pattern (`llm-service/research/architecture.md` §4).
- The stdio-shim launcher, a standalone `main()` with no KMP dependencies.
- The local-socket transport, new JVM-only infrastructure with no existing in-repo precedent
  (noted above).

No changes to `commonMain` repository interfaces, `DatabaseWriteActor`, `GraphWriter`, or Epic
7's domain model are needed — every integration point is an existing, stable, already-tested
seam. This satisfies requirements.md's Success Metrics line 79-81 ("requires no code change to
`kmp/src/commonMain` write-gating rules... it is a new client of those, not a bypass") by
construction, not by discipline.

## Key files for planning phase

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionInbox.kt`,
  `LlmSuggestionWriter.kt`, `PendingLlmSuggestion.kt` — reuse directly, no modification
  expected.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt:139-140, 418-481` —
  confirms constructor-injectable `LlmSuggestionInbox` (via `deps.llmSuggestionInbox`), useful
  as the wiring precedent for injecting the same instance into the new MCP adapter.
  `AppState.kt:166-168`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:79-80, 702, 713` — single
  active `RepositorySet`/DB connection despite multi-graph registry; the MCP adapter's only
  data-access entry point.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt:47-54` —
  `RepositorySet` field list.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/{PageRepository,
  BlockReadRepository, BlockSearchRepository, ReferenceRepository, SearchRepository,
  BlockRepository}.kt` — tool-to-method mapping above.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSynthesisContextBuilder.kt:60-100` —
  precedent for bounded page+backlinks context assembly; a useful template for the "read a
  page" MCP tool's response shape (though the MCP tool should return raw data, not an
  LLM-prompt-formatted string).
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/DriverFactory.jvm.kt:33-68`,
  `PooledJdbcSqliteDriver.kt:19-37` — WAL/IMMEDIATE/busy_timeout configuration; cited for the
  cross-process safety analysis above, not needed if the embedded-shim design is adopted.
  `desktop/Main.kt:36` (`fun main()`) — JVM desktop entry point where the Settings-gated MCP
  toggle and local-socket listener would be wired.
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/google/JvmGoogleAuthManager.kt` — the
  only existing `ServerSocket` usage in the codebase (OAuth loopback redirect); confirms no
  existing local-IPC-for-a-running-instance precedent to reuse, only a distant cousin.
- Issue #232 (`gh issue view 232`) — open/unimplemented, zero `project_plans/` entry; its
  "Suggested scope" section is the only artifact, not a partial implementation.
