# Validation Plan: camera-qr-export

**Date**: 2026-07-11

## Happy Path Scenario

Given two Android devices running SteleKit with `QrTransferSettings.enabled = true`
and no network, Bluetooth, or shared account path between them (the air-gap
baseline from requirements.md), when the user opens a page on Device A, taps
"Send via QR" to display the animated inset-card QR code, and points Device
B's "Import via camera" scanner at the screen, then Device B reconstructs the
page's markdown byte-identically (`Block.contentHash` set matches the
original) and imports it into its own graph, completing within roughly
30–60 seconds — this is the v1 fidelity gate (ADR-003, plan.md Phase 3,
Story 3.3.1). Error paths (oversized payload, stalled/wrong-code scans, name
collisions, cancellation, permission denial) are variations on this core
round trip, not equal-priority scope.

---

## Requirement → Test Mapping

Organized by plan.md Epic/Story. `N/A` rows are explicit per Step 5/Step 1 —
they record a deliberate absence (pure compile-time guarantee, manual-only
task, or deferred epic) rather than a silently skipped test.

### Epic 1.1 — Round-trippable serialization + error types + air-gap guard

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 1.1.1: `LogseqPageSerializer` extraction | `commonTest/.../db/LogseqPageSerializerTest.kt` | `serialize_should_ProduceCanonicalMarkdown_When_PageHasNestedBlocksAndProperties` | Unit | Happy path — tab-indented bullets + `tags:: a,b`, matches pre-extraction `GraphWriter.buildMarkdown` output byte-for-byte |
| Story 1.1.1 | `commonTest/.../db/LogseqPageSerializerTest.kt` | `serialize_should_MatchPriorGraphWriterOutput_When_ExistingGraphWriterTestsRerun` | Unit | Regression/"error" path — no behavioral change to disk writes after delegation; existing `GraphWriter` suite stays green |
| Story 1.1.1 | `commonTest/.../db/LogseqPageSerializerTest.kt` | `serialize_should_RoundTripThroughOutlinerPipeline_When_ContentHashesCompared` | Integration | Task 1.1.1c — `serialize` → `OutlinerPipeline` parse → `Block.contentHash` set equality (the lossless gate) |
| Story 1.1.2: `QrTransferError` sealed type | `commonTest/.../error/DomainErrorTest.kt` | `toUiMessage_should_ReturnUserFacingSizeMessage_When_PayloadTooLarge` | Unit | Happy path — `PayloadTooLarge(90000, 65536).toUiMessage()` returns a plain-language string, not a raw byte dump |
| Story 1.1.2 | `commonTest/.../error/DomainErrorTest.kt` | `toUiMessage_should_ReturnFourDistinctMessages_When_CalledForEveryQrTransferErrorVariant` | Unit | Error/exhaustiveness path — no two of the (now four, post dead-code-removal — see plan.md Domain Glossary) variants share identical UI copy (also the compiler enforces no `else` branch) |
| Story 1.1.2 | — | — | N/A | No external dependency in a pure sealed-type mapping; no integration test warranted |
| Story 1.1.3: Newtypes (`TransferId`/`ChunkIndex`/`PayloadChecksum`/`ChunkChecksum`) | `commonTest/.../transfer/TransferTypesTest.kt` | `transferId_should_ExposeUnderlyingIntValue_When_Constructed` | Unit | Happy path — value-class construction/equality/hashCode behave as expected |
| Story 1.1.3 | — | — | N/A | "Error path" is a **compile error** (`fun frameFor(index: ChunkIndex)` rejects a `PayloadChecksum` argument) — enforced by Kotlin's type system, not a runtime test |
| Story 1.1.3 | — | — | N/A | No external dependency |
| Story 1.1.4: CRC32 + `AirGapGuardTest` | `commonTest/.../transfer/Crc32Test.kt` | `crc32Of_should_ReturnStandardCheckValue_When_GivenKnownTestVector` | Unit | Happy path — ASCII `"123456789"` → `0xCBF43926` |
| Story 1.1.4 | `commonTest/.../transfer/Crc32Test.kt` | `crc32Of_should_ReturnDifferentValue_When_SingleByteFlipped` | Unit | Error path — tamper detection sensitivity (single-bit flip changes the checksum) |
| Story 1.1.4 | `jvmTest/.../transfer/AirGapGuardTest.kt` | `airGapGuardTest_should_FailWithOffendingFilePath_When_TransferPackageImportsNetworkDependency` | Integration | Scans real `transfer/` sources on disk for forbidden imports (`io.ktor`, git-sync, Google API) — structural, filesystem-backed, models `MigrationRunnerSchemaSyncTest` |

### Epic 1.2 — Fountain codec + chunk buffer

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 1.2.1: `FountainChunk` + `ChunkFrameCodec` | `commonTest/.../transfer/qrcode/ChunkFrameCodecTest.kt` | `chunkFrameCodec_should_RoundTripFountainChunk_When_ValidBytesEncodedAndDecoded` | Unit | Happy path — `decode(encode(chunk))` equals the original `FountainChunk` |
| Story 1.2.1 | `commonTest/.../transfer/qrcode/ChunkFrameCodecTest.kt` | `chunkFrameCodec_decode_should_ReturnNull_When_MagicByteOrVersionOrChunkCrcIsInvalid` | Unit | Error path — flipped magic byte, wrong version, or failing `chunkCrc` all yield `null`, never a partially-valid chunk |
| Story 1.2.1 | — | — | N/A | Pure Layer-1 code, no platform/I/O dependency (per plan.md's "no I/O, no platform deps") |
| Story 1.2.2: `FountainEncoder` | `commonTest/.../transfer/qrcode/FountainEncoderTest.kt` | `parts_should_MatchBcUrReferenceForFixedSeed_When_FirstFivePartsTaken` | Unit | Happy path — `ChunkIndex` sequence `0,1,2,3,4`, part 0 equals the BC-UR reference part-0 |
| Story 1.2.2 | `commonTest/.../transfer/qrcode/FountainEncoderTest.kt` | `constructor_should_ReturnPayloadTooLarge_When_PayloadExceedsMaxPayloadBytes` | Unit | Error path — 200 000-byte payload against a 65 536-byte cap returns `Left(PayloadTooLarge(200000, 65536))`, no allocation |
| Story 1.2.2 | — | — | N/A | Pure Layer-1 code |
| Story 1.2.3: `FountainDecoder` + `ChunkBuffer` proof gate | `commonTest/.../transfer/qrcode/ChunkBufferTest.kt` | `reassemble_should_ReturnVerifiedTransferPayload_When_PartsArriveOutOfOrderWithOneDuplicate` | Unit | Happy path — idempotent/order-independent acceptance, sufficient coverage yields `Right(VerifiedTransferPayload(...))` |
| Story 1.2.3 | `commonTest/.../transfer/qrcode/ChunkBufferTest.kt` | `reassemble_should_ReturnIntegrityCheckFailed_When_ReassembledCrcMismatchesHeaderPayloadCrc` | Unit | Error path — CRC32 mismatch never yields a `VerifiedTransferPayload`; also covers `isComplete()==true` not implying success |
| Story 1.2.3 | `commonTest/.../transfer/qrcode/ChunkBufferTest.kt` | `accept_should_RejectFrame_When_ClaimedPayloadLenExceedsMaxPayloadBytes` | Unit | Additional error path — bounded allocation guard; a 5 MB claim against a 64 KB cap is rejected before any buffer is allocated |
| Story 1.2.3 | — | — | N/A | Pure Layer-1 code, in-memory only |
| Story 1.2.4: BC-UR reference vector cross-validation | `commonTest/.../transfer/qrcode/FountainCodecVectorTest.kt` | `fountainEncoder_should_MatchPublishedBcUrReferenceFragmentBytes_When_ReplayingVectors` | Unit | Happy path — official BC-UR vectors match part bytes exactly; decoder reassembles the reference payload |
| Story 1.2.4 | `commonTest/.../transfer/qrcode/FountainCodecRoundTripTest.kt` | `reassemble_should_ReconstructExactPayload_When_SimulatedChannelDrops30PercentFramesRandomOrder` | Unit | Error/property path — lossy-channel property test over a random 2 KB payload |
| Story 1.2.4 | `commonTest/.../transfer/qrcode/FountainCodecVectorTest.kt` + `FountainCodecRoundTripTest.kt` (combined) | `fountainCodec_should_EncodeAndDecodeConsistently_When_EncoderAndDecoderComposedEndToEnd` | Integration | Cross-component (encoder + decoder combined, simulated lossy channel) — this pair of tests **is** Epic 1.2's integration coverage since Layer 1 has no external I/O to integrate against |

### Epic 2.1 — `FrameTransport` seam + `QrCodec` + chunk sizing + `QrFrameTransport`

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 2.1.1: `FrameTransport` Strategy interface | `commonTest/.../transfer/FrameTransportSignatureTest.kt` | `frameTransportSender_should_ExposeSuspendSendOfByteArrayFlow_When_InterfaceInspected` | Unit | Happy path — reflection-based signature check: `send` is `suspend`, takes `Flow<ByteArray>` |
| Story 2.1.1 | `commonTest/.../transfer/FrameTransportSignatureTest.kt` | `frameTransport_should_ContainNoQrSpecificTypesInSignature_When_InterfaceInspected` | Unit | Error/negative-space path — asserts absence of `QrMatrix`/`CameraFrame` from `FrameTransportSender`/`FrameTransportReceiver` signatures (keeps the seam medium-neutral) |
| Story 2.1.1 | — | — | N/A | Paper-validation against a hypothetical `AudioTransport` is a KDoc review artifact, not a runtime test |
| Story 2.1.2: `QrCodec` expect/actual + `QrMatrix` (ZXing) | `jvmTest/.../transfer/qrcode/QrCodecJvmTest.kt` | `decode_should_ReturnOriginalBytes_When_EncodedMatrixReRenderedAndDecoded` | Unit | Happy path — `encode([1,2,3])` → rendered bitmap → ZXing decode returns `[1,2,3]` |
| Story 2.1.2 | `jvmTest/.../transfer/qrcode/QrCodecJvmTest.kt` | `decode_should_ApplyRotationBeforeScanning_When_CameraFrameRotationDegreesIs90` | Unit | Error/edge path — guards the documented rotation-drift failure; a frame with `rotationDegrees=90` still decodes correctly |
| Story 2.1.2 | `jvmTest/.../transfer/qrcode/QrCodecJvmRoundTripTest.kt` | `qrCodec_should_MatchOriginalBytes_When_EncodedRenderedAndDecodedSameProcess` | Integration | ZXing is an external library — encode→bitmap→decode same-process round trip (also serves as Task 4.1.1b's automated analogue to the manual cross-device demo) |
| Story 2.1.3: Encoder chunk sizing + max-payload spike (UQ-1) | `commonTest/.../transfer/qrcode/FountainEncoderPreflightTest.kt` | `preflight_should_ReportEstimatedChunkCountAndByteSize_When_PageIsTwoKilobytes` | Unit | Happy path — `ceil(payloadLen/fragLen) × redundancy` estimate exposed for UI before any frame is displayed |
| Story 2.1.3 | `commonTest/.../transfer/qrcode/FountainEncoderPreflightTest.kt` | `preflight_should_FlagOversizeBeforeSending_When_EstimatedFrameCountExceedsThreshold` | Unit | Error path — fail-fast pre-flight rejection, no partial/broken QR ever renders |
| Story 2.1.3 | Task 2.1.3a (manual spike) | — | Manual | **Empirical spike, not automatable**: render QR at increasing `fragLen` × EC-level {L,M,Q}, scan from ~30–50 cm on a mid-range Android device, record largest reliably-decoding size; result recorded in `QrTransferSettings` KDoc |
| Story 2.1.4: `QrFrameTransport` adapter (creates `QrScanner`) | `commonTest/.../transfer/qrcode/QrScannerTest.kt` | `decode_should_ReturnDecoded_When_FrameContainsValidSteleKitChunk` | Unit | Happy path — `QrCodec.decode` → `ChunkFrameCodec.decode` succeeds, `ScanResult.Decoded(chunk)` returned |
| Story 2.1.4 | `commonTest/.../transfer/qrcode/QrScannerTest.kt` | `decode_should_ReturnNotSteleKitCodeOrNoCodeDetected_When_FrameIsWrongMagicOrHasNoQr` | Unit | Error path — differentiates "QR found, wrong magic/version" from "no QR at all" (the two `ScanResult` failure cases `QrDecodeUiState`'s `hint` depends on) |
| Story 2.1.4 | `commonTest/.../transfer/qrcode/QrFrameTransportTest.kt` | `qrFrameTransport_should_ReconstructPayload_When_SendOutputFedIntoFramesViaFakeCameraFrameSource` | Integration | Fake `CameraFrameSource` + in-process `QrCodec` fixture — proves the seam is real: `send(...)` output round-trips through `frames()` with no direct `QrCodec`/`QrScanner` calls above the adapter |

### Epic 2.2 — `CameraFrameSource` interface + Android frame source

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 2.2.1: `CameraFrameSource` + `CameraFrame` + No-op + `SensorModule` | `commonTest/.../platform/sensor/NoOpCameraFrameSourceTest.kt` | `noOpCameraFrameSource_should_ReturnEmptyFlowAndUnavailable_When_FrameStreamCollected` | Unit | Happy path — `NoOpCameraFrameSource.isAvailable == false`, `frameStream()` is `emptyFlow()` |
| Story 2.2.1 | `commonTest/.../platform/sensor/SensorModuleTest.kt` | `sensorModule_should_RejectPreflightWithHardwareUnavailable_When_NoCameraFrameSourceWired` | Unit | Error path — on a JVM process with no wiring, decode pre-flight rejects with `HardwareUnavailable` before entering `Scanning` |
| Story 2.2.1 | — | — | N/A | No hardware/platform dependency involved in the no-op path |
| Story 2.2.2: `AndroidCameraFrameSource` (CameraX `ImageAnalysis`) | `androidUnitTest/.../platform/sensor/AndroidCameraFrameSourceTest.kt` | `frameStream_should_EmitCameraFrameWithRotationDegrees_When_PermissionGrantedAndQrOnScreen` | Unit | Happy path — `CameraFrame`s emit at camera rate with correct `rotationDegrees` (Robolectric/fake `ImageProxy`) |
| Story 2.2.2 | `androidUnitTest/.../platform/sensor/AndroidCameraFrameSourceTest.kt` | `frameStream_should_EmitPermissionDeniedSensorError_When_CameraPermissionDenied` | Unit | Error path — flow emits `Left(SensorError.PermissionDenied("camera"))` and completes |
| Story 2.2.2 | `androidUnitTest/.../platform/sensor/AndroidCameraFrameSourceTest.kt` | `frameStream_should_UnbindImageAnalysisUseCase_When_CollectorCancels` | Integration | Exercises real CameraX `ImageAnalysis` use-case bind/unbind lifecycle — an external Android-framework dependency, not pure Kotlin; asserts no leak on flow cancellation |

### Epic 3.1 — Encoder (send) ViewModel + display UI

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 3.1.1: `QrTransferSettings` feature flag | `commonTest/.../transfer/qrcode/QrTransferSettingsTest.kt` | `qrTransferSettings_should_DefaultEnabledToFalse_When_ConstructedWithoutOverrides` | Unit | Happy path — flag defaults off, gating both entry points |
| Story 3.1.1 | `commonTest/.../transfer/qrcode/QrTransferSettingsTest.kt` | `constructor_should_RejectFramesPerSecond_When_RequestedValueExceeds3_NotClamp` | Unit | Error path — 8 fps rejects with an error at construction and stores nothing; explicitly NOT silently clamped to 3 |
| Story 3.1.1 | `jvmTest/.../transfer/qrcode/QrTransferSettingsPersistenceTest.kt` | `qrTransferSettings_should_PersistAcrossInstances_When_BackedByRealPlatformSettings` | Integration | Real `platform.Settings` storage backend (per `TagSettings` precedent) — round-trips `enabled`/`framesPerSecond`/`reduceMotion` across process-like instance boundaries |
| Story 3.1.2: `QrEncodeViewModel` + `QrEncodeUiState` | `businessTest/.../ui/transfer/QrEncodeViewModelTest.kt` | `start_should_TransitionIdleSerializingDisplaying_When_PageHasThreeBlocksAtDefaultFps` | Unit | Happy path — virtual clock, `Idle→Serializing→Displaying(frameIndex=0,...)`, advances one frame per ~400 ms tick |
| Story 3.1.2 | `businessTest/.../ui/transfer/QrEncodeViewModelTest.kt` | `start_should_TransitionToFailed_When_SerializedPayloadExceedsMaxPayloadBytes` | Unit | Error path — `Serializing → Failed(PayloadTooLarge)`, no `Displaying` frame ever emitted (UX gap G1) |
| Story 3.1.2 | `businessTest/.../ui/transfer/QrEncodeViewModelTest.kt` | `pause_should_FreezeFrameIndex_And_resume_should_ContinueAtSameIndex_When_LifecycleTogglesMidDisplay` | Unit | Additional — `Displaying(frameIndex=4)→Paused(frameIndex=4)→Displaying(frameIndex=4)`, position preserved (UX gaps G2/G3) |
| Story 3.1.2 | `businessTest/.../ui/transfer/QrEncodeViewModelTest.kt` | `cancel_should_StopLoopWithinOneTick_And_close_should_CancelScopeWithoutForgottenScopeException` | Integration | Wires the real scope-owning ViewModel to `QrFrameTransport.send(...)` (Story 2.1.4) end-to-end via a fake repository, verifying the VM never calls `FountainEncoder`/`ChunkFrameCodec`/`QrCodec.encode` directly |
| Story 3.1.3: Encoder Compose screen | `jvmTest/.../ui/transfer/QrEncodeScreenTest.kt` | `qrEncodeScreen_should_RenderInsetCardAndPreflightSummary_When_StateIsDisplaying` | Unit | Happy path (`ComposeTestRule`) — "Meeting Notes · 5 blocks · ~2 KB · ~12 frames" text + inset card + persistent "No internet connection used" line render for `Displaying` |
| Story 3.1.3 | `jvmTest/.../ui/transfer/QrEncodeScreenTest.kt` | `qrEncodeScreen_should_ShowDoneSendingCopyWithoutConfirmedDeliveryClaim_When_StateIsComplete` | Unit | Error/edge path (`ComposeTestRule`) — `Complete` copy reads "Sent — ask the other device to confirm it imported," never implying confirmed delivery |
| Story 3.1.3 | — | — | N/A | Full-flow rendering coverage is in **UX Acceptance Tests** below (criteria 7, 13, 16) rather than duplicated here |
| Story 3.1.4: Send entry point (page menu, flag-gated) | `jvmTest/.../ui/transfer/QrTransferEntryPointsTest.kt` | `pageMenu_should_ShowSendViaQrAction_When_QrTransferSettingsEnabled` | Unit | Happy path (`ComposeTestRule`) — menu item present and launches `QrEncodeScreen` |
| Story 3.1.4 | `jvmTest/.../ui/transfer/QrTransferEntryPointsTest.kt` | `pageMenu_should_OmitSendViaQrAction_When_QrTransferSettingsDisabled` | Unit | Error path — item is **absent**, not disabled/greyed, per Story 3.1.4 AC |
| Story 3.1.4 | — | — | N/A | Covered by UX criterion 1 below |

### Epic 3.2 — Decoder (receive) ViewModel + scan UI + import

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 3.2.1: `QrImportService` | `businessTest/.../transfer/qrcode/QrImportServiceTest.kt` | `import_should_ReturnRightPageName_When_NoCollisionAndValidVerifiedTransferPayload` | Unit | Happy path — parses via `OutlinerPipeline`, writes via `DatabaseWriteActor.savePage`/`saveBlocks`, returns `Right(PageName("Meeting Notes"))` |
| Story 3.2.1 | `businessTest/.../transfer/qrcode/QrImportServiceTest.kt` | `import_should_ReturnMarkdownParseFailed_When_ChecksumValidButOutlinerPipelineCannotParse` | Unit | Error path — checksum-valid but unparsable markdown returns a distinct terminal `Left`, never treated as success |
| Story 3.2.1 | `businessTest/.../transfer/qrcode/QrImportServiceTest.kt` | `import_should_DeleteOrphanedPage_When_SaveBlocksFailsAfterSavePageSucceeds` | Unit | Additional error path — compensating rollback; a subsequent query finds no orphaned zero-block page |
| Story 3.2.1 | `businessTest/.../transfer/qrcode/QrImportServiceTest.kt` | `import_should_WriteOnlyThroughDatabaseWriteActor_When_PageNameContainsPathTraversalCharacters` | Integration | Exercises real `DatabaseWriteActor`/DB write path — a name like `/etc/passwd` or `../etc` is validated/normalized and never used to construct a raw filesystem path (adversarial concern) |
| Story 3.2.2: `QrDecodeViewModel` + `QrTransferCoordinator` + `TransferSession` + `QrDecodeUiState` | `businessTest/.../transfer/qrcode/QrTransferCoordinatorTest.kt` | `start_should_TransitionScanningReassemblingImportingSuccess_When_StreamDeliversEnoughPartsForPageBody` | Unit | Happy path — `QrImportService` invoked only after `reassemble()` yields `Right(VerifiedTransferPayload)` |
| Story 3.2.2 | `businessTest/.../ui/transfer/QrDecodeViewModelTest.kt` | `start_should_TransitionDirectlyToPreflightFailed_When_CameraFrameSourceIsUnavailable` | Unit | Error path — skips `Scanning` entirely when hardware is unavailable |
| Story 3.2.2 | `businessTest/.../ui/transfer/QrDecodeViewModelTest.kt` | `frameLoop_should_SurfaceFailedState_NotCrashProcess_When_OutOfMemoryErrorThrownDuringCollection` | Unit | Additional error path — CLAUDE.md Throwable rule; `CoroutineExceptionHandler` converts an `OutOfMemoryError` into `Failed`, not a process kill |
| Story 3.2.2 | `businessTest/.../transfer/qrcode/QrTransferCoordinatorTest.kt` | `coordinator_should_ReconstructPayloadAndDeriveWrongCodeHint_When_UsingFakeFrameTransportReceiverAndFakeQrScanner` | Integration | Two constructor collaborators wired independently (Task 3.2.2d) — fake `FrameTransportReceiver` (data path) + fake `QrScanner` (diagnostics path); asserts a faked `NotSteleKitCode` yields `hint=WrongCode` with zero effect on `ChunkBuffer` |
| Story 3.2.3: Decoder Compose screen | `jvmTest/.../ui/transfer/QrDecodeScreenTest.kt` | `qrDecodeScreen_should_ShowFragmentCountCopyAndHapticTick_When_StateIsScanningWithHintNull` | Unit | Happy path (`ComposeTestRule`) — "Receiving… (7 fragments)" + tick fired on the 7th new fragment |
| Story 3.2.3 | `jvmTest/.../ui/transfer/QrDecodeScreenTest.kt` | `qrDecodeScreen_should_ShowWrongCodeCopy_DistinctFromStallCopy_When_HintIsWrongCode` | Unit | Error path — `hint=WrongCode` renders "That's not a SteleKit transfer code," never the generic stall message |
| Story 3.2.3 | — | — | N/A | Covered by UX criteria 8, 13, 14 below |
| Story 3.2.4: Permission + collision dialogs | `jvmTest/.../ui/transfer/QrImportConfirmDialogTest.kt` | `dialog_should_OfferKeepBothOverwriteCancel_When_PageNameCollisionDetected` | Unit | Happy path (`ComposeTestRule`) — three explicit choices rendered, no sub-dialogs |
| Story 3.2.4 | `jvmTest/.../ui/transfer/QrImportConfirmDialogTest.kt` | `dialog_should_BlockDismissRequestAndKeepCancelTappable_When_ChosenWriteIsInFlight` | Unit | Error/edge path — `onDismissRequest` guarded during write; only the two write-triggering buttons disable, `Cancel` stays tappable |
| Story 3.2.4 | — | — | N/A | Covered by UX criteria 4, 10 below |

### Epic 3.3 — Round-trip validation + resilience

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 3.3.1: Automated round-trip fidelity test (success-metric gate) | `commonTest/.../transfer/qrcode/QrRoundTripFidelityTest.kt` | `pipeline_should_PreserveAllBlockContentHashes_When_TwentyBlockFixturePageEncodedThroughLossyChannelAndReassembled` | Unit (also the story's E2E assertion) | Happy path — 20-block fixture with nested properties survives a 25%-drop channel byte-identically |
| Story 3.3.1 | `commonTest/.../transfer/qrcode/QrRoundTripFidelityTest.kt` | `pipeline_should_PreserveMultiByteUtf8Block_When_CodepointSplitsAcrossChunkBoundary` | Unit | Error/edge path — the "café ☕ — note" fixture guards codepoint-aware chunking (a naive byte-split would corrupt UTF-8) |
| Story 3.3.1 | `commonTest/.../transfer/qrcode/QrRoundTripFidelityTest.kt` | (same two tests above) | Integration | This story's own tests **are** the full-pipeline integration coverage: `serialize → FountainEncoder → simulated lossy channel → FountainDecoder → GraphLoader.importMarkdownString → OutlinerPipeline` |
| Story 3.3.2: Stall-timer behavior + cancel/backgrounding resilience (UQ-3) | `businessTest/.../transfer/qrcode/TransferSessionTest.kt` | `transferSession_should_TrackLastNewFragmentTimeAndEmitStalledHint_When_NoNewFragmentsFor8Seconds` | Unit | Happy path — stall-timer behavior added to the existing `TransferSession` aggregate (not a new type) |
| Story 3.3.2 | `businessTest/.../transfer/qrcode/QrTransferCoordinatorTest.kt` | `coordinator_should_TransitionCancelledWithNoWrite_When_UserCancelsDuringScanning` | Unit | Error path — cancel tears down the coordinator scope; no write occurs |
| Story 3.3.2 | `androidUnitTest/.../ui/transfer/QrDecodeViewModelBackgroundingTest.kt` | `transferSession_should_PreserveAccumulatedFragments_When_AppBackgroundedAndForegroundedWithinSameVmLifetime` | Integration | Simulates Android lifecycle background/foreground within the same VM (UQ-3 decision) — 5 fragments persist and scanning resumes without reset |
| Story 3.3.3: Structured transfer logging | `businessTest/.../ui/transfer/QrDecodeViewModelTest.kt` | `receive_should_EmitTransferEndedLogWithFrameCounts_When_ReceiveCompletesSuccessfully` | Unit | Happy path — `qr_transfer_ended{role=receiver, outcome=success, elapsedMs≈34000, framesDecoded=12}` via an injected logger spy |
| Story 3.3.3 | `businessTest/.../ui/transfer/QrEncodeViewModelTest.kt` | `send_should_EmitTransferEndedLogWithOutcomeCancelled_When_UserCancelsMidSend` | Unit | Error path — `outcome=cancelled` logged with frames-sent count, not silently dropped |
| Story 3.3.3 | — | — | N/A | Log emission is fully unit-testable via an injected logger spy; no external logging backend to integrate against (requirements.md: no new alerting/oncall) |
| Story 3.3.4: Concurrent-sender rejection + version-mismatch handling | `commonTest/.../transfer/qrcode/ChunkBufferConcurrencyTest.kt` | `chunkBuffer_should_IgnoreFrame_When_TransferIdDiffersFromActiveSession` | Unit | Happy path — a frame for `TransferId(9)` is ignored (logged) while bound to `TransferId(7)` |
| Story 3.3.4 | `commonTest/.../transfer/qrcode/ChunkFrameCodecTest.kt` | `decode_should_ReturnNullAndCountAsRejected_When_VersionByteIsUnknownFutureVersion` | Unit | Error path — `version=0x02` decodes to `null`, counted as a rejection, not silently accepted |
| Story 3.3.4 | — | — | N/A | Pure Layer-1 code, in-memory only |
| Story 3.3.5: Hardware-in-the-loop reliability pass | Task 3.3.5a (manual) | — | Manual | **Not automatable** — ≥20 real end-to-end transfers across varied lighting/distance/angle on real hardware; confirms no corrupted page ever passes the CRC32 proof gate; results tune `maxFragmentBytes`/`framesPerSecond` defaults (UQ-1/UQ-2) |

### Epic 4.1 — Desktop (JVM) send

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 4.1.1: Desktop send wiring | `jvmTest/.../ui/transfer/QrTransferEntryPointsTest.kt` | `desktopMenu_should_ShowSendViaQrAction_When_EnabledOnJvm` | Unit | Happy path — menu wiring present on the desktop entry-point host |
| Story 4.1.1 | `jvmTest/.../transfer/qrcode/QrCodecJvmTest.kt` | `qrCodec_encode_should_RejectOrFail_When_PayloadExceedsFrameCapacity` | Unit | Error path — encode-time guard before Canvas ever attempts to render an oversized matrix |
| Story 4.1.1 | `jvmTest/.../transfer/qrcode/QrCodecJvmRoundTripTest.kt` | `qrCodec_should_MatchOriginalBytes_When_EncodedRenderedAndDecodedSameProcess` | Integration | Task 4.1.1b — automated analogue to the manual cross-device demo (see also Story 2.1.2's integration row; same test satisfies both) |

### Epic 4.2 — iOS send

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Story 4.2.1: iOS `QrCodec` encode actual + send wiring | `iosTest/.../transfer/qrcode/QrCodecIosTest.kt` | `encode_should_ProduceQrMatrix_When_EncodingFixturePayloadViaCoreImage` | Unit | Happy path — CoreImage `CIQRCodeGenerator` produces a valid `QrMatrix` |
| Story 4.2.1 | `iosTest/.../transfer/qrcode/QrCodecIosTest.kt` | `decode_should_ThrowNotImplementedError_When_DecodeCalled_BecauseReceiveIsDeferred` | Unit | Error path — decode is explicitly unimplemented in v1 (Epic 4.4 deferral), not silently returning `null` as if "no QR found" |
| Story 4.2.1 | `iosTest/.../transfer/qrcode/QrCodecIosRoundTripTest.kt` | `qrCodecIos_should_ProduceStructurallyValidMatrix_When_EncodedAgainstSharedFixture` | Integration | Task 4.2.1b — same-process structural assertion in the interim (full decode-back round trip blocked on Epic 4.4) |

### Epic 4.3 / 4.4 — Deferred (not v1 scope)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Epic 4.3: Desktop receive (webcam) | — | — | N/A | **Explicitly deferred** per plan.md ("Not required for v1 sign-off") — no tests designed; revisit when this epic is scheduled |
| Epic 4.4: iOS receive (AVFoundation) | — | — | N/A | **Explicitly deferred** per plan.md — no tests designed; revisit when this epic is scheduled |

---

## UX Acceptance Tests

Each row maps 1:1 to a numbered criterion in `design/ux.md` §12 (16 criteria
total). This is a Compose Multiplatform app, not a web app — `ComposeTestRule`
(JVM/Android UI test) is the automatable tool; criteria that require real
optics, real assistive technology, or physical timing use a **manual
checklist** instead of assuming browser automation (Playwright does not apply
here).

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Send via QR in ≤2 taps (S1→S2/S3) | `jvmTest/.../ui/transfer/QrSendEntryPointUxTest.kt` | `sendViaQr_should_ReachEncoderScreen_When_PageMenuThenSendViaQrTapped` | `ComposeTestRule` | Launch page screen (flag enabled) → tap page menu (⋮) [tap 1] → tap "Send via QR" [tap 2] → assert `QrEncodeScreen` composed; fail if more than 2 taps required |
| 2. Import via camera in ≤2 taps (S7→S6/S8) | `jvmTest/.../ui/transfer/QrImportEntryPointUxTest.kt` | `importViaCamera_should_ReachPermissionOrDecoderScreen_When_ImportMenuThenImportViaCameraTapped` | `ComposeTestRule` | Tap Import ▾ menu [tap 1] → tap "Import via camera" [tap 2] → assert S6 (permission) or S8 (`Idle`/`PreflightFailed`) is shown |
| 3. Cancel in exactly 1 tap (S3, S4 Paused, S9) | `jvmTest/.../ui/transfer/QrCancelUxTest.kt` | `cancel_should_TransitionToCancelled_When_SingleTapOnCancelButton_ParameterizedOverDisplayingPausedAndScanning` | `ComposeTestRule` | Parameterized over `Displaying`, `Paused`, `Scanning`; render each, tap `[Cancel]` once, assert `Cancelled` reached with no confirmation dialog interposed |
| 4. Collision resolved in 1 tap (S11) | `jvmTest/.../ui/transfer/QrImportConfirmDialogUxTest.kt` | `collisionDialog_should_ResolveInSingleTap_When_KeepBothOverwriteOrCancelTapped` | `ComposeTestRule` | Render S11; tap each of "Keep both"/"Overwrite"/"Cancel" in turn (separate test runs); assert resolution with no further sub-dialog |
| 5. No dead ends — every error state offers a return action (S4, S6, S8, S10) | `jvmTest/.../ui/transfer/QrNoDeadEndUxTest.kt` | `everyTerminalState_should_OfferReturnAction_When_ErrorCancelledOrPermissionDeniedStateRendered` | `ComposeTestRule` | Parameterized over `PreflightFailed`, `Failed` (all 4 `QrTransferError` variants), `Cancelled` (both sides), permission-denied; assert a primary enabled action button is present and, when tapped, navigates to a known functional screen (not a blank terminal) |
| 6. Permission denial never fully blocks the feature (S6) | `jvmTest/.../ui/transfer/CameraPermissionRationaleDialogUxTest.kt` | `notNow_should_DismissToWorkingAlternative_When_PermissionRationaleDeclined` | `ComposeTestRule` | Render S6; tap "Not now"; assert dismissal reaches the existing git-sync/file-import alternative, never leaves the user stuck on the rationale dialog |
| 7. `PayloadTooLarge` shows actual + max size, offers `[Back]` (S2) | `jvmTest/.../ui/transfer/QrEncodeScreenUxTest.kt` | `payloadTooLarge_should_ShowActualAndMaxSizeInMessage_When_SerializingFailsPreflight` | `ComposeTestRule` | Drive VM to `Failed(PayloadTooLarge(sizeBytes, maxBytes))`; assert rendered text contains both concrete numbers (not "too big"); assert `[Back]` present and enabled |
| 8. Stalled scan shows exact copy; bar never animates without progress (S9) | `jvmTest/.../ui/transfer/QrDecodeScreenUxTest.kt` | `stalledScan_should_ShowMoveCloserCopy_AndFreezeProgressBar_When_StalledSecondsAtLeast8` | `ComposeTestRule` | Render `Scanning(uniqueFragments=7, stalledSeconds=9, hint=Stalled)`; assert the "move closer / adjust angle" copy; hold `uniqueFragments` fixed across recompositions and assert the progress indicator's visual state does not advance |
| 9. Every `QrTransferError` variant renders distinct copy | `commonTest/.../error/DomainErrorTest.kt` | `toUiMessage_should_ReturnFourDistinctMessages_When_CalledForEveryQrTransferErrorVariant` | Unit test (not UI-rendering, verifies the exhaustive `when`) | Iterate all four variants (`IncompleteTransfer`/`TransferCancelled` removed as dead code — see plan.md Domain Glossary), collect messages into a `Set`, assert `size == 4` |
| 10. Collision never silently overwrites/duplicates (S11) | `jvmTest/.../ui/transfer/QrImportConfirmDialogUxTest.kt` | `collisionDialog_should_BlockDismissOutsideTap_When_WriteInFlight_NeverAutoOverwriteOrDuplicate` | `ComposeTestRule` | Open dialog; tap "Overwrite"; while the spinner is active, invoke `onDismissRequest` (simulated tap-outside/back); assert the dialog remains and no premature write completes; assert `Cancel` stays tappable throughout |
| 11. Permission-denied vs. hardware-unavailable are visually/textually distinct (S6 vs S8) | `jvmTest/.../ui/transfer/QrDecodePreflightUxTest.kt` | `preflightFailed_should_ShowDistinctCopyAndIcon_When_HardwareUnavailable_VersusPermissionDenied` | `ComposeTestRule` | Render S8 `PreflightFailed(HardwareUnavailable)` and S6 permission-denied side by side; assert copy strings and icons differ, no shared text |
| 12. Every interactive control reachable via standard focus order, activatable without a pointer | `jvmTest/.../ui/transfer/QrTransferAccessibilityUxTest.kt` | `allInteractiveControls_should_BeReachableViaTabOrder_And_ActivatableWithoutPointer_When_NavigatingS1ThroughS11` | `ComposeTestRule` (automated subset) + Manual checklist | Automated: `onAllNodes(hasClickAction())` for every screen, assert each is focusable/mergeable into default `Button`/`AlertDialog` traversal, none bypasses it with raw `Modifier.clickable`. Manual: physical Tab/D-pad/switch-scan pass on a real device per surface |
| 13. QR canvas (S3) and camera viewfinder (S9) carry live-updating `contentDescription` | `jvmTest/.../ui/transfer/QrEncodeScreenUxTest.kt` + `QrDecodeScreenUxTest.kt` | `qrCanvas_should_UpdateContentDescriptionWithFrameIndex_When_DisplayingStateAdvances`; `cameraViewfinder_should_UpdateContentDescriptionWithFragmentCount_When_ScanningStateAdvances` | `ComposeTestRule` | Render `Displaying(frameIndex=4, ...)`, assert semantics description == "Sending, frame 4 of about 12"; advance to `frameIndex=5`, assert it updates. Mirror for the viewfinder with fragment count. Manual: confirm TalkBack/VoiceOver actually announces the update on a real device |
| 14. Color is never the sole signal for scanning state (S9 reticle) | `jvmTest/.../ui/transfer/QrDecodeScreenUxTest.kt` | `reticleState_should_PairIconShapeAndTextWithColor_When_LockedOnVersusSearching` | `ComposeTestRule` (automated) + Manual checklist | Automated: assert a text node and a distinct icon/shape resource accompany each color state (not color alone). Manual: apply a protanopia/deuteranopia simulation filter to a screenshot and confirm the state remains distinguishable |
| 15. Text/background contrast ≥4.5:1 on persistent status lines, both themes | `commonTest/.../ui/transfer/QrTransferContrastTest.kt` | `statusLineColors_should_MeetWcagAaContrastRatio_When_ComputedForLightAndDarkThemeTokenPairs` | Unit (pure relative-luminance contrast-ratio calculation over the actual `ColorScheme` tokens) + Manual checklist | Automated: compute WCAG contrast ratio for each foreground/background token pair used by the pre-flight summary, "No internet connection used" line, fragment count, and error messages in both themes; assert ≥4.5. Manual: visually confirm against real rendered screenshots (Roborazzi) with a contrast-checker tool |
| 16. WCAG 2.3.1 flash-safety — ≤3fps default, ≤60% viewport inset card, reduce-motion ≤2fps tap-advance | `jvmTest/.../ui/transfer/QrEncodeScreenFlashSafetyUxTest.kt` | `frameAdvance_should_NeverExceed3Fps_When_DisplayingAtDefaultSettings`; `insetCard_should_OccupyAtMost60PercentOfViewportArea_When_Displaying`; `reduceMotion_should_CapAdvanceRateAt2Fps_When_NextButtonHeldOrRapidlyTapped` | `ComposeTestRule` (virtual clock + layout-bounds measurement) + Manual checklist | Automated: simulate ≥10 s of virtual time and count frame changes (fails if any interval <300 ms / >3.33 fps); measure inset-card layout bounds against viewport bounds (≤60% area); simulate rapid/held taps in reduce-motion mode and assert the app itself rate-limits (not just relying on human tap speed) to ≤2 fps. Manual: **explicitly required by ux.md** — a human tester with a stopwatch times S3's frame changes over ≥10 s and confirms the reduce-motion alternative, independent of reading source code |

---

## Test Stack

- **Unit**: `kotlin.test` (`kotlin("test-junit")` on JVM/Desktop, `kotlin-test` on `commonTest`/`businessTest`) with JUnit 4 as the underlying runner (`junit:junit:4.13.2` on Desktop, `androidx.test.ext:junit` on Android). `kotlinx-coroutines-test:1.10.2` provides virtual-time `TestScope`/`runTest` for ViewModel and pacing-loop tests (`QrEncodeViewModelTest`, `QrDecodeViewModelTest`). No mocking framework is present in the repo (`grep` found no Mockito/MockK) — the existing convention is hand-written fakes (e.g. a fake `FrameTransportReceiver`, fake `QrScanner`, fake page repository), which this plan follows for `QrTransferCoordinatorTest`/`QrImportServiceTest`.
- **Integration**: same `kotlin.test`/JUnit runners, but exercising a real collaborator instead of a fake — real `platform.Settings` storage, real `DatabaseWriteActor`/in-memory or SQLDelight-backed DB, real ZXing (`QrCodecJvmRoundTripTest`), real CameraX `ImageAnalysis` bind/unbind (Robolectric-backed `androidUnitTest`), or a filesystem scan of real sources (`AirGapGuardTest`). No dedicated integration-test framework (e.g. Testcontainers) is needed — this feature has zero external network/service dependencies by design (the air-gap requirement).
- **E2E / UX**: `androidx.compose.ui:ui-test-junit4` `ComposeTestRule` (already used by `UiStateScreenshotTest`, `KeyboardShortcutTest`, `DemoBannerTest` in this repo) for automatable UX criteria; **manual checklist** for the subset ux.md itself flags as requiring a human tester (stopwatch-timed flash-rate confirmation, colorblindness simulation, TalkBack/VoiceOver announcement, real-screenshot contrast spot-check). Playwright/browser automation does not apply — this is a Compose Multiplatform app with no web/DOM surface for the QR feature (WASM/Web is out of scope per ADR-005).

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (commonTest/businessTest/jvmTest) | No coverage plugin (JaCoCo/Kover) is currently configured in this repo (`kmp/build.gradle.kts` has no `jacoco`/`kover` block) — verified by inspection, not assumed. Coverage for this feature is measured by the **Requirement → Test Mapping table above being fully populated** (every story has an explicit test or a documented `N/A`/`Manual` reason), not a line-coverage percentage. If a coverage tool is added later, `./gradlew :kmp:jvmTest` + a Kover/JaCoCo report under `build/reports/` would be the natural hook. | 100% of plan.md stories mapped (this document); no numeric line-coverage target is enforced |
| Android (androidUnitTest) | `./gradlew testDebugUnitTest` / `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` | All `androidMain`-specific tests (`AndroidCameraFrameSourceTest`, backgrounding test) green |
| Full suite gate | `./gradlew ciCheck` / `bazel test //...` — new `transfer/` package tests must be added to the existing `commonTest`/`businessTest`/`jvmTest`/`androidUnitTest` source sets so they run under the existing CI jobs with no new CI wiring | All new tests green in CI before the feature flag is flipped on for dogfooding (Risk Control) |

- All public service methods (`FountainEncoder`, `ChunkBuffer`/`FountainDecoder`, `ChunkFrameCodec`, `QrCodec`, `QrFrameTransport`, `QrImportService`, `QrTransferCoordinator`): happy path + error paths covered in the table above.
- All external integrations (ZXing, CameraX `ImageAnalysis`, `platform.Settings`, `DatabaseWriteActor`): unit-mocked/faked (coordinator/service tests) **and** at least one real integration test (Epic 2.1/2.2/3.1/3.2 integration rows above).
- UX acceptance criteria: all 16 criteria in `design/ux.md` §12 have a corresponding automated test, a manual-checklist step, or both (criteria 12, 14, 15, 16 are automated-plus-manual because ux.md explicitly calls out human/hardware verification as non-optional for those).
- **Migration/schema tests**: N/A — no schema changes, see plan.md's Migration Plan (writes route through the existing `DatabaseWriteActor.savePage`/`saveBlocks` methods; no new `CREATE TABLE`, no `MigrationRunner.all` entry required).
