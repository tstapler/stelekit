package dev.stapler.stelekit.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compile-time exhaustiveness guard for [HostWritePayload] — `flushHostWrite` (Task 4.2.2a)
 * dispatches on this type with a `when` and no `else` branch, so this test fails to *compile*
 * (not just fails at runtime) if a variant is ever added or removed without updating every
 * dispatch site. See Task 1.4.1d in
 * `project_plans/web-local-folder-livesync/implementation/plan.md`.
 */
class HostWritePayloadTest {

    @Test
    fun hostWritePayload_should_ExposeExactlyThreeVariants_When_FlushHostWriteDispatchesExhaustively() {
        val payloads: List<HostWritePayload> = listOf(
            HostWritePayload.Text("content"),
            HostWritePayload.Bytes(byteArrayOf(1, 2, 3)),
            HostWritePayload.Delete,
        )

        val labels = payloads.map { payload ->
            // Exhaustive `when` with no `else` branch — will not compile if a variant is added
            // to HostWritePayload without a corresponding branch here.
            when (payload) {
                is HostWritePayload.Text -> "text:${payload.content}"
                is HostWritePayload.Bytes -> "bytes:${payload.data.size}"
                is HostWritePayload.Delete -> "delete"
            }
        }

        assertEquals(listOf("text:content", "bytes:3", "delete"), labels)
    }
}
