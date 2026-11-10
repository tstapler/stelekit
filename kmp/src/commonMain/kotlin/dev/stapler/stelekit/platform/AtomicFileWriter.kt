package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/** How [AtomicFileWriter] can replace a file on a given path. */
enum class AtomicWriteMode {
    /** Temp file, then one atomic swap: a crash leaves the old or the new file complete. */
    ATOMIC_REPLACE,

    /** Temp file, read-back check, delete, rename. A crash between delete and rename leaves the destination absent. */
    TEMP_RENAME,

    /** Web and iOS: no safe replace exists. Writes return `Left(AtomicWriteUnsupported)`; there is no direct-overwrite fallback. */
    UNSUPPORTED,
}

/**
 * Replaces a file's bytes via a temp file next to it (JVM and Android; refuses elsewhere, see [AtomicWriteMode]).
 *
 * Extracted from `MarkdownTargetWriter.replaceFile`: symlink refusal, a compare-and-swap [write] `precondition`
 * between the temp write and the replace, restore of `original` after a failed rename, and a stale-temp sweep.
 * In [AtomicWriteMode.TEMP_RENAME] the delete-then-rename window is a real crash risk (the destination is
 * left absent and the new bytes stay in the temp file until swept); callers must treat Absent as recoverable.
 *
 * @param canonicalize symlink-resolving path function, deliberately without a default so protection is never
 *   silently off. Platforms without symlinks pass an identity function explicitly.
 * @param safAtomicReplaceVerified whether atomic replace is verified on `saf://`/`content://` paths; the
 *   `merge` layer owns that constant and passes it in, so `platform` imports nothing from `merge`.
 */
class AtomicFileWriter(
    private val fs: FileSystem,
    private val canonicalize: (String) -> String,
    private val tempSuffix: String,
    private val safAtomicReplaceVerified: Boolean = false,
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val sweeper = StaleTempSweeper(fs, tempSuffix, nowMillis)

    fun mode(path: String): AtomicWriteMode = mode(fs, path, safAtomicReplaceVerified)

    /**
     * Writes [bytes] to [path]. [original] is the content the caller computed [bytes] from (null: [path] must
     * not exist); it is restored if the final rename fails. [precondition] runs after the temp write and returns
     * `true` while the caller's view of [path] is still current; `false` yields `Left(MergeError.Retryable)`.
     */
    fun write(
        path: String,
        bytes: ByteArray,
        original: ByteArray? = null,
        precondition: (() -> Either<DomainError, Boolean>)? = null,
    ): Either<DomainError, Unit> {
        val mode = mode(path)
        if (mode == AtomicWriteMode.UNSUPPORTED) return DomainError.FileSystemError.AtomicWriteUnsupported(path).left()
        if (isSymlinkFile(path, canonicalize)) return DomainError.FileSystemError.SymlinkRefused(path).left()
        sweeper.sweepOnce(path.substringBeforeLast('/'))
        val tmp = "$path.${UuidGenerator.generateV7()}$tempSuffix"
        var deletedOriginal = false
        try {
            if (!fs.writeFileBytes(tmp, bytes)) return failAndClean(tmp, path, "temp write failed")
            if (precondition != null) {
                val current = when (val r = precondition()) {
                    is Either.Left -> {
                        runCatching { fs.deleteFile(tmp) }
                        return r
                    }
                    is Either.Right -> r.value
                }
                if (!current) {
                    runCatching { fs.deleteFile(tmp) }
                    return DomainError.MergeError.Retryable("The file changed while it was being written; try again.").left()
                }
            }
            if (mode == AtomicWriteMode.ATOMIC_REPLACE) {
                if (!fs.replaceFileAtomically(tmp, path)) return failAndClean(tmp, path, "atomic replace failed")
                return Unit.right()
            }
            val readBack = fs.readFileBytes(tmp)
            if (readBack == null || !readBack.contentEquals(bytes)) return failAndClean(tmp, path, "read-back verification failed")
            if (original != null) {
                deletedOriginal = fs.deleteFile(path)
                if (!deletedOriginal) return failAndClean(tmp, path, "could not replace existing file")
            }
            if (!fs.renameFile(tmp, path)) {
                if (original != null) fs.writeFileBytes(path, original)
                return failAndClean(tmp, path, "rename failed")
            }
            return Unit.right()
        } catch (e: Throwable) {
            if (deletedOriginal && original != null && !fs.fileExists(path)) runCatching { fs.writeFileBytes(path, original) }
            runCatching { fs.deleteFile(tmp) }
            if (e is CancellationException) throw e
            if (e is UnsupportedOperationException) return DomainError.FileSystemError.AtomicWriteUnsupported(path).left()
            return DomainError.FileSystemError.WriteFailed(path, e.message ?: "write failed").left()
        }
    }

    private fun failAndClean(tmp: String, path: String, why: String): Either<DomainError, Unit> {
        runCatching { fs.deleteFile(tmp) }
        return DomainError.FileSystemError.WriteFailed(path, why).left()
    }

    companion object {
        fun mode(fs: FileSystem, path: String, safAtomicReplaceVerified: Boolean = false): AtomicWriteMode = when {
            !fs.supportsTempRenameWrites -> AtomicWriteMode.UNSUPPORTED
            fs.supportsAtomicReplace(path) && (!path.isSafPath() || safAtomicReplaceVerified) -> AtomicWriteMode.ATOMIC_REPLACE
            else -> AtomicWriteMode.TEMP_RENAME
        }

        private fun String.isSafPath() = startsWith("saf://") || startsWith("content://")
    }
}

/** A page file that is itself a link resolves elsewhere than its directory entry; the swap would sever it. */
internal fun isSymlinkFile(path: String, canonicalize: (String) -> String): Boolean {
    val dir = path.substringBeforeLast('/')
    return canonicalize(path) != canonicalize(dir).trimEnd('/') + "/" + path.substringAfterLast('/')
}
