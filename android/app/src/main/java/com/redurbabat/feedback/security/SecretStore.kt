package com.redurbabat.feedback.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Typed failure of the encrypted local store. Never carries the plaintext. */
class SecretStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Encrypted local storage for values that the later server registration needs, for example the
 * device token. Values are sealed with an AES-256-GCM key that lives in the Android Keystore and
 * are stored as [SecretEnvelope] strings in private SharedPreferences.
 *
 * Nothing in this class logs keys, plaintext or ciphertext.
 */
class SecretStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun put(key: String, value: String) {
        require(key.isNotBlank()) { "Secret key must not be blank" }
        val envelope = encrypt(value.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(key, envelope.encode()).apply()
    }

    /** Returns null when nothing is stored under [key]. */
    fun get(key: String): String? {
        require(key.isNotBlank()) { "Secret key must not be blank" }
        val stored = preferences.getString(key, null) ?: return null
        val envelope = try {
            SecretEnvelope.decode(stored)
        } catch (error: IllegalArgumentException) {
            throw SecretStoreException("Stored secret is malformed", error)
        }
        return String(decrypt(envelope), Charsets.UTF_8)
    }

    fun remove(key: String) {
        require(key.isNotBlank()) { "Secret key must not be blank" }
        preferences.edit().remove(key).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun encrypt(plaintext: ByteArray): SecretEnvelope = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV is supplied: the keystore key requires randomized encryption and generates one.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        SecretEnvelope(cipher.iv, cipher.doFinal(plaintext))
    } catch (error: GeneralSecurityException) {
        throw SecretStoreException("Secret could not be encrypted", error)
    }

    private fun decrypt(envelope: SecretEnvelope): ByteArray = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, envelope.iv),
        )
        cipher.doFinal(envelope.ciphertext)
    } catch (error: GeneralSecurityException) {
        throw SecretStoreException("Secret could not be decrypted", error)
    }

    private fun secretKey(): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (error: GeneralSecurityException) {
            throw SecretStoreException("Android Keystore is unavailable", error)
        } catch (error: IOException) {
            throw SecretStoreException("Android Keystore could not be opened", error)
        }

        val existing = try {
            keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        } catch (error: GeneralSecurityException) {
            throw SecretStoreException("Secret store key could not be loaded", error)
        }
        if (existing != null) {
            return existing.secretKey
        }

        return try {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            generator.generateKey()
        } catch (error: GeneralSecurityException) {
            throw SecretStoreException("Secret store key could not be created", error)
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "feedback.device.secretstore.v1"
        const val DEFAULT_PREFERENCES_NAME = "feedback.secrets.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}
