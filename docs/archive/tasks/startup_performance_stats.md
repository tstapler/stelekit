# Feature: Startup Performance Statistics & Debugging

**STATUS: ✅ COMPLETED**
**Completion Date**: January 10, 2026
**Implementation Evidence**:
- Performance monitor infrastructure implemented in `kmp/src/commonMain/kotlin/com/logseq/kmp/performance/`
- Performance dashboard UI component created in `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/PerformanceDashboard.kt`
- Logging infrastructure available in `kmp/src/commonMain/kotlin/com/logseq/kmp/logging/`
- All acceptance criteria met

---

## Epic Overview

**User Value**: As a developer/user, I want to understand where time is spent during application startup and graph loading, so that I can identify bottlenecks and verify performance improvements.

**Success Metrics**:
-   ✅ Startup timeline is captured with < 1ms overhead per trace.
-   ✅ Slow operations (>100ms) are automatically logged.
-   ✅ A dedicated "Performance Dashboard" UI is available to visualize the startup sequence.

**Scope**:
-   **Included**: Instrumentation of `GraphLoader`, `App` initialization, Repository loading; In-app UI for stats; Console logging.
-   **Excluded**: Automated performance regression testing (CI), Sampling profiler integration.

## Architecture Decisions

### ADR 003: Lightweight Performance Monitor
-   **Context**: Need to track duration of suspend functions and blocks across KMP.
-   **Decision**: Implement a simple `PerformanceMonitor` singleton in `commonMain` using `kotlinx.datetime.Clock` (or platform specific high-res timer).
-   **Rationale**: Full tracing libraries (OpenTelemetry) are too heavy for this stage. Simple start/end tracking is sufficient.

## Story Breakdown

### Story 1: Instrumentation & Collection [1 day] ✅ COMPLETED
**User Value**: Data is collected and logged.
**Acceptance Criteria**:
-   ✅ `GraphLoader` logs duration of `loadGraph`, `loadDirectory`, and per-file parsing average.
-   ✅ Logs appear in the console/LogDashboard.

### Story 2: Performance Dashboard UI [1-2 days] ✅ COMPLETED
**User Value**: I can see the timeline visually.
**Acceptance Criteria**:
-   ✅ New "Performance" screen/modal.
-   ✅ List of tracked events with duration.
-   ✅ Color coding for slow operations.

## Atomic Task Decomposition

### Story 1: Instrumentation & Collection

#### Task 1.1: Performance Monitor Infrastructure [2h] ✅ COMPLETED
-   **Objective**: Create a helper to track start/end times and store them in memory.
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/performance/PerformanceMonitor.kt` (New)
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/logging/Logger.kt`
-   **Implementation**:
    -   `startTrace(name: String)`
    -   `endTrace(name: String)`
    -   Store events in a list/flow.
-   **Validation**: Unit test.

#### Task 1.2: Instrument GraphLoader [2h] ✅ COMPLETED
-   **Objective**: Add tracing to the graph loading process.
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`
-   **Implementation**:
    -   Wrap `loadGraph` block.
    -   Wrap `loadDirectory` block.
    -   Wrap `parseAndSavePage` block.
-   **Validation**: Run app, check logs.

### Story 2: Performance Dashboard UI

#### Task 2.1: Performance Dashboard Composable [3h] ✅ COMPLETED
-   **Objective**: A UI to display the captured traces.
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/PerformanceDashboard.kt` (New)
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/App.kt`
-   **Implementation**:
    -   `LazyColumn` of traces.
    -   Sort by duration or start time.
    -   Integrate into `App` (e.g. new Menu item "View > Performance").
-   **Validation**: Open dashboard, see graph load stats.

## Integration Checkpoints
-   ✅ **Checkpoint 1**: App logs "Graph loaded in X ms" to console.
-   ✅ **Checkpoint 2**: "Performance" menu item opens dashboard with data.

## Completion Notes

This feature has been successfully implemented and is now part of the main application. The performance monitoring infrastructure provides valuable insights into application startup and graph loading performance, enabling data-driven optimization decisions.

---

*Archived: January 10, 2026*
*Original Plan Created: Recent*
