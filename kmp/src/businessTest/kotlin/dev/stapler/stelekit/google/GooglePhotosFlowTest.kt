package dev.stapler.stelekit.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.google.GooglePhotosImportController
import dev.stapler.stelekit.platform.google.GooglePhotosImportState
import dev.stapler.stelekit.platform.google.GooglePhotosSessionPicker
import dev.stapler.stelekit.platform.google.PickedPhoto
import dev.stapler.stelekit.platform.google.PickedPhotos
import dev.stapler.stelekit.platform.google.PhotosPicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 8.1: the Photos Picker session flow and the import controller, over fakes (no network). */
class GooglePhotosFlowTest {
    private val notes = EffectiveNotesPath("/graph")
    private val two = listOf("https://base/1" to "item-1", "https://base/2" to "item-2")

    private fun picker(port: FakePhotosPort, store: FakeGoogleTokenStore = FakeGoogleTokenStore(), opened: MutableList<String> = mutableListOf(), maxPolls: Int = 5) =
        GooglePhotosSessionPicker(port, store, openPickerUri = { opened += it }, pollIntervalMs = 2_000, maxPollAttempts = maxPolls)

    @Test
    fun launchPicker_should_ReturnEverySelectedPhoto_When_TwoItemsChosen() = runTest {
        val port = FakePhotosPort(selection = two, pollsUntilSelected = 2)
        val opened = mutableListOf<String>()

        val picked = assertIs<Either.Right<PickedPhotos>>(picker(port, opened = opened).launchPicker()).value

        assertEquals(listOf("item-1", "item-2"), picked.photos.map { it.mediaItemId })
        assertEquals(0, picked.failedDownloads)
        assertEquals(listOf("https://photos.example/pick/s1"), opened, "the picker URI is opened exactly once")
        assertEquals(listOf("s1"), port.deletedSessions, "the session is cleaned up")
    }

    @Test
    fun launchPicker_should_CountFailedDownloads_When_SomeItemsCannotBeFetched() = runTest {
        val port = FakePhotosPort(selection = two + ("https://base/3" to "item-3"), failDownloadFor = setOf("item-2"), emptyBytesFor = setOf("item-3"))

        val picked = assertIs<Either.Right<PickedPhotos>>(picker(port).launchPicker()).value

        assertEquals(listOf("item-1"), picked.photos.map { it.mediaItemId })
        assertEquals(2, picked.failedDownloads, "a failed download and an empty body both count")
    }

    @Test
    fun launchPicker_should_RefuseWithoutCreatingASession_When_SignedOut() = runTest {
        val port = FakePhotosPort(selection = two)

        val result = picker(port, FakeGoogleTokenStore(authed = false)).launchPicker()

        assertIs<DomainError.SensorError.PermissionDenied>(assertIs<Either.Left<DomainError>>(result).value)
        assertEquals(0, port.created)
    }

    @Test
    fun launchPicker_should_FailAndDeleteSession_When_PickerNeverCompletes() = runTest {
        val port = FakePhotosPort(selection = two, pollsUntilSelected = Int.MAX_VALUE)

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(picker(port, maxPolls = 3).launchPicker()).value

        assertEquals(408, error.statusCode)
        assertEquals(3, port.polls)
        assertEquals(listOf("s1"), port.deletedSessions)
    }

    @Test
    fun launchPicker_should_FailAndDeleteSession_When_PickerCannotBeOpened() = runTest {
        val port = FakePhotosPort(selection = two)
        val failing = GooglePhotosSessionPicker(port, FakeGoogleTokenStore(), openPickerUri = { error("no browser") })

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(failing.launchPicker()).value

        assertTrue("no browser" in error.message)
        assertEquals(listOf("s1"), port.deletedSessions)
    }

    @Test
    fun launchPicker_should_FailWithoutSession_When_CreateFails() = runTest {
        val port = FakePhotosPort(createFails = true)

        assertIs<Either.Left<DomainError>>(picker(port).launchPicker())
        assertEquals(emptyList(), port.deletedSessions)
    }

    @Test
    fun launchPicker_should_FailWhenNothingSelected() = runTest {
        val port = FakePhotosPort(selection = emptyList())

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(picker(port).launchPicker()).value

        assertEquals("No photo selected from Google Photos.", error.message)
    }

    // ── controller ─────────────────────────────────────────────────────────────

    private class FixedPicker(private val result: Either<DomainError, PickedPhotos>) : PhotosPicker {
        override suspend fun launchPicker() = result
    }

    private fun photos(vararg ids: String) = PickedPhotos(ids.map { PickedPhoto(it.encodeToByteArray(), it) }, failedDownloads = 0).right()

    @Test
    fun import_should_SaveBothPhotosThroughAttachments_When_TwoSelected() = runTest {
        val attachments = RecordingAttachments()
        val controller = GooglePhotosImportController(FixedPicker(photos("item-1", "item-2")))

        val saved = controller.import(attachments, notes)

        assertEquals(listOf("google-photos-item1.jpg", "google-photos-item2.jpg"), saved.map { it.displayName })
        assertEquals(2, attachments.saved.size)
        assertEquals(GooglePhotosImportState.Idle, controller.state.value)
    }

    @Test
    fun import_should_ShowCouldntImportTwoPhotosRetry_When_BothSavesFail() = runTest {
        val attachments = RecordingAttachments(failFor = setOf("google-photos-a.jpg", "google-photos-b.jpg"))
        val controller = GooglePhotosImportController(FixedPicker(photos("a", "b")))

        val saved = controller.import(attachments, notes)

        assertEquals(emptyList(), saved)
        assertEquals(GooglePhotosImportState.Failed("Couldn't import 2 photos - Retry"), controller.state.value)
    }

    @Test
    fun import_should_KeepSavedPhotosAndReportOne_When_OneSaveFails() = runTest {
        val attachments = RecordingAttachments(failFor = setOf("google-photos-b.jpg"))
        val controller = GooglePhotosImportController(FixedPicker(photos("a", "b")))

        val saved = controller.import(attachments, notes)

        assertEquals(listOf("google-photos-a.jpg"), saved.map { it.displayName })
        assertEquals(GooglePhotosImportState.Failed("Couldn't import 1 photo - Retry"), controller.state.value)
    }

    @Test
    fun import_should_ReportDownloadFailuresFromThePicker() = runTest {
        val picked = PickedPhotos(listOf(PickedPhoto(byteArrayOf(1), "a")), failedDownloads = 2).right()
        val controller = GooglePhotosImportController(FixedPicker(picked))

        assertEquals(1, controller.import(RecordingAttachments(), notes).size)
        assertEquals(GooglePhotosImportState.Failed("Couldn't import 2 photos - Retry"), controller.state.value)
    }

    @Test
    fun import_should_ShowCouldntOpenRetry_When_PickerFails() = runTest {
        val controller = GooglePhotosImportController(FixedPicker(DomainError.NetworkError.HttpError(-1, "x").left()))

        assertEquals(emptyList(), controller.import(RecordingAttachments(), notes))
        assertEquals(GooglePhotosImportState.Failed("Couldn't open Google Photos - Retry"), controller.state.value)

        controller.dismiss()
        assertEquals(GooglePhotosImportState.Idle, controller.state.value)
    }

    @Test
    fun import_should_RunOnce_When_TappedWhileOpening() = runTest {
        val gate = CompletableDeferred<Unit>()
        var launches = 0
        val slow = object : PhotosPicker {
            override suspend fun launchPicker(): Either<DomainError, PickedPhotos> {
                launches++
                gate.await()
                return photos("a")
            }
        }
        val controller = GooglePhotosImportController(slow)
        val first = async { controller.import(RecordingAttachments(), notes) }
        advanceUntilIdle()
        assertEquals(GooglePhotosImportState.Opening, controller.state.value)

        assertEquals(emptyList(), controller.import(RecordingAttachments(), notes), "a second tap while opening is ignored")
        gate.complete(Unit)

        assertEquals(1, first.await().size)
        assertEquals(1, launches)
    }

    @Test
    fun import_should_ReturnToIdle_When_CancelledWhileOpening() = runTest {
        val gate = CompletableDeferred<Unit>()
        val hanging = object : PhotosPicker {
            override suspend fun launchPicker(): Either<DomainError, PickedPhotos> {
                gate.await()
                return photos("a")
            }
        }
        val controller = GooglePhotosImportController(hanging)
        val job = async { controller.import(RecordingAttachments(), notes) }
        advanceUntilIdle()
        assertEquals(GooglePhotosImportState.Opening, controller.state.value)

        job.cancel()
        advanceUntilIdle()

        assertEquals(GooglePhotosImportState.Idle, controller.state.value)
    }
}
