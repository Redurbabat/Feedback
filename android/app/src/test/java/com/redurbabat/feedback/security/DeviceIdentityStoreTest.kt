package com.redurbabat.feedback.security

import com.redurbabat.feedback.testutil.JvmDeviceKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityStoreTest {

    @Test
    fun loadOrCreateIsStableForTheSameKeyPair() {
        val store = DeviceIdentityStore(JvmDeviceKeyManager())
        val first = store.loadOrCreate()
        val second = store.loadOrCreate()
        assertEquals(first, second)
        assertTrue(first.deviceId.startsWith("fb-"))
        assertEquals(27, first.deviceId.length)
        assertEquals(16, first.fingerprint.split(":").size)
    }

    @Test
    fun differentKeyPairsProduceDifferentIdentities() {
        val first = DeviceIdentityStore(JvmDeviceKeyManager()).loadOrCreate()
        val second = DeviceIdentityStore(JvmDeviceKeyManager()).loadOrCreate()
        assertTrue(first.deviceId != second.deviceId)
        assertTrue(first.fingerprint != second.fingerprint)
        assertTrue(first.publicKeyBase64 != second.publicKeyBase64)
    }

    @Test
    fun signingDelegatesToTheKeyManager() {
        val keyManager = JvmDeviceKeyManager()
        val store = DeviceIdentityStore(keyManager)
        val payload = "feedback-pairing-v1".toByteArray(Charsets.UTF_8)
        val signature = store.sign(payload)
        assertTrue(signature.isNotEmpty())
        assertTrue(keyManager.verify(payload, signature))
    }

    @Test
    fun keyManagerFailuresSurfaceAsTypedErrorsWithTheirCause() {
        val cause = IllegalStateException("keystore unavailable")
        val store = DeviceIdentityStore(FailingKeyManager(cause))

        var identityError: DeviceIdentityException? = null
        try {
            store.loadOrCreate()
        } catch (error: DeviceIdentityException) {
            identityError = error
        }
        assertNotNull(identityError)
        assertSame(cause, identityError?.cause)

        var signError: DeviceIdentityException? = null
        try {
            store.sign(ByteArray(1))
        } catch (error: DeviceIdentityException) {
            signError = error
        }
        assertNotNull(signError)
        assertSame(cause, signError?.cause)
    }

    private class FailingKeyManager(private val cause: Throwable) : DeviceKeyManager {
        override fun ensureKeyPair() = Unit

        override fun publicKeySpki(): ByteArray =
            throw DeviceIdentityException("Device certificate is missing", cause)

        override fun sign(payload: ByteArray): ByteArray =
            throw DeviceIdentityException("Device signature failed", cause)
    }
}
