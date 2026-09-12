# Architecture Research: Desktop Quick Capture

## 1. Write-path integration (VERIFIED via code)

### The exact chain today (Android `CaptureActivity`)

`CaptureActivity.onCreate` → `CaptureViewModel.save()` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:53-118`) → `performSave()`:

1. `graphManager.getActiveRepositorySet()` — the *currently active* `RepositorySet` (see multi-graph note below). Errors out with "No active graph" if null.
2. `repoSet.journalService.ensureTodayJournal()` — `JournalService.ensureTodayJournal()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt:106-160`), mutex-guarded, idempotent, dedupes/merges any pre-existing duplicate "today" pages, creates the page via `writeActor.execute { pageRepository.savePage(...); blockRepository.saveBlock(initialBlock) }` if none exists.
3. Manually builds a new `Block` (fractional-index position via `FractionalIndexing.generateKeyBetween`) rather than calling `JournalService.appendToToday()` — likely so it can flush to disk (step 5) with the exact updated block list in hand.
4. `repoSet.writeActor.saveBlock(newBlock)` — routes through `DatabaseWriteActor` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`), the mandatory write-serialization point per this repo's `@DirectSqlWrite` rule. Falls back to a direct `@OptIn(DirectRepositoryWrite::class)` repository call only if `writeActor` is null (shouldn't happen on `SQLDELIGHT` backend).
5. `GraphWriter(fileSystem, writeActor = repoSet.writeActor).savePage(page, blocks, graphPath)` — flushes the updated page to the on-disk Markdown file. `GraphWriter.savePage` is defined at `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt:206`.

So the full path is: **UI action → `JournalService.ensureTodayJournal()` → `DatabaseWriteActor.saveBlock()` (DB) → `GraphWriter.savePage()` (disk)**. All of this lives in `commonMain`/is already platform-agnostic except for `PlatformFileSystem` and the `Application`/`ComponentActivity` wiring.

### How an in-process desktop capture window plugs in

Desktop (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt:36-153`) already holds a live `GraphManager` inside the Compose `application { }` block (constructed deeper in `StelekitApp`, not shown in `Main.kt` itself — `GraphManager` is instantiated once per app process and threaded through `StelekitApp`/`StelekitViewModel`). A desktop capture popup would:

- Be a second `Window { }` (or borderless `Popup`) inside the *same* `application { }` block, opened by a global-hotkey listener.
- Receive (or look up) the same `GraphManager` instance already owned by the running app — not a new one — since `GraphManager` is a singleton per process holding `activeRepositorySet`.
- Call `graphManager.getActiveRepositorySet()!!.journalService.ensureTodayJournal()` + `writeActor.saveBlock(...)` + `GraphWriter(...).savePage(...)`, i.e. **the exact same three-call chain `CaptureViewModel.performSave()` already uses** — this can be factored into a shared `commonMain` (or `jvmMain`, since desktop-only) `CaptureWriter`/`QuickCaptureService` function that both `CaptureViewModel` and a new desktop capture ViewModel call, eliminating the current duplication risk between platforms.

### Multi-graph: which graph is "active" for capture?

`GraphManager.getActiveRepositorySet()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:713`) returns `_activeRepositorySet.value` — the graph most recently passed to `switchGraph()`, tracked in `_graphRegistry.activeGraphId` and persisted via `saveRegistry()` (`GraphManager.kt:668-669`). This is exactly what Android's capture path already uses, so desktop capture inherits the same semantics for free: **capture always targets whatever graph the main window last had open**, not a user-selectable target. If a user runs multiple graphs and wants per-graph capture routing, that's an explicit v2 scope decision — nothing about the current write path supports capturing to a non-active graph without first calling `switchGraph()`, which would tear down and reopen the main window's DB connection (disruptive if the main window is open and the user is mid-edit).

## 2. Process/IPC boundary for the three out-of-process v1 surfaces

The in-process case (2.1) has no IPC problem — it's a second window in the same JVM. The IPC problem is specific to macOS Share Extension, the Nautilus script, and the Windows context-menu handler, all of which run as **separate OS processes** with no access to the running SteleKit JVM's heap.

Confirmed by code search: this repo has **no existing IPC surface** to build on — no `ktor-server-*` dependency (only `ktor-client-*`, used for `coil-network-ktor3` image loading; `kmp/build.gradle.kts:117-245`), no `ServerSocket`/socket code anywhere in `kmp/src`, no lock-file/single-instance code, and no `java.nio.file.WatchService` usage (the disk watcher's "native fast path" per `GraphFileWatcher`'s KDoc is implemented for Android's `ContentObserver` only — `startExternalChangeDetection`/`stopExternalChangeDetection` have **no JVM implementation** in `kmp/src/jvmMain`, confirmed by grep returning zero hits). JVM desktop currently relies solely on `GraphFileWatcher`'s 5-second poll (`pollIntervalMs: Long = 5_000L`, `GraphFileWatcher.kt:59`).

Evaluating the four options against "no #232 yet, must work whether or not SteleKit is running":

**(a) Unix domain socket / Windows named pipe, tiny protocol**
- Pros: no new listening surface beyond localhost equivalent risk (a UDS/pipe isn't network-exposed at all — strictly safer than (b)); low overhead; works when the app is running.
- Cons: platform-divergent implementation (`java.nio.channels.SocketChannel` w/ `UnixDomainSocketAddress` — JDK 16+, fine for this project's JVM baseline — vs. Windows named pipes need a separate code path, likely JNA or a small native shim since JDK has no first-class named-pipe API). Still needs a cold-start story (nothing listens if SteleKit isn't running) — same problem as every "running instance" option.
- This is real new infra, but *bounded* new infra (a background listener thread on the DB write-actor's scope), and it's the most idiomatic fit for "local-only, not intended to be a general REST API" (explicitly out of scope per requirements: "The local REST API itself (#232) ... this item is meant to be a thin client of it").

**(b) Minimal single-purpose local HTTP listener embedded in the desktop process**
- Pros: same OS reachability guarantee as (a) once running; HTTP is trivially callable from a Nautilus shell script (`curl`) and from a Windows registry handler (any HTTP client, or even `curl.exe` which ships with Windows 10 1803+) without writing a socket client in Bash/PowerShell.
- Cons: **requires adding `ktor-server-*` (or similar) as a new jvmMain dependency** — this project currently only depends on `ktor-client-*`. Any HTTP listener, even bound to `127.0.0.1` only, is a bigger attack-surface commitment than a UDS — needs to be trivially distinguishable from (and not accidentally a stepping-stone toward) full #232, which requirements explicitly says is out of scope. Given the requirements doc explicitly separates "local REST API (#232)" from this feature and flags it as a *dependency to avoid*, standing up any HTTP server here — even a tiny one — blurs that line and risks scope creep into #232's territory.
- Verdict: workable but goes against the explicit "no #232 dependency" instinct in the requirements; prefer (a) or (c) unless the Nautilus/Windows-side integration cost of a raw socket protocol proves too high in practice.

**(c) Lock-file + argv hand-off via a second short-lived JVM launch**
- Pros: matches the "single instance" pattern OS-native installers commonly use (macOS `open -a`, Windows single-instance mutex checks); no persistent listener at all when SteleKit is running — the second JVM launch detects an existing instance (e.g., via the same lock file / a PID + liveness check) and needs *some* channel to hand off the payload to the running instance, which circles back to (a) or (d) for the actual data transfer. In other words (c) is really "detection strategy," not a full IPC mechanism on its own — it still needs (a)/(d) underneath for the payload once an existing instance is detected.
- Cons: JVM cold-start latency (hundreds of ms to a few seconds) for the detector process itself is wasteful if all it does is check a lock file and forward — a lighter-weight detector (shell script checking for a lock file's PID, or the OS-native single-instance registration macOS/Windows already offer for app launchers) is preferable to spinning up a second JVM just to check "is the JVM running."

**(d) "Pending captures" directory + disk-watch (reusing `GraphLoader.externalFileChanges` pattern)**
- Pros: zero new IPC code — literally file writes. Every one of the three out-of-process surfaces can trivially write a small file (macOS Share Extension via `NSFileManager`, Nautilus script via shell redirect, Windows handler via any language) to a well-known `~/.stelekit/pending-captures/` directory. This *also* solves the cold-start case for free: if SteleKit isn't running, the file just sits there and gets picked up on next launch — no separate "cold start and hand off" logic needed, unlike (a)/(b)/(c) which all still need a distinct cold-start path.
- Cons: **not a live watch today on JVM** — `GraphFileWatcher`'s native fast path (`startExternalChangeDetection`) is Android-only (confirmed: zero references in `kmp/src/jvmMain`); JVM desktop only has the 5-second poll fallback. A capture inbox watched this way would have **up to 5s latency** for the "SteleKit already running" case — acceptable for a fire-and-forget capture (unlike editing conflict detection, there's no user waiting on a dialog), but worth calling out as a UX tradeoff vs. (a)'s near-instant delivery. Also needs its own small watcher (a dedicated poll loop over the pending-captures directory, not literally the graph's `GraphFileWatcher` instance, since capture files aren't graph pages) rather than true reuse of `GraphFileWatcher` — the *pattern* (poll + reconcile) is reusable, the class is not.

**Recommendation**: (d) for the cold-start path (unconditionally, since every option needs *some* fallback when SteleKit isn't running, and (d) already solves it with no extra code) + (a) as the live-instance fast path to avoid the 5s poll latency when SteleKit is already running, with (a) writing into the same pending-captures directory format as (d) so the actual "append block to today's journal" logic has exactly one entry point regardless of which path delivered the payload. This avoids (b)'s dependency/scope-creep problem and avoids (c)'s redundant JVM-cold-start-just-to-detect step.

## 3. Cross-platform code organization

Given the write-path chain in §1 is already 100% `commonMain` (`JournalService`, `DatabaseWriteActor`, `GraphWriter` all live under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/{repository,db}/`), the natural split is:

- **`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/`** (new package, sibling to existing `desktop/`, `git/`, `service/` packages under `jvmMain` — see the current `jvmMain` top-level layout: `benchmarks/`, `cli/`, `coroutines/`, `db/`, `desktop/`, `domain/`, `error/`, `export/`, `git/`, `llm/`, `logging/`, `performance/`, `platform/`, `repository/`, `service/`, `stats/`, `transfer/`, `ui/`, `util/`, `vault/`) — for:
  - The global-hotkey listener + in-process capture popup window (JVM-only Compose `Window`, wired into `Main.kt`'s `application { }`).
  - The pending-captures directory poller (option (d)) and the UDS/named-pipe listener (option (a)) — both JVM-specific I/O, not shared with Android/iOS.
  - A shared `CaptureWriter`/`QuickCaptureService` **could** live in `commonMain` (it's just "given text + a `RepositorySet`, run the §1 write chain") and be called from both `androidApp`'s `CaptureViewModel` and the new desktop capture ViewModel — this is the one piece of genuine dedup opportunity between the platforms, per the "reuse the enrichment pipeline" note in requirements.
- **Platform-specific installer/OS-integration glue — outside the KMP module entirely**, mirroring how `androidApp/` and `iosApp/` already sit beside `kmp/` at repo root (confirmed: `androidApp/` has its own `build.gradle.kts`, `src/`, `BUILD.bazel`; `iosApp/` similarly owns its Xcode project). Concretely:
  - A new top-level `macos-share-extension/` (or under a broader `desktop-integration/` dir) holding the `.appex` bundle source (Swift/Obj-C, since Share Extensions are native — KMP has no macOS-extension target) — this cannot be `jvmMain` at all since it's a separate non-JVM process by definition (§2's whole premise).
  - A new top-level `linux-integration/` (or `nautilus/`) holding the `.desktop`/Nautilus script (shell), since Nautilus actions are just executable scripts + a `.py`/`.desktop` registration, no build system needed beyond install-time file placement (likely wired into the existing Ansible/dotfiles-style install flow this user favors elsewhere, or a `make install-linux-integration` target — but that's a packaging decision, not KMP).
  - A new top-level `windows-integration/` holding the registry `.reg`/PowerShell installer for the context-menu handler.
  - This split cleanly matches the requirements doc's own framing: v1's Linux/Windows/macOS integrations are "SEPARATE OS processes" — they were never going to be KMP targets, so they don't belong under `kmp/` any more than `androidApp/`'s Android manifest or `iosApp/`'s Xcode project do.

This repo's strict rules (write-actor gating, dispatcher matrix, coroutine-scope ownership) all apply unchanged to the new `capture/` package under `jvmMain` and to any shared `commonMain` `CaptureWriter`, since it's just calling the existing `JournalService`/`DatabaseWriteActor`/`GraphWriter` chain — no new write paths are introduced, only new *callers* of the existing one. The listener/poller in `capture/` must own its own `CoroutineScope` (`SupervisorJob() + Dispatchers.Default` or `IO` for file/socket work) per the `rememberCoroutineScope`-must-not-escape-composition rule, since it's a long-lived background component, not transient UI work.

## 4. Event-Command-Policy table

Useful here — the flow has genuinely distinct actors (user, OS, SteleKit-the-process, SteleKit-the-UI) and two branching paths (running vs. cold-start) that prose tends to blur together.

| Actor | Command | Event | Policy (triggers next command) |
|---|---|---|---|
| User | Select text in another app, invoke capture (Share menu / Nautilus action / registry context-menu / global hotkey) | `CaptureRequested(text, source)` | OS routes to the appropriate handler for that surface |
| OS / Share-Extension / Nautilus script / registry handler | `LocateRunningInstance()` | `InstanceFound` \| `InstanceNotFound` | If found → forward payload via IPC (§2 option (a)/(d) live path). If not found → write to pending-captures dir (§2 option (d)) and optionally cold-start SteleKit |
| Handler process | `WritePendingCapture(text)` (cold-start / no-instance case) | `CaptureFileWritten` | SteleKit, once running, picks it up on next pending-captures scan (startup + periodic poll) |
| Handler process (in-process desktop case only) | `OpenCapturePopup()` | `CapturePopupShown` | User types/edits before confirming save — this is the *only* branch with a UI step; out-of-process surfaces in v1 are fire-and-forget, no confirmation UI |
| SteleKit (running instance) | `EnsureTodayJournal()` | `TodayJournalEnsured(page)` | Existing `JournalService.ensureTodayJournal()` — idempotent, mutex-guarded |
| SteleKit (running instance) | `AppendCaptureBlock(page, text)` | `BlockWrittenToDb` | Routed through `DatabaseWriteActor.saveBlock()` — mandatory per `@DirectSqlWrite` |
| SteleKit (running instance) | `FlushPageToDisk(page)` | `JournalBlockWritten` (terminal) | `GraphWriter.savePage()` — matches the existing Android `CaptureViewModel.performSave()` step 5 |

Note: the table intentionally treats "cold-start SteleKit" as a policy branch rather than its own actor lane — the requirements doc flags cold-start as a known open question ("plus a cold-start story when SteleKit isn't running"), and per §2's recommendation, the pending-captures directory (option (d)) makes cold-start a non-event: SteleKit's normal startup path just needs one new step, `ScanPendingCaptures()` → replay each file through the same `EnsureTodayJournal → AppendCaptureBlock → FlushPageToDisk` chain the running-instance path uses, so cold-start requires no new command/event vocabulary, just an extra trigger for the same three commands.
