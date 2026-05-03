package dev.stapler.stelekit.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.ui.theme.StelekitTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import dev.stapler.stelekit.editor.text.ITextOperations
import dev.stapler.stelekit.editor.format.IFormatProcessor
import dev.stapler.stelekit.editor.text.TextRange as EditorTextRange

/**
 * Main rich text editor composable for Logseq KMP
 */
@Composable
fun RichTextEditor(
    block: Block,
    textOperations: ITextOperations,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    onContentChange: (String) -> Unit = {},
    placeholder: String = "",
    enabled: Boolean = true
) {
    val scope = rememberCoroutineScope()
    
    // Get reactive text state for this block
    val textState by textOperations.getTextState(block.uuid).collectAsState()
    
    // Local state for TextField
    var textFieldValue by remember(block.uuid) {
        mutableStateOf(TextFieldValue(text = textState.content))
    }
    
    // Track previous content to detect changes
    var previousContent by remember(block.uuid) { mutableStateOf(textState.content) }
    
    // Update TextField when external state changes
    LaunchedEffect(textState.content) {
        if (textState.content != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = textState.content)
        }
    }
    
    // Handle text changes with debouncing
    LaunchedEffect(textFieldValue.text) {
        if (textFieldValue.text != previousContent) {
            previousContent = textFieldValue.text
            
            scope.launch {
                // Debounced content change
                kotlinx.coroutines.delay(300)
                if (textFieldValue.text == previousContent) {
                    textOperations.replaceText(
                        block.uuid,
                        EditorTextRange(0, textState.content.length),
                        textFieldValue.text
                    )
                    onContentChange(textFieldValue.text)
                }
            }
        }
    }
    
    // Handle selection changes
    LaunchedEffect(textFieldValue.selection) {
        // Normalize selection - Compose TextRange can have start > end when dragging backwards
        val selStart = minOf(textFieldValue.selection.start, textFieldValue.selection.end)
        val selEnd = maxOf(textFieldValue.selection.start, textFieldValue.selection.end)
        textOperations.setSelection(
            block.uuid,
            EditorTextRange(selStart, selEnd)
        )
    }
    
    // Editor styling with Material Design 3
    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onBackground
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Default
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                // Handle Enter key for block operations
                scope.launch {
                    textOperations.insertText(block.uuid, "\n")
                }
            }
        ),
        singleLine = false,
        maxLines = Int.MAX_VALUE,
        enabled = enabled,
        decorationBox = { innerTextField ->
            if (textFieldValue.text.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            innerTextField()
        }
    )
}


/**
 * Enhanced rich editor with formatting capabilities
 */
@Composable
fun RichFormattedEditor(
    block: Block,
    textOperations: ITextOperations,
    formatProcessor: IFormatProcessor,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    onContentChange: (String) -> Unit = {},
    placeholder: String = ""
) {
    val scope = rememberCoroutineScope()
    val textState by textOperations.getTextState(block.uuid).collectAsState()
    
    val currentFormat by remember { derivedStateOf { TextFormat() } }
    
    Column(modifier = modifier) {
        // Formatting toolbar
        RichFormattingToolbar(
            currentFormat = currentFormat,
            onFormatToggle = { format ->
                scope.launch {
                    textOperations.applyFormat(
                        block.uuid,
                        EditorTextRange(textState.selection.range.start, textState.selection.range.end),
                        format
                    )
                }
            }
        )
        
        // Main text editor
        RichTextEditor(
            block = block,
            textOperations = textOperations,
            modifier = Modifier.weight(1f),
            onFocusChange = onFocusChange,
            onContentChange = onContentChange,
            placeholder = placeholder
        )
    }
}

/**
 * Formatting toolbar for rich text operations
 */
@Composable
private fun RichFormattingToolbar(
    currentFormat: TextFormat,
    onFormatToggle: (TextFormat) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Bold button
        ToolbarButton(
            text = "B",
            isActive = currentFormat.bold,
            onClick = { onFormatToggle(currentFormat.copy(bold = !currentFormat.bold)) }
        )
        
        // Italic button
        ToolbarButton(
            text = "I",
            isActive = currentFormat.italic,
            onClick = { onFormatToggle(currentFormat.copy(italic = !currentFormat.italic)) }
        )
        
        // Code button
        ToolbarButton(
            text = "</>",
            isActive = currentFormat.code,
            onClick = { onFormatToggle(currentFormat.copy(code = !currentFormat.code)) }
        )
        
        // Quote button
        ToolbarButton(
            text = "\"",
            isActive = currentFormat.quote,
            onClick = { onFormatToggle(currentFormat.copy(quote = !currentFormat.quote)) }
        )
        
        // Link button
        ToolbarButton(
            text = "🔗",
            isActive = currentFormat.link != null,
            onClick = { 
                if (currentFormat.link != null) {
                    onFormatToggle(currentFormat.copy(link = null))
                } else {
                    // Show link dialog (TODO: implement)
                }
            }
        )
    }
}

/**
 * Simple toolbar button component
 */
@Composable
private fun ToolbarButton(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (isActive) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        modifier = Modifier.size(32.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
