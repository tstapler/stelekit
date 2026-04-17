# Requirements: Performance Monitoring & Management

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-13

## Problem Statement

SteleKit users (specifically the author on Android) experience visible stuttering and freezing during normal use — including scrolling through long pages, navigating between screens, typing/editing blocks, startup/graph load, and searching for block references. There is currently no tooling to detect, measure, or reproduce these performance problems systematically, making it difficult to file actionable bug reports or verify fixes.

## Success Criteria

- Worst-case jank events are captured with enough context to reproduce and fix them
- A frame-time overlay and timing histograms let us identify regressions during local development
- A one-tap bug report export packages perf metrics + device info + reproduction steps
- An OpenTelemetry-compatible APM integration provides ongoing visibility into performance over time (locally and optionally remotely)
- CI benchmarks eventually block regressions (not required in first release)

## Scope

### Must Have (MoSCoW)

- **Jank event capture**: Automatically record frames that exceed a threshold (e.g. >16 ms), with surrounding context (screen, action, block count)
- **Frame time overlay**: Real-time FPS / frame duration overlay, toggle-able via debug menu
- **Timing histograms**: Always-on lightweight metrics stored locally — DB load time, search latency, navigation latency, editor input latency
- **Bug report builder**: One-tap export of perf metrics, device info, and reproduction steps as a structured report (text/JSON)
- **OpenTelemetry integration**: Instrument spans and metrics using OTel APIs; exportable to any compatible backend or local file

### Should Have

- Debug menu (hidden in production, accessible via dev settings) to toggle any instrumentation layer
- Desktop (JVM) profiling support via shared KMP instrumentation
- Persistent local storage of timing histograms over time (SQLDelight)

### Could Have

- CI benchmark suite that runs on PR and flags regressions
- GitHub issue template pre-filled from bug report export

### Out of Scope

- Remote analytics server / cloud telemetry backend (no data leaves the device automatically)
- iOS-specific instrumentation (KMP-shared code will work on iOS but it is not a target for this phase)
- Direct GitHub/Jira API integration for submitting reports
- Automated CI benchmarks blocking PRs (deferred)

## Constraints

- **Tech stack**: Kotlin Multiplatform (KMP); shared instrumentation must compile for Android + JVM (Desktop); Jetpack Compose UI
- **Database**: SQLDelight 2.3.2 — timing histograms stored here
- **Overhead**: Always-on metrics must be lightweight (no UI impact); heavy tooling (frame overlay, traces) is debug-only or opt-in via debug menu
- **No backend**: No third-party analytics server in Phase 1; OTel exporter targets local file or stdout only
- **Dependencies**: Must not break existing `commonMain` / `androidMain` / `jvmMain` source set separation

## Context

### Existing Work

- App already has `GraphManager`, `GraphLoader`, `GraphWriter` with clear data-flow boundaries — good instrumentation points
- SQLDelight schema exists and is ready to add a metrics/histogram table
- No existing APM, tracing, or performance tooling in the codebase

### Stakeholders

- Primary: Tyler Stapler (sole developer, primary Android user)
- Secondary: Future contributors who need reproducible perf bug reports

## Research Dimensions Needed

- [ ] Stack — evaluate OpenTelemetry KMP libraries; Compose frame timing APIs on Android and JVM
- [ ] Features — survey comparable in-app perf overlays (LeakCanary, Perfetto, Android GPU Inspector, Compose Compiler metrics)
- [ ] Architecture — where to inject OTel spans in the existing data flow; how to store histograms in SQLDelight; debug menu pattern for KMP
- [ ] Pitfalls — overhead of always-on instrumentation; Compose recomposition tracking pitfalls; OTel SDK size on Android
