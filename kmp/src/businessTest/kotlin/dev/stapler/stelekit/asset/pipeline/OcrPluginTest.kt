package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer
import dev.stapler.stelekit.platform.ml.TextRecognizer
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** OCR plugin, the heap-safe decode shared with the labeling plugin, and the stored-text cap. */
class OcrPluginTest {
    private val fs = FakeFileSystem()
    private val path = "/g/assets/scan.png"

    private fun asset(
        type: AssetMediaType = AssetMediaType.IMAGE,
        processed: Boolean = false,
    ) = AssetEntry(
        uuid = AssetUuid("a1"), filePath = path, relativePath = "../assets/scan.png",
        mediaType = type, subfolder = "assets", tags = emptyList(), autoLabels = emptyList(),
        ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 1L,
        importedAtMs = 1L, mlProcessed = processed, mlAttemptedAt = null, mlFailed = false,
        contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    private class RecordingRecognizer(private val answer: Either<DomainError, String>) : TextRecognizer {
        var received: ByteArray? = null
        override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> {
            received = imageBytes
            return answer
        }
    }

    private class RecordingLabeler : ImageLabeler {
        var received: ByteArray? = null
        override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
            received = imageBytes
            return listOf(Label("cat", 0.9f)).right()
        }
    }

    /** Stands in for the platform decoder: returns [scaled] for the requested edge, recording it. */
    private class FakeDownscaler(private val scaled: ByteArray?, private val supported: Boolean = true) : ImageDownscalerPort {
        val requestedEdges = mutableListOf<Int>()
        override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? {
            requestedEdges += maxEdges
            return if (supported) maxEdges.map { scaled } else null
        }
    }

    private val smallJpeg = ByteArray(2_000) { 1 }
    private val fortyMegapixelFile = ByteArray(MAX_UNSCALED_IMAGE_BYTES + 1) { 7 }

    @Test
    fun recognizedText_becomesOcrText() = runTest {
        fs.writeFileBytes(path, byteArrayOf(1, 2, 3))
        val plugin = OcrPlugin(RecordingRecognizer("Invoice 4821".right()), fs, FakeDownscaler(smallJpeg))

        val result = plugin.processAsset(asset())

        assertEquals(AssetPipelineResult.OcrText("Invoice 4821"), result.getOrNull())
    }

    @Test
    fun recognizerLeft_isPassedThrough_soTheAssetIsMarkedFailed() = runTest {
        fs.writeFileBytes(path, byteArrayOf(1))
        val error = DomainError.DatabaseError.ReadFailed("ml kit down")
        val plugin = OcrPlugin(RecordingRecognizer(error.left()), fs, FakeDownscaler(smallJpeg))

        assertEquals(error, plugin.processAsset(asset()).leftOrNull())
    }

    @Test
    fun unreadableFile_isLeft() = runTest {
        val plugin = OcrPlugin(RecordingRecognizer("x".right()), fs, FakeDownscaler(smallJpeg))

        assertIs<DomainError.DatabaseError.ReadFailed>(plugin.processAsset(asset()).leftOrNull())
    }

    @Test
    fun recognizerThrowing_isLeft_notACrash() = runTest {
        fs.writeFileBytes(path, byteArrayOf(1))
        val throwing = object : TextRecognizer {
            override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> = error("boom")
        }

        val result = OcrPlugin(throwing, fs, FakeDownscaler(smallJpeg)).processAsset(asset())

        assertTrue(result.leftOrNull()!!.message.contains("boom"))
    }

    @Test
    fun onlyUnprocessedImagesAreHandled() {
        val plugin = OcrPlugin(NoOpTextRecognizer(), fs, FakeDownscaler(null))

        assertTrue(plugin.canProcess(asset()))
        assertFalse(plugin.canProcess(asset(AssetMediaType.PDF)))
        assertFalse(plugin.canProcess(asset(processed = true)))
    }

    @Test
    fun noOpRecognizer_returnsEmptyText() = runTest {
        assertEquals("", NoOpTextRecognizer().recognize(byteArrayOf(1)).getOrNull())
    }

    // ── Heap-safe decode ─────────────────────────────────────────────────────────────────────

    @Test
    fun ocrReceivesTheDownscaledCopy_neverTheLargeOriginal() = runTest {
        fs.writeFileBytes(path, fortyMegapixelFile)
        val recognizer = RecordingRecognizer("ok".right())
        val downscaler = FakeDownscaler(smallJpeg)

        OcrPlugin(recognizer, fs, downscaler).processAsset(asset())

        assertEquals(listOf(OCR_MAX_EDGE_PX), downscaler.requestedEdges)
        assertTrue(smallJpeg.contentEquals(recognizer.received))
        assertTrue(recognizer.received!!.size < MAX_UNSCALED_IMAGE_BYTES)
    }

    @Test
    fun labelerReceivesTheDownscaledCopy_neverTheLargeOriginal() = runTest {
        fs.writeFileBytes(path, fortyMegapixelFile)
        val labeler = RecordingLabeler()
        val downscaler = FakeDownscaler(smallJpeg)

        OnDeviceLabelingPlugin(labeler, fs, downscaler).processAsset(asset())

        assertEquals(listOf(LABELING_MAX_EDGE_PX), downscaler.requestedEdges)
        assertTrue(smallJpeg.contentEquals(labeler.received))
    }

    @Test
    fun largeOriginal_withNoDownscaledCopy_isRefused_notDecodedAtFullSize() = runTest {
        fs.writeFileBytes(path, fortyMegapixelFile)
        val recognizer = RecordingRecognizer("ok".right())

        val unsupported = OcrPlugin(recognizer, fs, FakeDownscaler(null, supported = false)).processAsset(asset())
        val failedCopy = OcrPlugin(recognizer, fs, FakeDownscaler(null)).processAsset(asset())

        assertNotNull(unsupported.leftOrNull())
        assertNotNull(failedCopy.leftOrNull())
        assertEquals(null, recognizer.received, "the recognizer never saw the original")
    }

    @Test
    fun smallOriginal_isPassedAsIs_whenThereIsNothingToShrink() = runTest {
        val small = byteArrayOf(9, 9, 9)
        fs.writeFileBytes(path, small)
        val recognizer = RecordingRecognizer("ok".right())

        OcrPlugin(recognizer, fs, FakeDownscaler(null)).processAsset(asset())
        assertTrue(small.contentEquals(recognizer.received))

        recognizer.received = null
        OcrPlugin(recognizer, fs, FakeDownscaler(null, supported = false)).processAsset(asset())
        assertTrue(small.contentEquals(recognizer.received))
    }

    @Test
    fun throwingDownscaler_fallsBackToASmallOriginal_butStillRefusesALargeOne() = runTest {
        val throwing = object : ImageDownscalerPort {
            override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? = error("decoder blew up")
        }
        val recognizer = RecordingRecognizer("ok".right())
        fs.writeFileBytes(path, byteArrayOf(5))
        assertEquals("ok", OcrPlugin(recognizer, fs, throwing).processAsset(asset()).getOrNull().let { (it as AssetPipelineResult.OcrText).text })

        fs.writeFileBytes(path, fortyMegapixelFile)
        assertNotNull(OcrPlugin(recognizer, fs, throwing).processAsset(asset()).leftOrNull())
    }

    // ── Stored text cap ──────────────────────────────────────────────────────────────────────

    @Test
    fun truncateUtf8_leavesShortTextAlone() {
        assertEquals("hello", truncateUtf8("hello"))
    }

    @Test
    fun truncateUtf8_cutsAtTheByteLimit() {
        assertEquals("abc", truncateUtf8("abcdef", maxBytes = 3))
    }

    @Test
    fun truncateUtf8_neverSplitsAMultiByteCodePoint() {
        // "é" is 2 bytes, "€" 3 bytes, the emoji is 4 bytes (a surrogate pair).
        assertEquals("a", truncateUtf8("aé", maxBytes = 2))
        assertEquals("aé", truncateUtf8("aé€", maxBytes = 4))
        assertEquals("a", truncateUtf8("a😀", maxBytes = 4))
        assertEquals("a😀", truncateUtf8("a😀b", maxBytes = 5))
    }

    @Test
    fun truncateUtf8_keepsALoneSurrogateAsOneUnit() {
        assertEquals("\uD83Dx", truncateUtf8("\uD83Dx", maxBytes = 10))
    }

    @Test
    fun aFiveMegabytePdfText_isStoredAtTheCap() = runTest {
        val repo = InMemoryAssetRepository()
        val entry = asset(AssetMediaType.PDF)
        repo.saveAsset(entry)
        val huge = "x".repeat(5 * 1024 * 1024)
        val registry = PluginRegistry()
        registry.register(object : AssetPipelinePlugin {
            override val id = "big"
            override fun canProcess(asset: AssetEntry) = true
            override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> =
                AssetPipelineResult.OcrText(huge).right()
        })
        val service = AssetPipelineService(registry, repo, DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()))

        service.processAsset(entry)

        val stored = repo.getAssetByUuid(entry.uuid).first().getOrNull()!!
        assertEquals(MAX_OCR_TEXT_BYTES, stored.ocrText!!.length)
        assertTrue(stored.mlProcessed)
        service.shutdown()
    }
}
