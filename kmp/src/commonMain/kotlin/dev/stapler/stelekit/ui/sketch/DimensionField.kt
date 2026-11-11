package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import kotlin.math.abs

const val DIMENSION_FIELD_TAG = "sketch-dimension-field"

/** Text of the field a line starts with: its length when it is horizontal or vertical, else empty. */
fun lengthTextOf(sixteenths: Long): String =
    LengthFormatter.format(Inches(abs(sixteenths)), LengthFormat.FEET_INCHES)

/**
 * A length field for the selected line. Any grammar the wiki length parser accepts works; text
 * that is not one length shows an inline error with an example and nothing is committed.
 */
@Composable
fun DimensionField(
    initial: String,
    onCommit: (Inches) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Length",
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    Row(modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            supportingText = { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.testTag(DIMENSION_FIELD_TAG),
        )
        TextButton(onClick = {
            DimensionInput.parse(text).fold({ error = it.message }, { onCommit(it) })
        }) { Text("Set") }
    }
}
