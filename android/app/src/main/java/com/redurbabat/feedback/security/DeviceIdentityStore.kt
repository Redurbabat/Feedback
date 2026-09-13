package com.redurbabat.feedback.security

/**
 * Stable entry point for device identity. Keeps the public API of the previous implementation
 * (`loadOrCreate()` and `sign()`) while the actual key handling moved behind [DeviceKeyManager],
 * which makes the surrounding logic testable without an Android Keystore.
 */
class DeviceIdentityStore(
    private val keyManager: DeviceKeyManager = AndroidKeystoreDeviceKeyManager(),
) {

    /** Loads the device identity, creating the key pair on first use. */
    fun loadOrCreate(): DeviceIdentity = DeviceIdentity.fromSpki(keyManager.publicKeySpki())

    /** Signs a canonical payload with the device identity key. */
    fun sign(payload: ByteArray): ByteArray = keyManager.sign(payload)
}
