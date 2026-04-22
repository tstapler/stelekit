package dev.stapler.stelekit.editor.persistence

import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.performance.PerformanceMonitor
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DatascriptBlockRepository
import dev.stapler.stelekit.ui.NotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock

/**
 * Factory for creating and integrating persistence components.
 * Updated to use UUID-native storage.
 */
object PersistenceFactory {
    
    /**
     * Create a fully integrated persistence system
     */
    fun createPersistenceSystem(
        blockRepository: BlockRepository,
        _graphWriter: GraphWriter,
        _fileSystem: PlatformFileSystem,
        notificationManager: NotificationManager,
        scope: CoroutineScope,
        config: PersistenceConfig = PersistenceConfig.DEFAULT
    ): IntegratedPersistenceSystem {
        
        // Create core persistence manager
        val persistenceManager = PersistenceManager(
            blockRepository = blockRepository,
            notificationManager = notificationManager,
            scope = scope,
            initialConfig = config
        )
        
        // Create performance components
        val debouncer = PersistenceDebouncer(
            scope = scope,
            debounceInterval = config.autoSaveInterval,
            maxBatchSize = config.maxQueueSize
        )
        
        val optimizer = PersistenceOptimizer()
        val performanceMonitor = PersistencePerformanceMonitor(scope, config.enablePerformanceMonitoring)
        
        // Create error handler
        val errorHandler = PersistenceErrorHandler(
            scope = scope,
            notificationManager = notificationManager,
            maxRetries = config.maxRetries,
            baseRetryDelay = config.retryDelay
        )
        
        return IntegratedPersistenceSystem(
            persistenceManager = persistenceManager,
            debouncer = debouncer,
            optimizer = optimizer,
            performanceMonitor = performanceMonitor,
            errorHandler = errorHandler,
            scope = scope
        )
    }
}

/**
 * Integrated persistence system that combines all components
 */
class IntegratedPersistenceSystem(
    val persistenceManager: PersistenceManager,
    val debouncer: PersistenceDebouncer,
    val optimizer: PersistenceOptimizer,
    val performanceMonitor: PersistencePerformanceMonitor,
    val errorHandler: PersistenceErrorHandler,
    private val scope: CoroutineScope
) {
    
    private val logger = dev.stapler.stelekit.logging.Logger("IntegratedPersistenceSystem")
    
    // Combined state
    val systemState: StateFlow<SystemState> = combine(
        persistenceManager.state,
        errorHandler.failedOperations,
        performanceMonitor.metrics,
        errorHandler.recoveryStatus
    ) { persistenceState, failedOps, metrics, recoveryStatus ->
        SystemState(
            persistenceState = persistenceState,
            failedOperationsCount = failedOps.size,
            performanceMetrics = metrics,
            recoveryStatus = recoveryStatus,
            isHealthy = persistenceState.isEnabled && 
                       failedOps.size < 10 && 
                       recoveryStatus != RecoveryStatus.FAILED
        )
    }.stateIn(scope, SharingStarted.Eagerly, SystemState())
    
    /**
     * Start the entire persistence system
     */
    suspend fun start(): Result<Unit> = try {
        // Start components in order
        performanceMonitor.start()
        debouncer.start()
        persistenceManager.start()
        
        // Connect debouncer to persistence manager
        scope.launch {
            debouncer.batches.collect { batch ->
                try {
                    PerformanceMonitor.startTrace("processBatch")
                    
                    // Optimize batch
                    val optimizedBatch = optimizer.optimizeBatch(batch)
                    
                    // Queue optimized changes
                    persistenceManager.queueChanges(optimizedBatch.optimizedChanges)
                    
                    PerformanceMonitor.endTrace("processBatch")
                    performanceMonitor.endOperation("processBatch", true)
                    
                    logger.debug("Processed batch of ${batch.size} changes (optimized to ${optimizedBatch.optimizedChanges.size})")
                    
                } catch (e: Exception) {
                    performanceMonitor.endOperation("processBatch", false)
                    
                    // Handle batch processing error
                    errorHandler.handleFailure(
                        operation = "processBatch",
                        blockUuid = null,
                        error = e,
                        context = PersistenceContext(
                            sessionId = generateSessionId(),
                            metadata = mapOf("batchSize" to batch.size)
                        )
                    )
                }
            }
        }
        
        logger.info("Integrated persistence system started")
        Result.success(Unit)
        
    } catch (e: Exception) {
        logger.error("Failed to start integrated persistence system", e)
        Result.failure(e)
    }
    
    /**
     * Stop the entire persistence system
     */
    suspend fun stop(force: Boolean = false): Result<Unit> = try {
        debouncer.stop()
        persistenceManager.stop(force)
        performanceMonitor.stop()
        
        logger.info("Integrated persistence system stopped")
        Result.success(Unit)
        
    } catch (e: Exception) {
        logger.error("Failed to stop integrated persistence system", e)
        Result.failure(e)
    }
    
    /**
     * Save a block with full integration
     */
    suspend fun saveBlockIntegrated(block: Block): Result<PersistenceResult> = try {
        performanceMonitor.startOperation("saveBlock")
        
        val result = persistenceManager.saveBlock(block)
        val persistenceResult = result.getOrThrow()
        
        if (persistenceResult.success) {
            performanceMonitor.endOperation("saveBlock", true, persistenceResult.duration)
        } else {
            performanceMonitor.endOperation("saveBlock", false, persistenceResult.duration)
            
            // Handle save failure
            errorHandler.handleFailure(
                operation = "saveBlock",
                blockUuid = block.uuid,
                error = Exception(persistenceResult.message ?: "Save failed"),
                context = PersistenceContext(
                    sessionId = generateSessionId(),
                    metadata = mapOf(
                        "retryCount" to persistenceResult.retryCount,
                        "blockUuid" to block.uuid
                    )
                )
            )
        }
        
        Result.success(persistenceResult)
        
    } catch (e: Exception) {
        performanceMonitor.endOperation("saveBlock", false)
        
        errorHandler.handleFailure(
            operation = "saveBlock",
            blockUuid = block.uuid,
            error = e,
            context = PersistenceContext(sessionId = generateSessionId())
        )
        
        Result.failure(e)
    }
    
    /**
     * Queue block changes through debouncer
     */
    suspend fun queueChangeIntegrated(change: BlockChange): Result<Unit> = try {
        performanceMonitor.startOperation("queueChange")
        
        // Queue through debouncer for batching
        debouncer.queueChange(change)
        
        performanceMonitor.endOperation("queueChange", true)
        Result.success(Unit)
        
    } catch (e: Exception) {
        performanceMonitor.endOperation("queueChange", false)
        Result.failure(e)
    }
    
    /**
     * Get comprehensive system statistics
     */
    suspend fun getSystemStats(): SystemStats {
        val persistenceStats = persistenceManager.stats.value
        val persistenceState = persistenceManager.state.value
        val performanceMetrics = performanceMonitor.metrics.value
        val recoveryStats = errorHandler.getRecoveryStats()
        val recentResults = persistenceManager.recentResults.value.take(10)
        
        return SystemStats(
            persistenceStats = persistenceStats,
            performanceMetrics = performanceMetrics,
            recoveryStats = recoveryStats,
            recentOperations = recentResults,
            systemHealth = calculateSystemHealth(persistenceStats, persistenceState, performanceMetrics, recoveryStats)
        )
    }
    
    /**
     * Perform system health check
     */
    suspend fun performHealthCheck(): HealthCheckResult {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Check persistence state
        val persistenceState = persistenceManager.state.value
        if (!persistenceState.isEnabled) {
            issues.add("Persistence manager is disabled")
        }
        
        if (persistenceState.pendingChangesCount > 50) {
            warnings.add("High number of pending changes: ${persistenceState.pendingChangesCount}")
        }
        
        // Check failed operations
        val failedOps = errorHandler.failedOperations.value
        if (failedOps.size > 10) {
            warnings.add("High number of failed operations: ${failedOps.size}")
        }
        
        if (errorHandler.recoveryStatus.value == RecoveryStatus.FAILED) {
            issues.add("Recovery system has failed")
        }
        
        // Check performance metrics
        val metrics = performanceMonitor.metrics.value
        if (metrics.conflictRate > 0.1) {
            warnings.add("High conflict rate: ${(metrics.conflictRate * 100 * 10).toInt() / 10.0}%")
        }
        
        if (metrics.retryRate > 0.2) {
            warnings.add("High retry rate: ${(metrics.retryRate * 100 * 10).toInt() / 10.0}%")
        }
        
        val healthScore = calculateHealthScore(issues.size, warnings.size)
        
        return HealthCheckResult(
            isHealthy = issues.isEmpty(),
            healthScore = healthScore,
            issues = issues,
            warnings = warnings,
            timestamp = Clock.System.now()
        )
    }
    
    private fun calculateSystemHealth(
        _persistenceStats: PersistenceStats,
        persistenceState: PersistenceState,
        performanceMetrics: PersistencePerformanceMetrics,
        recoveryStats: RecoveryStats
    ): SystemHealth {
        return when {
            !persistenceState.isAutoSaveActive -> SystemHealth.DEGRADED
            recoveryStats.totalFailures > 20 -> SystemHealth.CRITICAL
            recoveryStats.totalFailures > 5 -> SystemHealth.WARNING
            performanceMetrics.conflictRate > 0.1 -> SystemHealth.WARNING
            else -> SystemHealth.HEALTHY
        }
    }
    
    private fun calculateHealthScore(issues: Int, warnings: Int): Int {
        return maxOf(0, 100 - (issues * 20) - (warnings * 5))
    }
    
    private fun generateSessionId(): String {
        return "session_${Clock.System.now().epochSeconds}_${(1000..9999).random()}"
    }
}

/**
 * Overall system state
 */
data class SystemState(
    val persistenceState: PersistenceState = PersistenceState(),
    val failedOperationsCount: Int = 0,
    val performanceMetrics: PersistencePerformanceMetrics = PersistencePerformanceMetrics(0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0L),
    val recoveryStatus: RecoveryStatus = RecoveryStatus.IDLE,
    val isHealthy: Boolean = true
)

/**
 * Comprehensive system statistics
 */
data class SystemStats(
    val persistenceStats: PersistenceStats,
    val performanceMetrics: PersistencePerformanceMetrics,
    val recoveryStats: RecoveryStats,
    val recentOperations: List<PersistenceResult>,
    val systemHealth: SystemHealth
)

/**
 * Health check result
 */
data class HealthCheckResult(
    val isHealthy: Boolean,
    val healthScore: Int,
    val issues: List<String>,
    val warnings: List<String>,
    val timestamp: kotlin.time.Instant
)

/**
 * Overall system health status
 */
enum class SystemHealth {
    HEALTHY,
    WARNING,
    DEGRADED,
    CRITICAL
}

/**
 * Example usage demonstration
 */
object PersistenceExample {
    
    /**
     * Example of how to set up and use the integrated persistence system
     */
    suspend fun demonstrateUsage() {
        // This would be replaced with actual dependencies in real usage
        val scope = CoroutineScope(Dispatchers.Default)
        
        // Create mock dependencies (replace with real implementations)
        val blockRepository = DatascriptBlockRepository()
        val fileSystem = PlatformFileSystem()
        val graphWriter = GraphWriter(fileSystem)
        
        val notificationManager = NotificationManager()
        
        // Create integrated system
        val system = PersistenceFactory.createPersistenceSystem(
            blockRepository = blockRepository,
            _graphWriter = graphWriter,
            _fileSystem = fileSystem,
            notificationManager = notificationManager,
            scope = scope,
            config = PersistenceConfig.DEFAULT.copy(
                autoSaveInterval = 2000L,
                enablePerformanceMonitoring = true,
                enableConflictResolution = true
            )
        )
        
        // Start the system
        val startResult = system.start()
        if (startResult.isSuccess) {
            println("Persistence system started successfully")
        } else {
            println("Failed to start persistence system: ${startResult.exceptionOrNull()?.message}")
            return
        }
        
        // Example: Create and save a block
        val block = Block(
            uuid = "test-block-uuid",
            pageUuid = "test-page-uuid",
            content = "This is a test block",
            level = 0,
            position = 0,
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now()
        )
        
        val saveResult = system.saveBlockIntegrated(block)
        println("Block save result: ${saveResult.getOrNull()}")
        
        // Example: Queue changes through debouncer
        val change = BlockChange(
            blockUuid = block.uuid,
            type = ChangeType.CONTENT,
            timestamp = kotlin.time.Clock.System.now(),
            oldContent = "This is a test block",
            newContent = "This is an updated test block"
        )
        
        system.queueChangeIntegrated(change)
        println("Change queued for debounced processing")
        
        // Wait a bit for debouncing
        delay(3000)
        
        // Check system stats
        val stats = system.getSystemStats()
        println("System stats: ${stats.systemHealth}")
        
        // Perform health check
        val healthCheck = system.performHealthCheck()
        println("Health check: Score ${healthCheck.healthScore}, Healthy: ${healthCheck.isHealthy}")
        
        // Stop the system
        system.stop()
        println("Persistence system stopped")
    }
}
