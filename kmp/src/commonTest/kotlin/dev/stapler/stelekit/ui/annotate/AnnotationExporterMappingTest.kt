package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertIs

class AnnotationExporterMappingTest {

    @Test
    fun mapEncodeResult_nonEmptyBytes_returnsRight() {
        val result = mapEncodeResult(byteArrayOf(1, 2, 3))

        assertIs<Either.Right<ByteArray>>(result)
    }

    @Test
    fun mapEncodeResult_emptyBytes_returnsLeftEncodingFailed() {
        val result = mapEncodeResult(ByteArray(0))

        assertIs<Either.Left<DomainError.ExportError>>(result)
        assertIs<DomainError.ExportError.EncodingFailed>(result.value)
    }
}
