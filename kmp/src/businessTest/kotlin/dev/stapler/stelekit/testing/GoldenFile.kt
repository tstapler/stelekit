package dev.stapler.stelekit.testing

import dev.stapler.stelekit.docs.DocRepoLocator
import java.io.File
import kotlin.test.fail

/**
 * Golden-file assertions over `kmp/src/businessTest/resources/home-sketch/closet/<name>`.
 *
 * Reads the stored golden from the test classpath (works under Gradle and Bazel). Updating is a local,
 * Gradle-only act: `-Dgolden.update=true` rewrites the file on disk, and is refused when env `CI` is
 * set so a pipeline can never silently bless a changed output.
 */
class GoldenFile(
    private val resourceDir: String = "home-sketch/closet",
    private val diskDir: () -> File = { DocRepoLocator.repoRoot.resolve("kmp/src/businessTest/resources/$resourceDir") },
    private val property: (String) -> String? = System::getProperty,
    private val env: (String) -> String? = System::getenv,
) {
    fun assertMatches(name: String, actual: String) {
        val update = property(UPDATE_PROPERTY) == "true"
        val ci = env(CI_ENV) != null
        if (update && ci) fail("golden update forbidden in CI ($UPDATE_PROPERTY=true with $CI_ENV set)")
        if (update) {
            val file = diskDir().resolve(name)
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        val expected = GoldenFile::class.java.classLoader.getResourceAsStream("$resourceDir/$name")
            ?.bufferedReader()?.use { it.readText() }
            ?: fail("golden '$name' not found on the test classpath; create it with -D$UPDATE_PROPERTY=true (local, CI unset)")
        if (expected != actual) {
            fail("golden '$name' differs (expected ${expected.length} chars, actual ${actual.length}); " +
                "review the change, then re-run locally with -D$UPDATE_PROPERTY=true")
        }
    }

    companion object {
        const val UPDATE_PROPERTY = "golden.update"
        const val CI_ENV = "CI"
        val default = GoldenFile()
        fun assertMatches(name: String, actual: String) = default.assertMatches(name, actual)
    }
}
