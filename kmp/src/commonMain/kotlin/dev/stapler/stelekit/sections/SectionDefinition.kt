package dev.stapler.stelekit.sections

import kotlinx.serialization.Serializable

@Serializable
data class SectionDefinition(
    val id: String,
    val displayName: String,
    val color: String? = null,
    val pagePathPrefix: String,
    val journalPathPrefix: String,
    val sensitivity: String = "normal",
)
