package com.redurbabat.feedback.security

import org.json.JSONException
import org.json.JSONObject

/**
 * Owner-chosen behaviour of the local app lock.
 *
 * These are not secrets, but they are stored inside the Keystore-sealed [SecretStore] together with
 * the verifier: an attacker with write access to plain preferences could otherwise silently set
 * [autoLockTimeout] to [AutoLockTimeout.NEVER]. AES-GCM makes such an edit fail authentication
 * instead, and [decode] fails closed on anything it does not fully recognise.
 */
data class AppLockSettings(
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    val biometricUnlockEnabled: Boolean = false,
) {
    fun encode(): String = JSONObject()
        .put(KEY_VERSION, FORMAT_VERSION)
        .put(KEY_AUTO_LOCK, autoLockTimeout.storageKey)
        .put(KEY_BIOMETRIC, biometricUnlockEnabled)
        .toString()

    companion object {
        const val FORMAT_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_AUTO_LOCK = "autoLockTimeout"
        private const val KEY_BIOMETRIC = "biometricUnlockEnabled"

        val DEFAULT = AppLockSettings()

        /**
         * Strict parser. Returns null for anything malformed so the caller can fall back to the
         * safe [DEFAULT] rather than to a more permissive stored value.
         */
        fun decodeOrNull(value: String?): AppLockSettings? {
            if (value.isNullOrBlank()) {
                return null
            }
            return try {
                val json = JSONObject(value)
                if (json.getInt(KEY_VERSION) != FORMAT_VERSION) {
                    return null
                }
                val timeout = AutoLockTimeout.fromStorageKey(json.getString(KEY_AUTO_LOCK))
                    ?: return null
                AppLockSettings(
                    autoLockTimeout = timeout,
                    biometricUnlockEnabled = json.getBoolean(KEY_BIOMETRIC),
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}
