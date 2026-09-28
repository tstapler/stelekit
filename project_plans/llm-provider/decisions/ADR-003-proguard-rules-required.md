# ADR-003: Introduce ProGuard Rules File for Release Builds

**Date**: 2026-06-13
**Status**: Accepted

## Context

The project currently has no `proguard-rules.pro` file. Release builds use R8 with
`minifyEnabled = true`. Without explicit keep rules, R8 will strip:
- Ktor's ServiceLoader-based engine discovery (OkHttp engine on Android)
- `kotlinx.serialization` reflective access for `@Serializable` classes in `tags/`
- Arrow classes (`CircuitBreaker`, `Either`) used by LLM providers

The `LlmTagProvider`, its `@Serializable` response models, and the Ktor
`ContentNegotiation` plugin are all new code paths exposed only at runtime.

## Decision

Create `kmp/src/androidMain/proguard-rules.pro` with keep rules for:
- Ktor OkHttp engine ServiceLoader entries
- `kotlinx.serialization` keep rules (already partially needed by VoiceSettings but
  not yet codified — this ADR formalizes the requirement)
- Arrow resilience classes
- New `tags/` package serializable models

Reference the file from `kmp/build.gradle.kts` `android { buildTypes { release { proguardFiles(...) } } }`.

## Consequences

Positive:
- Release builds no longer silently strip HTTP client infrastructure.
- A single file covers all existing and future LLM-related keep requirements.

Negative / Trade-offs:
- APK size increases slightly (fewer classes stripped). Mitigated by scoping keep rules
  to specific packages rather than blanket wildcards.

## Alternatives Rejected

- **No action**: release builds would crash at runtime with `ClassNotFoundException` or
  `ServiceConfigurationError` when Ktor attempts to discover its engine.
- **`@Keep` annotations on each class**: scattered, easy to miss on new `@Serializable` models.
