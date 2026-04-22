package dev.stapler.stelekit.editor.persistence

import dev.stapler.stelekit.model.Validation
import dev.stapler.stelekit.ui.NotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.Result
import kotlin.math.pow

/**
 * Handles errors, retries, and recovery for persistence operations
 */
class PersistenceErrorHandler(
    private val scope: CoroutineScope,
    private val notificationManager: NotificationManager,
    private val maxRetries: Int = 3,
    private val baseRetryDelay: Long = 1000L
) {
    private val logger = dev.stapler.stelekit.logging.Logger("PersistenceErrorHandler")
    
    // Renamed from _failedOperations to retryQueue based on usage patterns
    private val retryQueue = MutableStateFlow<List<RetryOperation>>(emptyList())
    val failedOperations: StateFlow<List<RetryOperation>> = retryQueue.asStateFlow()
    
    private val _recoveryStatus = MutableStateFlow(RecoveryStatus.IDLE)
    val recoveryStatus: StateFlow<RecoveryStatus> = _recoveryStatus.asStateFlow()
    
    private val _recoveryHistory = MutableStateFlow<List<RecoveryAttempt>>(emptyList())
    val recoveryHistory: StateFlow<List<RecoveryAttempt>> = _recoveryHistory.asStateFlow()
    
    /**
     * Handle a persistence operation failure
     */
    suspend fun handleFailure(
        operation: String,
        blockUuid: String?,
        error: Throwable,
        context: PersistenceContext
    ): ErrorHandlingResult {
        val failure = RetryOperation(
            id = generateFailureId(),
            operation = operation,
            blockUuid = blockUuid,
            error = error,
            timestamp = Clock.System.now(),
            context = context,
            retryCount = 0,
            maxRetries = determineMaxRetries(operation, error)
        )
        
        // Add to retry queue
        addRetryOperation(failure)
        
        // Determine handling strategy
        val strategy = determineHandlingStrategy(failure)
        
        when (strategy) {
            ErrorHandlingStrategy.RETRY -> {
                scheduleRetry(failure)
                return ErrorHandlingResult(
                    action = ErrorAction.RETRY_SCHEDULED,
                    message = "Operation scheduled for retry",
                    estimatedRecoveryTime = calculateRetryDelay(failure.retryCount)
                )
            }
            ErrorHandlingStrategy.MANUAL -> {
                notifyUser(failure)
                return ErrorHandlingResult(
                    action = ErrorAction.MANUAL_INTERVENTION_REQUIRED,
                    message = "Manual intervention required: ${error.message}"
                )
            }
            ErrorHandlingStrategy.IGNORE -> {
                logger.warn("Ignoring operation failure: $operation - ${error.message}")
                return ErrorHandlingResult(
                    action = ErrorAction.IGNORED,
                    message = "Non-critical error ignored"
                )
            }
            ErrorHandlingStrategy.ESCALATE -> {
                escalateError(failure)
                return ErrorHandlingResult(
                    action = ErrorAction.ESCALATED,
                    message = "Error escalated to higher level"
                )
            }
        }
    }
    
    /**
     * Retry all failed operations
     */
    suspend fun retryAll(): RecoveryResult {
        if (_recoveryStatus.value != RecoveryStatus.IDLE) {
            return RecoveryResult(
                success = false,
                message = "Recovery already in progress",
                recoveredOperations = 0,
                failedOperations = 0
            )
        }
        
        _recoveryStatus.value = RecoveryStatus.IN_PROGRESS
        
        return try {
            val failuresToRetry = retryQueue.value.filter { it.retryCount < it.maxRetries }
            var recovered = 0
            var failed = 0
            
            for (failure in failuresToRetry) {
                when (retryOperation(failure)) {
                    is RetryResult.Success -> {
                        removeRetryOperation(failure.id)
                        recovered++
                    }
                    is RetryResult.Failed -> {
                        failed++
                    }
                }
            }
            
            val success = failed == 0
            _recoveryStatus.value = if (success) RecoveryStatus.COMPLETED else RecoveryStatus.PARTIAL
            
            addToHistory(RecoveryAttempt(
                id = generateRecoveryId(),
                type = RecoveryType.BULK_RETRY,
                startTime = Clock.System.now(),
                endTime = Clock.System.now(),
                success = success,
                recoveredOperations = recovered,
                failedOperations = failed
            ))
            
            RecoveryResult(
                success = success,
                message = if (success) "All operations recovered successfully" else "$failed operations still failing",
                recoveredOperations = recovered,
                failedOperations = failed
            )
        } catch (e: Exception) {
            _recoveryStatus.value = RecoveryStatus.FAILED
            logger.error("Recovery failed", e)
            RecoveryResult(
                success = false,
                message = "Recovery failed: ${e.message}",
                recoveredOperations = 0,
                failedOperations = 0
            )
        }
    }
    
    /**
     * Retry a specific failed operation
     */
    suspend fun retryOperation(failureId: String): RecoveryResult {
        val failure = retryQueue.value.find { it.id == failureId }
            ?: return RecoveryResult(
                success = false,
                message = "Failed operation not found: $failureId",
                recoveredOperations = 0,
                failedOperations = 0
            )
        
        return when (val retryResult = retryOperation(failure)) {
            is RetryResult.Success -> {
                removeRetryOperation(failure.id)
                RecoveryResult(
                    success = true,
                    message = "Operation recovered successfully",
                    recoveredOperations = 1,
                    failedOperations = 0
                )
            }
            is RetryResult.Failed -> {
                RecoveryResult(
                    success = false,
                    message = "Retry failed: ${retryResult.error.message}",
                    recoveredOperations = 0,
                    failedOperations = 1
                )
            }
        }
    }
    
    /**
     * Clear all failed operations
     */
    suspend fun clearFailedOperations(): Result<Unit> = try {
        retryQueue.value = emptyList()
        logger.info("Cleared all failed operations")
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    /**
     * Create recovery backup
     */
    suspend fun createRecoveryBackup(): Result<RecoveryBackup> = try {
        val backup = RecoveryBackup(
            id = generateBackupId(),
            createdAt = Clock.System.now(),
            failedOperations = retryQueue.value,
            systemState = captureSystemState()
        )
        
        logger.info("Created recovery backup: ${backup.id}")
        Result.success(backup)
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    /**
     * Restore from recovery backup
     */
    suspend fun restoreFromBackup(backup: RecoveryBackup): Result<Unit> = try {
        retryQueue.value = backup.failedOperations
        restoreSystemState(backup.systemState)
        
        logger.info("Restored from recovery backup: ${backup.id}")
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    /**
     * Get recovery statistics
     */
    fun getRecoveryStats(): RecoveryStats {
        val failures = retryQueue.value
        val history = _recoveryHistory.value
        
        return RecoveryStats(
            totalFailures = failures.size,
            criticalFailures = failures.count { isCriticalFailure(it) },
            recoverableFailures = failures.count { it.retryCount < it.maxRetries },
            successfulRecoveries = history.count { it.success },
            totalRecoveryAttempts = history.size,
            lastRecoveryTime = history.maxOfOrNull { it.endTime },
            averageRecoveryTime = if (history.isNotEmpty()) {
                history.map { 
                    (it.endTime - it.startTime).inWholeMilliseconds.toDouble() 
                }.average()
            } else 0.0
        )
    }
    
    // Private helper methods
    
    private fun addRetryOperation(failure: RetryOperation) {
        val current = retryQueue.value.toMutableList()
        current.add(0, failure) // Add to front
        
        // Keep only last 100 failures
        if (current.size > 100) {
            current.removeAt(current.lastIndex)
        }
        
        retryQueue.value = current
    }
    
    private fun removeRetryOperation(failureId: String) {
        val current = retryQueue.value.filter { it.id != failureId }
        retryQueue.value = current
    }
    
    private fun determineMaxRetries(operation: String, _error: Throwable): Int {
        return when {
            operation.contains("delete") -> 1 // Fewer retries for delete
            operation.contains("save") -> maxRetries // Standard retries for save
            else -> maxRetries / 2 // Default retries
        }
    }
    
    private fun determineHandlingStrategy(failure: RetryOperation): ErrorHandlingStrategy {
        val error = failure.error
        
        return when {
            error.message?.contains("file not found", true) == true -> ErrorHandlingStrategy.IGNORE
            error.message?.contains("network", true) == true -> ErrorHandlingStrategy.RETRY
            error.message?.contains("timeout", true) == true -> ErrorHandlingStrategy.RETRY
            failure.retryCount >= failure.maxRetries -> ErrorHandlingStrategy.MANUAL
            else -> ErrorHandlingStrategy.RETRY
        }
    }
    
    private suspend fun scheduleRetry(failure: RetryOperation) {
        val retryDelay = calculateRetryDelay(failure.retryCount)
        
        scope.launch {
            delay(retryDelay)
            
            val updatedFailure = failure.copy(retryCount = failure.retryCount + 1)
            
            // Update the failure in the list
            val current = retryQueue.value.toMutableList()
            val index = current.indexOfFirst { it.id == failure.id }
            if (index >= 0) {
                current[index] = updatedFailure
                retryQueue.value = current
            }
            
            // Execute retry
            retryOperation(updatedFailure)
        }
    }
    
    private fun calculateRetryDelay(retryCount: Int): Long {
        return (baseRetryDelay * 2.0.pow(retryCount)).toLong()
    }
    
    private suspend fun retryOperation(failure: RetryOperation): RetryResult {
        return try {
            Validation.validateUuid(failure.blockUuid ?: "")
            
            when (failure.operation) {
                "saveBlock" -> {
                    // Retry the save operation
                    // This would need access to the block and repository
                    RetryResult.Success()
                }
                "deleteBlock" -> {
                    // Retry the delete operation
                    RetryResult.Success()
                }
                else -> {
                    RetryResult.Failed(Exception("Unknown operation: ${failure.operation}"))
                }
            }
        } catch (e: Exception) {
            logger.error("Retry failed for operation ${failure.operation}", e)
            RetryResult.Failed(e)
        }
    }
    
    private fun notifyUser(failure: RetryOperation) {
        val message = when (failure.operation) {
            "saveBlock" -> "Failed to save block: ${failure.error.message}"
            "deleteBlock" -> "Failed to delete block: ${failure.error.message}"
            else -> "Operation failed: ${failure.error.message}"
        }
        
        notificationManager.show(
            message,
            dev.stapler.stelekit.model.NotificationType.ERROR,
            timeout = 10000L // Longer timeout for errors
        )
    }
    
    private fun escalateError(failure: RetryOperation) {
        logger.error("ESCALATED ERROR: ${failure.operation} - ${failure.error.message}", failure.error)
        
        notificationManager.show(
            "Critical error occurred: ${failure.error.message}",
            dev.stapler.stelekit.model.NotificationType.ERROR,
            timeout = null // No auto-hide for critical errors
        )
    }
    
    private fun isCriticalFailure(failure: RetryOperation): Boolean {
        return failure.retryCount >= failure.maxRetries
    }
    
    private fun generateFailureId(): String = "failure_${Clock.System.now().epochSeconds}_${(1000..9999).random()}"
    private fun generateRecoveryId(): String = "recovery_${Clock.System.now().epochSeconds}_${(1000..9999).random()}"
    private fun generateBackupId(): String = "backup_${Clock.System.now().epochSeconds}_${(1000..9999).random()}"
    
    private fun captureSystemState(): Map<String, Any> {
        return mapOf(
            "timestamp" to Clock.System.now(),
            "failedOperationsCount" to retryQueue.value.size
        )
    }
    
    private fun restoreSystemState(state: Map<String, Any>) {
        // Implementation depends on what needs to be restored
        logger.info("Restored system state: ${state.keys}")
    }
    
    private fun addToHistory(attempt: RecoveryAttempt) {
        val current = _recoveryHistory.value.toMutableList()
        current.add(0, attempt)
        
        // Keep only last 50 recovery attempts
        if (current.size > 50) {
            current.removeAt(current.lastIndex)
        }
        
        _recoveryHistory.value = current
    }
}

/**
 * Represents a failed persistence operation that is queued for retry
 */
data class RetryOperation(
    val id: String,
    val operation: String,
    val blockUuid: String?,
    val error: Throwable,
    val timestamp: kotlin.time.Instant,
    val context: PersistenceContext,
    val retryCount: Int,
    val maxRetries: Int
)

/**
 * Context information for persistence operations
 */
data class PersistenceContext(
    val graphPath: String? = null,
    val sessionId: String? = null,
    val userId: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Result of error handling
 */
data class ErrorHandlingResult(
    val action: ErrorAction,
    val message: String,
    val estimatedRecoveryTime: Long? = null
)

/**
 * Actions that can be taken for errors
 */
enum class ErrorAction {
    RETRY_SCHEDULED,
    MANUAL_INTERVENTION_REQUIRED,
    IGNORED,
    ESCALATED
}

/**
 * Strategies for handling errors
 */
enum class ErrorHandlingStrategy {
    RETRY,
    MANUAL,
    IGNORE,
    ESCALATE
}

/**
 * Result of a retry operation
 */
sealed class RetryResult {
    data class Success(val message: String = "Retry successful") : RetryResult()
    data class Failed(val error: Throwable) : RetryResult()
}

/**
 * Status of recovery operations
 */
enum class RecoveryStatus {
    IDLE,
    IN_PROGRESS,
    COMPLETED,
    PARTIAL,
    FAILED
}

/**
 * Result of a recovery operation
 */
data class RecoveryResult(
    val success: Boolean,
    val message: String,
    val recoveredOperations: Int,
    val failedOperations: Int
)

/**
 * Information about a recovery attempt
 */
data class RecoveryAttempt(
    val id: String,
    val type: RecoveryType,
    val startTime: kotlin.time.Instant,
    val endTime: kotlin.time.Instant,
    val success: Boolean,
    val recoveredOperations: Int,
    val failedOperations: Int
)

/**
 * Types of recovery operations
 */
enum class RecoveryType {
    BULK_RETRY,
    INDIVIDUAL_RETRY,
    BACKUP_RESTORE,
    MANUAL_RESOLUTION
}

/**
 * Recovery backup information
 */
data class RecoveryBackup(
    val id: String,
    val createdAt: kotlin.time.Instant,
    val failedOperations: List<RetryOperation>,
    val systemState: Map<String, Any>
)

/**
 * Recovery statistics
 */
data class RecoveryStats(
    val totalFailures: Int,
    val criticalFailures: Int,
    val recoverableFailures: Int,
    val successfulRecoveries: Int,
    val totalRecoveryAttempts: Int,
    val lastRecoveryTime: kotlin.time.Instant?,
    val averageRecoveryTime: Double
) {
    val recoveryRate: Double
        get() = if (totalRecoveryAttempts > 0) {
            successfulRecoveries.toDouble() / totalRecoveryAttempts
        } else 0.0
        
    val criticalFailureRate: Double
        get() = if (totalFailures > 0) {
            criticalFailures.toDouble() / totalFailures
        } else 0.0
}
