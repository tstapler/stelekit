package dev.stapler.stelekit

// `bazel test //kmp:business_tests` only runs classes listed below — a plain `srcs` glob
// (see kmp/src/businessTest/kotlin/BUILD.bazel) compiles every *Test.kt file in this source
// set, but JUnit's `@Suite.SuiteClasses` runner only executes what's named here, so a new
// test file that isn't added to this list silently never runs under Bazel. This list was
// found to cover only 25 of 101 businessTest files as of 2026-09-10; the other 76 compiled
// but never executed via this target. Bazel CI (bazel-ci.yml) only runs `//kmp:jvm_tests`,
// which does not depend on this target at all, so Bazel-side CI never exercised most of
// businessTest's tests. They do run via `./gradlew jvmTest` (Gradle's default JUnit
// auto-discovery, no suite list) — currently invoked only in the release gate
// (release.yml), not on every PR.
//
// 7 of those 76 needed real fixes rather than just adding an import: MigrationRunnerSchemaSyncTest,
// BacklogTriageRuleTest, GapBacklogSchemaTest, GapBacklogTraceabilityTest, JourneyDocsFrontmatterTest,
// JourneyStepCountRubricTest, and GitShadowWorktreeNoCoroutineScopeTest all read real repo files
// (SteleDatabase.sq, docs/journeys/*.md, project_plans/.../gap-backlog.md+plan.md,
// GitShadowWorktree.kt) or a Gradle-injected system property, none of which Bazel's jarred
// runfiles classpath provides the way Gradle's unpacked one does. Fixed by bundling each real
// file as a classpath resource (see BUILD.bazel's *_as_resource(s) targets in this package, and
// the root/kmp/androidMain BUILD.bazel filegroups they consume) and updating each test (and
// DocRepoLocator, shared by 4 of them) to try that resource first, falling back to the original
// Gradle-only resolution — the same Bazel-resource/Gradle-fallback idiom DemoFileSystemSyncTest
// already applies to DemoFileSystem.kt.
import dev.stapler.stelekit.clipboard.BlockClipboardTest
import dev.stapler.stelekit.domain.ImportServiceTest
import dev.stapler.stelekit.editor.LinkInsertionTest
import dev.stapler.stelekit.flashcard.FlashcardPropertiesTest
import dev.stapler.stelekit.flashcard.FlashcardReviewTest
import dev.stapler.stelekit.llm.IosAvailabilityMappingTest
import dev.stapler.stelekit.llm.LlmProviderAvailabilityTest
import dev.stapler.stelekit.llm.LlmProviderRegistryFactoryTest
import dev.stapler.stelekit.llm.LlmProviderRegistryTest
import dev.stapler.stelekit.llm.LlmSettingsTest
import dev.stapler.stelekit.performance.HistogramRegressionTest
import dev.stapler.stelekit.performance.HistogramWriterTest
import dev.stapler.stelekit.performance.PerfExporterPickerTest
import dev.stapler.stelekit.db.DemoFileSystemSyncTest
import dev.stapler.stelekit.db.DemoGraphPersistenceTest
import dev.stapler.stelekit.db.GraphInfoSerializationTest
import dev.stapler.stelekit.db.SplitJournalTest
import dev.stapler.stelekit.repository.BacklinkRepositoryTest
import dev.stapler.stelekit.sections.CrossSectionBacklinkRenderTest
import dev.stapler.stelekit.sections.NewPageAutoAssignmentTest
import dev.stapler.stelekit.transfer.GraphMergeServiceTest
import dev.stapler.stelekit.ui.ToolbarActionTest
import dev.stapler.stelekit.voice.VoiceCaptureViewModelTest
import dev.stapler.stelekit.voice.VoiceNoteBlockFormatTest
import dev.stapler.stelekit.voice.VoicePipelineFactoryTest
import dev.stapler.stelekit.voice.VoiceSettingsTest
import dev.stapler.stelekit.asset.AssetMediaTypeTest
import dev.stapler.stelekit.asset.AssetStoragePathResolverTest
import dev.stapler.stelekit.asset.MimeTypeDetectorTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineServiceTest
import dev.stapler.stelekit.asset.pipeline.PluginRegistryTest
import dev.stapler.stelekit.auto.AudiobookNoteFormatterTest
import dev.stapler.stelekit.auto.AudiobookNoteWriterTest
import dev.stapler.stelekit.db.BlockHierarchyCteTest
import dev.stapler.stelekit.db.DiskConflictBlockMatcherTest
import dev.stapler.stelekit.db.GraphLoaderDirtySetTest
import dev.stapler.stelekit.db.GraphManagerAddGraphTest
import dev.stapler.stelekit.db.GraphManagerEnrichmentCoordinatorTest
import dev.stapler.stelekit.db.GraphManagerInitAutoRestoreTest
import dev.stapler.stelekit.db.GraphManagerRemoveGraphTest
import dev.stapler.stelekit.db.GraphManagerUpdateGraphPathTest
import dev.stapler.stelekit.db.GraphManagerUpdateHostDirNameTest
import dev.stapler.stelekit.db.GraphSwitchInvalidationTest
import dev.stapler.stelekit.db.IndexDrainSectionFilterTest
import dev.stapler.stelekit.db.MigrationRunnerCoverageTest
import dev.stapler.stelekit.db.MigrationRunnerIndexTest
import dev.stapler.stelekit.db.MigrationRunnerSchemaSyncTest
import dev.stapler.stelekit.db.SqliteStatementAnalyzerTest
import dev.stapler.stelekit.db.WithoutRowidMigrationTest
import dev.stapler.stelekit.docs.BacklogTriageRuleTest
import dev.stapler.stelekit.docs.GapBacklogSchemaTest
import dev.stapler.stelekit.docs.GapBacklogTraceabilityTest
import dev.stapler.stelekit.docs.JourneyDocsFrontmatterTest
import dev.stapler.stelekit.docs.JourneyStepCountRubricTest
import dev.stapler.stelekit.editor.ImageAttachCallbackContractTest
import dev.stapler.stelekit.export.ExportServiceJournalRangeTest
import dev.stapler.stelekit.export.ExportServiceLinkedPagesTest
import dev.stapler.stelekit.git.GitShadowWorktreeNoCoroutineScopeTest
import dev.stapler.stelekit.git.GitSyncServiceRateLimitRetryTest
import dev.stapler.stelekit.git.GitSyncServiceTest
import dev.stapler.stelekit.git.merge.JournalMergeServiceTest
import dev.stapler.stelekit.git.merge.LogseqMergeDriverTest
import dev.stapler.stelekit.llm.AndroidOnDeviceFallbackTest
import dev.stapler.stelekit.llm.CustomProviderUrlValidationTest
import dev.stapler.stelekit.llm.LlmCredentialMigrationTest
import dev.stapler.stelekit.llm.LlmCredentialStoreTest
import dev.stapler.stelekit.llm.LlmSettingsCustomProviderTest
import dev.stapler.stelekit.llm.LlmSuggestionInboxTest
import dev.stapler.stelekit.llm.LlmSuggestionWriterTest
import dev.stapler.stelekit.llm.LlmSynthesisContextBuilderTest
import dev.stapler.stelekit.llm.LlmSynthesisServiceTest
import dev.stapler.stelekit.llm.PendingLlmSuggestionTest
import dev.stapler.stelekit.llm.StelekitViewModelLlmSuggestionTest
import dev.stapler.stelekit.llm.TagSuggestionOnDeviceDefaultTest
import dev.stapler.stelekit.llm.VoiceDeviceLlmMigrationTest
import dev.stapler.stelekit.model.BlockTypeTest
import dev.stapler.stelekit.performance.OtelProviderStabilityTest
import dev.stapler.stelekit.platform.measurement.MeasurementInjectionTest
import dev.stapler.stelekit.platform.security.CredentialAccessTest
import dev.stapler.stelekit.repository.AssetRepositoryTest
import dev.stapler.stelekit.repository.WikilinkBatchInsertTest
import dev.stapler.stelekit.sections.DeviceProfileTest
import dev.stapler.stelekit.sections.ThreeStateSubscriptionTest
import dev.stapler.stelekit.tags.LlmTagProviderTest
import dev.stapler.stelekit.tags.TagAvailabilityPollerTest
import dev.stapler.stelekit.tags.TagSuggestionEngineTest
import dev.stapler.stelekit.tags.TagSuggestionViewModelTest
import dev.stapler.stelekit.tags.WikiLinkExtractorTest
import dev.stapler.stelekit.transfer.qrcode.QrImportServiceTest
import dev.stapler.stelekit.transfer.qrcode.QrTransferCoordinatorTest
import dev.stapler.stelekit.transfer.qrcode.TransferSessionTest
import dev.stapler.stelekit.ui.GalleryViewModelTest
import dev.stapler.stelekit.ui.screens.DiskConflictFullScreenStateTest
import dev.stapler.stelekit.ui.state.BlockInvalidationIntegrationTest
import dev.stapler.stelekit.ui.transfer.QrDecodeViewModelTest
import dev.stapler.stelekit.ui.transfer.QrEncodeViewModelTest
import dev.stapler.stelekit.util.FractionalIndexingTest
import dev.stapler.stelekit.voice.GenAiErrorMappingTest
import dev.stapler.stelekit.voice.LlmProviderSupportTest
import dev.stapler.stelekit.voice.MlKitAvailabilityMappingTest
import dev.stapler.stelekit.voice.VoicePipelineConfigTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    BlockClipboardTest::class,
    ImportServiceTest::class,
    LinkInsertionTest::class,
    FlashcardPropertiesTest::class,
    FlashcardReviewTest::class,
    IosAvailabilityMappingTest::class,
    LlmProviderAvailabilityTest::class,
    LlmProviderRegistryFactoryTest::class,
    LlmProviderRegistryTest::class,
    LlmSettingsTest::class,
    HistogramRegressionTest::class,
    HistogramWriterTest::class,
    PerfExporterPickerTest::class,
    BacklinkRepositoryTest::class,
    CrossSectionBacklinkRenderTest::class,
    NewPageAutoAssignmentTest::class,
    DemoFileSystemSyncTest::class,
    DemoGraphPersistenceTest::class,
    GraphInfoSerializationTest::class,
    SplitJournalTest::class,
    GraphMergeServiceTest::class,
    ToolbarActionTest::class,
    VoiceCaptureViewModelTest::class,
    VoiceNoteBlockFormatTest::class,
    VoicePipelineFactoryTest::class,
    VoiceSettingsTest::class,
    AssetMediaTypeTest::class,
    AssetStoragePathResolverTest::class,
    MimeTypeDetectorTest::class,
    AssetPipelineServiceTest::class,
    PluginRegistryTest::class,
    AudiobookNoteFormatterTest::class,
    AudiobookNoteWriterTest::class,
    BlockHierarchyCteTest::class,
    DiskConflictBlockMatcherTest::class,
    GraphLoaderDirtySetTest::class,
    GraphManagerAddGraphTest::class,
    GraphManagerEnrichmentCoordinatorTest::class,
    GraphManagerInitAutoRestoreTest::class,
    GraphManagerRemoveGraphTest::class,
    GraphManagerUpdateGraphPathTest::class,
    GraphManagerUpdateHostDirNameTest::class,
    GraphSwitchInvalidationTest::class,
    IndexDrainSectionFilterTest::class,
    MigrationRunnerCoverageTest::class,
    MigrationRunnerIndexTest::class,
    MigrationRunnerSchemaSyncTest::class,
    SqliteStatementAnalyzerTest::class,
    WithoutRowidMigrationTest::class,
    BacklogTriageRuleTest::class,
    GapBacklogSchemaTest::class,
    GapBacklogTraceabilityTest::class,
    JourneyDocsFrontmatterTest::class,
    JourneyStepCountRubricTest::class,
    ImageAttachCallbackContractTest::class,
    ExportServiceJournalRangeTest::class,
    ExportServiceLinkedPagesTest::class,
    GitShadowWorktreeNoCoroutineScopeTest::class,
    GitSyncServiceRateLimitRetryTest::class,
    GitSyncServiceTest::class,
    JournalMergeServiceTest::class,
    LogseqMergeDriverTest::class,
    AndroidOnDeviceFallbackTest::class,
    CustomProviderUrlValidationTest::class,
    LlmCredentialMigrationTest::class,
    LlmCredentialStoreTest::class,
    LlmSettingsCustomProviderTest::class,
    LlmSuggestionInboxTest::class,
    LlmSuggestionWriterTest::class,
    LlmSynthesisContextBuilderTest::class,
    LlmSynthesisServiceTest::class,
    PendingLlmSuggestionTest::class,
    StelekitViewModelLlmSuggestionTest::class,
    TagSuggestionOnDeviceDefaultTest::class,
    VoiceDeviceLlmMigrationTest::class,
    BlockTypeTest::class,
    OtelProviderStabilityTest::class,
    MeasurementInjectionTest::class,
    CredentialAccessTest::class,
    AssetRepositoryTest::class,
    WikilinkBatchInsertTest::class,
    DeviceProfileTest::class,
    ThreeStateSubscriptionTest::class,
    LlmTagProviderTest::class,
    TagAvailabilityPollerTest::class,
    TagSuggestionEngineTest::class,
    TagSuggestionViewModelTest::class,
    WikiLinkExtractorTest::class,
    QrImportServiceTest::class,
    QrTransferCoordinatorTest::class,
    TransferSessionTest::class,
    GalleryViewModelTest::class,
    DiskConflictFullScreenStateTest::class,
    BlockInvalidationIntegrationTest::class,
    QrDecodeViewModelTest::class,
    QrEncodeViewModelTest::class,
    FractionalIndexingTest::class,
    GenAiErrorMappingTest::class,
    LlmProviderSupportTest::class,
    MlKitAvailabilityMappingTest::class,
    VoicePipelineConfigTest::class,
)
class AllBusinessTests
