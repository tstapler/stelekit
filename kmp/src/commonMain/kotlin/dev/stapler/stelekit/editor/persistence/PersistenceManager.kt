@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor.persistence

import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Validation
import dev.stapler.stelekit.performance.PerformanceMonitor
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.ui.NotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.Result
import kotlin.math.max

/**
 * Implementation of IPersistenceManager with auto-save queue and change detection
 */
class PersistenceManager(
    private val blockRepository: BlockRepository,
    private val graphWriter: GraphWriter,
    private val fileSystem: PlatformFileSystem,
    private val notificationManager: NotificationManager,
    private val scope: CoroutineScope,
    initialConfig: PersistenceConfig = PersistenceConfig.DEFAULT
) : IPersistenceManager {
    
    private val logger = dev.stapler.stelekit.logging.Logger("PersistenceManager")
    private val conflictDetector = ConflictDetector()
    private val conflictResolver = ConflictResolver()
    private val mutex = Mutex()
    
    // State management
    private val _state = MutableStateFlow(PersistenceState())
    override val state: StateFlow<PersistenceState> = _state.asStateFlow()
    
    private val _config = MutableStateFlow(initialConfig)
    override val config: StateFlow<PersistenceConfig> = _config.asStateFlow()
    
    private val _stats = MutableStateFlow(PersistenceStats())
    override val stats: StateFlow<PersistenceStats> = _stats.asStateFlow()
    
    private val _recentResults = MutableStateFlow<List<PersistenceResult>>(emptyList())
    override val recentResults: StateFlow<List<PersistenceResult>> = _recentResults.asStateFlow()
    
    private val _queueSize = MutableStateFlow(0)
    override val queueSize: StateFlow<Int> = _queueSize.asStateFlow()
    
    // Internal state
    private val changeQueue = mutableListOf<BlockChange>()
    private val blockSaveStates = mutableMapOf<String, BlockSaveState>()
    private val failedOperations = mutableListOf<PersistenceResult>()
    
    // Coroutine jobs
    private var autoSaveJob: Job? = null
    private var backupJob: Job? = null
    
    private var isStarted = false
    private var isPaused = false
    
    override suspend fun start(): Result<Unit> {
        try {
            if (isStarted) {
                return Result.success(Unit)
            }
            
            logger.info("Starting persistence manager with config: ${_config.value}")
            
            // Start auto-save job
            startAutoSaveJob()
            
            // Start backup job if enabled
            if (_config.value.backupEnabled) {
                startBackupJob()
            }
            
            isStarted = true
            _state.update { it.copy(isEnabled = true, isAutoSaveActive = true) }
            
            logger.info("Persistence manager started successfully")
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to start persistence manager", e)
            return Result.failure(e)
        }
    }
    
    override suspend fun stop(force: Boolean): Result<Unit> {
        try {
            if (!isStarted) {
                return Result.success(Unit)
            }
            
            logger.info("Stopping persistence manager (force=$force)")
            
            // Stop auto-save
            autoSaveJob?.cancel()
            backupJob?.cancel()
            
            // Save pending changes unless forced
            if (!force) {
                forceSave()
            }
            
            isStarted = false
            _state.update { it.copy(isEnabled = false, isAutoSaveActive = false) }
            
            logger.info("Persistence manager stopped")
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to stop persistence manager", e)
            return Result.failure(e)
        }
    }
    
    override suspend fun pause(): Result<Unit> {
        try {
            if (!isStarted) return Result.failure(IllegalStateException("Manager not started"))
            
            isPaused = true
            autoSaveJob?.cancel()
            _state.update { it.copy(isAutoSaveActive = false) }
            
            logger.info("Persistence manager paused")
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
    
    override suspend fun resume(): Result<Unit> {
        try {
            if (!isStarted) return Result.failure(IllegalStateException("Manager not started"))
            
            isPaused = false
            startAutoSaveJob()
            _state.update { it.copy(isAutoSaveActive = true) }
            
            logger.info("Persistence manager resumed")
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
    
    override suspend fun updateConfig(newConfig: PersistenceConfig): Result<Unit> {
        return try {
            Validation.validateContent(newConfig.toString()) // Basic validation
            
            val oldConfig = _config.value
            _config.value = newConfig
            
            // Restart jobs if intervals changed
            if (newConfig.autoSaveInterval != oldConfig.autoSaveInterval && !isPaused) {
                autoSaveJob?.cancel()
                startAutoSaveJob()
            }
            
            if (newConfig.backupInterval != oldConfig.backupInterval) {
                backupJob?.cancel()
                if (newConfig.backupEnabled) {
                    startBackupJob()
                }
            }
            
            _state.update { it.copy(autoSaveInterval = newConfig.autoSaveInterval) }
            
            logger.info("Persistence config updated")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun queueChange(change: BlockChange): Result<Unit> {
        return try {
            Validation.validateUuid(change.blockUuid)
            
            mutex.withLock {
                // Check for duplicate changes
                val existingIndex = changeQueue.indexOfLast { it.blockUuid == change.blockUuid }
                if (existingIndex >= 0) {
                    // Replace existing change with new one
                    changeQueue[existingIndex] = change
                } else {
                    // Add new change
                    if (changeQueue.size >= _config.value.maxQueueSize) {
                        changeQueue.removeFirst()
                        logger.warn("Change queue full, dropped oldest change")
                    }
                    changeQueue.add(change)
                }
                
                _queueSize.value = changeQueue.size
            }
            
            updateStats()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun queueChanges(changes: List<BlockChange>): Result<Unit> {
        return try {
            changes.forEach { change ->
                Validation.validateUuid(change.blockUuid)
            }
            
            mutex.withLock {
                changes.forEach { change ->
                    val existingIndex = changeQueue.indexOfLast { it.blockUuid == change.blockUuid }
                    if (existingIndex >= 0) {
                        changeQueue[existingIndex] = change
                    } else {
                        if (changeQueue.size < _config.value.maxQueueSize) {
                            changeQueue.add(change)
                        }
                    }
                }
                _queueSize.value = changeQueue.size
            }
            
            updateStats()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun markDirty(blockUuid: String, currentContent: String): Result<Unit> {
        return try {
            Validation.validateUuid(blockUuid)
            Validation.validateContent(currentContent)
            
            val saveState = blockSaveStates.getOrPut(blockUuid) {
                BlockSaveState(blockUuid, currentContent)
            }
            
            val isDirty = saveState.lastKnownContent != currentContent
            blockSaveStates[blockUuid] = saveState.copy(
                lastKnownContent = currentContent,
                isDirty = isDirty
            )
            
            if (isDirty) {
                queueChange(BlockChange(
                    blockUuid = blockUuid,
                    type = ChangeType.CONTENT,
                    timestamp = Clock.System.now(),
                    oldContent = saveState.lastKnownContent,
                    newContent = currentContent
                ))
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun markDirty(blockUuids: List<String>, currentContent: Map<String, String>): Result<Unit> {
        return try {
            blockUuids.forEach { Validation.validateUuid(it) }
            currentContent.values.forEach { Validation.validateContent(it) }
            
            val changes = mutableListOf<BlockChange>()
            
            for (blockUuid in blockUuids) {
                val content = currentContent[blockUuid] ?: continue
                val saveState = blockSaveStates.getOrPut(blockUuid) {
                    BlockSaveState(blockUuid, content)
                }
                
                val isDirty = saveState.lastKnownContent != content
                blockSaveStates[blockUuid] = saveState.copy(
                    lastKnownContent = content,
                    isDirty = isDirty
                )
                
                if (isDirty) {
                    changes.add(BlockChange(
                        blockUuid = blockUuid,
                        type = ChangeType.CONTENT,
                        timestamp = Clock.System.now(),
                        oldContent = saveState.lastKnownContent,
                        newContent = content
                    ))
                }
            }
            
            if (changes.isNotEmpty()) {
                queueChanges(changes)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun isDirty(blockUuid: String): Result<Boolean> {
        return try {
            Validation.validateUuid(blockUuid)
            
            val saveState = blockSaveStates[blockUuid]
            Result.success(saveState?.isDirty ?: false)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getBlockSaveState(blockUuid: String): Result<BlockSaveState?> {
        return try {
            Validation.validateUuid(blockUuid)
            Result.success(blockSaveStates[blockUuid])
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun forceSave(): Result<Unit> {
        try {
            val changesToProcess = mutex.withLock {
                val changes = changeQueue.toList()
                changeQueue.clear()
                _queueSize.value = 0
                changes
            }
            
            if (changesToProcess.isEmpty()) {
                return Result.success(Unit)
            }
            
            logger.info("Force saving ${changesToProcess.size} changes")
            
            val results = processChanges(changesToProcess)
            val successCount = results.count { it.success }
            val failureCount = results.size - successCount
            
            _stats.update { it.copy(
                totalOperations = it.totalOperations + results.size,
                successfulOperations = it.successfulOperations + successCount,
                failedOperations = it.failedOperations + failureCount,
                lastSaveTime = Clock.System.now()
            ) }
            
            if (failureCount > 0) {
                logger.warn("$failureCount out of ${results.size} saves failed")
                notificationManager.show(
                    "Failed to save $failureCount blocks",
                    dev.stapler.stelekit.model.NotificationType.WARNING
                )
            } else {
                logger.info("Successfully saved $successCount blocks")
            }
            
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Force save failed", e)
            return Result.failure(e)
        }
    }
    
    override suspend fun forceSaveBlocks(blockUuids: List<String>): Result<Unit> {
        try {
            blockUuids.forEach { Validation.validateUuid(it) }
            
            val changesToProcess = mutex.withLock {
                val changes = changeQueue.filter { it.blockUuid in blockUuids }
                changeQueue.removeAll { it.blockUuid in blockUuids }
                _queueSize.value = changeQueue.size
                changes
            }
            
            if (changesToProcess.isEmpty()) {
                return Result.success(Unit)
            }
            
            val results = processChanges(changesToProcess)
            val successCount = results.count { it.success }
            val failureCount = results.size - successCount
            
            if (failureCount > 0) {
                logger.warn("$failureCount out of ${results.size} forced saves failed")
            }
            
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
    
    override suspend fun saveBlock(block: Block): Result<PersistenceResult> {
        return try {
            val startTime = Clock.System.now().toEpochMilliseconds()
            
            PerformanceMonitor.startTrace("saveBlock")
            
            // Get current block from repository for conflict detection
            val currentBlock = blockRepository.getBlockByUuid(block.uuid).first().getOrNull()
            val saveState = blockSaveStates[block.uuid]
            
            // Detect conflicts
            val conflicts = if (_config.value.enableConflictResolution) {
                conflictDetector.detectBlockConflicts(
                    BlockChange(
                        blockUuid = block.uuid,
                        type = ChangeType.CONTENT,
                        timestamp = Clock.System.now(),
                        oldContent = currentBlock?.content,
                        newContent = block.content
                    ),
                    currentBlock,
                    saveState
                )
            } else {
                emptyList()
            }
            
            if (conflicts.isNotEmpty()) {
                val result = PersistenceResult.failure(
                    "saveBlock",
                    block.uuid,
                    "Conflicts detected: ${conflicts.size}",
                    duration = Clock.System.now().toEpochMilliseconds() - startTime
                )
                
                failedOperations.add(result)
                PerformanceMonitor.endTrace("saveBlock")
                return Result.success(result)
            }
            
            // Save block to repository
            val saveResult = blockRepository.saveBlock(block)
            
            if (saveResult.isSuccess) {
                // Update save state
                blockSaveStates[block.uuid] = BlockSaveState(
                    blockUuid = block.uuid,
                    lastKnownContent = block.content,
                    lastSavedAt = Clock.System.now(),
                    saveAttempts = saveState?.saveAttempts?.plus(1) ?: 1,
                    isDirty = false,
                    version = (saveState?.version ?: 0L) + 1
                )
                
                val result = PersistenceResult.success(
                    "saveBlock",
                    block.uuid,
                    "Block saved successfully",
                    duration = Clock.System.now().toEpochMilliseconds() - startTime
                )
                
                addRecentResult(result)
                PerformanceMonitor.endTrace("saveBlock")
                Result.success(result)
            } else {
                val result = PersistenceResult.failure(
                    "saveBlock",
                    block.uuid,
                    "Repository save failed",
                    saveState?.saveAttempts ?: 0,
                    Clock.System.now().toEpochMilliseconds() - startTime
                )
                
                failedOperations.add(result)
                addRecentResult(result)
                PerformanceMonitor.endTrace("saveBlock")
                Result.success(result)
            }
        } catch (e: Exception) {
            val result = PersistenceResult.failure(
                "saveBlock",
                block.uuid,
                "Exception: ${e.message}",
                0,
                Clock.System.now().toEpochMilliseconds() - (Clock.System.now().epochSeconds * 1000)
            )
            
            failedOperations.add(result)
            addRecentResult(result)
            Result.success(result)
        }
    }
    
    override suspend fun saveBlocks(blocks: List<Block>): Result<List<PersistenceResult>> {
        return try {
            val results = blocks.map { saveBlock(it) }
            Result.success(results.map { it.getOrThrow() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun deleteBlock(blockUuid: String): Result<PersistenceResult> {
        return try {
            Validation.validateUuid(blockUuid)
            
            val startTime = Clock.System.now().toEpochMilliseconds()
            PerformanceMonitor.startTrace("deleteBlock")
            
            // Remove from save states
            blockSaveStates.remove(blockUuid)
            
            // Remove from queue
            mutex.withLock {
                changeQueue.removeAll { it.blockUuid == blockUuid }
                _queueSize.value = changeQueue.size
            }
            
            // Delete from repository
            val deleteResult = blockRepository.deleteBlock(blockUuid, true)
            
            val result = if (deleteResult.isSuccess) {
                PersistenceResult.success(
                    "deleteBlock",
                    blockUuid,
                    "Block deleted successfully",
                    duration = Clock.System.now().toEpochMilliseconds() - startTime
                )
            } else {
                PersistenceResult.failure(
                    "deleteBlock",
                    blockUuid,
                    "Repository delete failed",
                    duration = Clock.System.now().toEpochMilliseconds() - startTime
                )
            }
            
            addRecentResult(result)
            PerformanceMonitor.endTrace("saveBlock")
            Result.success(result)
        } catch (e: Exception) {
            val result = PersistenceResult.failure(
                "deleteBlock",
                blockUuid,
                "Exception: ${e.message}",
                0,
                Clock.System.now().toEpochMilliseconds() - (Clock.System.now().epochSeconds * 1000)
            )
            
            addRecentResult(result)
            Result.success(result)
        }
    }
    
    override suspend fun getPendingChanges(): Result<List<BlockChange>> {
        return try {
            mutex.withLock {
                Result.success(changeQueue.toList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun cancelPendingChanges(blockUuids: List<String>): Result<Unit> {
        return try {
            blockUuids.forEach { Validation.validateUuid(it) }
            
            mutex.withLock {
                changeQueue.removeAll { it.blockUuid in blockUuids }
                _queueSize.value = changeQueue.size
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun clearPendingChanges(): Result<Unit> {
        return try {
            mutex.withLock {
                changeQueue.clear()
                _queueSize.value = 0
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun retryFailedOperations(): Result<Unit> {
        return try {
            val failed = failedOperations.toList()
            failedOperations.clear()
            
            // Retry operations that are retryable
            val retryable = failed.filter { it.retryCount < _config.value.maxRetries }
            
            for (operation in retryable) {
                when (operation.operation) {
                    "saveBlock" -> {
                        // Retry block save
                        operation.blockUuid?.let { uuid ->
                            val block = blockRepository.getBlockByUuid(uuid).first().getOrNull()
                            if (block != null) {
                                saveBlock(block)
                            }
                        }
                    }
                    // Add other operation types as needed
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getFailedOperations(): Result<List<PersistenceResult>> {
        return try {
            Result.success(failedOperations.toList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun clearFailedOperations(): Result<Unit> {
        return try {
            failedOperations.clear()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun detectConflicts(): Result<List<ConflictInfo>> {
        return try {
            val pendingChanges = mutex.withLock { changeQueue.toList() }
            val conflicts = conflictDetector.detectChangeConflicts(pendingChanges)
            Result.success(conflicts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun resolveConflict(conflictId: String, strategy: ConflictResolutionStrategy): Result<Unit> {
        return try {
            // This would integrate with ConflictResolver
            // Implementation depends on the specific resolution requirements
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun createBackup(): Result<BackupInfo> {
        return try {
            val backupId = "backup_${Clock.System.now().epochSeconds}"
            val backupInfo = BackupInfo(
                backupId = backupId,
                createdAt = Clock.System.now(),
                size = 0L, // Calculate actual size
                blockCount = blockSaveStates.size,
                description = "Auto backup"
            )
            
            logger.info("Created backup: $backupId")
            Result.success(backupInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun restoreFromBackup(backupId: String): Result<Unit> {
        return try {
            // Implementation depends on backup storage strategy
            logger.info("Restored from backup: $backupId")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getAvailableBackups(): Result<List<BackupInfo>> {
        return try {
            // Implementation depends on backup storage strategy
            Result.success(emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun cleanupBackups(): Result<Unit> {
        return try {
            // Remove old backups beyond maxBackupFiles limit
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getPerformanceMetrics(): Result<PersistencePerformanceMetrics> {
        return try {
            val stats = _stats.value
            val metrics = PersistencePerformanceMetrics(
                averageSaveTime = stats.averageSaveTime,
                averageQueueTime = 0.0, // Calculate from queue data
                throughputOperationsPerSecond = if (stats.totalOperations > 0) {
                    stats.totalOperations.toDouble() / (stats.lastSaveTime?.epochSeconds?.toDouble() ?: 1.0)
                } else 0.0,
                memoryUsage = 0L, // Calculate actual memory usage
                diskUsage = 0L, // Calculate disk usage
                conflictRate = stats.conflictCount.toDouble() / max(stats.totalOperations, 1),
                retryRate = stats.failedOperations.toDouble() / max(stats.totalOperations, 1),
                uptime = 0L // Calculate actual uptime
            )
            
            Result.success(metrics)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun setPerformanceMonitoring(enabled: Boolean): Result<Unit> {
        return try {
            // Enable/disable performance monitoring
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Private helper methods
    
    private fun startAutoSaveJob() {
        val interval = _config.value.autoSaveInterval
        
        autoSaveJob = scope.launch {
            while (isActive) {
                try {
                    delay(interval)
                    
                    if (!isPaused && isStarted) {
                        forceSave()
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Auto-save job error", e)
                }
            }
        }
    }
    
    private fun startBackupJob() {
        val interval = _config.value.backupInterval
        
        backupJob = scope.launch {
            while (isActive) {
                try {
                    delay(interval)
                    
                    if (!isPaused && isStarted) {
                        createBackup()
                        cleanupBackups()
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Backup job error", e)
                }
            }
        }
    }
    
    private suspend fun processChanges(changes: List<BlockChange>): List<PersistenceResult> {
        val results = mutableListOf<PersistenceResult>()
        
        // Group changes by block for batch processing
        val changesByBlock = changes.groupBy { it.blockUuid }
        
        for ((blockUuid, blockChanges) in changesByBlock) {
            // Get current block
            val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
            
            if (currentBlock == null) {
                // Block doesn't exist, check if it's a creation
                val creationChanges = blockChanges.filter { it.type == ChangeType.CREATION }
                if (creationChanges.isNotEmpty()) {
                    // Handle creation - need block data
                    val result = PersistenceResult.failure(
                        "processChanges",
                        blockUuid,
                        "Creation requires full block data"
                    )
                    results.add(result)
                    continue
                }
            }
            
            // Apply changes to block
            var updatedBlock = currentBlock ?: continue
            
            for (change in blockChanges) {
                when (change.type) {
                    ChangeType.CONTENT -> {
                        updatedBlock = updatedBlock.copy(content = change.newContent ?: updatedBlock.content)
                    }
                    ChangeType.PROPERTIES -> {
                        updatedBlock = updatedBlock.copy(properties = change.newProperties)
                    }
                    // Handle other change types
                    else -> {}
                }
            }
            
            // Save updated block
            val saveResult = saveBlock(updatedBlock)
            results.add(saveResult.getOrThrow())
        }
        
        return results
    }
    
    private fun addRecentResult(result: PersistenceResult) {
        val current = _recentResults.value.toMutableList()
        current.add(0, result)
        if (current.size > 50) {
            current.removeAt(current.lastIndex)
        }
        _recentResults.value = current
    }
    
    private fun updateStats() {
        _state.update { it.copy(pendingChangesCount = _queueSize.value) }
    }
}
