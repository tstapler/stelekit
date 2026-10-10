package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.query.QueryExecutor
import dev.stapler.stelekit.query.QueryParser
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/** Active graph's query dependencies, provided once above the page tree (see ScreenRouter). */
internal data class QueryBlockContext(
    val executor: QueryExecutor?,
    val pageRepository: PageRepository?,
    /** `live_query_blocks` debug flag; false renders `{{query}}` as literal text. */
    val enabled: Boolean,
)

@Suppress("CompositionLocalAllowlist") // graph-scoped service context, set once above the page tree
internal val LocalQueryBlockContext = staticCompositionLocalOf {
    QueryBlockContext(executor = null, pageRepository = null, enabled = false)
}

/** Max rows rendered before the "+K more" affix; the fetch ceiling is [QueryExecutor.DEFAULT_LIMIT]. */
internal const val MAX_QUERY_RESULTS_DISPLAY = 50

private val queryArgRegex = Regex("""^\{\{\s*query\s+(.*)\}\}$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Returns the argument of a block whose entire content is a single `{{query ...}}` macro, else null. */
internal fun queryArgFromContent(content: String): String? =
    queryArgRegex.matchEntire(content.trim())?.groupValues?.get(1)?.trim()

internal fun queryResultCountText(count: Int): String =
    if (count >= QueryExecutor.DEFAULT_LIMIT) "$count+ results" else "$count results"

/**
 * Live, read-only result list for a whole-block `{{query ...}}` macro. Handles the loading,
 * results, empty, read-error, and recognized-but-unsupported states; a malformed query never
 * reaches here (BlockItem keeps rendering it as literal text).
 *
 * Never requests focus: live updates must not steal focus from an edit in progress elsewhere.
 */
@Composable
internal fun QueryBlock(
    rawQuery: String,
    queryExecutor: QueryExecutor?,
    pageRepository: PageRepository?,
    onStartEditing: () -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(rawQuery) { QueryParser.parse(rawQuery) }
    var collapsed by remember { mutableStateOf(false) }

    // Keyed on the executor so a graph switch rebuilds the Flow against the new graph's repositories.
    val state: Either<DomainError, List<Block>>? by remember(rawQuery, queryExecutor) {
        val flow: Flow<Either<DomainError, List<Block>>?> = parsed.fold(
            { flowOf(null) },
            { q -> queryExecutor?.executeQuery(q) ?: flowOf(null) },
        )
        flow
    }.collectAsState(initial = null)

    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val unsupported = parsed.leftOrNull() is DomainError.ParseError.UnsupportedForm
    val fetched = state?.getOrNull()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.outlineVariant, shape)
            .background(colors.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onStartEditing)
                .padding(start = 10.dp, top = 2.dp, end = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = rawQuery,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (fetched != null) {
                Text(
                    text = queryResultCountText(fetched.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (collapsed) Icons.Default.ArrowDropDown else Icons.Default.ArrowDropUp,
                    contentDescription = if (collapsed) "Expand query results" else "Collapse query results",
                )
            }
        }

        if (!collapsed) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                when {
                    unsupported -> Column {
                        Text(
                            "Unsupported query — showing raw text:",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            "{{query $rawQuery}}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state == null -> Text(
                        "Loading query results...",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    state?.isLeft() == true -> Text(
                        "Unable to load results",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                    fetched.isNullOrEmpty() -> Text(
                        "No matching blocks",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    else -> QueryResultRows(fetched, pageRepository, onLinkClick)
                }
            }
        }
    }
}

@Composable
private fun QueryResultRows(
    results: List<Block>,
    pageRepository: PageRepository?,
    onLinkClick: (String) -> Unit,
) {
    val visible = remember(results) { results.take(MAX_QUERY_RESULTS_DISPLAY) }
    val pageNames by produceState(emptyMap<String, String>(), visible, pageRepository) {
        val repo = pageRepository ?: return@produceState
        value = visible.map { it.pageUuid.value }.distinct().mapNotNull { uuid ->
            repo.getPageByUuid(PageUuid(uuid)).first().getOrNull()?.name?.let { uuid to it }
        }.toMap()
    }

    Column(
        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visible.forEach { block ->
            val pageName = pageNames[block.pageUuid.value]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = block.content }
                    .let { if (pageName != null) it.clickable { onLinkClick(pageName) } else it }
                    .padding(vertical = 3.dp),
            ) {
                Text(block.content, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (pageName != null) {
                    Text(pageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        val remaining = results.size - visible.size
        if (remaining > 0) {
            Text("+$remaining more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
