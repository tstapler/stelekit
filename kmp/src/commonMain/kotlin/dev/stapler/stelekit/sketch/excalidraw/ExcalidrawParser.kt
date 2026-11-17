// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/**
 * Bounded, tolerant `.excalidraw` and `.excalidrawlib` parser and sanitiser (Story 5.1.1). It is the single
 * untrusted-input path of the importer: file pick, drag-and-drop, clipboard paste and library import all enter
 * through an [ImportSource] and the same [SceneLimits] checks (pre-mortem P3 #9).
 *
 * The file is never decoded as one JSON text: [JsonSpanScanner] cuts it into spans of at most
 * `maxImportSpanBytes` and only a span reaches [SpanDecoder]. The spans are re-assembled into the same tree a
 * whole-file decode would give and run through ONE extraction, so for well-formed in-limit input the result equals
 * the whole-file oracle ([parseWholeFile]) by construction. The parser never throws: every failure is an
 * `Either.Left(ImportError)`, cancellation excepted.
 *
 * Pinned to Excalidraw v0.18.1 (research/features.md section 14): `stickynote` is not a type there and is an
 * unknown type like any other; `embeddable`, `iframe` and `magicframe` stay as placeholders with a count (OD-6).
 */
class ExcalidrawParser(
    private val limits: SceneLimits = SceneLimits.Default,
    private val decoder: SpanDecoder = SpanDecoder.Default,
    private val onBase64Slice: (Int) -> Unit = {},
) {
    /** Parses a scene from [source]. */
    suspend fun parse(source: ImportSource, budget: ParseBudget = ParseBudget.start(limits)): Either<ImportError, ImportResult> =
        guarded { extractScene(assembleScene(source, JsonSpanScanner(limits, budget, decoder).scanScene(source), budget), budget) }

    /** Parses a `.excalidrawlib` from [source]; image elements become placeholders (a library has no `files`). */
    suspend fun parseLibrary(source: ImportSource, budget: ParseBudget = ParseBudget.start(limits)): Either<ImportError, ImportedLibrary> =
        guarded { extractLibrary(source, JsonSpanScanner(limits, budget, decoder).scanLibrary(source), budget) }

    /**
     * The whole-file oracle of the differential test: decode the entire text with one `parseToJsonElement`
     * (last duplicate key wins), then run the same extraction. It has no span cap; it exists for tests.
     */
    suspend fun parseWholeFile(bytes: ByteArray, budget: ParseBudget = ParseBudget.start(limits)): Either<ImportError, ImportResult> =
        guarded {
            if (bytes.size > limits.maxImportBytes) throw ImportAbort(ImportSource.tooLarge(limits))
            val text = bytes.decodeToString().removePrefix(BOM_TEXT)
            val root = try {
                decoder.decode(text)
            } catch (e: SerializationException) {
                throw ImportAbort(ImportError(ImportReason.MalformedJson, e.message.orEmpty()))
            } catch (e: IllegalArgumentException) {
                throw ImportAbort(ImportError(ImportReason.MalformedJson, e.message.orEmpty()))
            }
            val obj = root as? JsonObject ?: throw ImportAbort(ImportError(ImportReason.MalformedJson, "the root is not an object"))
            extractScene(Assembled(obj, emptyMap(), emptyList()), budget)
        }

    private suspend fun <T> guarded(block: suspend () -> T): Either<ImportError, T> = try {
        block().right()
    } catch (e: ImportAbort) {
        e.error.left()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ImportError(ImportReason.Unexpected, e::class.simpleName.orEmpty() + ": " + e.message.orEmpty()).left()
    }

    // ---- assembly: spans back into the tree a whole-file decode would give

    private class Assembled(val root: JsonObject, val placeholders: Map<String, ImportReason>, val issues: List<ImportIssue>)

    private fun decodeSpan(source: ImportSource, range: ByteRange): JsonElement {
        val text = source.read(range.start, range.length.toInt()).decodeToString()
        return try {
            decoder.decode(text)
        } catch (e: SerializationException) {
            throw ImportAbort(ImportError(ImportReason.MalformedJson, e.message.orEmpty()))
        } catch (e: IllegalArgumentException) {
            throw ImportAbort(ImportError(ImportReason.MalformedJson, e.message.orEmpty()))
        }
    }

    private suspend fun assembleScene(source: ImportSource, scan: SceneScan, budget: ParseBudget): Assembled {
        val tree = LinkedHashMap<String, JsonElement>()
        val issues = ArrayList<ImportIssue>()
        val placeholders = LinkedHashMap<String, ImportReason>()
        for ((key, member) in scan.members) {
            when (member) {
                is Member.Value -> tree[key] = decodeOk(source, member.span, budget, 0) ?: JsonNull
                is Member.Elements -> tree[key] = JsonArray(member.spans.mapIndexedNotNull { i, ref ->
                    decodeOk(source, ref, budget, 1) ?: run {
                        issues += ImportIssue((ref as SpanRef.Dropped).reason, "$key[$i]", "${ref.range.length} bytes")
                        null
                    }
                })
                is Member.Files -> tree[key] = assembleFiles(source, member, budget, issues, placeholders)
            }
        }
        return Assembled(JsonObject(tree), placeholders, issues)
    }

    private suspend fun decodeOk(source: ImportSource, ref: SpanRef, budget: ParseBudget, elements: Int): JsonElement? {
        if (ref !is SpanRef.Ok) return null
        budget.tick(ref.range.length, elements)
        return decodeSpan(source, ref.range)
    }

    private suspend fun assembleFiles(
        source: ImportSource,
        files: Member.Files,
        budget: ParseBudget,
        issues: MutableList<ImportIssue>,
        placeholders: MutableMap<String, ImportReason>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (entry in files.entries) {
            val refusal = refuseEntry(entry)
            if (refusal != null) {
                placeholders[entry.id] = refusal
                issues += ImportIssue(refusal, entry.id)
                continue
            }
            val fields = LinkedHashMap<String, JsonElement>()
            for ((key, ref) in entry.members) fields[key] = decodeOk(source, ref, budget, 0) ?: continue
            val dataUrl = entry.dataUrl
            if (dataUrl != null) {
                fields[JsonSpanScanner.DATA_URL_KEY] = JsonPrimitive(readRaw(source, dataUrl, budget).replace(SLASH_ESCAPE, "/"))
            }
            out[entry.id] = if (entry.isObject) JsonObject(fields) else fields[""] ?: JsonNull
        }
        return JsonObject(out)
    }

    /** Reads a raw byte range in 64 KB steps so a 15 MB `dataURL` yields between steps (base64 is ASCII, so a step never splits a character). */
    private suspend fun readRaw(source: ImportSource, range: ByteRange, budget: ParseBudget): String {
        val out = StringBuilder()
        var at = range.start
        while (at < range.end) {
            val step = minOf(ParseBudget.YIELD_BYTES, range.end - at).toInt()
            out.append(source.read(at, step).decodeToString())
            at += step
            budget.tick(step.toLong())
        }
        return out.toString()
    }

    /** Why a `files` entry cannot be decoded at all (it becomes a placeholder), or null. */
    private fun refuseEntry(entry: FileScan): ImportReason? {
        if (!entry.isObject) return (entry.members.firstOrNull()?.second as? SpanRef.Dropped)?.reason
        entry.members.forEach { (_, ref) -> if (ref is SpanRef.Dropped) return ref.reason }
        if (entry.members.sumOf { (it.second as? SpanRef.Ok)?.range?.length ?: 0L } > limits.maxImportSpanBytes) return ImportReason.ImportSpanTooLarge
        if (entry.dataUrlEscaped) return ImportReason.DataUrlEscaped
        val data = entry.dataUrl ?: return null
        return if (data.length > encodedImageCap()) ImportReason.ImageTooLarge else null
    }

    /** Base64 characters (plus the data-URL prefix) that a `maxImageBytes` file can occupy. */
    private fun encodedImageCap(): Long = limits.maxImageBytes / BASE64_GROUP_BYTES * BASE64_GROUP_CHARS + DATA_URL_PREFIX_ALLOWANCE

    // ---- extraction (shared by the scanner path and the whole-file oracle)

    private suspend fun extractScene(assembled: Assembled, budget: ParseBudget): ImportResult {
        val root = assembled.root
        val type = root["type"]
        val typeText = (type as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != null && type !is JsonNull && typeText !in SCENE_TYPES) {
            throw ImportAbort(ImportError(ImportReason.NotExcalidraw, "type is not excalidraw"))
        }
        val issues = ArrayList<ImportIssue>(assembled.issues)
        val tally = Tally()
        val elements = extractElements(root["elements"], "elements", budget, issues, tally)
        val repaired = ElementSetRepair.repair(elements, issues)

        val placeholderIds = LinkedHashSet<String>(assembled.placeholders.keys)
        val files = extractFiles(root["files"], budget, issues, placeholderIds)
        val scene = ExcalidrawScene(repaired, files, extractAppState(root["appState"]), placeholderIds, extractFileVersion(root["version"]))
        return ImportResult(scene, ImportReport(issues, tally.deleted, tally.unknown, tally.unsupported))
    }

    private class Tally {
        var deleted = 0
        val unknown = LinkedHashMap<String, Int>()
        val unsupported = LinkedHashMap<String, Int>()
    }

    private suspend fun extractElements(
        raw: JsonElement?,
        label: String,
        budget: ParseBudget,
        issues: MutableList<ImportIssue>,
        tally: Tally,
    ): List<ExcalidrawElement> {
        if (raw == null || raw is JsonNull) return emptyList()
        if (raw !is JsonArray) {
            issues += ImportIssue(ImportReason.ElementsNotAnArray, label)
            return emptyList()
        }
        val sanitizer = ElementSanitizer(limits)
        val kept = ArrayList<ExcalidrawElement>()
        for ((i, item) in raw.withIndex()) {
            budget.tick(0, 1)
            val obj = item as? JsonObject
            if (obj == null) {
                issues += ImportIssue(ImportReason.ElementNotAnObject, "$label[$i]")
                continue
            }
            when (val outcome = sanitizer.sanitize(obj, i)) {
                is ElementOutcome.Kept -> {
                    kept += outcome.element
                    issues += outcome.issues
                    if (outcome.element.unsupported) tally.unsupported[outcome.element.type] = (tally.unsupported[outcome.element.type] ?: 0) + 1
                    if (kept.size > limits.maxElements) {
                        throw ImportAbort(ImportError(ImportReason.TooManyElements, "more than ${limits.maxElements} elements", limits.maxElements.toLong()))
                    }
                }
                is ElementOutcome.Dropped -> outcome.issue?.let { issues += it }
                is ElementOutcome.Deleted -> tally.deleted++
                is ElementOutcome.UnknownType -> tally.unknown[outcome.type] = (tally.unknown[outcome.type] ?: 0) + 1
            }
        }
        return kept
    }

    private suspend fun extractFiles(raw: JsonElement?, budget: ParseBudget, issues: MutableList<ImportIssue>, placeholderIds: MutableSet<String>): List<ExcalidrawFile> {
        if (raw == null || raw is JsonNull) return emptyList()
        if (raw !is JsonObject) {
            issues += ImportIssue(ImportReason.FilesNotAnObject, "files")
            return emptyList()
        }
        val admitter = ImageAdmitter(limits, budget, onBase64Slice)
        val admitted = ArrayList<ExcalidrawFile>()
        for ((key, value) in raw) {
            val entry = value as? JsonObject ?: continue
            val id = (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: key
            val mime = (entry["mimeType"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val dataUrl = (entry["dataURL"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val created = (entry["created"] as? JsonPrimitive)?.let { CanonicalJson.doubleOf(it) }?.takeIf { it.isFinite() }?.toLong()
            when (val result = admitter.admit(id, mime, dataUrl, created)) {
                is Admission.Admitted -> admitted += result.file
                is Admission.Refused -> {
                    placeholderIds += id
                    issues += ImportIssue(result.reason, id, result.detail)
                }
            }
        }
        return admitted
    }

    private fun extractFileVersion(raw: JsonElement?): Int? {
        val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.let { CanonicalJson.doubleOf(it) } ?: return null
        return if (value.isFinite() && value == floor(value) && value in 0.0..Int.MAX_VALUE.toDouble()) value.toInt() else null
    }

    private fun extractAppState(raw: JsonElement?): ExcalidrawAppState? {
        val obj = raw as? JsonObject ?: return null
        val grid = (obj["gridSize"] as? JsonPrimitive)?.let { if (it.isString) null else CanonicalJson.doubleOf(it) }?.takeIf { it.isFinite() }
        val background = (obj["viewBackgroundColor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return ExcalidrawAppState(grid, background)
    }

    // ---- library

    private suspend fun extractLibrary(source: ImportSource, scan: SceneScan, budget: ParseBudget): ImportedLibrary {
        for ((key, member) in scan.members) {
            if (member is Member.Value) {
                val decoded = decodeOk(source, member.span, budget, 0)
                if (key == TYPE_KEY && decoded != null && (decoded as? JsonPrimitive)?.content != LIBRARY_TYPE) {
                    throw ImportAbort(ImportError(ImportReason.NotExcalidraw, "type is not excalidrawlib"))
                }
            }
        }
        val items = (scan.members[JsonSpanScanner.LIBRARY_ITEMS_KEY] ?: scan.members[JsonSpanScanner.LIBRARY_V1_KEY]) as? Member.Elements
            ?: throw ImportAbort(ImportError(ImportReason.NotExcalidraw, "no library items"))
        val issues = ArrayList<ImportIssue>()
        val out = ArrayList<ExcalidrawLibraryItem>()
        var storedBytes = 0L
        for ((i, ref) in items.spans.withIndex()) {
            if (ref !is SpanRef.Ok) {
                issues += ImportIssue((ref as SpanRef.Dropped).reason, "libraryItems[$i]", "${ref.range.length} bytes")
                continue
            }
            if (out.size >= limits.maxLibraryItems) {
                issues += ImportIssue(ImportReason.LibraryTooManyItems, "libraryItems[$i]", "more than ${limits.maxLibraryItems} items; the rest are ignored")
                break
            }
            storedBytes += ref.range.length
            if (storedBytes > limits.maxLibraryBytes) {
                throw ImportAbort(ImportError(ImportReason.LibraryTooLarge, "the library is larger than ${limits.maxLibraryBytes} bytes", limits.maxLibraryBytes))
            }
            budget.tick(ref.range.length, 1)
            out += libraryItem(decodeSpan(source, ref.range), i, budget, issues)
        }
        return ImportedLibrary(out, ImportReport(issues))
    }

    private suspend fun libraryItem(item: JsonElement, index: Int, budget: ParseBudget, issues: MutableList<ImportIssue>): ExcalidrawLibraryItem {
        val obj = item as? JsonObject
        val id = (obj?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "item-$index"
        val name = (obj?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val tally = Tally()
        val elements = extractElements(obj?.get("elements") ?: item.takeIf { it is JsonArray }, "libraryItems[$index].elements", budget, issues, tally)
        val noImages = elements.map { e ->
            if (e.type != IMAGE_TYPE) return@map e
            issues += ImportIssue(ImportReason.LibraryImage, e.id)
            e.copy(raw = JsonObject(e.raw - "fileId"))
        }
        return ExcalidrawLibraryItem(id, name, ElementSetRepair.repair(noImages, issues))
    }

    private companion object {
        const val BOM_TEXT = "﻿"
        const val SLASH_ESCAPE = "\\/"
        const val TYPE_KEY = "type"
        const val LIBRARY_TYPE = "excalidrawlib"
        const val IMAGE_TYPE = "image"
        const val BASE64_GROUP_BYTES = 3
        const val BASE64_GROUP_CHARS = 4
        const val DATA_URL_PREFIX_ALLOWANCE = 1024L
        val SCENE_TYPES = setOf("excalidraw", "excalidraw/clipboard")
    }
}
