package com.redurbabat.feedback.security

import com.redurbabat.feedback.network.ServerEndpoint
import com.redurbabat.feedback.pairing.DeviceRegistration
import org.json.JSONObject

/** Persisted server registration. The complete record is sealed by [SecretStore]. */
data class StoredDeviceRegistration(
    val endpoint: ServerEndpoint,
    val registration: DeviceRegistration,
)

class DeviceRegistrationStore(
    private val secretStore: SecretStore,
) {
    fun save(endpoint: ServerEndpoint, registration: DeviceRegistration) {
        val capabilities = org.json.JSONArray()
        registration.grantedCapabilities.forEach(capabilities::put)
        val json = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("serverBaseUrl", endpoint.baseUrl)
            .put("deviceToken", registration.deviceToken)
            .put("serverDeviceId", registration.serverDeviceId)
            .put("publicDeviceId", registration.publicDeviceId)
            .put("name", registration.name)
            .put("pairedAt", registration.pairedAt)
            .put("grantedCapabilities", capabilities)
            .put("serverPublicKey", registration.serverPublicKey)
        secretStore.put(STORE_KEY, json.toString())
    }

    fun load(): StoredDeviceRegistration? {
        val value = secretStore.get(STORE_KEY) ?: return null
        return try {
            val json = JSONObject(value)
            require(json.getInt("version") == FORMAT_VERSION) { "Unsupported registration format" }
            val array = json.getJSONArray("grantedCapabilities")
            val capabilities = buildList {
                for (index in 0 until array.length()) {
                    add(array.getString(index))
                }
            }
            StoredDeviceRegistration(
                endpoint = ServerEndpoint.parse(json.getString("serverBaseUrl")),
                registration = DeviceRegistration(
                    deviceToken = json.getString("deviceToken"),
                    serverDeviceId = json.getString("serverDeviceId"),
                    publicDeviceId = json.getString("publicDeviceId"),
                    name = json.getString("name"),
                    pairedAt = json.getString("pairedAt"),
                    grantedCapabilities = capabilities,
                    serverPublicKey = json.getString("serverPublicKey"),
                ),
            )
        } catch (error: Exception) {
            throw SecretStoreException("Stored device registration is malformed", error)
        }
    }

    fun clear() {
        secretStore.remove(STORE_KEY)
    }

    companion object {
        /**
         * Bumped to 2 when the server's public key became part of a registration. A version 1
         * record is refused rather than read without it: a registration that cannot verify the
         * server would silently be the very thing this field exists to prevent. No release has
         * ever shipped version 1, so refusing costs nothing and guessing would cost the guarantee.
         */
        private const val FORMAT_VERSION = 2
        private const val STORE_KEY = "device.registration.v1"
    }
}
