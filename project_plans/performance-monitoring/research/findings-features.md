# Findings: Features

## Summary

SteleKit needs an in-app performance monitoring solution with frame-time overlay, jank event capture, and bug report export. The Android ecosystem offers several mature patterns:

1. **System-level tools** (Perfetto, GPU Inspector) provide deep insight but require ADB/external tooling
2. **Jetpack libraries** (JankStats) offer platform integration but limited flexibility
3. **Debug-only overlays** (LeakCanary, Chucker pattern) show the non-intrusive model for debug features
4. **Compose-specific metrics** (Compiler Metrics, recomposition tracking) enable high-level performance visibility
5. **Custom frame overlay** via `Choreographer` is lightweight, KMP-compatible, and gives real-time feedback

**Recommendation**: Build a layered approach combining:
- Always-on lightweight metrics (DB load, search latency → SQLDelight histograms)
- Jetpack `JankStats` for frame jank detection (Android-only; disable in release builds)
- Custom `Choreographer`-based frame overlay (debug-only, toggleable)
- OpenTelemetry instrumentation at business logic boundaries (shared KMP code)
- Bug report builder that exports metrics + device context

## Options Surveyed

### 1. Android GPU Inspector / Perfetto (System-Level Tracing)

**What it captures:**
- Frame rendering timeline (CPU vs GPU work)
- Surface flinger events, memory allocations, system-wide thread scheduling
- Full render pipeline visibility

**How activated:**
- Requires ADB: `adb shell perfetto [options]` or via system UI (Android 12+)
- Not embedded in-app; binary `.perfetto-trace` file analyzed offline

**KMP/Compose compatibility:** Android only; no JVM equivalent; not suitable for in-app export

**Integration effort:** Very High — requires external tooling

**Notes:** Gold standard for "what went wrong at frame level"; not suitable for in-app overlay or one-tap export. Excellent for developer investigations; poor for automated monitoring.

---

### 2. Android `JankStats` (Jetpack Performance Monitoring API)

**What it captures:**
- Frame duration (ns precision)
- Jank classification: `JANK_NONE`, `JANK_CUD` (CPU/GPU/Deadline miss)
- Frame render time vs vsync period; callback fired per frame (real-time)

**How activated:**
- Register `OnFrameListener` via `JankStats.createAndTrack()`
- Lightweight: ~0.5% CPU overhead [TRAINING_ONLY — verify]
- Toggle via `isEnabled` flag; works in debug and release builds

**How it reports:** Synchronous callback per frame; no built-in UI overlay or export

**KMP/Compose compatibility:** Android-only (requires Jetpack); no Desktop/JVM equivalent

**Integration effort:** Low — Jetpack library, straightforward callback API

**Notes:** Built by Google for Jetpack Compose monitoring; no persistent storage — requires listener to capture history.

---

### 3. Compose Compiler Metrics (Recomposition Analysis)

**What it captures:**
- Number of recompositions per composable, reasons for recomposition
- "Skipped" frames (composable called but didn't emit)

**How activated:**
- Compiler flag in Gradle: `composeCompilerMetrics = file("build/metrics")`
- Runs at compile time; zero runtime overhead

**KMP/Compose compatibility:** Works on Android and Desktop (JVM Compose)

**Integration effort:** Very low setup; very high analysis effort — requires offline processing

**Notes:** Excellent for code review; not suitable for user-facing monitoring or bug reports. Build-time diagnostic, not runtime tool.

---

### 4. LeakCanary (Model for Non-Intrusive Debug Integration)

**Key lessons for SteleKit:**
1. **Debug-only activation**: Only runs in debug builds; can be toggled in settings
2. **Non-invasive**: Annotation-based or initialization-based; no code instrumentation
3. **One-tap export**: Built-in UI for sharing reports without custom dialogs
4. **Background processing**: Heavy lifting happens off-main-thread
5. **Persistent storage**: Retains history for later analysis

**Notes:** Perfect model for how to integrate debug tooling without prod overhead.

---

### 5. Chucker (OkHttp Inspector — Model for In-App Overlay Pattern)

**Key lessons for SteleKit:**
1. **In-app overlay pattern**: Toggleable persistent UI (not just a notification)
2. **Persistent local storage**: Logs stored in SQLite; queryable
3. **Export builder**: Structured export (JSON) with metadata
4. **Gesture-based activation**: Shake or debug menu entry
5. **Minimal production footprint**: Interceptor is no-op if disabled

**Notes:** Excellent template for "always-on logging with debug UI". Shows how to layer: core logic (capture) + UI (overlay) + export.

---

### 6. Firebase Performance Monitoring (Auto-Instrumented Traces)

**Key lessons for SteleKit:**
1. **Sampling strategy**: Not every frame needs capture; sample at rates (e.g., 1%, 10%)
2. **Custom trace boundaries**: Clear start/stop points around expensive operations
3. **Metadata attachment**: Traces can carry tags (device, session ID)

**Notes:** Too heavyweight for local-only architecture. Remote dependency conflicts with SteleKit's "no analytics server" constraint.

---

### 7. Sentry Performance (Transaction + Breadcrumb Model)

**Key lessons for SteleKit:**
1. **Breadcrumb model**: Store discrete user actions (not continuous metrics) for context
2. **Hierarchical spans**: Transactions contain child operations
3. **Device context**: Attach device info (memory, CPU, OS version) to reports
4. **Sampling + filtering**: Can sample transactions and filter on client before sending
5. **Human-readable export**: Breadcrumb timeline gives reproducible steps

**Notes:** Excellent reference for "how to structure a perf event with context". Remote dependency misaligned with local-only requirement.

---

### 8. Custom Frame Overlay (Choreographer-Based)

**What it captures:** Real-time FPS counter, frame rendering times, jank highlighting

**How activated:**
- Register callback with `Choreographer.getInstance()`; callback fires ~60 times/sec
- Debug-only; toggleable via debug menu; no external dependencies

**KMP/Compose compatibility:**
- Android-only (Choreographer is Android API)
- Desktop JVM: use `System.nanoTime()` polling (less accurate)

**Integration effort:** Low — ~50–100 lines of Kotlin for basic overlay

**Notes:** Proven pattern used in many Android performance tools. Good complement to JankStats. [TRAINING_ONLY — verify: Choreographer callback precision ~1 ms on modern Android]

---

## Trade-off Matrix

| Option | Depth of Insight | Integration Effort | Production Safety | KMP/Compose Compat | Notes |
|--------|------------------|--------------------|-------------------|-------------------|-------|
| GPU Inspector / Perfetto | Excellent | Very High | N/A (offline only) | Android only | Investigative only |
| JankStats | High (frame classification) | Low | High | Android only | Perfect for jank capture |
| Compose Compiler Metrics | Medium (recomposition) | Very Low setup | Zero runtime impact | Compose (Android + Desktop) | Build-time only |
| LeakCanary | N/A (memory) | Extremely Low | High | Android only | Model for debug integration |
| Chucker | Medium (network) | Low-Medium | High | Android only | Model for overlay + export |
| Firebase Performance | High | Low initial | Medium (cloud dep) | Android only | Good trace design reference |
| Sentry Performance | Excellent | Medium | Medium (cloud dep) | Android only | Breadcrumb model useful |
| Custom Frame Overlay | Medium (frame time) | Low | High (debug-only) | Android + Desktop (partial) | Simple, proven |

## Risk and Failure Modes

### 1. Over-instrumentation overhead
- **Risk**: Multiple layers exceed CPU/memory budget
- **Mitigation**: Profile each layer independently; use sampling; conditional compilation for heavy features

### 2. Jank detection false positives
- **Risk**: Confusing "legitimate frame miss" (GC pause, system scheduling) with "app bug"
- **Mitigation**: JankStats classifies jank reason (JANK_CUD); pair with breadcrumb context

### 3. SQLDelight histogram storage growth
- **Risk**: Unbounded metric accumulation bloating local database
- **Mitigation**: Retention policy (7 days); aggregate before storage; TTL cleanup

### 4. Frame overlay jank
- **Risk**: The overlay itself causes stuttering, masking actual perf problems
- **Mitigation**: Off-main-thread drawing; minimal allocations in Choreographer callback

### 5. KMP Desktop frame timing gap
- **Risk**: Custom frame overlay only works on Android; no vsync on JVM Desktop
- **Mitigation**: Desktop uses OTel spans + application-level timers instead

### 6. Export privacy/security
- **Risk**: Bug report may contain sensitive app data (note content)
- **Mitigation**: Explicit field selection with redaction; never auto-upload

## Migration and Adoption Cost

### Phase 1: Foundation (2–3 weeks)
- JankStats + `OnFrameListener` in debug builds
- Custom Choreographer frame overlay (Compose composable)
- SQLDelight `perf_metrics` table
- Debug menu toggle

### Phase 2: OTel integration (3–4 weeks)
- Instrument key boundaries: GraphLoader, SearchManager, Editor
- OTel → SQLDelight exporter

### Phase 3: Bug report builder (1–2 weeks)
- Export UI (metrics, device info, reproduction steps)
- JSON serialization (one-tap copy or file export)

## Operational Concerns

1. **Debug menu**: Toggleable without rebuild; KMP preferences backed by SharedPreferences (Android) / Preferences (Desktop)
2. **Histogram retention**: 7 days, 1 MB max, hourly aggregation; background cleanup job
3. **Export privacy**: Preview what's included; field-level redaction; clipboard/file only (no auto-submit)
4. **Cross-platform consistency**: Metrics named identically on Android and Desktop; frame timing N/A on Desktop (documented)

## Prior Art and Lessons Learned

### From LeakCanary
- Debug-only activation is standard; non-invasive; one-tap export with context; background processing

### From Chucker
- In-app overlay pattern; persistent local storage; structured export; conditional activation

### From Firebase Performance / Sentry
- Transaction + span hierarchy; device context attachment; sampling strategy; breadcrumb timeline

### From JankStats
- Frame classification; lightweight callback (nanosecond precision); tight Compose integration

## Open Questions

- [ ] Which OpenTelemetry KMP library should SteleKit use? — blocks OTel instrumentation design
- [ ] Can OTel traces be efficiently exported to SQLDelight? — blocks architecture design
- [ ] What is CPU/memory overhead of JankStats + frame callback in practice on SteleKit workloads?
- [ ] How to handle frame timing on Desktop (JVM)? — blocks cross-platform design
- [ ] What's the APK size impact of JankStats + OTel SDK?
- [ ] Should histogram storage use a separate SQLDelight database?

## Recommendation

**Adopt a layered approach combining proven patterns from LeakCanary, Chucker, JankStats, and custom frame overlay:**

### Layer 1: Always-on lightweight metrics (production-safe)
- Business logic timing via OpenTelemetry (shared KMP code)
- Store histograms in SQLDelight with TTL cleanup
- Reference: Firebase sampling strategy, Sentry breadcrumb model

### Layer 2: Android-specific jank detection (debug builds only)
- JankStats for frame jank classification
- Custom Choreographer callback for real-time FPS overlay (toggleable in debug menu)
- Reference: LeakCanary debug-only pattern

### Layer 3: Bug report builder (one-tap export)
- Export perf metrics + device context + reproduction steps as JSON
- User previews and can redact before export
- Reference: Chucker export builder

### Layer 4: Desktop support
- Same OTel instrumentation (KMP-portable)
- Frame overlay N/A; document limitation

### Avoid
- Remote APM backend (conflicts with local-only requirement)
- Perfetto/GPU Inspector integration (external tooling, not in-app)
- Compose Compiler Metrics in production (compile-time only)

## Pending Web Searches

1. `"opentelemetry kotlin multiplatform KMP 2024 2025"` — identify which OTel libraries work on KMP
2. `"android jankstats overhead performance metrics 2024"` — verify CPU/memory/battery impact
3. `"compose frame timing choreographer desktop jvm alternative"` — find Desktop equivalents
4. `"kotlin multiplatform debug menu settings preferences shared"` — best pattern for cross-platform DebugSettings
5. `"sqldelight time series metrics storage performance histograms"` — verify suitability + aggregation patterns
