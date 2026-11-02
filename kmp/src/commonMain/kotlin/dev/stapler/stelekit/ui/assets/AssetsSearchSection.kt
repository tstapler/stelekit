package dev.stapler.stelekit.ui.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.AssetSearchHit
import dev.stapler.stelekit.asset.AssetSearchService
import dev.stapler.stelekit.asset.AssetUuid
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val SEARCHING_FILES_TEXT = "Searching files..."
internal const val ASSETS_SEARCH_FAILED_TEXT = "Couldn't search files - try again"
internal const val ASSETS_SEARCH_DEBOUNCE_MS = 250L
internal const val ASSETS_SEARCH_SLOW_MS = 200L

private sealed interface SectionState {
    data object Hidden : SectionState
    data object Searching : SectionState
    data object Failed : SectionState
    data class Results(val hits: List<AssetSearchHit>) : SectionState
}

/**
 * Builds [excerpt] with every case-insensitive occurrence of [query] bold on a tinted background.
 * Never underlined: underline is reserved for tappable links, and weight plus tint is not color-only.
 */
@Composable
internal fun assetSnippet(excerpt: String, query: String): AnnotatedString {
    val tint = MaterialTheme.colorScheme.tertiaryContainer
    return buildAnnotatedString {
        val needle = query.trim()
        var from = 0
        while (needle.isNotEmpty()) {
            val at = excerpt.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            append(excerpt.substring(from, at))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = tint, textDecoration = TextDecoration.None)) {
                append(excerpt.substring(at, at + needle.length))
            }
            from = at + needle.length
        }
        append(excerpt.substring(from))
    }
}

/**
 * The "Assets" section under the global-search results. It queries only while [visible] and the
 * query has at least [AssetSearchService.MIN_GLOBAL_QUERY_LENGTH] characters, after a debounce
 * that the next keystroke cancels, at most [AssetSearchService.GLOBAL_SECTION_LIMIT] rows. A
 * slow query shows "Searching files...", a failure shows a retry row, and no matches hide it.
 */
@Composable
fun AssetsSearchSection(
    query: String,
    service: AssetSearchService,
    onShowAll: (String) -> Unit,
    onOpenAsset: (AssetUuid) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    debounceMs: Long = ASSETS_SEARCH_DEBOUNCE_MS,
    slowMs: Long = ASSETS_SEARCH_SLOW_MS,
) {
    var state by remember { mutableStateOf<SectionState>(SectionState.Hidden) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(query, visible, retry) {
        if (!visible || !AssetSearchService.shouldQueryGlobal(query)) {
            state = SectionState.Hidden
            return@LaunchedEffect
        }
        delay(debounceMs)
        coroutineScope {
            val slow = launch {
                delay(slowMs)
                state = SectionState.Searching
            }
            val result = service.search(query.trim(), AssetSearchService.GLOBAL_SECTION_LIMIT)
            slow.cancel()
            state = result.fold(
                ifLeft = { SectionState.Failed },
                ifRight = { hits -> if (hits.isEmpty()) SectionState.Hidden else SectionState.Results(hits) },
            )
        }
    }
    when (val s = state) {
        SectionState.Hidden -> Unit
        SectionState.Searching -> SectionBody(modifier) {
            Text(SEARCHING_FILES_TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionState.Failed -> SectionBody(modifier) {
            Text(ASSETS_SEARCH_FAILED_TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text("Retry") }
        }
        is SectionState.Results -> SectionBody(modifier, action = {
            TextButton(onClick = { onShowAll(query.trim()) }) { Text("Show all in Assets") }
        }) {
            s.hits.forEach { hit -> AssetHitRow(hit, query.trim(), onOpenAsset) }
        }
    }
}

@Composable
private fun SectionBody(modifier: Modifier = Modifier, action: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The action sits beside the title so it stays on screen however many rows follow.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Assets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            action()
        }
        content()
    }
}

/** One result is one node: file name, source label and the emphasized snippet read together. */
@Composable
private fun AssetHitRow(hit: AssetSearchHit, query: String, onOpenAsset: (AssetUuid) -> Unit) {
    val name = hit.asset.filePath.substringAfterLast('/')
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onOpenAsset(hit.asset.uuid) }
            .semantics(mergeDescendants = true) {},
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        hit.preview?.sourceLabel?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        hit.preview?.takeIf { it.excerpt.isNotEmpty() }?.let {
            Text(assetSnippet(it.excerpt, query), style = MaterialTheme.typography.bodySmall)
        }
    }
}
