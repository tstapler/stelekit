# Validation Plan: web-image-management

**Date**: 2026-11-01
**Method**: every requirement/success metric has at least one happy-path unit test, one error-path unit test, and an integration test where a store, browser API or external host is involved. Test names follow `subject_should_Expect_When_Condition`. Test layer rules: pure logic in `commonTest` (virtual time with `runTest`, no `runBlocking`, no real `delay`, no `Thread.sleep`, no `GlobalScope`); browser interop in `wasmJsTest` (Karma, `:kmp:wasmJsBrowserTest`); Compose-behavior tests that do not need pixels in `androidUnitTest` (Robolectric); `jvmTest` only for Roborazzi/desktop-specific and with a `// jvmTest-reason:` header; structural/wiring gates in `businessTest`.

## Requirement to test mapping

Requirement IDs: R1 attach ingress, R2 persistence + sync of attached images, R3 asset index/browser, R4 display and tiers, R5 annotation/import, R6 enrichment, R7 memory, R8 persistence/quota, R9 git LFS, R10 cross-graph copy, R11 safety/security, R12 no regression.

| Req | Test file | Test name | Type | Scenario |
|---|---|---|---|---|
| R1 | `commonTest/.../service/AttachmentPolicyTest.kt` | `plan_should_AcceptAllowListedImage_When_WithinSizeCap` | Unit | happy |
| R1 | same | `plan_should_ReturnTooLarge_When_OverCap` | Unit | error |
| R1 | same | `plan_should_ReturnUnsupportedType_When_ExtensionNotAllowed` | Unit | error |
| R1 | `commonTest/.../service/AttachmentPolicyPropertyTest.kt` | `sanitizedName_should_ContainNoUnsafeChars_When_AnyInput` | Property | names |
| R1 | same | `uniqueName_should_NeverCollide_When_AnyExistingSet` | Property | uniqueness |
| R1 | same | `markdownRoundTrip_should_ResolveSamePath_When_NameSanitized` | Property | encoding (P15) |
| R1 | `wasmJsTest/.../service/WasmMediaAttachmentServiceTest.kt` | `attachFiles_should_WriteStreamToOpfsAndRegisterIndex_When_PickerReturnsMany` | Integration | multi-select |
| R1 | same | `attach_should_ReturnQuotaExceededAndLeaveNoPartialFile_When_CreateWritableRejects` | Integration | error (P6) |
| R1 | same | `attach_should_SerializeSameNameWrites_When_TwoConcurrent` | Integration | race |
| R1 | `wasmJsTest/.../ui/components/PasteInteropTest.kt` | `paste_should_ForwardImageFiles_When_ClipboardHasImage` | Integration | happy |
| R1 | same | `paste_should_NotPreventDefault_When_ClipboardIsText` | Integration | error/negative |
| R1 | `commonTest/.../ui/components/HtmlImageLinkExtractorTest.kt` | `extract_should_ReturnMarkdownLink_When_HtmlHasImgSrc` / `extract_should_ReturnNull_When_NoImg` | Unit | happy/error |
| R1 | `wasmJsTest/.../platform/sensor/WebCameraProviderTest.kt` | `capture_should_StageFileAndReturnPlatformImageFile_When_CameraAvailable` / `capture_should_ReturnHardwareUnavailable_When_NoSecureContext` | Integration | happy/error |
| R1 | `wasmJsTest/.../service/ShareInboxConsumerTest.kt` | `consume_should_MoveSharedFilesIntoAssets_When_InboxPopulated` / `consume_should_ClearInboxAndSurfaceError_When_WriteFails` | Integration | share |
| R1 | `kmp/src/wasmJsMain/resources/service-worker-share.test.js` | `postShareTarget_should_StoreFilesAndRedirect303` / `postShareTarget_should_NotCacheRequest` | JS unit | SW (P14) |
| R1 | `wasmJsTest/.../service/FolderImageImportTest.kt`, `ZipImageImportRoundTripTest.kt` | `import_should_AttachOnlyImages_When_FolderHasMixedFiles` / `zipImport_should_RoundTripExporterOutput` | Integration | bulk |
| R2 | `wasmJsTest/.../platform/binary/BinaryWriteDirtyTrackingTest.kt` | `writeStream_should_RecordDirtyWriteWithNoBytes_When_BinaryPath` | Integration | happy |
| R2 | same | `writeStream_should_NotRecordDirty_When_LocalOnlyPath` | Integration | error/negative (P3) |
| R2 | `wasmJsTest/.../service/AttachDirtyAndHostQueueTest.kt` | `attach_should_AppearInDirtySetAndHostWriteQueue_When_Succeeds` | Integration | P1 |
| R2 | `wasmJsTest/.../git/WasmGitWriteServiceMockedIntegrationTest.kt` (extended) | `push_should_CreateOneBlobWithCorrectBase64_When_StoreBackedImageDirty` / `push_should_ReturnFileTooLarge_When_ImageOverMaxBlob` | Integration | MockEngine |
| R2 | `wasmJsTest/.../platform/HostDirectorySyncBinaryReconcileTest.kt` | `reconcile_should_NotPopulateBytesCache_When_HostHasImages` | Integration | P2 |
| R2 | same | `reconcile_should_ProduceSameOpfsContents_When_ComparedToLegacyPath` | Differential | regression |
| R2 | `wasmJsTest/.../export/GraphZipExporterWasmJsTest.kt` (extended) | `zip_should_ContainAttachedImageBytes_When_ImageInOpfs` / `zip_should_ExcludeLocalOnlyPaths` | Integration | export |
| R3 | `commonTest/.../asset/AssetIndexBinaryReadsTest.kt` | `registerAsset_should_UseHeadAndStoreHash_When_FileLargerThanCap` / `registerAsset_should_IndexByNameAndSize_When_HashUnavailable` | Unit | happy/error |
| R3 | `wasmJsTest/.../service/AttachRegistersAssetTest.kt` | `attach_should_RegisterAssetRow_When_IndexingActive` | Integration | index |
| R3 | `wasmJsTest/.../ui/assets/AssetBrowserWebTest.kt` | `browser_should_ListSeededImages_When_AssetsExist` / `browser_should_ShowEmptyState_When_NoAssets` | Integration | UI on wasm |
| R3 | `wasmJsTest/.../AssetMoveWebTest.kt` | `move_should_RelocateFileAndRewriteRefs_When_Moved` / `replay_should_HealHalfDoneMove_When_CrashedBetweenCopyAndDelete` | Integration | WAL |
| R3 | `androidUnitTest/.../ui/assets/AssetDeleteConfirmTest.kt` | `delete_should_ShowReferencedByCount_When_PagesReference` | Unit (Robolectric) | UX |
| R3 | `wasmJsTest/.../PlatformFileOpenerWebTest.kt` | `open_should_DownloadWithForcedMime_When_Svg` / `open_should_NeverNavigateToBlobUrl` | Integration | P17 |
| R4 | `commonTest/.../platform/image/ImageDerivativeBackendTest.kt` | `ensure_should_UseBackendOutput_When_Success` / `ensure_should_RememberGiveUpAndReturnOriginal_When_BackendFails` | Unit | tiers |
| R4 | `wasmJsTest/.../platform/image/WebImageDerivativeBackendTest.kt` | `generate_should_ProduceUprightThumbnail_When_ExifRotated` / `generate_should_ReturnNoDerivative_When_AnimatedGif` | Integration | EXIF/animated |
| R4 | `wasmJsTest/.../ui/components/ImageBlockRenderTest.kt` | `imageBlock_should_RenderPixels_When_BlobUrlModel` / `imageBlock_should_ShowPlaceholder_When_ModelIsLfsPointer` | Integration | S1/P4 |
| R5 | `commonTest`/`businessTest` `ImageImportBinaryCopyTest.kt` | `copy_should_StreamBytes_When_FileSourceHandle` / `copy_should_ReturnUnsupportedType_When_NoBinaryIO` | Unit | no silent text fallback |
| R5 | `androidUnitTest/.../AnnotationTooLargeStateTest.kt`, `AnnotationCapabilityGatingTest.kt` | `editor_should_ShowViewOnly_When_ImageOverCanvasCeiling` / `editor_should_HideBleAndDepth_When_CapabilitiesAbsent` | Unit (Robolectric) | gating |
| R5 | `wasmJsTest/.../ui/annotate/WebImportAnnotateExportTest.kt` | `flow_should_ImportAnnotateExportAndTrackDirty_When_PngImported` | Integration | e2e |
| R6 | `commonTest/.../asset/pipeline/WebOcrPluginFakeTest.kt` | `ocr_should_StoreText_When_FakeRecognizerReturns` / `ocr_should_SkipAndLog_When_RecognizerFails` | Unit | plugin |
| R6 | `wasmJsTest/.../platform/ml/{WebTextRecognizerTest,WebImageLabelerTest,WebMonocularDepthEstimatorTest}.kt` | `recognize_should_ReturnKnownText_When_FixtureImage` etc. | Integration | engine smoke |
| R6 | `wasmJsTest/.../platform/ml/WebMemoryPressureTest.kt` | `pressure_should_PausePipeline_When_VisibilityHidden` | Integration | pause |
| R7 | `commonTest/.../platform/binary/ResidentBytesMeterTest.kt` | `acquire_should_Fail_When_OverBudget` / `release_should_RestoreBudget` | Unit | budget |
| R7 | `wasmJsTest/.../platform/binary/ResidentBytesBudgetTest.kt` | `attachTwentyImages_should_KeepResidentBytesUnderOneImage_When_Streamed` | Integration | P2 |
| R7 | `wasmJsTest/.../ui/assets/LargeGraphImageIndexWebTest.kt` | `index_should_StayInBoundedBatches_When_2000Images` | Integration | scale |
| R8 | `commonTest/.../storage/StorageHealthClassifierTest.kt` | `classify_should_ReturnNearQuota_When_UsageAtLeast85Percent` / property over used/quota | Unit/Property | health |
| R8 | `androidUnitTest/.../StoragePanelTest.kt` | `panel_should_ShowCategoriesAndPersistStatus` | Unit (Robolectric) | UI |
| R8 | `wasmJsTest/.../storage/StorageHealthWasmTest.kt` | `persist_should_BeRequestedOnce_When_FirstAttach` | Integration | D14 |
| R9 | `commonTest/.../git/lfs/LfsPointerResolverTest.kt` | `resolve_should_ReturnPointer_When_ValidPointerBytes` / `resolve_should_ReturnNotPointer_When_PngMagic` / property round trip | Unit/Property | pointer |
| R9 | `commonTest/.../git/lfs/client/LfsErrorMapperTest.kt`, batch `MockEngine` tests | `map_should_ProduceQuotaError_When_507` etc. | Unit | shared protocol |
| R9 | existing JVM `LfsBatchClientStatusTest`, `JvmGitRepositoryLfsTransferTest` | unchanged | Integration | contract after extraction |
| R9 | `wasmJsTest/.../git/lfs/WebLfsObjectStoreTest.kt` | `commit_should_RejectObject_When_OidMismatch` / `commit_should_PersistObject_When_HashAndSizeMatch` | Integration | integrity |
| R9 | `wasmJsTest/.../git/lfs/WebLfsReadServiceTest.kt` | `read_should_UseMediaEndpoint_When_GitHub` / `read_should_ReturnTypedError_When_401` | Integration | MockEngine |
| R9 | `wasmJsTest/.../git/WasmGitWriteServiceLfsGuardTest.kt` (extended) | `guard_should_HoldDirtyImage_When_ImageNotInBytesCacheAndPathTracked` / `guard_should_FailClosed_When_ContentUnlocatable` | Integration | P18 blocker |
| R9 | `wasmJsTest/.../git/lfs/LfsWriteCapabilityResolverTest.kt` | `resolve_should_PickDirect_When_ProbePasses` / `resolve_should_FallToReadOnly_When_NothingAvailable` | Unit | modes |
| R9 | `businessTest/.../git/lfs/LfsStagingCallSitesStructuralTest.kt` (amended) | `converter_should_HaveExactlyOneStagingCallSite_BehindOptIn` | Structural | exception pinned |
| R9 | `androidUnitTest/.../LfsImagePlaceholderTest.kt` | `placeholder_should_ShowSizeAndDownload_When_NotDownloaded` | Unit (Robolectric) | UX |
| R10 | `commonTest`/`businessTest` `AssetCopierBinaryApiTest.kt` | `copy_should_WriteViaRouter_When_WebIsTarget` / `copy_should_RenameOnCollisionWithHash_When_SameNameDifferentBytes` | Unit | router |
| R11 | `businessTest/.../binary/LocalOnlyCallSitesStructuralTest.kt` | `everyWritePath_should_ConsultLocalOnlyPaths` | Structural | P3 |
| R11 | `commonTest/.../binary/LocalOnlyPathsTest.kt` | property: `isLocalOnly` true for all generated `.stelekit/cache/**` | Property | P3 |
| R11 | `businessTest/.../binary/NoBlobUrlNavigationStructuralTest.kt` | `wasmMain_should_NeverNavigateToBlobUrl` | Structural | P17 |
| R12 | existing suites | `WasmMediaAttachmentServiceTest`, `DropZoneInteropTest`, `PageDropTargetListenerLifecycleTest`, `WasmGitWriteService*`, `LfsRawBinaryNeverCommitted*`, `HostDirectorySync*`, `MigrationRunnerSchemaSyncTest`, `WiringCompletenessTest` | Regression | green |
| R12 | `businessTest/.../wiring` | `WiringCompletenessTest` with wasm `Main.kt` in `WiringRoots` | Gate | P13 |

## Test stack
- **Unit/property**: `kotlin.test` + `kotest-assertions-core` + `kotest-property` (plain library, not the Spec runner) in `commonTest`, wrapped in `runTest`.
- **Integration (web)**: `kotlin("test-wasm-js")` in `wasmJsTest` via `./gradlew :kmp:wasmJsBrowserTest` (Karma, headless Chrome). Prerequisite: `~/.yarnrc.yml` `nodeLinker: node-modules` (repo memory); `scripts/fix-wasm-karma-tooling.sh` if mocha/webpack errors appear. Mock network with Ktor `MockEngine` (see `LfsTolerantMockEngine`).
- **Compose behavior**: Robolectric in `androidUnitTest` (`./gradlew testDebugUnitTest`); no X11 needed. Pixel tests, if any, `jvmTest` with `scripts/jvm-display-check.sh`.
- **JS (service worker, manifest)**: node-run tests next to existing `looks-like-app-shell.test.js`.
- **Structural/wiring**: `businessTest` (Gradle and Bazel).
- **Manual**: owner-run matrices (below).

## Coverage targets
- commonMain new code: at least 80 percent line coverage, 100 percent of public service methods have happy and error tests.
- Every new wasm interop function has an `OpfsInterop`-style test or is exercised by an integration test that fails if it is removed.
- Every new `DomainError` variant is produced by at least one test and has a display string test.
- Every pitfall P1 to P18 has at least one named test or an explicit "manual only" entry in the manual matrix.

## Manual validation matrices (owner-run; wall-clock blockers B1 to B5)
| ID | Matrix | Pass criteria |
|---|---|---|
| M1 | Attach (picker, drop, paste, camera) x Chromium, Firefox, Safari desktop, Android Chrome, iOS Safari | image appears, survives reload, appears in Assets, push succeeds or shows a typed reason |
| M2 | Storage: fill to quota on each browser | typed QuotaExceeded, no partial file, panel shows near-quota |
| M3 | Share Target on installed Android Chrome PWA and desktop Chrome | shared image offered for add; unsupported browsers show no hint |
| M4 | GitHub and GitLab private repo: push of 1, 20 and 70 MiB images; 100 images | success or typed FileTooLarge; no tab crash; rate-limit behavior recorded |
| M5 | LFS: view LFS image on GitHub and GitLab (private), Direct on Bitbucket/Azure if available, Converter on GitHub and GitLab | per `git-lfs-support` Story E7.6 checklist |
| M6 | Memory: 2,000-image graph on a mid-range phone (Chrome Android) and iOS Safari | no reload/crash during index; recorded heap |
| M7 | Host folder: connect, attach, disconnect permission, reconnect | pending writes flushed; banner states correct |

## Gate checks for the Phase 4 readiness gate (run inline after this file)
1. Every requirement R1 to R12 has at least one test row above: yes.
2. `plan.md` has no TODO/TBD in architecture or task sections: the only placeholder was replaced; re-verified by grep in the gate step.
3. ADRs referenced by `plan.md` exist on disk: ADR-001..003 present.
4. No unresolved BLOCKER in `adversarial-review.md`: the single blocker is marked resolved with the plan patch cited.
