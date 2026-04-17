# ADR-004: In-Memory Ring Buffer + Stdout Exporter (No Remote Backend)

**Date**: 2026-04-13
**Status**: Accepted
**Deciders**: Tyler Stapler

---

## Context

OTel spans need an exporter. Options range from a no-op (spans discarded) to a full remote OTLP backend. The requirements explicitly state: "No remote analytics backend in Phase 1." Four exporter configurations were evaluated:

| Exporter | Remote? | Useful in bug reports? | Setup complexity | Privacy impact |
|----------|---------|----------------------|-----------------|---------------|
| No-op (discard) | No | No | Zero | None |
| Stdout/logging only | No | Partial (requires log capture) | Low | Low |
| In-memory ring buffer | No | Yes (snapshot in bug report) | Low | None (controlled export) |
| Remote OTLP (Jaeger, Grafana Cloud) | Yes | Yes | High (backend infra needed) | High (user data leaves device) |

---

## Decision

Use **two exporters in parallel**:

1. **`RingBufferSpanExporter`** (custom, `commonMain`): Fixed-capacity circular buffer of 1000 spans. Provides a `snapshot()` function consumed by `BugReportBuilder`. Zero network I/O. Opt-in export via "Export Bug Report" button.

2. **`OtlpJsonLoggingSpanExporter`** (from `opentelemetry-kotlin` SDK): Writes completed spans as JSON to stdout. Enabled only when `DebugMenuState.isOtelStdoutEnabled = true`. Useful for piping to a local Jaeger instance during development without any code change.

Remote OTLP (e.g., Grafana Cloud) is explicitly deferred to a future phase. No telemetry is sent without the user explicitly tapping "Export Bug Report."

---

## Rationale

1. **Privacy first**: No spans leave the device unless the user explicitly exports. The ring buffer holds data in RAM; it is not written to disk in its span form (only histogram counts are persisted). This is appropriate for a note-taking app that may contain sensitive personal content.

2. **Bug report utility**: A 1000-span ring buffer covering the last ~10 minutes of app operation provides sufficient context to diagnose a reproduction. Captures the spike that caused jank without requiring the user to have a remote backend configured.

3. **Development ergonomics**: The stdout exporter with JSON output allows a developer to run `./gradlew run | grep 'span'` to see live traces during local development, or pipe to a local `docker run jaegertracing/all-in-one` instance with zero configuration.

4. **Capacity reasoning**: 1000 spans is approximately 10 minutes of activity at ~1.5 spans/second (a busy user navigating, typing, and searching). Beyond 1000, the oldest spans (startup, cold path) are the least relevant for diagnosing current jank.

---

## Ring Buffer Design

`RingBufferSpanExporter` implements OTel `SpanExporter`:

- Backed by a `ArrayDeque<SpanData>` with capacity 1000.
- Protected by a `kotlinx.coroutines.sync.Mutex` (not `java.util.concurrent` — KMP safe).
- `export(spans)`: acquires mutex, appends spans, drops oldest if `size > capacity`.
- `snapshot()`: acquires mutex, returns `List<SpanData>` copy (immutable snapshot for serialization).
- `flush()` and `shutdown()`: no-op (no I/O to flush).

---

## Consequences

**Positive**:
- Zero network I/O until user explicitly exports — no privacy concerns, no battery drain.
- `RingBufferSpanExporter` is testable in isolation (PERF-3.4 unit tests).
- stdout exporter is optional and debug-only — no production overhead.

**Negative**:
- The ring buffer is not persisted to disk. If the app crashes, span history is lost. Histogram data (persisted to SQLDelight) compensates by providing aggregate latency information even after a crash.
- 1000 spans at ~500 bytes each (estimated, including attributes) = ~500 KB RAM. This is acceptable for the debug-mode use case. The ring buffer is NOT initialized in release builds unless the user opts into the debug menu.
- Future remote OTel integration will require ADR revision and a privacy disclosure update (out of scope for Phase 1).

---

## Future Considerations

When a remote backend is added (Phase N), the architecture requires only adding a third exporter to `OtelProvider.initialize()` — no changes to instrumentation code. The ring buffer and stdout exporter remain available alongside the remote exporter.
