package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.platform.ml.NoOpMonocularDepthEstimator
import kotlin.concurrent.Volatile

/**
 * Holds the platform-appropriate sensor providers for the current target.
 *
 * Analogous to [dev.stapler.stelekit.repository.RepositoryFactory]: callers depend on the
 * interface and the platform entry point wires in the concrete implementation at startup.
 *
 * Usage:
 * ```kotlin
 * // In androidMain entry point:
 * SensorModule.cameraProvider = AndroidCameraProvider(context, activity)
 * SensorModule.cameraFrameSource = AndroidCameraFrameSource(context, activity)
 * SensorModule.motionSensorProvider = AndroidMotionSensorProvider(context)
 * SensorModule.depthSensorProvider = ARCoreDepthProvider()
 * SensorModule.monocularDepthEstimator = OnnxMonocularDepthEstimator()
 *
 * // In jvmMain entry point:
 * SensorModule.cameraProvider = NoOpCameraProvider()
 * SensorModule.motionSensorProvider = NoOpMotionSensorProvider()
 * SensorModule.depthSensorProvider = NoOpDepthProvider()
 * // monocularDepthEstimator defaults to NoOp
 * ```
 *
 * All defaults are no-op implementations so the common code compiles and runs safely on any
 * platform before the entry point has configured the module.
 */
object SensorModule {

    /**
     * The active [CameraProvider] for this process.
     *
     * Set once at application startup by the platform entry point.
     * Thread-safe for simple read after startup (Kotlin `@Volatile`).
     */

    @Volatile var cameraProvider: CameraProvider = NoOpCameraProvider()

    /**
     * The active [CameraFrameSource] for this process.
     *
     * Provides a continuous camera luminance-frame stream, e.g. for QR code decode. Kept
     * separate from [cameraProvider] (ISP, see ADR-002 in camera-qr-export).
     * Set once at application startup by the platform entry point.
     * Thread-safe for simple read after startup (Kotlin `@Volatile`).
     */

    @Volatile var cameraFrameSource: CameraFrameSource = NoOpCameraFrameSource()

    /**
     * The active [MotionSensorProvider] for this process.
     *
     * Provides GPS, compass bearing, and accelerometer pitch/roll data.
     * Set once at application startup by the platform entry point.
     * Thread-safe for simple read after startup (Kotlin `@Volatile`).
     */

    @Volatile var motionSensorProvider: MotionSensorProvider = NoOpMotionSensorProvider()

    /**
     * The active [DepthSensorProvider] for this process.
     *
     * Provides ARCore (Android) or LiDAR (iOS) depth frame acquisition.
     * Set once at application startup by the platform entry point.
     * Thread-safe for simple read after startup (Kotlin `@Volatile`).
     */

    @Volatile var depthSensorProvider: DepthSensorProvider = NoOpDepthProvider()

    /**
     * The active [MonocularDepthEstimator] for this process.
     *
     * Default is [NoOpMonocularDepthEstimator] on all platforms until the ONNX Runtime
     * dependency and model asset are added.
     * Set once at application startup by the platform entry point.
     * Thread-safe for simple read after startup (Kotlin `@Volatile`).
     */

    @Volatile var monocularDepthEstimator: MonocularDepthEstimator = NoOpMonocularDepthEstimator()
}
