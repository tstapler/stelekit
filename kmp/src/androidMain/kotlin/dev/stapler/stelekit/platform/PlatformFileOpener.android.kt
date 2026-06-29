// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberPlatformFileOpener(): PlatformFileOpener {
    val context = LocalContext.current
    return remember(context) { AndroidPlatformFileOpener(context) }
}

private class AndroidPlatformFileOpener(private val context: Context) : PlatformFileOpener {

    override suspend fun openFile(absolutePath: String, mimeType: String) {
        withContext(Dispatchers.IO) {
            val uri = resolveUri(absolutePath) ?: return@withContext
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    private fun resolveUri(path: String): Uri? = when {
        path.startsWith("content://") -> Uri.parse(path)
        path.startsWith("file://") -> Uri.parse(path)
        path.startsWith("saf://") -> safToContentUri(path) ?: copyToCache(File(path.substringAfterLast('/')))?.let { providerUri(it) }
        else -> {
            // Legacy absolute POSIX path — copy to share_export cache and serve via FileProvider
            val src = File(path)
            if (!src.exists()) return null
            copyToCache(src)?.let { providerUri(it) }
        }
    }

    /**
     * Converts a saf:// path to a DocumentsContract content:// URI for use with
     * Intent.ACTION_VIEW. Falls back to null on any parsing failure (e.g. document IDs
     * containing ':' confusing the URI parser), in which case the caller falls back to
     * copy-to-cache.
     *
     * SAF path structure: `saf://{Uri.encode(treeUri)}/{relativePath}`
     * where the encoded tree URI has no unencoded '/' chars (toSafRoot uses Uri.encode).
     */
    private fun safToContentUri(safPath: String): Uri? = try {
        val withoutScheme = safPath.removePrefix("saf://")
        val slashIdx = withoutScheme.indexOf('/')
        val treePart = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
        val relativePath = if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1) else ""
        val treeUri = Uri.parse(Uri.decode(treePart))
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childDocId = if (relativePath.isEmpty()) treeDocId else "$treeDocId/$relativePath"
        DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
    } catch (_: Exception) { null }

    private fun copyToCache(src: File): File? = try {
        val cacheDir = File(context.cacheDir, "share_export").also { it.mkdirs() }
        val dest = File(cacheDir, src.name)
        src.copyTo(dest, overwrite = true)
        dest
    } catch (_: Exception) { null }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
