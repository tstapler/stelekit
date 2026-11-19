// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource

object ArrangeBarTags {
    fun action(id: ArrangeActionId) = "sketch.arrange.${id.name.lowercase()}"
}

/**
 * Group, ungroup and z-order buttons for the selection (Task 3.5.1b): the same commands the keyboard map plans
 * ([ArrangeModel]), each at least a 44 dp target, disabled with its reason in the screen-reader text. [onPlan] gets the
 * command to run and the locked elements it left out, for the `LockedSkipped` toast.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArrangeBar(
    scene: SketchSceneV2,
    selection: List<ElementId>,
    env: KeymapEnv,
    ids: ElementIdSource,
    onPlan: (Command, List<ElementId>) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (action in ArrangeModel.actions(scene, selection, env)) {
            TextButton(
                onClick = {
                    val plan = ArrangeModel.plan(action.id, scene, selection, ids)
                    plan.command?.let { onPlan(it, plan.skippedLocked) }
                },
                enabled = action.enabled,
                modifier = Modifier.heightIn(min = TARGET_DP.dp).widthIn(min = TARGET_DP.dp)
                    .testTag(ArrangeBarTags.action(action.id))
                    .semantics { contentDescription = action.description },
            ) { Text(action.label) }
        }
    }
}

private const val TARGET_DP = 44
