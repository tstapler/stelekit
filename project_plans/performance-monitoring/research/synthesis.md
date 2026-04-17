# Research Synthesis: Performance Monitoring & Management

**Date**: 2026-04-13
**Input files**: findings-stack.md, findings-features.md, findings-architecture.md, findings-pitfalls.md
**Web searches completed**: 2026-04-13

---

## Decision Required

Choose an observability stack and instrumentation architecture that lets us detect, measure, and report performance problems in SteleKit (Android + Desktop JVM) using KMP-compatible code.

---

## Context

SteleKit users experience stuttering on Android during: scrolling long pages, navigating between pages, typing/editing, startup, and block-reference search. Currently there is zero instrumentation. We need a system that can:
1. Detect jank events with enough context to reproduce and fix them
2. Surface frame-time and operation-timing metrics during development
3. Package findings into one-tap bug reports
4. Store timing histograms persistently for trend analysis

All of this must work on Android and Desktop (JVM) from shared KMP code, with no remote analytics server.

---

## Critical Discovery: Official OTel KMP SDK Now Exists

A major finding from web search changes the stack recommendation significantly:

**March 24, 2026 — CNCF announced `open-telemetry/opentelemetry-kotlin`**, an official KMP implementation of the OpenTelemetry specification donated by Embrace. It supports Android (API ≥ 21), JVM (JDK ≥ 8), iOS, and JavaScript. Version 0.2.0 is available on Maven.

Key capabilities:
- Full OTel API: context propagation, span creation, metric instruments (counters, histograms, gauges), log bridge API
- **Native Kotlin coroutine integration**: propagates trace context through structured concurrency without manual span passing
- Two modes: **Regular** (pure KMP implementation) and **Compatibility** (delegates to opentelemetry-java)
- Exporters: OTLP and in-memory; file exporter needs to be built or contributed

This eliminates the previous uncertainty about OTel KMP support and makes it the clear foundation choice.

---

## Options Considered

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| **A: `opentelemetry-kotlin` (official KMP OTel)** | Official OTel SDK targeting Android + JVM + iOS + JS; coroutines-native; v0.2.0 | New/young SDK (~weeks old); unknown binary footprint on Android; community contributions welcome |
| **B: `opentelemetry-android` (Android-only OTel agent)** | Mature Android auto-instrumentation agent; comprehensive | Android-only breaks KMP unity; commonMain code cannot use it |
| **C: `JankStats` (Jetpack)** | Frame jank classification (JANK_CUD); zero allocation per frame; Android-only | Android-only; no Desktop equivalent; no persistent storage; needs pairing with storage layer |
| **D: Manual instrumentation only** | `System.nanoTime()` + SQLDelight histogram table; no third-party OTel SDK | No standard trace format; no coroutine propagation; custom everything; zero binary size impact |
| **E: Firebase Performance / Sentry** | Auto-instrumented traces with cloud backend | Remote dependency conflicts with local-only requirement; Android-only |
| **F: Macrobenchmark (Jetpack)** | CI-grade benchmarks using `FrameTimingMetric`; standardized jank measurement | CI/offline only; not for in-app monitoring; not a runtime overlay |

---

## Dominant Trade-off

**SDK maturity vs. KMP unity.**

The mature option (`opentelemetry-android`) is Android-only and breaks KMP. The KMP-native option (`opentelemetry-kotlin`) is brand-new (March 2026, v0.2.0), meaning it may have rough edges, missing exporters, and API instability. The alternative (manual instrumentation) avoids both risks but gives up OTel standards.

**We land on the KMP-native OTel SDK side of this tension**, because:
- The SDK is officially maintained by CNCF, not a one-person community fork
- Embrace donated a battle-tested implementation built for mobile
- Coroutine-native context propagation solves our hardest architecture problem without manual work
- If the SDK turns out to be too immature, our decorator/histogram pattern can run alongside it with no coupling — we don't lose the always-on layer

---

## Recommendation

**Choose: `opentelemetry-kotlin` (official KMP OTel SDK, Regular Mode) + JankStats + SQLDelight histograms + in-memory ring buffer exporter**

### Architecture

```
Shared KMP Layer (commonMain)
  opentelemetry-kotlin API
    → Decorator pattern on Repository interfaces (zero domain coupling)
    → Coroutine context propagation via native OTel KMP integration
    → SQLDelight bucketed histogram table (0, 16, 33, 50, 100, 500, 1000, 5000 ms)
    → In-memory ring buffer exporter (last 1000 spans → bug report snapshot)
    → OTLP/stdout exporter (development; pipe to file)

Android-specific (androidMain)
  JankStats (zero allocation/frame; FrameMetrics API Android 7+)
    → Jank classification (JANK_CUD) logged to histogram table
  Choreographer frame overlay (debug-only; toggleable via debug menu)
  Expect/actual DebugMenuOverlay (Android: Material Dialog)

Desktop-specific (jvmMain)
  Expect/actual DebugMenuOverlay (Compose window)
  Application-level timer spans (no Choreographer equivalent)

Bug Report Builder (shared)
  Snapshot: ring buffer traces + histogram P50/P95/P99 + device info
  Export: JSON to clipboard or file (no auto-upload)

CI (future phase)
  Jetpack Macrobenchmark + FrameTimingMetric
  Fail build if P95 frame time regresses > threshold
```

### Implementation Phases

| Phase | What | Duration |
|-------|------|---------|
| **1 — Baseline** | SQLDelight histogram table; async Channel-based batch writes; always-on timing for GraphLoader, navigation, search | 3–5 days |
| **2 — OTel + JankStats** | Add `opentelemetry-kotlin`; decorator pattern on repositories; JankStats jank capture; coroutine context propagation; OTLP/stdout exporter | 4–6 days |
| **3 — UX** | Debug menu overlay (expect/actual); in-memory ring buffer; bug report builder (JSON export) | 3–4 days |
| **4 — CI** | Macrobenchmark + FrameTimingMetric; PR gate on P95 frame regression | 3–5 days |

---

## Accept These Costs

- **Binary size unknown**: `opentelemetry-kotlin` v0.2.0 has no published APK size data. Must measure empirically in CI before Phase 2 ships. Add CI gate: fail if release APK grows > 2 MB.
- **SDK API instability**: v0.2.0 will have breaking changes. Pin to exact version; audit changelog on each update. No auto-upgrade.
- **No file exporter yet**: Official OTel KMP file/OTLP exporter may not exist in v0.2.0. Will need to implement `SpanExporter` backed by OTLP/stdout or file in Phase 2. This is a known, bounded task.
- **Desktop has no frame-level timing**: No Choreographer on JVM Desktop. Desktop relies on OTel span durations for latency data, not vsync-based frame timing. Document this limitation.
- **JankStats is Android-only**: This is acceptable because the problem (phone stuttering) is Android-specific. Desktop gets equivalent insight from OTel spans.

---

## Reject These Alternatives

- **`opentelemetry-android`**: Rejected because it is Android-only. Importing it in commonMain breaks the Desktop/JVM build (KMP source set leakage). The official KMP SDK covers the same ground with shared code.
- **Firebase Performance / Sentry**: Rejected because both require a remote backend. SteleKit's Phase 1 requirement is explicitly "no data leaves the device automatically." Both are also Android-only.
- **Manual instrumentation only (no OTel)**: Rejected because we'd lose OTel's standard trace format, coroutine context propagation, and compatibility with future collector tooling. The overhead of the OTel KMP SDK is likely small; we can verify empirically. The upside of standardization is high.
- **Macrobenchmark as primary runtime monitor**: Rejected for runtime/in-app use — it is a CI/offline tool. It is the right choice for Phase 4 CI benchmarks only.

---

## Key Verified Findings (from Web Searches)

| Finding | Status | Source |
|---------|--------|--------|
| Official OTel KMP SDK exists (`open-telemetry/opentelemetry-kotlin`) | **Confirmed** | CNCF announcement March 2026 |
| SDK supports Android + JVM + iOS + JS | **Confirmed** | GitHub repo; opentelemetry.io |
| Native Kotlin coroutine context propagation | **Confirmed** | WebProNews; opentelemetry-extension-kotlin jar |
| JankStats: zero allocations per frame | **Confirmed** | Android Developers blog |
| JankStats: works Android 7+ (FrameMetrics) | **Confirmed** | Android Developers docs |
| R8 has built-in ServiceLoader support | **Confirmed** | GitHub kotlin/kotlinx.coroutines #3111; AGP blog |
| Compose scroll jank dropped to 0.2% in Compose 1.9 | **Confirmed** | Android Developers Blog Dec 2025 |
| Macrobenchmark `FrameTimingMetric` for CI jank measurement | **Confirmed** | Android Developer codelabs |
| OTel APK binary size for KMP SDK | **Unknown** — SDK too new; must measure empirically | — |

---

## Open Questions Before Committing

- [ ] What is the actual APK size delta of `opentelemetry-kotlin` v0.2.0 on SteleKit's release build? — blocks go/no-go on including OTel SDK in production builds
- [ ] Does `opentelemetry-kotlin` v0.2.0 ship a working OTLP file exporter, or must we implement one? — determines Phase 2 implementation scope
- [ ] Does the SDK's coroutine propagation work without the Java SDK (Regular Mode), or does it require the Compatibility Mode on Android/JVM? — determines build complexity
- [ ] Are there R8 keep rules published by `open-telemetry/opentelemetry-kotlin` yet? — determines if we need to write them from scratch

These questions are answerable by spiking `opentelemetry-kotlin` in a branch before committing to Phase 2.

---

## Sources

- [open-telemetry/opentelemetry-kotlin — GitHub](https://github.com/open-telemetry/opentelemetry-kotlin)
- [Announcing a Kotlin Multiplatform API and SDK for OpenTelemetry — CNCF](https://www.cncf.io/blog/2026/03/24/announcing-a-kotlin-multiplatform-api-and-sdk-for-opentelemetry/)
- [New OpenTelemetry Kotlin SDK — opentelemetry.io](https://opentelemetry.io/blog/2026/kotlin-multiplatform-opentelemetry/)
- [JankStats Library — Android Developers](https://developer.android.com/topic/performance/jankstats)
- [JankStats Goes Alpha — Chet Haase, Android Developers Medium](https://medium.com/androiddevelopers/jankstats-goes-alpha-8aff942255d5)
- [Configure and troubleshoot R8 Keep Rules — Android Developers Blog](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html)
- [Kotlin Coroutines and OpenTelemetry Tracing — bytegoblin.io](https://bytegoblin.io/blog/kotlin-coroutines-and-opentelemetry-tracing.mdx)
- [Deeper Performance Considerations — Android Developers Blog](https://android-developers.googleblog.com/2025/11/deeper-performance-considerations.html)
- [What's new in Jetpack Compose December '25 — Android Developers Blog](https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html)
- [Inspect app performance with Macrobenchmark — Android Developers](https://developer.android.com/codelabs/android-macrobenchmark-inspect)

### Findings Files
- [findings-stack.md](findings-stack.md)
- [findings-features.md](findings-features.md)
- [findings-architecture.md](findings-architecture.md)
- [findings-pitfalls.md](findings-pitfalls.md)
