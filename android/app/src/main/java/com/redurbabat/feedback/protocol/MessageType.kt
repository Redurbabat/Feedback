package com.redurbabat.feedback.protocol

/**
 * WebSocket message types v1 (protocol/PROTOCOL.md section 7.1).
 *
 * [requiresSession] marks the privileged types: without a session id the device answers
 * [ProtocolError.SESSION_EXPIRED] and never executes the request.
 */
enum class MessageType(
    val wireName: String,
    val requiresSession: Boolean,
) {
    AGENT_HELLO("agent.hello", false),
    AGENT_HELLO_ACK("agent.hello.ack", false),
    AGENT_HEARTBEAT("agent.heartbeat", false),
    AGENT_HEARTBEAT_ACK("agent.heartbeat.ack", false),
    CAPABILITY_STATE("capability.state", false),
    CAPABILITY_UPDATE("capability.update", false),
    SYSTEM_INFO_REQUEST("system.info.request", true),
    SYSTEM_INFO_RESPONSE("system.info.response", true),
    DEVICE_REVOKED("device.revoked", false),
    ERROR("error", false);

    companion object {
        /** Deny by default: an unknown type resolves to null and is answered with UNSUPPORTED. */
        fun fromWire(value: String): MessageType? {
            for (candidate in values()) {
                if (candidate.wireName == value) {
                    return candidate
                }
            }
            return null
        }
    }
}
