package dev.stapler.stelekit.editor.persistence

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.Result

/**
 * Interface for managing persistence of block changes with auto-save capabilities.
 * This interface provides a high-level API for handling block persistence,
 * including auto-save, change detection, and error handling.
 */
interface IPersistenceManager {
    
    // ===== STATE MANAGEMENT =====
    
    /**
     * Current state of the persistence manager
     */
    val state: StateFlow<PersistenceState>
    
    /**
     * Statistics about persistence operations
     */
    val stats: StateFlow<PersistenceStats>
    
    /**
     * Results of recent persistence operations
     */
    val recentResults: StateFlow<List<PersistenceResult>>
    
    // ===== CONFIGURATION =====
    
    /**
     * Current configuration
     */
    val config: StateFlow<PersistenceConfig>
    
    /**
     * Update the persistence configuration
     */
    suspend fun updateConfig(newConfig: PersistenceConfig): Either<DomainError, Unit>
    
    // ===== LIFECYCLE MANAGEMENT =====
    
    /**
     * Start the persistence manager
     */
    suspend fun start(): Either<DomainError, Unit>
    
    /**
     * Stop the persistence manager and save any pending changes
     */
    suspend fun stop(force: Boolean = false): Either<DomainError, Unit>
    
    /**
     * Pause auto-save operations
     */
    suspend fun pause(): Either<DomainError, Unit>
    
    /**
     * Resume auto-save operations
     */
    suspend fun resume(): Either<DomainError, Unit>
    
    // ===== CHANGE TRACKING =====
    
    /**
     * Queue a block change for persistence
     */
    suspend fun queueChange(change: BlockChange): Either<DomainError, Unit>
    
    /**
     * Queue multiple block changes for batch persistence
     */
    suspend fun queueChanges(changes: List<BlockChange>): Either<DomainError, Unit>
    
    /**
     * Mark a block as dirty (needs saving)
     */
    suspend fun markDirty(blockUuid: String, currentContent: String): Either<DomainError, Unit>
    
    /**
     * Mark multiple blocks as dirty
     */
    suspend fun markDirty(blockUuids: List<String>, currentContent: Map<String, String>): Either<DomainError, Unit>
    
    /**
     * Check if a block has unsaved changes
     */
    suspend fun isDirty(blockUuid: String): Either<DomainError, Boolean>
    
    /**
     * Get the save state of a specific block
     */
    suspend fun getBlockSaveState(blockUuid: String): Either<DomainError, BlockSaveState?>
    
    // ===== PERSISTENCE OPERATIONS =====
    
    /**
     * Force save all pending changes immediately
     */
    suspend fun forceSave(): Either<DomainError, Unit>
    
    /**
     * Force save changes for specific blocks
     */
    suspend fun forceSaveBlocks(blockUuids: List<String>): Either<DomainError, Unit>
    
    /**
     * Save a single block immediately
     */
    suspend fun saveBlock(block: Block): Either<DomainError, PersistenceResult>
    
    /**
     * Save multiple blocks in a batch
     */
    suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, List<PersistenceResult>>
    
    /**
     * Delete a block and its state
     */
    suspend fun deleteBlock(blockUuid: String): Either<DomainError, PersistenceResult>
    
    // ===== QUEUE MANAGEMENT =====
    
    /**
     * Get the current size of the pending changes queue
     */
    val queueSize: StateFlow<Int>
    
    /**
     * Get list of pending changes
     */
    suspend fun getPendingChanges(): Either<DomainError, List<BlockChange>>
    
    /**
     * Cancel pending changes for specific blocks
     */
    suspend fun cancelPendingChanges(blockUuids: List<String>): Either<DomainError, Unit>
    
    /**
     * Clear all pending changes
     */
    suspend fun clearPendingChanges(): Either<DomainError, Unit>
    
    // ===== ERROR HANDLING & RECOVERY =====
    
    /**
     * Retry failed operations
     */
    suspend fun retryFailedOperations(): Either<DomainError, Unit>
    
    /**
     * Get failed operations that can be retried
     */
    suspend fun getFailedOperations(): Either<DomainError, List<PersistenceResult>>
    
    /**
     * Clear failed operations history
     */
    suspend fun clearFailedOperations(): Either<DomainError, Unit>
    
    // ===== CONFLICT MANAGEMENT =====
    
    /**
     * Detect conflicts between queued changes and saved state
     */
    suspend fun detectConflicts(): Either<DomainError, List<ConflictInfo>>
    
    /**
     * Resolve a conflict using the specified strategy
     */
    suspend fun resolveConflict(conflictId: String, strategy: ConflictResolutionStrategy): Either<DomainError, Unit>
    
    // ===== BACKUP & RECOVERY =====
    
    /**
     * Create a backup of current state
     */
    suspend fun createBackup(): Either<DomainError, BackupInfo>
    
    /**
     * Restore from a backup
     */
    suspend fun restoreFromBackup(backupId: String): Either<DomainError, Unit>
    
    /**
     * Get available backups
     */
    suspend fun getAvailableBackups(): Either<DomainError, List<BackupInfo>>
    
    /**
     * Clean up old backups
     */
    suspend fun cleanupBackups(): Either<DomainError, Unit>
    
    // ===== PERFORMANCE MONITORING =====
    
    /**
     * Get performance metrics for the persistence system
     */
    suspend fun getPerformanceMetrics(): Either<DomainError, PersistencePerformanceMetrics>
    
    /**
     * Enable or disable performance monitoring
     */
    suspend fun setPerformanceMonitoring(enabled: Boolean): Either<DomainError, Unit>
}

/**
 * Information about a detected conflict
 */
data class ConflictInfo(
    val conflictId: String,
    val blockUuid: String,
    val changeType: String,
    val conflictingChanges: List<BlockChange>,
    val detectedAt: kotlin.time.Instant,
    val severity: ConflictSeverity,
    val description: String
)

/**
 * Severity of a conflict
 */
enum class ConflictSeverity {
    LOW,      // Can be auto-resolved
    MEDIUM,   // Requires user attention
    HIGH,     // Data loss possible
    CRITICAL  // Immediate attention required
}



/**
 * Information about a backup
 */
data class BackupInfo(
    val backupId: String,
    val createdAt: kotlin.time.Instant,
    val size: Long,
    val blockCount: Int,
    val description: String,
    val isAutoBackup: Boolean = false
)

/**
 * Performance metrics for the persistence system
 */
data class PersistencePerformanceMetrics(
    val averageSaveTime: Double,
    val averageQueueTime: Double,
    val throughputOperationsPerSecond: Double,
    val memoryUsage: Long,
    val diskUsage: Long,
    val conflictRate: Double,
    val retryRate: Double,
    val uptime: Long
)
