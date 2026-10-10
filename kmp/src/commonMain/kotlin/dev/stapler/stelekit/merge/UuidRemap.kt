package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.GraphId
import okio.ByteString.Companion.encodeUtf8

/**
 * Deterministic source-uuid -> target-uuid remap (ADR-002 rev. 3). Always remaps, never consults
 * the target: an off-graph target's DB is closed and `insertBlock` is INSERT OR REPLACE on uuid.
 * SHA-256 rather than `UuidGenerator.generateDeterministic` (64-bit FNV, collision-prone).
 */
object UuidRemap {
    private val REF = Regex("\\(\\(([^()\\s]+)\\)\\)")

    fun uuidFor(sourceGraphId: GraphId, sourceUuid: String): String =
        hashToUuid("merge:${sourceGraphId.value}:$sourceUuid")

    /** Distinct seed prefix keeps it from ever equalling [uuidFor] for the same (graph, uuid). */
    fun conflictUuid(sourceGraphId: GraphId, sourceUuid: String, normalizedContent: String): String =
        hashToUuid(
            "merge-conflict:${sourceGraphId.value}:$sourceUuid:" + normalizedContent.encodeUtf8().sha256().hex(),
        )

    /** sourceUuid -> uuid' for every block with an explicit uuid in the trees. */
    fun compute(sourceGraphId: GraphId, blocks: List<MergeBlock>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        fun walk(bs: List<MergeBlock>) {
            bs.forEach { b ->
                b.uuid?.let { map[it] = uuidFor(sourceGraphId, it) }
                walk(b.children)
            }
        }
        walk(blocks)
        return map
    }

    /** Rewrites `((uuid))` (and so `{{embed ((uuid))}}`) via [map]; unmapped refs are left alone. */
    fun rewriteRefs(content: String, map: Map<String, String>): String =
        if (map.isEmpty() || !content.contains("((")) content
        else REF.replace(content) { m -> map[m.groupValues[1]]?.let { "(($it))" } ?: m.value }

    private fun hashToUuid(seed: String): String {
        val h = seed.encodeUtf8().sha256().hex().substring(0, 32)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}
