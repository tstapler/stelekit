package dev.stapler.stelekit.wiring

import dev.stapler.stelekit.docs.DocRepoLocator
import java.io.File
import java.util.jar.JarFile

/**
 * Loads the files the wiring gates audit. Keys are `<sourceSet>/<path under its kotlin dir>`, for
 * example `commonMain/dev/stapler/stelekit/ui/StelekitAppDependencies.kt`; the Android app's
 * sources are `androidApp.main/...` and `androidApp.test/...`.
 *
 * Two paths, mirroring [DocRepoLocator]: under Gradle the real source tree is walked; under Bazel
 * the sources are classpath resources bundled by `wiring_*_as_resources` in this package's
 * BUILD.bazel (one `java_library` per `resource_strip_prefix`) and are listed out of the jars on
 * `java.class.path`. Neither path ever returns an empty set silently: callers fail on a missing root.
 */
object WiringInputs {
    private const val ANDROID_APP_MAIN = "androidApp.main"
    private const val ANDROID_APP_TEST = "androidApp.test"
    private val KMP_ENTRY = Regex("""^(\w+(?:Main|Test))/kotlin/(.+\.kt)$""")
    private val APP_ENTRY = Regex("""^src/(main|test)/kotlin/(.+\.kt)$""")

    /** Production sources of every source set that can hold a seam implementation or a root. */
    val productionSources: Map<String, String> by lazy { all.filterKeys { isProduction(it) } }

    /** Entry-point and smoke test sources, for the catalog checks. */
    val testSources: Map<String, String> by lazy { all.filterKeys { !isProduction(it) } }

    private fun isProduction(key: String): Boolean {
        val set = key.substringBefore('/')
        return set.endsWith("Main") || set == ANDROID_APP_MAIN
    }

    /** Which loader produced [all]; asserted by the Bazel negative test. */
    val mode: String by lazy { if (repoRootOrNull() != null) "gradle-disk" else "classpath" }

    private val all: Map<String, String> by lazy {
        val root = repoRootOrNull()
        if (root != null) fromDisk(root) else fromClasspath()
    }

    private fun repoRootOrNull(): File? = try {
        DocRepoLocator.repoRoot
    } catch (e: Throwable) {
        null
    }

    private fun fromDisk(root: File): Map<String, String> {
        val result = sortedMapOf<String, String>()
        val kmpSrc = root.resolve("kmp/src")
        kmpSrc.listFiles { f -> f.isDirectory }.orEmpty().forEach { setDir ->
            val kotlinDir = setDir.resolve("kotlin")
            if (!kotlinDir.isDirectory || !KMP_ENTRY.matches("${setDir.name}/kotlin/x.kt")) return@forEach
            kotlinDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/build/" !in it.invariantSeparatorsPath }
                .forEach { result["${setDir.name}/${it.relativeTo(kotlinDir).invariantSeparatorsPath}"] = it.readText() }
        }
        for ((dirName, set) in listOf("main" to ANDROID_APP_MAIN, "test" to ANDROID_APP_TEST)) {
            val dir = root.resolve("androidApp/src/$dirName/kotlin")
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .forEach { result["$set/${it.relativeTo(dir).invariantSeparatorsPath}"] = it.readText() }
        }
        return result
    }

    private fun fromClasspath(): Map<String, String> {
        val result = sortedMapOf<String, String>()
        System.getProperty("java.class.path").split(File.pathSeparatorChar)
            .filter { it.endsWith(".jar") }
            .forEach { path ->
                runCatching {
                    JarFile(path).use { jar ->
                        for (entry in jar.entries()) {
                            if (entry.isDirectory || !entry.name.endsWith(".kt")) continue
                            val key = keyFor(entry.name) ?: continue
                            val text = jar.getInputStream(entry).bufferedReader().readText()
                            check(result.put(key, text) == null) { "ambiguous root: $key appears twice on the classpath" }
                        }
                    }
                }.onFailure { if (it is IllegalStateException) throw it }
            }
        return result
    }

    private fun keyFor(resourceName: String): String? {
        KMP_ENTRY.matchEntire(resourceName)?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        APP_ENTRY.matchEntire(resourceName)?.let {
            return (if (it.groupValues[1] == "main") ANDROID_APP_MAIN else ANDROID_APP_TEST) + "/" + it.groupValues[2]
        }
        return null
    }

    /**
     * A data file from `businessTest/resources` (or the `scripts/ci` baseline copy). Fails loudly:
     * a missing file must never read as an empty allowlist or baseline.
     */
    fun dataFile(name: String, repoFallback: String? = null): String {
        val stream = WiringInputs::class.java.classLoader.getResourceAsStream(name)
        if (stream != null) return stream.bufferedReader().use { it.readText() }
        val root = repoRootOrNull()
        if (repoFallback != null && root != null) {
            val file = root.resolve(repoFallback)
            if (file.isFile) return file.readText()
        }
        error("data file not found on the test classpath: $name (check business_test_data_as_resources in BUILD.bazel)")
    }
}
