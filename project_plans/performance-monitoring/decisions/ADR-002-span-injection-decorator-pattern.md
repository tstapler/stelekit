# ADR-002: Span Injection via Decorator Pattern on Repository Interfaces

**Date**: 2026-04-13
**Status**: Accepted
**Deciders**: Tyler Stapler

---

## Context

OTel spans need to wrap repository operations (`PageRepository`, `SearchRepository`, etc.) to measure DB latency. Three injection strategies were considered:

| Strategy | Coupling | Testability | Domain Pollution | Effort |
|----------|---------|-------------|-----------------|--------|
| Inline instrumentation in each repository implementation | High (impl knows about OTel) | Hard (mocking OTel in unit tests) | Medium (tracing code in DB layer) | Low (no new files) |
| AOP / bytecode weaving | None | Hard (magic; breaks on KMP) | None | High (not viable in KMP without JVM) |
| Decorator wrapping repository interfaces | None | Excellent (decorator is independently testable) | None (domain code untouched) | Medium (one file per interface) |

---

## Decision

Use the **Decorator pattern** to inject OTel spans. Each repository interface (`PageRepository`, `BlockRepository`, `SearchRepository`) gets an instrumented wrapper class in `commonMain/kotlin/dev/stapler/stelekit/performance/`:

- `InstrumentedPageRepository` wraps `PageRepository`
- `InstrumentedSearchRepository` wraps `SearchRepository`

Wiring happens exclusively in `RepositoryFactoryImpl.createXxxRepository()` — the single assembly point for repository instances. Domain classes (`StelekitViewModel`, `GraphLoader`) are unaware of instrumentation.

---

## Rationale

1. **Zero domain coupling**: The `PageRepository` interface, `GraphLoader`, and `StelekitViewModel` contain no tracing code. Instrumentation is a cross-cutting concern and belongs in the infrastructure layer.

2. **Testability**: `InstrumentedPageRepository` can be unit-tested with a fake `PageRepository` delegate and a test `Tracer`, without launching the full app. The span start/end contract is verifiable in isolation.

3. **Open-Closed compliance**: Adding a new repository method requires only modifying the decorator, not hunting down instrumentation scattered across implementations. The decorator is open for extension (new methods) without modifying the delegate.

4. **KMP compatible**: Pure Kotlin delegation — no reflection, no bytecode weaving, no JVM-only APIs. Compiles for Android, JVM Desktop, and future iOS/JS targets.

5. **Replaces existing `PerformanceMonitor.startTrace()` calls**: The decorator provides a structured replacement for the ad-hoc `PerformanceMonitor` singleton. Call sites are migrated in Phase 2 without a risky simultaneous refactor.

---

## Consequences

**Positive**:
- Repository interfaces remain clean; new team members do not see tracing noise.
- Instrumented wrappers can be toggled off at runtime (OTel not initialized → no-op tracer → zero overhead).
- Consistent span naming convention enforced in one location.

**Negative**:
- One new file per repository interface decorated. With 5 repository interfaces, this adds 5 new files. This is accepted as a necessary cost of clean architecture.
- If a repository interface adds a new method, the decorator must also be updated. A compile-time guarantee is maintained because decorators implement the full interface — a missing method is a compilation error, not a runtime bug.
- `BlockRepository` is not decorated in Phase 2 (deprioritized — no user-reported block-level latency issues). It can be added in a future phase without architectural change.

---

## Implementation Notes

The decorator factory wiring in `RepositoryFactoryImpl`:

```kotlin
override fun createPageRepository(backend: GraphBackend): PageRepository {
    val base = /* existing switch */ ...
    return if (OtelProvider.isInitialized()) {
        InstrumentedPageRepository(base, OtelProvider.getTracer("stelekit.repository"))
    } else {
        base
    }
}
```

The `OtelProvider.isInitialized()` guard ensures tests that do not initialize OTel receive the unwrapped repository — no test setup changes required for pre-existing tests.
