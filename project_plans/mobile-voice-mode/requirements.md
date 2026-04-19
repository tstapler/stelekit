# Requirements: Mobile Voice Mode

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-18

## Problem Statement

Users want to capture thoughts hands-free in situations where looking at or typing on a phone is impractical or unsafe — driving, parties, walking. Current SteleKit requires active text input. This feature enables a one-tap voice capture flow that transcribes speech, formats it into Logseq-compatible outliner syntax with `[[links]]`, and appends it to the daily journal — all without manual editing.

## Success Criteria

- User taps a mic button, speaks for any duration, and a correctly formatted outliner entry appears in today's journal page
- Works entirely hands-free: no keyboard interaction required from trigger to saved entry
- LLM provider is configurable per-user via an extensible plugin API (not hardcoded to one vendor)
- Pipeline works on Android and iOS via a platform-agnostic commonMain design with thin platform adapters

## Scope

### Must Have (MoSCoW)
- Single-tap microphone trigger accessible from the main UI (no navigation required)
- Unlimited-duration voice recording — user stops when done, no timeout
- Speech-to-text transcription (Whisper API or platform STT)
- LLM pass to convert raw transcript → Logseq outliner format with `[[wikilinks]]`
- Automatic insertion into today's daily journal page
- Extensible LLM provider API: pluggable backends (remote API key, on-device ML, custom)
- Support for at least: Remote API w/ user-supplied key (OpenAI / Anthropic / compatible), on-device platform LLM (Android AICore / Gemini Nano, Apple Intelligence where available)

### Out of Scope
- Multi-language support (English only at launch)
- Real-time transcription display while speaking (result shown only after processing)
- Raw audio file storage or playback after transcription
- Background/passive listening (user must actively trigger each recording)
- Desktop platform support for this feature

## Constraints

- **Tech stack**: Kotlin Multiplatform; shared logic in `commonMain`, platform audio capture in `androidMain` / `iosMain`; Compose Multiplatform UI
- **Timeline**: No hard deadline — ship when well-architected
- **Dependencies**: Daily journal page must already exist or be auto-created (existing behavior)
- **Plugin API**: Must be designed so third-party or community plugins can add new STT/LLM backends without forking core code

## Context

### Existing Work
- SteleKit already has a daily journal concept and page insertion via `GraphWriter`
- `import-topic-suggestions` project established a plugin/provider interface pattern (ADR-002-topic-enricher-plugin-interface.md) — this feature should follow the same extensibility model
- Branch `stelekit-mobile-mode` is the working branch for mobile-first improvements

### Stakeholders
- Primary user: Tyler (solo developer and user) — needs discreet, eyes-free note capture while driving or in social settings
- Future users who want voice-driven knowledge capture with their own LLM credentials

## Research Dimensions Needed

- [ ] Stack — evaluate STT options (Whisper API, Android SpeechRecognizer, iOS SFSpeechRecognizer, platform ML kits) and LLM API client libraries for KMP
- [ ] Features — survey comparable voice-to-notes tools (Audiopen, Whisper Memos, Notion AI voice) for UX patterns
- [ ] Architecture — plugin interface design for STT+LLM providers, commonMain pipeline, platform audio capture adapter pattern
- [ ] Pitfalls — microphone permissions across platforms, audio focus management (Android), on-device model availability gates, LLM prompt design for outliner fidelity
