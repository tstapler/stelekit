package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchLayer
import dev.stapler.stelekit.sketch.Zone
import dev.stapler.stelekit.sketch.ZoneReport
import dev.stapler.stelekit.sketch.ZoneSet

/** Layers: show, hide and lock; plus assigning the selected shape to a layer. */
@Composable
fun LayerPanel(
    props: SheetProps,
    hasSelection: Boolean,
    onAdd: (String) -> Unit,
    onToggleVisible: (String, Boolean) -> Unit,
    onToggleLock: (String, Boolean) -> Unit,
    onAssignSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 8.dp)) {
        Text("Layers")
        props.layers.forEach { layer ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(layer.name)
                Checkbox(checked = layer.visible, onCheckedChange = { onToggleVisible(layer.name, it) })
                Text("Show")
                Checkbox(checked = layer.locked, onCheckedChange = { onToggleLock(layer.name, it) })
                Text("Lock")
                if (hasSelection) TextButton(onClick = { onAssignSelection(layer.name) }) { Text("Move selection here") }
            }
        }
        if (hasSelection) TextButton(onClick = { onAssignSelection("") }) { Text("Move selection to default layer") }
        NameAdder(label = "New layer", valid = SketchLayer::isValidName, onAdd = onAdd)
    }
}

/** Named zones with a typed length each, and the choice of which one the others must sum to. */
@Composable
fun ZonePanel(
    zones: ZoneSet,
    report: ZoneReport?,
    onAdd: (String) -> Unit,
    onText: (String, String?) -> Unit,
    onTotal: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 8.dp)) {
        Text("Zones")
        zones.zones.forEach { zone -> ZoneRow(zone, report, onText, onTotal, onRemove) }
        NameAdder(label = "New zone", valid = { name -> name.isNotBlank() && zones.zones.none { it.key == name } }, onAdd = onAdd)
    }
}

@Composable
private fun ZoneRow(zone: Zone, report: ZoneReport?, onText: (String, String?) -> Unit, onTotal: (String, Boolean) -> Unit, onRemove: (String) -> Unit) {
    var text by remember(zone.key, zone.text) { mutableStateOf(zone.text.orEmpty()) }
    val bad = report?.textErrors?.contains(zone.key) == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(zone.key) },
            singleLine = true,
            isError = bad,
            supportingText = if (bad) ({ Text("Not a length. Try 12 3/16\"") }) else null,
        )
        TextButton(onClick = { onText(zone.key, text.ifBlank { null }) }) { Text("Set") }
        Checkbox(checked = zone.isTotal, onCheckedChange = { onTotal(zone.key, it) })
        Text("Total")
        TextButton(onClick = { onRemove(zone.key) }) { Text("Remove") }
    }
}

@Composable
private fun NameAdder(label: String, valid: (String) -> Boolean, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) }, singleLine = true)
        TextButton(enabled = valid(name.trim()), onClick = { onAdd(name.trim()); name = "" }) { Text("Add") }
    }
}
