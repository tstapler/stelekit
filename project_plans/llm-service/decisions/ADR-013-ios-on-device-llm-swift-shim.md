# ADR-013: iOS On-Device LLM via Swift Foundation Models Shim + Cinterop

**Date**: 2026-07-01
**Status**: Accepted
**Deciders**: Tyler Stapler
**Context**: llm-service Phase 3 (planning) — implements requirements.md's resolved decision to ship iOS on-device LLM in v1

---

## Context

`requirements.md`'s Resolved Decisions table states: "iOS on-device LLM — v1 or
architected-for-later? **Implement in v1.** Ship a working iOS on-device provider
(Apple Intelligence / on-device APIs) alongside Android on-device, not deferred." This
closes Open Question 4 ("iOS on-device LLM APIs... research needed on the current API
surface (Foundation Models framework) before planning") at the requirements layer; this
ADR records the resulting implementation-architecture decision.

Stack research confirms Apple's `FoundationModels` framework (iOS 26 / iPadOS 26 /
macOS 26+) is the relevant API — Swift-native access to Apple's on-device ~3B-parameter
model, the same model backing Apple Intelligence features. Critically, **no direct
Kotlin/Native interop path exists**:

- Kotlin/Native's Objective-C interop (`cinterop`) can only bind against
  Objective-C-visible APIs. Pure Swift types not annotated `@objc`/`@objcMembers` are
  invisible to Kotlin/Native.
- `FoundationModels`'s actual surface (`LanguageModelSession`, the `@Generable` macro
  for structured output, Swift structured-concurrency `async`/`await` calls) is built
  on Swift macros and generics that have no Objective-C representation. They cannot be
  exposed via `@objc` even if attempted — macro-generated conformances and generic
  session types do not bridge.

The codebase already has a working precedent for bridging a platform-native secure API
into `iosMain` via `cinterop`: `IosCredentialStore.kt` calls Security-framework C APIs
(`SecItemAdd`/`SecItemCopyMatching`/`SecItemDelete`) directly, because `Security` is a
C/Objective-C framework with a native `cinterop`-visible surface. `FoundationModels` has
no such surface — it requires an intermediate Swift module that Kotlin/Native can bind
against.

Runtime availability constraints (pitfalls research §2.2) that any implementation must
surface, not hardcode:
- OS: iOS 26 / iPadOS 26 / macOS 26+; hardware: A17 Pro / M1 or newer (Neural Engine).
- Apple Intelligence must be user-enabled in Settings — a supported device with AI
  disabled reports unavailable.
- Background model download must have completed (an intermediate "not yet downloaded"
  state, analogous to ML Kit's `DOWNLOADABLE`/`DOWNLOADING`).
- Region/language eligibility is a **live, moving target** (ongoing EU DMA-related
  restrictions) — must be a runtime API query (`SystemLanguageModel.availability`)
  evaluated fresh each session, never cached across app versions or hardcoded as an
  allow/deny list.
- Content-safety guardrails can reject a programmatically-constructed system prompt
  itself (not just user content), which is a distinct failure class from "invalid API
  key" or "rate limited" and needs its own result case.
- No CI lane can exercise this path: the iOS Simulator uses the host Mac's on-device
  models and requires macOS 26+ with Apple Intelligence enabled on the host hardware.
  This project's CI is Bazel/Gradle on Linux for JVM/Android — the iOS on-device
  provider can only be manually verified on physical/simulator hardware a contributor
  owns.

## Decision

Implement iOS on-device LLM support in v1 via a small **Swift shim module** with an
`@objc`-compatible completion-handler API, cinterop-bound into `iosMain`, following
the same architectural pattern already established by `IosCredentialStore.kt` (Kotlin
code calling into a native-framework-adjacent Obj-C-visible surface via `cinterop`) —
except here the C/Obj-C surface is hand-authored (the shim) rather than a pre-existing
system framework header.

1. A new small Swift package/target (e.g. `iosApp/FoundationModelsShim`) exposes a
   minimal, Objective-C-compatible surface — a class with `@objc` methods such as:

   ```swift
   func format(transcript: String, systemPrompt: String,
               completion: @escaping (String?, NSError?) -> Void)
   func checkAvailability(completion: @escaping (AvailabilityStatus) -> Void)
   ```

   The shim internally performs the Swift-only `LanguageModelSession` calls,
   availability checks (`SystemLanguageModel.default.availability`), and guardrail/
   error handling, converting results to plain strings/`NSError` (or a small
   `@objc`-representable enum for availability/error taxonomy) before crossing the
   bridge. A completion-handler shape is used (not `async`/`await`) because Swift
   structured concurrency does not bridge to Obj-C/cinterop either — the existing
   `suspend fun format(...)` contract in Kotlin already crosses a suspend boundary,
   so `iosMain` wraps the completion-handler call in `suspendCancellableCoroutine`.

2. The shim is built as an Objective-C-header-emitting framework/module that the iOS
   app's Xcode project links. Per `CLAUDE.md`, "Gradle is kept only for iOS" for the
   iOS target — the shim is added alongside the existing iOS build path, with a
   `.def` file + generated header + `cinterop` block in `iosMain`'s Kotlin/Native
   target configuration, following the standard "Kotlin/Native binds an Apple
   framework via a hand-authored Obj-C header" pattern.

3. `iosMain` implements the unified `LlmProvider`/`LlmFormatterProvider` contract by
   calling the generated cinterop bindings, exposed via
   `expect fun platformOnDeviceLlmProvider(): LlmProvider?` (per the architecture
   research's cross-platform capability-wiring pattern), returning `null` when the
   shim reports the framework unavailable at build/runtime — consistent with the
   `MlKitLlmFormatterProvider.create()`-returns-`null` convention already used on
   Android.

4. Availability is surfaced as a tri-state (ready / needs-setup-or-download /
   unsupported), not a boolean — mirroring the Android on-device tri-state fix noted
   in ADR-014-adjacent architecture research, and specifically distinguishing
   "device/OS/region ineligible" from "eligible but Apple Intelligence not yet
   enabled by the user" from "eligible, download in progress," since these have
   different, actionable UI copy.
5. Content-safety guardrail rejections get their own `LlmResult.Failure` case
   (e.g. `ContentRejected`) distinct from network/API-key errors, so the UI can say
   "this couldn't be processed on-device" rather than implying a configuration
   problem, and can offer "try a remote provider instead" as the natural recovery
   path via the provider registry.

## Consequences

**Positive**:
- Delivers the requirements-mandated v1 iOS on-device provider, giving iOS the same
  on-device-parity goal already targeted for Android.
- Reuses an established bridging pattern from the same codebase
  (`IosCredentialStore.kt`) rather than inventing a new one — lower design risk even
  though the shim itself is new code.
- The completion-handler/`suspendCancellableCoroutine` boundary keeps the
  `LlmFormatterProvider.format(...)` contract unchanged for all existing and future
  callers (voice, tag suggestion, synthesis) — no call-site-visible difference between
  a remote provider and the iOS on-device provider.

**Negative / risks**:
- This is real, net-new engineering: a Swift module, an Obj-C-compatible API surface
  design, a `.def`/cinterop configuration, and Xcode/Gradle build wiring — sized as
  likely the largest single unit of work in the full v1 provider set, larger than the
  Android ML Kit integration (which needed no cross-language bridge since ML Kit
  ships a Kotlin/Java API directly).
- **No CI coverage is possible for this path.** The iOS Simulator's on-device models
  require host macOS 26+ with Apple Intelligence enabled on Apple Intelligence-eligible
  hardware; this project's CI is Bazel/Gradle on Linux. The shim's Kotlin-side
  wiring (registry integration, error mapping, availability tri-state handling) can
  and must be tested via `businessTest` against a fake implementation of the
  `LlmProvider` interface (per pitfalls research §3.3), but the actual
  `FoundationModels` call path is verifiable only by a contributor with qualifying
  physical/simulator hardware. This gap must be documented as a known testing-strategy
  limitation, not silently accepted.
- Region/eligibility must be re-checked at runtime every session, not cached — a
  build-time or session-cached allow/deny list will go stale as Apple's DMA-driven
  regional rollout changes independent of SteleKit releases.
- The 256-token-class output ceiling problem seen on Android on-device (ADR/pitfalls
  research §2.1, §2.2) applies here too — Apple's ~3B model is tuned for
  summarization/extraction/short dialog, not open-ended synthesis. The graph-edit
  synthesis feature must treat the iOS on-device provider the same way it treats the
  Android on-device provider: either chunk/stitch, or exclude it from that feature's
  available-provider list with clear UI messaging.

## Alternatives Considered

### Pure Kotlin/Native cinterop directly against the `@Generable`-macro Foundation Models API

Rejected. As established above, `FoundationModels`'s Swift-macro-based and
structured-concurrency API surface has no Objective-C representation and cannot be
exposed via `@objc` even with effort — this is not a tooling limitation that a more
careful `.def` file could work around, it is a fundamental mismatch between what
Kotlin/Native cinterop can bind (Obj-C-visible symbols) and what the framework
exposes (Swift-only macro-generated types). Any attempt to skip the shim layer and
bind directly would not compile.

### Defer iOS on-device support to v2

Rejected per explicit requirements decision. `requirements.md`'s Resolved Decisions
table settles this: "Implement in v1... not deferred." The engineering cost
identified above (Swift shim, no CI coverage) was a known input to that decision, not
new information discovered after the fact — deferring would reopen a question the
project has already closed at the requirements layer, and would leave iOS as the only
v1-scoped platform without on-device parity, contradicting the stated success metric
of on-device parity across platforms.

### Skip Foundation Models and rely solely on remote providers for iOS

Rejected. This would satisfy neither the requirements' explicit v1 decision nor the
project's "must work offline / degrade gracefully" constraint for iOS specifically —
iOS users without a configured remote-provider API key would have zero LLM-tier
functionality, unlike Android users who get on-device fallback. This is the same gap
already identified as the concrete bug in tag suggestion today (Android
`MlKitLlmFormatterProvider` existing but unwired) — leaving iOS remote-only would be
knowingly reintroducing the equivalent gap for a second platform rather than fixing
it.
