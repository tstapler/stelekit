// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun CalibrationChangeWarningDialog(
    existingAnnotationCount: Int,
    onKeepCurrentScale: () -> Unit,
    onChangeScale: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepCurrentScale,
        title = {
            Text(text = "Changing scale will update all measurements")
        },
        text = {
            Text(
                text = "You have $existingAnnotationCount measurement${if (existingAnnotationCount == 1) "" else "s"} using the current scale. " +
                    "Changing the scale will recalculate all of them.\n\n" +
                    "The original annotations are preserved — only the numeric values change.",
            )
        },
        dismissButton = {
            TextButton(onClick = onKeepCurrentScale) {
                Text("Keep current scale")
            }
        },
        confirmButton = {
            TextButton(onClick = onChangeScale) {
                Text("Change scale")
            }
        },
    )
}
