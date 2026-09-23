package dev.stapler.stelekit.sections

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SectionManifest(
    val version: Int = 1,
    @SerialName("section") val sections: List<SectionDefinition> = emptyList(),
) {
    companion object {
        const val FILENAME = ".stele-sections"
    }
}
