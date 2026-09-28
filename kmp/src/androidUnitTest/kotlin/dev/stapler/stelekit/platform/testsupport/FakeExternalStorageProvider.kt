package dev.stapler.stelekit.platform.testsupport

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File

/**
 * A fake SAF `DocumentsProvider` test double that models **eventual consistency** between
 * a sync client writing files to shared storage and the SAF provider's index catching up.
 *
 * Real Android `DocumentsProvider`s (e.g. `ExternalStorageProvider`) can lag behind a write
 * made by another process: a file genuinely exists on disk, but a `ContentResolver.query()`
 * for that document (single-document existence check, or a parent's children listing) does
 * not yet return it. This provider keeps two views of the same document set:
 *
 *  - "ground truth" ([allDocs]) — every document that has been "written" via [addFileHidden]
 *    / [addFileVisible] / [addDirectory].
 *  - "indexed"/"visible" — the subset of ground truth that [query] currently returns, tracked
 *    per-entry via [Entry.visible].
 *
 * [addFileHidden] adds a document to ground truth without making it visible (simulating a
 * write from another app that the provider has not indexed yet). [advanceIndex] /
 * [revealFile] flip pending entries to visible (simulating the provider catching up).
 *
 * Document IDs follow the real `ExternalStorageProvider` convention
 * (`"{volumeId}:{relativePath}"`, e.g. `"primary:test-graph/journals/2026_08_23.md"`) by
 * simple parent-relative concatenation (`"$parentDocId/$name"`), matching exactly what
 * [dev.stapler.stelekit.platform.PlatformFileSystem]'s `parseDocumentUri` synthetically
 * constructs — so this fake and the production code agree on document IDs without any
 * changes to production code.
 */
class FakeExternalStorageProvider : ContentProvider() {

    private data class Entry(
        val docId: String,
        val name: String,
        val mimeType: String,
        var lastModified: Long,
        var size: Long,
        var visible: Boolean,
        var content: ByteArray = ByteArray(0),
    )

    // Ground truth: every document ever added, indexed or not.
    private val allDocs = mutableMapOf<String, Entry>()

    // Ground truth parent -> children docId list (independent of visibility).
    private val children = mutableMapOf<String, MutableList<String>>()

    override fun onCreate(): Boolean = true

    // -------------------------------------------------------------------------
    // Test control surface
    // -------------------------------------------------------------------------

    /** Adds an always-visible directory. Directory listing lag is not modeled — only files. */
    fun addDirectory(parentDocId: String, name: String): String {
        val docId = "$parentDocId/$name"
        allDocs[docId] = Entry(
            docId = docId,
            name = name,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            lastModified = System.currentTimeMillis(),
            size = 0L,
            visible = true,
        )
        children.getOrPut(parentDocId) { mutableListOf() }.add(docId)
        return docId
    }

    /**
     * Adds a file to ground truth WITHOUT making it visible to queries yet — simulates a
     * write from another app (a sync client) that the SAF provider has not indexed yet.
     */
    fun addFileHidden(
        parentDocId: String,
        name: String,
        content: String = "",
        mimeType: String = "text/markdown",
    ): String {
        val docId = "$parentDocId/$name"
        val bytes = content.toByteArray(Charsets.UTF_8)
        allDocs[docId] = Entry(
            docId = docId,
            name = name,
            mimeType = mimeType,
            lastModified = System.currentTimeMillis(),
            size = bytes.size.toLong(),
            visible = false,
            content = bytes,
        )
        children.getOrPut(parentDocId) { mutableListOf() }.add(docId)
        return docId
    }

    /** Adds a file that is immediately indexed/visible — the non-lagging baseline case. */
    fun addFileVisible(
        parentDocId: String,
        name: String,
        content: String = "",
        mimeType: String = "text/markdown",
    ): String {
        val docId = addFileHidden(parentDocId, name, content, mimeType)
        allDocs.getValue(docId).visible = true
        return docId
    }

    /** Makes a single pending document visible — simulates the provider indexing just that file. */
    fun revealFile(docId: String) {
        allDocs[docId]?.visible = true
    }

    /** Makes every pending ground-truth document visible — simulates the provider fully catching up. */
    fun advanceIndex() {
        allDocs.values.forEach { it.visible = true }
    }

    // -------------------------------------------------------------------------
    // ContentProvider / DocumentsProvider query contract
    // -------------------------------------------------------------------------

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(cols)
        val docId = documentIdFromUri(uri) ?: return cursor

        if (isChildrenQuery(uri)) {
            for (childId in children[docId].orEmpty()) {
                val entry = allDocs[childId] ?: continue
                if (entry.visible) addRow(cursor, cols, entry)
            }
        } else {
            val entry = allDocs[docId]
            if (entry != null && entry.visible) addRow(cursor, cols, entry)
        }
        return cursor
    }

    /**
     * Serves `ContentResolver.openInputStream()` (used by `PlatformFileSystem.readFile`).
     * A hidden (not-yet-indexed) document is not readable, matching real SAF behavior.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val docId = documentIdFromUri(uri) ?: return null
        val entry = allDocs[docId] ?: return null
        if (!entry.visible) return null
        val tmp = File.createTempFile("fake-saf-", ".tmp")
        tmp.deleteOnExit()
        tmp.writeBytes(entry.content)
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = documentIdFromUri(uri)?.let { allDocs[it]?.mimeType }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    // -------------------------------------------------------------------------
    // URI parsing helpers
    // -------------------------------------------------------------------------

    /**
     * Both [DocumentsContract.buildDocumentUriUsingTree] and
     * [DocumentsContract.buildChildDocumentsUriUsingTree] produce paths of the shape
     * `.../document/{docId}` (optionally followed by `/children`). `Uri.getPathSegments()`
     * already URL-decodes each segment, so the raw segment following "document" is the
     * document ID as-is — no extra decoding needed.
     */
    private fun documentIdFromUri(uri: Uri): String? {
        val segments = uri.pathSegments
        val docIdx = segments.indexOf("document")
        if (docIdx < 0 || docIdx + 1 >= segments.size) return null
        return segments[docIdx + 1]
    }

    private fun isChildrenQuery(uri: Uri): Boolean = uri.pathSegments.lastOrNull() == "children"

    private fun addRow(cursor: MatrixCursor, cols: Array<String>, entry: Entry) {
        val row = cols.map { col ->
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> entry.docId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> entry.mimeType
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> entry.lastModified
                DocumentsContract.Document.COLUMN_SIZE -> entry.size
                else -> null
            }
        }
        cursor.addRow(row)
    }

    private companion object {
        val DEFAULT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
