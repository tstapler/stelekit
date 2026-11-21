// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGuideStrings

object HelpSearchTags {
    const val DIALOG = "sketch.help"
    const val FIELD = "sketch.help.search"
    const val LIST = "sketch.help.list"
    const val EMPTY = "sketch.help.empty"
    const val BODY = "sketch.help.body"
    const val REPLAY = "sketch.help.replay"
    const val BACK = "sketch.help.back"
    const val CLOSE = "sketch.help.close"
    const val TIPS = "sketch.help.tips"
    fun result(id: String) = "sketch.help.result.$id"
    fun topic(id: String) = "sketch.help.topic.$id"
}

private val ROW = 48.dp
private val LIST_MAX = 320.dp
private val DIALOG_MAX = 480.dp

/**
 * Help and search in one dialog: with nothing typed it lists the Help topics (each with its full text and a Replay button when a
 * tour backs it) and the switch for automatic tips; typing ranks tools, actions and topics together. Enter or a tap on a
 * result uses it. [onRun] receives tool and action results; a topic opens inside the dialog.
 */
@Composable
fun HelpSearchDialog(
    tours: TourController?,
    tools: Collection<ToolId>,
    actions: Collection<String>,
    onRun: (SearchAction) -> Unit,
    onDismiss: () -> Unit,
    startTopic: String? = null,
    autoFocus: Boolean = true,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HelpSearchContent(tours, tools, actions, onRun, onDismiss, startTopic, autoFocus)
    }
}

/** The dialog's body without its window, so it can be composed and tested on its own. */
@Composable
internal fun HelpSearchContent(
    tours: TourController?,
    tools: Collection<ToolId>,
    actions: Collection<String>,
    onRun: (SearchAction) -> Unit,
    onDismiss: () -> Unit,
    startTopic: String? = null,
    autoFocus: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var topicId by remember { mutableStateOf(startTopic) }
    val entries = remember(tools, actions) { GuideSearch.entries(tools, actions) }
    val results = remember(query, entries) { GuideSearch.rank(query, entries) }
    val topic = topicId?.let { id -> GuideContent.helpTopics.firstOrNull { it.id == id } }
    val pick: (SearchAction) -> Unit = { action ->
        if (action is SearchAction.OpenTopic) topicId = action.topicId else onRun(action)
    }
    Surface(
        Modifier.fillMaxWidth().widthIn(max = DIALOG_MAX).padding(16.dp).testTag(HelpSearchTags.DIALOG),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (topic != null) topic.title else SketchGuideStrings.Help.TITLE, style = MaterialTheme.typography.headlineSmall)
            if (topic != null) TopicBody(topic, tours, onBack = { topicId = null }, onReplay = { tours?.replay(it); onDismiss() })
            else SearchBody(query, { query = it }, results, tours, autoFocus, { results.firstOrNull()?.let { pick(it.action) } }, pick)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).defaultMinSize(minHeight = ROW).testTag(HelpSearchTags.CLOSE)) { Text(SketchGuideStrings.Help.CLOSE) }
        }
    }
}

@Composable
internal fun SearchBody(
    query: String,
    onQuery: (String) -> Unit,
    results: List<SearchEntry>,
    tours: TourController?,
    autoFocus: Boolean,
    onSubmit: () -> Unit,
    pick: (SearchAction) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(HelpSearchTags.FIELD),
            label = { Text(SketchGuideStrings.Search.FIELD) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
        if (query.isBlank()) TopicList(tours, pick) else ResultList(results, pick)
    }
}

@Composable
private fun ResultList(results: List<SearchEntry>, pick: (SearchAction) -> Unit) {
    if (results.isEmpty()) {
        Text(SketchGuideStrings.Search.NO_MATCH, Modifier.testTag(HelpSearchTags.EMPTY), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(Modifier.heightIn(max = LIST_MAX).verticalScroll(rememberScrollState()).testTag(HelpSearchTags.LIST)) {
        for (entry in results) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = ROW).testTag(HelpSearchTags.result(entry.id)).clickable { pick(entry.action) }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(kindLabel(entry.kind), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun kindLabel(kind: SearchKind): String = when (kind) {
    SearchKind.Tool -> SketchGuideStrings.Search.KIND_TOOL
    SearchKind.Action -> SketchGuideStrings.Search.KIND_ACTION
    SearchKind.Help -> SketchGuideStrings.Search.KIND_HELP
}

@Composable
internal fun TopicList(tours: TourController?, pick: (SearchAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.heightIn(max = LIST_MAX).verticalScroll(rememberScrollState()).testTag(HelpSearchTags.LIST)) {
            for (topic in GuideContent.helpTopics) {
                Text(
                    topic.title,
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = ROW).testTag(HelpSearchTags.topic(topic.id)).clickable { pick(SearchAction.OpenTopic(topic.id)) }.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        if (tours != null) TipsSwitch(tours)
    }
}

@Composable
internal fun TipsSwitch(tours: TourController) {
    var on by remember { mutableStateOf(tours.autoTours) }
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = ROW), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(SketchGuideStrings.Help.TIPS_SWITCH, Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = { on = it; tours.setAutoTours(it) },
            modifier = Modifier.testTag(HelpSearchTags.TIPS).semantics { contentDescription = SketchGuideStrings.Help.TIPS_SWITCH },
        )
    }
}

@Composable
private fun TopicBody(topic: HelpTopic, tours: TourController?, onBack: () -> Unit, onReplay: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(topic.body, Modifier.testTag(HelpSearchTags.BODY), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.defaultMinSize(minHeight = ROW).testTag(HelpSearchTags.BACK)) { Text(SketchGuideStrings.Help.BACK) }
            val tour = topic.tourId
            if (tour != null && tours != null) {
                TextButton(onClick = { onReplay(tour) }, modifier = Modifier.defaultMinSize(minHeight = ROW).testTag(HelpSearchTags.REPLAY)) { Text(SketchGuideStrings.Help.REPLAY) }
            }
        }
    }
}
