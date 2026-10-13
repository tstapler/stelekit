package dev.stapler.stelekit.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.WindowSizeClass
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Renders every [ComponentGallery] story in light and dark themes at a phone (COMPACT) and a
 * desktop (EXPANDED) width, so a UI change can be reviewed across all components at once.
 *
 * Roborazzi writes nothing unless a mode flag is set:
 *   ./gradlew :kmp:jvmTest --tests '*ComponentGalleryScreenshotTest' -Proborazzi.test.record=true
 * Images land in `kmp/build/outputs/roborazzi/gallery/`, with `index.html` as a contact sheet.
 * Rerun with `-Proborazzi.test.compare=true` after a change to get before/after diffs.
 */
@RunWith(Parameterized::class)
class ComponentGalleryScreenshotTest(
    private val story: GalleryStory,
    private val theme: StelekitThemeMode,
    private val sizeClass: WindowSizeClass,
) {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun capture() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalWindowSizeClass provides sizeClass) {
                StelekitTheme(themeMode = theme) {
                    Surface {
                        Box(Modifier.width(widthFor(sizeClass)).padding(16.dp)) { story.content() }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        // A Dialog/AlertDialog adds a second root (its own window layer); capture that one, since it
        // holds the dialog. Plain stories have a single root.
        val roots = composeTestRule.onAllNodes(isRoot())
        val last = roots.fetchSemanticsNodes().size - 1
        roots[last].captureRoboImage(imagePath(story.id, theme, sizeClass))
    }

    companion object {
        private const val OUTPUT_DIR = "build/outputs/roborazzi/gallery"

        /** `GALLERY_FILTER=dialogs-` (forwarded as the `gallery.filter` system property) renders only stories whose id starts with it (faster iteration). */
        private val storyFilter: String? = System.getProperty("gallery.filter")?.takeIf { it.isNotBlank() }

        private val THEMES = listOf(StelekitThemeMode.LIGHT, StelekitThemeMode.DARK)
        private val SIZES = listOf(WindowSizeClass.COMPACT, WindowSizeClass.EXPANDED)

        // Representative widths inside each class: a 390dp phone and a 900dp desktop pane.
        private fun widthFor(sizeClass: WindowSizeClass) = if (sizeClass == WindowSizeClass.COMPACT) 390.dp else 900.dp

        private fun imagePath(id: String, theme: StelekitThemeMode, sizeClass: WindowSizeClass) =
            "$OUTPUT_DIR/$id--${theme.name.lowercase()}-${sizeClass.name.lowercase()}.png"

        /** Pixels must not depend on the machine: pin the zone and locale that date formatting reads. */
        @JvmStatic
        @BeforeClass
        fun pinEnvironment() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.US)
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}-{2}")
        fun cases(): List<Array<Any>> =
            ComponentGallery.stories.filter { storyFilter == null || it.id.startsWith(storyFilter) }.flatMap { story ->
                THEMES.flatMap { theme -> SIZES.map { size -> arrayOf<Any>(story, theme, size) } }
            }

        /** Contact sheet: one row per story, one column per theme x size, for quick visual review. */
        @JvmStatic
        @AfterClass
        fun writeIndex() {
            val dir = File(OUTPUT_DIR)
            if (!dir.isDirectory) return // no record/compare flag was set, so nothing was captured
            val columns = THEMES.flatMap { t -> SIZES.map { s -> t to s } }
            val rows = ComponentGallery.stories.filter { storyFilter == null || it.id.startsWith(storyFilter) }.joinToString("\n") { story ->
                val cells = columns.joinToString("") { (t, s) ->
                    val name = File(imagePath(story.id, t, s)).name
                    if (File(dir, name).exists()) "<td><img src=\"$name\" loading=\"lazy\"></td>" else "<td>missing</td>"
                }
                "<tr><th>${story.id}</th>$cells</tr>"
            }
            val header = columns.joinToString("") { (t, s) -> "<th>${t.name.lowercase()} / ${s.name.lowercase()}</th>" }
            File(dir, "index.html").writeText(
                """
                <!doctype html><meta charset="utf-8"><title>Component gallery</title>
                <style>body{font:14px sans-serif;margin:16px}td,th{vertical-align:top;padding:6px;border:1px solid #ccc}img{max-width:420px}</style>
                <table><tr><th>story</th>$header</tr>
                $rows
                </table>
                """.trimIndent(),
            )
        }
    }
}
