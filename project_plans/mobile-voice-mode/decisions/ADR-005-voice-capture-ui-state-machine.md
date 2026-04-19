# ADR-005: Voice Capture UI State Machine

**Status**: Proposed
**Date**: 2026-04-18

## Context

The voice capture pipeline transitions through a series of states from user tap to journal insert.
The UI must reflect each stage clearly so users understand what the system is doing without
requiring active attention (eyes-free use case).

States required: idle (waiting for tap), recording (mic open, audio accumulating), post-recording
processing (transcription), post-transcription processing (LLM formatting), completion, and error.
Users must be able to cancel at any point.

The existing pattern for pipeline state in SteleKit: `sealed interface` state type exposed via
`StateFlow`, a single cancellable `Job` field in the ViewModel. `ImportViewModel.scanJob` is the
reference implementation.

UI placement: `PlatformBottomBar.android.kt` currently has 4 navigation items. The mic button
should be a visually distinct center affordance (FAB-style) rather than a 5th navigation icon,
consistent with Material Design 3 FAB-in-bottom-bar patterns.

## Decision

**State machine:**

```kotlin
// commonMain — voice/VoiceCaptureState.kt
sealed interface VoiceCaptureState {
    data object Idle : VoiceCaptureState
    data object Recording : VoiceCaptureState
    data object Transcribing : VoiceCaptureState
    data object Formatting : VoiceCaptureState
    data class Done(val insertedText: String) : VoiceCaptureState
    data class Error(val stage: PipelineStage, val message: String) : VoiceCaptureState
}

enum class PipelineStage { RECORDING, TRANSCRIPTION, LLM, JOURNAL_INSERT }
```

Valid transitions:
- `Idle` → `Recording`: user taps mic button
- `Recording` → `Transcribing`: user taps stop, or `stopRecording()` is called
- `Transcribing` → `Formatting`: `TranscriptResult.Success` received
- `Transcribing` → `Done`: `TranscriptResult.Empty` (no LLM call, nothing to insert)
- `Transcribing` → `Error`: `TranscriptResult.Failure`
- `Formatting` → `Done`: `LlmResult.Success`, journal insert complete
- `Formatting` → `Error`: `LlmResult.Failure`
- Any state → `Idle`: user cancels, dismisses error, or `Done` auto-resets after 3 seconds

**ViewModel:**

```kotlin
// commonMain — voice/VoiceCaptureViewModel.kt
class VoiceCaptureViewModel(
    private val audioRecorder: AudioRecorder,
    private val sttProvider: SpeechToTextProvider,
    private val llmProvider: LlmFormatterProvider,
    private val journalService: JournalService,
    private val systemPrompt: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VoiceCaptureState>(VoiceCaptureState.Idle)
    val state: StateFlow<VoiceCaptureState> = _state.asStateFlow()

    private var pipelineJob: Job? = null

    fun onMicTapped() { /* Idle → start; Recording → stop */ }
    fun cancel() { pipelineJob?.cancel(); _state.value = VoiceCaptureState.Idle }
    fun dismissError() { _state.value = VoiceCaptureState.Idle }
}
```

**`VoiceCaptureButton` composable** (`commonMain/ui/components/VoiceCaptureButton.kt`):

- `Idle`: mic icon (outlined), normal size
- `Recording`: pulsing red circle + stop icon, `contentDescription = "Stop recording"`
- `Transcribing` / `Formatting`: `CircularProgressIndicator` replacing the icon, disabled
- `Done`: checkmark icon, auto-resets to `Idle` after 3 seconds via `LaunchedEffect`
- `Error`: error icon (red); tapping dismisses to `Idle`

The button is added to `PlatformBottomBar` as a center floating element, respecting the
`isLeftHanded` flag for position adjustment.

**`PlatformBottomBar` modification:** The button is passed in as a composable slot parameter
rather than hardcoded, to keep `PlatformBottomBar` testable and avoid coupling it to
`VoiceCaptureViewModel`:

```kotlin
@Composable
expect fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onSearch: () -> Unit,
    isLeftHanded: Boolean,
    voiceCaptureButton: @Composable () -> Unit = {},   // new slot
)
```

`GraphContent` passes `voiceCaptureButton = { VoiceCaptureButton(voiceCaptureViewModel.state, ...) }`.

## Rationale

**Sealed interface state**: Exhaustive `when` expressions in the composable are guaranteed by the
compiler — no `else` branch needed, no missed state. Adding a new state (e.g., `Paused` for Phase
3 foreground service) is a compile-time breaking change that forces all consumers to handle it.

**Separate `PipelineStage` enum in `Error`**: Users and logs need to know where the pipeline
failed (recording, transcription, LLM formatting, or journal insert). A string message alone
is insufficient for actionable UX ("Recording was interrupted — tap to retry" vs "Transcription
failed — check your API key").

**Single cancellable `Job`**: Mirrors `ImportViewModel.scanJob`. The entire pipeline runs inside
one `Job`; `cancel()` propagates `CancellationException` through the pipeline and triggers
`finally` blocks (which clean up the temp audio file). No additional synchronization needed.

**Auto-reset from `Done`**: Users should be able to immediately start a new recording after
completion. A 3-second `Done` state shows the confirmation, then `Idle` is restored
automatically. If the user taps during `Done`, the reset fires immediately.

**`VoiceCaptureButton` as a slot in `PlatformBottomBar`**: Keeps `PlatformBottomBar` unaware of
the voice pipeline. The ViewModel is created in `GraphContent` and passed down as a composable
lambda. This is the same pattern as other bottom bar callbacks (`onSearch`, `onNavigate`).

**Phase gates encoded in state semantics**:
- Phase 1 (Story 1): `Formatting` state is entered but `NoOpLlmFormatterProvider` returns
  immediately — the user sees a brief `Formatting` flash before `Done`. Acceptable for Story 1.
- Phase 2: `AndroidMlKitSpeechToTextProvider` replaces Whisper on supported devices; no state
  changes needed.
- Phase 3: A `Paused` state can be added for foreground service scenarios (recording paused
  by phone call interruption). All existing `when` branches compile-error until handled.

## Consequences

- `VoiceCaptureViewModel` is created in `GraphContent` alongside `JournalsViewModel` and
  `AllPagesViewModel` using the `remember { }` pattern.
- `PlatformBottomBar` signature adds `voiceCaptureButton: @Composable () -> Unit = {}` — a
  backward-compatible default.
- Both `PlatformBottomBar.android.kt` and any `iosMain` actual must handle the new slot.
- Lifecycle: `VoiceCaptureViewModel.cancel()` must be called from the `DisposableEffect` in
  `GraphContent` on `Lifecycle.Event.ON_PAUSE` to stop recording if the app is backgrounded
  mid-capture (Phase 1 foreground-only).
- The `Done` auto-reset uses `LaunchedEffect(state)` in `VoiceCaptureButton` — no timer
  management in the ViewModel.
- `VoiceCaptureState` must be in `commonMain` — iOS and Android share the same state type.

## Alternatives Considered

**FAB outside `PlatformBottomBar`**: A separate `FloatingActionButton` composable added directly
in `MainLayout` would avoid modifying `PlatformBottomBar`. Eliminated because it requires
`MainLayout` to know about voice capture, breaking its current separation of concerns.

**`Paused` state in v1**: Eliminated. Background recording is Phase 3 scope. Adding `Paused`
prematurely adds `when` branches to all consumers without delivering value.

**Error with retry**: The `Error` state includes `stage` to support a "Retry from [stage]"
action in a future story. For v1, tapping the error dismisses to `Idle` — the user retaps the
mic to start a fresh recording.
