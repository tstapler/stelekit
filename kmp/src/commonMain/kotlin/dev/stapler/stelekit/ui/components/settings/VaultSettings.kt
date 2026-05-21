package dev.stapler.stelekit.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.vault.VaultError
import kotlinx.coroutines.launch

/**
 * Vault settings panel: shows status, enables encryption (create vault),
 * and manages keyslots (add / remove passphrases, lock).
 *
 * All operations are gated by the caller-supplied callbacks; null means
 * the action is unavailable (e.g. no crypto engine on this platform).
 */
@Composable
fun VaultSettings(
    isParanoidMode: Boolean,
    isVaultUnlocked: Boolean,
    onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)? = null,
    onLockVault: (() -> Unit)? = null,
    onListActiveSlots: (suspend () -> List<Int>)? = null,
) {
    val scope = rememberCoroutineScope()

    // Status section
    SettingsSection("Encryption Status") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isParanoidMode) Icons.Default.Shield else Icons.Default.LockOpen,
                contentDescription = null,
                tint = if (isParanoidMode) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = if (isParanoidMode) "Paranoid mode enabled" else "Encryption disabled",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = when {
                        !isParanoidMode -> "Files are stored as plain markdown."
                        isVaultUnlocked -> "Graph is unlocked. Files are encrypted at rest."
                        else -> "Graph is locked."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    HorizontalDivider()

    // Enable encryption (only shown when not yet encrypted and engine is available)
    if (!isParanoidMode && onCreateVault != null) {
        CreateVaultSection(onCreateVault)
        HorizontalDivider()
    }

    // Keyslot management (only shown when vault is unlocked)
    if (isParanoidMode && isVaultUnlocked) {
        KeyslotManagementSection(
            onAddKeyslot = onAddKeyslot,
            onRemoveKeyslot = onRemoveKeyslot,
            onListActiveSlots = onListActiveSlots,
        )
        HorizontalDivider()

        if (onLockVault != null) {
            SettingsSection("Session") {
                OutlinedButton(
                    onClick = onLockVault,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Lock vault")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Create vault sub-section
// ---------------------------------------------------------------------------

@Composable
private fun CreateVaultSection(
    onCreateVault: suspend (CharArray) -> Either<VaultError, Unit>,
) {
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }

    SettingsSection("Enable Encryption") {
        Text(
            "Paranoid mode encrypts all graph files with ChaCha20-Poly1305 and derives your key with Argon2id. " +
            "A .stele-vault header is created in the graph directory. This cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        AnimatedVisibility(!showForm) {
            Button(onClick = { showForm = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Enable encryption…")
            }
        }

        AnimatedVisibility(showForm) {
            PassphraseFormCard(
                title = "Create vault",
                submitLabel = "Encrypt graph",
                requireConfirm = true,
                onSubmit = { passphrase ->
                    when (val r = onCreateVault(passphrase)) {
                        is Either.Right -> { showForm = false; null }
                        is Either.Left -> r.value.message
                    }
                },
                onCancel = { showForm = false },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Keyslot management sub-section
// ---------------------------------------------------------------------------

@Composable
private fun KeyslotManagementSection(
    onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)?,
    onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)?,
    onListActiveSlots: (suspend () -> List<Int>)?,
) {
    val scope = rememberCoroutineScope()
    var activeSlots by remember { mutableStateOf<List<Int>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf<String?>(null) }
    var showAddForm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (onListActiveSlots != null) {
            loading = true
            activeSlots = onListActiveSlots()
            loading = false
        }
    }

    SettingsSection("Passphrases") {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp))
        } else {
            activeSlots.forEachIndexed { displayIndex, slotIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Passphrase ${displayIndex + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (onRemoveKeyslot != null && activeSlots.size > 1) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    when (val r = onRemoveKeyslot(slotIndex)) {
                                        is Either.Right -> {
                                            activeSlots = activeSlots - slotIndex
                                            removeError = null
                                        }
                                        is Either.Left -> removeError = r.value.message
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove passphrase ${displayIndex + 1}",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            if (removeError != null) {
                Text(
                    removeError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(!showAddForm && onAddKeyslot != null && activeSlots.size < 4) {
                OutlinedButton(
                    onClick = { showAddForm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add passphrase")
                }
            }

            AnimatedVisibility(showAddForm && onAddKeyslot != null) {
                PassphraseFormCard(
                    title = "Add passphrase",
                    submitLabel = "Add",
                    requireConfirm = true,
                    onSubmit = { passphrase ->
                        when (val r = onAddKeyslot!!(passphrase)) {
                            is Either.Right -> {
                                // Refresh slot list after add
                                if (onListActiveSlots != null) {
                                    activeSlots = onListActiveSlots()
                                }
                                showAddForm = false
                                null
                            }
                            is Either.Left -> r.value.message
                        }
                    },
                    onCancel = { showAddForm = false },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared passphrase form card
// ---------------------------------------------------------------------------

@Composable
private fun PassphraseFormCard(
    title: String,
    submitLabel: String,
    requireConfirm: Boolean,
    onSubmit: suspend (CharArray) -> String?,  // returns error string or null on success
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val mismatch = requireConfirm && confirm.isNotEmpty() && passphrase != confirm
    val canSubmit = !busy && passphrase.isNotBlank() && (!requireConfirm || passphrase == confirm)

    fun submit() {
        if (!canSubmit) return
        val chars = passphrase.toCharArray()
        passphrase = ""
        confirm = ""
        error = null
        busy = true
        scope.launch {
            error = onSubmit(chars)
            busy = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it; error = null },
                label = { Text("Passphrase") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                visualTransformation = if (showPassphrase) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassphrase = !showPassphrase }) {
                        Icon(
                            if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                        )
                    }
                },
            )

            if (requireConfirm) {
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm passphrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text("Passphrases do not match") }) else null,
                    visualTransformation = if (showPassphrase) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                )
            }

            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
                Button(
                    onClick = ::submit,
                    enabled = canSubmit,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(submitLabel)
                }
            }
        }
    }
}
