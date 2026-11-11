package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchSheetKind
import dev.stapler.stelekit.sketch.ViewDirection
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter

/** Sheet type, view direction and scale (the grid step) for the open sheet. */
@Composable
fun SheetPropertiesPanel(
    props: SheetProps,
    onKind: (SketchSheetKind) -> Unit,
    onDirection: (ViewDirection) -> Unit,
    onScale: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipRow("Sheet") {
            SketchSheetKind.entries.forEach { kind ->
                FilterChip(selected = props.kind == kind, onClick = { onKind(kind) }, label = { Text(kind.label()) })
            }
        }
        ChipRow("View") {
            props.kind.directions.forEach { dir ->
                FilterChip(selected = props.viewDirection == dir, onClick = { onDirection(dir) }, label = { Text(dir.label()) })
            }
        }
        Text("Grid step")
        DimensionField(
            initial = LengthFormatter.format(props.gridStep, LengthFormat.INCHES_MIXED),
            label = "Grid step",
            onCommit = { step -> sanitizedScale(step)?.let(onScale) },
        )
    }
}

/** A grid step must stay within the sheet's scale limits; out-of-range values are ignored. */
fun sanitizedScale(step: Inches): Long? = step.sixteenths.takeIf { it in 1..SheetProps.MAX_SCALE_SIXTEENTHS }

@Composable
private fun ChipRow(title: String, content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
        content()
    }
}

fun SketchSheetKind.label(): String = when (this) {
    SketchSheetKind.PLAN -> "Plan"
    SketchSheetKind.ELEVATION -> "Elevation"
    SketchSheetKind.SECTION -> "Section"
}

fun ViewDirection.label(): String = when (this) {
    ViewDirection.TOP -> "From above"
    ViewDirection.FROM_FRONT -> "From front"
    ViewDirection.FROM_BACK -> "From back"
}
