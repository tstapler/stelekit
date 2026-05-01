package dev.stapler.stelekit.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.CoroutineScope

actual class PlatformFileSystem actual constructor() : FileSystem {
    private var context: Context? = null
    private var onPickDirectory: (suspend () -> String?)? = null
    private var onPickSaveFile: (suspend (suggestedName: String, mimeType: String) -> String?)? = null
    private val maxPathLength = 4096
    private val maxFileSize = 100 * 1024 * 1024
    private val homeDir: String by lazy {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.absolutePath
            ?: "/storage/emulated/0/Documents"
    }

    // SAF state
    private var treeUri: Uri? = null
    private var treeRootDocId: String? = null

    private var shadowCache: ShadowFileCache? = null
    private var changeDetector: SafChangeDetector? = null

    companion object {
        private const val TAG = "PlatformFileSystem"
        const val PREFS_NAME = "stelekit_prefs"
        const val KEY_SAF_TREE_URI = "saf_tree_uri"

        fun toSafRoot(treeUri: Uri): String = "saf://${Uri.encode(Uri.decode(treeUri.toString()))}"

        fun isSafPermissionValid(context: Context, uri: Uri): Boolean {
            val hasGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
            if (!hasGrant) return false
            return try {
                DocumentFile.fromTreeUri(context, uri)?.exists() == true
            } catch (e: SecurityException) {
                false
            }
        }
    }

    fun init(context: Context, onPickDirectory: (suspend () -> String?)? = null) {
        this.context = context
        this.onPickDirectory = onPickDirectory

        // Restore persisted SAF URI
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_SAF_TREE_URI, null)
        Log.d(TAG, "init: stored SAF URI = $uriStr")
        if (uriStr != null) {
            try {
                val uri = Uri.parse(uriStr)
                val held = context.contentResolver.persistedUriPermissions
                Log.d(TAG, "init: ${held.size} persistable grants held: ${held.map { "${it.uri} r=${it.isReadPermission} w=${it.isWritePermission}" }}")
                val valid = isSafPermissionValid(context, uri)
                Log.d(TAG, "init: isSafPermissionValid($uri) = $valid")
                if (valid) {
                    treeUri = uri
                    treeRootDocId = DocumentsContract.getTreeDocumentId(uri).also { docId ->
                        shadowCache = ShadowFileCache(context, ShadowFileCache.graphIdFor(docId))
                    }
                    Log.d(TAG, "init: SAF restored — treeRootDocId=$treeRootDocId")
                } else {
                    Log.w(TAG, "init: stored URI has no valid persistable permission — clearing")
                    prefs.edit().remove(KEY_SAF_TREE_URI).apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "init: corrupt URI string — clearing", e)
                prefs.edit().remove(KEY_SAF_TREE_URI).apply()
            }
        } else {
            Log.d(TAG, "init: no stored SAF URI — user needs to pick a folder")
        }
    }

    actual override fun getDefaultGraphPath(): String {
        val uri = treeUri ?: return "${homeDir}/stelekit"
        return toSafRoot(uri)
    }

    actual override fun expandTilde(path: String): String {
        if (path.startsWith("saf://")) return path
        return if (path.startsWith("~")) path.replaceFirst("~", homeDir) else path
    }

    // -------------------------------------------------------------------------
    // SAF URI helpers
    // -------------------------------------------------------------------------

    private data class DocumentInfo(
        val id: String,
        val name: String,
        val mimeType: String,
        val lastModified: Long
    )

    private fun parseDocumentUri(safPath: String): Uri {
        val withoutScheme = safPath.removePrefix("saf://")
        // The first unencoded '/' separates the encoded tree URI from the relative path.
        // toSafRoot() encodes every character including '/' as '%2F', so there are no
        // unencoded slashes in the tree part — slashIdx == -1 means this is the tree root.
        val slashIdx = withoutScheme.indexOf('/')
        val relativePath = if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1) else ""

        // Use the stored treeUri directly rather than reconstructing it from the saf:// path.
        // Reconstructing via Uri.decode() strips percent-encoding, which causes Android's URI
        // parser to split document IDs containing ':' or '/' (e.g. "primary:personal-wiki/logseq")
        // into multiple path segments. getTreeDocumentId() then returns only the first segment,
        // making every file/directory lookup resolve to the wrong location.
        val resolvedTreeUri = treeUri
            ?: Uri.parse(Uri.decode(withoutScheme.let {
                if (slashIdx >= 0) it.substring(0, slashIdx) else it
            }))
        val treeDocId = DocumentsContract.getTreeDocumentId(resolvedTreeUri)
        val childDocId = if (relativePath.isEmpty()) treeDocId else "$treeDocId/$relativePath"
        return DocumentsContract.buildDocumentUriUsingTree(resolvedTreeUri, childDocId)
    }

    private fun parseTreeUri(safPath: String): Uri {
        // Same encoding issue as parseDocumentUri — use stored treeUri directly.
        return treeUri ?: run {
            val withoutScheme = safPath.removePrefix("saf://")
            val slashIdx = withoutScheme.indexOf('/')
            val encodedTreePart = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
            Uri.parse(Uri.decode(encodedTreePart))
        }
    }

    /** Returns the stored tree URI (used by MainActivity to pre-fill the folder picker hint). */
    fun getStoredTreeUri(): Uri? = treeUri

    private fun parseParentDocUri(safPath: String): Uri {
        // Strip last segment to get parent path
        val lastSlash = safPath.lastIndexOf('/')
        val parentPath = safPath.substring(0, lastSlash)
        return parseDocumentUri(parentPath)
    }

    private fun queryDocumentMimeType(docUri: Uri): String? {
        val ctx = context ?: return null
        return ctx.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun queryDocumentLastModified(docUri: Uri): Long? {
        val ctx = context ?: return null
        return ctx.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
        }
    }

    private fun queryChildren(parentDocUri: Uri): List<DocumentInfo> {
        val ctx = context ?: return emptyList()
        val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentDocUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val results = mutableListOf<DocumentInfo>()
        ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modCol  = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                results.add(DocumentInfo(
                    id = cursor.getString(idCol),
                    name = cursor.getString(nameCol),
                    mimeType = cursor.getString(mimeCol),
                    lastModified = cursor.getLong(modCol)
                ))
            }
        }
        return results
    }

    private fun createMarkdownFile(parentDocUri: Uri, fileName: String): Uri? {
        val ctx = context ?: return null
        // "application/octet-stream" prevents provider from appending its own extension
        return DocumentsContract.createDocument(
            ctx.contentResolver,
            parentDocUri,
            "application/octet-stream",
            fileName  // must include ".md" extension
        )
    }

    // -------------------------------------------------------------------------
    // FileSystem implementation — SAF paths
    // -------------------------------------------------------------------------

    actual override fun readFile(path: String): String? {
        if (!path.startsWith("saf://")) return legacyReadFile(path)
        // Check shadow first — avoids Binder IPC during Phase 3 background indexing
        val relativePath = relativePathFromSaf(path)
        if (relativePath.isNotEmpty()) {
            val shadow = shadowCache?.resolve(relativePath)
            if (shadow != null) {
                try { return shadow.readText() } catch (_: Exception) {
                    Log.d(TAG, "readFile: shadow miss for $relativePath — falling back to SAF")
                }
            }
        }
        return safReadContent(path)
    }

    private fun safReadContent(path: String): String? = try {
        val docUri = parseDocumentUri(path)
        context?.contentResolver?.openInputStream(docUri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }
    } catch (e: SecurityException) { Log.w(TAG, "readFile: permission denied for $path", e); null }
    catch (e: IllegalArgumentException) { Log.w(TAG, "readFile: invalid URI for $path", e); null }
    catch (e: Exception) { Log.w(TAG, "readFile: unexpected error for $path", e); null }

    /** Extracts the relative path within the graph (e.g. "pages/Foo.md") from a saf:// URL. */
    private fun relativePathFromSaf(safPath: String): String {
        val withoutScheme = safPath.removePrefix("saf://")
        val slashIdx = withoutScheme.indexOf('/')
        return if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1) else ""
    }

    actual override fun writeFile(path: String, content: String): Boolean {
        if (path.startsWith("content://")) return contentUriWriteFile(path, content)
        if (!path.startsWith("saf://")) return legacyWriteFile(path, content)
        return try {
            var docUri = parseDocumentUri(path)
            // If file doesn't exist, create it first
            val ctx = context ?: return false
            val docFile = DocumentFile.fromSingleUri(ctx, docUri)
            if (docFile == null || !docFile.exists()) {
                val fileName = path.substringAfterLast('/')
                val parentPath = path.substring(0, path.lastIndexOf('/'))
                // Ensure the parent directory exists (e.g. "journals/" on a fresh graph)
                if (!directoryExists(parentPath)) {
                    createDirectory(parentPath)
                }
                val parentDocUri = parseParentDocUri(path)
                docUri = createMarkdownFile(parentDocUri, fileName) ?: return false
            }
            // "wt" = write + truncate. Never use "w" alone — it does not truncate on all providers.
            ctx.contentResolver.openOutputStream(docUri, "wt")?.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).apply { write(content); flush() }
            }
            true
        } catch (e: SecurityException) { Log.w(TAG, "writeFile: permission denied for $path", e); false }
        catch (e: IllegalArgumentException) { Log.w(TAG, "writeFile: invalid URI for $path", e); false }
        catch (e: Exception) { Log.w(TAG, "writeFile: unexpected error for $path", e); false }
    }

    actual override fun listFiles(path: String): List<String> {
        if (!path.startsWith("saf://")) return legacyListFiles(path)
        return try {
            val docUri = parseDocumentUri(path)
            queryChildren(docUri)
                .filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
                .map { it.name }
                .sorted()
        } catch (e: SecurityException) { Log.w(TAG, "listFiles: permission denied for $path", e); emptyList() }
        catch (e: IllegalArgumentException) { Log.w(TAG, "listFiles: invalid URI for $path", e); emptyList() }
    }

    actual override fun listDirectories(path: String): List<String> {
        if (!path.startsWith("saf://")) return legacyListDirectories(path)
        return try {
            val docUri = parseDocumentUri(path)
            queryChildren(docUri)
                .filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
                .map { it.name }
                .sorted()
        } catch (e: SecurityException) { Log.w(TAG, "listDirectories: permission denied for $path", e); emptyList() }
        catch (e: IllegalArgumentException) { Log.w(TAG, "listDirectories: invalid URI for $path", e); emptyList() }
    }

    actual override fun fileExists(path: String): Boolean {
        if (!path.startsWith("saf://")) return legacyFileExists(path)
        return try {
            val docUri = parseDocumentUri(path)
            queryDocumentMimeType(docUri)?.let { it != DocumentsContract.Document.MIME_TYPE_DIR } == true
        } catch (e: SecurityException) { Log.w(TAG, "fileExists: permission denied for $path", e); false }
        catch (e: IllegalArgumentException) { Log.w(TAG, "fileExists: invalid URI for $path", e); false }
    }

    actual override fun directoryExists(path: String): Boolean {
        if (!path.startsWith("saf://")) return legacyDirectoryExists(path)
        return try {
            val docUri = parseDocumentUri(path)
            queryDocumentMimeType(docUri) == DocumentsContract.Document.MIME_TYPE_DIR
        } catch (e: SecurityException) { Log.w(TAG, "directoryExists: permission denied for $path", e); false }
        catch (e: IllegalArgumentException) { Log.w(TAG, "directoryExists: invalid URI for $path", e); false }
    }

    actual override fun createDirectory(path: String): Boolean {
        if (!path.startsWith("saf://")) return legacyCreateDirectory(path)
        return try {
            val docUri = parseDocumentUri(path)
            val ctx = context ?: return false
            val df = DocumentFile.fromSingleUri(ctx, docUri)
            if (df?.exists() == true && df.isDirectory) return true
            val dirName = path.substringAfterLast('/')
            val parentPath = path.substring(0, path.lastIndexOf('/'))
            // Ensure ancestor directories exist first (e.g. .stelekit/ before .stelekit/pages/).
            if (!directoryExists(parentPath)) {
                if (!createDirectory(parentPath)) return false
            }
            // Create the directory inside the CORRECT parent, not at the tree root.
            // The old code used fromTreeUri(safTreeUri) which always resolved to the wiki
            // root, causing Android to auto-rename duplicate sub-dirs as "pages (1)", "pages (2)".
            val parentDocUri = parseDocumentUri(parentPath)
            DocumentsContract.createDocument(
                ctx.contentResolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                dirName,
            ) != null
        } catch (e: SecurityException) { false }
        catch (e: IllegalArgumentException) { false }
        catch (e: Exception) { false }
    }

    actual override fun deleteFile(path: String): Boolean {
        if (!path.startsWith("saf://")) return legacyDeleteFile(path)
        return try {
            val docUri = parseDocumentUri(path)
            DocumentsContract.deleteDocument(
                context?.contentResolver ?: return false,
                docUri
            )
        } catch (e: SecurityException) { Log.w(TAG, "deleteFile: permission denied for $path", e); false }
        catch (e: IllegalArgumentException) { Log.w(TAG, "deleteFile: invalid URI for $path", e); false }
        catch (e: Exception) { Log.w(TAG, "deleteFile: unexpected error for $path", e); false }
    }

    actual override fun getLastModifiedTime(path: String): Long? {
        if (!path.startsWith("saf://")) return legacyGetLastModifiedTime(path)
        return try {
            val docUri = parseDocumentUri(path)
            queryDocumentLastModified(docUri)
        } catch (e: SecurityException) { Log.w(TAG, "getLastModifiedTime: permission denied for $path", e); null }
        catch (e: IllegalArgumentException) { Log.w(TAG, "getLastModifiedTime: invalid URI for $path", e); null }
    }

    override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> {
        if (!path.startsWith("saf://")) return super.listFilesWithModTimes(path)
        return try {
            val docUri = parseDocumentUri(path)
            queryChildren(docUri)
                .filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
                .map { it.name to it.lastModified }
                .sortedBy { it.first }
        } catch (e: SecurityException) { Log.w(TAG, "listFilesWithModTimes: permission denied for $path", e); emptyList() }
        catch (e: IllegalArgumentException) { Log.w(TAG, "listFilesWithModTimes: invalid URI for $path", e); emptyList() }
    }

    actual override fun pickDirectory(): String? = null // Handled via pickDirectoryAsync on Android

    /**
     * Registers the callback that launches ACTION_CREATE_DOCUMENT and returns the chosen
     * content:// URI string (or null if cancelled). Must be called from MainActivity after
     * registering the ActivityResultLauncher.
     */
    fun initSaveFilePicker(onPickSaveFile: suspend (suggestedName: String, mimeType: String) -> String?) {
        this.onPickSaveFile = onPickSaveFile
    }

    override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? {
        return onPickSaveFile?.invoke(suggestedName, mimeType)
    }

    actual override suspend fun pickDirectoryAsync(): String? {
        val result = onPickDirectory?.invoke() ?: return null
        // Refresh internal SAF state after a successful pick so hasStoragePermission() is current.
        //
        // We do NOT reconstruct the URI from the saf:// encoding here. toSafRoot() calls
        // Uri.decode() internally, which strips percent-encoding, so Uri.parse() of the decoded
        // string produces a URI whose toString() differs from the original treeUri (e.g.
        // "primary:personal-wiki/logseq" vs "primary%3Apersonal-wiki%2Flogseq"). Android's
        // Uri.equals() compares toString() directly — no normalization — so isSafPermissionValid
        // would always return false on paths with colons or slashes in the document ID.
        //
        // Instead, read the URI that MainActivity already persisted to SharedPreferences before
        // completing the deferred. That URI is the original treeUri from the picker and was the
        // exact value passed to takePersistableUriPermission, so it matches what Android stored.
        if (result.startsWith("saf://")) {
            try {
                val ctx = context
                if (ctx != null) {
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    val uriStr = prefs.getString(KEY_SAF_TREE_URI, null)
                    if (uriStr != null) {
                        val uri = Uri.parse(uriStr)
                        if (isSafPermissionValid(ctx, uri)) {
                            treeUri = uri
                            treeRootDocId = DocumentsContract.getTreeDocumentId(uri).also { docId ->
                                shadowCache = ShadowFileCache(ctx, ShadowFileCache.graphIdFor(docId))
                            }
                            Log.d(TAG, "pickDirectoryAsync: SAF state refreshed — treeRootDocId=$treeRootDocId")
                        } else {
                            Log.w(TAG, "pickDirectoryAsync: permission not valid for $uri after pick")
                        }
                    } else {
                        Log.w(TAG, "pickDirectoryAsync: no URI in SharedPreferences after pick")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pickDirectoryAsync: failed to refresh SAF state", e)
            }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // New FileSystem interface methods (Story 3 + Story 4)
    // -------------------------------------------------------------------------

    override fun updateShadow(path: String, content: String) {
        if (!path.startsWith("saf://")) return
        val relativePath = relativePathFromSaf(path)
        if (relativePath.isNotEmpty()) shadowCache?.update(relativePath, content)
    }

    override fun invalidateShadow(path: String) {
        if (!path.startsWith("saf://")) return
        val relativePath = relativePathFromSaf(path)
        if (relativePath.isNotEmpty()) shadowCache?.invalidate(relativePath)
    }

    override suspend fun syncShadow(graphPath: String) {
        val cache = shadowCache ?: return
        if (!graphPath.startsWith("saf://")) return
        val pagesPath = "$graphPath/pages"
        val journalsPath = "$graphPath/journals"
        val pagesMods = listFilesWithModTimes(pagesPath)
        val journalsMods = listFilesWithModTimes(journalsPath)
        cache.syncFromSaf("pages", pagesMods) { fileName -> safReadContent("$pagesPath/$fileName") }
        cache.syncFromSaf("journals", journalsMods) { fileName -> safReadContent("$journalsPath/$fileName") }
    }

    override fun hasStoragePermission(): Boolean {
        val uri = treeUri ?: return false
        return isSafPermissionValid(context ?: return false, uri)
    }

    override fun displayNameForPath(path: String): String {
        if (path.startsWith("content://")) return displayNameForContentUri(path)
        if (!path.startsWith("saf://")) return super.displayNameForPath(path)
        return try {
            val ctx = context ?: return super.displayNameForPath(path)
            val resolvedTreeUri = treeUri ?: return super.displayNameForPath(path)
            DocumentFile.fromTreeUri(ctx, resolvedTreeUri)?.name
                ?: super.displayNameForPath(path)
        } catch (_: Exception) { super.displayNameForPath(path) }
    }

    private fun displayNameForContentUri(uriString: String): String {
        return try {
            val ctx = context ?: return uriString.substringAfterLast("/")
            val uri = Uri.parse(uriString)
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uriString.substringAfterLast("/")
        } catch (_: Exception) { uriString.substringAfterLast("/") }
    }

    override fun getLibraryDisplayName(): String? {
        val ctx = context ?: return null
        val uri = treeUri
        if (uri == null) {
            // Try to get name from stored URI even if permission is revoked
            val str = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SAF_TREE_URI, null) ?: return null
            return try {
                DocumentFile.fromTreeUri(ctx, Uri.parse(str))?.name
            } catch (_: Exception) { null }
        }
        return try {
            DocumentFile.fromTreeUri(ctx, uri)?.name
        } catch (_: SecurityException) { null }
    }

    override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {
        val uri = treeUri ?: return
        val ctx = context ?: return
        changeDetector = SafChangeDetector(ctx, uri, onChange).also { it.start(scope) }
    }

    override fun stopExternalChangeDetection() {
        changeDetector?.stop()
        changeDetector = null
    }

    // -------------------------------------------------------------------------
    // Content URI write helper (ACTION_CREATE_DOCUMENT results)
    // -------------------------------------------------------------------------

    private fun contentUriWriteFile(uriString: String, content: String): Boolean {
        return try {
            val ctx = context ?: return false
            val uri = Uri.parse(uriString)
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { stream -> // "wt" = write-truncate
                stream.bufferedWriter(Charsets.UTF_8).apply { write(content); flush() }
            }
            true
        } catch (e: SecurityException) { Log.w(TAG, "contentUriWriteFile: permission denied", e); false }
        catch (e: Exception) { Log.w(TAG, "contentUriWriteFile: error writing to $uriString", e); false }
    }

    // -------------------------------------------------------------------------
    // Legacy File-based helpers (unchanged from original; renamed from public methods)
    // -------------------------------------------------------------------------

    private fun legacyReadFile(path: String): String? {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            val file = File(validatedPath)
            if (!file.exists() || !file.isFile) return null
            if (file.length() > maxFileSize) return null
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun legacyWriteFile(path: String, content: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            if (content.length > maxFileSize) return false
            val file = File(validatedPath)
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs()
            }
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun legacyListFiles(path: String): List<String> {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            val directory = File(validatedPath)
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun legacyListDirectories(path: String): List<String> {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            val directory = File(validatedPath)
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun legacyFileExists(path: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            val file = File(validatedPath)
            file.exists() && file.isFile
        } catch (e: Exception) {
            false
        }
    }

    private fun legacyDirectoryExists(path: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            val file = File(validatedPath)
            file.exists() && file.isDirectory
        } catch (e: Exception) {
            false
        }
    }

    private fun legacyCreateDirectory(path: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            val file = File(validatedPath)
            if (file.exists()) {
                return file.isDirectory
            }
            file.mkdirs()
        } catch (e: Exception) {
            false
        }
    }

    private fun legacyDeleteFile(path: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            val file = File(validatedPath)
            if (!file.exists()) return true
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (e: Exception) {
            false
        }
    }

    private fun legacyGetLastModifiedTime(path: String): Long? {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validateLegacyPath(expandedPath)
            val file = File(validatedPath)
            if (file.exists() && file.isFile) {
                file.lastModified()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun validateLegacyPath(path: String): String {
        require(path.length <= maxPathLength) { "Path exceeds maximum length" }
        require(!path.contains('\u0000')) { "Path contains null bytes" }
        val normalized = path.replace(Regex("[/\\\\]+"), "/")
        val expandedPath = expandTilde(normalized)
        val canonicalPath = File(expandedPath).canonicalPath
        // Enforce containment within the public Documents directory to prevent path traversal
        val homePath = File(homeDir).canonicalPath
        require(canonicalPath.startsWith(homePath)) { "Path must be within the allowed directory" }
        return canonicalPath
    }
}
