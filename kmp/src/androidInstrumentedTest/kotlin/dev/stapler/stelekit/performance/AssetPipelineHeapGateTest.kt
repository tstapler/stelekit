// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.asset.pipeline.AssetPipelineService
import dev.stapler.stelekit.asset.pipeline.MAX_UNSCALED_IMAGE_BYTES
import dev.stapler.stelekit.asset.pipeline.PipelineProgress
import dev.stapler.stelekit.asset.pipeline.PipelineRunMarker
import dev.stapler.stelekit.asset.pipeline.PlatformCaps
import dev.stapler.stelekit.asset.pipeline.PluginEnableKeys
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.asset.pipeline.defaultAssetPlugins
import dev.stapler.stelekit.console.spike.AndroidSpikeSupport
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.PdfTextExtractor
import dev.stapler.stelekit.platform.ml.TextRecognizer
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Task 5.2c, the BLOCKING heap gate for the asset pipeline (unit U8).
 *
 * Runs the real stack (index walk, `defaultAssetPlugins`, `AssetPipelineService`) with fake ML
 * ports over an owner-scale graph: a database of at least [MIN_DB_BYTES], at least [MIN_ASSET_ROWS]
 * `asset_index` rows, and ten real 40 MP (7000x5714) JPEGs. It runs twice, in order:
 * indexing only (every plugin key unset, the configuration U8 ships) and then all on-device
 * plugin keys set (the configuration U9 ships). The preconditions fail the test rather than let a
 * smaller database or a larger heap pass it, because that would not falsify the 512 MB assumption.
 *
 * Run: ./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.performance.AssetPipelineHeapGateTest
 * Results are published as SPIKE-RESULT lines to Downloads (see [AndroidSpikeSupport.record]).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AssetPipelineHeapGateTest {

    private class FakeSettings : Settings {
        private val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }

    private class CountingLabeler : ImageLabeler {
        val calls = AtomicInteger()
        val bytesSeen = AtomicLong()
        val maxBytes = AtomicLong()
        override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
            calls.incrementAndGet()
            bytesSeen.addAndGet(imageBytes.size.toLong())
            maxBytes.accumulateAndGet(imageBytes.size.toLong(), ::maxOf)
            return listOf(Label("cat", 0.9f)).right()
        }
    }

    private class CountingRecognizer : TextRecognizer {
        val calls = AtomicInteger()
        val maxBytes = AtomicLong()
        override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> {
            calls.incrementAndGet()
            maxBytes.accumulateAndGet(imageBytes.size.toLong(), ::maxOf)
            return "Invoice 4821".right()
        }
    }

    private class CountingPdf : PdfTextExtractor {
        val calls = AtomicInteger()
        override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> {
            calls.incrementAndGet()
            return "extracted text".right()
        }
    }

    /** Polls used heap on its own thread; the peak is what a 512 MB cap would have to absorb. */
    private class PeakHeapSampler : AutoCloseable {
        @Volatile private var running = true
        @Volatile var peakBytes = 0L
        private val thread = Thread {
            val rt = Runtime.getRuntime()
            while (running) {
                peakBytes = maxOf(peakBytes, rt.totalMemory() - rt.freeMemory())
                LockSupport.parkNanos(SAMPLE_INTERVAL_MS * NANOS_PER_MS)
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            running = false
            thread.join()
        }
    }

    private val settings = FakeSettings()
    private val labeler = CountingLabeler()
    private val pdf = CountingPdf()
    private val recognizer = CountingRecognizer()

    private fun <T> measured(label: String, block: suspend () -> T): T {
        System.gc()
        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()
        val sampler = PeakHeapSampler()
        val t0 = System.nanoTime()
        try {
            return runBlocking { block() }
        } finally {
            sampler.close()
            val peakMb = sampler.peakBytes / MB
            AndroidSpikeSupport.record(
                SPIKE, label,
                "peak_heap_mb=$peakMb start_heap_mb=${before / MB} max_heap_mb=${rt.maxMemory() / MB} elapsed_s=${(System.nanoTime() - t0) / NANOS_PER_SECOND}",
            )
            val budgetMb = minOf(HEAP_BUDGET_MB, (rt.maxMemory() * HEAP_BUDGET_FRACTION).toLong() / MB)
            assertTrue("$label: peak heap ${peakMb}MB must stay under the ${budgetMb}MB budget", peakMb < budgetMb)
        }
    }

    private fun newStack(): Pair<AssetIndexingController, AssetPipelineService> {
        val marker = PipelineRunMarker(settings)
        val controller = AssetIndexingController(settings, AssetIndexService(repo), toggleVisibleProvider = { true }, runMarker = marker)
        val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
        val caps = PlatformCaps(labeler, pdf, recognizer)
        val pipeline = AssetPipelineService(
            PluginRegistry(), repo, actor,
            pluginSource = { defaultAssetPlugins(caps, settings, fs) },
            runMarker = marker,
        )
        return controller to pipeline
    }

    @Test
    fun run1_indexingOnly_withEveryPluginKeyUnset() {
        AndroidSpikeSupport.record(SPIKE, "preconditions", "db_bytes=${dbFile.length()} max_heap_mb=${Runtime.getRuntime().maxMemory() / MB}")
        assertTrue("database must be at least ${MIN_DB_BYTES / MB}MB (was ${dbFile.length() / MB}MB)", dbFile.length() >= MIN_DB_BYTES)
        assertTrue("heap must be capped at 512MB or less", Runtime.getRuntime().maxMemory() <= HEAP_CAP_BYTES)
        assertFalse("largeHeap must be off", context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0)

        settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true)
        val (controller, pipeline) = newStack()
        measured("run1_indexing_only") {
            controller.onGraphReady(EffectiveNotesPath(root.absolutePath), writeActor = null, fileSystem = fs)
            withTimeout(WAIT_MS) { controller.progress.first { it is AssetIndexProgress.Finished } }
            pipeline.scheduleBackfill()
        }

        val rows = runBlocking { repo.countAssets().getOrNull() ?: -1L }
        AndroidSpikeSupport.record(SPIKE, "run1_rows", "asset_index_rows=$rows unprocessed=${runBlocking { repo.countUnprocessedAssets().getOrNull() }}")
        assertTrue("asset_index must hold at least $MIN_ASSET_ROWS rows (was $rows)", rows >= MIN_ASSET_ROWS)
        assertEquals("no plugin key is set, so the drain never starts", PipelineProgress.Idle, pipeline.progress.value)
        assertEquals("nothing may be marked processed or failed", rows, runBlocking { repo.countUnprocessedAssets().getOrNull() })
        assertEquals(0, labeler.calls.get())
        assertEquals(0, pdf.calls.get())
        assertEquals(0, recognizer.calls.get())
        controller.close()
        pipeline.shutdown()
    }

    @Test
    fun run2_allOnDevicePluginKeysSet() {
        settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true)
        settings.putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS), true)
        settings.putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.PDF_TEXT), true)
        settings.putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.TEXT_RECOGNITION), true)
        val (controller, pipeline) = newStack()
        val rows = runBlocking { repo.countAssets().getOrNull() ?: -1L }
        assertTrue("run1 must have indexed at least $MIN_ASSET_ROWS rows first (was $rows)", rows >= MIN_ASSET_ROWS)

        measured("run2_all_plugin_keys") {
            pipeline.scheduleBackfill()
            withTimeout(WAIT_MS) { pipeline.progress.first { it is PipelineProgress.Finished } }
        }

        val end = pipeline.progress.value
        AndroidSpikeSupport.record(
            SPIKE, "run2_result",
            "progress=$end labeler_calls=${labeler.calls.get()} labeler_bytes_mb=${labeler.bytesSeen.get() / MB} labeler_max_bytes=${labeler.maxBytes.get()} " +
                "ocr_calls=${recognizer.calls.get()} ocr_max_bytes=${recognizer.maxBytes.get()} pdf_calls=${pdf.calls.get()}",
        )
        assertTrue("drain must finish, was $end", end is PipelineProgress.Finished)
        assertEquals("every row reaches a terminal state", 0L, runBlocking { repo.countUnprocessedAssets().getOrNull() })
        assertEquals("every image was labeled", (IMAGE_FILES + BIG_FILE_COUNT).toLong(), labeler.calls.get().toLong())
        assertEquals("every image was read for text", (IMAGE_FILES + BIG_FILE_COUNT).toLong(), recognizer.calls.get().toLong())
        assertTrue(
            "no model saw the original 40 MP file (labeler max ${labeler.maxBytes.get()}, ocr max ${recognizer.maxBytes.get()} bytes)",
            labeler.maxBytes.get() < MAX_UNSCALED_IMAGE_BYTES && recognizer.maxBytes.get() < MAX_UNSCALED_IMAGE_BYTES,
        )
        controller.close()
        pipeline.shutdown()
    }

    companion object {
        private const val SPIKE = "asset-heap-gate"
        private const val MB = 1024L * 1024L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val SAMPLE_INTERVAL_MS = 20L
        private const val WAIT_MS = 20L * 60L * 1000L
        private const val MIN_DB_BYTES = 451L * MB
        private const val MIN_ASSET_ROWS = 9_500L
        private const val HEAP_CAP_BYTES = 512L * MB
        // Set from the first measured run on the Pixel_Fold AVD (run2 peak 49 MB of a 192 MB cap), about 2x headroom.
        private const val HEAP_BUDGET_MB = 100L
        private const val HEAP_BUDGET_FRACTION = 0.85
        private const val IMAGE_FILES = 9_300
        private const val PDF_FILES = 200
        private const val BIG_FILE_COUNT = 10
        private const val PHOTO_WIDTH = 7_000
        private const val PHOTO_HEIGHT = 5_714
        private const val PHOTO_TEXT_PX = 700f
        private const val JPEG_QUALITY = 85
        private const val FILES_PER_DIR = 500
        private const val PAGES_PER_BATCH = 250
        private const val BLOCKS_PER_PAGE = 10
        private const val BLOCK_CONTENT_BYTES = 4_000
        private const val MIN_PAGES = 9_500

        private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val dbName = "asset-heap-gate-${System.nanoTime()}.db"
        private lateinit var dbFile: File
        private lateinit var root: File
        private lateinit var fs: PlatformFileSystem
        private lateinit var repo: SqlDelightAssetRepository
        private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        @BeforeClass
        @JvmStatic
        fun seed() {
            SteleKitContext.init(context)
            DriverFactory.setContext(context)
            fs = PlatformFileSystem().apply { init(context) }
            val driver = DriverFactory().createDriver("jdbc:sqlite:$dbName")
            repo = SqlDelightAssetRepository(SteleDatabase(driver))
            dbFile = context.getDatabasePath(dbName)
            seedGraphRows()
            seedAssetFiles()
            AndroidSpikeSupport.record(SPIKE, "seeded", "db_bytes=${dbFile.length()} files=${IMAGE_FILES + PDF_FILES + BIG_FILE_COUNT}")
        }

        /** Pages and blocks (with FTS triggers) until the database reaches owner scale. */
        private fun seedGraphRows() {
            val filler = "lorem ipsum dolor sit amet ".repeat(BLOCK_CONTENT_BYTES / 27 + 1).take(BLOCK_CONTENT_BYTES)
            val rw = AndroidSpikeSupport.requeryReadWrite(dbFile)
            try {
                var page = 0
                while (page < MIN_PAGES || dbFile.length() < MIN_DB_BYTES) {
                    rw.beginTransaction()
                    repeat(PAGES_PER_BATCH) {
                        rw.execSQL("INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('gate-page-$page','Gate $page',1,1)")
                        for (b in 0 until BLOCKS_PER_PAGE) {
                            rw.execSQL(
                                "INSERT INTO blocks(uuid,page_uuid,content,position,created_at,updated_at) VALUES (?,?,?,?,1,1)",
                                arrayOf("gate-page-$page-b$b", "gate-page-$page", "$filler $page $b", "a$b"),
                            )
                        }
                        page++
                    }
                    rw.setTransactionSuccessful()
                    rw.endTransaction()
                }
                rw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            } finally {
                rw.close()
            }
        }

        /** Real files under the app-owned graph directory the Android file system can reach. */
        private fun seedAssetFiles() {
            root = File(context.filesDir, "graphs/asset-heap-gate-${System.nanoTime()}")
            for (i in 0 until IMAGE_FILES) {
                val dir = File(root, "assets/img${i / FILES_PER_DIR}").apply { mkdirs() }
                File(dir, "img$i.png").writeBytes(png + i.toString().encodeToByteArray())
            }
            for (i in 0 until PDF_FILES) {
                val dir = File(root, "assets/pdf").apply { mkdirs() }
                File(dir, "doc$i.pdf").writeBytes("%PDF-1.4\n%$i\n%%EOF".encodeToByteArray())
            }
            val photo = fortyMegapixelJpeg()
            for (i in 0 until BIG_FILE_COUNT) {
                val dir = File(root, "assets/big").apply { mkdirs() }
                // Bytes after the JPEG end marker are ignored by decoders and keep each content hash distinct.
                File(dir, "photo$i.jpg").writeBytes(photo + ByteArray(i + 1) { (it + i).toByte() })
            }
        }

        /** A real, decodable 7000x5714 (40 MP) JPEG; the file is small but the full decode is 160 MB as ARGB_8888. */
        private fun fortyMegapixelJpeg(): ByteArray {
            val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.RGB_565)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = PHOTO_TEXT_PX }
                canvas.drawText("Invoice 4821", PHOTO_TEXT_PX / 2, PHOTO_HEIGHT / 2f, paint)
                return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            root.deleteRecursively()
            context.deleteDatabase(dbName)
        }
    }
}
