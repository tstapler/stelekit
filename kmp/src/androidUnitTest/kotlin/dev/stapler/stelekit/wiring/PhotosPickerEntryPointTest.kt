// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.google.AndroidGoogleIntegration
import dev.stapler.stelekit.platform.google.DriveBrowserPort
import dev.stapler.stelekit.platform.google.DriveFile
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import dev.stapler.stelekit.platform.google.GoogleBuildFlags
import dev.stapler.stelekit.platform.google.GoogleIntegration
import dev.stapler.stelekit.platform.google.GooglePhotosImportState
import dev.stapler.stelekit.platform.google.PhotosPicker
import dev.stapler.stelekit.platform.google.PickedPhoto
import dev.stapler.stelekit.platform.google.PickedPhotos
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.MediaAttachmentService
import dev.stapler.stelekit.ui.GraphContentAssetStack
import dev.stapler.stelekit.ui.components.GooglePhotosEntry
import dev.stapler.stelekit.ui.rememberGraphContentAssetStack
import dev.stapler.stelekit.ui.rememberGraphContentGooglePhotosEntry
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import dev.stapler.stelekit.ui.AppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 8.1 wiring proof (Robolectric, in-memory backend, see [WiredAppFixture.android]): the real
 * `rememberGraphContentGooglePhotosEntry` over the real asset stack saves two picked photos through
 * the indexing attachment decorator, so both appear in `asset_index`, and the markdown for both is
 * inserted. The Google API itself is a fake: no network, and the OAuth device pass stays the owner's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PhotosPickerEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val graphPath = "/test/wired-graph"
    private val dataDir: File = createTempDirectory("photos_picker_entry_").toFile()
    private var fixture: WiredAppFixture? = null

    @After
    fun tearDown() {
        AssetBuildFlags.internalBuild = false
        GoogleBuildFlags.internalBuild = false
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private class FsAttachments(private val fs: FakeFileSystem) : MediaAttachmentService {
        override suspend fun pickAndAttach(notesPath: EffectiveNotesPath, pageRelativePath: String): Either<DomainError, AttachmentResult>? = null
        override suspend fun attachBytes(bytes: ByteArray, suggestedName: String, notesPath: EffectiveNotesPath): Either<DomainError, AttachmentResult> {
            fs.writeFileBytes(notesPath.child("assets/$suggestedName"), bytes)
            return AttachmentResult("../assets/$suggestedName", suggestedName).right()
        }
    }

    private class TwoPhotos : PhotosPicker {
        var launches = 0
        override suspend fun launchPicker(): Either<DomainError, PickedPhotos> {
            launches++
            return PickedPhotos(
                listOf(PickedPhoto(byteArrayOf(1, 2, 3), "photo-a"), PickedPhoto(byteArrayOf(4, 5, 6), "photo-b")),
                failedDownloads = 0,
            ).right()
        }
    }

    private class FakeAuth(private val signedIn: Boolean) : GoogleAuthManager {
        override suspend fun authenticate(): Either<DomainError, String> = "me@example.com".right()
        override suspend fun signOut() {}
        override suspend fun isAuthenticated() = signedIn
        override suspend fun getConnectedEmail(): String? = if (signedIn) "me@example.com" else null
    }

    private object NoDrive : DriveUploader, DriveBrowserPort {
        override suspend fun uploadFile(fileName: String, mimeType: String, bytes: ByteArray, parentFolderId: String?) = "id".right()
        override suspend fun listFiles(folderId: String?): Either<DomainError, List<DriveFile>> = emptyList<DriveFile>().right()
        override suspend fun downloadFile(fileId: String): Either<DomainError, ByteArray> = DomainError.NetworkError.HttpError(404, "x").left()
    }

    private class Mounted(
        val wired: WiredAppFixture,
        val entry: () -> GooglePhotosEntry?,
        val stack: () -> GraphContentAssetStack?,
        val inserted: MutableList<Pair<BlockUuid, String>>,
    )

    private fun mount(picker: PhotosPicker, signedIn: Boolean = true, withAttachments: Boolean = true): Mounted {
        AssetBuildFlags.internalBuild = true
        val fs = FakeFileSystem()
        fs.writeFileBytes("$graphPath/assets/existing.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0))
        val ports = FakePlatformPorts(fileSystem = fs)
        ports.settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true)
        val wired = runBlocking { WiredAppFixture.android(ApplicationProvider.getApplicationContext<Context>(), dataDir, ports, graphPath) }
        fixture = wired
        val appState = MutableStateFlow(AppState(currentGraphPath = graphPath, isFullyLoaded = true))
        val deps = wired.deps.copy(
            platformIntegrations = wired.deps.platformIntegrations.copy(attachmentService = if (withAttachments) FsAttachments(fs) else null),
        )
        val google = GoogleIntegration(FakeAuth(signedIn), NoDrive, NoDrive, photosPicker = picker)
        val inserted = mutableListOf<Pair<BlockUuid, String>>()
        var entry: GooglePhotosEntry? = null
        var stack: GraphContentAssetStack? = null
        composeRule.setContent {
            val vault = rememberGraphContentVaultSetup(wired.graphManager, fs, null)
            val io = rememberGraphContentGraphIoStack(
                deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo, Logger("PhotosPickerEntryPointTest"),
            )
            val assets = rememberGraphContentAssetStack(deps, vault.effectiveFileSystem, io, appState, Logger("PhotosPickerEntryPointTest"))
            stack = assets
            entry = rememberGraphContentGooglePhotosEntry(
                google = google,
                attachments = assets.attachmentService,
                notesPath = EffectiveNotesPath(graphPath),
                signInRecheckKey = null,
                insertMarkdown = { uuid, markdown -> inserted += uuid to markdown },
            )
        }
        composeRule.waitForIdle()
        return Mounted(wired, { entry }, { stack }, inserted)
    }

    private fun rows(wired: WiredAppFixture) = runBlocking { wired.repos.assetRepository.countAssets().getOrNull() ?: -1L }

    @Test
    fun importingTwoPhotos_savesBothThroughTheIndexingDecorator_andInsertsTheirMarkdown() {
        val picker = TwoPhotos()
        val mounted = mount(picker)
        runBlocking { withTimeout(15_000) { assertNotNull(mounted.stack()).indexing.progress.first { it is AssetIndexProgress.Finished } } }
        val entry = assertNotNull(mounted.entry())
        assertTrue(entry.signedIn, "a signed-in account enables the entry")

        entry.onImport(BlockUuid("block-1"))
        composeRule.waitUntil(timeoutMillis = 10_000) { mounted.inserted.isNotEmpty() }

        assertEquals(1, picker.launches)
        assertEquals(BlockUuid("block-1"), mounted.inserted.single().first)
        val markdown = mounted.inserted.single().second
        assertEquals(2, markdown.split("![").size - 1, "both photos become image links: $markdown")
        ObservableEffect.assert("google-photos-asset-rows") { assertEquals(3L, rows(mounted.wired), "1 existing file plus 2 imported photos") }
        composeRule.waitForIdle()
        assertEquals(GooglePhotosImportState.Idle, mounted.entry()?.status)
    }

    @Test
    fun signedOut_disablesTheEntry_butStillBuildsIt() {
        val mounted = mount(TwoPhotos(), signedIn = false)

        val entry = assertNotNull(mounted.entry())
        assertFalse(entry.signedIn)
    }

    @Test
    fun noPlatformAttachmentService_hidesTheEntry() {
        val mounted = mount(TwoPhotos(), withAttachments = false)

        assertNull(mounted.entry())
    }

    @Test
    fun androidRoot_buildsNothingInReleaseVariants_andEverySurfaceInInternalBuilds() {
        val fs = FakeFileSystem()

        GoogleBuildFlags.internalBuild = false
        assertNull(AndroidGoogleIntegration.create(fs, oauthClientId = "client-id"), "a release build must not construct Google surfaces")

        GoogleBuildFlags.internalBuild = true
        val integration = assertNotNull(AndroidGoogleIntegration.create(fs, oauthClientId = "client-id"))
        assertNotNull(integration.photosPicker)
        assertNotNull(integration.imageRenderer)
    }
}
