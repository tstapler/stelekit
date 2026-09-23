package dev.stapler.stelekit.docs

import dev.stapler.stelekit.ui.Screen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class WebsiteDocsCoverageTest {

    private val repoRoot: File by lazy {
        val resource = javaClass.classLoader.getResource("demo-graph/pages")
            ?: error(
                "demo-graph/pages not found on classpath — is the test classpath misconfigured?"
            )
        var dir = File(resource.toURI())
        while (dir != dir.parentFile) {
            if (dir.resolve("kmp").isDirectory && dir.resolve("site").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error(
            "Could not locate repository root (dir containing both kmp/ and site/) from: $resource"
        )
    }

    private val siteDocsDir: File by lazy {
        repoRoot.resolve("site/src/content/docs/user")
    }

    private fun slugFor(title: String): String =
        title.lowercase()
            .replace(' ', '-')
            .replace(Regex("[^a-z0-9-]"), "")

    @Test
    fun `every HelpPage-annotated Screen has a website docs page`() {
        assertTrue(
            siteDocsDir.isDirectory,
            "site/src/content/docs/user/ not found at $siteDocsDir — " +
                "create the directory and add at least one .mdx file"
        )

        val annotated = Screen::class.java.declaredClasses
            .mapNotNull { cls ->
                cls.getAnnotation(HelpPage::class.java)?.let { ann -> cls.simpleName to ann }
            }

        val failures = mutableListOf<String>()
        for ((screenName, annotation) in annotated) {
            val docs = annotation.docs.java.getDeclaredConstructor().newInstance() as DiataxisDoc
            // HowToDoc check MUST come first: MinimalFeatureDoc implements both,
            // and howTo.title is the canonical user-facing name (R5).
            val title = when (docs) {
                is HowToDoc -> docs.howTo.title
                is ReferenceDoc -> docs.reference.title
                else -> continue
            }
            val slug = slugFor(title)
            val mdx = siteDocsDir.resolve("$slug.mdx")
            val md  = siteDocsDir.resolve("$slug.md")
            when {
                !mdx.exists() && !md.exists() ->
                    failures += "[$screenName] Missing: site/src/content/docs/user/$slug.mdx"
                mdx.exists() && mdx.readText().isBlank() ->
                    failures += "[$screenName] Empty: site/src/content/docs/user/$slug.mdx"
                md.exists()  && md.readText().isBlank() ->
                    failures += "[$screenName] Empty: site/src/content/docs/user/$slug.md"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Website docs missing or empty:\n${failures.joinToString("\n")}"
        )
    }
}
