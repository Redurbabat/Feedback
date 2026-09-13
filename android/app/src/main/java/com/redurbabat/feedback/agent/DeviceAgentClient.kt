package com.redurbabat.feedback.agent

import com.redurbabat.feedback.device.SystemInfoProvider
import com.redurbabat.feedback.files.FileTransferCancelReason
import com.redurbabat.feedback.files.FilesOutcome
import com.redurbabat.feedback.files.FilesRequestHandler
import com.redurbabat.feedback.files.OutgoingFileMessage
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
    /** Null where the device has no shared areas wired up; files.read then answers UNSUPPORTED. */
    private val filesHandler: FilesRequestHandler? = null,
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
            MessageType.FILES_SHARES_REQUEST,
            MessageType.FILES_LIST_REQUEST,
            MessageType.FILES_METADATA_REQUEST,
            MessageType.FILES_DOWNLOAD_START,
            MessageType.FILES_DOWNLOAD_ACK,
            MessageType.FILES_DOWNLOAD_CANCEL,
            -> handleFilesRequest(socket, envelope)

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
        if (!serverGranted.contains(Capability.FILES_READ)) {
            // A withdrawn capability must stop an already running transfer, not just future ones.
            webSocket?.let { cancelFileTransfers(it, FileTransferCancelReason.CAPABILITY_REVOKED) }
        }
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
            relatesTo = envelope.messageId,
        )
    }

    /**
     * Answers one `files.*` request.
     *
     * The capability is re-evaluated here on every single request rather than once per session, so
     * withdrawing `files.read` locally or on the server stops an in-flight browse immediately and
     * cancels any running transfer.
     */
    private fun handleFilesRequest(socket: WebSocket, envelope: Envelope) {
        val sessionError = envelope.sessionRequirementError()
        if (sessionError != null) {
            sendError(socket, envelope.messageId, sessionError, "Remote Session fehlt")
            return
        }

        val handler = filesHandler
        if (handler == null) {
            sendError(
                socket,
                envelope.messageId,
                ProtocolError.UNSUPPORTED,
                "files.read ist auf diesem Geraet nicht verfuegbar",
            )
            return
        }

        val permission = EffectivePermission.evaluate(
            capability = Capability.FILES_READ,
            serverGranted = serverGranted,
            deviceGranted = localCapabilities.granted(),
            osAvailable = setOf(Capability.FILES_READ),
            sessionAuthorized = setOf(Capability.FILES_READ),
        )
        val denial = permission.denialReason()
        if (denial != null) {
            cancelFileTransfers(socket, FileTransferCancelReason.CAPABILITY_REVOKED)
            sendError(socket, envelope.messageId, denial, "files.read ist nicht freigegeben")
            return
        }

        val outcome = when (envelope.type) {
            MessageType.FILES_SHARES_REQUEST -> handler.handleSharesRequest()
            MessageType.FILES_LIST_REQUEST -> handler.handleListRequest(envelope.payload)
            MessageType.FILES_METADATA_REQUEST -> handler.handleMetadataRequest(envelope.payload)
            MessageType.FILES_DOWNLOAD_START -> handler.handleDownloadStart(envelope.payload)
            MessageType.FILES_DOWNLOAD_ACK -> handler.handleDownloadAck(envelope.payload)
            MessageType.FILES_DOWNLOAD_CANCEL -> handler.handleCancel(envelope.payload)
            else -> FilesOutcome.Failure(ProtocolError.UNSUPPORTED, "Unerwarteter files-Typ")
        }

        when (outcome) {
            is FilesOutcome.Reply ->
                sendFileMessage(
                    socket,
                    envelope.sessionId,
                    outcome.message,
                    relatesTo = envelope.messageId,
                )
            is FilesOutcome.Failure ->
                sendError(socket, envelope.messageId, outcome.error, outcome.message)

            FilesOutcome.Accepted -> Unit
        }

        drainFileTransfers(socket, envelope.sessionId)
    }

    /** Puts out every chunk the send window currently allows. */
    private fun drainFileTransfers(socket: WebSocket, sessionId: String?) {
        val handler = filesHandler ?: return
        for (message in handler.drainReadyMessages()) {
            if (!sendFileMessage(socket, sessionId, message)) {
                return
            }
        }
    }

    private fun cancelFileTransfers(socket: WebSocket, reason: FileTransferCancelReason) {
        val handler = filesHandler ?: return
        for (message in handler.cancelAll(reason)) {
            sendFileMessage(socket, null, message)
        }
    }

    /**
     * Chunk and cancel frames belong to a transfer and correlate through `transferId` in the
     * payload, so [relatesTo] is only set for the direct answers to a request.
     */
    private fun sendFileMessage(
        socket: WebSocket,
        sessionId: String?,
        message: OutgoingFileMessage,
        relatesTo: String? = null,
    ): Boolean = send(socket, message.type, sessionId, message.payload, relatesTo)

    private fun handleRevoked(socket: WebSocket) {
        cancelFileTransfers(socket, FileTransferCancelReason.DEVICE_REVOKED)
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

    /**
     * [relatesTo] carries the `messageId` of the request being answered (protocol section 7).
     * Every response must set it: the server correlates on it, and a session id cannot do that job
     * because one files session has several requests open at the same time.
     */
    private fun send(
        socket: WebSocket,
        type: MessageType,
        sessionId: String?,
        payload: JSONObject,
        relatesTo: String? = null,
    ): Boolean {
        val envelope = Envelope.create(
            type = type,
            messageId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestampEpochMillis = now(),
            payload = payload,
            relatesTo = relatesTo,
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
