package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

/** iOS Core ML image labeler — stub until VNImageRequestHandler Kotlin/Native bindings are wired. */
class CoreMlImageLabeler : ImageLabeler {
    override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> =
        emptyList<Label>().right()
}
