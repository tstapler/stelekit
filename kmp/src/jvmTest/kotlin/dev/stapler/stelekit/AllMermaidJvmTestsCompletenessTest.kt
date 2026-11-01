package dev.stapler.stelekit

import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.runners.Suite

/**
 * The mermaid Bazel target runs only the classes listed in [AllMermaidJvmTests]; a test class added to the
 * target's srcs but left out of the suite passes silently with zero cases. This fails when one is unlisted.
 */
class AllMermaidJvmTestsCompletenessTest {

    private val location = File(AllMermaidJvmTests::class.java.protectionDomain.codeSource.location.toURI())

    // Gradle runs every class in an unpacked directory shared with the rest of jvmTest, so only the Bazel jar is comparable.
    private fun assumeBazelJar() = Assume.assumeTrue(location.isFile)

    private val testClasses: Set<String> by lazy {
        JarFile(location).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
            .filter { it.endsWith(".class") && '$' !in it }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .filter { name ->
                val cls = try {
                    Class.forName(name, false, AllMermaidJvmTests::class.java.classLoader)
                } catch (_: LinkageError) {
                    return@filter false
                }
                !java.lang.reflect.Modifier.isAbstract(cls.modifiers) &&
                    cls.declaredMethods.any { it.isAnnotationPresent(org.junit.Test::class.java) }
            }
            .toSet()
    }

    private val registered: Set<String> by lazy {
        AllMermaidJvmTests::class.java.getAnnotation(Suite.SuiteClasses::class.java).value.map { it.java.name }.toSet()
    }

    @Test
    fun `every mermaid test class is registered in AllMermaidJvmTests`() {
        assumeBazelJar()
        val unlisted = (testClasses - registered).sorted()
        assertTrue(
            unlisted.isEmpty(),
            "Add to AllMermaidJvmTests @Suite.SuiteClasses (Bazel skips them otherwise):\n" + unlisted.joinToString("\n"),
        )
    }

    @Test
    fun `the scan finds the registered classes`() {
        assumeBazelJar()
        assertTrue(
            testClasses.size >= registered.size / 2,
            "scan found ${testClasses.size} test classes but the suite lists ${registered.size}; test detection is likely broken",
        )
        assertTrue(AllMermaidJvmTestsCompletenessTest::class.java.name in testClasses, "scan found ${testClasses.size} classes")
    }
}
