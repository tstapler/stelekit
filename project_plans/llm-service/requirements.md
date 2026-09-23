# Requirements: llm-service

**Date**: 2026-07-01
**Type**: refactor + feature addition (existing project: SteleKit)

## Problem Statement

LLM usage in SteleKit is scattered and inconsistent across platforms and features:

- Voice formatting (`LlmFormatterProvider`) already has three implementations —
  `ClaudeLlmFormatterProvider`, `OpenAiLlmFormatterProvider`, and Android-only
  `MlKitLlmFormatterProvider` (on-device Gemini Nano via ML Kit/AICore) — but there is
  no unified concept of "the user's configured providers" that features can discover
  and choose from.
- Tag suggestion (`LlmTagProvider`, wired in `App.kt` via `buildLlmFormatterForTags`)
  only checks for an Anthropic or OpenAI API key. It does not fall back to the
  on-device `MlKitLlmFormatterProvider` that already exists in the codebase — so an
  Android user with no API key configured gets zero LLM-tier tag suggestions, even
  though their device may support on-device inference today.
- There is no way for an LLM to operate across the graph rather than a single
  block/transcript at a time — e.g. proposing edits, tag corrections, or synthesized
  notes that span multiple pages/blocks — and no approval mechanism for that class of
  operation.
- Every new LLM-powered feature currently has to re-solve provider selection,
  credential storage, and offline fallback from scratch.

This is one connected initiative: a unified provider abstraction, parity for
on-device inference across the features that already assume remote-only LLMs, a
user-facing way to configure and swap providers per feature, and a new
approval-gated workflow for LLM-driven library (graph) edits.

## Users / Consumers

Both human users and internal call sites:

- **Human users** (all platforms — Desktop, Android, iOS, Web): configure provider
  credentials in Settings, choose which provider a feature uses, and review/approve
  or reject LLM-proposed graph edits, tags, or synthesized notes.
- **Internal feature call sites**: voice formatting, tag suggestion, and future
  knowledge-synthesis features consume the provider abstraction instead of
  hand-wiring specific providers.

## Success Metrics

- **Extensibility**: adding a new LLM provider/backend is a self-contained change
  (implement the provider interface + register it) — no edits required in tag
  suggestion, voice formatting, or other call sites.
- **Android on-device parity**: tag suggestions produce LLM-tier results on a
  supported Android device with zero API keys configured, using the existing
  `MlKitLlmFormatterProvider` (Gemini Nano via ML Kit/AICore).
- **User-facing provider management**: a Settings surface lists available providers,
  lets the user add/edit credentials, and lets the user pick which provider each
  LLM-powered feature (tagging, voice, synthesis) uses.
- **Approval-gated edit workflow**: an LLM can propose an edit, tag change, or
  synthesized note against the graph; it surfaces as a pending suggestion that the
  user must explicitly accept before anything is written. No exceptions — this
  applies uniformly even to "high confidence" output from this new workflow. (This
  does not change the existing tag-suggestion feature's local-match auto-apply
  behavior, which is a separate, already-shipped tier.)

## Constraints

- **Must work offline / degrade gracefully**: on-device and local tiers must
  continue to function with zero network dependency; remote providers are an
  optional enhancement layered on top, consistent with the existing tag-suggestion
  and voice patterns.
- **No mandatory new backend/server**: this stays client-side within the existing
  KMP shared codebase plus platform-specific on-device APis (ML Kit/AICore on
  Android, on-device/Apple Intelligence APIs on iOS). No new backend service to
  build or operate.
- **Secure per-platform credential storage**: API keys/credentials must use
  platform-appropriate secure storage (e.g. Android Keystore-backed, iOS Keychain),
  not new plaintext preferences.
- **No hard deadline** — scoped by completeness/quality, not a ship-by date.

## Resolved Decisions

| Question | Decision |
|----------|----------|
| Refactor existing code or leave it? | **Refactor.** Unify `LlmFormatterProvider` implementations (`ClaudeLlmFormatterProvider`, `OpenAiLlmFormatterProvider`, `MlKitLlmFormatterProvider`) and `ClaudeTopicEnricher` under one provider abstraction; migrate existing call sites (voice, tag suggestion) onto it. |
| Auto-apply vs. approval for new LLM-driven writes? | **Always require explicit approval.** No auto-apply exception, even for high-confidence output, for anything produced by the new edit/synthesis workflow. |
| iOS on-device LLM — v1 or architected-for-later? | **Implement in v1.** Ship a working iOS on-device provider (Apple Intelligence / on-device APIs) alongside Android on-device, not deferred. |
| v1 provider set | Anthropic Claude (remote), OpenAI-compatible (remote), Android on-device (ML Kit/Gemini Nano), iOS on-device (Apple Intelligence/on-device), Google Gemini API (remote), local self-hosted (Ollama/LM Studio), Azure OpenAI, and a generic OpenAI-compatible custom-base-URL provider type. |

## Scope

### In Scope

- **Unified LLM provider abstraction**: a common interface (evolving the existing
  `LlmFormatterProvider` shape) that all providers implement, with a
  registry/discovery mechanism features use to find configured, available
  providers instead of hand-checking individual API keys.
- **Provider implementations**:
  - Anthropic Claude (remote) — migrate existing `ClaudeLlmFormatterProvider`
  - OpenAI-compatible (remote) — migrate existing `OpenAiLlmFormatterProvider`
  - Google Gemini API (remote) — new
  - Generic OpenAI-compatible custom-base-URL provider — new; covers Ollama,
    LM Studio, Azure OpenAI, OpenRouter, and similar without one bespoke
    implementation per vendor
  - Android on-device (ML Kit/Gemini Nano) — migrate existing
    `MlKitLlmFormatterProvider`; wire into tag suggestion and any new features,
    not just voice formatting
  - iOS on-device (Apple Intelligence/on-device APIs) — new
- **Secure credential storage per platform** for all remote provider API keys and
  custom endpoint configuration (base URL, model name, etc. where applicable).
- **Settings UI** for provider management: list available/configured providers,
  add/edit/remove credentials, and select which provider a given feature (tagging,
  voice, future synthesis) uses. Includes surfacing on-device availability/download
  status (mirrors existing `MlKitLlmFormatterProvider.checkEligible()`).
  - Migrate existing voice-settings LLM key storage (`VoiceSettings` Anthropic/OpenAI
    keys) into the new credential storage rather than maintaining two systems.
- **Migrate existing consumers** (`LlmTagProvider` / tag suggestion,
  voice formatting pipeline, `ClaudeTopicEnricher`) onto the unified abstraction so
  they automatically gain on-device fallback and multi-provider support.
- **Approval-gated LLM library-editing workflow**: a new capability where an LLM can
  propose graph-level operations (block edits, tag changes, synthesized new
  notes/pages) that are queued as pending suggestions and require explicit
  per-suggestion user accept/reject before any write occurs.

### Out of Scope

- Cloud sync of credentials across a user's devices — credentials stay local per
  device.
- Changing the existing tag-suggestion feature's local-match auto-apply tier
  (`project_plans/llm-provider/`) — that shipped behavior is unaffected; only the
  LLM tier of that feature gains on-device fallback via the new abstraction.
- A full multi-step autonomous agent (tool use, multi-turn planning) — the edit
  workflow is single-shot propose → user approves/rejects, not an agentic loop.

## Open Questions

1. What does a "synthesized note" concretely look like in the approval UI — a new
   page created from scratch, or always an edit anchored to existing blocks/pages?
   Needs research into UI patterns for reviewing/diffing proposed graph changes.
2. For the generic OpenAI-compatible custom-endpoint provider, what validation (if
   any) confirms the endpoint is reachable/compatible before saving credentials?
3. Does the approval-gated edit workflow need a queue/inbox (multiple pending
   suggestions accumulate) or is it always synchronous (one proposal, immediate
   accept/reject, no persistence across app restarts)?
4. iOS on-device LLM APIs (Apple Intelligence) have their own availability/eligibility
   constraints (OS version, device, on-device model foundation availability) —
   research needed on the current API surface (Foundation Models framework) before
   planning.
