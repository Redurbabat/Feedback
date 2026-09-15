package com.redurbabat.feedback.agent

enum class AgentConnectionState {
    STOPPED,
    CONNECTING,
    ONLINE,
    OFFLINE,

    /**
     * The address answered, but not with the key this device paired with.
     *
     * Terminal on purpose, like [REVOKED] and unlike [OFFLINE]: retrying cannot help, because
     * nothing is broken - the address now leads somewhere else. Reconnecting in a loop would mean
     * knocking on a stranger's door every few minutes, and the device token is never sent again
     * until the owner decides what happened (THREAT_MODEL 4.20 and 4.21).
     */
    UNTRUSTED_SERVER,
    REVOKED,
    ERROR,
}
