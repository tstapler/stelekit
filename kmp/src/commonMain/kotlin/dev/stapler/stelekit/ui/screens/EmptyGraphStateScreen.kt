package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown when the user has explicitly removed their only graph (via [GraphManager.removeGraph]'s
 * last-real-graph path — see that method's `graphsExplicitlyEmptied` flag) — a lightweight
 * "nothing to see here" prompt, deliberately distinct from [LibrarySetupScreen]'s first-launch
 * copy and from the full multi-step [dev.stapler.stelekit.ui.onboarding.Onboarding] flow (no demo
 * option, no explanation of what SteleKit is — the user has already been through that once).
 */
@Composable
fun EmptyGraphStateScreen(
    onTryDemo: () -> Unit,
    onCreateGraph: (() -> Unit)? = null,
    errorMessage: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.NoteAdd,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "No graphs yet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Create a graph to get started.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            if (onCreateGraph != null) {
                Button(
                    onClick = onCreateGraph,
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) {
                    Text("Create a Graph")
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onTryDemo) {
                    Text("Try the Demo Graph Instead")
                }
            } else {
                // Native directory picker unsupported on this browser/session — no way to create
                // a real graph here, so the demo is the only path out of this screen.
                Text(
                    "This browser can't pick a folder. Try the demo graph instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onTryDemo,
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) {
                    Text("Try Demo Graph")
                }
            }
        }
    }
}
