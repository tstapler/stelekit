package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.ui.sketch.v2.DimensionHint
import dev.stapler.stelekit.ui.sketch.v2.LengthUnitChip
import dev.stapler.stelekit.ui.sketch.v2.LengthUnitTags
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import kotlin.math.abs

const val DIMENSION_FIELD_TAG = "sketch-dimension-field"
const val DIMENSION_FIELD_HINT_TAG = "sketch-dimension-field-hint"
private val DIMENSION_FIELD_WIDTH = 168.dp

/** Text of the field a line starts with: its length when it is horizontal or vertical, else empty. */
fun lengthTextOf(sixteenths: Long, format: LengthFormat = LengthFormat.FEET_INCHES): String =
    LengthFormatter.format(Inches(abs(sixteenths)), format)

/**
 * A length field for the selected line. Any grammar the wiki length parser accepts works; text
 * that is not one length shows an inline error with an example and nothing is committed. While
 * typing, the hint under the field reads the text back ("= 3' 7 3/4" (43 3/4")") in [format]; a
 * length between two sixteenths says it will be rounded. [onFormatCycle], when given, adds the unit chip.
 */
@Composable
fun DimensionField(
    initial: String,
    onCommit: (Inches) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Length",
    format: LengthFormat = LengthFormat.FEET_INCHES,
    onFormatCycle: (() -> Unit)? = null,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    val reading = if (text == initial) null else DimensionHint.reading(text, format)
    Row(modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            supportingText = {
                val message = error ?: reading?.let { it.roundedNotice ?: it.text }
                message?.let {
                    Text(it, Modifier.testTag(DIMENSION_FIELD_HINT_TAG).semantics { liveRegion = LiveRegionMode.Polite }, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.width(DIMENSION_FIELD_WIDTH).testTag(DIMENSION_FIELD_TAG),
        )
        onFormatCycle?.let { LengthUnitChip(format, it, LengthUnitTags.FIELD_CHIP) }
        TextButton(onClick = {
            DimensionInput.interpret(text).fold({ error = it.message }, { onCommit(it.inches) })
        }) { Text("Set") }
    }
}
