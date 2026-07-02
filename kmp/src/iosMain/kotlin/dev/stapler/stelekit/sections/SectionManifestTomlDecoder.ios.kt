package dev.stapler.stelekit.sections

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.serializer

private val sectionToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

internal actual fun decodeSectionManifestToml(content: String): SectionManifest? =
    sectionToml.decodeFromString(serializer<SectionManifest>(), content)
