package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.vault.CryptoLayer
import dev.stapler.stelekit.vault.JvmCryptoEngine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour of [GraphLoader] before and after a notes folder has been set. */
class GraphLoaderNotesPathTest {

    private val fileSystem = object : FileSystem {
        override fun getDefaultGraphPath(): String = "/docs"
        override fun expandTilde(path: String): String = path
        override fun pickDirectory(): String? = null
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = false
        override fun directoryExists(path: String): Boolean = true
        override fun listFiles(path: String): List<String> = emptyList()
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = true
        override fun deleteFile(path: String): Boolean = true
        override fun createDirectory(path: String): Boolean = true
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val engine = JvmCryptoEngine()
    private val layer = CryptoLayer(engine, engine.secureRandom(32))
    private val loader = GraphLoader(fileSystem, InMemoryPageRepository(), InMemoryBlockRepository())

    /** Subscribes before [emit] runs, since [GraphLoader.externalFileChanges] does not replay. */
    private suspend fun contentEmittedBy(emit: () -> Unit): String? = coroutineScope {
        val change = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(300) { loader.externalFileChanges.first() }
        }
        emit()
        change.await()?.content
    }

    @Test
    fun `createSectionJournalPage fails before any graph is loaded`() = runBlocking {
        val result = loader.createSectionJournalPage("acme", LocalDate(2026, 6, 29))

        assertTrue(result.isLeft(), "no notes folder is set, so there is nowhere to write the journal")
    }

    @Test
    fun `host bytes are dropped when a crypto layer is set without a notes folder`() = runBlocking {
        loader.setCryptoLayer(layer)
        val bytes = layer.encrypt("pages/N.md.stek", "secret".encodeToByteArray())

        assertNull(contentEmittedBy { loader.emitExternalFileChangeBytes("/g/pages/N.md.stek", bytes) })
    }

    @Test
    fun `host bytes decrypt with the notes-relative path as AAD`() = runBlocking {
        loader.setGraphPath(EffectiveNotesPath("/g/"))
        loader.setCryptoLayer(layer)
        val bytes = layer.encrypt("pages/N.md.stek", "secret".encodeToByteArray())

        assertEquals("secret", contentEmittedBy { loader.emitExternalFileChangeBytes("/g/pages/N.md.stek", bytes) })
    }

    @Test
    fun `a file outside the notes folder is decrypted with its absolute path as AAD`() = runBlocking {
        loader.setGraphPath(EffectiveNotesPath("/g"))
        loader.setCryptoLayer(layer)
        val bytes = layer.encrypt("/elsewhere/N.md.stek", "stray".encodeToByteArray())

        assertEquals("stray", contentEmittedBy { loader.emitExternalFileChangeBytes("/elsewhere/N.md.stek", bytes) })
    }
}
