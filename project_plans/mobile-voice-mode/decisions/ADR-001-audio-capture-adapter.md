# ADR-001: Audio Capture Adapter Shape

**Status**: Proposed
**Date**: 2026-04-18

## Context

The voice pipeline requires a platform-specific audio recording layer. KMP has no standard audio
recording API in `commonMain`; every target exposes a different native API:

- Android: `AudioRecord` (raw PCM) or `MediaRecorder` (encoded file)
- iOS: `AVAudioRecorder` or `AVAudioEngine` (native Obj-C via cinterop)
- Desktop (JVM): `javax.sound.sampled.TargetDataLine` — out of scope for this feature

Two KMP design choices were evaluated: `expect class AudioRecorder` (monolithic platform class)
vs plain `interface AudioRecorder` in `commonMain` with platform implementations injected at
assembly time.

The output format is also a decision: raw PCM (`Flow<ByteArray>`) vs a temp file (`PlatformAudioFile`).

The OpenAI Whisper API accepts `.m4a` directly (confirmed: flac, mp3, mp4, mpeg, mpga, m4a, ogg,
wav, webm). Raw PCM is NOT accepted. Both Android and iOS record to `.m4a` natively.

## Decision

Define `AudioRecorder` as a **plain interface in `commonMain`**, not an `expect class`. The
interface produces a temp file path (`PlatformAudioFile`) rather than a PCM stream.

```kotlin
// commonMain — audio/AudioRecorder.kt
interface AudioRecorder {
    /** Blocks until [stopRecording] is called. Returns path to a temp .m4a file. */
    suspend fun recordToFile(): PlatformAudioFile
    suspend fun stopRecording()
}

/** Cross-platform wrapper for a temp audio file path. */
@JvmInline
value class PlatformAudioFile(val path: String)

/** Default: no-op recorder that returns an empty temp file path (tests / desktop). */
object NoOpAudioRecorder : AudioRecorder {
    override suspend fun recordToFile() = PlatformAudioFile("")
    override suspend fun stopRecording() = Unit
}
```

**Android implementation** (`androidMain`): `AudioRecord` (raw PCM, `VOICE_COMMUNICATION` audio
source) + `MediaCodec` AAC encoder → `.m4a` temp file in `context.cacheDir`. Uses
`VOICE_COMMUNICATION` audio source for system-level noise cancellation. Requests
`AudioManager.AUDIOFOCUS_GAIN_TRANSIENT` before recording and abandons it after.

**iOS implementation** (`iosMain`): `AVAudioRecorder` recording to a `.m4a` URL in
`NSTemporaryDirectory()`. `AVAudioSession.sharedInstance().setCategory(.record)` must be called
before the recorder starts. The session category is restored to `.playback` or `.ambient` after
`stopRecording()` returns.

Temp file cleanup is NOT the responsibility of `AudioRecorder`. `VoiceCaptureViewModel` deletes
the file in a `finally` block after `transcribe()` returns, regardless of success or failure.

## Rationale

**Plain interface over `expect class`**: The `expect class` pattern in KMP requires every
property and method in the expect to mirror in every actual. Adding a capability later forces
all platform actuals to update in lockstep — including a `jvmMain` stub for Desktop, which is
explicitly out of scope. A plain `interface` in `commonMain` avoids this rigidity. `AudioRecorder`
is consumed by exactly one ViewModel (`VoiceCaptureViewModel`), so there is no reason to pay the
overhead of KMP class machinery. This follows the same reasoning that applies to `TopicEnricher`
in ADR-002 (import-topic-suggestions project).

**Temp file over PCM stream**: Real-time transcription display ("result shown only after
processing") is explicitly out of scope in `requirements.md`. A `Flow<ByteArray>` PCM stream
would require reassembly into a file before Whisper upload anyway, adding complexity with no
v1 benefit. Both `AVAudioRecorder` (iOS) and `AudioRecord`+`MediaCodec` (Android) naturally
produce a file. Whisper's 25 MB limit (~26 minutes at 128 kbps AAC) is acceptable for all
realistic single-session recordings.

**`AudioRecord` + `MediaCodec` over `MediaRecorder` on Android**: `MediaRecorder` corrupts MP4
box headers when interrupted by a phone call or audio focus loss because it has no pause/resume
support on API < 24. `AudioRecord` reads raw PCM into a buffer; on interruption the read loop
can be paused and resumed cleanly. The AAC encoding via `MediaCodec` adds ~50 lines but produces
a reliable file in all interruption scenarios. This is a confirmed failure mode from multiple
Android developer reports and is the community recommendation.

**`VOICE_COMMUNICATION` audio source**: Using this `AudioSource` constant (not `DEFAULT`) when
constructing `AudioRecord` applies system-level noise cancellation and echo suppression. This
improves Whisper accuracy in the driving and ambient-noise scenarios that are the primary use
case for this feature.

## Consequences

- `VoiceCaptureViewModel` receives an `AudioRecorder` via constructor injection with
  `NoOpAudioRecorder` as the default.
- `androidMain` provides `AndroidAudioRecorder` (wired in `MainActivity`).
- `iosMain` provides `IosAudioRecorder` (wired in the iOS entry point).
- Desktop (`jvmMain`) uses `NoOpAudioRecorder` by default — voice capture is mobile-only per
  requirements.
- The `VoiceCaptureViewModel` owns temp file lifecycle: creates the path, passes it to the STT
  provider, deletes it in a `finally` block.
- Phase 2 (waveform animation): a `Flow<ByteArray>` secondary method can be added to
  `AudioRecorder` without breaking the primary `recordToFile()` contract.

## Alternatives Considered

**`expect class AudioRecorder`**: Eliminated. Forces `jvmMain` stub even though Desktop is out
of scope. `expect class` rigidity is unjustified for a single-consumer interface.

**`Flow<ByteArray>` PCM output**: Deferred to Phase 2 (waveform animation). Whisper does not
accept raw PCM and reassembly adds complexity not justified by v1 scope.

**`MediaRecorder` on Android**: Eliminated due to confirmed MP4 corruption on audio focus loss.
`AudioRecord` + `MediaCodec` is the reliable alternative.
