package dev.stapler.stelekit.sections

import androidx.compose.ui.graphics.Color
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

fun SectionDefinition.parsedColor(fallback: Color): Color =
    color?.let {
        try { Color(it.trimStart('#').toLong(16) or 0xFF000000L) }
        catch (_: NumberFormatException) { null }
    } ?: fallback
