package com.redurbabat.feedback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.redurbabat.feedback.pairing.PairingInvitation
import com.redurbabat.feedback.security.DeviceIdentity

@Composable
fun FeedbackApp(
    identity: DeviceIdentity?,
    identityAvailable: Boolean,
    onCreatePairingInvitation: () -> PairingInvitation,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            HomeScreen(
                identity = identity,
                identityAvailable = identityAvailable,
                onCreatePairingInvitation = onCreatePairingInvitation,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    identity: DeviceIdentity?,
    identityAvailable: Boolean,
    onCreatePairingInvitation: () -> PairingInvitation,
) {
    var pairingInvitation by remember(identity?.deviceId) {
        mutableStateOf<PairingInvitation?>(null)
    }
    var pairingError by remember(identity?.deviceId) {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Feedback",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Dieses Gerät besitzt eine eigene kryptografische Identität und ist noch nicht gekoppelt.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DeviceCard(identity = identity)

        Button(
            onClick = {
                runCatching(onCreatePairingInvitation)
                    .onSuccess {
                        pairingInvitation = it
                        pairingError = false
                    }
                    .onFailure {
                        pairingError = true
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = identityAvailable,
        ) {
            Text(
                if (pairingInvitation == null) {
                    "Pairing-Code erzeugen"
                } else {
                    "Neuen Pairing-Code erzeugen"
                },
            )
        }

        pairingInvitation?.let { invitation ->
            PairingCard(invitation)
        }

        if (pairingError) {
            Text(
                text = "Der Pairing-Code konnte nicht signiert werden.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Sicherheit",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        SecurityRow(
            label = "Geräteschlüssel",
            value = if (identityAvailable) "Bereit · Android Keystore" else "Fehler",
        )
        SecurityRow("Remote-Zugriff", "Aus")
        SecurityRow("Aktive Sitzung", "Keine")
    }
}

@Composable
private fun DeviceCard(identity: DeviceIdentity?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Dieses Gerät",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Nicht gekoppelt",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            if (identity != null) {
                IdentityValue("Device-ID", identity.deviceId)
                IdentityValue("Fingerprint", identity.fingerprint)
            } else {
                Text(
                    text = "Die lokale Geräteidentität konnte nicht initialisiert werden. Pairing bleibt deshalb deaktiviert.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = "Der private Schlüssel verlässt den Android Keystore nicht. Beim Pairing wird nur der öffentliche Schlüssel verwendet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PairingCard(invitation: PairingInvitation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Pairing-Code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = invitation.code.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Der signierte Pairing-Nachweis läuft lokal nach 5 Minuten ab. Die Serverregistrierung folgt in Phase 2.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IdentityValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SecurityRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
