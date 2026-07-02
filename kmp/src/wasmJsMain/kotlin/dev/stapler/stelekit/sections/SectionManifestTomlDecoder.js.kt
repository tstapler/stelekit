package dev.stapler.stelekit.sections

// ktoml generates invalid WASM bytecode (wasm-opt validation failure) — return null
// so SectionManifestParser falls back to an empty SectionManifest in the browser.
internal actual fun decodeSectionManifestToml(content: String): SectionManifest? = null
