package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.VaultState
import dev.stapler.stelekit.vault.VaultError
import dev.stapler.stelekit.vault.VaultNamespace

/**
 * Full-screen vault unlock dialog shown when a paranoid-mode graph requires a passphrase.
 *
 * "Open alternate graph" is a subtle secondary action below the main form; it does not
 * visually suggest that a hidden volume exists (plausible deniability per NFR-5).
 */
@Composable
fun VaultUnlockScreen(
    graphName: String,
    vaultState: VaultState,
    onUnlock: (passphrase: CharArray, namespace: VaultNamespace) -> Unit,
    modifier: Modifier = Modifier,
) {
    var passphraseText by remember { mutableStateOf("") }
    var showHiddenOption by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val isUnlocking = vaultState == VaultState.Unlocking
    val errorMessage = when (vaultState) {
        is VaultState.Error -> when (vaultState.error) {
            is VaultError.InvalidCredential -> "Incorrect passphrase."
            is VaultError.HeaderTampered -> "Vault header integrity check failed. The vault may have been tampered with."
            is VaultError.CorruptedFile -> "Vault file is corrupted."
            is VaultError.UnsupportedVersion -> "Unsupported vault version."
            else -> vaultState.error.message
        }
        else -> null
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun attemptUnlock(namespace: VaultNamespace) {
        if (passphraseText.isBlank()) return
        val chars = passphraseText.toCharArray()
        passphraseText = ""
        onUnlock(chars, namespace)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 400.dp).padding(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = graphName,
                    style = MaterialTheme.typography.headlineSmall,
                )

                Text(
                    text = "This graph is protected. Enter your passphrase to unlock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = passphraseText,
                    onValueChange = { passphraseText = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { attemptUnlock(VaultNamespace.OUTER) }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    enabled = !isUnlocking,
                    isError = errorMessage != null,
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Button(
                    onClick = { attemptUnlock(VaultNamespace.OUTER) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isUnlocking && passphraseText.isNotBlank(),
                ) {
                    if (isUnlocking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Unlocking...")
                    } else {
                        Text("Unlock")
                    }
                }

                // Subtle secondary action — intentionally low-contrast to preserve deniability
                TextButton(
                    onClick = { showHiddenOption = !showHiddenOption },
                    modifier = Modifier.alpha(0.5f),  // intentionally dim
                ) {
                    Text(
                        text = if (showHiddenOption) "Cancel" else "Advanced",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (showHiddenOption) {
                    TextButton(
                        onClick = { attemptUnlock(VaultNamespace.HIDDEN) },
                        enabled = !isUnlocking && passphraseText.isNotBlank(),
                    ) {
                        Text(
                            text = "Open alternate graph",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

