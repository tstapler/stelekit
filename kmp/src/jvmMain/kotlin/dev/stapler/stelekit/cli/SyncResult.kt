package dev.stapler.stelekit.cli

import kotlinx.serialization.Serializable

@Serializable
data class SyncResult(
    val graph: String,
    val branch: String,
    val localCommits: Int,
    val remoteCommits: Int,
    val conflicts: List<String> = emptyList(),
    val status: String, // "success" | "conflicts" | "auth_error" | "network_error" | "no_config" | "error"
)
