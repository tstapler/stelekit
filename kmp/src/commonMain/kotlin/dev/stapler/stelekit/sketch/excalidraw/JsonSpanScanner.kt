// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The one place JSON text is decoded; a test substitutes a counting decoder to prove the per-call size bound. */
fun interface SpanDecoder {
    fun decode(text: String): JsonElement

    companion object {
        val Default = SpanDecoder { Json.parseToJsonElement(it) }
    }
}

/** A half-open byte range `[start, end)` of an [ImportSource]. */
class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/** One span of the file: decodable, or dropped before any decode with the reason. */
sealed interface SpanRef {
    class Ok(val range: ByteRange) : SpanRef
    class Dropped(val reason: ImportReason, val range: ByteRange) : SpanRef
}

/** One entry of the `files` map, scanned but not decoded. */
class FileScan(
    val id: String,
    /** False when the entry value is not a JSON object (kept as a plain span for oracle equality). */
    val isObject: Boolean,
    /** Every member other than a raw `dataURL`, as (unescaped key, span). A non-object entry has one member with an empty key. */
    val members: List<Pair<String, SpanRef>>,
    /** The raw bytes between the quotes of a string `dataURL`, never JSON-decoded. */
    val dataUrl: ByteRange?,
    /** True when `dataUrl` carries an escape other than `\/`; the image becomes a `DataUrlEscaped` placeholder. */
    val dataUrlEscaped: Boolean,
)

sealed interface Member {
    /** A top-level value kept as one span (an `elements` or `files` value of the wrong JSON type is one too). */
    class Value(val span: SpanRef) : Member

    /** The `elements` array (or a library's item array), one span per element. */
    class Elements(val spans: List<SpanRef>) : Member

    class Files(val entries: List<FileScan>) : Member
}

/** The result of one scan: top-level members by unescaped key, last duplicate wins. */
class SceneScan(val members: Map<String, Member>)

/**
 * Byte-level splitter that cuts an Excalidraw file into JSON spans so no single decode call is larger than
 * `SceneLimits.maxImportSpanBytes` (Story 5.1.1, Task 5.1.1e, D-31). It walks the BYTES in 64 KB chunks with a
 * small resumable state machine (string and escape state, bracket depth, no allocation per byte), enforces the
 * JSON depth limit before any decode, validates the structure of the root object, the `elements` array, the
 * `files` map and each `files` entry, and treats everything inside a span as a balanced skip that the span decoder
 * validates later. A `files` entry's `dataURL` string is a raw byte range: it is never JSON-decoded.
 *
 * Refusals unwind as [ImportAbort] to the parse boundary: `MalformedJson`, `ImportTimeout`, `FileTooLarge`, and
 * an oversized or over-deep top-level span other than `elements` or `files`.
 */
class JsonSpanScanner(
    private val limits: SceneLimits = SceneLimits.Default,
    private val budget: ParseBudget = ParseBudget.start(limits),
    private val decoder: SpanDecoder = SpanDecoder.Default,
) {
    /** Scene mode: the `elements` array holds the element spans, and `files` is descended into. */
    suspend fun scanScene(source: ImportSource): SceneScan = run(source, SpanMachine(limits, setOf(ELEMENTS_KEY), descendFiles = true, decoder))

    /** Library mode: one span per library item (`libraryItems`, or the v1 `library`); nothing else is descended into. */
    suspend fun scanLibrary(source: ImportSource): SceneScan =
        run(source, SpanMachine(limits, setOf(LIBRARY_ITEMS_KEY, LIBRARY_V1_KEY), descendFiles = false, decoder))

    private suspend fun run(source: ImportSource, machine: SpanMachine): SceneScan {
        if (source.size > limits.maxImportBytes) throw ImportAbort(ImportSource.tooLarge(limits))
        var offset = 0L
        while (offset < source.size) {
            val chunk = source.read(offset, ParseBudget.YIELD_BYTES.toInt())
            if (chunk.isEmpty()) break
            machine.feed(chunk, offset)
            offset += chunk.size
            budget.tick(chunk.size.toLong())
        }
        return machine.finish()
    }

    companion object {
        const val ELEMENTS_KEY = "elements"
        const val FILES_KEY = "files"
        const val DATA_URL_KEY = "dataURL"
        const val LIBRARY_ITEMS_KEY = "libraryItems"
        const val LIBRARY_V1_KEY = "library"
    }
}

private fun malformed(why: String): Nothing = throw ImportAbort(ImportError(ImportReason.MalformedJson, why))

private const val BYTE_MASK = 0xFF
private const val SPACE = 0x20
private const val TAB = 0x09
private const val LF = 0x0A
private const val CR = 0x0D
private const val QUOTE = 0x22
private const val COMMA = 0x2C
private const val COLON = 0x3A
private const val SLASH = 0x2F
private const val BACKSLASH = 0x5C
private const val OPEN_BRACE = 0x7B
private const val CLOSE_BRACE = 0x7D
private const val OPEN_BRACKET = 0x5B
private const val CLOSE_BRACKET = 0x5D
private val BOM = intArrayOf(0xEF, 0xBB, 0xBF)

private fun isWhitespace(b: Int) = b == SPACE || b == TAB || b == LF || b == CR

/** A byte that ends a bare scalar (number, literal) or cannot start a value. */
private fun isStructuralEnd(b: Int) = b == COMMA || b == CLOSE_BRACE || b == CLOSE_BRACKET

/** Accumulates the bytes of one object key and decodes it (unescaping when needed). */
private class KeyAccumulator(private val maxBytes: Long, private val decoder: SpanDecoder) {
    private var buf = ByteArray(KEY_BUF_START)
    private var len = 0
    private var escaping = false
    private var hadEscape = false

    fun reset() {
        len = 0
        escaping = false
        hadEscape = false
    }

    /** Returns true when [b] is the closing quote; the key is then available from [key]. */
    fun accept(b: Int): Boolean {
        if (escaping) {
            escaping = false
        } else if (b == BACKSLASH) {
            escaping = true
            hadEscape = true
        } else if (b == QUOTE) {
            return true
        }
        if (len >= buf.size) {
            if (buf.size >= maxBytes) throw ImportAbort(ImportError(ImportReason.ImportSpanTooLarge, "a key is larger than the span limit", maxBytes))
            buf = buf.copyOf(buf.size * 2)
        }
        buf[len++] = b.toByte()
        return false
    }

    fun key(): String {
        if (!hadEscape) return buf.decodeToString(0, len)
        val quoted = "\"" + buf.decodeToString(0, len) + "\""
        return try {
            (decoder.decode(quoted) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: malformed("bad key")
        } catch (e: ImportAbort) {
            throw e
        } catch (e: Exception) {
            malformed("bad escape in a key")
        }
    }

    private companion object {
        const val KEY_BUF_START = 64
    }
}

/** The resumable automaton; see [JsonSpanScanner]. */
private class SpanMachine(
    private val limits: SceneLimits,
    private val arrayKeys: Set<String>,
    private val descendFiles: Boolean,
    private val decoder: SpanDecoder,
) {
    private val members = LinkedHashMap<String, Member>()

    private var state = S_ROOT_BEFORE
    private var bomLeft = 0

    // Structure stack: role and current key per open structural container.
    private val roles = IntArray(CTX_SLOTS)
    private val keys = arrayOfNulls<String>(CTX_SLOTS)
    private var top = 0

    // Key accumulation.
    private val keyAcc = KeyAccumulator(limits.maxImportSpanBytes, decoder)

    // Span state.
    private var spanKind = 0
    private var spanStart = 0L
    private var rel = 0
    private var inString = false
    private var escape = false
    private var scalar = false
    private var tooDeep = false
    private val opened = BooleanArray(limits.maxJsonDepth + 2)

    // Raw dataURL state.
    private var rawStart = 0L
    private var rawEscaped = false

    // Collected data.
    private var arraySpans = ArrayList<SpanRef>()
    private var fileEntries = LinkedHashMap<String, FileScan>()
    private var entryMembers = ArrayList<Pair<String, SpanRef>>()
    private var entryData: ByteRange? = null
    private var entryEscaped = false

    fun feed(buf: ByteArray, base: Long) {
        var i = 0
        while (i < buf.size) {
            if (consume(buf[i].toInt() and BYTE_MASK, base + i)) i++
        }
    }

    fun finish(): SceneScan {
        if (state != S_DONE) malformed("unexpected end of input")
        return SceneScan(members)
    }

    /** Returns false when the byte must be processed again in the new state (a scalar ended on a delimiter). */
    private fun consume(b: Int, off: Long): Boolean {
        when (state) {
            S_ROOT_BEFORE -> rootBefore(b, off)
            S_DONE -> if (!isWhitespace(b)) malformed("data after the root object")
            S_OBJ_OPEN -> when {
                isWhitespace(b) -> Unit
                b == QUOTE -> beginKey()
                b == CLOSE_BRACE -> closeObject()
                else -> malformed("expected a key or }")
            }
            S_OBJ_KEY_NEXT -> when {
                isWhitespace(b) -> Unit
                b == QUOTE -> beginKey()
                else -> malformed("expected a key")
            }
            S_KEY -> if (keyAcc.accept(b)) {
                keys[top - 1] = keyAcc.key()
                state = S_COLON
            }
            S_COLON -> when {
                isWhitespace(b) -> Unit
                b == COLON -> state = S_VAL_START
                else -> malformed("expected :")
            }
            S_VAL_START -> if (!isWhitespace(b)) startValue(b, off)
            S_OBJ_AFTER -> when {
                isWhitespace(b) -> Unit
                b == COMMA -> state = S_OBJ_KEY_NEXT
                b == CLOSE_BRACE -> closeObject()
                else -> malformed("expected , or }")
            }
            S_ARR_OPEN -> when {
                isWhitespace(b) -> Unit
                b == CLOSE_BRACKET -> closeArray()
                else -> startValue(b, off)
            }
            S_ARR_NEXT -> if (!isWhitespace(b)) startValue(b, off)
            S_ARR_AFTER -> when {
                isWhitespace(b) -> Unit
                b == COMMA -> state = S_ARR_NEXT
                b == CLOSE_BRACKET -> closeArray()
                else -> malformed("expected , or ]")
            }
            S_SPAN -> return spanByte(b, off)
            S_RAW -> rawByte(b, off)
        }
        return true
    }

    private fun rootBefore(b: Int, off: Long) {
        if (bomLeft > 0) {
            if (b != BOM[BOM.size - bomLeft]) malformed("bad byte order mark")
            bomLeft--
            return
        }
        when {
            off == 0L && b == BOM[0] -> bomLeft = BOM.size - 1
            isWhitespace(b) -> Unit
            b == OPEN_BRACE -> {
                push(ROLE_ROOT)
                state = S_OBJ_OPEN
            }
            else -> malformed("the root is not an object")
        }
    }

    // ---- keys

    private fun beginKey() {
        keyAcc.reset()
        state = S_KEY
    }

    // ---- values

    private fun startValue(b: Int, off: Long) {
        if (b == COLON || isStructuralEnd(b)) malformed("unexpected token where a value is expected")
        val role = roles[top - 1]
        val key = keys[top - 1]
        when (role) {
            ROLE_ROOT -> when {
                key in arrayKeys && b == OPEN_BRACKET -> {
                    arraySpans = ArrayList()
                    push(ROLE_ARRAY)
                    state = S_ARR_OPEN
                }
                descendFiles && key == JsonSpanScanner.FILES_KEY && b == OPEN_BRACE -> {
                    fileEntries = LinkedHashMap()
                    push(ROLE_FILES)
                    state = S_OBJ_OPEN
                }
                else -> beginSpan(b, off, K_TOP)
            }
            ROLE_ARRAY -> beginSpan(b, off, K_ELEMENT)
            ROLE_FILES -> if (b == OPEN_BRACE) openEntry() else {
                beginSpan(b, off, K_FILE_NONOBJECT)
            }
            else -> if (key == JsonSpanScanner.DATA_URL_KEY && b == QUOTE) beginRaw(off) else beginSpan(b, off, K_FILE_MEMBER)
        }
    }

    private fun openEntry() {
        entryMembers = ArrayList()
        entryData = null
        entryEscaped = false
        push(ROLE_ENTRY)
        state = S_OBJ_OPEN
    }

    private fun push(role: Int) {
        roles[top] = role
        keys[top] = null
        top++
    }

    private fun closeObject() {
        val role = roles[--top]
        when (role) {
            ROLE_ROOT -> state = S_DONE
            ROLE_FILES -> {
                members[JsonSpanScanner.FILES_KEY] = Member.Files(fileEntries.values.toList())
                state = S_OBJ_AFTER
            }
            ROLE_ENTRY -> {
                val id = keys[top - 1] ?: ""
                fileEntries[id] = FileScan(id, true, entryMembers, entryData, entryEscaped)
                state = S_OBJ_AFTER
            }
            else -> malformed("mismatched }")
        }
    }

    private fun closeArray() {
        if (roles[--top] != ROLE_ARRAY) malformed("mismatched ]")
        members[keys[top - 1] ?: ""] = Member.Elements(arraySpans)
        state = S_OBJ_AFTER
    }

    // ---- spans

    private fun beginSpan(b: Int, off: Long, kind: Int) {
        spanKind = kind
        spanStart = off
        rel = 0
        inString = false
        escape = false
        scalar = false
        tooDeep = false
        when (b) {
            OPEN_BRACE, OPEN_BRACKET -> openBracket(b)
            QUOTE -> inString = true
            else -> scalar = true
        }
        state = S_SPAN
    }

    private fun openBracket(b: Int) {
        rel++
        val abs = top + rel
        if (abs > limits.maxJsonDepth) tooDeep = true else opened[abs] = b == OPEN_BRACE
    }

    private fun closeBracket(b: Int) {
        val abs = top + rel
        if (abs <= limits.maxJsonDepth && opened[abs] != (b == CLOSE_BRACE)) malformed("mismatched bracket")
        rel--
    }

    private fun spanByte(b: Int, off: Long): Boolean {
        if (scalar) {
            if (isWhitespace(b) || isStructuralEnd(b)) {
                endSpan(off)
                return false
            }
            return true
        }
        if (inString) {
            when {
                escape -> escape = false
                b == BACKSLASH -> escape = true
                b == QUOTE -> {
                    inString = false
                    if (rel == 0) endSpan(off + 1)
                }
            }
            return true
        }
        when (b) {
            QUOTE -> inString = true
            OPEN_BRACE, OPEN_BRACKET -> openBracket(b)
            CLOSE_BRACE, CLOSE_BRACKET -> {
                closeBracket(b)
                if (rel == 0) endSpan(off + 1)
            }
        }
        return true
    }

    private fun endSpan(end: Long) {
        val range = ByteRange(spanStart, end)
        val ref: SpanRef = when {
            tooDeep -> SpanRef.Dropped(ImportReason.ImportTooDeep, range)
            range.length > limits.maxImportSpanBytes -> SpanRef.Dropped(ImportReason.ImportSpanTooLarge, range)
            else -> SpanRef.Ok(range)
        }
        when (spanKind) {
            K_TOP -> recordTop(ref)
            K_ELEMENT -> arraySpans.add(ref)
            K_FILE_MEMBER -> recordEntryMember(ref)
            else -> {
                val id = keys[top - 1] ?: ""
                fileEntries[id] = FileScan(id, false, listOf("" to ref), null, false)
            }
        }
        state = if (roles[top - 1] == ROLE_ARRAY) S_ARR_AFTER else S_OBJ_AFTER
    }

    private fun recordTop(ref: SpanRef) {
        val key = keys[top - 1] ?: ""
        if (ref is SpanRef.Dropped) {
            val cap = if (ref.reason == ImportReason.ImportTooDeep) limits.maxJsonDepth.toLong() else limits.maxImportSpanBytes
            throw ImportAbort(ImportError(ref.reason, "the top-level member \"$key\" is over the limit", cap))
        }
        members[key] = Member.Value(ref)
    }

    private fun recordEntryMember(ref: SpanRef) {
        val key = keys[top - 1] ?: ""
        if (key == JsonSpanScanner.DATA_URL_KEY) entryData = null
        entryMembers.add(key to ref)
    }

    // ---- raw dataURL string

    private fun beginRaw(off: Long) {
        rawStart = off + 1
        rawEscaped = false
        escape = false
        state = S_RAW
    }

    private fun rawByte(b: Int, off: Long) {
        when {
            escape -> {
                escape = false
                if (b != SLASH) rawEscaped = true
            }
            b == BACKSLASH -> escape = true
            b == QUOTE -> {
                entryData = ByteRange(rawStart, off)
                entryEscaped = rawEscaped
                entryMembers.removeAll { it.first == JsonSpanScanner.DATA_URL_KEY }
                state = S_OBJ_AFTER
            }
        }
    }

    private companion object {
        const val S_ROOT_BEFORE = 0
        const val S_OBJ_OPEN = 1
        const val S_KEY = 2
        const val S_COLON = 3
        const val S_VAL_START = 4
        const val S_OBJ_AFTER = 5
        const val S_OBJ_KEY_NEXT = 6
        const val S_ARR_OPEN = 7
        const val S_ARR_NEXT = 8
        const val S_ARR_AFTER = 9
        const val S_SPAN = 10
        const val S_RAW = 11
        const val S_DONE = 12

        const val ROLE_ROOT = 0
        const val ROLE_ARRAY = 1
        const val ROLE_FILES = 2
        const val ROLE_ENTRY = 3

        const val K_TOP = 0
        const val K_ELEMENT = 1
        const val K_FILE_MEMBER = 2
        const val K_FILE_NONOBJECT = 3

        const val CTX_SLOTS = 8
    }
}
