package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.platform.sensor.DepthSensorProvider
import dev.stapler.stelekit.model.NormalizedPoint

/**
 * Calibration fallback chain.
 *
 * Tries calibration sources in descending accuracy order:
 *   1. BLE laser reading (injected externally — caller provides [bleCalibration])
 *   2. Manual reference (injected externally — caller provides [manualCalibration])
 *   3. ARCore / LiDAR depth (via [depthSensorProvider], if [isAvailable])
 *   4. EXIF focal-length math (via [ExifCalibrationService], if [ImageSensorData] has focal data)
 *   5. Monocular ML depth (via [monocularDepthEstimator], if [isAvailable])
 *   6. [CalibrationMethod.NONE] — no calibration available
 *
 * Each skipped step is logged at INFO level with the reason.
 *
 * @param depthSensorProvider       depth hardware abstraction (ARCore/LiDAR/NoOp)
 * @param monocularDepthEstimator   ML depth estimator (ONNX/CoreML/NoOp)
 */
class CalibrationFallbackChain(
    private val depthSensorProvider: DepthSensorProvider,
    private val monocularDepthEstimator: MonocularDepthEstimator,
) {
    private val logger = Logger("CalibrationFallbackChain")

    /**
     * Determine the best available [Calibration] using all available sources.
     *
     * @param bleCalibration        non-null if a BLE laser reading was injected
     * @param manualCalibration     non-null if the user has drawn a reference line
     * @param sensorData            EXIF sensor data for focal-length estimation
     * @param imageWidthPx          native image width (for EXIF math)
     * @param depthTapPoint         normalized tap point for depth-based calibration
     * @param mlDepthMap            depth map from ML estimator (may be null)
     * @param depthHintMeters       optional depth hint for EXIF estimation
     * @return the best [Calibration] available, or a [CalibrationMethod.NONE] calibration
     */
    suspend fun resolve(
        bleCalibration: Calibration? = null,
        manualCalibration: Calibration? = null,
        sensorData: ImageSensorData? = null,
        imageWidthPx: Double = 0.0,
        depthTapPoint: NormalizedPoint? = null,
        mlDepthMap: FloatArray? = null,
        imageHeightPx: Double = 0.0,
        depthHintMeters: Double? = null,
    ): Calibration {

        // 1. BLE laser — highest accuracy (±1 mm)
        if (bleCalibration != null) {
            logger.info("CalibrationFallbackChain: using BLE_LASER calibration (±1mm)")
            return bleCalibration
        }
        logger.info("CalibrationFallbackChain: BLE_LASER not available — no laser reading injected")

        // 2. Manual reference object — 100% confidence by definition
        if (manualCalibration != null) {
            logger.info("CalibrationFallbackChain: using MANUAL_REFERENCE calibration (100% confidence)")
            return manualCalibration
        }
        logger.info("CalibrationFallbackChain: MANUAL_REFERENCE not available — no reference line drawn")

        // 3. ARCore / LiDAR depth
        if (depthSensorProvider.isAvailable && depthTapPoint != null && imageWidthPx > 0.0) {
            val frameResult = depthSensorProvider.acquireDepthFrame()
            frameResult.fold(
                ifLeft = { err ->
                    logger.info("CalibrationFallbackChain: depth sensor skipped — ${err.message}")
                },
                ifRight = { frame ->
                    if (frame != null) {
                        val cal = CalibrationService.computeFromDepthFrame(frame, depthTapPoint, imageWidthPx)
                        if (cal != null) {
                            logger.info(
                                "CalibrationFallbackChain: using ${cal.method} calibration " +
                                    "(confidence ${cal.confidencePercent}%)",
                            )
                            return cal
                        } else {
                            logger.info("CalibrationFallbackChain: depth frame returned zero/no-confidence depth at tap point")
                        }
                    } else {
                        logger.info("CalibrationFallbackChain: depth sensor available but no frame ready")
                    }
                },
            )
        } else {
            logger.info(
                "CalibrationFallbackChain: ARCORE_DEPTH skipped — " +
                    "isAvailable=${depthSensorProvider.isAvailable}, " +
                    "tapPoint=$depthTapPoint, imageWidth=$imageWidthPx",
            )
        }

        // 4. EXIF focal-length math (±15%)
        if (sensorData != null && imageWidthPx > 0.0) {
            val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx, depthHintMeters)
            if (cal != null) {
                logger.info("CalibrationFallbackChain: using EXIF_FOCAL calibration (±15%, confidence 20%)")
                return cal
            } else {
                logger.info("CalibrationFallbackChain: EXIF_FOCAL skipped — focal length data absent in EXIF")
            }
        } else {
            logger.info("CalibrationFallbackChain: EXIF_FOCAL skipped — no sensor data or image width")
        }

        // 5. Monocular ML depth (±15%, last resort)
        val mlReady = monocularDepthEstimator.isAvailable && mlDepthMap != null
        val mlParamsValid = depthTapPoint != null && imageWidthPx > 0.0 && imageHeightPx > 0.0
        if (mlReady && mlParamsValid) {
            val cal = CalibrationService.computeFromMLDepth(mlDepthMap, depthTapPoint, imageWidthPx, imageHeightPx)
            if (cal != null) {
                logger.info(
                    "CalibrationFallbackChain: using MONOCULAR_ML calibration (±15%, confidence 15%)",
                )
                return cal
            } else {
                logger.info("CalibrationFallbackChain: MONOCULAR_ML depth map returned zero depth at tap point")
            }
        } else {
            logger.info(
                "CalibrationFallbackChain: MONOCULAR_ML skipped — " +
                    "ready=$mlReady, paramsValid=$mlParamsValid",
            )
        }

        // 6. No calibration available
        logger.info("CalibrationFallbackChain: all methods exhausted — returning NONE")
        return Calibration(method = CalibrationMethod.NONE, pixelsPerMeter = 0.0, confidencePercent = 0)
    }
}
