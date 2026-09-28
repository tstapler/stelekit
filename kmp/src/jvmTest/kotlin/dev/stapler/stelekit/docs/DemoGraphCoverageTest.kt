package dev.stapler.stelekit.docs

import dev.stapler.stelekit.testing.getClasspathDirectory
import dev.stapler.stelekit.ui.Screen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DemoGraphCoverageTest {

    private val demoGraphPagesDir: File by lazy {
        File(getClasspathDirectory(javaClass.classLoader, "demo-graph"), "pages")
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

    @Test
    fun `coverage summary — all Screen subclasses`() {
        val screenClass = Screen::class.java
        val allSubclasses = screenClass.declaredClasses.toList()

        val annotated = allSubclasses.filter { it.getAnnotation(HelpPage::class.java) != null }
        val exempt = allSubclasses.filter {
            // @HelpExempt has SOURCE retention — not visible at runtime.
            // Count by absence: neither @HelpPage nor any known exempt annotation.
            it.getAnnotation(HelpPage::class.java) == null &&
            it.simpleName in KNOWN_EXEMPT_SCREENS
        }
        val unannotated = allSubclasses - annotated.toSet() - exempt.toSet()

        println("""
            Screen coverage summary:
              Total Screen subclasses : ${allSubclasses.size}
              @HelpPage annotated     : ${annotated.size}
              Known exempt            : ${exempt.size}
              Unannotated (gap)       : ${unannotated.size}
              Unannotated names       : ${unannotated.map { it.simpleName }}
        """.trimIndent())
        // This test always passes — it is informational only.
    }

    companion object {
        // Mirrors the @HelpExempt reason list. Keep in sync with AppState.kt.
        private val KNOWN_EXEMPT_SCREENS = setOf(
            "LibraryStats", "Notifications", "Logs", "Performance",
            "VaultUnlock", "Import", "AnnotationEditor", "Gallery", "AssetBrowser"
        )
    }
}
