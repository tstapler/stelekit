// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositoryFactoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coverage for [CaptureWriter.writeCapture] against the real `SQLDELIGHT` backend. The unit
 * tests in [CaptureWriterTest] use `IN_MEMORY`, which never opens a real SQLite file and never
 * exercises [dev.stapler.stelekit.db.GraphWriter]'s real markdown write. This proves the block
 * survives a fresh reload of the database (a second [RepositoryFactoryImpl] against the same
 * SQLite file, standing in for an app restart) and that the markdown file on disk actually
 * contains the captured text.
 */
private const val CAPTURED_TEXT = "Buy milk from the store"

class CaptureWriterIntegrationTest {

    @Test
    fun writeCapture_should_PersistBlockToDatabaseAndDisk_When_UsingRealSqlDelightBackendAndGraphWriter() =
        runBlocking {
            val graphDir = Files.createTempDirectory("capture-writer-integration").toString()
            val jdbcUrl = "jdbc:sqlite:${File(graphDir, "graph.db").absolutePath}"
            // GraphWriter.savePage() enforces a security whitelist on the real PlatformFileSystem —
            // registerGraphRoot()/withRoot() must match the graph path or the markdown write
            // silently resolves to Failed even though the DB write lands (see the same note in
            // PendingCapturePollerTest).
            val fileSystem = PlatformFileSystem.withRoot(graphDir)

            val savedPageUuid = writeCaptureAndClose(jdbcUrl, fileSystem, graphDir)
            assertBlockSurvivesFreshReload(jdbcUrl, savedPageUuid)
            assertMarkdownFileOnDiskContainsCapturedText(graphDir)
        }

    /** Writes one capture via a real SQLDELIGHT-backed [RepositoryFactoryImpl], then closes it. */
    private suspend fun writeCaptureAndClose(
        jdbcUrl: String,
        fileSystem: PlatformFileSystem,
        graphDir: String,
    ): dev.stapler.stelekit.model.PageUuid {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = RepositoryFactoryImpl(DriverFactory(), jdbcUrl)
        try {
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            requireNotNull(repoSet.writeActor) { "expected a live writeActor for the SQLDELIGHT backend" }

            val result = CaptureWriter.writeCapture(repoSet, fileSystem, graphDir, CAPTURED_TEXT)
            return assertIs<CaptureResult.Saved>(result).page.uuid
        } finally {
            scope.cancel()
            factory.close()
        }
    }

    /**
     * Fresh reload: a brand-new [RepositoryFactoryImpl]/`RepositorySet` pointed at the same
     * SQLite file, standing in for the app being restarted.
     */
    private suspend fun assertBlockSurvivesFreshReload(jdbcUrl: String, pageUuid: dev.stapler.stelekit.model.PageUuid) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = RepositoryFactoryImpl(DriverFactory(), jdbcUrl)
        try {
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            val blocks = repoSet.blockRepository.getBlocksForPage(pageUuid).first().getOrNull().orEmpty()
            assertEquals(
                1,
                blocks.count { it.content == CAPTURED_TEXT },
                "expected the captured block to round-trip from a fresh RepositorySet reload",
            )
        } finally {
            scope.cancel()
            factory.close()
        }
    }

    private fun assertMarkdownFileOnDiskContainsCapturedText(graphDir: String) {
        val markdownFiles = File(graphDir).walkTopDown().filter { it.extension == "md" }.toList()
        assertTrue(markdownFiles.isNotEmpty(), "expected GraphWriter to have written a markdown file to disk")
        val content = markdownFiles.joinToString("\n") { it.readText() }
        assertTrue(
            content.contains(CAPTURED_TEXT),
            "expected the markdown file on disk to contain the captured text",
        )
    }
}
