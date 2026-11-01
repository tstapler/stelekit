package dev.stapler.stelekit.wiring

/**
 * Fixture helper every `wiring-catalog.txt` entry-point test must call after it has invoked the real
 * root factory: `ObservableEffect.assert("<label>") { assertTrue(rowCount > 0) }`.
 *
 * The body must assert runtime state (an `asset_index` row count, a `SyncTick` log line), never that
 * a class is referenced, so a feature that is constructed but never runs fails here. The catalog
 * check (`WiringCatalog`) enforces the label and the call shape statically.
 */
object ObservableEffect {
    inline fun assert(label: String, effect: () -> Unit) {
        try {
            effect()
        } catch (e: AssertionError) {
            throw AssertionError("observable effect '$label' did not happen through the real root factory: ${e.message}", e)
        }
    }
}
