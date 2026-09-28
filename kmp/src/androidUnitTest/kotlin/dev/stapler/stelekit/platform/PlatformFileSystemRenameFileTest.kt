// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the BLOCKER fixed in `PlatformFileSystem.renameFile`: Android inherited
 * [FileSystem.renameFile]'s `= false` default, so every real relocate (`AtomicFileRelocationStep`)
 * failed on its very first file move during the final repoint step. Covers both backends the
 * production override dispatches on: a real (`java.io.File`) path — [DirectAccessFolder]/[AppOwned]
 * — and a `saf://` path backed by a real `DocumentsProvider` call contract — [SafFolder].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlatformFileSystemRenameFileTest {

    private lateinit var context: Context
    private lateinit var fs: PlatformFileSystem

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fs = PlatformFileSystem().apply { init(context) }
    }

    // ─── Real-path backend (DirectAccessFolder / AppOwned) ────────────────────

    @Test
    fun `renameFile moves a real file within the same directory`() {
        val graphsRoot = File(context.filesDir, "graphs").apply { mkdirs() }
        val from = File(graphsRoot, "g1/pages/Old Page.md")
        from.parentFile?.mkdirs()
        from.writeText("- hello")
        val to = File(graphsRoot, "g1/pages/New Page.md").absolutePath

        assertTrue(fs.renameFile(from.absolutePath, to), "renameFile must succeed for a same-directory real-path move")
        assertEquals("- hello", fs.readFile(to))
        assertFalse(fs.fileExists(from.absolutePath), "the source path must no longer exist after a successful rename")
        assertEquals(7L, fs.getFileSize(to), "getFileSize must reflect the moved file's real length via File.length()")
    }

    @Test
    fun `renameFile moves a real file across directories, matching AtomicFileRelocationStep's staging-to-destination repoint`() {
        val graphsRoot = File(context.filesDir, "graphs").apply { mkdirs() }
        val from = File(graphsRoot, "g1/.staging-xyz/pages/Note.md")
        from.parentFile?.mkdirs()
        from.writeText("- staged content")
        val to = File(graphsRoot, "g1/pages/Note.md").absolutePath

        assertTrue(fs.renameFile(from.absolutePath, to), "renameFile must succeed for a cross-directory real-path move")
        assertEquals("- staged content", fs.readFile(to))
        assertFalse(fs.fileExists(from.absolutePath))
    }

    // ─── SAF backend (SafFolder) ───────────────────────────────────────────────

    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:rename-test-graph"))
    private val rootDocId = "primary:rename-test-graph"
    private val graphSafPath = PlatformFileSystem.toSafRoot(treeUri)

    @Test
    fun `renameFile renames a SAF document within the same directory via DocumentsContract renameDocument`() {
        val provider = registerFakeDocumentsProvider()
        provider.addDirectory(rootDocId, "pages")
        provider.addFile("$rootDocId/pages", "Old.md", "- saf hello")

        val from = "$graphSafPath/pages/Old.md"
        val to = "$graphSafPath/pages/New.md"

        assertTrue(fs.renameFile(from, to), "renameFile must succeed via renameDocument for a same-directory SAF move")
        assertEquals("- saf hello", fs.readFile(to))
        assertFalse(fs.fileExists(from), "the old SAF document id must no longer resolve after rename")
        assertTrue(fs.fileExists(to))
    }

    @Test
    fun `renameFile moves a SAF document across directories via DocumentsContract moveDocument`() {
        val provider = registerFakeDocumentsProvider()
        provider.addDirectory(rootDocId, "staging")
        provider.addDirectory(rootDocId, "journals")
        provider.addFile("$rootDocId/staging", "Note.md", "- saf staged")

        val from = "$graphSafPath/staging/Note.md"
        val to = "$graphSafPath/journals/Note.md"

        assertTrue(fs.renameFile(from, to), "renameFile must succeed via moveDocument for a cross-directory SAF move")
        assertEquals("- saf staged", fs.readFile(to))
        assertFalse(fs.fileExists(from), "the source directory must no longer contain the moved document")
        assertEquals(12L, fs.getFileSize(to), "getFileSize must reflect the SAF document's real size via a COLUMN_SIZE query")
    }

    @Test
    fun `renameFile moves and renames a SAF document across directories in one call`() {
        val provider = registerFakeDocumentsProvider()
        provider.addDirectory(rootDocId, "staging")
        provider.addDirectory(rootDocId, "pages")
        provider.addFile("$rootDocId/staging", "Draft.md", "- draft")

        val from = "$graphSafPath/staging/Draft.md"
        val to = "$graphSafPath/pages/Published.md"

        assertTrue(fs.renameFile(from, to), "a move + rename in one call must succeed (moveDocument then renameDocument)")
        assertEquals("- draft", fs.readFile(to))
        assertFalse(fs.fileExists(from))
        assertFalse(fs.fileExists("$graphSafPath/pages/Draft.md"), "the document must end up under its new name, not the old one")
    }

    private fun registerFakeDocumentsProvider(): FakeRenameCapableProvider =
        Robolectric.buildContentProvider(FakeRenameCapableProvider::class.java)
            .create("com.android.externalstorage.documents")
            .get()

    /**
     * Minimal fake `DocumentsProvider` implementing the `ContentProvider.call()`-based
     * `DocumentsContract.renameDocument`/`moveDocument` contract, which
     * `FakeExternalStorageProvider` (the existing indexing-lag test double, `platform/testsupport/`)
     * does not implement. Document IDs follow the same `"{parentDocId}/{name}"` synthetic
     * convention [PlatformFileSystem.parseDocumentUri] already assumes real `ExternalStorageProvider`
     * document IDs to have.
     */
    class FakeRenameCapableProvider : ContentProvider() {
        private data class Entry(var docId: String, var name: String, val mimeType: String, val file: File)

        private val docs = mutableMapOf<String, Entry>()

        fun addDirectory(parentDocId: String, name: String): String {
            val docId = "$parentDocId/$name"
            docs[docId] = Entry(docId, name, DocumentsContract.Document.MIME_TYPE_DIR, File(""))
            return docId
        }

        fun addFile(parentDocId: String, name: String, content: String): String {
            val docId = "$parentDocId/$name"
            val file = File.createTempFile("fake-rename-doc-", ".tmp").apply { writeText(content) }
            docs[docId] = Entry(docId, name, "text/markdown", file)
            return docId
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: DEFAULT_PROJECTION
            val cursor = MatrixCursor(cols)
            val entry = docIdFromUri(uri)?.let { docs[it] }
            if (entry != null) {
                cursor.addRow(
                    cols.map { col ->
                        when (col) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> entry.docId
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> entry.mimeType
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> entry.file.takeIf { it.exists() }?.lastModified() ?: 0L
                            DocumentsContract.Document.COLUMN_SIZE -> entry.file.takeIf { it.exists() }?.length() ?: 0L
                            else -> null
                        }
                    },
                )
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = docIdFromUri(uri)?.let { docs[it]?.mimeType }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val entry = docIdFromUri(uri)?.let { docs[it] } ?: return null
            return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            return when (method) {
                METHOD_RENAME_DOCUMENT -> {
                    val uri = extras?.getParcelable<Uri>(EXTRA_URI) ?: return null
                    val newName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return null
                    val docId = docIdFromUri(uri) ?: return null
                    val entry = docs.remove(docId) ?: return null
                    val newDocId = "${docId.substringBeforeLast('/')}/$newName"
                    entry.docId = newDocId
                    entry.name = newName
                    docs[newDocId] = entry
                    Bundle().apply { putParcelable(EXTRA_URI, docUri(newDocId)) }
                }
                METHOD_MOVE_DOCUMENT -> {
                    val srcUri = extras?.getParcelable<Uri>(EXTRA_URI) ?: return null
                    val targetParentUri = extras.getParcelable<Uri>(EXTRA_TARGET_URI) ?: return null
                    val srcDocId = docIdFromUri(srcUri) ?: return null
                    val targetParentDocId = docIdFromUri(targetParentUri) ?: return null
                    val entry = docs.remove(srcDocId) ?: return null
                    val newDocId = "$targetParentDocId/${entry.name}"
                    entry.docId = newDocId
                    docs[newDocId] = entry
                    Bundle().apply { putParcelable(EXTRA_URI, docUri(newDocId)) }
                }
                else -> null
            }
        }

        private fun docUri(docId: String): Uri =
            Uri.parse("content://com.android.externalstorage.documents/document/${Uri.encode(docId)}")

        /** Mirrors `FakeExternalStorageProvider.documentIdFromUri` — the raw "document" path segment. */
        private fun docIdFromUri(uri: Uri): String? {
            val segments = uri.pathSegments
            val idx = segments.indexOf("document")
            if (idx < 0 || idx + 1 >= segments.size) return null
            return segments[idx + 1]
        }

        private companion object {
            val DEFAULT_PROJECTION = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )

            // DocumentsContract.METHOD_RENAME_DOCUMENT/METHOD_MOVE_DOCUMENT/EXTRA_URI/EXTRA_TARGET_URI
            // are @hide framework constants — absent from the public android.jar this module
            // compiles against, so they can't be referenced by symbol. Literal values verified via
            // `javap -private -constants` against Robolectric's real (unstripped) android-all
            // DocumentsContract.class, matching exactly what the public renameDocument()/
            // moveDocument() static helpers this test exercises pass to ContentResolver.call().
            const val METHOD_RENAME_DOCUMENT = "android:renameDocument"
            const val METHOD_MOVE_DOCUMENT = "android:moveDocument"
            const val EXTRA_URI = "uri"
            const val EXTRA_TARGET_URI = "android.content.extra.TARGET_URI"
        }
    }
}
