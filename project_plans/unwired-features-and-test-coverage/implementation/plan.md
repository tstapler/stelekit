# Implementation Plan: unwired-features-and-test-coverage

**Feature**: Make every dormant subsystem either reachable from the shipped app and proven end to end through its real composition root, or removed because live code supersedes it; ship the dev console, desktop and Android scheduled sync wiring, and the asset pipeline (on-device plugins plus consented cloud Claude); and add a repo-wide, run-not-estimated coverage program with a wiring-completeness gate so unwired seams cannot recur.
**Date**: 2026-10-27
**Status**: Planning complete; implementation can start at U0. Repair iterations 1-3 are summarized at the end of the Self-review, and triad round 2 (engineering G1-G11, UX GAP-1..GAP-14, product items) is recorded as repair iteration 4 there, and triad round 3 (engineering B1 and G1-G10, UX items 1-12, product items 1-6) as repair iteration 5. Complexity 4; six owner decisions (OD-1..OD-6, see Unresolved Questions) gate named stories only.
**Success metric 4 is NOT closable inside this plan**: the full pull step (Story 2.4) waits on dev-plan PR-A1/PR-A2, which this plan does not fund; Epic 2 is complete at U2 only for the interim fetch-only tick (staleness visibility), and metric 4 stays "partially met" until U16 ships (see Requirements traceability and OD-6).
**ADRs**: [ADR-001 dormant-code disposition policy](../decisions/ADR-001-dormant-code-disposition-policy.md), [ADR-002 wiring enforcement](../decisions/ADR-002-wiring-enforcement.md), [ADR-003 coverage program](../decisions/ADR-003-coverage-program.md), [ADR-004 asset pipeline composition root](../decisions/ADR-004-asset-pipeline-composition-root.md), [ADR-005 cloud plugin egress controls](../decisions/ADR-005-cloud-plugin-egress-controls.md), [ADR-006 console delivery deltas](../decisions/ADR-006-console-delivery-deltas.md), [ADR-007 sync wiring and cutover](../decisions/ADR-007-sync-wiring-and-cutover.md), [ADR-008 owner-decision items](../decisions/ADR-008-owner-decision-items.md). Built on, not copied from: `project_plans/dev-console-git-sync-fix/decisions/ADR-001` (console package), `ADR-002` (SQL guard and write path), `ADR-004` (scheduled sync) and its `implementation/plan.md`.

Path legend: `...` = `kotlin/dev/stapler/stelekit`; all paths are under `kmp/src/` unless absolute or prefixed `androidApp/`. "Dev plan" = `project_plans/dev-console-git-sync-fix/implementation/plan.md`. Test placement follows repo `CLAUDE.md`: pure logic in `commonTest`, business logic in `businessTest`, Compose behavior in `androidUnitTest` (Robolectric), `jvmTest` only for pixel rendering or desktop-only code; run `scripts/jvm-display-check.sh -- <cmd>` before trusting any `jvmTest` UI result. Every Bazel/Gradle invocation an agent runs unattended is wrapped in `timeout 30m`.

**Facts re-verified 2026-10-27 against HEAD `6d280c12dd` (line refs rechecked; git files changed after 2026-10-10)**:
- `GitSyncService.kt` is 786 lines; `startPeriodicSync` `:751`, `stopPeriodicSync` `:764`, `shutdown` `:774`, `fetchOnly` `:498`, `commitLocalChanges` `:567`, `abortActiveMerge` `:733` (dev plan cites `:634`, stale). `startPeriodicSync` still has no production caller; its tick body is `refreshLocalStatus` + `fetchOnly` (fetch only).
- Dev plan PR-A1 guards are **not shipped**: `grep -rln "RepairNeeded\|MassChange\|ScheduledSyncPolicy\|ColdSyncRunner\|SyncStaleness" kmp/src androidApp/src` returns nothing.
- `GitSyncServiceRegistry` is `androidMain` (`WorkManagerSyncScheduler.kt:253`); it **is** registered in production at `androidApp/.../MainActivity.kt:413` (the `register` call that ADR-004 and the research said was missing exists; it sits in `MainActivity`, not beside `registerGitSyncService` at `GraphContentGitSyncSetup.kt:43-46`). `WorkManagerSyncScheduler.schedule` has one production call, `resumeFor` (`WorkManagerSyncScheduler.kt:106`, reached only after a clone or storage move), so the periodic worker is never enqueued in normal use.
- PR #397 (`RepositorySet.sqlConsole` conflict named in requirements) is **MERGED** (`gh pr view 397`), so that blocker is a rebase check, not a wait. PR #501 (this branch, `fix/android-oom-reconcile-leaks`) is OPEN and mergeable.
- `SettingsDialog.kt:142` and `:229` still gate the Developer category on `onLibsqlDriverToggle != null || onMcpServerToggle != null`.
- The asset table is `asset_index` (not `assets`); `searchAssets` already matches `ocr_text`, `auto_labels` and `tags`; `countUnprocessedAssets` already exists (`SteleDatabase.sq:1189-1193`), so no schema change is needed for progress or search.
- `OtelSpanElement` is used by the `currentSpan` extension in its own file, which `OtelSqlDelightExtensions.kt:33` consumes: a scan false positive, not dormant.
- Repair-iteration facts (grep/`wc` on 2026-10-27): `StelekitViewModel.kt` is **2451** lines; the memory hook is `StelekitViewModel.onMemoryPressure()` at `:1440` (body: `graphLoader.cancelBackgroundWork()`), reached from `MainActivity.onTrimMemory` (`MainActivity.kt:526`, invokes the handler at `:540`) through `onMemoryPressureHandler` (`:76`, registered `:472`) and `GraphContentBootstrapEffects.kt:42`. `DirectSqlWriteOptInAllowlistTest` does **not** exist (40 lines matching `OptIn(DirectSqlWrite` across `commonMain`/`jvmMain`/`androidMain` at HEAD).
- `StelekitAppPlatformIntegrations` is a `data class` in `ui/StelekitAppDependencies.kt:87` (300-line file), not its own file; `StelekitAppDeps.platformIntegrations` defaults to `StelekitAppPlatformIntegrations()` at `:270` and `:292`. Explicit constructions: `desktop/Main.kt:221`, `wasmJsMain/.../browser/Main.kt:364` and `:511`, `MainActivity.kt:475`.
- `DriverFactory.android.kt:174` `createReadDriver` returns a second normal (read-write) `AndroidSqliteDriver`; `DriverFactory.jvm.kt:144`, `DriverFactory.js.kt:23` and `DriverFactory.ios.kt:17` return `null`. `GitShadowWorktree.kt:465` already opens `android.database.sqlite.SQLiteDatabase.openDatabase(..., OPEN_READONLY)`.
- `AirGapGuardTest` has **no allowlist**: it scans only `transfer/` for forbidden import substrings (`AirGapGuardTest.kt:23-44`). Production files that already construct an HTTP client or import `io.ktor.client` (`grep -rl "import io.ktor.client"` over `commonMain`/`jvmMain`/`androidMain`/`wasmJsMain`/`iosMain` and `androidApp/src`, recounted 2026-10-27: **20** files; the looser `grep -rlw HttpClient` gives 23 because it also matches comments and type names, so it is not the rule) include `voice/ClaudeLlmFormatterProvider.kt` (posts to `https://api.anthropic.com/v1/messages`, header `x-api-key`, model constant `claude-haiku-4-5-20251001`, text-only `ClaudeRequest`), `llm/LlmProviderRegistryFactory.kt`, `voice/OpenAiLlmFormatterProvider.kt`, `voice/GeminiLlmFormatterProvider.kt`, `voice/WhisperSpeechToTextProvider.kt`, `llm/CustomOpenAiCompatibleLlmProvider.kt`, `domain/UrlFetcher{Jvm,Android,Ios}.kt`, `domain/WaybackMachineService.kt`, `platform/google/*`, `git/GitHubDeviceFlowClient.kt` and the wasm git clients. Anthropic API keys already live in `llm/LlmCredentialStore.kt` under `llm.anthropic.api_key` (constructed as `LlmCredentialStore(CredentialStore())` at `App.kt:453`, `GraphManager.kt:244`).
- `git_config` (`SteleDatabase.sq:989-1005`) is the only table with credential-shaped columns: `ssh_key_passphrase_key`, `https_token_key`, `oauth_token_key` hold keystore **aliases**, not secret values (the `.sq` has no other `token|secret|password|api_key|credential` column; grep).

---

## Domain Glossary
*(Exact names below are used in code, tests and comments.)*

| Term | Definition | Notes |
|------|-----------|-------|
| `DormantItem` | A production type reachable only from its own file and/or tests, per the scan in Story 0.2 | Row in the Disposition Register below |
| `Disposition` | `Wire` (qualifiers: rebuild, build out, adopt) / `DeleteSuperseded(by)` / `OwnerDecision` / `Keep` (qualifiers: false positive, verify, out of scope (iOS)) | Sealed type in the register tooling; every `DormantItem` has exactly one; the Register and ADR-001 use this one set |
| `CompositionRoot` | A file that constructs services: `desktop/Main.kt`, `androidApp/.../MainActivity.kt`, `SteleKitApplication.kt`, `ui/GraphContent*Setup.kt`, `ui/StelekitAppDependencies.kt`, `repository/RepositoryFactory.kt`, `asset/pipeline/DefaultAssetPlugins.kt` (declares the function `defaultAssetPlugins`) | The fixed root set `WiringCompletenessTest` searches |
| `WiringSeam` | An interface whose implementations must be constructed from a `CompositionRoot` (`BackgroundSyncScheduler`, `AssetPipelinePlugin`, `ConsoleCommand`, `MeasurementDeviceFactory`, `WaybackMachineService`, `CameraProvider`) | The one list lives in `WiringCompletenessTest` and is identical to Task 1.1a's. `Plugin` is not a seam (Task 5.5d deletes it and the old `PluginHost`); `CameraProvider` counts only for the implementations that remain after Task 10.5a |
| `WiringAllowlist` | `kmp/src/businessTest/resources/wiring-allowlist.txt`; lines `ClassName  reason  YYYY-MM-DD  renewals=N` that expire after 90 days, except reason class `out-of-scope-ios` (date field `never`, no renewals, so the iOS pair does not re-fail the gate every 90 days). Reason classes: `out-of-scope-ios` (seeded at U1, Task 1.1b), `awaiting-unit <Un>` (seeded at U1 for every seam implementation no root constructs yet; the unit that wires or deletes the class removes the line in the same PR), `false-positive` / `entry-point` (Task 7.5a), the renewal reasons `awaiting OD-n` / `awaiting pass 12.n`, and `blocked-on-owner <OD-n or pass 12.n>` (see the owner-gated rule below) | Failing entries print class and expiry. **Renewal rule** (requirements metric 1): an entry may be renewed by editing its date only if its reason names the open item it waits on (`awaiting OD-n`, `awaiting pass 12.n` or `awaiting-unit <Un>` for a unit that has not yet merged); the test fails a renewal whose reason names none, and the Story 11.5 summary prints the renewed count. **Renewal cap**: at most 2 renewals (`renewals=2` is the last; the next expiry fails with `renewal limit reached: <Class> awaiting <item>`, and a PR that lowers `renewals` fails). The only exits are wire, delete (OwnerGate), an ADR-001 amendment recorded in Unresolved Questions, or, for an entry owned by an owner-gated unit, conversion to `blocked-on-owner`. **Owner-gated rule (engineering G6)**: an `awaiting-unit` entry whose unit waits on an unanswered OD or an unscheduled owner pass (U9-U11 behind passes 12.2a/12.2b/12.4, U13 behind pass 12.5, U14 behind OD-5, U15 behind OD-1, U16 behind OD-6) may be converted once, at its first expiry, to `blocked-on-owner <item>`: the date field records the day it was set, it never expires and takes no renewals, so a wall-clock the agent does not control cannot turn the gate red. It is not silent: the nightly job and the Story 11.5 summary list every such entry with its age and the item it waits on, the test rejects a reason that names neither `OD-1`..`OD-6` nor a `pass 12.` item, and the wiring or deletion PR removes the line under the stale-entry rule. For every other class, renewal beyond 2 is not allowed and clearing the entry is an owner decision (ADR-001 amendment). **Stale-entry rule**: an entry whose class is constructed by a root, or no longer exists, fails (so a wiring PR must delete its line) |
| `WiringCatalog` | `kmp/src/businessTest/resources/wiring-catalog.txt`; maps each wired seam to its entry-point test class | Test fails if the named class does not exist or does not construct the root |
| `ReachabilityBaseline` | `scripts/ci/reachability-baseline.txt`; committed set of known-unreferenced type names (any type, name-based scan); the CI scan fails on any name not in it | Ratchet: Stories shrink it, never grow it. **Relation to `WiringAllowlist`**: separate inputs of separate tests. `WiringCompletenessTest` reads only the allowlist (seam implementations that no root constructs); the Story 1.2 scan reads only the baseline. A seam implementation that is both unconstructed and unreferenced appears in both until it is wired or deleted; the Story 1.2 CI job fails if an `awaiting-unit` allowlist class the scan reports unreferenced is missing from the baseline (so neither file can be shrunk alone), and shrinking one entry without its twin fails |
| `WiredAppFixture` | Test fixture that composes a `GraphContent*Setup` stack over real SQLDelight with fake platform ports; real `platformCaps()` / device-factory functions are exercised separately by root-function tests (Story 5.2, Task 7.3a) | Android: Robolectric (`androidUnitTest`); desktop: `jvmTest` |
| `CoverageTier` | Enum `T1` (integrity/security/sync/write paths), `T2` (domain logic, view models, services), `T3` (Compose UI and platform glue), `T4` (excluded: generated, DTO, benchmarks, demo) | Defined in `coverage-tiers.yml` |
| `CoverageMap` | `docs/coverage/coverage-map.json` plus rendered `coverage-map.md`: per package line, branch, mutation, tier, churn, rank | Produced by `scripts/ci/coverage-map.py` |
| `CoverageRatchet` | Per-**package** floor equal to that package's last recorded value (the tier sets the target and whether a drop fails: T1/T2 fail, T3 warns); nightly job fails if a T1/T2 package drops below its floor | Not a PR-path gate |
| `ConsoleMode` | Derived per statement, never a user-toggled setting: `ReadOnly` (the default and the only mode for plain `sql`) / `GuardedWrite` (entered for one statement by `sql!` only: confirm + backup, then back to `ReadOnly`) | `sql` is always read-only; a write sent to plain `sql` returns the one string `Denied: plain sql is read-only. Use sql! for writes.`; ADR-006 |
| `DeveloperMode` | Setting `developer_mode_enabled`; default false; read failure counts as false | Gate enforced in `ConsoleSession.run`, not only in navigation |
| `SqlConsoleReader` | Console-owned, engine-enforced read-only connection | Dev ADR-002 item 1 |
| `SqlConsoleWriter` | Actor-routed raw writer with one `@OptIn(DirectSqlWrite)` function | Dev ADR-002 item 2 |
| `QueryDeadline` | Wall-clock cap (default 10 s) plus user cancel for a console query; enforced by cursor-loop checks and a connection-abandon fallback | ADR-006 |
| `Redactor` | The single commonMain secret scrubber used by console output, diagnostics export and the `Logger` sink | Replaces the per-file copies (no alias type is kept) |
| `ConsoleBackup` | `VACUUM INTO` snapshot under `console-backups/`, keep the 2 newest by default (configurable up to 5) | Dev ADR-002 item 4; agrees with Story 3.3, ADR-006 and UW-11 |
| `ScheduledSyncSettings` | `scheduled_sync_enabled` (default false) and `GitConfig.pollIntervalMinutes` | Interval picker values 5, 15, 30, 60 |
| `SyncTick` | One iteration of `PeriodicSyncDriver`; result logged with graph id and wall-clock time | Tick lambda is fetch-only until Story 2.4 swaps it |
| `PeriodicSyncDriver` | New collaborator in its own file (`git/PeriodicSyncDriver.kt`) owning the interval loop, per-tick `Throwable` guard, `SyncTick` log line, busy skip, pause-after-3-consecutive-failures, immediate first tick, and `StateFlow<ScheduledSyncStatus>`; takes `tick: suspend () -> TickOutcome`, a clock and a CEH-guarded scope | `GitSyncService.startPeriodicSync` delegates to it (Story 2.0) |
| `ScheduledSyncStatus` | `{phase, lastSuccessAtMs, nextAtMs, paused: Boolean, consecutiveFailures, lastFailureCause}` exposed by the driver; `phase` is the sealed `SyncPhase` (`NotScheduled`, `WaitingFirstTick`, `Idle`, `InProgress`, `Offline`, `NeedsAttention(cause)`, `Paused`, and on Android only `BackgroundRestricted`, set by an injected `BackgroundRestrictionProbe` when battery saver, Doze or a restricted standby bucket means WorkManager may not run; other platforms never report it) that the D1 status line renders, so no state in `design/ux.md` D1 lacks a type | Story 2.3 and the sync badge read it; wall-clock, not monotonic |
| `PlatformCaps` | Immutable value `{imageLabeler, pdfText, textRecognizer, cloud: CloudPluginFactory?}` passed to `defaultAssetPlugins`; `cloud` is a **factory** `(CloudConsentGate) -> CloudClaudePlugin`, not a start-time config | `PlatformCaps.NONE` sentinel; omission is a compile error (ADR-004, ADR-005) |
| `defaultAssetPlugins` | The single function that builds the `List<AssetPipelinePlugin>` from `PlatformCaps` and the `PluginEnableKey` reads | Only registration site; returns no on-device plugin while no key is set |
| `PluginEnableKey` | Settings key `asset_plugin_<id>_enabled`, `<id>` in `text_recognition`, `image_labels`, `pdf_text`; default false; a read failure counts as false; read by `defaultAssetPlugins` on each drain start (Task 5.2a, unit U8) | The staged rollout depends on it existing in U8: with only the master `asset_pipeline_enabled` on, no plugin runs. The Settings switches that write it arrive in U10 (Task 5.5c) |
| `TextRecognizer` | New port `suspend fun recognize(bytes): Either<DomainError, String>` replacing `OcrPlugin`'s misuse of `ImageLabeler` | ML Kit impl on Android; `NoOpTextRecognizer` elsewhere |
| `PipelineProgress` | `StateFlow<PipelineProgress>` on `AssetPipelineService`: `Idle` / `Running(done, total, failed)` / `Paused(reason)` / `Cancelled(done, total)` / `Finished(done, failed)` / `Stopped(StopReason.GraphSwitched)` (every state the P4 row of `design/ux.md` shows has a type) | Total from `countUnprocessedAssets` |
| `PipelineRunMarker` | Persisted "pipeline started" marker (Settings key) set when a run starts and cleared on clean pause/finish/cancel; the crash-loop breaker (Task 5.1g) counts consecutive starts that find it still set | After 2 unclean exits `asset_pipeline_enabled` is forced to false |
| `AttemptedSet` | Per-run set of asset UUIDs already tried, cleared when a run ends | Bounded by run, not process |
| `CloudConsent` | Sealed: `NotAsked` / `Granted(provider, atMs, scope: CloudBackfillScope)` / `Revoked(atMs)` | Stored per graph in `Settings`; key in the credential store |
| `CloudBackfillScope` | `NewOnly` (default; only assets attached after consent) / `IncludeExisting` (also the images already in the graph that have no description) | Chosen in the consent dialog (Task 6.2a); the pipeline filters cloud-eligible assets by `AssetEntry.importedAtMs >= consent.atMs` unless `IncludeExisting` (Task 6.2c must verify what `importedAtMs` holds for files first registered by the `AssetIndexService` backfill; UNVERIFIED. If it is the index time, a persisted watermark taken at consent time replaces the timestamp); the dialog shows the live count of existing eligible images |
| `ProviderId` | Newtype over String for a cloud provider (`"anthropic"`) | Not a raw string in consent APIs |
| `EgressLedger` | Per-day and per-session upload counters plus one INFO log line per upload | Counted in the pipeline, not the plugin; the per-day counter is persisted in `Settings` so a restart does not reset it |
| `CloudConsentGate` | `suspend fun grant(graphId, provider): GrantedConsent?`; reads `CloudConsent` from `Settings` and the key from `LlmCredentialStore` on every call; returns `null` unless consent is `Granted` for that graph and a key exists | Only mint site of `GrantedConsent` (ADR-005) |
| `GrantedConsent` | Token value (graph id, provider, mintedAtMs) that `CloudClaudePlugin.processAsset` requires as a parameter; constructor is `private` (Kotlin `internal` is module-wide and would let any `commonMain` file mint it); the gate is the only construction scope, via a nested/companion factory visible only to `CloudConsentGate` | Parse-don't-validate: without the token the upload code cannot be called |
| `EgressSurfaceAuditTest` | `businessTest` source audit listing every production file that constructs an `HttpClient` or imports `io.ktor.client` against a committed `egress-allowlist.txt` | Replaces the claim that `AirGapGuardTest` has an allowlist (Story 6.3) |
| `NONE` sentinels | `PlatformCaps.NONE`, `BackgroundSyncSchedulerFactory.None` | Named explicitly by every host; `StelekitAppPlatformIntegrations` fields have no default |
| `WiredFeature` | A feature whose `WiringCatalog` entry names an entry-point test that asserts an observable effect through the real root factory | Success metric 1 counts it as done only after its owner device pass (Epic 12) is recorded and its flag-default decision is written down (on, or off with a dated reason) |
| `OwnerGate` | A task that must not start until the owner confirms in the PR description | Used for every deletion and for OD items |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Disposition of dormant code | Per-item register with named superseding code or named added functionality (ADR-001) | Fowler "Strangler"/evidence-first deletion | Bulk delete; bulk wire | Owner decision 2026-10-27: wire if it adds functionality, delete only superseded or duplicate |
| Wiring enforcement | Source-audit test + baseline-diffed scan + feature catalog (ADR-002) | Existing `AllBusinessTestsCompletenessTest`, `CaptureWriterSourceAuditTest` precedent | R8-only; code review discipline | R8 covers Android only and needs minify; reviews already failed once (24 dead `editor.*` files) |
| Asset services construction | Per-graph stack file `GraphContentAssetSetup.kt` (Service Layer, composition root) | PoEAA Service Layer | Fields on `RepositorySet`; construction inside `StelekitViewModel` | Services need DB actor + fs + platform ML ports; ViewModel is a hotspot; `RepositorySet` is for repositories |
| Plugin registration | Total function of `PlatformCaps` (`defaultAssetPlugins`) | type-driven-design | Ad hoc `register` calls | Adding a plugin edits one function; a missing capability is a compile error |
| Pipeline drain loop | Read offset 0, skip `AttemptedSet`, advance offset only past stuck rows (mirrors `GraphLoader.indexRemainingPages`) | CLAUDE.md "Background indexing" | Fixed `offset += batchSize` (current) | Current loop skips rows because processed rows leave the result set |
| `CloudConsent` | Sealed interface | type-driven-design | Boolean flag | Revocation needs timestamps and provider; illegal "granted without provider" unrepresentable |
| `ConsoleMode` | Sealed interface; writes unreachable in `ReadOnly` | type-driven-design | Boolean `allowWrites` | Compiler-enforced exhaustive handling in the writer |
| Console read connection | Console-owned, non-pooled, engine-enforced read-only connection (JVM `SQLiteConfig.setReadOnly(true)`; Android `SQLiteDatabase.openDatabase(..., OPEN_READONLY)`); never `createReadDriver`; no classifier fallback for the mutation guard | Dev ADR-002; spike 0.3e | `PRAGMA query_only` on pooled connection; reusing `createReadDriver` | Leaks into 8 pooled JVM connections; Android `createReadDriver` is read-write and JVM returns `null`; abandon-and-close must never kill an app connection |
| Query cancel | Cursor-loop deadline checks; if a single `step` blocks, abandon-and-close the console-owned connection | Resilience (timeout + bulkhead) | Rely on coroutine cancel | Coroutine cancel does not interrupt SQLite (pitfalls B5) |
| Redaction | One `Redactor` at every sink (Singleton-by-construction, injected) | GoF Decorator at the sink | Per-feature copies | `ghp_` patterns currently appear in several files (grep); copies drift |
| Scheduled sync driver | `PeriodicSyncDriver` collaborator; `GitSyncService.startPeriodicSync` is a thin delegation (dev ADR-004 keeps the timer in the service's scope, not in `sync()`) | Dev ADR-004; Fowler "Extract Class" | Growing the 786-line `GitSyncService` with tick policy; `DesktopSyncScheduler` + callback | `GitSyncService` is a hotspot (12 commits in 90 days) and `sync()` must not gain scheduling branches; a second scope with no exception handler duplicates the loop |
| Cloud consent enforcement | `GrantedConsent` token minted only by `CloudConsentGate`, required by `CloudClaudePlugin.processAsset`, re-checked per asset in `AssetPipelineService` next to `EgressLedger` | type-driven-design (parse, don't validate) | Boolean flag checked at enqueue; plugin constructed only when consent exists at start | Consent, key and switch are runtime per-graph state; `PlatformCaps` is built once per process |
| Cloud transport | Reuse the Anthropic Messages transport and `LlmCredentialStore("anthropic")` via an extracted shared client (ADR-005) | DRY / single credential owner | Second HTTP client and `VaultCredentialStore` key | Two key stores means two places to enter, forget and revoke the same key |
| Coverage measurement | JaCoCo (Gradle) as the canonical number, PIT tiered | Existing build | Kover; Bazel coverage; SaaS | Zero new dependency; Bazel coverage unproven; SaaS uploads confidential source |
| Coverage gating | Diff coverage 80 (existing) on PRs; per-tier `CoverageRatchet` nightly | Existing + ratchet | Repo-wide percentage gate on PRs | A repo-wide gate on PRs would add CI memory load (pitfalls E5) and punish unrelated PRs |
| Dormant search/RTC items | Evidence-gated: delete when a named live class supersedes, `OwnerDecision` with costed paths otherwise (ADR-008) | ADR-001 | Silent delete | Owner decision |

**Creative pass (Step 0.5): approaches considered**

| # | Approach | Strength | Weakness |
|---|----------|----------|----------|
| A | Wire everything dormant, then test | Maximum functionality recovered | Wires stubs (RTC, Datalog, `CloudClaudePlugin` returns `Labels(emptyList())`): tests pass, feature absent; duplicates live query and sync code |
| B | Delete everything unreferenced, then add coverage | Smallest surface, fastest metric 1 | Contradicts the owner decision; loses the planned asset pipeline and cloud work |
| C | **Per-item register + guardrails first + feature stories through real roots (chosen)** | Wires what is real, builds out what is a stub only where the capability is wanted, deletes only evidenced duplicates, and the guardrail stops recurrence | Largest scope; needs an owner gate on every deletion |

Chosen C. Guardrails ship first so each later wiring PR visibly shrinks `ReachabilityBaseline`.

---

## Disposition Register (every dormant item; Step 3.5 detail)
*(Evidence: `research/features.md` §1-§2, `research/architecture.md` §6, this plan's re-verification. "Adds" = functionality the owner gains. "Real" = what must be built or fixed so wiring is not a stub. Every deletion is an `OwnerGate`.)*

| # | Item | Disposition | Adds / Superseded by | What makes it real (task) | Done = owner pass + flag-default decision |
|---|------|-------------|----------------------|----------------------------|---|
| 1 | `DesktopSyncScheduler` (`jvmMain/.../git/DesktopSyncScheduler.kt`) | **DeleteSuperseded** | `PeriodicSyncDriver` via `GitSyncService.startPeriodicSync` (`GitSyncService.kt:751`), dev ADR-004 "the desktop timer is `startPeriodicSync` inside the service"; the class has no `CoroutineExceptionHandler` and no test | Task 2.1c | n/a |
| 2 | `startPeriodicSync` + `GitConfig.pollIntervalMinutes` (stored, read by nothing) | **Wire** | Remote staleness visible while open now (Stories 2.0-2.3); desktop pull (metric 4) only after Story 2.4, which waits on dev-plan PR-A1/A2 | Stories 2.0, 2.1, 2.3, 2.4 | Required (Epic 12 item recorded; flag default written down) |
| 3 | `BackgroundSyncScheduler.schedule` / `WorkManagerSyncScheduler` enqueue | **Wire** | Android closed-app fetch keeps behind-count fresh | Story 2.2 | Required (Epic 12 item recorded; flag default written down) |
| 4 | `AssetIndexService` | **Wire** | Populates `asset_index`; today the Assets screen can only show an empty index | Story 4.1 (also replaces `collectFilesRecursively` full materialization) | Required (Epic 12 item recorded; flag default written down) |
| 5 | `AssetMoveService`, `replayPendingMoves` | **Wire** | Safe asset moves with WAL replay at open | Story 4.2 | Required (Epic 12 item recorded; flag default written down) |
| 6 | `AssetPipelineService` | **Wire** | Labels, OCR text and PDF text for assets | Stories 5.1 (fix drain offset bug test-first, progress state), 5.2 | Required (Epic 12 item recorded; flag default written down) |
| 7 | `PluginRegistry` | **Wire** | Plugin dispatch | Story 5.2 | Required (Epic 12 item recorded; flag default written down) |
| 8 | `OnDeviceLabelingPlugin` | **Wire** | Image labels, no network | Story 5.4 | Required (Epic 12 item recorded; flag default written down) |
| 9 | `PdfTextPlugin` | **Wire** | PDF text becomes searchable | Story 5.4 | Required (Epic 12 item recorded; flag default written down) |
| 10 | `OcrPlugin` | **Wire (rebuild)** | Real OCR. Today it joins `ImageLabeler` labels (`OcrPlugin.kt:20-27`): labeling, not OCR. ML Kit `text-recognition:16.0.1` is declared and unused | Story 5.3 (`TextRecognizer` port, ML Kit impl, plugin rewrite) | Required (Epic 12 item recorded; flag default written down) |
| 11 | `CloudClaudePlugin`, `CloudEnrichmentConfig` | **Wire (build out)** | Best-quality description/OCR, opt-in. `processAsset` is a stub returning `Labels(emptyList())` after bumping its counter. Reuses the existing Anthropic transport (`ClaudeLlmFormatterProvider`) and `LlmCredentialStore("anthropic")`; `CloudEnrichmentConfig.apiKey` is removed (key is read from the store at call time) | Epic 6 (shared client extraction, HTTP call, `CloudConsentGate`, cap, EXIF strip, revocation) | Required (Epic 12 item recorded; flag default written down) |
| 12 | `CloudVisionPlugin` | **DeleteSuperseded** | `CloudClaudePlugin` (owner decision 2026-10-27: one cloud provider); stub, no HTTP | Task 6.4a | n/a |
| 13 | `MlKitImageLabeler`, `AndroidPdfTextExtractor`, `OnnxImageLabeler`, `JvmPdfTextExtractor` | **Wire** | They are the producers the plugins need; no factory builds them | Story 5.2 (`defaultAssetPlugins`); `OnnxImageLabeler` needs a model file: if absent the capability is `NoOpImageLabeler` and the Plugins row shows "model not installed" | Required (Epic 12 item recorded; flag default written down) |
| 14 | `CoreMlImageLabeler`, `IosPdfTextExtractor` | **Out of scope (iOS)** | iOS has no Bazel/KMP path; requirements exclude iOS | Allowlisted at U1 under the non-expiring reason class `out-of-scope-ios` (Task 1.1b; not Task 7.5a) | n/a |
| 15 | `NoOpPdfTextExtractor`, `NoOpImageLabeler` | **Wire** | Defaults inside `PlatformCaps` | Story 5.2 | Required (Epic 12 item recorded; flag default written down) |
| 16 | `RTCManager`, `RTCOp` (`rtc/RTCManager.kt`, 92 lines of placeholders) | **DeleteSuperseded** | Multi-device is served by `GitSyncService` and folder sync; real-time editing would need a server and CRDT and conflicts with markdown-as-source-of-truth and `GraphWriter`. Evidence: no transport, `emptyList()` bodies, 0 callers, 0 tests, 0 plan mentions (`research/features.md`) | Task 10.2a. Cost of the alternative path is in ADR-008 | n/a |
| 17 | `VectorSearch`, `InferenceWorker`, `PlaceholderInferenceWorker`, `RemoteInferenceWorker`, `InferenceBridge` | **OwnerDecision OD-1** | Adds semantic search. Cannot be wired as is: re-embeds every block per query over a caller-supplied `List<Block>`, violating "graph-scale reads must be bounded"; `InferenceWorker` has no real implementation. **Constraint on OD-1**: even a chunked kNN is O(all blocks) per interactive query, so the build path is only viable if Spike 9.1 measures full-scan latency within budget at graph scale (else an ANN or page-level pre-filter is required) | Conditional Epic 9 (persisted embeddings, bounded kNN, `MigrationRunner` entry) vs deletion; costs in ADR-008 | Required (Epic 12 item recorded; flag default written down) |
| 18 | `DatalogEngine`, `DatalogQuery`, `DatalogPattern`, `VisualQueryBuilder`, `DatalogSearchSupport` | **DeleteSuperseded (folded into `{{query}}`)** | `query/QueryParser` + `QueryExecutor` + `SimpleQuery` (Task/PageProperty/Between/PageRef/And/Or/Not). `execute()` returns `emptyList()`; the regex parser handles flat triples only, all expressible as `{{query}}` forms. live-query-blocks plan flags it as an inert stub | Task 10.3a (also test that a Logseq advanced query shows the existing `UnsupportedForm` message instead of silently empty) | n/a |
| 19 | `JsPluginBridge` | **OwnerDecision OD-2** | Would add Logseq-style JS plugins; needs a JS engine per platform and a sandbox. No code supersedes it; methods log and return `null`; `getJsBridge()` has 0 callers | Delete if confirmed (Task 10.4a); costed alternative in ADR-008 | Required (Epic 12 item recorded; flag default written down) |
| 20 | `PluginHost`, `Plugin` (`platform/PluginSystem.kt`); status bar "N plugins active" (`GraphContentStatusAndBottomBar.kt:54`) | **DeleteSuperseded (by `PluginRegistry`)** | Two enable/disable registries would be two sources of truth. `PluginRegistry` (asset pipeline) becomes the single enabled-state source behind Settings > Plugins; the status bar reads its enabled count as a `StateFlow`. `PluginHost`/`Plugin` have no in-tree plugin (`research/architecture.md` §6). Task 5.5d (OwnerGate OD-5) removes only those two types and rewires the status-bar label and `StelekitAppDependencies` plumbing to `PluginRegistry` in the same PR. `JsPluginBridge` references neither type (verified: `PluginSystem.kt` declares `Plugin`, `PluginHost`, `JsPluginBridge`), so it and the file stay until Task 10.4a (OD-2) | Task 5.5d, Task 10.4a | n/a |
| 21 | `MigrationCli` | **DeleteSuperseded** | `MigrationRunner` (live path, `db/MigrationRunner.kt`); CLI has no `Main.kt` dispatch and no Gradle task | Task 10.1a | n/a |
| 22 | `PluginMigration`, `EavTuple` (`jvmMain/.../repository/Migration.kt`) | **DeleteSuperseded** | `MigrationRunner`/`Migrations.kt`; 0 references anywhere | Task 10.1a | n/a |
| 23 | `command/` package (`Command.kt`, `UndoManager.kt`, `BlockCommands.kt`) | **DeleteSuperseded** | `editor/commands/*` and `db/UndoManager`; 0 importers, 0 tests | Task 10.1a | n/a |
| 24 | `CommandManagerCoordinator` (`ui/CommandManagerCoordinator.kt`, 45 lines) | **Wire (adopt)** | `StelekitViewModel.kt:346-348` builds an identical `CommandManager.create`; the 2026-10-04 extraction landed without adoption. Adopting shrinks the 2451-line ViewModel. If the equivalence test shows a behavioral difference, the task deletes the coordinator instead | Story 7.4 | Required (Epic 12 item recorded; flag default written down) |
| 25 | `HtmlClipboardReader` (jvmMain) | **Wire** | Rich-text paste on desktop through the live `HtmlBlockConverter` | Story 7.2 | Required (Epic 12 item recorded; flag default written down) |
| 26 | `WebcamCameraProvider` (jvm), `WebCameraProvider` (wasm) | **DeleteSuperseded** | Both stubs state `isAvailable=false` and defer to the file picker (`DesktopFilePicker`, HTML file input); they add nothing. Real webcam capture is a new feature (JavaCV / `getUserMedia`) and is not claimed here | Task 10.5a | n/a |
| 27 | `GooglePhotosPickerLauncher`, `PhotosPickerApiClient` | **Wire** | Import photos from Google Photos; Google sign-in is already wired (`GoogleAccountSettings`) | Story 8.1 (OAuth device pass is an owner blocker) | Required (Epic 12 item recorded; flag default written down) |
| 28 | `DriveExportService`, `DriveFileBrowserViewModel`/`DriveFileBrowserScreen` | **Wire** | Export annotated images and sidecar to Drive; browse Drive | Story 8.2 | Required (Epic 12 item recorded; flag default written down) |
| 29 | `KtorWaybackMachineService`, `rememberArchiveUrlAction`, `BlockItem.onArchiveUrl` hook (`BlockItem.kt:91,550`) | **Wire** | Right-click a URL to archive it; the UI hook exists and the service is never constructed | Story 7.1 | Required (Epic 12 item recorded; flag default written down) |
| 30 | `KeyboardEmulationDeviceFactory` | **Wire** | Keyboard-wedge meters always available as the fallback (its own KDoc says register at startup) | Task 7.3a | Required (Epic 12 item recorded; flag default written down) |
| 31 | `AndroidUsbSerialFactory` (stub delegating to `NoOpBleScanner`) | **Wire (build out)** | USB-OTG serial meters; needs the `usb-serial-for-android` dependency (LGPL via dynamic aar, accepted in the image-meter plan) | Task 7.3b; hardware pass optional blocker | Required (Epic 12 item recorded; flag default written down) |
| 32 | `NoOpErrorTracker` | **DeleteSuperseded** | `ErrorTracker` is consumed only by `desktop/Main.kt` (local `JvmErrorTracker` breadcrumbs); no other module takes an `ErrorTracker`, so the null object has no consumer | Task 10.1a | n/a |
| 33 | `ICommandRegistry` | **Keep (false positive)** | Interface seam implemented in `editor/commands/CommandRegistry.kt`; recorded in the allowlist | Task 7.5a | n/a |
| 34 | `InitializingDebugScreen` | **Keep (false positive)** | Used inside `SettingsDialogHost.kt` | Task 7.5a | n/a |
| 35 | `OtelSpanElement` | **Keep (false positive)** | Used by `currentSpan` extension in-file, consumed by `OtelSqlDelightExtensions.kt:33` | Task 7.5a | n/a |
| 36 | `jvmMain/.../benchmarks/*Benchmark` | **Keep (verify)** | Entry points; Task 7.5a confirms they are launched by Gradle/JMH and adds them to the allowlist | Task 7.5a | n/a |
| 37 | `BackgroundSyncScheduler` interface (port for closed-process scheduling) | **Keep** | Port for closed-process scheduling; implemented by `WorkManagerSyncScheduler`; wired by Story 2.2 and a seam in `WiringCompletenessTest` | Story 2.2 | n/a |
| 38 | `IOSKableBleScanner`, `IosMediaAttachmentService`, `LidarHardwareCheck`, `UrlFetcherIos` (all under `iosMain`) | **Out of scope (iOS)** | Same reason as row 14: iOS has no Bazel/KMP path here. Found by the U0a scan (Task 0.2) after the register was written | Allowlist under `out-of-scope-ios` (non-expiring) | n/a |
| 39 | `AppStateOptics`, `BlockOptics`, `PageOptics`, `PropertyOptics`, `CursorState`, `LruWeigher`, `NamespaceUtils`, `PropertiesParser`/`PropertiesResult`, `MeasureTemplateResolver`, `ChunkChecksum`, `LfsPointerSniffer`, `GitObjectContentVerifier`, `DesktopFileSystem`, `AssetPendingMove`, `ExplanationDoc`/`ExplanationContent`/`TutorialDoc`/`TutorialContent`, `BenchmarkRegistry`/`GraphPreset` | **Unclassified (owner triage)** | Reported dormant by the U0a scan but absent from the original register. Not yet read for intent; some are probably helpers used only in-file or by tests, some may be leftovers. No disposition is assumed here | Triage in U14: each gets Wire, Keep (false positive, allowlist) or DeleteSuperseded. Any deletion needs OD-5 confirmation for that name | n/a |

Deletion mechanics (all `OwnerGate`): remove the types and their tests, remove names from `AllBusinessTests`'s class list (its completeness test fails otherwise), run `./gradlew detekt` and compile for Android, JVM, wasm, then the Bazel `//kmp:business_tests` class-count check (recount from the real run), per `editor-subsystem-dead-code/research/architecture.md`.

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `StelekitViewModel.kt` (2451 lines, 91 commits per the research) | God object; inline `CommandManager.create` duplicates the coordinator; `onMemoryPressure()` (`:1440`) → `graphLoader.cancelBackgroundWork()` | Isolate via seam | All new services live in `GraphContent*Setup.kt` stacks; the only edits are one call in `onMemoryPressure()` to a `MemoryPressureListeners` registry that the asset stack subscribes to (Task 5.1d) and removing inline command setup (Story 7.4) |
| `GraphManager.kt`, `GraphLoader.kt` | Hotspots (1601 / 1970 lines) | Isolate via seam | Asset stack starts after `onFullyLoaded`; no edits except reading existing hooks |
| `RepositoryFactory.kt` / `RepositorySet` | Optional capability fields | Extend as-is | One nullable `sqlConsole` field, precedent `perfExporter` at `:63`; #397 already merged, so rebase check only |
| `GitSyncService.kt` | Hotspot (786 lines, 12 commits in 90 days); `sync()` must not gain scheduling branches (dev ADR-004); the plan would otherwise add per-tick guard, `SyncTick` logging, busy skip, wall-clock last-success, pause-after-3-failures, countdown state and an immediate first tick | Isolate via seam (refactor-first) | Task 2.0a extracts `PeriodicSyncDriver` into its own file with an injected `tick` lambda, clock and CEH scope; `startPeriodicSync` becomes a thin delegation; Story 2.3 reads the driver's `StateFlow<ScheduledSyncStatus>`; Story 2.4 swaps only the tick lambda (`runScheduledSync`) |
| `DesktopSyncScheduler`, three timer mechanisms | Duplicate loops, one unguarded | Refactor-first | Collapse to the in-service loop before wiring (Story 2.1) |
| `AssetPipelineService.drainUnprocessed` | Offset skip defect; `attemptedUuids` process-lifetime growth | Refactor-first | Failing test first, then fix (Stories 0.4, 5.1) |
| `AssetIndexService.collectFilesRecursively` | Materializes the whole asset tree | Refactor-first | Replace with streaming bounded walk in Task 4.1c |
| `OcrPlugin` | Misnamed: wraps `ImageLabeler` | Refactor-first | Port rewrite in Story 5.3 before registration |
| `SettingsDialog.kt` | Category visibility predicate hard-wired to libsql/MCP toggles | Refactor-first | Task 3.1d changes the predicate before the toggle is added |
| Per-file secret scrubbers (`GitOperationSupport.kt`, `CloudEnrichmentConfig.kt`, others) | Several redaction copies | Refactor-first | Tasks 3.1b and 3.1g-3.1i consolidate into one `Redactor` before the console depends on it |
| `PluginSystem.kt` | Placeholder host with a dead status-bar label; would be a second enabled-state registry beside `PluginRegistry` | Refactor-first | Story 5.5 makes `PluginRegistry` the single source and rewrites the status bar to read it; Task 5.5d removes `PluginHost`/`Plugin` (OwnerGate OD-5); `JsPluginBridge` and the file are removed by Task 10.4a only (OwnerGate OD-2 and OD-5) |
| `ui/StelekitAppDependencies.kt` (300 lines, 7 commits in 90 days) | `StelekitAppPlatformIntegrations` defaults hide missing hosts | Extend as-is | New fields (`backgroundSyncSchedulerFactory`, `platformCaps`) get explicit `NONE` sentinels and the default constructions at `:270`/`:292` are updated; the 4 host constructions (`desktop/Main.kt:221`, wasm `Main.kt:364`/`:511`, `MainActivity.kt:475`) each name their value (Tasks 2.2a/2.2e, 5.2a/5.2d) |
| `ui/ScreenRouter.kt` (510 lines, 4 commits in 90 days) | `Screen.Console` and the Drive route add `when` branches | Isolate via seam | Console and Drive routes registered through a route table entry in their own files (`ConsoleRoute.kt`, `DriveRoute.kt`) so `ScreenRouter.kt` gains one lookup, not two inlined screens (Tasks 3.1e, 8.2a) |
| `ui/AppState.kt` (293 lines, 11 commits in 90 days, highest churn of the touched UI files) | Console/Drive flags add global state | Isolate via seam | Console visibility stays in `ConsoleSession`/`DeveloperMode` state; `AppState` gains only the `Screen` enum entries (Tasks 3.1e, 8.2a) |
| `ui/components/SearchDialog.kt` (704 lines, 1 commit in 90 days) | Assets section and Semantic toggle | Isolate via seam | Assets section is its own composable plus a `SearchSource` implementation; `SearchDialog.kt` only adds the section slot (Task 5.6a, 9.4a) |
| `ui/components/settings/SettingsDialog.kt` (481 lines, 6 commits in 90 days) | Developer-category predicate is hard-wired (`:142`, `:229`); new Plugins/Console rows | Refactor-first | Task 3.1d replaces the predicate with a list of category-visibility lambdas before adding the console toggle |

---

## Migration Plan

- **Schema**: no table or column change in the committed scope. `asset_index` already has `cloud_description TEXT` (nullable), `ml_tags_source TEXT NOT NULL DEFAULT 'NONE'`, `ocr_text`, `auto_labels`, `ml_processed`, `ml_failed` (`SteleDatabase.sq:1143-1163`, created by `MigrationRunner` migration `asset_index_table` at `MigrationRunner.kt:454-475`), plus `updateAssetCloudDescription` (`SteleDatabase.sq:1211`) and `AssetRepository.updateCloudDescription(uuid, text, mlTagsSource)`. Cloud-derived text therefore has its own storage and a source marker today, so ADR-004's rule (never mislabeled text in `ocr_text`) and UW-56's "AI description" label are met without a new column; adding `ai_description`/`ai_description_source` would duplicate `cloud_description`/`ml_tags_source` (decision recorded in ADR-004/ADR-005). The real gap is the read side: `searchAssets` matches `ocr_text`, `auto_labels` and `tags` but not `cloud_description`, so Task 6.2d adds it to the projected search queries. That is a SELECT-only `.sq` change: regenerate and commit `kmp/src/generated/sqldelight/` (per repo `CLAUDE.md`); reversibility: revert the PR (no data written in a new shape); zero-downtime: no DDL, so `MigrationRunner.all` is unchanged and `MigrationRunnerSchemaSyncTest` (which enforces `CREATE TABLE IF NOT EXISTS` names only) stays green. The queries `selectUnprocessedAssets`, `countUnprocessedAssets`, `searchAssets` already exist. Tasks 5.1f and 5.6a add read-only projected queries (`selectUnprocessedAssetsLight`, `searchAssetsLight`, `selectAssetsSearchPreview`; no `ocr_text` or `auto_labels` blob in list rows); any `.sq` change requires `./gradlew :kmp:generateCommonMainSteleDatabase` + the documented `rsync` and a commit of `kmp/src/generated/sqldelight/`. If Task 6.2d finds that a new column is unavoidable after all (for example the existing `ml_tags_source` value set cannot carry the source), the fallback is a nullable `ALTER TABLE ... ADD COLUMN` entry in `MigrationRunner.all` (migrations use the `PRAGMA table_info`-guarded add-column helper at `MigrationRunner.kt:44-53`), the `.sq` column plus regenerated sources, and a note in `MigrationRunnerSchemaSyncTest` coverage; additive and nullable, so rollback leaves an unused column.
- **Conditional (Epic 9 only if OD-1 = build)**: new table `block_embeddings` must be added to `SteleDatabase.sq` **and** `MigrationRunner.all`; `MigrationRunnerSchemaSyncTest` enforces it. Reversibility: additive table, drop on rollback; zero-downtime: created `IF NOT EXISTS`, backfill is background and bounded.
- **Console backups and other new on-device state**: files under `console-backups/` are not a schema change. New on-device state added by this plan is the console backups and recovery directories, the `CloudConsent` keys, the `PipelineRunMarker` and the per-day egress counter (all in `Settings`). `androidApp/src/main/AndroidManifest.xml:34` already sets `android:allowBackup="false"` and no backup-rules XML exists; Task 3.3c additionally ships exclusion rules for all of the above and a manifest test, so the exclusion holds even if `allowBackup` is later enabled (a restored consent or run marker on a new device would be wrong).
- **Rollback**: each unit is a separate PR; revert the PR; settings keys added are read with safe defaults so reverting leaves stale keys harmless.

## Observability Plan

- **Logs** (all via `Logger`, persisted sink, redacted by `Redactor` before write): one line per `SyncTick` (graph id, decision, outcome, wall-clock time, last-success time); one line per console command (verb, risk, outcome, duration, never arguments with secrets); one INFO line per cloud upload (asset uuid, bytes, provider, never content); pipeline start/stop/pause with counts; every `WiringCompletenessTest` failure prints class and roots searched.
- **Metrics**: `PerformanceMonitor` spans: `asset.pipeline.process` (per asset, plugin id), `asset.index.backfill.batch`, `console.sql.read` and `console.sql.write` (row count, duration, truncated flag), `sync.tick`. Operations over 100 ms get a span. Startup heap before/after enabling the asset stack is recorded by a perf test (Task 5.2b) and compared using the PR #501 export-based method.
- **Alerts**: none paging (single-user app). Failures surface in-app: sync badge and staleness chip, pipeline "N failed - view log" row, Logs screen. Nightly CI: coverage ratchet failure opens an issue; wiring allowlist expiry fails the test.

## Risk Control

- **Feature flags** (all default off until verified on device): `developer_mode_enabled`, `scheduled_sync_enabled`, `asset_pipeline_enabled`, per-plugin `asset_plugin_<id>_enabled` (read by `defaultAssetPlugins` from U8, Task 5.2a, default false: with only `asset_pipeline_enabled` on, no on-device plugin is constructed and the drain does not start, so "indexing only" really runs only `AssetIndexService`; the Settings switches that write the keys arrive in U10, Task 5.5c), `CloudConsent` per provider and graph. Gates are enforced in the service entry point, not only in the UI (pitfalls A7); each has a "flag unset means inert" test. Cloud consent is enforced twice: `CloudClaudePlugin.processAsset` cannot be called without a `GrantedConsent` token, and `AssetPipelineService` re-checks the gate per asset next to `EgressLedger`.
- **Release exclusion (pre-mortem P1-1)**: the `sql!` write path and the restore command are compiled out of release builds (`ConsoleBuildFlags.WRITES_ENABLED` false for release variants and desktop distributables) until the owner device pass 12.1 passes on a COPY of the real 451 MB DB; flipping the flag is a separate one-line PR (Task 3.5c, unit U18) that cites the recorded pass.
- **Crash-loop breaker (pre-mortem P1-2)**: the asset pipeline persists a `PipelineRunMarker`; two consecutive unclean exits force `asset_pipeline_enabled=false` (Task 5.1g). Indexing (U7/U8) and on-device plugins (U9) ship in separate releases; the mechanism is the per-plugin keys of Task 5.2a, which stay false in the U8 release, so no plugin runs under the master flag alone (inert-when-unset tests UT-164 and IT-102).
- **Indexing before the heap gate (engineering G6)**: U7 may merge, but `asset_pipeline_enabled` stays false in every shipped build until U8's Task 5.2c has a recorded pass; the interim switch is hidden in release variants (`AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE`), flipped by a one-line PR (Task 5.2e, unit U19) that cites the pass; pass 12.2a runs on a debug/internal build before that flip.
- **No background notifications (UX GAP-10)**: backfill runs only while the app process is alive and honors the charger gate; scheduled sync checks run via WorkManager while the app is closed. Neither posts a notification nor starts a foreground service in this plan; a paused backfill or paused sync is visible only in-app (Settings rows, the sync badge) and in the Logs screen. Revisit only if the pass 12.2a shows the OS killing a charging backfill.
- **Rollback**: standard revert via PR close + revert commit per unit; no unit depends on a flag flip to roll back.
- **Staged rollout**: console ships dark; sync interim tick is fetch-only; pipeline starts with indexing only (master flag on, every `asset_plugin_<id>_enabled` key false), then on-device plugins (keys set per plugin, Task 5.5c), then cloud (consent); each stage waits for its owner device pass (Epic 12).
- **Hard rules re-checked in every story**: no `rememberCoroutineScope()` into long-lived objects; long-lived scopes carry a `CoroutineExceptionHandler`; plugin and tick bodies catch `Throwable` and rethrow `CancellationException`; no unbounded repository read; no write outside `DatabaseWriteActor`.

## Unresolved Questions
*(Each is an `OwnerGate` on the named story only; everything else proceeds.)*
- [ ] **OD-1** Build persisted-embedding semantic search (Epic 9) or delete `VectorSearch` and family? Recommendation: build, because it adds a capability and the infrastructure (ONNX Runtime on JVM and Android, model download plumbing) exists; cost and alternative in ADR-008. **Constraint**: chunked kNN is bounded in memory but still O(all blocks) per interactive query on a 512 MB phone at 9.5k pages, so Spike 9.1 must record full-scan latency at graph scale and the build is only confirmed if it fits the budget (or an ANN / page-level pre-filter fallback fits). Blocks Stories 9.1-9.4 and the deletion of `VectorSearch`; owner.
- [ ] **OD-2** Delete `JsPluginBridge` (recommended; no superseding code and no runtime) or fund a JS engine and sandbox (ADR-008 cost)? Blocks Task 10.4a; owner.
- [ ] **OD-3** Desktop OCR: ship `NoOpTextRecognizer` on JVM (recommended default; ML Kit is Android-only) or add Tesseract/tess4j (native packaging per OS)? Blocks nothing in the committed scope; Android OCR proceeds. Owner.
- [ ] **OD-4** Do you own a USB-serial measurement device to verify Task 7.3b on? If not, the stub is built and verified with a fake port only. Blocks the optional device pass; owner.
- [ ] **OD-5** Confirm each deletion in the Disposition Register (#1, #12, #16, #18, #20, #21-#23, #26, #32) in the PR description at implementation time. Blocks Tasks 2.1c, 5.5d, 6.4a, 10.1a, 10.2a, 10.3a, 10.5a, plus the two conditional fallback deletions (#24 `CommandManagerCoordinator` in Task 7.4a, #36 unlaunched benchmarks in Task 7.5a, each needs a row confirmation before it deletes anything); Task 10.4a additionally needs OD-2; owner.
- [ ] **OD-6** Schedule dev-plan PR-A1 guards and PR-A2 policy (the pull half of scheduled sync, metric 4)? Without a decision U16 never starts. Decision requested by the time U2 is released: name who runs them and by when, or record "not scheduled" (metric 4 then stays "visibility met, pull pending"). Cost in ADR-008; owner.
- [ ] Spike results (Story 0.3, delivered in unit U0) fix console details that the dev ADR-002 left "pending spikes"; they are a **merge gate** for the SQL read and write units (U4, U5) and for restore (U6), recorded in the ADR-006 addendum before those PRs merge; owner device is not needed for them.
- [ ] Dev plan PR-A1 guards (Tasks 2.3e, 2.3f), Task 2.2d0 (first-sync review; UW-16 is deferred to U16 because this plan does not fund it) and PR-A2 (`ScheduledSyncPolicy`, Task 2.4a) have not shipped; Story 2.4 here is blocked on them. **Metric 4 cannot close inside this plan**: the pull step lives in Story 2.4/U16, which is a declared dependency on those dev-plan units (listed in the Delivery units table and wall-clock blockers). Owner decides whether to schedule them from the dev plan (no re-spec here); until they ship, Epic 2 delivers fetch-only staleness visibility and M4 is reported "partially met".

## Delivery units (independently shippable PRs)

| Unit | Contents | Depends on |
|------|----------|-----------|
| U0 | Stories 0.1, 0.2, 0.3 (baselines, scan, capability spikes; the spike results and ADR-006 addendum are the merge gate for U4/U5/U6); Task 0.4a moves to U8 (a red test must not merge alone). **Story 0.3 may ship as its own PR (U0b)**: U1 depends only on Task 0.2, so an AVD or KVM problem that blocks the emulator-gated Tasks 0.3d/0.3e stalls U0b and the console units that cite its result (U4/U5/U6), not U0a (Stories 0.1, 0.2) or U1 | none |
| U1 | Epic 1 (guardrails; `WiringCompletenessTest` in `businessTest` with Bazel resource bundling, Tasks 1.1c/1.1f). **Merges green** because Task 1.1b seeds the expiring `awaiting-unit <Un>` allowlist entries for every seam implementation that no root constructs yet (plus the non-expiring iOS pair); each later unit deletes its entries in the same PR | U0 (0.2) |
| U2 | Story 2.0 (extract `PeriodicSyncDriver`, behavior-preserving, first PR) then 2.1 + 2.2 + 2.3 (sync wiring, interim fetch tick, settings UI). Epic 2 is **not** complete at U2: metric 4 waits on U16, and UW-16 (first-sync review gate) is deferred to U16 with dev Task 2.2d0 | U1 (Story 2.0 also waits for U1); Task 2.1c needs OD-5 |
| U3 | Story 3.1 (console core, dark) | U1 |
| U4 | Story 3.2 (SQL read) | U3; **U0 spikes 0.3a-e recorded (merge gate)**; #397 rebase check (Task 3.2a) |
| U5 | Story 3.3 (`sql!` guarded writes + backup; write code compiled out of release builds, Task 3.3e; emulator concurrent-write test, Task 3.3f; FTS consistency test, Task 3.3g) | U4; U0 spikes 0.3a-e recorded (merge gate; if Spike 0.3d finds neither interrupt nor abandon-and-close safe on `requery`, Android ships without `sql!` in the first slice) |
| U6 | Stories 3.4, 3.5 (restore, console E2E/a11y, first-open smoke test); restore stays compiled out of release builds until owner pass 12.1 on a COPY of the real DB; the one-line release flip is U18 | U5 |
| U7 | Story 4.1, 4.2 (asset indexing and move; Task 4.1d adds the minimal Settings > Plugins "Asset indexing" switch for `asset_pipeline_enabled`). **Release rule**: `asset_pipeline_enabled` ships default off and the switch is hidden in release variants (`AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE` false) until the one-line flip PR (Task 5.2e, unit U19) that cites the recorded Task 5.2c pass; debug/internal builds show it, so U7's 9.5k-row backfill never runs in a shipped build before the U8 heap gate has a recorded pass | U1 |
| U8 | A **stack of PRs**, not one: (a) Task 5.1d (`MemoryPressureListeners`, one line in `StelekitViewModel.onMemoryPressure()`), (b) Task 5.1e (non-null `writeActor`), (c) Task 5.1f (projected queries, SQLDelight regeneration), (d) Tasks 5.2a + 5.2d (`PlatformCaps`, `defaultAssetPlugins`, the per-plugin `asset_plugin_<id>_enabled` key read (default false, so the list is empty and no drain starts until a key is set; inert-when-unset tests UT-164, IT-102), host constructions; one PR because the no-default field breaks every host until each names a value). **U8 therefore ships indexing only under the master `asset_pipeline_enabled` flag**; the real OnDeviceLabelingPlugin and PdfTextPlugin are constructed only when their key is set. Each is green on its own and may merge before the gate PR. **Gate PR**: Task 0.4a + Task 5.1a (failing drain test red commit first, fix green commit second; the only pair that must share a PR), Tasks 5.1b, 5.1c, 5.1g (crash-loop breaker), 5.2b, and Task 5.2c (512 MB emulator run, a **blocking gate**) | U7 |
| U9 | Stories 5.3, 5.4 (OCR port, on-device plugins) | U8 with the Task 5.2c gate recorded; **ships in a different release than U7/U8** and only after owner pass 12.2a (indexing only) is recorded; Task 5.2c must have recorded both runs (indexing only, and all on-device plugin keys set). Until U10's switches exist the keys are set only by a debug/internal build |
| U10 | Stories 5.5, 5.6 (PluginHost removal, full Settings > Plugins replacing the Task 4.1d interim row with per-plugin switches bound to the keys U8 already reads, Logs initial-filter argument, result surfacing) | U8, U9; **OD-5** for Task 5.5d (OwnerGate: deletes `PluginHost`/`Plugin`) |
| U11 | Epic 6 (cloud Claude: shared Anthropic client, `CloudConsentGate`, egress audit, cloud-description search) | U10 (Task 6.1a, a behavior-preserving extraction that touches no pipeline code, is the only part that may merge earlier, and only after its manual real-call check is pasted into the PR); **OD-5** for Task 6.4a (OwnerGate: deletes `CloudVisionPlugin`) |
| U12 | Epic 7 (small wirings) | U1 (each story independent) |
| U13 | Epic 8 (Google integration) | U1, **U7** (Story 8.1 registers imports with `AssetIndexService`); OAuth device pass |
| U14 | Epic 10 (deletions, one PR per row group) | U1; OD-5 per item; **10.4 also needs OD-2 and U10** (Task 10.4a requires Task 5.5d, in U10, to have removed `PluginHost`/`Plugin` first) |
| U15 | Epic 9 (semantic search) | OD-1 |
| U16 | Story 2.4 (cutover to full sync; swaps only the `PeriodicSyncDriver` tick lambda); **closes metric 4** | U2 + dev plan PR-A1 + PR-A2 shipped and released (outside this plan; declared dependency, not funded here) |
| U17 | Epic 11 (coverage program): 11.1-11.3 early (after U0), 11.4 waves interleaved, 11.5 last | U0 for 11.1-11.2; 11.4 after baseline |
| U18 | Task 3.5c (one-line PR: `ConsoleBuildFlags.WRITES_ENABLED` true in release variants and desktop distributables; XS; OwnerGate) | U6 and a recorded owner pass 12.1 on a COPY of the real 451 MB DB, cited in the PR |
| U19 | Task 5.2e (one-line PR: `AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE` true in release variants; `asset_pipeline_enabled` and every plugin key stay false by default; XS; OwnerGate) | U7, U8 with both Task 5.2c runs recorded, cited in the PR; does not wait for pass 12.2a, which runs on a debug/internal build |

**Merge and ordering rules (apply to every unit)**:

1. **Test registration (definition of done for any story that adds a test class).** A new `businessTest` class named `*Test` must be registered in `AllBusinessTests`; `AllBusinessTestsCompletenessTest` fails the build when a `*Test.kt` file under `kmp/src/businessTest` is missing from the suite (it reads the source tree under Gradle and the classpath under Bazel; verified in its KDoc), so that rule is covered by an existing test and no new completeness test is added. It does **not** cover a helper class whose name does not end in `Test`, or the other source sets (`commonTest`, `androidUnitTest`, `jvmTest`, `androidInstrumentedTest`), which are discovered by their own Gradle runner; for those the PR description pastes the runner's reported count. Every unit's PR description pastes the class total from a real `timeout 30m bazel test //kmp:business_tests` run (recount from the run, never from this plan).
2. **Emulator-gated items.** Tasks 0.3d/0.3e (Android part), the Story 3.2 Android bypass test (IT-11), Task 3.3f (IT-86), Task 5.2c (IT-89) and Task 5.3c cannot run on a PR gate (`androidInstrumentedTest`, `Pixel_Fold` AVD, one class at a time). **The implementing agent runs them** (`./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` per repo `CLAUDE.md`; `ANDROID_HOME=$HOME/Android/Sdk`; result XML read from `kmp/build/outputs/androidTest-results/connected/debug/`), pastes the XML summary into the PR description, then `adb emu kill`s the emulator. The owner never needs `adb logcat`. **If the emulator cannot run (no KVM, no SDK, boot or run timeout) the unit does not merge**: unavailable is a block, not a skip, and no flag default may be flipped on a missing run. The owner device passes (Epic 12) are separate and do not substitute.
3. **Release rule for U7 (heap gate before shipped backfill).** See the U7 row: the interim switch is hidden in release variants until the Task 5.2c pass is recorded (then exposed by the one-line U19 PR, Task 5.2e), and UT-129 additionally guards the default flip.
4. **Ordering against the dev plan on shared files.** Task 3.1g edits `GitOperationSupport.kt`, which dev PR-A1 also edits: whichever merges first wins and the other rebases and reruns the `Redactor` tests (`git log -- GitOperationSupport.kt` at task start; do not duplicate the dev plan's edits). Task 2.2b equals dev Task 2.4c: at task start grep for `GitSyncServiceRegistry` in `commonMain`; if dev Task 2.4c has landed, 2.2b reduces to the balance test and promotes nothing (the same rule applies in reverse if this task lands first).
5. **Open bugs.** `docs/bugs/open/` holds BUG-001 (iOS Gradle classloader; iOS CI is `continue-on-error`), BUG-002 (benchmark jobs skip on push; Epic 11 scope note), BUG-003 (wasm JPEG encoder; Task 6.1b scope note) and BUG-004 (wasm page drop target is a no-op, issue #189). BUG-001 and BUG-004 do not intersect planned work: iOS is out of scope, and BUG-004 concerns the wasm page drop target while Task 4.1b hooks the attach/import/share paths, not drag-and-drop.

**Why this order (why now)**. The units are ordered by what each one unblocks and by how much damage a wrong step does on a 512 MB phone the owner cannot attach `adb` to:

1. **U0 and U1 first**: the baseline, the calibrated scan and the wiring gate cost the least and make every later PR visibly shrink `ReachabilityBaseline`; without them a "wired" feature is a claim, not a proof. U0's spikes are also a merge gate for the console.
2. **U2 (sync) and U3-U6 (console) next**: the owner is debugging Android OOM from offline log exports right now (PR #501), so an on-device read-only query tool (U3-U4) pays back before any new feature and needs no new background work. Writes and restore (U5-U6) are the highest-consequence code and ship compiled out of release until pass 12.1, so they can land early without exposure. Sync staleness visibility (U2) is cheap, flag-off and exercises the new driver seam.
3. **U7 then U8, U9, U10, U11 (assets) in separate releases**: indexing (U7-U8) is the only part that touches a 9.5k-row table at scale and so goes first, alone, behind the 512 MB emulator gate; on-device plugins (U9-U10) add CPU/heap work and ship one release later; cloud Claude (U11) sends data off the device and therefore comes last in the chain, after the Plugins surface and consent UI it lives in.
4. **U12 (small wirings) and U13 (Google), U14 (deletions)**: independent, low-risk, user-visible wins that can fill any wave; U13 waits only for U7 because imports register with `AssetIndexService`. Deletions are owner-gated and shrink the baseline.
5. **U15 (semantic search)** exists only if OD-1 = build and the latency spike passes. **U16 (full sync cutover)** waits on dev-plan units outside this plan. **U17 (coverage program)** runs alongside: the map and tiers are early (they only need U0), gap waves interleave, and the zero-check is last because it depends on every other unit.

## Dependency Visualization

```
                         [dev plan PR-A1 guards + 2.2d0]--[dev plan PR-A2 policy]--+
                                                                                    |
 U0 baselines/scan ──┬──> U1 guardrails ──┬──> U2 sync wiring ───────────────────> U16 cutover (2.4, closes M4)
 (0.1,0.2,0.3)        │                    │   (2.0 driver first)
                     │                    ├──> U3 console core ──> U4 SQL read ──> U5 writes ──> U6 restore+E2E
 S0.3 spikes ────────┼────────────────────┘          ^ (U0 spikes are a merge gate for U4, U5, U6)
                     │                    │
                     │                    ├──> U7 asset index/move ──> U8 pipeline core ──┬─> U9 OCR + on-device ──> U10 Plugins UI ──> U11 cloud Claude
                     │                    │                                               └──────────────────────────^
                     │                    ├──> U12 small wirings (7.1..7.5, independent)
                     │                    ├──> U13 Google integration (8.1..8.3)  [also needs U7]
                     │                    └──> U14 deletions (OD-5 gated, independent PRs) ──> shrinks ReachabilityBaseline
 U0 ──> U17 coverage program: 11.1 map ──> 11.2 tiers/baseline ──> 11.3 gating ──> 11.4 gap waves (A..D) ──> 11.5
 OD-1 ──> U15 semantic search (9.1 spike ─> 9.2 schema ─> 9.3 index/kNN ─> 9.4 UI)

 Owner wall-clock (Epic 12): 12.1 console device pass (after U6), 12.2a indexing-only pass (after U8, before U9 ships), 12.2b on-device plugins pass (after U10),
 12.3 desktop sync manual run (after U2/U16), 12.4 cloud consent with a real key (after U11), 12.5 Google OAuth (after U13)
```

---
## Phase 0: Evidence and guardrails

### Epic 0: Baselines and spikes
**Goal**: Replace every estimate with a measured number or a run spike, and prove the one suspected defect, before building on any of it.

#### Story 0.1: Run-not-estimated coverage baseline
**Superseded in part (ADR-003 addendum 2026-10-29)**: coverage and mutation are per PR, not nightly; ignore the nightly `CoverageRatchet`, scheduled PIT and nightly issue filing below. The existing `diffCoverage` 80% PR gate is the coverage gate.
**As the** owner, **I want** real line, branch and mutation numbers per package, **so that** the coverage map ranks gaps from data.
**Acceptance Criteria**:
- A scheduled full run exists and its output is a committed artifact, not a console dump.
  - *Given* `.github/workflows/coverage-baseline.yml` on `workflow_dispatch` and nightly, *When* it runs `businessTest`+`commonTest` on JVM, `testDebugUnitTest` on Robolectric, and `jvmTest` under `scripts/jvm-display-check.sh`, *Then* it uploads `jacoco.xml`, `jacoco-android.xml` and a `runtime.json` containing wall-clock seconds and peak RSS per tier.
- The first baseline replaces the stale partial numbers (research: JVM 23,489/69,331 lines = 33.9%, Android 8,479/70,498 = 12.0%, both from change-scoped runs dated 2026-10-16).
  - *Given* the baseline run completes, *When* `coverage-map.py` totals the XMLs, *Then* the recorded totals are written next to the stale figures with the command used, and any figure not produced by a run is labelled INFERRED.
- PIT is timed before tiers are fixed.
  - *Given* `./gradlew :kmp:mutationTest -Pmutate.classes=<db/ and repository/ classes> -Pmutate.threshold=0`, *When* it finishes, *Then* `runtime.json` records its duration and survivor count for Tier 1, and ADR-003's nightly cadence is confirmed or changed from that number.
**Files**: `.github/workflows/coverage-baseline.yml`, `scripts/ci/coverage-run.sh`, `docs/coverage/baseline-2026-10.md`.

##### Task 0.1a: Baseline workflow and runner script (S)
- CI assertion: the workflow's job-level `if:` contains no `github.event.pull_request.draft` condition (BUG-002 guard; see Epic 11 scope note). Reuse `kmp/build.gradle.kts` `jacocoTestReport` (`:1521`) and `jacocoAndroidTestReport`; do not add Kover. Runner wraps `jvmTest` with `scripts/jvm-display-check.sh --` and `timeout`. Memory budget: reuse existing job sizing; add no shard (pitfalls E5).
##### Task 0.1b: Execute the baseline and commit the numbers (S)
- Wall-clock blocker: CI/queue. Record per-package results in `docs/coverage/baseline-2026-10.md`.
##### Task 0.1c: Timed Tier-1 PIT run (S)
- Files: `scripts/ci/pit-tier1.sh`. Records duration and survivors; does not gate.

#### Story 0.2: Calibrated reachability scan
**As the** owner, **I want** a precise unreferenced-type list, **so that** success metric 1 is measurable and false positives stop misleading scope.
**Acceptance Criteria**:
- The scan reproduces the known list and filters the known false positives.
  - *Given* `scripts/ci/reachability-scan.py` run on HEAD, *When* it lists candidates, *Then* it contains `AssetIndexService`, `RTCManager`, `DatalogEngine`, `CloudClaudePlugin`, `DesktopSyncScheduler` and excludes `InitializingDebugScreen`, `OtelSpanElement`, Android manifest components, `@Serializable` DTOs, `*UiState` types and `generated/sqldelight` tables.
- R8 cross-check calibrates the grep for Android.
  - *Given* a one-off release build with `-dontobfuscate -dontoptimize -printusage` (minify is off today, `androidApp/build.gradle.kts:60`), *When* the usage report is diffed against the scan's Android-reachable candidates, *Then* the diff is recorded in `docs/coverage/reachability-calibration.md` and every disagreement has a stated reason.
- The baseline is committed.
  - *Given* the scan output at HEAD, *When* it is written to `scripts/ci/reachability-baseline.txt`, *Then* each line is `TypeName<TAB>file<TAB>register-row-number` and the register in this plan accounts for every line (a script check).
**Files**: `scripts/ci/reachability-scan.py`, `scripts/ci/reachability-baseline.txt`, `docs/coverage/reachability-calibration.md`.

##### Task 0.2a: Scan script with false-positive filters (M)
- Token-index over `kmp/src` and `androidApp/src`, production dirs only; filters listed above; `sg` call-site confirmation for each candidate printed with `--confirm`.
##### Task 0.2b: R8 `-printusage` calibration run (S)
- Local one-off; no CI change. Wall-clock: a release-variant build under `timeout 30m` (memory per PR #498).
##### Task 0.2c: Commit baseline (XS)

#### Story 0.3: Capability spikes that gate the console
**As the** implementer, **I want** the SQLite and driver behavior proven by run, **so that** the read-only guard is an engine property and not a hope.
**Delivery and gate**: Story 0.3 is part of unit U0. The PRs for Stories 3.2 and 3.3 (U4, U5) and restore (U6) cannot merge until the ADR-006 addendum records the result of Spikes 0.3a-e; if Spike 0.3d shows that neither interrupt nor abandon-and-close is safe on the `requery` driver, Android ships without `sql!` in the first slice.
**Acceptance Criteria** (spikes 0.3a-c are dev plan Story 0.2a-c, cited, not re-specified; 0.3d and 0.3e are new):
- Read-only enforcement per driver.
  - *Given* a file DB on the JVM driver opened with `SQLiteConfig.setReadOnly(true)` on a dedicated non-pooled connection, *When* `UPDATE pages SET name='x'` and `WITH c AS (SELECT 1) DELETE FROM pages` run on it, *Then* both throw a read-only error and `SELECT COUNT(*) FROM pages` is unchanged; *And* *Given* the same app DB, *When* `PRAGMA query_only=1` runs on the console connection, *Then* a subsequent app write on any of the 8 pooled connections succeeds.
- Cancellation per driver (new).
  - *Given* a console connection running `WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<1e9) SELECT count(*) FROM r`, *When* the 10 s `QueryDeadline` elapses or the user cancels, *Then* control returns within 1 s on JVM via `Statement.cancel()` (or connection abandon-and-close) and on Android via `CancellationSignal` if the `requery` fork supports it, else via abandon-and-close; the spike records which one each driver needs.
- Backup and swap.
  - *Given* `VACUUM INTO '<tmp>/b.db'` on a 451 MB-class test DB (generated 9.5k-page graph), *When* it completes, *Then* `PRAGMA integrity_check` on the copy returns `ok` and the elapsed time and peak heap are recorded; the live-DB swap order from dev ADR-002 item 4 is proven on a temp graph (dev spike 0.2b).
- Write cascade.
  - *Given* a raw `UPDATE blocks SET content='zz' WHERE uuid=<u>` through `DatabaseWriteActor.execute`, *When* the actor emits invalidation, *Then* the spike records whether any `GraphWriter` path wrote markdown (expected no; dev spike 0.2c).
- Column-level read denial per driver (new, Spike 0.3e; UNVERIFIED until run).
  - *Given* a file DB and each candidate read connection (xerial `sqlite-jdbc` on JVM; platform `android.database.sqlite.SQLiteDatabase` and the `com.github.requery:sqlite-android:3.49.0` fork on Android), *When* the spike searches for `sqlite3_set_authorizer` or an equivalent hook (public API, reflection into the bundled native handle, or a build flag) and installs a callback returning `SQLITE_DENY` for `SQLITE_READ` on `git_config.https_token_key`, `oauth_token_key` and `ssh_key_passphrase_key`, *Then* the spike records per driver YES/NO, and if YES proves denial survives `SELECT * FROM git_config`, `WITH c AS (SELECT * FROM git_config) SELECT * FROM c`, `SELECT https_token_key AS x FROM git_config`, a view-free subquery and a `JOIN`; if NO the result is recorded as NO and Story 3.2 uses its fallback criterion (no denial claim).
  - *Given* the read-only handles from Spike 0.3a, *Then* the spike confirms the console handle is a separate `OPEN_READONLY` handle that is not taken from `DriverFactory.createReadDriver` (Android returns a read-write driver, JVM returns `null`), and that abandon-and-close of it leaves the app's pooled connections usable.
**Files**: `jvmTest/.../console/spike/ReadOnlyEnforcementSpikeTest.kt`, `QueryCancelSpikeTest.kt`, `DbSwapSpikeTest.kt`, `WriteCascadeSpikeTest.kt`, `AuthorizerSpikeTest.kt` (+ `androidInstrumentedTest/.../console/spike/AuthorizerSpikeAndroidTest.kt`); results recorded in ADR-006 addendum.

##### Task 0.3a: Read-only enforcement and pooled-leak spike, JVM (M)
##### Task 0.3b: `VACUUM INTO` and live-DB swap spike (M)
##### Task 0.3c: Write-cascade spike (S)
##### Task 0.3d: Cancel/interrupt spike per driver incl. Android emulator `Pixel_Fold` via `connectedDebugAndroidTest` (M)
- Android part runs on the emulator per CLAUDE.md ("Reproduce on the Pixel_Fold AVD"); no logcat; read the result XML.
##### Task 0.3e: SQLite authorizer availability per driver (sqlite-jdbc, platform SQLite, requery) and console-handle independence (S)
- Android part on `Pixel_Fold`; outcome decides which Story 3.2 credential criterion applies and is recorded in ADR-006 addendum.

#### Story 0.4: Prove the suspected asset defects before fixing them
**As the** owner, **I want** the drain-offset bug demonstrated by a failing test, **so that** the fix is test-first. The failing test ships **in the same PR as the fix** (unit U8): commit 1 adds the test and its red run is pasted into the PR description, commit 2 fixes `drainUnprocessed`; no PR merges a red test to `main`.
**Acceptance Criteria**:
- The skip is reproduced.
  - *Given* 25 `AssetEntry` rows with `mlProcessed=false` in a SQLDelight-backed `AssetRepository` and a plugin that succeeds on every asset, *When* `AssetPipelineService.scheduleBackfill` drains with `batchSize=10`, *Then* the test asserts all 25 reach `mlProcessed=true`, and it **fails at HEAD** with only 15 or fewer processed (rows 11-20 skipped when offset moved to 10 after the first 10 left the result set), proving or refuting the defect.
- Emptiness of the live Assets screen is recorded.
  - *Given* the owner's phone build of HEAD, *When* the Assets screen is opened on a graph with images, *Then* the owner records empty or non-empty (research architecture header 2 is grep-only).
**Files**: `businessTest/.../asset/pipeline/AssetPipelineDrainBugTest.kt`.

##### Task 0.4a: Failing drain test on the SQLDelight backend, not `IN_MEMORY` (S)
- `@Ignore` is forbidden and a red test never merges alone: Task 0.4a is delivered inside unit U8 with Task 5.1a (red commit first, green commit second, one PR). If the test passes at HEAD the defect is refuted and 5.1a shrinks to the terminal-state test. Device check 0.4b is Epic 12 item 12.2.

---

### Epic 1: Wiring enforcement
**Goal**: Unwired seams fail CI, and every wired feature has a named end-to-end test through its real composition root.

#### Story 1.1: `WiringCompletenessTest` and the feature catalog
**As the** owner, **I want** CI to fail when a seam implementation is never constructed from a composition root, **so that** dormant code cannot return.
**Acceptance Criteria**:
- Unconstructed implementations fail.
  - *Given* class `FooPlugin : AssetPipelinePlugin` whose name followed by `(` appears in no `CompositionRoot` file, *When* `WiringCompletenessTest` runs, *Then* it fails printing `FooPlugin` and the roots searched.
- Allowlist entries expire.
  - *Given* line `FooLabeler  temporary reason  2026-10-27` in `wiring-allowlist.txt`, *When* the test runs on 2027-02-01 (> 90 days later), *Then* it fails naming the expired entry; *Given* `CoreMlImageLabeler  out-of-scope-ios  never`, *Then* it does not expire (the iOS pair would otherwise fail the gate again every 90 days).
- U1 merges green, with a countdown instead of a silent exemption (engineering G1).
  - *Given* HEAD, where `OcrPlugin`, `PdfTextPlugin`, `OnDeviceLabelingPlugin`, `CloudClaudePlugin`, `CloudVisionPlugin`, `KeyboardEmulationDeviceFactory`, `KtorWaybackMachineService`, `DesktopSyncScheduler` and the camera stubs are constructed by no root, *When* `wiring-allowlist.txt` holds one `awaiting-unit <Un>  <date>  renewals=0` line per unconstructed seam implementation (Task 1.1b; set generated from the run), *Then* `WiringCompletenessTest` passes; *Given* any one of those lines removed, *Then* it fails naming the class (the allowlist, not a weaker test, is what makes U1 green).
  - *Given* an entry with `renewals=2` whose date has passed, *Then* the test fails with `renewal limit reached: <Class> awaiting <item>`; *Given* a PR that edits `renewals=2` to a lower number, *Then* it fails; *Given* a listed class that a root now constructs (or that no longer exists), *Then* it fails as a stale entry.
- Catalog ties a feature to its entry-point test.
  - *Given* `wiring-catalog.txt` line `AssetPipelinePlugin  AssetStackEntryPointTest`, *When* that class is missing, *Then* the test fails; *When* present but the class does not reference `GraphContentAssetSetup`, *Then* it fails.
- Limits are stated in the test KDoc, and the catalog is the layer that catches them.
  - *Given* a constructor that exists only inside dead code, *Then* `WiringCompletenessTest` alone passes (text match limit, stated); *And* *Given* the planted dead-code-constructor fixture of Task 1.1e (a seam implementation constructed only inside a never-called function of a test-only copy of a root), *Then* the catalog observable-effect check fails because the effect never happens through the real root factory (the name-based reachability scan is not claimed to catch it).
- Runs where it can see its inputs, with no display (Bazel and Gradle).
  - *Given* `bazel test //kmp:business_tests` in the sandbox, *When* `WiringCompletenessTest` loads the root set, *Then* it reads `desktop/Main.kt`, `androidApp/.../MainActivity.kt`, `androidApp/.../SteleKitApplication.kt`, `ui/GraphContent*Setup.kt`, `ui/StelekitAppDependencies.kt`, `repository/RepositoryFactory.kt` and `defaultAssetPlugins` from classpath resources bundled by a new filegroup (same idiom as `graph_content_wiring_test_sources_as_resources` in `kmp/src/jvmTest/kotlin/BUILD.bazel:29` and `git_shadow_worktree_as_resource` in `kmp/src/businessTest/kotlin/BUILD.bazel:31`), and the class is registered in `AllBusinessTests` (its completeness test passes).
  - *Given* any one root file missing from the classpath (or from the working-directory walk under Gradle), *Then* the test **fails** with `root not found: <name>; roots searched: [...]`; an empty root set never passes.
  - *Given* a planted orphan seam implementation added in a test-only copy of the root set, *When* run under Bazel, *Then* the test fails naming it (negative test proving the gate bites under the sandbox, not only under Gradle).
  - *Given* `scripts/jvm-display-check.sh` reports Wayland-only, *Then* the wiring gate still runs and gives real signal, because it is in `businessTest`, not `jvmTest` (which is tagged `requires-display`, `kmp/src/jvmTest/kotlin/BUILD.bazel:85-87`).
- Catalog entries require real composition and an observable effect.
  - *Given* a catalog line naming `FooEntryPointTest`, *Then* the check requires that test to call `WiredAppFixture.` (or the platform root factory under test) and to reference the stack file; a comment mentioning the stack does not satisfy it.
  - *Given* the catalog line format `Seam  EntryPointTest  effect:<label>`, *Then* the test class must call `ObservableEffect.assert("<label>") { ... }` (a fixture helper) after invoking the real root factory, and the assertion must be about runtime state, for example `asset_index` row count > 0 after loading a fixture graph, or a `SyncTick` log line after the interval, not that a class is referenced; a catalog line without an effect label fails.
**Files**: `businessTest/.../wiring/WiringCompletenessTest.kt`, `businessTest/resources/wiring-allowlist.txt`, `businessTest/resources/wiring-catalog.txt`, `kmp/src/businessTest/kotlin/BUILD.bazel` (one `java_library` per `resource_strip_prefix`, e.g. `wiring_root_sources_as_resources`, `business_test_data_as_resources`), `kmp/src/businessTest/resources/BUILD.bazel` (data filegroup), `scripts/ci/BUILD.bazel` (baseline export), `androidApp/BUILD.bazel` (`exports_files` / filegroup for `MainActivity.kt` and `SteleKitApplication.kt`; the file exists), `kmp/src/jvmMain/kotlin/BUILD.bazel` and `kmp/src/commonMain/kotlin/BUILD.bazel` (filegroups for `Main.kt` and the `GraphContent*Setup.kt` set).

##### Task 1.1a: Seam list, source walk, root set, failure messages (M)
- Modelled on `businessTest/.../AllBusinessTestsCompletenessTest.kt` and `jvmTest/.../capture/CaptureWriterSourceAuditTest.kt` (the latter resolves `src/...` or `kmp/src/...` from the working directory; this test must instead load resources and fail loudly). Seams (identical to the Glossary's `WiringSeam`): `BackgroundSyncScheduler`, `AssetPipelinePlugin`, `ConsoleCommand`, `MeasurementDeviceFactory`, `WaybackMachineService`, `CameraProvider` (remaining implementations after Task 10.5a). `Plugin` was dropped from the list: Task 5.5d deletes it.
##### Task 1.1c: Bazel resource bundling of all roots (incl. `androidApp`) (M)
- Reclassified S to M and split (Bazel resource bundling is where this repo hit its pitfalls; the data-file half is now Task 1.1f). Files: `androidApp/BUILD.bazel`, `kmp/src/jvmMain/kotlin/BUILD.bazel`, `kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/businessTest/kotlin/BUILD.bazel`, `scripts/ci/BUILD.bazel`: five BUILD files that are mechanical filegroup/`java_library` additions (exempt from the 3-5 non-mechanical-file rule; see the sizing rule in Effort estimate).
- Filegroups in each package that owns a root file. `java_library(resources=...)` takes **one** `resource_strip_prefix` per target, so use **one `java_library` per distinct `resource_strip_prefix`** in `businessTest/kotlin/BUILD.bazel` (as the existing `*_as_resource` targets do: `git_shadow_worktree_as_resource`, `git_sync_service_as_resource`, ...). Bare-name collisions: `desktop/Main.kt` (jvmMain) and `browser/Main.kt` (wasmJsMain) are both `Main.kt`, and `commonMain` and `androidMain` may share other names. Strip to `.../dev/stapler/stelekit` (not to the leaf directory) for any target that holds a colliding name so resources keep their sub-path (`desktop/Main.kt`, `browser/Main.kt`), and the test looks roots up by that relative path; a duplicate resource name across the classpath fails the test (`ambiguous root: <name>`).
- Verified by running `timeout 30m bazel test //kmp:business_tests` and recounting the class total from the real run.
##### Task 1.1f: `businessTest/resources/` data-file bundling and the planted-orphan negative test (S)
- **This task creates the directory `kmp/src/businessTest/resources/`** (absent at HEAD) and bundles its data files into the test classpath, again with **one `java_library` per distinct `resource_strip_prefix`** (never one library with several prefixes). Data files bundled the same way: `wiring-allowlist.txt`, `wiring-catalog.txt`, `egress-allowlist.txt` and the reachability baseline copy are plain `.txt`. `kmp/src/businessTest/resources/` does not exist at HEAD and `business_tests` globs only `**/*.kt` (`BUILD.bazel:75`), so they would be invisible under Bazel. Create the files in a package that owns them (`kmp/src/businessTest/resources/BUILD.bazel` with `filegroup(name = "business_test_data", srcs = glob(["*.txt"]))`, plus `exports_files(["//scripts/ci:reachability-baseline.txt"])` or a filegroup in `scripts/ci/BUILD.bazel`), add `java_library(name = "business_test_data_as_resources", resources = [...], resource_strip_prefix = "kmp/src/businessTest/resources")` (a second one for the baseline with `resource_strip_prefix = "scripts/ci"`), and list both in `business_tests` `resources`/`runtime_deps` beside `:journey_docs_as_resources`. Under Gradle they load from `src/businessTest/resources` as ordinary test resources; the same loader (`classpath`) serves both.
- The planted-orphan negative test also deletes one data file from the classpath and must fail with `root not found`; verified by a real `timeout 30m bazel test //kmp:business_tests` run.
##### Task 1.1b: Allowlist with 90-day expiry, renewal cap, U1 seeding, and catalog check (S)
- **U1 must merge green.** `WiringCompletenessTest` fails for any seam implementation that no root constructs, and several exist today. The first run of Task 1.1a on HEAD prints that set; this task writes every member into `wiring-allowlist.txt` as `ClassName  awaiting-unit <Un>  <U1 merge date + 90 days>  renewals=0`, naming the unit that wires or deletes it. The set is **generated from the run, not typed from this plan**; expected from a 2026-10-27 grep of non-test construction sites (`ClassName(` outside the class's own file): `OcrPlugin`, `PdfTextPlugin`, `OnDeviceLabelingPlugin` (wired by `defaultAssetPlugins`: Tasks 5.2a, 5.3a; units U8/U9), `CloudClaudePlugin` (Tasks 6.1c, 6.2c; U11), `CloudVisionPlugin` (deleted by Task 6.4a; U11), `KtorWaybackMachineService` (Task 7.1; U12), `KeyboardEmulationDeviceFactory` (Task 7.3a; U12), `AndroidUsbSerialFactory` (Task 7.3b; U12), `DesktopSyncScheduler` (deleted by Task 2.1c; U2), `WebcamCameraProvider` and `WebCameraProvider` (deleted by Task 10.5a; U14), plus `WorkManagerSyncScheduler` if the audit finds no root construction (Story 2.2; U2) and any of `NoOpCameraProvider` / `NoOpBleScanner` that only their own factories construct (kept; U12 records them). The camera stubs follow Task 10.5a's read-before-delete rule.
- The iOS pair (`CoreMlImageLabeler`, `IosPdfTextExtractor`) is seeded here under the non-expiring `out-of-scope-ios` class (date `never`, no renewals). The verified false positives and benchmark entries are added by Task 7.5a (U12); until then they stay in `ReachabilityBaseline` only, so U1 does not depend on U12.
- **Allowlist versus baseline**: the allowlist makes the seam test green and is a countdown (90 days, 2 renewals, then a hard fail); `ReachabilityBaseline` is the separate name-based scan input (Glossary). Each `awaiting-unit` class that the scan also reports is in both files with the same register row, and the unit that wires or deletes it removes both lines (stale-entry rule). Every other register row (non-seam types such as `RTCManager`) starts in `ReachabilityBaseline` only.
- Wiring DoD for each unit that constructs a seam implementation: delete its allowlist line in the same PR (a class that is constructed and still listed fails).
##### Task 1.1d: Observable-effect catalog contract: `ObservableEffect` helper, catalog line format with `effect:<label>`, check that fails on a missing label or a class that never calls it (S)
##### Task 1.1e: Planted dead-code-constructor negative test: a seam implementation constructed only inside an unreachable function of a test-only root copy must make the catalog check fail, under Gradle and Bazel (S)

#### Story 1.2: Baseline-diffed reachability scan in CI
**Acceptance Criteria**:
- New unreferenced type fails; shrinking passes.
  - *Given* a PR adding `class OrphanThing` referenced nowhere, *When* the `reachability` CI job runs the scan, *Then* it fails listing `OrphanThing`; *Given* a PR that deletes `RTCManager`, *Then* it passes and the job prints "baseline can shrink: remove RTCManager" (a follow-up commit updates the file).
**Files**: `.github/workflows/ci.yml` (new job `reachability`, no Gradle needed, so no memory impact), `scripts/ci/reachability-scan.py`.
##### Task 1.2a: CI job and baseline diff, including the allowlist-twin consistency check (an `awaiting-unit` class the scan reports must be in the baseline too) (S)

#### Story 1.3: Composition-root fixtures
**Acceptance Criteria**:
- The fixture composes the real stack.
  - *Given* `WiredAppFixture.android()` over an in-memory-path SQLDelight DB and fake `FileSystem`, `ImageLabeler`, `Settings`, *When* a test composes `rememberGraphContentGitSyncStack(...)`, *Then* the returned `GitSyncService` is the object in `GraphManager.activeGitSyncService`, and disposing the composition calls `shutdown()` (no tick afterwards).
- A desktop variant exists for code in `jvmMain`.
  - *Given* `WiredAppFixture.desktop()`, *When* it composes the same stack, *Then* behavior is identical and no X11 display is required (headless Compose is not used; the stack is built without pixels).
- First-open smoke test per new screen, on the `androidApp` root.
  - *Given* the Robolectric `androidApp` test source set (`androidApp/src/test/kotlin`) launching `MainActivity` (the shipped root, not the `kmp` library fixture) with the relevant flag on, *When* each new or changed screen in this plan is opened (the end-state list: Console, Settings > Plugins, Assets browser with the new search/empty state, Drive browser; at U1 it holds only the screens that exist then and each unit appends its own), *Then* it renders without throwing and shows its primary element; the screen list lives in `FirstOpenSmokeTest` and a screen added by a story but missing from it fails the catalog check. Precedent: pitfall A6 (`AssetFilter.all` crashed on every open and only an entry-point run found it).
**Files**: `androidUnitTest/.../wiring/WiredAppFixture.kt`, `jvmTest/.../wiring/DesktopWiredAppFixture.kt`, `androidApp/src/test/kotlin/.../FirstOpenSmokeTest.kt`.
##### Task 1.3a: Android fixture on Robolectric (L)
- Priced L (240k) and not split because the fixture is one composed object (real SQLDelight over an in-memory-path DB, fake `FileSystem`/`ImageLabeler`/`Settings`, `GraphContent*Setup` composition, disposal assertions) that every `*EntryPointTest` depends on; two halves would each be untestable alone. If the first worker run does not finish it, split the fake-port set (`FakePlatformPorts`) from the composition helper rather than growing the task.
##### Task 1.3b: Desktop fixture (S)
##### Task 1.3c: `FirstOpenSmokeTest` on the `androidApp` root with the per-screen list, wired into the catalog check (M)
- The list starts with the screens that exist when U1 merges (Settings > Git sync, Assets browser, the Settings > Plugins placeholder, Logs) and **each unit appends its own screens in its own PR** (Console in U3, the Assets search/empty state in U10, Drive in U13); the catalog check already fails a screen that a story adds but the list omits.

#### Story 1.4: Placement and flake-prevention audits
**As the** owner, **I want** new tests placed per `CLAUDE.md` and written to avoid the repo's known flake classes, **so that** hundreds of added tests do not multiply flakiness (pitfalls E4).
**Acceptance Criteria**:
- Placement.
  - *Given* a `jvmTest` file importing `androidx.compose.ui.test` without a `// jvmTest-reason: <pixels|desktop-only>` comment, *When* `TestPlacementAuditTest` runs, *Then* it fails naming the file.
- Hygiene, ratcheted.
  - *Given* a new test using `Thread.sleep`, `GlobalScope`, a real `Dispatchers.IO` scope with no `@AfterTest` cancel, or a real-clock `delay(` outside `runTest`, *When* `TestHygieneAuditTest` runs, *Then* it fails; existing violations are in `test-hygiene-baseline.txt` and may only shrink.
**Files**: `businessTest/.../audit/TestPlacementAuditTest.kt`, `businessTest/.../audit/TestHygieneAuditTest.kt`, `businessTest/resources/test-hygiene-baseline.txt`.
##### Task 1.4a: Placement audit (S)
##### Task 1.4b: Hygiene audit with baseline (M)
- Rules from pitfalls E4: owned scope cancelled in teardown, no real clocks, no shared singleton state (`GraphSwitchInvalidationTest`, `CaptureShareReviewTest` precedents). New test classes must appear in the `bazel test //kmp:business_tests` class count (recount from the real run).

---

## Phase 1: Sync wiring

### Epic 2: Desktop and Android scheduled sync
**Goal**: Metric 4: with a configured interval the app pulls remote commits while open, proven through `GraphContentGitSyncSetup`. Built on dev ADR-004; the guard and policy code it names is owned by the dev plan. **This plan can deliver only the staleness-visibility half** (fetch-only ticks, Stories 2.0-2.3); the pull half is Story 2.4, blocked on dev-plan PR-A1/PR-A2, so metric 4 is "partially met" at U2 and closes only at U16. **Why the owner wants it**: UNVERIFIED by the owner, stated as hygiene. A desktop session that never checks the remote shows a stale working copy until `Sync` is pressed, so divergence is found late; the staleness chip (U2) makes it visible earlier and the pull step (U16) removes the manual press. If the owner says the pain is not real, OD-6 "not scheduled" is the right answer and U2 stands as the visibility half only.

#### Story 2.0: Extract `PeriodicSyncDriver` (refactor-first, behavior-preserving)
**As the** implementer, **I want** the scheduling policy out of the 786-line `GitSyncService`, **so that** the hotspot does not gain a pause/backoff state machine and Story 2.4 only swaps a lambda.
**Acceptance Criteria**:
- Characterization first.
  - *Given* the current `startPeriodicSync` (a `delay` then `refreshLocalStatus` + `fetchOnly` loop, `GitSyncService.kt:751-761`), *When* a characterization test using `runTest` + `advanceTimeBy` runs against HEAD, *Then* it pins: 3 fetches in 45 virtual minutes at 15 min, `interval <= 0` launches nothing, a second `startPeriodicSync` cancels the first; the same test passes unchanged after the extraction.
- Driver owns the policy, service only delegates.
  - *Given* `PeriodicSyncDriver(tick: suspend () -> TickOutcome, clock, scope)` in `git/PeriodicSyncDriver.kt`, *When* `GitSyncService.startPeriodicSync(graphId, interval)` is called, *Then* it constructs/starts the driver with `tick = { refreshLocalStatus(graphId); fetchOnly(graphId).toTickOutcome() }` and contains no loop, no counter and no `delay`; `GitSyncService.kt` line count does not grow by more than the delegation (asserted in review, not a test).
- Driver behaviors (tested at the driver, not the service).
  - *Given* a tick lambda that throws `IllegalStateException` on tick 1 and succeeds on tick 2, *Then* tick 1 logs WARN (redacted), tick 2 runs, `CancellationException` propagates; *Given* a busy signal (`GitSyncBusyCounter == 1`), *Then* the tick is skipped with `Skip(Busy)` and the lambda is not called; *Given* 3 consecutive failures, *Then* the loop pauses and `status.value.paused == true`; *Given* the first start after graph load, *Then* one tick runs immediately, then every interval; *Given* a success, *Then* `status.value.lastSuccessAtMs` equals the injected wall-clock (not monotonic elapsed; pitfalls C3) and `nextAtMs = lastAttempt + interval`.
- Status is observable.
  - *Given* the driver, *Then* it exposes `StateFlow<ScheduledSyncStatus>` and `TickOutcome` is a sealed type (`Success`, `Failure(cause)`, `Skip(SkipReason)` with a sealed `SkipReason`), not strings.
**Files**: `commonMain/.../git/PeriodicSyncDriver.kt` (new), `commonMain/.../git/GitSyncService.kt` (delegation only), `businessTest/.../git/PeriodicSyncDriverTest.kt`, `businessTest/.../git/GitSyncServicePeriodicTest.kt` (characterization).
##### Task 2.0a: Characterization test, then extract `PeriodicSyncDriver` with the existing behavior and an injected tick lambda (M)
- Ships first (own PR, U2 step 1); `GitSyncService.kt` diff is limited to replacing the loop body with delegation.
##### Task 2.0b: Driver hardening: per-tick `Throwable` guard, `SyncTick` log, busy skip, pause after 3 consecutive failures, immediate first tick, `StateFlow<ScheduledSyncStatus>` (M)
- Test with `runTest` + `advanceTimeBy`; no real sleeps; scope owns a `CoroutineExceptionHandler`.

#### Story 2.1: Desktop loop driven from the sync stack (interim, fetch-only tick)
**As the** owner, **I want** the app to check the remote on a timer while open, **so that** staleness shows without my pressing Sync.
**Acceptance Criteria**:
- Exactly one loop per service instance, started from the stack.
  - *Given* `rememberGraphContentGitSyncStack` composed with `scheduled_sync_enabled=true` and `GitConfig.pollIntervalMinutes=15`, *When* virtual time advances 45 minutes, *Then* the fake `GitRepository` records 3 `fetch` calls and the log has 3 `SyncTick` lines.
  - *Given* the composition is disposed, *When* another 15 minutes pass, *Then* zero further ticks (loop cancelled by `shutdown()` at `GraphContentGitSyncSetup.kt:45`).
- Interval edits restart the loop.
  - *Given* a running loop at 15 minutes, *When* `pollIntervalMinutes` changes to 5 in `GitConfigRepository`, *Then* the next tick occurs 5 minutes after the edit and only one loop exists (`stopPeriodicSync()` runs first, `GitSyncService.kt:752`).
- Off by default and for non-positive intervals.
  - *Given* `scheduled_sync_enabled` unset, *When* the stack is composed, *Then* no loop starts; *Given* `intervalMinutes=0`, *Then* `startPeriodicSync` returns without launching (existing guard `:753`).
- A throwing tick does not end the loop.
  - *Given* a `GitRepository.fetch` that throws `IllegalStateException` on tick 1 and succeeds on tick 2, *When* two intervals pass, *Then* tick 1 logs WARN with the redacted message, tick 2 runs, and `CancellationException` is not swallowed.
- Busy and lock aware.
  - *Given* `GitSyncBusyCounter == 1` (a manual sync running), *When* a tick fires, *Then* it is skipped with `Skip(Busy)` logged and no git call is made.
- First tick is not a full interval late (pitfalls C2).
  - *Given* flag on and the graph reaches fully loaded, *When* the stack starts the loop, *Then* one tick runs immediately after load, then every interval.
**Files**: `commonMain/.../ui/GraphContentGitSyncSetup.kt`, `commonMain/.../platform/Settings.kt` (key constant only), `androidUnitTest/.../wiring/GitSyncStackEntryPointTest.kt`; `GitSyncService.kt` is touched only by Story 2.0's delegation. The throw/busy/first-tick criteria above are asserted through the stack here and at the driver in Story 2.0.

##### Task 2.1a: Start/restart loop from `GraphContentGitSyncSetup` keyed on `(service, flag, interval)` (M)
- Failing entry-point test first (`GitSyncStackEntryPointTest`, via `WiredAppFixture`). Catalog line `BackgroundSyncScheduler  GitSyncStackEntryPointTest`.
##### Task 2.1b: Stack-level assertions of the driver behaviors (throwing tick, busy skip, first tick after load, interval restart) via `WiredAppFixture` (S)
- The behaviors themselves live in `PeriodicSyncDriver` (Task 2.0b); this task only proves the stack wires them. Test with `runTest` + `advanceTimeBy`; no real sleeps.
##### Task 2.1c: Delete `DesktopSyncScheduler` — OwnerGate OD-5 (XS)
- Superseded by `startPeriodicSync` (register row 1). Remove the file; keep the `BackgroundSyncScheduler` interface (Android implements it).

#### Story 2.2: Android periodic enqueue and registry balance
**Acceptance Criteria**:
- The worker is enqueued in normal use.
  - *Given* the Android stack composed with `scheduled_sync_enabled=true` and interval 5, *When* composition completes, *Then* `WorkManager` (test driver) has one unique periodic work named for the graph with period 15 minutes (the 15-minute floor; settings text says so).
- Disabling cancels it.
  - *Given* an enqueued work, *When* the flag turns off, *Then* the work is cancelled.
- Registry registration is balanced across switches.
  - *Given* 5 consecutive `switchGraph` calls, *When* each stack composes and disposes, *Then* `GitSyncServiceRegistry` holds exactly the active graph's service at the end and never a closed one (pitfalls A3), and a worker run for a registered graph finds its service (`WorkManagerSyncScheduler.kt:138`).
**Files**: `commonMain/.../ui/StelekitAppDependencies.kt` (`StelekitAppPlatformIntegrations` is a data class at `:87`; new non-defaulted `backgroundSyncSchedulerFactory`; default constructions at `:270` and `:292` removed), host constructions `desktop/Main.kt:221`, `wasmJsMain/.../browser/Main.kt:364` and `:511`, `MainActivity.kt:475`, `androidMain/.../git/WorkManagerSyncScheduler.kt`, `androidApp/.../MainActivity.kt` (move `register` beside `registerGitSyncService` per dev ADR-004; `MainActivity.kt:413`), `androidUnitTest/.../git/GitSyncSchedulingEntryPointTest.kt`.
##### Task 2.2a: `backgroundSyncSchedulerFactory` field with no default, explicit `BackgroundSyncSchedulerFactory.None` sentinel, and the stack calling `schedule`/`cancel` (M)
- The field has no default and `StelekitAppDeps.platformIntegrations` loses its `= StelekitAppPlatformIntegrations()` default, so each host construction must name its value; compile error on omission is the point. Non-mechanical files: `StelekitAppDependencies.kt`, `GraphContentGitSyncSetup.kt`, `WorkManagerSyncScheduler.kt`, `GitSyncSchedulingEntryPointTest.kt`.
##### Task 2.2e: Mechanical host edits for Task 2.2a: name the value in `desktop/Main.kt:221`, `wasmJsMain/.../browser/Main.kt:364` and `:511`, `MainActivity.kt:475` (`WorkManager` factory on Android, `None` on desktop/wasm/iOS; a test-only host uses `None` explicitly) and move the `register` call beside `registerGitSyncService` (S, one-line edits; same PR as Task 2.2a)
##### Task 2.2b: Promote `GitSyncServiceRegistry` to `commonMain` (thread-safe) and register in the stack (S)
- Identical to dev plan Task 2.4c; if PR-A2 lands first this task reduces to the balance test.

#### Story 2.3: Settings: Scheduled sync section
**As the** owner, **I want** a switch and interval picker, **so that** I can turn it on and see that it works.
**Copy truth rule**: until U16 the tick only fetches (Story 2.1), so all copy says "check" and "checked", never "sync" and "synced"; U16 changes the label to "Sync automatically" and the status verb to "synced" in the same PR as the pull step (UW-17 and UW-65 are re-pointed then).
**Acceptance Criteria**:
- Controls follow the UX research.
  - *Given* Settings > Git sync with a configured remote, *When* opened, *Then* a switch "Check for updates automatically" (default off) and a radio group with 5, 15, 30, 60 minutes are shown, each row at least 48dp, and a platform helper line: desktop "Checks the remote while SteleKit is open." and Android "Also checks about every 15 minutes while SteleKit is closed. Open the app to merge." (the Android line is true because Story 2.2 enqueues the periodic worker when the flag is on).
- Disabled with reason.
  - *Given* no remote configured, *Then* the switch is disabled with "Set up Git sync first" and a button to the wizard.
- Observable schedule (reads `PeriodicSyncDriver.status`, Story 2.0; no state is added to `GitSyncService`).
  - *Given* a successful tick 3 minutes ago at interval 15, *Then* the text reads "Last checked 3 min ago · next in 12 min" and updates without stealing focus.
- Android clamp is shown, not hidden.
  - *Given* Android (WorkManager floor 15 minutes), *Then* the 5-minute option is disabled with the reason "Android runs at most every 15 minutes", and if a stored interval below 15 exists the section shows "Runs every 15 min on this device" as the effective interval; selecting 15, 30 or 60 shows exactly that value.
- Android background restrictions are shown (UX gap 10).
  - *Given* Android with battery saver, Doze or a restricted standby bucket reported by the injected `BackgroundRestrictionProbe`, *Then* the status line reads "Background checks are restricted by Android. Checks run while SteleKit is open." (`SyncPhase.BackgroundRestricted`; not an error, no badge change, a polite live region on change), it replaces the closed-app sentence of the helper line, and it clears when the restriction ends; desktop never shows it (UW-103, UT-166).
- Stopping state.
  - *Given* the switch is turned off while a tick is running, *Then* the status shows "Stopping..." only if that lasts over 2 s, then "Not scheduled".
- Pause after repeated failures (state owned by the driver) and Resume.
  - *Given* `ScheduledSyncStatus.paused == true` after 3 consecutive failed ticks, *Then* the row reads "Paused after 3 failures - tap to fix" with the last failure cause; tapping the row routes to the matching fix; a separate `Resume` button is **always visible and enabled while paused** (it does not guess whether the cause is fixed) and runs exactly one tick now: success clears the pause, failure keeps it paused and shows the new cause.
  - *Given* the pause, *Then* it is also visible outside Settings: `SyncStatusBadge` shows a "Sync paused" state (amber, icon shape differs from the error state, tap opens Settings > Git sync) within one tick of the third failure (Task 2.3b).
- First-sync review gate (UW-16) is deferred.
  - *Given* dev Task 2.2d0 (the first-sync review flag) is not funded by this plan, *Then* Story 2.3 renders no "Review first sync to enable" row and the interim tick is fetch-only: it makes no change to the working tree or index (asserted by hashing the working tree before and after a tick) and its `SyncTick` log line says `decision=fetch`. A `decision=full` line can only appear after U16 (Story 2.4). The disabled-with-reason row is added in U16 together with Story 2.4. UW-16 is marked deferred in `design/ux.md`.
**Files**: `commonMain/.../ui/components/settings/FolderSyncSettings.kt` or a new `ScheduledSyncSection.kt` beside it (named differently from the Glossary's `ScheduledSyncSettings` keys), `commonMain/.../ui/components/SyncStatusBadge.kt` (paused state), `androidUnitTest/.../settings/ScheduledSyncSettingsTest.kt`, `jvmTest/.../ui/components/SyncStatusBadgeTest.kt` (existing).
##### Task 2.3a: Settings section (label, platform helper, interval picker with Android clamp, status, `BackgroundRestricted` line, Resume) + Robolectric test (M)
##### Task 2.3b: `SyncStatusBadge` paused state fed by `ScheduledSyncStatus.paused`, with the working-tree-unchanged interim assertion (S)

#### Story 2.4: Cutover to full scheduled sync (blocked on dev plan PR-A1 guards and PR-A2 policy; closes metric 4)
**Dependency (declared, not funded here)**: dev-plan Tasks 2.3e, 2.3f, 2.2d0, 2.4a must be merged and released before this story starts; until then success metric 4 is reported "partially met" and U16 does not start.
**Acceptance Criteria**:
- Only the tick lambda changes.
  - *Given* Story 2.0's `PeriodicSyncDriver`, *Then* this story's diff changes the lambda passed from `startPeriodicSync` to `runScheduledSync` and touches no loop, guard, pause or status code (reviewed by diffstat on `PeriodicSyncDriver.kt`: zero lines).
- Tick body delegates to the policy.
  - *Given* dev plan Tasks 2.3e/2.3f (repository-state and mass-change guards), 2.2d0 (first-sync confirmation) and 2.4a (`ScheduledSyncPolicy`, `runScheduledSync`) are merged, *When* `startPeriodicSync` ticks with a remote 2 commits ahead, an idle editor and a confirmed first sync, *Then* `remoteCommitsMerged == 2` and `reloadFiles` received the changed journal paths (dev plan Story 2.4 criterion, exercised here through the stack).
- Guards precede enabling.
  - *Given* the guards are absent (grep for `RepairNeeded` empty), *Then* Task 2.4a refuses to change the tick body (a compile-time dependency on `ScheduledSyncPolicy` makes this structural).
**Files**: `GitSyncService.kt` (`startPeriodicSync` passes `runScheduledSync` as the tick lambda), `businessTest/.../git/ScheduledSyncStackE2ETest.kt`, `jvmTest/.../git/ScheduledSyncOriginE2ETest.kt` (temp origin).
##### Task 2.4a: Switch tick body to `runScheduledSync` (S)
##### Task 2.4b: Entry-point E2E with a temp origin through `GraphContentGitSyncSetup` (M)
- Desktop metric 4 proof; the manual run is item 12.3.

---
## Phase 2: Developer console

### Epic 3: Console (first slice of dev plan Epics 4-6, plus the deltas below)
**Goal**: Metric 3: the owner runs read-only SQL and fetches diagnostics on-device without a rebuild; writes need an explicit confirm and an automatic backup (owner decision 2026-10-27; ADR-006). Design, package layout, `ConsoleContext`, tokenizer, scrollback and UI come from the dev plan Stories 4.1-4.6, 5.1, 5.4, 5.5, 5.8, 6.1-6.4 and `dev-console-git-sync-fix/design/ux.md`; this epic names which of those are built, their order, and the deltas. `git`, `fs`, `sh` and `graph` command families, and `journals-diff`/`index-status` (which need unshipped PR-B), stay in the dev plan and are not built here.

**Command surface**: of the dev plan's `graph` family exactly one verb is built, `graph restore-backup <name>` (which runs `graph reload` internally), plus the read-only `backups` list; `backups restore <name>` is an alias for the same handler. Restore and the backups list are dev-plan-derived (dev ADR-002 and ADR-006) and are recorded as in scope in `requirements.md`.

**Deltas to the dev plan (recorded in ADR-006)**:
1. `sql` is always read-only; `sql!` is the only write verb (matches dev plan Story 5.5 and dev design UX-100) and enters `ConsoleMode.GuardedWrite` for that one statement, through the confirm modal and a preceding `ConsoleBackup`. There is no Settings switch for writes.
2. `QueryDeadline` and user cancel are specified by Spike 0.3d, not left to coroutine cancel.
3. One `Redactor`: Tasks 3.1b and 3.1g-3.1i consolidate the existing copies before the console depends on it.
4. The Developer category visibility fix (`SettingsDialog.kt:142`, `:229`) lands in the same PR as the toggle.
5. Bypass suite (Task 3.2d) is a named acceptance gate, not an incidental test.
6. The console owns its read connection (new `OPEN_READONLY` handle, never `createReadDriver`), and the "credential tables unreadable" claim is replaced by what Spike 0.3e proves (authorizer) or an honest fallback (aliases only, Redactor).
7. Backups are guarded by a free-space check and a retention cap sized for a 451 MB database.
8. Spikes 0.3a-e are a merge gate for U4 and U5, and the `sql!` write path and restore command are compiled out of release builds (`ConsoleBuildFlags.WRITES_ENABLED`, Task 3.3e) until owner pass 12.1 succeeds on a COPY of the real DB (pre-mortem P1-1).

#### Story 3.1: Console core, dark behind developer mode
**As the** owner, **I want** a console screen behind a developer-mode toggle, **so that** I can run `help`, `diag` and `logs` on my phone.
**Acceptance Criteria**:
- Default off hides everything.
  - *Given* a fresh install with `developer_mode_enabled` absent, *When* Settings opens, *Then* the Developer category shows the toggle with the warning "Lets you run SQL writes and shell commands. Your notes can be changed or lost." and no console row exists in Settings or the Logs overflow.
- Visibility fix.
  - *Given* a platform host where `onLibsqlDriverToggle == null` and `onMcpServerToggle == null` (Web), *When* Settings opens, *Then* the Developer category is still listed because the console toggle is non-null (today `SettingsDialog.kt:142` hides it).
- Gate is in the dispatcher.
  - *Given* the flag off and a restored `Screen.Console` route, *When* `ConsoleSession.run("diag")` is called, *Then* it returns `ConsoleError.Denied("developer mode off")` and nothing executes; *Given* `Settings.getBoolean` throws, *Then* the flag is treated as off.
- Turning it off while open.
  - *Given* the console open with a running command, *When* developer mode is switched off, *Then* the screen closes, the command is cancelled and scrollback is cleared.
- Session scope ownership.
  - *Given* `rememberConsoleSession(...)` in `remember {}`, *Then* the session owns `CoroutineScope(SupervisorJob()+Default+CoroutineExceptionHandler)` and a source audit finds no `rememberCoroutineScope` value passed in; *Given* a command that throws `OutOfMemoryError`, *Then* an ERR block appears and the session survives.
- One redactor.
  - *Given* output `origin https://u:ghp_x1y2z3@github.com/o/r.git`, *Then* the scrollback shows `https://[redacted]@github.com/o/r.git`; *And* *Given* a source audit scoped to production `commonMain`, `jvmCommonMain`, `jvmMain`, `androidMain`, `wasmJsMain` and `androidApp/src/main` (tests excluded; wasm's own git writer and credential code are in scope), *Then* the literal secret-pattern prefixes (`ghp_`, `github_pat_`, `glpat-`, `AKIA`) appear in exactly one production file.
- Config-change retention.
  - *Given* a Robolectric orientation change without Activity recreation, *Then* input, scrollback and a pending confirm are retained, and a manifest test asserts `android:configChanges` still contains `orientation|screenSize|uiMode` (`androidApp/src/main/AndroidManifest.xml:44`).
**Files**: `commonMain/.../console/{ConsoleCommand,ConsoleRegistry,ConsoleError,CommandLineTokenizer,ConsoleOutput,ScrollbackBuffer,ConsoleSession,DeveloperMode}.kt`, `commonMain/.../console/commands/{HelpCommand,DiagCommand,LogsCommand}.kt`, `commonMain/.../security/Redactor.kt` (or the existing logging package, chosen by Task 3.1b; no `ConsoleRedactor` alias is kept), `commonMain/.../ui/{ConsoleComposition,GraphContentConsole}.kt`, `commonMain/.../ui/{AppState,ScreenRouter}.kt` (`Screen.Console` next to `Screen.Logs` at `ScreenRouter.kt:229`, `@HelpExempt`), `commonMain/.../ui/components/settings/{SettingsDialog,DeveloperSettings}.kt`, `commonMain/.../ui/screens/ConsoleScreen.kt`, tests under `commonTest/.../console/`, `businessTest/.../console/`, `androidUnitTest/.../console/`.

##### Task 3.1a: Contracts, registry, tokenizer with property test (M)
- Dev plan Tasks 4.1a-c, 4.2a. No reuse of `editor/commands` or the (to be deleted) `command/` package. Bazel placement and wasm compile check included (Dev Task 4.1b); recount `business_tests` classes from the real run.
##### Task 3.1b: Introduce the single `Redactor` and route the three sinks through it (M)
- Files (4-5): `commonMain/.../security/Redactor.kt` (or the existing logging package; no `ConsoleRedactor` alias is kept), its `commonTest` property test, the `Logger` file sink, the diagnostics export, and the console output hook. Property test: generated secret-shaped tokens embedded in arbitrary text never survive; documented limit: hex-encoded secrets are not caught. The exact-secret path reads vault secrets through the existing credential store.
- Recount 2026-10-27 (`grep -rln "ghp_\|github_pat_\|\[redacted\]\|REDACTED" kmp/src androidApp/src`): **14 files, of which 2 are production** (`commonMain/.../asset/pipeline/CloudEnrichmentConfig.kt`, `jvmCommonMain/.../git/GitOperationSupport.kt`) and 12 are tests or fixtures; the earlier "12 files" counted tests. Migration is therefore split by module and by mechanical move into Tasks 3.1g and 3.1h, and the audit into Task 3.1i, so no task touches more than 5 files.
##### Task 3.1g: Migrate `jvmCommonMain` `GitOperationSupport.kt` scrubber to `Redactor` and update its tests (S)
- Files (3-4): `GitOperationSupport.kt` plus the git tests that assert on its redacted output. Behavior-preserving: the existing redaction assertions stay green unchanged.
##### Task 3.1h: Migrate `commonMain` `CloudEnrichmentConfig.kt` scrubber to `Redactor` (S)
- Files (2-3): `CloudEnrichmentConfig.kt` and its test. Only the scrubber moves here; `apiKey` is removed later by Task 6.1c.
##### Task 3.1i: `RedactorSingleSiteAuditTest` and the console-history `PatLeakageAuditTest` variant (S)
- Files (2-3): the audit over production `commonMain`, `jvmCommonMain`, `jvmMain`, `androidMain`, `wasmJsMain` and `androidApp/src/main` asserting `ghp_`, `github_pat_`, `glpat-`, `AKIA` appear in exactly one production file; sources bundled per Task 1.1c. Satisfies Story 3.1's "One redactor" criterion.
##### Task 3.1c: `ScrollbackBuffer` and `ConsoleSession` (M)
- Dev Tasks 4.4a-b: 5,000-line ring buffer, 2 KB cell cap, 60 ms batching, spill-to-file for large output, CEH, `catch Throwable`, history in memory only.
##### Task 3.1d: Developer-mode gate, Settings visibility predicate, toggle UI (S)
- Dev Tasks 4.5a-b with the predicate fix: `SettingsDialog.kt` (481 lines, 6 commits in 90 days) replaces the hard-wired `onLibsqlDriverToggle != null || onMcpServerToggle != null` predicate (`:142`, `:229`) with a list of category-visibility lambdas before the console toggle is added. `androidUnitTest/.../SettingsDeveloperCategoryTest.kt`.
##### Task 3.1e: Composition root, `Screen.Console` route, entry points (M)
- `ScreenRouter.kt` (510 lines) and `AppState.kt` (293 lines, 11 commits in 90 days) gain only a route-table entry (`ConsoleRoute.kt`) and the `Screen` enum value; console state stays in `ConsoleSession`/`DeveloperMode`.
- Dev Task 4.6a: `ui/ConsoleComposition.kt` + `ui/GraphContentConsole.kt`; `GraphContentCameraCapture.kt` diff versus Epic 1 of the dev plan stays empty. Logs-overflow entry visible only in developer mode. U3 creates a **minimal** `androidUnitTest/.../console/ConsoleEntryPointTest` (engineering G1: `WiredAppFixture.android()`, developer mode on, open Settings > Developer > Dev console, run `help`, `ObservableEffect.assert("console-help")`) so the catalog line `ConsoleCommand  ConsoleEntryPointTest  effect:console-help` names a class that exists in this PR; Task 3.5b extends the same class (it does not create it). Validated by IT-101.
##### Task 3.1f: Console screen blocks, `help`, `diag`, `logs`, first-open chips (`tables`, `sql: newest journals`, `diag`, `help`) (M)
- Dev Stories 5.1, 6.1, 6.2 (extra-keys row, chips, history), `diag` reuses `GraphDiagnosticsCollector`; export via `rememberShareProvider`, file `console-<yyyyMMdd-HHmmss>.txt`, redacted.

#### Story 3.2: `sql` read, fail closed
**As the** owner, **I want** to run read-only SQL against the live DB, **so that** I can answer questions like the 2026-10-27 OOM triage without a rebuild.
**Acceptance Criteria**:
- Reads run on the console-owned engine-enforced read-only connection (a separate `OPEN_READONLY` handle that never shares the app pool).
  - *Given* `SqlConsoleReader` on the JVM driver, *When* `sql "select name from pages order by updated_at desc limit 3"` runs on a 3-page fixture, *Then* three rows return in a grid with a header row and an OK chip.
- Mutation bypass suite: every statement below is rejected by the engine, none changes any table's row count, and the Android driver agrees (emulator).
  - *Given* each of `UPDATE pages SET name='x'`, `DELETE FROM blocks`, `WITH c AS (SELECT 1) DELETE FROM pages`, `INSERT INTO pages(uuid,name) VALUES('u','n') RETURNING uuid`, `SELECT 1; DELETE FROM pages`, `PRAGMA writable_schema=1`, `PRAGMA query_only=0`, `PRAGMA journal_mode=DELETE`, `ATTACH DATABASE '/tmp/x.db' AS x`, `VACUUM INTO '/tmp/y.db'`, `SELECT load_extension('x')`, `CREATE TABLE t(a)`, `DROP TABLE pages`, `REPLACE INTO pages ...`, and a mixed-case/comment-obfuscated `/**/dElEtE/**/ FROM pages`, *When* run through `sql`, *Then* the result is an ERR block (DML/DDL: `Denied: plain sql is read-only. Use sql! for writes.`, mapped from the engine read-only error; PRAGMA/ATTACH/`load_extension`/`VACUUM INTO`: the engine error), no file `/tmp/x.db` or `/tmp/y.db` exists, and `SELECT COUNT(*)` of every table equals its pre-run value.
  - *Given* a property test generating mutating statements by wrapping DML/DDL in CTEs, comments, quoting and case variants, *Then* the same invariant holds for all generated inputs.
- No leak into the app.
  - *Given* the console ran `PRAGMA query_only=1`, *When* the app saves a block through `DatabaseWriteActor`, *Then* the write succeeds (pooled connections untouched).
- Bounded results and time.
  - *Given* a table of 100,000 rows with a 1 MB BLOB cell, *When* `select * from t` runs, *Then* at most 500 rows are returned with a "TRUNCATED at 500 rows" marker, each cell shows at most 2 KB, and peak retained heap stays under the test budget (assert via `ConsoleBoundedStreamingTest`).
  - *Given* the recursive-CTE query from Spike 0.3d, *When* 10 s elapse (or Cancel is tapped at 2 s), *Then* the block ends CANCELLED within 1 s of the trigger, partial rows are kept, and the next query on a fresh console connection succeeds.
- Credential exposure is stated honestly (decided by Spike 0.3e; a read-only connection cannot deny table reads and a table-name classifier is bypassable by CTEs, subqueries, aliases and `SELECT * FROM (SELECT ...)`, so no classifier is used).
  - *Given* the repo at HEAD, *Then* `CredentialColumnInventoryTest` (`businessTest`, reads `SteleDatabase.sq` as the existing `sqldatabase_schema_as_resource`) fails if any column named `*_key`, `*token*`, `*secret*`, `*password*`, `*passphrase*` or `*credential*` exists outside the committed list `console-credential-columns.txt`, whose only entries today are `git_config.ssh_key_passphrase_key`, `git_config.https_token_key`, `git_config.oauth_token_key`; and asserts that file records they hold keystore aliases (the secret values live in `CredentialStore`/`LlmCredentialStore`/`VaultCredentialStore`, never in `SteleDatabase`).
  - *Given* Spike 0.3e = **YES** on a driver, *When* `sql "select * from git_config"`, a CTE over it, an aliased column, a subquery and a `JOIN` run on that driver's console handle, *Then* each is denied at the engine by the authorizer (`SQLITE_DENY` on `SQLITE_READ` for the three columns), with identical results on JVM and the emulator.
  - *Given* Spike 0.3e = **NO** on a driver, *Then* the "credential tables are unreadable" claim is **dropped** for that driver (ADR-006 addendum states the real exposure: the alias strings, e.g. `git_https_token_<graphId>`, are visible, not any secret), `sql` output and CSV export pass through the single `Redactor`, and no test claims denial.
- Disabled with a reason where no read-only connection exists.
  - *Given* wasm (no read-only connection) or iOS, *When* `help` runs, *Then* `sql` is listed "disabled: no read-only connection on this platform" and `sql` returns `ConsoleError.Unsupported`.
- Graph closed mid-query.
  - *Given* `GraphManager.switchGraph` closes the DB during a query, *Then* the block ends "graph closed during command" with no crash.
**Files**: `commonMain/.../console/commands/SqlCommand.kt`, `commonMain/.../console/views/SqlConsoleView.kt`, `commonMain/.../db/SqlConsoleReader.kt`, `jvmMain/.../db/SqlConsoleReader.jvm.kt`, `androidMain/.../db/SqlConsoleReader.android.kt`, `commonMain/.../repository/RepositoryFactory.kt` (nullable `sqlConsole` on `RepositorySet`), `businessTest/.../console/{SqlConsoleBypassTest,CredentialColumnInventoryTest,ConsoleBoundedStreamingTest}.kt`, `businessTest/resources/console-credential-columns.txt`, `jvmTest/.../console/SqlConsoleReaderJvmTest.kt`, `androidInstrumentedTest/.../console/SqlConsoleReaderAndroidTest.kt`.

##### Task 3.2a: `SqlConsoleView` and `RepositorySet.sqlConsole` seam (M)
- Rebase onto current `main` first (#397 merged; resolve any `RepositorySet` conflict). Only edit to a shared file (`RepositoryFactory.kt:63` region).
##### Task 3.2b: Read connection per platform: JVM a new non-pooled JDBC connection with `SQLiteConfig.setReadOnly(true)`; Android its own `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)` handle (precedent `GitShadowWorktree.kt:465`; driver class chosen by Spike 0.3e); others disabled (M)
- `DriverFactory.createReadDriver` is **not** reused (Android's returns a read-write `AndroidSqliteDriver`, JVM/wasm/iOS return `null`), and abandon-and-close only ever closes the console's own handle. Installs the authorizer on drivers where Spike 0.3e = YES. Uses Spike 0.3a/0.3e results; no classifier fallback for the mutation guard.
##### Task 3.2c: Row/cell cap by cursor, `QueryDeadline`, cancel button, abandon-and-close fallback (M)
- Uses Spike 0.3d result; coroutine side uses `runInterruptible` on `PlatformDispatcher.DB`; never `Dispatchers.IO` literals.
##### Task 3.2d: Bypass suite and generated-statement property test (M)
- The list in the criterion above is the minimum; add any statement found during implementation.
##### Task 3.2e: `CredentialColumnInventoryTest` over `SteleDatabase.sq` and the Redactor on grid/CSV output (S)
##### Task 3.2f: Result grid, row-cap marker, CSV export, `tables` / `schema <t>` / `EXPLAIN QUERY PLAN` chips (M)
- Dev Stories 6.4; grid scrolls horizontally with sticky first column; `LazyColumn`.

#### Story 3.3: `sql!` guarded writes with automatic backup
**As the** owner, **I want** writes only through an explicit confirm with a backup taken first, **so that** a repair statement cannot silently lose notes.
**Acceptance Criteria**:
- Plain `sql` is always read-only; `sql!` is the only write verb.
  - *Given* developer mode on, *When* `sql "update pages set is_favorite=1 where name='Test'"` runs, *Then* the block reads `Denied: plain sql is read-only. Use sql! for writes.` (the one error string for a write sent to plain `sql`), nothing changes, and no setting turns this off.
  - *Given* `sql! update pages set is_favorite=1 where name='Test'`, *Then* `ConsoleMode.GuardedWrite` is entered for that statement only and `ReadOnly` is restored when it ends (success, cancel or failure).
  - *Given* a build without the write path (release variants before pass 12.1), *Then* `help` lists `sql!` as `disabled: writes not enabled in this build` and running it prints that text; no write code is reachable.
- Confirm modal shows the verbatim statement and a dry-run count.
  - *Given* `sql! update pages set is_favorite=1 where name like 'Proj%'` matching 7 rows, *When* submitted, *Then* a modal shows the statement verbatim, "7 rows will change", and a backup path; Cancel leaves the DB unchanged; default focus is Cancel.
- Backup is bounded and failure blocks the write (451 MB database on a phone).
  - *Given* a database of size D and free space F on the backup volume, *When* a write is confirmed, *Then* if `F < 1.2 x D` the write is **refused** with "Backup failed: not enough free space (need X MB, have Y MB). Nothing was changed." and nothing executes; *When* `VACUUM INTO` fails or `integrity_check` is not `ok`, *Then* the block reads "Backup failed: <reason>. Nothing was changed." (the single failure copy, UW-09) and the DB is unchanged; the confirm modal shows the backup size (`~D MB`); default retention is the 2 newest backups (not 5; configurable up to 5), so the worst case on a 451 MB DB is ~0.9 GB instead of ~2.2 GB; a stale partial backup file from a killed process is removed on the next run.
- Large writes escalate the confirm (the count text alone is not enough friction for a table-wide change).
  - *Given* the dry-run count is at least 1,000 rows **or** at least 50% of the target table's rows, *When* the confirm modal opens, *Then* the primary button is disabled until the owner types the exact count, the modal says "This changes N of M rows in <table>" (M from `SELECT count(*)`), and the verb-and-count button label is unchanged; below the threshold the standard modal applies. `ConfirmTier` is a sealed type (`Standard`, `TypedCount`) decided in one pure function tested with property inputs.
- Dry run is a rolled-back execution, not a classifier (engineering G2; ADR-006 addendum 4).
  - *Given* `sql! update blocks set content='x' where page_uuid=<p>` on a fixture with the FTS triggers installed, *When* the confirm modal opens, *Then* the count comes from executing the statement inside a `DatabaseWriteActor` transaction that ends by throwing the private `DryRunRollback` sentinel; the table and `blocks_fts` after the dry run equal their state before, no invalidation was emitted, and the dry run ends within the 10 s `QueryDeadline` or the write is refused with "Couldn't count the rows this would change in time. Nothing was changed."
  - *Given* a statement whose triggers or cascades change other tables, a statement that touches more than one table, or one the dry run cannot complete, *Then* the modal lists each table with its row count and requires at least the typed count, or, if no count was obtained, the write is refused with "Can't count the rows this would change, so it won't run. Nothing was changed." (the rule is one pure function with `ConfirmTier`, Task 3.3b).
- The confirmed count is re-checked inside the write transaction (UX gap 5).
  - *Given* the owner confirmed "N rows will change", *When* another writer changes the table so that the real write would need `TypedCount` although `Standard` was confirmed, or would change a different number than the typed count, *Then* the write is rolled back before commit, the block reads "More rows would change than you confirmed (N now, M when you confirmed). Nothing was changed. Run it again to re-confirm.", and the backup is kept.
- Backup precedes the first write of a session and each destructive class.
  - *Given* no backup exists, *When* the owner confirms the update, *Then* `console-backups/<graph>-<ts>.db` exists (integrity_check ok) before `UPDATE` runs; *When* an `INSERT` follows within 10 minutes, *Then* no new backup; *When* a `DELETE` follows, *Then* a new backup is taken; backups beyond the retention cap (default 2 newest) are deleted.
- Hard deny (confirm cannot override).
  - *Given* `CREATE TABLE`, `DROP`, `ALTER`, `ATTACH`, `VACUUM`, `load_extension`, a writable `PRAGMA`, `INSERT INTO blocks_fts ...`, a write to migration bookkeeping, and a write to `git_config`, *When* confirmed, *Then* each returns `denied` with the reason. The hard-deny check is a statement classifier applied only to `sql!` input (every `sql!` passes the confirm modal; plain `sql` has no classifier because the verb and the engine-enforced read-only connection decide); where Spike 0.3e = YES the writer connection also denies `SQLITE_UPDATE`/`SQLITE_INSERT`/`SQLITE_DELETE` on `git_config` at the engine, otherwise the classifier plus the confirm modal is the stated limit.
- Actor-routed and tagged.
  - *Given* a confirmed write, *Then* it runs inside `DatabaseWriteActor.execute` (invalidation emitted; app UI refreshes), the result carries the "DB-only, not written to markdown" tag, `diag` lists it in the session dirty list, and `DirectSqlWriteOptInAllowlistTest` (created in Task 3.3a; it does not exist at HEAD) passes with exactly one new allowlisted site (`SqlConsoleWriter.rawExec`) over a baseline of the 40 current `OptIn(DirectSqlWrite` lines.
- No markdown cascade (dev ADR-002 item 5).
  - *Given* a raw write alone, *When* the file watcher and scheduled sync tick, *Then* no markdown file changes and no commit is made.
- Android Auto Backup exclusions (new on-device state must not leave the device).
  - *Given* the Android manifest, which today sets `android:allowBackup="false"` (`androidApp/src/main/AndroidManifest.xml:34`) and ships no backup-rules XML, *When* a test parses it, *Then* either `allowBackup` is still `false` or `fullBackupContent` and `dataExtractionRules` exclude every new state location: `console-backups/`, `console-recovery/`, `git-abort-recovery/`, and the `Settings` store holding the `CloudConsent`, `PipelineRunMarker`, per-day egress counter and `asset_pipeline_enabled` keys. Defense in depth: this task ships the rules files even though `allowBackup=false` already applies, so flipping `allowBackup` later cannot restore a stale consent or run marker (which would grant consent on a new device or trip the crash-loop breaker) or copy a 451 MB backup into cloud backup.
- Gate: spikes first, write path off in release builds.
  - *Given* the ADR-006 addendum lacks any of Spikes 0.3a-e, *Then* this story's PR does not merge; *Given* a release variant build before the owner pass 12.1 is recorded, *Then* `ConsoleBuildFlags.WRITES_ENABLED` is false, `sql!` and `graph restore-backup` are disabled, and a test asserts the default (the flip is a separate one-line PR citing the recorded pass).
- Backup does not stall the app (Android emulator, synthetic ~450 MB database).
  - *Given* `androidInstrumentedTest` on `Pixel_Fold` with a synthetic ~450 MB DB, *When* `ConsoleBackup` runs `VACUUM INTO` while a concurrent `DatabaseWriteActor` write is issued, *Then* the app write completes within the bound recorded by Spike 0.3b (set from its measured backup time, not guessed) and no `database is locked` error surfaces.
- Raw writes cannot desync the FTS index (pitfalls B4).
  - *Given* `sql! insert or replace into blocks ...` and `sql! update blocks set content=...`, *When* the write completes, *Then* an FTS consistency check passes (search for the new content finds the row; no stale `blocks_fts` row for replaced content), or the statement was rejected before it ran, or FTS is repaired in the same actor transaction; the choice is recorded in ADR-006 and tested by `SqlWriteFtsConsistencyTest`.
**Files**: `commonMain/.../db/SqlConsoleWriter.kt`, `commonMain/.../console/ConsoleBuildFlags.kt`, `commonMain/.../db/RestrictedDatabaseQueries.kt` (one `@DirectSqlWrite` raw-exec stub), `commonMain/.../console/commands/SqlWriteGuard.kt`, `commonMain/.../console/ConsoleBackup.kt`, `androidApp/src/main/AndroidManifest.xml` and `androidApp/src/main/res/xml/` (extraction/backup rules), `businessTest/.../db/DirectSqlWriteOptInAllowlistTest.kt`, `businessTest/.../console/{SqlWriteGuardTest,ConsoleBackupTest,SqlWriteCascadeTest}.kt`, `androidUnitTest/.../console/BackupExclusionManifestTest.kt`, `businessTest/.../console/{SqlWriteFtsConsistencyTest,ConsoleBuildFlagsTest}.kt`, `androidInstrumentedTest/.../console/ConsoleBackupConcurrentWriteTest.kt`.
##### Task 3.3a: `SqlConsoleWriter`, `RestrictedDatabaseQueries` stub; first create `DirectSqlWriteOptInAllowlistTest` with the 40 current opt-in sites as baseline, then add the single new site (M)
##### Task 3.3b: `sql!` verb parsing, hard-deny list, rolled-back dry run (row counts, trigger effects, time bound) and the in-transaction re-check; plain `sql` writes map the engine read-only error to the single `Denied: plain sql is read-only. Use sql! for writes.` string (M)
- **Dry-run mechanism**: the dry run executes the real statement inside a `DatabaseWriteActor` transaction (the single-writer path, so no app write interleaves) and always ends by throwing a private `DryRunRollback` sentinel that rolls the transaction back; nothing is committed or emitted as an invalidation. It records `changes()` (rows the statement changed directly) and `total_changes()` before and after (rows changed by triggers and `ON DELETE CASCADE` show up only in the second), plus the tables touched. FTS triggers fire inside the transaction, so their cost counts toward the time and their writes roll back with it. It runs under the 10 s `QueryDeadline`; on timeout it is rolled back and the write is refused. DDL, `PRAGMA`, `ATTACH`, `VACUUM` are hard-denied earlier and never reach the dry run.
- **Escalate-or-refuse rule** (one pure function with `ConfirmTier`, property-tested): (1) one target table and `total_changes` delta equal to `changes()`: tier from the count as in Task 3.3d; (2) triggers or cascades changed other tables (delta greater than `changes()`): the modal lists each table with its count and the tier is at least `TypedCount` on the larger total, whatever the percentage; (3) a statement that touches more than one table directly, or that the dry run cannot complete (error, timeout, or a non-deterministic function such as `random()` or `datetime('now')` in the SET or WHERE): `TypedCount` if a count was obtained, otherwise the write is **refused**; (4) when the table size is unknown the 50% rule is skipped and only the 1,000-row and 10-row rules apply.
- **Re-check in the real write**: the real write runs in one actor transaction and, before commit, compares the actual `changes()` and `total_changes()` with what the owner confirmed; it rolls back with the same sentinel when the confirmed tier was `Standard` and the actual count needs `TypedCount`, or when the tier was `TypedCount` and the actual count differs from the typed count. Validated by UT-161, UT-162, UT-163, IT-97, IT-98, UW-105 and UW-106; the mechanism is recorded in ADR-006 addendum 4.
##### Task 3.3c: `ConsoleBackup` (`VACUUM INTO`, runtime-detect fallback `wal_checkpoint(TRUNCATE)` + copy on a quiesced actor, 1.2x free-space precheck, failure-blocks-write, retention default 2) and manifest exclusions (M)
##### Task 3.3d: Modal triggers line and persistent `Restore backup` action on the write result, confirm tiers (`Standard` / `TypedCount` escalation at >= 1,000 rows, or >= 50% of the table **and** at least 10 rows changed so a 2-row table never trains the owner to type through it; typed fields are exact-match after trimming surrounding spaces, `RESTORE` is case-sensitive, autocorrect and auto-capitalization off, numeric keypad for counts; UX GAP-8/GAP-9, UW-90/UW-91), dirty-list tag, write-cascade regression (M)
- Soft keyboard and process death (UX gaps 3 and 11): the typed-count dialog body uses `imePadding` and the action row stays pinned and visible with the IME open at 360 dp, in landscape and at 2.0x font scale (Robolectric cannot model the IME, so the IME-open check is a pass 12.1 step); a confirm that is open when the process dies leaves nothing behind (no backup, no write, no marker) and is not restored on relaunch. Validated by UW-89, UW-91, UW-104 and IT-103.
- **Split on first-run overrun**: modal triggers line and persistent action (S) / confirm tiers and typed-field specs (S) / dirty-list tag and cascade regression (S), rather than growing this task.
##### Task 3.3e: `ConsoleBuildFlags.WRITES_ENABLED` (false in release variants and desktop distributables, true in debug) gating `sql!` and `graph restore-backup`; `help` shows `disabled: writes not enabled in this build`; default-value test (S)
##### Task 3.3f: Emulator test `ConsoleBackupConcurrentWriteTest`: actor write during `VACUUM INTO` of a synthetic ~450 MB DB on `Pixel_Fold` (M)
##### Task 3.3g: `SqlWriteFtsConsistencyTest` for raw `INSERT OR REPLACE`/`UPDATE` on `blocks`, with the reject-or-repair decision recorded in ADR-006 (S)

#### Story 3.4: Restore a backup (last console action)
**Acceptance Criteria**:
- Restore is a verified swap.
  - *Given* a backup from before a bad `UPDATE`, *When* `graph restore-backup <name>` is confirmed ("later changes are lost"), *Then* the snapshot is `integrity_check`ed on a temp copy, the actor drains, the console reader closes, the live DB and `-wal`/`-shm` are renamed `*.pre-restore`, the snapshot is copied in, the graph is re-added through `GraphManager.removeGraph`/`addGraph`, `graph reload` runs so disk wins, and row counts match the backup.
  - *Given* any step fails, *Then* the `*.pre-restore` files are renamed back and the error names the backup path.
- Restore respects the cross-graph lock rules.
  - *Given* a restore in progress for graph A and a concurrent share or copy writing to A through `TargetWriterRouter`, *When* both run (stress test modeled on `GraphManagerSwitchLockStressTest` / `TargetWriterRouterInFlightSwitchTest`), *Then* neither deadlocks and the router's write lands either fully before the swap or fully after it (never on a half-swapped DB); the swap runs under `GraphWriteLock(A)` and never holds a second `GraphWriteLock`; `GraphManager.awaitPendingMigration()` is **not** awaited while the lock is held (readiness comes from `GraphManager.readyGraph`, not the registry's `activeGraphId`).
- DB-only rows are exported first.
  - *Given* DB-only journal rows (2026-10-07, -09, -10 in the owner's graph), *When* restore starts, *Then* `DbOnlyRowsGuard` exports them to `console-recovery/` and requires a typed confirm.
- The confirm quantifies what is lost (Task 3.4d).
  - *Given* a backup taken 2 days ago with 9,402 pages and a live DB with 9,431 pages, *When* the owner opens Restore, *Then* the confirm shows the backup's age ("2 days ago"), page and block counts of the backup versus the live DB, how many rows differ, and how many DB-only journal rows `DbOnlyRowsGuard` will export first (with the export path); the destructive button is labelled with the verb ("Restore 2-day-old backup").
- Restore has a progress and an outcome state (the swap of a 451 MB DB is long).
  - *Given* a restore is confirmed, *Then* a blocking progress dialog (the `StorageMoveProgressDialog` precedent) shows the current step ("Checking backup", "Exporting local-only rows", "Swapping database", "Reloading graph"), says "Keep SteleKit open", disables Back, and ends in an outcome block: success "Restored. Pages: 9,402, blocks: N - matches the backup" or the failure text naming the backup path; the progress is not a toast and is announced as a polite live region on step change.
- The `*.pre-restore` copy is an undo, with a defined lifetime.
  - *Given* a successful restore, *Then* the outcome offers `Undo restore` (swaps the `*.pre-restore` files back under the same lock rules) and the Backups list shows one entry "Before restore <time>"; only one pre-restore set is kept (the next restore replaces it after the owner is told), it survives app restart, and it is deleted only by an explicit "Delete" or when free space falls below the 1.2x rule (then the owner is told before deletion). The in-block `Restore backup` action on a write result lives in scrollback only (cleared on restart or when developer mode is turned off); after a restart the Backups list is the only entry point.
- (AC 3.4-F) The loss of the undo copy is announced before the swap, not after (UX GAP-5).
  - *Given* free space after the snapshot copy would fall under the 1.2x rule so the `*.pre-restore` set cannot be kept, *When* the owner opens the restore confirm, *Then* the confirm says "Not enough space to keep an undo copy. Continue without undo?" with the extra choice, defaults to Cancel, and no `*.pre-restore` file is deleted before the owner confirms; *Given* a previous `*.pre-restore` set exists that this restore would replace, *Then* the confirm says "This replaces the earlier undo copy from <time>".
- (AC 3.4-G) A restore interrupted by process death, screen-off or backgrounding never leaves a half-swapped database (UX GAP-4).
  - *Given* a `RestoreJournal` marker written before the first rename and cleared only after the outcome, *When* the process is killed after any of the steps "Swapping database" or "Reloading graph" (injected at each step boundary), *Then* the next launch detects the marker before opening the graph, renames `*.pre-restore` back (or completes the already-verified copy-in if the marker records it finished), opens a database whose row counts equal either the pre-restore state or the backup, and shows one notice "A restore was interrupted - your previous data was put back" (or "...was finished") in Settings > Developer > Console backups, one WARN log line, and a **one-time in-app banner on the first open after the recovery**, independent of developer mode and of the console UI (UX gap 1): "A restore was interrupted - your previous data was put back. Nothing from the restore was kept." (or "...was finished.") with `OK` and a `Details` action (Settings > Developer > Console backups when developer mode is on, the Logs screen otherwise); the dismissal persists and the banner does not return (Task 3.4h). *Given* the dialog is visible, *Then* the window keeps the screen on (`FLAG_KEEP_SCREEN_ON` on Android) and the restore continues while the process lives if the owner backgrounds the app; no foreground service is started in this plan (restore is dev-build-only until pass 12.1; the next-launch recovery is the safety net, and pass 12.1 records whether the OS killed a backgrounded restore on the phone). A power-button screen-off is allowed and is covered by the same recovery path.
- (AC 3.4-H) Undo of an undo is defined (UX GAP-14).
  - *Given* `Undo restore` is confirmed, *Then* it swaps the live DB and the `*.pre-restore` set, so the set now holds the state that was live just before the undo and the Backups list shows exactly one "Before restore <time>" entry (the set is exchanged, never duplicated, never two sets); undoing again returns to the first state; the copy is a toggle between two states. Each undo shows the same confirm and the same "no undo copy" rule.
- (AC 3.4-I) Restore dialogs survive configuration change (UX GAP-6).
  - *Given* the restore confirm with "RESTORE" half typed, or the progress dialog at "Swapping database", *When* the device rotates, folds or unfolds, enters split-screen or the font scale changes, *Then* the typed text, the step and the scroll position are unchanged, the restore is not restarted and is not cancelled, and Back stays disabled.
- (AC 3.4-J) Restore progress shows elapsed time and a stall state, and a failed rollback has a copy and an exit (UX gap 2).
  - *Given* the progress dialog is visible, *Then* it shows an elapsed-time line ("Elapsed 2:10", updated every second and not announced to screen readers; only step changes are), and *Given* a step has run past twice its expected duration (the Spike 0.3b measured time for this backup size, floor 3 minutes), *Then* the dialog adds "This is taking longer than expected. Keep SteleKit open. If it is interrupted, your previous data is put back the next time SteleKit starts." with a `Backups` link; nothing is cancelled automatically and Back stays disabled for the restore itself.
  - *Given* a step fails and the automatic rollback to `*.pre-restore` also fails, *Then* the outcome reads "Restore failed and your previous data could not be put back automatically. Your files are safe at <paths>." with `Copy details` and `Close SteleKit` (the exit); the `RestoreJournal` marker is kept so the next launch retries the rollback, and one ERROR log line names both paths.
- (AC 3.4-K) Destructive follow-ups have confirms (UX gap 7).
  - *Given* `Delete` on the "Before restore <time>" entry, *Then* a standard confirm "Delete the undo copy from <time>? You won't be able to undo the restore afterwards." with `Delete` and `Cancel` (Cancel default) appears; *Given* `Undo restore`, *Then* the standard confirm (Cancel default) applies and typing `RESTORE` is **not** required, because the undo is itself reversible (AC 3.4-H) and returns a state the owner already had.
- (AC 3.4-L) A confirm dialog open when the process dies leaves nothing behind (UX gap 11).
  - *Given* the restore confirm with `RESTORE` half typed, *When* the process is killed, *Then* after relaunch no `RestoreJournal` marker, no `*.pre-restore` file, no snapshot copy and no database change exists and the confirm is not restored.
- Restore stays out of release builds until the owner has verified it on a COPY.
  - *Given* `ConsoleBuildFlags.WRITES_ENABLED` is false, *Then* `graph restore-backup` is disabled; the first real use is owner pass 12.1 on a copy of the real 451 MB DB, never on the live DB.
**Files**: `commonMain/.../console/views/DbRestoreView.kt`, `commonMain/.../console/commands/GraphRestoreCommand.kt`, `commonMain/.../console/DbOnlyRowsGuard.kt`, `jvmTest/.../console/DbRestoreSwapTest.kt`, `businessTest/.../console/{DbOnlyRowsGuardTest,DbRestoreLockStressTest}.kt`.
##### Task 3.4a: Swap mechanics through public `GraphManager` API only (no new methods on the 1601-line class) (M)
##### Task 3.4e: The `backups` command and the Settings > Developer > "Console backups" list (rows: retained snapshots, unreadable reason, Restore, Copy path; `backups restore <name>` is an alias of `graph restore-backup <name>`) (S; split from Task 3.4a)
##### Task 3.4b: `DbOnlyRowsGuard` and recovery export (M)
##### Task 3.4c: Restore lock-order discipline under `GraphWriteLock` and the concurrent-writer stress test (M)
##### Task 3.4d: Restore preflight summary (age, counts, DB-only rows, and the "no undo copy" warning of AC 3.4-F), progress and outcome state, `Undo restore` from `*.pre-restore` with single-copy retention and the undo-of-undo rule, and the standard confirms of AC 3.4-K for `Delete` and `Undo restore` (S)
##### Task 3.4f: `RestoreJournal` marker and next-launch recovery: write the marker before the swap, auto-rollback a half-swap to `*.pre-restore` at the next launch before the graph opens, keep-screen-on during the dialog, state restoration across configuration change (M); validated by UT-150, UT-151, IT-93 and UW-86, UW-88
- **Split on first-run overrun**: marker and next-launch recovery (M) from keep-screen-on and state restoration across configuration change (S).
##### Task 3.4g: Restore progress elapsed-time line and stall state, and the rollback-failure outcome with copy and exit (AC 3.4-J) (S)
- Files (3): the progress and outcome composables in `DbRestoreView.kt`, a pure `RestoreProgressState` (virtual-time testable), and the rollback-failure outcome type. Validated by UT-167, IT-100 and UW-98.
##### Task 3.4h: One-time interrupted-restore banner independent of developer mode, dismissal persisted in `Settings` (AC 3.4-G) (S)
- Files (3-4): a `RestoreRecoveryNotice` read at launch beside the journal check, `RestoreRecoveryBanner.kt` in the app shell, and its `Settings` key, which is added to the Task 3.3c backup-exclusion list. Validated by UT-170 and UW-86.

#### Story 3.5: Accessibility, retention and entry-point end to end
**Acceptance Criteria**:
- Entry-point E2E.
  - *Given* `WiredAppFixture.android()` with developer mode on, *When* the test opens Settings > Developer > Dev console, runs `help` then `sql "select 1"`, *Then* the screen shows the OK block "1" and the audit log line contains the verb but no secrets.
- First-open smoke test on the shipped root.
  - *Given* `FirstOpenSmokeTest` (Task 1.3c) launching `MainActivity` with developer mode on, *When* Settings > Developer > Dev console opens, *Then* it renders and `help` runs without a crash.
- TalkBack semantics.
  - *Given* a finished block, *Then* its node has a single focusable summary `contentDescription` ("Command sql, succeeded in 120 ms, 14 rows") and a polite live region on the status chip only; chips and extra keys are at least 48dp; layout holds at 360dp and font scale 2.0.
**Files**: `androidUnitTest/.../console/{ConsoleEntryPointTest,ConsoleAccessibilityTest,ConsoleRetentionTest}.kt`.
##### Task 3.5a: Accessibility and retention suite on Robolectric (M)
##### Task 3.5b: Entry-point E2E through the real stack: extends the minimal `ConsoleEntryPointTest` created in Task 3.1e with `sql "select 1"` and the audit-log assertion (the catalog line already exists from Task 3.1e) (M)
- The TalkBack pass and soft-keyboard ergonomics cannot be tested; item 12.1.
##### Task 3.5c: Post-pass flip PR: `ConsoleBuildFlags.WRITES_ENABLED` true in release variants and desktop distributables (XS; unit U18; OwnerGate)
- One-line change plus the default-value test update (UT-118 and UT-119 flip their release-variant expectation in this PR only). The PR description cites the recorded owner pass 12.1 on a COPY of the real 451 MB DB; until it merges, pass 12.1 runs on a debug build with the flag true. Validated by UT-169.

---

## Phase 3: Asset pipeline

### Epic 4: Asset indexing and moves
**Goal**: `asset_index` is populated and moves are safe, so the shipped Assets screen shows real data (register rows 4-5). Placement per ADR-004.

#### Story 4.1: Construct and wire `AssetIndexService`
**As the** owner, **I want** attached and existing assets indexed, **so that** the Assets screen is not empty.
**Acceptance Criteria**:
- Attach path registers.
  - *Given* `WiredAppFixture.android()` with a fake `FileSystem` and an image attached through `MediaAttachmentService`, *When* the attach completes, *Then* `asset_index` has one row with the file's relative path and content hash, and an attach failure in the index never blocks the attach.
- Idempotent on content hash.
  - *Given* the same bytes attached twice, *Then* one row.
- Backfill after load, bounded.
  - *Given* a graph with 3,000 files under `assets/`, *When* the graph reaches fully loaded, *Then* indexing proceeds in batches of 50 on `PlatformDispatcher.Default`, the file walk is a streaming iterator (no list of 3,000 paths held), and `loadGraph` never overlaps it; *When* the graph is switched mid-run, *Then* the run is cancelled.
- Assets screen reads real data.
  - *Given* the above, *When* `AssetBrowserViewModel` loads, *Then* it shows the indexed rows (JVM Compose over real SQLDelight plus an on-device test per CLAUDE.md "Android debugging").
- Owner can switch indexing on before the full Plugins page exists (needed for pass 12.2a and the staged U7/U8-then-U9 release).
  - *Given* a debug/internal build (`AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE` true), *When* the owner opens Settings > Plugins, *Then* the placeholder is replaced by one row "Asset indexing" (helper "Scans attached files so they appear in Assets. Off by default.") bound to `asset_pipeline_enabled`; when on it shows one status line ("Indexing files..." then "N of M indexed", from the index walk's own progress (files indexed of files found), not `countUnprocessedAssets`, which only the plugin drain decrements and which therefore stays at the row count while no plugin key is set); flipping it starts or stops the Task 4.1a stack without a restart. *Given* a release variant before the flip PR, *Then* the row is absent and the key stays false (a default-value test asserts it). *Given* the crash-loop breaker (Task 5.1g) turned the flag off, *Then* the row shows off with the breaker's text.
**Files**: `commonMain/.../ui/GraphContentAssetSetup.kt` (new stack), `commonMain/.../ui/components/settings/PluginsSettings.kt` (interim row), `commonMain/.../asset/pipeline/AssetBuildFlags.kt`, `androidUnitTest/.../settings/AssetIndexingSwitchTest.kt`, `commonMain/.../asset/AssetIndexService.kt` (replace `collectFilesRecursively` with a streaming walk), `commonMain/.../ui/GraphContentGraphIoSetup.kt` or the media attach call site, `androidUnitTest/.../wiring/AssetStackEntryPointTest.kt`, `businessTest/.../asset/AssetIndexBackfillBoundedTest.kt`, `androidInstrumentedTest/.../AssetBrowserIndexedE2ETest.kt`.
##### Task 4.1a: `GraphContentAssetSetup.kt` constructing the index service, `DisposableEffect` shutdown, started after `onFullyLoaded`, flag `asset_pipeline_enabled` (M)
##### Task 4.1b: Hook `registerAsset` into attach/import/share paths (M)
##### Task 4.1c: Streaming bounded walk replacing `collectFilesRecursively`; cancel on switch (M)
##### Task 4.1d: Minimal Settings > Plugins "Asset indexing" switch bound to `asset_pipeline_enabled`, and `AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE` (false in release variants until the post-gate flip PR; true in debug/internal builds) (S)
- Files: `commonMain/.../ui/components/settings/PluginsSettings.kt` (replaces the "No plugins installed" placeholder with this one row), `commonMain/.../asset/pipeline/AssetBuildFlags.kt`, `androidUnitTest/.../settings/AssetIndexingSwitchTest.kt`. Task 5.5b later builds the full list around this row and keeps it as the master switch. Validated by UW-97.

#### Story 4.2: Wire `AssetMoveService`
**Acceptance Criteria**:
- WAL replay at open.
  - *Given* a `asset_pending_moves` row left by a crash between the file move and the reference rewrite, *When* the graph opens, *Then* `replayPendingMoves` completes the move and rewrites references through `GraphWriter`, and the row is cleared.
- Moves never write markdown behind `GraphWriter`.
  - *Given* a user moves `assets/a.png` to `assets/photos/a.png`, *Then* the page block reference changes via `GraphWriter`, the watcher does not report an external change for the moved path, and a double tap on Move performs one move.
**Files**: `GraphContentAssetSetup.kt`, `commonMain/.../ui/assets/AssetDetailScreen.kt` (Move action if absent), `businessTest/.../asset/AssetMoveReplayEntryPointTest.kt`.
##### Task 4.2a: Construct service, call `replayPendingMoves` at open (M)
##### Task 4.2b: Surface Move in the asset detail screen with double-tap guard (S)

### Epic 5: Pipeline core and on-device plugins
**Goal**: Register rows 6-10, 13, 15, 20. Bounded, cancellable, off by default (NFR).

#### Story 5.1: `AssetPipelineService` correctness and progress
**PR packaging** (Delivery units table, U8): Tasks 5.1d, 5.1e and 5.1f land as separate stacked PRs before the gate PR; only Task 0.4a and Task 5.1a must share a PR (red commit, then green commit).
**Acceptance Criteria**:
- Drain reaches a terminal state for every asset (the Story 0.4 test turns green).
  - *Given* 25 unprocessed assets, 7 of which make the plugin return `Left`, *When* the backfill runs, *Then* all 25 end `mlProcessed=true` or `mlFailed=true`, none is processed twice, and the loop terminates when a batch is fully attempted.
  - *Given* property test over N in 0..60 and a random failure mask, *Then* the same terminal-state invariant holds.
- One in flight, off the DB dispatcher.
  - *Given* backfill running, *Then* at most one `processAsset` is active (semaphore) and plugin work runs on `PlatformDispatcher.Default`.
- Cancel on memory pressure and graph switch.
  - *Given* `MainActivity.onTrimMemory` (`MainActivity.kt:526`, handler invoked `:540`) reaches `StelekitViewModel.onMemoryPressure()` (`:1440`, beside `graphLoader.cancelBackgroundWork()`), *Then* a `MemoryPressureListeners` registry that the ViewModel's one added line notifies cancels the backfill and the in-flight plugin, and `PipelineProgress` becomes `Paused(PauseReason.Memory)` (sealed `PauseReason`: `Memory`, `SessionCap`, `DailyCap`, `Network`, `Charger`, `User`; not strings).
- Progress state.
  - *Given* 340 of 2,100 processed and 12 failed, *Then* `PipelineProgress.Running(done=340, total=2100, failed=12)` is exposed and `total` comes from `countUnprocessedAssets`.
- Writes only through the actor (no bypass).
  - *Given* `AssetPipelineService`, *Then* its `writeActor: DatabaseWriteActor` is a **non-null** constructor dependency, the `DirectRepositoryWrite` import and the `if (writeActor != null) ... else direct repository write` fallbacks at `AssetPipelineService.kt:37,77-93` are deleted, `processAsset`/`scheduleProcess`/`scheduleBackfill`/`drainUnprocessed` take no actor parameter, and a source audit (`CaptureWriterSourceAuditTest` style) finds no `@OptIn(DirectRepositoryWrite::class)` in `asset/pipeline/`; tests construct a real actor over in-memory repositories.
- List queries do not materialize blobs.
  - *Given* `selectUnprocessedAssets` and `searchAssets` are `SELECT *` (`SteleDatabase.sq:1187,1190`), *Then* the drain and search list paths use projected `...Light` queries that omit `ocr_text` and `auto_labels`, and `QueryPlanAuditTest` covers them (Task 5.1f).
- Plugin containment (injected `OutOfMemoryError`).
  - *Given* a fake plugin throwing `OutOfMemoryError` from `processAsset`, from the downscale step and from the `ocr_text` write path (three injection points), *Then* in each case the asset is marked failed, the pipeline continues, the process survives (CEH on the service scope), and the `PipelineRunMarker` is cleared on the clean stop.
- Crash-loop breaker (pre-mortem P1-2; the user cannot reach Settings if the app dies on launch).
  - *Given* the pipeline starts and persists a `PipelineRunMarker` (Settings key), *When* the process exits without clearing it (pause, finish or cancel clears it) on two consecutive launches (also readable from `ApplicationExitInfo` on API 30+, already replayed by `PreviousSessionLog`), *Then* the third launch sets `asset_pipeline_enabled=false`, logs why at WARN, starts nothing, and Settings > Plugins shows "Turned off after repeated crashes" with a re-enable switch that resets the counter; *Given* one unclean exit, *Then* the flag stays on and the counter is 1; *Given* a clean pause, *Then* the counter resets to 0.
**Files**: `commonMain/.../asset/pipeline/AssetPipelineService.kt`, `commonMain/.../asset/pipeline/{PipelineProgress,PauseReason}.kt`, `commonMain/.../ui/MemoryPressureListeners.kt`, `commonMain/.../ui/StelekitViewModel.kt` (one line in `onMemoryPressure()` at `:1440`), `commonMain/sqldelight/.../SteleDatabase.sq` (projected read queries; regenerate and commit generated sources), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightAssetRepository.kt`, `businessTest/.../asset/pipeline/{AssetPipelineDrainBugTest,AssetPipelineTerminalStateTest,AssetPipelineProgressTest,AssetPipelineCrashLoopBreakerTest,AssetPipelineOomInjectionTest}.kt`, `commonMain/.../asset/pipeline/PipelineRunMarker.kt`.
##### Task 5.1a: Fix `drainUnprocessed`: read offset 0, filter `AttemptedSet`, advance offset only past rows that remain unprocessed (S)
##### Task 5.1b: Semaphore(1), `Default` dispatcher, `AttemptedSet` cleared per run, per-plugin circuit breaker, Throwable guards (M)
##### Task 5.1c: `PipelineProgress` StateFlow, pause/resume/cancel API (S)
##### Task 5.1d: `MemoryPressureListeners` registry notified from `StelekitViewModel.onMemoryPressure()` (`:1440`); the asset stack subscribes (S)
- Before editing, re-read the Activity caller chain (`MainActivity.kt:526/540/472`, `GraphContentBootstrapEffects.kt:37-42`): the handler is a single slot, so the registry must fan out rather than add a second slot.
##### Task 5.1e: Make `writeActor` a non-null constructor dependency; delete the `DirectRepositoryWrite` fallback; update tests to a real actor (S)
##### Task 5.1f: Projected list queries (`selectUnprocessedAssetsLight`, `searchAssetsLight`, `selectAssetsSearchPreview`), regenerate SQLDelight sources, `QueryPlanAuditTest` entries (S; the regenerated `kmp/src/generated/sqldelight/` files are mechanical `rsync` output and are exempt from the file-count rule)
##### Task 5.1g: Crash-loop breaker: persisted `PipelineRunMarker`, auto-disable `asset_pipeline_enabled` after 2 consecutive unclean exits, injected-`OutOfMemoryError` tests at three points (M)
- The marker and counter live in `Settings` and are covered by Task 3.3c's backup-exclusion rules (a restored marker would trip the breaker on a new device); validated by UT-126..UT-128, IT-88 and UT-84.

#### Story 5.2: `defaultAssetPlugins(PlatformCaps)` and platform wiring
**Acceptance Criteria**:
- Total function of capabilities.
  - *Given* `PlatformCaps(imageLabeler=MlKitImageLabeler, pdfText=AndroidPdfTextExtractor, textRecognizer=MlKitTextRecognizer, cloud=null)` and the three on-device plugin keys true, *When* `defaultAssetPlugins` runs, *Then* the list is `[OnDeviceLabelingPlugin, PdfTextPlugin, OcrPlugin]` and contains no cloud plugin; *Given* `cloud=null` (`PlatformCaps.NONE`), *Then* the cloud plugin class is not constructed anywhere (structural test).
  - *Given* `cloud` is a **factory** (`CloudPluginFactory`, not a start-time constant), *When* the user grants or revokes consent mid-session, *Then* the plugin list is re-derived from the registry/gate on the next drain without an app restart, and a revoked graph's drain contains no cloud plugin (see Story 6.2 for the enforcement point).
- Staged rollout is real: the per-plugin enable keys are read in U8, not U10 (triad round 3, engineering B1).
  - *Given* `asset_pipeline_enabled` is true and no `asset_plugin_<id>_enabled` key is set (`text_recognition`, `image_labels`, `pdf_text`; default false; a read failure counts as false), *When* `defaultAssetPlugins` runs with a fully populated `PlatformCaps`, *Then* it returns no on-device plugin, `AssetPipelineService` does not start a drain (no asset is marked processed or failed and no `processAsset` is called), and only `AssetIndexService` runs; *Given* only `asset_plugin_image_labels_enabled` is true, *Then* the list is `[OnDeviceLabelingPlugin]`; the keys are re-read at each drain start, so a change takes effect without a restart.
  - *Given* `AssetStackEntryPointTest` with the master flag on and every plugin key unset, *When* a PNG and a PDF are attached, *Then* `asset_index` has both rows with `ml_processed=0` and the fake labeler and extractor call counts are 0 (the inert-when-unset test, UT-164 and IT-102).
- Entry points build real capabilities, not fakes.
  - *Given* the Android root's real `platformCaps(context)` factory function (extracted from `SteleKitApplication` so a test can call it) and the desktop `jvmPlatformCaps()` from `desktop/Main.kt`, *When* `PlatformCapsRootTest` (`androidUnitTest` / `jvmTest` with `// jvmTest-reason: desktop-only`) executes them, *Then* the real `MlKitImageLabeler`/`AndroidPdfTextExtractor` (construction only, no inference) and `JvmPdfTextExtractor` are the instances returned, never `NoOp*` on a capable platform; fixtures may inject fakes, but this root-function test may not.
- Android heap gate (BLOCKING: U8 cannot merge, and the flag cannot default on, until it has run).
  - *Given* `AssetPipelineHeapGateTest` in `androidInstrumentedTest` on the `Pixel_Fold` AVD with `largeHeap` off and the heap capped at 512 MB, *When* the real pipeline (real `defaultAssetPlugins` path with fake ML ports, flag on) runs twice, once with every plugin key unset (indexing only, the configuration U8 ships) and once with all on-device plugin keys set (the configuration U9 ships), each over a synthetic 9.5k-page graph and a 9.5k-row `asset_index`, including a batch with a 40 MP fixture, *Then* no `OutOfMemoryError` or process death occurs and peak heap stays under the budget recorded in the test (set from the first measured run, INFERRED until then); the test fails if `asset_pipeline_enabled` default is flipped to true without this test passing. The AVD is seeded to the owner's scale before each run and the test first asserts its preconditions (database file at least 451 MB, produced by the Spike 0.3b generator; `asset_index` row count at least 9,500; `Runtime.maxMemory()` at most 512 MB) and fails otherwise, because a pass on a smaller database or a larger heap does not falsify the 512 MB assumption (product item 3). The JVM-only `AssetStackStartupHeapTest` and a Robolectric run are not sufficient (they cannot see Android heap behavior).
- Models absent degrade honestly.
  - *Given* JVM with no ONNX model file, *Then* `imageLabeler=NoOpImageLabeler`, never a failing silent plugin; the Plugins-row text "model not installed" is asserted in Story 5.5 (the row is built in U10, not U8).
- No startup cost.
  - *Given* the flag off, *When* the app starts, *Then* no plugin is constructed and startup heap equals the flag-less build within the perf test tolerance; *Given* flag on, *Then* models load on first use only.
**Files**: `commonMain/.../asset/pipeline/DefaultAssetPlugins.kt`, `commonMain/.../asset/pipeline/PluginEnableKeys.kt`, `commonMain/.../ui/StelekitAppDependencies.kt` (`platformCaps` field on `StelekitAppPlatformIntegrations` at `:87`, no default, `PlatformCaps.NONE` sentinel named by each host: `desktop/Main.kt:221`, wasm `Main.kt:364`/`:511`, `MainActivity.kt:475`), `androidApp/.../SteleKitApplication.kt`, `jvmMain/.../desktop/Main.kt`, `businessTest/.../asset/pipeline/DefaultAssetPluginsTest.kt`, `androidUnitTest/.../wiring/PlatformCapsRootTest.kt`, `jvmTest/.../wiring/JvmPlatformCapsRootTest.kt`, `jvmTest/.../performance/AssetStackStartupHeapTest.kt`, `androidUnitTest/.../performance/AssetStackHeapBudgetTest.kt`, `androidInstrumentedTest/.../performance/AssetPipelineHeapGateTest.kt`.
##### Task 5.2a: `PlatformCaps` (with `NONE`), `defaultAssetPlugins`, the per-plugin enable-key read (`asset_plugin_<id>_enabled`, default false) with its inert-when-unset tests, and the no-default `platformCaps` field on `StelekitAppPlatformIntegrations` (M)
- The key read lives here, in U8, so the master flag alone cannot start a plugin; an empty plugin list means no drain starts. The Settings switches that write the keys are Task 5.5c (U10). If the first run does not finish, split the key read and its tests (S) from `PlatformCaps` and the field (S).
##### Task 5.2d: Mechanical host edits: extract `platformCaps()` / `jvmPlatformCaps()` root factories (`SteleKitApplication.kt`, `desktop/Main.kt`) and name the value in all four host constructions (`desktop/Main.kt:221`, wasm `Main.kt:364`/`:511`, `MainActivity.kt:475`) (S, one-line edits per host; lands in the same PR as Task 5.2a because the no-default field breaks every host until each names a value)
##### Task 5.2b: Lazy construction and startup-heap test (S)
##### Task 5.2c: Real-caps root-function tests and the Android 512 MB emulator heap gate — blocking gate, result pasted into the U8 PR description (M)
- Reclassified S to M (repair iteration 3): it bundles two platforms' root-function tests (`PlatformCapsRootTest`, `JvmPlatformCapsRootTest`) with the riskiest gate in the plan (`AssetPipelineHeapGateTest` on `Pixel_Fold`, synthetic 9.5k-page graph, 40 MP fixture). If the first emulator run needs more than one worker run, split the root-function tests (S) from the emulator gate (M) rather than growing this task.
- The gate runs twice (indexing only with all plugin keys unset, then all on-device plugin keys set) on an AVD seeded to the owner's scale: a 451 MB-class database, a 9.5k-row `asset_index` and a 512 MB heap cap, asserted as preconditions by the test itself; the result of both runs is pasted into the U8 PR description and U9 may not ship without the second.
##### Task 5.2e: Post-gate flip PR: `AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE` true in release variants (XS; unit U19; OwnerGate)
- One-line change plus the default-value test update; the PR cites both recorded Task 5.2c runs. It exposes the switch only: `asset_pipeline_enabled` and every plugin key stay false by default. Pass 12.2a does not wait for it: the owner runs a **debug/internal build** (installed by the implementing agent with `bazel mobile-install //kmp:android_app --config=android` or `./gradlew installAndroid`) because the row is absent in release variants before this PR. Validated by UT-168.

#### Story 5.3: Real OCR through a `TextRecognizer` port
**Acceptance Criteria**:
- `OcrPlugin` no longer labels.
  - *Given* a fake `TextRecognizer` returning "Invoice 4821", *When* `OcrPlugin.processAsset` runs on an image asset, *Then* the result is `OcrText("Invoice 4821")`; *Given* the recognizer returns `Left`, *Then* the plugin returns `Left` and the asset is marked failed.
- ML Kit implementation.
  - *Given* the `text-recognition:16.0.1` artifact (already declared, `kmp/build.gradle.kts:312`) and a Robolectric/instrumented fixture image, *When* `MlKitTextRecognizer.recognize` runs on the Pixel_Fold emulator, *Then* a known test image returns its printed text (on-device test; Robolectric cannot run ML Kit).
- JVM default.
  - *Given* JVM, *Then* `NoOpTextRecognizer` is used (OD-3); the Plugins-row text "not available on desktop" is asserted in Story 5.5.
**Files**: `commonMain/.../platform/ml/TextRecognizer.kt` (+ `NoOpTextRecognizer` in `MlServiceStubs.kt`), `androidMain/.../platform/ml/MlKitTextRecognizer.kt`, `commonMain/.../asset/pipeline/OcrPlugin.kt`, `businessTest/.../asset/pipeline/OcrPluginTest.kt`, `androidInstrumentedTest/.../MlKitTextRecognizerTest.kt`.
##### Task 5.3a: Port, no-op, `OcrPlugin` rewrite with tests on fakes (S)
##### Task 5.3b: `MlKitTextRecognizer` (M)
##### Task 5.3c: Instrumented test on the emulator (S)

#### Story 5.4: On-device plugins end to end
**Acceptance Criteria**:
- Through the real stack.
  - *Given* `WiredAppFixture.android()` with fake `ImageLabeler` returning `["cat","indoor"]` and fake `PdfTextExtractor` returning "Q3 report", flag on, *When* a PNG and a PDF are attached, *Then* `asset_index` shows `auto_labels="cat,indoor"` for the PNG and `ocr_text` containing "Q3 report" for the PDF, `ml_processed=1` for both, written in one actor transaction each.
- Heap-safe decode.
  - *Given* a 40 MP image, *Then* the plugin receives a downscaled decode via `ImageDownscalerPort` (or `DecodeBudget` once the image-pipeline plan lands), never the original bytes; test asserts the bytes passed to the labeler are below the budget.
- Crash between labels and flag.
  - *Given* a simulated crash after labels but before the processed flag, *Then* both are written or neither is (single transaction).
- Stored text is capped.
  - *Given* a PDF whose extracted text is 5 MB, *Then* `ocr_text` is truncated at 64 KB (constant `MAX_OCR_TEXT_BYTES`, cut at a code-point boundary, with a `truncated` log line) before the actor write, and no list query returns the column (Story 5.1 projected queries).
**Files**: `commonMain/.../asset/pipeline/{OnDeviceLabelingPlugin,PdfTextPlugin}.kt`, `androidUnitTest/.../wiring/AssetStackEntryPointTest.kt`.
##### Task 5.4a: End-to-end wiring tests, downscale before ML, single-transaction write (M)

#### Story 5.5: `PluginHost` and Settings > Plugins
**As the** owner, **I want** to enable plugins and see progress, **so that** I control battery, heap and cost.
**Decision**: one enabled-state registry. `PluginRegistry` is the single source of truth; the Logseq-style `PluginHost`/`Plugin` (with `onEnable/onDisable`) is not bridged to it by an adapter, because that would create two sources of "enabled". It has no in-tree plugin (`research/architecture.md` §6) and is removed by Task 5.5d (register row 20; OwnerGate OD-5). `JsPluginBridge` and the file `PluginSystem.kt` are not touched here: the bridge references neither type and is gated by OD-2, so Task 10.4a removes them.
**Acceptance Criteria**:
- First-open smoke test on the shipped root.
  - *Given* `FirstOpenSmokeTest` (Task 1.3c) with `asset_pipeline_enabled` on, *When* Settings > Plugins opens, *Then* it renders without a crash.
- Replaces the empty placeholder.
  - *Given* Settings > Plugins on Android, *When* opened, *Then* it lists "Text recognition", "Image labels", "PDF text" with switches (default off) and no longer shows "No plugins installed" (`PluginsSettings.kt:12-16`).
- Capability reasons on the rows (moved here from Stories 5.2 and 5.3, because this story builds the row).
  - *Given* JVM with no ONNX model file (`NoOpImageLabeler`), *Then* the "Image labels" row is disabled with the text "model not installed"; *Given* JVM (`NoOpTextRecognizer`, OD-3), *Then* the "Text recognition" row is disabled with "not available on desktop"; never a hidden row or a silent failing plugin.
- The cloud plugin counts in the status bar (UX GAP-2 decision).
  - *Given* cloud consent `Granted` for the active graph and the cloud switch on, *Then* `PluginRegistry.enabledCount` includes the cloud plugin (so "N plugins active" counts it); *Given* `NotAsked` or `Revoked`, *Then* it does not.
- `View log` is reachable without developer mode and pre-filtered (UX GAP-13).
  - *Given* developer mode off, *When* the owner taps `View log` on the progress row or a plugin failure, *Then* the Logs screen opens already filtered to pipeline lines (today `Screen.Logs` is a `data object` with no filter argument, `AppState.kt:54`, reached from the top-bar overflow, `TopBar.kt:203-206`; Task 5.5e adds the initial-filter argument) and no Developer-category setting is required.
- Status bar is truthful and reads the single registry.
  - *Given* two plugins enabled in `PluginRegistry`, *Then* the status bar reads "2 plugins active" (`GraphContentStatusAndBottomBar.kt:54`) from `PluginRegistry.enabledCount`; *Given* none, it shows nothing; *Given* a source audit, *Then* no production class reads enabled state from anything other than `PluginRegistry` (the stack-level `AssetStackEntryPointTest` asserts the count through `WiredAppFixture`, not only a settings component test).
- Progress row states and controls (every state in `design/ux.md` P4).
  - *Given* `Cancelled`, `Finished(2088, 12)` or a plugin breaker open (Task 5.1b), *Then* the row offers `Start again`, `Retry failed` + `Dismiss`, and `Resume` + `View log` respectively with the P4 texts.
  - *Given* `PipelineProgress.Running(340, 2100, 12)`, *Then* the row reads "Image processing: 340 of 2,100 done · 12 failed - view log" with Pause and Cancel; a determinate bar carries `ProgressBarRangeInfo`; announcements are throttled to every 10%.
- Waiting for charger.
  - *Given* Android, battery not charging and backfill requested, *Then* the row reads "Waiting for charger" until charging or the user taps "Process now".
- First render and failure announcement (loading and a11y states).
  - *Given* the plugin settings have not been read yet, *When* Settings > Plugins first renders, *Then* the rows show "Loading..." (switches disabled, no stale `off`) until the read completes or fails; a read failure shows the rows as off with "Couldn't load plugin settings" and a retry. *Given* a plugin fails to initialize, *Then* the inline "Couldn't start text recognition - see log" is a polite live region while focus stays on the switch.
  - *Given* the indeterminate "Indexing files..." state (Assets browser) or a determinate bar, *When* the system animation scale is 0 (reduced motion), *Then* the indicator does not animate and the text states progress.
**Files**: `commonMain/.../asset/pipeline/PluginRegistry.kt`, `commonMain/.../platform/PluginSystem.kt` (`PluginHost` and `Plugin` removed in Task 5.5d; the file and `JsPluginBridge` removed in Task 10.4a), `commonMain/.../ui/StelekitAppDependencies.kt`, `commonMain/.../ui/components/settings/PluginsSettings.kt`, `commonMain/.../ui/GraphContentStatusAndBottomBar.kt`, `commonMain/.../ui/{AppState,ScreenRouter}.kt` (Task 5.5e only: `Screen.Logs` initial filter), `androidUnitTest/.../settings/PluginsSettingsTest.kt`, `androidUnitTest/.../logs/LogsScreenFilterTest.kt`.
##### Task 5.5a: `PluginRegistry` exposes `enabledCount: StateFlow<Int>` and per-plugin enabled state as the single source (S)
##### Task 5.5b: Settings > Plugins UI with progress, pause, cancel, charging gate; the Task 4.1d row becomes the master switch; `View log` opens the screen of Task 5.5e (M)
- Files (4-5 non-mechanical): `PluginsSettings.kt`, the progress-row composable, the pause/cancel/charger-gate wiring, `PluginRegistry` UI state, `PluginsSettingsTest`. **Split on first-run overrun**: progress row and its states (S) from the plugin list and switches (S).
##### Task 5.5c: Per-plugin switches bound to the `asset_plugin_<id>_enabled` keys that Task 5.2a already reads (the key names, default false and the inert-when-unset tests live in U8, not here); turning a switch on persists the key and starts the drain without a restart; the master `asset_pipeline_enabled` key and its minimal switch already exist from Tasks 4.1a/4.1d (S)
##### Task 5.5e: Logs initial-filter argument: `Screen.Logs` (a `data object` at `AppState.kt:54`) gains an optional initial filter with a default value so existing call sites compile unchanged; `ScreenRouter` passes it; `View log` on the progress row or a plugin failure opens Logs pre-filtered to pipeline lines without developer mode (S; split from Task 5.5b; validated by UW-95)
- Files (3-4): `AppState.kt` (highest-churn UI file: one default-valued parameter), `ScreenRouter.kt` (one lookup), the Logs screen filter state, `LogsScreenFilterTest`.
##### Task 5.5d: Remove `PluginHost` and `Plugin` only (not `JsPluginBridge`, not the file) and rewire the status bar and `StelekitAppDependencies` to read `PluginRegistry` (S) — OwnerGate OD-5

#### Story 5.6: Surface results
**Acceptance Criteria**:
- Assets search finds extracted text.
  - *Given* an asset with `ocr_text="Invoice 4821"`, *When* the Assets search bar queries `4821`, *Then* the asset is returned via `searchAssetsLight` (limit 50, projected rows without `ocr_text`/`auto_labels`) and the snippet comes from `selectAssetsSearchPreview(uuids)`, which returns for those at most 50 uuids a bounded excerpt (<= 240 characters around the match) of the matched column and the matched-column marker; these two queries are the one pair used by both the Assets bar and the global section (the global section passes limit 5).
- Global search section is bounded per keystroke.
  - *Given* the same asset, *When* `SearchDialog` queries `4821`, *Then* an "Assets" section shows it using the projected bounded query (limit 5, no `ocr_text` blob in the row), without scanning `blocks`; the query is debounced (>= 250 ms after the last keystroke, cancelled on the next), and `QueryPlanAuditTest` records its plan (`LIKE '%q%'` over `ocr_text` is a full scan of `asset_index`, so it is only run when the section is visible and the query has >= 3 characters).
- First-open smoke test on the shipped root.
  - *Given* `FirstOpenSmokeTest` (Task 1.3c) with an indexed image in the graph, *When* the Assets browser opens with the new search bar and empty state, *Then* it renders without a crash (pitfall A6 precedent).
- Empty-state prompt (a dismissible card above a non-empty grid with persistent dismissal, `design/ux.md` A1).
  - *Given* no plugin enabled and an image in the graph, *Then* the Assets browser shows a card that reads "Make images searchable - turn on text recognition" with a link to Settings > Plugins.
  - *Given* the owner tapped "Not now", *Then* the card stays hidden across launches, and Settings > Plugins offers a row "Show the searchable-images suggestion again" that clears the dismissal (UX GAP-12); the row is hidden while the card is not dismissed.
- Match emphasis does not collide with link styling (UX GAP-11).
  - *Given* a search snippet, *Then* the matched term is bold on a tinted background and is never underlined (underline is reserved for tappable links); the emphasis survives high-contrast and is not color-only.
- Loading states for search.
  - *Given* a query slower than 200 ms, *Then* the Assets bar shows "Searching..." and the global-search Assets section shows a "Searching files..." row (other sections stay interactive); a result replaces it, and a failure shows the retry row of UW-57.
- Inline pipeline progress in the Assets browser.
  - *Given* the pipeline is `Running` or waiting for the charger, *Then* the Assets browser shows a one-line status above the grid ("Processing 340 of 2,100 - details") linking to the Plugins progress row, so the owner need not leave the browser to see why results are incomplete.
**Files**: `commonMain/.../ui/components/SearchDialog.kt`, `commonMain/.../ui/assets/{AssetBrowserEmptyState,AssetBrowserSearchBar}.kt`, `commonMain/sqldelight/.../SteleDatabase.sq` (SELECT only, if needed; regenerate and commit generated sources), `androidUnitTest/.../assets/AssetResultsSurfaceTest.kt`.
##### Task 5.6a: Global search "Assets" section as its own composable + `SearchSource` with a bounded, debounced, projected query (M)
##### Task 5.6b: Prompt card with persisted dismissal, "Show all in Assets", "Indexing files..." and index-failed states (S)

### Epic 6: Cloud Claude plugin (opt-in, owner decision 2026-10-27)
**Goal**: Register row 11. Egress controls from `research/architecture.md` §4 and ADR-005; confidential data; default off. Reuses the existing Anthropic transport and `LlmCredentialStore("anthropic")` (evidence: `ClaudeLlmFormatterProvider` already posts to `https://api.anthropic.com/v1/messages` with `x-api-key`, circuit breaker and error mapping; its request type is text-only and its model is a hard-coded constant, so it is extended, not duplicated).

#### Story 6.1: Real `CloudClaudePlugin`
**Acceptance Criteria**:
- One Anthropic transport, shared.
  - *Given* `ClaudeLlmFormatterProvider` (voice formatting) and `ClaudeTopicEnricher`, *When* the shared `AnthropicMessagesClient` is extracted (URL, `anthropic-version`, `x-api-key` header, circuit breaker, `LlmProviderSupport.mapHttpError`) and the content type grows from `String` to a list of text/image blocks, *Then* characterization tests prove the existing text requests are byte-identical (including `max_tokens: 256` for the enricher), and `CloudClaudePlugin` calls the same client; a source audit finds exactly one file containing the string `api.anthropic.com`.
- Sends a downscaled, EXIF-stripped image and parses a description.
  - *Given* a 12 MP JPEG with GPS EXIF, *When* the plugin builds the request, *Then* the bytes are downscaled to the configured max edge and contain no EXIF/GPS (test decodes the payload and asserts no `Exif` APP1 segment), and a mock HTTP engine receives exactly one request carrying the key (read from `LlmCredentialStore.getApiKey("anthropic")` at call time, never stored on `CloudEnrichmentConfig`) in a header, never in a URL or log.
  - *Given* a mock response with description "A receipt from Acme", *Then* `AssetPipelineResult` carries it as `cloudDescription`; Story 6.2 persists it to `asset_index.cloud_description` (never `ocr_text`).
- No retry storm.
  - *Given* a 429 or 5xx, *Then* the asset is marked failed with a typed error and no automatic retry loop runs.
- Model id comes from config, looked up at implementation time with the `claude-api` skill, not hard-coded from memory.
**Files**: `commonMain/.../llm/AnthropicMessagesClient.kt` (extracted), `commonMain/.../voice/ClaudeLlmFormatterProvider.kt` (delegates), `commonMain/.../asset/pipeline/CloudClaudePlugin.kt`, `commonMain/.../asset/pipeline/CloudEnrichmentConfig.kt` (`apiKey` removed), `commonMain/.../asset/pipeline/ExifStripper.kt` (+ `expect/actual` only if no common option), `businessTest/.../llm/AnthropicMessagesClientCharacterizationTest.kt`, `businessTest/.../asset/pipeline/CloudClaudePluginTest.kt` (Ktor `MockEngine`).
##### Task 6.1a: Extract `AnthropicMessagesClient` from `ClaudeLlmFormatterProvider` with characterization tests of the existing wire format (M)
- **Manual real-call check before merge (pre-mortem #4(c), engineering G8)**: the characterization tests prove the request bytes, not that Anthropic still accepts them. Before the extraction PR merges, the implementing agent (or the owner) makes one real voice-format call through the extracted client with the owner's key (the existing voice formatting path in a debug build, a short fixed transcript) and one `ClaudeTopicEnricher` call, and pastes both response statuses and the formatted text into the PR description (never the key). If no key or network is available the PR does not merge; unavailable is a block, as for emulator gates. Validated by IT-99.
##### Task 6.1b: Downscale + EXIF/GPS strip with payload assertions (M)
- **Open-bug scope note (BUG-003)**: `docs/bugs/open/BUG-003-wasm-image-encoder-empty-bytes.md` records `ImageEncoder.encodeToJpeg` returning empty bytes on wasm. This task specifies the `ImageDownscalerPort` and `ExifStripper` path (Story 5.4) and does not call `ImageEncoder.encodeToJpeg`; if the implementer finds it must reuse `ImageEncoder`, that reuse stays Android/JVM-only, because wasm hosts name `PlatformCaps.NONE` (Story 5.2) and so never construct the cloud plugin. The plan therefore does not depend on the wasm encoder path. (Also noted: `wasmJsMain/.../ImageEncoder.wasmJs.kt` now contains a Canvas-2D implementation from commit `cb7edec2e6` (#350, 2026-09-23), so the bug file appears stale; the owner decides whether to close it. This plan makes no claim either way.)
##### Task 6.1c: `CloudClaudePlugin` image request on the shared client with mock-engine tests (S)

#### Story 6.2: Consent gate, key, revocation
**Acceptance Criteria**:
- Consent dialog gates the switch.
  - *Given* Settings > Plugins > "Cloud description (Anthropic)", *When* the owner flips the switch, *Then* a dialog states what is sent (image bytes), to whom ("Anthropic"), the **name of the graph** the consent applies to, **exactly which images** upload and how many, the per-session and per-day caps, with buttons "Enable uploads" / "Cancel" and default focus Cancel; Escape or Cancel leaves the switch off and `CloudConsent.NotAsked`.
  - *Given* a graph with 1,760 images that have no description yet, *When* the dialog opens, *Then* it offers `CloudBackfillScope.NewOnly` (default: "Only images you attach from now on") or `IncludeExisting` ("Also the 1,760 images already in this graph, at most 100 a day, about 18 days"), the count and the day estimate are computed from `countUnprocessedAssets` and the live daily cap at open, and the chosen scope is stored in `CloudConsent.Granted.scope`; with `NewOnly` the pipeline makes zero requests for assets created before the consent time.
  - *Given* the live count of existing undescribed images is 0, *Then* the second option is not shown (the scope is `NewOnly`, stated as plain text rather than a one-item radio group), and in both cases the dialog says "Descriptions are saved in this graph and can be searched." (UW-102).
  - *Given* Task 6.1b (EXIF/GPS strip) is **not** merged, *Then* the dialog says "Location and camera details stored in the image are sent too." in place of the EXIF-removed line, and it never claims removal; *Given* Task 6.1b is merged, *Then* it says "Location data (EXIF) is removed first." (UW-34 and UW-83).
  - *Given* the caps line, *Then* it reads "Up to 20 images per app launch, 100 per day" (the session counter resets when the process starts and not on a graph switch; the daily counter resets at local midnight, per Story 6.3), with the numbers read from the live config (UW-85).
  - *Given* the dialog at 360 dp and 2.0x font scale, *Then* the title and the action row (Cancel, Enable uploads) stay pinned while the body scrolls, and the "Don't enable this if your images contain anything you wouldn't send to a third party." warning sits above the bullet list (UW-89).
  - *Given* the network line, *Then* it is truthful per platform: where `NetworkMonitor.isMetered` is available (Android) it reads "Only on an unmetered network"; where it is not (desktop, which has no metered signal) it reads "Only while online" and never claims unmetered; the dialog also links to Anthropic's data-handling policy.
- Granted is recorded per graph and provider.
  - *Given* the owner taps "Enable uploads", *Then* `CloudConsent.Granted(ProviderId("anthropic"), atMs, scope)` is stored in `Settings` for this graph only; another graph stays `NotAsked`.
- Enforcement point: no consent token, no HTTP call.
  - *Given* `CloudClaudePlugin.processAsset(asset, consent: GrantedConsent)` and `GrantedConsent`'s constructor `private` with `CloudConsentGate` as the only construction scope (the only mint site, ADR-005; not `internal`, which is module-wide), *Then* code that does not hold a token cannot compile a call to the upload path. A source audit, `GrantedConsentMintSiteAuditTest` (`businessTest/.../asset/pipeline/`, Task 6.2c, sources bundled per Task 1.1c), asserts the text `GrantedConsent(` appears in no production file other than `CloudConsentGate.kt` (and `GrantedConsent.kt`'s own declaration); tests obtain tokens by running the real `CloudConsentGate` over a fake `Settings` and fake `LlmCredentialStore` (fake the gate's inputs, not the gate), so no test-only mint path exists and the audit has no test exemption.
  - *Given* the plugin is present in the registry and consent is `NotAsked`, or `Revoked`, or the `anthropic` key is missing from `LlmCredentialStore`, *When* the pipeline drains, *Then* the mock HTTP engine records **zero** requests and the asset is skipped (stays unprocessed for the cloud plugin, not marked failed).
  - *Given* a drain of 10 cloud-eligible assets with consent `Granted`, *When* consent is revoked after asset 3 completes, *Then* assets 4-10 make zero HTTP calls (consent is re-read per asset by `AssetPipelineService` next to `EgressLedger`, not captured at enqueue or at start), and an in-flight request for asset 4, if any, is cancelled.
  - *Given* graph A `Granted` and graph B `NotAsked` sharing the one `anthropic` key, *When* graph B drains, *Then* zero requests are made for B (the gate takes the graph id; consent is per graph, the key is global).
  - *Given* the stack-level test `CloudStackEntryPointTest` through `WiredAppFixture` with consent granted, a key present and a Ktor `MockEngine`, *Then* exactly one request is made through the real `defaultAssetPlugins` path and its result lands in `asset_index`.
- Key is the existing Anthropic key (one key store).
  - *Given* the owner already entered an Anthropic key under Settings > LLM (`LlmProviderSettings`), *Then* Settings > Plugins shows "Using your Anthropic key from LLM settings" and a link; if absent it deep-links to that screen instead of a second masked field; "Test key" uploads nothing; `Settings`, `Logger` output and diagnostics contain no key (extend `PatLeakageAuditTest`-style audit).
  - *Given* "Forget API key" is tapped, *Then* an inline confirm (Cancel is the default) says it also stops Claude voice formatting; on confirm `LlmCredentialStore.deleteApiKey("anthropic")` runs, the UI states this also disables Claude voice formatting, and one deletion removes the key everywhere.
- Cloud text has its own storage, source marker and label (decided: existing columns, no new column).
  - *Given* the mock engine returns "A receipt from Acme" for asset X, *When* the pipeline persists it, *Then* `AssetRepository.updateCloudDescription(X, text, mlTagsSource="CLOUD_CLAUDE")` runs in the one actor transaction with the processed flag, `ocr_text` and `auto_labels` are untouched, and no cloud text is ever written to `ocr_text` (ADR-004). `asset_index` already has `cloud_description` and `ml_tags_source` (`SteleDatabase.sq:1152,1161`), so no `ALTER TABLE`, `MigrationRunner.all` entry or `ai_description` column is added; Task 6.2d verifies the existing `ml_tags_source` value set can carry `CLOUD_CLAUDE`.
  - *Given* an asset whose `cloud_description` contains "Acme", *When* the Assets search bar or global search queries "Acme", *Then* the asset is returned (the projected search queries now match `cloud_description`) and the row exposes which column matched, so the UI labels the snippet "AI description" (UW-56) and an `ocr_text` match is labelled as text found in the image; after revoke, already stored descriptions remain but the UI offers "Delete AI descriptions" for this graph.
  - *Given* the revoke control, *Then* its copy states "Descriptions already received are kept. Images already sent can't be recalled from Anthropic." (UW-84); an asset whose description came from the cloud shows a persistent "Described by Anthropic" marker (text plus icon shape) in the Assets detail screen and as a badge on its grid cell, driven by `ml_tags_source = CLOUD_CLAUDE` (Task 6.2f), and the marker stays after revoke. An image that was uploaded but whose description failed is not marked; the per-upload INFO log line (Story 6.3) is the complete record of sends.
- Revocation is immediate.
  - *Given* 5 queued cloud items and consent revoked, *Then* all 5 are dropped without upload, the UI says "5 queued items dropped", `CloudConsent.Revoked` is stored, and any in-flight upload is cancelled.
  - *Given* the switch is turned off or `Revoke consent` is tapped, *Then* revocation takes effect immediately with **no confirm dialog** (revoking is the safe direction) and the line under the switch reads "Cloud descriptions are off. Descriptions already received are kept. Images already sent can't be recalled from Anthropic."; no "revocation confirmation" exists anywhere, and turning the switch on again re-asks the consent dialog (UW-99). `Delete AI descriptions`, beside it, shows a standard confirm "Delete AI descriptions for <graph>? N descriptions will be removed from this graph's search. Images already sent can't be recalled." with `Delete` and `Cancel` (Cancel default) (UW-100).
**Files**: `commonMain/.../asset/pipeline/{CloudConsent,CloudConsentGate,GrantedConsent}.kt`, `commonMain/.../asset/pipeline/AssetPipelineService.kt` (per-asset gate check), `commonMain/.../ui/components/settings/{PluginsSettings,CloudConsentDialog}.kt`, `androidUnitTest/.../settings/CloudConsentDialogTest.kt`, `androidUnitTest/.../wiring/CloudStackEntryPointTest.kt`, `businessTest/.../asset/pipeline/CloudConsentEnforcementTest.kt`, `businessTest/.../asset/pipeline/GrantedConsentMintSiteAuditTest.kt`, `businessTest/.../asset/CloudKeyLeakageAuditTest.kt`, `commonMain/sqldelight/.../SteleDatabase.sq` (SELECT only; regenerate and commit generated sources), `businessTest/.../asset/CloudDescriptionStorageTest.kt`.
##### Task 6.2a: `CloudConsent` sealed type with `CloudBackfillScope`, dialog (graph name, new-vs-existing choice with live count, platform-true network line, policy link), per-graph storage, revoke (M)
##### Task 6.2b: Key from `LlmCredentialStore("anthropic")`, settings link, "Test key", "Forget API key", leak audit (S)
##### Task 6.2c: `CloudConsentGate` + `GrantedConsent` token, per-asset check in `AssetPipelineService` (including the `NewOnly` filter), enforcement tests (zero HTTP when NotAsked/Revoked/no key, mid-drain revoke, per-graph isolation) (M)
- **Split on first-run overrun**: `CloudConsentGate` and `GrantedConsent` (S) / the per-asset check and `NewOnly` filter including the `importedAtMs` verification (S) / the enforcement tests (S).
##### Task 6.2e: `PlatformCaps.cloud` factory wiring and the stack-level mock-engine `CloudStackEntryPointTest` (S; split from Task 6.2c)
- **Files**: `commonMain/.../asset/pipeline/{CloudConsentGate,GrantedConsent}.kt`, `commonMain/.../asset/pipeline/AssetPipelineService.kt` (per-asset gate check), `businessTest/.../asset/pipeline/CloudConsentEnforcementTest.kt`, `businessTest/.../asset/pipeline/GrantedConsentMintSiteAuditTest.kt` (source audit, sources bundled per Task 1.1c), `androidUnitTest/.../wiring/CloudStackEntryPointTest.kt`.
##### Task 6.2f: Persistent "Described by Anthropic" marker in the Assets grid and detail screen, driven by `ml_tags_source = CLOUD_CLAUDE` (S)
##### Task 6.2d: Persist cloud text through `updateCloudDescription` with `ml_tags_source=CLOUD_CLAUDE`; add `cloud_description` to `searchAssetsLight` and `selectAssetsSearchPreview` with a matched-column marker; regenerate and commit SQLDelight sources (`rsync` step per repo `CLAUDE.md`); `QueryPlanAuditTest` entries; storage test proving `ocr_text` is untouched (S)

#### Story 6.3: Caps, network gate, ledger, egress-surface audit
**Acceptance Criteria**:
- Caps counted in the pipeline and durable.
  - *Given* `sessionCap=20`, a per-day cap of 100 and a per-asset size cap of 8 MB, *When* the 21st asset of the session arrives, *Then* the pipeline stops with `Paused(PauseReason.SessionCap)` and says so; a 9 MB asset is skipped with a reason; the per-day counter is persisted in `Settings` (key + local-date) so a process restart does not reset it, while the session counter resets per process; neither lives in the plugin.
- Session limit is a picker (owner-facing label "Limit per launch"; `sessionCap` is the internal name, UW-85).
  - *Given* the cloud page, *Then* the session limit is chosen from 10, 20 or 50 (default 20), never above the per-day cap, and the paused text names the live value.
  - *Given* the cloud page and the consent dialog, *Then* the launch and day counters are labelled "all graphs" ("This launch: 3 of 20, all graphs"; "Today: 12 of 100, all graphs") because the counters are app-wide while consent and the page are per graph, so a second graph never appears to have its own budget (UW-101).
- Network gate (needs a metered signal that does not exist today).
  - *Given* `NetworkMonitor` (`platform/NetworkMonitor.kt`) exposes only `isOnline` and `observeConnectivity()` at HEAD (VERIFIED by grep: the only `metered` hit in `kmp/src` is the unrelated `setAllowedOverMetered(true)` in `DepthModelDownloader.kt`), *Then* Task 6.3c adds `isMetered: Boolean?` (`null` = platform cannot tell; Android maps `ConnectivityManager.isActiveNetworkMetered`; JVM, iOS and wasm return `null`).
  - *Given* offline, or `isMetered == true`, *Then* no upload is attempted and the row says "Waiting for unmetered network" (Android) or "Waiting for network" (offline anywhere); *Given* `isMetered == null` (desktop), *Then* uploads proceed when online and the consent dialog and status copy never say "unmetered". There is no "Upload now anyway" action (consistent with ADR-005).
- Egress log.
  - *Given* an upload, *Then* one INFO line has asset uuid, bytes, provider and no content.
- Every production HTTP client is accounted for (replaces the false `AirGapGuardTest` allowlist claim).
  - *Given* `AirGapGuardTest` scans only `transfer/` for forbidden imports and has no allowlist (`AirGapGuardTest.kt:23-44`), *Then* it is left as is, and a new `EgressSurfaceAuditTest` (`businessTest`, source files bundled as resources) lists every production file under `commonMain`, `jvmMain`, `androidMain`, `wasmJsMain`, `iosMain`, `jvmCommonMain` and `androidApp/src/main` that constructs an `HttpClient` or imports `io.ktor.client`, and compares the set to the committed `egress-allowlist.txt` (one line per file: path, destination class `first-party | user-configured | third-party-llm | third-party-google | git-remote | url-fetch`, reason).
  - *Given* a new file that imports `io.ktor.client` and is not in the allowlist, *Then* the test fails naming it; *Given* an allowlist entry whose file no longer exists, *Then* it fails (stale entry).
  - *Given* the initial allowlist, *Then* it is **generated from the test run** (the test prints the discovered set in allowlist format and a one-off run seeds the file; destination class and reason are then filled by hand) rather than typed from this plan. The rule is exact: a production file is in scope iff it contains the line prefix `import io.ktor.client`; constructing an `HttpClient` without that import is caught by a second check on the token `HttpClient(`. The 2026-10-27 run yields **20** files (the existing LLM providers `ClaudeLlmFormatterProvider`, `OpenAiLlmFormatterProvider`, `GeminiLlmFormatterProvider`, `CustomOpenAiCompatibleLlmProvider`, `LlmProviderRegistryFactory`, `LlmProviderSettings`, `WhisperSpeechToTextProvider`; `UrlFetcher{Jvm,Android,Ios}`; `WaybackMachineService`; `platform/google/*`; `GitHubDeviceFlowClient`; the three wasm git files `WasmGitRepository`, `WasmLfsRulesSource`, `WasmGitWriteService`) and the asset pipeline adds **no new entry**, because `CloudClaudePlugin` uses `AnthropicMessagesClient`.
- Scope of the privacy claim.
  - *Given* the pipeline, *Then* the claim is "with `cloud=null`/no consent the **asset pipeline** has no path to a third party", asserted by `CloudStructuralAuditTest` (no `AnthropicMessagesClient` reference in `asset/` outside `CloudClaudePlugin`, which `defaultAssetPlugins` constructs only through the factory). The app as a whole is **not** claimed air-gapped: voice/LLM providers, URL fetch and Google already send data to third parties by user opt-in (listed in `egress-allowlist.txt`).
**Files**: `commonMain/.../asset/pipeline/EgressLedger.kt`, `AssetPipelineService.kt`, `businessTest/.../audit/EgressSurfaceAuditTest.kt`, `businessTest/resources/egress-allowlist.txt`, `businessTest/.../asset/pipeline/{EgressLedgerTest,CloudStructuralAuditTest}.kt`.
##### Task 6.3a: `EgressLedger` caps (durable per-day counter) + network gate in the pipeline (M)
##### Task 6.3b: `EgressSurfaceAuditTest` + committed allowlist + `CloudStructuralAuditTest` (S)
##### Task 6.3c: `NetworkMonitor.isMetered` expect/actual on Android, JVM, iOS and wasm, with an Android mapping test (S)

#### Story 6.4: Remove `CloudVisionPlugin` — OwnerGate OD-5
**Acceptance Criteria**:
- Removal is clean.
  - *Given* confirmation in the PR description, *When* `CloudVisionPlugin.kt` and its references are removed, *Then* compile (Android, JVM, wasm) and `detekt` pass and `ReachabilityBaseline` loses the entry.
##### Task 6.4a: Delete `CloudVisionPlugin` (superseded by `CloudClaudePlugin`) (XS)

---
## Phase 4: Small wirings, Google integration, deletions

### Epic 7: Small wirings (each independently shippable)
**Goal**: Register rows 24, 25, 29, 30, 31 (Stories 7.1-7.4) and, through Task 7.5a, rows 33-36 (row 14, the iOS pair, is allowlisted earlier by Task 1.1b at U1; row 37 is wired by Story 2.2). **User pain**: none stated by the owner; these are hygiene wirings of already-written code, approved 2026-10-27 under "wire if it adds functionality". No outcome observation (O1-O3) is attached to them, and Task 7.3b (USB serial) is built and verified with a fake port only when OD-4 is answered "no device".

#### Story 7.1: Wayback Machine archive action
**Acceptance Criteria**:
- Right-click archives.
  - *Given* a block containing `https://example.com/a`, *When* the owner right-clicks the URL (`BlockItem.onUrlRightClick`, `BlockItem.kt:550`) on desktop, *Then* a snackbar shows "Archiving https://example.com/a…" and, on a mock `KtorWaybackMachineService` success, the block gains the archive link via `onBlockContentUpdate`.
- Constructed at a root.
  - *Given* `WaybackMachineService` is constructed only in a `CompositionRoot` file and passed to `rememberArchiveUrlAction`, *Then* the `WiringCompletenessTest` seam `WaybackMachineService` passes without allowlist.
- States (`design/ux.md` 10a, UW-82).
  - *Given* the archive call fails, *Then* the snackbar shows "Couldn't archive - Retry" (no silent failure); in flight it reads "Archiving https://example.com/a…".
**Files**: `commonMain/.../ui/GraphContentViewModelSetup.kt` or the file that builds `BlockItem` callbacks (find with `sg`), `commonMain/.../ui/components/ArchiveUrlAction.kt`, `jvmTest/.../domain/WaybackMachineServiceTest.kt` (existing), `androidUnitTest/.../wiring/ArchiveUrlEntryPointTest.kt`.
##### Task 7.1a: Construct service in the root, pass the lambda to `BlockItem`, entry-point test (S)

#### Story 7.2: Desktop rich-text paste
**Acceptance Criteria**:
- HTML on the clipboard becomes blocks.
  - *Given* the AWT clipboard holding `text/html` `<ul><li>one</li><li>two</li></ul>`, *When* the owner pastes into an empty block on desktop, *Then* two sibling blocks "one" and "two" are created through the existing `HtmlBlockConverter` path used by `ImportViewModel`; plain-text clipboard still pastes as before.
- Reader is bounded.
  - *Given* a 5 MB HTML clipboard, *Then* the reader truncates at the converter's documented cap and shows a notice, never loading unbounded.
- Over-cap notice (UW-82).
  - *Given* the clipboard HTML exceeds the converter cap, *Then* the first part pastes and a one-time notice says the rest was too large.
**Files**: `jvmMain/.../domain/HtmlClipboardReader.kt`, paste handler located via the `block-reorder-paste` plan's entry (`sg` for the desktop paste callback), `jvmTest/.../domain/HtmlPasteEntryPointTest.kt` (no pixels; `jvmTest-reason: desktop-only AWT clipboard`).
##### Task 7.2a: Wire the reader into the desktop paste handler with a bounded read (M)

#### Story 7.3: Measurement device factories
**Acceptance Criteria**:
- Keyboard fallback always available.
  - *Given* app startup on Android and desktop, *When* `MeasurementDeviceRegistry` scans, *Then* the `KeyboardEmulationDeviceFactory` device is emitted (registered beside `KableBleScanner` at `SteleKitApplication.kt:199`, and in `desktop/Main.kt`).
- USB serial (build out).
  - *Given* `usb-serial-for-android` added to `androidMain` (`kmp/build.gradle.kts` and `MODULE.bazel`, LGPL via dynamic aar per the image-meter plan) and a fake `UsbSerialPort` emitting the line "12.34 mm\r\n", *When* `AndroidUsbSerialFactory.scan` runs, *Then* a device is emitted and the reading parses to 12.34 mm; without a device the flow completes empty (no crash).
**Files**: `androidApp/.../SteleKitApplication.kt`, `jvmMain/.../desktop/Main.kt`, `androidMain/.../platform/measurement/usb/AndroidUsbSerialFactory.kt`, `commonTest/.../platform/measurement/MeasurementDeviceRegistryTest.kt` (existing), `androidUnitTest/.../AndroidUsbSerialFactoryTest.kt`.
##### Task 7.3a: Register `KeyboardEmulationDeviceFactory` in both roots through one extracted function per root (`defaultMeasurementDeviceFactories()` in `SteleKitApplication` and in `desktop/Main.kt`) so a root-level test can execute the real registration list and assert the keyboard factory (and, on Android, `AndroidUsbSerialFactory`) are in it (S)
##### Task 7.3b: Add dependency (Gradle + Bazel lock), implement USB scan with an injectable port, fake-port tests (M)
- Optional hardware pass per OD-4.
- **Licence and distribution note**: the image-meter plan records `usb-serial-for-android` (kai-morich fork) as LGPL 2.1, accepted with dynamic linking via the `.aar` (`project_plans/image-meter/implementation/plan.md:17`); upstream licence text must be re-read at the pinned version when the dependency is added (UNVERIFIED here). Obligations to meet in this task: keep it a dynamically linked `.aar` (no shaded or R8-inlined copy), ship the licence text in the app's third-party notices, and keep the release APK's dependency list auditable. Distribution is the self-hosted F-Droid repository (`fdroid/config.yml`, `release.yml`), not the official F-Droid index, so no inclusion-policy review is triggered, but the notices file is checked by a release-workflow assertion.

#### Story 7.4: Adopt `CommandManagerCoordinator`
**Acceptance Criteria**:
- Behavior equivalence.
  - *Given* the command id `block.toggle-todo` with no block being edited, *When* executed through the coordinator, *Then* it returns `CommandResult.Error("No block is currently being edited")`; with an active `BlockStateManager` it calls `requestTodoToggle()`; all other ids delegate to `CommandManager.executeCommand` — checked against `StelekitViewModel`'s current inline path before the swap.
- ViewModel shrinks.
  - *Given* the swap, *Then* `StelekitViewModel.kt` no longer calls `CommandManager.create` directly (`:346-348`) and `StelekitViewModelCrashReproductionTest` still passes. If the equivalence check finds a difference, this task deletes the coordinator instead and records why.
**Files**: `commonMain/.../ui/StelekitViewModel.kt`, `commonMain/.../ui/CommandManagerCoordinator.kt`, `businessTest/.../ui/CommandManagerCoordinatorEquivalenceTest.kt`.
##### Task 7.4a: Equivalence test, then adopt (or delete) (S)

#### Story 7.5: Record verified false positives
**Acceptance Criteria**:
- Allowlist is explicit.
  - *Given* `ICommandRegistry`, `InitializingDebugScreen`, `OtelSpanElement` and the three `jvmMain/.../benchmarks/*Benchmark` mains (the iOS pair is already present from Task 1.1b), *When* `wiring-allowlist.txt` is read, *Then* each has a reason and a date; the benchmark entries cite the Gradle/JMH launch that proves they are entry points (verified, not assumed; if they are not launched by any task, they move to the deletions epic as an OD-5 item).
##### Task 7.5a: Verify and record (XS)

### Epic 8: Google integration wiring
**Goal**: Register rows 27-28; Google sign-in already exists (`GoogleAccountSettings`). **User pain**: none stated by the owner; hygiene wiring of already-written code, approved 2026-10-27, with no outcome observation attached; it is verified by the OAuth device pass 12.5, which the owner may defer.

#### Story 8.1: Google Photos picker in the import menu (Android)
**Acceptance Criteria**:
- Entry exists and is gated.
  - *Given* a signed-in Google account on Android, *When* the owner opens the attach/import menu, *Then* "From Google Photos" is shown and launches `GooglePhotosPickerLauncher`; signed out, it is disabled with "Sign in to Google in Settings".
- Selected items import.
  - *Given* a fake `PhotosPickerApiClient` returning 2 items, *When* the owner selects both, *Then* two files are saved through `MediaAttachmentService` and registered by `AssetIndexService`.
- States (UW-82).
  - *Given* the picker is opening or an import of 2 photos fails, *Then* the menu shows "Opening Google Photos…" and then "Couldn't import 2 photos - Retry" respectively.
**Files**: `androidMain/.../platform/google/GooglePhotosPickerLauncher.kt`, `androidApp/.../MainActivity.kt` (activity-result registration), `commonMain/.../ui/...` import menu, `androidUnitTest/.../google/PhotosPickerEntryPointTest.kt`.
##### Task 8.1a: Menu entry, launcher construction in the Android root, fake-client E2E (M)

#### Story 8.2: Drive export and browser
**Acceptance Criteria**:
- Export from annotation.
  - *Given* an annotated image with a measurement and a fake `GoogleApiClient`, *When* the owner taps "Export to Drive", *Then* `DriveExportService` uploads the JPEG and the `.measure.json` sidecar and shows the Drive link.
- Browser is reachable.
  - *Given* Settings > Google account signed in, *Then* "Browse Drive" opens `DriveFileBrowserScreen` through a `Screen` route.
- First-open smoke test on the shipped root.
  - *Given* `FirstOpenSmokeTest` (Task 1.3c), *When* "Browse Drive" opens `DriveFileBrowserScreen` over a fake client, *Then* it renders without a crash.
- States (UW-82).
  - *Given* an export in flight, *Then* "Uploading…" with Cancel shows; *Given* the Drive browser first renders, is empty, or fails, *Then* it shows "Loading…", "No files" or "Couldn't load Drive - Retry". *Given* the encoded JPEG is zero bytes, *Then* nothing is uploaded and the owner sees "Couldn't encode the image - nothing was uploaded".
**Files**: `commonMain/.../platform/google/DriveExportService.kt`, `commonMain/.../ui/drive/DriveFileBrowserScreen.kt`, `commonMain/.../ui/{AppState,ScreenRouter}.kt`, `commonMain/.../ui/components/settings/GoogleAccountSettings.kt`, `androidUnitTest/.../google/DriveEntryPointTest.kt`.
##### Task 8.2a: Annotate export action, Drive route, settings entry, fake-client E2E (M)
- **Open-bug scope note (BUG-003)**: the Drive export consumes JPEG bytes from `AnnotationExporter.bakeAndEncode` (`ImageEncoder.encodeToJpeg`). BUG-003 (`docs/bugs/open/BUG-003-wasm-image-encoder-empty-bytes.md`) reports empty bytes on wasm only. "Export to Drive" is wired on Android (Story 8.1/8.2 are Android-first) and tested with a fake client and fake JPEG bytes; wasm Drive export is **out of scope** for this plan, so nothing here depends on the wasm encoder, and Task 8.2a adds a guard that refuses to upload a zero-byte image ("Couldn't encode the image - nothing was uploaded").

#### Story 8.3: Fake-backed end-to-end suite for Google flows
**Acceptance Criteria**:
- No network in tests.
  - *Given* `GoogleApiClient` replaced by a fake via the `open class` seam (`GoogleApiClient.kt:48`), *When* the suite runs, *Then* it makes zero network calls (AirGap-style assertion) and covers token-expired → re-auth prompt.
##### Task 8.3a: Suite and catalog entries (S); the real OAuth pass is item 12.5

### Epic 9: Semantic search (conditional on OD-1 = build)
**Goal**: Register row 17 — turn the dormant `VectorSearch` into a shipped, bounded feature. Not started until the owner confirms; costs in ADR-008.

#### Story 9.1: Embedding source spike
**Acceptance Criteria**:
- A model is chosen by measurement.
  - *Given* ONNX Runtime (JVM 1.26.0, Android 1.20.0 declared) and a candidate small sentence-embedding model, *When* the spike embeds 1,000 blocks, *Then* it records dimension, model size, per-block latency and peak heap on the Pixel_Fold emulator and JVM, and a model is chosen or semantic search is declared infeasible on the 512 MB heap (ending the epic). The spike also records the **full-scan kNN latency** over stored vectors at graph scale (9.5k pages, the real block count, 512 MB heap) because chunked scanning is O(all blocks) per interactive query; if p95 exceeds the budget set in the spike (proposed 300 ms to first results, INFERRED) the epic requires an ANN index or a page-level pre-filter before Story 9.3, or is declared infeasible.
##### Task 9.1a: Spike and model decision recorded in ADR-008 (M)

#### Story 9.2: Persisted embeddings
**Acceptance Criteria**:
- Table exists everywhere.
  - *Given* `block_embeddings(block_uuid, model_id, vector BLOB)` added to `SteleDatabase.sq` and `MigrationRunner.all`, *When* `MigrationRunnerSchemaSyncTest` runs, *Then* it passes; an existing user DB gains the table via `MigrationRunner.applyAll()`. Generated sources regenerated and committed.
##### Task 9.2a: Schema + migration + restricted write stubs (M)

#### Story 9.3: Bounded indexer and kNN
**Acceptance Criteria**:
- Indexing is a bounded drain.
  - *Given* 9,500 pages, *When* the indexer runs, *Then* it drains `getUnembeddedBlocks(limit=100, offset)` with an attempted set (terminates with failing rows), runs only when idle/charging on Android, and `QueryPlanAuditTest` covers its queries.
- Query never reads the graph and stays within the Spike 9.1 latency budget.
  - *Given* a query "budget planning", *Then* similarity is computed in chunks of at most 500 stored vectors with a running top-k heap; no `List<Block>` of the graph is materialized (heap test); *And* the query's p95 latency at the 9.5k-page fixture is within the Spike 9.1 budget, or the pre-filter/ANN fallback chosen by the spike is in place.
##### Task 9.3a: Embedding indexer service in a `GraphContentSemanticSetup.kt` stack (M)
##### Task 9.3b: Chunked cosine top-k reusing `VectorSearch.cosineSimilarity`, replacing the per-query embed-everything path (M)

#### Story 9.4: Search UI
**Acceptance Criteria**:
- Distinct and honest.
  - *Given* embeddings exist, *When* the owner toggles "Semantic" in `SearchDialog`, *Then* results come from kNN with a "semantic" label; with no index yet, the toggle is disabled with "Indexing N of M".
##### Task 9.4a: SearchDialog mode, progress, entry-point E2E (M)

### Epic 10: Superseded deletions (every task is an OwnerGate, OD-5)
**Goal**: Remove only what live code replaces; each PR shrinks `ReachabilityBaseline`.

#### Story 10.1: Dead duplicates with no consumer
**Acceptance Criteria**:
- Clean removal.
  - *Given* confirmation per file, *When* `kmp/src/commonMain/.../command/` (3 files), `jvmMain/.../desktop/MigrationCli.kt`, `jvmMain/.../repository/Migration.kt` (`PluginMigration`, `EavTuple`) and `NoOpErrorTracker` are deleted, *Then* Android, JVM and wasm compile, `./gradlew detekt` passes, `AllBusinessTestsCompletenessTest` passes, and the Bazel `business_tests` class count is unchanged (none of these had tests; recount from the real run).
##### Task 10.1a: Delete the files and update the baseline (S)

#### Story 10.2: `RTCManager`
**Acceptance Criteria**:
- Removal.
  - *Given* confirmation, *When* `rtc/RTCManager.kt` is deleted, *Then* the build is green and jacoco's zero-coverage `rtc` package (0/26) disappears from the map.
##### Task 10.2a: Delete `RTCManager`, `RTCOp` (XS)

#### Story 10.3: Datalog family (folded into `{{query}}`)
**Acceptance Criteria**:
- Advanced queries fail loudly, not silently empty.
  - *Given* a block `{{query [:find ?b :where [?b :block/content "x"]]}}`, *When* it renders, *Then* `QueryParser` returns `ParseError.UnsupportedForm` and `QueryBlock` shows the existing unsupported-form message (test added before deletion); *And* *Given* `search/DatalogQuery.kt` removed, *Then* `DatalogBlockRepository` (the live in-memory repo) is untouched.
##### Task 10.3a: Add the unsupported-form test, delete the family, update `live-query-blocks` note (S)

#### Story 10.4: `JsPluginBridge` (conditional on OD-2 = delete)
**Acceptance Criteria**:
- Dead label gone.
  - *Given* OD-2 = delete and OD-5 confirmation, and `PluginHost`/`Plugin` already removed by Task 5.5d, *When* `JsPluginBridge` is removed, *Then* `PluginSystem.kt` is empty and deleted, the status bar and `StelekitAppDependencies` already read `PluginRegistry` (Task 5.5d), and the build is green on Android, JVM and wasm. *Given* OD-2 = build, *Then* `JsPluginBridge` and `PluginSystem.kt` stay and Task 5.5d is unaffected.
##### Task 10.4a: Delete the bridge (XS); hard dependency on Task 5.5d (U10) having merged, so `PluginHost`/`Plugin` are already gone and the status bar reads `PluginRegistry`

#### Story 10.5: Camera stubs
**Acceptance Criteria**:
- Read-before-delete.
  - *Given* `WebcamCameraProvider` (jvm) and `WebCameraProvider` (wasm), *When* both are re-read at implementation start, *Then* if either contains real capture code it is wired instead; otherwise both are deleted and `SensorModule.cameraProvider` defaults to `NoOpCameraProvider` as today.
##### Task 10.5a: Verify, then delete (XS)

---

## Phase 5: Repo-wide coverage program

### Epic 11: Comprehensive coverage strategy
**Goal**: Success metric 2. "Comprehensive" means: every package has a measured number, a tier, a target and a rank; every wired feature has an entry-point test; gaps close in ranked waves; nothing regresses.

**Tiers (ADR-003; targets INFERRED until the baseline replaces them)**:

| Tier | Contents (risk) | Line / branch target | Mutation | Churn weight |
|------|-----------------|----------------------|----------|--------------|
| T1 | `db/`, `repository/`, `parser/`, `outliner/`, `merge/`, `git/`, `vault/`, `security`, `console/`, asset write paths, `query/` (data integrity, security, sync) | 85 / 70 | PIT nightly over listed packages; report-only first, gating after 3 clean runs | x4 |
| T2 | Domain services and ViewModels, `editor/`, `export/`, `capture/`, `asset/pipeline/`, `platform/google/` | 70 / 55 | PIT per-PR on changed classes (existing) | x2 |
| T3 | Compose UI and platform glue | 40 / n.a., plus a first-open smoke test per screen | none (PIT is JVM bytecode only; `androidMain` is never mutated) | x1 |
| T4 | Generated, DTO, serialization models, benchmarks, demo graph | excluded | none | 0 |

**Where each test belongs (CLAUDE.md)**:

| Test kind | Source set | Runner |
|-----------|-----------|--------|
| Pure function, parser, policy, redactor | `commonTest` (kotest property where the input space is structured) | all targets |
| Business logic with repositories/actor/fakes | `businessTest` | JVM; counted in `bazel test //kmp:business_tests` |
| Compose behavior, dialogs, settings, a11y, composition-root entry points | `androidUnitTest` (Robolectric) | `./gradlew testDebugUnitTest`; headless |
| Pixels, Roborazzi, AWT clipboard, JGit against a temp repo | `jvmTest` with `// jvmTest-reason:` | `scripts/jvm-display-check.sh -- ...` |
| Android driver, ML Kit, OAuth-free device behavior | `androidInstrumentedTest` | Pixel_Fold emulator, one class at a time |

**Open-bug scope note (BUG-002)**: `docs/bugs/open/BUG-002-benchmark-jobs-skip-on-push-to-main.md` records that the `benchmark` and `android-benchmark` workflow jobs are skipped on every push to `main` (their `if: github.event.pull_request.draft == false` is false for push events). This program does **not** rely on those workflows or on `benchmarks/history/`: its numbers come from the new `coverage-baseline.yml` workflow (Story 0.1), whose triggers are `workflow_dispatch` and `schedule` and contain no draft-PR condition (checked by a CI assertion in Task 0.1a). Fixing BUG-002 is not funded here.

**Flake-prevention rules for every new test** (pitfalls E4): owned scope cancelled in teardown; virtual time via `runTest`; no real clocks, no `Thread.sleep`, no shared singleton state; no `@Ignore` or retry to turn red green without a stated root cause; new classes confirmed present in the `business_tests` count.

#### Story 11.1: Coverage map generator and artifact format
**Acceptance Criteria**:
- Artifact shape is fixed.
  - *Given* the baseline XMLs, *When* `scripts/ci/coverage-map.py` runs, *Then* it writes `docs/coverage/coverage-map.json` where each package entry has `pkg`, `sourceSet`, `tier`, `lines{covered,total}`, `branches{covered,total}`, `mutation{killed,total,measured}`, `commits90d`, `reachablePct`, `score`, `rank`, `ownerPlan` and `target{line,branch}`, plus a top-level `generatedAt`, `commit`, `runner` (the exact command) and `tiersFile` hash, and renders `coverage-map.md` sorted by rank.
- Score definition.
  - *Given* a package with 1,647 uncovered lines, tier T1 (weight 4) and 12 commits in 90 days, *Then* `score = uncoveredLines × tierWeight × log2(2 + commits90d)` = 1,647 × 4 × log2(14) ≈ 25,082, and a T3 package with the same uncovered lines scores lower.
- Reachable and unreachable coverage are separated (pitfalls E1).
  - *Given* a package whose classes are all in `ReachabilityBaseline`, *Then* `reachablePct=0` and deleting them removes them from the denominator instead of scoring as a regression.
- One canonical runner.
  - *Given* Gradle `jvmTest`/`testDebugUnitTest` XMLs, *Then* `runner` records Gradle and the map refuses to merge an XML from Bazel (numbers are not comparable, pitfalls E6).
**Files**: `scripts/ci/coverage-map.py`, `docs/coverage/{coverage-map.json,coverage-map.md,SCHEMA.md}`, `scripts/ci/coverage-map_test.py`.
##### Task 11.1a: Generator merging JVM + Android XML by package, churn from `git log` (M)
##### Task 11.1b: Schema doc, golden test on a fixture XML (S)

#### Story 11.2: Tiers file and baseline population
**Acceptance Criteria**:
- Every package is classified.
  - *Given* `coverage-tiers.yml` mapping package globs to tiers, *When* the generator runs, *Then* it fails listing any package without a tier (so new packages must be classified).
- Targets come from the run.
  - *Given* the Story 0.1 baseline, *Then* each package's initial `CoverageRatchet` floor equals its measured value and the INFERRED targets in this plan are replaced by measured-informed targets in ADR-003's addendum.
##### Task 11.2a: `coverage-tiers.yml` covering every package (S)
##### Task 11.2b: Populate the first map, set ratchet floors, record in ADR-003 addendum (S)

#### Story 11.3: Gating policy
**Superseded in part (ADR-003 addendum 2026-10-29)**: coverage and mutation are per PR, not nightly; ignore the nightly `CoverageRatchet`, scheduled PIT and nightly issue filing below. The existing `diffCoverage` 80% PR gate is the coverage gate.
**Acceptance Criteria**:
- PR path unchanged and cheap.
  - *Given* a PR, *Then* the existing `diffCoverage` threshold 80 (`kmp/build.gradle.kts:1546`) and report-only PIT remain the only coverage gates; no new job is added to the PR path that raises CI memory (pitfalls E5).
- Nightly ratchet.
  - *Given* the nightly `coverage-baseline` run, *When* a T1 or T2 package's line coverage drops below its floor, *Then* the job fails and files an issue naming the package and the commit range; T3 drops warn only.
- Mutation tiering.
  - *Given* the nightly Tier-1 PIT run, *Then* survivors per package are written to the map; after 3 consecutive runs with a stable result the T1 floor becomes gating (decision recorded in ADR-003).
- Wiring gate.
  - *Given* success metric 1, *Then* `WiringCompletenessTest`, `reachability` and the `WiringCatalog` check run on every PR (cheap, no Gradle test run for the scan).
##### Task 11.3a: Nightly ratchet job and issue filing, PR-path audit (M)

#### Story 11.4: Ranked gap-closing waves
**As the** owner, **I want** the biggest risk x churn gaps closed first, **so that** coverage rises where bugs would hurt.
**Acceptance Criteria**:
- Each wave is generated from the map, not from this plan.
  - *Given* the committed `coverage-map.json`, *When* a wave starts, *Then* its task list is the top 5 ranked packages not owned by an existing per-area plan (`annotate-ui-test-coverage`, `asset-service-test-coverage`, `db-repository-test-coverage`, `editor-subsystem-coverage`, `doc-coverage`, `platform-perf-google-coverage`) and not owned by Epics 3-8 (which carry their own entry-point tests); the list is written to `docs/coverage/wave-<n>.md`.
- Wave exit.
  - *Given* wave A targets `git/` (5% at the stale 2026-10-16 partial run: 84 of 1,731 lines), *When* its tests land, *Then* the package's line coverage is at least its tier target or the shortfall is explained in `wave-<n>.md`, and the ratchet floor is raised to the new value.
- Every test follows the placement table and flake rules; a changed test class is run 10 times in a loop once before merge to catch order dependence.
**Wave candidates from the stale partial data (confirm against the baseline, which supersedes this list)**: A: `git/` and `vault/` (T1); B: `merge` UI and `screens/git` (T3, with a first-open smoke test each); C and D: next ranked.
##### Task 11.4a: Wave A (top-5 T1 gaps) (L)
##### Task 11.4b: Wave B (L)
##### Task 11.4c: Wave C (L)
##### Task 11.4d: Wave D (L)
- Priced L (240k each) because a T1 package can need more than one M of test writing (`git/` is 84 of 1,731 lines at the stale 2026-10-16 run; closing it to 85/70 alone is well over 130k tokens). They are generated-list tasks whose package list comes from the map, so they cannot be split ahead of the baseline; the split rule is applied at wave start: one worker run covers up to 5 packages and a package the map says needs more becomes its own task instead of growing the wave. Expect this line to overrun even at L; the +25% checkpoint then fires and reports the delta (informational).

#### Story 11.5: Reachable-versus-unreachable reporting and catalog completeness
**Acceptance Criteria**:
- Metric 1 is a number.
  - *Given* `ReachabilityBaseline` and `WiringCatalog`, *When* the nightly job runs, *Then* it prints "unreferenced types: N (baseline M); wired features with entry-point tests: K of J" and the final PR of this project shows N=0 apart from the explicitly allowlisted set (the iOS pair under `out-of-scope-ios`, the verified false positives of Task 7.5a, and items awaiting an open OD); a feature counts toward metric 1 only when its Epic 12 owner pass is recorded and its flag-default decision is written down (on, or off with a dated reason), tracked in the Disposition Register's last column.
##### Task 11.5a: Nightly summary line and the final zero-check (S)

---

## Phase 6: Owner device verification (wall-clock blockers; not priced as agent work)

### Epic 12
- **12.1 Console on the device (after U6)**: TalkBack pass on the block list, soft-keyboard ergonomics (extra keys, chips, history) at 360dp and font scale 1.5/2.0, including that the pinned action row of the typed-count and `RESTORE` dialogs stays reachable with the IME open (UW-89, UW-91); read-only query on the real DB, then a guarded write (`sql!`) and a restore on a COPY of the real 451 MB DB (never the live DB first), using a debug build with `ConsoleBuildFlags.WRITES_ENABLED` true. Passing and recording it is the precondition for the one-line PR that turns the flag on in release builds. Also record outcome observation O1 (requirements.md): one real on-device issue diagnosed with the console. Owner.
- **12.2a Asset indexing on the device (after U8, before U9 ships)**: Task 0.4b emptiness check before; then enable indexing only on the 9.5k-page graph, using the Task 4.1d "Asset indexing" switch on a **debug/internal build** (the row is absent in release variants until Task 5.2e/U19; the implementing agent installs the build with `bazel mobile-install //kmp:android_app --config=android` or `./gradlew installAndroid` per repo `CLAUDE.md`) with every `asset_plugin_<id>_enabled` key left false, so only `AssetIndexService` runs, and confirm in the Logs screen that no plugin `processAsset` line appears; record the `perf export` heap trace using the PR #501 method (that PR states cure is unproven, "no heap dump"); confirm no ANR and that the crash-loop breaker is not tripped. Owner.
- **12.2b On-device plugins on the device (after U10, released separately from 12.2a's release)**: turn on the per-plugin switches (Task 5.5c) one at a time on the same graph; confirm charging gate, pause/cancel, no ANR, heap trace as above; record O2 (find a real asset by extracted text) and O3 (Assets screen non-empty). Owner.
- **12.3 Desktop sync manual run (after U2, again after U16)**: interval on, remote gets a commit, the app shows updated state without pressing Sync; later three consecutive full syncs leave `behind == 0`. Owner.
- **12.4 Cloud consent with a real key (after U11)**: consent dialog, one real upload, revoke and forget key; confirm no GPS EXIF reaches the provider (owner supplies a geotagged test image); also look at whether the "Described by Anthropic" badge on grid cells adds visual noise at 360 dp (a look check, not a design change). Owner.
- **12.5 Google OAuth (after U13)**: Photos picker and Drive export with the real account. Owner.
- **12.6 Closeout**: update the requirements doc Baseline, record final metric values, delete resolved rows from `ReachabilityBaseline`.

---

## Effort estimate

Basis: bands and weights INFERRED from public price ratios and the class bands in `ESTIMATION.md`; recalibrate after the first implementer run. Raw tokens = sum of task cost classes (XS 20k, S 55k, M 130k, L 240k; no L tasks remain after splitting). Assumed output share 12% for implementation, so CU per raw token = 1 + 4 × 0.12 = 1.48 (all input counted as fresh, so this is an upper bound; cached re-reads of the plan cost less). "Est. CU incl. verification" applies a x1.75 verification multiplier (story-level review, `6-verify`, CI reruns).

| Story | Name | Tasks | Cost classes | Est. raw tokens | Output share | Est. CU (x1.48) | Est. CU incl. x1.75 verification | Wall-clock blocker |
|---|---|---|---|---|---|---|---|---|
| 0.1 | Coverage baseline | 3 | S+S+S | 165k | 12% | 244k | 427k | CI run |
| 0.2 | Reachability scan | 3 | M+S+XS | 205k | 12% | 303k | 531k | release build |
| 0.3 | Console capability spikes (incl. authorizer 0.3e) | 5 | M+M+S+M+S | 500k | 12% | 740k | 1295k | emulator |
| 0.4 | Drain bug test | 1 | S | 55k | 12% | 81k | 142k | device check 12.2 |
| 1.1 | WiringCompletenessTest (businessTest, Bazel bundling) | 6 | M+M+S+S+S+S | 480k | 12% | 710k | 1243k | - |
| 1.2 | Reachability CI ratchet | 1 | S | 55k | 12% | 81k | 142k | - |
| 1.3 | Composition-root fixtures | 3 | L+S+M | 425k | 12% | 629k | 1101k | - |
| 1.4 | Placement/flake audits | 2 | S+M | 185k | 12% | 274k | 479k | - |
| 2.0 | Extract PeriodicSyncDriver (refactor-first) | 2 | M+M | 260k | 12% | 385k | 673k | - |
| 2.1 | Desktop loop wiring | 3 | M+S+XS | 205k | 12% | 303k | 531k | OD-5 (2.1c) |
| 2.2 | Android enqueue + registry | 3 | M+S+S | 240k | 12% | 355k | 622k | - |
| 2.3 | Scheduled sync settings | 2 | M+S | 185k | 12% | 274k | 479k | - |
| 2.4 | Full-sync cutover | 2 | S+M | 185k | 12% | 274k | 479k | dev plan PR-A1/A2 released |
| 3.1 | Console core | 9 | M+M+S+S+S+M+S+M+M | 870k | 12% | 1288k | 2253k | #397 rebase check |
| 3.2 | SQL read | 6 | M+M+M+M+S+M | 705k | 12% | 1043k | 1826k | spikes 0.3 |
| 3.3 | SQL guarded writes | 7 | M+M+M+M+S+M+S | 760k | 12% | 1125k | 1968k | - |
| 3.4 | Restore (incl. lock-order stress, progress stall states, recovery banner) | 8 | M+S+M+M+S+M+S+S | 740k | 12% | 1095k | 1917k | - |
| 3.5 | Console a11y + E2E (+ post-pass flip, U18) | 3 | M+M+XS | 280k | 12% | 414k | 725k | device 12.1 (3.5c) |
| 4.1 | Asset index wiring | 4 | M+M+M+S | 445k | 12% | 659k | 1153k | - |
| 4.2 | Asset move wiring | 2 | M+S | 185k | 12% | 274k | 479k | - |
| 5.1 | Pipeline correctness (non-null actor, projected queries) | 7 | S+M+S+S+S+S+M | 535k | 12% | 792k | 1386k | - |
| 5.2 | defaultAssetPlugins (plugin keys, real-caps + Android heap gate, post-gate flip U19) | 5 | M+S+S+M+XS | 390k | 12% | 577k | 1010k | emulator (blocking gate 5.2c) |
| 5.3 | Real OCR port | 3 | S+M+S | 240k | 12% | 355k | 622k | emulator |
| 5.4 | On-device plugins E2E | 1 | M | 130k | 12% | 192k | 337k | - |
| 5.5 | Plugin registry + Plugins UI (+ Logs filter argument) | 5 | S+M+S+S+S | 350k | 12% | 518k | 907k | - |
| 5.6 | Surface results | 2 | M+S | 185k | 12% | 274k | 479k | - |
| 6.1 | Cloud Claude plugin (shared Anthropic client) | 3 | M+M+S | 315k | 12% | 466k | 816k | - |
| 6.2 | Consent gate + key + revoke | 6 | M+S+M+S+S+S | 480k | 12% | 710k | 1243k | - |
| 6.3 | Caps + ledger | 3 | M+S+S | 240k | 12% | 355k | 622k | - |
| 6.4 | Delete CloudVision | 1 | XS | 20k | 12% | 30k | 52k | OD-5 |
| 7.1 | Wayback | 1 | S | 55k | 12% | 81k | 142k | - |
| 7.2 | Desktop rich paste | 1 | M | 130k | 12% | 192k | 337k | - |
| 7.3 | Measurement factories | 2 | S+M | 185k | 12% | 274k | 479k | OD-4 (optional) |
| 7.4 | CommandManagerCoordinator | 1 | S | 55k | 12% | 81k | 142k | - |
| 7.5 | False-positive allowlist | 1 | XS | 20k | 12% | 30k | 52k | - |
| 8.1 | Photos picker | 1 | M | 130k | 12% | 192k | 337k | OAuth 12.5 |
| 8.2 | Drive export/browser | 1 | M | 130k | 12% | 192k | 337k | OAuth 12.5 |
| 8.3 | Google fake E2E | 1 | S | 55k | 12% | 81k | 142k | - |
| 9.1 | Embedding spike | 1 | M | 130k | 12% | 192k | 337k | OD-1 |
| 9.2 | Embeddings schema | 1 | M | 130k | 12% | 192k | 337k | OD-1 |
| 9.3 | Indexer + kNN | 2 | M+M | 260k | 12% | 385k | 673k | OD-1 |
| 9.4 | Semantic UI | 1 | M | 130k | 12% | 192k | 337k | OD-1 |
| 10.1 | Delete dead duplicates | 1 | S | 55k | 12% | 81k | 142k | OD-5 |
| 10.2 | Delete RTC | 1 | XS | 20k | 12% | 30k | 52k | OD-5 |
| 10.3 | Delete Datalog family | 1 | S | 55k | 12% | 81k | 142k | OD-5 |
| 10.4 | Delete JsPluginBridge | 1 | XS | 20k | 12% | 30k | 52k | OD-2/OD-5 |
| 10.5 | Camera stubs | 1 | XS | 20k | 12% | 30k | 52k | OD-5 |
| 11.1 | Coverage map generator | 2 | M+S | 185k | 12% | 274k | 479k | - |
| 11.2 | Tiers + ratchet floors | 2 | S+S | 110k | 12% | 163k | 285k | baseline run |
| 11.3 | Gating policy | 1 | M | 130k | 12% | 192k | 337k | 3 nightly soak |
| 11.4 | Gap waves A-D | 4 | L+L+L+L | 960k | 12% | 1421k | 2486k | - |
| 11.5 | Metric-1 summary | 1 | S | 55k | 12% | 81k | 142k | - |

**Totals** (recounted by script from the table and by `grep -c` of the `#### Story` / `##### Task` headings after repair iteration 5, the triad round-3 repair): 52 stories, 140 tasks, 13035k raw tokens (about 13.0M), 19.3M CU before verification, 33.8M CU with the x1.75 verification multiplier. Iteration 5 added 5 tasks (3.4g, 3.4h, 3.5c, 5.2e, 5.5e; 205k raw) over iteration 4's 135 tasks / 12830k raw / 19.0M / 33.2M. Iteration 4 added 8 tasks (1.1f, 2.2e, 3.4e, 3.4f, 4.1d, 5.2d, 6.2e, 6.2f; 515k raw) and reclassified Task 1.1c S to M (+75k), Task 1.3a M to L (+110k) and Tasks 11.4a-d M to L (+440k), for +1140k raw (about 3.0M CU with verification) over iteration 3's 127 tasks / 11690k raw / 17.3M / 30.3M, which had added 405k raw over iteration 2's 121 tasks / 11285k raw / 16.7M / 29.2M, which had itself added 665k raw over iteration 1's 113 tasks / 10620k raw / 15.7M / 27.5M. Of this, the conditional Epic 9 is 650k raw (1.7M CU with verification). Task 0.4a and Task 5.1e/5.1f are counted in their stories; Task 0.4a is delivered in unit U8's gate PR.

**Task sizing rule (repair iteration 4)**: a task fits one worker run when it touches 3-5 non-mechanical files. Edits that are mechanical (one-line host constructions, `BUILD.bazel` filegroup additions, regenerated `kmp/src/generated/sqldelight/` output, golden/fixture data) do not count toward that limit but must be named as mechanical in the task, and where the mechanical edits are numerous they are split into their own S task (Tasks 1.1f, 2.2e, 3.4e, 5.2d, 5.5e, 6.2e). Tasks that cannot be split ahead of data (Task 1.3a, one composed fixture; Tasks 11.4a-d, lists generated from the coverage map) are priced L (240k) with the reason stated in the task; ESTIMATION.md's "L: split it, or say why it can't be split" is satisfied by that stated reason.

**Size band**: XL (> 15M CU), informational; conditional Epic 9 and the four coverage waves are included because the owner pursues full scope. Planning overhead (this document plus 8 ADRs) is already spent and not in the table.

**Critical path in agent waves** (stories in a wave fan out in parallel; a story with an arrow serializes; reconciled with the Delivery-units table so no wave starts work whose unit dependency lands later):
- Wave 1 (U0): 0.1, 0.2, 0.3 (all independent; 0.3 is in U0 and is the merge gate for U4/U5/U6).
- Wave 2 (U1): 1.1-1.4 (1.1c Bazel bundling is part of 1.1); 11.1.
- Wave 3 (after U1): 2.0 (U2 step 1; it waits for U1, it does not "need no guardrail"), 3.1, 4.1 (incl. Task 4.1d, the interim switch), 7.1-7.5, 10.1-10.3, 10.5, 11.2.
- Wave 4: 2.1, 2.2, 2.3 (after 2.0), 3.2 (after 3.1 and the Wave 1 spikes), 4.2, 11.3; Task 6.1a (shared-client extraction) may start here because it touches no pipeline code.
- Wave 5: 3.3 (after 3.2), 5.1 (U8 needs U7 complete), 8.1 (U13 needs U7), 8.2, 11.4a.
- Wave 6: 3.4, 5.2 (after 5.1, same PR as 5.1: U8), 8.3, 11.4b.
- Wave 7: 3.5, 5.3, 5.4 (U9, after U8 and the Task 5.2c gate), 11.4c.
- Wave 8: 5.5, 5.6 (U10 needs U8 and U9), 11.4d. Story 10.4 (Task 10.4a) waits for Task 5.5d, so it runs no earlier than wave 9 even though the rest of Epic 10 runs in wave 3.
- Wave 9: 6.1 (rest), 6.2, 6.3, 6.4 (U11 needs U10; Story 6.2's UI lives in the Plugins settings built by 5.5), 11.5; 9.x (if OD-1) runs as a parallel chain of 4 waves from wave 3.
- Serial depth: console 0.3 (wave 1) -> 3.1 -> 3.2 -> 3.3 -> 3.4 -> 3.5 (7 waves including U1); assets 4.1 -> 4.2 -> 5.1 -> 5.2 -> 5.3/5.4 -> 5.5/5.6 -> 6.2 -> 6.3 (9 waves). Story 2.4 sits outside the chain: it waits on dev plan units (see blocker 3), so the plan's critical path ends before metric 4 closes.

**Overrun checkpoint**: if cumulative spend on completed stories exceeds the table value by more than 25% (CU), pause and report the delta and its cause (wrong estimate or real surprise); informational, not an invitation to cut.

**Wall-clock blockers** (the only calendar-time items):
1. Owner device passes 12.1, 12.2a, 12.2b, 12.3-12.5 (owner), and the outcome observations O1-O3 of requirements.md recorded at them; emulator `Pixel_Fold` runs for Tasks 0.3d/0.3e, 3.3f, 5.2c and 5.3c are agent-executable but slow, and an unavailable emulator blocks the merge (Merge and ordering rules, item 2). U18 and U19 are one-line flip PRs that wait on pass 12.1 and the Task 5.2c runs respectively and sit outside the waves.
2. Story 0.1b full baseline run and nightly CI runs (CI queue); Story 0.2b release build (CI/local memory per PR #498).
3. Dev plan PR-A1 guards (Tasks 2.3e/2.3f), Task 2.2d0 and PR-A2 policy shipped and released (owner schedules; Story 2.4 / U16 waits). **Success metric 4 cannot close inside this plan** because of this blocker; it is a declared dependency, not funded work.
4. PR #501 merge (this branch is `fix/android-oom-reconcile-leaks`; U-units branch from `main` after it merges, or rebase) (owner review).
5. `RepositorySet` rebase check for `sqlConsole`: #397 is already merged, so this is a conflict check at Task 3.2a, not a wait.
6. OD-1, OD-2, OD-3, OD-4, OD-5, OD-6 owner confirmations at the named stories (OD-6 by the time U2 is released).
7. Coverage gating promotion needs 3 consecutive nightly PIT runs (soak), so mutation gating lands at least 3 nightly cycles after Story 11.3.

---

## Requirements traceability

| Requirement / metric | Where met |
|----------------------|-----------|
| M1: every in-scope feature reachable and tested through its real entry point; zero types in the unreferenced scan (allowlist renewal rule, 2-renewal cap and `blocked-on-owner` class: Glossary `WiringAllowlist`; U1 seeding: Task 1.1b; "wired and tested" versus "done" reported separately) | Epic 1 (guard + catalog), every feature story's entry-point test, Epic 10 deletions, Story 11.5 zero-check |
| M2: coverage map with run numbers, tier, target, ranked gaps | Stories 0.1, 11.1, 11.2, 11.4 |
| M3: read-only SQL + diagnostics on-device in any build; guarded writes (confirm + backup) and restore in dev/internal builds only until pass 12.1 | Epic 3 (Stories 3.1-3.3): plain `sql` is always read-only, `sql!` is the only write path and is compiled out of release builds until item 12.1 passes on a copy of the real DB; credential-exposure statement per Spike 0.3e (Story 3.2) |
| M4: desktop sync pulls remote while open, test + manual run | **Partially met at U2** (Stories 2.0-2.3: fetch-only ticks, staleness visible, driver, UI). **Closes only at U16 / Story 2.4**, which depends on dev-plan PR-A1/PR-A2 outside this plan; manual run 12.3 repeats after U16. The final N=0 check (Story 11.5) reports M4 as "visibility met, pull pending" (partially met) until then; scheduling of PR-A1/PR-A2 is owner decision OD-6; the staleness-visibility acceptance observation is in requirements.md metric 4 |
| M5: every dormant item has a recorded decision | Disposition Register (39 rows; rows 38-39 added after the U0a scan), ADR-001, ADR-008 |
| Owner decision: wire if adds functionality; delete only superseded | Register; every deletion an `OwnerGate` |
| Owner decision: console read-only default + guarded writes | ADR-006, Story 3.3 |
| Owner decision: CloudClaude with consent, cap, EXIF strip, revocation; drop Vision | Epic 6 (consent enforced by `GrantedConsent` token + per-asset gate, Story 6.2), Task 6.4a, ADR-005 |
| Scope additions beyond "wiring existing code" (owner-approved 2026-10-27, recorded in the requirements.md amendment) | Real OCR (5.3), cloud HTTP client (Epic 6), USB serial + dependency (7.3b), Google Photos/Drive surfaces (Epic 8), global-search Assets section (5.6a), conditional semantic search (Epic 9) |
| NFR: bounded, cancellable background work, heap | Stories 4.1, 5.1, 5.2, 6.3, 9.3 |
| NFR: Android uncaught Throwable; scopes with CEH | Hard rules in Risk Control; Stories 2.1, 3.1, 5.1 |
| Observability (Logger, spans, tick log, last-success) | Observability Plan; Stories 2.1, 3.1, 5.1 |
| Constraint: SQLDelight regen + `MigrationRunner` rule | Migration Plan; Stories 5.6, 9.2 |

## Self-review (adversarial pass)

- Cross-references: each task id used in the Disposition Register exists (2.1c, 6.4a, 7.5a, 10.1a, 10.2a, 10.3a, 10.4a, 10.5a, 7.3a/b, Stories 4.1, 4.2, 5.1-5.5, 7.1, 7.2, 7.4, 8.1, 8.2). Every dev plan task cited (4.1a-c, 4.2a, 4.4a-b, 4.5a-b, 4.6a, 2.3e, 2.3f, 2.2d0, 2.4a, 2.4c, 5.5e) was read in `dev-console-git-sync-fix/implementation/plan.md` on 2026-10-27.
- Risk of double implementation: Story 2.2b duplicates dev Task 2.4c and Story 2.4 depends on dev Story 2.4; both are marked "reduce to a test if the dev plan lands first".
- Load-bearing claims that are still INFERRED: the drain-skip defect (proven or refuted by Task 0.4a before any fix), `requery` cancellation support (Spike 0.3d), `OnnxImageLabeler` model availability (Task 5.2a), tier targets (replaced after Story 0.1), the benchmark entry points (Task 7.5a).
- No cut line, no human-time estimates, no downscaling question appears; the five open items are decisions that change what gets built, not scope reductions.
- Repair iteration 2 (2026-10-27): closed consistency B1 (`sql!` is the only write verb; one denial string `Denied: plain sql is read-only. Use sql! for writes.`), B2 (`ux.md` C3 now matches Story 3.2), B3 (see next line), and pre-mortem P1-1..P1-3 as explicit tasks and gates (3.3e-g, 5.1g, 5.2c blocking, 1.1d-e, 1.3c, Register last column, separate releases, 12.2a/12.2b). Verified by grep before editing: `asset_index` already has `cloud_description` and `ml_tags_source` (`SteleDatabase.sq:1152,1161`), `updateAssetCloudDescription` (`:1211`) and `AssetRepository.updateCloudDescription`, and the `asset_index_table` migration includes `cloud_description` (`MigrationRunner.kt:466`). `PluginSystem.kt` declares `Plugin`, `PluginHost`, `JsPluginBridge`, and the bridge references neither of the other two. The items left open then (C2, C8, C9, C11, C12, C13 and nitpicks N1-N6) were closed or dispositioned in repair iteration 3; see `implementation/consistency.md`.
- Cloud-description storage (B3) deviates from the repair request: the consistency finding said `asset_index` has only `ocr_text`, `auto_labels` and `tags`, which is false at HEAD, so adding `ai_description`/`ai_description_source` would duplicate existing columns; Task 6.2d writes `cloud_description`/`ml_tags_source=CLOUD_CLAUDE` and extends the search queries instead, with a stated fallback to a real migration if the `ml_tags_source` value set cannot carry the marker.
- Repair iteration 1 (2026-10-27): re-verified by grep before editing: `StelekitViewModel.kt` 2451 lines, `onMemoryPressure()` at `:1440`, `DirectSqlWriteOptInAllowlistTest` absent (40 `OptIn(DirectSqlWrite` lines), `AirGapGuardTest` has no allowlist, 20 production files importing `io.ktor.client` (23 for the loose word `HttpClient`), `ClaudeLlmFormatterProvider` posts text-only to `api.anthropic.com`, `LlmCredentialStore` key namespace `llm.anthropic.api_key`, `AssetPipelineService` nullable `writeActor` and `DirectRepositoryWrite` import, `selectUnprocessedAssets`/`searchAssets` are `SELECT *`, `createReadDriver` per platform, `androidApp/BUILD.bazel` exists, `git_config` holds aliases only. Still INFERRED: whether any driver exposes `sqlite3_set_authorizer` (Spike 0.3e), the Android heap budget number (first run), and the 300 ms semantic-search budget.
- Repair iteration 3 (2026-10-27, triad review): product (metric wording, renewal rule, Epics 7/8 in requirements In Scope, ordering rationale after the Delivery units table), UX (consent scope and graph name, platform-true network line, D1 label and Android clamp, Resume and badge pause, restore quantification, progress, undo and lifetime, large-write escalation, loading states, a11y, new UW-63..UW-81) and engineering (consistency.md re-run and rewritten; Task 3.1b split into 3.1b/3.1g/3.1h/3.1i after a recount showed only 2 of the 14 matching files are production; Task 5.2c reclassified to M; BUG-002/BUG-003 scope notes; licence and backup notes). Two verified facts changed the design: `NetworkMonitor` has no metered signal on any platform (grep), so Task 6.3c adds one and the plan no longer offers "Upload now anyway"; and `android:allowBackup="false"` already holds (`AndroidManifest.xml:34`), so Task 3.3c's rules are defense in depth. Still INFERRED: `importedAtMs` semantics for backfilled assets (Task 6.2c), the upstream `usb-serial-for-android` licence text (Task 7.3b). Counts above were produced by script, not typed.
- Repair iteration 4 (2026-10-27, triad round 2). **Engineering**: G1 (U1 merges green via seeded `awaiting-unit` allowlist entries with a 2-renewal cap; allowlist and `ReachabilityBaseline` defined as independent inputs with a twin-consistency check; Register row 14 and Task 7.5a aligned to "iOS pair at U1, Task 1.1b"), G2 (Task 4.1d interim "Asset indexing" switch in U7, hidden in release variants until the Task 5.2c pass; the "model not installed" and "not available on desktop" row texts moved from Stories 5.2/5.3 to Story 5.5), G3 (Tasks 1.1f, 2.2e, 3.4e, 3.4f, 5.2d, 6.2e split out; 1.1c to M; 1.3a and 11.4a-d priced L with stated reasons; sizing rule and table updated), G4 (U8 is a PR stack; only Tasks 0.4a and 5.1a must share), G5 (U14 row: 10.4 needs U10), G6 (U7 release rule), G7 (test-registration DoD, covered by `AllBusinessTestsCompletenessTest` for `*Test` classes in `businessTest`, KDoc verified), G8 (agent runs emulator gates; unavailable blocks merge), G9 (ordering rule vs dev PR-A1 / Task 2.4c), G10 (BUG-001/BUG-004 line; `docs/bugs/open/` listing verified), G11 (Status line). **UX**: GAP-1..GAP-14 are carried into Stories 3.3, 3.4, 5.5, 5.6 and 6.2 and into `design/ux.md` (UW-83..UW-97). **Product**: riskiest assumption, outcome observations O1-O3, renewal cap, delivered-half M4 observation, OD-6, and the explicit not-doing list are in `requirements.md`. Not addressed: the Android process-death behavior of a backgrounded restore and the OS killing a charging backfill are design decisions verified only at passes 12.1/12.2a, and the Logs initial-filter argument is new code not yet written (`Screen.Logs` is a `data object` today, `AppState.kt:54`). Counts above were produced by `grep -c` and a script over the table, not typed.
- Repair iteration 5 (2026-10-27, triad round 3). **Engineering B1**: the per-plugin enable keys and their inert-when-unset tests moved from Task 5.5c (U10) into Task 5.2a (U8) with Glossary `PluginEnableKey`; U8, U9, U10, Risk Control and passes 12.2a/12.2b restated so the master flag alone runs only indexing; the interim row's "N of M indexed" now reads the index walk, not `countUnprocessedAssets` (which only the drain decrements). **G1** minimal `ConsoleEntryPointTest` in Task 3.1e; **G2** dry-run, escalate-or-refuse and in-transaction re-check specified in Task 3.3b and ADR-006 addendum 4; **G3** Tasks 3.5c and 5.2e plus units U18/U19, and pass 12.2a runs on a debug/internal build before the flip; **G4** Logs filter split out as Task 5.5e and split-on-overrun notes on 3.3d, 3.4f, 5.2a, 5.5b, 6.2c; **G5** OD-5 on the U10 and U11 rows; **G6** `blocked-on-owner` allowlist class; **G7** Story 0.3 may ship as U0b; **G8** manual real-call check in Task 6.1a; **G10** stale counts in `consistency.md` and `pre-mortem.md`. **UX** items 1-12 are in Stories 2.3, 3.3, 3.4 and 6.2/6.3 and `design/ux.md` (UW-86, UW-89, UW-91 extended; UW-98..UW-106 appended). **Product** items 1-3 and 5: owner-pain statements for Epics 2, 7 and 8, AVD seeding in Task 5.2c, Metric 1 wording `OD-1..OD-6`. Not addressed: product item 4 (O2 may never be recorded; no deadline invented) and item 6 (baseline numbers stay OPEN until Story 0.1). Counts above were produced by script and `grep -c`, not typed.
