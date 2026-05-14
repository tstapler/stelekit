# Exploratory Testing Guide

This document covers how to detect UI jank and performance problems before users do.
The tooling here is layered — start with the in-app overlays for quick feedback during
development, then use the profiling tools to diagnose root causes.

---

## Quick reference

| Signal | Platform | How to activate |
|--------|----------|-----------------|
| Frame time overlay (ms + JANK badge) | Android + Desktop | Debug Menu → Frame Overlay |
| JankStats frame counter | Android | Debug Menu → JankStats |
| gfxinfo session (p50/p95/p99 + jank %) | Android | `scripts/gfxinfo-session.sh` |
| Skia FPS counter (native renderer) | Desktop | `./gradlew :kmp:run -PfpsOverlay` |
| JFR + async-profiler (always-on) | Desktop | `./gradlew :kmp:run` (automatic) |
| Compose recomposition report | Android + Desktop | `-PrecompositionReport` |
| Phase TTI regression tests | All | `./gradlew :kmp:jvmTest` |
| SLO violation alerts | All | Automatic (30s warm-up, 60s interval) |

---

## 1. In-app frame time overlay

The quickest way to spot jank during a manual session. The overlay shows the last frame
duration in milliseconds and flashes a **JANK** badge when a frame takes more than 32ms
(i.e. misses the 30 fps budget on a 60 Hz display, or the effective 2× refresh period).

**How to enable:**
- On Android and Desktop: open the Debug Menu (long-press the header, or Developer menu
  on Desktop → Debug Menu) and toggle **Frame Overlay**
- The toggle persists across restarts via `DebugFlagRepository`

**What to look for:**
- Sustained frame times > 16ms → approaching 60 fps limit
- Any frame > 32ms → classified as jank by the JANK_CUD heuristic
- Spikes during typing → possible recomposition or debounce issue
- Spikes during navigation → page load or layout cost

**Implementation:** `PlatformFrameTimeOverlay` (JVM: driven by `withFrameNanos` loop;
Android: driven by `JankStatsManager`). Source: `performance/PlatformJankStatsEffect.*`.

---

## 2. Android JankStats

`androidx.metrics.performance.JankStats` hooks into the window's `FrameMetrics` API and
classifies each frame using the JANK_CUD heuristic (janky if duration > 2× refresh
period, e.g. > 32ms on a 60 Hz device or > 22ms on a 90 Hz device).

**How to enable:** Debug Menu → **JankStats**

**What it does:**
- Records every frame duration to `HistogramWriter` under `frame_duration`
- Emits an OpenTelemetry `jank_frame` span with `frame.duration_ms` and `frame.state`
  attributes for each janky frame — these appear in the Spans tab under OTel Stdout
- Exposes `JankStatsManager.frameMetric: StateFlow<FrameMetric>` for the overlay

**Implementation:** `androidMain/performance/JankStatsManager.kt`

---

## 3. Automated Android frame stats (`scripts/gfxinfo-session.sh`)

`dumpsys gfxinfo` collects frame timing at the OS level — independent of the app — and
gives p50/p90/p95/p99 percentiles plus a janky frame count for a whole session.

```bash
./scripts/gfxinfo-session.sh
# or with explicit package:
./scripts/gfxinfo-session.sh dev.stapler.stelekit.debug
```

The script:
1. Resets counters at the start of the session
2. Presents the exploratory testing checklist (see §7)
3. Dumps stats after you press Enter and shows a parsed summary
4. Saves the raw output to `kmp/build/reports/gfxinfo/gfxinfo-<timestamp>.txt`

**Interpreting results:**
- Jank rate < 5%: normal
- Jank rate 5–10%: investigate during next profiling session
- Jank rate ≥ 10%: actionable — profile before shipping

---

## 4. Desktop Skia FPS overlay (`-PfpsOverlay`)

The Skia renderer exposes a native FPS counter that measures throughput at the GPU
pipeline level, below Compose's `withFrameNanos`. This catches rendering bottlenecks
that the Compose-level overlay misses (e.g. GPU overdraw, texture upload stalls).

```bash
./gradlew :kmp:run -PfpsOverlay
```

The overlay appears as a small FPS counter in the window chrome. There is no frame-time
breakdown — pair with the in-app Frame Overlay for per-frame timing detail.

**What to look for:**
- FPS consistently < 30 → rendering pipeline bottleneck; check GPU overdraw
- FPS drops during text input → Compose recomposition cost; run recomposition report (§5)
- FPS drops during navigation → page load contention; check JFR (§6)

---

## 5. Compose compiler recomposition report (`-PrecompositionReport`)

The Compose compiler can emit a per-composable breakdown of whether each function is
skippable, restartable, read-only, or inline. Non-skippable composables recompose on
every parent recomposition — finding and fixing them eliminates the most common source
of UI jank.

```bash
./gradlew :kmp:jvmTest -PrecompositionReport
# Output: kmp/build/reports/recomposition/
#   kmp_release-composables.txt  — per-composable stats
#   kmp_release-composables.csv  — CSV for spreadsheet analysis
#   kmp_release-module.json      — module summary (skippable vs. restartable counts)
```

**Reading the report:**

In `*-composables.txt`, look for:
```
restartable skippable fun PageView(...)     ← ideal: can skip if inputs unchanged
restartable fun BlockList(...)              ← bad: recomposes even if list unchanged
```

A composable is non-skippable when any parameter is an unstable type (a mutable class,
a `List<T>` instead of `ImmutableList<T>`, a lambda not wrapped in `remember`, etc.).

**Common fixes:**
- Replace `List<T>` with `@Immutable`-annotated wrapper or `kotlinx.collections.immutable`
- Wrap lambdas passed as parameters in `remember { ... }`
- Mark data classes `@Stable` or `@Immutable` when their fields are known-stable

**Implementation note:** the `composeCompiler { }` block in `kmp/build.gradle.kts` is
only active when `-PrecompositionReport` is passed — it adds zero overhead in normal
builds.

---

## 6. JFR profiling (always-on during `./gradlew :kmp:run`)

Every desktop run automatically records a JFR session and an async-profiler wall-clock
trace. These capture CPU hotspots, allocation pressure, and I/O/lock wait times at
microsecond granularity — useful for diagnosing jank root causes found via the overlay.

```bash
./gradlew :kmp:run          # records to kmp/build/profiles/run-<timestamp>.jfr
./gradlew :kmp:run -PfpsOverlay  # + Skia FPS overlay in the window

# Convert the last JFR to flamegraphs (runs automatically on exit):
./gradlew :kmp:convertLastProfile
# Or run the full benchmark suite with flamegraph output:
./scripts/benchmark-local.sh
./scripts/benchmark-local.sh /path/to/your/graph
```

**Outputs:**
```
kmp/build/profiles/
  run-<timestamp>.jfr              — raw JFR recording
  run-<timestamp>-cpu.collapsed    — wall-clock stacks (async-profiler)
  run-<timestamp>-alloc.collapsed  — allocation stacks

kmp/build/reports/                 — benchmark run outputs
  flamegraph.html                  — interactive allocation flamegraph
  flamegraph-alloc.png
  flamegraph-cpu.png
```

**Correlation with jank:** if the frame overlay shows spikes at a specific action, open
the JFR in IntelliJ/JDK Mission Control and filter to the timestamp of the spike. The
thread timeline shows exactly what the UI thread was doing during the slow frame.

---

## 7. Phase TTI regression tests

Automated tests that fail when startup load time regresses past the defined budgets. Run
as part of `ciCheck` and `jvmTest`.

```bash
./gradlew :kmp:jvmTest --tests "*.GraphLoadJankTest"
./gradlew :kmp:connectedAndroidTest --tests "*.AndroidGraphBenchmark"
```

**Budgets:**

| Graph size | Phase 1 TTI budget | What it represents |
|---|---|---|
| TINY (50 pages, 14 journals) | < 3s | New user, first few weeks |
| SMALL (200 pages, 30 journals) | < 5s | Typical user after months |
| Android (500 pages, 90 journals) | < 10s | Medium real-world graph |

Phase 1 is the point at which the UI transitions from loading spinner to interactive
(the N most-recent journals are visible). It must complete before Phase 2 (full
background load) — the tests enforce this ordering.

---

## 8. SLO violation alerts (automatic)

`SloChecker` runs in the background and emits OTel ERROR spans when any operation
exceeds its p99 budget. Violations appear automatically in the Spans tab when OTel
Stdout is enabled (Debug Menu → OTel Stdout).

**Default SLO thresholds:**

| Operation | p99 budget |
|---|---|
| `graph_load` | 5,000ms |
| `navigation` | 100ms |
| `search` | 300ms |
| `editor_input` | 100ms |
| `sql.select` | 50ms |
| `sql.insert/update/delete` | 150ms |
| `db.queue_wait` | 500ms |

A `regression.suspected` span fires when p99 ≥ 2× the threshold with ≥ 20 samples.

**Implementation:** `commonMain/performance/SloChecker.kt`

---

## 9. Exploratory session checklist

Use this checklist for a structured manual session. It exercises the code paths most
likely to cause jank or stutter.

1. **Large graph load** — open a graph with 100+ pages; time from launch to interactive
2. **Journal scroll** — fast-scroll through the journal list; watch for frame drops
3. **Page navigation** — navigate to 10+ different pages using the sidebar and backlinks
4. **Block editing burst** — type rapidly in a block (triggers debounce + recomposition)
5. **Search dialog** — open search and type quickly; latency > 100ms is noticeable
6. **Backlinks panel** — open a page with many backlinks and scroll
7. **Multi-graph switch** — if multiple graphs are open, switch between them
8. **All Pages view** — scroll through the full page list on a large graph

Run `scripts/gfxinfo-session.sh` on Android to capture frame stats for the whole
session. On Desktop, leave the Frame Overlay enabled and note timestamps of any spikes.

---

## 10. Recommended next step: Jetpack Macrobenchmark

The current `AndroidGraphBenchmark` measures data loading latency on a real device.
To measure rendered frame timing on a real device (the Android equivalent of JFR's
`withFrameNanos`), add a `:macrobenchmark` module that uses `MacrobenchmarkRule` with
`FrameTimingMetric`.

A macrobenchmark module requires:
- A separate Gradle module with `com.android.test` plugin
- `targetProjectPath = ":androidApp"`
- `testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"`
- Tests using `MacrobenchmarkRule { startActivityAndWait(); ... }` with `FrameTimingMetric`

This would give p50/p90/p99 rendered frame times for scripted user flows, catch
regressions in CI, and complement the existing `gfxinfo-session.sh` manual workflow.
See [Macrobenchmark docs](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
for setup details.
