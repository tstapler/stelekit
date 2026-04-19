# ADR-002: Speech-to-Text Provider Interface

**Status**: Proposed
**Date**: 2026-04-18

## Context

The voice pipeline requires a speech-to-text step that converts a recorded audio file into a
text transcript. Multiple STT backends exist with different trade-offs:

- **OpenAI Whisper API** (`whisper-1`, `gpt-4o-mini-transcribe`): remote, paid ($0.003–$0.006/min),
  works on all platforms from `commonMain` via Ktor multipart POST, accepts `.m4a` directly.
- **Android ML Kit GenAI STT** (`createOnDeviceSpeechRecognizer`): free, on-device, foreground-only
  (raises `ErrorCode.BACKGROUND_USE_BLOCKED` from background contexts), requires API 31+.
- **iOS `SFSpeechRecognizer`**: free, on-device capable (iOS 13+), streaming output, per-task
  duration limit (~1 min) requiring chunked usage for long recordings.
- **WhisperKit** (iOS, Argmax): on-device Core ML, production-ready (ICML 2025), Phase 2 scope.

The seam pattern established by `TopicEnricher` / ADR-002 (import-topic-suggestions) specifies:
`suspend fun interface` with a `NoOp` default, injected at construction time.

Whisper hallucination on silent audio is a confirmed issue (openai/whisper Discussion #1606):
transcribing silence produces "Thank you." and similar tokens. The interface must model this as a
distinct result state so the ViewModel can suppress LLM calls on empty transcripts.

## Decision

Define `SpeechToTextProvider` as a `suspend fun interface` in `commonMain` with a sealed result
type that models all failure modes without exceptions crossing the provider boundary:

```kotlin
// commonMain — voice/SpeechToTextProvider.kt
fun interface SpeechToTextProvider {
    suspend fun transcribe(audio: PlatformAudioFile): TranscriptResult
}

sealed interface TranscriptResult {
    data class Success(val text: String) : TranscriptResult
    data object Empty : TranscriptResult
    sealed interface Failure : TranscriptResult {
        data object NetworkError : Failure
        data class ApiError(val code: Int, val message: String) : Failure
        data object AudioTooShort : Failure
        data object PermissionDenied : Failure
    }
}

/** Default: no-op provider for tests and desktop. */
object NoOpSpeechToTextProvider : SpeechToTextProvider {
    override suspend fun transcribe(audio: PlatformAudioFile) = TranscriptResult.Empty
}
```

**Tier 1 default (v1)**: `WhisperSpeechToTextProvider` in `commonMain`. Ktor multipart POST to
`https://api.openai.com/v1/audio/transcriptions`. Uses user-supplied API key from settings.
Maps `transcript.split().size < 10` to `TranscriptResult.Empty` to gate Whisper silence
hallucination before the LLM call.

**Tier 1a Android (Phase 2)**: `AndroidMlKitSpeechToTextProvider` in `androidMain`. Uses ML Kit
GenAI `createOnDeviceSpeechRecognizer` (API 31+). Free, on-device, foreground-only. Falls back
to `WhisperSpeechToTextProvider` when `ErrorCode.BACKGROUND_USE_BLOCKED` or device is below
API 31.

**Tier 1b iOS (Story 3)**: `IosSpeechToTextProvider` in `iosMain`. Uses `SFSpeechRecognizer`
with `requiresOnDeviceRecognition = true` where supported. Requires chunking for recordings
longer than ~60 seconds. Falls back to `WhisperSpeechToTextProvider` when unavailable.

**Tier 0 (tests / Desktop)**: `NoOpSpeechToTextProvider` — returns `TranscriptResult.Empty`.

## Rationale

**`suspend fun interface`**: Mirrors the `TopicEnricher` seam pattern exactly. Single-method
functional interface; implementors need no base class hierarchy. Callable from a coroutine
without additional wrapping.

**Sealed `TranscriptResult` over exceptions**: Follows the `FetchResult` pattern in
`UrlFetcher.kt`. Exceptions do not cross provider boundaries — every failure mode is modeled as
a value type. This makes exhaustive `when` expressions possible in the ViewModel without
`try/catch` at the call site. `CancellationException` is the only exception the ViewModel
handles (to support pipeline cancellation).

**`Empty` as a distinct state**: Whisper hallucination on silence is a confirmed issue. Returning
`TranscriptResult.Empty` rather than a short garbage string lets `VoiceCaptureViewModel`
surface a "Nothing was captured — try again" message and skip the LLM call entirely. The
word-count gate (`< 10 words`) is the recommended community mitigation for the standard
`whisper-1` model.

**Whisper as v1 default**: Whisper is the only STT option that works identically on all targets
from a single `commonMain` code path. No platform-specific code is required for the happy path.
Platform native STT providers (ML Kit, SFSpeechRecognizer) are added as optional built-ins
behind the same interface for Phase 2 — free STT for users who prefer on-device processing.

**No streaming interface**: "Real-time transcription display while speaking (result shown only
after processing)" is explicitly out of scope in `requirements.md`. A batch `suspend fun`
interface is sufficient. Streaming can be added as a parallel `Flow`-based variant in a future
phase without modifying the batch contract.

## Consequences

- `VoiceCaptureViewModel` receives `sttProvider: SpeechToTextProvider = NoOpSpeechToTextProvider`
  as a constructor parameter.
- `WhisperSpeechToTextProvider` requires a Whisper API key from `VoiceSettings`. If the key is
  absent at construction time, `transcribe()` returns `TranscriptResult.Failure.ApiError(401, ...)`.
- `AndroidMlKitSpeechToTextProvider` (Phase 2) requires `build.gradle.kts` addition:
  `implementation("com.google.mlkit:genai-speech-recognition:1.x.x")` in `androidMain`.
- `IosSpeechToTextProvider` (Story 3) requires adding `Speech.framework` to the iOS cinterop
  definition in `build.gradle.kts`.
- All providers must handle `CancellationException` propagation: do not catch it in the provider.
- The `TranscriptResult` sealed hierarchy is a stable public API commitment once the plugin
  registry is added.

## Alternatives Considered

**Streaming `Flow<TranscriptChunk>` interface**: Eliminated for v1 because streaming display is
out of scope. Forces asymmetric implementations (Whisper is batch-only). Can be added as a
parallel interface in Phase 2 without changing the batch contract.

**`SpeechRecognizer.createOnDeviceSpeechRecognizer` as v1 default (Android)**: Eliminated for v1
because it is foreground-only and unavailable below API 31. Whisper is universal and covers all
device tiers. ML Kit is a Phase 2 enhancement for supported devices.

**`openai-kotlin` v4.1.0 library for Whisper**: Eliminated. Raw Ktor multipart POST is ~50 lines
and Ktor is already present. The library adds ~1–2 MB and typed models not needed for the narrow
`transcribe()` surface.
