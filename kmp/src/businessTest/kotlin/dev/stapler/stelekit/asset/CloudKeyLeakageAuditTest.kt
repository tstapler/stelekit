package dev.stapler.stelekit.asset

import dev.stapler.stelekit.asset.pipeline.CloudBackfillScope
import dev.stapler.stelekit.asset.pipeline.CloudEnrichmentConfig
import dev.stapler.stelekit.asset.pipeline.CloudProvider
import dev.stapler.stelekit.asset.pipeline.CloudRig
import dev.stapler.stelekit.asset.pipeline.TEST_KEY
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Anthropic key is only ever a request header: not in logs, settings, errors, URLs or bodies. */
class CloudKeyLeakageAuditTest {
    @Test
    fun anthropicKey_should_AppearInNoLogSettingOrDiagnosticsExport_When_TestKeyAndCallsMade() = runTest {
        val logs = mutableListOf<String>()
        val handle = LogManager.addSink(object : LogSink {
            override fun write(entry: LogEntry, formatted: String) { logs += formatted }
        })
        val leaks = mutableListOf<String>()
        try {
            for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests)) {
                val rig = CloudRig(status = status)
                rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
                val result = rig.plugin.processAsset(rig.putImage("a1"))

                leaks += rig.settings.store.filterValues { TEST_KEY in it }.keys.map { "settings key $it" }
                if (TEST_KEY in result.toString()) leaks += "result for $status"
                rig.http.requests.forEach { request ->
                    if (TEST_KEY in request.url.toString()) leaks += "url for $status"
                    assertEquals(TEST_KEY, request.headers["x-api-key"])
                }
                if (TEST_KEY in rig.http.bodyText()) leaks += "body for $status"
            }
        } finally {
            handle.close()
        }
        if (logs.any { TEST_KEY in it }) leaks += "log output"
        assertTrue(CloudEnrichmentConfig(CloudProvider.CLAUDE).toString().contains(TEST_KEY).not())

        assertEquals(emptyList(), leaks)
        assertFalse(logs.isEmpty(), "the audit must have seen real log lines")
    }
}
