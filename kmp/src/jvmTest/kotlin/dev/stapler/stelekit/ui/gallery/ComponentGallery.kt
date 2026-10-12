package dev.stapler.stelekit.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.ui.components.BlockquoteBlock
import dev.stapler.stelekit.ui.components.CodeFenceBlock
import dev.stapler.stelekit.ui.components.DemoBanner
import dev.stapler.stelekit.ui.components.GitSyncStatus
import dev.stapler.stelekit.ui.components.HeadingBlock
import dev.stapler.stelekit.ui.components.MutedText
import dev.stapler.stelekit.ui.components.OrderedListItemBlock
import dev.stapler.stelekit.ui.components.SectionBadge
import dev.stapler.stelekit.ui.components.SnippetText
import dev.stapler.stelekit.ui.components.SyncStatusBadge
import dev.stapler.stelekit.ui.components.TableBlock
import dev.stapler.stelekit.ui.components.ThematicBreakBlock

/**
 * One named, deterministic rendering of a component (a "story"). Stories use fixed inputs only --
 * no clocks, animations or I/O -- so two runs of the same code produce the same pixels.
 */
data class GalleryStory(val id: String, val content: @Composable () -> Unit)

/**
 * Storybook-style registry of components rendered in isolation. To cover a new component, add a
 * [GalleryStory] here; [ComponentGalleryScreenshotTest] renders every story in each theme and
 * width class. Keep stories to stateless inputs and skip anything that animates.
 */
object ComponentGallery {

    private const val LONG_HEADING =
        "Asked/discussed [[^^liquid soil aeration^^]]; wrote one page: extension sources find no evidence it relieves compaction"

    private fun heading(level: Int, text: String = "Heading level $level with [[a wiki link]] and **bold**") =
        GalleryStory("heading-h$level") {
            HeadingBlock(
                content = "${"#".repeat(level)} $text",
                level = level,
                linkColor = MaterialTheme.colorScheme.primary,
                onStartEditing = {},
                onLinkClick = {},
            )
        }

    /** Typography and the small, self-contained block/inline components. Ids keep their original names. */
    private val basicStories: List<GalleryStory> = listOf(
        heading(1), heading(2), heading(3), heading(4), heading(5), heading(6),
        GalleryStory("heading-long-wrapping") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeadingBlock("# $LONG_HEADING", 1, MaterialTheme.colorScheme.primary, {}, {})
                HeadingBlock("## $LONG_HEADING", 2, MaterialTheme.colorScheme.primary, {}, {})
            }
        },
        GalleryStory("heading-scale-with-body") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..6).forEach { level ->
                    HeadingBlock("${"#".repeat(level)} Heading $level", level, MaterialTheme.colorScheme.primary, {}, {})
                }
                MutedText("Body text for scale comparison")
            }
        },
        GalleryStory("blockquote") {
            BlockquoteBlock(
                content = "> A quoted block with a [[wiki link]] and *emphasis*.",
                linkColor = MaterialTheme.colorScheme.primary,
                onStartEditing = {},
                onLinkClick = {},
            )
        },
        GalleryStory("code-fence-kotlin") {
            CodeFenceBlock(
                content = "```kotlin\nfun greet(name: String) = \"Hello, \$name\"\n```",
                language = "kotlin",
                onStartEditing = {},
            )
        },
        GalleryStory("thematic-break") { ThematicBreakBlock(onStartEditing = {}) },
        GalleryStory("ordered-list") {
            Column {
                OrderedListItemBlock("1. First step with a [[link]]", 1, MaterialTheme.colorScheme.primary, {}, {})
                OrderedListItemBlock("2. Second step", 2, MaterialTheme.colorScheme.primary, {}, {})
            }
        },
        GalleryStory("table") {
            TableBlock(
                content = """
                    | Scenario | Prefer | Reason |
                    |---|---|---|
                    | Small collection | Collection | Inline functions |
                    | Large collection | Sequence | Avoids allocations |
                """.trimIndent(),
                onStartEditing = {},
            )
        },
        GalleryStory("muted-text") { MutedText("Muted helper text") },
        GalleryStory("snippet-text") { SnippetText("A search result with a <em>highlighted</em> match") },
        GalleryStory("section-badge") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionBadge(SectionDefinition("work", "Work", "#3B82F6", "pages/work/", "journals/work/"))
                SectionBadge(SectionDefinition("plain", "No colour", null, "pages/plain/", "journals/plain/"))
            }
        },
        GalleryStory("sync-status-badge") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    GitSyncStatus(SyncState.Idle),
                    GitSyncStatus(SyncState.MergeAvailable(3)),
                    GitSyncStatus(SyncState.LocalChangesPending(4)),
                    GitSyncStatus(SyncState.RateLimited(30)),
                    GitSyncStatus(SyncState.CredentialVaultLocked),
                    GitSyncStatus(SyncState.Idle, firstSyncReviewPending = true),
                ).forEach { SyncStatusBadge(status = it, onSyncClick = {}, onReviewFirstSync = {}) }
            }
        },
        GalleryStory("demo-banner") { DemoBanner(onDismiss = {}, modifier = Modifier.padding(0.dp)) },
    )

    /**
     * Every story, grouped by file. Category files (one per area) each export a list; keep ids
     * prefixed `blocks-`, `dialogs-`, `chrome-`, `settings-`, `screens-` so a run can be filtered.
     */
    val stories: List<GalleryStory> =
        basicStories + blockStories + dialogStories + chromeStories + settingsStories + screenStories
}
