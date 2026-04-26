package dev.stapler.stelekit.editor.persistence

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.Result

/**
 * Interface for handling real-time data synchronization between
 * local storage and remote sources (if applicable).
 * Provides conflict detection, resolution strategies, and sync state management.
 */
interface IDataSynchronizer {
    
    // ===== SYNC STATE MANAGEMENT =====
    
    /**
     * Current synchronization state
     */
    val syncState: StateFlow<SyncState>
    
    /**
     * Recent synchronization results
     */
    val syncHistory: StateFlow<List<SyncResult>>
    
    /**
     * Active synchronization progress
     */
    val syncProgress: StateFlow<SyncProgress>
    
    // ===== CONFIGURATION =====
    
    /**
     * Current synchronization configuration
     */
    val config: StateFlow<SyncConfig>
    
    /**
     * Update synchronization configuration
     */
    suspend fun updateConfig(newConfig: SyncConfig): Either<DomainError, Unit>
    
    // ===== SYNC OPERATIONS =====
    
    /**
     * Start synchronization process
     */
    suspend fun startSync(): Either<DomainError, SyncSession>
    
    /**
     * Stop active synchronization
     */
    suspend fun stopSync(sessionId: String): Either<DomainError, Unit>
    
    /**
     * Force immediate synchronization
     */
    suspend fun forceSync(): Either<DomainError, SyncResult>
    
    /**
     * Synchronize specific blocks
     */
    suspend fun syncBlocks(blockUuids: List<String>): Either<DomainError, List<SyncResult>>
    
    /**
     * Synchronize all changes since last sync
     */
    suspend fun syncIncremental(): Either<DomainError, SyncResult>
    
    /**
     * Perform full synchronization (reconcile all data)
     */
    suspend fun syncFull(): Either<DomainError, SyncResult>
    
    // ===== CONFLICT DETECTION & RESOLUTION =====
    
    /**
     * Detect conflicts between local and remote data
     */
    suspend fun detectConflicts(): Either<DomainError, List<SyncConflict>>
    
    /**
     * Get conflicts by severity level
     */
    suspend fun getConflictsBySeverity(severity: ConflictSeverity): Either<DomainError, List<SyncConflict>>
    
    /**
     * Resolve a specific conflict
     */
    suspend fun resolveConflict(conflictId: String, resolution: ConflictResolution): Either<DomainError, Unit>
    
    /**
     * Resolve multiple conflicts with the same strategy
     */
    suspend fun resolveConflicts(conflictIds: List<String>, resolution: ConflictResolution): Either<DomainError, Unit>
    
    /**
     * Auto-resolve conflicts using configured strategies
     */
    suspend fun autoResolveConflicts(): Either<DomainError, List<ConflictResolutionResult>>
    
    // ===== SYNC SESSION MANAGEMENT =====
    
    /**
     * Get active sync sessions
     */
    suspend fun getActiveSessions(): Either<DomainError, List<SyncSession>>
    
    /**
     * Get sync session details
     */
    suspend fun getSession(sessionId: String): Either<DomainError, SyncSession?>
    
    /**
     * Cancel a sync session
     */
    suspend fun cancelSession(sessionId: String): Either<DomainError, Unit>
    
    /**
     * Retry failed sync operations
     */
    suspend fun retrySync(sessionId: String): Either<DomainError, SyncResult>
    
    // ===== REMOTE DATA MANAGEMENT =====
    
    /**
     * Get remote data status
     */
    suspend fun getRemoteStatus(): Either<DomainError, RemoteStatus>
    
    /**
     * Push local changes to remote
     */
    suspend fun pushChanges(): Either<DomainError, PushResult>
    
    /**
     * Pull changes from remote
     */
    suspend fun pullChanges(): Either<DomainError, PullResult>
    
    /**
     * Check if remote is available and accessible
     */
    suspend fun checkRemoteConnectivity(): Either<DomainError, Boolean>
    
    // ===== CHANGE TRACKING =====
    
    /**
     * Track local changes for synchronization
     */
    suspend fun trackLocalChange(change: TrackedChange): Either<DomainError, Unit>
    
    /**
     * Get untracked local changes
     */
    suspend fun getUntrackedChanges(): Either<DomainError, List<TrackedChange>>
    
    /**
     * Mark changes as synchronized
     */
    suspend fun markChangesSynced(changeIds: List<String>): Either<DomainError, Unit>
    
    /**
     * Get synchronization history for a block
     */
    suspend fun getBlockSyncHistory(blockUuid: String): Either<DomainError, List<SyncResult>>
    
    // ===== PERFORMANCE MONITORING =====
    
    /**
     * Get synchronization performance metrics
     */
    suspend fun getPerformanceMetrics(): Either<DomainError, SyncPerformanceMetrics>
    
    /**
     * Enable or disable performance monitoring
     */
    suspend fun setPerformanceMonitoring(enabled: Boolean): Either<DomainError, Unit>
    
    // ===== ERROR HANDLING =====
    
    /**
     * Get failed sync operations
     */
    suspend fun getFailedSyncs(): Either<DomainError, List<SyncResult>>
    
    /**
     * Clear sync history
     */
    suspend fun clearSyncHistory(): Either<DomainError, Unit>
    
    /**
     * Reset synchronization state (use with caution)
     */
    suspend fun resetSyncState(): Either<DomainError, Unit>
}

/**
 * Current state of synchronization
 */
enum class SyncState {
    IDLE,           // No active synchronization
    CONNECTING,     // Establishing connection to remote
    SYNCING,        // Active synchronization in progress
    CONFLICTING,    // Conflicts detected, waiting for resolution
    ERROR,          // Synchronization error occurred
    DISCONNECTED,   // Remote is not accessible
    PAUSED          // Synchronization is paused
}

/**
 * Configuration for synchronization
 */
data class SyncConfig(
    val enabled: Boolean = false,
    val autoSync: Boolean = true,
    val syncInterval: Long = 60000L, // 1 minute
    val maxRetries: Int = 3,
    val retryDelay: Long = 5000L,
    val conflictResolutionStrategy: ConflictResolutionStrategy = ConflictResolutionStrategy.MANUAL,
    val enablePush: Boolean = true,
    val enablePull: Boolean = true,
    val batchSize: Int = 50,
    val timeout: Long = 30000L,
    val enableCompression: Boolean = true,
    val enableEncryption: Boolean = false
) {
    init {
        require(syncInterval > 0) { "Sync interval must be positive" }
        require(maxRetries >= 0) { "Max retries must be non-negative" }
        require(retryDelay > 0) { "Retry delay must be positive" }
        require(batchSize > 0) { "Batch size must be positive" }
        require(timeout > 0) { "Timeout must be positive" }
    }
}

/**
 * Result of a synchronization operation
 */
data class SyncResult(
    val sessionId: String,
    val operationType: SyncOperationType,
    val success: Boolean,
    val startTime: kotlin.time.Instant,
    val endTime: kotlin.time.Instant,
    val processedBlocks: Int = 0,
    val conflicts: List<SyncConflict> = emptyList(),
    val errors: List<String> = emptyList(),
    val bytesTransferred: Long = 0L,
    val metadata: Map<String, String> = emptyMap()
) {
    val duration: kotlin.time.Duration
        get() = endTime - startTime
        
    val hasConflicts: Boolean
        get() = conflicts.isNotEmpty()
        
    val hasErrors: Boolean
        get() = errors.isNotEmpty()
}

/**
 * Types of synchronization operations
 */
enum class SyncOperationType {
    FULL_SYNC,       // Complete synchronization
    INCREMENTAL,     // Incremental sync of changes
    PUSH,            // Push local changes to remote
    PULL,            // Pull remote changes locally
    CONFLICT_RESOLVE // Conflict resolution operation
}

/**
 * Progress of an ongoing synchronization
 */
data class SyncProgress(
    val sessionId: String,
    val operationType: SyncOperationType,
    val totalItems: Int,
    val processedItems: Int,
    val currentPhase: String,
    val estimatedTimeRemaining: kotlin.time.Duration? = null,
    val bytesTransferred: Long = 0L,
    val errorsEncountered: Int = 0
) {
    val percentageComplete: Float
        get() = if (totalItems > 0) (processedItems.toFloat() / totalItems) * 100f else 0f
        
    val isComplete: Boolean
        get() = processedItems >= totalItems
}

/**
 * Information about a synchronization conflict
 */
data class SyncConflict(
    val conflictId: String,
    val blockUuid: String,
    val conflictType: ConflictType,
    val localVersion: BlockVersion,
    val remoteVersion: BlockVersion,
    val detectedAt: kotlin.time.Instant,
    val severity: ConflictSeverity,
    val description: String,
    val suggestions: List<ConflictResolution> = emptyList()
)

/**
 * Types of conflicts
 */
enum class ConflictType {
    CONTENT_CONFLICT,     // Same block modified in both locations
    STRUCTURE_CONFLICT,   // Block hierarchy conflicts
    DELETION_CONFLICT,    // Block deleted in one location, modified in another
    DUPLICATE_CONFLICT,   // Same block created in both locations
    METADATA_CONFLICT     // Block properties/conflicts
}

/**
 * Version information for a block
 */
data class BlockVersion(
    val blockUuid: String,
    val content: String,
    val properties: Map<String, String>,
    val version: Long,
    val lastModified: kotlin.time.Instant,
    val modifiedBy: String? = null,
    val checksum: String? = null
)


/**
 * Specific resolution for a conflict
 */
data class ConflictResolution(
    val strategy: ConflictResolutionStrategy,
    val blockUuid: String,
    val resolvedContent: String? = null,
    val resolvedProperties: Map<String, String> = emptyMap(),
    val notes: String? = null
)

/**
 * Result of conflict resolution
 */
data class ConflictResolutionResult(
    val conflictId: String,
    val strategy: ConflictResolutionStrategy,
    val success: Boolean,
    val resolvedAt: kotlin.time.Instant,
    val notes: String? = null
)

/**
 * Active synchronization session
 */
data class SyncSession(
    val sessionId: String,
    val operationType: SyncOperationType,
    val startTime: kotlin.time.Instant,
    val status: SyncState,
    val progress: SyncProgress? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Status of remote storage
 */
data class RemoteStatus(
    val isAccessible: Boolean,
    val lastSyncTime: kotlin.time.Instant? = null,
    val totalBlocks: Long = 0L,
    val totalSize: Long = 0L,
    val serverVersion: String? = null,
    val features: List<String> = emptyList(),
    val connectionLatency: kotlin.time.Duration? = null
)

/**
 * Result of pushing changes to remote
 */
data class PushResult(
    val success: Boolean,
    val pushedBlocks: Int,
    val conflicts: List<SyncConflict>,
    val errors: List<String>,
    val bytesTransferred: Long,
    val duration: kotlin.time.Duration
)

/**
 * Result of pulling changes from remote
 */
data class PullResult(
    val success: Boolean,
    val pulledBlocks: Int,
    val conflicts: List<SyncConflict>,
    val errors: List<String>,
    val bytesTransferred: Long,
    val duration: kotlin.time.Duration
)

/**
 * Tracked change for synchronization
 */
data class TrackedChange(
    val changeId: String,
    val blockUuid: String,
    val changeType: ChangeType,
    val timestamp: kotlin.time.Instant,
    val data: Map<String, String>,
    val synced: Boolean = false
)

/**
 * Performance metrics for synchronization
 */
data class SyncPerformanceMetrics(
    val averageSyncTime: Double,
    val averageThroughput: Double,
    val conflictRate: Double,
    val successRate: Double,
    val connectionLatency: Double,
    val dataTransferRate: Double,
    val uptime: Long,
    val totalOperations: Long
)
