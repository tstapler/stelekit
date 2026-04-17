# ADR-002: TopicEnricher Interface as Plugin Extension Point

**Date**: 2026-04-17
**Status**: Accepted

## Context

The user explicitly requested that the topic-suggestion architecture "expose APIs so people can implement plugins for whatever they want." Concurrently, the Claude API opt-in requirement demands a seam between the heuristic extraction layer (which must run offline, zero-cost) and any enrichment layer (which may have network or API dependencies).

Two questions must be answered together:
1. Where should the enrichment abstraction boundary live?
2. How should third-party implementations be injected?

The existing `PageSaver` functional interface in `ImportViewModel` establishes the project's idiom: a minimal functional interface injected at construction time, with a `NoOp` default, and a production-wired version constructed outside the ViewModel.

## Decision

Define `TopicEnricher` as a `suspend fun interface` in `domain/`:

```kotlin
fun interface TopicEnricher {
    suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>): List<TopicSuggestion>
}
```

Provide `NoOpTopicEnricher` (returns `localSuggestions` unchanged) as the default. Inject `topicEnricher` into `ImportViewModel` as a constructor parameter with `NoOpTopicEnricher()` as its default value — mirroring the `pageSaver` seam pattern exactly.

The enricher is **not** injected into `ImportService`. It is a ViewModel-level concern because enrichment is async, has potential side effects (network I/O, API cost), and requires coroutine lifecycle management.

## Rationale

**Interface over abstract class** keeps the contract minimal. A `suspend fun interface` with a single method is the narrowest stable API surface. Third-party implementors need to implement exactly one function; they are not bound to any base-class hierarchy or internal state model.

**ViewModel injection, not domain injection.** `ImportService` is a pure function. Injecting an async interface into it would force `scan()` to become a `suspend fun`, change every call site, and require coroutine scope plumbing in the domain layer. The ViewModel already manages a coroutine scope and a `scanJob`; the enricher coroutine is a natural second job alongside it.

**`NoOpTopicEnricher` as default** ensures the feature is local-first by construction. A developer building and running the app without an API key configured gets the heuristic-only path with zero code changes. No null checks are needed in the ViewModel — the interface contract is always satisfied.

**Plugin extension point v1.** The `TopicEnricher` interface is the v1 plugin API. Its contract — `enhance(rawText, localSuggestions) → List<TopicSuggestion>` — gives plugin authors full access to the original text and the local candidates. They may re-rank, filter, add, or remove suggestions. This surface should remain stable when a plugin registry is added.

**Full plugin registry is out of scope for v1.** Auto-discovery via `ServiceLoader` (JVM), or explicit registration, requires a `PluginRegistry` design that is a separate MDD project (`stelekit-plugin-api`). In v1, the host application controls which `TopicEnricher` implementation is active. This is intentional — it keeps this feature shippable without designing a full plugin system.

## Consequences

- `ImportViewModel` constructor gains `topicEnricher: TopicEnricher = NoOpTopicEnricher()`.
- The secondary `GraphWriter`-accepting constructor in `ImportViewModel` also defaults to `NoOpTopicEnricher()` — no production call-site changes unless a `ClaudeTopicEnricher` is wired in.
- Third-party implementors can provide any `TopicEnricher` by implementing the single `enhance` function. The interface is in `domain/` and has no dependencies beyond `TopicSuggestion`.
- The `TopicEnricher` interface signature is a public API commitment. It must not be changed without versioning consideration once the plugin registry is added.
- `ImportViewModel` tests inject a `FakeTopicEnricher` (returns fixed suggestions), following the same pattern as `FakePageSaver`.

## Patterns Applied

- Dependency Inversion Principle (ViewModel depends on `TopicEnricher` abstraction, not `ClaudeTopicEnricher` concretion)
- Interface Segregation Principle (single-method interface; implementors are not forced to depend on unused methods)
- Strategy pattern (enrichment strategy is swappable at construction time)
- Seam pattern (identical to `PageSaver` seam for `GraphWriter`)
