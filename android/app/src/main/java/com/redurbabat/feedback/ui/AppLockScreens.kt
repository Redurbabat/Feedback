package com.redurbabat.feedback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.redurbabat.feedback.security.AppLockPolicy
import com.redurbabat.feedback.security.SensitiveAction

/**
 * Screens that stand in front of the management UI.
 *
 * They deliberately show nothing about the device beyond the app name: no device id, no
 * fingerprint, no pairing or connection state. Whatever is visible here is visible to anyone
 * holding the unlocked phone.
 */

@Composable
fun AppLockSetupScreen(
    state: FeedbackUiState,
    onConfigure: (String, String) -> Unit,
    onDefer: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    LockScaffold(title = "App schützen") {
        Text(
            text = "Schütze die Geräteverwaltung mit einer lokalen PIN oder Passphrase. " +
                "Sie bleibt auf diesem Gerät und ersetzt weder den Geräteschlüssel noch eine " +
                "Freigabe im Control Center.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SecretField(
            value = secret,
            onValueChange = { secret = it },
            label = "PIN oder Passphrase",
            enabled = !state.appLockBusy,
        )
        SecretField(
            value = confirmation,
            onValueChange = { confirmation = it },
            label = "Wiederholen",
            enabled = !state.appLockBusy,
        )

        Text(
            text = "Mindestens ${AppLockPolicy.MIN_PIN_DIGITS} Ziffern, oder eine Passphrase mit " +
                "mindestens ${AppLockPolicy.MIN_PASSPHRASE_LENGTH} Zeichen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.appLockError?.let { ErrorText(it) }

        if (state.appLockBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Button(
            onClick = { onConfigure(secret, confirmation) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.appLockBusy && secret.isNotEmpty() && confirmation.isNotEmpty(),
        ) {
            Text("Schutz aktivieren")
        }

        TextButton(
            onClick = onDefer,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.appLockBusy,
        ) {
            Text("Später")
        }

        Text(
            text = "Ohne App-Schutz kann jede Person mit Zugriff auf das entsperrte Telefon die " +
                "Geräteverwaltung öffnen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AppLockUnlockScreen(
    state: FeedbackUiState,
    onUnlock: (String) -> Unit,
    onBiometricUnlock: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    val lockedOut = state.appLockRemainingLockoutMillis > 0L
    val inputEnabled = !state.appLockBusy && !lockedOut

    LockScaffold(title = "App gesperrt") {
        if (state.appLockUnavailable) {
            ErrorText(
                "Die vorhandene App-Sperre konnte nicht sicher geladen werden. " +
                    "Die Oberfläche bleibt gesperrt.",
            )
        }

        SecretField(
            value = secret,
            onValueChange = { secret = it },
            label = "PIN oder Passphrase",
            enabled = inputEnabled,
        )

        if (lockedOut) {
            ErrorText(
                "Zu viele Fehlversuche. Erneut versuchen in " +
                    "${formatLockoutCountdown(state.appLockRemainingLockoutMillis)}.",
            )
        } else {
            state.appLockError?.let { ErrorText(it) }
        }

        if (state.appLockBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Button(
            onClick = {
                onUnlock(secret)
                secret = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = inputEnabled && secret.isNotEmpty(),
        ) {
            Text("Entsperren")
        }

        if (state.biometricUnlockEnabled && state.biometricUnlockAvailable) {
            TextButton(
                onClick = onBiometricUnlock,
                modifier = Modifier.fillMaxWidth(),
                enabled = inputEnabled,
            ) {
                Text("Mit Biometrie entsperren")
            }
        }
    }
}

/**
 * Asks for the secret again before an action that weakens protection or hands out a privilege.
 * Rendered over the management UI, which stays unlocked underneath.
 */
@Composable
fun SensitiveActionDialog(
    action: SensitiveAction,
    state: FeedbackUiState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var secret by remember(action) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!state.sensitiveActionBusy) onDismiss() },
        title = { Text(action.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = action.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecretField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = "PIN oder Passphrase",
                    enabled = !state.sensitiveActionBusy,
                )
                state.sensitiveActionError?.let { ErrorText(it) }
                if (state.sensitiveActionBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(secret) },
                enabled = !state.sensitiveActionBusy && secret.isNotEmpty(),
            ) {
                Text(action.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.sensitiveActionBusy) {
                Text("Abbrechen")
            }
        },
    )
}

@Composable
private fun LockScaffold(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Feedback",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        content()
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= AppLockPolicy.MAX_SECRET_LENGTH) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
