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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.app.R
import dev.stapler.stelekit.tile.CaptureTileService
import dev.stapler.stelekit.ui.NoGraphPlaceholderContent
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

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

        val app = application as SteleKitApplication

        // Task 1.3: parse share intent before setContent (EXTRA_STREAM copy is synchronous)
        if (savedInstanceState == null) {
            val shareContent = parseShareIntent(intent)
            if (shareContent.imageLocalPath != null) {
                viewModel.initializeText("[image: ${shareContent.imageLocalPath}]\n${shareContent.text}".trim())
            } else {
                viewModel.initializeText(shareContent.text)
            }
        }

        setContent {
            StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
                val repoSet by (app.graphManager?.activeRepositorySet
                    ?: remember { MutableStateFlow(null) })
                    .collectAsState()
                if (repoSet == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NoGraphPlaceholderContent()
                    }
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

    // Task 1.3: re-parse share extras when singleTop brings this Activity to front
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shareContent = parseShareIntent(intent)
        if (shareContent.imageLocalPath != null) {
            viewModel.initializeText("[image: ${shareContent.imageLocalPath}]\n${shareContent.text}".trim())
        } else {
            viewModel.initializeText(shareContent.text)
        }
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
            val body = clipText?.takeIf { it.isNotBlank() }
                ?: extraText?.takeIf { it.isNotBlank() }
                ?: ""
            val title = subject?.takeIf { it.isNotBlank() }
            return normalizeShareWhitespace(
                when {
                    title != null && body.isNotBlank() && title != body -> "$title\n$body"
                    body.isNotBlank() -> body
                    else -> title ?: ""
                }
            )
        }

        /**
         * Normalizes whitespace artifacts common in browser/HTML-aware share payloads.
         * Order is fixed: unify line endings -> normalize NBSP -> collapse space/tab runs ->
         * collapse blank-line runs. A single `\n` between two content lines is left untouched.
         */
        internal fun normalizeShareWhitespace(text: String): String {
            val unifiedLineEndings = text.replace("\r\n", "\n").replace('\r', '\n')
            val nbspNormalized = unifiedLineEndings.replace('\u00A0', ' ')
            val spacesCollapsed = nbspNormalized.replace(SPACE_TAB_RUN, " ")
            return spacesCollapsed.replace(BLANK_LINE_RUN, "\n\n")
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

/** Spelled-out confidence word at the same 0.7/0.4 thresholds as `ImportScreen.kt:551-554`. */
internal fun confidenceWord(confidence: Float): String = when {
    confidence >= 0.7f -> "high"
    confidence >= 0.4f -> "medium"
    else -> "low"
}

@Composable
internal fun CaptureScreen(
    viewModel: CaptureViewModel,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val captureText by viewModel.captureText.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    // Story 3.1.2/3.2.1 staleness gate: a Ready scan computed against text the user has since
    // edited away from must not drive the preview line or the chip tray (design/ux.md Surfaces
    // 2 & 3, Cross-Check Findings #1/#6).
    val readyState = (scanState as? CaptureViewModel.ScanState.Ready)?.takeIf { it.text == captureText }
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
    // chip enters the "Done" window instead of finishing.
    LaunchedEffect(saveState, pendingChips.isEmpty()) {
        if (saveState == CaptureViewModel.SaveState.Saved) {
            if (pendingChips.isEmpty()) onSaved() else isDone = true
        }
    }

    // Task 4.3.1b/c: resettable ~2.75s auto-finish timer, paused (not merely extended) while
    // accessibility focus is present anywhere in the sheet.
    LaunchedEffect(isDone, resetKey, hasAccessibilityFocus) {
        if (isDone && !hasAccessibilityFocus) {
            delay(2_750)
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

    // Auto-save on back if there is unsaved text
    BackHandler(
        enabled = captureText.isNotBlank() && saveState == CaptureViewModel.SaveState.Idle,
    ) {
        viewModel.save()
    }

    fun onChipInteraction() {
        // Task 4.3.1b: any chip tap resets the Done-window auto-finish timer to its full duration.
        if (isDone) resetKey++
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Translucent dim layer — tapping it dismisses (or saves if text is non-empty). During
        // the post-save "Done" window it always finishes immediately (Task 4.3.1c) — the capture
        // was already saved, so viewModel.save() must never run a second time here.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(CAPTURE_SCRIM_TEST_TAG)
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    if (isDone) onSaved() else if (captureText.isBlank()) onDismiss() else viewModel.save()
                },
        )

        // Bottom-anchored capture sheet
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .focusGroup()
                .onFocusEvent { hasFocusWithinSheet = it.hasFocus }
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

                Text(
                    text = "Today's Journal",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
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
                } else {
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
                            enabled = saveState == CaptureViewModel.SaveState.Idle && captureText.isNotBlank(),
                        ) {
                            if (saveState == CaptureViewModel.SaveState.Saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text("Save")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Compact suggestion chip — `[leading icon/dot][term][×]` at reduced scale, structurally
 * copied from `ImportScreen.kt:551-620`'s `TopicSuggestionChip` (plan.md Task 3.1.1a). The
 * accept region (dot/icon + term) and the dismiss `×` are two independently 48×48dp-minimum
 * tap targets (`minimumInteractiveComponentSize()`, AC #20), merged into a single TalkBack
 * node whose default double-tap action is accept and whose `customActions` exposes dismiss
 * (AC #22).
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
                            (confidence ?: 0f) >= 0.7f -> MaterialTheme.colorScheme.primary
                            (confidence ?: 0f) >= 0.4f -> MaterialTheme.colorScheme.secondary
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
            // AC #20: the dismiss icon's fixed-size content never exceeds 48dp on its own, so —
            // unlike the accept region above — an explicit Modifier.size(48.dp) is needed here to
            // guarantee the minimum touch target (minimumInteractiveComponentSize() alone depends
            // on LocalMinimumInteractiveComponentEnforcement, unreliable under a bare
            // MaterialTheme wrapper). minimumInteractiveComponentSize() is deliberately omitted —
            // it would be a no-op after an exact 48dp size is already applied.
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
