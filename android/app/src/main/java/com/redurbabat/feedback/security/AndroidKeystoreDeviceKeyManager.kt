package com.redurbabat.feedback.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Device identity key pair inside the Android Keystore.
 *
 * secp256r1 / SHA256withECDSA, alias [DEFAULT_KEY_ALIAS]. The private key is generated inside the
 * keystore and is never exported, serialised or logged. Every failure surfaces as
 * [DeviceIdentityException] with its original cause instead of a generic IllegalStateException.
 */
class AndroidKeystoreDeviceKeyManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : DeviceKeyManager {

    override fun ensureKeyPair() {
        val keyStore = loadKeyStore()
        val exists = try {
            keyStore.containsAlias(keyAlias)
        } catch (error: GeneralSecurityException) {
            throw DeviceIdentityException("Device key alias could not be read", error)
        }
        if (!exists) {
            generateKeyPair()
        }
    }

    override fun publicKeySpki(): ByteArray {
        ensureKeyPair()
        val certificate = readCertificate()
        val encoded = certificate.publicKey?.encoded
        if (encoded == null || encoded.isEmpty()) {
            throw DeviceIdentityException("Device public key is not SPKI encodable")
        }
        return encoded
    }

    private fun readCertificate(): Certificate {
        val certificate: Certificate? = try {
            loadKeyStore().getCertificate(keyAlias)
        } catch (error: GeneralSecurityException) {
            throw DeviceIdentityException("Device certificate could not be read", error)
        }
        if (certificate == null) {
            throw DeviceIdentityException("Device certificate is missing")
        }
        return certificate
    }

    override fun sign(payload: ByteArray): ByteArray {
        ensureKeyPair()
        val privateKey = readPrivateKey()
        return try {
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initSign(privateKey)
            signature.update(payload)
            signature.sign()
        } catch (error: GeneralSecurityException) {
            throw DeviceIdentityException("Device signature failed", error)
        }
    }

    private fun readPrivateKey(): PrivateKey {
        val key: Key? = try {
            loadKeyStore().getKey(keyAlias, null)
        } catch (error: GeneralSecurityException) {
            throw DeviceIdentityException("Device private key could not be loaded", error)
        }
        if (key !is PrivateKey) {
            throw DeviceIdentityException("Device private key is missing")
        }
        return key
    }

    private fun loadKeyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (error: GeneralSecurityException) {
        throw DeviceIdentityException("Android Keystore is unavailable", error)
    } catch (error: IOException) {
        throw DeviceIdentityException("Android Keystore could not be opened", error)
    }

    private fun generateKeyPair() {
        try {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .build()

            generator.initialize(spec)
            generator.generateKeyPair()
        } catch (error: GeneralSecurityException) {
            throw DeviceIdentityException("Device key pair could not be created", error)
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "feedback.device.identity.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val EC_CURVE = "secp256r1"
    }
}
