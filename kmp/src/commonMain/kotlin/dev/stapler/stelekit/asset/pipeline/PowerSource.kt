package dev.stapler.stelekit.asset.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the device is on external power; the plugin drain waits for it on battery-powered hosts. */
interface PowerSource {
    val isCharging: StateFlow<Boolean>
}

/** Hosts with no battery concern (desktop, web): the drain never waits. */
object AlwaysPowered : PowerSource {
    override val isCharging: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
