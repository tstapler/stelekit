package dev.stapler.stelekit.editor.performance

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.editor.*
import dev.stapler.stelekit.editor.state.EditorState
import dev.stapler.stelekit.editor.blocks.IBlockOperations
import dev.stapler.stelekit.editor.text.ITextOperations
import dev.stapler.stelekit.editor.text.TextRange
import dev.stapler.stelekit.editor.components.RichTextEditor
import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Performance-optimized editor composable with virtual scrolling and efficient recomposition
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PerformanceOptimizedEditor(
    editorState: StateFlow<EditorState>,
    textOperations: ITextOperations,
    blockOperations: IBlockOperations,
    onBlockFocus: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val editorStateValue by editorState.collectAsState()
    val scope = rememberCoroutineScope()
    
    // Performance: Debounced state update
    var lastContentUpdate by remember { mutableStateOf(0L) }
    val debouncedUpdateDelay = remember { 16L } // ~60fps for responsive typing
    
    // Performance: Virtual scrolling for large documents
    val listState = rememberLazyListState()
    
    // Performance: First visible item index for optimization
    val firstVisibleIndex by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex
        }
    }
    
    // Performance: Cleanup job for debouncing
    val debounceJob = remember { mutableStateOf<Job?>(null) }
    
    // Performance: Immediate UI updates with debounced persistence
    LaunchedEffect(editorStateValue.blocks) {
        debounceJob.value?.cancel()
        debounceJob.value = scope.launch {
            delay(16L) // ~60fps for responsive typing
            // Update only if not within debounce window
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (now - lastContentUpdate > 16L) {
                lastContentUpdate = now
            }
        }
    }
    
    // Performance: Use LazyColumn for virtual scrolling
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Performance: Use itemsIndexed for efficient key-based recomposition
        itemsIndexed(
            items = editorStateValue.blocks,
            key = { _, block: Block -> block.uuid }
        ) { index, block ->
            // Performance: Only render visible blocks
            if (index >= firstVisibleIndex - 5 && index <= firstVisibleIndex + 20) {
                PerformanceOptimizedBlockEditor(
                    block = block,
                    textOperations = textOperations,
                    blockOperations = blockOperations,
                    onFocus = { 
                        onBlockFocus(block.uuid)
                        // Performance: Track focus for optimization
                        scope.launch {
                            // Preload adjacent blocks for better performance
                            preloadAdjacentBlocks(index, editorStateValue.blocks, textOperations)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            } else {
                // Performance: Render placeholder for off-screen blocks
                BlockEditorPlaceholder(
                    level = block.level,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                )
            }
        }
    }
}

/**
 * Performance-optimized block editor with minimal recomposition
 */
@Composable
private fun PerformanceOptimizedBlockEditor(
    block: Block,
    textOperations: ITextOperations,
    blockOperations: IBlockOperations,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    
    // Performance: Memoize text state to avoid unnecessary recompositions
    val textStateFlow = remember(block.uuid) {
        textOperations.getTextState(block.uuid)
    }
    val textState by textStateFlow.collectAsState()
    
    // Performance: Debounced change handler
    var lastChangeTime by remember { mutableStateOf(0L) }
    val changeDebounceMs = remember { 200L }
    
    // Always render the editor - content sync handled by RichTextEditor internally
    RichTextEditor(
        block = block,
        textOperations = textOperations,
        modifier = modifier,
        onFocusChange = { focused ->
            if (focused) onFocus()
        },
        onContentChange = { newContent ->
            // Performance: Debounce rapid changes
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (now - lastChangeTime > changeDebounceMs) {
                lastChangeTime = now
                scope.launch {
                    // Performance: Batch update to reduce repository calls
                    textOperations.replaceText(
                        block.uuid,
                        TextRange(0, textState.content.length),
                        newContent
                    )
                }
            }
        }
    )
}

/**
 * Lightweight placeholder for off-screen blocks
 */
@Composable
private fun BlockEditorPlaceholder(
    level: Int,
    modifier: Modifier = Modifier
) {
    val indentation = (level * 16).dp
    Spacer(
        modifier = modifier
            .padding(start = indentation)
            .fillMaxWidth()
            .height(32.dp)
    )
}

/**
 * Performance monitoring and optimization utilities
 */
class EditorPerformanceManager {
    
    private val performanceMetrics = mutableMapOf<String, MutableList<Long>>()
    private val renderTimes = mutableMapOf<String, Long>()
    
    /**
     * Track performance of editor operations
     */
    fun trackOperation(operation: String, duration: Long) {
        val metrics = performanceMetrics.getOrPut(operation) { mutableListOf() }
        metrics.add(duration)
        
        // Performance: Keep only last 100 measurements
        if (metrics.size > 100) {
            metrics.removeAt(0)
        }
    }
    
    /**
     * Get average performance for operation
     */
    fun getAverageTime(operation: String): Long {
        val metrics = performanceMetrics[operation] ?: return 0L
        return if (metrics.isEmpty()) 0L else metrics.average().toLong()
    }
    
    /**
     * Check if performance is acceptable
     */
    fun isPerformanceAcceptable(operation: String): Boolean {
        val average = getAverageTime(operation)
        return when (operation) {
            "text-insert", "text-delete", "text-replace" -> average < 50 // 50ms
            "block-create", "block-delete", "block-move" -> average < 100 // 100ms
            "render-block" -> average < 16 // 60fps = 16ms
            else -> true
        }
    }
    
    /**
     * Get performance recommendations
     */
    fun getPerformanceRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        
        performanceMetrics.forEach { (operation, metrics) ->
            if (metrics.isNotEmpty()) {
                val average = metrics.average()
                val max = metrics.maxOrNull() ?: 0L
                
                when {
                    operation.startsWith("text") && average > 50 -> {
                        recommendations.add("Consider optimizing string operations for $operation")
                    }
                    operation.startsWith("block") && average > 100 -> {
                        recommendations.add("Consider batching operations for $operation")
                    }
                    max > average * 5 -> {
                        recommendations.add("Investigate performance spikes in $operation")
                    }
                }
            }
        }
        
        return recommendations
    }
    
    companion object {
        // Performance thresholds (in milliseconds)
        const val TEXT_OPERATION_THRESHOLD = 50L
        const val BLOCK_OPERATION_THRESHOLD = 100L
        const val RENDER_THRESHOLD = 16L // 60fps
        
        // Memory thresholds
        const val MAX_BLOCKS_IN_MEMORY = 1000
        const val MAX_TEXT_STATES = 500
    }
}

/**
 * Coroutine manager for editor operations
 */
class EditorCoroutineManager {
    private val activeOperations = mutableSetOf<String>()
    private val operationJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    
    /**
     * Execute operation with cancellation management
     */
    suspend fun <T> executeOperation(
        operationId: String,
        block: suspend () -> T
    ): T {
        // Cancel previous operation with same ID
        operationJobs[operationId]?.cancel()
        
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            activeOperations.add(operationId)
        }
        operationJobs[operationId] = job
        
        return try {
            block()
        } finally {
            activeOperations.remove(operationId)
            operationJobs.remove(operationId)
        }
    }
    
    /**
     * Check if operation is active
     */
    fun isOperationActive(operationId: String): Boolean {
        return operationId in activeOperations
    }
    
    /**
     * Cancel all operations
     */
    fun cancelAllOperations() {
        operationJobs.values.forEach { it.cancel() }
        activeOperations.clear()
        operationJobs.clear()
    }
}

/**
 * Performance-optimized scope helper
 */
@Composable
fun rememberEditorScope(): kotlinx.coroutines.CoroutineScope {
    return rememberCoroutineScope()
}

/**
 * Preload adjacent blocks for better scrolling performance
 */
private suspend fun preloadAdjacentBlocks(
    currentIndex: Int,
    blocks: List<Block>,
    textOperations: ITextOperations
) {
    val preloadRange = maxOf(0, currentIndex - 2)..minOf(blocks.size - 1, currentIndex + 10)
    
    preloadRange.forEach { index ->
        val block = blocks.getOrNull(index)
        if (block != null) {
            // Preload text states for better scrolling performance
            textOperations.getTextState(block.uuid)
        }
    }
}
