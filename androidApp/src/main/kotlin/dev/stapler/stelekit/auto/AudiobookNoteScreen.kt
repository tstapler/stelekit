// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.stapler.stelekit.SteleKitApplication
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.llm.buildLlmProviderRegistry
import dev.stapler.stelekit.voice.CarAudioRecorder
import dev.stapler.stelekit.voice.VoiceCaptureState
import dev.stapler.stelekit.voice.VoiceCaptureViewModel
import dev.stapler.stelekit.voice.buildVoicePipeline
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.voice.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AudiobookNoteScreen(
    carContext: CarContext,
    private val observer: ObservedSession = MediaSessionObserver(carContext),
    private val noteWriter: NoteWriter? = null,
    private val voiceViewModel: VoiceCaptureViewModel? = null,
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastNoteSnippet: String? = null
    private var pendingError: String? = null
    private var pendingRetryAction: (() -> Unit)? = null
    private var micPermissionRequested = false

    private val steleApp: SteleKitApplication?
        get() = carContext.applicationContext as? SteleKitApplication

    private val writer: NoteWriter by lazy {
        noteWriter ?: run {
            val gm = steleApp?.graphManager
            val repoSet = gm?.getActiveRepositorySet()
            val journalService = repoSet?.journalService ?: NoOpJournalService()
            val settings = AudiobookAutoSettings(carContext)
            AudiobookNoteWriter(journalService, settings)
        }
    }

    // buildVoicePipeline is now suspend (it live-checks LLM provider availability), so it can
    // no longer be built inside a `by lazy { }` delegate. Instead the VM is constructed once,
    // asynchronously, inside the onCreate lifecycle callback below (which already runs inside
    // screenScope) and cached here — a suspend-safe holder in place of the old `by lazy`.
    // Nullable: briefly `null` between onCreate firing and the pipeline finishing construction;
    // callers below treat that window as "not ready yet" (falls back to the idle/main template,
    // clicks become a no-op) rather than blocking the Android Auto main thread.
    private var vm: VoiceCaptureViewModel? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                observer.start()
                // Build (or reuse the injected) VM, then observe its state for the voice note
                // flow — both happen inside the same coroutine so `vm` is only ever assigned
                // once fully constructed.
                screenScope.launch {
                    val resolvedVm = voiceViewModel ?: run {
                        val recorder = CarAudioRecorder(carContext)
                        val settings = VoiceSettings(PlatformSettings())
                        // LLM provider registry + settings (Epic 8) — built the same way
                        // ui/App.kt and MainActivity build them, so Android Auto voice notes
                        // get the user's configured LLM provider (remote or on-device)
                        // instead of silently falling back to no-op formatting.
                        val llmCredentialStore = LlmCredentialStore(CredentialStore())
                        val llmSettings = LlmSettings(PlatformSettings())
                        val llmProviderRegistry = buildLlmProviderRegistry(llmCredentialStore, llmSettings)
                        val pipeline = buildVoicePipeline(
                            audioRecorder = recorder,
                            settings = settings,
                            directSpeechProvider = null,
                            registry = llmProviderRegistry,
                            llmSettings = llmSettings,
                        )
                        VoiceCaptureViewModel(
                            pipeline = pipeline,
                            journalService = NoOpJournalService(),
                        )
                    }
                    vm = resolvedVm
                    invalidate()

                    resolvedVm.state.collect { state ->
                        when (state) {
                            is VoiceCaptureState.Done -> {
                                val bookInfo = observer.bookInfo.value
                                val result = writer.writeNote(
                                    AudiobookNote.VoiceNote(
                                        transcribedText = state.insertedText,
                                        bookInfo = bookInfo,
                                    )
                                )
                                result.fold(
                                    ifLeft = { error ->
                                        pendingError = error.message
                                        pendingRetryAction = { resolvedVm.resetToIdle(); resolvedVm.onMicTapped() }
                                        invalidate()
                                    },
                                    ifRight = {
                                        val words = state.insertedText.trim().split("\\s+".toRegex())
                                        lastNoteSnippet = words.takeLast(3).joinToString(" ")
                                        resolvedVm.resetToIdle()
                                        invalidate()
                                    }
                                )
                            }
                            is VoiceCaptureState.Error -> {
                                pendingError = state.message
                                pendingRetryAction = { resolvedVm.dismissError() }
                                invalidate()
                            }
                            else -> invalidate()
                        }
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                observer.close()
                vm?.close()
                screenScope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val error = pendingError
        if (error != null) {
            pendingError = null
            val retryAction = pendingRetryAction
            pendingRetryAction = null
            return MessageTemplate.Builder(error)
                .setTitle("Error")
                .addAction(
                    Action.Builder()
                        .setTitle("Retry")
                        .setOnClickListener {
                            retryAction?.invoke()
                            invalidate()
                        }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Dismiss")
                        .setOnClickListener { invalidate() }
                        .build()
                )
                .build()
        }

        // vm is null only in the brief window before onCreate's async pipeline construction
        // finishes — treat that the same as Idle (falls through to the main grid template).
        return when (vm?.state?.value) {
            is VoiceCaptureState.Recording -> buildRecordingTemplate()
            is VoiceCaptureState.Transcribing, is VoiceCaptureState.Formatting -> buildProcessingTemplate()
            else -> buildMainGridTemplate()
        }
    }

    private fun buildMainGridTemplate(): Template {
        val bookInfo = observer.bookInfo.value
        val subtitle = when {
            !observer.isNotificationListenerEnabled() -> "Enable media access in SteleKit settings"
            bookInfo.isActive && bookInfo.title != null -> bookInfo.title
            else -> "No book detected"
        }

        val headerBuilder = Header.Builder()
            .setTitle(subtitle)

        val hasMicPermission = checkMicPermission()

        val gridItems = ItemList.Builder().apply {
            // Voice note button
            addItem(
                GridItem.Builder()
                    .setTitle(if (hasMicPermission) "Voice note" else "Voice note (needs mic)")
                    .setImage(CarIcon.COMPOSE_MESSAGE)
                    .setOnClickListener { onVoiceNoteClicked(hasMicPermission) }
                    .build()
            )

            // Bookmark button
            addItem(
                GridItem.Builder()
                    .setTitle("Bookmark")
                    .setImage(CarIcon.APP_ICON)
                    .setOnClickListener { onBookmarkClicked() }
                    .build()
            )

            // Quick tag button
            addItem(
                GridItem.Builder()
                    .setTitle("Quick tag")
                    .setImage(CarIcon.ALERT)
                    .setOnClickListener { onQuickTagClicked() }
                    .build()
            )

            // Audio snippet (degraded to bookmark) button
            addItem(
                GridItem.Builder()
                    .setTitle("Bookmark position")
                    .setImage(CarIcon.APP_ICON)
                    .setOnClickListener { onAudioSnippetClicked() }
                    .build()
            )
        }.build()

        return GridTemplate.Builder()
            .setHeader(headerBuilder.build())
            .setSingleList(gridItems)
            .build()
    }

    private fun buildRecordingTemplate(): Template {
        return PaneTemplate.Builder(
            Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle("Listening…")
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Stop")
                        .setOnClickListener {
                            screenScope.launch { vm?.onMicTapped() }
                        }
                        .build()
                )
                .build()
        )
            .setHeader(
                Header.Builder()
                    .setTitle("Voice Note")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun buildProcessingTemplate(): Template {
        return MessageTemplate.Builder("Processing…")
            .setTitle("Voice Note")
            .build()
    }

    private fun onVoiceNoteClicked(hasMicPermission: Boolean) {
        if (!hasMicPermission) {
            requestMicPermission()
            return
        }
        observer.refreshBookInfo()
        vm?.onMicTapped()
        invalidate()
    }

    private fun onBookmarkClicked() {
        observer.refreshBookInfo()
        val bookInfo = observer.bookInfo.value
        screenScope.launch {
            val result = writer.writeNote(AudiobookNote.BookmarkNote(bookInfo))
            result.fold(
                ifLeft = { error ->
                    pendingError = error.message
                    pendingRetryAction = { onBookmarkClicked() }
                    invalidate()
                },
                ifRight = {
                    lastNoteSnippet = "Saved!"
                    invalidate()
                    delay(2_000)
                    lastNoteSnippet = null
                    invalidate()
                }
            )
        }
    }

    private fun onQuickTagClicked() {
        observer.refreshBookInfo()
        val settings = AudiobookAutoSettings(carContext)
        screenManager.push(QuickTagScreen(carContext, writer, observer, settings))
    }

    private fun onAudioSnippetClicked() {
        observer.refreshBookInfo()
        val bookInfo = observer.bookInfo.value
        screenScope.launch {
            val result = writer.writeNote(AudiobookNote.AudioSnippetBookmarkNote(bookInfo))
            result.fold(
                ifLeft = { error ->
                    pendingError = error.message
                    pendingRetryAction = { onAudioSnippetClicked() }
                    invalidate()
                },
                ifRight = {
                    lastNoteSnippet = "Position saved!"
                    invalidate()
                    delay(2_000)
                    lastNoteSnippet = null
                    invalidate()
                }
            )
        }
    }

    private fun checkMicPermission(): Boolean {
        return carContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicPermission() {
        if (micPermissionRequested) return
        micPermissionRequested = true
        carContext.requestPermissions(
            listOf(android.Manifest.permission.RECORD_AUDIO)
        ) { _, _ ->
            micPermissionRequested = false
            invalidate()
        }
    }
}
