package dev.stapler.stelekit.wiring

/**
 * The fixed `CompositionRoot` set (plan Glossary). Roots are looked up by source-set-qualified
 * path in [WiringInputs.productionSources]; a root that cannot be found fails with
 * `root not found: <name>; roots searched: [...]` and an empty root set never passes.
 */
object WiringRoots {
    private const val PKG = "dev/stapler/stelekit"

    /** Exact-path roots. */
    val EXACT: List<String> = listOf(
        "jvmMain/$PKG/desktop/Main.kt",
        "androidApp.main/$PKG/MainActivity.kt",
        "androidApp.main/$PKG/SteleKitApplication.kt",
        "commonMain/$PKG/ui/StelekitAppDependencies.kt",
        "commonMain/$PKG/repository/RepositoryFactory.kt",
    )

    private val GRAPH_CONTENT_SETUP = Regex("""^commonMain/$PKG/ui/GraphContent\w*Setup\.kt$""")

    /**
     * Roots that a later unit creates. Absent is tolerated only until the file exists; once it
     * exists it is searched like any other root. `asset/pipeline/DefaultAssetPlugins.kt` is
     * created by Task 5.2a (unit U8).
     */
    val PENDING: Map<String, String> = mapOf(
        "commonMain/$PKG/asset/pipeline/DefaultAssetPlugins.kt" to "created by Task 5.2a (U8)",
    )

    class Resolution(val roots: Map<String, String>, val missing: List<String>) {
        fun requireComplete(): Map<String, String> {
            check(missing.isEmpty()) {
                missing.joinToString("\n") { "root not found: $it; roots searched: ${roots.keys.sorted()}" }
            }
            return roots
        }
    }

    fun resolve(sources: Map<String, String>): Resolution {
        val found = linkedMapOf<String, String>()
        val missing = mutableListOf<String>()
        for (path in EXACT) {
            val text = sources[path]
            if (text == null) missing += path else found[path] = text
        }
        val setups = sources.filterKeys { GRAPH_CONTENT_SETUP.matches(it) }
        if (setups.isEmpty()) missing += "commonMain/$PKG/ui/GraphContent*Setup.kt (at least one)"
        found.putAll(setups)
        PENDING.keys.forEach { path -> sources[path]?.let { found[path] = it } }
        return Resolution(found, missing)
    }
}
