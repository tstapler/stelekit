package dev.stapler.stelekit.merge

import okio.ByteString.Companion.encodeUtf8

/**
 * The marker of an app-owned page: the properties of a first block with visible text.
 * A page-property marker does not survive a reload (ADR-006 spike 5.1), so it is not recognised.
 */
object OwnedMarker {
    const val OWNED = "stelekit-owned"
    const val DIGEST = "stelekit-digest"
    const val HASH = "stelekit-digest-hash"

    /** Block 0 when it carries `stelekit-owned:: true`, else null. */
    fun of(page: MergePage): MergeBlock? = page.blocks.firstOrNull()?.takeIf { it.properties[OWNED] == "true" }

    fun slug(marker: MergeBlock): String? = marker.properties[DIGEST]?.takeIf { it.isNotBlank() }
}

/**
 * The one hash of an owned page: sha256 over the canonical form of blocks 1..n (content, sorted
 * properties without `id`, children). uuid-free, so it does not depend on labelling, renderer,
 * line endings or re-saves; excludes block 0, which carries the hash itself.
 */
object OwnedHash {
    fun of(page: MergePage): String = ofBody(page.blocks.drop(1))

    fun ofBody(body: List<MergeBlock>): String = body.joinToString("") { canonical(it) }.encodeUtf8().sha256().hex()

    private fun canonical(b: MergeBlock): String = buildString {
        append('[').append(b.content)
        b.properties.filterKeys { it != "id" }.entries.sortedBy { it.key }
            .forEach { (k, v) -> append('|').append(k).append('=').append(v) }
        b.children.forEach { append(canonical(it)) }
        append(']')
    }
}
