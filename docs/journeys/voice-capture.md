---
journey_id: voice-capture
platforms: [desktop, android, ios, web]
jtbd_tier: functional
step_count_target: "1 tap to start recording, 1 tap to stop (or auto-detected end-of-speech); state machine has no dead ends (design/ux.md criteria 13, 15)"
current_step_count:
  desktop: "FIXED (Phase G2, Story G2.1.1): the Idle FAB now renders disabled (MicOff icon, contentDescription \"Voice capture isn't available on this device\") whenever VoicePipelineConfig.isSupported is false — tapping is a no-op and the button never enters Recording at all. Discoverable before any tap, not just after a failed attempt."
  android: "ONLY platform with a real audio-recording backend. 1 tap to start, 1 tap to stop, auto-transcribe/format, Done state with optional TagChipRow. Meets target. Unaffected by this fix (VoicePipelineConfig.isSupported is true — AndroidAudioRecorder is a real AudioRecorder)."
  ios: "FIXED — same as Desktop."
  web: "FIXED — same as Desktop."
heuristic_findings: |
  RE-VERIFIED 2026-07-06 (Task G2.1.1a reconciliation pass, before implementing): the code path
  for a NoOp-backed pipeline does NOT reach Done. `VoiceCaptureViewModel.recordAndTranscribe()`
  (voice/VoiceCaptureViewModel.kt:107) already checked `result.isEmpty` and set
  `VoiceCaptureState.Error(RECORDING, "Microphone permission denied", PERMISSION_DENIED)` before
  ever reaching Transcribing — this check predates this phase (git blame: 2026-04-26) and is
  covered by an existing test (`VoiceCaptureViewModelTest`: "permission denied (empty path) emits
  Error at RECORDING"). So the original claim below ("walks the full state machine to a false
  Done") does not hold against the current code — filed here as an honest correction rather than
  silently reusing the stale claim.
  The underlying GAP-002 finding is still real and still the correct highest-priority G2 target,
  just for a narrower reason: the Idle button was fully tappable and visually identical on all 4
  platforms, and the *only* signal a Desktop/iOS/Web user ever got that voice capture doesn't work
  there was a **misleading** error message — "Microphone permission denied" — surfacing only after
  a brief Recording pulse, when the real cause is "not implemented on this platform," not a denied
  OS permission. A user has no way to know ahead of time, and would reasonably (but incorrectly)
  go check their OS mic permissions in response. This is still the worst visibility-of-system-status
  violation in this journey, and still the same wired-looking-but-non-functional pattern as
  `block.toggle-todo` — just "post-tap misleading error" rather than "false success," which is
  what this fix (below) now closes.

  FIX (Phase G2, Story G2.1.1 — GAP-002): `VoicePipelineConfig.isSupported` (new computed
  property, `voice/VoicePipelineConfig.kt`) is `true` when a real `AudioRecorder` (not
  `NoOpAudioRecorder`) or a `DirectSpeechProvider` is wired, `false` otherwise.
  `VoiceCaptureButton` (`ui/components/VoiceCaptureButton.kt`) takes a new `isSupported: Boolean =
  true` parameter; when `false`, the Idle state renders a disabled FAB (MicOff icon,
  `contentDescription = "Voice capture isn't available on this device"`) instead of the normal
  tappable Mic FAB — the tap handler is a no-op and the Recording/Transcribing states are never
  entered. `App.kt` wires `isSupported = voicePipeline.isSupported` at the one call site. This
  moves the "not available here" signal from a confusing post-tap error message to an honest,
  up-front, disabled affordance — closing the worst part of the visibility-of-system-status and
  discoverability findings below without attempting real Desktop/iOS/Web audio recording (out of
  this phase's appetite).
test_ids: [VoicePipelineConfigTest, VoiceCaptureButtonScreenshotTest.voiceCaptureButton_idle_unsupported_isDisabled, VoiceCaptureButtonScreenshotTest.voiceCaptureButton_idle_supported_isEnabledByDefault]
status: audited
last_verified: 2026-07-06
---

# Voice capture

## Trigger
User taps the voice-capture button to dictate a note instead of typing.

## Platform availability — CONFIRMED via platform-gating investigation (Task A.1.4a)

`VoiceCaptureButton.kt` (commonMain, no platform-specific variants) has **no `expect`/`actual` declarations and no platform conditional anywhere in the composable itself** — it renders identically on all 4 platforms, and its full `Idle → Recording → Transcribing/Formatting → Done | Error` state machine is walkable on all 4.

However, whether the button's tap **actually records real audio** is gated one layer down, by which `AudioRecorder` backs the pipeline:

- `AudioRecorder` interface (`voice/AudioRecorder.kt:11-22`) has a `NoOpAudioRecorder` fallback (lines 24-26) that returns an empty file and no-ops `stopRecording`.
- `VoicePipelineConfig` defaults `audioRecorder = NoOpAudioRecorder()` (`voice/VoicePipelineConfig.kt:48`).
- The **only real implementation anywhere in the repo** is `AndroidAudioRecorder` (`androidMain/kotlin/dev/stapler/stelekit/voice/AndroidAudioRecorder.kt:28`), instantiated only in the separate Android app module (`androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt:243`, `VoiceCaptureWidgetViewModel.kt:82`).
- `App.kt`'s `voicePipeline` parameter (`ui/App.kt:190`) defaults to `VoicePipelineConfig()` (i.e. `NoOpAudioRecorder`), and none of `jvmMain/.../desktop/Main.kt`, `wasmJsMain/.../browser/Main.kt`, or the iOS Swift entry points (`iosApp/iosApp/ContentView.swift`, `iosAppApp.swift`) pass a `voicePipeline` override.

**Confirmed finding, per Task A.1.4a's brief to verify empirically rather than assume**: voice capture is functionally available **only on Android**. On **Desktop, iOS, and Web**, the button renders and is fully tappable — but records against a `NoOpAudioRecorder`, producing an empty file. **Update, re-verified 2026-07-06 before implementing (Task G2.1.1a)**: `VoiceCaptureViewModel.recordAndTranscribe()` already detects the empty file (`result.isEmpty`) and short-circuits straight to an `Error(RECORDING, "Microphone permission denied", PERMISSION_DENIED)` state — it does **not** proceed to Transcribing/Done, so the original claim of "walks the full state machine to a false Done" does not hold against the current code (this check predates this phase; see `VoiceCaptureViewModelTest`'s "permission denied (empty path)" test). The real, still-live problem: the Idle button was fully tappable and visually indistinguishable from the working Android path, and the only signal a Desktop/iOS/Web user got was a **misleading** error message ("Microphone permission denied") after a brief Recording pulse — misleading because the real cause is "not implemented on this platform," not a denied OS permission, so a user has no way to discover the limitation ahead of time and would reasonably misdiagnose it as a permissions problem. This is still the same class of gap as `block.toggle-todo`'s wired-looking-but-non-functional command (features.md §2).

**FIXED (Phase G2, Story G2.1.1)**: the Idle button now checks `VoicePipelineConfig.isSupported` and renders disabled (MicOff icon, honest "isn't available on this device" label) up front on unsupported platforms, rather than only revealing the limitation via a misleading post-tap error. See "Heuristic findings" below for full detail.

## Current step sequence — state machine (`VoiceCaptureButton.kt`, `voice/VoiceCaptureState.kt:9-26`)

1. **Idle** (line ~75) — if `isSupported` (Android, or any platform with a real `AudioRecorder`/`DirectSpeechProvider`): FAB with Mic icon, `contentDescription = "Start recording"`. **If not supported** (Desktop/iOS/Web today): disabled FAB with MicOff icon, `contentDescription = "Voice capture isn't available on this device"` — tap is a no-op, state never leaves Idle.
2. Tap (supported platforms only) → **Recording** — amplitude-driven or fixed-period pulsing FAB, Stop icon, `contentDescription = "Stop recording"`.
3. Tap → **Transcribing**/**Formatting** — disabled FAB with spinner + semantics label (pipeline stages `RECORDING`/`TRANSCRIBING`/`FORMATTING`/`JOURNAL`, `VoiceCaptureState.kt:5`).
4. → **Done** — auto-resets after `DONE_AUTO_RESET_MS` = 5000ms; if `isLikelyTruncated`, shows an explicit "may be incomplete" warning variant rather than silently accepting a partial transcript — a correctly-handled edge case, model for other surfaces per design/ux.md (i). `TagChipRow` renders inline if tags were suggested from the transcript.
5. Or → **Error** — shows message + dismiss button, carrying a `VoiceErrorKind` (`PERMISSION_DENIED`/`NO_GRAPH`/`GENERIC`, `VoiceCaptureState.kt:7`) — always paired with a dismiss action, no dead end. Still reachable on unsupported platforms only via a real permission/network/journal failure once the button becomes supported in the future — not via the NoOp path anymore, since that path is now pre-empted at Idle.

## Notes
- research/ux.md §3's accessibility audit confirms every state-dependent icon in this file already carries a correct, non-null `contentDescription` — no accessibility gap found here.
- The confirmed 3-of-4-platform functional gap above was the primary candidate for Phase G2's single highest-priority voice-mode backlog item (per plan.md's Phase G2 scope, GAP-002 in `gap-backlog.md`) and has now been fixed per the above.

## Heuristic findings

1. **Visibility of system status — BEFORE (as originally audited, since corrected — see note below) / AFTER (fixed, Phase G2).** BEFORE: on Desktop, iOS, and Web, the Idle FAB was fully tappable and visually identical to the working Android path; the only signal of non-support was a post-tap error message reading "Microphone permission denied" — misleading, since the real cause was "not implemented on this platform," not a denied OS permission (re-verified 2026-07-06: the pipeline does short-circuit to this `Error` state rather than reaching a false `Done`, correcting this doc's original claim — but the misleading-message problem was real and was still the worst violation in this journey). AFTER: `VoiceCaptureButton` now takes `isSupported: Boolean` (backed by `VoicePipelineConfig.isSupported`) and renders the Idle state as a disabled MicOff FAB with `contentDescription = "Voice capture isn't available on this device"` when unsupported — the limitation is now visible before any tap, not discoverable only via a confusing error after one.
2. **Consistency and standards — a recurring anti-pattern, now closed for this journey.** The state-machine icons and `contentDescription`s follow standard conventions consistently (`"Start recording"` → `"Stop recording"`, `VoiceCaptureButton.kt`) and research/ux.md §3 confirms no accessibility gap in the icon labels themselves. Before the fix, the appearance of a fully-wired control that was actually inert (modulo the misleading error message) was the same pattern as `block.toggle-todo`'s wired-looking-but-non-functional command (features.md §2) — a product-level consistency problem recurring across journeys. The fix closes this instance by making the button's disabled state visually and semantically distinct (MicOff icon, muted color, disabled semantics) rather than identical to the working control.
3. **Discoverability — BEFORE: mixed / AFTER: platform limitation now discoverable up front.** BEFORE: the entry point itself was well discoverable (standard FAB, clear `contentDescription`), but the *platform limitation* was not — nothing distinguished the 3 non-functional platforms from the 1 working one before a tap. AFTER: the disabled FAB plus its `contentDescription` now surfaces the limitation immediately, without requiring the user to tap first and read (and potentially misinterpret) an error message.
4. **Minimal memory load — positive, unaffected by this fix.** The Error state always pairs its message with a dismiss action and a specific `VoiceErrorKind` (`PERMISSION_DENIED`/`NO_GRAPH`/`GENERIC`), so the user isn't left to infer or recall what went wrong. The Done state's auto-reset after `DONE_AUTO_RESET_MS` = 5000ms, plus the explicit "may be incomplete" warning variant when `isLikelyTruncated` rather than silently accepting a partial transcript, means the user isn't required to remember to manually dismiss the state or to double-check truncation themselves — the system surfaces it.
