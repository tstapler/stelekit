package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.ImageSensorData

/**
 * A platform-obtained image file ready for import into the annotation pipeline.
 *
 * [path] is an absolute, stable file path that can be passed to [FileSystem.readFileBytes].
 * On Android this is always a path inside the app's cache dir — never a content:// URI.
 *
 * [mimeType] is the MIME type of the image (e.g. "image/jpeg", "image/png").
 *
 * [capturedAtMs] is the capture epoch millisecond, or null when unknown (imported from file).
 *
 * [focalLengthMm], [focalLength35mmEq], [cameraMake], [cameraModel] are extracted from
 * EXIF metadata where available.
 *
 * [sensorData] is a snapshot of motion/GPS sensor readings at the moment of capture,
 * obtained from [MotionSensorProvider.sensorDataFlow]. All fields are nullable — null means
 * the sensor was unavailable or permission was denied at capture time. This field is null
 * when the image was imported from a file (not captured live).
 */
data class PlatformImageFile(
    val path: String,
    val mimeType: String = "image/jpeg",
    val capturedAtMs: Long? = null,
    val focalLengthMm: Double? = null,
    val focalLength35mmEq: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val sensorData: ImageSensorData? = null,
)
