# ADR-001: OpenTelemetry Kotlin SDK Selection

**Date**: 2026-04-13
**Status**: Accepted
**Deciders**: Tyler Stapler

---

## Context

SteleKit requires distributed tracing and metrics collection that works across Android and JVM Desktop from a single KMP `commonMain` codebase. The existing `opentelemetry-api:1.43.0` (Java SDK) is already declared in `commonMain` but is a Java library — it does not provide native coroutine context propagation and will not compile for iOS or JS targets.

Three options were evaluated:

| Option | KMP Support | Coroutine Integration | Maturity |
|--------|------------|----------------------|----------|
| `io.opentelemetry:opentelemetry-api` (Java SDK) | JVM + Android only (via expect/actual shims) | None native; requires `asContextElement()` wrapper | Stable, CNCF incubating |
| Custom lightweight tracer | Full KMP | Full control | Not maintained; reinventing the wheel |
| `io.opentelemetry.kotlin:opentelemetry-kotlin` v0.2.0 | Android + JVM + iOS + JS | Native `OpenTelemetryContextElement` coroutine propagation | Official CNCF project; donated by Embrace March 2026 |

---

## Decision

Use **`opentelemetry-kotlin` v0.2.0** (the official KMP OpenTelemetry SDK) as the observability foundation.

- `io.opentelemetry.kotlin:opentelemetry-kotlin-api:0.2.0` in `commonMain`
- `io.opentelemetry.kotlin:opentelemetry-kotlin-sdk-android:0.2.0` in `androidMain`
- `io.opentelemetry.kotlin:opentelemetry-kotlin-sdk-jvm:0.2.0` in `jvmMain`

The existing `io.opentelemetry:opentelemetry-api:1.43.0` declaration in `commonMain` is removed to avoid classpath conflicts.

---

## Rationale

1. **Official CNCF project**: Donated by Embrace to the OpenTelemetry project and announced at KubeCon March 2026. This is the canonical path for KMP OTel, not a community fork.

2. **Native coroutine context propagation**: The SDK provides `OpenTelemetryContextElement` for Kotlin coroutines, enabling parent-child span linkage across `withContext(Dispatchers.IO)` boundaries without manual context passing. This directly addresses the highest-severity known issue (context loss across dispatcher switches).

3. **Target coverage**: Supports Android, JVM, iOS, and JS from a single API surface. SteleKit's `commonMain` shared code will compile for all targets without expect/actual shims for the core tracing API.

4. **Two modes**: "Regular" mode (KMP-native implementation) and "Compatibility" mode (delegates to Java SDK). SteleKit will use Regular mode to avoid the Java SDK dependency.

5. **Export flexibility**: Ships `OtlpJsonLoggingSpanExporter` (stdout) and `InMemorySpanExporter` out of the box — both needed for Phase 1 (no remote backend).

---

## Consequences

**Positive**:
- Single tracing API works in `commonMain` without platform branching.
- Coroutine propagation eliminates the highest-severity bug risk in the plan.
- Future iOS target support is available without SDK replacement.

**Negative**:
- SDK v0.2.0 is new; binary size impact on Android APK is not yet empirically measured. A CI gate (PERF-4.2) will enforce a 2 MB maximum APK size increase.
- Breaking API changes between v0.2.x minor versions are possible during incubation. Pin to an exact version in `build.gradle.kts`; update only with a dedicated PR.
- ServiceLoader (SPI) pattern used internally by the SDK requires ProGuard keep rules in release builds (addressed in PERF-2.2).

**Migration from existing Java OTel API**:
- The `opentelemetry-api:1.43.0` dependency is removed. Any existing call sites using `io.opentelemetry.*` Java packages must be migrated to the KMP API (`io.opentelemetry.kotlin.*`). As of this decision, no call sites exist — the old dependency was declared but unused.
