package dev.stapler.stelekit.git.lfs

import com.sun.net.httpserver.HttpServer
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** How the batch client turns HTTP statuses and object-level errors into domain errors, against a scripted server. */
class LfsBatchClientStatusTest {
    private val hits = AtomicInteger()
    private var script: (Int) -> Triple<Int, String, Map<String, String>> = { Triple(200, """{"objects":[]}""", emptyMap()) }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            ex.requestBody.readBytes()
            val (status, body, headers) = script(hits.incrementAndGet())
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        start()
    }
    private val http = HttpClient(OkHttp)
    private val dir: File = createTempDirectory("lfs_status_").toFile()
    private val pointer = LfsPointer(LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"), 10)
    private val client get() = LfsBatchClient(http, "http://127.0.0.1:${server.address.port}", authHeader = null, maxAttempts = 2, backoffMillis = 1)

    @AfterTest
    fun tearDown() {
        server.stop(0)
        http.close()
        dir.deleteRecursively()
    }

    private fun batchError(operation: String = "download") =
        runBlocking { client.batch(operation, listOf(pointer)) }.leftOrNull()

    private fun respond(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        script = { Triple(status, body, headers) }
    }

    @Test
    fun batch_should_ReportAuthFailure_When_ServerReturns401() {
        respond(401)
        assertEquals(DomainError.GitError.LfsAuthFailed(readOnly = false), batchError())
    }

    @Test
    fun batch_should_ReportReadOnlyAuth_When_UploadIsForbidden() {
        respond(403, "forbidden")
        assertEquals(DomainError.GitError.LfsAuthFailed(readOnly = true), batchError("upload"))
    }

    @Test
    fun batch_should_ReportQuota_When_ForbiddenBodyMentionsQuotaOrStatusIs507() {
        respond(403, "Bandwidth limit exceeded")
        assertIs<DomainError.GitError.LfsQuotaExceeded>(batchError())
        respond(507)
        assertIs<DomainError.GitError.LfsQuotaExceeded>(batchError())
    }

    @Test
    fun batch_should_ExplainUnsupportedServer_When_ServerReturns404() {
        respond(404)
        assertTrue(assertIs<DomainError.GitError.FetchFailed>(batchError()).message.contains("404"))
    }

    @Test
    fun batch_should_RetryServerErrorsThenGiveUp_WithTheOperationsErrorType() {
        respond(503)
        assertIs<DomainError.GitError.FetchFailed>(batchError("download"))
        assertEquals(2, hits.get(), "one retry with maxAttempts = 2")
        respond(500)
        assertIs<DomainError.GitError.PushFailed>(batchError("upload"))
    }

    @Test
    fun batch_should_HonourRetryAfterAndSucceed_When_ThrottledOnce() {
        script = { n -> if (n == 1) Triple(429, "", mapOf("Retry-After" to "0")) else Triple(200, """{"objects":[]}""", emptyMap()) }
        assertTrue(runBlocking { client.batch("download", listOf(pointer)) }.isRight())
        assertEquals(2, hits.get())
    }

    @Test
    fun batch_should_RejectUnreadableBody() {
        respond(200, "not json")
        assertIs<DomainError.GitError.FetchFailed>(batchError())
    }

    @Test
    fun download_should_MapObjectErrors_AndMissingActions() = runBlocking<Unit> {
        val gone = BatchObject(pointer.oid.hex, 10, error = BatchError(404, "gone"))
        assertIs<DomainError.GitError.LfsObjectMissing>(client.download(gone, File(dir, "a")).leftOrNull())
        val quota = BatchObject(pointer.oid.hex, 10, error = BatchError(507, "full"))
        assertIs<DomainError.GitError.LfsQuotaExceeded>(client.download(quota, File(dir, "b")).leftOrNull())
        val refused = BatchObject(pointer.oid.hex, 10, error = BatchError(422, "nope"))
        assertIs<DomainError.GitError.FetchFailed>(client.download(refused, File(dir, "c")).leftOrNull())
        assertIs<DomainError.GitError.LfsObjectMissing>(client.download(BatchObject(pointer.oid.hex, 10), File(dir, "d")).leftOrNull())
    }

    @Test
    fun download_should_FailWithStatus_When_ObjectHostReturnsClientError() = runBlocking<Unit> {
        respond(403, "denied")
        val obj = BatchObject(pointer.oid.hex, 10, actions = mapOf("download" to BatchAction("http://127.0.0.1:${server.address.port}/o")))
        assertEquals(DomainError.GitError.LfsAuthFailed(readOnly = false), client.download(obj, File(dir, "x")).leftOrNull())
    }

    @Test
    fun upload_should_MapObjectErrors_AndFailWhenVerifyIsRejected() = runBlocking<Unit> {
        val file = File(dir, "f").apply { writeText("0123456789") }
        val refused = BatchObject(pointer.oid.hex, 10, error = BatchError(422, "nope"))
        assertIs<DomainError.GitError.PushFailed>(client.upload(refused, file).leftOrNull())
        val missing = BatchObject(pointer.oid.hex, 10, error = BatchError(410, "gone"))
        assertIs<DomainError.GitError.LfsObjectMissing>(client.upload(missing, file).leftOrNull())
        respond(500)
        val verifyOnly = BatchObject(pointer.oid.hex, 10, actions = mapOf("verify" to BatchAction("http://127.0.0.1:${server.address.port}/v")))
        assertIs<DomainError.GitError.PushFailed>(client.upload(verifyOnly, file).leftOrNull())
    }
}
