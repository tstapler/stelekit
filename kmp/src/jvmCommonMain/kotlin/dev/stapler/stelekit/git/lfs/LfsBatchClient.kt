// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable internal data class BatchRef(val oid: String, val size: Long)

@Serializable internal data class BatchRequest(
    val operation: String,
    val transfers: List<String> = listOf("basic"),
    val objects: List<BatchRef>,
    @SerialName("hash_algo") val hashAlgo: String = "sha256",
)

@Serializable internal data class BatchAction(val href: String, val header: Map<String, String>? = null)

@Serializable internal data class BatchError(val code: Int = 0, val message: String = "")

@Serializable internal data class BatchObject(
    val oid: String,
    val size: Long = 0,
    val actions: Map<String, BatchAction>? = null,
    val error: BatchError? = null,
)

@Serializable internal data class BatchResponse(val objects: List<BatchObject> = emptyList())

/** Installs the timeouts every LFS transfer needs; engine choice stays with the platform (OkHttp on JVM and Android). */
fun HttpClientConfig<*>.configureLfsTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30 * 60_000L // a 100 MB image over a slow link; the socket timeout catches stalls
        socketTimeoutMillis = 60_000
    }
}

/**
 * Git LFS batch API client (https://github.com/git-lfs/git-lfs/blob/main/docs/api/batch.md), basic transfer only.
 * [authHeader] is attached to the batch and verify calls only; object hrefs get exactly the headers the batch
 * response listed for them, so the user's token never reaches a third-party storage host.
 */
class LfsBatchClient(
    private val http: HttpClient,
    private val lfsUrl: String,
    private val authHeader: String?,
    private val maxAttempts: Int = 3,
    private val backoffMillis: Long = 1_000L,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Maps each object's oid to its transfer instruction; an object-level error becomes a Left for the whole call. */
    internal suspend fun batch(operation: String, pointers: List<LfsPointer>): Either<DomainError.GitError, Map<String, BatchObject>> {
        val byOid = LinkedHashMap<String, BatchObject>()
        for (chunk in pointers.chunked(BATCH_CHUNK)) {
            val body = json.encodeToString(BatchRequest(operation, objects = chunk.map { BatchRef(it.oid.hex, it.size) }))
            val response = withRetry(operation) {
                http.post("$lfsUrl/objects/batch") {
                    header(HttpHeaders.Accept, LFS_JSON)
                    authHeader?.let { header(HttpHeaders.Authorization, it) }
                    contentType(ContentType.parse(LFS_JSON))
                    setBody(body)
                }
            }.fold({ return it.left() }, { it })
            val parsed = try {
                json.decodeFromString<BatchResponse>(response)
            } catch (e: Exception) {
                return DomainError.GitError.FetchFailed("Git LFS returned an unreadable response").left()
            }
            parsed.objects.forEach { byOid[it.oid] = it }
        }
        return byOid.right()
    }

    /** Downloads [obj] into [dest] (a temp file); the caller verifies the hash before trusting it. */
    internal suspend fun download(obj: BatchObject, dest: File): Either<DomainError.GitError, Unit> {
        obj.error?.let { return objectError(obj, it, upload = false).left() }
        val action = obj.actions?.get("download") ?: return DomainError.GitError.LfsObjectMissing(obj.oid).left()
        var attempt = 0
        while (true) {
            attempt++
            val outcome: Either<DomainError.GitError, Boolean> = try {
                http.prepareGet(action.href) { action.header?.forEach { (k, v) -> header(k, v) } }.execute { resp ->
                    if (resp.status.isSuccess()) {
                        dest.outputStream().buffered().use { out -> resp.bodyAsChannel().toInputStream().use { it.copyTo(out) } }
                        true.right()
                    } else if (isRetryable(resp.status) && attempt < maxAttempts) {
                        false.right()
                    } else {
                        statusError(resp.status, "download", resp.bodyAsText()).left()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= maxAttempts) return DomainError.GitError.FetchFailed("Git LFS download failed: ${e::class.simpleName}").left()
                false.right()
            }
            val done = outcome.fold({ return it.left() }, { it })
            if (done) return Unit.right()
            delay(backoffMillis * attempt)
        }
    }

    /** Uploads [file] if the server asked for it (no `upload` action means it already has the object), then verifies. */
    internal suspend fun upload(obj: BatchObject, file: File): Either<DomainError.GitError, Unit> {
        obj.error?.let { return objectError(obj, it, upload = true).left() }
        val upload = obj.actions?.get("upload")
        if (upload != null) {
            val result = withRetry("upload") {
                http.request(upload.href) {
                    method = HttpMethod.Put
                    upload.header?.forEach { (k, v) -> header(k, v) }
                    setBody(FileBody(file))
                }
            }
            result.onLeft { return it.left() }
        }
        val verify = obj.actions?.get("verify") ?: return Unit.right()
        return withRetry("upload") {
            http.post(verify.href) {
                header(HttpHeaders.Accept, LFS_JSON)
                authHeader?.let { header(HttpHeaders.Authorization, it) }
                verify.header?.forEach { (k, v) -> header(k, v) }
                contentType(ContentType.parse(LFS_JSON))
                setBody(json.encodeToString(BatchRef(obj.oid, obj.size)))
            }
        }.map { }
    }

    private suspend fun withRetry(operation: String, call: suspend () -> HttpResponse): Either<DomainError.GitError, String> {
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= maxAttempts) return networkError(operation, e::class.simpleName).left()
                delay(backoffMillis * attempt)
                continue
            }
            if (response.status.isSuccess()) return response.bodyAsText().right()
            if (isRetryable(response.status) && attempt < maxAttempts) {
                delay(retryAfterMillis(response) ?: (backoffMillis * attempt))
                continue
            }
            return statusError(response.status, operation, response.bodyAsText()).left()
        }
    }

    private fun networkError(operation: String, cause: String?): DomainError.GitError =
        if (operation == "upload") DomainError.GitError.PushFailed("Git LFS upload failed: $cause")
        else DomainError.GitError.FetchFailed("Git LFS request failed: $cause")

    private fun statusError(status: HttpStatusCode, operation: String, body: String): DomainError.GitError = when {
        status.value == 401 -> DomainError.GitError.LfsAuthFailed(readOnly = false)
        status.value == 403 && mentionsQuota(body) -> DomainError.GitError.LfsQuotaExceeded(null)
        status.value == 403 -> DomainError.GitError.LfsAuthFailed(readOnly = operation == "upload")
        status.value == 507 || status.value == 509 -> DomainError.GitError.LfsQuotaExceeded(null)
        status.value == 404 -> DomainError.GitError.FetchFailed("Git LFS is not available for this repository (404)")
        operation == "upload" -> DomainError.GitError.PushFailed("Git LFS upload failed (HTTP ${status.value})")
        else -> DomainError.GitError.FetchFailed("Git LFS request failed (HTTP ${status.value})")
    }

    private fun objectError(obj: BatchObject, error: BatchError, upload: Boolean): DomainError.GitError = when (error.code) {
        404, 410 -> DomainError.GitError.LfsObjectMissing(obj.oid)
        507, 509 -> DomainError.GitError.LfsQuotaExceeded(error.message)
        else -> if (upload) DomainError.GitError.PushFailed("Git LFS refused ${obj.oid.take(8)}: ${error.message}")
        else DomainError.GitError.FetchFailed("Git LFS refused ${obj.oid.take(8)}: ${error.message}")
    }

    private fun mentionsQuota(body: String) = body.contains("quota", ignoreCase = true) || body.contains("bandwidth", ignoreCase = true)

    private fun isRetryable(status: HttpStatusCode) = status.value == 429 || status.value in 500..599 && status.value != 507 && status.value != 509

    private fun retryAfterMillis(response: HttpResponse): Long? =
        response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1_000)?.coerceAtMost(30_000)

    private fun HttpStatusCode.isSuccess() = value in 200..299

    private class FileBody(private val file: File) : OutgoingContent.ReadChannelContent() {
        override val contentLength: Long = file.length()
        override val contentType: ContentType = ContentType.Application.OctetStream
        override fun readFrom(): ByteReadChannel = file.readChannel()
    }

    companion object {
        const val LFS_JSON = "application/vnd.git-lfs+json"
        private const val BATCH_CHUNK = 100
    }
}
