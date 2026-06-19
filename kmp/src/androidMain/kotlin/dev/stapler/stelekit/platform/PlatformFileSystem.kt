package dev.stapler.stelekit.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException

actual class PlatformFileSystem actual constructor() : FileSystem {
    private var context: Context? = null
    private var onPickDirectory: (suspend () -> String?)? = null
    private var onPickSaveFile: (suspend (suggestedName: String, mimeType: String) -> String?)? = null
    private var onPickFile: (suspend () -> String?)? = null
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

    // Session-scoped sets for eliminating redundant SAF existence-check IPC calls.
    // ConcurrentHashMap.newKeySet() is used because writeFile/writeFileBytes may be called
    // from concurrent coroutines (though saveMutex serializes GraphWriter calls, other
    // callers like GraphLoader do not hold the mutex).
    private val knownExistingFiles: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val knownExistingDirs: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // Write-behind queue — non-null when MANAGE_EXTERNAL_STORAGE is not granted.
    private var writeBehindQueue: WriteBehindQueue? = null

    companion object {
        private const val TAG = "PlatformFileSystem"
        const val PREFS_NAME = "stelekit_prefs"
        const val KEY_SAF_TREE_URI = "saf_tree_uri"

        // Set to false after the first invalidateStaleShadow call in this process.
        // Allows a full shadow purge on cold start so external writes (e.g. Termux)
        // are always picked up rather than served from the stale on-device cache.
        private val freshProcess = AtomicBoolean(true)

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

    /**
     * Registers a callback for picking SSH key files from device storage.
     * The callback must copy the file to app-private storage (context.getDir("ssh_keys", ...))
     * and return the app-private path. Do NOT call takePersistableUriPermission on the URI.
     */
    fun initFilePicker(onPickFile: suspend () -> String?) {
        this.onPickFile = onPickFile
    }

    override suspend fun pickFileAsync(): String? = onPickFile?.invoke()

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
            } catch (e: CancellationException) {
                throw e
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
        val lastModified: Long,
        val size: Long = 0L,
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
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val results = mutableListOf<DocumentInfo>()
        ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modCol  = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                results.add(DocumentInfo(
                    id = cursor.getString(idCol),
                    name = cursor.getString(nameCol),
                    mimeType = cursor.getString(mimeCol),
                    lastModified = cursor.getLong(modCol),
                    size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
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
    // MANAGE_EXTERNAL_STORAGE fast-path helpers
    // -------------------------------------------------------------------------

    /** True when MANAGE_EXTERNAL_STORAGE is granted — enables java.io.File fast path. */
    @Suppress("MagicNumber")
    private fun isDirectAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 30 &&
        android.os.Environment.isExternalStorageManager()

    /**
     * Resolves a saf:// path to a real filesystem path when MANAGE_EXTERNAL_STORAGE
     * is granted. Returns null if the SAF tree doc ID cannot be mapped to a real path
     * (e.g. external SD card with unknown volume UUID).
     *
     * SAF document IDs use the form "primary:relative/path" for internal storage, or
     * "<uuid>:relative/path" for removable storage. We resolve "primary:" to
     * Environment.getExternalStorageDirectory() and volumes via StorageManager.
     */
    @Suppress("MagicNumber")
    private fun resolveToRealPath(safPath: String): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val docId = treeRootDocId ?: return null
        val colonIdx = docId.indexOf(':')
        if (colonIdx < 0) return null
        val volumeName = docId.substring(0, colonIdx)
        val relativeInVolume = docId.substring(colonIdx + 1)
        val volumeRoot: java.io.File = if (volumeName == "primary") {
            android.os.Environment.getExternalStorageDirectory()
        } else {
            val ctx = context ?: return null
            val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java) ?: return null
            sm.storageVolumes.firstOrNull { it.uuid?.equals(volumeName, ignoreCase = true) == true }
                ?.directory ?: return null
        }
        // saf://... path: strip "saf://{encodedTreeUri}/{relativePath}"; relativePath is everything after first '/'
        val withoutScheme = safPath.removePrefix("saf://")
        val slashIdx = withoutScheme.indexOf('/')
        val graphRelative = if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1) else ""
        val graphRoot = java.io.File(volumeRoot, relativeInVolume)
        return if (graphRelative.isEmpty()) graphRoot.absolutePath else java.io.File(graphRoot, graphRelative).absolutePath
    }

    // -------------------------------------------------------------------------
    // FileSystem implementation — SAF paths
    // -------------------------------------------------------------------------

    actual override fun readFile(path: String): String? {
        if (!path.startsWith("saf://")) return legacyReadFile(path)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyReadFile(realPath)
        }
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

    override fun readFileBytes(path: String): ByteArray? {
        if (!path.startsWith("saf://")) return legacyReadFileBytes(path)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyReadFileBytes(realPath)
        }
        return try {
            val docUri = parseDocumentUri(path)
            context?.contentResolver?.openInputStream(docUri)?.use { it.readBytes() }
        } catch (e: Exception) { null }
    }

    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        if (!path.startsWith("saf://")) return legacyWriteFileBytes(path, data)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyWriteFileBytes(realPath, data)
        }
        return try {
            var docUri = parseDocumentUri(path)
            val ctx = context ?: return false
            if (path !in knownExistingFiles) {
                val docFile = DocumentFile.fromSingleUri(ctx, docUri)
                if (docFile == null || !docFile.exists()) {
                    val fileName = path.substringAfterLast('/')
                    val parentPath = path.substring(0, path.lastIndexOf('/'))
                    if (parentPath !in knownExistingDirs && !directoryExists(parentPath)) {
                        if (!createDirectory(parentPath)) return false
                    }
                    knownExistingDirs.add(parentPath)
                    val parentDocUri = parseDocumentUri(parentPath)
                    val mimeType = when (fileName.substringAfterLast('.').lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        else -> "application/octet-stream"
                    }
                    docUri = DocumentsContract.createDocument(
                        ctx.contentResolver, parentDocUri, mimeType, fileName
                    ) ?: return false
                }
            }
            ctx.contentResolver.openOutputStream(docUri, "wt")?.use { stream ->
                stream.write(data)
            }
            knownExistingFiles.add(path)
            true
        } catch (e: Exception) { false }
    }

    actual override fun writeFile(path: String, content: String): Boolean {
        if (path.startsWith("content://")) return contentUriWriteFile(path, content)
        if (!path.startsWith("saf://")) return legacyWriteFile(path, content)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) {
                val ok = legacyWriteFile(realPath, content)
                if (ok) updateShadow(path, content)
                return ok
            }
        }
        return try {
            var docUri = parseDocumentUri(path)
            val ctx = context ?: return false
            // Skip DocumentFile.exists() IPC for files known to exist from this session.
            // knownExistingFiles is populated on every successful write, so the second and
            // subsequent saves of a page never pay the existence-check Binder IPC cost.
            if (path !in knownExistingFiles) {
                val docFile = DocumentFile.fromSingleUri(ctx, docUri)
                if (docFile == null || !docFile.exists()) {
                    val fileName = path.substringAfterLast('/')
                    val parentPath = path.substring(0, path.lastIndexOf('/'))
                    // Use knownExistingDirs to skip the directoryExists IPC for known directories
                    // (e.g. "pages/" and "journals/" are created once and stable for the session).
                    if (parentPath !in knownExistingDirs && !directoryExists(parentPath)) {
                        if (!createDirectory(parentPath)) return false
                    }
                    knownExistingDirs.add(parentPath)
                    val parentDocUri = parseParentDocUri(path)
                    docUri = createMarkdownFile(parentDocUri, fileName) ?: return false
                }
            }
            // "wt" = write + truncate. Never use "w" alone — it does not truncate on all providers.
            ctx.contentResolver.openOutputStream(docUri, "wt")?.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).apply { write(content); flush() }
            }
            knownExistingFiles.add(path)
            true
        } catch (e: SecurityException) { Log.w(TAG, "writeFile: permission denied for $path", e); false }
        catch (e: IllegalArgumentException) { Log.w(TAG, "writeFile: invalid URI for $path", e); false }
        catch (e: Exception) { Log.w(TAG, "writeFile: unexpected error for $path", e); false }
    }

    actual override fun listFiles(path: String): List<String> {
        if (!path.startsWith("saf://")) return legacyListFiles(path)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyListFiles(realPath)
        }
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
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyListDirectories(realPath)
        }
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
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyFileExists(realPath)
        }
        return try {
            val docUri = parseDocumentUri(path)
            queryDocumentMimeType(docUri)?.let { it != DocumentsContract.Document.MIME_TYPE_DIR } == true
        } catch (e: SecurityException) { Log.w(TAG, "fileExists: permission denied for $path", e); false }
        catch (e: IllegalArgumentException) { Log.w(TAG, "fileExists: invalid URI for $path", e); false }
    }

    actual override fun directoryExists(path: String): Boolean {
        if (!path.startsWith("saf://")) return legacyDirectoryExists(path)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyDirectoryExists(realPath)
        }
        return try {
            val docUri = parseDocumentUri(path)
            queryDocumentMimeType(docUri) == DocumentsContract.Document.MIME_TYPE_DIR
        } catch (e: SecurityException) { Log.w(TAG, "directoryExists: permission denied for $path", e); false }
        catch (e: IllegalArgumentException) { Log.w(TAG, "directoryExists: invalid URI for $path", e); false }
    }

    actual override fun createDirectory(path: String): Boolean {
        if (!path.startsWith("saf://")) return legacyCreateDirectory(path)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyCreateDirectory(realPath)
        }
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
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) {
                val ok = legacyDeleteFile(realPath)
                if (ok) {
                    invalidateShadow(path)
                }
                return ok
            }
        }
        return try {
            val docUri = parseDocumentUri(path)
            val deleted = DocumentsContract.deleteDocument(
                context?.contentResolver ?: return false,
                docUri
            )
            if (deleted) knownExistingFiles.remove(path)
            deleted
        } catch (e: SecurityException) { Log.w(TAG, "deleteFile: permission denied for $path", e); false }
        catch (e: IllegalArgumentException) { Log.w(TAG, "deleteFile: invalid URI for $path", e); false }
        catch (e: Exception) { Log.w(TAG, "deleteFile: unexpected error for $path", e); false }
    }

    actual override fun getLastModifiedTime(path: String): Long? {
        if (!path.startsWith("saf://")) return legacyGetLastModifiedTime(path)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) return legacyGetLastModifiedTime(realPath)
        }
        return try {
            val docUri = parseDocumentUri(path)
            queryDocumentLastModified(docUri)
        } catch (e: SecurityException) { Log.w(TAG, "getLastModifiedTime: permission denied for $path", e); null }
        catch (e: IllegalArgumentException) { Log.w(TAG, "getLastModifiedTime: invalid URI for $path", e); null }
    }

    override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> {
        if (!path.startsWith("saf://")) return super.listFilesWithModTimes(path)
        if (isDirectAccess()) {
            val realPath = resolveToRealPath(path)
            if (realPath != null) {
                return try {
                    val dir = java.io.File(realPath)
                    if (!dir.exists() || !dir.isDirectory) return emptyList()
                    dir.listFiles()
                        ?.filter { it.isFile }
                        ?.map { it.name to it.lastModified() }
                        ?.sortedBy { it.first }
                        ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }
        }
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
            } catch (e: CancellationException) {
                throw e
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
        if (relativePath.isEmpty()) return
        // For direct-access paths, stamp shadow with the actual file mtime so
        // invalidateStale sees shadow.mtime == file.mtime (both from real FS).
        // For write-behind paths the SAF file doesn't exist yet; ShadowFlushActor
        // stamps the mtime after the flush succeeds.
        val mtime = if (isDirectAccess()) getLastModifiedTime(path) ?: 0L else 0L
        shadowCache?.update(relativePath, content, mtime)
    }

    override fun invalidateShadow(path: String) {
        if (!path.startsWith("saf://")) return
        val relativePath = relativePathFromSaf(path)
        if (relativePath.isNotEmpty()) shadowCache?.invalidate(relativePath)
        knownExistingFiles.remove(path)  // force re-check next write
    }

    override fun readShadowOnly(path: String): String? {
        if (!path.startsWith("saf://")) return null
        val relativePath = relativePathFromSaf(path)
        if (relativePath.isEmpty()) return null
        return try {
            shadowCache?.resolve(relativePath)?.readText()
        } catch (_: Exception) { null }
    }

    override fun shadowExists(path: String): Boolean {
        if (!path.startsWith("saf://")) return false
        val relativePath = relativePathFromSaf(path)
        return relativePath.isNotEmpty() && shadowCache?.resolve(relativePath) != null
    }

    /** Activate write-behind mode for SAF paths. Call with null to deactivate. */
    fun setWriteBehindQueue(queue: WriteBehindQueue?) {
        writeBehindQueue = queue
    }

    override fun markDirty(path: String, content: String): Boolean {
        val queue = writeBehindQueue ?: return false
        if (!path.startsWith("saf://")) return false
        if (isDirectAccess()) return false  // direct access is faster than write-behind
        updateShadow(path, content)
        queue.enqueue(path)
        return true
    }

    override suspend fun flushPendingWrites() {
        val queue = writeBehindQueue ?: return
        val cache = shadowCache ?: return
        ShadowFlushActor(this, cache, queue).flush()
    }

    override suspend fun invalidateStaleShadow(graphPath: String) {
        val cache = shadowCache ?: return
        if (!graphPath.startsWith("saf://")) return

        // On the first call in this process, purge the entire shadow directory.
        // External tools (e.g. Termux) can write files while the app is dead; the
        // SAF provider may return stale metadata on cold start, making mtime-based
        // invalidation unreliable. A full purge guarantees freshness at the cost of
        // one SAF-backed startup for each cold-start cycle.
        if (freshProcess.getAndSet(false)) {
            Log.d(TAG, "invalidateStaleShadow: first call after process start — purging shadow for $graphPath")
            cache.deleteAll()
            return
        }

        val pagesPath = "$graphPath/pages"
        val journalsPath = "$graphPath/journals"
        // Two IPC calls: mtime + size from a single SAF cursor per directory.
        val pagesMeta = listFilesWithMetadata(pagesPath)
        val journalsMeta = listFilesWithMetadata(journalsPath)
        cache.invalidateStale("pages", pagesMeta)
        cache.invalidateStale("journals", journalsMeta)
    }

    // Returns (fileName, lastModified, size) for each file in the directory.
    // Used by invalidateStaleShadow to detect staleness by both mtime and size.
    private fun listFilesWithMetadata(path: String): List<Triple<String, Long, Long>> {
        if (isDirectAccess()) {
            val realPath = if (path.startsWith("saf://")) resolveToRealPath(path) else path
            if (realPath != null) {
                return try {
                    val dir = File(realPath)
                    if (!dir.exists() || !dir.isDirectory) return emptyList()
                    dir.listFiles()?.filter { it.isFile }
                        ?.map { Triple(it.name, it.lastModified(), it.length()) }
                        ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }
        }
        if (!path.startsWith("saf://")) return emptyList()
        return try {
            val docUri = parseDocumentUri(path)
            queryChildren(docUri)
                .filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
                .map { Triple(it.name, it.lastModified, it.size) }
        } catch (e: SecurityException) {
            Log.w(TAG, "listFilesWithMetadata: permission denied for $path", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "listFilesWithMetadata: invalid URI for $path", e)
            emptyList()
        }
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { super.displayNameForPath(path) }
    }

    private fun displayNameForContentUri(uriString: String): String {
        return try {
            val ctx = context ?: return uriString.substringAfterLast("/")
            val uri = Uri.parse(uriString)
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uriString.substringAfterLast("/")
        } catch (e: CancellationException) {
            throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
        return try {
            DocumentFile.fromTreeUri(ctx, uri)?.name
        } catch (_: SecurityException) { null }
    }

    override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {
        val uri = treeUri ?: return
        val ctx = context ?: return
        val realPath = if (isDirectAccess()) resolveToRealPath(getDefaultGraphPath()) else null
        changeDetector = SafChangeDetector(ctx, uri, onChange, realPath).also { it.start(scope) }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun legacyReadFileBytes(path: String): ByteArray? {
        return try {
            val file = File(expandTilde(path))
            if (!file.exists() || !file.isFile) return null
            if (file.length() > maxFileSize) return null
            file.readBytes()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { null }
    }

    private fun legacyWriteFileBytes(path: String, data: ByteArray): Boolean {
        return try {
            val file = File(expandTilde(path))
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) parentDir.mkdirs()
            file.writeBytes(data)
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { false }
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
