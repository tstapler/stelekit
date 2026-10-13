// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

/** SHA-256 object id of LFS content: exactly 64 lowercase hex characters. */
@kotlin.jvm.JvmInline
value class LfsOid(val hex: String) {
    init {
        require(isValid(hex)) { "LFS oid must be 64 lowercase hex characters" }
    }

    override fun toString(): String = hex

    companion object {
        const val LENGTH = 64

        fun isValid(candidate: String): Boolean =
            candidate.length == LENGTH && candidate.all { it in '0'..'9' || it in 'a'..'f' }

        fun parseOrNull(candidate: String): LfsOid? = if (isValid(candidate)) LfsOid(candidate) else null
    }
}

/** A parsed Git LFS pointer file (https://github.com/git-lfs/git-lfs/blob/main/docs/spec.md). */
data class LfsPointer(
    val oid: LfsOid,
    val size: Long,
    /** `ext-N-name` lines as (key, value), kept in file order (which the spec makes sorted). */
    val extensions: List<Pair<String, String>> = emptyList(),
) {
    init {
        require(size >= 0) { "LFS pointer size must be non-negative" }
    }
}

sealed interface LfsPointerParse {
    data class Valid(val pointer: LfsPointer) : LfsPointerParse

    /** Starts like a pointer (version line) but violates the spec; never commit it as is. */
    data class Corrupt(val reason: String) : LfsPointerParse

    data object NotAPointer : LfsPointerParse
}

/** Spec-strict pointer parser and serialiser; the single pointer model on every platform. */
object LfsPointerCodec {
    const val CANONICAL_VERSION = "https://git-lfs.github.com/spec/v1"

    /** Accepted on read only (git-lfs `pointer.go`); INFERRED, never written. */
    private val LEGACY_VERSIONS = setOf("https://hawser.github.com/spec/v1", "http://git-media.io/v/2")

    /** The spec caps a pointer at 1024 bytes; a conforming one is therefore at most 1023 bytes. */
    const val MAX_POINTER_BYTES = 1024

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun parse(bytes: ByteArray): LfsPointerParse {
        val startsWithBom = bytes.size >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]
        val body = if (startsWithBom) bytes.copyOfRange(3, bytes.size) else bytes
        if (!looksLikeVersionLine(body)) return LfsPointerParse.NotAPointer
        if (startsWithBom) return LfsPointerParse.Corrupt("BOM")
        if (bytes.size >= MAX_POINTER_BYTES) return LfsPointerParse.Corrupt("TOO_LARGE")
        if (bytes.any { it == 0.toByte() }) return LfsPointerParse.Corrupt("NUL")
        if (bytes.any { it == '\r'.code.toByte() }) return LfsPointerParse.Corrupt("CRLF")
        val text = decodeUtf8Strict(bytes) ?: return LfsPointerParse.Corrupt("NOT_UTF8")
        if (!text.endsWith("\n")) return LfsPointerParse.Corrupt("NO_TRAILING_NEWLINE")

        val lines = text.removeSuffix("\n").split("\n")
        val pairs = ArrayList<Pair<String, String>>(lines.size)
        for (line in lines) {
            val space = line.indexOf(' ')
            if (space <= 0) return LfsPointerParse.Corrupt("MALFORMED_LINE")
            val key = line.substring(0, space)
            val value = line.substring(space + 1)
            if (!key.all(::isKeyChar)) return LfsPointerParse.Corrupt("BAD_KEY")
            pairs += key to value
        }
        if (pairs.first().first != "version") return LfsPointerParse.Corrupt("VERSION_NOT_FIRST")
        val version = pairs.first().second
        if (version != CANONICAL_VERSION && version !in LEGACY_VERSIONS) return LfsPointerParse.Corrupt("BAD_VERSION")

        val rest = pairs.drop(1)
        if (rest.map { it.first }.toSet().size != rest.size || rest.any { it.first == "version" }) {
            return LfsPointerParse.Corrupt("DUPLICATE_KEY")
        }
        if (rest.map { it.first } != rest.map { it.first }.sorted()) return LfsPointerParse.Corrupt("UNSORTED_KEYS")

        val oidValue = rest.firstOrNull { it.first == "oid" }?.second ?: return LfsPointerParse.Corrupt("MISSING_OID")
        if (!oidValue.startsWith("sha256:")) return LfsPointerParse.Corrupt("OID_METHOD")
        val oid = LfsOid.parseOrNull(oidValue.removePrefix("sha256:")) ?: return LfsPointerParse.Corrupt("BAD_OID")
        val sizeValue = rest.firstOrNull { it.first == "size" }?.second ?: return LfsPointerParse.Corrupt("MISSING_SIZE")
        if (sizeValue.isEmpty() || !sizeValue.all { it in '0'..'9' }) return LfsPointerParse.Corrupt("BAD_SIZE")
        val size = sizeValue.toLongOrNull() ?: return LfsPointerParse.Corrupt("BAD_SIZE")

        // Unknown keys are legal (forward compatibility) and are intentionally not modelled.
        val extensions = rest.filter { it.first.startsWith("ext-") }
        return LfsPointerParse.Valid(LfsPointer(oid, size, extensions))
    }

    /** Canonical bytes: version first, then keys sorted (`ext-N-name` lines sort before `oid`). */
    fun serialize(pointer: LfsPointer): ByteArray {
        val lines = buildList {
            addAll(pointer.extensions)
            add("oid" to "sha256:${pointer.oid.hex}")
            add("size" to pointer.size.toString())
        }.sortedBy { it.first }
        val text = buildString {
            append("version ").append(CANONICAL_VERSION).append('\n')
            for ((k, v) in lines) append(k).append(' ').append(v).append('\n')
        }
        return text.encodeToByteArray()
    }

    /** True when [head] begins (case-insensitively) with the pointer version line. */
    private fun looksLikeVersionLine(head: ByteArray): Boolean {
        val prefix = "version "
        if (head.size < prefix.length) return false
        for (i in prefix.indices) {
            if (head[i].toInt().toChar().lowercaseChar() != prefix[i]) return false
        }
        val firstLineEnd = head.indexOfFirst { it == '\n'.code.toByte() }.let { if (it < 0) head.size else it }
        val line = head.copyOfRange(0, minOf(firstLineEnd, 256)).decodeToString().trimEnd('\r').lowercase()
        return "git-lfs" in line || "hawser" in line || "git-media" in line
    }

    private fun isKeyChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-'

    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        val decoded = bytes.decodeToString()
        // Lenient decode substitutes U+FFFD for invalid sequences; a round trip exposes them.
        return if (decoded.encodeToByteArray().contentEquals(bytes)) decoded else null
    }
}

/** Cheap pre-check so callers never read a large file body to decide "is this a pointer". */
object LfsPointerSniffer {
    fun looksLikePointer(size: Long, head: ByteArray?): Boolean {
        if (size >= LfsPointerCodec.MAX_POINTER_BYTES || size <= 0) return false
        if (head == null) return false
        return LfsPointerCodec.parse(head) !is LfsPointerParse.NotAPointer
    }
}
