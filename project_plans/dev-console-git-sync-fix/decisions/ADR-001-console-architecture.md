# ADR-001: Console is a separate `console/` package with capability-scoped context

**Status**: Accepted (plan phase) | **Date**: 2026-10-10

## Context
The dev console must reach git, SQL, filesystem, settings, logs and graph state on Android, Desktop and (partially) Web, without becoming a second god-object next to `GraphManager` (1601 lines) and `StelekitViewModel` (2358 lines, highest churn in the repo). An editor `CommandRegistry` (`editor/commands/CommandRegistry.kt`) and `Command<T>` (`command/Command.kt`) already exist but are palette- and undo-oriented.

## Decision
- New package `kmp/src/commonMain/kotlin/dev/stapler/stelekit/console/`. Names `ConsoleCommand`, `ConsoleRegistry`, `ConsoleSession` (no reuse of `CommandRegistry`/`Command`; reuse would leak console commands into the command palette).
- Commands receive a narrow immutable `ConsoleContext` of nullable capability views (`ActiveGraphView?`, `GitConsoleView?`, `SqlConsoleView?`, `DbRestoreView?` (restore and swap, ADR-002 A3), `ProcessRunner?`, `FileSystem`, `Settings`, `ConsoleRedactor`, clock). Adapters live in one composition root (`ui/ConsoleComposition.kt`, hosted by a new `ui/GraphContentConsole.kt` in the `GraphContent*` family); console wiring is **not** added to `GraphContentCameraCapture.kt` beyond the Epic 1 diagnostics change. Commands never see `GraphManager`.
- Registry is an injected list; a new probe is a one-file addition. Capabilities absent on a platform make the command listed-but-disabled with a reason (mirrors `TargetWriterCapabilities`).
- `ConsoleSession` owns its `CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler)`; never `rememberCoroutineScope()`. It is hosted by a `remember {}` site, which is sufficient for rotation because `MainActivity` declares `configChanges` (`androidApp/src/main/AndroidManifest.xml:44`); it is lost on process death by design. The setting is called developer mode in the UI and `developer_mode_enabled` in code.

## Alternatives rejected
- Extend editor `CommandRegistry`: palette leakage, not argv-shaped.
- Give commands `GraphManager`: god-object coupling, untestable without full graph.
- Debug-socket inspector / Termux / Flipper: network-exposed or external dependency (requirements: out of scope).

## Consequences
Unit-testable with fakes; wasm passes `null` for git/process. One more composition root to maintain. Adds only a `Screen.Console` route (annotated `@HelpExempt`, like its siblings in `AppState.kt:46-52`) to `AppState.kt`/`ScreenRouter.kt` (hotspot-safe).
