package com.redurbabat.feedback.security

import android.content.Context
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

sealed interface AppUnlockResult {
    data object Success : AppUnlockResult
    data object NotConfigured : AppUnlockResult
    data class Invalid(val failedAttempts: Int, val lockoutUntilEpochMillis: Long?) : AppUnlockResult
    data class Locked(val lockoutUntilEpochMillis: Long) : AppUnlockResult
}

class AppLockException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Local UI lock verifier.
 *
 * The derived verifier and salt are themselves sealed by [SecretStore]'s Android-Keystore key.
 * The plaintext PIN/passphrase is never persisted. Failed-attempt counters live in private app
 * storage and are persisted before an invalid result is returned.
 */
class AppLockStore(
    context: Context,
    private val secretStore: SecretStore,
    private val random: SecureRandom = SecureRandom(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        META_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun isConfigured(): Boolean = secretStore.get(VERIFIER_KEY) != null

    @Synchronized
    fun configure(secret: CharArray) {
        require(AppLockPolicy.isAcceptableSecret(secret)) {
            "PIN or passphrase does not meet the local lock policy"
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val algorithm = preferredAlgorithm()
        val hash = derive(secret, salt, algorithm, PBKDF2_ITERATIONS)
        val record = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("algorithm", algorithm)
            .put("iterations", PBKDF2_ITERATIONS)
            .put("salt", CryptoUtils.Base64.encode(salt))
            .put("hash", CryptoUtils.Base64.encode(hash))
        secretStore.put(VERIFIER_KEY, record.toString())
        resetFailures()
    }

    @Synchronized
    fun verify(secret: CharArray, nowEpochMillis: Long): AppUnlockResult {
        val lockoutUntil = preferences.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil > nowEpochMillis) {
            return AppUnlockResult.Locked(lockoutUntil)
        }

        val encoded = secretStore.get(VERIFIER_KEY) ?: return AppUnlockResult.NotConfigured
        val verifier = parseVerifier(encoded)
        val candidate = derive(
            secret = secret,
            salt = verifier.salt,
            algorithm = verifier.algorithm,
            iterations = verifier.iterations,
        )
        if (CryptoUtils.constantTimeEquals(candidate, verifier.hash)) {
            resetFailures()
            return AppUnlockResult.Success
        }

        val failedAttempts = preferences.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val delayMs = AppLockPolicy.lockoutDelayMillis(failedAttempts)
        val nextLockout = if (delayMs == 0L) {
            0L
        } else {
            nowEpochMillis + delayMs.coerceAtMost(Long.MAX_VALUE - nowEpochMillis)
        }
        val stored = preferences.edit()
            .putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
            .putLong(KEY_LOCKOUT_UNTIL, nextLockout)
            .commit()
        if (!stored) {
            throw AppLockException("Local lock attempt counter could not be persisted")
        }
        return AppUnlockResult.Invalid(
            failedAttempts = failedAttempts,
            lockoutUntilEpochMillis = nextLockout.takeIf { it > nowEpochMillis },
        )
    }

    private fun resetFailures() {
        val stored = preferences.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .commit()
        if (!stored) {
            throw AppLockException("Local lock state could not be persisted")
        }
    }

    private fun parseVerifier(value: String): Verifier = try {
        val json = JSONObject(value)
        require(json.getInt("version") == FORMAT_VERSION) { "Unsupported app lock format" }
        val algorithm = json.getString("algorithm")
        val iterations = json.getInt("iterations")
        require(iterations in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) {
            "Invalid app lock iteration count"
        }
        val salt = CryptoUtils.Base64.decode(json.getString("salt"))
        val hash = CryptoUtils.Base64.decode(json.getString("hash"))
        require(salt.size >= 16) { "Invalid app lock salt" }
        require(hash.size == HASH_BYTES) { "Invalid app lock hash" }
        Verifier(algorithm, iterations, salt, hash)
    } catch (error: Exception) {
        throw AppLockException("Stored app lock verifier is malformed", error)
    }

    private fun preferredAlgorithm(): String = try {
        SecretKeyFactory.getInstance(ALGORITHM_SHA256)
        ALGORITHM_SHA256
    } catch (_: GeneralSecurityException) {
        try {
            SecretKeyFactory.getInstance(ALGORITHM_SHA1)
            ALGORITHM_SHA1
        } catch (error: GeneralSecurityException) {
            throw AppLockException("No supported PBKDF2 provider is available", error)
        }
    }

    private fun derive(
        secret: CharArray,
        salt: ByteArray,
        algorithm: String,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } catch (error: GeneralSecurityException) {
            throw AppLockException("App lock verifier could not be derived", error)
        } finally {
            spec.clearPassword()
        }
    }

    private data class Verifier(
        val algorithm: String,
        val iterations: Int,
        val salt: ByteArray,
        val hash: ByteArray,
    )

    companion object {
        private const val META_PREFERENCES = "feedback.app.lock.meta.v1"
        private const val VERIFIER_KEY = "app.lock.verifier.v1"
        private const val KEY_FAILED_ATTEMPTS = "failedAttempts"
        private const val KEY_LOCKOUT_UNTIL = "lockoutUntil"
        private const val FORMAT_VERSION = 1
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val PBKDF2_ITERATIONS = 210_000
        private const val MIN_ACCEPTED_ITERATIONS = 100_000
        private const val MAX_ACCEPTED_ITERATIONS = 2_000_000
        private const val ALGORITHM_SHA256 = "PBKDF2WithHmacSHA256"
        private const val ALGORITHM_SHA1 = "PBKDF2WithHmacSHA1"
    }
}
