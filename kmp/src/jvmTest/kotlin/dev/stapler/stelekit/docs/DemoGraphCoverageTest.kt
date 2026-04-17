package dev.stapler.stelekit.docs

import dev.stapler.stelekit.ui.Screen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class DemoGraphCoverageTest {

    private val demoGraphPagesDir: File by lazy {
        val url = javaClass.classLoader.getResource("demo-graph/pages")
            ?: fail("demo-graph/pages not found in classpath")
        File(url.toURI())
    }

    private fun pageExists(title: String): Boolean =
        File(demoGraphPagesDir, "$title.md").exists()

    private fun pageNonEmpty(title: String): Boolean {
        val f = File(demoGraphPagesDir, "$title.md")
        return f.exists() && f.readText().isNotBlank()
    }

    private fun findAnnotatedClasses(): List<Pair<String, HelpPage>> {
        val screenClass = Screen::class.java
        return (screenClass.declaredClasses.toList() + listOf(screenClass))
            .mapNotNull { clazz ->
                clazz.getAnnotation(HelpPage::class.java)?.let { clazz.simpleName to it }
            }
    }

    @Test
    fun `all HelpPage annotations reference existing non-empty pages`() {
        val annotated = findAnnotatedClasses()
        // Zero annotations until Story 5 — this test passes vacuously
        for ((className, annotation) in annotated) {
            val docs = annotation.docs.java.getDeclaredConstructor().newInstance() as DiataxisDoc
            if (docs is HowToDoc) {
                val title = docs.howTo.title
                assertTrue(pageExists(title),
                    "[$className] HowTo page missing: '$title.md' in demo-graph/pages/")
                assertTrue(pageNonEmpty(title),
                    "[$className] HowTo page is empty: '$title.md'")
            }
            if (docs is ReferenceDoc) {
                val title = docs.reference.title
                assertTrue(pageExists(title),
                    "[$className] Reference page missing: '$title.md' in demo-graph/pages/")
                assertTrue(pageNonEmpty(title),
                    "[$className] Reference page is empty: '$title.md'")
            }
        }
    }

    @Test
    fun `annotated count is reported for visibility`() {
        val count = findAnnotatedClasses().size
        // This test always passes — it just prints the count for CI visibility
        println("DemoGraphCoverageTest: found $count @HelpPage-annotated Screen entries")
    }
}
