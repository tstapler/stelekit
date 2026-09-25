// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.git.model.GitRepoHistoryEntry
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.ui.components.MutedText
import kotlinx.coroutines.withContext

@Composable
internal fun Step2RepoPath(
    cloneMode: CloneMode,
    repoRoot: String,
    onRepoRootChange: (String) -> Unit,
    cloneUrl: String,
    onCloneUrlChange: (String) -> Unit,
    graphName: String,
    onGraphNameChange: (String) -> Unit,
    graphDescription: String,
    onGraphDescriptionChange: (String) -> Unit,
    wikiSubdir: String,
    onWikiSubdirChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean = repoRoot.isNotBlank(),
    onBrowseRepoRoot: (() -> Unit)? = null,
    onBrowseWikiSubdir: (() -> Unit)? = null,
    // ponytail: detectionUnavailable/existingRepoNeedsAllFilesAccess/saveToAppStorage/
    // showAppStorageChoice each gate a genuinely independent, mutually-exclusive display state
    // (not one flag secretly doing two things) — left as plain booleans rather than forced into
    // an enum that wouldn't add clarity.
    detectionUnavailable: Boolean = false,
    existingRepoNeedsAllFilesAccess: Boolean = false,
    saveToAppStorage: Boolean = false,
    // True only where an App-storage-vs-custom-folder choice actually exists (cloning a new repo
    // on a platform with app-owned storage, i.e. Android today) — everywhere else this screen has
    // only ever shown a plain path field, unaffected by any of this.
    showAppStorageChoice: Boolean = false,
    onSelectAppStorage: (() -> Unit)? = null,
    repoHistory: List<GitRepoHistoryEntry> = emptyList(),
    onSelectHistoryEntry: ((GitRepoHistoryEntry) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Repository path", style = MaterialTheme.typography.titleMedium)

        if (cloneMode == CloneMode.CloneNewRepository) {
            CloneUrlSection(
                repoHistory = repoHistory,
                onSelectHistoryEntry = onSelectHistoryEntry ?: {},
                cloneUrl = cloneUrl,
                onCloneUrlChange = onCloneUrlChange,
                graphName = graphName,
                onGraphNameChange = onGraphNameChange,
                graphDescription = graphDescription,
                onGraphDescriptionChange = onGraphDescriptionChange,
            )
        }

        RepoRootSection(
            cloneMode = cloneMode,
            showAppStorageChoice = showAppStorageChoice,
            saveToAppStorage = saveToAppStorage,
            onSelectAppStorage = onSelectAppStorage ?: {},
            repoHistory = repoHistory,
            onSelectHistoryEntry = onSelectHistoryEntry ?: {},
            repoRoot = repoRoot,
            onRepoRootChange = onRepoRootChange,
            onBrowseRepoRoot = onBrowseRepoRoot,
        )

        RepoRootAccessWarning(
            existingRepoNeedsAllFilesAccess = existingRepoNeedsAllFilesAccess,
            detectionUnavailable = detectionUnavailable,
        )

        WikiSubdirField(
            wikiSubdir = wikiSubdir,
            onWikiSubdirChange = onWikiSubdirChange,
            repoRoot = repoRoot,
            onBrowseWikiSubdir = onBrowseWikiSubdir,
        )

        GitSetupNavRow(onBack = onBack, onNext = onNext, nextEnabled = nextEnabled)
    }
}

/** Clone-URL + graph name/description fields, shown only for [CloneMode.CloneNewRepository]. */
@Composable
private fun CloneUrlSection(
    repoHistory: List<GitRepoHistoryEntry>,
    onSelectHistoryEntry: (GitRepoHistoryEntry) -> Unit,
    cloneUrl: String,
    onCloneUrlChange: (String) -> Unit,
    graphName: String,
    onGraphNameChange: (String) -> Unit,
    graphDescription: String,
    onGraphDescriptionChange: (String) -> Unit,
) {
    // Compose-rules MultipleEmitters: this function must be self-contained in its own layout
    // rather than relying on the caller's Column, so it renders correctly regardless of context.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (repoHistory.isNotEmpty()) {
            RecentRepoList(repoHistory, onSelectHistoryEntry)
        }
        OutlinedTextField(
            value = cloneUrl,
            onValueChange = onCloneUrlChange,
            label = { Text("Remote URL (HTTPS or SSH)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = graphName,
            onValueChange = onGraphNameChange,
            label = { Text("Graph name (optional)") },
            placeholder = { Text(repoNameFromUrl(cloneUrl) ?: "Defaults to the repository name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = graphDescription,
            onValueChange = onGraphDescriptionChange,
            label = { Text("Description (optional)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
    }
}

/**
 * The repo-destination field — either the App-storage-vs-custom-folder radio choice (only where
 * [showAppStorageChoice] applies) or a plain path field, each with its own "Recent" picker above
 * it when [repoHistory] has entries for the currently relevant field.
 *
 * ponytail: [showAppStorageChoice]/[saveToAppStorage] are the same independent, mutually-exclusive
 * display gates [Step2RepoPath]'s own KDoc explains — not one flag secretly doing two things.
 */
@Composable
private fun RepoRootSection(
    cloneMode: CloneMode,
    showAppStorageChoice: Boolean,
    saveToAppStorage: Boolean,
    onSelectAppStorage: () -> Unit,
    repoHistory: List<GitRepoHistoryEntry>,
    onSelectHistoryEntry: (GitRepoHistoryEntry) -> Unit,
    repoRoot: String,
    onRepoRootChange: (String) -> Unit,
    onBrowseRepoRoot: (() -> Unit)?,
) {
    // Compose-rules MultipleEmitters: see CloneUrlSection's comment above.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showAppStorageChoice) {
            RepoRootStorageChoice(
                saveToAppStorage = saveToAppStorage,
                onSelectAppStorage = onSelectAppStorage,
                onSelectCustomFolder = { onBrowseRepoRoot?.invoke() },
            )
        } else if (cloneMode == CloneMode.UseExistingClone && repoHistory.isNotEmpty()) {
            RecentRepoList(repoHistory, onSelectHistoryEntry)
        }
        // App storage needs no path field — nothing to type or browse to.
        if (!showAppStorageChoice || !saveToAppStorage) {
            RepoRootPathField(repoRoot = repoRoot, onRepoRootChange = onRepoRootChange, onBrowseRepoRoot = onBrowseRepoRoot)
        }
    }
}

@Composable
private fun WikiSubdirField(
    wikiSubdir: String,
    onWikiSubdirChange: (String) -> Unit,
    repoRoot: String,
    onBrowseWikiSubdir: (() -> Unit)?,
) {
    OutlinedTextField(
        value = wikiSubdir,
        onValueChange = onWikiSubdirChange,
        label = { Text("Notes subfolder (optional)") },
        placeholder = { Text("e.g. logseq or notes/pages") },
        supportingText = {
            Text(wikiSubdirError(wikiSubdir) ?: "Relative to the repository root. Leave empty if notes are at the root.")
        },
        isError = wikiSubdirError(wikiSubdir) != null,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = if (onBrowseWikiSubdir != null && repoRoot.isNotBlank()) {
            {
                IconButton(onClick = onBrowseWikiSubdir) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Browse subfolders of the repository root",
                    )
                }
            }
        } else null,
    )
}

/**
 * Explicit App-storage-vs-custom-folder chooser for the "clone a remote repository" destination —
 * previously this was a passive "Save to: App storage (default) [Change…]" summary card, which
 * read as a fait accompli rather than a choice between two options (UX finding from the wizard's
 * screenshot audit). Mirrors [Step1CloneMode]'s exact radio-row convention so both "which mode am
 * I in" choices in this wizard look and behave the same way.
 */
@Composable
private fun RepoRootStorageChoice(
    saveToAppStorage: Boolean,
    onSelectAppStorage: () -> Unit,
    onSelectCustomFolder: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Save to", style = MaterialTheme.typography.labelMedium)
        StorageChoiceRow(
            label = "App storage",
            subtitle = "Private to SteleKit — no folder access needed.",
            selected = saveToAppStorage,
            onClick = onSelectAppStorage,
        )
        StorageChoiceRow(
            label = "Custom folder",
            subtitle = "Choose where the clone is saved on disk.",
            selected = !saveToAppStorage,
            onClick = onSelectCustomFolder,
        )
    }
}

@Composable
private fun StorageChoiceRow(label: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            MutedText(subtitle)
        }
    }
}

/**
 * "Recent" picker above the repo-URL/repo-path field — mirrors [GitSetupStep3Auth]'s "Saved
 * tokens"/"Saved accounts" rows exactly (same [Role.RadioButton] row shape), since this is the
 * same "remembered X, pick one or enter a new one" pattern applied to repository locations
 * instead of credentials (see `GitRepoHistoryStore`'s KDoc). Unlike Step 3's saved-credential
 * rows, picking an entry here doesn't hide the field below it — the field stays the single
 * source of truth and a pick just fills it, so editing what was picked needs no extra "switch
 * back to manual entry" step.
 */
@Composable
private fun RecentRepoList(
    entries: List<GitRepoHistoryEntry>,
    onSelect: (GitRepoHistoryEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Recent", style = MaterialTheme.typography.labelMedium)
        entries.forEach { entry -> RecentRepoRow(entry, onClick = { onSelect(entry) }) }
    }
}

@Composable
private fun RecentRepoRow(entry: GitRepoHistoryEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = false, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = false, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(entry.value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (entry.wikiSubdir.isNotBlank()) MutedText("Notes in ${entry.wikiSubdir}")
        }
    }
}

@Composable
private fun RepoRootPathField(
    repoRoot: String,
    onRepoRootChange: (String) -> Unit,
    onBrowseRepoRoot: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Pick the folder that directly contains .git — usually your project's top-level " +
                "folder, not a notes/pages subfolder inside it. You can point at a subfolder " +
                "separately below.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = repoRoot,
            onValueChange = onRepoRootChange,
            label = { Text("Local repository root path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = if (onBrowseRepoRoot != null) {
                {
                    IconButton(onClick = onBrowseRepoRoot) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Browse for directory",
                        )
                    }
                }
            } else null,
        )
    }
}

@Composable
private fun RepoRootAccessWarning(
    existingRepoNeedsAllFilesAccess: Boolean,
    detectionUnavailable: Boolean,
) {
    if (existingRepoNeedsAllFilesAccess) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "This folder was picked via the system document picker, so SteleKit can " +
                    "only see its content — not open its .git as a real repository. " +
                    "Connecting to an existing repository this way requires granting " +
                    "\"All files access\", or use \"Clone new repo\" instead with this " +
                    "same remote URL.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { dev.stapler.stelekit.platform.openAllFilesAccessSettings() }) {
                Text("Open \"All files access\" settings")
            }
        }
    } else if (detectionUnavailable) {
        MutedText(
            "No .git found directly in this folder, and SteleKit can't look above it to " +
                "find one automatically (the system document picker only grants access to " +
                "what you picked and what's inside it). If your repository is a parent " +
                "folder, browse again and pick that folder instead. If your notes live in a " +
                "subfolder of what you picked, use \"Browse\" below to select it.",
        )
    }
}

/**
 * In-app subfolder browser scoped to [repoRoot] — replaces free-text entry for the common "notes
 * live in a subfolder of the repo" case, since [repoRoot] itself is frequently an opaque picker
 * URI (Android SAF, wasm OPFS) that a user cannot meaningfully hand-type a path relative to.
 * Built entirely on [FileSystem.listDirectories], which every platform actual already implements
 * for its own path scheme — no new platform-specific plumbing needed.
 */
@Composable
internal fun WikiSubdirBrowserDialog(
    fileSystem: FileSystem,
    repoRoot: String,
    initialSubdir: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var segments by remember {
        mutableStateOf(initialSubdir.split('/').filter { it.isNotBlank() })
    }
    var subdirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(segments) {
        loading = true
        val path = (listOf(repoRoot) + segments).joinToString("/")
        subdirs = withContext(PlatformDispatcher.IO) { fileSystem.listDirectories(path).sorted() }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select wiki subdirectory") },
        text = {
            WikiSubdirBrowserBody(
                segments = segments,
                subdirs = subdirs,
                loading = loading,
                onNavigateUp = { segments = segments.dropLast(1) },
                onNavigateInto = { name -> segments = segments + name },
            )
        },
        confirmButton = {
            Button(onClick = { onSelect(segments.joinToString("/")); onDismiss() }) {
                Text("Select this folder")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun WikiSubdirBrowserBody(
    segments: List<String>,
    subdirs: List<String>,
    // ponytail: loading is the standard spinner-vs-content flag — the function's entire purpose,
    // not a hidden second responsibility.
    loading: Boolean,
    onNavigateUp: () -> Unit,
    onNavigateInto: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (segments.isEmpty()) "/ (repository root)" else "/" + segments.joinToString("/"),
            style = MaterialTheme.typography.labelMedium,
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            WikiSubdirList(
                segments = segments,
                subdirs = subdirs,
                onNavigateUp = onNavigateUp,
                onNavigateInto = onNavigateInto,
            )
        }
    }
}

@Composable
private fun WikiSubdirList(
    segments: List<String>,
    subdirs: List<String>,
    onNavigateUp: () -> Unit,
    onNavigateInto: (String) -> Unit,
) {
    Column(
        modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
    ) {
        if (segments.isNotEmpty()) {
            TextButton(onClick = onNavigateUp) {
                Text("..")
            }
        }
        subdirs.forEach { name ->
            TextButton(onClick = { onNavigateInto(name) }) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(name)
            }
        }
        if (subdirs.isEmpty()) {
            MutedText("No subfolders here.")
        }
    }
}
