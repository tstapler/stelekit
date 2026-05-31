// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface BleDevicePanelState {
    data object Scanning : BleDevicePanelState
    data object NoDevicesFound : BleDevicePanelState
    data class DevicesFound(val devices: List<ExternalMeasurementDevice>) : BleDevicePanelState
    data class Connecting(val deviceName: String) : BleDevicePanelState
    data class Connected(val device: ExternalMeasurementDevice) : BleDevicePanelState
    data class AwaitingReading(val device: ExternalMeasurementDevice) : BleDevicePanelState
    data class ReadingReceived(val device: ExternalMeasurementDevice, val valueMeters: Double) : BleDevicePanelState
    data class PermissionDenied(val isPermanent: Boolean) : BleDevicePanelState
    data object BluetoothOff : BleDevicePanelState
}

private fun formatMeters(valueMeters: Double): String {
    return if (valueMeters >= 1.0) {
        "${"%.3f".format(valueMeters)} m"
    } else {
        "${(valueMeters * 1000).toInt()} mm"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleDevicePanel(
    onDismiss: () -> Unit,
    onUseDrawMethod: () -> Unit,
    /** Called with the measured distance in meters when user confirms "Use this measurement". */
    onReadingAccepted: (Double) -> Unit,
) {
    var panelState by remember { mutableStateOf<BleDevicePanelState>(BleDevicePanelState.Scanning) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        delay(300)
        panelState = BleDevicePanelState.Scanning

        val collectedDevices = mutableListOf<ExternalMeasurementDevice>()
        val result = withTimeoutOrNull(10_000L) {
            MeasurementDeviceRegistry.getAllDevices().collect { device ->
                collectedDevices.add(device)
                panelState = BleDevicePanelState.DevicesFound(collectedDevices.toList())
            }
        }

        if (result == null && collectedDevices.isEmpty()) {
            panelState = BleDevicePanelState.NoDevicesFound
        }
    }

    LaunchedEffect(panelState) {
        val state = panelState
        if (state is BleDevicePanelState.Connected) {
            panelState = BleDevicePanelState.AwaitingReading(state.device)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val state = panelState) {
                is BleDevicePanelState.Scanning -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = "Scanning for devices…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                    TextButton(onClick = onUseDrawMethod) {
                        Text("Use draw method instead")
                    }
                }

                is BleDevicePanelState.NoDevicesFound -> {
                    Icon(
                        imageVector = Icons.Default.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = "No devices found",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Make sure your meter is turned on and in range.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { panelState = BleDevicePanelState.Scanning }) {
                            Text("Try Again")
                        }
                        TextButton(onClick = onUseDrawMethod) {
                            Text("Draw on photo")
                        }
                    }
                }

                is BleDevicePanelState.DevicesFound -> {
                    Text(
                        text = "Select your laser meter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn {
                        items(state.devices) { device ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        text = device.deviceName,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = "Tap to connect",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        panelState = BleDevicePanelState.Connecting(device.deviceName)
                                        scope.launch {
                                            delay(2000)
                                            // TODO: wire device.connect() when available
                                            panelState = BleDevicePanelState.Connected(device)
                                        }
                                    },
                                ) {
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }

                is BleDevicePanelState.Connecting -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = "Connecting to ${state.deviceName}…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TextButton(onClick = { panelState = BleDevicePanelState.Scanning }) {
                        Text("Cancel")
                    }
                }

                is BleDevicePanelState.Connected -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Connected: ${state.device.deviceName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "Point laser at your reference, then press the measure button on the device.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    TextButton(onClick = onUseDrawMethod) {
                        Text("Use draw method instead")
                    }
                }

                is BleDevicePanelState.AwaitingReading -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Connected: ${state.device.deviceName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "Point laser at your reference, then press the measure button on the device.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    TextButton(onClick = onUseDrawMethod) {
                        Text("Use draw method instead")
                    }
                }

                is BleDevicePanelState.ReadingReceived -> {
                    Text(
                        text = "Reading received",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = formatMeters(state.valueMeters),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { panelState = BleDevicePanelState.AwaitingReading(state.device) },
                        ) {
                            Text("Retake")
                        }
                        Button(
                            onClick = {
                                onReadingAccepted(state.valueMeters)
                                onDismiss()
                            },
                        ) {
                            Text("Use this measurement")
                        }
                    }
                }

                is BleDevicePanelState.PermissionDenied -> {
                    Icon(
                        imageVector = Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Bluetooth access denied",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (state.isPermanent) {
                            "Go to Settings → Permissions to allow Bluetooth."
                        } else {
                            "Bluetooth access is needed to connect your laser meter."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.isPermanent) {
                        OutlinedButton(onClick = onUseDrawMethod) {
                            Text("Open Settings")
                        }
                    }
                    TextButton(onClick = onUseDrawMethod) {
                        Text("Use draw method instead")
                    }
                }

                is BleDevicePanelState.BluetoothOff -> {
                    Icon(
                        imageVector = Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = "Bluetooth is turned off",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Turn on Bluetooth to connect your laser meter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onUseDrawMethod) {
                        Text("Use draw method instead")
                    }
                }
            }
        }
    }
}
