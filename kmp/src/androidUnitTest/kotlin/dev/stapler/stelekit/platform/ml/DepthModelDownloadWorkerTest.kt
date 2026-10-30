package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val target = DepthModelStorage.modelFile(context)
    private val payload = Random(11).nextBytes((DepthModelStorage.MIN_MODEL_BYTES + 512 * 1024).toInt())
    private var status = 200
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/m") { ex ->
            if (status != 200) {
                ex.sendResponseHeaders(status, -1)
            } else {
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
            ex.close()
        }
        start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        target.parentFile?.deleteRecursively()
    }

    private fun worker(attempt: Int = 0) = TestListenableWorkerBuilder<DepthModelDownloadWorker>(context)
        .setInputData(workDataOf(DepthModelDownloadWorker.KEY_URL to "http://127.0.0.1:${server.address.port}/m"))
        .setRunAttemptCount(attempt)
        .build()

    @Test
    fun `a successful run puts a ready model file in place and clears the partial`() = runBlocking<Unit> {
        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertContentEquals(payload, target.readBytes())
        assertFalse(File(target.parentFile, "${target.name}.part").exists())
    }

    @Test
    fun `a server error retries early and fails with a message after the last attempt`() = runBlocking<Unit> {
        status = 503
        assertTrue(worker(attempt = 0).doWork() is ListenableWorker.Result.Retry)

        val last = worker(attempt = 4).doWork()
        assertTrue(last is ListenableWorker.Result.Failure)
        assertTrue(last.outputData.getString(DepthModelDownloadWorker.KEY_ERROR)!!.isNotBlank())
    }

    @Test
    fun `a model that is already present finishes without touching the network`() = runBlocking<Unit> {
        target.parentFile!!.mkdirs()
        target.writeBytes(ByteArray((DepthModelStorage.MIN_MODEL_BYTES + 1).toInt()))
        server.stop(0)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }
}
