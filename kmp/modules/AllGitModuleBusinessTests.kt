package dev.stapler.stelekit.modules

import dev.stapler.stelekit.db.AtomicFileRelocationStepTest
import dev.stapler.stelekit.db.BlockHierarchyCteTest
import dev.stapler.stelekit.db.BulkCopyVerifierTest
import dev.stapler.stelekit.db.CalibrationBasisMigrationTest
import dev.stapler.stelekit.db.CalibrationGeometryPersistenceTest
import dev.stapler.stelekit.db.CalibrationLensPersistenceTest
import dev.stapler.stelekit.db.DemoGraphPersistenceTest
import dev.stapler.stelekit.db.DiskConflictBlockMatcherTest
import dev.stapler.stelekit.db.GraphInfoSerializationTest
import dev.stapler.stelekit.db.GraphLoaderLfsPointerTest
import dev.stapler.stelekit.db.GraphLocatorTest
import dev.stapler.stelekit.db.GraphManagerAddGraphTest
import dev.stapler.stelekit.db.GraphManagerEnrichmentCoordinatorTest
import dev.stapler.stelekit.db.GraphManagerInitAutoRestoreTest
import dev.stapler.stelekit.db.GraphManagerNotesPathTest
import dev.stapler.stelekit.db.GraphManagerPendingStorageLocationRemovalTest
import dev.stapler.stelekit.db.GraphManagerRemovalHookTest
import dev.stapler.stelekit.db.GraphManagerRemoveGraphTest
import dev.stapler.stelekit.db.GraphManagerSwitchLockStressTest
import dev.stapler.stelekit.db.GraphManagerSwitchNotesPathTest
import dev.stapler.stelekit.db.GraphManagerUpdateGraphPathTest
import dev.stapler.stelekit.db.GraphManagerUpdateHostDirNameTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorAppOwnedTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorCancellationTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorFailureTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorFlagClearingTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorLinkTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorLoggingTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorPathPersistenceTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorQuiesceTimeoutTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorReopenFailureTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorSameGraphSwitchTest
import dev.stapler.stelekit.db.GraphWriteLockTest
import dev.stapler.stelekit.db.IndexDrainSectionFilterTest
import dev.stapler.stelekit.db.MeasurementValueMigrationTest
import dev.stapler.stelekit.db.MigrationRunnerCoverageTest
import dev.stapler.stelekit.db.ShareWritePathMigrationTest
import dev.stapler.stelekit.db.ShareWritePathRepairTest
import dev.stapler.stelekit.db.SplitJournalTest
import dev.stapler.stelekit.db.SqlDelightBlockRepositoryTest
import dev.stapler.stelekit.db.SqliteStatementAnalyzerTest
import dev.stapler.stelekit.db.UndoManagerTest
import dev.stapler.stelekit.db.misplaced.MisplacedFilesServiceTest
import dev.stapler.stelekit.git.ColdSyncRunnerTest
import dev.stapler.stelekit.git.ConflictMarkerDiffScanTest
import dev.stapler.stelekit.git.EditLockTest
import dev.stapler.stelekit.git.GateAssertingGitRepositoryTest
import dev.stapler.stelekit.git.GateKeyResolutionTest
import dev.stapler.stelekit.git.GitConfigCloneDepthMappingTest
import dev.stapler.stelekit.git.GitOperationSupportHelpersTest
import dev.stapler.stelekit.git.GitRefDiagnosticsTest
import dev.stapler.stelekit.git.GitRepositoryUnshallowTest
import dev.stapler.stelekit.git.GitSyncBusyCounterFetchOnlyTest
import dev.stapler.stelekit.git.GitSyncCancelBoundaryTest
import dev.stapler.stelekit.git.GitSyncMergeUnitContainmentTest
import dev.stapler.stelekit.git.GitSyncServiceAutoCommitTest
import dev.stapler.stelekit.git.GitSyncServiceAutoMergeTest
import dev.stapler.stelekit.git.GitSyncServiceChangeBackTest
import dev.stapler.stelekit.git.GitSyncServiceFirstSyncGateTest
import dev.stapler.stelekit.git.GitSyncServicePublishFailureTest
import dev.stapler.stelekit.git.GitSyncServiceRateLimitRetryTest
import dev.stapler.stelekit.git.GitSyncServiceRegistryTest
import dev.stapler.stelekit.git.GitSyncServiceScheduledSyncTest
import dev.stapler.stelekit.git.GitSyncServiceSingleFlightTest
import dev.stapler.stelekit.git.GitSyncServiceStepRoutingTest
import dev.stapler.stelekit.git.GitSyncServiceStuckStateTest
import dev.stapler.stelekit.git.GitSyncTaskRunnerPolicyTest
import dev.stapler.stelekit.git.GitTransportRetryStateTest
import dev.stapler.stelekit.git.InstallIdTest
import dev.stapler.stelekit.git.JvmGitRepositoryOwnedOperationTest
import dev.stapler.stelekit.git.PausedRetryNowAndDurabilityTest
import dev.stapler.stelekit.git.PausedRetryPolicyTest
import dev.stapler.stelekit.git.PeriodicSyncDriverTest
import dev.stapler.stelekit.git.PrivateRootLockFilesTest
import dev.stapler.stelekit.git.RateLimitRetryGateTest
import dev.stapler.stelekit.git.ResolveGateKeyFailureTest
import dev.stapler.stelekit.git.ScheduledSyncPolicyTest
import dev.stapler.stelekit.git.ScheduledSyncStackE2ETest
import dev.stapler.stelekit.git.ShallowMergeTest
import dev.stapler.stelekit.git.SidecarConflictResolverTest
import dev.stapler.stelekit.git.SqlDelightGitConfigRepositoryRoundTripTest
import dev.stapler.stelekit.git.SyncInProgressConsumersTest
import dev.stapler.stelekit.git.SyncObservabilityLogTest
import dev.stapler.stelekit.git.SyncOriginTriggerMappingTest
import dev.stapler.stelekit.git.SyncSingleFlightCancellationTest
import dev.stapler.stelekit.git.SyncSingleFlightModelTest
import dev.stapler.stelekit.git.SyncSingleFlightRegressionTest
import dev.stapler.stelekit.git.SyncStatusSheetModelPresentationTest
import dev.stapler.stelekit.git.lfs.FileSystemLfsContentProbeTest
import dev.stapler.stelekit.git.lfs.GitAttributesDifferentialTest
import dev.stapler.stelekit.git.lfs.GitWildmatchTest
import dev.stapler.stelekit.git.lfs.LfsIndexVerifierTest
import dev.stapler.stelekit.git.lfs.LfsRawBinaryNeverCommittedPropertyTest
import dev.stapler.stelekit.git.lfs.LfsRawBinaryNeverCommittedTest
import dev.stapler.stelekit.git.lfs.LfsRulesLoaderTest
import dev.stapler.stelekit.git.merge.JournalMergeServiceTest
import dev.stapler.stelekit.git.merge.LogseqMergeDriverTest
import dev.stapler.stelekit.git.merge.StrayPageMergeTest
import dev.stapler.stelekit.platform.FileSystemStoragePortsTest
import dev.stapler.stelekit.platform.StaleTempSweeperTest
import dev.stapler.stelekit.repository.ImageAnnotationTagEscapingTest
import dev.stapler.stelekit.repository.MeasurementOverridePersistenceTest
import dev.stapler.stelekit.repository.RealActorOrderingTest
import dev.stapler.stelekit.repository.SketchDeleteProjectTest
import dev.stapler.stelekit.repository.SketchRepositoryContractTest
import dev.stapler.stelekit.repository.SketchStaleEditorTest
import dev.stapler.stelekit.repository.SqlDelightAssetRepositoryContractTest
import dev.stapler.stelekit.repository.SqlDelightMeasurementAnnotationWritesTest
import dev.stapler.stelekit.repository.SqlDelightSearchRepositoryTest
import dev.stapler.stelekit.repository.WikilinkBatchInsertTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/** Generated by scripts/kmp-module-tests-sync.py: the businessTest classes of module `git`. */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    AtomicFileRelocationStepTest::class,
    BlockHierarchyCteTest::class,
    BulkCopyVerifierTest::class,
    CalibrationBasisMigrationTest::class,
    CalibrationGeometryPersistenceTest::class,
    CalibrationLensPersistenceTest::class,
    DemoGraphPersistenceTest::class,
    DiskConflictBlockMatcherTest::class,
    GraphInfoSerializationTest::class,
    GraphLoaderLfsPointerTest::class,
    GraphLocatorTest::class,
    GraphManagerAddGraphTest::class,
    GraphManagerEnrichmentCoordinatorTest::class,
    GraphManagerInitAutoRestoreTest::class,
    GraphManagerNotesPathTest::class,
    GraphManagerPendingStorageLocationRemovalTest::class,
    GraphManagerRemovalHookTest::class,
    GraphManagerRemoveGraphTest::class,
    GraphManagerSwitchLockStressTest::class,
    GraphManagerSwitchNotesPathTest::class,
    GraphManagerUpdateGraphPathTest::class,
    GraphManagerUpdateHostDirNameTest::class,
    GraphRelocationCoordinatorAppOwnedTest::class,
    GraphRelocationCoordinatorCancellationTest::class,
    GraphRelocationCoordinatorFailureTest::class,
    GraphRelocationCoordinatorFlagClearingTest::class,
    GraphRelocationCoordinatorLinkTest::class,
    GraphRelocationCoordinatorLoggingTest::class,
    GraphRelocationCoordinatorPathPersistenceTest::class,
    GraphRelocationCoordinatorQuiesceTimeoutTest::class,
    GraphRelocationCoordinatorReopenFailureTest::class,
    GraphRelocationCoordinatorSameGraphSwitchTest::class,
    GraphWriteLockTest::class,
    IndexDrainSectionFilterTest::class,
    MeasurementValueMigrationTest::class,
    MigrationRunnerCoverageTest::class,
    ShareWritePathMigrationTest::class,
    ShareWritePathRepairTest::class,
    SplitJournalTest::class,
    SqlDelightBlockRepositoryTest::class,
    SqliteStatementAnalyzerTest::class,
    UndoManagerTest::class,
    MisplacedFilesServiceTest::class,
    ColdSyncRunnerTest::class,
    ConflictMarkerDiffScanTest::class,
    EditLockTest::class,
    GateAssertingGitRepositoryTest::class,
    GateKeyResolutionTest::class,
    GitConfigCloneDepthMappingTest::class,
    GitOperationSupportHelpersTest::class,
    GitRefDiagnosticsTest::class,
    GitRepositoryUnshallowTest::class,
    GitSyncBusyCounterFetchOnlyTest::class,
    GitSyncCancelBoundaryTest::class,
    GitSyncMergeUnitContainmentTest::class,
    GitSyncServiceAutoCommitTest::class,
    GitSyncServiceAutoMergeTest::class,
    GitSyncServiceChangeBackTest::class,
    GitSyncServiceFirstSyncGateTest::class,
    GitSyncServicePublishFailureTest::class,
    GitSyncServiceRateLimitRetryTest::class,
    GitSyncServiceRegistryTest::class,
    GitSyncServiceScheduledSyncTest::class,
    GitSyncServiceSingleFlightTest::class,
    GitSyncServiceStepRoutingTest::class,
    GitSyncServiceStuckStateTest::class,
    GitSyncTaskRunnerPolicyTest::class,
    GitTransportRetryStateTest::class,
    InstallIdTest::class,
    JvmGitRepositoryOwnedOperationTest::class,
    PausedRetryNowAndDurabilityTest::class,
    PausedRetryPolicyTest::class,
    PeriodicSyncDriverTest::class,
    PrivateRootLockFilesTest::class,
    RateLimitRetryGateTest::class,
    ResolveGateKeyFailureTest::class,
    ScheduledSyncPolicyTest::class,
    ScheduledSyncStackE2ETest::class,
    ShallowMergeTest::class,
    SidecarConflictResolverTest::class,
    SqlDelightGitConfigRepositoryRoundTripTest::class,
    SyncInProgressConsumersTest::class,
    SyncObservabilityLogTest::class,
    SyncOriginTriggerMappingTest::class,
    SyncSingleFlightCancellationTest::class,
    SyncSingleFlightModelTest::class,
    SyncSingleFlightRegressionTest::class,
    SyncStatusSheetModelPresentationTest::class,
    FileSystemLfsContentProbeTest::class,
    GitAttributesDifferentialTest::class,
    GitWildmatchTest::class,
    LfsIndexVerifierTest::class,
    LfsRawBinaryNeverCommittedPropertyTest::class,
    LfsRawBinaryNeverCommittedTest::class,
    LfsRulesLoaderTest::class,
    JournalMergeServiceTest::class,
    LogseqMergeDriverTest::class,
    StrayPageMergeTest::class,
    FileSystemStoragePortsTest::class,
    StaleTempSweeperTest::class,
    ImageAnnotationTagEscapingTest::class,
    MeasurementOverridePersistenceTest::class,
    RealActorOrderingTest::class,
    SketchDeleteProjectTest::class,
    SketchRepositoryContractTest::class,
    SketchStaleEditorTest::class,
    SqlDelightAssetRepositoryContractTest::class,
    SqlDelightMeasurementAnnotationWritesTest::class,
    SqlDelightSearchRepositoryTest::class,
    WikilinkBatchInsertTest::class,
)
class AllGitModuleBusinessTests
