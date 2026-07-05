package dev.stapler.stelekit.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Type-safe wrapper for graph identifiers (sha256(canonicalPath).take(16)). */
@Serializable
@JvmInline
value class GraphId(val value: String)

/**
 * Information about a single graph (knowledge base).
 */
@Serializable
data class GraphInfo(
    val id: GraphId,           // sha256(canonicalPath).take(16)
    val path: String,         // Canonical absolute path
    val displayName: String,  // User-facing name (defaults to directory name)
    val addedAt: Long,        // Epoch millis
    val isParanoidMode: Boolean = false,  // True when .stele-vault is present
    val detectedRepoRoot: String? = null,
    val detectedWikiSubdir: String? = null,
    val gitDetectionDismissed: Boolean = false,
)

/**
 * Registry of all known graphs and which one is active.
 */
@Serializable
data class GraphRegistry(
    val activeGraphId: GraphId? = null,
    val graphs: List<GraphInfo> = emptyList()
) {
    /**
     * Get the set of graph IDs for quick lookup
     */
    val graphIds: Set<GraphId>
        get() = graphs.map { it.id }.toSet()
}
