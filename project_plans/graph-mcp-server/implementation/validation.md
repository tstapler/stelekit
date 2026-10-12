# Validation Plan: graph-mcp-server
**Date**: 2026-09-14

## Happy Path Scenario

Given SteleKit is running with a graph open and the user has enabled "MCP Server"
in Settings, when an MCP client (e.g. Claude Desktop, connected via
`McpLauncherMain`'s stdio↔socket proxy) calls `search_graph` to find a page and
then `propose_block_edit` to suggest a change to one of its blocks, then the
search returns real content from the open graph, the proposal appears as a
pending suggestion (with an "via Claude Desktop" provenance chip) in
`LlmSuggestionReviewScreen` without any write to disk/DB having occurred, and
the user's own "Apply" click in that screen is what actually writes the change.

## Migration Plan Confirmation

Confirmed by reading `implementation/plan.md` in full: it has no "Migration
Plan" heading and no `CREATE TABLE`/schema-change task anywhere in its six
phases — every story reuses existing `commonMain` repositories
(`PageRepository`, `BlockReadRepository`, `BlockSearchRepository`,
`SearchRepository`) and the existing `LlmSuggestionInbox`/`PendingLlmSuggestion`
domain model unmodified (Tech Debt Disposition table, `plan.md:75`). No
`MigrationRunner.all` entry is needed, so the reversibility-migration test this
template would otherwise require is skipped, per the task brief's Step 5.

## Test Stack

- **Unit**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/**` — JUnit
  (`kotlin.test` + `org.junit.Test`, matching `UpgradeResilienceTest.kt`'s and
  `McpAdapterTest.kt`'s own planned shape) against the SDK's in-memory
  `ChannelTransport` (no socket, no subprocess) and fake repositories —
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt`
  and `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt`
  are the existing test-double sources to extend rather than hand-rolling new
  ones per tool test.
- **Integration**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/mcp/**` for
  real-graph/real-SQLite paths (mirrors `MigrationRunnerSchemaSyncTest.kt`'s and
  `LlmSuggestionInboxTest.kt`'s businessTest placement); `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/mcp/**`
  for anything touching a real OS resource businessTest can't reach cleanly
  (Unix-domain socket bind, subprocess spawn, POSIX file permissions) — those
  need `java.nio`/`ProcessBuilder`, which is a JVM-only concern the same way
  `UpgradeResilienceTest.kt` already lives in `jvmTest` for JDBC-driver reasons.
- **E2E / UX**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/mcp/**` and
  `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/mcp/**` Compose tests
  (`createComposeRule()`), following `QrTransferAccessibilityUxTest.kt`'s
  (a11y), `QrTransferContrastTest.kt`'s (WCAG contrast, computed against real
  `ColorScheme` tokens, not a manual tool run), and
  `LlmSuggestionCaptionStatesUiTest.kt`'s (1:1 criterion-to-`@Test` docstring
  convention) exact precedents already shipped in this repo.
- **Naming convention** (confirmed by `grep -rhn "fun \`" kmp/src/jvmTest kmp/src/businessTest`
  against `UpgradeResilienceTest.kt`, `GraphLoaderTest.kt`, etc.): backtick,
  lowercase-first, full-sentence test names describing subject + action +
  observable outcome — no `test`-prefixed or `should`-style names. Followed
  throughout this document.

---

## Requirement → Test Mapping

Rows are grouped by `plan.md` Story (the unit this plan's own file lists are
organized around). Each Story maps to one or more of `requirements.md`'s five
"In Scope (v1)" bullets — see the Coverage summary below the table. Stories
with no data-store/external-call touch (1.1.1, 2.1.2, 3.2.1) get no
Integration row per the task brief's "if data store or external call
involved" qualifier.

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| Story 1.1.1 — SDK dependency resolves (Gradle) | N/A — build command, not a test file | `./gradlew :kmp:dependencies --configuration jvmRuntimeClasspath \| grep -i "kotlin-stdlib\|incompatible"` | Build Verification | No `kotlin-stdlib`/metadata-incompatibility warning after adding `kotlin-sdk-server:0.15.0` |
| Story 1.1.1 — SDK dependency resolves (Bazel) | N/A — build command | `bazel build //kmp:desktop_app` | Build Verification | Bazel resolves the equivalent maven coordinates with no conflict |
| Story 1.1.2 — `McpAdapter` skeleton | `mcp/McpAdapterTest.kt` | `` `McpAdapter buildServer responds to initialize with an empty tools capability over ChannelTransport`() `` | Unit (happy) | Fresh `McpAdapter(fakeGraphManager, fakeSink, { "test-client" })`, `initialize` round-trip |
| Story 1.1.2 — `McpToolError` vocabulary | `mcp/McpToolErrorTest.kt` | `` `GraphNotOpen toCallToolResult returns isError true with a no-graph-open message`() `` (one `@Test` per variant: `AmbiguousPageName`, `InboxFull`, `Timeout`, `Internal`, `UnexpectedFailure`) | Unit (error) | Each `McpToolError` subtype maps to a distinct, human-readable `CallToolResult(isError = true, ...)` |
| Story 1.1.2 — `McpAdapter` depends on `McpSuggestionSink`, never `LlmSuggestionInbox` | `mcp/McpAdapterTest.kt` | `` `McpAdapter compiles and builds a Server with no LlmSuggestionInbox import anywhere in McpAdapter_kt`() `` (grep-based structural assertion, per Task 1.1.2's own AC) | Unit (structural) | `Grep`-driven assertion that `McpAdapter.kt` never references `LlmSuggestionInbox` |
| Story 1.1.2 — `McpClientIdentity` | `mcp/McpClientIdentityTest.kt` | `` `McpClientIdentity resolve returns unknown-mcp-client before any session initializes`() `` | Unit (happy) | Default value before handshake |
| Story 1.1.2 — `McpClientIdentity` | `mcp/McpClientIdentityTest.kt` | `` `McpClientIdentity resolve returns the negotiated client name after onSessionInitialized fires`() `` | Unit (happy) | Real `initialize` handshake over `ChannelTransport` sets `clientVersion.name` |
| Story 1.1.3 — `addTimedTool` passthrough | `mcp/McpToolTimeoutTest.kt` | `` `addTimedTool returns the handler result unchanged when it completes before the timeout`() `` | Unit (happy) | Baseline: normal tool call is unaffected by the wrapper |
| Story 1.1.3 — `addTimedTool` timeout mapping | `mcp/McpToolTimeoutTest.kt` | `` `addTimedTool maps a TimeoutCancellationException to McpToolError_Timeout within the configured timeoutMs`() `` | Unit (error) | `timeoutMs = 100L`, handler `delay(5_000)`, response arrives ~100ms, not 5s |
| Story 1.1.3 — `addTimedTool` broad-`Throwable` mapping | `mcp/McpToolTimeoutTest.kt` | `` `addTimedTool maps an unhandled IllegalStateException to McpToolError_UnexpectedFailure without leaking the raw message`() `` | Unit (error) | Handler throws directly (no `delay`); result names tool + exception class, never `"boom"` |
| Story 1.2.1 — socket bind + permissions | `mcp/McpSocketServerTest.kt` | `` `McpSocketServer start binds a unix domain socket with a 0700-permission parent directory`() `` | Integration (real socket + filesystem, temp `user.home`) | `~/.stelekit/mcp/g1.sock` exists, parent dir is `rwx------` |
| Story 1.2.1 — stop idempotency | `mcp/McpSocketServerTest.kt` | `` `McpSocketServer stop removes the socket file and is safe to call twice in a row`() `` | Integration | Neither call throws; socket file gone after the first |
| Story 1.2.1 — reject second connection | `mcp/McpSocketServerTest.kt` | `` `McpSocketServer rejects a second simultaneous connection with a JSON-RPC -32000 busy error and leaves the first session unaffected`() `` | Integration (error) | Second `SocketChannel` gets the error frame + close within 2s; first session's `tools/list` still completes afterward |
| Story 1.2.2 — byte proxy | `mcp/McpLauncherMainTest.kt` | `` `McpLauncherMain proxies bytes bidirectionally between stdio and the connected socket`() `` | Integration (real socket, real launcher process) | Bytes written to launcher stdin arrive on the socket's read side and vice versa |
| Story 1.2.2 — no server listening | `mcp/McpLauncherMainTest.kt` | `` `McpLauncherMain exits 1 with the graph id named in stderr within 2 seconds when no socket is listening`() `` | Integration (error) | No hang; exit code 1; message names `g1` |
| Story 2.1.1 — `search_graph` happy path | `mcp/tools/SearchGraphToolTest.kt` | `` `search_graph returns a PageHit for a matching page over ChannelTransport`() `` | Unit (happy) | Fake `SearchRepository`, query `"atomic notes"` hits `"Zettelkasten Method"` |
| Story 2.1.1 — `search_graph` limit clamp | `mcp/tools/SearchGraphToolTest.kt` | `` `search_graph clamps a limit above 50 to 50 before calling SearchRepository_searchWithFilters`() `` | Unit (edge) | `limit = 500` in, repository call receives `limit = 50` |
| Story 2.1.1 — `search_graph` no graph open | `mcp/tools/SearchGraphToolTest.kt` | `` `search_graph returns GraphNotOpen without hanging when no repository set is active`() `` | Unit (error) | `getActiveRepositorySet() == null` ⇒ immediate `GraphNotOpen`, no timeout |
| Story 2.1.1 — `search_graph` real data | `mcp/McpEndToEndReadTest.kt` | `` `tools_call search_graph returns a real page from an on-disk graph through ChannelTransport`() `` | Integration (real `GraphManager`, temp on-disk graph) | Proves transport → handler → repository → SQLite → transport, no repository mocks |
| Story 2.1.2 — `list_graphs` happy path | `mcp/tools/ListGraphsToolTest.kt` | `` `list_graphs marks the active graph isActive true and every other graph false`() `` | Unit (happy) | Two registered graphs, `g1` active |
| Story 2.1.2 — `list_graphs` empty registry | `mcp/tools/ListGraphsToolTest.kt` | `` `list_graphs returns an empty array without throwing when no graphs are registered`() `` | Unit (error/edge) | Zero registered graphs is a valid, non-exceptional state |
| Story 2.2.1 — `read_page` by uuid | `mcp/tools/ReadPageToolTest.kt` | `` `read_page by uuid returns the page's blocks via getPageByUuid`() `` | Unit (happy) | Exact uuid match |
| Story 2.2.1 — `read_page` ambiguous name | `mcp/tools/ReadPageToolTest.kt` | `` `read_page by name returns AmbiguousPageName when the same name matches pages in two different sections`() `` | Unit (error) | `pages.name` is only `UNIQUE(name, section_id)` (`SteleDatabase.sq:19`) — cross-section collision must not silently pick one |
| Story 2.2.1 — `read_page` block pagination | `mcp/tools/ReadPageToolTest.kt` | `` `read_page paginates a 300-block page to blockLimit 50 and sets hasMoreBlocks true`() `` | Unit (edge) | Never returns the full tree for a large page |
| Story 2.2.1 — `read_page` journal-date resolution | `mcp/tools/ReadPageToolTest.kt` | `` `read_page by date resolves via getJournalPageByDate regardless of the on-disk filename format`() `` | Unit (edge) | `date = "2026-09-14"` finds a page stored on disk as `2026_09_14` |
| Story 2.2.1 — `read_page` real data | `mcp/McpEndToEndReadTest.kt` | `` `tools_call read_page returns real block content from an on-disk graph through ChannelTransport`() `` | Integration | Same real-graph fixture as `search_graph`'s integration row |
| Story 2.2.2 — `list_backlinks` happy path | `mcp/tools/ListBacklinksToolTest.kt` | `` `list_backlinks returns the wiki-linked block from getLinkedReferences, not a child block`() `` | Unit (happy) | Confirms semantic-reference vs. outline-child disambiguation |
| Story 2.2.2 — `list_backlinks` no backlinks | `mcp/tools/ListBacklinksToolTest.kt` | `` `list_backlinks returns an empty result without throwing when the target page has no backlinks`() `` | Unit (error/edge) | Page exists but is never `[[wiki-linked]]` |
| Story 2.2.2 — `list_backlinks` real data *(coverage addition — see note below)* | `mcp/McpEndToEndReadTest.kt` | `` `tools_call list_backlinks returns a real wiki-linked block from an on-disk graph through ChannelTransport`() `` | Integration | `plan.md`'s Task 6.1.1a only asserts `list_backlinks` appears in `tools/list`, not a real `tools/call`; this row closes that gap so all three read tools get the same real-data proof `search_graph`/`read_page` get |
| Story 2.3.1 — `note://<uuid>` resource content | `mcp/resources/PageResourceTest.kt` | `` `resources_read on a note uuid returns text matching read_page's output for the same page`() `` | Unit (happy) | Same resolver, same content — no second read path |
| Story 2.3.1 — `resources/list` stays empty | `mcp/resources/PageResourceTest.kt` | `` `resources_list returns an empty array and makes zero PageRepository calls against an 8000-page graph`() `` | Unit (error/edge) | Recording fake `PageRepository` proves no unbounded enumeration (CLAUDE.md's graph-scale-reads rule) |
| Story 2.3.1 — `resources/templates/list` | `mcp/resources/PageResourceTest.kt` | `` `resources_templates_list returns exactly one note uri template`() `` | Unit (edge) | Publishes `note://{uuid}` without walking the page table |
| Story 3.1.1 — `propose_block_edit` fresh snapshot | `mcp/tools/ProposeBlockEditToolTest.kt` | `` `propose_block_edit reads the block's live content fresh and stores it as currentContentSnapshot`() `` | Unit (happy) | Never trusts a client-supplied "current content" string |
| Story 3.1.1 — `propose_block_edit` provenance | `mcp/tools/ProposeBlockEditToolTest.kt` | `` `propose_block_edit stores a sourceProviderId matching the mcp-client-name-uuid convention`() `` | Unit (happy) | `"mcp:claude-desktop:<uuid>"`, fresh `generateV7()`, not a JSON-RPC call id |
| Story 3.1.1 — `propose_block_edit` no direct write | `mcp/tools/ProposeBlockEditToolTest.kt` | `` `propose_block_edit never calls DatabaseWriteActor or GraphWriterPort and adds exactly one pending suggestion`() `` | Unit (happy) | Recording fake write-actor stays at zero calls |
| Story 3.1.1 — `propose_block_edit` no graph open | `mcp/tools/ProposeBlockEditToolTest.kt` | `` `propose_block_edit returns GraphNotOpen and leaves the pending map unchanged when no graph is active`() `` | Unit (error) | Guard fires before any `!!` |
| Story 3.1.1 — `propose_block_edit` write gate under real graph | `mcp/McpProposeWriteGateTest.kt` | `` `propose_block_edit leaves on-disk block content unchanged while LlmSuggestionWriter materializeAndWrite still applies it`() `` | Integration | Proves the gate is real, not "nothing happened because nothing was implemented" — asserts both the non-write and the eventual accept-path write |
| Story 3.1.2 — `propose_new_page` happy path | `mcp/tools/ProposeNewPageToolTest.kt` | `` `propose_new_page stores a NewPage suggestion with the proposed title and every proposed block`() `` | Unit (happy) | Two-block tree, `depth`/`order` preserved |
| Story 3.1.2 — `propose_new_page` no graph open | `mcp/tools/ProposeNewPageToolTest.kt` | `` `propose_new_page returns GraphNotOpen and leaves the pending map unchanged when no graph is active`() `` | Unit (error) | Mirrors Story 3.1.1's guard exactly (Task 3.1.2a) |
| Story 3.1.2 — no direct write | `mcp/tools/ProposeNewPageToolTest.kt` | (asserted inside the happy-path test above, reusing Story 3.1.1's recording write-actor harness per Task 3.1.2b) | Unit (happy) | Zero `DatabaseWriteActor`/`GraphWriterPort` calls; no separate businessTest needed since Story 3.1.1's integration row already proves the shared `McpPendingCapGuard`/`ViewModelSuggestionSink` path never writes |
| Story 3.2.1 — per-source cap enforced | `mcp/McpPendingCapTest.kt` | `` `propose_block_edit is refused with InboxFull 20 at the 21st mcp-sourced proposal while 5 matcher-sourced entries stay unaffected`() `` | Unit (happy — cap enforcement is the intended behavior, not a bug) | Pending size stays at 25, not 26; proves per-source not global |
| Story 3.2.1 — race-free check-and-propose | `mcp/McpPendingCapTest.kt` | `` `McpPendingCapGuard proposeIfUnderCap lets exactly one of two concurrent calls at 19 pending reach 20`() `` | Unit (error/concurrency) | Two `async {}`-launched calls against one shared guard; count never reaches 21 |
| Story 3.2.2 — badge count derivation | `llm/StelekitViewModelLlmSuggestionTest.kt` (extend existing file) | `` `pendingMcpSuggestionCount counts only sourceProviderId entries prefixed mcp colon`() `` | Unit (happy) | 3 `mcp:`-sourced + 2 matcher-sourced ⇒ count is 3, not 5 |
| Story 4.1.1 — default off | `ui/StelekitViewModelSettingsTest.kt` *(new — mirrors the existing `isLibsqlDriverEnabled` test if one exists; `Glob` for `*LibsqlDriver*Test*` first)* | `` `uiState mcpServerEnabled defaults to false on a fresh install with no prior PlatformSettings value`() `` | Unit (happy) | No stored `"mcp.server.enabled"` key ⇒ `false` |
| Story 4.1.1 — setter persists | same file | `` `setMcpServerEnabled true persists via platformSettings putBoolean and updates uiState immediately`() `` | Unit (happy) | Round-trips through `StubSettings` |
| Story 4.1.1 — missing-key read-back | same file | `` `mcpServerEnabled reads back false the first time StelekitViewModel initializes with a settings store that has never seen the key`() `` | Unit (error/edge) | Distinguishes "never set" from "explicitly set false" |
| Story 4.1.2 — launcher command java-path resolution | `ui/components/settings/SettingsDialogMcpServerTest.kt` | `` `mcpLauncherCommand resolves the java binary from java_home and never emits a bare unqualified java on PATH`() `` | Unit (happy) | `java.home = /opt/stelekit/runtime` ⇒ command starts `/opt/stelekit/runtime/bin/java -jar` |
| Story 4.1.2 — dev-mode fallback | same file | `` `mcpLauncherCommand falls back to the gradlew runMcpLauncher hint when compose_application_resources_dir is unset`() `` | Unit (error/edge) | No packaged-install resource dir present (running via `./gradlew run`) |
| Story 4.1.3 — controller starts on toggle | `mcp/McpServerControllerTest.kt` | `` `McpServerController starts McpSocketServer exactly once when mcpServerEnabled flips false to true while a graph is active`() `` | Integration (real `StelekitViewModel`, real socket) | Socket file exists afterward |
| Story 4.1.3 — controller never starts without a graph | `mcp/McpServerControllerTest.kt` | `` `McpServerController never calls McpSocketServer start while mcpServerEnabled is true but no graph is open`() `` | Integration (error/guard) | `graphManager.getActiveGraphId() == null` blocks start even with the toggle on |
| Story 5.1.1 — permission self-heal | `mcp/McpSocketPathPermissionTest.kt` | `` `McpSocketServer start corrects a pre-existing 0755 mcp directory to 0700 before binding`() `` | Integration | Simulates a pre-hardening install or manual tampering |
| Story 5.1.1 — idempotent on already-correct permissions | `mcp/McpSocketPathPermissionTest.kt` | `` `McpSocketServer start leaves an already-0700 mcp directory unchanged`() `` | Integration (edge) | Regression guard so the correction logic isn't accidentally loosening anything |
| Story 6.1.1 — `tools/list` completeness | `mcp/McpEndToEndReadTest.kt` | `` `tools_list returns all six registered tool names against a real GraphManager`() `` | Integration | `search_graph`, `list_graphs`, `read_page`, `list_backlinks`, `propose_block_edit`, `propose_new_page` |
| Story 6.1.2 — accept path proves the gate is real | `mcp/McpProposeWriteGateTest.kt` | `` `LlmSuggestionWriter materializeAndWrite changes b1's content to after only once the human accepts`() `` | Integration | Second half of the Story 3.1.1 integration row — without this half, "nothing changed" can't be told apart from "silently broken" |
| Story 6.1.3 — real subprocess/stdio/socket handshake (pre-mortem P1 #2) | `mcp/McpRealSubprocessSmokeTest.kt` | `` `McpLauncherMain subprocess completes a real stdio initialize handshake and returns real search results over a real unix socket`() `` | Integration (real `ProcessBuilder` subprocess, no `ChannelTransport`) | Proves every seam (subprocess → stdio → launcher byte-proxy → socket → `Server`) composes, not just each seam in isolation — the gap every other test in this table leaves open |
| Story 4.1.2 — copy-to-clipboard affordance (pre-mortem P1 #3) | `ui/components/settings/SettingsDialogMcpServerTest.kt` | `` `Copy button on McpServerSettings writes the exact displayed launcher command to the clipboard`() `` | Unit (happy) | Tapping Copy calls `ClipboardManager.setText` with the currently-rendered `mcpLauncherCommand` string |

### Coverage summary against `requirements.md`'s In Scope (v1) bullets

| # | In-Scope bullet (`requirements.md:101-112`) | Covered by |
|---|---|---|
| 1 | MCP server process (JVM/Desktop), stdio transport | Stories 1.2.1, 1.2.2 (socket bind, launcher byte-proxy) |
| 2 | Tools: search pages/blocks, read a page, list backlinks, propose create/update | Stories 2.1.1, 2.2.1, 2.2.2, 3.1.1, 3.1.2 |
| 3 | Resources: pages addressable by name/UUID | Story 2.3.1 |
| 4 | Reuses existing repositories directly, no bespoke data-access layer | Proven by every Integration row above hitting a real `GraphManager`/SQLite-backed repository, not a second read path |
| 5 | Approval surface reuses Epic 7's inbox | Stories 3.1.1, 3.1.2, 3.2.1 (real `LlmSuggestionInbox`/`McpPendingCapGuard`, no second queue) |

**5 / 5 In-Scope bullets covered (100%)**, plus both `requirements.md` Success
Metrics ("real graph data end-to-end" and "never mutates before accept") get
their own dedicated Phase 6 businessTests rather than being inferred from unit
tests alone.

---

## UX Acceptance Tests

Numbered to match `design/ux.md` §4's 12 criteria exactly, following
`LlmSuggestionCaptionStatesUiTest.kt`'s precedent of a 1:1 criterion-to-`@Test`
docstring reference back to this table.

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Enable MCP in ≤2 clicks | `ui/components/settings/SettingsDialogMcpServerTest.kt` | `` `enabling MCP takes exactly two clicks from Settings opened to the toggle in the on state`() `` | ComposeTestRule (`createComposeRule`) | Render `SettingsDialog`, click "MCP Server" category, click the toggle, assert `isMcpServerEnabled == true` and no confirmation dialog appeared |
| 2. Disable MCP in ≤2 clicks, same-frame status update | same file | `` `disabling MCP reverts the status line to Not running within the same composition, no perceptible delay`() `` | ComposeTestRule | Starting from the "on" state, click the toggle off, assert the status `Text` node reads "Not running" with no intervening loading state |
| 3. Copy launcher command in ≤1 click | same file | `` `clicking Copy invokes the copy callback with the exact runnable launcher command containing --graph-id`() `` | ComposeTestRule | Toggle already on; click "Copy"; assert the callback/clipboard payload equals the rendered command string verbatim |
| 4. Provenance distinguishable without reading rationale | `ui/screens/llm/LlmSuggestionReviewScreenProvenanceTest.kt` | `` `a user can tell the MCP-sourced suggestion from the matcher-sourced one by the chip alone, without reading the diff`() `` | ComposeTestRule | Render the screen with one `mcp:`-sourced and one matcher-sourced pending suggestion; assert the chip node exists only on the first and is absent (not just invisible) on the second |
| 5. No dead ends | N/A — documentation cross-check, not a code test | N/A | Manual doc review | Re-read `design/ux.md` §2's four Interactive-Surface "Exit path" columns and confirm none reads empty/"none" — performed as part of this validation pass; see `ux.md:121-125,218-226,263-268,311-317` for the four tables. Not automatable: it asserts a property of the *document*, not of running code. |
| 6. Stale-suggestion error is specific | `ui/screens/llm/LlmSuggestionReviewScreenProvenanceTest.kt` | `` `accepting an mcp-sourced suggestion whose block changed since propose shows the literal This block changed since the suggestion was made snackbar`() `` | ComposeTestRule | Seed a stale `mcp:`-sourced suggestion, click Apply, assert the snackbar text exactly (not a generic "Something went wrong") — reuses `LlmSuggestionWriter`'s existing, already-shipped error string, no new copy |
| 7. Cap-reached legible to the human | `ui/mcp/McpPendingBadgeAndSettingsConsistencyTest.kt` | `` `at the 20-pending cap the sidebar badge and the Settings panel pending-count line both read 20`() `` | ComposeTestRule | Seed 20 `mcp:`-sourced pending suggestions; render both the sidebar and the Settings MCP panel against the same `StateFlow`; assert both surfaces show `"20"` |
| 8. Socket-bind failure never blocks core app use | `ui/mcp/McpFailureBannerDoesNotBlockEditingTest.kt` | `` `a user can create edit and save a page normally while the MCP failed-to-start banner is on screen`() `` | ComposeTestRule | Render the app shell with the banner visible; perform a normal edit-and-save cycle; assert it completes with no additional dialog/click required to route around the banner |
| 9. Keyboard-only operability | `ui/mcp/McpSettingsAccessibilityUxTest.kt` | `` `every new MCP control is reachable via Tab order and activatable via Enter or Space`() `` | ComposeTestRule, `hasClickAction() and hasRequestFocusAction()` | Mirrors `QrTransferAccessibilityUxTest.kt`'s exact assertion pattern, applied to: Settings toggle, Copy button, Review-suggestions button, sidebar badge, banner's Retry/Dismiss buttons |
| 10. Screen-reader labels complete | `ui/mcp/McpSettingsAccessibilityUxTest.kt` | `` `the banner sidebar badge and provenance chip each expose a complete contentDescription, not a bare icon or number`() `` | ComposeTestRule, `onNodeWithContentDescription` | Banner: `contentDescription == message`; badge: `"3 pending MCP suggestions"` not `"3"`; chip: `"Proposed via Claude Desktop"` not just the icon |
| 11. Contrast ≥4.5:1 | `ui/mcp/McpUxContrastTest.kt` | `` `the failure banner's errorContainer pair and the provenance chip's default AssistChip colors both meet WCAG AA 4_5 to 1 in light and dark scheme`() `` | Programmatic WCAG relative-luminance calculation against the real `lightColorScheme`/`darkColorScheme` tokens `Theme.kt` constructs (no screenshot tool) | Follows `QrTransferContrastTest.kt`'s exact pattern: reconstruct the same `ColorScheme` factory calls, compute contrast ratio, assert ≥4.5 for both token pairs in both themes |
| 12. No new confirmation dialog for routine accept | `ui/screens/llm/LlmSuggestionReviewScreenProvenanceTest.kt` | `` `accepting a single mcp-sourced suggestion takes exactly one click on Apply, identical to a matcher-sourced one`() `` | ComposeTestRule | Click "Apply" once on an `mcp:`-sourced item; assert it's removed from the queue with no intervening confirmation dialog (bulk "Apply all" double-confirm is unaffected and untested here — out of scope for this criterion) |

**12 / 12 UX acceptance criteria covered** — 10 as automated Compose/JUnit
tests, 1 (contrast) as an automated non-Compose programmatic calculation
following an existing repo pattern, 1 (no dead ends) as an explicit
documentation cross-check rather than a fabricated code test, named as such
rather than silently omitted.

---

## Coverage Targets and How to Measure

No JVM line-coverage tool (JaCoCo/Kover) is configured anywhere in this repo
— confirmed by `grep -n "kover\|jacoco" kmp/build.gradle.kts build.gradle.kts settings.gradle.kts`
returning no matches. The template's `jacocoTestReport` command does not
exist here, so this plan uses this repo's actual pass/fail gate instead: the
Requirement → Test Mapping table above *is* the coverage instrument — every
Story and every UX criterion has a named, traceable test, and the tables above
already show 100% Story/criterion coverage by construction, not by percentage
threshold.

| Stack | Verification command | Target |
|---|---|---|
| Kotlin/JVM unit (`mcp/**` tool/adapter tests) | `bazel test //kmp:jvm_tests` or `./gradlew jvmTest --tests "dev.stapler.stelekit.mcp.*"` | All new tests green; zero regressions in the existing suite |
| Kotlin/JVM business (real-graph, real-inbox integration) | `bazel test //kmp:business_tests` or `./gradlew jvmTest --tests "dev.stapler.stelekit.mcp.*"` (businessTest is a jvmTest-hosted source set per `kmp/TESTING_README.md`) | `McpEndToEndReadTest`, `McpProposeWriteGateTest` green against a real temp graph |
| Compose UX acceptance | `./gradlew ciCheck` (or `xvfb-run --auto-servernum ./gradlew ciCheck` headless) | All 10 automated UX rows green; screenshot/Roborazzi regressions, if any new ones are added, reviewed manually per existing convention |
| Full local gate before shipping | `bazel test //...` and `./gradlew ciCheck` | Both green — this repo's actual "is it ready" bar (`CLAUDE.md`'s Bazel/Gradle command tables), used in place of a numeric coverage percentage |
