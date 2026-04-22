# Requirements: Span Viewer Improvements

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-21

## Problem Statement

The Spans waterfall tab in the PerformanceDashboard shows span bars and durations but exposes none of the recorded attributes (e.g. `block.count`, `file.path`, `to.insert`). There is no way to filter to slow or failing traces, no timeline ruler to quantify spans visually, and errors do not sort to the top. The current viewer is not meaningfully more useful than a basic log file. The goal is to close the gap with production-grade observability UIs (Jaeger, Honeycomb, Datadog APM) within the constraints of a single Compose file.

## Success Criteria

- Clicking any span row opens a detail panel showing all key-value attributes, exact timestamps, and IDs
- A filter toolbar lets users narrow to traces by name, minimum duration, and error status
- A timeline ruler with labelled tick marks appears above every waterfall so spans are quantitatively readable
- Each trace header shows span count and the name of the slowest child span
- Traces with at least one ERROR span sort to the top of the list

## Scope

### Must Have

- **Span detail panel** — slide-in or expandable panel per span with: all attributes as a key-value table, start/end epoch ms, span ID, parent span ID, trace ID, status code badge
- **Filter toolbar** — name-contains text field, minimum-duration slider (0 / 50 / 100 / 250 / 500 ms presets), errors-only toggle
- **Timeline ruler** — horizontal axis above each waterfall showing labelled ms ticks scaled to the trace duration
- **Span count + slowest-child label** in trace header (e.g. `parseAndSavePage  (7 spans · slowest: db.getBlocks 42ms)`)
- **Error-first sort** — traces containing ERROR spans bubble above all-OK traces

### Should Have

- Status code color badge (green `OK`, red `ERROR`) in the detail panel and on each span row
- Attributes shown in sorted key order for reproducible display

### Out of Scope

- Remote export / sharing of spans
- Aggregation / flame graph view (deferred)
- Editing or annotating spans

## Constraints

- All changes in a single Compose file (`PerformanceDashboard.kt`) — no new files unless unavoidable
- Must compile for `commonMain` (no Android/JVM-specific imports)
- No new dependencies
