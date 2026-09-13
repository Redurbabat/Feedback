package com.redurbabat.feedback.protocol

/**
 * Capability list v1 (protocol/PROTOCOL.md section 8.1). There is no implicit hierarchy:
 * `screen.control` does not imply `files.read`, `files.read` does not imply `media.photos.read`.
 *
 * `system.info`, `files.read`, `media.photos.read`, `media.videos.read` and `screen.view` are
 * implemented in v1. Everything else is declared, denied by default and answers with
 * [ProtocolError.UNSUPPORTED] until a real feature exists behind it.
 */
enum class Capability(
    val wireName: String,
    val implemented: Boolean,
) {
    SYSTEM_INFO("system.info", true),
    FILES_READ("files.read", true),
    MEDIA_PHOTOS_READ("media.photos.read", true),
    MEDIA_VIDEOS_READ("media.videos.read", true),
    SCREEN_VIEW("screen.view", true),
    SCREEN_CONTROL("screen.control", false),
    CLIPBOARD_READ("clipboard.read", false),
    CLIPBOARD_WRITE("clipboard.write", false);

    companion object {
        /** Deny by default: unknown wire names resolve to null, never to a fallback capability. */
        fun fromWire(value: String): Capability? {
            for (candidate in values()) {
                if (candidate.wireName == value) {
                    return candidate
                }
            }
            return null
        }

        /** Maps a wire list, silently dropping unknown entries so old servers cannot widen access. */
        fun fromWireList(values: Collection<String>): Set<Capability> {
            val result = LinkedHashSet<Capability>()
            for (value in values) {
                val capability = fromWire(value)
                if (capability != null) {
                    result.add(capability)
                }
            }
            return result
        }

        fun toWireList(capabilities: Collection<Capability>): List<String> {
            val result = ArrayList<String>(capabilities.size)
            for (capability in capabilities) {
                result.add(capability.wireName)
            }
            return result
        }
    }
}

/**
 * effective(cap) = serverGranted AND deviceGranted AND osPermissionAvailable AND sessionAuthorized.
 *
 * All four factors are re-evaluated on the device. The server can never force a capability that
 * is not granted locally.
 */
data class EffectivePermission(
    val capability: Capability,
    val serverGranted: Boolean,
    val deviceGranted: Boolean,
    val osAvailable: Boolean,
    val sessionAuthorized: Boolean,
) {
    val effective: Boolean
        get() = capability.implemented &&
            serverGranted &&
            deviceGranted &&
            osAvailable &&
            sessionAuthorized

    /** Null when the capability may be used; otherwise the error the device has to answer with. */
    fun denialReason(): ProtocolError? = when {
        !capability.implemented -> ProtocolError.UNSUPPORTED
        !serverGranted -> ProtocolError.CAPABILITY_DENIED
        !deviceGranted -> ProtocolError.CAPABILITY_DENIED
        !osAvailable -> ProtocolError.PERMISSION_REQUIRED
        !sessionAuthorized -> ProtocolError.SESSION_EXPIRED
        else -> null
    }

    companion object {
        fun evaluate(
            capability: Capability,
            serverGranted: Set<Capability>,
            deviceGranted: Set<Capability>,
            osAvailable: Set<Capability>,
            sessionAuthorized: Set<Capability>,
        ): EffectivePermission = EffectivePermission(
            capability = capability,
            serverGranted = serverGranted.contains(capability),
            deviceGranted = deviceGranted.contains(capability),
            osAvailable = osAvailable.contains(capability),
            sessionAuthorized = sessionAuthorized.contains(capability),
        )
    }
}
