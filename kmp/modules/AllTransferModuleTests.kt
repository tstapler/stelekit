package dev.stapler.stelekit.transfer

import dev.stapler.stelekit.transfer.Crc32Test
import dev.stapler.stelekit.transfer.TransferTypesTest
import dev.stapler.stelekit.transfer.qrcode.ChunkBufferConcurrencyTest
import dev.stapler.stelekit.transfer.qrcode.ChunkBufferTest
import dev.stapler.stelekit.transfer.qrcode.ChunkFrameCodecTest
import dev.stapler.stelekit.transfer.qrcode.FountainCodecRoundTripTest
import dev.stapler.stelekit.transfer.qrcode.FountainCodecVectorTest
import dev.stapler.stelekit.transfer.qrcode.FountainDecoderTest
import dev.stapler.stelekit.transfer.qrcode.FountainEncoderPreflightTest
import dev.stapler.stelekit.transfer.qrcode.FountainEncoderTest
import dev.stapler.stelekit.transfer.qrcode.QrFrameTransportTest
import dev.stapler.stelekit.transfer.qrcode.QrScannerTest
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettingsTest
import dev.stapler.stelekit.transfer.qrcode.TransferPayloadEnvelopeTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * The commonTest tests of the `transfer` module (W9 stage A, G7). Runs against //kmp/modules:transfer only, so an edit to
 * any other module (git, merge, the UI, ...) does not rebuild or rerun it. Not part of AllBusinessTests/AllJvmTests.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    Crc32Test::class,
    TransferTypesTest::class,
    ChunkBufferConcurrencyTest::class,
    ChunkBufferTest::class,
    ChunkFrameCodecTest::class,
    FountainCodecRoundTripTest::class,
    FountainCodecVectorTest::class,
    FountainDecoderTest::class,
    FountainEncoderPreflightTest::class,
    FountainEncoderTest::class,
    QrFrameTransportTest::class,
    QrScannerTest::class,
    QrTransferSettingsTest::class,
    TransferPayloadEnvelopeTest::class,
)
class AllTransferModuleTests
