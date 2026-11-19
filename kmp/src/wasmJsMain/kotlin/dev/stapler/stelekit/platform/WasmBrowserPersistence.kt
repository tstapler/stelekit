package dev.stapler.stelekit.platform

/** [BrowserPersistence] over `navigator.storage.persist()`; the first-write dialog calls it from its click handler (D-125). */
class WasmBrowserPersistence : BrowserPersistence {
    override suspend fun request(): PersistenceState = opfsRequestPersist()
}
