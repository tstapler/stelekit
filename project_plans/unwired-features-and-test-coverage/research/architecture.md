# Architecture Research: unwired-features-and-test-coverage

Builds on, and does not re-derive:
- `project_plans/dev-console-git-sync-fix/research/architecture.md` (console structure §1.2 line 35, raw SQL §1.3 line 66, hotspot dispositions §4 line 165, sync Event-Command-Policy §5 line 186) and its ADRs `decisions/ADR-001..006` (ADR-002 SQL guard, ADR-004 scheduled sync).
- `project_plans/asset-management/research/architecture.md` (plugin DI §C line 288, backfill §E line 510, move saga §D line 433).
- `project_plans/image-pipeline/implementation/plan.md` (typed image path, `DecodeBudget`).
- `project_plans/editor-subsystem-dead-code/research/architecture.md` (precedent for delete-with-tests).

Paths are under `kmp/src/` unless noted. VERIFIED = I opened the code or ran grep (command named); INFERRED = reasoning.

---

## 0. Headlines (read first)

1. **The scheduler gap is wider than the requirements say (VERIFIED).** `grep -rn "WorkManagerSyncScheduler\|DesktopSyncScheduler" --include='*.kt' androidApp kmp/src/{commonMain,jvmMain}` finds no constructor call outside `WorkManagerSyncScheduler.resumeFor` (`androidMain/.../git/WorkManagerSyncScheduler.kt:105`). `BackgroundSyncScheduler.schedule()` has **no production caller on any platform**, and `GitSyncService.startPeriodicSync` (`commonMain/.../git/GitSyncService.kt:751`) also has none. `GitConfig.pollIntervalMinutes` (`git/model/GitConfig.kt:22`, set in `ui/screens/git/GitSetupScreen.kt:186`) is stored and never consumed. Only the Android worker's *registry* half is wired (`androidApp/.../MainActivity.kt:413` registers the service in `GitSyncServiceRegistry`). So three timer mechanisms exist (`DesktopSyncScheduler`, `startPeriodicSync`, WorkManager) and none is started. ADR-004 (line 12) already records "`register` has no production caller"; `MainActivity.kt:413` appears to have closed that, but the enqueue side did not.
2. **Assets table is never populated in production (VERIFIED by grep, device unconfirmed).** `saveAsset(` has no caller outside `asset/AssetIndexService.kt` (lines 44, 46, 167, 173), and `AssetIndexService` has no non-test reference. `ui/ScreenRouter.kt:348,360` builds `AssetBrowserViewModel`/`AssetDetailViewModel` over `repos.assetRepository`, so the shipped Assets screen can only show an empty index. Wiring the pipeline starts with *indexing*, not ML.
3. **Several "dormant" classes are stubs, not features (VERIFIED).** `RTCManager` (`rtc/RTCManager.kt`, 92 lines) is placeholders returning `emptyList()`; `DatalogEngine.execute` returns `emptyList()` (`search/DatalogQuery.kt:~64`); `VisualQueryBuilder.buildFromSchema` is a TODO; `JsPluginBridge` methods log and return `null` (`platform/PluginSystem.kt:72+`); `CloudClaudePlugin.processAsset` returns `Labels(emptyList())` after bumping its session counter (`asset/pipeline/CloudClaudePlugin.kt:23-27`); `OcrPlugin` takes an `ImageLabeler`, not an OCR engine (`asset/pipeline/OcrPlugin.kt:11-14`). Wiring a stub makes tests pass and the feature absent. Recommend **delete** for the placeholders (section 6).

---

## 1. Composition roots (VERIFIED map)

| Root | Where | Owns |
|---|---|---|
| Desktop entry | `jvmMain/.../desktop/Main.kt` | JVM platform integrations, `GraphManager` |
| Android entry | `androidApp/.../MainActivity.kt`; `SteleKitApplication` (`kmp/src/androidMain` or `androidApp`, `Application.onCreate` must `catch Throwable` per CLAUDE.md) | WorkManager, `GitSyncServiceRegistry`, `graphManager` |
| Web entry | `wasmJsMain/.../browser/Main.kt` | wasm integrations |
| App-wide deps | `ui/StelekitAppDependencies.kt:41` (`pluginHost: PluginHost = PluginHost()` default) | cross-graph singletons |
| Per-graph UI stacks | `ui/GraphContent*Setup.kt` (`GitSyncSetup`, `GraphIoSetup`, `VaultSetup`, `TagVoiceSetup`, `ViewModelSetup`, `CameraCapture`), each a `remember{}`-built "stack" | graph-scoped services, torn down by `DisposableEffect` (e.g. `GraphContentGitSyncSetup.kt:42-46` shuts down `GitSyncService`) |
| Per-graph data | `repository/RepositoryFactory.kt` -> `RepositorySet` (class params `RepositoryFactory.kt:48-74`, nullable optional capabilities such as `perfExporter`; `assetRepository` at `:74`, built at `:434`) | repositories, `DatabaseWriteActor` |
| Screens | `ui/ScreenRouter.kt` (`Screen` sealed class in `ui/AppState.kt`) | per-screen ViewModels, e.g. asset browser at `:348` |

Rule that decides placement: a service that needs the *active graph's* repos/actor lives in a `GraphContent*Setup.kt` stack or hangs off `RepositorySet` as a nullable capability; a service that is *process-wide* (WorkManager, notification channel) is built in the platform entry and handed in through `StelekitAppPlatformIntegrations` (precedent: `gitSyncBusyCounter`, `GraphContentGitSyncSetup.kt:76`).

---

## 2. Feature A: dev console / SQL viewer

**Disposition: Isolate via seam.** The design is settled in `dev-console-git-sync-fix/research/architecture.md` §1.2-1.4 and ADR-001/002/005; do not re-derive. Verified today: no `console/` dir exists and `grep -rli "dev_console\|developer" commonMain` finds no flag.

Integration points (cite, then add only what that doc lacks):

| Point | Wire | Note |
|---|---|---|
| Route | `Screen.Console` in `ui/AppState.kt`, case in `ScreenRouter.kt` (next to `Screen.Logs`) | Dev-mode flag checked in the command layer too (research §1.6) |
| Context bundle | `ConsoleContext` adapters built in a new `GraphContentConsoleSetup.kt` stack, same style as `GraphContentCameraCapture.kt:50` (`GraphDiagnosticsCollector`) | New file, not an edit to `GraphContentViewModelSetup` |
| SQL seam | nullable `sqlConsole` on `RepositorySet` (ADR-002 item 6); built inside `RepositoryFactoryImpl` where `activeDriver` is private (`RepositoryFactory.kt:117-119`) | Only edit to a shared file; conflicts with PR #397 (requirements blocker) |
| Writes | `SqlConsoleWriter` -> `DatabaseWriteActor.execute{}` with one `@OptIn(DirectSqlWrite)` function + one `RestrictedDatabaseQueries` stub (ADR-002 item 2) | Matches CLAUDE.md "only approved class-level opt-in" rule |
| Scope | `ConsoleSession` owns `CoroutineScope(SupervisorJob()+Default+CoroutineExceptionHandler)` | CLAUDE.md scope-ownership + Android uncaught-Throwable rules |

### Failure modes (Complexity 4)

| # | Failure mode | Control | Residual / test |
|---|---|---|---|
| F1 | A "read" statement mutates (CTE-wrapped `DELETE`, `PRAGMA`, multi-statement) | ADR-002: engine-enforced read-only connection (`SQLiteConfig.setReadOnly`/`OPEN_READONLY`), fail closed, classifier is only a router | Spikes 0.2a/b/c unrun (ADR status "pending spikes"). Add a property test: for generated mutating statements, read connection rejects, row-count of every table unchanged |
| F2 | Read connection leaks `query_only` into pooled app connections (8 pooled JVM connections, ADR-002 context) | Dedicated connection, never the pool | Test: app write succeeds after console read of `PRAGMA query_only=1` |
| F3 | Credential disclosure via `SELECT` | Credential tables not on read allowlist (ADR-002 item 3); `ConsoleRedactor`; `PatLeakageAuditTest` variant | Add allowlist-completeness test: every table in `SteleDatabase.sq` is classified (allow/deny) so a new table fails CI |
| F4 | Large result materializes in heap (451 MB DB, 512 MB heap) | Cursor iteration, 500-row cap, 2 KB per cell, scrollback ring buffer 5k lines / 2 MB (research §1.4) | Test with 1 MB-cell BLOB rows; assert peak retained < budget |
| F5 | Long query pins the single write path or UI | Reads on own connection; `withTimeout` + driver cancel; writes serialized by actor | Android driver-specific interrupt unverified |
| F6 | Write drifts DB from markdown (cache vs source of truth) | Tag "DB-only", backup before destructive classes, `DbOnlyRowsGuard`, disk-wins `graph reload` (ADR-002 items 4-5) | Spike 0.2c: does actor invalidation trigger `GraphWriter`? Unverified |
| F7 | Backup is an unencrypted full DB copy in app dir; Android Auto Backup defaults on | Task 5.5f exclusion rules | Verify manifest rules in a test that parses `AndroidManifest.xml` |
| F8 | Exposure on Web (no read-only connection) | Disable `sql` on wasm/iOS with reason (ADR-002 item 1) | Compose test for disabled state |

---

## 3. Feature B: desktop scheduled sync

**Disposition: Refactor-first (small), then Extend as-is.** Refactor = collapse three timers to one policy-driven path before wiring any of them.

Evidence of duplication (VERIFIED, files read):
- `jvmMain/.../git/DesktopSyncScheduler.kt`: delay loop, `onTick` callback, `CoroutineScope(SupervisorJob()+IO)` with **no `CoroutineExceptionHandler`** (violates the CLAUDE.md long-lived-scope rule) and an unguarded `onTick()` in `while(true)` (one thrown error ends the loop silently). No test references it (`grep` over `*Test` dirs empty). Introduced in one commit (`7269c4eea6`), never touched since.
- `GitSyncService.startPeriodicSync` (`:751`): same loop, inside the service that already owns a guarded `scope`, calls `refreshLocalStatus` + `fetchOnly`.
- `WorkManagerSyncScheduler` (`androidMain`): enqueue half unwired (section 0).

Recommended wiring (consistent with ADR-004; do not contradict it):
1. **Delete `DesktopSyncScheduler`.** It adds a second scope and a callback indirection over what `startPeriodicSync` does. The `BackgroundSyncScheduler` interface stays, as the port for *closed-process* scheduling (WorkManager), and gets a `NoOp`/desktop-in-service implementation only if a second consumer appears (YAGNI otherwise).
2. ADR-004 changes the tick body: `startPeriodicSync` calls `runScheduledSync` (policy: `Run`/`FetchOnly`/`Skip`) instead of `fetchOnly`. Interval = `GitConfig.pollIntervalMinutes` (already stored, default 5; Android clamp to 15).
3. **Single start point**: `GraphContentGitSyncSetup.kt` (`rememberGraphContentGitSyncStack`) is the only place the service is built and torn down (`:39-46`). Start the loop in the same `remember`/`DisposableEffect`, keyed on `(service, config.pollIntervalMinutes)`; `shutdown()` already cancels it. On Android the same effect additionally calls `WorkManagerSyncScheduler(context, graphId).schedule(...)` via a factory in `StelekitAppPlatformIntegrations` (new nullable field `backgroundSyncSchedulerFactory`), so the common stack never imports `androidMain`.
4. Settings flag default off (requirements Risk Control): `scheduled_sync_enabled`, read via `Settings`.

Consistency requirements: tick must hold the per-graph git write lock (`GitWriteLockNaming`) and `GitSyncBusyCounter`, per the sync Event-Command-Policy rows 17-19 in the dev-console research §5. Do not re-state that table. **Delta to it** (only rows this plan adds):

| Event | Command | Policy |
|---|---|---|
| Stack composed with `pollIntervalMinutes > 0` and flag on | `startPeriodicSync(graphId, n)` | Exactly one loop per service instance; `stopPeriodicSync()` first (already in the function) |
| `pollIntervalMinutes` edited in Git settings | restart loop with new interval | Today the value is stored and ignored: this row is the actual fix for metric 4 |
| Graph switch / `GraphContent` leaves composition | `shutdown()` | Already done at `GraphContentGitSyncSetup.kt:45`; test asserts no tick after dispose |
| Tick throws | log at WARN, continue loop | Loop catches `Throwable` (not `CancellationException`) per tick |

Test strategy (for the plan): virtual-time test on `GitSyncService` with a fake `GitRepository` proving N ticks after N intervals, zero after `shutdown()`, one tick after an interval edit; a Compose/stack test (Robolectric `androidUnitTest` per CLAUDE.md) that composing `rememberGraphContentGitSyncStack` with the flag on starts the loop. That second test is what "reachable from the shipped app" means; a unit test of the loop alone is the existing failure mode.

---

## 4. Feature C: asset / image pipeline

**Disposition: Isolate via seam**, in three increments: (1) indexing, (2) on-device pipeline, (3) cloud plugins last/optional. Not Refactor-first: the services are self-contained, but fix the two defects below in the same change that wires them.

Current wiring gap (VERIFIED): `AssetIndexService`, `AssetMoveService`, `AssetPipelineService`, `PluginRegistry`, and all five plugins have no non-test construction site. `ImageLabeler` has an Android impl (`androidMain/.../platform/ml/MlKitImageLabeler.kt`) and a `NoOpImageLabeler` (`platform/ml/MlServiceStubs.kt`); `PdfTextExtractor` has `AndroidPdfTextExtractor`. JVM has neither.

Where each goes (follows `asset-management/research/architecture.md` §C line 418: "not on `RepositorySet`, wired in the Compose layer like `GraphLoader`"):

| Component | Composition root | Lifecycle |
|---|---|---|
| `PluginRegistry` | built in platform entry via one function `defaultAssetPlugins(platform): List<AssetPipelinePlugin>` and passed through `StelekitAppPlatformIntegrations` | process-wide, immutable after build |
| `AssetIndexService`, `AssetPipelineService` | new `GraphContentAssetSetup.kt` stack (same shape as `GraphContentGitSyncSetup.kt`) with `DisposableEffect { onDispose { shutdown() } }` | per graph; started after `onFullyLoaded` so it never competes with graph import |
| `AssetMoveService` | same stack; `replayPendingMoves` called once at graph open (it has a WAL table) | per graph |
| On-import hook | `MediaAttachmentService` -> `AssetIndexService.registerAsset` -> `AssetPipelineService.scheduleProcess` | event-driven |
| Memory pressure | `StelekitViewModel` already calls `graphLoader.cancelBackgroundWork()` on trim (`StelekitViewModel.kt:1441`); add `assetPipeline.cancelBackground()` beside it | CLAUDE.md heap rule |

Defects to fix while wiring (both VERIFIED by reading, behavior INFERRED until a test runs):
- **Backfill skips rows.** `AssetPipelineService.drainUnprocessed` (`AssetPipelineService.kt:123-140`) advances `offset += batchSize` over `selectUnprocessedAssets` (`SteleDatabase.sq:1190`: `WHERE ml_processed = 0 AND ml_failed = 0 … LIMIT/OFFSET`). Each processed (or failed) row leaves that result set, so the next offset skips ~half the remaining rows. `GraphLoader.indexRemainingPages` avoids this with an attempted-UUID set plus offset advance only past stuck rows (CLAUDE.md "Background indexing"); this loop only applies the set as a filter. Fix: always read offset 0 and rely on the attempted set for termination. Property test: N assets, mixed success/failure plugins, all reach a terminal state.
- **Unbounded state and memory.** `attemptedUuids` grows for the process lifetime; `AssetIndexService.collectFilesRecursively` materializes the whole asset tree (warns only past 1000, `AssetIndexService.kt:~141`); plugins read whole files (`OcrPlugin` takes `imageBytes: ByteArray`). On a 512 MB heap this must go through the `DecodeBudget` of `image-pipeline/implementation/plan.md` or, minimally, a downscaled decode (the existing `ImageDownscalerPort`) before ML. Cap concurrency at 1 and process on `PlatformDispatcher.Default`, never DB.

### Event-Command-Policy: asset pipeline

| # | Event | Command | Policy |
|---|---|---|---|
| 1 | Image/PDF attached in editor or share | `AssetIndexService.registerAsset` (write via actor) | Idempotent on content hash; failure never blocks the attach |
| 2 | Graph reaches fully-loaded | `AssetIndexService.startBackfill` (batches of 50) | Never during `loadGraph`; cancelled by graph switch |
| 3 | Asset row inserted with `ml_processed=0` | `AssetPipelineService.scheduleProcess` | Feature flag default off; one asset in flight |
| 4 | Plugin returns Left or throws | `markMlFailed`, continue to next asset | Failed rows excluded from backfill (`ml_failed=1`); retry only on explicit user action |
| 5 | All plugins Right | one actor `execute` writing labels + ocr + processed | Single transaction so a crash cannot leave labels without the flag |
| 6 | `onTrimMemory` / graph switch | cancel backfill and in-flight plugin | CLAUDE.md heap rule |
| 7 | User moves asset | `AssetMoveService.moveAsset` (WAL row, file move, reference rewrite) | Markdown rewrite goes through `GraphWriter`, never behind it (CLAUDE.md cross-graph rule); `replayPendingMoves` at open |
| 8 | Cloud plugin enabled and asset eligible | send bytes to third party | See egress controls below; refused unless every gate passes |

### Cloud-plugin data egress (Complexity 4)

Facts (VERIFIED): `CloudEnrichmentConfig` has `enabled=false`, `sessionCap=20`, `toString()` redacts the key; the plugins' `canProcess` honors `enabled`/cap; `processAsset` is a stub with no HTTP. Requirements classify the data as confidential.

Controls to require before registering either cloud plugin (each testable):
1. **Not registered unless config exists and is explicitly enabled**: `defaultAssetPlugins` omits them otherwise, so the unconfigured app has no code path to a third party. Structural test: the module declaring Ktor client calls for these plugins is referenced only from that factory.
2. **Opt-in per graph, not global**; consent text names the provider and what is sent (image bytes, PDF text). Stored in `Settings`, default false.
3. **Key storage** in the vault/credential store (`VaultCredentialStore`), never `Settings` plaintext; `apiKey` never logged (existing redaction) and added to the `PatLeakageAuditTest`-style audit.
4. **Hard caps**: session cap exists; add a per-day cap and a per-asset size cap, counted in the pipeline (not only in the plugin, because `sessionCount` is incremented before the call and resets per process).
5. **Network gate**: skip on `NetworkMonitor` offline and metered (Android), mirroring `GitSyncService`'s monitor use.
6. **Egress log**: one INFO line per upload (asset uuid, bytes, provider), no content, visible in the Logs screen/console.
7. Air-gap interplay: a `transfer/AirGapGuardTest` exists in `jvmTest`; extend its allowlist deliberately so cloud egress is the only permitted exception and a new HTTP call elsewhere still fails.

Recommendation: ship increments 1 and 2 first; implement cloud plugins only on owner request (they are stubs, so there is no regression from leaving them unregistered). Delete decision for them is in section 6.

---

## 5. Structural enforcement: stop new unwired seams recurring

Existing precedents (VERIFIED): `businessTest/.../AllBusinessTestsCompletenessTest.kt` (source-walk structural guard), `jvmTest/.../capture/CaptureWriterSourceAuditTest.kt`, `jvmTest/.../transfer/AirGapGuardTest.kt`, `PatLeakageAuditTest`, and the proposed `DirectSqlWriteOptInAllowlistTest` (ADR-002 Task 5.5a). Same pattern, new rule.

**Rule 1: `WiringCompletenessTest` (jvmTest, source audit).** For each seam interface in an explicit list (`BackgroundSyncScheduler`, `AssetPipelinePlugin`, `ConsoleCommand`, `GraphLoaderPort`-style ports as desired), find every implementing class in `src/*Main`, and assert its simple name followed by `(` appears in a non-test, non-own-file source under a composition-root set (`desktop/Main.kt`, `MainActivity.kt`, `SteleKitApplication.kt`, `ui/GraphContent*Setup.kt`, `StelekitAppDependencies.kt`, `RepositoryFactory.kt`, platform `*Factory`/`defaultAssetPlugins`). Failures print the class and the roots searched. An allowlist file `wiring-allowlist.txt` holds `ClassName  reason  YYYY-MM-DD` and the test fails on entries older than 90 days, so "dormant by design" expires. Limits: text match, so a constructor in dead code still passes; run it against the transitive reachability check below to cover that.
**Rule 2: reachability scan in CI (broader, advisory first).** Re-run the requirements' name-grep as a script that excludes manifest components, `@Serializable` DTOs, same-file helpers (the false-positive classes the requirements list), outputs a candidate list, and is diffed against a committed baseline: new entries fail. This measures success metric 1 directly. R8 `-printusage` on the release APK is an independent compiler-grade check for Android; compare once to calibrate the grep.
**Rule 3: type-driven registration so omission is a compile error.** Make the registry a total function of a platform capability set instead of ad hoc `register` calls: `fun defaultAssetPlugins(caps: PlatformCaps): List<AssetPipelinePlugin>` where `PlatformCaps` carries `imageLabeler`, `pdfText`, `cloud: CloudEnrichmentConfig?`; adding a plugin requires editing this one function, and the structural test in Rule 1 needs only that function as its root. Same shape for console commands (`ConsoleRegistry` takes an injected list, research §1.2) and schedulers (`backgroundSyncSchedulerFactory` field with no default, so a new platform host fails to compile until it supplies one or an explicit `NoOp`).
**Rule 4: end-to-end entry-point test per feature** (success metric 1): drive the composition root, not the class. Android: Robolectric via the `GraphContent*Setup` stack (CLAUDE.md prefers `androidUnitTest` for headless Compose); desktop: `jvmTest` with `scripts/jvm-display-check.sh` only when pixels are needed.

---

## 6. Dormant search / RTC / plugin code: wire-or-delete (architecture view)

Verified shapes via `Read`; values judged only on architecture fit. The owner decides; recommendations default to the evidence.

| Item | State (VERIFIED) | Architecture fit | Recommendation | Disposition |
|---|---|---|---|---|
| `RTCManager`, `RTCOp` (`rtc/RTCManager.kt`) | All bodies placeholders; no transport, no "client-ops" store; would need a server | Real RTC conflicts with file-as-source-of-truth + git sync; two merge authorities | **Delete** (with tests) | Extend as-is not applicable; delete |
| `DatalogEngine`, `DatalogQuery`, `VisualQueryBuilder`, `DatalogSearchSupport` (`search/DatalogQuery.kt`) | `execute` returns empty; builder TODO; overlaps shipped `query/QueryExecutor` (`RepositorySet.queryExecutor`, `RepositoryFactory.kt:77`) and FTS | Duplicates the query subsystem | **Delete**; revisit only as a front-end to `QueryExecutor` | Delete |
| `VectorSearch`, `InferenceWorker`, `PlaceholderInferenceWorker`, `RemoteInferenceWorker`, `InferenceBridge` (`search/VectorSearch.kt`) | Brute-force cosine over a caller-supplied `List<Block>`; embeddings per call; placeholder worker | O(graph) read and embedding recompute violate the "graph-scale reads must be bounded" rule (CLAUDE.md); needs an embedding store (schema + `MigrationRunner` + backfill) | **Delete now**; if semantic search is wanted, new plan with persisted embeddings and an on-device model (the LLM provider work in `llm-provider/` is the right seam) | Delete (Refactor-first if wired) |
| `PluginHost`, `Plugin`, `JsPluginBridge` (`platform/PluginSystem.kt`) | `PluginHost` is already constructed by default (`StelekitAppDependencies.kt:41`) and only its count is shown (`GraphContentStatusAndBottomBar.kt:54`); bridge methods log and return `null` | A plugin host with no plugins and no sandbox is an attack-surface placeholder; JS interop has no platform design | **Delete `JsPluginBridge`**; keep `Plugin`/`PluginHost` only if a first in-tree plugin is planned, else delete and remove the status-bar count | Delete / Isolate via seam |
| `DesktopSyncScheduler` | See section 3 | duplicate timer | **Delete** | Delete |
| `CloudClaudePlugin`, `CloudVisionPlugin` | Stubs, no HTTP | egress risk, no value yet | **Delete or park behind owner request**; do not register | Isolate via seam if kept |
| `OcrPlugin` | Actually wraps `ImageLabeler` (no OCR engine exists) | Misnamed; would write label output into `ocr_text` | **Fix or delete**: either add a real `OcrEngine` port (ML Kit text recognition) or drop the plugin; do not register as is | Refactor-first |
| `OnDeviceLabelingPlugin`, `PdfTextPlugin` | Real, thin, depend on `ImageLabeler`/`PdfTextExtractor` (Android impls exist) | fits | **Wire** (increment 2) | Extend as-is |

Delete mechanics: follow `editor-subsystem-dead-code/research/architecture.md` ("Risk: does deleting break compilation?" line 92 onward): remove the types, their tests, and entries in `AllBusinessTests`'s suite list (the completeness test fails otherwise), then rerun the Bazel target split check (`commonmain-bazel-target-split`).

---

## 7. Tech-debt / hotspot disposition (summary)

Churn figures are not re-measured here; use the numbers already recorded in `dev-console-git-sync-fix/research/architecture.md` §4 (GraphLoader 1970 lines/63 commits, GraphManager 1601/54, StelekitViewModel 2358/91, GitSyncService 687/16). One new datum I did not measure: `GraphContent*Setup.kt` files are the intended place for new services; they are small and separate, so new stacks do not enlarge a hotspot.

| Area | Disposition | Reason |
|---|---|---|
| `GraphManager`, `StelekitViewModel`, `GraphLoader` | **Isolate via seam** | New work goes in new `GraphContent*Setup.kt` stacks and adapters; only edit is one trim-memory line beside `cancelBackgroundWork()` |
| `RepositoryFactory` / `RepositorySet` | **Extend as-is** (one nullable field `sqlConsole`) | Precedent `perfExporter`; rebase on #397 first |
| `GitSyncService` | **Extend as-is** | `startPeriodicSync` already exists; add `runScheduledSync` per ADR-004, no scheduling branches inside `sync()` |
| `DesktopSyncScheduler` / `BackgroundSyncScheduler` | **Refactor-first (delete + collapse timers)** | Section 3 |
| `AssetPipelineService` | **Refactor-first (bounded fix)** | Offset-skip defect and unbounded set before wiring |
| `AssetIndexService`, `AssetMoveService`, `PluginRegistry` | **Extend as-is** | Self-contained; wire them |
| Cloud plugins, `OcrPlugin` | **Isolate via seam** (or delete) | Section 4 egress controls |
| RTC, Datalog, VectorSearch, JsPluginBridge | **Delete** | Section 6 |

---

## 8. Open questions / unverified

1. (Device) Confirm the Assets screen is empty on the owner phone (header 2 is grep-only).
2. (Run) Spikes 0.2a/b/c of ADR-002 still gate F1, F2, F6; unrun.
3. (Run) Confirm the drain-skip defect with a test before fixing; the reasoning is from `SteleDatabase.sq:1190` plus the loop, not an execution.
4. (Owner) Delete vs park for cloud plugins, `PluginHost`, and `OcrPlugin`.
5. (Verify) Whether Android `schedule()` is invoked by any path I did not grep (reflection, manifest `androidx.startup`). Grep over `androidApp`, `commonMain`, `jvmMain` found none; `androidMain` only the `resumeFor` call.
6. (Not measured) Line/branch coverage per module; coverage map belongs to the testing-strategy dimension of this plan.
7. (Heap) PR #501's body states it is unproven that the fixes cure the OOM ("no heap dump"); any new background pipeline should be measured against the same export-based method, not assumed safe.
