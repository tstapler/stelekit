// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitHostAdapter
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException

/**
 * Supplies `.gitattributes` layers for the web, which has no working tree: each ancestor directory's file is
 * read from the remote at [ref] (the base sha being committed on top of). A `.gitattributes` the user changed
 * locally (a dirty entry, passed as [localOverrides] keyed by repo-relative directory) wins over the remote copy,
 * so an edit staged in the same commit applies to that commit.
 *
 * Fails closed: a 404 means "no rules at that level", anything else that stops the file being read is an error,
 * because committing without knowing the rules could upload a raw binary.
 */
class WasmLfsRulesSource(
    private val httpClient: HttpClient,
    private val hostConfig: GitHostConfig,
    private val ref: String,
    private val localOverrides: Map<String, ByteArray?> = emptyMap(),
    private val cache: MutableMap<String, String?> = mutableMapOf(),
) : RemoteRulesSource {

    override suspend fun load(changedPaths: Collection<String>): Either<DomainError.GitError, AttributeRuleSet> {
        val directories = LinkedHashSet<String>().apply { add("") }
        for (path in changedPaths) {
            var dir = path.substringBeforeLast('/', "")
            while (dir.isNotEmpty() && directories.add(dir)) dir = dir.substringBeforeLast('/', "")
        }
        val files = ArrayList<GitAttributesFile>()
        for (dir in directories.sorted()) { // root first, then shallow to deep: a stable request order
            val text = attributesText(dir).fold({ return it.left() }, { it }) ?: continue
            files += GitAttributesParser.parse(text, dirPrefix = dir)
        }
        return AttributeRuleSet(files).right()
    }

    private suspend fun attributesText(dir: String): Either<DomainError.GitError, String?> {
        if (dir in localOverrides) return localOverrides.getValue(dir)?.decodeToString().right()
        val path = if (dir.isEmpty()) LfsPathPolicy.GITATTRIBUTES_FILE else "$dir/${LfsPathPolicy.GITATTRIBUTES_FILE}"
        val key = "${hostConfig.apiBase}|$ref|$path"
        if (key in cache) return cache.getValue(key).right()
        return fetch(path).also { result -> result.onRight { cache[key] = it } }
    }

    private suspend fun fetch(path: String): Either<DomainError.GitError, String?> = try {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = httpClient.get(rawUrl(path)) { header(headerName, headerValue) }
        when (val status = response.status.value) {
            404 -> null.right()
            in 200..299 -> response.bodyAsText().right()
            else -> DomainError.GitError.NetworkFailure(
                "Could not read $path to check Git LFS rules (HTTP $status), so nothing was committed",
            ).left()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainError.GitError.NetworkFailure(
            "Could not read $path to check Git LFS rules (${e.message}), so nothing was committed",
        ).left()
    }

    private fun rawUrl(path: String): String = when (hostConfig.type) {
        GitHostType.GITHUB -> "https://raw.githubusercontent.com/${hostConfig.owner}/${hostConfig.repo}/$ref/$path"
        GitHostType.GITLAB -> "${hostConfig.apiBase}/repository/files/${path.replace("/", "%2F")}/raw?ref=$ref"
        GitHostType.UNSUPPORTED -> error("Cannot read .gitattributes from an unsupported git host")
    }
}

/** [LfsContentProbe] over bytes the commit already holds in memory, keyed by repo-relative path. */
class WasmLfsContentProbe(private val contentByPath: Map<String, ByteArray>) : LfsContentProbe {
    override suspend fun size(path: String): Long? = contentByPath[path]?.size?.toLong()

    override suspend fun readHead(path: String, maxBytes: Int): ByteArray? =
        contentByPath[path]?.let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
}

/**
 * Structural second check on the single place a blob is created: a tracked path whose content is neither a valid
 * pointer nor empty never becomes a blob, even if a caller skipped the guard. A null policy cannot be passed.
 */
class WasmLfsBlobGate(private val policy: LfsPathPolicy) {
    fun isTracked(apiPath: String): Boolean = policy.isTracked(apiPath)

    /** Throws [IllegalStateException]: reaching it is a programming error, never a user condition. */
    fun requireNotRawAtTrackedPath(apiPath: String, content: ByteArray) {
        if (!policy.isTracked(apiPath)) return
        val acceptable = content.isEmpty() ||
            (content.size < LfsPointerCodec.MAX_POINTER_BYTES && LfsPointerCodec.parse(content) is LfsPointerParse.Valid)
        check(acceptable) { "LFS guard bypassed: refusing to create a raw blob for LFS-tracked path $apiPath" }
    }
}
