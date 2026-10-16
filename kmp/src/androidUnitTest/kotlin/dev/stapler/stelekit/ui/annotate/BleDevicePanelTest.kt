// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceRegistry
import dev.stapler.stelekit.platform.measurement.MeasurementReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

private class StubDevice(override val deviceName: String) : ExternalMeasurementDevice {
    override val deviceId = "stub"
    override val connectionState: StateFlow<DeviceConnectionState> = MutableStateFlow(DeviceConnectionState.CONNECTED)
    override suspend fun connect(): Either<DomainError, Unit> = Unit.right()
    override suspend fun disconnect() {}
    override fun measurementFlow(): Flow<MeasurementReading> = emptyFlow()
}

@RunWith(RobolectricTestRunner::class)
class BleDevicePanelTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun setUp() = MeasurementDeviceRegistry.clearForTesting()

    @After
    fun tearDown() = MeasurementDeviceRegistry.clearForTesting()

    @Test
    fun noFactories_showsNoDevicesFound_andFallbackActions() {
        var draw = 0
        rule.setContent { BleDevicePanel(onDismiss = {}, onUseDrawMethod = { draw++ }, onReadingAccept = {}) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("No devices found").assertExists()
        rule.onNodeWithText("Make sure your meter is turned on and in range.").assertExists()
        rule.onNodeWithText("Draw on photo").performClick()
        assertEquals(1, draw)
    }

    @Test
    fun tryAgain_rescansAndReturnsToNoDevicesFound() {
        rule.setContent { BleDevicePanel(onDismiss = {}, onUseDrawMethod = {}, onReadingAccept = {}) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Try Again").performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithText("Scanning for devices…").assertExists()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("No devices found").assertExists()
    }

    @Test
    fun discoveredDevice_isListed_andConnectFlowsToConnectedState() {
        MeasurementDeviceRegistry.register(object : MeasurementDeviceFactory {
            override fun scan(): Flow<ExternalMeasurementDevice> = flowOf(StubDevice("Leica DISTO"))
        })
        rule.setContent { BleDevicePanel(onDismiss = {}, onUseDrawMethod = {}, onReadingAccept = {}) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Select your laser meter").assertExists()
        rule.onNodeWithText("Leica DISTO").assertExists()

        rule.onNodeWithText("Connect").performClick()
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Connecting to Leica DISTO…").assertExists()

        rule.mainClock.advanceTimeBy(2_500)
        rule.onNodeWithText("Connected: Leica DISTO").assertExists()
        rule.onNodeWithText("Point laser at your reference, then press the measure button on the device.").assertExists()
    }

    @Test
    fun cancelWhileConnecting_returnsToScanning() {
        MeasurementDeviceRegistry.register(object : MeasurementDeviceFactory {
            override fun scan(): Flow<ExternalMeasurementDevice> = flowOf(StubDevice("Bosch GLM"))
        })
        rule.setContent { BleDevicePanel(onDismiss = {}, onUseDrawMethod = {}, onReadingAccept = {}) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Connect").performClick()
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Scanning for devices…").assertExists()
        // The in-flight mock connection must not resurrect a Connected state after Cancel.
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithText("Connected: Bosch GLM").assertDoesNotExist()
    }
}
