package dev.stapler.stelekit.model

import kotlinx.serialization.Serializable

/**
 * Information about a single graph (knowledge base).
 */
@Serializable
data class GraphInfo(
    val id: String,           // sha256(canonicalPath).take(16)
    val path: String,         // Canonical absolute path
    val displayName: String,  // User-facing name (defaults to directory name)
    val addedAt: Long         // Epoch millis
)

/**
 * Registry of all known graphs and which one is active.
 */
@Serializable
data class GraphRegistry(
    val activeGraphId: String? = null,
    val graphs: List<GraphInfo> = emptyList()
) {
    /**
     * Get the set of graph IDs for quick lookup
     */
    val graphIds: Set<String>
        get() = graphs.map { it.id }.toSet()
}