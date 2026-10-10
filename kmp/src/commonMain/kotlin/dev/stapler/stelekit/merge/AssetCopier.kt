package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.PageFileResolver
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.util.ContentHasher

/**
 * [page] has asset links rewritten for renamed files. [assetsCopied] counts files written (or that
 * would be, on a dry run); [assetsRenamed] those placed under `name-<hash8>.ext`.
 */
data class AssetCopyResult(
    val page: MergePage,
    val assetsCopied: Int,
    val assetsRenamed: Int,
    val warnings: List<String>,
)

/**
 * Copies `../assets/...` files referenced by a page into the target graph's `assets/` folder,
 * deduplicated by content hash. Same name + different bytes -> `name-<hash8>.ext` and the link is rewritten.
 * A missing or unsafe source asset is a warning; the page still copies.
 *
 * @param canonicalize symlink-resolving path function; identity default checks lexically only.
 */
class AssetCopier(
    private val fs: FileSystem,
    private val canonicalize: (String) -> String = { it },
) {
    /** Pure: asset paths (relative to `assets/`) referenced from [content]. */
    fun assetRefs(content: String): List<String> = LINK.findAll(content).map { it.groupValues[2] }.toList()

    /**
     * [dryRun] resolves outcomes and counts without writing, for `plan()`; `apply()` passes false.
     * Decisions are cached per call, so one asset linked twice is copied once.
     */
    fun copy(
        page: MergePage,
        sourceRoot: String,
        targetRoot: String,
        dryRun: Boolean = false,
    ): Either<DomainError, AssetCopyResult> {
        val srcAssets = "${sourceRoot.trimEnd('/')}/assets"
        val dstAssets = "${targetRoot.trimEnd('/')}/assets"
        val decisions = HashMap<String, Decision>()
        val warnings = mutableListOf<String>()
        var copied = 0
        var renamed = 0

        fun decide(rel: String): Either<DomainError, Decision> {
            decisions[rel]?.let { return it.right() }
            val d = resolve(rel, srcAssets, dstAssets, dryRun).fold({ return it.left() }, { it })
            decisions[rel] = d
            when (d) {
                is Decision.Missing -> warnings += d.warning
                is Decision.Place -> {
                    if (d.write) copied++
                    if (d.finalRel != rel) renamed++
                }
            }
            return d.right()
        }

        fun rewrite(content: String): Either<DomainError, String> {
            if (!content.contains("assets/")) return content.right()
            val sb = StringBuilder()
            var last = 0
            for (m in LINK.findAll(content)) {
                val rel = m.groupValues[2]
                val d = decide(rel).fold({ return it.left() }, { it })
                sb.append(content, last, m.range.first)
                sb.append(if (d is Decision.Place && d.finalRel != rel) m.value.removeSuffix(rel) + d.finalRel else m.value)
                last = m.range.last + 1
            }
            return sb.append(content, last, content.length).toString().right()
        }

        fun block(b: MergeBlock): Either<DomainError, MergeBlock> {
            val content = rewrite(b.content).fold({ return it.left() }, { it })
            val children = b.children.map { c -> block(c).fold({ return it.left() }, { it }) }
            return b.copy(content = content, children = children).right()
        }

        val blocks = page.blocks.map { b -> block(b).fold({ return it.left() }, { it }) }
        return AssetCopyResult(page.copy(blocks = blocks), copied, renamed, warnings).right()
    }

    private sealed interface Decision {
        data class Missing(val warning: String) : Decision
        data class Place(val finalRel: String, val write: Boolean) : Decision
    }

    private fun resolve(rel: String, srcAssets: String, dstAssets: String, dryRun: Boolean): Either<DomainError, Decision> {
        val srcPath = "$srcAssets/$rel"
        if (!contained(srcAssets, srcPath)) {
            return Decision.Missing("asset path escapes the assets folder: $rel").right()
        }
        val bytes = try {
            fs.readFileBytes(srcPath)
        } catch (_: UnsupportedOperationException) {
            null
        } ?: return Decision.Missing("source asset missing: $rel").right()
        return placeBytes(rel, bytes, dstAssets, dryRun)
    }

    /**
     * Stores in-memory [bytes] as `assets/<rel>` of [targetRoot] (a share's image), with the same
     * containment and hash-dedupe as [copy]. Returns the final path relative to `assets/`.
     */
    fun store(rel: String, bytes: ByteArray, targetRoot: String): Either<DomainError, String> {
        val dstAssets = "${targetRoot.trimEnd('/')}/assets"
        return placeBytes(rel, bytes, dstAssets, dryRun = false).flatMap { d ->
            when (d) {
                is Decision.Place -> d.finalRel.right()
                is Decision.Missing -> DomainError.FileSystemError.WriteFailed("$dstAssets/$rel", d.warning).left()
            }
        }
    }

    private fun placeBytes(rel: String, bytes: ByteArray, dstAssets: String, dryRun: Boolean): Either<DomainError, Decision> {
        val dstPath = "$dstAssets/$rel"
        if (!contained(dstAssets, dstPath)) {
            return Decision.Missing("asset path escapes the assets folder: $rel").right()
        }
        val hash = ContentHasher.sha256(bytes)

        val existing = if (fs.fileExists(dstPath)) fs.readFileBytes(dstPath) else null
        if (existing == null) return place(rel, dstPath, bytes, dryRun)
        if (ContentHasher.sha256(existing) == hash) return Decision.Place(rel, write = false).right()

        val renamedRel = withHash(rel, hash.take(HASH_LEN))
        val renamedPath = "$dstAssets/$renamedRel"
        if (!contained(dstAssets, renamedPath)) {
            return Decision.Missing("asset path escapes the assets folder: $rel").right()
        }
        val renamedExisting = if (fs.fileExists(renamedPath)) fs.readFileBytes(renamedPath) else null
        if (renamedExisting != null && ContentHasher.sha256(renamedExisting) == hash) {
            return Decision.Place(renamedRel, write = false).right()
        }
        return place(renamedRel, renamedPath, bytes, dryRun)
    }

    private fun place(rel: String, dstPath: String, bytes: ByteArray, dryRun: Boolean): Either<DomainError, Decision> {
        if (!dryRun) {
            fs.createDirectory(dstPath.substringBeforeLast('/'))
            if (!fs.writeFileBytes(dstPath, bytes)) {
                return DomainError.FileSystemError.WriteFailed(dstPath, "asset write failed").left()
            }
            val written = fs.readFileBytes(dstPath)
            if (written == null || ContentHasher.sha256(written) != ContentHasher.sha256(bytes)) {
                return DomainError.FileSystemError.WriteFailed(dstPath, "asset verification failed").left()
            }
        }
        return Decision.Place(rel, write = true).right()
    }

    private fun contained(root: String, path: String) =
        PageFileResolver.isWithin(root, path) && PageFileResolver.isWithin(canonicalize(root), canonicalize(path))

    private fun withHash(rel: String, hash8: String): String {
        val dir = rel.substringBeforeLast('/', "")
        val file = rel.substringAfterLast('/')
        val dot = file.lastIndexOf('.')
        val renamed = if (dot > 0) "${file.substring(0, dot)}-$hash8${file.substring(dot)}" else "$file-$hash8"
        return if (dir.isEmpty()) renamed else "$dir/$renamed"
    }

    private companion object {
        const val HASH_LEN = 8

        /** `[text](../assets/<path>)` or `![alt](../assets/<path>)`; group 2 is the path under assets/. */
        val LINK = Regex("""(!?\[[^\]]*]\(\s*\.\./assets/)([^)\s]+)""")
    }
}
