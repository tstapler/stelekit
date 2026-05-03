package dev.stapler.stelekit.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextRange as ComposeTextRange

import dev.stapler.stelekit.editor.state.EditorState
import dev.stapler.stelekit.editor.text.ITextOperations
import dev.stapler.stelekit.editor.state.EditorConfig
import dev.stapler.stelekit.editor.text.TextRange
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.performance.PerformanceMonitor
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Rich text field component that extends BasicTextField with Logseq-specific features.
 * Provides comprehensive text editing capabilities with IME support, keyboard handling, and performance monitoring.
 */
@Composable
fun RichTextEditor(
    blockId: String,
    editorState: EditorState,
    textOperations: ITextOperations,
    editorConfig: EditorConfig,
    onEditorStateChange: (EditorState) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = { },
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = { },
    onTriggerDetected: (String, androidx.compose.ui.geometry.Rect?) -> Unit = { _, _ -> }
) {
    val logger = remember { Logger("RichTextEditor") }
    val scope = rememberCoroutineScope()
    
    // Performance monitoring
    val performanceTraceId: String = remember { PerformanceMonitor.startTrace("rich-text-editor") }
    
    // Focus management
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    
    // Local text field state
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = textOperations.getTextState(blockId).value.content,
                selection = ComposeTextRange(0, 0)
            )
        )
    }
    
    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    // Note: In a real implementation, we would sync textFieldValue with the specific block's text state.
    // For now, assuming single block editing or passed state. 
    // Since this is a component, it should probably receive the specific text/selection, not the whole EditorState?
    // But keeping signature as requested.
    
    // Handle text changes with incremental updates
    fun onValueChange(newValue: TextFieldValue) {
        val oldValue = textFieldValue
        textFieldValue = newValue
        
        // Trigger Detection
        try {
            val cursor = newValue.selection.min
            val textBeforeCursor = newValue.text.take(cursor)
            val match = Regex("\\[\\[([^\\]]*)\$").find(textBeforeCursor)
            
            if (match != null) {
                val query = match.groupValues[1]
                val safeCursor = cursor.coerceIn(0, textLayoutResult?.layoutInput?.text?.length ?: 0)
                val rect = if (textLayoutResult != null && safeCursor <= textLayoutResult!!.layoutInput.text.length) {
                    try {
                        textLayoutResult?.getCursorRect(safeCursor)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } else null
                
                onTriggerDetected(query, rect)
            } else {
                onTriggerDetected("", null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error in trigger detection", e)
        }
        
        scope.launch {
            try {
                // Calculate actual diff for efficient updates
                val commonPrefix = oldValue.text.commonPrefixWith(newValue.text).length
                val commonSuffix = oldValue.text.commonSuffixWith(newValue.text).length
                
                if (commonPrefix + commonSuffix < oldValue.text.length || commonPrefix + commonSuffix < newValue.text.length) {
                    // Only replace the changed portion
                    val replaceStart = commonPrefix
                    val replaceEnd = oldValue.text.length - commonSuffix
                    val newText = newValue.text.substring(commonPrefix, newValue.text.length - commonSuffix)
                    
                    textOperations.replaceText(blockId, TextRange(replaceStart, replaceEnd), newText)
                }
                
                // Update selection efficiently 
                if (newValue.selection != oldValue.selection) {
                    textOperations.setSelection(blockId, TextRange(newValue.selection.start, newValue.selection.end))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error handling text change", e)
            }
        }
    }
    
    // Enhanced text style with configuration
    val enhancedTextStyle = textStyle.merge(
        TextStyle(
            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = (14 * 1.5f).sp,
            letterSpacing = 0.sp
        )
    )
    
    // Enhanced modifier with configuration
    val enhancedModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 48.dp)
        .clip(MaterialTheme.shapes.small)
        .border(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            shape = MaterialTheme.shapes.small
        )
        .background(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small
        )
        .padding(
            horizontal = 16.dp,
            vertical = 12.dp
        )
        .focusRequester(focusRequester)
        .onFocusChanged { focusState ->
            isFocused = focusState.isFocused
        }
        .then(
            Modifier.onGloballyPositioned { _ ->
                PerformanceMonitor.mark("rich-text-field-positioned")
            }
        )
    
    Box(
        modifier = enhancedModifier,
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { onValueChange(it) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = enhancedTextStyle,
            cursorBrush = cursorBrush,
            enabled = enabled && !readOnly,
            readOnly = readOnly,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions.copy(
                imeAction = ImeAction.Default,
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            onTextLayout = { result ->
                textLayoutResult = result
                onTextLayout(result)
            }
        )
        
        // Placeholder
        if (textFieldValue.text.isEmpty() && !isFocused) {
            placeholder()
        }
    }
    
    // Cleanup performance trace
    DisposableEffect(Unit) {
        onDispose {
            PerformanceMonitor.endTrace(performanceTraceId)
        }
    }
}

/**
 * Rich text field optimized for block content in Logseq.
 */
@Composable
fun RichTextBlockField(
    blockId: String,
    editorState: EditorState,
    textOperations: ITextOperations,
    editorConfig: EditorConfig,
    onEditorStateChange: (EditorState) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "New block...",
    level: Int = 0,
    onDelete: () -> Unit = { },
    onTriggerDetected: (String, androidx.compose.ui.geometry.Rect?) -> Unit = { _, _ -> }
) {
    val scope = rememberCoroutineScope()
    
    RichTextEditor(
        blockId = blockId,
        editorState = editorState,
        textOperations = textOperations,
        editorConfig = editorConfig,
        onEditorStateChange = onEditorStateChange,
        onTriggerDetected = onTriggerDetected,
        modifier = modifier.padding(
            start = (level * 24).dp,
            top = 4.dp,
            bottom = 4.dp,
            end = 16.dp
        ),
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onNext = {
                scope.launch {
                    // Handle Enter key logic
                }
            }
        )
    )
}
