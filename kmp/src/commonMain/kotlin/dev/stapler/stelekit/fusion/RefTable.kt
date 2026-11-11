package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.util.ContentHasher

/**
 * Maps opaque [SourceRef]s back to a human locator, which may be a page title. DEVICE-LOCAL by
 * construction: it is not serializable and nothing that reaches the synced sidecar or an LLM
 * payload may hold one (OD-H8). It is rebuilt from the local database, not persisted here.
 */
class RefTable {
    private val locators = HashMap<SourceRef, String>()

    val size: Int get() = locators.size

    /** The same kind and locator always give the same ref; the locator text never appears in it. */
    fun refFor(kind: SourceKind, locator: String): SourceRef {
        val id = ContentHasher.sha256("${kind.name}:$locator").take(ID_LENGTH)
        val ref = SourceRef(kind, id)
        locators[ref] = locator
        return ref
    }

    fun describe(ref: SourceRef): String? = locators[ref]

    private companion object {
        const val ID_LENGTH = 12
    }
}
