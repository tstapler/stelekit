package dev.stapler.stelekit

import dev.stapler.stelekit.benchmark.GraphLoadJankTest
import dev.stapler.stelekit.benchmark.GraphLoadTimingTest
import dev.stapler.stelekit.benchmark.RepositoryBenchmarkRunnerTest
import dev.stapler.stelekit.benchmark.RepositoryBenchmarkTest
import dev.stapler.stelekit.cache.CacheHitBenchmarkTest
import dev.stapler.stelekit.db.AppLoadJournalIntegrationTest
import dev.stapler.stelekit.db.BacklinkRenamerTest
import dev.stapler.stelekit.db.BlocksFtsTriggerTest
import dev.stapler.stelekit.db.ConflictMarkerDetectorTest
import dev.stapler.stelekit.db.DatabaseWriteActorTest
import dev.stapler.stelekit.db.DemoGraphIntegrationTest
import dev.stapler.stelekit.db.DiffMergeTest
import dev.stapler.stelekit.db.ExternalChangeConflictTest
import dev.stapler.stelekit.db.FileRegistryTest
import dev.stapler.stelekit.db.GraphLoaderIntegrationTest
import dev.stapler.stelekit.db.GraphLoaderProgressiveTest
import dev.stapler.stelekit.db.GraphLoaderSectionFilterTest
import dev.stapler.stelekit.db.GraphLoaderTest
import dev.stapler.stelekit.sections.SectionFilterTest
import dev.stapler.stelekit.sections.SectionManifestParserTest
import dev.stapler.stelekit.db.GraphLoaderWatcherTest
import dev.stapler.stelekit.db.GraphWriterTest
import dev.stapler.stelekit.db.MigrationRunnerApplyAllTest
import dev.stapler.stelekit.db.PooledJdbcSqliteDriverTest
import dev.stapler.stelekit.db.QueryPlanAuditTest
import dev.stapler.stelekit.db.SanitizationTest
import dev.stapler.stelekit.docs.DemoGraphCoverageTest
import dev.stapler.stelekit.docs.FeatureDocRegistryTest
import dev.stapler.stelekit.domain.ClaudeTopicEnricherTest
import dev.stapler.stelekit.domain.UrlFetcherJvmTest
import dev.stapler.stelekit.export.ExportIntegrationTest
import dev.stapler.stelekit.export.HtmlExporterTest
import dev.stapler.stelekit.integration.JournalParseReproTest
import dev.stapler.stelekit.integration.PipelineReproductionTest
import dev.stapler.stelekit.llm.RemoteLlmProviderTest
import dev.stapler.stelekit.migration.ChangeApplierTest
import dev.stapler.stelekit.migration.DagValidatorTest
import dev.stapler.stelekit.migration.DryRunTest
import dev.stapler.stelekit.migration.DslEvaluatorTest
import dev.stapler.stelekit.migration.MigrationDslTest
import dev.stapler.stelekit.migration.MigrationRegressionTest
import dev.stapler.stelekit.migration.MigrationRunnerTest
import dev.stapler.stelekit.migration.MigrationsTest
import dev.stapler.stelekit.migration.NormalizeJournalNamesMigrationTest
import dev.stapler.stelekit.migration.RepairTest
import dev.stapler.stelekit.platform.PlatformSettingsContainsKeyTest
import dev.stapler.stelekit.repository.CacheInvalidationTest
import dev.stapler.stelekit.repository.ExactTitleMatchTest
import dev.stapler.stelekit.repository.FtsRebuildTest
import dev.stapler.stelekit.repository.SaveBlocksChunkingTest
import dev.stapler.stelekit.repository.SearchLatencyTest
import dev.stapler.stelekit.repository.SearchRepositoryIntegrationTests
import dev.stapler.stelekit.repository.VisitRecencyMultiplierTest
import dev.stapler.stelekit.search.FtsQueryBuilderTest
import dev.stapler.stelekit.stats.LibraryWrappedTest
import dev.stapler.stelekit.testing.OutlinerMonkeyTest
import dev.stapler.stelekit.ui.DemoBannerTest
import dev.stapler.stelekit.ui.DiskConflictResolutionTest
import dev.stapler.stelekit.ui.GraphSwitcherDemoFilterTest
import dev.stapler.stelekit.ui.DragDropReorderTest
import dev.stapler.stelekit.ui.KeyboardShortcutTest
import dev.stapler.stelekit.ui.MigrationReadyLoadingTest
import dev.stapler.stelekit.ui.OutlinerRegressionTest
import dev.stapler.stelekit.ui.RecentPagesTest
import dev.stapler.stelekit.ui.StelekitViewModelLlmSettingsTest
import dev.stapler.stelekit.ui.StelekitViewModelLoadingTest
import dev.stapler.stelekit.ui.components.ApplyAutocompleteSelectionTest
import dev.stapler.stelekit.ui.components.ParseMarkdownWithStylingTest
import dev.stapler.stelekit.ui.components.SearchDialogTest
import dev.stapler.stelekit.ui.components.SuggestionContextMenuTest
import dev.stapler.stelekit.ui.components.SuggestionNavigatorPanelTest
import dev.stapler.stelekit.ui.components.SuggestionRenderBenchmarkTest
import dev.stapler.stelekit.ui.components.TopBarTest
import dev.stapler.stelekit.ui.components.settings.AddEditLlmProviderDialogTest
import dev.stapler.stelekit.ui.components.settings.LlmProviderListScreenTest
import dev.stapler.stelekit.ui.components.settings.PerFeatureProviderPickerTest
import dev.stapler.stelekit.ui.components.settings.SettingsDialogTest
import dev.stapler.stelekit.ui.components.settings.VoiceCaptureSettingsTest
import dev.stapler.stelekit.ui.layout.DesktopLayoutTest
import dev.stapler.stelekit.ui.layout.MobileLayoutTest
import dev.stapler.stelekit.ui.layout.SidebarLoadingStateTest
import dev.stapler.stelekit.ui.screens.AllPagesViewModelTest
import dev.stapler.stelekit.ui.screens.JournalsViewSqlDelightTest
import dev.stapler.stelekit.ui.screens.JournalsViewUITest
import dev.stapler.stelekit.ui.screens.PageViewUITest
import dev.stapler.stelekit.vault.crypto.CryptoEngineTest
import dev.stapler.stelekit.vault.integration.GraphLayerCryptoTest
import dev.stapler.stelekit.vault.integration.VaultRoundTripTest
import dev.stapler.stelekit.vault.layer.CryptoLayerTest
import dev.stapler.stelekit.vault.perf.VaultPerformanceTest
import dev.stapler.stelekit.vault.property.VaultPropertyTest
import dev.stapler.stelekit.vault.security.AdversarialTest
import dev.stapler.stelekit.vault.security.KeyslotIntegrityTest
import dev.stapler.stelekit.vault.security.NoncePropertyTest
import dev.stapler.stelekit.vault.vault.VaultHeaderSerializerTest
import dev.stapler.stelekit.vault.vault.VaultManagerTest
import dev.stapler.stelekit.voice.ClaudeLlmFormatterProviderTest
import dev.stapler.stelekit.voice.OpenAiLlmFormatterProviderTest
import dev.stapler.stelekit.voice.WhisperSpeechToTextProviderTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    GraphLoadJankTest::class,
    GraphLoadTimingTest::class,
    RepositoryBenchmarkRunnerTest::class,
    RepositoryBenchmarkTest::class,
    CacheHitBenchmarkTest::class,
    DatabaseIntegrationTest::class,
    AppLoadJournalIntegrationTest::class,
    BacklinkRenamerTest::class,
    BlocksFtsTriggerTest::class,
    ConflictMarkerDetectorTest::class,
    DatabaseWriteActorTest::class,
    DemoGraphIntegrationTest::class,
    DiffMergeTest::class,
    ExternalChangeConflictTest::class,
    FileRegistryTest::class,
    GraphLoaderIntegrationTest::class,
    GraphLoaderProgressiveTest::class,
    GraphLoaderSectionFilterTest::class,
    GraphLoaderTest::class,
    SectionFilterTest::class,
    SectionManifestParserTest::class,
    GraphLoaderWatcherTest::class,
    GraphWriterTest::class,
    MigrationRunnerApplyAllTest::class,
    PooledJdbcSqliteDriverTest::class,
    QueryPlanAuditTest::class,
    SanitizationTest::class,
    DemoGraphCoverageTest::class,
    FeatureDocRegistryTest::class,
    ClaudeTopicEnricherTest::class,
    UrlFetcherJvmTest::class,
    ExportIntegrationTest::class,
    HtmlExporterTest::class,
    JournalParseReproTest::class,
    PipelineReproductionTest::class,
    ChangeApplierTest::class,
    DagValidatorTest::class,
    DryRunTest::class,
    DslEvaluatorTest::class,
    MigrationDslTest::class,
    MigrationRegressionTest::class,
    MigrationRunnerTest::class,
    MigrationsTest::class,
    NormalizeJournalNamesMigrationTest::class,
    RepairTest::class,
    CacheInvalidationTest::class,
    ExactTitleMatchTest::class,
    FtsRebuildTest::class,
    SaveBlocksChunkingTest::class,
    SearchLatencyTest::class,
    SearchRepositoryIntegrationTests::class,
    VisitRecencyMultiplierTest::class,
    FtsQueryBuilderTest::class,
    LibraryWrappedTest::class,
    OutlinerMonkeyTest::class,
    ApplyAutocompleteSelectionTest::class,
    ParseMarkdownWithStylingTest::class,
    SearchDialogTest::class,
    SuggestionContextMenuTest::class,
    SuggestionNavigatorPanelTest::class,
    SuggestionRenderBenchmarkTest::class,
    TopBarTest::class,
    DemoBannerTest::class,
    GraphSwitcherDemoFilterTest::class,
    DiskConflictResolutionTest::class,
    DragDropReorderTest::class,
    KeyboardShortcutTest::class,
    DesktopLayoutTest::class,
    MobileLayoutTest::class,
    SidebarLoadingStateTest::class,
    MigrationReadyLoadingTest::class,
    OutlinerRegressionTest::class,
    RecentPagesTest::class,
    AllPagesViewModelTest::class,
    JournalsViewSqlDelightTest::class,
    JournalsViewUITest::class,
    PageViewUITest::class,
    StelekitViewModelLoadingTest::class,
    StelekitViewModelLlmSettingsTest::class,
    LlmProviderListScreenTest::class,
    AddEditLlmProviderDialogTest::class,
    PerFeatureProviderPickerTest::class,
    SettingsDialogTest::class,
    VoiceCaptureSettingsTest::class,
    CryptoEngineTest::class,
    GraphLayerCryptoTest::class,
    VaultRoundTripTest::class,
    CryptoLayerTest::class,
    VaultPerformanceTest::class,
    VaultPropertyTest::class,
    AdversarialTest::class,
    KeyslotIntegrityTest::class,
    NoncePropertyTest::class,
    VaultHeaderSerializerTest::class,
    VaultManagerTest::class,
    ClaudeLlmFormatterProviderTest::class,
    OpenAiLlmFormatterProviderTest::class,
    RemoteLlmProviderTest::class,
    WhisperSpeechToTextProviderTest::class,
    PlatformSettingsContainsKeyTest::class,
)
class AllJvmTests
