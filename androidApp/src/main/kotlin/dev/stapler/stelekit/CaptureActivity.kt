// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon as ComposeIcon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.app.R
import dev.stapler.stelekit.capture.OffGraphCapture
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.tile.CaptureTileService
import dev.stapler.stelekit.ui.NoGraphPlaceholderContent
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test-only hook (`CaptureActivityTest.kt`) for locating the full-screen dim/scrim layer. */
internal const val CAPTURE_SCRIM_TEST_TAG = "capture_scrim"

/**
 * Lightweight translucent overlay for quick note capture.
 * Launched from the home screen widget, Quick Settings Tile, and Android share sheet.
 * Writes to today's journal page via DatabaseWriteActor + GraphWriter.
 */
class CaptureActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.hostAttached = true

        // Task 1.3: parse share intent before setContent (EXTRA_STREAM copy is synchronous).
        // The image is already in app-private storage and the text is in the intent, which the
        // system re-delivers after process death; the typed text and capture id ride in the bundle.
        if (savedInstanceState == null) {
            initializeFrom(parseShareIntent(intent))
            viewModel.beginShare(ShareShortcutPublisher.targetGraphIdFrom(intent))
            if (intent.getBooleanExtra(EXTRA_OPEN_DESTINATION_MENU, false)) viewModel.setMenuOpen(true)
        } else {
            restoreFrom(savedInstanceState)
        }

        setContent {
            StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
                val destination by viewModel.destination.collectAsState()
                if (destination is CaptureDestination.NoGraphs) {
                    NoGraphsContent(viewModel, onClose = { finish() })
                } else {
                    CaptureScreen(
                        viewModel = viewModel,
                        onSaved = {
                            // Task 2.2: prompt tile add on first save
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                promptAddTileOnce()
                            }
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    private fun initializeFrom(shareContent: ShareContent) {
        if (shareContent.imageLocalPath != null) {
            viewModel.initializeText("[image: ${shareContent.imageLocalPath}]\n${shareContent.text}".trim())
        } else {
            viewModel.initializeText(shareContent.text)
        }
    }

    private fun restoreFrom(state: Bundle) {
        viewModel.initializeText(state.getString(STATE_TEXT).orEmpty())
        viewModel.beginShare(state.getString(STATE_OVERRIDE_GRAPH), state.getString(STATE_CAPTURE_ID))
        if (state.getBoolean(STATE_HANDLED)) viewModel.restoreHandled()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CAPTURE_ID, viewModel.captureId)
        outState.putString(STATE_TEXT, viewModel.captureText.value)
        outState.putString(STATE_OVERRIDE_GRAPH, viewModel.overrideGraphIdValue)
        outState.putBoolean(STATE_HANDLED, viewModel.isHandled)
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.hostAttached = false
        super.onDestroy()
    }

    // Task 1.3: re-parse share extras when singleTop brings this Activity to front
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shareContent = parseShareIntent(intent)
        val text = if (shareContent.imageLocalPath != null) {
            "[image: ${shareContent.imageLocalPath}]\n${shareContent.text}".trim()
        } else {
            shareContent.text
        }
        viewModel.onNewShare(text, ShareShortcutPublisher.targetGraphIdFrom(intent))
    }

    // Task 1.3: Bug 3 mitigation
    private fun parseShareIntent(intent: Intent): ShareContent {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            return ShareContent("", null)
        }
        val clipText  = intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString()
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject   = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val text = buildShareText(clipText, extraText, subject)

        // Bug 2 mitigation: copy EXTRA_STREAM synchronously before any coroutine launch
        val imagePath = if (intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val streamUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            streamUri?.let { copyStreamToPrivateStorage(it) }
        } else null

        return ShareContent(text, imagePath)
    }

    private fun copyStreamToPrivateStorage(uri: android.net.Uri): String? = try {
        val outFile = java.io.File(cacheDir, "share_${System.currentTimeMillis()}.jpg")
        val copied = contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        if (copied != null) outFile.absolutePath else null
    } catch (_: SecurityException) { null }
      catch (_: Exception) { null }

    // Task 2.2: prompt at most once after first successful save (API 33+)
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun promptAddTileOnce() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TILE_PROMPTED, false)) return
        prefs.edit().putBoolean(KEY_TILE_PROMPTED, true).apply()
        try {
            val sbm = getSystemService(android.app.StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, CaptureTileService::class.java),
                getString(R.string.tile_label_capture),
                Icon.createWithResource(this, R.drawable.ic_tile_capture),
                mainExecutor,
            ) { /* result callback — ignored */ }
        } catch (_: Exception) { /* OS may reject if tile already added or quota exceeded */ }
    }

    private data class ShareContent(val text: String, val imageLocalPath: String?)

    companion object {
        private const val PREFS_NAME = "stelekit_capture_prefs"
        private const val KEY_TILE_PROMPTED = "pref_tile_prompt_shown"
        private const val STATE_CAPTURE_ID = "capture_id"
        private const val STATE_TEXT = "capture_text"
        private const val STATE_OVERRIDE_GRAPH = "capture_override_graph"
        private const val STATE_HANDLED = "capture_handled"

        /** Set by the next-start notice's Change action to open with the destination menu showing. */
        const val EXTRA_OPEN_DESTINATION_MENU = "open_destination_menu"

        // Compiled once — Regex construction is not free, and this runs on every share intent.
        //
        // KNOWN LIMITATION (see project_plans/android-share-capture-whitespace/implementation/
        // plan.md "Scope Decision"): this collapses leading indentation too, with no
        // line-position exemption. If a captured block's raw content is ever re-parsed through
        // MarkdownPreprocessor/OutlinerPipeline, embedded list nesting inside shared text will
        // not survive. Deliberate, deferred tradeoff — not yet verified against real re-parse
        // paths.
        private val SPACE_TAB_RUN = Regex("[ \t]{2,}")
        private val BLANK_LINE_RUN = Regex("\n[ \t]*(?:\n[ \t]*)+")

        /**
         * Combines share intent text sources into a single string.
         *
         * Priority: clipData text > EXTRA_TEXT > EXTRA_SUBJECT.
         * takeIf { isNotBlank() } prevents an empty clipData from eating the fallback chain.
         * When EXTRA_SUBJECT (page title) and a URL body are both present and distinct,
         * they are joined with a newline so neither is silently dropped.
         */
        internal fun buildShareText(
            clipText: String?,
            extraText: String?,
            subject: String?,
        ): String {
            // takeIf { isNotBlank() } prevents an empty/blank source from blocking fallbacks
            val body = normalizeShareWhitespace(
                clipText?.takeIf { it.isNotBlank() }
                    ?: extraText?.takeIf { it.isNotBlank() }
                    ?: ""
            )
            // Normalized before the equality check below, so whitespace-only differences
            // between subject and body (e.g. double-space vs single-space) don't defeat dedup.
            val title = subject?.takeIf { it.isNotBlank() }?.let { normalizeShareWhitespace(it) }
            return when {
                title != null && body.isNotBlank() && title != body -> "$title\n$body"
                body.isNotBlank() -> body
                else -> title ?: ""
            }
        }

        /**
         * Normalizes whitespace artifacts common in browser/HTML-aware share payloads.
         * Order is fixed: unify line endings -> normalize NBSP -> collapse space/tab runs ->
         * collapse blank-line runs -> trim leading/trailing whitespace. A single `\n` between
         * two content lines is left untouched.
         */
        internal fun normalizeShareWhitespace(text: String): String {
            val unifiedLineEndings = text.replace("\r\n", "\n").replace('\r', '\n')
            val nbspNormalized = unifiedLineEndings.replace('\u00A0', ' ')
            val spacesCollapsed = nbspNormalized.replace(SPACE_TAB_RUN, " ")
            return spacesCollapsed.replace(BLANK_LINE_RUN, "\n\n").trim()
        }
    }
}

/**
 * Two chip kinds share the pending-suggestion tray (Epic 3.1, Fix for pre-mortem.md P1 #2):
 * a heuristic new-page candidate (confidence score) and an exact existing-page match that
 * requires explicit confirmation before it's folded into `linkedText`. Distinguished by icon
 * only — see `design/ux.md` Surface 3.
 */
internal enum class CaptureChipKind { NEW_PAGE, EXISTING_LINK }

/** Rendering-only wrapper unifying both chip buckets for the tray's single `LazyRow`. */
internal sealed interface CaptureChipItem {
    val term: String
    data class NewPage(override val term: String, val confidence: Float) : CaptureChipItem
    data class ExistingLink(override val term: String) : CaptureChipItem
}

// Shared with CaptureSuggestionChip's dotColor below — same 0.7/0.4 thresholds as
// `ImportScreen.kt:551-554` (a separate, pre-existing, out-of-scope copy there).
private const val CONFIDENCE_HIGH_THRESHOLD = 0.7f
private const val CONFIDENCE_MEDIUM_THRESHOLD = 0.4f

/** Spelled-out confidence word at the same thresholds as `ImportScreen.kt:551-554`. */
internal fun confidenceWord(confidence: Float): String = when {
    confidence >= CONFIDENCE_HIGH_THRESHOLD -> "high"
    confidence >= CONFIDENCE_MEDIUM_THRESHOLD -> "medium"
    else -> "low"
}

private const val CONFIRM_FINISH_MS = 1_200L
private const val QUEUED_FINISH_MS = 1_800L
private const val BACK_TOAST_MS = 5_000L

/** Test tags for the destination menu (Robolectric tests). */
internal const val DESTINATION_ROW_TEST_TAG = "capture_destination_row"
internal fun graphChipTestTag(id: GraphId) = "capture_graph_chip_${id.value}"

@Composable
internal fun CaptureScreen(
    viewModel: CaptureViewModel,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val captureText by viewModel.captureText.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val destination by viewModel.destination.collectAsState()
    val graphChoices by viewModel.graphChoices.collectAsState()
    val menuOpen by viewModel.menuOpen.collectAsState()
    val resultNote by viewModel.resultNote.collectAsState()
    val backSave by viewModel.backSave.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val uiScope = rememberCoroutineScope() // transient snackbar work only; every write is in the ViewModel

    val offGraph = (destination as? CaptureDestination.Ready)?.isActive == false

    // Story 3.1.2/3.2.1 staleness gate: a Ready scan computed against text the user has since
    // edited away from must not drive the preview line or the chip tray (design/ux.md Surfaces
    // 2 & 3, Cross-Check Findings #1/#6). Link suggestions are off for a graph that isn't open.
    val readyState = (scanState as? CaptureViewModel.ScanState.Ready)?.takeIf { it.text == captureText && !offGraph }
    val existingLinkChips: List<CaptureChipItem> = readyState?.confirmFirstNames
        ?.map { CaptureChipItem.ExistingLink(it) }
        .orEmpty()
    val newPageChips: List<CaptureChipItem> = readyState?.result?.topicSuggestions
        ?.filterNot { it.dismissed || it.accepted }
        ?.sortedByDescending { it.confidence }
        ?.map { CaptureChipItem.NewPage(it.term, it.confidence) }
        .orEmpty()
    val pendingChips = (existingLinkChips + newPageChips).take(4)

    // Epic 4.3: post-save "Done" window — the sheet stays open only while chips are pending.
    var isDone by remember { mutableStateOf(false) }
    var resetKey by remember { mutableIntStateOf(0) }

    // Task 4.3.1c: approximate "TalkBack accessibility focus is somewhere in the sheet" as
    // "ordinary Compose focus is somewhere in the sheet" (via a focusGroup + onFocusEvent on the
    // sheet's root) AND "a screen reader's touch-exploration mode is active" (the standard
    // Android proxy for "TalkBack is running") — verified at implementation time per plan.md
    // Task 4.3.1c's own note that Compose has no single built-in signal for this.
    val context = LocalContext.current
    val accessibilityManager = remember {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
    }
    var hasFocusWithinSheet by remember { mutableStateOf(false) }
    val hasAccessibilityFocus = hasFocusWithinSheet && accessibilityManager?.isTouchExplorationEnabled == true

    LaunchedEffect(saveState) {
        val errorState = saveState as? CaptureViewModel.SaveState.Error
        if (errorState != null) {
            snackbarHostState.showSnackbar(
                "Save failed — ${errorState.throwable?.message ?: "unknown error"}"
            )
        }
    }

    // Task 4.3.1a/b: zero pending chips finishes immediately (unchanged behavior); ≥1 pending
    // chip enters the "Done" window instead of finishing. A resolved destination first shows its
    // confirmation row ("Added to Work graph") for a beat; an open menu pauses that auto-finish.
    LaunchedEffect(saveState, pendingChips.isEmpty(), menuOpen, destination is CaptureDestination.Ready) {
        when (saveState) {
            CaptureViewModel.SaveState.Saved -> when {
                pendingChips.isNotEmpty() -> isDone = true
                destination is CaptureDestination.Ready -> if (!menuOpen) { delay(CONFIRM_FINISH_MS); onSaved() }
                else -> onSaved()
            }
            is CaptureViewModel.SaveState.Queued -> if (!menuOpen) { delay(QUEUED_FINISH_MS); onSaved() }
            else -> Unit
        }
    }

    // Task 4.3.1b/c: resettable ~2.75s auto-finish timer, paused (not merely extended) while
    // accessibility focus is present anywhere in the sheet or the destination menu is open.
    LaunchedEffect(isDone, resetKey, hasAccessibilityFocus, menuOpen) {
        if (isDone && !hasAccessibilityFocus && !menuOpen) {
            delay(2_750)
            onSaved()
        }
    }

    // Back auto-save toast window; an open menu can't coexist with it, but keep the same pause rule.
    LaunchedEffect(backSave, hasAccessibilityFocus) {
        if (backSave is CaptureViewModel.BackSaveState.Done && !hasAccessibilityFocus) {
            delay(BACK_TOAST_MS)
            onSaved()
        }
    }

    // Task 4.1.3c: chip-accept failure snackbar — same SnackbarHostState as save failures.
    LaunchedEffect(Unit) {
        viewModel.chipFailure.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Focus returns to the text field when the menu closes, typed text untouched.
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (menuWasOpen && !menuOpen) focusRequester.requestFocus()
        menuWasOpen = menuOpen
    }

    // Back with text auto-saves to the shown destination (Task 4.2.1g). Back with empty text is
    // not intercepted, so the system just closes. The legacy label path keeps its original save.
    // After a failed save (Error) Back retries and queues, so closing never drops the text.
    BackHandler(
        enabled = captureText.isNotBlank() &&
            (saveState == CaptureViewModel.SaveState.Idle || saveState is CaptureViewModel.SaveState.Error) &&
            backSave == CaptureViewModel.BackSaveState.None && !menuOpen,
    ) {
        if (destination is CaptureDestination.Legacy && saveState == CaptureViewModel.SaveState.Idle) viewModel.save()
        else viewModel.backSave()
    }
    BackHandler(enabled = menuOpen) { viewModel.setMenuOpen(false) }

    fun onChipInteraction() {
        // Task 4.3.1b: any chip tap resets the Done-window auto-finish timer to its full duration.
        if (isDone) resetKey++
    }

    val toast = backSave as? CaptureViewModel.BackSaveState.Done
    val busy = saveState == CaptureViewModel.SaveState.Saving || backSave == CaptureViewModel.BackSaveState.Saving

    Box(modifier = Modifier.fillMaxSize()) {
        // Translucent dim layer — tapping it dismisses (or saves if text is non-empty). During
        // the post-save "Done" window it always finishes immediately (Task 4.3.1c) — the capture
        // was already saved, so viewModel.save() must never run a second time here.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(CAPTURE_SCRIM_TEST_TAG)
                .background(Color.Black.copy(alpha = if (toast != null) 0f else 0.4f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    if (isDone || toast != null) onSaved()
                    else if (captureText.isBlank()) onDismiss()
                    else if (destination is CaptureDestination.Legacy) viewModel.save()
                    else viewModel.backSave()
                },
        )

        if (toast != null) {
            BackSaveToast(
                done = toast,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                onUndo = {
                    viewModel.undoBackSave { ok ->
                        if (ok) onSaved() else uiScope.launch { snackbarHostState.showSnackbar("Couldn't undo — the note was edited") }
                    }
                },
                onChange = {
                    val text = toast.record?.text.orEmpty()
                    viewModel.undoBackSave { ok ->
                        if (ok) viewModel.reopenAfterUndo(text)
                        else uiScope.launch { snackbarHostState.showSnackbar("Couldn't undo — the note was edited") }
                    }
                },
            )
        } else {
            CaptureSheet(
                viewModel = viewModel,
                captureText = captureText,
                saveState = saveState,
                busy = busy,
                readyState = readyState,
                pendingChips = pendingChips,
                destination = destination,
                graphChoices = graphChoices,
                menuOpen = menuOpen,
                resultNote = resultNote,
                isDone = isDone,
                focusRequester = focusRequester,
                onFocusWithin = { hasFocusWithinSheet = it },
                onChipInteraction = ::onChipInteraction,
                onDismiss = onDismiss,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun CaptureSheet(
    viewModel: CaptureViewModel,
    captureText: String,
    saveState: CaptureViewModel.SaveState,
    busy: Boolean,
    readyState: CaptureViewModel.ScanState.Ready?,
    pendingChips: List<CaptureChipItem>,
    destination: CaptureDestination,
    graphChoices: List<GraphChoice>,
    menuOpen: Boolean,
    resultNote: String?,
    isDone: Boolean,
    focusRequester: FocusRequester,
    onFocusWithin: (Boolean) -> Unit,
    onChipInteraction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offGraph = (destination as? CaptureDestination.Ready)?.isActive == false
    // Bottom-anchored capture sheet
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .navigationBarsPadding()
            .imePadding()
            .focusGroup()
            .onFocusEvent { onFocusWithin(it.hasFocus) }
            // Consume clicks so they don't propagate to the dim layer
            .clickable(enabled = false, indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Spacer(Modifier.height(12.dp))

            DestinationRow(
                destination = destination,
                saveState = saveState,
                resultNote = resultNote,
                menuOpen = menuOpen,
                graphChoices = graphChoices,
                enabled = !isDone && !busy,
                onToggleMenu = { viewModel.setMenuOpen(!menuOpen) },
                onCloseMenu = { viewModel.setMenuOpen(false) },
                onSelect = viewModel::selectGraph,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = captureText,
                onValueChange = viewModel::updateText,
                enabled = !isDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("Capture a note…") },
                minLines = 3,
                maxLines = 8,
            )
            Spacer(Modifier.height(12.dp))

            if (offGraph) {
                Text(
                    text = OffGraphCapture.LINK_SUGGESTIONS_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Epic 3.2/Task 3.2.1a: read-only auto-link preview — never rewrites the live field.
            if (readyState != null && readyState.result.linkedText != readyState.text) {
                Text(
                    text = readyState.result.linkedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Epic 3.1/Task 3.1.2a: capped, combined chip tray — existing-link chips first,
            // then new-page chips by descending confidence, cap 4 total, silent truncation.
            if (pendingChips.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pendingChips, key = { it.term }) { chip ->
                        when (chip) {
                            is CaptureChipItem.NewPage -> CaptureSuggestionChip(
                                term = chip.term,
                                confidence = chip.confidence,
                                kind = CaptureChipKind.NEW_PAGE,
                                onAccept = { onChipInteraction(); viewModel.acceptSuggestion(chip.term) },
                                onDismiss = { onChipInteraction(); viewModel.dismissSuggestion(chip.term) },
                            )
                            is CaptureChipItem.ExistingLink -> CaptureSuggestionChip(
                                term = chip.term,
                                confidence = null,
                                kind = CaptureChipKind.EXISTING_LINK,
                                onAccept = { onChipInteraction(); viewModel.acceptExistingLink(chip.term) },
                                onDismiss = { onChipInteraction(); viewModel.dismissExistingLinkSuggestion(chip.term) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (isDone) {
                // Epic 4.3/Surface 5: button row replaced by a compact "Saved" confirmation
                // for the duration of the post-save Done window.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "✓ Saved",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (destination is CaptureDestination.Unavailable) {
                UnavailableActions(destination, viewModel, onDismiss)
            } else {
                ActionRow(viewModel, saveState, busy, captureText, destination, onDismiss)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionRow(
    viewModel: CaptureViewModel,
    saveState: CaptureViewModel.SaveState,
    busy: Boolean,
    captureText: String,
    destination: CaptureDestination,
    onDismiss: () -> Unit,
) {
    val checking = destination == CaptureDestination.Checking
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = onDismiss,
            enabled = saveState != CaptureViewModel.SaveState.Saving,
        ) { Text("Dismiss") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = viewModel::save,
            enabled = saveState == CaptureViewModel.SaveState.Idle && !busy && captureText.isNotBlank() && !checking,
        ) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                checking -> Text("Checking destination...")
                else -> Text("Save")
            }
        }
    }
}

/** UX S10 "Destination unavailable": the text stays visible; Save is replaced by three explicit actions. */
@Composable
private fun UnavailableActions(
    destination: CaptureDestination.Unavailable,
    viewModel: CaptureViewModel,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) { Text("Dismiss") }
        destination.fallback?.let { fallback ->
            TextButton(onClick = viewModel::saveToFallback) { Text("Save to ${fallback.label}") }
        }
        TextButton(onClick = viewModel::retryDestination) { Text("Retry") }
        if (destination.graphId != null) {
            TextButton(onClick = viewModel::queueForLater) { Text("Queue for later") }
        }
    }
}

/**
 * The destination row (UX S10): one button node that reads "Saving to Personal graph - Today's
 * Journal" and opens the graph menu. After a save the same node announces the outcome.
 */
@Composable
private fun DestinationRow(
    destination: CaptureDestination,
    saveState: CaptureViewModel.SaveState,
    resultNote: String?,
    menuOpen: Boolean,
    graphChoices: List<GraphChoice>,
    enabled: Boolean,
    onToggleMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onSelect: (GraphId) -> Unit,
) {
    val outcome = when (saveState) {
        CaptureViewModel.SaveState.Saved -> resultNote
        is CaptureViewModel.SaveState.Queued -> resultNote
        else -> null
    }
    when (destination) {
        CaptureDestination.Legacy, CaptureDestination.NoGraphs -> Text(
            text = "Today's Journal",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        CaptureDestination.Checking -> Row(
            modifier = Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) {
                contentDescription = "Saving to... (checking)"
                liveRegion = LiveRegionMode.Polite
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Saving to... (checking)", style = MaterialTheme.typography.labelMedium)
        }
        is CaptureDestination.Unavailable -> Text(
            text = destination.reason,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        is CaptureDestination.Ready -> {
            val label = destination.graph.label
            Column {
                Box {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = enabled, role = Role.DropdownList, onClick = onToggleMenu)
                            .testTag(DESTINATION_ROW_TEST_TAG)
                            .semantics(mergeDescendants = true) {
                                contentDescription = outcome ?: "Saving to $label. Double-tap to change"
                                liveRegion = LiveRegionMode.Polite
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = outcome ?: "Saving to $label - Today's Journal",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (outcome == null) ComposeIcon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    if (graphChoices.size > MAX_INLINE_CHIPS) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = onCloseMenu) {
                            graphChoices.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.label) },
                                    onClick = { onSelect(choice.id) },
                                    modifier = Modifier.testTag(graphChipTestTag(choice.id)),
                                )
                            }
                        }
                    }
                }
                destination.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (menuOpen && graphChoices.size <= MAX_INLINE_CHIPS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        graphChoices.forEach { choice ->
                            GraphChip(choice, selected = choice.id == destination.graph.id, onClick = { onSelect(choice.id) })
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_INLINE_CHIPS = 4

@Composable
private fun GraphChip(choice: GraphChoice, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .testTag(graphChipTestTag(choice.id))
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = if (selected) "${choice.label}, selected" else choice.label
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "✓ ${choice.name}" else choice.name, style = MaterialTheme.typography.labelLarge)
    }
}

/** Back auto-save confirmation (Task 4.2.1h): names the graph; Undo and Change only when a block was added. */
@Composable
private fun BackSaveToast(
    done: CaptureViewModel.BackSaveState.Done,
    onUndo: () -> Unit,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(16.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(done.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (done.record != null) {
                TextButton(onClick = onUndo) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
                TextButton(onClick = onChange) { Text("Change", color = MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

/** The "no graphs configured" placeholder (ADR-004): Close, or Back, keeps the text for the first graph. */
@Composable
internal fun NoGraphsContent(viewModel: CaptureViewModel, onClose: () -> Unit) {
    val text by viewModel.captureText.collectAsState()
    val note by viewModel.resultNote.collectAsState()
    val failed = note == CaptureViewModel.NO_GRAPH_SAVE_FAILED
    val clipboard = LocalClipboardManager.current
    val close = { if (text.isBlank()) onClose() else viewModel.closeWithoutGraph() }
    LaunchedEffect(note) {
        if (note != null && !failed) {
            delay(QUEUED_FINISH_MS)
            onClose()
        }
    }
    BackHandler(enabled = note == null || failed) { close() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NoGraphPlaceholderContent()
        if (text.isNotBlank()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
            Spacer(Modifier.height(8.dp))
        }
        note?.let {
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        if (failed) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy text") }
            TextButton(onClick = onClose) { Text("Close anyway") }
        }
        TextButton(onClick = close, enabled = note == null || failed) { Text(if (failed) "Retry" else "Close") }
    }
}

/**
 * Compact suggestion chip — `[leading icon/dot][term][×]`, structurally copied from
 * `ImportScreen.kt:551-620`'s `TopicSuggestionChip` (plan.md Task 3.1.1a). The accept region
 * and dismiss `×` are two independent 48×48dp-minimum tap targets, merged into one TalkBack
 * node whose default double-tap action is accept and whose `customActions` exposes dismiss.
 */
@Composable
internal fun CaptureSuggestionChip(
    term: String,
    confidence: Float?,
    kind: CaptureChipKind,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        // Semantics live directly on the accept IconButton (not merged in from siblings): a
        // `mergeDescendants = true` block spanning both this and the dismiss IconButton would
        // leave which child's OnClick action "wins" the merge ambiguous — putting them on the
        // accept node's own semantics keeps its default double-tap action unambiguously "accept"
        // while `customActions` still exposes dismiss to TalkBack (AC #22).
        IconButton(
            onClick = {
                // Story 3.1.3/Task 3.1.3a: haptic fires synchronously, before the async write.
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onAccept()
            },
            // AC #20: the accept region's own dot/icon + term content already exceeds 48dp width
            // for any non-trivial term, and minimumInteractiveComponentSize() covers the height
            // (and any pathologically short term) — no fixed .size() here, since the content is
            // variable-width and must not be clipped.
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .semantics(mergeDescendants = true) {
                    contentDescription = when (kind) {
                        CaptureChipKind.NEW_PAGE ->
                            "Suggested page, $term, confidence ${confidenceWord(confidence ?: 0f)}. Double-tap to accept."
                        CaptureChipKind.EXISTING_LINK -> "Existing page, $term. Double-tap to link."
                    }
                    customActions = listOf(
                        CustomAccessibilityAction("Dismiss suggestion") { onDismiss(); true },
                    )
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (kind) {
                    CaptureChipKind.NEW_PAGE -> {
                        val dotColor = when {
                            (confidence ?: 0f) >= CONFIDENCE_HIGH_THRESHOLD -> MaterialTheme.colorScheme.primary
                            (confidence ?: 0f) >= CONFIDENCE_MEDIUM_THRESHOLD -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                    }
                    CaptureChipKind.EXISTING_LINK -> ComposeIcon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(text = term, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(
            onClick = onDismiss,
            // AC #20: unlike the accept region above, this icon's fixed-size content never
            // exceeds 48dp on its own, so an explicit Modifier.size(48.dp) is needed to guarantee
            // the minimum touch target — minimumInteractiveComponentSize() is omitted since it
            // would be a no-op after an exact 48dp size is already applied.
            modifier = Modifier.size(48.dp),
        ) {
            ComposeIcon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
