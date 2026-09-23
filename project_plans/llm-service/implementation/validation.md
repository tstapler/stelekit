# Validation Plan: llm-service

**Date**: 2026-07-01
**Phase**: 4 (validate) — pre-code test design + implementation readiness gate
**Inputs**: `requirements.md`, `implementation/plan.md` (8 epics / 39 stories / ~106 tasks), `implementation/adversarial-review.md` (verdict: CONCERNS), `decisions/ADR-011..014`

## Happy Path Scenario

Given an Android device with no LLM API keys configured and the ML Kit on-device model
downloaded, when the user opens a page and triggers tag suggestion, then
`LlmProviderRegistry.availableForFeature(TAG_SUGGESTION)` resolves the on-device provider
automatically (no explicit selection needed) and the user receives LLM-tier tag
suggestions with zero network calls — the requirements doc's headline success metric.

---

## Test Stack

- **Unit / business logic**: `businessTest` (JVM, no Android/iOS SDK, no Compose) — `./gradlew jvmTest` / `bazel test //kmp:business_tests`
- **JVM integration** (Ktor `MockEngine`, HTTP wire-format tests): `jvmTest` — `bazel test //kmp:jvm_tests`
- **Android instrumented/unit**: `androidUnitTest` — `./gradlew testDebugUnitTest` / `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android`
- **iOS**: no automated CI lane (Gradle #17559 + K2/Compose-Multiplatform klib incompatibility, per Epic 5's corrected CI framing) — manual verification on macOS/Xcode only, documented per task
- **UI/Compose screenshot**: Roborazzi via Gradle (`ciCheck`) — not migrated to Bazel yet

---

## Requirement → Test Mapping

Requirements are taken verbatim from `requirements.md`'s Scope / Success Metrics /
Constraints sections. Each maps to plan.md epic/story and validation.md test(s).

### Scope: Unified LLM provider abstraction

| Requirement | Epic/Story | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|---|
| Common `LlmProvider` interface + registry/discovery, no hand-checking individual API keys | 1.1–1.3 | `LlmProviderAvailabilityTest.kt` | `unavailable_and_preparing_dataclass_equality_and_copy_preserve_retryable_default` | Unit | Happy path — data-class semantics guard fallback-selection logic |
| " | 1.2 | `RemoteLlmProviderTest.kt` | `checkAvailability_should_AlwaysReturnAvailable` | Unit | Happy path |
| " | 1.2 | `RemoteLlmProviderTest.kt` | `formatter_should_DelegateUnchanged_ToWrappedLlmFormatterProvider` | Unit | Happy path |
| " | 1.3 | `LlmProviderRegistryTest.kt` | `availableForFeature_should_ReturnEmpty_When_AllProvidersUnavailable` | Unit | Error path (table-driven matrix) |
| " | 1.3 | `LlmProviderRegistryTest.kt` | `availableForFeature_should_ExcludeShortFormOnly_When_ExcludeFlagTrue_AndOnDeviceOnlyAvailable` | Unit | Error/edge path |
| " | 1.3 | `LlmProviderRegistryTest.kt` | `availableForFeature_should_IncludeOnDevice_When_ExcludeFlagFalse` | Unit | Happy path |
| " | 1.3 | `LlmProviderRegistryTest.kt` | `availableForFeature_should_ReturnBoth_When_RemoteAndOnDeviceAvailable` | Unit | Happy path |
| Per-feature provider selection storage | 1.4 | `LlmSettingsTest.kt` | `getSelectedProviderId_should_RoundTrip_When_SetThenGet` | Unit | Happy path |
| " | 1.4 | `LlmSettingsTest.kt` | `getSelectedProviderId_should_ReturnNull_When_Unset` | Unit | Error path (absence = Auto) |
| " | 1.4 | `LlmSettingsTest.kt` | `selection_should_BeIndependent_AcrossThreeLlmFeatureValues` | Unit | Regression — no cross-feature bleed |
| `platformOnDeviceLlmProvider()` expect/actual scaffold compiles on all targets | 1.5 | build verification (no test file) | `bazel build //kmp:jvm_tests //kmp:android_app --config=android` + Gradle iOS/wasm targets | Integration (build) | Happy path |
| Registry composition root | 1.6 | `LlmProviderRegistryFactoryTest.kt` | `buildLlmProviderRegistry_should_ReturnEmptyRegistry_When_NoCredentialsAndNoOnDevice` | Unit | Error path |
| " | 1.6 | `LlmProviderRegistryFactoryTest.kt` | `buildLlmProviderRegistry_should_ContainOneEntry_When_OnlyAnthropicKeyPresent` | Unit | Happy path |
| " | 1.6 | `LlmProviderRegistryFactoryTest.kt` | `buildLlmProviderRegistry_should_ContainThreeEntries_When_BothKeysAndOnDevicePresent` | Unit | Happy path |
| `Settings.containsKey()` capability | 1.7 | (platform actuals, no dedicated unit test — exercised via consumers) | see Story 8.2 tests below | Integration | Happy/error |
| `wasmJs` Ktor client engine links | 1.8 | build verification | `bazel build //kmp:web_app` | Integration (build) | Happy path |

### Scope: Provider implementations

| Requirement | Epic/Story | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|---|
| Anthropic Claude (remote) — migrate existing | 8.3 | `ClaudeLlmFormatterProviderTest.kt` (existing) | (unchanged, see Regression section) | Regression | Must keep passing unchanged |
| OpenAI-compatible (remote) — migrate existing | 3.2 | `OpenAiLlmFormatterProviderTest.kt` (extended) | `init_should_ConstructSuccessfully_When_LoopbackHttpAndAllowInsecureTrue` | Unit | Happy path |
| " | 3.2 | `OpenAiLlmFormatterProviderTest.kt` | `init_should_Throw_When_NonLoopbackHttpEvenWithAllowInsecureTrue` | Unit | Error path (security boundary) |
| " | 3.2 | `OpenAiLlmFormatterProviderTest.kt` | `init_should_BeUnaffected_When_HttpsRegardlessOfFlag` | Unit | Regression |
| " | 3.2 | `OpenAiLlmFormatterProviderTest.kt` | `request_should_OmitAuthorizationHeader_When_ApiKeyBlank` | Unit | Edge path — no malformed `Bearer ` header |
| " | 3.2 | `OpenAiLlmFormatterProviderTest.kt` | `request_should_SendCustomModel_When_ModelConstructorArgProvided` | Unit | Happy path |
| Google Gemini API (remote) — new | 3.1 | `GeminiLlmFormatterProviderTest.kt` | `format_should_ParseSuccess_When_200Response` | Unit | Happy path |
| " | 3.1 | `GeminiLlmFormatterProviderTest.kt` | `format_should_MapError_When_401Or429Or5xx` | Unit | Error path (table, mirrors `LlmProviderSupportTest`) |
| " | 3.1 | `GeminiLlmFormatterProviderTest.kt` | `format_should_ReturnNetworkError_When_EmptyCandidatesInResponse` | Unit | Error path |
| " | 3.1 | `GeminiLlmFormatterProviderTest.kt` | `circuitBreaker_should_Open_When_ThreeConsecutiveFailures` | Unit | Error path |
| " | 3.1c | `LlmProviderRegistryFactoryTest.kt` (extended) | `buildLlmProviderRegistry_should_IncludeGemini_When_GeminiKeyPresent` | Unit | Happy path |
| Generic OpenAI-compatible custom-base-URL provider — new (Ollama/LM Studio/Azure/OpenRouter) | 3.3 | `CustomOpenAiCompatibleLlmProviderTest.kt` | `checkAvailability_should_ReturnAvailable_When_200OnModelsEndpoint` | Unit | Happy path |
| " | 3.3 | `CustomOpenAiCompatibleLlmProviderTest.kt` | `checkAvailability_should_ReturnUnavailableRetryable_When_ConnectionRefused` | Unit | Error path |
| " | 3.3 | `CustomOpenAiCompatibleLlmProviderTest.kt` | `checkAvailability_should_ReturnUnavailableNonRetryable_When_401` | Unit | Error path |
| " | 3.3a | `CustomOpenAiCompatibleLlmProviderTest.kt` | `fetchAvailableModels_should_ParseModelIdList_When_ValidResponse` | Unit | Happy path |
| " | 3.3c | `LlmProviderRegistryFactoryTest.kt` (extended) | `buildLlmProviderRegistry_should_ConstructOneProviderPerCustomId_When_MultipleCustomConfigsExist` | Unit | Happy path — multi-instance |
| " | 3.3d | build/manual verification | `bazel build //kmp:web_app` + manual CORS check documented in task notes | Integration (build) + Manual | Constraint verification |
| Android on-device (ML Kit/Gemini Nano) — migrate + wire into tag suggestion | 4.1–4.3 | see Android section below | | | |
| iOS on-device (Apple Intelligence/on-device APIs) — new | 5.1–5.5 | see iOS section below | | | |

### Scope: Secure credential storage per platform

| Requirement | Epic/Story | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|---|
| `CredentialStore` relocation, zero behavior change | 2.1 | existing `AndroidCredentialStoreTest`/`JvmCredentialStoreTest`/etc. (re-run post-move) | (unchanged) | Regression | Must pass unmodified after package move |
| Synchronous `storeBlocking()` — see dedicated regression test | 2.1d | `AndroidCredentialStoreCommitTest.kt` (new) | `storeBlocking_should_UseCommitNotApply_And_ReturnCommitResult` | Unit (androidUnitTest) | See "Adversarial-Review Patch Regression Tests" below |
| `LlmCredentialStore` typed wrapper, namespaced keys | 2.2 | `LlmCredentialStoreTest.kt` | `apiKey_should_RoundTrip_PerProviderId` | Unit | Happy path |
| " | 2.2 | `LlmCredentialStoreTest.kt` | `apiKey_should_BeIsolated_AcrossProviderIds` | Unit | Regression |
| " | 2.2d | `LlmCredentialStoreTest.kt` | `setApiKeyBlocking_should_ReturnFalse_When_StoreBlockingFails` | Unit | Error path |
| " | 2.2d | `LlmCredentialStoreTest.kt` | `setApiKeyBlocking_should_ReturnTrue_And_ReflectInGetApiKey_When_StoreBlockingSucceeds` | Unit | Happy path |
| Non-secret custom-provider config schema (no JSON blob) | 2.2b | `LlmSettingsCustomProviderTest.kt` | `customProviderConfig_should_RoundTrip_AndSurviveRemovalOfSibling` | Unit | Happy path |
| One-shot migration of `VoiceSettings` keys into `LlmCredentialStore`, no permanent fallback | 2.3 | `LlmCredentialMigrationTest.kt` | see "Adversarial-Review Patch Regression Tests" below (this is the B1-fix test) | Unit | Happy + error + crash-simulation |
| Removal of `VoiceSettings` plaintext accessors once migrated | 2.4 | `VoiceSettingsTest.kt` (modified) | assertions on deleted methods removed; migration-path assertions live in `LlmCredentialMigrationTest` | Unit | Regression (test suite shape) |
| " | 2.4c | `AndroidCredentialStoreFailsLoudTest.kt` | `credentialStoreInit_should_PropagateException_When_KeystoreInitThrows_NotFallbackToPlaintext` | Unit (androidUnitTest) | Error path — fail-loud lock-in |

### Scope: Settings UI

| Requirement | Epic/Story | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|---|
| `AppState`/`StelekitViewModel` plumbing for settings visibility | 6.1 | `StelekitViewModelLlmSettingsTest.kt` (new) | `openLlmProviderSettings_should_SetVisibleTrue_And_dismiss_should_SetFalse` | Unit | Happy path |
| Provider list screen, live status, never optimistic "Available" | 6.2 | `LlmProviderListScreenTest.kt` (Roborazzi/Compose UI, Gradle-only) | `providerRow_should_ShowCheckingAvailability_BeforeAsyncResolutionCompletes` | UI (manual + screenshot) | Happy path — see UX Acceptance Tests |
| Add/Edit provider form + fetch-models validation | 6.3 | `AddEditLlmProviderDialogTest.kt` (Compose UI) | `saveButton_should_BeDisabled_When_NonLoopbackHttpUrlEntered` | UI (manual + screenshot) | Error path — resolves Open Question 2 |
| " | 6.3 | `AddEditLlmProviderDialogTest.kt` | `fetchModelsButton_should_PopulateDropdown_When_ProbeSucceeds` | UI (manual) | Happy path |
| Per-feature provider picker, default "Auto" | 6.4 | `PerFeatureProviderPickerTest.kt` (Compose UI) | `picker_should_ShowAutoOption_And_ExcludeShortFormOnly_When_FeatureIsSynthesis` | UI (manual + screenshot) | Happy path |
| Wired into `SettingsDialog` | 6.5 | `SettingsDialogTest.kt` (existing, extended) | `settingsDialog_should_ShowLlmProvidersCategory` | UI (screenshot) | Happy path |
| Migrate `VoiceCaptureSettings` off plaintext keys | 6.6 | see "Adversarial-Review Patch Regression Tests" below (M2 fix) | | Unit + UI | Regression |

### Scope: Migrate existing consumers

| Requirement | Epic/Story | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|---|
| `LlmTagProvider`/tag suggestion migration, gains on-device fallback | 8.2 | `TagSuggestionOnDeviceDefaultTest.kt` | `selection_should_DefaultToExplicitlyDisabled_When_ExistingInstall_TagsLlmTierKeyPresent` | Unit | Error/regression path — B2-dependent fix |
| " | 8.2 | `TagSuggestionOnDeviceDefaultTest.kt` | `selection_should_DefaultToAuto_When_FreshInstall_TagsLlmTierKeyAbsent` | Unit | Happy path |
| " | 8.2d | `TagSuggestionEngineTest.kt`, `TagSuggestionViewModelTest.kt`, `LlmProviderSupportTest.kt` (existing) | (unchanged) | Regression | Must pass unmodified |
| Voice formatting pipeline migration, preserves `getUseDeviceLlm()` effective behavior | 8.1 | `VoiceDeviceLlmMigrationTest.kt` | `migration_should_SetOnDeviceSelection_When_GetUseDeviceLlmWasTrue` | Unit | Happy path |
| " | 8.1 | `VoiceDeviceLlmMigrationTest.kt` | `migration_should_LeaveSelectionNull_When_GetUseDeviceLlmWasFalse` | Unit | Happy path (default preserved) |
| " | 8.1e | `VoiceCaptureViewModelTest.kt` (existing) | (unchanged) | Regression | Must pass unmodified |
| `ClaudeTopicEnricher` migration (last, most divergent) | 8.3 | `ClaudeTopicEnricherTest.kt` (rewritten per plan's explicit flag) | `enhance_should_FallBackToLocalSuggestions_When_NonSuccessOrParseFailure` | Unit | Error path (preserved behavior) |
| " | 8.3b | `ClaudeTopicEnricherTest.kt` | `enhance_should_ShortCircuit_ViaSharedCircuitBreaker_When_ThreeConsecutiveFailures` | Unit | Error path — replaces deleted retry-on-429 assertions |
| " | 8.3b | `ClaudeTopicEnricherTest.kt` | `enhance_should_PreserveWireCompat_ModelNameAndMaxTokens256AndPromptShape` | Unit | Regression (wire-format) |
| Final cleanup — zero remaining plaintext call sites | 8.4a | grep + full suite run (no dedicated test file) | `bazel test //...` post-grep-confirmation | Integration | Regression gate |

### Scope: Approval-gated LLM library-editing workflow

| Requirement | Epic/Story | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|---|
| Sealed proposal type (`BlockEdit`/`TagChange`/`NewPage`), mandatory graph-scoping | 7.1 | `PendingLlmSuggestionTest.kt` | `allVariants_should_HaveNonBlankIdAndGraphId_ViaExhaustiveWhen` | Unit | Happy path |
| In-memory inbox, session-scoped | 7.2 | `LlmSuggestionInboxTest.kt` | `propose_should_AddToPending` | Unit | Happy path |
| " | 7.2 | `LlmSuggestionInboxTest.kt` | `remove_should_RemoveFromPending` | Unit | Happy path |
| " | 7.2 | `LlmSuggestionInboxTest.kt` | `pendingForGraph_should_FilterCorrectly_AcrossTwoDifferentGraphIds` | Unit | Edge path — multi-graph |
| " | 7.2 | `LlmSuggestionInboxTest.kt` | `propose_should_Overwrite_When_DuplicateIdProposed` | Unit | Edge path — dedup semantics |
| Visibility/accept/reject wiring, no auto-dismiss, multi-graph guard | 7.3 | `StelekitViewModelLlmSuggestionTest.kt` (new) | `reviewVisible_should_BecomeTrue_When_PendingForCurrentGraphBecomesNonEmpty` | Unit | Happy path |
| " | 7.3 | `StelekitViewModelLlmSuggestionTest.kt` | `rejectLlmSuggestion_should_RemoveFromInbox_NoConfirmation` | Unit | Happy path |
| " | 7.3 | `StelekitViewModelLlmSuggestionTest.kt` | `acceptLlmSuggestion_should_NoOp_When_SuggestionAlreadyResolved` | Unit | Error path — race guard |
| " | 7.3 | `StelekitViewModelLlmSuggestionTest.kt` | `acceptLlmSuggestion_should_SurfaceError_NotRemove_When_GraphIdMismatchesCurrentGraph` | Unit | Error path — multi-graph guard |
| Staleness re-validation + `GraphWriter` materialization, single write path for all variants | 7.4 | `LlmSuggestionWriterTest.kt` | `materializeAndWrite_should_CallWrite_When_BlockEditTargetMatchesSnapshot` | Unit | Happy path |
| " | 7.4c | `LlmSuggestionWriterTest.kt` | `materializeAndWrite_should_NotWrite_SurfaceConcurrentWrite_When_BlockEditTargetContentChanged` | Unit | Error path |
| " | 7.4c | `LlmSuggestionWriterTest.kt` | `materializeAndWrite_should_NotWrite_SurfaceNotFound_When_BlockEditTargetDeleted` | Unit | Error path |
| " | 7.4c | `LlmSuggestionWriterTest.kt` | `materializeAndWrite_should_ConstructFreshPageAndBlocks_When_NewPage` | Unit | Happy path |
| " | 7.4c | `LlmSuggestionWriterTest.kt` | `materializeAndWrite_should_ResolveToSameSavePageShape_When_TagChange` | Unit | Regression (design-goal confirmation) |
| Review UI, one-at-a-time, no confirmation on discard | 7.5 | `LlmSuggestionReviewScreenTest.kt` (Compose UI) | `discardButton_should_FireImmediately_NoConfirmationDialog` | UI (manual + screenshot) | Happy path |
| " | 7.5 | `LlmSuggestionReviewScreenTest.kt` | `applyAllButton_should_RequireSecondConfirmation_NotFirstAction` | UI (manual) | Edge path — approval-fatigue mitigation |
| Bounded synthesis proposal generator | 7.6 | `LlmSynthesisContextBuilderTest.kt` | see "Adversarial-Review Patch Regression Tests" below (bounded-read test) | Unit | Happy + regression |
| " | 7.6d | `LlmSynthesisServiceTest.kt` | `parse_should_ProduceExpectedTagChangeProposals_When_WellFormedFreeTextOutput` | Unit | Happy path |
| " | 7.6d | `LlmSynthesisServiceTest.kt` | `parse_should_TolerateMalformedOutput_BestEffortSubset_NoCrash` | Unit | Error path |
| " | 7.6d | `LlmSynthesisServiceTest.kt` | `proposalCount_should_BeCappedAtConfiguredMax_When_ModelReturnsMore` | Unit | Edge path — approval-fatigue cap |
| " | 7.6d | `LlmSynthesisServiceTest.kt` | `service_should_RefuseWithClearMessage_When_OnlyShortFormOnlyProviderAvailable` | Unit | Error path |
| "Always require explicit approval, no auto-apply exception" (Resolved Decision) | 7.3/7.4 | `StelekitViewModelLlmSuggestionTest.kt` + `LlmSuggestionWriterTest.kt` | (covered by accept/reject tests above — no code path exists that writes without an explicit `acceptLlmSuggestion(id)` call) | Unit | Design invariant — enforced by absence of any other write call site |

### Success Metrics

| Success Metric | Test(s) | Type |
|---|---|---|
| Extensibility: new provider = self-contained change | `LlmProviderRegistryTest.kt`, `LlmProviderRegistryFactoryTest.kt` (table-driven, provider-count-agnostic assertions) | Unit — structural (registry accepts any `LlmProvider` list; no call-site changes needed to add Gemini/custom in Epic 3) |
| Android on-device parity, zero API keys | `AndroidOnDeviceFallbackTest.kt`, `TagSuggestionOnDeviceDefaultTest.kt` | Unit |
| User-facing provider management (Settings UI) | Epic 6 UX Acceptance Tests (below) | UI/manual |
| Approval-gated edit workflow, no exceptions | Epic 7 tests above + explicit design-invariant note | Unit |

### Constraints

| Constraint | Test(s) | Type |
|---|---|---|
| Offline/degrade gracefully — on-device tiers work with zero network | `AndroidOnDeviceFallbackTest.kt` (registry returns on-device provider with no remote key), Story 5.5 manual verification (iOS) | Unit + Manual |
| No mandatory new backend/server | N/A — architectural constraint verified by ADR review (no server code introduced anywhere in plan.md; confirmed by file-list audit below) | Architecture review |
| Secure per-platform credential storage, no new plaintext preferences | `LlmCredentialStoreTest.kt`, `LlmCredentialMigrationTest.kt`, `AndroidCredentialStoreFailsLoudTest.kt`, `AndroidCredentialStoreCommitTest.kt` | Unit |
| No hard deadline | N/A — non-testable scheduling constraint | — |

---

## Android-Specific Tests (`androidUnitTest`, cannot run in `businessTest`/`jvmTest`)

| Test File | Test Name | Purpose |
|---|---|---|
| `AndroidCredentialStoreCommitTest.kt` | `storeBlocking_should_UseCommitSynchronously_NotApply` | Verifies `AndroidCredentialStore.storeBlocking()` calls `SharedPreferences.Editor.commit()`, not `.apply()` — see dedicated regression test below |
| `AndroidCredentialStoreFailsLoudTest.kt` | `init_should_PropagateException_When_EncryptedSharedPreferencesCreateThrows` | Locks in fail-loud behavior (Story 2.4c) |
| (pure-function extraction, location TBD per Task 4.1d) `MlKitAvailabilityMappingTest.kt` | `mapFeatureStatus_should_ReturnExpectedAvailability_ForEachFeatureStatusValue` | Table-driven over all `FeatureStatus` values; lands in `businessTest` if fully SDK-independent per Task 4.1d, else documented `androidUnitTest` exception |

Everything else in Epic 4 (`AndroidOnDeviceLlmProvider`, registry wiring, fallback ordering)
is deliberately designed to run in `businessTest` against fake `LlmProvider`s — **no real ML
Kit SDK dependency** is required for Epic 4's business-logic tests, per Task 4.3a's explicit
instruction ("this test must run in `businessTest`, not `androidUnitTest`").

## iOS Tests — CANNOT run in this repo's CI; manual verification only

The following are explicitly **manual-verification-only** per Epic 5's corrected CI framing
(Gradle issue #17559 — `KotlinNativeBundleBuildService` classloader mismatch in
multi-project builds with AGP — plus Kotlin 2.3.x K2 compiler's inability to read Compose
Multiplatform 1.7.x klibs; `ci-ios.yml` works around both via a `compileKotlinJvm` proxy
for `commonMain`, but no CI lane builds or runs the actual iOS/cinterop compile path):

| Story/Task | What must be manually verified | Where documented |
|---|---|---|
| 5.1c | Bare Xcode project builds and runs (placeholder screen) on a contributor's Mac | Story 5.1 acceptance criteria |
| 5.2c | `PingShim.ping()` cinterop smoke test — see dedicated regression test below | Story 5.2 acceptance criteria (hard gate — Story 5.3 cannot start until this passes) |
| 5.3c | `FoundationModelsShim.checkAvailability`/`format` exercised on macOS 26+/Apple-Intelligence-eligible hardware for at least `available` + one `unavailable` case | Story 5.3 acceptance criteria |
| 5.4c | Real `.def`/cinterop binding compiles, generates Kotlin signatures for `FoundationModelsShim`'s two methods | Story 5.4 acceptance criteria (real gate, not optional) |
| 5.5e | Full Kotlin→cinterop→Swift shim→`FoundationModels` path exercised end-to-end on physical/simulator hardware | Story 5.5 acceptance criteria |

What **is** automatable and lands in `businessTest` (zero cinterop/iOS SDK dependency):

| Test File | Test Name | Purpose |
|---|---|---|
| `IosAvailabilityMappingTest.kt` | `mapShimCode_should_ReturnExpectedAvailability_ForEachOfFiveCodes` | Table-driven over shim codes 0–4, pure `(Int, String?) -> LlmProviderAvailability` function (Task 5.5d) |

**Bottom line**: Epic 5 contributes 5 manual-verification gates (5.1c, 5.2c, 5.3c, 5.4c,
5.5e) that no CI run in this repository can execute or substitute for. A reviewer merging
Epic 5 PRs must independently confirm these were actually performed (e.g. a PR description
noting Xcode/OS version and device) — this is a process control, not a test-suite gap.

---

## UX Acceptance Tests

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| User can add a custom OpenAI-compatible provider (Ollama) in ≤4 steps | `AddEditLlmProviderDialogTest.kt` | `addOllamaProvider_manualFlow` | Manual (Compose Multiplatform screenshot tests are JVM-only via Roborazzi; full interaction flow is manual) | Open Settings → AI Providers → Add Provider → select Ollama preset → Fetch models → Save |
| Provider list never shows an optimistic "Available" before the async check resolves | `LlmProviderListScreenTest.kt` | `initialRender_should_ShowCheckingAvailability` | Manual + Roborazzi screenshot | Open provider list with a slow/mocked `checkAvailability()`; confirm first frame shows "Checking availability…" not a green dot |
| Rejecting a suggestion has no confirmation dialog (feels reversible) | `LlmSuggestionReviewScreenTest.kt` | `discard_should_BeSingleTap` | Manual | Propose a suggestion → tap Discard → confirm immediate removal, no dialog |
| Bulk "Apply all" requires a second confirmation; "Discard all" does not | `LlmSuggestionReviewScreenTest.kt` | `applyAll_should_RequireConfirm_discardAll_should_Not` | Manual | Propose 3+ suggestions → tap Apply all (expect confirm prompt) vs Discard all (expect immediate) |
| No dead ends — retiring `VoiceCaptureSettings` credential fields leaves a redirect note | `VoiceCaptureSettingsTest.kt` (Roborazzi) | `screen_should_ShowRedirectNote_When_CredentialFieldsRemoved` | Screenshot + manual | Render `VoiceCaptureSettings` post-Story-6.6, confirm "Configure AI provider keys in Settings → AI Providers" text is present |
| Web (`wasmJs`) users see an explanatory message instead of a broken remote-provider config, if CORS blocks it | `LlmProviderListScreenTest.kt` | `webTarget_should_HideOrExplainRemoteProviders_When_CorsBlocksThem` | Manual (browser) | Load web build; open AI Providers; confirm messaging per Task 3.3d's finding (conditional — only if CORS confirmed blocking) |
| Keyboard navigable (Settings dialog, provider list, review screen) | manual | `keyboardNav_settingsAndReview` | Manual | Tab through Settings → AI Providers → Add/Edit form → Review screen; confirm no keyboard trap, logical tab order |

---

## Integration/Regression Tests — MUST keep passing unchanged

Per plan.md's sequencing overview (pitfalls §6.3) and Story 8.4's explicit checkpoint, the
following existing suites must pass **unmodified** at every intermediate commit and at the
end of the full migration:

| Test File | Why it must stay unchanged | Checkpoint story |
|---|---|---|
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/LlmProviderSupportTest.kt` | Shared error-mapping/truncation-detection table reused by every remote provider (Claude, OpenAI, Gemini, custom) — a break here means every provider's error handling is suspect | 8.2d, 8.4a |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModelTest.kt` | `LlmFormatterProvider` fun-interface contract is explicitly never replaced, only wrapped (Key Design Decision #1) | 8.1e, 8.4a |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceSettingsTest.kt` | Only the deleted-method assertions are removed (Story 2.4b); everything else (the non-credential fields) stays | 2.4b |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngineTest.kt` | `LlmTagProvider.suggestTags()` contract explicitly unchanged — fallback-selection logic lives in `App.kt`/registry layer, never inside `LlmTagProvider` (pitfalls §6.1 constraint) | 8.2d |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt` | Same as above, ViewModel layer | 8.2d |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/voice/ClaudeLlmFormatterProviderTest.kt` | Validates *implementation* behavior, unaffected by the registry/wrapper layer built around it | 8.4a |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/voice/OpenAiLlmFormatterProviderTest.kt` | Only gains new assertions (Story 3.2c) — existing assertions for `withDefaults(apiKey)` byte-identical behavior must remain green | 3.2c, 8.4a |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricherTest.kt` | **Explicitly exempted from "unchanged"** — plan.md flags this file for substantial rewrite (Story 8.3b, pitfalls §6.1's "will be substantially rewritten, not preserved unchanged"); wire-compatibility assertions (model name, `max_tokens: 256`, prompt shape) are the regression-test subset that must survive the rewrite | 8.3b |

Verification command for the unchanged set: `bazel test //kmp:jvm_tests //kmp:business_tests`
(or `./gradlew jvmTest` per repo convention) run at Story 8.4a as the final sequencing
checkpoint, plus incrementally after each epic per the sequencing overview.

---

## Adversarial-Review Patch Regression Tests

These four tests specifically regression-guard the fixes the adversarial review confirmed
resolved (3 originally-BLOCKED findings, 1 MAJOR finding directly relevant to test design).
Each is written to fail if a future edit reverts the fix, not just to confirm the fix works
today.

### 1. Android credential migration uses `commit()` not `apply()`, survives a simulated crash-between-write-and-flush (B1 fix)

**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/security/AndroidCredentialStoreCommitTest.kt` (new)

```
storeBlocking_should_CallCommitNotApply_On_SharedPreferencesEditor
  — spy/fake SharedPreferences.Editor; call storeBlocking(); assert commit() was
    invoked and apply() was NOT invoked.

storeBlocking_should_ReturnCommitsBooleanResult_NotAssumeSuccess
  — fake Editor.commit() returns false (simulated disk-full/IO failure); assert
    storeBlocking() propagates false, does not silently report success.
```

**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmCredentialMigrationTest.kt` (new — the highest-value test in Epic 2 per pitfalls §6.1)

```
migration_should_MigrateBothKeys_And_ClearVoiceSettings_And_SetMigratedFlag
  — happy path: both keys present, empty LlmCredentialStore → run() → both migrated,
    VoiceSettings getters return null, flag set.

migration_should_BeNoOp_When_AlreadyMigrated
  — run twice; second run is a no-op, no exceptions, values unchanged.

migration_should_MigrateOnlyAnthropic_When_OpenAiNeverConfigured
  — partial-config case; OpenAI absence is not treated as failure.

migration_should_CallSetApiKeyBlocking_NotSetApiKey_ForTheWrite
  — fake LlmCredentialStore recording which method was invoked; asserts
    setApiKeyBlocking() was called — regression guard against silent reversion to
    the non-durable apply()-based path.

migration_should_simulateCrashBetweenWriteAndFlush_should_NotClearPlaintext_When_StoreBlockingReturnsFalse
  — THE crash-simulation test: fake LlmCredentialStore.setApiKeyBlocking() returns
    false (models a commit() that failed — e.g. disk full, or a process kill during
    the synchronous write before it durably lands). Assert:
      (a) VoiceSettings plaintext key is NOT cleared
      (b) migrated flag is NOT set
      (c) no exception propagates
    This directly proves the migration cannot lose a key even in the exact race
    window ADR-011 Decision step 2 exists to close (apply()'s async-flush gap) —
    modeled here as storeBlocking() itself reporting non-durable failure rather than
    silently succeeding.

migration_should_NotClearPlaintext_When_StoreBlockingReturnsTrue_ButReadBackMismatches
  — corrupted-write edge case: setApiKeyBlocking() returns true but getApiKey()
    read-back doesn't match what was written → plaintext NOT cleared, flag NOT set
    (defense-in-depth beyond the durable-write guarantee alone).

migration_should_ResumeAndComplete_When_RunAgainAfterStoreIsFixed
  — partial-then-resume: run once with the store failing (per above), fix the fake,
    run again → completes on the second call, without needing to distinguish "never
    started" from "failed midway."
```

### 2. `Settings.containsKey()` on each platform actual (B2 fix)

**Files**:
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/PlatformSettingsContainsKeyTest.kt` (new, Android actual)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/platform/PlatformSettingsContainsKeyTest.kt` (new, JVM actual)
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/TagSuggestionOnDeviceDefaultTest.kt` (new — the consumer-level regression proving the capability is load-bearing, not just present)

```
# Per-platform actual (android + jvm; wasmJs verified via existing wasmJs test
# infra if present, otherwise documented as a manual/browser-console check since
# this repo's wasmJs test tooling is limited):

containsKey_should_ReturnFalse_When_KeyNeverPut
containsKey_should_ReturnTrue_When_KeyPutEvenIfValueEqualsTypedDefault
  — the case that actually matters: put a boolean value equal to getBoolean()'s
    default (e.g. put "false" when the typed default is also false) → containsKey()
    still returns true, proving it is NOT implemented in terms of the typed getter's
    default-comparison (which would make it useless for Story 8.2's purpose).

# Consumer-level proof (TagSuggestionOnDeviceDefaultTest.kt):
selection_should_DefaultToExplicitlyDisabled_When_ExistingInstall_TagsLlmTierKeyPresent
  — fake Settings with "tags.llm_tier_enabled" explicitly put (containsKey() = true,
    value happens to equal the typed default true) → TAG_SUGGESTION selection is the
    "__disabled__" sentinel, NOT Auto — this is the scenario containsKey()'s fix
    specifically exists to make distinguishable from a fresh install.
selection_should_DefaultToAuto_When_FreshInstall_TagsLlmTierKeyAbsent
  — fake Settings with the key never put (containsKey() = false) → selection is
    null/Auto.
```

Every fake `Settings` test double used elsewhere in the suite (`VoiceSettingsTest.MapSettings`
and any other in-repo fake found by grep, per Task 1.7b) must implement `containsKey`
correctly — this is verified indirectly: any test relying on a fake `Settings` whose
`containsKey` is wired to `false` unconditionally (a common accidental default) would fail
`TagSuggestionOnDeviceDefaultTest.kt`'s existing-install case.

### 3. Synthesis context builder's page-selection is bounded (M1 fix)

**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmSynthesisContextBuilderTest.kt` (new, styled after `QueryPlanAuditTest`/`LargeGraphWarmStartCrashTest`)

```
build_should_IssueAtMostNBoundedQueries_When_RunAgainstLargeGraphFixture
  — reuse the existing synthetic large-graph fixture (the same one
    LargeGraphWarmStartCrashTest/QueryPlanAuditTest use, if accessible) and assert
    the total query count issued by LlmSynthesisContextBuilder.build() stays under a
    fixed bound (does not scale with graph size) — proves O(1)-ish behavior, not
    O(graph).

build_should_NeverFetchFullPageContent_ForMoreThanCappedPageCount
  — assert no more than the configured cap (e.g. 20) pages ever have their content
    fetched, regardless of graph size.

build_should_SelectExactlyTheBacklinksPlusOutboundLinksUnion_When_FixtureHasKnownLinkStructure
  — THE test that closes the exact loophole the adversarial review's M1 finding
    flagged: a fixture with a known, hand-constructed backlink/outbound-link graph
    structure around the current page. Assert the fetched-content page set is
    EXACTLY the expected union of (a) backlinks via getLinkedReferences() and
    (b) outbound [[wiki-links]] resolved via getPagesByNames() — not merely "the
    query count is low." A future implementer who substitutes a scan-based
    relevance heuristic that happens to still call getPagesByNames() (satisfying the
    two tests above) would still fail THIS test, because the selected page set would
    differ from the specified backlinks-then-outbound-links union.

build_should_PreferBacklinksOverOutboundLinks_When_UnionExceedsCapOf20
  — fixture where backlinks + outbound links > 20 → assert backlinks are kept in
    full (up to the cap) and outbound links are truncated first, per the documented
    priority rule.

build_should_CapTotalPromptCharacters_And_PerPageContentChars
  — assert MAX_BLOCK_CHARS/MAX_VOCABULARY_SIZE-style caps are enforced on the
    assembled prompt (mirrors LlmTagProvider's existing cap pattern).
```

### 4. Cinterop smoke-test for the iOS Swift shim (`PingShim`, Story 5.2)

**Files**:
- `kmp/src/nativeInterop/cinterop/PingShim.def` (new, per Task 5.2b)
- Trivial Swift shim under `iosApp/` (per Task 5.2a) — `@objc(PingShim) public class PingShim: NSObject { @objc public func ping() -> Int32 { return 42 } }`
- Manual verification procedure (documented, not automated — see iOS section above)

```
Manual test procedure (Task 5.2c — hard gate, must pass before Story 5.3 starts):
  1. Build the Story 5.1 Xcode scaffold app with the PingShim target linked.
  2. From a throwaway iosMain Kotlin entry point (or the scaffold app itself),
     call the cinterop-generated binding for PingShim().ping().
  3. Assert the returned value is exactly 42 — proves the Kotlin→cinterop→Swift
     boundary crosses correctly in this codebase for a hand-authored shim (as
     opposed to IosCredentialStore.kt's binding against Apple's own Security
     framework headers, which the adversarial review's B3 finding correctly
     identified as a materially simpler, non-equivalent precedent).
  4. Record: Xcode version, macOS version, device/simulator used, and the actual
     observed return value, as a code comment or PR note — per Story 5.2's
     explicit "document the verification steps taken" requirement.

This is a MANUAL-VERIFICATION-ONLY gate — no CI lane in this repository can
execute it (Gradle #17559 + K2/CMP klib incompatibility, per Epic 5's corrected
CI framing). It counts toward the "iOS manual verification" tally below, not the
automated unit/integration counts.
```

---

## Test Case Counts by Type

| Type | Count | Notes |
|---|---|---|
| **Unit** (`businessTest` + `jvmTest`, includes table-driven cases counted per named test method, not per data row) | 78 | Epic 1: 13, Epic 2: 11 (incl. 7 `LlmCredentialMigrationTest` cases), Epic 3: 14, Epic 4: 2 business-logic (+1 Android-only, counted separately), Epic 5: 1 (`IosAvailabilityMappingTest`), Epic 6: 1 (`StelekitViewModelLlmSettingsTest`), Epic 7: 22, Epic 8: 14 |
| **Android-only unit** (`androidUnitTest`) | 5 | `AndroidCredentialStoreCommitTest` (2), `AndroidCredentialStoreFailsLoudTest` (1), `PlatformSettingsContainsKeyTest`-android (2) |
| **Integration** (Ktor `MockEngine` wire-format + registry composition + build verification) | 9 | Gemini/CustomOpenAiCompatible provider HTTP tests counted under Unit above use `MockEngine` but are business-logic-scoped; this row counts the 4 build-verification checkpoints (1.5, 1.8, 3.3d, 5.4c-build) + 5 full-suite regression-gate runs (2.1b, 8.1e, 8.2d, 8.4a, plus the sequencing-checkpoint re-runs) |
| **UI / Compose (screenshot, Roborazzi, Gradle-only)** | 6 | `LlmProviderListScreenTest`, `AddEditLlmProviderDialogTest`, `PerFeatureProviderPickerTest`, `SettingsDialogTest` (extended), `LlmSuggestionReviewScreenTest`, `VoiceCaptureSettingsTest` (extended) |
| **UX acceptance / manual** | 7 | Table in "UX Acceptance Tests" above |
| **iOS manual-verification-only** (no CI substitute exists) | 5 | Stories 5.1c, 5.2c, 5.3c, 5.4c, 5.5e |
| **Regression (existing suites, must stay green, unmodified except where explicitly flagged)** | 8 files | `LlmProviderSupportTest`, `VoiceCaptureViewModelTest`, `VoiceSettingsTest` (partially modified — deletions only), `TagSuggestionEngineTest`, `TagSuggestionViewModelTest`, `ClaudeLlmFormatterProviderTest`, `OpenAiLlmFormatterProviderTest` (extended, not replaced), `ClaudeTopicEnricherTest` (explicitly rewritten, wire-compat subset preserved) |
| **Total distinct new/modified test files** | ~34 | Across `businessTest`, `jvmTest`, `androidUnitTest`, and Compose UI test source sets |

---

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (`businessTest`, `jvmTest`) | `./gradlew jacocoTestReport` → check `kmp/build/reports/jacoco/` | ≥80% line on all new `llm/` package code |
| Android (`androidUnitTest`) | same Gradle Jacoco pipeline, Android variant | ≥80% line on `MlKitLlmFormatterProvider`'s pure-function extraction; SDK-dependent code paths excluded from the ratio (documented exclusion, not silently uncounted) |
| iOS/Swift shim | N/A — no coverage tooling wired for the Swift target in this plan; manual verification is the only signal | N/A (documented gap) |

- All public `LlmProvider`/`LlmProviderRegistry`/`LlmCredentialStore`/`LlmSettings`/
  `LlmSuggestionInbox` methods: happy path + error paths covered (see tables above).
- All external HTTP integrations (Gemini, generalized OpenAI-compatible, existing
  Claude/OpenAI): unit-mocked via Ktor `MockEngine`; no live-network integration test in
  this plan (consistent with existing `ClaudeLlmFormatterProviderTest`/
  `OpenAiLlmFormatterProviderTest` convention).
- UX acceptance criteria: each criterion in the "UX Acceptance Tests" table above has a
  corresponding manual step or Roborazzi screenshot test; there is no `design/ux.md` for
  this project, so these were derived directly from plan.md's Settings UI (Epic 6) and
  Review UI (Story 7.5) acceptance criteria plus the approval-fatigue UX rules cited from
  pitfalls research.

---

## Implementation Readiness Gate

Checked against `requirements.md`, `implementation/plan.md`, this file
(`implementation/validation.md`), and `implementation/adversarial-review.md`.

### Criterion 1: Every requirement in requirements.md's Scope section maps to ≥1 task in plan.md

**PASS.** Cross-checked every bullet under requirements.md's "In Scope" list against
plan.md's epic/story structure:

- Unified provider abstraction → Epic 1 (all 8 stories)
- Anthropic/OpenAI migration → Epic 8 Story 8.1/8.3 (consumers), providers themselves
  pre-exist and are wrapped in Epic 1 Story 1.2/1.6
- Gemini (new) → Epic 3 Story 3.1
- Generic OpenAI-compatible custom-base-URL provider → Epic 3 Story 3.2/3.3
- Android on-device migration + wiring into tag suggestion → Epic 4 (all 3 stories) +
  Epic 8 Story 8.2
- iOS on-device (new) → Epic 5 (all 5 stories)
- Secure credential storage per platform → Epic 2 (all 4 stories)
- Settings UI (list, add/edit, per-feature picker, on-device status surfacing, migrate
  `VoiceSettings` UI) → Epic 6 (all 6 stories)
- Migrate existing consumers (`LlmTagProvider`, voice pipeline, `ClaudeTopicEnricher`) →
  Epic 8 (all 4 stories)
- Approval-gated LLM library-editing workflow → Epic 7 (all 6 stories)

No requirements-Scope bullet was found without a corresponding epic/story. Out-of-Scope
items (cloud credential sync, changing the existing tag-suggestion local-match auto-apply
tier, full multi-step autonomous agent) are correctly absent from plan.md — confirmed no
story attempts any of them.

### Criterion 2: Every task in plan.md has test coverage specified in validation.md

**PASS, with 2 documented exceptions that are correctly non-test-bearing.**

Every task that produces testable logic has a corresponding test in the Requirement→Test
Mapping tables above, cross-referenced by epic/story number. The exceptions:

- Tasks that are pure build-verification steps (1.5a–e, 1.8b, 3.3d partial, 5.4c) are
  covered by "run the build command and confirm it succeeds" — this is listed as
  Integration-type verification in the counts table, not skipped.
- The 5 iOS manual-verification-only tasks (5.1c, 5.2c, 5.3c, 5.4c's runtime portion,
  5.5e) have no automated test by design (documented CI limitation, not a gap in this
  validation plan) — each has an explicit manual procedure specified above.

No task was found with genuinely zero coverage plan (automated or manual).

### Criterion 3: The adversarial review's verdict is not BLOCKED (it's CONCERNS — concerns are genuinely non-fatal/cosmetic)

**PASS.** `adversarial-review.md`'s verdict is **CONCERNS**, upgraded from a prior BLOCKED
state. Independently re-verified in this pass:

- All 3 originally-BLOCKED findings (B1 credential-migration race-safety, B2
  `Settings.containsKey()`, B3 Epic 5 scope/CI framing) are resolved in substance per the
  review's own analysis, and this validation plan additionally encodes each fix as a named
  regression test in the "Adversarial-Review Patch Regression Tests" section above (items
  1–4), so the fixes are not just documented as resolved but structurally enforced by the
  test suite going forward.
- All 4 MAJOR findings (M1 synthesis candidate-selection heuristic, M2
  `VoiceCaptureSettings.kt` retirement, M3 `wasmJs` Ktor engine, M4 Story 7.4 wrong
  citation) are resolved per the review.
- The two remaining findings (C1: stale story-range table cells "5.1–5.3"/"6.1–6.5"
  should read "5.1–5.5"/"6.1–6.6"; C2: stale "34 stories, 87 tasks" total line should read
  "39 stories, ~106 tasks") are explicitly labeled non-blocking/editorial by the review
  itself, confirmed cosmetic on inspection (they are summary-table drift, not
  content/scope errors — the epic bodies themselves are internally consistent with no
  duplicate or missing story/task IDs). These do not affect requirement coverage, task
  definitions, or test design, and do not block Phase 5.

### Criterion 4: No contradiction between the ADRs and the plan

**PASS.** Checked all 4 ADRs against plan.md's corresponding epics:

- **ADR-011** (credential consolidation) ↔ **Epic 2**: the synchronous-write
  (`storeBlocking()`/`commit()`) mechanism, one-shot no-fallback migration sequencing,
  and namespaced key scheme in ADR-011's Decision section match Epic 2 Stories 2.1–2.3
  exactly, including the specific `llm.<providerId>.api_key` key format. No divergence
  found.
- **ADR-012** (in-memory inbox) ↔ **Epic 7**: ADR-012's `LlmSuggestionInbox` code sample
  (constructor, `propose`/`remove`, `MutableStateFlow<Map<String, PendingLlmSuggestion>>`)
  matches Story 7.2's acceptance criteria verbatim, including the explicit "no new
  SQLDelight table" constraint carried through to plan.md's Ground Rules section ("no new
  tables" is listed as inherited from `CLAUDE.md`, consistent with ADR-012 not requiring
  one). `pendingForGraph()` in the ADR matches Story 7.2's spec.
- **ADR-013** (iOS Swift shim) ↔ **Epic 5**: substantively consistent — both describe the
  same shim architecture (completion-handler `@objc` surface,
  `suspendCancellableCoroutine` wrapping, tri-state availability, `ContentRejected` as a
  distinct failure case). One **known, already-flagged, non-blocking textual
  inconsistency** exists: ADR-013 itself (lines 54–58, 133–135) still states "this
  project's CI is Bazel/Gradle on Linux for JVM/Android" and "No CI lane can exercise
  this path," which the adversarial review's own B3 analysis identifies as imprecise —
  the accurate framing (macOS `macos-latest` runners exist; the real gap is Gradle
  #17559 + K2/CMP klib incompatibility) is correctly captured in plan.md's Epic 5 goal
  section and in Section "Flagged for ADR" item 4's closing note ("This ADR's own
  CI-framing text should be corrected to match"). This is a **documentation-consistency
  debt already identified and tracked in plan.md itself**, not an undiscovered
  contradiction — plan.md is the implementation-driving artifact and is self-correcting
  on this point, so it does not block Phase 5, but ADR-013 should receive the same
  one-paragraph correction the next time it's touched (carried forward as a housekeeping
  note, not a gate).
- **ADR-014** (flat registry) ↔ **Epic 1**: ADR-014's `LlmProviderRegistry`/`LlmProvider`/
  `LlmSettings` shapes match Epic 1 Stories 1.2–1.4 with two intentional, non-contradictory
  refinements visible in plan.md but not yet reflected in the ADR's code sample: (a)
  `LlmProviderAvailability` gained a third case's parameterization (`Preparing(detail:
  String?)` vs. the ADR's bare `Downloading` object, and `Unavailable(reason, retryable)`
  vs. the ADR's `Unavailable(reason)`) — this is an additive refinement (retryability and
  detail text), not a contradiction, and is consistent with ADR-014's own text
  acknowledging the tri-state is "load-bearing, not decorative" and subject to
  implementation-time detail. (b) `supportsLongFormOutput` on `LlmProvider` is new in
  plan.md (Story 1.2, feeding Epic 7's synthesis provider filter) and not present in
  ADR-014's original interface sketch — this is a plan-level addition for a downstream
  epic (7) that postdates ADR-014's authoring context, not a reversal of anything ADR-014
  decided. Neither refinement contradicts ADR-014's core decision (flat registry over
  single-active-backend enum) or its stated consequences.

No hard contradiction (a statement in one artifact directly negating a statement in
another) was found between any ADR and plan.md.

### Verdict: **PASS**

All 4 readiness-gate criteria pass. Criterion 3 carries forward two explicitly-cosmetic
editorial findings from the adversarial review (C1, C2 — stale summary-table entries) as
housekeeping items, not blockers. Criterion 4 carries forward one documentation-consistency
debt (ADR-013's CI-framing text, already flagged in plan.md itself) as a housekeeping item
for the next ADR touch, not a blocker. **Recommendation**: fix C1/C2 in plan.md's summary
tables and the ADR-013 CI-framing paragraph as low-cost hygiene before or during Phase 5,
but neither is required to start implementation.
