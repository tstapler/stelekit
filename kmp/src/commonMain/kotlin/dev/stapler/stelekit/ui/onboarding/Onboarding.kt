package dev.stapler.stelekit.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.i18n.t
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.launch

enum class OnboardingStep {
    WELCOME,
    GRAPH_SELECTION,
    KEYMAP_INTRO
}

@Composable
fun Onboarding(
    fileSystem: FileSystem,
    onComplete: () -> Unit,
    onGraphSelected: (String) -> Unit
) {
    var currentStep by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var graphPath by remember { mutableStateOf(fileSystem.getDefaultGraphPath()) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (currentStep) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.GRAPH_SELECTION -> GraphSelectionStep(fileSystem) { path ->
                    graphPath = path
                    onGraphSelected(path)
                    // Auto-advance so the user isn't left on the same screen after picking
                    currentStep = OnboardingStep.KEYMAP_INTRO
                }
                OnboardingStep.KEYMAP_INTRO -> KeymapIntroStep()
            }

            Spacer(modifier = Modifier.height(48.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentStep != OnboardingStep.WELCOME) {
                    TextButton(onClick = {
                        currentStep = OnboardingStep.entries[currentStep.ordinal - 1]
                    }) {
                        Text(t("onboarding.back"))
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Button(onClick = {
                    if (currentStep == OnboardingStep.KEYMAP_INTRO) {
                        onComplete()
                    } else {
                        currentStep = OnboardingStep.entries[currentStep.ordinal + 1]
                    }
                }) {
                    Text(
                        if (currentStep == OnboardingStep.KEYMAP_INTRO) 
                            t("onboarding.finish") 
                        else 
                            t("onboarding.next")
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = t("onboarding.welcome.title"),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = t("onboarding.welcome.desc"),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GraphSelectionStep(
    fileSystem: FileSystem,
    onGraphSelected: (String) -> Unit
) {
    var selectedPath by remember { mutableStateOf(fileSystem.getDefaultGraphPath()) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = t("onboarding.graph.title"),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = t("onboarding.graph.desc"),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            val scope = rememberCoroutineScope()
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = selectedPath,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        val path = fileSystem.pickDirectoryAsync()
                        if (path != null) {
                            selectedPath = path
                            onGraphSelected(path)
                        }
                    }
                }) {
                    Text("Select Graph Directory")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = {
                    // Use the demo graph path relative to the project root
                    val demoPath = "deps/graph-parser/test/resources/exporter-test-graph"
                    selectedPath = demoPath
                    onGraphSelected(demoPath)
                }) {
                    Text("Load Demo Graph")
                }
            }
        }
    }
}

@Composable
private fun KeymapIntroStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = t("onboarding.keymap.title"),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = t("onboarding.keymap.desc"),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        Column(modifier = Modifier.fillMaxWidth()) {
            ShortcutItem("Ctrl + K", "Search or Create")
            ShortcutItem("Ctrl + B", "Toggle Sidebar")
            ShortcutItem("Ctrl + N", "New Page")
        }
    }
}

@Composable
private fun ShortcutItem(keys: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = keys,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
