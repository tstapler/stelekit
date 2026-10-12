package dev.stapler.stelekit.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.repository.DateRange
import dev.stapler.stelekit.repository.SearchScope
import dev.stapler.stelekit.ui.Command
import dev.stapler.stelekit.ui.components.CommandItem
import dev.stapler.stelekit.ui.components.SearchResultRow
import dev.stapler.stelekit.ui.components.ActivePrefixChipRow
import dev.stapler.stelekit.ui.components.settings.CategoryItem
import dev.stapler.stelekit.ui.components.settings.SettingsCategory
import dev.stapler.stelekit.ui.screens.ParsedQuery
import dev.stapler.stelekit.ui.screens.copy.CopyBackgroundNotice
import kotlin.time.Instant
import androidx.compose.material3.Text
import dev.stapler.stelekit.db.StorageMoveUiState
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.CustomProviderConfig
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.ui.DiskConflict
import dev.stapler.stelekit.ui.components.CommandPalette
import dev.stapler.stelekit.ui.components.DiskConflictDialog
import dev.stapler.stelekit.ui.components.NewGraphDialog
import dev.stapler.stelekit.ui.components.PlainGraphAppOwnedWarningDialog
import dev.stapler.stelekit.ui.components.RenamePageDialog
import dev.stapler.stelekit.ui.components.SectionPickerDialog
import dev.stapler.stelekit.ui.components.StorageMoveChoiceDialog
import dev.stapler.stelekit.ui.components.StorageMoveConfirmDialog
import dev.stapler.stelekit.ui.components.StorageMoveProgressDialog
import dev.stapler.stelekit.ui.components.settings.AddEditLlmProviderDialog
import dev.stapler.stelekit.ui.screens.copy.CopyDialogSurface
import dev.stapler.stelekit.ui.screens.copy.CopyPullSwitchConfirmDialog
import dev.stapler.stelekit.ui.screens.copy.CopyRunFailedDialog
import dev.stapler.stelekit.ui.screens.copy.InterruptedCopyDialog
import dev.stapler.stelekit.ui.screens.copy.InterruptedCopyNotice
import dev.stapler.stelekit.ui.screens.copy.InterruptedProblem
import dev.stapler.stelekit.ui.screens.git.GitHubOAuthDialog
import dev.stapler.stelekit.ui.screens.git.OAuthDialogState

/*
 * Dialog stories. The harness captures the last root, so each story shows exactly one real
 * dialog (a second dialog would stack another layer). The window-free pieces rendered inside
 * dialogs are covered as well.
 *
 * Skipped: SearchDialog (needs a SearchViewModel over repositories), CapturePreviewDialog
 * (loads a file via Coil), CopyProgressDialog / GitHubOAuthDialog Loading+ShowCode+Polling
 * (spinner animation or live countdown clock), SettingsDialog (needs many injected stores).
 */

private val FIXED_DATE = Instant.fromEpochSeconds(1_700_000_000)

private val galleryPage = Page(
    uuid = PageUuid("00000000-0000-0000-0000-000000000999"),
    name = "Project Notes",
    createdAt = FIXED_DATE,
    updatedAt = FIXED_DATE,
)
private val appOwned = StorageLocation.AppOwned("g1")
private val hostFolder = StorageLocation.HostFolder("g1", "Documents/notes")
private val safFolder = StorageLocation.SafFolder("g1", "content://tree/notes")

private fun moveProgress(id: String, state: StorageMoveUiState) =
    GalleryStory("dialogs-storage-move-progress-$id") { StorageMoveProgressDialog("My Notes", state, {}, {}, {}, {}) }

private fun oauth(id: String, state: OAuthDialogState) =
    GalleryStory("dialogs-github-oauth-$id") { GitHubOAuthDialog(state, {}, {}, {}, {}, {}) }

private fun interrupted(id: String, notice: InterruptedCopyNotice) =
    GalleryStory("dialogs-interrupted-copy-$id") { InterruptedCopyDialog(notice, {}, {}) }

private val realDialogStories: List<GalleryStory> = listOf(
    GalleryStory("dialogs-rename-page-idle") { RenamePageDialog(galleryPage, busy = false, error = null, onConfirm = {}, onDismiss = {}) },
    GalleryStory("dialogs-rename-page-busy") { RenamePageDialog(galleryPage, busy = true, error = null, onConfirm = {}, onDismiss = {}) },
    GalleryStory("dialogs-rename-page-error") {
        RenamePageDialog(galleryPage, busy = false, error = "A page named \"Archive\" already exists", onConfirm = {}, onDismiss = {})
    },
    GalleryStory("dialogs-new-graph") {
        NewGraphDialog(
            name = "Research", onNameChange = {}, description = "Notes for the field study",
            onDescriptionChange = {}, canCreate = true, onCreate = {}, onDismiss = {},
            location = { Text("Location: App storage") },
        )
    },
    GalleryStory("dialogs-new-graph-empty") {
        NewGraphDialog(
            name = "", onNameChange = {}, description = "", onDescriptionChange = {},
            canCreate = false, onCreate = {}, onDismiss = {}, location = { Text("Location: App storage") },
        )
    },
    GalleryStory("dialogs-disk-conflict") {
        val conflict = DiskConflict.ifReal(
            pageUuid = "00000000-0000-0000-0000-000000000999",
            pageName = "Project Notes",
            filePath = "pages/Project Notes.md",
            editingBlockUuid = BlockUuid("00000000-0000-0000-0000-000000000123"),
            localContent = "Draft the plan for the launch",
            diskContent = "- Draft the plan for the release\n- Review budget",
            diskBlockContent = "Draft the plan for the release",
        )
        if (conflict != null) DiskConflictDialog(conflict, {}, {}, {}, {}, {}) else Text("conflict fixture invalid")
    },
    GalleryStory("dialogs-plain-graph-app-owned-warning") {
        PlainGraphAppOwnedWarningDialog(
            bodyText = "Graphs in app storage are not visible to other apps and are removed if you uninstall SteleKit.",
            onExportZip = { true }, onCreateAnyway = {}, onGoBack = {},
        )
    },
    GalleryStory("dialogs-plain-graph-app-owned-warning-no-export") {
        PlainGraphAppOwnedWarningDialog(
            bodyText = "Graphs in app storage are not visible to other apps and are removed if you uninstall SteleKit.",
            onExportZip = null, onCreateAnyway = {}, onGoBack = {},
        )
    },
    GalleryStory("dialogs-storage-move-choice") {
        StorageMoveChoiceDialog("My Notes", appOwned, safFolder, {}, {}, onDismissRequest = {})
    },
    GalleryStory("dialogs-storage-move-choice-no-link") {
        StorageMoveChoiceDialog("My Notes", appOwned, safFolder, {}, {}, isLinkAvailable = false, onDismissRequest = {})
    },
    GalleryStory("dialogs-storage-move-confirm") {
        StorageMoveConfirmDialog("My Notes", appOwned, hostFolder, onConfirm = {}, onDismissRequest = {})
    },
    moveProgress("copying", StorageMoveUiState.Copying(4200, 8030)),
    moveProgress("summary", StorageMoveUiState.Summary),
    moveProgress("failed", StorageMoveUiState.Failed(DomainError.StorageError.VerificationFailed("pages/foo.md", "hash mismatch"))),
    moveProgress("reopen-failed", StorageMoveUiState.ReopenFailed(GraphId("g1"), DomainError.StorageError.ReopenFailed("g1"))),
    GalleryStory("dialogs-section-picker") {
        SectionPickerDialog(
            sections = listOf(
                SectionDefinition("work", "Work", "#3B82F6", "pages/work/", "journals/work/"),
                SectionDefinition("home", "Home", "#10B981", "pages/home/", "journals/home/"),
            ),
            currentSectionId = "work", onSelect = {}, onDismiss = {},
        )
    },
    GalleryStory("dialogs-copy-pull-switch-confirm") { CopyPullSwitchConfirmDialog("Work Notes", {}, {}) },
    GalleryStory("dialogs-copy-run-failed") {
        CopyRunFailedDialog("Work Notes", "The destination folder is read-only.", {}, {}, {})
    },
    GalleryStory("dialogs-copy-dialog-surface") {
        CopyDialogSurface(title = "Dialog surface", onDismissRequest = {}) { Text("Shared chrome for the copy dialogs.") }
    },
    interrupted("resumable", InterruptedCopyNotice("Work Notes", pagesCopied = 120, totalPages = 400)),
    interrupted("staging-gone", InterruptedCopyNotice("Work Notes", 120, 400, InterruptedProblem.StagingGone)),
    interrupted("target-gone", InterruptedCopyNotice("Work Notes", 120, null, InterruptedProblem.TargetGone)),
    oauth("success", OAuthDialogState.Success("tstapler")),
    oauth("error", OAuthDialogState.Error("The device code expired. Try again.")),
    GalleryStory("dialogs-add-llm-provider-new") { AddEditLlmProviderDialog(existingConfig = null, onSave = { _, _ -> }, onCancel = {}) },
    GalleryStory("dialogs-add-llm-provider-edit") {
        AddEditLlmProviderDialog(
            existingConfig = CustomProviderConfig("p1", "Local Ollama", "http://localhost:11434/v1", "llama3", allowInsecureHttp = true),
            onSave = { _, _ -> }, onCancel = {},
        )
    },
    GalleryStory("dialogs-command-palette") {
        CommandPalette(
            visible = true,
            commands = listOf(
                Command("new-page", "New page", "Ctrl+N") {},
                Command("search", "Search everything", "Ctrl+K") {},
                Command("settings", "Open settings") {},
            ),
            onDismiss = {},
        )
    },
)

internal val dialogStories: List<GalleryStory> = realDialogStories + listOf(
    GalleryStory("dialogs-search-result-row-plain") {
        Column {
            SearchResultRow(title = "Project Notes", isSelected = false, onClick = {})
            SearchResultRow(title = "Project Notes", isSelected = true, onClick = {})
        }
    },
    GalleryStory("dialogs-search-result-row-rich") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchResultRow(
                title = "Garden Planning",
                isSelected = false,
                onClick = {},
                subtitle = "Projects / Home / Garden",
                relativeDate = "2 days ago",
                inlineTags = listOf("garden", "spring"),
            )
            SearchResultRow(
                title = "Soil notes",
                isSelected = true,
                onClick = {},
                subtitle = "Journal / 2026-04-12",
                relativeDate = "Yesterday",
                snippet = "Added compost to the <em>raised beds</em> and tested pH",
            )
        }
    },
    GalleryStory("dialogs-search-prefix-chips") {
        ActivePrefixChipRow(
            parsedQuery = ParsedQuery(
                ftsTerm = "compost",
                dateRange = DateRange(startDate = FIXED_DATE, endDate = FIXED_DATE),
                tagFilter = "garden",
                scopeOverride = SearchScope.PAGES_ONLY,
                propertyFilters = mapOf("status" to "active"),
            ),
            onRemoveTag = {}, onRemoveScope = {}, onRemoveDate = {}, onRemoveProperty = {},
        )
    },
    GalleryStory("dialogs-search-prefix-chips-tag-only") {
        ActivePrefixChipRow(
            parsedQuery = ParsedQuery(ftsTerm = "compost", tagFilter = "garden"),
            onRemoveTag = {}, onRemoveScope = {}, onRemoveDate = {}, onRemoveProperty = {},
        )
    },
    GalleryStory("dialogs-command-palette-items") {
        Column {
            CommandItem(Command("new-page", "New page", "Ctrl+N") {}, isSelected = false, onClick = {})
            CommandItem(Command("search", "Search everything", "Ctrl+K") {}, isSelected = true, onClick = {})
            CommandItem(Command("settings", "Open settings") {}, isSelected = false, onClick = {})
        }
    },
    GalleryStory("dialogs-settings-category-items") {
        Column {
            SettingsCategory.entries.take(5).forEachIndexed { i, c ->
                CategoryItem(category = c, isSelected = i == 1, onClick = {})
            }
        }
    },
    GalleryStory("dialogs-copy-background-notice") { CopyBackgroundNotice(targetName = "Work Notes") },
)
