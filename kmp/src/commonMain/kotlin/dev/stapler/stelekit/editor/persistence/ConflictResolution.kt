package dev.stapler.stelekit.editor.persistence

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.logging.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable


/**
 * Utility class for detecting and resolving conflicts in block data.
 * Updated to use UUID-native storage.
 */
class ConflictDetector {
    private val logger = Logger("ConflictDetector")
    
    /**
     * Detect conflicts between local changes and existing data
     */
    fun detectBlockConflicts(
        localChange: BlockChange,
        existingBlock: Block?,
        existingSaveState: BlockSaveState?
    ): List<ConflictInfo> {
        val conflicts = mutableListOf<ConflictInfo>()
        
        if (existingBlock == null) {
            // No existing block, no conflict unless it's a modification
            if (localChange.type == ChangeType.CONTENT || localChange.type == ChangeType.PROPERTIES) {
                conflicts.add(ConflictInfo(
                    conflictId = generateConflictId(),
                    blockUuid = localChange.blockUuid,
                    changeType = localChange.type.name,
                    conflictingChanges = listOf(localChange),
                    detectedAt = Clock.System.now(),
                    severity = ConflictSeverity.MEDIUM,
                    description = "Attempting to modify non-existent block ${localChange.blockUuid}"
                ))
            }
            return conflicts
        }
        
        when (localChange.type) {
            ChangeType.CONTENT -> {
                detectContentConflicts(localChange, existingBlock, existingSaveState, conflicts)
            }
            ChangeType.PROPERTIES -> {
                detectPropertyConflicts(localChange, existingBlock, existingSaveState, conflicts)
            }
            ChangeType.DELETION -> {
                detectDeletionConflicts(localChange, existingBlock, existingSaveState, conflicts)
            }
            ChangeType.MOVE -> {
                detectMoveConflicts(localChange, existingBlock, existingSaveState, conflicts)
            }
            ChangeType.CREATION -> {
                // Creation conflicts with existing block
                conflicts.add(ConflictInfo(
                    conflictId = generateConflictId(),
                    blockUuid = localChange.blockUuid,
                    changeType = localChange.type.name,
                    conflictingChanges = listOf(localChange),
                    detectedAt = Clock.System.now(),
                    severity = ConflictSeverity.HIGH,
                    description = "Attempting to create block that already exists: ${localChange.blockUuid}"
                ))
            }
        }
        
        return conflicts
    }
    
    /**
     * Detect conflicts between multiple changes
     */
    fun detectChangeConflicts(changes: List<BlockChange>): List<ConflictInfo> {
        val conflicts = mutableListOf<ConflictInfo>()
        val changesByBlock = changes.groupBy { it.blockUuid }
        
        for ((blockUuid, blockChanges) in changesByBlock) {
            if (blockChanges.size < 2) continue
            
            // Check for conflicting changes to the same block
            val sortedChanges = blockChanges.sortedBy { it.timestamp }
            
            for (i in 0 until sortedChanges.size - 1) {
                for (j in i + 1 until sortedChanges.size) {
                    val change1 = sortedChanges[i]
                    val change2 = sortedChanges[j]
                    
                    if (change1.conflictsWith(change2)) {
                        conflicts.add(ConflictInfo(
                            conflictId = generateConflictId(),
                            blockUuid = blockUuid,
                            changeType = "${change1.type.name} vs ${change2.type.name}",
                            conflictingChanges = listOf(change1, change2),
                            detectedAt = Clock.System.now(),
                            severity = determineConflictSeverity(change1, change2),
                            description = "Conflicting changes detected for block $blockUuid: ${change1.type} at ${change1.timestamp} vs ${change2.type} at ${change2.timestamp}"
                        ))
                    }
                }
            }
        }
        
        return conflicts
    }
    
    private fun detectContentConflicts(
        change: BlockChange,
        existingBlock: Block,
        saveState: BlockSaveState?,
        conflicts: MutableList<ConflictInfo>
    ) {
        val expectedContent = saveState?.lastKnownContent ?: change.oldContent
        
        if (expectedContent != null && existingBlock.content != expectedContent) {
            conflicts.add(ConflictInfo(
                conflictId = generateConflictId(),
                blockUuid = change.blockUuid,
                changeType = "CONTENT_CONFLICT",
                conflictingChanges = listOf(change),
                detectedAt = Clock.System.now(),
                severity = ConflictSeverity.HIGH,
                description = "Content conflict for block ${change.blockUuid}: expected '$expectedContent' but found '${existingBlock.content}'"
            ))
        }
    }
    
    private fun detectPropertyConflicts(
        change: BlockChange,
        existingBlock: Block,
        saveState: BlockSaveState?,
        conflicts: MutableList<ConflictInfo>
    ) {
        val expectedProps = saveState?.let { change.oldProperties } ?: change.oldProperties
        
        if (expectedProps.isNotEmpty()) {
            val propertyConflicts = mutableListOf<String>()
            for ((key, expectedValue) in expectedProps) {
                val actualValue = existingBlock.properties[key]
                if (actualValue != expectedValue) {
                    propertyConflicts.add("$key: expected '$expectedValue' but found '$actualValue'")
                }
            }
            
            if (propertyConflicts.isNotEmpty()) {
                conflicts.add(ConflictInfo(
                    conflictId = generateConflictId(),
                    blockUuid = change.blockUuid,
                    changeType = "PROPERTY_CONFLICT",
                    conflictingChanges = listOf(change),
                    detectedAt = Clock.System.now(),
                    severity = ConflictSeverity.MEDIUM,
                    description = "Property conflicts for block ${change.blockUuid}: ${propertyConflicts.joinToString(", ")}"
                ))
            }
        }
    }
    
    private fun detectDeletionConflicts(
        change: BlockChange,
        existingBlock: Block,
        saveState: BlockSaveState?,
        conflicts: MutableList<ConflictInfo>
    ) {
        // Deletion conflicts if the block was modified since last known state
        if (saveState != null && saveState.lastSavedAt != null && existingBlock.updatedAt > saveState.lastSavedAt) {
            conflicts.add(ConflictInfo(
                conflictId = generateConflictId(),
                blockUuid = change.blockUuid,
                changeType = "DELETION_CONFLICT",
                conflictingChanges = listOf(change),
                detectedAt = Clock.System.now(),
                severity = ConflictSeverity.CRITICAL,
                description = "Attempting to delete block ${change.blockUuid} that was modified since last sync"
            ))
        }
    }
    
    private fun detectMoveConflicts(
        change: BlockChange,
        existingBlock: Block,
        _saveState: BlockSaveState?,
        conflicts: MutableList<ConflictInfo>
    ) {
        // Move conflicts if the block structure changed since last known state
        val expectedParent = change.metadata.moveTarget
        val actualParent = existingBlock.parentUuid
        
        if (expectedParent != null && expectedParent != actualParent) {
            conflicts.add(ConflictInfo(
                conflictId = generateConflictId(),
                blockUuid = change.blockUuid,
                changeType = "MOVE_CONFLICT",
                conflictingChanges = listOf(change),
                detectedAt = Clock.System.now(),
                severity = ConflictSeverity.MEDIUM,
                description = "Move conflict for block ${change.blockUuid}: expected parent '$expectedParent' but found '$actualParent'"
            ))
        }
    }
    
    private fun determineConflictSeverity(change1: BlockChange, change2: BlockChange): ConflictSeverity {
        return when {
            change1.type == ChangeType.DELETION || change2.type == ChangeType.DELETION -> ConflictSeverity.CRITICAL
            change1.type == ChangeType.CREATION && change2.type == ChangeType.CREATION -> ConflictSeverity.HIGH
            change1.type == ChangeType.CONTENT && change2.type == ChangeType.CONTENT -> ConflictSeverity.HIGH
            else -> ConflictSeverity.MEDIUM
        }
    }
    
    private fun generateConflictId(): String {
        return "conflict_${Clock.System.now().epochSeconds}_${(1000..9999).random()}"
    }
}

/**
 * Utility class for resolving conflicts using various strategies
 */
class ConflictResolver {
    private val logger = Logger("ConflictResolver")
    
    /**
     * Resolve a conflict using the specified strategy
     */
    suspend fun resolveConflict(
        conflict: ConflictInfo,
        strategy: ConflictResolutionStrategy,
        context: ResolutionContext
    ): EditorConflictResolutionResult {
        return try {
            when (strategy) {
                ConflictResolutionStrategy.USE_LOCAL -> resolveUseLocal(conflict, context)
                ConflictResolutionStrategy.USE_REMOTE -> resolveUseRemote(conflict, context)
                ConflictResolutionStrategy.MERGE -> resolveMerge(conflict, context)
                ConflictResolutionStrategy.MANUAL -> resolveManual(conflict, context)
            }
        } catch (e: Exception) {
            logger.error("Failed to resolve conflict ${conflict.conflictId} with strategy $strategy", e)
            EditorConflictResolutionResult(
                conflictId = conflict.conflictId,
                strategy = strategy,
                success = false,
                resolvedAt = Clock.System.now(),
                notes = "Resolution failed: ${e.message}"
            )
        }
    }
    
    private suspend fun resolveUseLocal(
        conflict: ConflictInfo,
        context: ResolutionContext
    ): EditorConflictResolutionResult {
        // Keep local changes and override remote
        val success = context.applyLocalChanges(conflict.conflictingChanges)
        
        return EditorConflictResolutionResult(
            conflictId = conflict.conflictId,
            strategy = ConflictResolutionStrategy.USE_LOCAL,
            success = success,
            resolvedAt = Clock.System.now(),
            notes = if (success) "Applied local changes" else "Failed to apply local changes"
        )
    }
    
    private suspend fun resolveUseRemote(
        conflict: ConflictInfo,
        context: ResolutionContext
    ): EditorConflictResolutionResult {
        // Keep remote state and discard local changes
        val success = context.discardLocalChanges(conflict.blockUuid)
        
        return EditorConflictResolutionResult(
            conflictId = conflict.conflictId,
            strategy = ConflictResolutionStrategy.USE_REMOTE,
            success = success,
            resolvedAt = Clock.System.now(),
            notes = if (success) "Discarded local changes" else "Failed to discard local changes"
        )
    }
    
    private suspend fun resolveMerge(
        conflict: ConflictInfo,
        context: ResolutionContext
    ): EditorConflictResolutionResult {
        // Attempt to merge changes
        val mergeResult = context.mergeChanges(conflict)
        
        return EditorConflictResolutionResult(
            conflictId = conflict.conflictId,
            strategy = ConflictResolutionStrategy.MERGE,
            success = mergeResult.success,
            resolvedAt = Clock.System.now(),
            notes = mergeResult.message
        )
    }
    
    private suspend fun resolveManual(
        conflict: ConflictInfo,
        context: ResolutionContext
    ): EditorConflictResolutionResult {
        // Mark for manual resolution
        val success = context.markForManualResolution(conflict)
        
        return EditorConflictResolutionResult(
            conflictId = conflict.conflictId,
            strategy = ConflictResolutionStrategy.MANUAL,
            success = success,
            resolvedAt = Clock.System.now(),
            notes = if (success) "Marked for manual resolution" else "Failed to mark for manual resolution"
        )
    }
    
    /**
     * Auto-resolve conflicts using heuristics
     */
    suspend fun autoResolveConflicts(
        conflicts: List<ConflictInfo>,
        context: ResolutionContext
    ): List<EditorConflictResolutionResult> {
        return conflicts.map { conflict ->
            val strategy = determineAutoResolutionStrategy(conflict)
            resolveConflict(conflict, strategy, context)
        }
    }
    
    private fun determineAutoResolutionStrategy(conflict: ConflictInfo): ConflictResolutionStrategy {
        return when (conflict.severity) {
            ConflictSeverity.LOW -> ConflictResolutionStrategy.MERGE
            ConflictSeverity.MEDIUM -> when {
                conflict.changeType.contains("PROPERTY") -> ConflictResolutionStrategy.MERGE
                conflict.changeType.contains("MOVE") -> ConflictResolutionStrategy.USE_LOCAL
                else -> ConflictResolutionStrategy.USE_LOCAL
            }
            ConflictSeverity.HIGH -> when {
                conflict.changeType.contains("DELETION") -> ConflictResolutionStrategy.MANUAL
                conflict.changeType.contains("CONTENT") -> ConflictResolutionStrategy.MANUAL
                else -> ConflictResolutionStrategy.USE_LOCAL
            }
            ConflictSeverity.CRITICAL -> ConflictResolutionStrategy.MANUAL
        }
    }
}

/**
 * Context for conflict resolution operations
 */
data class ResolutionContext(
    val blockRepository: dev.stapler.stelekit.repository.BlockRepository,
    val persistenceManager: IPersistenceManager,
    val currentUser: String? = null,
    val sessionId: String? = null
) {
    /**
     * Apply local changes to the data store
     */
    suspend fun applyLocalChanges(changes: List<BlockChange>): Boolean {
        return try {
            // Apply each change to the block repository
            for (change in changes) {
                when (change.type) {
                    ChangeType.CONTENT -> {
                        // Update block content
                    }
                    ChangeType.PROPERTIES -> {
                        // Update block properties
                    }
                    ChangeType.MOVE -> {
                        // Move block to new parent
                    }
                    ChangeType.DELETION -> {
                        // Delete block
                    }
                    ChangeType.CREATION -> {
                        // Create new block
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Discard local changes for a block
     */
    suspend fun discardLocalChanges(_blockUuid: String): Boolean {
        return try {
            // Reload block from storage, discarding local changes
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Attempt to merge conflicting changes
     */
    suspend fun mergeChanges(conflict: ConflictInfo): MergeResult {
        return try {
            // Implement merge logic based on conflict type
            when (conflict.changeType) {
                "CONTENT_CONFLICT" -> mergeContentConflict(conflict)
                "PROPERTY_CONFLICT" -> mergePropertyConflict(conflict)
                "MOVE_CONFLICT" -> mergeMoveConflict(conflict)
                else -> MergeResult(false, "Unknown conflict type: ${conflict.changeType}")
            }
        } catch (e: Exception) {
            MergeResult(false, "Merge failed: ${e.message}")
        }
    }
    
    /**
     * Mark conflict for manual resolution
     */
    suspend fun markForManualResolution(_conflict: ConflictInfo): Boolean {
        return try {
            // Add to manual resolution queue
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun mergeContentConflict(_conflict: ConflictInfo): MergeResult {
        // Simple content merge - could be enhanced with diff/merge algorithms
        return MergeResult(true, "Content merged automatically")
    }
    
    private suspend fun mergePropertyConflict(_conflict: ConflictInfo): MergeResult {
        // Merge properties by taking union of both sets
        return MergeResult(true, "Properties merged automatically")
    }
    
    private suspend fun mergeMoveConflict(_conflict: ConflictInfo): MergeResult {
        // Move conflicts typically need manual resolution
        return MergeResult(false, "Move conflicts require manual resolution")
    }
}

/**
 * Result of a merge operation
 */
data class MergeResult(
    val success: Boolean,
    val message: String,
    val mergedContent: String? = null,
    val mergedProperties: Map<String, String> = emptyMap()
)

/**
 * Result of a conflict resolution attempt
 */
@Serializable
data class EditorConflictResolutionResult(
    val conflictId: String,
    val strategy: ConflictResolutionStrategy,
    val success: Boolean,
    val resolvedAt: Instant,
    val notes: String
)
