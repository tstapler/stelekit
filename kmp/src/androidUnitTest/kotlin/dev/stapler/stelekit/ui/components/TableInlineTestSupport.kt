// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.table.TableClipboard

/** Records what the table copies; stands in for the Compose clipboard adapter. */
internal class RecordingClipboard : TableClipboard {
    val copies = mutableListOf<String>()
    override suspend fun copy(plainTsv: String, html: String?) {
        copies += plainTsv
    }
    override suspend fun paste(): String? = null
}

internal class MapSettings : Settings {
    private val values = HashMap<String, Any>()
    override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
    override fun putString(key: String, value: String) { values[key] = value }
    override fun containsKey(key: String) = key in values
}

/** One inline table on screen with everything the tests observe: commits, raw-edit, sheet, clipboard, parent key presses. */
internal class TableTestHost(private val rule: ComposeContentTestRule) {
    val table = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 3 | red |\n| Pear | 5 | green |"
    var content by mutableStateOf(table)
    val commits = mutableListOf<String>()
    val clipboard = RecordingClipboard()
    var started = 0
    var sheetOpened = 0
    var pageCopyKeys = 0
    var sheetOwner by mutableStateOf<BlockUuid?>(null)
    var blockSelection by mutableStateOf(false)
    var present by mutableStateOf(true)
    private var generation by mutableStateOf(0)
    private var activePreferences by mutableStateOf(TablePreferences(null))
    val uuid = BlockUuid("table-block")

    @Suppress("LongParameterList")
    fun show(
        initial: String = table,
        inline: Boolean = true,
        sheet: Boolean = false,
        undoHost: TableUndoHost? = null,
        preferences: TablePreferences = TablePreferences(null),
    ) {
        content = initial
        activePreferences = preferences
        val prefs = TableDisplayPrefs(inlineEditEnabled = inline, sheetEnabled = sheet)
        rule.setContent {
            CompositionLocalProvider(
                LocalTableClipboard provides clipboard,
                LocalTableUndoHost provides undoHost,
                LocalTableSheetOwner provides sheetOwner,
                LocalTablePreferences provides activePreferences,
            ) {
                androidx.compose.runtime.key(generation) {
                if (present) MaterialTheme {
                    Surface {
                        Box(
                            Modifier.width(360.dp).onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) pageCopyKeys++
                                false
                            },
                        ) {
                            TableBlock(
                                content = content,
                                onStartEditing = { started++ },
                                prefs = prefs,
                                blockUuid = uuid,
                                onCommit = { commits += it },
                                onOpenSheet = { sheetOpened++ },
                                inBlockSelectionMode = blockSelection,
                            )
                        }
                    }
                }
                }
            }
        }
        rule.waitForIdle()
    }

    /** A fresh composition of the same table, as after an app restart: new state, new preferences object, same Settings. */
    fun restart(preferences: TablePreferences, initial: String = table) {
        content = initial
        activePreferences = preferences
        generation++
        rule.waitForIdle()
    }
}
