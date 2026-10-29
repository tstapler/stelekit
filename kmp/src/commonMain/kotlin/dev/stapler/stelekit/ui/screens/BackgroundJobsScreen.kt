// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.jobs.BackgroundJobRegistry
import dev.stapler.stelekit.jobs.JobState
import dev.stapler.stelekit.jobs.JobStatus
import dev.stapler.stelekit.ui.components.typedItems

/** Lists downloads, clones and other background work, including ones a previous app run started. */
@Composable
fun BackgroundJobsScreen(registry: BackgroundJobRegistry, modifier: Modifier = Modifier) {
    val jobs by registry.jobs.collectAsState(initial = emptyList())
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Background jobs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (jobs.any { !it.isActive }) {
                TextButton(onClick = registry::clearFinished) { Text("Clear finished") }
            }
        }
        if (jobs.isEmpty()) {
            Text(
                "Nothing running. Downloads and clones appear here while they work and after they finish.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                typedItems(jobs, key = { it.id }) { job -> JobRow(job, onCancel = { registry.cancel(job.id) }) }
            }
        }
    }
}

@Composable
private fun JobRow(job: JobStatus, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(job.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${job.kind.label} · ${describe(job.state)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (job.state is JobState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val state = job.state) {
                is JobState.Running -> state.progressPercent
                    ?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is JobState.Queued -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
            if (job.isActive && job.cancellable) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Plain-language state line for a job row. */
internal fun describe(state: JobState): String = when (state) {
    JobState.Queued -> "Waiting to start"
    is JobState.Running -> state.progressPercent?.let { "Running, $it%" } ?: "Running"
    JobState.Succeeded -> "Finished"
    is JobState.Failed -> state.reason?.let { "Failed: $it" } ?: "Failed"
    JobState.Cancelled -> "Cancelled"
}
