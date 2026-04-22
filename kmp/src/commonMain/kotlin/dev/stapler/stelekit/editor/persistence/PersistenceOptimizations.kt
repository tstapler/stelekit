package dev.stapler.stelekit.editor.persistence

import dev.stapler.stelekit.performance.PerformanceMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debouncer for batching and throttling persistence operations
 */
class PersistenceDebouncer(
    private val scope: CoroutineScope,
    private val debounceInterval: Long = 3000L,
    private val maxBatchSize: Int = 50
) {
    private val logger = dev.stapler.stelekit.logging.Logger("PersistenceDebouncer")
    
    private val changeChannel = Channel<BlockChange>(capacity = Channel.UNLIMITED)
    private val batchChannel = Channel<List<BlockChange>>(capacity = Channel.UNLIMITED)
    
    private var debounceJob: Job? = null
    private var batchProcessorJob: Job? = null
    
    val batches: Flow<List<BlockChange>> = batchChannel.receiveAsFlow()
    
    /**
     * Queue a change for debouncing
     */
    suspend fun queueChange(change: BlockChange) {
        changeChannel.send(change)
    }
    
    /**
     * Queue multiple changes
     */
    suspend fun queueChanges(changes: List<BlockChange>) {
        changes.forEach { queueChange(it) }
    }
    
    /**
     * Start the debouncing process
     */
    suspend fun start() {
        stop()
        
        debounceJob = scope.launch {
            val pendingChanges = mutableMapOf<String, BlockChange>()
            
            while (isActive) {
                try {
                    // Collect changes for debounce interval
                    val collectStartTime = Clock.System.now()
                    
                    while (isActive) {
                        val hasChange = changeChannel.tryReceive().getOrNull() != null
                        
                        if (hasChange) {
                            // Drain all available changes
                            while (true) {
                                val change = changeChannel.tryReceive().getOrNull() ?: break
                                
                                // Keep only the latest change for each block
                                pendingChanges[change.blockUuid] = change
                            }
                        }
                        
                        // Check if debounce interval has passed or max batch size reached
                        val elapsed = Clock.System.now() - collectStartTime
                        if (elapsed.inWholeMilliseconds >= debounceInterval || 
                            pendingChanges.size >= maxBatchSize) {
                            break
                        }
                        
                        delay(100) // Check every 100ms
                    }
                    
                    // Emit batch if we have changes
                    if (pendingChanges.isNotEmpty()) {
                        val batch = pendingChanges.values.toList()
                        pendingChanges.clear()
                        
                        PerformanceMonitor.startTrace("debounceBatch")
                        batchChannel.send(batch)
                        PerformanceMonitor.endTrace("debounceBatch")
                        
                        logger.debug("Emitted batch of ${batch.size} debounced changes")
                    }
                    
                } catch (e: Exception) {
                    logger.error("Debouncing error", e)
                }
            }
        }
        
        logger.info("Persistence debouncer started with interval ${debounceInterval}ms, max batch size $maxBatchSize")
    }
    
    /**
     * Stop the debouncing process and flush remaining changes
     */
    suspend fun stop() {
        debounceJob?.cancel()
        batchProcessorJob?.cancel()
        
        // Flush remaining changes
        val pendingChanges = mutableListOf<BlockChange>()
        while (true) {
            val change = changeChannel.tryReceive().getOrNull() ?: break
            pendingChanges.add(change)
        }
        
        if (pendingChanges.isNotEmpty()) {
            batchChannel.send(pendingChanges)
            logger.info("Flushed ${pendingChanges.size} pending changes on stop")
        }
        
        logger.info("Persistence debouncer stopped")
    }
    
    /**
     * Force immediate flush of pending changes
     */
    suspend fun flush() {
        val pendingChanges = mutableListOf<BlockChange>()
        while (true) {
            val change = changeChannel.tryReceive().getOrNull() ?: break
            pendingChanges.add(change)
        }
        
        if (pendingChanges.isNotEmpty()) {
            batchChannel.send(pendingChanges)
        }
    }
}


/**
 * Performance monitor for persistence operations
 */
class PersistencePerformanceMonitor(
    private val scope: CoroutineScope,
    private val enabled: Boolean = true
) {
    private val logger = dev.stapler.stelekit.logging.Logger("PersistencePerfMonitor")
    private val mutex = Mutex()
    
    private val operationTimes = mutableMapOf<String, MutableList<Long>>()
    private val operationCounts = mutableMapOf<String, Long>()
    private val operationErrors = mutableMapOf<String, Long>()
    
    private val _metrics = MutableStateFlow(PersistencePerformanceMetrics(0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0L))
    val metrics: StateFlow<PersistencePerformanceMetrics> = _metrics.asStateFlow()
    
    private var monitoringJob: Job? = null
    
    /**
     * Start performance monitoring
     */
    fun start() {
        if (!enabled) return
        
        // Don't call stop() here as it might be suspend
        // Just cancel directly
        monitoringJob?.cancel()
        
        monitoringJob = scope.launch {
            while (isActive) {
                try {
                    updateMetrics()
                    delay(5000) // Update metrics every 5 seconds
                } catch (e: Exception) {
                    logger.error("Performance monitoring error", e)
                }
            }
        }
        
        logger.info("Persistence performance monitoring started")
    }
    
    /**
     * Stop performance monitoring
     */
    fun stop() {
        monitoringJob?.cancel()
        monitoringJob = null
        logger.info("Persistence performance monitoring stopped")
    }
    
    /**
     * Record the start of an operation
     */
    fun startOperation(operation: String) {
        if (!enabled) return
        
        PerformanceMonitor.startTrace("persistence_$operation")
    }
    
    /**
     * Record the completion of an operation
     */
    suspend fun endOperation(operation: String, success: Boolean, duration: Long? = null) {
        if (!enabled) return
        
        PerformanceMonitor.endTrace("persistence_$operation")
        
        // Record metrics
        mutex.withLock {
            operationTimes.getOrPut(operation) { mutableListOf() }.add(duration ?: 0)
            operationCounts[operation] = (operationCounts[operation] ?: 0) + 1
            
            if (!success) {
                operationErrors[operation] = (operationErrors[operation] ?: 0) + 1
            }
            
            // Keep only last 100 measurements per operation
            val times = operationTimes[operation]!!
            if (times.size > 100) {
                times.removeAt(0)
            }
        }
    }
    
    /**
     * Get performance metrics for a specific operation
     */
    suspend fun getOperationMetrics(operation: String): OperationMetrics? {
        if (!enabled) return null
        
        return mutex.withLock {
            val times = operationTimes[operation] ?: return@withLock null
            val count = operationCounts[operation] ?: return@withLock null
            val errors = operationErrors[operation] ?: 0L
            
            OperationMetrics(
                operation = operation,
                totalOperations = count,
                successfulOperations = count - errors,
                failedOperations = errors,
                averageTime = times.average(),
                minTime = times.minOrNull() ?: 0L,
                maxTime = times.maxOrNull() ?: 0L,
                lastOperationTime = times.lastOrNull() ?: 0L
            )
        }
    }
    
    /**
     * Get all operation metrics
     */
    suspend fun getAllOperationMetrics(): Map<String, OperationMetrics> {
        if (!enabled) return emptyMap()
        
        return mutex.withLock {
            operationCounts.keys.mapNotNull { operation ->
                // Since we are inside the lock, we can access the maps directly
                val times = operationTimes[operation] ?: return@mapNotNull null
                val count = operationCounts[operation] ?: return@mapNotNull null
                val errors = operationErrors[operation] ?: 0L
                
                operation to OperationMetrics(
                    operation = operation,
                    totalOperations = count,
                    successfulOperations = count - errors,
                    failedOperations = errors,
                    averageTime = times.average(),
                    minTime = times.minOrNull() ?: 0L,
                    maxTime = times.maxOrNull() ?: 0L,
                    lastOperationTime = times.lastOrNull() ?: 0L
                )
            }.toMap()
        }
    }
    
    /**
     * Clear all performance data
     */
    suspend fun clearMetrics() {
        mutex.withLock {
            operationTimes.clear()
            operationCounts.clear()
            operationErrors.clear()
        }
        
        _metrics.value = PersistencePerformanceMetrics(0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0L)
    }
    
    private suspend fun updateMetrics() {
        val allMetrics = getAllOperationMetrics()
        
        if (allMetrics.isEmpty()) return
        
        val totalOps = allMetrics.values.sumOf { it.totalOperations }
        val totalErrors = allMetrics.values.sumOf { it.failedOperations }
        val avgTime = allMetrics.values.map { it.averageTime }.average()
        
        val metrics = PersistencePerformanceMetrics(
            averageSaveTime = avgTime,
            averageQueueTime = 0.0, // Would need queue tracking
            throughputOperationsPerSecond = totalOps.toDouble() / 5.0, // Per 5 second interval
            memoryUsage = memoryUsage,
            diskUsage = diskUsage,
            conflictRate = if (totalOps > 0) totalErrors.toDouble() / totalOps else 0.0,
            retryRate = 0.0, // Would need retry tracking
            uptime = Clock.System.now().toEpochMilliseconds()
        )

        _metrics.value = metrics
    }

    // Platform-specific memory usage calculation; in KMP common, returns 0 until platform code is available.
    private val memoryUsage: Long = 0L

    // Would need platform-specific disk usage calculation.
    private val diskUsage: Long = 0L
}


/**
 * Metrics for a specific operation
 */
data class OperationMetrics(
    val operation: String,
    val totalOperations: Long,
    val successfulOperations: Long,
    val failedOperations: Long,
    val averageTime: Double,
    val minTime: Long,
    val maxTime: Long,
    val lastOperationTime: Long
) {
    val successRate: Double
        get() = if (totalOperations > 0) successfulOperations.toDouble() / totalOperations else 0.0
        
    val errorRate: Double
        get() = if (totalOperations > 0) failedOperations.toDouble() / totalOperations else 0.0
}

/**
 * Optimizer for persistence operations using various strategies
 */
class PersistenceOptimizer {
    private val logger = dev.stapler.stelekit.logging.Logger("PersistenceOptimizer")
    
    /**
     * Optimize batch of changes by grouping and ordering
     */
    fun optimizeBatch(changes: List<BlockChange>): OptimizedBatch {
        val startTime = Clock.System.now().toEpochMilliseconds()
        
        // Group changes by type and block
        val groupedChanges = changes.groupBy { it.blockUuid }
        val optimizedChanges = mutableListOf<BlockChange>()
        
        for ((blockUuid, blockChanges) in groupedChanges) {
            // Sort changes by timestamp
            val sortedChanges = blockChanges.sortedBy { it.timestamp }
            
            // Merge compatible changes
            val mergedChanges = mergeCompatibleChanges(sortedChanges)
            optimizedChanges.addAll(mergedChanges)
        }
        
        // Sort by dependency and priority
        val sortedChanges = sortChangesByPriority(optimizedChanges)
        
        val duration = Clock.System.now().toEpochMilliseconds() - startTime
        logger.debug("Optimized ${changes.size} changes to ${sortedChanges.size} in ${duration}ms")
        
        return OptimizedBatch(
            originalChanges = changes,
            optimizedChanges = sortedChanges,
            reductionRatio = 1.0 - (sortedChanges.size.toDouble() / changes.size),
            optimizationTime = duration
        )
    }
    
    /**
     * Detect and remove redundant changes
     */
    fun removeRedundantChanges(changes: List<BlockChange>): List<BlockChange> {
        val seenChanges = mutableSetOf<String>()
        val filteredChanges = mutableListOf<BlockChange>()
        
        for (change in changes) {
            val changeKey = "${change.blockUuid}_${change.type}"
            
            // Skip if we've seen this change type for this block
            if (seenChanges.contains(changeKey)) {
                continue
            }
            
            seenChanges.add(changeKey)
            filteredChanges.add(change)
        }
        
        return filteredChanges
    }
    
    /**
     * Group changes by page for batch processing
     */
    fun groupChangesByPage(
        changes: List<BlockChange>,
        getBlockPageId: (String) -> Long?
    ): Map<Long, List<BlockChange>> {
        return changes.mapNotNull { change ->
            getBlockPageId(change.blockUuid)?.let { it to change }
        }.groupBy({ it.first }, { it.second })
    }
    
    /**
     * Estimate processing time for changes
     */
    fun estimateProcessingTime(changes: List<BlockChange>): ProcessingTimeEstimate {
        val baseTimePerChange = 50L // 50ms per change average
        val batchOverhead = 100L // 100ms batch overhead
        
        val totalTime = baseTimePerChange * changes.size + batchOverhead
        val complexityMultiplier = calculateComplexityMultiplier(changes)
        
        return ProcessingTimeEstimate(
            estimatedTime = (totalTime * complexityMultiplier).toLong(),
            complexity = complexityMultiplier,
            recommendedBatchSize = min(changes.size, 50),
            shouldParallelize = changes.size > 10
        )
    }
    
    private fun mergeCompatibleChanges(changes: List<BlockChange>): List<BlockChange> {
        if (changes.size <= 1) return changes
        
        val merged = mutableListOf<BlockChange>()
        var current: BlockChange? = null
        
        for (change in changes) {
            if (current == null) {
                current = change
            } else if (canMergeChanges(current, change)) {
                current = mergeChanges(current, change)
            } else {
                merged.add(current)
                current = change
            }
        }
        
        current?.let { merged.add(it) }
        return merged
    }
    
    private fun canMergeChanges(change1: BlockChange, change2: BlockChange): Boolean {
        // Only merge changes of same type for same block
        return change1.blockUuid == change2.blockUuid && 
               change1.type == change2.type &&
               change1.type in listOf(ChangeType.CONTENT, ChangeType.PROPERTIES)
    }
    
    private fun mergeChanges(change1: BlockChange, change2: BlockChange): BlockChange {
        return change2.copy(
            oldContent = change1.oldContent,
            oldProperties = change1.oldProperties,
            metadata = change2.metadata.copy(
                version = max(change1.metadata.version, change2.metadata.version)
            )
        )
    }
    
    private fun sortChangesByPriority(changes: List<BlockChange>): List<BlockChange> {
        return changes.sortedWith(compareBy<BlockChange> { change ->
            when (change.type) {
                ChangeType.DELETION -> 0 // First
                ChangeType.CREATION -> 1
                ChangeType.MOVE -> 2
                ChangeType.CONTENT -> 3
                ChangeType.PROPERTIES -> 4 // Last
            }
        }.thenBy { it.timestamp })
    }
    
    private fun calculateComplexityMultiplier(changes: List<BlockChange>): Double {
        var complexity = 1.0
        
        // Increase complexity for certain operations
        val deletionCount = changes.count { it.type == ChangeType.DELETION }
        val moveCount = changes.count { it.type == ChangeType.MOVE }
        
        complexity += (deletionCount * 0.2) // Deletions are expensive
        complexity += (moveCount * 0.3) // Moves are expensive
        
        // Increase complexity for large batches
        if (changes.size > 20) complexity += 0.5
        if (changes.size > 50) complexity += 0.5
        
        return min(complexity, 3.0) // Cap at 3x
    }
}

/**
 * Result of batch optimization
 */
data class OptimizedBatch(
    val originalChanges: List<BlockChange>,
    val optimizedChanges: List<BlockChange>,
    val reductionRatio: Double,
    val optimizationTime: Long
)

/**
 * Processing time estimate
 */
data class ProcessingTimeEstimate(
    val estimatedTime: Long,
    val complexity: Double,
    val recommendedBatchSize: Int,
    val shouldParallelize: Boolean
)
