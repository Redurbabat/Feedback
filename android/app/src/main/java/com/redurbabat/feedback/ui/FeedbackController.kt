package com.redurbabat.feedback.ui

import android.content.Context
import com.redurbabat.feedback.agent.AgentConnectionState
import com.redurbabat.feedback.agent.BackgroundAgentService
import com.redurbabat.feedback.agent.BackgroundConnectionStore
import com.redurbabat.feedback.agent.DeviceAgentClient
import com.redurbabat.feedback.device.SystemInfoProvider
import com.redurbabat.feedback.network.FeedbackHttpException
import com.redurbabat.feedback.network.OkHttpJsonTransport
import com.redurbabat.feedback.network.ServerEndpoint
import com.redurbabat.feedback.pairing.AndroidDeviceMetadataProvider
import com.redurbabat.feedback.pairing.ServerPairingClient
import com.redurbabat.feedback.pairing.ServerPairingException
import com.redurbabat.feedback.pairing.ServerPairingSession
import com.redurbabat.feedback.pairing.ServerPairingStatus
import com.redurbabat.feedback.permissions.LocalCapabilityStore
import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.security.DeviceIdentity
import com.redurbabat.feedback.security.DeviceIdentityStore
import com.redurbabat.feedback.security.DeviceRegistrationStore
import com.redurbabat.feedback.security.SecretStore
import com.redurbabat.feedback.security.StoredDeviceRegistration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairingUiPhase {
    data object Idle : PairingUiPhase
    data object Starting : PairingUiPhase

    data class Waiting(
        val displayCode: String,
        val expiresAt: String,
    ) : PairingUiPhase

    data object Claiming : PairingUiPhase
    data class Failed(val message: String) : PairingUiPhase
}

data class FeedbackUiState(
    val identity: DeviceIdentity? = null,
    val identityAvailable: Boolean = false,
    val serverUrl: String = "",
    val pairing: PairingUiPhase = PairingUiPhase.Idle,
    val paired: Boolean = false,
    val pairedDeviceName: String? = null,
    val pairedServer: String? = null,
    val pairedPublicDeviceId: String? = null,
    val pairedAt: String? = null,
    val agentState: AgentConnectionState = AgentConnectionState.STOPPED,
    val backgroundConnectionEnabled: Boolean = false,
    val systemInfoGrantedLocally: Boolean = false,
    val globalMessage: String? = null,
)

/**
 * Coordinator for the visible Android app.
 *
 * Pairing secrets and the device token never enter [FeedbackUiState]. The token is persisted only
 * through [DeviceRegistrationStore], which seals the complete registration with Android Keystore.
 * A persistent connection is opt-in and handed to [BackgroundAgentService], which is a visible
 * foreground service with an ongoing notification and user-accessible stop action.
 */
class FeedbackController(context: Context) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val identityStore = DeviceIdentityStore()
    private val secretStore = SecretStore(applicationContext)
    private val registrationStore = DeviceRegistrationStore(secretStore)
    private val localCapabilityStore = LocalCapabilityStore(applicationContext)
    private val backgroundStore = BackgroundConnectionStore(applicationContext)
    private val metadata = AndroidDeviceMetadataProvider.current()
    private val systemInfoProvider = SystemInfoProvider(applicationContext)

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    private var pairingJob: Job? = null
    private var agentStateJob: Job? = null
    private var agent: DeviceAgentClient? = null
    private val closed = AtomicBoolean(false)

    init {
        initialize()
    }

    fun setServerUrl(value: String) {
        if (_state.value.pairing is PairingUiPhase.Starting ||
            _state.value.pairing is PairingUiPhase.Waiting ||
            _state.value.pairing is PairingUiPhase.Claiming
        ) {
            return
        }
        _state.value = _state.value.copy(
            serverUrl = value,
            pairing = if (_state.value.pairing is PairingUiPhase.Failed) {
                PairingUiPhase.Idle
            } else {
                _state.value.pairing
            },
            globalMessage = null,
        )
    }

    fun startPairing() {
        if (closed.get() || !_state.value.identityAvailable || _state.value.paired) {
            return
        }
        pairingJob?.cancel()
        pairingJob = scope.launch {
            val endpoint = try {
                ServerEndpoint.parse(_state.value.serverUrl)
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    pairing = PairingUiPhase.Failed(
                        "Bitte eine gültige HTTPS-Adresse des Feedback-Servers eingeben.",
                    ),
                )
                return@launch
            }

            _state.value = _state.value.copy(
                serverUrl = endpoint.baseUrl,
                pairing = PairingUiPhase.Starting,
                globalMessage = null,
            )

            val client = ServerPairingClient(
                transport = OkHttpJsonTransport(endpoint),
                identityStore = identityStore,
            )

            val session = try {
                client.start(metadata)
            } catch (error: Exception) {
                failPairing(error)
                return@launch
            }

            _state.value = _state.value.copy(
                pairing = PairingUiPhase.Waiting(
                    displayCode = session.displayCode,
                    expiresAt = session.expiresAt,
                ),
            )

            pollUntilFinished(endpoint, client, session)
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        if (!_state.value.paired) {
            _state.value = _state.value.copy(pairing = PairingUiPhase.Idle, globalMessage = null)
        }
    }

    fun setSystemInfoGranted(granted: Boolean) {
        if (!_state.value.paired) {
            return
        }
        runCatching {
            localCapabilityStore.setGranted(Capability.SYSTEM_INFO, granted)
        }.onSuccess {
            _state.value = _state.value.copy(systemInfoGrantedLocally = granted)
            if (_state.value.backgroundConnectionEnabled) {
                BackgroundAgentService.notifyCapabilitiesChanged()
            } else {
                agent?.notifyCapabilitiesChanged()
            }
        }.onFailure {
            _state.value = _state.value.copy(
                globalMessage = "Die lokale Freigabe konnte nicht gespeichert werden.",
            )
        }
    }

    fun setBackgroundConnectionEnabled(enabled: Boolean) {
        if (!_state.value.paired || closed.get()) {
            return
        }
        if (enabled == _state.value.backgroundConnectionEnabled) {
            return
        }

        val stored = runCatching { registrationStore.load() }.getOrNull()
        if (stored == null) {
            _state.value = _state.value.copy(
                paired = false,
                backgroundConnectionEnabled = false,
                globalMessage = "Keine gültige lokale Registrierung gefunden. Bitte erneut koppeln.",
            )
            return
        }

        if (enabled) {
            agentStateJob?.cancel()
            agentStateJob = null
            agent?.stop()
            agent = null
            try {
                BackgroundAgentService.start(applicationContext)
                _state.value = _state.value.copy(
                    backgroundConnectionEnabled = true,
                    agentState = AgentConnectionState.CONNECTING,
                    globalMessage = "Hintergrundverbindung aktiviert. Android zeigt dafür eine dauerhafte Benachrichtigung.",
                )
                observeBackgroundAgent()
            } catch (_: Exception) {
                backgroundStore.setEnabled(false)
                _state.value = _state.value.copy(
                    backgroundConnectionEnabled = false,
                    globalMessage = "Die Hintergrundverbindung konnte von Android nicht gestartet werden.",
                )
                startAgent(stored)
            }
        } else {
            BackgroundAgentService.stop(applicationContext)
            _state.value = _state.value.copy(
                backgroundConnectionEnabled = false,
                globalMessage = "Hintergrundverbindung beendet. Solange die App geöffnet ist, bleibt der Agent verbunden.",
            )
            startAgent(stored)
        }
    }

    fun reportBackgroundNotificationPermissionDenied() {
        _state.value = _state.value.copy(
            backgroundConnectionEnabled = false,
            globalMessage = "Für die sichtbare Hintergrundverbindung sind Benachrichtigungen erforderlich. Die Verbindung wurde nicht aktiviert.",
        )
    }

    fun reconnectAgent() {
        if (!_state.value.paired || closed.get()) {
            return
        }
        if (_state.value.backgroundConnectionEnabled) {
            BackgroundAgentService.reconnect()
        } else {
            agent?.start()
        }
    }

    /**
     * Removes only the local credential. The server-side device record is intentionally left
     * untouched; owners should use the Control Center revoke action when they want global revoke.
     */
    fun forgetLocalRegistration() {
        pairingJob?.cancel()
        pairingJob = null
        agentStateJob?.cancel()
        agentStateJob = null
        BackgroundAgentService.stop(applicationContext)
        backgroundStore.setEnabled(false)
        agent?.stop()
        agent = null
        registrationStore.clear()
        localCapabilityStore.clear()
        _state.value = _state.value.copy(
            pairing = PairingUiPhase.Idle,
            paired = false,
            pairedDeviceName = null,
            pairedServer = null,
            pairedPublicDeviceId = null,
            pairedAt = null,
            agentState = AgentConnectionState.STOPPED,
            backgroundConnectionEnabled = false,
            systemInfoGrantedLocally = false,
            globalMessage = "Lokale Kopplung entfernt. Für einen globalen Widerruf das Control Center verwenden.",
        )
    }

    fun clearMessage() {
        _state.value = _state.value.copy(globalMessage = null)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        pairingJob?.cancel()
        agentStateJob?.cancel()
        agent?.stop()
        agent = null
        scope.cancel()
    }

    private fun initialize() {
        val identity = runCatching { identityStore.loadOrCreate() }.getOrNull()
        val stored = runCatching { registrationStore.load() }.getOrNull()
        val backgroundEnabled = stored != null && backgroundStore.isEnabled()
        _state.value = FeedbackUiState(
            identity = identity,
            identityAvailable = identity != null,
            serverUrl = stored?.endpoint?.baseUrl.orEmpty(),
            paired = stored != null,
            pairedDeviceName = stored?.registration?.name,
            pairedServer = stored?.endpoint?.baseUrl,
            pairedPublicDeviceId = stored?.registration?.publicDeviceId,
            pairedAt = stored?.registration?.pairedAt,
            backgroundConnectionEnabled = backgroundEnabled,
            systemInfoGrantedLocally = localCapabilityStore.granted().contains(Capability.SYSTEM_INFO),
            globalMessage = if (identity == null) {
                "Die Geräteidentität konnte nicht aus dem Android Keystore geladen werden."
            } else {
                null
            },
        )
        if (stored != null) {
            if (backgroundEnabled) {
                try {
                    BackgroundAgentService.start(applicationContext)
                    observeBackgroundAgent()
                } catch (_: Exception) {
                    backgroundStore.setEnabled(false)
                    _state.value = _state.value.copy(
                        backgroundConnectionEnabled = false,
                        globalMessage = "Die gespeicherte Hintergrundverbindung konnte nicht gestartet werden. Der Agent läuft nur solange die App geöffnet ist.",
                    )
                    startAgent(stored)
                }
            } else {
                startAgent(stored)
            }
        }
    }

    private suspend fun pollUntilFinished(
        endpoint: ServerEndpoint,
        client: ServerPairingClient,
        session: ServerPairingSession,
    ) {
        while (true) {
            delay(session.pollIntervalMs)
            val status = try {
                client.status(session)
            } catch (error: Exception) {
                failPairing(error)
                return
            }

            when (status) {
                ServerPairingStatus.PENDING -> Unit
                ServerPairingStatus.APPROVED -> {
                    _state.value = _state.value.copy(pairing = PairingUiPhase.Claiming)
                    val registration = try {
                        client.claim(session)
                    } catch (error: Exception) {
                        failPairing(error)
                        return
                    }
                    try {
                        registrationStore.save(endpoint, registration)
                    } catch (_: Exception) {
                        _state.value = _state.value.copy(
                            pairing = PairingUiPhase.Failed(
                                "Die Kopplung wurde bestätigt, aber das Geräte-Token konnte nicht sicher gespeichert werden.",
                            ),
                        )
                        return
                    }
                    val stored = StoredDeviceRegistration(endpoint, registration)
                    _state.value = _state.value.copy(
                        pairing = PairingUiPhase.Idle,
                        paired = true,
                        pairedDeviceName = registration.name,
                        pairedServer = endpoint.baseUrl,
                        pairedPublicDeviceId = registration.publicDeviceId,
                        pairedAt = registration.pairedAt,
                        backgroundConnectionEnabled = false,
                        globalMessage = "Gerät erfolgreich gekoppelt.",
                    )
                    backgroundStore.setEnabled(false)
                    startAgent(stored)
                    return
                }

                ServerPairingStatus.REJECTED -> {
                    _state.value = _state.value.copy(
                        pairing = PairingUiPhase.Failed("Die Kopplung wurde im Control Center abgelehnt."),
                    )
                    return
                }

                ServerPairingStatus.EXPIRED -> {
                    _state.value = _state.value.copy(
                        pairing = PairingUiPhase.Failed("Der Kopplungscode ist abgelaufen. Bitte erneut starten."),
                    )
                    return
                }

                ServerPairingStatus.CONSUMED -> {
                    _state.value = _state.value.copy(
                        pairing = PairingUiPhase.Failed(
                            "Diese Kopplung wurde bereits verwendet. Bitte einen neuen Code erzeugen.",
                        ),
                    )
                    return
                }
            }
        }
    }

    private fun startAgent(stored: StoredDeviceRegistration) {
        agentStateJob?.cancel()
        agent?.stop()
        val next = DeviceAgentClient(
            storedRegistration = stored,
            metadata = metadata,
            localCapabilities = localCapabilityStore,
            systemInfoProvider = systemInfoProvider,
            registrationStore = registrationStore,
        )
        agent = next
        agentStateJob = scope.launch {
            next.state.collect { connectionState ->
                applyAgentState(connectionState)
            }
        }
        next.start()
    }

    private fun observeBackgroundAgent() {
        agentStateJob?.cancel()
        agentStateJob = scope.launch {
            BackgroundAgentService.state.collect { connectionState ->
                if (closed.get()) {
                    return@collect
                }
                if (
                    connectionState == AgentConnectionState.STOPPED &&
                    _state.value.backgroundConnectionEnabled &&
                    !backgroundStore.isEnabled()
                ) {
                    _state.value = _state.value.copy(backgroundConnectionEnabled = false)
                    val stored = runCatching { registrationStore.load() }.getOrNull()
                    if (stored != null) {
                        startAgent(stored)
                    }
                    return@collect
                }
                applyAgentState(connectionState)
            }
        }
    }

    private fun applyAgentState(connectionState: AgentConnectionState) {
        if (closed.get()) {
            return
        }
        if (connectionState == AgentConnectionState.REVOKED) {
            backgroundStore.setEnabled(false)
            localCapabilityStore.clear()
            _state.value = _state.value.copy(
                paired = false,
                pairedDeviceName = null,
                pairedServer = null,
                pairedPublicDeviceId = null,
                pairedAt = null,
                agentState = connectionState,
                backgroundConnectionEnabled = false,
                systemInfoGrantedLocally = false,
                globalMessage = "Dieses Gerät wurde im Control Center widerrufen.",
            )
        } else {
            _state.value = _state.value.copy(agentState = connectionState)
        }
    }

    private fun failPairing(error: Exception) {
        _state.value = _state.value.copy(
            pairing = PairingUiPhase.Failed(pairingErrorMessage(error)),
        )
    }

    private fun pairingErrorMessage(error: Exception): String = when (error) {
        is FeedbackHttpException -> when (error.protocolCode) {
            "RATE_LIMITED" -> "Zu viele Kopplungsversuche. Bitte kurz warten und erneut versuchen."
            "PAIRING_EXPIRED" -> "Die Kopplung ist abgelaufen. Bitte erneut starten."
            "PAIRING_ALREADY_USED" -> "Diese Kopplung wurde bereits verwendet. Bitte erneut starten."
            "INVALID_MESSAGE" -> "Der Server hat die Geräteanfrage abgelehnt. Server- und App-Version prüfen."
            else -> "Der Feedback-Server hat die Anfrage abgelehnt (${error.statusCode})."
        }

        is ServerPairingException -> "Die sichere Kopplung mit dem Server ist fehlgeschlagen."
        else -> "Der Server ist nicht erreichbar oder die TLS-Verbindung konnte nicht aufgebaut werden."
    }
}
