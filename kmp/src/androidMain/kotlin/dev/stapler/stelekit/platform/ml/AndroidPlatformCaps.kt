package dev.stapler.stelekit.platform.ml

import dev.stapler.stelekit.asset.pipeline.PlatformCaps

/** The Android root's on-device capabilities. Construction only: no model loads until first use. */
fun androidPlatformCaps(): PlatformCaps = PlatformCaps(
    imageLabeler = MlKitImageLabeler(),
    pdfText = AndroidPdfTextExtractor(),
    textRecognizer = MlKitTextRecognizer(),
)
