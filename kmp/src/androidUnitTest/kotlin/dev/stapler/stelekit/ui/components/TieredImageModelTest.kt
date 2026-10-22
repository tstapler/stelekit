// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.image.ImageTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory stand-in; the Bazel android unit-test target has no access to commonTest fakes. */
private class MemoryFileSystem : FileSystem {
    val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf<String>()

    override fun getDefaultGraphPath() = "/graph"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = files[path]?.decodeToString()
    override fun writeFile(path: String, content: String): Boolean = writeFileBytes(path, content.encodeToByteArray())
    override fun listFiles(path: String) = emptyList<String>()
    override fun listDirectories(path: String) = emptyList<String>()
    override fun fileExists(path: String) = path in files
    override fun directoryExists(path: String) = path in dirs
    override fun createDirectory(path: String): Boolean = dirs.add(path).let { true }
    override fun deleteFile(path: String) = files.remove(path) != null
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
    override fun readFileBytes(path: String): ByteArray? = files[path]
    override fun writeFileBytes(path: String, data: ByteArray): Boolean { files[path] = data; return true }
}

/** [rememberTieredImageModel] hands Coil the right file per tier and never the original while a derivative is pending. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TieredImageModelTest {
    @get:Rule
    val rule = createComposeRule()

    private val graph = EffectiveNotesPath("/graph")
    private val fs = MemoryFileSystem()

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun modelFor(
        reference: String,
        tier: ImageTier,
        notes: EffectiveNotesPath? = graph,
        files: FileSystem? = fs,
    ): State<TieredImageModel> {
        lateinit var state: State<TieredImageModel>
        rule.setContent {
            CompositionLocalProvider(LocalNotesPath provides notes, LocalFileSystem provides files) {
                state = rememberTieredImageModel(reference, tier, notes)
            }
        }
        return state
    }

    @Test
    fun `original tier uses the reference as is`() {
        assertEquals(TieredImageModel("../assets/a.png", null), modelFor("../assets/a.png", ImageTier.ORIGINAL).value)
    }

    @Test
    fun `remote images are not derived`() {
        val state = modelFor("https://example.com/a.png", ImageTier.THUMBNAIL)
        rule.waitForIdle()
        assertEquals(TieredImageModel("https://example.com/a.png", null), state.value)
    }

    @Test
    fun `without a file system the reference is used`() {
        val state = modelFor("../assets/a.png", ImageTier.THUMBNAIL, files = null)
        rule.waitForIdle()
        assertEquals("../assets/a.png", state.value.model)
    }

    @Test
    fun `thumbnail tier waits for a generated thumbnail instead of loading the original`() {
        fs.files["/graph/assets/thumb.png"] = png(600, 400)
        val state = modelFor("../assets/thumb.png", ImageTier.THUMBNAIL)
        assertNull(state.value.model, "original must not be handed to Coil while the thumbnail is pending")

        rule.waitUntil(timeoutMillis = 10_000) { state.value.model != null }
        assertTrue(assertNotNull(state.value.model).endsWith(".thumb.jpg"), state.value.toString())
        assertNull(state.value.placeholder)
    }

    @Test
    fun `display tier shows the thumbnail while the display image loads`() {
        fs.files["/graph/assets/display.png"] = png(2000, 1000) // wider than the 1600 px display edge
        val state = modelFor("../assets/display.png", ImageTier.DISPLAY)

        rule.waitUntil(timeoutMillis = 10_000) { state.value.model != null }
        assertTrue(assertNotNull(state.value.model).endsWith(".display.jpg"), state.value.toString())
        assertTrue(assertNotNull(state.value.placeholder).endsWith(".thumb.jpg"), state.value.toString())
    }
}
