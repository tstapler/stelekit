package dev.stapler.stelekit.platform.measurement

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Central registry for all [MeasurementDeviceFactory] implementations.
 *
 * Each transport layer (BLE via Kable, keyboard emulation, USB serial) registers
 * its factory at app startup. [getAllDevices] aggregates results from all factories
 * into a single hot flow.
 *
 * Thread-safety: [register] is called during app initialization (single thread);
 * [getAllDevices] is thereafter read-only and safe to call from any coroutine.
 */
object MeasurementDeviceRegistry {
    private val factories = mutableListOf<MeasurementDeviceFactory>()

    /**
     * Register a [MeasurementDeviceFactory] with the registry.
     *
     * Must be called before [getAllDevices] is first collected.
     * Typically called in the platform entry point (Application.onCreate, Main.kt, etc.).
     */
    fun register(factory: MeasurementDeviceFactory) {
        factories.add(factory)
    }

    /**
     * Return a snapshot of currently registered factories for inspection/testing.
     */
    fun registeredFactories(): List<MeasurementDeviceFactory> = factories.toList()

    /**
     * Merge the [MeasurementDeviceFactory.scan] flows from all registered factories into
     * a single flow that emits every discovered [ExternalMeasurementDevice].
     *
     * Returns an empty flow when no factories are registered.
     */
    fun getAllDevices(): Flow<ExternalMeasurementDevice> {
        if (factories.isEmpty()) return kotlinx.coroutines.flow.emptyFlow()
        return factories.map { it.scan() }.merge()
    }

    /**
     * Clear all registered factories. Intended for use in tests only.
     */
    internal fun clearForTesting() {
        factories.clear()
    }
}
