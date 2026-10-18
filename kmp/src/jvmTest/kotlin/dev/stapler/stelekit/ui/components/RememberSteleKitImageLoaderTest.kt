package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import coil3.ImageLoader
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

/** [rememberSteleKitImageLoader] registers [SteleKitAssetMapper] and [SteleKitSafPathMapper] from the composition locals. */
class RememberSteleKitImageLoaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun loaderWith(notesPath: EffectiveNotesPath?, fileSystem: FileSystem?): ImageLoader {
        lateinit var loader: ImageLoader
        composeTestRule.setContent {
            CompositionLocalProvider(LocalNotesPath provides notesPath, LocalFileSystem provides fileSystem) {
                loader = rememberSteleKitImageLoader()
            }
        }
        composeTestRule.waitForIdle()
        return loader
    }

    private fun ImageLoader.mapperTypes() = components.mappers.map { it.first::class }

    @Test fun `registers the asset and saf mappers when a notes folder and file system are provided`() {
        val types = loaderWith(EffectiveNotesPath("/home/user/graph"), FakeFileSystem()).mapperTypes()

        assertTrue(SteleKitAssetMapper::class in types)
        assertTrue(SteleKitSafPathMapper::class in types)
    }

    @Test fun `registers neither mapper when no notes folder or file system is provided`() {
        val types = loaderWith(null, null).mapperTypes()

        assertTrue(SteleKitAssetMapper::class !in types)
        assertTrue(SteleKitSafPathMapper::class !in types)
    }
}
