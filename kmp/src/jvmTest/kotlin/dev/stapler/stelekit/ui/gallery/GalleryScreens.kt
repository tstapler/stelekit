package dev.stapler.stelekit.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import arrow.core.right
import dev.stapler.stelekit.git.merge.JournalMergeProposal
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.ConflictHunk
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.llm.ProposedBlock
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.merge.PullIndexState
import dev.stapler.stelekit.ui.VaultState
import dev.stapler.stelekit.ui.screens.DiskConflictFullScreen
import dev.stapler.stelekit.ui.screens.EmptyGraphStateScreen
import dev.stapler.stelekit.ui.screens.LibrarySetupScreen
import dev.stapler.stelekit.ui.screens.PermissionRecoveryScreen
import dev.stapler.stelekit.ui.screens.VaultUnlockScreen
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.ui.screens.copy.CopyPagesActions
import dev.stapler.stelekit.ui.screens.copy.CopyPagesContent
import dev.stapler.stelekit.ui.screens.copy.CopyPagesState
import dev.stapler.stelekit.ui.screens.copy.DestinationAction
import dev.stapler.stelekit.ui.screens.copy.DestinationActionKind
import dev.stapler.stelekit.ui.screens.copy.DestinationRow
import dev.stapler.stelekit.ui.screens.copy.DestinationRowView
import dev.stapler.stelekit.ui.screens.copy.DestinationStatus
import dev.stapler.stelekit.ui.screens.copy.DisabledKind
import dev.stapler.stelekit.ui.screens.copy.ListLoad
import dev.stapler.stelekit.ui.screens.copy.PageRowState
import dev.stapler.stelekit.ui.screens.copy.PickedPages
import dev.stapler.stelekit.ui.screens.copy.PageSelectionRow
import dev.stapler.stelekit.ui.screens.copy.PullIndexStatus
import dev.stapler.stelekit.ui.screens.copy.PullSourceChooser
import dev.stapler.stelekit.ui.screens.git.CloneMode
import dev.stapler.stelekit.ui.screens.git.ConflictResolutionScreen
import dev.stapler.stelekit.ui.screens.git.GitConnectionTestState
import dev.stapler.stelekit.ui.screens.git.GitSetupNavRow
import dev.stapler.stelekit.ui.screens.git.JournalMergeReviewScreen
import dev.stapler.stelekit.ui.screens.git.Step1CloneMode
import dev.stapler.stelekit.ui.screens.git.Step3Auth
import dev.stapler.stelekit.ui.screens.git.Step4Branch
import dev.stapler.stelekit.ui.screens.git.Step5TestAndSave
import dev.stapler.stelekit.ui.screens.llm.BlockDiffView
import dev.stapler.stelekit.ui.screens.llm.LlmSuggestionReviewScreen
import dev.stapler.stelekit.ui.screens.llm.NewPagePreview
import dev.stapler.stelekit.vault.VaultError

/** Stories for the screens category -- see [ComponentGallery]. */

/** Gives fillMaxSize screens a bounded height so they can measure inside the gallery's scroll host. */
@Composable
private fun Bounded(height: Dp = 700.dp, content: @Composable () -> Unit) {
    Box(Modifier.height(height)) { content() }
}

private val demoRows = listOf(
    PageRowState(PageUuid("page-a"), "Project Atlas", isJournal = false, blockCount = 42),
    PageRowState(PageUuid("page-b"), "2026-06-12", isJournal = true, blockCount = 7),
    PageRowState(PageUuid("page-c"), "Reading list", isJournal = false, blockCount = 1),
    PageRowState(PageUuid("page-d"), "Meeting notes with a rather long title that should truncate cleanly", isJournal = false, subtitle = "12 KB - Jun 3"),
)

private val demoDestinations = listOf(
    DestinationRow(GraphId("g-main"), "Main notes", DestinationStatus.Disabled(DisabledKind.CurrentGraph, "Current graph")),
    DestinationRow(GraphId("g-work"), "Work", DestinationStatus.Available),
    DestinationRow(GraphId("g-enc"), "Private vault", DestinationStatus.Disabled(DisabledKind.Encrypted, "Encrypted graphs can't be copied into")),
    DestinationRow(
        GraphId("g-old"),
        "Archive",
        DestinationStatus.Disabled(
            DisabledKind.NoGrant,
            "Folder access was revoked",
            DestinationAction(DestinationActionKind.RegrantAccess, "Re-grant access"),
        ),
    ),
    DestinationRow(GraphId("g-bad"), "Flaky drive", DestinationStatus.CouldntCheck("Couldn't check Flaky drive: timed out")),
)

private val mergeProposal = JournalMergeProposal(
    filePath = "journals/2026_06_12.md",
    localContent = "- Morning run\n- Wrote the report",
    remoteContent = "- Morning run\n- Called the plumber",
    proposedMerge = "- Morning run\n- Wrote the report\n- Called the plumber",
    confidenceWarning = true,
    hasConflictMarkers = false,
    backupPath = "journals/2026_06_12.md.bak",
)

private val conflictFiles = listOf(
    ConflictFile(
        filePath = "/graph/pages/Atlas.md",
        wikiRelativePath = "pages/Atlas.md",
        hunks = listOf(
            ConflictHunk("h1", listOf("- Status: in progress"), listOf("- Status: shipped")),
            ConflictHunk("h2", listOf("- Owner: Tyler"), listOf("- Owner: Sam", "- Reviewer: Tyler")),
        ),
    ),
    ConflictFile(
        filePath = "/graph/pages/Reading.md",
        wikiRelativePath = "pages/Reading.md",
        hunks = listOf(ConflictHunk("h3", listOf("- Dune"), listOf("- Dune", "- Hyperion"))),
    ),
)

private fun blockEdit(id: String, before: String, after: String) = PendingLlmSuggestion.BlockEdit(
    id = id,
    graphId = "g-main",
    sourceProviderId = "demo-provider",
    proposedAtEpochMs = 1_750_000_000_000L,
    rationale = "Tightens the wording and fixes a typo.",
    pageUuid = "page-a",
    blockUuid = "block-$id",
    currentContentSnapshot = before,
    proposedContent = after,
)

private val newPageSuggestion = PendingLlmSuggestion.NewPage(
    id = "np1",
    graphId = "g-main",
    sourceProviderId = "demo-provider",
    proposedAtEpochMs = 1_750_000_000_000L,
    rationale = "Collects the scattered notes on soil into one page.",
    proposedTitle = "Soil aeration",
    proposedBlocks = listOf(
        ProposedBlock("Overview of [[aeration]] methods", 0, 0),
        ProposedBlock("Core aeration reduces compaction", 1, 1),
        ProposedBlock("Best done in **early autumn**", 1, 2),
    ),
)

private val copyBase = CopyPagesState(
    direction = CopyDirection.Push,
    activeGraphName = "Main notes",
    rows = demoRows,
    totalMatching = 213,
    listLoad = ListLoad.Idle,
    destinations = demoDestinations,
    destinationId = GraphId("g-work"),
    picked = PickedPages(setOf(PageUuid("page-a"), PageUuid("page-c"))),
)

private val pullBase = CopyPagesState(
    direction = CopyDirection.Pull,
    activeGraphName = "Main notes",
    destinations = demoDestinations,
    destinationId = GraphId("g-work"),
)

internal val screenStories: List<GalleryStory> = listOf(
    GalleryStory("screens-empty-graph") {
        Bounded { EmptyGraphStateScreen(onTryDemo = {}, onCreateGraph = {}) }
    },
    GalleryStory("screens-empty-graph-error") {
        Bounded { EmptyGraphStateScreen(onTryDemo = {}, onCreateGraph = {}, errorMessage = "Couldn't open the selected folder.") }
    },
    GalleryStory("screens-library-setup") {
        Bounded { LibrarySetupScreen(onChooseFolder = {}) }
    },
    GalleryStory("screens-library-setup-error") {
        Bounded { LibrarySetupScreen(onChooseFolder = {}, errorMessage = "That folder is not readable.") }
    },
    GalleryStory("screens-permission-recovery") {
        Bounded {
            PermissionRecoveryScreen(
                folderName = "Documents/notes",
                onReconnectFolder = {},
                onChooseDifferentFolder = {},
            )
        }
    },
    GalleryStory("screens-permission-recovery-error") {
        Bounded {
            PermissionRecoveryScreen(
                folderName = null,
                onReconnectFolder = {},
                onChooseDifferentFolder = {},
                errorMessage = "Access to the folder was revoked.",
            )
        }
    },
    GalleryStory("screens-vault-unlock-locked") {
        Bounded { VaultUnlockScreen("Private notes", VaultState.Locked, { _, _ -> }) }
    },
    GalleryStory("screens-vault-unlock-invalid") {
        Bounded {
            VaultUnlockScreen("Private notes", VaultState.Error(VaultError.InvalidCredential()), { _, _ -> })
        }
    },
    GalleryStory("screens-disk-conflict-different") {
        Bounded {
            DiskConflictFullScreen(
                localContent = "- Plan the trip\n- Book flights\n- Pack bags",
                diskContent = "- Plan the trip\n- Book trains\n- Pack bags\n- Buy insurance",
                onDismiss = {},
            )
        }
    },
    GalleryStory("screens-disk-conflict-identical") {
        Bounded {
            DiskConflictFullScreen(localContent = "- Same text", diskContent = "- Same text", onDismiss = {})
        }
    },
    GalleryStory("screens-git-nav-row") {
        Column(Modifier.padding(16.dp)) {
            GitSetupNavRow(onBack = {}, onNext = {})
            GitSetupNavRow(onBack = {}, onNext = {}, nextEnabled = false)
        }
    },
    GalleryStory("screens-git-step1-clone-mode") {
        Column(Modifier.padding(16.dp)) {
            Step1CloneMode(CloneMode.UseExistingClone, {}, {})
        }
    },
    GalleryStory("screens-git-step3-auth-https") {
        Column(Modifier.padding(16.dp)) {
            Step3Auth(
                authType = GitAuthType.HTTPS_TOKEN,
                onAuthTypeChange = {},
                sshKeyPath = "",
                onSshKeyPathChange = {},
                httpsToken = "ghp_example",
                onHttpsTokenChange = {},
                tokenVisible = false,
                onToggleTokenVisible = {},
                sshPassphrase = "",
                onSshPassphraseChange = {},
                onBack = {},
                onNext = {},
            )
        }
    },
    GalleryStory("screens-git-step3-auth-ssh") {
        Column(Modifier.padding(16.dp)) {
            Step3Auth(
                authType = GitAuthType.SSH_KEY,
                onAuthTypeChange = {},
                sshKeyPath = "/home/demo/.ssh/id_ed25519",
                onSshKeyPathChange = {},
                httpsToken = "",
                onHttpsTokenChange = {},
                tokenVisible = false,
                onToggleTokenVisible = {},
                sshPassphrase = "",
                onSshPassphraseChange = {},
                onBack = {},
                onNext = {},
            )
        }
    },
    GalleryStory("screens-git-step4-branch") {
        Column(Modifier.padding(16.dp)) {
            Step4Branch("main", {}, 15, {}, {}, {})
        }
    },
    GalleryStory("screens-git-step5-idle") {
        Column(Modifier.padding(16.dp)) {
            Step5TestAndSave(GitConnectionTestState.Idle, false, null, {}, {}, {}, {})
        }
    },
    GalleryStory("screens-git-step5-success") {
        Column(Modifier.padding(16.dp)) {
            Step5TestAndSave(GitConnectionTestState.Success("Connected to origin/main"), false, null, {}, {}, {}, {})
        }
    },
    GalleryStory("screens-git-step5-failure") {
        Column(Modifier.padding(16.dp)) {
            Step5TestAndSave(
                GitConnectionTestState.Failure("Authentication failed (401)"),
                saving = false,
                saveError = "Could not save the configuration.",
                onBack = {},
                onTestConnection = {},
                onCancelTestConnection = {},
                onSave = {},
            )
        }
    },
    GalleryStory("screens-git-conflict-resolution") {
        Bounded(800.dp) {
            ConflictResolutionScreen(conflicts = conflictFiles, onResolve = { _, _ -> Unit.right() }, onDismiss = {})
        }
    },
    GalleryStory("screens-git-journal-merge-review") {
        Bounded(800.dp) {
            JournalMergeReviewScreen(mergeProposal, onAccept = {}, onFallbackToManual = {}, onDismiss = {})
        }
    },
    GalleryStory("screens-llm-block-diff") {
        Column(Modifier.padding(16.dp)) {
            BlockDiffView(
                beforeContent = "Core aeration reduce compaction",
                afterContent = "Core aeration reduces soil compaction",
            )
        }
    },
    GalleryStory("screens-llm-new-page-preview") {
        Column(Modifier.padding(16.dp)) {
            NewPagePreview("Soil aeration", newPageSuggestion.proposedBlocks)
        }
    },
    GalleryStory("screens-llm-suggestion-review-block-edit") {
        Bounded(800.dp) {
            LlmSuggestionReviewScreen(
                pending = listOf(
                    blockEdit("e1", "Core aeration reduce compaction", "Core aeration reduces soil compaction"),
                    blockEdit("e2", "TODO call plumber", "DONE call plumber"),
                ),
                onAccept = {}, onReject = {}, onAcceptAll = {}, onRejectAll = {}, onDismiss = {},
            )
        }
    },
    GalleryStory("screens-llm-suggestion-review-new-page") {
        Bounded(800.dp) {
            LlmSuggestionReviewScreen(
                pending = listOf(newPageSuggestion),
                onAccept = {}, onReject = {}, onAcceptAll = {}, onRejectAll = {}, onDismiss = {},
            )
        }
    },
    GalleryStory("screens-copy-page-selection-rows") {
        Column {
            PageSelectionRow(demoRows[0], checked = true, onToggle = {})
            PageSelectionRow(demoRows[1], checked = false, onToggle = {})
            PageSelectionRow(demoRows[2], checked = false, onToggle = {})
            PageSelectionRow(demoRows[3], checked = true, onToggle = {})
        }
    },
    GalleryStory("screens-copy-destination-rows") {
        Column(Modifier.padding(16.dp)) {
            demoDestinations.forEach { DestinationRowView(it, chosen = it.graphId == GraphId("g-work"), actions = CopyPagesActions()) }
        }
    },
    GalleryStory("screens-copy-pull-source-chooser") {
        Box(Modifier.height(500.dp).padding(16.dp)) { PullSourceChooser(pullBase, CopyPagesActions()) }
    },
    GalleryStory("screens-copy-pull-source-none") {
        Box(Modifier.height(200.dp).padding(16.dp)) {
            PullSourceChooser(pullBase.copy(destinations = demoDestinations.take(1), destinationId = null), CopyPagesActions())
        }
    },
    GalleryStory("screens-copy-pull-index-ready") {
        Box(Modifier.padding(16.dp)) { PullIndexStatus(pullBase.copy(indexState = PullIndexState.Ready(1280)), CopyPagesActions()) }
    },
    GalleryStory("screens-copy-pages-push") {
        Bounded(800.dp) { CopyPagesContent(copyBase, CopyPagesActions()) }
    },
)
