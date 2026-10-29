package dev.stapler.stelekit.platform.ml

import dev.stapler.stelekit.asset.pipeline.PlatformCaps
import java.io.File

/** Where the optional ONNX image-label model lives; absent by default. */
fun defaultOnnxModelFile(): File =
    File(System.getProperty("user.home"), ".stelekit/models/image_labeler.onnx")

/**
 * The desktop root's on-device capabilities. Without a model file the image labeler is
 * [NoOpImageLabeler] (reported as unavailable, never a silently failing plugin).
 */
fun jvmPlatformCaps(modelFile: File = defaultOnnxModelFile()): PlatformCaps = PlatformCaps(
    imageLabeler = if (modelFile.isFile) OnnxImageLabeler(modelFile.path) else NoOpImageLabeler(),
    pdfText = JvmPdfTextExtractor(),
)
