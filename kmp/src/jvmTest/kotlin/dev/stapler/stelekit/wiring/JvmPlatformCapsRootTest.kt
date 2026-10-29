// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only (the real desktop root factory); needs no display

package dev.stapler.stelekit.wiring

import dev.stapler.stelekit.platform.ml.JvmPdfTextExtractor
import dev.stapler.stelekit.platform.ml.NoOpImageLabeler
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer
import dev.stapler.stelekit.platform.ml.OnnxImageLabeler
import dev.stapler.stelekit.platform.ml.jvmPlatformCaps
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs

/** The real desktop root factory: PDF text is real, and the labeler is real only when a model file exists. */
class JvmPlatformCapsRootTest {
    private val dir: File = createTempDirectory("jvm_caps_").toFile()

    @Test
    fun withoutAModelFile_theLabelerIsNoOp_andPdfTextIsReal() {
        val caps = jvmPlatformCaps(modelFile = File(dir, "missing.onnx"))

        assertIs<NoOpImageLabeler>(caps.imageLabeler)
        assertIs<JvmPdfTextExtractor>(caps.pdfText)
    }

    @Test
    fun theTextRecognizerIsNoOp_becauseDesktopOcrIsNotShipped() {
        assertIs<NoOpTextRecognizer>(jvmPlatformCaps(modelFile = File(dir, "missing.onnx")).textRecognizer)
    }

    @Test
    fun withAModelFile_theLabelerIsTheOnnxLabeler() {
        val model = File(dir, "model.onnx").apply { writeBytes(byteArrayOf(0)) }

        val caps = jvmPlatformCaps(modelFile = model)

        assertIs<OnnxImageLabeler>(caps.imageLabeler)
    }
}
