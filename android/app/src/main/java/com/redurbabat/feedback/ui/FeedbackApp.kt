package com.redurbabat.feedback.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.redurbabat.feedback.agent.AgentConnectionState
import com.redurbabat.feedback.security.DeviceIdentity

@Composable
fun FeedbackApp(controller: FeedbackController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            controller.setBackgroundConnectionEnabled(true)
        } else {
            controller.reportBackgroundNotificationPermissionDenied()
        }
    }

    val onBackgroundConnectionChanged: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            controller.setBackgroundConnectionEnabled(false)
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            controller.setBackgroundConnectionEnabled(true)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            HomeScreen(
                state = state,
                onServerUrlChanged = controller::setServerUrl,
                onStartPairing = controller::startPairing,
                onCancelPairing = controller::cancelPairing,
                onSystemInfoChanged = controller::setSystemInfoGranted,
                onBackgroundConnectionChanged = onBackgroundConnectionChanged,
                onReconnect = controller::reconnectAgent,
                onForgetLocalRegistration = controller::forgetLocalRegistration,
                onClearMessage = controller::clearMessage,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    state: FeedbackUiState,
    onServerUrlChanged: (String) -> Unit,
    onStartPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onSystemInfoChanged: (Boolean) -> Unit,
    onBackgroundConnectionChanged: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onForgetLocalRegistration: () -> Unit,
    onClearMessage: () -> Unit,
) {
    val busy = state.pairing is PairingUiPhase.Starting ||
        state.pairing is PairingUiPhase.Waiting ||
        state.pairing is PairingUiPhase.Claiming

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Feedback",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (state.paired) {
                "Dieses Gerät ist sicher gekoppelt. Remote-Funktionen bleiben lokal und serverseitig getrennt freigabepflichtig."
            } else {
                "Kopple dieses Android-Gerät mit deinem Feedback Control Center. Der private Geräteschlüssel bleibt im Android Keystore."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.globalMessage?.let { message ->
            MessageCard(message = message, onDismiss = onClearMessage)
        }

        IdentityCard(
            identity = state.identity,
            identityAvailable = state.identityAvailable,
            paired = state.paired,
            pairedDeviceName = state.pairedDeviceName,
        )

        if (state.paired) {
            PairedConnectionCard(state = state, onReconnect = onReconnect)
            BackgroundConnectionCard(
                enabled = state.backgroundConnectionEnabled,
                onEnabledChanged = onBackgroundConnectionChanged,
            )
            CapabilityCard(
                systemInfoGranted = state.systemInfoGrantedLocally,
                onSystemInfoChanged = onSystemInfoChanged,
            )
            SecurityCard()
            LocalRemovalCard(onForgetLocalRegistration = onForgetLocalRegistration)
        } else {
            PairingCard(
                state = state,
                busy = busy,
                onServerUrlChanged = onServerUrlChanged,
                onStartPairing = onStartPairing,
                onCancelPairing = onCancelPairing,
            )
            SecurityCard()
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun IdentityCard(
    identity: DeviceIdentity?,
    identityAvailable: Boolean,
    paired: Boolean,
    pairedDeviceName: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = "Dieses Gerät",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = pairedDeviceName ?: if (paired) "Gekoppelt" else "Noch nicht gekoppelt",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            if (identity != null) {
                IdentityValue("Device-ID", identity.deviceId)
                IdentityValue("Fingerprint", identity.fingerprint)
            } else {
                Text(
                    text = "Die lokale Geräteidentität konnte nicht initialisiert werden. Pairing bleibt deaktiviert.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = if (identityAvailable) {
                    "EC-P-256 Geräteschlüssel bereit · Android Keystore"
                } else {
                    "Geräteschlüssel nicht verfügbar"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PairingCard(
    state: FeedbackUiState,
    busy: Boolean,
    onServerUrlChanged: (String) -> Unit,
    onStartPairing: () -> Unit,
    onCancelPairing: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Mit Control Center koppeln",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChanged,
                label = { Text("Server-Adresse") },
                placeholder = { Text("https://feedback.example.com") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("Nur HTTPS. Zugangsdaten in der URL werden nicht akzeptiert.")
                },
            )

            when (val pairing = state.pairing) {
                PairingUiPhase.Idle -> {
                    Button(
                        onClick = onStartPairing,
                        enabled = state.identityAvailable && state.serverUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Kopplung starten")
                    }
                }

                PairingUiPhase.Starting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Sichere Kopplung wird auf dem Server vorbereitet …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCancelPairing, modifier = Modifier.fillMaxWidth()) {
                        Text("Abbrechen")
                    }
                }

                is PairingUiPhase.Waiting -> {
                    Text(
                        text = "Code im Control Center eingeben",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = pairing.displayCode.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Öffne das Feedback Control Center, melde dich an und bestätige genau dieses Gerät. Der Code ist kurzlebig und ersetzt nicht den kryptografischen Gerätenachweis.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IdentityValue("Gültig bis", pairing.expiresAt)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancelPairing, modifier = Modifier.fillMaxWidth()) {
                        Text("Abbrechen")
                    }
                }

                PairingUiPhase.Claiming -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Bestätigung erhalten. Geräte-Token wird sicher übernommen …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is PairingUiPhase.Failed -> {
                    Text(
                        text = pairing.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = onStartPairing,
                        enabled = state.identityAvailable && state.serverUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Erneut versuchen")
                    }
                }
            }
        }
    }
}

@Composable
private fun PairedConnectionCard(
    state: FeedbackUiState,
    onReconnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Verbindung",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = agentStateLabel(state.agentState),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.agentState == AgentConnectionState.ONLINE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            state.pairedServer?.let { IdentityValue("Server", it) }
            state.pairedPublicDeviceId?.let { IdentityValue("Device-ID", it) }
            state.pairedAt?.let { IdentityValue("Gekoppelt seit", it) }

            if (state.agentState == AgentConnectionState.ERROR ||
                state.agentState == AgentConnectionState.OFFLINE
            ) {
                OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Erneut verbinden")
                }
            }
        }
    }
}

@Composable
private fun BackgroundConnectionCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "Hintergrundverbindung",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (enabled) {
                        "Aktiv. Feedback bleibt über einen sichtbaren Android-Vordergrunddienst verbunden. Die dauerhafte Benachrichtigung enthält eine Beenden-Aktion."
                    } else {
                        "Aus. Der Agent ist nur erreichbar, solange die App geöffnet ist. Aktivieren ist freiwillig und immer sichtbar."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
        }
    }
}

@Composable
private fun CapabilityCard(
    systemInfoGranted: Boolean,
    onSystemInfoChanged: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Lokale Freigaben",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Eine Funktion funktioniert nur, wenn sie sowohl hier auf dem Gerät als auch im Control Center freigegeben ist.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("Systeminformationen", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Modell, Android-Version, Akku, Speicher und Netzwerktyp. Keine IMEI, MAC-Adresse oder Telefonnummer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = systemInfoGranted,
                    onCheckedChange = onSystemInfoChanged,
                )
            }
        }
    }
}

@Composable
private fun SecurityCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Sicherheit",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SecurityRow("Privater Geräteschlüssel", "Android Keystore")
            SecurityRow("Serververkehr", "HTTPS / WSS")
            SecurityRow("Remote-Freigaben", "Deny-by-default")
            Text(
                text = "Aktive Bildschirm- oder Fernsteuerung wird später ausschließlich über Androids offizielle Berechtigungswege und sichtbare Sitzungsanzeigen umgesetzt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocalRemovalCard(onForgetLocalRegistration: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Lokale Kopplung",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Hier wird nur das lokale Geräte-Token entfernt. Für einen vollständigen Widerruf des Geräts verwende das Control Center.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onForgetLocalRegistration) {
                Text("Lokale Kopplung entfernen")
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
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

private fun agentStateLabel(state: AgentConnectionState): String = when (state) {
    AgentConnectionState.STOPPED -> "Gestoppt"
    AgentConnectionState.CONNECTING -> "Verbindet …"
    AgentConnectionState.ONLINE -> "Online"
    AgentConnectionState.OFFLINE -> "Offline"
    AgentConnectionState.REVOKED -> "Widerrufen"
    AgentConnectionState.ERROR -> "Fehler"
}
