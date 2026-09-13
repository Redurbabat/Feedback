package com.redurbabat.feedback.testutil

import com.redurbabat.feedback.security.DeviceKeyManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Test double that uses the plain JVM security provider instead of the Android Keystore.
 * Same curve and same signature algorithm, so signatures produced here verify exactly like the
 * ones produced on a device.
 */
class JvmDeviceKeyManager : DeviceKeyManager {

    private val keyPair: KeyPair = createKeyPair()

    val publicKey: PublicKey
        get() = keyPair.public

    override fun ensureKeyPair() = Unit

    override fun publicKeySpki(): ByteArray = keyPair.public.encoded

    override fun sign(payload: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(payload)
        return signature.sign()
    }

    fun verify(payload: ByteArray, signatureBytes: ByteArray): Boolean {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initVerify(keyPair.public)
        signature.update(payload)
        return signature.verify(signatureBytes)
    }

    private fun createKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    companion object {
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
