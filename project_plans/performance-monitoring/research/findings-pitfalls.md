# Findings: Pitfalls

## Summary

Adding always-on performance instrumentation to SteleKit introduces eight categories of risk: binary bloat from the OpenTelemetry Android SDK (5–15 MB), database write amplification from continuous histogram storage, false-positive recomposition counts from instrumentation itself, frame callback overhead and missed metrics due to threading and Choreographer scheduling, fragile coroutine context propagation in structured concurrency, measurement observer effect changing the code being measured, R8/ProGuard minification breaking OTel reflection-based initialization, and accidental coupling between platform-specific code and shared KMP source sets. All are mitigable with proper architecture and testing, but require explicit design and discipline to prevent production impact.

**Recommendation**: Pursue a hybrid approach — always-on lightweight histograms + opt-in OTel SDK. Address the four highest-risk items (R8 breakage, KMP source set leakage, context loss, observer effect) before Phase 2 OTel integration.

## Options Surveyed

1. **No instrumentation** — Zero overhead, smallest binary. Loses systematic visibility.
2. **Always-on lightweight histograms only** — SQLDelight writes only. Moderate overhead, small binary, limited insight.
3. **Always-on histograms + debug-only frame callback** — Histograms always-on; frame overlay debug-only. Moderate overhead, larger binary (+OTel SDK), production-safe.
4. **Always-on histograms + OTel SDK (debug exporters)** — Largest binary, more runtime cost, most instrumentation power.
5. **Hybrid: always-on lightweight histograms + opt-in OTel** — Histograms always run at low cost; OTel spans only when enabled via debug menu. **Best balance.**

## Trade-off Matrix

| Risk Area | Likelihood | Severity | Mitigation Available | Notes |
|-----------|-----------|----------|---------------------|-------|
| OTel SDK binary size (5–15 MB) | High | Moderate | R8 rules, selective inclusion | Biggest unknown; depends on which exporters included |
| SQLDelight write contention | Medium | Moderate | WAL mode, async batching | WAL mitigates but adds complexity |
| Compose recomposition false positives | High | Minor | Separate measurement flag | RecompositionCounter itself causes extra recompositions |
| Frame callback dropped frames | Medium | Moderate | Fallback detection, dual-path | Choreographer drops callbacks under high jank |
| Coroutine context loss | Medium | High | OTel context hooks, ContextElements | Tricky at Dispatchers.IO and external library boundaries |
| Measurement observer effect | Medium | High | Inline probes, controlled experiments | Hard to detect; instrumentation cost can hide bottlenecks |
| ProGuard/R8 reflection breakage | Medium | High | Explicit keep rules, build-time validation | OTel uses ServiceLoader and reflection |
| KMP source set leakage | High | High | Strict lint rules, integration tests | Easy to import `android.os.Handler` in commonMain |

## Risk and Failure Modes

### 1. OTel SDK Binary Size

**Failure Mode**: Adding the full OpenTelemetry Android SDK (with OTLP exporter) increases APK by 5–15 MB after obfuscation, pushing install size over carrier/Play Store limits or triggering user complaints.

**Why It Happens**:
- OTel Android SDK includes `opentelemetry-android-agent`, `opentelemetry-exporter-otlp`, and transitive deps (protobuf, gRPC, OkHttp)
- Multiple exporter implementations bundled even if only one is used
- ProGuard/R8 shrinking is not perfectly aggressive on library code that uses reflection/SPI
- No standard "lite" distribution; must manually exclude unwanted dependencies

**Likelihood**: High | **Severity**: Moderate

**Mitigations**:
- Use `opentelemetry-android` SDK (not the full `-agent` variant); manually compose only needed modules
- Exclude OTLP exporter if only using file/stdout export
- Apply aggressive R8 rules: explicitly keep OTel interfaces, shrink transitive deps
- Measure APK delta in CI on every change; fail build if delta > 2 MB
- Defer full OTel to debug builds; release flavor uses lightweight histogram-only code paths

**Evidence** [TRAINING_ONLY — verify]:
- OpenTelemetry Android SDK full package ~8–12 MB pre-obfuscation
- OTLP exporter + gRPC adds ~5 MB; proper R8 shrinking can reduce to ~3–4 MB

---

### 2. Always-On SQLDelight Write Amplification

**Failure Mode**: Writing histogram records on every navigation, edit, and database load event overwhelms the SQLite WAL, causing main thread contention, query timeouts, UI freezes (especially on low-end devices). Over time, the metrics table grows unbounded, slowing schema migrations.

**Why It Happens**:
- SteleKit has frequent edits, searches, and graph traversals — potentially 100+ writes/hour
- SQLite in WAL mode allows concurrent reads but serializes writes; each histogram write takes ~1–5 ms including fsync
- SQLDelight generates synchronous extension methods that are easy to call from UI code, encouraging blocking writes
- No automatic table cleanup; metrics grow until explicit deletion

**Likelihood**: Medium to High | **Severity**: Moderate

**Mitigations**:
- **Batch writes**: Collect histogram events in a coroutine-safe queue; flush every 5–10 seconds on `Dispatchers.IO`
- **Async insertion**: Never insert on `Dispatchers.Main`
- **Bounded table**: Keep last 7 days; use periodic DELETE job
- **Sampling**: Only write some events (1 in 10 searches) if write pressure is extreme

```kotlin
// WRONG: Blocks UI thread
fun recordHistogram(name: String, millis: Long) {
    database.histogramQueries.insert(name, millis)
}

// GOOD: Non-blocking via channel
private val histogramQueue = Channel<Pair<String, Long>>(capacity = 100)

init {
    viewModelScope.launch(Dispatchers.IO) {
        for ((name, millis) in histogramQueue) {
            database.histogramQueries.insert(name, millis)
        }
    }
}

fun recordHistogram(name: String, millis: Long) {
    histogramQueue.trySend(Pair(name, millis))
}
```

---

### 3. Compose Recomposition Tracking False Positives

**Failure Mode**: Using `RecompositionCounter` or Compose Compiler metrics causes those measurements themselves to trigger additional recompositions, skewing data. Developers misinterpret as real regressions and optimize the wrong thing.

**Why It Happens**:
- `RecompositionCounter` reads state internally; reading state in Compose causes the measuring composable to recompose when state changes
- Compiler metrics collection modifies all composables to emit telemetry calls
- No built-in way to distinguish "recompositions from real state changes" from "recompositions from measurement infrastructure"

**Likelihood**: High | **Severity**: Minor to Moderate

**Mitigations**:
- Run recomposition counting in a separate debug-only APK variant, not in main builds
- Use **compiler metrics file output** (build-time), not runtime counters
- Focus on relative trends, not absolute counts (detect changes between commits)
- Prefer explicit state dependency tracking over generic recomposition counters

**Evidence** [TRAINING_ONLY — verify]:
- Compose Compiler metrics can increase recomposition count by 5–15% due to instrumentation probes

---

### 4. Frame Callback Dropped Metrics and Threading Hazards

**Failure Mode**: Choreographer `FrameCallback` registration fails silently from background threads, callbacks are dropped when frame queue fills under jank, and heavy work in the callback delays the next frame. Battery drain increases from high-frequency polling.

**Why It Happens**:
- `Choreographer` is thread-local and main-thread-only; registering from a background thread silently fails
- When the main thread is overloaded, the Choreographer frame queue can drop pending callbacks with no notification
- Frame callback overhead itself contributes to jank if the callback does heavy work
- Choreographer fires 60 Hz; high-frequency callbacks can prevent idle CPU states

**Likelihood**: Medium | **Severity**: Moderate

**Mitigations**:
- Always register from `Handler(Looper.getMainLooper())`; check `Looper.getMainLooper().isCurrentThread()` first
- Use `WeakReference` to avoid memory leaks; null-check before use
- Offload all heavy work from the callback to a background coroutine
- Supplement with periodic (1 Hz) battery-safe fallback detection

```kotlin
// WRONG: Callback registered from wrong thread or heavy work inline
fun startFrameCapture() {
    Choreographer.getInstance().postFrameCallback { frameNanos ->
        processFrameData(frameNanos)  // Heavy! Blocks next frame
    }
}

// GOOD: Register on main thread; offload processing
fun startFrameCapture() {
    Handler(Looper.getMainLooper()).post {
        Choreographer.getInstance().postFrameCallback { frameNanos ->
            frameBuffer.offer(frameNanos)  // Only record; process elsewhere
        }
    }
}
```

---

### 5. Coroutine Context Propagation and Structured Concurrency Gaps

**Failure Mode**: OpenTelemetry context (current span, baggage) is lost when coroutines cross dispatcher boundaries (Main → IO → CPU), especially with `GlobalScope` or external thread launches. Trace spans become disconnected; parent-child relationships are wrong.

**Why It Happens**:
- OTel maintains thread-local context via `ContextStorage`; when a coroutine suspends and resumes on a different thread, the context is not automatically transferred
- `Dispatchers.IO` pools threads aggressively; a coroutine may resume on a completely different thread
- External libraries (SQLDelight) often launch queries on background threads without OTel context awareness

**Likelihood**: Medium | **Severity**: High

**Mitigations**:
- Wrap `withContext(Dispatchers.IO)` with OTel context propagation utilities
- Include OTel context as a `CoroutineContext.Element` in the coroutine scope
- Explicitly create and pass child spans through function parameters rather than relying on thread-local context
- Test cross-dispatcher span propagation explicitly in unit tests

```kotlin
// WRONG: Context lost across dispatcher
tracer.startSpan("load_data").use { span ->
    withContext(Dispatchers.IO) {
        database.load()  // Context is lost here
    }
}

// GOOD: Explicitly manage context
tracer.startSpan("load_data").use { span ->
    withContext(Dispatchers.IO + span.asContext()) {
        database.load()
    }
}
```

---

### 6. Measurement Observer Effect (Heisenberg Problem)

**Failure Mode**: The act of instrumenting code changes its behavior in ways that hide the original problem. For example, adding a timing probe to a tight loop adds CPU overhead that prevents it from being CPU-bound; the bottleneck appears to be I/O when it was actually computation.

**Why It Happens**:
- Each `System.nanoTime()` call adds ~10–100 ns overhead
- Allocating span objects triggers garbage collection, changing latency distribution
- Lock contention around shared histogram buffers can artifactually slow measured code
- In tight loops, instrumentation changes CPU cache footprint, affecting branch prediction

**Likelihood**: Medium | **Severity**: High

**Mitigations**:
- Measure instrumentation cost: benchmark with and without; quantify delta
- Use sampling (1 in 100 events) to reduce observer effect
- Verify fixes by running before/after benchmarks in randomized order
- Use profiler tools (Perfetto, Android Studio Profiler) as a cross-check — they hook into the runtime without source code instrumentation

---

### 7. ProGuard/R8 Minification Breaking OTel Initialization

**Failure Mode**: After R8 obfuscation, OpenTelemetry SDK fails to initialize because it uses reflection and ServiceLoader (SPI) to discover exporters and context propagators at runtime. R8 removes "unused" code including OTel SPI metadata files in `/META-INF/services/`. Instrumentation silently stops working in release builds.

**Why It Happens**:
- OTel SDKs use Java ServiceLoader to discover implementations at runtime
- ServiceLoader requires `/META-INF/services/…` metadata files to be present
- R8 deletes metadata files or moves classes that SPI references, breaking the link
- OTel classes often not directly referenced in app code → R8 marks them as unused

**Likelihood**: Medium to High | **Severity**: High

**Mitigations**:
```
# proguard-rules.pro
-keep class io.opentelemetry.** { *; }
-keep interface io.opentelemetry.** { *; }
-keepclasseswithmembernames class io.opentelemetry.** { *; }
-keepresourcexmembers class /^META-INF\/services\/.*/
```
- In CI, unzip the release APK and verify OTel classes and ServiceLoader metadata files are present
- Build and run instrumentation tests against the release APK (with R8 applied), not just debug
- Implement a manual initialization path that doesn't rely on SPI as a fallback

---

### 8. KMP Source Set Leakage (Android-Only Code in commonMain)

**Failure Mode**: A developer accidentally imports an Android-specific class (e.g., `android.os.Handler`, `Context`, `Choreographer`) into `commonMain` code. Code compiles fine on Android but fails to compile on JVM. If CI only tests Android, this slips into main and breaks the Desktop build.

**Why It Happens**:
- Gradle allows direct imports from any source set; no compile-time check that commonMain uses only KMP-safe APIs
- IDEs don't always highlight this error immediately when indexing Android first
- Copy-paste from `androidMain` examples makes the mistake easy
- OTel modules (e.g., `opentelemetry-android`) are Android-specific; importing in common code pulls in Android dependencies

**Likelihood**: High | **Severity**: High

**Mitigations**:
- Add a Gradle lint rule or custom lint plugin to prevent imports of `android.*` in `commonMain` source files
- Put OTel Android-specific code (Choreographer, frame callbacks) in `androidMain` only; expose via platform-agnostic expect/actual interface
- CI **must** build and test both Android (`androidMain`) and JVM (`jvmMain`) targets
- Use `expect/actual` declarations to make platform-specific APIs explicit:

```kotlin
// commonMain
expect fun recordFrameTime(millis: Long)

// androidMain
actual fun recordFrameTime(millis: Long) {
    choreographer.postFrameCallback { ... }
}

// jvmMain
actual fun recordFrameTime(millis: Long) {
    // JVM implementation using nanoTime polling
}
```

## Migration and Adoption Cost

1. **Initial instrumentation setup** (~3–5 days):
   - KMP modules for `commonHistogram`, `androidFrameCallback`, `androidOTel`
   - OTel SDK integration; R8 rules configured; binary size measured
   - SQLDelight schema for metrics table
   - Debug menu toggle for frame overlay

2. **Integration with existing code** (~2–3 days):
   - Identify instrumentation points in `GraphManager`, `GraphLoader`, navigation
   - Add span creation around key operations
   - Test OTel context propagation across dispatcher boundaries
   - Verify no source set leakage with JVM build

3. **Testing and validation** (~2–3 days):
   - Unit tests for frame callback thread safety
   - APK size checks integrated into CI
   - Benchmark recomposition overhead; SQLDelight write latency under load
   - Test release build with R8 applied

4. **Documentation** (~1 day)

**Adoption risk**: Moderate. Well-architected instrumentation has low ongoing maintenance. Ad-hoc instrumentation accretes technical debt quickly.

## Operational Concerns

1. **Storage and retention**: Histogram data grows unbounded without cleanup. Implement 7-day retention from day one. Without cleanup, the metrics table will consume significant storage on user devices.

2. **Performance regression feedback loop**: If frame callback overhead is high, it triggers more jank → more investigation → more measurement → more overhead. Mitigate with strict overhead budgets.

3. **OTel SDK updates**: OTel releases major versions 2–3x/year. Update carefully; test updates in CI before deploying.

4. **Export and privacy**: If OTel exporters are enabled, ensure they don't leak user data (SQL queries with block content, sensitive properties). Frame callback data is less sensitive.

5. **CI cost**: Instrumentation slightly increases build time and test duration, especially on JVM/Android matrix builds.

## Prior Art and Lessons Learned

### From Android Community

- **LeakCanary**: Always-on, non-blocking memory profiler — keep checks on background thread; only report high-confidence findings
- **Perfetto / Android GPU Inspector**: Frame-time overlays must be toggle-able; GPU Inspector uses frame pacing data (more reliable than Choreographer callbacks alone)
- **Firebase Performance**: Hybrid — always-on lightweight metrics + opt-in detailed traces; ~3–5 MB APK penalty; explicit R8 rules required
- **DataDog, New Relic, Sentry**: All maintain OTel SDKs with published R8 rules; common pattern — core SDK lean, exporters optional

### From Compose Community

- **Compose Compiler metrics**: Available free, but only in debug builds; production recomposition tracking not recommended
- **RecompositionCounter**: Diagnostic tool, not production metric; overhead is non-negligible
- **Best practices**: Focus on `CompositionLocal` for dependency injection; avoid direct state reads in hot paths

### From KMP Community

- **Ktor client**: Demonstrates ContextElement for request context propagation across dispatchers
- **Kotlinx.serialization**: Explicit keep rules + SerializersModule registration avoids R8 reflection issues
- **Koin DI**: Had SPI discovery issues on Android when minified; fixed in 3.3+ with module-based initialization (no SPI)

## Open Questions

- [ ] What is the actual APK size impact of `opentelemetry-android` + lightweight file exporter on SteleKit? (Need to measure in CI)
- [ ] How much does always-on histogram writing slow down typical user interactions? (Need micro-benchmarks with real write batching strategy)
- [ ] Can Compose Compiler metrics (build-time) replace runtime `RecompositionCounter`? (Avoids observer effect; requires separate CI job)
- [ ] Which OTel exporters are actually needed for Phase 1? (Drives binary size decision)
- [ ] Can we use `context.lineageTracking` or other zero-cost patterns to avoid R8 issues? (Probably not — SPI unavoidable in OTel)
- [ ] What is the best way to test that frame callbacks are not dropped under jank? (Simulate jank programmatically; verify callback count)

## Recommendation

**Pursue Option 5: Hybrid approach — always-on lightweight histograms + opt-in OTel.**

**Rationale:**
- Always-on histograms (SQLDelight writes) provide immediate value with manageable overhead when batched and async
- OTel SDK in debug-only or opt-in mode keeps binary size acceptable and avoids observer effect in production
- Frame callbacks debug-only mitigates threading and overhead risks
- Separate `androidMain` modules for platform-specific code prevents KMP leakage

**Phased rollout:**
1. **Phase 1 (MVP)**: Always-on histograms + debug menu toggles for frame overlay; no OTel yet
2. **Phase 2**: Add OTel SDK; validate APK size and R8 rules in CI; offer opt-in exporter
3. **Phase 3**: Recomposition tracking (if needed); measure overhead carefully

**Key guardrails:**
- APK size check: fail build if release APK grows by > 2 MB
- Latency budget: histogram writes must not add > 1 ms to hot paths (verify in micro-benchmarks)
- Thread safety tests: explicit tests for frame callback threading and context propagation
- CI validation: build both Android and JVM targets; test release build with R8

**Risk priority order (address before Phase 2):**
1. KMP source set leakage — easy to prevent, high impact if missed
2. R8/ProGuard breakage — add keep rules from day one
3. Coroutine context loss — establish propagation pattern early; hard to retrofit
4. Observer effect — budget instrumentation overhead; don't measure without measuring the measurement

## Pending Web Searches

1. `"opentelemetry android SDK APK size impact 2024 2025"` — actual binary bloat from different exporter combinations
2. `"sqlite write-ahead log WAL mode android kotlin performance concurrent writes"` — verify WAL effectiveness under high write load
3. `"compose recomposition counter instrumentation overhead kotlin 2024"` — quantify false-positive rate; production viability
4. `"choreographer framecallback dropped frames jank detection android"` — confirm drop behavior; fallback detection strategies
5. `"opentelemetry kotlin coroutines context propagation dispatchers.io 2024"` — best practices for context preservation across dispatchers
6. `"proguard r8 rules opentelemetry spi serviceloader android 2024"` — known good R8 configurations for OTel
7. `"kmp kotlin multiplatform lint rules prevent android imports commonMain"` — available lint plugins for KMP separation
8. `"jetpack compose frame pacing api alternative choreographer jank detection 2024"` — newer frame pacing APIs vs Choreographer
