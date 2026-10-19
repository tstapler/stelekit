package dev.stapler.stelekit

import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runners.Suite

/**
 * Bazel runs only the classes listed in [AllJvmTests]; a test class left out passes silently with
 * zero cases. This finds every concrete JUnit test class compiled alongside the suite and fails
 * when one is neither listed nor in [UNREGISTERED_JVM_TESTS].
 */
class AllJvmTestsCompletenessTest {

    private val excludedFromBazel = Regex("""(Screenshot|Roborazzi)|Mermaid(EngineActor|JvmEngine|RendererFallback|RendererSmoke|SecurityDirective)Test""")

    private fun compiledClassNames(): List<String> {
        val location = File(AllJvmTests::class.java.protectionDomain.codeSource.location.toURI())
        val entries = if (location.isDirectory) {
            location.walkTopDown().filter { it.extension == "class" }
                .map { it.relativeTo(location).path }.toList()
        } else {
            JarFile(location).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        return entries.filter { it.endsWith(".class") && '$' !in it }
            .map { it.removeSuffix(".class").replace('/', '.').replace('\\', '.') }
    }

    private fun isConcreteTestClass(name: String): Boolean {
        val cls = try {
            Class.forName(name, false, AllJvmTests::class.java.classLoader)
        } catch (_: LinkageError) {
            return false
        }
        return !java.lang.reflect.Modifier.isAbstract(cls.modifiers) &&
            cls.methods.any { it.isAnnotationPresent(org.junit.Test::class.java) }
    }

    private val testClasses: Set<String> by lazy {
        compiledClassNames()
            .filterNot { excludedFromBazel.containsMatchIn(it.substringAfterLast('.')) }
            .filter(::isConcreteTestClass)
            .toSet()
    }

    private val registered: Set<String> by lazy {
        AllJvmTests::class.java.getAnnotation(Suite.SuiteClasses::class.java).value.map { it.java.name }.toSet()
    }

    @Test
    fun `every jvmTest class is registered in AllJvmTests`() {
        val unlisted = (testClasses - registered - UNREGISTERED_JVM_TESTS).sorted()
        assertTrue(
            unlisted.isEmpty(),
            "Add to AllJvmTests @Suite.SuiteClasses (Bazel skips them otherwise):\n" + unlisted.joinToString("\n"),
        )
    }

    @Test
    fun `the unregistered list holds only classes that are still unregistered`() {
        val stale = (UNREGISTERED_JVM_TESTS.filter { it in registered || it !in testClasses }).sorted()
        assertTrue(
            stale.isEmpty(),
            "Remove from UNREGISTERED_JVM_TESTS (registered or no longer a test class):\n" + stale.joinToString("\n"),
        )
    }

    @Test
    fun `the scan sees this test class`() {
        assertTrue(AllJvmTestsCompletenessTest::class.java.name in testClasses, "scan found ${testClasses.size} classes")
    }
}
