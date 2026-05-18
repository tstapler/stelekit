package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Floating text-entry overlay for the [AnnotationTool.LABEL] tool.
 *
 * The user taps to place an anchor point; this overlay appears at screen center so they
 * can type the label text. Confirm submits the LABEL annotation; dismiss cancels it.
 */
@Composable
fun LabelInputOverlay(
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF01A1A1A))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Add label",
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Label text", color = Color.Gray) },
            singleLine = true,
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFFBBBBBB))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onConfirm, enabled = text.isNotBlank()) {
                Text("Add")
            }
        }
    }
}
