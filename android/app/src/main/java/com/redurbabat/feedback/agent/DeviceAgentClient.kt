package com.redurbabat.feedback.agent

import com.redurbabat.feedback.device.SystemInfoProvider
import com.redurbabat.feedback.network.ServerEndpoint
import com.redurbabat.feedback.pairing.DeviceMetadata
import com.redurbabat.feedback.permissions.LocalCapabilityStore
import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.protocol.EffectivePermission
import com.redurbabat.feedback.protocol.Envelope
import com.redurbabat.feedback.protocol.EnvelopeResult
import com.redurbabat.feedback.protocol.MessageType
import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.protocol.ProtocolError
import com.redurbabat.feedback.security.DeviceRegistrationStore
import com.redurbabat.feedback.security.StoredDeviceRegistration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-lifetime Android agent connection.
 *
 * This class does not attempt to evade Android background limits. A later lifecycle/service layer
 * may keep it running where Android permits; this transport itself only authenticates, reports
 * presence and answers explicitly permitted protocol requests.
 */
class DeviceAgentClient(
    private val storedRegistration: StoredDeviceRegistration,
    private val metadata: DeviceMetadata,
    private val localCapabilities: LocalCapabilityStore,
    private val systemInfoProvider: SystemInfoProvider,
    private val registrationStore: DeviceRegistrationStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .build(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AgentConnectionState.STOPPED)
    val state: StateFlow<AgentConnectionState> = _state.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var serverGranted: Set<Capability> = emptySet()

    @Volatile
    private var lastAgentActivity: Long = now()

    private var heartbeatJob: Job? = null

    fun start() {
        if (_state.value == AgentConnectionState.CONNECTING ||
            _state.value == AgentConnectionState.ONLINE
        ) {
            return
        }
        _state.value = AgentConnectionState.CONNECTING
        val request = Request.Builder()
            .url(storedRegistration.endpoint.webSocket("/api/v1/agent/ws"))
            .header(
                "Authorization",
                "Bearer ${storedRegistration.registration.deviceToken}",
            )
            .build()
        webSocket = client.newWebSocket(request, Listener())
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.close(1000, "client stopped")
        webSocket = null
        _state.value = AgentConnectionState.STOPPED
        scope.cancel()
    }

    /** Call after the owner changes a local capability in the visible app UI. */
    fun notifyCapabilitiesChanged() {
        val socket = webSocket ?: return
        if (_state.value != AgentConnectionState.ONLINE) {
            return
        }
        sendCapabilityState(socket)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@DeviceAgentClient.webSocket = webSocket
            lastAgentActivity = now()
            sendHello(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val receivedAt = now()
            lastAgentActivity = receivedAt
            when (val parsed = Envelope.parse(text, receivedAt)) {
                is EnvelopeResult.Failure -> {
                    sendError(webSocket, null, parsed.error, "Ungueltige Servernachricht")
                }

                is EnvelopeResult.Success -> handleEnvelope(webSocket, parsed.envelope, receivedAt)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            this@DeviceAgentClient.webSocket = null
            if (_state.value != AgentConnectionState.REVOKED &&
                _state.value != AgentConnectionState.STOPPED
            ) {
                _state.value = AgentConnectionState.OFFLINE
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            this@DeviceAgentClient.webSocket = null
            if (_state.value != AgentConnectionState.REVOKED &&
                _state.value != AgentConnectionState.STOPPED
            ) {
                _state.value = AgentConnectionState.ERROR
            }
        }
    }

    private fun handleEnvelope(socket: WebSocket, envelope: Envelope, receivedAt: Long) {
        when (envelope.type) {
            MessageType.AGENT_HELLO_ACK -> handleHelloAck(socket, envelope)
            MessageType.AGENT_HEARTBEAT_ACK -> Unit
            MessageType.CAPABILITY_UPDATE -> handleCapabilityUpdate(envelope)
            MessageType.SYSTEM_INFO_REQUEST -> handleSystemInfoRequest(socket, envelope, receivedAt)
            MessageType.DEVICE_REVOKED -> handleRevoked(socket)
            MessageType.ERROR -> Unit
            else -> sendError(
                socket,
                envelope.messageId,
                ProtocolError.UNSUPPORTED,
                "Nachrichtentyp wird auf Android nicht angenommen",
            )
        }
    }

    private fun handleHelloAck(socket: WebSocket, envelope: Envelope) {
        if (envelope.sessionId != null) {
            sendError(socket, envelope.messageId, ProtocolError.INVALID_MESSAGE, "hello ack mit sessionId")
            return
        }
        val heartbeatMs = envelope.payload.optLong(
            "heartbeatIntervalMs",
            ProtocolConstants.HEARTBEAT_INTERVAL_MS,
        )
        if (heartbeatMs !in 5_000L..300_000L) {
            sendError(
                socket,
                envelope.messageId,
                ProtocolError.INVALID_MESSAGE,
                "Ungueltiges Heartbeat-Intervall",
            )
            return
        }
        serverGranted = parseCapabilities(envelope.payload.optJSONArray("serverGrantedCapabilities"))
        _state.value = AgentConnectionState.ONLINE
        startHeartbeat(socket, heartbeatMs)
    }

    private fun handleCapabilityUpdate(envelope: Envelope) {
        if (envelope.sessionId != null) {
            return
        }
        serverGranted = parseCapabilities(envelope.payload.optJSONArray("serverGrantedCapabilities"))
    }

    private fun handleSystemInfoRequest(
        socket: WebSocket,
        envelope: Envelope,
        receivedAt: Long,
    ) {
        val sessionError = envelope.sessionRequirementError()
        if (sessionError != null) {
            sendError(socket, envelope.messageId, sessionError, "Remote Session fehlt")
            return
        }
        val permission = EffectivePermission.evaluate(
            capability = Capability.SYSTEM_INFO,
            serverGranted = serverGranted,
            deviceGranted = localCapabilities.granted(),
            osAvailable = setOf(Capability.SYSTEM_INFO),
            sessionAuthorized = setOf(Capability.SYSTEM_INFO),
        )
        val denial = permission.denialReason()
        if (denial != null) {
            sendError(socket, envelope.messageId, denial, "system.info ist nicht freigegeben")
            return
        }

        val snapshot = systemInfoProvider.collect(
            nowEpochMillis = receivedAt,
            lastAgentActivityEpochMillis = lastAgentActivity,
        )
        send(
            socket,
            MessageType.SYSTEM_INFO_RESPONSE,
            envelope.sessionId,
            snapshot.toJson(),
        )
    }

    private fun handleRevoked(socket: WebSocket) {
        registrationStore.clear()
        heartbeatJob?.cancel()
        heartbeatJob = null
        _state.value = AgentConnectionState.REVOKED
        socket.close(4003, "device revoked")
    }

    private fun sendHello(socket: WebSocket) {
        val granted = localCapabilities.granted()
        send(
            socket,
            MessageType.AGENT_HELLO,
            sessionId = null,
            payload = JSONObject()
                .put("appVersion", metadata.appVersion)
                .put("osVersion", metadata.osVersion)
                .put("sdkInt", metadata.sdkInt)
                .put("deviceName", metadata.deviceName)
                .put("grantedCapabilities", JSONArray(Capability.toWireList(granted))),
        )
    }

    private fun sendCapabilityState(socket: WebSocket) {
        send(
            socket,
            MessageType.CAPABILITY_STATE,
            sessionId = null,
            payload = JSONObject().put(
                "grantedCapabilities",
                JSONArray(Capability.toWireList(localCapabilities.granted())),
            ),
        )
    }

    private fun startHeartbeat(socket: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!send(socket, MessageType.AGENT_HEARTBEAT, null, JSONObject())) {
                    _state.value = AgentConnectionState.OFFLINE
                    return@launch
                }
            }
        }
    }

    private fun sendError(
        socket: WebSocket,
        relatesTo: String?,
        error: ProtocolError,
        message: String,
    ) {
        val payload = JSONObject()
            .put("code", error.code)
            .put("message", message)
        if (relatesTo != null) {
            payload.put("relatesTo", relatesTo)
        }
        send(socket, MessageType.ERROR, null, payload)
    }

    private fun send(
        socket: WebSocket,
        type: MessageType,
        sessionId: String?,
        payload: JSONObject,
    ): Boolean {
        val envelope = Envelope.create(
            type = type,
            messageId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestampEpochMillis = now(),
            payload = payload,
        )
        val sent = socket.send(envelope.serialize())
        if (sent) {
            lastAgentActivity = now()
        }
        return sent
    }

    private fun parseCapabilities(array: JSONArray?): Set<Capability> {
        if (array == null) {
            return emptySet()
        }
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.optString(index, "")
            if (value.isNotBlank()) {
                values.add(value)
            }
        }
        return Capability.fromWireList(values).filterTo(LinkedHashSet()) { it.implemented }
    }
}
