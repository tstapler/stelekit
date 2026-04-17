package dev.stapler.stelekit.editor.persistence

import dev.stapler.stelekit.model.Block
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Represents the current state of the persistence system
 */
@Serializable
data class PersistenceState(
    val isEnabled: Boolean = true,
    val autoSaveInterval: Long = 3000L, // 3 seconds
    val maxQueueSize: Int = 100,
    val maxRetries: Int = 3,
    val isAutoSaveActive: Boolean = false,
    val lastSaveTime: Instant? = null,
    val pendingChangesCount: Int = 0,
    val failedOperationsCount: Int = 0,
    val totalSavesCount: Long = 0L
) {
    init {
        require(autoSaveInterval > 0) { "Auto-save interval must be positive" }
        require(maxQueueSize > 0) { "Max queue size must be positive" }
        require(maxRetries >= 0) { "Max retries must be non-negative" }
    }
}

/**
 * Represents the save state of an individual block
 */
@Serializable
data class BlockSaveState(
    val blockUuid: String,
    val lastKnownContent: String,
    val lastSavedAt: Instant? = null,
    val saveAttempts: Int = 0,
    val lastError: String? = null,
    val isDirty: Boolean = false,
    val version: Long = 0L
) {
    init {
        require(blockUuid.isNotBlank()) { "Block UUID cannot be blank" }
        require(saveAttempts >= 0) { "Save attempts must be non-negative" }
        require(version >= 0) { "Version must be non-negative" }
    }
}

/**
 * Represents a change to a block that needs to be persisted
 */
@Serializable
data class BlockChange(
    val blockUuid: String,
    val type: ChangeType,
    val timestamp: Instant,
    val oldContent: String? = null,
    val newContent: String? = null,
    val oldProperties: Map<String, String> = emptyMap(),
    val newProperties: Map<String, String> = emptyMap(),
    val metadata: ChangeMetadata = ChangeMetadata()
) {
    init {
        require(blockUuid.isNotBlank()) { "Block UUID cannot be blank" }
    }

    /**
     * Check if this change is equivalent to another change (for deduplication)
     */
    fun isEquivalentTo(other: BlockChange): Boolean {
        return blockUuid == other.blockUuid &&
                type == other.type &&
                oldContent == other.oldContent &&
                newContent == other.newContent &&
                oldProperties == other.oldProperties &&
                newProperties == other.newProperties
    }

    /**
     * Check if this change conflicts with another change
     */
    fun conflictsWith(other: BlockChange): Boolean {
        if (blockUuid != other.blockUuid) return false
        
        return when (type) {
            ChangeType.CONTENT -> {
                other.type == ChangeType.CONTENT && 
                oldContent != other.newContent &&
                newContent != other.oldContent
            }
            ChangeType.PROPERTIES -> {
                other.type == ChangeType.PROPERTIES &&
                (oldProperties != other.newProperties ||
                 newProperties != other.oldProperties)
            }
            ChangeType.DELETION -> other.type != ChangeType.DELETION
            ChangeType.CREATION -> false // Creation doesn't conflict
            ChangeType.MOVE -> other.type == ChangeType.MOVE && 
                              metadata.moveTarget != other.metadata.moveTarget
        }
    }
}

/**
 * Types of changes that can occur to a block
 */
@Serializable
enum class ChangeType {
    CREATION,    // Block was created
    CONTENT,     // Block content was modified
    PROPERTIES,  // Block properties were modified
    MOVE,        // Block was moved/renumbered
    DELETION     // Block was deleted
}

/**
 * Metadata associated with a change
 */
@Serializable
data class ChangeMetadata(
    val source: String = "editor", // Source of the change (editor, sync, import, etc.)
    val userId: String? = null,     // User who made the change (if applicable)
    val sessionId: String? = null,  // Session identifier
    val moveTarget: String? = null, // Target parent UUID for move operations
    val version: Long = 0L,         // Change version for conflict resolution
    val isAutoSave: Boolean = false // Whether this was an auto-save operation
) {
    init {
        require(version >= 0) { "Version must be non-negative" }
    }
}

/**
 * Configuration for the persistence manager
 */
@Serializable
data class PersistenceConfig(
    val autoSaveEnabled: Boolean = true,
    val autoSaveInterval: Long = 3000L,
    val maxQueueSize: Int = 100,
    val maxRetries: Int = 3,
    val retryDelay: Long = 1000L,
    val enableChangeDetection: Boolean = true,
    val enableConflictResolution: Boolean = true,
    val backupEnabled: Boolean = true,
    val backupInterval: Long = 300000L, // 5 minutes
    val maxBackupFiles: Int = 10,
    val enablePerformanceMonitoring: Boolean = true,
    val enableDetailedLogging: Boolean = false
) {
    init {
        require(autoSaveInterval > 0) { "Auto-save interval must be positive" }
        require(maxQueueSize > 0) { "Max queue size must be positive" }
        require(maxRetries >= 0) { "Max retries must be non-negative" }
        require(retryDelay > 0) { "Retry delay must be positive" }
        require(backupInterval > 0) { "Backup interval must be positive" }
        require(maxBackupFiles > 0) { "Max backup files must be positive" }
    }

    companion object {
        val DEFAULT = PersistenceConfig()
        
        val FAST = PersistenceConfig(
            autoSaveInterval = 1000L,
            maxQueueSize = 50,
            maxRetries = 1,
            retryDelay = 500L
        )
        
        val SLOW = PersistenceConfig(
            autoSaveInterval = 10000L,
            maxQueueSize = 200,
            maxRetries = 5,
            retryDelay = 2000L
        )
        
        val TESTING = PersistenceConfig(
            autoSaveEnabled = false,
            enableChangeDetection = false,
            enableConflictResolution = false,
            backupEnabled = false,
            enablePerformanceMonitoring = false,
            enableDetailedLogging = true
        )
    }
}

/**
 * Result of a persistence operation
 */
@Serializable
data class PersistenceResult(
    val success: Boolean,
    val operation: String,
    val blockUuid: String? = null,
    val message: String? = null,
    val timestamp: Instant,
    val retryCount: Int = 0,
    val duration: Long = 0L // Duration in milliseconds
) {
    init {
        require(retryCount >= 0) { "Retry count must be non-negative" }
        require(duration >= 0) { "Duration must be non-negative" }
    }
    
    companion object {
        fun success(operation: String, blockUuid: String? = null, message: String? = null, duration: Long = 0L) =
            PersistenceResult(
                success = true,
                operation = operation,
                blockUuid = blockUuid,
                message = message,
                timestamp = kotlin.time.Clock.System.now(),
                duration = duration
            )
            
        fun failure(operation: String, blockUuid: String? = null, message: String? = null, retryCount: Int = 0, duration: Long = 0L) =
            PersistenceResult(
                success = false,
                operation = operation,
                blockUuid = blockUuid,
                message = message,
                timestamp = kotlin.time.Clock.System.now(),
                retryCount = retryCount,
                duration = duration
            )
    }
}

/**
 * Statistics about persistence operations
 */
@Serializable
data class PersistenceStats(
    val totalOperations: Long = 0L,
    val successfulOperations: Long = 0L,
    val failedOperations: Long = 0L,
    val averageSaveTime: Double = 0.0,
    val lastSaveTime: Instant? = null,
    val queueSize: Int = 0,
    val conflictCount: Int = 0,
    val backupCount: Long = 0L
) {
    val successRate: Double
        get() = if (totalOperations > 0) successfulOperations.toDouble() / totalOperations else 0.0
        
    val failureRate: Double
        get() = if (totalOperations > 0) failedOperations.toDouble() / totalOperations else 0.0
}

/**
 * Strategies for resolving conflicts
 */
@Serializable
enum class ConflictResolutionStrategy {
    USE_LOCAL,      // Keep local changes
    USE_REMOTE,     // Use remote/saved changes
    MERGE,          // Attempt to merge changes
    MANUAL          // Require manual resolution
}
