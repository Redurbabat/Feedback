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
import com.redurbabat.feedback.screen.OutgoingScreenMessage
import com.redurbabat.feedback.screen.ScreenCaptureSource
import com.redurbabat.feedback.screen.ScreenOutcome
import com.redurbabat.feedback.screen.ScreenRequestHandler
import com.redurbabat.feedback.screen.ScreenStopReason
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
/**
 * The capabilities that can govern a shared area (protocol section 8.3.1).
 *
 * Listed explicitly rather than derived from "everything implemented", so adding a new
 * implemented capability does not silently make it a way to read shares.
 */
private val SHARE_CAPABILITIES = listOf(
    Capability.FILES_READ,
    Capability.MEDIA_PHOTOS_READ,
    Capability.MEDIA_VIDEOS_READ,
)

class DeviceAgentClient(
    private val storedRegistration: StoredDeviceRegistration,
    private val metadata: DeviceMetadata,
    private val localCapabilities: LocalCapabilityStore,
    private val systemInfoProvider: SystemInfoProvider,
    private val registrationStore: DeviceRegistrationStore,
    /** Null where the device has no shared areas wired up; files.read then answers UNSUPPORTED. */
    private val filesHandler: FilesRequestHandler? = null,
    /** Null where screen capture is not wired up; screen.view then answers UNSUPPORTED. */
    private val screenCapture: ScreenCaptureSource? = null,
    /** The display size the encoder should mirror, as (width, height) in pixels. */
    private val displaySize: () -> Pair<Int, Int> = { Pair(0, 0) },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .build(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Built here rather than injected, because it needs a way to say "there is something to
     * send" and only this class knows the socket. Frames arrive from the encoder without anyone
     * asking for them, so waiting for the next incoming message would add a frame of delay.
     */
    private val screenHandler: ScreenRequestHandler? = screenCapture?.let { source ->
        ScreenRequestHandler(source) { pushScreenMessages() }
    }

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

    /**
     * Protocol section 8.5.9: the projection stops the moment the connection does.
     *
     * Not "kept warm for a reconnect". A capture with nobody receiving it is the device filming
     * itself while the owner believes the session is over (THREAT_MODEL 4.17).
     */
    private fun stopScreenCaptureOnConnectionLoss() {
        screenHandler?.stopLocally(ScreenStopReason.CONNECTION_LOST)
    }

    fun stop() {
        stopScreenCaptureOnConnectionLoss()
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
            stopScreenCaptureOnConnectionLoss()
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
            stopScreenCaptureOnConnectionLoss()
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

            MessageType.SCREEN_START,
            MessageType.SCREEN_FRAME_ACK,
            MessageType.SCREEN_KEYFRAME_REQUEST,
            MessageType.SCREEN_STOP,
            -> handleScreenRequest(socket, envelope)

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
        if (!serverGranted.contains(Capability.SCREEN_VIEW)) {
            // Same rule, and it matters more here: a withdrawn screen.view has to stop the
            // MediaProjection now, not when the ten minute session happens to run out.
            screenHandler?.stopLocally(ScreenStopReason.CAPABILITY_REVOKED)
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

        // Each capability is evaluated on its own: there is no hierarchy, so granting
        // media.photos.read must never open a files.read share, and the other way round.
        val deviceGranted = localCapabilities.granted()
        val allowed = SHARE_CAPABILITIES.filterTo(LinkedHashSet()) { capability ->
            EffectivePermission.evaluate(
                capability = capability,
                serverGranted = serverGranted,
                deviceGranted = deviceGranted,
                osAvailable = setOf(capability),
                sessionAuthorized = setOf(capability),
            ).denialReason() == null
        }

        if (allowed.isEmpty()) {
            cancelFileTransfers(socket, FileTransferCancelReason.CAPABILITY_REVOKED)
            sendError(
                socket,
                envelope.messageId,
                ProtocolError.CAPABILITY_DENIED,
                "Kein Lesezugriff ist freigegeben",
            )
            return
        }

        val outcome = when (envelope.type) {
            MessageType.FILES_SHARES_REQUEST -> handler.handleSharesRequest(allowed)
            MessageType.FILES_LIST_REQUEST -> handler.handleListRequest(envelope.payload, allowed)
            MessageType.FILES_METADATA_REQUEST ->
                handler.handleMetadataRequest(envelope.payload, allowed)
            MessageType.FILES_DOWNLOAD_START ->
                handler.handleDownloadStart(envelope.payload, allowed)
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

    /**
     * Answers one `screen.*` request.
     *
     * As with files, the capability is re-evaluated per request rather than per session. Unlike
     * files, a denial also stops the capture: a picture the owner just revoked must not keep
     * being taken while the refusal travels back.
     */
    private fun handleScreenRequest(socket: WebSocket, envelope: Envelope) {
        val sessionError = envelope.sessionRequirementError()
        if (sessionError != null) {
            sendError(socket, envelope.messageId, sessionError, "Remote Session fehlt")
            return
        }

        val handler = screenHandler
        if (handler == null) {
            sendError(
                socket,
                envelope.messageId,
                ProtocolError.UNSUPPORTED,
                "screen.view ist auf diesem Geraet nicht verfuegbar",
            )
            return
        }

        val denial = EffectivePermission.evaluate(
            capability = Capability.SCREEN_VIEW,
            serverGranted = serverGranted,
            deviceGranted = localCapabilities.granted(),
            osAvailable = setOf(Capability.SCREEN_VIEW),
            sessionAuthorized = setOf(Capability.SCREEN_VIEW),
        ).denialReason()
        if (denial != null) {
            handler.stopLocally(ScreenStopReason.CAPABILITY_REVOKED)
            sendError(socket, envelope.messageId, denial, "screen.view ist nicht freigegeben")
            return
        }

        val outcome = when (envelope.type) {
            MessageType.SCREEN_START -> {
                val (width, height) = displaySize()
                handler.handleStart(envelope.payload, envelope.sessionId, width, height)
            }
            MessageType.SCREEN_FRAME_ACK -> handler.handleAck(envelope.payload)
            MessageType.SCREEN_KEYFRAME_REQUEST -> handler.handleKeyframeRequest(envelope.payload)
            MessageType.SCREEN_STOP -> handler.handleStop(envelope.payload)
            else -> ScreenOutcome.Failure(ProtocolError.UNSUPPORTED, "Unerwarteter screen-Typ")
        }

        if (outcome is ScreenOutcome.Failure) {
            sendError(socket, envelope.messageId, outcome.error, outcome.message)
        }
        drainScreenMessages(socket, envelope.sessionId)
    }

    /**
     * Puts out everything the screen handler has queued.
     *
     * Called both from request handling and from the handler's own "there is something to send"
     * callback, because frames arrive without anybody asking for them.
     */
    private fun drainScreenMessages(socket: WebSocket, sessionId: String?) {
        val handler = screenHandler ?: return
        for (message in handler.drainReadyMessages()) {
            if (!sendScreenMessage(socket, sessionId, message)) {
                return
            }
        }
    }

    /**
     * The encoder has frames ready.
     *
     * Runs on the encoder thread. If the socket is gone the capture has to end, not queue: a
     * projection with nowhere to send is exactly what section 8.5.9 forbids.
     */
    private fun pushScreenMessages() {
        val handler = screenHandler ?: return
        val socket = webSocket
        if (socket == null) {
            handler.stopLocally(ScreenStopReason.CONNECTION_LOST)
            return
        }
        drainScreenMessages(socket, handler.activeSessionId)
    }

    /** Frames correlate through `streamId`, so `relatesTo` is not set on them. */
    private fun sendScreenMessage(
        socket: WebSocket,
        sessionId: String?,
        message: OutgoingScreenMessage,
    ): Boolean = send(socket, message.type, sessionId, message.payload)

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
        screenHandler?.stopLocally(ScreenStopReason.DEVICE_REVOKED)
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
