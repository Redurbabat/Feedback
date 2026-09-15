package com.redurbabat.feedback.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.redurbabat.feedback.agent.AgentConnectionState
import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.files.FileShare
import com.redurbabat.feedback.files.FileShareKind
import com.redurbabat.feedback.files.FileShareStore
import com.redurbabat.feedback.files.FileSharePresentation
import com.redurbabat.feedback.security.AutoLockTimeout
import com.redurbabat.feedback.security.SensitiveAction
import com.redurbabat.feedback.security.DeviceIdentity

@Composable
fun FeedbackApp(
    controller: FeedbackController,
    biometricGateway: BiometricGateway = BiometricGateway.Unavailable,
) {
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

    // The document pickers are Android's own consent surface: this app never enumerates storage,
    // it only receives what the owner handed over there. Both contracts add
    // FLAG_GRANT_PERSISTABLE_URI_PERMISSION so the grant survives a restart and can be taken
    // persistently - and released again when the owner withdraws the share.
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = PersistableOpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            controller.addFileShare(uri, FileShareKind.TREE)
        }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = PersistableOpenDocument(),
    ) { uri ->
        if (uri != null) {
            controller.addFileShare(uri, FileShareKind.FILE)
        }
    }

    // Androids Fotoauswahl braucht KEINE Laufzeitberechtigung. Sie ist genau dafuer gebaut,
    // Zugriff auf das Ausgewaehlte zu geben und auf nichts sonst - eine App, die stattdessen
    // READ_MEDIA_IMAGES anfordert, bekommt die ganze Mediathek.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            FileShareStore.MAX_COLLECTION_ITEMS,
        ),
    ) { uris ->
        if (uris.isNotEmpty()) {
            controller.addMediaShare(uris, Capability.MEDIA_PHOTOS_READ)
        }
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            FileShareStore.MAX_COLLECTION_ITEMS,
        ),
    ) { uris ->
        if (uris.isNotEmpty()) {
            controller.addMediaShare(uris, Capability.MEDIA_VIDEOS_READ)
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
            when {
                // Fail closed: a configured lock always wins over any other screen.
                state.appLockConfigured && !state.appUnlocked -> AppLockUnlockScreen(
                    state = state,
                    onUnlock = controller::unlockApp,
                    onBiometricUnlock = {
                        biometricGateway.prompt(
                            onSuccess = controller::unlockWithBiometrics,
                            onFailure = controller::reportBiometricUnlockFailed,
                        )
                    },
                )

                !state.appLockConfigured && !state.appLockSetupDeferred -> AppLockSetupScreen(
                    state = state,
                    onConfigure = controller::configureAppLock,
                    onDefer = controller::deferAppLockSetup,
                )

                else -> HomeScreen(
                    state = state,
                    onServerUrlChanged = controller::setServerUrl,
                    onStartPairing = controller::startPairing,
                    onCancelPairing = controller::cancelPairing,
                    onCapabilityChanged = controller::setCapabilityGranted,
                    onPickPhotos = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickVideos = {
                        videoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                    onPickFolderShare = { folderPickerLauncher.launch(null) },
                    onPickFileShare = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onRemoveFileShare = controller::removeFileShare,
                    onForgetUnavailableFileShares = controller::forgetUnavailableFileShares,
                    onBackgroundConnectionChanged = onBackgroundConnectionChanged,
                    onSetupLinksIgnoredChanged = controller::setSetupLinksIgnored,
                    onReconnect = controller::reconnectAgent,
                    onForgetLocalRegistration = controller::forgetLocalRegistration,
                    onClearMessage = controller::clearMessage,
                    onLockNow = controller::lockApp,
                    onStartAppLockSetup = controller::startAppLockSetup,
                    onAutoLockTimeoutChanged = controller::setAutoLockTimeout,
                    onBiometricUnlockChanged = controller::setBiometricUnlockEnabled,
                    onDisableAppLock = {
                        controller.requestSensitiveAction(SensitiveAction.DISABLE_APP_LOCK)
                    },
                )
            }
        }

        state.pendingSensitiveAction?.let { action ->
            SensitiveActionDialog(
                action = action,
                state = state,
                onConfirm = controller::confirmSensitiveAction,
                onDismiss = controller::cancelSensitiveAction,
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
    onCapabilityChanged: (Capability, Boolean) -> Unit,
    onPickPhotos: () -> Unit,
    onPickVideos: () -> Unit,
    onPickFolderShare: () -> Unit,
    onPickFileShare: () -> Unit,
    onRemoveFileShare: (String) -> Unit,
    onForgetUnavailableFileShares: () -> Unit,
    onBackgroundConnectionChanged: (Boolean) -> Unit,
    onSetupLinksIgnoredChanged: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onForgetLocalRegistration: () -> Unit,
    onClearMessage: () -> Unit,
    onLockNow: () -> Unit,
    onStartAppLockSetup: () -> Unit,
    onAutoLockTimeoutChanged: (AutoLockTimeout) -> Unit,
    onBiometricUnlockChanged: (Boolean) -> Unit,
    onDisableAppLock: () -> Unit,
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
                state = state,
                onCapabilityChanged = onCapabilityChanged,
            )
            FileShareCard(
                shares = state.fileShares,
                unavailableCount = state.fileSharesUnavailable,
                busy = state.fileShareBusy,
                filesReadGranted = state.filesReadGrantedLocally,
                onPickFolderShare = onPickFolderShare,
                onPickFileShare = onPickFileShare,
                onPickPhotos = onPickPhotos,
                onPickVideos = onPickVideos,
                onRemoveFileShare = onRemoveFileShare,
                onForgetUnavailableFileShares = onForgetUnavailableFileShares,
            )
            AppLockCard(
                state = state,
                onLockNow = onLockNow,
                onStartAppLockSetup = onStartAppLockSetup,
                onAutoLockTimeoutChanged = onAutoLockTimeoutChanged,
                onBiometricUnlockChanged = onBiometricUnlockChanged,
                onDisableAppLock = onDisableAppLock,
            )
            SetupLinkCard(
                ignored = state.setupLinksIgnored,
                onIgnoredChanged = onSetupLinksIgnoredChanged,
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
            AppLockCard(
                state = state,
                onLockNow = onLockNow,
                onStartAppLockSetup = onStartAppLockSetup,
                onAutoLockTimeoutChanged = onAutoLockTimeoutChanged,
                onBiometricUnlockChanged = onBiometricUnlockChanged,
                onDisableAppLock = onDisableAppLock,
            )
            SetupLinkCard(
                ignored = state.setupLinksIgnored,
                onIgnoredChanged = onSetupLinksIgnoredChanged,
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

            // The name, not just a filled-in field: the owner should be able to read which server
            // this build belongs to and compare it with what they expect.
            state.buildServerAuthority?.let { authority ->
                Text(
                    text = "Diese App ist für $authority gebaut. Ein Einrichtungslink kann nur " +
                        "diese Adresse bestätigen, keine andere einführen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.setupLinkNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.setupLinkRejected) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

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
                    // "Scannen" stood here and was not true: there is no reader for this code
                    // anywhere - not in this app and not in the control center. The QR carries the
                    // high-entropy ticket the protocol describes (5.2) and stays for the day a
                    // reader exists, but the line that tells the owner what to do now names the
                    // only thing that works.
                    Text(
                        text = "Code im Control Center eingeben",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    QrCodeImage(
                        payload = pairing.qrPayload,
                        contentDescription = "QR-Code mit dem Kopplungs-Ticket. Es gibt dafür " +
                            "noch keinen Leser; für die Kopplung den sechsstelligen Code " +
                            "eingeben, der darunter steht.",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(
                        text = pairing.displayCode.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Öffne das Feedback Control Center, melde dich an und bestätige genau dieses Gerät. QR-Code und Zahlencode sind kurzlebig und ersetzen nicht den kryptografischen Gerätenachweis. Der QR-Code enthält weder einen privaten Schlüssel noch ein Geräte-Token.",
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

/**
 * The revocation path for setup links. Off means a tapped link is refused before it is even looked
 * at; the address is then typed, exactly as before this feature existed.
 */
@Composable
private fun SetupLinkCard(
    ignored: Boolean,
    onIgnoredChanged: (Boolean) -> Unit,
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
                    text = "Einrichtungslinks ignorieren",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (ignored) {
                        "An. Getippte Einrichtungslinks werden abgelehnt. Die Serveradresse gibst du selbst ein."
                    } else {
                        "Aus. Ein Einrichtungslink darf die Serveradresse vorschlagen - aber nur, wenn sie mit der übereinstimmt, für die diese App gebaut ist oder mit der sie bereits gekoppelt ist. Eine Kopplung startet ein Link nie."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = ignored,
                onCheckedChange = onIgnoredChanged,
            )
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

/**
 * Document pickers that ask for a persistable grant.
 *
 * The AndroidX contracts request read access for the current process only. Feedback needs the
 * grant to survive a restart, because a shared area is meant to stay shared until the owner
 * withdraws it - and a grant that cannot be taken persistently also cannot be released again.
 */
private class PersistableOpenDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(PERSISTABLE_READ_FLAGS)
}

private class PersistableOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(PERSISTABLE_READ_FLAGS)
}

private const val PERSISTABLE_READ_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

@Composable
private fun CapabilityCard(
    state: FeedbackUiState,
    onCapabilityChanged: (Capability, Boolean) -> Unit,
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
            CapabilityRow(
                title = "Systeminformationen",
                description = "Modell, Android-Version, Akku, Speicher und Netzwerktyp. Keine IMEI, MAC-Adresse oder Telefonnummer.",
                checked = state.systemInfoGrantedLocally,
                onCheckedChange = { onCapabilityChanged(Capability.SYSTEM_INFO, it) },
            )
            HorizontalDivider()
            CapabilityRow(
                title = "Dateizugriff",
                description = "Nur lesen, und nur in den Ordnern und Dateien, die du unten freigibst. " +
                    FileSharePresentation.shareSummary(
                        state.fileShares.count { it.capability == Capability.FILES_READ },
                    ),
                checked = state.filesReadGrantedLocally,
                onCheckedChange = { onCapabilityChanged(Capability.FILES_READ, it) },
            )
            HorizontalDivider()
            CapabilityRow(
                title = "Fotos",
                description = "Nur die Bilder, die du unten über Androids Fotoauswahl auswählst. Videos bleiben davon unberührt.",
                checked = state.mediaPhotosGrantedLocally,
                onCheckedChange = { onCapabilityChanged(Capability.MEDIA_PHOTOS_READ, it) },
            )
            HorizontalDivider()
            CapabilityRow(
                title = "Videos",
                description = "Nur die Videos, die du unten über Androids Fotoauswahl auswählst. Fotos bleiben davon unberührt.",
                checked = state.mediaVideosGrantedLocally,
                onCheckedChange = { onCapabilityChanged(Capability.MEDIA_VIDEOS_READ, it) },
            )
            HorizontalDivider()
            CapabilityRow(
                title = "Bildschirm zeigen",
                description = "Erlaubt dem Control Center, um eine Übertragung zu bitten. Jede " +
                    "einzelne Übertragung fragt danach noch einmal hier und dann bei Android " +
                    "nach - dieser Schalter startet nichts. Sichtbar wäre dann alles, was die " +
                    "Anzeige zeigt. Kein Ton, keine Aufnahme, keine Fernsteuerung.",
                checked = state.screenViewGrantedLocally,
                onCheckedChange = { onCapabilityChanged(Capability.SCREEN_VIEW, it) },
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * The areas the owner handed over, and the only place to add or withdraw one.
 *
 * Withdrawing is never gated: taking access away is always allowed to be the quick path.
 */
@Composable
private fun FileShareCard(
    shares: List<FileShare>,
    unavailableCount: Int,
    busy: Boolean,
    filesReadGranted: Boolean,
    onPickFolderShare: () -> Unit,
    onPickFileShare: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickVideos: () -> Unit,
    onRemoveFileShare: (String) -> Unit,
    onForgetUnavailableFileShares: () -> Unit,
) {
    val now = System.currentTimeMillis()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Freigegebene Bereiche",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Feedback sieht ausschließlich, was du hier über Androids eigene Auswahldialoge übergibst. Es fordert weder pauschalen Speicherzugriff noch eine Medienberechtigung an und kann nichts ändern, löschen oder öffnen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!filesReadGranted && shares.isNotEmpty()) {
                Text(
                    text = "Der Schalter „Dateizugriff“ ist aus. Diese Bereiche bleiben gespeichert, sind aber gerade nicht abrufbar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FileSharePresentation.unavailableNotice(unavailableCount)?.let { notice ->
                HorizontalDivider()
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = onForgetUnavailableFileShares,
                    enabled = !busy,
                ) {
                    Text("Nicht erreichbare Einträge entfernen")
                }
            }

            HorizontalDivider()

            if (shares.isEmpty()) {
                Text(
                    text = "Noch nichts freigegeben.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                for (share in shares) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(share.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = FileSharePresentation.shareLine(share, now),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { onRemoveFileShare(share.shareId) },
                            enabled = !busy,
                        ) {
                            Text("Entfernen")
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onPickFolderShare,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Ordner freigeben")
                }
                OutlinedButton(
                    onClick = onPickFileShare,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Datei freigeben")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Two separate pickers, because the two capabilities are separate: a photo
                // selection never becomes a way to read videos.
                OutlinedButton(
                    onClick = onPickPhotos,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Fotos auswählen")
                }
                OutlinedButton(
                    onClick = onPickVideos,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Videos auswählen")
                }
            }
        }
    }
}

@Composable
private fun AppLockCard(
    state: FeedbackUiState,
    onLockNow: () -> Unit,
    onStartAppLockSetup: () -> Unit,
    onAutoLockTimeoutChanged: (AutoLockTimeout) -> Unit,
    onBiometricUnlockChanged: (Boolean) -> Unit,
    onDisableAppLock: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.appLockConfigured) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "App-Schutz",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (state.appLockConfigured) {
                Text(
                    text = "PIN oder Passphrase schützt diese Oberfläche. Der Verifier liegt " +
                        "Keystore-versiegelt auf dem Gerät; die Eingabe selbst wird nie " +
                        "gespeichert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AutoLockSelector(
                    selected = state.autoLockTimeout,
                    onSelected = onAutoLockTimeoutChanged,
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Biometrie",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (state.biometricUnlockAvailable) {
                                "Komfortabler Entsperrweg. PIN oder Passphrase bleibt notwendig."
                            } else {
                                "Auf diesem Gerät nicht eingerichtet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.biometricUnlockEnabled,
                        onCheckedChange = onBiometricUnlockChanged,
                        enabled = state.biometricUnlockAvailable,
                    )
                }

                HorizontalDivider()

                OutlinedButton(onClick = onLockNow, modifier = Modifier.fillMaxWidth()) {
                    Text("Jetzt sperren")
                }
                TextButton(onClick = onDisableAppLock, modifier = Modifier.fillMaxWidth()) {
                    Text("App-Schutz deaktivieren")
                }
            } else {
                Text(
                    text = "Kein lokaler App-Schutz aktiv. Jede Person mit Zugriff auf das " +
                        "entsperrte Telefon kann diese Geräteverwaltung öffnen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onStartAppLockSetup, modifier = Modifier.fillMaxWidth()) {
                    Text("App-Schutz einrichten")
                }
            }
        }
    }
}

/**
 * Auto-lock picker. Uses a plain anchored [DropdownMenu] rather than `ExposedDropdownMenuBox`,
 * whose anchor API has changed repeatedly between Material3 releases.
 */
@Composable
private fun AutoLockSelector(
    selected: AutoLockTimeout,
    onSelected: (AutoLockTimeout) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Automatisch sperren nach",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(selected.label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AutoLockTimeout.entries.forEach { timeout ->
                    DropdownMenuItem(
                        text = { Text(timeout.label) },
                        onClick = {
                            expanded = false
                            onSelected(timeout)
                        },
                    )
                }
            }
        }
        if (selected == AutoLockTimeout.NEVER) {
            Text(
                text = "Nie hält die Oberfläche bis zum Beenden der App entsperrt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
