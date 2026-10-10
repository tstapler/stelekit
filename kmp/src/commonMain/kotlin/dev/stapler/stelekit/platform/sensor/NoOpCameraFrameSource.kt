package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Camera frame source for platforms without a streaming camera implementation
 * (JVM desktop, WASM, and any platform not yet wired to a real [CameraFrameSource]).
 *
 * Always reports [isAvailable] = `false` and [frameStream] emits nothing.
 */
class NoOpCameraFrameSource : CameraFrameSource {

    override val isAvailable: Boolean = false

    override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
}
