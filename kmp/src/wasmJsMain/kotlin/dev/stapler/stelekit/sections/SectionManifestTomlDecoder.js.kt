package dev.stapler.stelekit.sections

// ktoml generates invalid WASM bytecode (wasm-opt validation failure) — return null
// so SectionManifestParser falls back to an empty SectionManifest in the browser.
internal actual fun decodeSectionManifestToml(content: String): SectionManifest? = null

internal actual fun encodeSectionManifestToml(manifest: SectionManifest): String =
    throw UnsupportedOperationException("TOML encoding not supported on WASM")
