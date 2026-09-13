package com.redurbabat.feedback.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

data class DeviceIdentity(
    val deviceId: String,
    val fingerprint: String,
    val publicKeyBase64: String,
)

class DeviceIdentityStore {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun loadOrCreate(): DeviceIdentity {
        val store = keyStore
        if (!store.containsAlias(KEY_ALIAS)) {
            generateKeyPair()
        }

        val certificate = store.getCertificate(KEY_ALIAS)
            ?: error("Feedback device certificate is missing")
        val publicKeyBytes = certificate.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val hex = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        return DeviceIdentity(
            deviceId = "fb-${hex.take(24)}",
            fingerprint = hex.chunked(4).joinToString(":"),
            publicKeyBase64 = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP),
        )
    }

    fun sign(payload: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("Feedback private key is missing")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()

        generator.initialize(spec)
        generator.generateKeyPair()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "feedback.device.identity.v1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
