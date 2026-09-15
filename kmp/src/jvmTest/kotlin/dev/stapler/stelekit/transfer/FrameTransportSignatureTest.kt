package dev.stapler.stelekit.transfer

import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 2.1.1 acceptance criteria: [FrameTransportSender.send] is `suspend` and takes
 * `Flow<ByteArray>`, and neither seam interface's signature references a QR-specific type
 * (keeps `FrameTransport` medium-neutral per ADR-006).
 */
class FrameTransportSignatureTest {

    @Test
    fun frameTransportSender_should_ExposeSuspendSendOfByteArrayFlow_When_InterfaceInspected() {
        val send: KFunction<*> = FrameTransportSender::send
        assertTrue(send.isSuspend, "FrameTransportSender.send must be a suspend function")

        val framesParam = send.parameters.first { it.kind == KParameter.Kind.VALUE }
        val paramType = framesParam.type.toString()
        assertTrue(
            paramType.contains("Flow") && paramType.contains("ByteArray"),
            "send's frames parameter must be Flow<ByteArray>, was $paramType",
        )
    }

    @Test
    fun frameTransport_should_ContainNoQrSpecificTypesInSignature_When_InterfaceInspected() {
        val send: KFunction<*> = FrameTransportSender::send
        val frames: KFunction<*> = FrameTransportReceiver::frames

        val signature = (
            send.parameters.map { it.type.toString() } + send.returnType.toString() +
                frames.parameters.map { it.type.toString() } + frames.returnType.toString()
            ).joinToString()

        assertFalse(signature.contains("QrMatrix"), "FrameTransport signatures must not reference QrMatrix")
        assertFalse(signature.contains("CameraFrame"), "FrameTransport signatures must not reference CameraFrame")
    }
}
