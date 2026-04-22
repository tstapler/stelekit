# Span Viewer Improvements Implementation Plan

**Feature**: Span Viewer — observability parity with Jaeger/Honeycomb/Datadog APM
**Status**: Complete
**Last Updated**: 2026-04-21

---

## Context

The PerformanceDashboard Spans tab shows waterfall bars but hides all recorded attributes, has no filtering, no timeline ruler, and no error prioritisation. This plan adds five targeted improvements entirely within `PerformanceDashboard.kt`.

**Primary file**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PerformanceDashboard.kt`

---

## Stories and Atomic Tasks

### SVI-1 — Error-first sort + span count in trace header

**SVI-1.1** — Sort `groupIntoTraces` output so ERROR traces come before OK traces (secondary sort: newest first within each tier)

**SVI-1.2** — Extend `TraceGroup` with `spanCount: Int` and `slowestChildName: String` + `slowestChildMs: Long`

**SVI-1.3** — Update `TraceWaterfallRow` header to show `(N spans · slowest: <name> <ms>ms)`

**Status**: [ ] Not started

---

### SVI-2 — Filter toolbar

**SVI-2.1** — Add `filterName: String`, `minDurationMs: Long`, `errorsOnly: Boolean` state to `SpansTab`

**SVI-2.2** — Render a second toolbar row with: `OutlinedTextField` for name search, chip-row for duration presets (Any / ≥50ms / ≥100ms / ≥250ms / ≥500ms), errors-only `FilterChip`

**SVI-2.3** — Apply filter to `traces` list: `traces.filter { trace -> ... }`

**Status**: [ ] Not started

---

### SVI-3 — Timeline ruler

**SVI-3.1** — Add `TimelineRuler(durationMs: Long)` composable that draws 4–6 labelled tick marks using `Canvas` + `drawLine` + overlaid `Text` at proportional x positions

**SVI-3.2** — Render `TimelineRuler` inside `TraceWaterfallRow` between the header and the span rows, aligned to the same 60% timeline column used by `SpanWaterfallRow`

**Status**: [ ] Not started

---

### SVI-4 — Span detail panel

**SVI-4.1** — Add `selectedSpan: SerializedSpan?` state to `SpansTab`; pass setter down through `TraceWaterfallRow` → `SpanWaterfallRow`

**SVI-4.2** — Make each `SpanWaterfallRow` clickable; clicking sets `selectedSpan`

**SVI-4.3** — Add `SpanDetailPanel(span: SerializedSpan, onDismiss: () -> Unit)` composable:
  - Status badge (green `OK` / red `ERROR`)
  - Duration, start epoch ms, end epoch ms
  - Trace ID, span ID, parent span ID (monospace, selectable)
  - Attributes table (sorted key → value rows)
  - Dismiss button

**SVI-4.4** — Show `SpanDetailPanel` in a `Box` overlay at the bottom of the Spans column when `selectedSpan != null`

**Status**: [ ] Not started

---

### SVI-5 — Status badge on span rows

**SVI-5.1** — When `span.statusCode == "ERROR"`, show a small red `●` dot prefix before the span name in `SpanWaterfallRow`

**Status**: [ ] Not started

---

## Implementation Order

1. SVI-1 (sort + header metadata) — pure data, no UI risk
2. SVI-5 (error dot) — one line, unblocks visual verification of errors
3. SVI-2 (filter toolbar) — additive UI
4. SVI-3 (timeline ruler) — additive UI
5. SVI-4 (detail panel) — requires state threading, do last

---

## Acceptance Checklist

- [x] Traces with an ERROR span always appear above all-OK traces
- [x] Header shows correct span count and slowest child name
- [x] Name filter, duration filter, and errors-only toggle each narrow the trace list correctly
- [x] Timeline ruler tick labels are readable and proportionally correct
- [x] Clicking a span row shows the detail panel with all attributes
- [x] Detail panel can be dismissed
- [x] Compiles clean for JVM (`./gradlew :kmp:compileKotlinJvm`)
- [x] Detekt clean (`./gradlew :kmp:detekt`)
