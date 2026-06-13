package dev.stapler.stelekit.benchmark

import kotlinx.serialization.Serializable

/**
 * Unified benchmark types shared across JVM, Android, and wasmJs targets.
 *
 * BenchmarkResult is the canonical output of every scenario run. All platform runners
 * serialize results to JSON using this schema so baseline comparisons are cross-platform.
 */

@Serializable
data class BenchmarkResult(
    val platform: String,
    val scenario: String,
    val graphConfig: String,
    val runAtEpochMs: Long,
    val gitSha: String,
    val metrics: Map<String, Double>,
)

/**
 * Graph size presets. Page and journal counts match [SyntheticGraphGenerator] companion constants.
 *
 * TINY   —  ~50 pages,  ~14 journals, sparse links        (quick smoke test, always runs in CI)
 * SMALL  — ~200 pages,  ~30 journals, moderate links       (CI regression baseline)
 * MEDIUM — ~500 pages,  ~90 journals, moderate links       (realistic small library)
 * LARGE  — ~2000 pages, ~365 journals, dense links         (stress test)
 */
enum class GraphPreset {
    TINY,
    SMALL,
    MEDIUM,
    LARGE,
}

/**
 * Descriptor held in [BenchmarkRegistry]. Holds metadata only — no concrete scenario instances,
 * which may reference JVM-only types.
 *
 * Platform runners map descriptors to local scenario instances via a platform-local factory.
 */
@Serializable
data class ScenarioDescriptor(
    val name: String,
    val preset: GraphPreset,
    val supportedPlatforms: Set<String>,
)

/**
 * Single source of truth for which scenarios exist and which platforms support them.
 *
 * Platform strings: "jvm", "android", "wasmjs"
 *
 * UserSession is JVM-only (requires a real graph path; synthetic data produces meaningless results).
 * WriteConcurrency and NavigationLatency require SQLite; wasmJs uses in-memory repos only.
 */
object BenchmarkRegistry {
    val all: List<ScenarioDescriptor> = listOf(
        ScenarioDescriptor(
            name = "GraphLoad",
            preset = GraphPreset.TINY,
            supportedPlatforms = setOf("jvm", "android", "wasmjs"),
        ),
        ScenarioDescriptor(
            name = "GraphLoad",
            preset = GraphPreset.SMALL,
            supportedPlatforms = setOf("jvm", "android"),
        ),
        ScenarioDescriptor(
            name = "WriteConcurrency",
            preset = GraphPreset.TINY,
            supportedPlatforms = setOf("jvm", "android"),
        ),
        ScenarioDescriptor(
            name = "NavigationLatency",
            preset = GraphPreset.SMALL,
            supportedPlatforms = setOf("jvm", "android"),
        ),
        ScenarioDescriptor(
            name = "UserSession",
            preset = GraphPreset.SMALL,
            supportedPlatforms = setOf("jvm"),
        ),
    )
}
