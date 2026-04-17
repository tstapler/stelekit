# Research Plan: Performance Monitoring & Management

**Date**: 2026-04-13
**Input**: `project_plans/performance-monitoring/requirements.md`

## Subtopics

| # | Subtopic | Output file | Search cap |
|---|----------|------------|-----------|
| 1 | Stack | `findings-stack.md` | 5 searches |
| 2 | Features | `findings-features.md` | 5 searches |
| 3 | Architecture | `findings-architecture.md` | 5 searches |
| 4 | Pitfalls | `findings-pitfalls.md` | 4 searches |

## Per-Subtopic Strategy

### 1. Stack
Key question: What OpenTelemetry KMP libraries exist, and how do we access Compose frame timing on Android + JVM?

Search queries:
- "opentelemetry kotlin multiplatform KMP 2024 2025"
- "opentelemetry android kotlin SDK compose performance tracing"
- "compose frame timing android choreographer jank detection kotlin"
- "opentelemetry KMP multiplatform gradle dependency"
- "android frame metrics API jank detection jetpack compose"

Trade-off axes: KMP compatibility, API surface, binary size, maintenance, ease of integration

### 2. Features
Key question: What perf overlays, jank detectors, and bug report tools already exist that we can learn from or reuse?

Search queries:
- "android performance overlay compose FPS frame time debug"
- "leakcannary equivalent performance monitoring android kotlin"
- "perfetto android tracing API kotlin compose integration"
- "compose compiler metrics performance analysis 2024"
- "android strict mode jank detection compose"

Trade-off axes: depth of insight, ease of use, integration effort, production safety

### 3. Architecture
Key question: Where do OTel spans fit in SteleKit's data flow, and how do we store histograms in SQLDelight?

Search queries:
- "opentelemetry kotlin spans coroutines structured concurrency"
- "sqldelight time series metrics storage android kotlin"
- "kotlin multiplatform debug menu settings panel compose"
- "opentelemetry OTLP file exporter android local"
- "compose performance instrumentation layered architecture kotlin"

Trade-off axes: invasiveness, observability granularity, storage overhead, debug ergonomics

### 4. Pitfalls
Key question: What goes wrong when adding always-on instrumentation to Compose apps?

Search queries:
- "opentelemetry android SDK APK size impact kotlin"
- "compose recomposition tracking overhead instrumentation pitfalls"
- "always-on performance monitoring android battery memory overhead"
- "opentelemetry KMP known issues limitations 2024 2025"

Trade-off axes: binary size impact, runtime overhead, accuracy, maintenance burden
